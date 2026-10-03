# Review and roadmap: from "Ridge" to Whoop-plus

Reviewed 2026-10-03 at upstream `e043868` (Ridge 0.2.1).

## Verdict

**A good base to build on.** It's careful, well-tested code with a clear science contract.
It already does Whoop's core trio (recovery, strain, sleep) honestly, on your own server.
What it lacks is what makes Whoop *feel* like Whoop: passive syncing, insights from your
behaviour, and the trends layer. It also lacks anything that is really about metabolism.
Those are the gaps worth filling, and the architecture makes them straightforward.

## What's solid

- **Code health:** server `ruff` clean, 86 tests pass locally (87 DB tests skip without
  Docker; CI runs them). The protocol is tested against a fake strap and real byte vectors.
- **Science discipline:** every formula, gate and source is in `docs/spec/02-science.md`. A
  golden fixture (172 values) pins the derive chain. Metrics with missing inputs are
  withheld with a reason, never faked. Keep this. It's the difference between alpha and
  noise.
- **Data model:** raw per-minute samples in TimescaleDB, derived values in one
  `derived_daily (day, metric, value, flags)` table, and `rederive` recomputes everything
  from raw. A new metric is just another module writing rows.
- **Privacy:** no Zepp at runtime, no cloud, no analytics. Your data, your box.

## Risks and gaps

| Issue | Impact | Fix |
|---|---|---|
| Profile (DOB/sex/height/SR-PA) has no API or UI | 6+ metrics withheld until you insert it by SQL | #1 |
| Sync is manual (upstream decision D19) | The biggest UX gap with Whoop; data is lost if you don't sync for days | #2, #3 |
| Analytics engine (spec §3) never ported | Caffeine and alcohol get logged but nothing learns from them | #4 |
| Android 12+ and the Helio Strap only | No iPhone; no other wearable | Hard limit; an iOS client is a separate project |
| Zepp dependency: key fetch, firmware updates | A firmware update can break parsers (it happened in June 2026). A re-pair means a new key | Update firmware deliberately; re-run tests after |
| Single upstream maintainer | Bus factor 1; protocol knowledge lives in their head and spec/01 | Keep our changes in our own files so we can carry on alone |
| AGPL-3.0 | Fine for personal use. A hosted product would have to publish its source | Decide before any productising |
| Energy is an estimate (±15–20 %) | "Metabolism" from a strap alone is inference, not measurement | Roadmap P2: real metabolic inputs |

## Whoop vs Ridge today

| Whoop | Ridge 0.2.1 | Gap |
|---|---|---|
| Recovery (HRV, RHR, sleep, RR) | ✓ with weighted breakdown and live readiness | Parity |
| Strain 0–21 | ✓ TRIMP on a personal 90-day scale | Parity |
| Sleep performance, need, debt | ✓ NSF need, 14-night debt, 4-dimension sleep health, SRI | Ridge is ahead on regularity |
| Stress monitor | Strap's raw 0–100 stress, charted only | No stress load metric |
| Health monitor / illness signals | ✓ illness flag (RR + skin temp) | Parity, and more transparent |
| VO₂max | Jurca non-exercise estimate (needs profile) | Whoop's is also estimated |
| Whoop Age / Healthspan | Spec exists (§2.11), cut by upstream | #19 |
| Journal + insights | Journal (3 kinds), **no insights** | #4, #5 |
| Monthly performance report | — | #8 |
| Coach (LLM) | Cut by upstream (D21) | #6, #7 |
| Passive background sync | Manual | #2, #3 |
| Labs, blood work | — | #17 |
| Workout tracking | Strap summaries only (sport codes unmapped) | #9–#12 |
| Data import | — | #13, #14, #18 |
| Smart alarm | Strap alarms, fixed time only | [CHECK: does Helio firmware have smart wake?] |

## Where the moat and alpha actually come from

For a personal system, the moat is **years of raw per-minute data you own, plus formulas you
can audit and change**. Whoop can't give you either. The alpha comes from **joining sources
Whoop never sees** (food, glucose, labs, your own behaviour tags) and running real n=1
statistics over them. Correlations use FDR control, effects get sizes, and nothing is
shown without enough data.

A strap can't measure metabolism directly. It measures HR, HRV, skin temperature, SpO₂,
breathing rate and movement. Metabolic insight needs metabolic inputs: weight trend, intake,
glucose, labs. That's P2.

## Ranked backlog

