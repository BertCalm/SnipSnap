# SPACE revision 2: earlier body-color movement

This is a listening candidate for the existing finite physical-morph prototype.
It uses SnipSnap commit `84471534aef0182f9098c38374a62a5aa0899244`.
No production engine, presets, HOLD or application registration is introduced.

The gesture is a picked attack → early dark–bright–dark color movement →
settling body color → source release and decay. The readout is a designed
trajectory, not a simulated rotating body or acoustic room.

## Why it is built this way

- The first excursion lasts 90 ms and peaks at 45 ms, placing the color change
  in the part of the original clips that carries most of their energy.
- A narrower dark readout and a stronger bright readout give the trajectory more
  contrast. Both read the same waveguide; neither feeds it or changes its tuning.
- The direct guide component retains the root. Dark bandwidth follows pitch so
  the low and high registers remain comparable.
- SPACE uses a square-root depth curve to give lower settings useful travel.
  Zero follows the original static path exactly, including the original output.
- The motion returns smoothly to the MODEL center by 180 ms. Source-off remains
  at 550 ms and the total clip remains 1.4 seconds.

## Comparison controls and evidence

The comparison contains original and revised mappings at five SPACE settings
(0, 0.25, 0.5, 0.75, 1) and MIDI 48, 60, 72. Other parameters are MODEL 0.4,
STIFF 0.6, BRIGHT 0.5 and velocity 0.7. The seed remains the original seed.

`raw/` holds unlevelled conditioned renders. `matched/` applies one scalar per
complete clip to reach a common RMS across all 30 takes. The common target is
lowered once if any take would exceed 0.9 peak. There is no compression,
time stretching or normalization of individual time windows. These comparison
levels differ from the original audition's house-levelled clips; its archive
and all existing audio paths remain intact.

The six revision tests and 36 existing StringsTest tests passed. Regression
checks reproduce all 86 published original WAVs byte for byte. The revision
checks 50 parameter-edge cases, silence at zero velocity, nonfinite SPACE
fallback, determinism, unchanged guide diagnostics and exact replay of all
30 comparison recipes.

The motion check compares moving output against the same revised readout frozen
at its starting position. In three early windows it measures high/low spectral
energy ratio. A change of more than 3 dB across those windows is required at
SPACE 0.25, 0.5 and 1; a gain-only counterexample must fail this criterion.
This establishes time-varying color, not subjective audibility or approval.

`verification.json`, `metrics.csv`, `corner-metrics.csv` and `motion-checks.csv`
contain the fresh results. The pitch gate remains 35 cents. Timings are cloud
JVM renders, not Android performance. No new higher-rate alias-reference test
or full application-suite claim is made.

## Replay

Comparison recipes use renderer `morph-space-r2`, schema version 2, an explicit
`original` or `revised` variant and `raw_gain` for exact comparison-level replay.
The existing version-1 recipe replay remains supported by its original task.

```sh
cd /workspace/SnipSnap
GRADLE_USER_HOME=/workspace/.gradle bash ./gradlew --no-daemon --max-workers=3 \
  -I /workspace/neuron-z1/prototype.init.gradle :synth:replayMorphSpace \
  -PmorphRecipe=/workspace/neuron-z1/space-r2/recipes/revised-60-1.0.json \
  -PmorphOutput=/tmp/space-revised.wav
```

Use a new output filename and run Gradle invocations serially. The comparison ZIP
includes the renderer and tests under `source/`; it requires the pinned SnipSnap
toolkit to build.

## Listening verdict

Is motion clear at 50% and 100%, while still reading as one played object?
Does the stronger body-color excursion instead read as a filter effect?
Josh's original report was that SPACE appeared to do nothing. The revised
candidate remains unapproved until he listens.
