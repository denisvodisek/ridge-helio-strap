# Our science and read-model additions (Denis's fork)

Upstream's contract is `docs/spec/02-science.md`; this file holds what we add on top, with
the same rules: every threshold sourced or marked as ours, known-value tests beside the code.

## S1 · Recovery contributor states (read time, `read/summary.py`)

Each recovery factor on the Today and Recovery cards carries a plain-word state, so a
contributor never relies on colour alone (DESIGN U4). Computed when the summary is read,
from the factor row already in `flags.factors`; nothing is stored or re-derived.

| Factor | better | watch | otherwise | Source |
|---|---|---|---|---|
| `hrv` | z > 0.3 | z < −0.5 | typical | spec/02 §2.8 (HRV favourable / unfavourable) |
| `rhr` | z < −0.3 | z > 0.5 | typical | spec/02 §2.8 ("RHR mirrored") |
| `rr` | z < −0.3 | z > 0.5 | typical | **ours**: §2.8 gives no RR thresholds; mirrored like RHR because higher breathing rate in sleep is the unfavourable direction (§2.7 scores it `50 − 15·z`) |
| `sleep` | TST ≥ need | — | short | factual, no threshold: asleep against the NSF need already used by §2.7 |

Labels say the direction, not a verdict: HRV better → "Above usual", watch → "Below usual";
RHR and RR the other way round; typical → "Typical"; sleep → "Need met" or "N min short".
The colour carries good or bad (green better, amber watch/short, neutral typical). We
deliberately do not say "Pay attention": with §2.8's z < −0.5 a normally distributed marker
lands in `watch` on about 31 % of days, which is ordinary variation, not an alarm. The phone
shows the label; it does not compute the state.

## S2 · Learning periods (read time, `read/summary.py`)

A card whose number depends on a personal baseline that does not exist yet is withheld as
`learning_baseline` with `progress: {have, need}`, so the app can say "3 of 5 nights"
instead of "sync the strap" (DESIGN U5). The withheld reason is the same whether the
owner is new or returning from a long gap.

| Card | have | need | Source |
|---|---|---|---|
| recovery | max(rows of `hrv_sleep_avg`, rows of `rhr_daily`) in the 42 days before the day | 5 | spec/02 §2.7 ("needs ≥ 5 points"); recovery needs HRV or RHR |
| strain | days with `cardio_load > 0` in the 90 days ending the day | 7 | **ours.** §2.5 sets no minimum, so on the first day P95 is that day's own load and strain is always 21.0; through the first week the hardest day sits near 21. Seven days is a judgement call, not a published number [CHECK: revisit once real data shows how fast P95 settles] |

Until strain is scored, the card carries the raw `cardio_load` beside the withheld state,
so the day's load is still visible, just not placed on a 0–21 scale.

## S3 · Workout sessions (roadmap #9, `sessions.py`)

A session is a time window with a sport, from one of three sources: `ridge` (started and
stopped in the app, or logged afterwards), `suggested` (a S4 suggestion the owner
confirmed) or `strap` (a workout the strap recorded, read from `workout`). Everything about
a session is computed when it is read, from the per-minute HR already stored, with the
same definitions as the day (spec/02 §2.4): nothing new is stored but the window.

```
minutes  = waking per-minute HR means in [start, end)   (HR_VALID_SQL, as cardio_load)
hrmax    = 208 − 0.7 × age on the session's day           (Tanaka 2001, §2.4)
rhr      = the measured resting HR cardio_load would use  (≤ 30 d old, §2.4; else withhold)
trimp    = trimp_total(minutes, rhr, hrmax, sex)          (Banister, derive/trimp.py)
zones    = Edwards minutes, lower bounds 50/60/70/80/90 % HRmax   (§2.4)
strain   = strain_from_load(trimp, P95 of daily cardio_load, 90 d) — same 0–21 scale as the
           day, so a session and its day compare; withheld while S2 says strain is learning
peak, avg = max / mean of the minutes
hrr1, hrr2 = HR in the last minute before `end` − HR 1 and 2 min after `end`
```

**HR recovery** (`hrr1`, `hrr2`): a well-evidenced autonomic fitness marker (Cole et al.
1999, NEJM: HRR1 ≤ 12 bpm after an exercise test predicted mortality). Ours to apply here,
with two honest limits shown beside it: the strap gives per-minute means, not beat-level
HR, and a cool-down walk is not the test's protocol. So it is shown as **your own trend**
across sessions of the same sport, never against Cole's cut-off. Withheld when either
minute has no HR, or a later session starts within 2 min.

Withheld reasons: `profile_or_weight_missing` (trimp, zones, strain), `no_measured_rhr`,
`no_hr_in_window` (no HR minutes at all). Sports are a closed list: tennis, treadmill,
stairs (stair-climber machine), run, walk, ride, gym, swim, yoga, other.