S = a session or two · M = a few sessions · L = a project in itself.

### P0 · Foundations (do first)

1. **Profile screen + API** (S). `GET/PUT /v1/profile` and a Settings → Profile screen; on
   change, rederive the affected days. Unblocks energy, cardio load, strain, sleep need,
   VO₂max and biological age. Upstream would likely take this as a PR.
2. **Sync when the app opens** (S). Start `SyncService` on the app's `ON_START` if the last
   sync is older than ~15 min (a cooldown, so flipping between apps doesn't hammer the
   strap). `SyncRunner` already refuses a second sync while one runs.
3. **Background sync** (M). A WorkManager periodic job (every 1–2 h, Android picks the
   exact time) that runs the same sync. Pair it with a `CompanionDeviceManager` association
   for the strap, which exempts the app from Android's background limits on starting the
   Bluetooth foreground service. A Settings toggle, plus our decision `DD1` (overrides
   upstream D19). **It's more than a convenience: the strap only keeps respiratory rate
   for ~3 days and spot SpO₂ for ~12** (spec/01 §9), so days without a sync lose data
   the recovery score and illness flag depend on.

### P1 · The insight engine (the alpha)

4. **Port spec/02 §3 analytics** (M). Robust baselines, anomaly detection (|z| ≥ 2),
   Spearman lag correlations between daily metrics, Mann-Whitney for "days with X vs
   without", Benjamini-Hochberg FDR, and the caffeine/alcohol cut-off ("caffeine after 14:00
   costs you 22 min of deep sleep"). The spec and thresholds are already written. Output:
   `/v1/insights` plus an Insights screen. Label every finding n=1.
5. **Richer journal** (S per kind). Quick-tap tags that feed #4: late meal, screens in
   bed, sauna, cold exposure, supplements, mood, energy and soreness (1–5), travel,
   meditation. `manual_entry` already has duration, severity and notes, so no migration
   is needed.
6. **Talk to your data: MCP server** (S). A small read-only MCP server over the Ridge
   database (a read-only Postgres role, typed tools like `daily(metric, from, to)`,
   `day_summary(day)`, `workouts(range)`, `journal(range)`, plus guarded read-only SQL).
   You can then ask Claude Desktop or Claude Code anything about your data. It's the
   cheapest route to a chat with your data, and it proves which tools the in-app chat needs.
7. **In-app Coach chat** (M). A Chat tab, backed by `POST /v1/chat` on the server. The
   server runs the Claude API with the same tools as #6, so the model only sees query
   results, never the database, and the API key stays on the server. Numbers come from
   tools, never from the model's memory. Upstream cut its LLM (D21) because a local
   Gemma 12B on an 8 GB GPU was too weak and slow. A cloud model fixes that, at the cost
   of sending query results to Anthropic per question: our decision `DD2`. A fully local
   model is possible later (Ollama on a GPU box) but expect weaker answers.
8. **Weekly and monthly report** (S–M). Server-built: trends, best and worst days, what
   moved recovery, new findings from #4. Numbers first; a written narrative via #7's
   plumbing once it exists.

### P1 · Workouts (tennis, treadmill)

What the strap gives today: one **summary per workout** (start, sport code, duration,
calories, avg/max/min HR), plus per-minute HR all day. A workout only exists if it was
started in the Zepp app or auto-detected by the strap. The sport-code table isn't mapped
yet, and the per-second workout track (`0x06`) isn't decoded (spec/01 §7.2, §8). There's no
GPS and no raw motion data, so no shot counts or stroke detection.

9. **Sessions you start in Ridge** (S–M). A Start/Stop (or "log a past session") button
   in Ridge with a sport (tennis, treadmill run, gym…), stored as a time window. The server
   computes everything from the per-minute HR it already has: TRIMP load, strain for the
   session, time in each HR zone, peak HR, and **HR recovery** (bpm drop in the first
   minutes after stopping, a well-evidenced fitness marker). Works for any sport, today,
   with no protocol work.
