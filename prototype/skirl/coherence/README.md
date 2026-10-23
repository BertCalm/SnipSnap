# Skirl contact-coherence experiment

Measured with the Python harness, not Kotlin. The positive-contact WHIRR
prototype produced strong contact-comb components beside the fixed musical
root. This experiment changes the acoustic contact polarity while preserving
the mechanical trajectory, phase-crossing event times, assigned resistance-work
budgets and disk/support mode tables. It adds no root oscillator or new clock.

The candidate is `rim1024`: a 1024-entry periodic map made with the native
`Dsp.seedFor("SKIRL", 1, "WHIRR")` hash and `Dsp.Noise` recurrence, including
Float rounding. Each existing event linearly samples that map at its measured
body-frame rim phase. The sample's sign selects the contact pulse's polarity.
The map is an authored acoustic surface readout, not a mechanical force or a
validated material measurement. The terminal inputs use the last valid rim
phase, without extending rolling mechanics beyond the cutoff.

Both routes share the same event budget. For signed pulses, the accumulated
source energy changes by `q*a*a + 2*c*a`; the positive quadratic root is capped
at the original `sqrt(budget)` coefficient. Negative overlap cannot boost a
contact to spend the remainder. The post-filter source still withdraws to zero
at REST.

The WHIRR fixture starts at 8 degrees with resistance coefficient `.0044` and
exits at 1.96 degrees. EDGE is `.4`, PLATE is `.5`, and velocity is `.25` or `1`.
Pitch is measured without restricting the detector to the expected root, in
300 ms windows beginning at `.25 s`, `.75 s`, half the rolling duration and
350 ms after terminal onset. The output stage uses the reference harness's
RMS proxy and approximate decimator.

| Contact readout | Worst absolute error across C2/C3, both velocities and all four windows |
| --- | ---: |
| Positive pulses | 140.3 cents |
| Signed rim map, 256 entries | 43.2 cents |
| Signed rim map, 1024 entries | 23.9 cents |
| Deterministic per-event polarity control | 11.0 cents |
| Bipolar pulse alone | 87.9 cents |
| Seeded finite friction grains | 32.5 cents |

The 1024-entry candidate's minimum confidence is `.826`. In soft C2's first
window, the original dominant spectral peaks are 55.52 and 69.98 Hz; the
candidate's strongest peak is 65.95 Hz, close to the requested 65.41 Hz. The
joint source energy remains within assigned budgets for every variant.

`report.json` preserves the original measured report byte-for-byte;
`provenance.json` records its hash and the original source hash. The portable
source differs only in helper lookup and output-path arguments.

Run from the repository root after installing `prototype/skirl/requirements.txt`:

```sh
python3 prototype/skirl/coherence/experiment.py --report /tmp/skirl-coherence-report.json
```

No WAVs are written by default. Optional `--audio-dir /tmp/skirl-coherence-audio`
writes comparison clips outside the source tree. Native Kotlin render, pitch,
aliasing and listening acceptance remain separate checks; these Python
measurements do not establish them.
