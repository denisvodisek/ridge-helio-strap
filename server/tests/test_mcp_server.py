"""The read-only MCP server: its tools return what the app sees, and nothing can write."""

from __future__ import annotations

import psycopg
import pytest

from strap_server import config, mcp_server
from strap_server.derive import derive_day, derive_night
from tests.derive import _seed

pytestmark = pytest.mark.db
TZ = "Asia/Kolkata"


@pytest.fixture
def seeded(db, test_dsn, monkeypatch):
    with psycopg.connect(test_dsn) as conn, conn.cursor() as cur:
        _seed.seed(cur)
        for start, end in _seed.nights():
            derive_night(cur, _seed.OWNER, TZ, start, end)
        for day in _seed.DAYS:
            derive_day(cur, _seed.OWNER, TZ, day)
    parts = dict(p.split("=", 1) for p in test_dsn.split())
    for key in ("host", "port", "dbname", "user", "password"):
        monkeypatch.setenv({"dbname": "POSTGRES_DB"}.get(key, f"POSTGRES_{key.upper()}"), parts[key])
    monkeypatch.setenv("OWNER_ID", str(_seed.OWNER))
    config.get_settings.cache_clear()
    yield test_dsn
    config.get_settings.cache_clear()


def test_tools_return_what_the_app_reads(seeded) -> None:
    day = _seed.DAYS[-1].isoformat()
    listed = {m["metric"]: m for m in mcp_server.metrics()}
    assert listed["recovery_score"]["last"] == day
    summary = mcp_server.day_summary(day)
    assert summary["date"] == day and "value" in summary["recovery"]
    trend = mcp_server.daily(["steps_total"], _seed.DAYS[0].isoformat(), day)
    assert len(trend["metrics"]["steps_total"]) == len(_seed.DAYS)
    assert mcp_server.owner_profile()["height_cm"] == 175.0


def test_query_reads(seeded) -> None:
    out = mcp_server.run_query("SELECT metric, count(*) FROM derived_daily GROUP BY metric ORDER BY metric", seeded)
    assert out["columns"] == ["metric", "count"] and out["rows"] and not out["truncated"]


@pytest.mark.parametrize("sql", [
    "DELETE FROM profile",
    "SELECT 1; DELETE FROM profile",  # two statements: a prepared statement refuses
    "WITH gone AS (DELETE FROM profile RETURNING *) SELECT * FROM gone",  # read-only transaction refuses
    "select set_config('default_transaction_read_only', 'off', false); delete from profile",
])
def test_query_cannot_write(seeded, sql) -> None:
    with pytest.raises((ValueError, psycopg.Error)):
        mcp_server.run_query(sql, seeded)
    with psycopg.connect(seeded) as conn:
        assert conn.execute("SELECT count(*) FROM profile").fetchone()[0] == 1
