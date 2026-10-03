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
