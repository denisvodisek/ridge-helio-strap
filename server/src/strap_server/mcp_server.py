"""Talk to your data: a read-only MCP server over the Ridge database (roadmap #6).

    docker compose exec -T api python -m strap_server.mcp_server      # stdio, from deploy/

Claude Desktop or Claude Code runs that command and gets typed tools over the same read code
the app uses (`read/summary.py`, `read/history.py`, `read/series.py`, `profile.py`), so a
number Claude quotes is the number the app shows. Nothing here writes: every connection is
read-only, and the one free-form tool runs a single prepared statement inside a READ ONLY
transaction with a timeout. The database keeps no open port; the command runs where the
server runs (over `ssh` for a remote box).
"""

from __future__ import annotations

import json
from datetime import date, datetime
from decimal import Decimal
from typing import Any
from uuid import UUID

import psycopg
from mcp.server.mcpserver import MCPServer

from strap_server import journal, profile, sessions
from strap_server.config import get_settings
from strap_server.derive._common import _day_bounds_utc
from strap_server.read import history, series, summary

QUERY_ROW_LIMIT = 500
QUERY_TIMEOUT_MS = 10_000

INSTRUCTIONS = """\
Ridge is one person's self-hosted wearable data (Amazfit Helio Strap): per-minute samples and
nightly/daily metrics derived by audited formulas. It is n=1 data: describe it, compare it with
the person's own baseline, and never present a population norm as theirs.

Rules: quote numbers only from tool results, never from memory. A card or metric that is
"withheld" has a named reason (missing profile, learning period, no data); report the reason,
never invent a value. Prefer the typed tools; use `query` only when they can't answer.

Start with `metrics` to see what exists and over which dates, `day_summary` for one day as the
app shows it, and `daily` for trends. Days are local dates in the owner's timezone.
"""

mcp = MCPServer("ridge", instructions=INSTRUCTIONS)


def _owner() -> UUID:
    return UUID(get_settings().owner_id)


def _connect() -> psycopg.Connection:
    """A read-only session: writes fail even if a statement tries one."""
    return psycopg.connect(
        get_settings().conninfo(),
        options=f"-c default_transaction_read_only=on -c statement_timeout={QUERY_TIMEOUT_MS}",
    )


def _tz(conn: psycopg.Connection) -> str:
    row = conn.execute("SELECT timezone FROM app_user WHERE id = %s", (_owner(),)).fetchone()
    return row[0] if row else get_settings().owner_timezone


def _local_bounds(tz: str, first: date, last: date) -> tuple[datetime, datetime]:
    """[first local midnight, the midnight after last) in UTC, as the API's range reads use."""
    return _day_bounds_utc(first, tz)[0], _day_bounds_utc(last, tz)[1]


def _plain(value: Any) -> Any:
    """JSON-safe values for tool results (dates as ISO strings, decimals as floats)."""
    return json.loads(json.dumps(value, default=lambda v: v.isoformat() if isinstance(v, date | datetime) else float(v)
                                 if isinstance(v, Decimal) else str(v)))


@mcp.tool()
def metrics() -> list[dict]:
    """Every derived daily metric with how many days it has and its first and last day."""
    with _connect() as conn:
        rows = conn.execute(
            "SELECT metric, count(*), min(day), max(day) FROM derived_daily WHERE user_id = %s GROUP BY metric ORDER BY metric",
            (_owner(),),
        ).fetchall()
    return [{"metric": m, "days": n, "first": f.isoformat(), "last": la.isoformat()} for m, n, f, la in rows]


@mcp.tool()
def day_summary(day: str) -> dict:
    """One local day exactly as the app's Today screen gets it: recovery, strain, sleep, heart,
    stress, steps, VO2max and the illness flag, each a value with context or a withheld reason.
    `day` is YYYY-MM-DD."""
    with _connect() as conn, conn.cursor() as cur:
        return _plain(summary.day_summary(cur, _owner(), _tz(conn), date.fromisoformat(day)))


