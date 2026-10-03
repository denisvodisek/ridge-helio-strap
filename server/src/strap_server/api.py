"""HTTP API. Single owner; the phone authenticates with one bearer token.

    uv run uvicorn strap_server.api:app --host 0.0.0.0 --port 8766
"""

from __future__ import annotations

import hashlib
import hmac
import json
from datetime import date, datetime
from typing import Annotated
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, Query, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from strap_server import chat, journal, profile, sessions
from strap_server.config import Settings, get_settings
from strap_server.db import connection
from strap_server.ingest.models import IngestPayload, IngestSummary
from strap_server.ingest.service import ingest
from strap_server.read import history, series, summary

app = FastAPI(title="strap", docs_url=None, redoc_url=None)


def owner(
    settings: Annotated[Settings, Depends(get_settings)],
    authorization: Annotated[str | None, Header()] = None,
) -> UUID:
    """The owner's id when the bearer token matches; 401 otherwise, never saying why."""
    expected = settings.device_token_sha256.lower()
    token = (authorization or "").removeprefix("Bearer ").strip()
    presented = hashlib.sha256(token.encode()).hexdigest()
    if not expected or not token or not hmac.compare_digest(presented, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "unauthorized")
    return UUID(settings.owner_id)


@app.get("/healthz")
def healthz() -> dict:
    with connection() as conn:
        conn.execute("SELECT 1")
    return {"ok": True}


def _ensure_owner(conn, user_id: UUID, settings: Settings) -> None:
    """The owner's row, created by the first write of any kind (a fresh server has none)."""
    conn.execute(
        "INSERT INTO app_user (id, timezone) VALUES (%s, %s) ON CONFLICT (id) DO NOTHING",
        (user_id, settings.owner_timezone),
    )


def _owner_tz(conn, user_id: UUID, settings: Settings) -> str:
    row = conn.execute("SELECT timezone FROM app_user WHERE id = %s", (user_id,)).fetchone()
    return row[0] if row else settings.owner_timezone


@app.get("/v1/day/{day}/series")
def get_day_series(
    day: date,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
    metrics: str = "hr,stress,steps",
) -> dict:
    """Raw per-minute points for one local day (D12) and the sleep windows to shade."""
    names = [m for m in metrics.split(",") if m]
    unknown = [m for m in names if m not in series.SERIES]
    if unknown or not names:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, f"unknown series: {unknown}; known: {sorted(series.SERIES)}")
    with connection() as conn, conn.cursor() as cur:
        return series.day_series(cur, user_id, _owner_tz(conn, user_id, settings), day, names)


@app.get("/v1/day/{day}/summary")
def get_day_summary(
    day: date,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    """The day's cards, each a value with context or a named withheld reason."""
    with connection() as conn, conn.cursor() as cur:
        return summary.day_summary(cur, user_id, _owner_tz(conn, user_id, settings), day)


@app.get("/v1/series/{name}")
def get_bucket_series(
    name: str,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
    bucket: str = "1h",
) -> dict:
    """Buckets that keep the peaks: min, max, mean, sum, count and the time of the max."""
    if name not in series.SERIES:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"unknown series; known: {sorted(series.SERIES)}")
    with connection() as conn, conn.cursor() as cur:
        try:
            return series.bucket_series(cur, user_id, _owner_tz(conn, user_id, settings), name, start, end, bucket)
        except ValueError as e:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(e)) from e


def _local_bounds(tz: str, first: date, last: date) -> tuple:
    from strap_server.derive._common import _day_bounds_utc

    return _day_bounds_utc(first, tz)[0], _day_bounds_utc(last, tz)[1]


@app.get("/v1/daily")
def get_daily(
    user_id: Annotated[UUID, Depends(owner)],
    metrics: str,
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
) -> dict:
    """Derived daily rows per metric (value + flags); absent days are gaps."""
    with connection() as conn, conn.cursor() as cur:
        try:
            return history.daily(cur, user_id, [m for m in metrics.split(",") if m], start, end)
        except ValueError as e:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(e)) from e


@app.get("/v1/workouts")
def get_workouts(
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
) -> list[dict]:
    with connection() as conn, conn.cursor() as cur:
        return history.workouts(cur, user_id, *_local_bounds(_owner_tz(conn, user_id, settings), start, end))


