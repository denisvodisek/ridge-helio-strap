# Our decisions (Denis's fork)

Numbered `DD1…` so they never collide with upstream's `D` numbers in `docs/DECISIONS.md`.
A `DD` that overrides an upstream `D` says so.

| # | Decision |
|---|---|
| DD1 | **Sync automatically (2026-10-03). Overrides D19.** The strap keeps respiratory rate for only ~3 days and spot SpO₂ for ~12 (spec/01 §9), so days without a sync lose inputs recovery and the illness flag depend on, and a manual sync is the biggest gap with Whoop. Step 1 (done): sync whenever the app comes to the front, if the last attempt (failed ones included) is over 15 min old (`sync/AutoSync.kt`). Step 2 (roadmap #3): a periodic WorkManager sync in the background with a `CompanionDeviceManager` association and a Settings toggle. Pull-to-refresh stays for an immediate sync. |
| DD2 | **Ask your data through a cloud model (2026-10-03). Overrides D20/D21's "no cloud model".** `POST /v1/chat` runs a tool loop on the server: the model (OpenRouter, default `deepseek/deepseek-v4.1-flash`, set by `CHAT_MODEL`) calls the same read-only tools as the MCP server, and only those tool results leave the box, with each question. The database, the raw tables and the API key never do; the key lives in `deploy/.env` as `OPENROUTER_API_KEY`, and without it the chat says how to add one. Upstream cut its LLM because a local 12B model was too weak; a cloud model answers well, at this privacy cost, which is ours to accept. |
| DD3 | **Strap workouts are editable, by hiding, never by rewriting (2026-10-04).** The owner can change a strap workout's sport or time, or delete it (SPEC S3). `workout` stays exactly what the strap sent, with ingest its only writer, so a delete hides the row by its start (`workout_hidden`) and an edit hides it and creates a `session` with source `strap`. Rewriting `workout` instead would be undone by the strap's next sync, which upserts the same row. |
