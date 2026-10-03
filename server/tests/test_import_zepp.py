"""Zepp export import (SPEC S5) on a tiny synthetic export: dates, the minute-row shift, and the derive."""

from __future__ import annotations

import uuid
from pathlib import Path

import psycopg
import pytest

from strap_server import config, import_zepp

pytestmark = pytest.mark.db


def _csv(folder: Path, name: str, header: str, rows: list[str]) -> None:
    (folder / name).mkdir()
    (folder / name / f"{name}_1.csv").write_text("﻿" + header + "\n" + "\n".join(rows) + "\n", encoding="utf-8")


@pytest.fixture
def export(tmp_path: Path) -> Path:
    # One night in Hong Kong: 2026-03-02 00:00-07:00 local = 03-01 16:00 to 23:00 UTC.
    # Its minute rows are dated 03-03 (Zepp's one-day shift); a placeholder night is skipped.
    _csv(tmp_path, "SLEEP", "date,deepSleepTime,shallowSleepTime,wakeTime,start,stop,REMTime,naps",
         ["2026-03-02,90,250,10,2026-03-01 16:00:00+0000,2026-03-01 23:00:00+0000,70,",
          "2026-03-03,0,0,0,2026-03-02 16:00:00+0000,2026-03-02 16:00:00+0000,0,"])
    minutes = [f"2026-03-03,{h:02d}:{m:02d},{'DEEP' if h < 2 else 'LIGHT'},55,{14.5 if m == 0 else ''}" for h in range(7) for m in range(60)]
    _csv(tmp_path, "SLEEP_MINUTE", "date,time,stage,hr,respiratory_rate", minutes)
    hr = [f"2026-03-02,{h:02d}:{m:02d},{55 if h < 7 else 80}" for h in range(24) for m in range(60)]
    _csv(tmp_path, "HEARTRATE_AUTO", "date,time,heartRate", hr)
    _csv(tmp_path, "ACTIVITY_MINUTE", "date,time,steps", ["2026-03-02,09:00,100", "2026-03-02,09:01,0"])
    _csv(tmp_path, "ACTIVITY", "date,steps,distance,runDistance,calories", ["2026-03-02,8000,6000,0,300"])
    _csv(tmp_path, "SPORT", "type,startTime,sportTime(s),maxPace(/meter),minPace(/meter),distance(m),avgPace(/meter),calories(kcal)",
         ["17,2026-03-02 10:00:00+0000,5400,-1.0,-1.0,-1.0,-1.0,640.0"])
    _csv(tmp_path, "BODY", "time,weight,height,bmi,fatRate,bodyWaterRate,boneMass,metabolism,muscleRate,visceralFat",
         ["2026-03-01 01:00:00+0000,80.0,180.0,0,0,0,0,0,0,0"])
    _csv(tmp_path, "USER", "userId,gender,height,weight,nickName,avatar,birthday", ["1,1,180.0,80,x,,1990-01"])
    return tmp_path


@pytest.fixture
def owner(db, test_dsn, monkeypatch):
    oid = uuid.uuid4()
    parts = dict(p.split("=", 1) for p in test_dsn.split())
    for key in ("host", "port", "dbname", "user", "password"):
        monkeypatch.setenv({"dbname": "POSTGRES_DB"}.get(key, f"POSTGRES_{key.upper()}"), parts[key])
    monkeypatch.setenv("OWNER_ID", str(oid))
    monkeypatch.setenv("OWNER_TIMEZONE", "Asia/Hong_Kong")
    config.get_settings.cache_clear()
    yield oid
    with psycopg.connect(test_dsn) as conn:
        conn.execute("DELETE FROM app_user WHERE id = %s", (oid,))
    config.get_settings.cache_clear()


def test_dry_run_counts_and_writes_nothing(export, owner, test_dsn) -> None:
    counts = import_zepp.run(export, dry_run=True, log=lambda _: None)
    assert counts["nights"] == 1 and counts["nights_with_stages"] == 1  # the placeholder night is not sleep
    assert counts["hr_minutes"] == 1440 and counts["step_minutes"] == 1 and counts["breathing_minutes"] == 7
    with psycopg.connect(test_dsn) as conn:
        assert conn.execute("SELECT count(*) FROM sample WHERE user_id = %s", (owner,)).fetchone()[0] == 0


def test_import_lands_where_the_strap_would_have_put_it(export, owner, test_dsn) -> None:
    import_zepp.run(export, log=lambda _: None)
    with psycopg.connect(test_dsn) as conn:
        start, end, deep, stages = conn.execute(
            "SELECT start_ts, end_ts, deep_min, stages FROM sleep_session WHERE user_id = %s", (owner,)).fetchone()
        # The minute rows dated 03-03 were placed in the night of 03-01/02 UTC, first stage deep.
        assert (start.isoformat(), end.isoformat()) == ("2026-03-01T16:00:00+00:00", "2026-03-01T23:00:00+00:00")
        assert deep == 90 and stages[0][2] == 5 and stages[0][0] == int(start.timestamp() * 1000)
        assert conn.execute("SELECT sport, duration_s FROM workout WHERE user_id = %s", (owner,)).fetchone() == (17, 5400)
        assert conn.execute("SELECT steps FROM device_daily_total WHERE user_id = %s", (owner,)).fetchone()[0] == 8000
        rhr = conn.execute("SELECT value FROM derived_daily WHERE user_id = %s AND metric = 'rhr_daily'", (owner,)).fetchone()
        assert rhr is not None and rhr[0] == pytest.approx(55, abs=1)  # derived from the imported night's HR