@mcp.tool()
def daily(metrics: list[str], start: str, end: str) -> dict:
    """Derived daily values (with their flags) for the given metrics over [start, end], oldest
    first; missing days are gaps, never zeros. Up to 400 days. Dates are YYYY-MM-DD."""
    with _connect() as conn, conn.cursor() as cur:
        return _plain(history.daily(cur, _owner(), metrics, date.fromisoformat(start), date.fromisoformat(end)))


@mcp.tool()
def samples(name: str, start: str, end: str, bucket: str = "1h") -> dict:
    """Raw strap samples bucketed (15m, 1h or 1d) with min, max, mean, sum, count and the time
    of each bucket's max. Names: hr, stress, steps, hrv, spo2, skin_temp, respiratory_rate."""
    if name not in series.SERIES:
        raise ValueError(f"unknown series; known: {sorted(series.SERIES)}")
    with _connect() as conn, conn.cursor() as cur:
        return _plain(series.bucket_series(cur, _owner(), _tz(conn), name, date.fromisoformat(start), date.fromisoformat(end), bucket))


@mcp.tool()
def workouts(start: str, end: str) -> list[dict]:
    """Workouts the strap recorded starting in [start, end] (local dates), newest first."""
    with _connect() as conn, conn.cursor() as cur:
        first, last = _local_bounds(_tz(conn), date.fromisoformat(start), date.fromisoformat(end))
        return _plain(history.workouts(cur, _owner(), first, last))


@mcp.tool()
def workout_sessions(start: str, end: str) -> list[dict]:
    """Workout sessions (started or logged in Ridge, confirmed suggestions, and the strap's own
    workouts) starting in [start, end], newest first, each with avg/peak HR, TRIMP load, zone
    minutes, strain (0-21) and HR recovery, or the reason one is withheld."""
    with _connect() as conn, conn.cursor() as cur:
        tz = _tz(conn)
        first, last = _local_bounds(tz, date.fromisoformat(start), date.fromisoformat(end))
        return _plain(sessions.list_range(cur, _owner(), tz, first, last))


@mcp.tool()
def journal_entries(start: str, end: str) -> list[dict]:
    """Caffeine (mg), alcohol (standard drinks) and weight (kg) logged in [start, end], newest first."""
    with _connect() as conn:
        first, last = _local_bounds(_tz(conn), date.fromisoformat(start), date.fromisoformat(end))
        return _plain(journal.entries(conn, _owner(), first, last))


@mcp.tool()
def owner_profile() -> dict:
    """Height, sex, date of birth, self-reported activity level (Jurca SR-PA 0-4) and the latest weight."""
    with _connect() as conn:
        return _plain(profile.get(conn, _owner()))


@mcp.tool()
def query(sql: str) -> dict:
    """One read-only SQL statement (SELECT or WITH) for questions the other tools can't answer.
    Tables: sample (ts, metric, value), derived_daily (day, metric, value, flags), sleep_session,
    workout, manual_entry, weight_log, profile, illness_flag; filter on user_id is unnecessary
    (one owner). At most 500 rows come back; 10 s timeout."""
    return run_query(sql)


def run_query(sql: str, conninfo: str | None = None) -> dict:
    """The guard behind `query`: one prepared statement (a prepared statement cannot hold two),
    in a READ ONLY transaction on a read-only session, with a timeout and a row cap."""
    text = sql.strip().rstrip(";")
    if not text.lower().startswith(("select", "with")):
        raise ValueError("only a single SELECT or WITH statement is allowed")
    opts = f"-c default_transaction_read_only=on -c statement_timeout={QUERY_TIMEOUT_MS}"
    with psycopg.connect(conninfo or get_settings().conninfo(), options=opts) as conn:
        conn.execute("SET TRANSACTION READ ONLY")
        with conn.cursor() as cur:
            cur.execute(text, prepare=True)
            columns = [d.name for d in cur.description or []]
            rows = cur.fetchmany(QUERY_ROW_LIMIT + 1)
        conn.rollback()
    return _plain({"columns": columns, "rows": [list(r) for r in rows[:QUERY_ROW_LIMIT]], "truncated": len(rows) > QUERY_ROW_LIMIT})


if __name__ == "__main__":
    mcp.run()