**Editing** (migration 0003, DD3). Any session can change sport, start or end, or be
deleted, including one the strap recorded (id `strap:<start ms>`). `workout` stays the
strap's own record, written only by ingest, so an edit is never a write to it:

- deleting a strap workout hides it by its start (`workout_hidden`);
- editing one hides it the same way and creates a session with source `strap`: the strap's
  window and mapped sport, with the edit applied. Later edits go to that session.

The strap sends the same workout again on the next sync; it lands on the hidden row and
changes nothing the owner sees. A hidden workout still counts as covered time for S4
(deleting it says it wasn't a workout, so it isn't offered back). Energy (spec/02) still
adds the device's calories for a hidden workout and skips its minutes in the MET walk
[CHECK: right for an edit, which keeps the effort; arguably wrong for a delete of a
workout the strap started by mistake].

## S4 · Suggested sessions ("looks like a workout")

**Ours.** The owner shouldn't have to remember to press Start. For each local day, a run of
waking minutes is suggested when:

- HR ≥ rhr + 0.40 × (hrmax − rhr), i.e. at least 40 % of HR reserve, the lower bound of
  moderate intensity in ACSM's Guidelines (11th ed., Table 6.1);
- the run lasts ≥ 20 min, bridging dips below the line of ≤ 3 min (a changeover, a set rest);
- it overlaps no sleep, no session and no strap workout, and wasn't dismissed before.

Suggestions are offers, never data: nothing counts as a workout until the owner confirms
one (it then becomes a `suggested` session with the sport they picked) or dismisses it
(remembered by its start minute). [CHECK: tune 40 % / 20 min / 3 min on real days]

## S5 · Zepp export import (`import_zepp.py`)

Facts about the export, checked on a real one (2026-10-03), that the importer relies on:

- SLEEP, SPORT and BODY carry UTC timestamps with an offset; HEARTRATE_AUTO, ACTIVITY_MINUTE
  and SLEEP_MINUTE carry local date + time with no offset. Local times are read in the
  owner's timezone [CHECK: wrong for days spent in another timezone; the export doesn't say].
- SLEEP_MINUTE's `date` is one day later than its night: rows dated D fall inside the night
  SLEEP files under D−1 (first and last minutes match the UTC night to the minute on the
  nights checked). Each minute is placed at D−1 (D−2 for times from 18:00, for nights that
  start before midnight) and kept only if it lands inside a UTC night; others are dropped.
- Zepp writes placeholder nights with start = stop; nights under 30 min are skipped.
- 0 and −1 mean "not measured" in numeric columns.
- No HRV, SpO₂, skin temperature or stress in the export: recovery's history rests on resting
  HR and sleep until the strap's own data accrues.

Workout types stay Zepp's numbers in `workout.sport`; the mapping to Ridge's sports is
applied when read, so a corrected mapping needs no re-import.

Mapping (`sessions.ZEPP_SPORTS`), confirmed by the owner against his own sessions: 17 tennis,
52 gym, 54 stairs, 8 treadmill, 6 walk; anything else shows as "other" with its code kept.
[CHECK: that the strap's own workout records (`0x05`, spec/01 §7.2) use the same type codes
as Zepp's export; likely, since Zepp's records come from the strap, but not yet seen live.]

## S6 · Calories by source (read time, `read/calories.py`)

**Ours.** The Today screen shows the day's calories as a base plus what was added on top
of it, split by source. No new science: it is spec/02's energy model (`derive/energy.py`)
walked the same way, minute by minute, with each minute's energy filed by what the minute
was. Nothing is stored; it is computed when the summary is read.

```
bmr_min    = basal_calories / 1440                         (the day's Mifflin-St Jeor BMR)
base       = bmr_min × minutes walked                      (resting energy)
steps      = Σ over minutes with steps      (MET − 1) × bmr_min   (ACSM walking/running)
movement   = Σ over other non-workout minutes (MET − 1) × bmr_min
             (awake NEAT 1.3 / 1.55, and asleep 0.95, which is slightly under base)
workouts   = the strap's workout calories − bmr_min × workout minutes
total      = base + steps + movement + workouts
```

`MET` is `energy._minute_met`, the model's own function, and the inputs are the stored
`basal_calories` and the `stride_m` on `total_calories`, so for a finished day `total`
equals the stored `total_calories`. Workout minutes are skipped by the walk and counted from
the strap's own calories, exactly as spec/02 does; the base share of those minutes is taken
off so it isn't counted twice.

**A day still running is walked up to the current minute only.** The stored
`total_calories` for today walks the whole day and so counts the hours still to come as
seated time; a tracker can't show those as burned. The card says it covers "so far", and
gives that stored total beside it as the day's estimate (`day_estimate`): where the day
ends up if the rest of it is quiet, seated or asleep.

Withheld, as the other calorie cards, when the profile or weight is missing
(`profile_or_weight_missing`) or the day isn't derived yet. The weight caveats and the
"workout without calories" caveat ride along from `total_calories`.
