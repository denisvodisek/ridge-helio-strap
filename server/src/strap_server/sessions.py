"""Workout sessions: started in the app, logged afterwards, confirmed from a suggestion, or
recorded by the strap (docs/denis/SPEC.md S3, S4; roadmap #9).

Only a session's window and sport are stored. Its load, zones, strain and HR recovery are
computed when it is read, from the per-minute HR already in `sample`, with the day's own
definitions (`derive/cardio_load.py`'s accumulator, `read/summary.py`'s strain scale), so a
session can never score differently from the day it sits in.

A strap workout (id `strap:<start ms>`) is never written here: deleting one hides it, and
editing one hides it and makes it a session with source 'strap' (migration 0003).
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Literal
from uuid import UUID
from zoneinfo import ZoneInfo

from psycopg import Connection, Cursor
from pydantic import BaseModel, Field, model_validator

from strap_server.derive import freshness
from strap_server.derive._common import _age, _day_bounds_utc, _load_profile
from strap_server.derive.cardio_load import _measured_rhr, _trimp_and_zones
from strap_server.derive.hr_validity import HR_VALID_BOUNDS, HR_VALID_SQL
from strap_server.read import summary

Sport = Literal["tennis", "treadmill", "stairs", "run", "walk", "ride", "gym", "swim", "yoga", "other"]
MAX_SESSION = timedelta(hours=12)

# SPEC S4 (ours): at least 40 % of HR reserve (ACSM moderate), for 20 min, bridging dips of 3.
SUGGEST_RESERVE_FRACTION = 0.40
SUGGEST_MIN_MINUTES = 20
SUGGEST_MAX_DIP_MINUTES = 3
# Zepp/strap workout type codes -> Ridge sports (SPEC S3), confirmed by the owner against his
# own sessions in the Zepp export (2026-10-03). Unknown codes stay "other", their code kept.
ZEPP_SPORTS = {17: "tennis", 52: "gym", 54: "stairs", 8: "treadmill", 6: "walk"}
STRAP_ID = "strap:"
# A strap workout the owner hasn't deleted or edited (those are hidden; an edit lives on as a session).
VISIBLE_WORKOUT_SQL = "NOT EXISTS (SELECT 1 FROM workout_hidden h WHERE h.user_id = workout.user_id AND h.start_ts = workout.start_ts)"
NO_HR = "no_hr_in_window"
NO_RHR = "no_measured_rhr"
MESSAGES = {
    NO_HR: "The strap recorded no heart rate during this session.",
    NO_RHR: "Load needs a resting heart rate measured in the last 30 days: wear the strap overnight.",
    freshness.PROFILE_INCOMPLETE: "Load and zones need your height, sex and date of birth, plus a logged weight.",
}


class SessionIn(BaseModel):
    sport: Sport
    start: datetime
    end: datetime
    source: Literal["ridge", "suggested"] = "ridge"
    notes: str | None = Field(default=None, max_length=200)

    @model_validator(mode="after")
    def plausible(self) -> SessionIn:
        if self.start.tzinfo is None or self.end.tzinfo is None:
            raise ValueError("start and end need a UTC offset")
        if not self.start < self.end <= self.start + MAX_SESSION:
            raise ValueError("a session runs forwards and lasts at most 12 hours")
        if self.start > datetime.now(UTC) + timedelta(minutes=5):
            raise ValueError("a session can't start in the future")
        return self


class SessionPatch(BaseModel):
    sport: Sport | None = None
    start: datetime | None = None
    end: datetime | None = None
    notes: str | None = Field(default=None, max_length=200)


def _withheld(reason: str) -> dict:
    return {"withheld": {"reason": reason, "message": MESSAGES[reason]}}


def _minutes(cur: Cursor, user_id: UUID, start: datetime, end: datetime) -> list[tuple[datetime, float]]:
    """Per-minute valid HR means in [start, end), oldest first."""
    cur.execute(
        "SELECT date_trunc('minute', ts) m, avg(value) FROM sample "
        f"WHERE user_id = %s AND metric = 'hr' AND {HR_VALID_SQL} AND ts >= %s AND ts < %s GROUP BY m ORDER BY m",
        (user_id, *HR_VALID_BOUNDS, start, end),
    )
    return [(m, float(v)) for m, v in cur.fetchall()]


def _sleep_windows(cur: Cursor, user_id: UUID, start: datetime, end: datetime) -> list[tuple[datetime, datetime]]:
    cur.execute("SELECT start_ts, end_ts FROM sleep_session WHERE user_id = %s AND end_ts >= %s AND start_ts < %s", (user_id, start, end))
    return cur.fetchall()


def stats(cur: Cursor, user_id: UUID, tz: str, start: datetime, end: datetime) -> dict:
    """Everything SPEC S3 says about one window: HR, load, zones, strain and HR recovery."""
    minutes = _minutes(cur, user_id, start, end)
    if not minutes:
        return {"hr": _withheld(NO_HR)}
    hrs = [v for _, v in minutes]
    out: dict = {"hr": {"avg": round(sum(hrs) / len(hrs)), "peak": round(max(hrs)), "minutes": len(hrs)}}
    out["hrr"] = _hr_recovery(cur, user_id, end)
    day = start.astimezone(ZoneInfo(tz)).date()
    prof = _load_profile(cur, user_id, tz, day)
    if prof is None:
        out["load"] = _withheld(freshness.PROFILE_INCOMPLETE)
        return out
    rhr = _measured_rhr(cur, user_id, day)
    if rhr is None:
        out["load"] = _withheld(NO_RHR)
        return out
    hrmax = 208 - 0.7 * _age(prof["dob"], day)  # Tanaka 2001, as spec/02 §2.4
    trimp, zones, _ = _trimp_and_zones(minutes, _sleep_windows(cur, user_id, start, end), rhr, hrmax, prof["sex"])
    p95, scored_days = summary.strain_scale(cur, user_id, day)
    strain = summary.strain_from_load(trimp, p95) if scored_days >= summary.STRAIN_SCALE_MIN_DAYS else None
    out["load"] = {"trimp": round(trimp, 1), "zone_min": zones, "hrmax": round(hrmax), "rhr": round(rhr),
                   "strain": strain, "strain_learning": None if strain is not None else
                   {"have": scored_days, "need": summary.STRAIN_SCALE_MIN_DAYS}}
    return out


def _hr_recovery(cur: Cursor, user_id: UUID, end: datetime) -> dict | None:
    """HRR1/HRR2 (SPEC S3): last minute before `end` minus the 1st and 2nd minutes after.

    None when a minute is missing or another session starts within 2 min (the drop would be
    the next effort's, not recovery)."""
    last = (end - timedelta(microseconds=1)).replace(second=0, microsecond=0)
    around = dict(_minutes(cur, user_id, last, last + timedelta(minutes=3)))
    at, one, two = (around.get(last + timedelta(minutes=k)) for k in (0, 1, 2))
    if at is None or one is None:
        return None
    cur.execute(
        "SELECT 1 FROM session WHERE user_id = %(u)s AND start_ts > %(e)s AND start_ts <= %(e2)s "
        f"UNION ALL SELECT 1 FROM workout WHERE user_id = %(u)s AND start_ts > %(e)s AND start_ts <= %(e2)s AND {VISIBLE_WORKOUT_SQL} LIMIT 1",
        {"u": user_id, "e": end, "e2": end + timedelta(minutes=2)},
    )
    if cur.fetchone():
        return None
    return {"hrr1": round(at - one), "hrr2": None if two is None else round(at - two)}


def _row(r: tuple) -> dict:
    sid, sport, start, end, source, notes = r
    return {"id": str(sid), "sport": sport, "start": int(start.timestamp() * 1000), "end": int(end.timestamp() * 1000),
            "source": source, "notes": notes}


def list_range(cur: Cursor, user_id: UUID, tz: str, first: datetime, last: datetime) -> list[dict]:
    """Sessions and strap workouts starting in [first, last), newest first, each with its stats."""
    cur.execute(
        "SELECT id, sport, start_ts, end_ts, source, notes FROM session WHERE user_id = %s AND start_ts >= %s AND start_ts < %s",
        (user_id, first, last),
    )
    items = [_row(r) for r in cur.fetchall()]
    cur.execute(
        "SELECT start_ts, duration_s, sport FROM workout WHERE user_id = %s AND start_ts >= %s AND start_ts < %s AND duration_s > 0 "
        f"AND {VISIBLE_WORKOUT_SQL}",
        (user_id, first, last),
    )
    for start, dur, code in cur.fetchall():
        items.append({"id": f"{STRAP_ID}{int(start.timestamp() * 1000)}", "sport": ZEPP_SPORTS.get(code, "other"), "strap_sport_code": code,
                      "start": int(start.timestamp() * 1000), "end": int((start + timedelta(seconds=dur)).timestamp() * 1000),
                      "source": "strap", "notes": None})
    for it in items:
        it["stats"] = stats(cur, user_id, tz, datetime.fromtimestamp(it["start"] / 1000, UTC), datetime.fromtimestamp(it["end"] / 1000, UTC))
    return sorted(items, key=lambda it: it["start"], reverse=True)


def create(conn: Connection, user_id: UUID, tz: str, s: SessionIn, source: str | None = None) -> dict:
    r = conn.execute(
        "INSERT INTO session (user_id, sport, start_ts, end_ts, source, notes) VALUES (%s, %s, %s, %s, %s, %s) "
        "RETURNING id, sport, start_ts, end_ts, source, notes",
        (user_id, s.sport, s.start, s.end, source or s.source, s.notes),
    ).fetchone()
    out = _row(r)
    with conn.cursor() as cur:
        out["stats"] = stats(cur, user_id, tz, s.start, s.end)
    return out


def _strap_start(session_id: str) -> datetime | None:
    """The start a `strap:<ms>` id names; None for a session's own id."""
    ms = session_id.removeprefix(STRAP_ID)
    return datetime(1970, 1, 1, tzinfo=UTC) + timedelta(milliseconds=int(ms)) if ms != session_id and ms.isdigit() else None


def _hide(conn: Connection, user_id: UUID, start: datetime) -> tuple | None:
    """Hides the strap workout starting at `start`: its (start, duration_s, sport code), or None
    when there is no such workout or it is hidden already."""
    return conn.execute(
        "WITH w AS (SELECT start_ts, duration_s, sport FROM workout "
        "           WHERE user_id = %(u)s AND date_trunc('milliseconds', start_ts) = %(s)s AND duration_s > 0), "
        "h AS (INSERT INTO workout_hidden (user_id, start_ts) SELECT %(u)s, start_ts FROM w ON CONFLICT DO NOTHING RETURNING start_ts) "
        "SELECT w.* FROM w JOIN h USING (start_ts)",
        {"u": user_id, "s": start},
    ).fetchone()


def update(conn: Connection, user_id: UUID, tz: str, session_id: str, patch: SessionPatch) -> dict | None:
    if (strap := _strap_start(session_id)) is not None:
        # Editing what the strap recorded makes it a session the owner owns; the original stays
        # in `workout`, hidden, so a re-sync can't put it back. Atomic: a refused edit rolls back the hide.
        w = _hide(conn, user_id, strap)
        if w is None:
            return None
        start, dur, code = w
        made = SessionIn(sport=patch.sport or ZEPP_SPORTS.get(code, "other"), start=patch.start or start,
                         end=patch.end or start + timedelta(seconds=dur), notes=patch.notes)
        return create(conn, user_id, tz, made, source="strap")
    r = conn.execute("SELECT sport, start_ts, end_ts, notes FROM session WHERE user_id = %s AND id::text = %s", (user_id, session_id)).fetchone()
    if r is None:
        return None
    merged = SessionIn(sport=patch.sport or r[0], start=patch.start or r[1], end=patch.end or r[2],
                       notes=patch.notes if patch.notes is not None else r[3])
    row = conn.execute(
        "UPDATE session SET sport = %s, start_ts = %s, end_ts = %s, notes = %s WHERE user_id = %s AND id::text = %s "
        "RETURNING id, sport, start_ts, end_ts, source, notes",
        (merged.sport, merged.start, merged.end, merged.notes, user_id, session_id),
    ).fetchone()
    out = _row(row)
    with conn.cursor() as cur:
        out["stats"] = stats(cur, user_id, tz, merged.start, merged.end)
    return out


def delete(conn: Connection, user_id: UUID, session_id: str) -> bool:
    if (strap := _strap_start(session_id)) is not None:
        return _hide(conn, user_id, strap) is not None
    return conn.execute("DELETE FROM session WHERE user_id = %s AND id::text = %s", (user_id, session_id)).rowcount > 0


def dismiss(conn: Connection, user_id: UUID, start: datetime) -> None:
    conn.execute(
        "INSERT INTO session_dismissal (user_id, start_ts) VALUES (%s, %s) ON CONFLICT DO NOTHING",
        (user_id, start.replace(second=0, microsecond=0)),
    )


def suggestions(cur: Cursor, user_id: UUID, tz: str, day) -> list[dict]:
    """SPEC S4: sustained moderate-or-harder HR on `day` that no session, workout or sleep covers."""
    prof = _load_profile(cur, user_id, tz, day)
    rhr = _measured_rhr(cur, user_id, day) if prof else None
    if prof is None or rhr is None:
        return []  # no reserve to measure against: offer nothing rather than guess
    hrmax = 208 - 0.7 * _age(prof["dob"], day)
    line = rhr + SUGGEST_RESERVE_FRACTION * (hrmax - rhr)
    start, end = _day_bounds_utc(day, tz)
    minutes = _minutes(cur, user_id, start, end)
    busy = _sleep_windows(cur, user_id, start, end)
    cur.execute("SELECT start_ts, end_ts FROM session WHERE user_id = %s AND end_ts > %s AND start_ts < %s", (user_id, start, end))
    busy += cur.fetchall()
    # Hidden strap workouts too: deleting one says it wasn't a workout, so it isn't offered back.
    cur.execute(
        "SELECT start_ts, start_ts + make_interval(secs => duration_s) FROM workout WHERE user_id = %s AND start_ts < %s "
        "AND start_ts + make_interval(secs => duration_s) > %s",
        (user_id, end, start),
    )
    busy += cur.fetchall()
    cur.execute("SELECT start_ts FROM session_dismissal WHERE user_id = %s AND start_ts >= %s AND start_ts < %s", (user_id, start, end))
    dismissed = {r[0] for r in cur.fetchall()}

    out = []
    for first, last in _runs([(m, v) for m, v in minutes if v >= line]):
        if (last - first) + timedelta(minutes=1) < timedelta(minutes=SUGGEST_MIN_MINUTES):
            continue
        stop = last + timedelta(minutes=1)
        if first in dismissed or any(s < stop and e > first for s, e in busy):
            continue
        inside = [v for m, v in minutes if first <= m < stop]
        out.append({"start": int(first.timestamp() * 1000), "end": int(stop.timestamp() * 1000),
                    "minutes": int((stop - first).total_seconds() // 60), "avg_hr": round(sum(inside) / len(inside)),
                    "peak_hr": round(max(inside))})
    return out


def _runs(above: list[tuple[datetime, float]]) -> list[tuple[datetime, datetime]]:
    """Minutes above the line joined into runs, bridging gaps of ≤ SUGGEST_MAX_DIP_MINUTES."""
    runs: list[tuple[datetime, datetime]] = []
    for m, _ in above:
        if runs and m - runs[-1][1] <= timedelta(minutes=SUGGEST_MAX_DIP_MINUTES + 1):
            runs[-1] = (runs[-1][0], m)
        else:
            runs.append((m, m))
    return runs
