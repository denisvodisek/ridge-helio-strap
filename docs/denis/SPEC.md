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
