"""Workout sessions (SPEC S3) and suggestions (SPEC S4): computed from the day's own HR, by the day's own rules."""

from __future__ import annotations

import hashlib
import uuid
from datetime import UTC, datetime, timedelta

import psycopg
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from strap_server import api, config, sessions
from strap_server.derive import derive_day, derive_night
from tests.derive import _seed

pytestmark = pytest.mark.db
TZ = "Asia/Kolkata"
DAY = _seed.DAYS[-1]
HOUR = (_seed._local(DAY, 8, 0), _seed._local(DAY, 9, 0))  # the seed's 60 min at 125 bpm


@pytest.fixture
def seeded(db, test_dsn):
    with psycopg.connect(test_dsn) as conn, conn.cursor() as cur:
        _seed.seed(cur)
        cur.execute("DELETE FROM session")
        cur.execute("DELETE FROM session_dismissal")
        for start, end in _seed.nights():
            derive_night(cur, _seed.OWNER, TZ, start, end)
        for day in _seed.DAYS:
            derive_day(cur, _seed.OWNER, TZ, day)
    return test_dsn


def test_a_session_scores_exactly_what_its_day_scores(seeded) -> None:
    """The hour is the day's only waking HR, so its TRIMP must be the day's cardio_load."""
    with psycopg.connect(seeded) as conn:
        made = sessions.create(conn, _seed.OWNER, TZ, sessions.SessionIn(sport="tennis", start=HOUR[0], end=HOUR[1]))
        day_load = conn.execute("SELECT value FROM derived_daily WHERE metric = 'cardio_load' AND day = %s", (DAY,)).fetchone()[0]
    load = made["stats"]["load"]
    assert load["trimp"] == pytest.approx(day_load, abs=0.05)
    assert sum(load["zone_min"]) == 60 and made["stats"]["hr"] == {"avg": 125, "peak": 125, "minutes": 60}
    assert 0 < load["strain"] <= 21  # 8 seeded days of load: past the S2 learning period
    assert made["stats"]["hrr"] is None  # no HR after 09:00: recovery withheld, never guessed


def test_hr_recovery_is_the_drop_after_stopping(seeded) -> None:
    after = [(HOUR[1] + timedelta(minutes=k, seconds=30), "hr", hr) for k, hr in ((0, 104.0), (1, 92.0))]
    with psycopg.connect(seeded) as conn:
        conn.cursor().executemany("INSERT INTO sample (user_id, ts, metric, value) VALUES (%s, %s, %s, %s)",
                                  [(_seed.OWNER, *a) for a in after])
        out = sessions.stats(conn.cursor(), _seed.OWNER, TZ, *HOUR)
    assert out["hrr"] == {"hrr1": 125 - 104, "hrr2": 125 - 92}


def test_list_merges_strap_workouts_and_edits_round_trip(seeded) -> None:
    first, last = _seed._local(_seed.DAYS[0], 0, 0), _seed._local(DAY, 23, 59)
    with psycopg.connect(seeded) as conn:
        made = sessions.create(conn, _seed.OWNER, TZ, sessions.SessionIn(sport="gym", start=HOUR[0], end=HOUR[1]))
        moved = sessions.update(conn, _seed.OWNER, TZ, made["id"], sessions.SessionPatch(sport="treadmill", end=HOUR[1] - timedelta(minutes=30)))
        listed = sessions.list_range(conn.cursor(), _seed.OWNER, TZ, first, last)
        assert sessions.delete(conn, _seed.OWNER, made["id"]) and not sessions.delete(conn, _seed.OWNER, made["id"])
    assert moved["sport"] == "treadmill" and moved["stats"]["hr"]["minutes"] == 30
    assert {it["source"] for it in listed} == {"ridge", "strap"}


