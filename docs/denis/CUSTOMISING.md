# Customising Ridge yourself

Three kinds of change, from easiest to most involved: **how it looks**, **what it shows**,
**what it computes**. Each section says where to edit and how to check it.

The fastest loop for anything visual: demo server + demo app (SETUP §5). No strap, no
real data, and changes show up after a rebuild and reinstall.

## 1 · How it looks (Android, Kotlin + Jetpack Compose)

| Want to change | Edit |
|---|---|
| Colours: background, cards, recovery zones, per-metric accents | `android/app/src/main/kotlin/app/strap/ui/theme/Theme.kt`: `DarkRidge`/light `RidgeColors`, `MetricColors`/`MetricTone` (one accent per metric family) |
| Recovery zone thresholds (≥67 green, 34–66 yellow) | `RidgeColors.zone()` in `Theme.kt` |
| Type scale | `RidgeType` in `Theme.kt` |
| Today screen layout, card order | `ui/today/TodayScreen.kt`, `ui/today/DayCards.kt` |
| Recovery breakdown screen | `ui/today/RecoveryScreen.kt` |
| Gauges (the Whoop-style rings) | `ui/components/Gauge.kt` |
| Charts: line, range bands, daily bars, timeline | `ui/components/Charts.kt`, `RangeChart.kt`, `DailyBars.kt`, `Timeline.kt` |
| "Your usual" baseline band | `ui/components/Baseline.kt` |
| Tabs (Today / Sleep / Activity / Journal / Strap) | `enum class Tab` in `MainActivity.kt` |
| Info sheets (the "?" explanations) | `ui/components/Sheets.kt` |
| App name, icon | `res/values/strings.xml`, `res/drawable/ic_launcher_*.xml` |

To make it feel more like Whoop: the bones are already there (dark theme, three gauges,
zone colours). The biggest visual levers are `Gauge.kt` (stroke weight, glow, number
typography) and the Today card hierarchy in `TodayScreen.kt`. Ask Claude to "act as a
designer": the `design-references` skill pulls Whoop/Bevel-style patterns first.

Check: `./gradlew :app:lintRelease`, then install the demo build and look at it.

## 2 · What it shows (the server → app contract)

The app renders what the server's **day summary** returns, nothing more:

```
GET /v1/day/{day}/summary   → read/summary.py: day_summary()  → one dict per card
GET /v1/day/{day}/series    → read/series.py   (per-minute HR, stress, steps)
GET /v1/series/{name}       → week/month buckets with min/max kept
GET /v1/daily               → read/history.py  (derived_daily rows over a range)
```

Every card is either `{value, baseline, z, flags…}` or `{"withheld": {reason, message}}`.
The app side lives in `ui/today/TodayData.kt` (parsing) and `DayCards.kt` (drawing), and
talks through `api/ApiClient.kt`.

**Showing an existing metric that has no card yet** (e.g. `hr_zone_minutes` or
`spo2_overnight`, which are derived but which no screen reads by name as of v0.2.1):
add a `*_card()` in `read/summary.py` that reads it from `derived_daily` and add it to
`day_summary()`. Then parse it in `TodayData.kt` and draw it in `DayCards.kt`. No derive
change, no rederive.

## 3 · What it computes (a new metric, end to end)

Worked recipe, e.g. a daily "stress load" from the unused per-minute stress stream:

1. **Spec first.** Write the formula, its inputs, its gates (when it's withheld, and the
   reason ids) and its source in `docs/denis/SCIENCE.md` (ours; create it on the first
   metric). If it's not sourced, say so, as upstream does with its "+0.5 °C is an unsourced
   heuristic".
2. **Derive.** New module `server/src/strap_server/derive/stress_load.py`, modelled on
   `rhr.py` (short) or `mvpa.py`. Read from `sample` (metric names: `hr`, `hrv`, `spo2`,
   `skin_temp_c`, `respiratory_rate`, `stress`, `steps_per_minute`). Write with
   `_upsert_daily(...)` from `derive/_common.py` into `derived_daily (day, metric, value, flags)`.
   Use the day-bounds helpers. Never assume 1440 minutes in a day (DST).
3. **Wire it** into `derive/orchestrator.py`, `derive_day()`, after anything it depends on.
4. **Test.** `server/tests/derive/test_stress_load.py` with known values (the existing
   tests show the seed/fixture pattern). The golden fixture must keep passing. If your
   module writes new rows that the fixture checks, re-baseline it deliberately and note why.
5. **Read.** Add a card in `read/summary.py` (§2).
6. **Show.** `TodayData.kt` + `DayCards.kt` (§1).
7. **Backfill.** Deploy, then `docker compose exec api python -m strap_server.rederive`.

A new **raw input** (a column or table) needs a migration: add
`server/src/strap_server/migrations/m0002_<name>.py` with a `STATEMENTS` tuple like
`m0001_initial.py`. It runs on the next server start.

A new **data source** (CGM, scale, Health Connect, labs) is a new ingest path: a model in
`ingest/models.py`, upsert in `ingest/upsert.py`, or a separate endpoint in `api.py`
guarded by the same device token.

### Journal kinds

`server/src/strap_server/journal.py` fixes the kinds (`caffeine`, `alcohol`, `water`,
`supplement`, `weight`), their units and plausibility limits. Adding a kind means: add it
to the `Literal`, `UNITS` and `limits`, then add the entry UI in `ui/journal/JournalScreen.kt`.
Water's total, average and reminder live in `hydration.py` (SPEC S8), not in the phone.
`manual_entry` already has `end_ts`, `severity`, `notes` and `flags`, so durations (sauna,
fasting) and ratings (mood, soreness) fit without a migration. A supplement's effect is
`notes`.

## 4 · Strap behaviour

Alarms can be read and written. Other strap settings (HR interval, stress, SpO₂, sleep
detection) are read-only. Writing them is a protocol job: document the bytes in
`docs/spec/01-strap-protocol.md`, probe **read-only** first, read back every write, add
test vectors to `android/strap-protocol/src/test/`. Don't experiment on the strap blind.
A bad write can mean a factory reset, a re-pair and a new key.

## 5 · Living with upstream

Upstream ships often. To keep pulling its fixes cheaply:

- **Put new things in new files** (`derive/stress_load.py`, `docs/denis/…`, new composables)
  and keep edits to upstream files small: a line to wire in, not a rewrite.
- Pull regularly: `git fetch upstream && git rebase upstream/main`. Run the tests after.
- If upstream changes a formula (its release notes say so), rederive after deploying.
- A fix that's generally useful (e.g. a profile endpoint) can go back upstream as a PR.
  The licence is AGPL, so contributions are AGPL too.

## 6 · Working with Claude on this repo

Open a session in `~/Development/ridge-helio-strap`; `CLAUDE.md` loads the context. Good
openers:

- "Build roadmap item #1 (profile screen + API), spec first, then server, then app."
- "Act as a designer: make the Today screen feel closer to Whoop, demo data only."
- "Add a journal kind for sauna with duration."
- "Port spec/02 §3 analytics (correlations + caffeine cut-off)."
