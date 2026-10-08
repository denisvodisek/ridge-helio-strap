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
from strap_server.derive.robust import median

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
MAX_TOOL_ROUNDS = 8
# After this many lookup rounds the next call has tools switched off, so the model answers with
# what it has rather than exploring until the limit (DeepSeek likes to keep looking).
FORCE_ANSWER_AFTER = 4
TOOL_RESULT_CHARS = 24_000  # a year of daily rows fits; a runaway query gets cut, and says so

SYSTEM = mcp_server.INSTRUCTIONS + """
You are answering in a phone app. Keep it short and plain: lead with the answer in one or two
sentences, then the numbers that back it. Use the person's own history as the yardstick. No
filler, no pep talk, no metaphors, no emoji. Say "not enough data yet" when that is the truth.
You are not a doctor: for anything that sounds medical, say what the data shows and suggest
checking with one.

Speed: the last 14 days and your usual (the median of the 30 days before) are below. Answer
from them when they suffice, which is most of the time; call a tool only for older data, other
metrics or finer detail, call independent tools in the same turn, and stop looking once you
can answer.

Format (the app renders it; keep it light):
- Markdown: short paragraphs, **bold** for the key number, "-" bullets, "|" tables for
  comparisons (at most 4 columns, 8 rows), "###" headings only if the answer has parts.
- Up to 4 stat cards, as a fenced block named stats holding a JSON array:
  ```stats
  [{{"label": "Avg sleep", "value": "7h 31m", "note": "−8 min vs usual", "tone": "bad"}}]
  ```
  tone is good, bad or neutral (good = better for the person, e.g. lower resting HR).
- One small chart when a trend matters, as a fenced block named chart:
  ```chart
  {{"type": "bar", "title": "Sleep, last 7 nights", "unit": "h", "points": [["Mon", 7.5], ["Tue", 6.9]]}}
  ```
  type is bar or line; 3 to 31 points; values are plain numbers from the data.
Never put a number in a card or chart that you didn't get from the context or a tool.
Before you say something rose, fell, improved or worsened, check the order of the numbers and
which direction is better for that metric (lower is better for resting HR, sleep debt and
load; higher for HRV, recovery and sleep).

Today is {today}; the person's timezone is {tz}.

Metrics that exist (for the daily tool): {metric_names}

Last 14 days (blank = no value that day):
{recent}
"""

# The context pack: the columns most questions need, so most answers need no tool call.
RECENT_COLUMNS = [
    ("recovery", "recovery_score", "value"), ("sleep_min", "sleep_health_score_4dim", "tst_min"),
    ("sleep_debt_min", "sleep_debt_min", "value"), ("hrv_ms", "hrv_sleep_avg", "value"),
    ("rest_hr", "rhr_daily", "value"), ("load_trimp", "cardio_load", "value"), ("steps", "steps_total", "value"),
]


def context_pack(days: int = 14, usual_days: int = 30) -> tuple[str, str]:
    """(metric names, the last `days` days as a table, plus a "usual" row: the median of each
    column over the `usual_days` before them) for the system prompt."""
    names = ", ".join(m["metric"] for m in mcp_server.metrics())
    with mcp_server._connect() as conn:
        rows = conn.execute(
            "SELECT day, metric, value, flags FROM derived_daily WHERE user_id = %s AND metric = ANY(%s) "
            "AND day > (SELECT max(day) FROM derived_daily WHERE user_id = %s) - %s ORDER BY day",
            (mcp_server._owner(), [m for _, m, _ in RECENT_COLUMNS], mcp_server._owner(), days + usual_days),
        ).fetchall()
    table: dict = {}
    for day, metric, value, flags in rows:
        for col, m, field in RECENT_COLUMNS:
            if m == metric:
                v = value if field == "value" else (flags or {}).get(field)
                if v is not None:
                    table.setdefault(day, {})[col] = float(v)
    if not table:
        return names, "(no data yet)"
    ordered = sorted(table)
    recent, before = ordered[-days:], ordered[:-days]

    def fmt(col: str, v: float | None) -> str:
        return "" if v is None else (f"{v:.1f}" if col == "hrv_ms" else str(round(v)))

    cols = [c for c, _, _ in RECENT_COLUMNS]
    lines = ["day | " + " | ".join(cols)]
    lines += [f"{d.isoformat()} | " + " | ".join(fmt(c, table[d].get(c)) for c in cols) for d in recent]
    if before:
        usual = {c: median([table[d][c] for d in before if c in table[d]]) if any(c in table[d] for d in before) else None for c in cols}
        lines.append(f"usual ({len(before)} days before, median) | " + " | ".join(fmt(c, usual[c]) for c in cols))
    return names, "\n".join(lines)


def _system(tz: str) -> str:
    names, recent = context_pack()
    return SYSTEM.format(today=datetime.now(ZoneInfo(tz)).date().isoformat(), tz=tz, metric_names=names, recent=recent)


def _fn(name: str, description: str, properties: dict, required: list[str]) -> dict:
    return {"type": "function", "function": {"name": name, "description": description,
            "parameters": {"type": "object", "properties": properties, "required": required}}}


