"""Import a Zepp account export (the unzipped folder of CSVs) as history (roadmap #13).

    docker compose cp ~/Downloads/<export folder> api:/tmp/zepp       # from deploy/
    docker compose exec api python -m strap_server.import_zepp /tmp/zepp [--dry-run]

What comes across, and how (docs/denis/SPEC.md S5):
- HEARTRATE_AUTO: per-minute HR (local time, owner's timezone)        -> sample "hr"
- ACTIVITY_MINUTE: per-minute steps                                    -> sample "steps"
- SLEEP + SLEEP_MINUTE: nights (UTC-stamped) with minute stages and the
  sleeping respiratory rate where present                              -> sleep_session, sample "respiratory_rate"
- ACTIVITY: daily steps, distance, calories                            -> device_daily_total
- SPORT: workouts with Zepp's numeric type                             -> workout (type kept raw)
- BODY: weigh-ins                                                      -> weight_log
Not in a Zepp export at all: HRV, SpO2, skin temperature, stress. The profile (USER) is
printed, not written: it has only a birth month, and the profile is the owner's to set.

Rows go through the same upserts the phone's sync uses; one re-derive runs at the end.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections.abc import Iterator
from datetime import UTC, date, datetime, time, timedelta
from pathlib import Path
from uuid import UUID
from zoneinfo import ZoneInfo

from strap_server.config import get_settings
from strap_server.db import connection
from strap_server.ingest import upsert
from strap_server.ingest.models import DailyTotalIn, SampleIn, SleepIn, WorkoutIn
from strap_server.rederive import rederive

STAGES = {"LIGHT": 4, "DEEP": 5, "WAKE": 7, "REM": 8}  # the strap's own codes (spec/01 §9)
BATCH = 20_000


def _rows(folder: Path, name: str) -> Iterator[dict]:
    for path in sorted((folder / name).glob("*.csv")):
        with path.open(encoding="utf-8-sig", newline="") as f:
            yield from csv.DictReader(f)


def _ms(t: datetime) -> int:
    return int(t.timestamp() * 1000)


def _utc(text: str) -> datetime:
    return datetime.strptime(text, "%Y-%m-%d %H:%M:%S%z").astimezone(UTC)


def _local(day: str, hhmm: str, zone: ZoneInfo) -> datetime:
    return datetime.combine(date.fromisoformat(day), time.fromisoformat(hhmm), zone)


def _num(text: str | None) -> float | None:
    """A Zepp number, or None for blank and its placeholders (0 and -1 mean 'not measured')."""
    try:
        v = float(text) if text not in (None, "") else None
    except ValueError:
        return None
    return v if v is not None and v > 0 else None


def heart_rate(folder: Path, zone: ZoneInfo) -> Iterator[SampleIn]:
    for r in _rows(folder, "HEARTRATE_AUTO"):
        hr = _num(r.get("heartRate"))
        if hr is not None:
            yield SampleIn(metric="hr", ts=_ms(_local(r["date"], r["time"], zone)), value=hr)


def steps(folder: Path, zone: ZoneInfo) -> Iterator[SampleIn]:
    for r in _rows(folder, "ACTIVITY_MINUTE"):
        n = _num(r.get("steps"))
        if n is not None:
            yield SampleIn(metric="steps", ts=_ms(_local(r["date"], r["time"], zone)), value=n)


def nights(folder: Path, zone: ZoneInfo) -> tuple[list[SleepIn], list[SampleIn], int]:
    """Nights from SLEEP, each with the SLEEP_MINUTE stages that fall inside it.

    SLEEP_MINUTE's `date` is one day later than the night it describes (rows dated D sit
    inside the night SLEEP files under D-1; checked night by night on a real export), and its
    times are local. So each minute is placed at date-1 (or date-2 for evening times, in case
    a night starts before midnight) and kept only if it lands inside a UTC-stamped night: a
    minute that matches no night is dropped, never guessed into one.
    """
    windows = []
    for r in _rows(folder, "SLEEP"):
        if not r.get("start") or not r.get("stop"):
            continue
        start, end = _utc(r["start"]), _utc(r["stop"])
        if end - start < timedelta(minutes=30):
            continue  # Zepp writes placeholder nights (start == stop); they are not sleep
        windows.append((start, end, r))
    candidates: list[tuple[datetime, str, float | None]] = []
    for r in _rows(folder, "SLEEP_MINUTE"):
        d = date.fromisoformat(r["date"])
        t = time.fromisoformat(r["time"])
        for back in (1, 2) if t.hour >= 18 else (1,):
            candidates.append((datetime.combine(d - timedelta(days=back), t, zone).astimezone(UTC), r["stage"], _num(r.get("respiratory_rate"))))
    candidates.sort(key=lambda c: c[0])

    sessions, breathing, matched = [], [], 0
    for start, end, r in windows:
        inside = [(t, s, rr) for t, s, rr in candidates if start - timedelta(minutes=5) <= t < end + timedelta(minutes=5) and s in STAGES]
        stages: list[tuple[int, int, int]] = []
        for t, s, _ in inside:
            code, t0, t1 = STAGES[s], _ms(t), _ms(t + timedelta(minutes=1))
            if stages and stages[-1][2] == code and stages[-1][1] == t0:
                stages[-1] = (stages[-1][0], t1, code)
            else:
                stages.append((t0, t1, code))
        matched += bool(stages)
        breathing += [SampleIn(metric="respiratory_rate", ts=_ms(t), value=rr) for t, _, rr in inside if rr is not None]
        sessions.append(SleepIn(
            start_ts=_ms(start), end_ts=_ms(end), kind="main", stages=stages[:2_000],
            deep_min=int(_num(r.get("deepSleepTime")) or 0), light_min=int(_num(r.get("shallowSleepTime")) or 0),
            rem_min=int(_num(r.get("REMTime")) or 0), wake_min=int(_num(r.get("wakeTime")) or 0),
        ))
    return sessions, breathing, matched


def daily_totals(folder: Path, zone: ZoneInfo) -> list[DailyTotalIn]:
    out = []
    for r in _rows(folder, "ACTIVITY"):
        # Read at the end of its day: a whole day's count, not a mid-day reading.
        read_at = datetime.combine(date.fromisoformat(r["date"]), time(23, 59, 59), zone)
        out.append(DailyTotalIn(read_at=_ms(read_at), steps=int(_num(r.get("steps")) or 0) or None,
                                distance_m=_num(r.get("distance")), calories=_num(r.get("calories"))))
    return out


def workouts(folder: Path) -> list[WorkoutIn]:
    out = []
    for r in _rows(folder, "SPORT"):
        out.append(WorkoutIn(start_ts=_ms(_utc(r["startTime"])), sport=int(r["type"]), duration_s=int(_num(r.get("sportTime(s)")) or 0),
                             calories=int(_num(r.get("calories(kcal)")) or 0) or None, distance_m=_num(r.get("distance(m)"))))
    return out


def weights(folder: Path) -> list[tuple[datetime, float]]:
    return [(_utc(r["time"]), w) for r in _rows(folder, "BODY") if (w := _num(r.get("weight"))) is not None]


def run(folder: Path, dry_run: bool = False, conninfo: str | None = None, log=print) -> dict:
    user_id = UUID(get_settings().owner_id)
    with connection(conninfo) as conn:
        conn.execute("INSERT INTO app_user (id, timezone) VALUES (%s, %s) ON CONFLICT (id) DO NOTHING", (user_id, get_settings().owner_timezone))
        tz = conn.execute("SELECT timezone FROM app_user WHERE id = %s", (user_id,)).fetchone()[0]
    zone = ZoneInfo(tz)
    hr, st = list(heart_rate(folder, zone)), list(steps(folder, zone))
    sleep, breathing, matched = nights(folder, zone)
    totals, sport, body = daily_totals(folder, zone), workouts(folder), weights(folder)
    counts = {"hr_minutes": len(hr), "step_minutes": len(st), "nights": len(sleep), "nights_with_stages": matched,
              "breathing_minutes": len(breathing), "daily_totals": len(totals), "workouts": len(sport), "weigh_ins": len(body), "timezone": tz}
    log("found: " + ", ".join(f"{k} {v}" for k, v in counts.items()))
    for r in _rows(folder, "USER"):
        log(f"profile in the export (not written; set it in the app): sex code {r.get('gender')}, height {r.get('height')} cm, born {r.get('birthday')}")
    if dry_run:
        return counts
    samples = hr + st + breathing
    with connection(conninfo) as conn, conn.cursor() as cur:
        for i in range(0, len(samples), BATCH):
            upsert.upsert_samples(cur, user_id, samples[i:i + BATCH])
        upsert.upsert_sleep(cur, user_id, sleep)
        upsert.upsert_workouts(cur, user_id, sport)
        upsert.upsert_daily_totals(cur, user_id, tz, totals)
        for ts, kg in body:
            cur.execute("INSERT INTO weight_log (user_id, ts, kg) VALUES (%s, %s, %s) ON CONFLICT (user_id, ts) DO NOTHING", (user_id, ts, kg))
    log("stored; re-deriving every day (a few minutes for months of data)…")
    counts["days_derived"] = rederive(conninfo, user_id, log=log)
    return counts


def main() -> int:
    p = argparse.ArgumentParser(description="Import a Zepp export folder into Ridge.")
    p.add_argument("folder", type=Path)
    p.add_argument("--dry-run", action="store_true", help="count what would be imported, write nothing")
    a = p.parse_args()
    if not (a.folder / "HEARTRATE_AUTO").is_dir():
        print(f"{a.folder} doesn't look like an unzipped Zepp export (no HEARTRATE_AUTO folder).", file=sys.stderr)
        return 2
    run(a.folder, a.dry_run)
    return 0


if __name__ == "__main__":
    sys.exit(main())
