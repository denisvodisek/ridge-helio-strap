# Design direction: Ridge at the level of Whoop, Oura and Apple Health

Written 2026-10-03 from a live reference scan (Whoop's 2025 home, Oura's 2024 app, Apple
Vitals and Fitness, Bevel, Gentler Streak, Garmin, Athlytic, Apple's WWDC22 chart talk,
NN/g, Datawrapper, Material 3 Expressive) and an audit of every Ridge screen on demo data.
Sources are in the Claude Agents swipe file under "Health & data apps (mobile)".

## The bar

The best ones share eight habits. Ridge already has the first two; the rest are the work.

1. **One hero score per domain, and every score opens its contributors.** ✓ (recovery, strain, sleep dials)
2. **Everything against your own baseline, the band always visible.** ✓ ("usual" ticks, 30/42-day baselines)
3. **Plain words beside every number.** "Typical / Above usual / Pay attention", never colour alone.
4. **Learning periods said out loud.** "Learning your baseline · 3 of 5 nights", not a blank.
5. **Card charts are static; the detail chart is interactive** (axes, scrub, Day/Week/Month).
6. **A sentence states the takeaway above each detail chart**, computed by the server.
7. **Sync is invisible** unless you ask for it, and confirms quietly when it brings news.
8. **Delight is small and earned**: a count-up once, a ring that draws, a crown at 85+, a haptic on save.

And three things we never do: join a line across missing days as if measured, use a gauge or
donut for anything but the one hero number, or let a streak break on a sick day.

## Audit: what to change (ranked)

| # | Change | Why | Where |
|---|---|---|---|
| U1 | **Quiet sync.** No progress bar or spinner over the content on every tab. Auto-sync shows only in the top-bar subtitle ("Syncing · 40 %"); pull-to-refresh uses M3's morphing `LoadingIndicator`; the end is a short snackbar only when new data arrived. | The bar + floating spinner sit on top of cards on all five tabs and make an automatic background job look like an error. | `MainActivity`, `Refreshable` |
| U2 | **Distinct sleep-stage colours** (deep = indigo, REM = cyan, light = periwinkle, awake = amber), shared by hypnogram, tiles and legend. | Deep and REM are two shades of one purple today; Oura and Apple separate them by hue. | `Charts.stageShades`, theme |
| U3 | **Readable detail charts.** Y-axis min/max labels with two faint gridlines; today's day chart ends at "now" (future hours faded, not blank); the "usual" band behind daily trend lines; lines break at gaps. | The HR chart has no values on its y-axis and two-thirds of it is empty future. | `DayLineChart`, `DailyBars`, `RangeChart` |
| U4 | **Plain-word contributor states.** Each recovery contributor gets a label (Typical / Better than usual / Pay attention) next to its dot; only "Pay attention" is red. | A green or amber dot alone is colour as the only signal (NN/g). | `DayCards`, `RecoveryScreen` |
| U5 | **Calibration states.** A card whose baseline is still forming shows "Learning your baseline · n of N" with a thin progress bar, from a server reason that carries n and N. | Denis's first weeks are exactly this state; "withheld" text reads like a fault. | `read/summary.py` + cards |
| U6 | **Today without repetition.** Today keeps the three dials, a one-line outlook, the top two contributors that moved recovery, and the day timeline; the full contributor list lives on Recovery. | The full contributor list appears twice, on Today and on Recovery. | `TodayScreen`, `DayCards` |
| U7 | **Readiness in words.** "Today's load so far: about a quarter of a typical day" with a bar, not "51 vs your typical 193". | Raw TRIMP units mean nothing to a person. | `RecoveryScreen` |
| U8 | **Motion and haptics.** Hero number counts up once per day; cards rise in with a 30 ms stagger on first load; pushed screens use a shared-axis slide; `CONFIRM` haptic on save/log, a tick per hour while scrubbing a day chart. All via `MaterialTheme.motionScheme`, and off when the system's remove-animations setting is on. | Today the app only animates the gauge fill and the segmented buttons. | theme, components |
| U9 | **Profile that explains itself.** DOB typed (M3 `DisplayMode.Input`), height as a stepper, activity level as five short chips with one-line help, and an "unlocks" line per field (e.g. date of birth → sleep need). | It reads like a form; the reason for each field is the best persuasion (Noom). | `ProfileScreen` |
| U10 | **Earned delight.** A small crown next to recovery ≥ 85 and next to a night that met its sleep need. | Oura's crown: noticed, never loud. | dials |

Later, with the insight engine (roadmap #4): a fourth hero row "What's moving your recovery"
(n=1 findings) and a Trends tab replacing the Strap tab, which moves under the top-bar battery
chip and Settings. Navigation stays five tabs, never swipe-between-tabs for scores.

## Rules for every UI change

- Numbers and sentences come from the server (CLAUDE.md "one definition per metric").
  The phone may phrase a server value; it never computes one.
- Every animation has a reduced-motion path (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`
  → snap to the end state).
- Contrast AA in both themes; touch targets ≥ 48 dp; charts have a content description that
  reads the takeaway sentence.
- Screenshots for review come from the demo data only.
