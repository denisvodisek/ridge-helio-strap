"""Ask your data: the tool loop runs the model's calls against the real read code, and fails plainly."""

from __future__ import annotations

import json

import httpx
import pytest

from strap_server import chat, config
from tests.test_mcp_server import seeded  # noqa: F401 — the seeded DB + settings fixture

pytestmark = pytest.mark.db


def _fake_model(script: list[dict]):
    """An OpenRouter stand-in that replays `script` and records what it was sent."""
    sent: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"message": script[len(sent) - 1]}]})

    return httpx.Client(transport=httpx.MockTransport(handler)), sent


def test_the_model_answers_from_the_tool_results(seeded, monkeypatch) -> None:  # noqa: F811
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-key")
    config.get_settings.cache_clear()
    client, sent = _fake_model([
        {"content": "", "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "owner_profile", "arguments": "{}"}}]},
        {"content": "You're 175 cm."},
    ])
    out = chat.answer(chat.ChatIn(messages=[{"role": "user", "content": "How tall am I?"}]), "Asia/Kolkata", client)
    assert out["reply"] == "You're 175 cm." and out["tools"][0]["name"] == "owner_profile"
    tool_msg = sent[1]["messages"][-1]
    assert tool_msg["role"] == "tool" and json.loads(tool_msg["content"])["height_cm"] == 175.0


def test_a_bad_tool_call_goes_back_to_the_model_as_an_error(seeded, monkeypatch) -> None:  # noqa: F811
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-key")
    config.get_settings.cache_clear()
    client, sent = _fake_model([
        {"content": "", "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "query", "arguments": '{"sql": "DELETE FROM profile"}'}}]},
        {"content": "I can only read."},
    ])
    chat.answer(chat.ChatIn(messages=[{"role": "user", "content": "delete my profile"}]), "UTC", client)
    assert "error" in json.loads(sent[1]["messages"][-1]["content"])


def test_without_a_key_the_chat_says_how_to_add_one(monkeypatch) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "")
    config.get_settings.cache_clear()
    with pytest.raises(chat.ChatUnavailable, match="OPENROUTER_API_KEY"):
        chat.answer(chat.ChatIn(messages=[{"role": "user", "content": "hi"}]), "UTC")
    config.get_settings.cache_clear()


def _sse(chunks: list[dict]) -> bytes:
    return b"".join(b"data: " + json.dumps({"choices": [{"delta": c}]}).encode() + b"\n\n" for c in chunks) + b"data: [DONE]\n\n"


def test_stream_reports_tools_then_streams_the_reply(seeded, monkeypatch) -> None:  # noqa: F811
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-key")
    config.get_settings.cache_clear()
    rounds = [
        _sse([{"tool_calls": [{"index": 0, "id": "c1", "function": {"name": "owner_profile", "arguments": ""}}]},
              {"tool_calls": [{"index": 0, "function": {"arguments": "{}"}}]}]),
        _sse([{"content": "You're "}, {"content": "**175 cm**."}]),
    ]
    sent: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(json.loads(request.content))
        return httpx.Response(200, content=rounds[len(sent) - 1], headers={"content-type": "text/event-stream"})

    events = list(chat.stream(chat.ChatIn(messages=[{"role": "user", "content": "How tall am I?"}]), "Asia/Kolkata",
                              httpx.Client(transport=httpx.MockTransport(handler))))
    assert [e["type"] for e in events] == ["status", "delta", "delta", "done"]
    assert "".join(e["text"] for e in events if e["type"] == "delta") == "You're **175 cm**."
    assert events[-1]["tools"] == ["owner_profile"]
    assert "Last 14 days" in sent[0]["messages"][0]["content"]  # the context pack rode along
    assert json.loads(sent[1]["messages"][-1]["content"])["height_cm"] == 175.0