10. **Tennis view** (S, after #9). Session strain and zones against your tennis history;
    work/rest pattern from the HR curve (rallies vs changeovers); match vs practice tag;
    next-day recovery after tennis vs other days (feeds #4).
11. **Treadmill runs → a measured VO₂max** (M, after #9). Log speed and incline per
    segment (the treadmill's own numbers). Known speed and grade give oxygen cost from the
    ACSM equations, so steady segments let the server fit HR against VO₂ and estimate a
    *measured* VO₂max, a big upgrade on the questionnaire-based Jurca estimate. Spec/02
    §2.10 already describes the method (HR-reserve inversion, ≥ 6 steady windows); it was
    only dropped because there was no speed source without GPS. A treadmill is that source.
    Also gives pace trend at a fixed HR (aerobic efficiency) over months.
12. **Decode the strap's own workouts better** (M, protocol work). Map the sport codes
    (record a few known sessions in the Zepp app, read them back) and decode the
    per-second track `0x06` for true peak HR and second-level curves. Read-only probing,
    as CONTRIBUTING requires. [CHECK: which sports the Helio Strap can auto-detect, and
    whether Ridge can start a strap workout itself over the `0019` workout service]

### P2 · Metabolism and imported data

13. **Import your Zepp history** (S–M). The strap only holds ~30 days, so everything from
    before Ridge lives in the Zepp cloud. A one-off importer for the Zepp account export
    (or the same API `keyfetch` signs into) backfills months or years of baselines on day
    one. [CHECK: the Zepp export format and whether it includes per-minute HR]
14. **Health Connect import** (M). Read nutrition, smart-scale weight and body fat, blood
    pressure and glucose from HC into the server. One ingest path covers many apps and
    devices. Upstream only planned HC *export*.
15. **Weight trend + energy balance** (M). Smoothed weight trend (EWMA), estimated
    expenditure from the existing energy module, then an *adaptive* TDEE once intake
    arrives via #14 (weight change vs intake, MacroFactor-style). Real numbers on
    maintenance calories and on deficit or surplus.
16. **CGM integration** (M–L). FreeStyle Libre (via LibreLinkUp) or Dexcom (official API).
    Glucose overlaid on meals, sleep and workouts; variability (CV, time in range,
    post-meal peaks); glucose against the next night's HRV and recovery. The strongest
    metabolic signal you can get. [CHECK: whether Whoop has added a CGM integration since
    mid-2026; which CGMs are easy to get in HK]
17. **Labs** (S–M). Blood panels (HbA1c, fasting glucose and insulin, lipids, ApoB,
    hs-CRP, ferritin, vitamin D, testosterone, TSH) entered by hand or parsed from PDF, as
    dated series next to your trends.
18. **CSV import** (S). A generic `date, metric, value` upload for anything else (an old
    spreadsheet, another app's export).

### P3 · Deeper body signals

19. **Biological age** (S, after #1). Port spec/02 §2.11: chronological age adjusted by
    fitness (VO₂max vs your age and sex), sleep and resting HR, through mortality-risk
    ratios. Upstream cut it from v1 scope; the spec is complete. Gets much better with a
    measured VO₂max from #11. This is the honest version of "metabolic age". The bathroom-
    scale kind (your BMR vs an age table) has no real evidence behind it.
20. **Stress load** (S). From the unused 5-minute stress stream: waking time in high
    arousal, and recovery time after stress spikes. Framed as the strap's arousal index,
    not "stress" in the clinical sense (as D28 does).
21. **HRV trend quality** (S). 7-day rolling mean and coefficient of variation of nightly
    HRV (Plews et al. 2012–13). A rising CV with a falling mean is an early overreaching
    signal, and it's better evidenced than any single night's number.
22. **Circadian profile** (S). Chronotype from free-day sleep midpoint (MSFsc,
    Roenneberg), social jetlag, and the timing of the nightly skin-temperature curve (the
    strap records skin temp every minute, all day).
23. **Undecoded strap streams** (L, research). Spec/01 §8 lists data the strap holds that
    nobody has decoded: `0x4a`, a likely **beat-to-beat (RR interval) buffer**, which would
    allow real HRV analysis instead of the strap's summary number; `0x4e`, a possible
    AFib / sleep-apnoea event log; `0x3b`, a daily summary. This is real alpha that no
    app gives you, but slow, careful protocol work.

## Suggested first three sessions

1. Server + app running on real data (SETUP), profile set by SQL, and the Zepp history
   import (#13) if the export has what we need, so baselines exist from day one.
2. #1 profile screen, #2 sync on open, #3 background sync. After that the data collects
   itself.
3. #6 MCP server (talk to your data in Claude straight away), then #9 workout sessions
   before your next tennis match.

Then #4 + #5 (insights), #11 (treadmill VO₂max), #7 (in-app chat).

Most metrics need 1–4 weeks of history before they say anything (SETUP §7), so get real
data flowing early, before the big features.