def test_sustained_moderate_hr_is_offered_until_confirmed_or_dismissed(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        offered = sessions.suggestions(conn.cursor(), _seed.OWNER, TZ, DAY)
        assert [(s["start"], s["minutes"]) for s in offered] == [(int(HOUR[0].timestamp() * 1000), 60)]
        sessions.dismiss(conn, _seed.OWNER, HOUR[0])
        assert sessions.suggestions(conn.cursor(), _seed.OWNER, TZ, DAY) == []
        other = _seed.DAYS[-2]
        sessions.create(conn, _seed.OWNER, TZ, sessions.SessionIn(sport="run", start=_seed._local(other, 8, 10),
                                                                  end=_seed._local(other, 8, 40), source="suggested"))
        assert sessions.suggestions(conn.cursor(), _seed.OWNER, TZ, other) == []  # covered by a session now


def test_short_or_dipping_efforts(seeded) -> None:
    """A 3-minute dip is bridged (a changeover); under 20 minutes is not a workout."""
    day = _seed.DAYS[-1]
    with psycopg.connect(seeded) as conn:
        cur = conn.cursor()
        cur.execute("DELETE FROM sample WHERE metric = 'hr' AND ts >= %s AND ts < %s", HOUR)
        rows = [(_seed._local(day, 8, 0) + timedelta(minutes=m), 130.0) for m in range(12)]  # 12 min hard
        rows += [(_seed._local(day, 8, 15) + timedelta(minutes=m), 130.0) for m in range(12)]  # 3-min dip, 12 more
        rows += [(_seed._local(day, 15, 0) + timedelta(minutes=m), 130.0) for m in range(15)]  # 15 min alone: too short
        cur.executemany("INSERT INTO sample (user_id, ts, metric, value) VALUES (%s, %s, 'hr', %s)", [(_seed.OWNER, t, v) for t, v in rows])
        offered = sessions.suggestions(cur, _seed.OWNER, TZ, day)
    assert [s["minutes"] for s in offered] == [27]


def test_implausible_sessions_are_refused() -> None:
    now = datetime.now(UTC)
    for start, end in ((now, now - timedelta(minutes=1)), (now - timedelta(hours=13), now), (now + timedelta(hours=1), now + timedelta(hours=2))):
        with pytest.raises(ValidationError):
            sessions.SessionIn(sport="run", start=start, end=end)
    with pytest.raises(ValidationError):
        sessions.SessionIn(sport="quidditch", start=now - timedelta(hours=1), end=now)


def test_sessions_over_http(db, test_dsn, monkeypatch) -> None:
    owner, token = uuid.uuid4(), "sessions-test-token"
    monkeypatch.setenv("DEVICE_TOKEN_SHA256", hashlib.sha256(token.encode()).hexdigest())
    monkeypatch.setenv("OWNER_ID", str(owner))
    parts = dict(p.split("=", 1) for p in test_dsn.split())
    for key in ("host", "port", "dbname", "user", "password"):
        monkeypatch.setenv({"dbname": "POSTGRES_DB"}.get(key, f"POSTGRES_{key.upper()}"), parts[key])
    config.get_settings.cache_clear()
    try:
        client, auth = TestClient(api.app), {"Authorization": f"Bearer {token}"}
        end = datetime.now(UTC).replace(microsecond=0)
        r = client.post("/v1/sessions", json={"sport": "tennis", "start": (end - timedelta(hours=1)).isoformat(), "end": end.isoformat()}, headers=auth)
        assert r.status_code == 200, r.text
        assert r.json()["stats"]["hr"]["withheld"]["reason"] == "no_hr_in_window"
        day = end.date().isoformat()
        assert len(client.get(f"/v1/sessions?from={day}&to={day}", headers=auth).json()) == 1
        assert client.get(f"/v1/sessions/suggestions?day={day}", headers=auth).json() == []
        assert client.delete(f"/v1/sessions/{r.json()['id']}", headers=auth).status_code == 200
        with psycopg.connect(test_dsn) as conn:
            conn.execute("DELETE FROM app_user WHERE id = %s", (owner,))
    finally:
        config.get_settings.cache_clear()
