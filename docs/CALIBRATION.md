# Calibration — feeding the classifier real truth

The classifier is rules, not a model, on purpose: instantly fast, no
model file, wrong in *explainable* ways. Its thresholds were tuned on
synthetic renders and are re-calibrated against real captures as they
arrive. This file names the two on-ramps.

## The labeled corpus: `reference/calibration/`

Drop isolated one-shot recordings here, named by what they are:

```
reference/calibration/kick 01.wav
reference/calibration/kick phone far.wav
reference/calibration/snare 01.wav
reference/calibration/hatclosed 01.wav
```

The first word of the filename is the label — one of `kick`, `snare`,
`clap`, `hatclosed`/`closedhat`, `hatopen`/`openhat`, `tom`, `perc`,
`tonal`, `loop`. The calibration harness
(`CalibrationCorpusTest`) classifies every file, prints a per-file
report with the feature numbers a threshold gets moved by, a confusion
matrix, and an accuracy line — and fails under 60%, the "broken on
real material" floor. An empty folder is an honest skip, not a
failure.

The most valuable recordings are **each drum alone**: the Live III
playing just its kick, just its snare, and so on — once close to the
phone and once across the room. That turns threshold *reasoning* into
threshold *fitting*.

## The capture profile: context, not thresholds

The first real capture (`reference/live3 room take.wav`) taught the
wave-TT lesson: a phone across a room rolls off the sub (that capture
kept **0.09%** of its low band below 150 Hz, against 80%+ for
full-range material), and a kick judged without knowing that files as
a snare — its identity is intact to the ear, its `lowRatio` evidence
gone.

The fix is context, not looser thresholds. `CaptureProfile.measure`
reads the whole capture once; only a *provably* rolled-off capture
changes anything. Under such a profile, a hit the rules shelved as
PERC is re-judged by what survives the mic — dark centroid (the real
kicks' knock clusters at 350–670 Hz), low high-band share, near-tonal
flatness — and its decay says which sound it was: punchy is a KICK,
sustained is a TONAL note, the gap between stays honestly PERC.
Confidence is capped sub-certain (0.5–0.7): the profile argues, it
does not testify.

`chop` measures the profile automatically and says so:

```
phone capture heard: sub rolled off (0.1% of the low band below 150 Hz) - kicks judged by shape
```

A permanent phone-simulation corpus (synthetic renders through a
~24 dB/oct highpass, labeled by construction) guards the rule in
`CalibrationCorpusTest`, so it cannot regress while waiting for more
real captures.

## Lessons the fixtures taught (kept so they stay taught)

- A pure-sub synthetic kick **cannot** reproduce the phone failure: a
  highpass only quiets it — its spectral *shape* stays all-low.
  Realistic kick fixtures carry the knock and click a real drum does.
- Real phone chains roll off far steeper than one filter stage
  (~24 dB/oct equivalent); the reference capture kept a thousandth of
  its sub.
- A sustained low note held for seconds keeps enough sub through any
  realistic rolloff that a mix containing one can read full-range —
  which is why the profile is *measured from the capture*, never
  assumed from the device.
- The attack-burst gate belongs to the kick alone: a sustained low
  note's own cycles read as "bursts" and mean nothing about it.