@app.get("/v1/journal")
def get_journal(
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
) -> list[dict]:
    with connection() as conn:
        return journal.entries(conn, user_id, *_local_bounds(_owner_tz(conn, user_id, settings), start, end))


@app.post("/v1/journal")
def post_journal(
    entry: journal.JournalIn,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    with connection() as conn:
        _ensure_owner(conn, user_id, settings)
        return journal.add(conn, None, user_id, entry)


@app.delete("/v1/journal/{entry_id}")
def delete_journal(entry_id: str, user_id: Annotated[UUID, Depends(owner)]) -> dict:
    with connection() as conn:
        if not journal.delete(conn, user_id, entry_id):
            raise HTTPException(status.HTTP_404_NOT_FOUND, "no such entry")
    return {"deleted": entry_id}


@app.get("/v1/profile")
def get_profile(user_id: Annotated[UUID, Depends(owner)]) -> dict:
    with connection() as conn:
        return profile.get(conn, user_id)


@app.put("/v1/profile")
def put_profile(
    body: profile.ProfileIn,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    """Replaces the profile; a change re-derives every day before answering."""
    with connection() as conn:
        _ensure_owner(conn, user_id, settings)
        return profile.put(conn, None, user_id, body)


@app.get("/v1/sessions")
def get_sessions(
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
) -> list[dict]:
    """Workout sessions (yours and the strap's) starting in the local days [from, to], with stats."""
    with connection() as conn, conn.cursor() as cur:
        tz = _owner_tz(conn, user_id, settings)
        return sessions.list_range(cur, user_id, tz, *_local_bounds(tz, start, end))


@app.post("/v1/sessions")
def post_session(
    body: sessions.SessionIn,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    with connection() as conn:
        _ensure_owner(conn, user_id, settings)
        return sessions.create(conn, user_id, _owner_tz(conn, user_id, settings), body)


# PUT too: Android's HttpURLConnection (the app's client) cannot send PATCH.
@app.api_route("/v1/sessions/{session_id}", methods=["PATCH", "PUT"])
def patch_session(
    session_id: str,
    body: sessions.SessionPatch,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    with connection() as conn:
        out = sessions.update(conn, user_id, _owner_tz(conn, user_id, settings), session_id, body)
    if out is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "no such session")
    return out


@app.delete("/v1/sessions/{session_id}")
def delete_session(session_id: str, user_id: Annotated[UUID, Depends(owner)]) -> dict:
    with connection() as conn:
        if not sessions.delete(conn, user_id, session_id):
            raise HTTPException(status.HTTP_404_NOT_FOUND, "no such session")
    return {"deleted": session_id}


@app.get("/v1/sessions/suggestions")
def get_session_suggestions(
    day: date,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> list[dict]:
    """'Looks like a workout': sustained moderate HR on `day` nothing else covers (SPEC S4)."""
    with connection() as conn, conn.cursor() as cur:
        return sessions.suggestions(cur, user_id, _owner_tz(conn, user_id, settings), day)


class Dismissal(BaseModel):
    start: datetime


@app.post("/v1/sessions/suggestions/dismiss")
def dismiss_suggestion(body: Dismissal, user_id: Annotated[UUID, Depends(owner)]) -> dict:
    with connection() as conn:
        sessions.dismiss(conn, user_id, body.start)
    return {"dismissed": body.start.isoformat()}


@app.post("/v1/chat")
def post_chat(
    body: chat.ChatIn,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    """Ask your data: the model answers from read-only tool results (DD2)."""
    with connection() as conn:
        tz = _owner_tz(conn, user_id, settings)
    try:
        return chat.answer(body, tz)
    except chat.ChatUnavailable as e:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(e)) from e


@app.post("/v1/chat/stream")
def post_chat_stream(
    body: chat.ChatIn,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> StreamingResponse:
    """Ask your data, streamed as newline-delimited JSON events (status, delta, done, error)."""
    with connection() as conn:
        tz = _owner_tz(conn, user_id, settings)
    return StreamingResponse((json.dumps(e) + "\n" for e in chat.stream(body, tz)), media_type="application/x-ndjson")


@app.post("/v1/ingest")
def post_ingest(
    payload: IngestPayload,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> IngestSummary:
    with connection() as conn:
        _ensure_owner(conn, user_id, settings)
        tz = conn.execute("SELECT timezone FROM app_user WHERE id = %s", (user_id,)).fetchone()[0]
        return ingest(conn, user_id, tz, payload)
