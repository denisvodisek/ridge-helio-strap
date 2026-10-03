"""Ask your data: a chat over the same read-only tools as the MCP server (roadmap #7, DD2).

The phone sends the conversation; the server asks the model (OpenRouter, default DeepSeek
V4.1 Flash), runs whatever read-only tools the model calls against this database, and loops
until the model answers. The model never sees the database, only the tool results it asked
for, and the API key stays on the server. Numbers in an answer come from those results.
"""

from __future__ import annotations

import json
from datetime import datetime
from typing import Any, Literal
from zoneinfo import ZoneInfo

import httpx
from pydantic import BaseModel, Field

from strap_server import mcp_server
from strap_server.config import get_settings

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
MAX_TOOL_ROUNDS = 8
TOOL_RESULT_CHARS = 24_000  # a year of daily rows fits; a runaway query gets cut, and says so

SYSTEM = mcp_server.INSTRUCTIONS + """
You are answering in a phone app. Keep it short and plain: lead with the answer in one or two
sentences, then at most a few bullet points with the numbers that back it. Use the person's own
history as the yardstick. No filler, no pep talk, no metaphors. Say "not enough data yet" when
that is the truth. You are not a doctor: for anything that sounds medical, say what the data
shows and suggest checking with one.
Today is {today}; the person's timezone is {tz}.
"""


def _fn(name: str, description: str, properties: dict, required: list[str]) -> dict:
    return {"type": "function", "function": {"name": name, "description": description,
            "parameters": {"type": "object", "properties": properties, "required": required}}}


_DAY = {"type": "string", "description": "local date, YYYY-MM-DD"}
TOOLS = [
    _fn("metrics", "Every derived daily metric with its day count and first/last day.", {}, []),
    _fn("day_summary", "One day as the Today screen shows it: recovery, strain, sleep, heart, stress, steps, VO2max.", {"day": _DAY}, ["day"]),
    _fn("daily", "Daily values with flags for some metrics over a date range (max 400 days); gaps are missing days.",
        {"metrics": {"type": "array", "items": {"type": "string"}}, "start": _DAY, "end": _DAY}, ["metrics", "start", "end"]),
    _fn("samples", "Raw strap samples in buckets (15m, 1h, 1d): min, max, mean, sum, count. Names: hr, stress, steps, hrv, spo2, skin_temp, respiratory_rate.",
        {"name": {"type": "string"}, "start": _DAY, "end": _DAY, "bucket": {"type": "string", "enum": ["15m", "1h", "1d"]}}, ["name", "start", "end"]),
    _fn("workout_sessions", "Workouts in a date range with HR, load, zones, strain and HR recovery.", {"start": _DAY, "end": _DAY}, ["start", "end"]),
    _fn("journal_entries", "Caffeine, alcohol and weight logged in a date range.", {"start": _DAY, "end": _DAY}, ["start", "end"]),
    _fn("owner_profile", "Height, sex, date of birth, activity level and latest weight.", {}, []),
    _fn("query", "One read-only SQL SELECT for anything the other tools can't answer (500 rows max).", {"sql": {"type": "string"}}, ["sql"]),
]
_IMPL = {
    "metrics": mcp_server.metrics, "day_summary": mcp_server.day_summary, "daily": mcp_server.daily,
    "samples": mcp_server.samples, "workout_sessions": mcp_server.workout_sessions,
    "journal_entries": mcp_server.journal_entries, "owner_profile": mcp_server.owner_profile, "query": mcp_server.query,
}


class Message(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=4_000)


class ChatIn(BaseModel):
    messages: list[Message] = Field(min_length=1, max_length=40)


class ChatUnavailable(Exception):
    """No key configured, or the model provider failed: said plainly to the phone."""


def _run_tool(name: str, raw_args: str) -> str:
    """One tool call, its result as text for the model; errors go back as text too, so the
    model can correct itself (a bad date, an unknown metric) instead of the chat failing."""
    try:
        args = json.loads(raw_args or "{}")
        result = _IMPL[name](**args)
        text = json.dumps(result, default=str)
    except Exception as e:  # noqa: BLE001 — every failure is reported to the model, none is swallowed
        return json.dumps({"error": f"{type(e).__name__}: {e}"})
    return text if len(text) <= TOOL_RESULT_CHARS else text[:TOOL_RESULT_CHARS] + '…"[truncated: narrow the range]"'


def answer(chat: ChatIn, tz: str, client: httpx.Client | None = None) -> dict:
    """The model's reply after it has looked at whatever it needed, plus which tools it used."""
    settings = get_settings()
    if not settings.openrouter_api_key:
        raise ChatUnavailable("Add OPENROUTER_API_KEY to the server's deploy/.env, then restart it.")
    messages: list[dict[str, Any]] = [{"role": "system", "content": SYSTEM.format(today=datetime.now(ZoneInfo(tz)).date().isoformat(), tz=tz)}]
    messages += [m.model_dump() for m in chat.messages]
    used: list[dict] = []
    http = client or httpx.Client(timeout=90)
    try:
        for _ in range(MAX_TOOL_ROUNDS):
            r = http.post(OPENROUTER_URL, headers={"Authorization": f"Bearer {settings.openrouter_api_key}", "X-Title": "Ridge"},
                          json={"model": settings.chat_model, "messages": messages, "tools": TOOLS, "temperature": 0.2})
            if r.status_code != 200:
                raise ChatUnavailable(f"The model provider answered HTTP {r.status_code}.")
            msg = r.json()["choices"][0]["message"]
            calls = msg.get("tool_calls") or []
            if not calls:
                return {"reply": (msg.get("content") or "").strip(), "tools": used, "model": settings.chat_model}
            messages.append({"role": "assistant", "content": msg.get("content") or "", "tool_calls": calls})
            for call in calls:
                name, raw = call["function"]["name"], call["function"].get("arguments") or "{}"
                used.append({"name": name, "arguments": raw})
                messages.append({"role": "tool", "tool_call_id": call["id"],
                                 "content": _run_tool(name, raw) if name in _IMPL else json.dumps({"error": "unknown tool"})})
        raise ChatUnavailable("The model kept asking for more data without answering. Try a narrower question.")
    except httpx.HTTPError as e:
        raise ChatUnavailable("Could not reach the model provider.") from e
    finally:
        if client is None:
            http.close()