_DAY = {"type": "string", "description": "local date, YYYY-MM-DD"}
TOOLS = [
    _fn("day_summary", "One day as the Today screen shows it: recovery, strain, sleep, heart, stress, steps, VO2max.", {"day": _DAY}, ["day"]),
    _fn("daily", "Daily values with flags for some metrics over a date range (max 400 days); gaps are missing days.",
        {"metrics": {"type": "array", "items": {"type": "string"}}, "start": _DAY, "end": _DAY}, ["metrics", "start", "end"]),
    _fn("samples", "Raw strap samples in buckets (15m, 1h, 1d): min, max, mean, sum, count. Names: hr, stress, steps, hrv, spo2, skin_temp, respiratory_rate.",
        {"name": {"type": "string"}, "start": _DAY, "end": _DAY, "bucket": {"type": "string", "enum": ["15m", "1h", "1d"]}}, ["name", "start", "end"]),
    _fn("workout_sessions", "Workouts in a date range with HR, load, zones, strain and HR recovery.", {"start": _DAY, "end": _DAY}, ["start", "end"]),
    _fn("journal_entries", "Caffeine, alcohol, water, supplements and weight logged in a date range.",
        {"start": _DAY, "end": _DAY}, ["start", "end"]),
    _fn("journal_hydration", "Water logged on one day, the average on days with a log, and the drink reminder (workout calories and heat). Not a target volume.",
        {"day": _DAY}, ["day"]),
    _fn("owner_profile", "Height, sex, date of birth, activity level and latest weight.", {}, []),
    _fn("query", "One read-only SQL SELECT for anything the other tools can't answer (500 rows max).", {"sql": {"type": "string"}}, ["sql"]),
]
_IMPL = {
    "day_summary": mcp_server.day_summary, "daily": mcp_server.daily,
    "samples": mcp_server.samples, "workout_sessions": mcp_server.workout_sessions,
    "journal_entries": mcp_server.journal_entries, "journal_hydration": mcp_server.journal_hydration,
    "owner_profile": mcp_server.owner_profile, "query": mcp_server.query,
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
    messages: list[dict[str, Any]] = [{"role": "system", "content": _system(tz)}]
    messages += [m.model_dump() for m in chat.messages]
    used: list[dict] = []
    http = client or httpx.Client(timeout=90)
    try:
        for round_ in range(MAX_TOOL_ROUNDS):
            body = {"model": settings.chat_model, "messages": messages, "tools": TOOLS, "temperature": 0.2}
            if round_ >= FORCE_ANSWER_AFTER:
                body["tool_choice"] = "none"
            r = http.post(OPENROUTER_URL, headers={"Authorization": f"Bearer {settings.openrouter_api_key}", "X-Title": "Ridge"}, json=body)
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


# What the app shows while a tool runs: plain, present tense.
TOOL_STATUS = {
    "metrics": "Checking what's recorded", "day_summary": "Reading that day", "daily": "Reading your history",
    "samples": "Reading raw readings", "workout_sessions": "Looking at workouts", "journal_entries": "Reading your journal",
    "journal_hydration": "Checking water and supplements",
    "owner_profile": "Reading your profile", "query": "Searching your data",
}


def stream(chat: ChatIn, tz: str, client: httpx.Client | None = None):
    """The same loop as `answer`, as events for the phone: {"type": "status"} while a tool
    runs, {"type": "delta"} for each piece of the reply, then {"type": "done"} (or "error")."""
    settings = get_settings()
    if not settings.openrouter_api_key:
        yield {"type": "error", "message": "Add OPENROUTER_API_KEY to the server's deploy/.env, then restart it."}
        return
    messages: list[dict[str, Any]] = [{"role": "system", "content": _system(tz)}] + [m.model_dump() for m in chat.messages]
    used: list[str] = []
    http = client or httpx.Client(timeout=httpx.Timeout(90, read=120))
    try:
        for round_ in range(MAX_TOOL_ROUNDS):
            content, calls = "", {}
            body = {"model": settings.chat_model, "messages": messages, "tools": TOOLS, "temperature": 0.2, "stream": True}
            if round_ >= FORCE_ANSWER_AFTER:
                body["tool_choice"] = "none"
            with http.stream("POST", OPENROUTER_URL, json=body,
                             headers={"Authorization": f"Bearer {settings.openrouter_api_key}", "X-Title": "Ridge"}) as r:
                if r.status_code != 200:
                    yield {"type": "error", "message": f"The model provider answered HTTP {r.status_code}."}
                    return
                for line in r.iter_lines():
                    if not line.startswith("data: ") or line == "data: [DONE]":
                        continue
                    delta = (json.loads(line[6:]).get("choices") or [{}])[0].get("delta") or {}
                    if delta.get("content"):
                        content += delta["content"]
                        yield {"type": "delta", "text": delta["content"]}
                    for tc in delta.get("tool_calls") or []:
                        c = calls.setdefault(tc.get("index", 0), {"id": "", "name": "", "arguments": ""})
                        c["id"] = tc.get("id") or c["id"]
                        fn = tc.get("function") or {}
                        c["name"] += fn.get("name") or ""
                        c["arguments"] += fn.get("arguments") or ""
            if not calls:
                yield {"type": "done", "tools": used, "model": settings.chat_model}
                return
            ordered = [calls[i] for i in sorted(calls)]
            messages.append({"role": "assistant", "content": content, "tool_calls": [
                {"id": c["id"], "type": "function", "function": {"name": c["name"], "arguments": c["arguments"] or "{}"}} for c in ordered]})
            for c in ordered:
                used.append(c["name"])
                yield {"type": "status", "text": TOOL_STATUS.get(c["name"], "Looking things up") + "…"}
                messages.append({"role": "tool", "tool_call_id": c["id"],
                                 "content": _run_tool(c["name"], c["arguments"]) if c["name"] in _IMPL else json.dumps({"error": "unknown tool"})})
        yield {"type": "error", "message": "The model kept asking for more data without answering. Try a narrower question."}
    except httpx.HTTPError:
        yield {"type": "error", "message": "Could not reach the model provider."}
    finally:
        if client is None:
            http.close()

