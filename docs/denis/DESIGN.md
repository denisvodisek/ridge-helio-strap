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

Status 2026-10-03: U1–U10 built on `feat/profile-screen` (see git log); U3 gridlines, U8 motion and U10 still need a look on a real device.

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
| U10 | **Earned delight.** A small crown springs in above recovery ≥ 85 % and above a night that met its sleep need, after the arc has drawn. 85 is Oura's line and a display rule (`CROWN_AT`), not science. | Oura's crown: noticed, never loud. | dials |

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

## v2 · The design system (2026-10-03)

From a second, fuller reference pass (Refero styles for Linear, Raycast, Auros, Bevel, Apple
dark and Stryds; Whoop's brand guidelines; Kinetics, MicroKit, Motion Primitives, Magic UI,
Aceternity; Liquid Glass; Component Gallery; Oura, Gentler Streak, Bevel). Skipped as low
signal: AppShot (marketing shots, light apps), DESIGN.md (no dark health systems), Garmin.

**Principle: colour is data.** The interface is near-black and nearly colourless; the only
saturated pixels are metric values. Primary buttons are ink-filled (Raycast), one per screen.

- **Themes** (Settings → Appearance): Auto, Midnight `#08090B` (default), Void `#000000`
  (OLED; metric colours slightly desaturated against halation), Daylight `#F3F4F6`, Aurora
  `#03191A` (lavender accent). All tokens in `ui/theme/Theme.kt`; every metric colour ≥ 4.8:1
  on its card.
- **Surfaces**: card one step up from the background, 1 dp hairline, no drop shadows.
  Radius: cards 20, tiles and buttons 12 (rounded rectangles, not pills), chips 8.
- **Charts**: 2 dp line with a 7 dp 18 %-alpha glow underneath (no blur), a vertical
  gradient fill (26 % → 0) down to the baseline per unbroken run, 3 horizontal hairline
  gridlines labelled inside the plot, no vertical grid. Card charts stay unlabelled.
- **Motion** (Kinetics springs as Compose): numbers damping 0.54 / stiffness 280 (digits roll
  independently, `SlidingNumber`), layout 0.67 / 320, toggles 0.60 / 340, fades expo-out
  220 ms. Ending a workout is hold-to-confirm (800 ms ring, haptic on commit).
- **Type** (pending the font download): Geist with `tnum` on every number, Geist Mono for
  axes and timers; hero numerals 56–72 sp weight 500, labels 11 sp uppercase +0.08 em.
- **Icons** (pending the download): Phosphor Light at 24 dp, Regular at 16–20, Fill for the
  selected tab; replaces `material-icons-extended`.
- **Copy**: plain, short, the answer first; no exclamation marks, no metaphors, no "journey".

Not yet: shared-element card → detail transitions, Haze blur on the floating bars, an aurora
background on Ask (API 33+ only).
