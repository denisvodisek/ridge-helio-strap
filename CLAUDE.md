# Ridge (Denis's fork): project context for Claude

Personal project. A fork of [TheCommishDeuce/ridge-helio-strap](https://github.com/TheCommishDeuce/ridge-helio-strap):
self-hosted storage and analysis for the **Amazfit Helio Strap**. The goal is a Whoop-like
experience with deeper, personal (n=1) insight into body, metabolism and wellbeing.

Read these before working:

| File | What |
|---|---|
| `docs/denis/SETUP.md` | Getting it running on Denis's machines: server, key, app, profile |
| `docs/denis/CUSTOMISING.md` | Where things live and how to change them: look, metrics, journal, the fork workflow |
| `docs/denis/ROADMAP.md` | Review findings, the Whoop gap, and the ranked "moat and alpha" backlog |
| `docs/ARCHITECTURE.md` | Upstream: how strap → phone → server fits together |
| `docs/spec/02-science.md` | Upstream: every formula, gate and source. **The science contract** |
| `docs/spec/01-strap-protocol.md` | Upstream: the BLE protocol, byte by byte |
| `docs/DECISIONS.md` | Upstream: why things are the way they are (D1–D28) |

`docs/denis/` is ours. Everything else under `docs/` is upstream: change it only when a
change is meant to go upstream, or record our own decisions in `docs/denis/DECISIONS.md`
(create it on the first one, numbered `DD1…` so it never collides with upstream's `D` numbers).

## Layout

```
android/strap-protocol/   Pure Kotlin/JVM: BLE handshake, crypto, framing, decoders (tests run without a phone)
android/app/              Jetpack Compose app "Ridge": UI, BLE transport, SQLite store, upload outbox
server/                   Python 3.13, FastAPI, psycopg3, TimescaleDB
  src/strap_server/ingest/   validate + upsert what the phone sends
  src/strap_server/derive/   the science, one module per metric family, ordered by orchestrator.py
  src/strap_server/read/     read API: series, day summary (the cards), history
  src/strap_server/mcp_server.py  read-only MCP tools over the same read code (ours, SETUP §8)
  src/strap_server/migrations/  schema; applied on every start
deploy/                   Docker Compose: db + api + nightly backup
tools/keyfetch/           One-shot Zepp account → strap MAC + auth key
tools/demo-data/          Synthetic 5-week dataset + demo server on :8767 for UI work
```

## Commands

```sh
# Server (from server/)
uv sync && uv run ruff check && uv run pytest -q     # DB tests skip unless the test DB is up
docker run -d --name strap-test-db -p 127.0.0.1:5598:5432 -e POSTGRES_DB=strap \
  -e POSTGRES_USER=strap -e POSTGRES_PASSWORD=local-test-only timescale/timescaledb:latest-pg17

# App (from android/, JDK 17)
./gradlew :strap-protocol:test
./gradlew :app:assembleDemo        # UI work against tools/demo-data
./gradlew :app:assembleRelease     # real app; signed if ~/.android/ridge-release.jks exists

# Demo server with fake data (repo root)
tools/demo-data/load.sh            # up on 127.0.0.1:8767, prints a token
tools/demo-data/load.sh down

# After changing a formula, on the server box
docker compose exec api python -m strap_server.rederive
```

## Rules this codebase keeps (keep them)

- **No invented numbers.** A metric missing an input is *withheld with a named reason*,
  never shown as 0 or a population default. New metrics follow the same pattern
  (`withheld(reason)` in `read/summary.py`, reason ids + one-sentence messages).
- **Science changes are spec-first.** Update `docs/spec/02-science.md` (or our own spec in
  `docs/denis/`), add known-value tests, and keep the golden fixture
  `server/tests/fixtures/derive/expected_daily.json` passing unless the change
  deliberately re-baselines it.
- **One definition per metric, on the server.** The phone renders server payloads; it does
  not recompute.
- **Protocol is written down before it is coded**, probed read-only on real hardware
  first, and every write to the strap is read back.
- **Gadgetbridge is reference reading only, never copied** (AGPL lineage, D8).
- **Never commit** auth keys, tokens, `.env`, real MACs, real health data or screenshots of
  real data. Screenshots come from the demo data only.
- Match the surrounding code: naming, comment density, idioms. `ruff` and Kotlin lint stay clean.

## Known gotchas

- **The profile (DOB, sex, height, SR-PA)** is set in Settings → Profile (`GET`/`PUT
  /v1/profile`, ours). A change re-derives the whole history. Without it, energy, cardio
  load, strain, sleep need, recovery's sleep part and VO₂max are all withheld.
- `rederive` deletes `derived_daily` rows its pass no longer produces (ours): a cleared
  input must withhold, not leave yesterday's number behind. Ingest does not do this
  (it can derive a day without its night).
- **The correlations / caffeine-cutoff engine in spec/02 §3 was never ported.** The
  journal stores caffeine, alcohol and weight, but nothing analyses caffeine or alcohol yet.
- `stress` (per 5 min) is collected and charted but used by no formula (spec/02 §5).
- Sync runs when the app opens (15 min cooldown, `sync/AutoSync.kt`, our DD1 overriding
  upstream D19). Nothing runs in the background yet (roadmap #3).
- One owner per server; the owner id is the constant `00000000-0000-0000-0000-000000000001`
  (`server/src/strap_server/config.py`).
- Installing upstream's signed APK and later your own build won't upgrade in place
  (different signing keys). Pick one signing key from day one.
- Licence is **AGPL-3.0**. Fine for personal use; if this ever becomes a hosted product,
  the modified source must be offered to its users.

## Denis's preferences

Direct, fast, lead with the answer. Flag uncertainty as `[CHECK: reason]`. Git author
`denisvodisek`. Remotes: `origin` = `denisvodisek/ridge-helio-strap` (our fork, push here),
`upstream` = `TheCommishDeuce/ridge-helio-strap` (pull only).

Owner confirmed (2026-10-03): owns a Helio Strap, latest Android, Zepp account with password.
