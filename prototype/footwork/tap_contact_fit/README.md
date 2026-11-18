# FOOTWORK: owner-reference contact study

Status: isolated synthesized toe/heel **candidates**, awaiting the owner's ear.
Read against repository `43fc60525d0c9b307e868dce085572d4c3989729`.
No new groove, Kotlin model, native kit, or export is introduced here.

## Reference evidence

All five owner uploads were decoded and measured at mono 44.1 kHz:

| Reference | Upload timestamp | Duration | Observed context |
|---|---|---:|---|
| 1 | 20261116-083341 | 11.19 s | Close, low-angle solo dancer |
| 2 | 20261116-083407 | 6.73 s | Three-person rehearsal |
| 3 | 20261116-083453 | 11.42 s | Larger dance group |
| 4 | 20261116-083528 | 14.03 s | Audio only; context unverified |
| 5 | 20261116-083703 | 15.05 s | Audio only; context unverified |

`analysis.json` records candidate onsets, broad-band energy and 120 ms energy
decay measurements for all five. Automatic onsets are not labeled shoe contacts.
Group recordings, background signals, room response, codec and capture gain
confound physical interpretations. Reference 1 has decoded peaks above 1.0;
its level is not a calibration of force. No claim of listening/transcription.

The solo recording offers more separated events for this first source rebuild.
Three mid-heavy contacts supply the intended toe candidate; three low-heavy
contacts supply the intended heel candidate. They are selected by spectrum and
spacing, **not verified anatomical toe/heel labels**. Three disjoint events per
group are held out. References 2–5 are contextual measurements, not fit data or
a source of additional labeled training hits. Precise training/holdout times are
in `model.json`; the recording's room/floor contribution cannot be separated.

## Why it is built this way

- A 64-point average magnitude spectrum generates one minimum-phase contact
  filter. It replaces the previous independent band sources plus added low body.
  Removing it would lose the measured middle-frequency body and response shape.
- Fresh noise and a small coherent pressure component drive that same filter.
  The runtime contains no recording PCM, captured phase or sampled impulse
  response. It still risks a noise-like texture; measurements cannot settle that.
- Two fitted envelope lobes plus a fixed 32 ms tail basis spread energy beyond
  the initial spike. A second lobe is a model of the envelope, **not proof** of a
  second anatomical contact. Room/floor decay can enter these fitted parameters.
- Velocity changes amplitude and slightly changes response frequency/time;
  seeded repeats change excitation, response and envelope. One fixed gain covers
  the complete two-voice grid, preserving dynamics instead of leveling each hit.
- The 4× source passes the Python house band-limit/decimation/DC/tail stages.
  Roughly 12 ms per render in this run; filter caching is optional at this size.
  This is not a Kotlin timing or numerical-parity measurement.

## Measurements and limits

For hard take 0 over the first 120 ms, toe energy in 250–800 Hz changes from
20.6% to 60.4%; time to 90% energy changes from 25.3 to 36.2 ms. Heel crest
factor changes from 20.9 to 14.8 dB and time to 50% energy from 5.6 to 12.9 ms.
These describe a fuller sustained contact envelope, not established realism.

Mean Jensen–Shannon distance on the five broad-band fractions against held-out
same-recording events changes from 0.496 to 0.202 for toe, 0.084 to 0.064 for
heel. Lower is closer on this narrow metric only. The displayed toe reference
still has a longer decay, and the heel reference still has more low-frequency
energy than its candidate. The fit does not recreate the recordings exactly.

The owner-private audition presents reference/new/previous at equal energy over
the first 120 ms, with a common headroom reduction per comparison. This is not
perceptual loudness matching. Separate 12-hit strips retain source gain:
soft four takes, medium four, hard four. Every candidate is 240 ms with 160 ms
between dynamics hits. Older rendered audio is used only for comparison.

Verified in Python: 24 cells, deterministic rerender, velocity-zero silence,
finite output, peak <= 0.82, DC bound, zero tail, monotonically increasing
velocity energy, distinct repeats before and after PCM16 saving, decoded strip
ordering and quantization error below 5e-5. `report.json` contains measurements.
This does not exercise native selection/playback/export. The MPC 3 chain-WAV
slice-map blocker and all outstanding instrument acceptance gates remain open.
Six retained Street/Industrial phrase hashes are unchanged.

The supplied media are private reference material with no assumed redistribution
license. They are not committed here. Only short comparison excerpts appear on
the existing owner-private Site. Numeric fit parameters and analysis are stored;
rendering from `model.json` does not need the recordings.

## Reproduce

Requires NumPy, SciPy and Matplotlib. Decode the five private recordings as mono
float32 44.1 kHz `ref-1.wav` through `ref-5.wav` in a local directory. Then:

```sh
python3 prototype/footwork/tap_contact_fit/analyze.py /private/decoded /tmp/contact-fit
python3 prototype/footwork/tap_contact_fit/build.py /private/decoded /path/to/prior-tap-impact /tmp/contact-fit
```

To synthesize without any recording, import `render` from `build.py` and call
`render(json.load(open('model.json')), 'toe', .65, 0)` (or `'heel'`). This returns
mono float audio at 44.1 kHz. Reference fitting and comparison export are separate
from that render function. Files are an audition study, not a release package.
