# CREASE — second listening prototype

CREASE is an original, offline pitched engine inspired by phase-distortion synthesis.
Its gesture is bend → crease → unfold: pressure changes two periodic phase maps,
and the maps remember and release that pressure at different rates.

The first audition was rejected by its owner for sounding too similar. Harmonic
power analysis confirmed that the three defaults placed about 99% of their
harmonic energy in the fundamental. Small numerical changes passed the original
checks without establishing useful timbral variation.

Model 2 broadens the phase maps themselves. It explores ROUND, PINCH and RUBBER;
voicing remains provisional. The six-voice/36-preset factory roster and production
app, patch, kit and keyboard-instrument registration remain later work.

## Controls and performance

MIDI pitch and velocity are render arguments, leaving FOLD, PRESSURE, DRAG,
SPLIT, RELAX and HOLD as the six controls. FOLD sets phase geometry; PRESSURE
sets its excursion; DRAG delays its response; SPLIT separates layer shapes and
timing; RELAX sets their recovery. Finite HOLD extends the gate; its top setting
renders a recurring pressure gesture as a sustained loop.

The supported range is MIDI 24–84. The two oscillators share the requested root.
Phase mapping changes waveform shape within each cycle, with no detune or phase
reversal. No samples or effects are required.

## Two saved synthesis models

Model 1 is preserved in `CreaseV1.kt` for exact old recipe replay and comparison.
It uses a short Fourier warp with `sum(k * abs(c[k])) <= 0.9`, guaranteeing
`0.1 <= w'(phase) <= 1.9`. That deliberately limited family proved too restrained
in the first listening test.

Model 2 uses smooth positive analytic phase maps with wider compression:

- ROUND uses a full-cycle Möbius map, moving from a rounded wave toward a rich
  all-harmonic shape.
- PINCH uses a half-cycle elliptical map with odd-harmonic narrow pulses. A
  bounded lead between fast deformation and slow recovery makes a push pinch
  ahead of equilibrium and open again as pressure falls.
- RUBBER composes the two maps so pressure and recovery move harmonic lobes and
  notches as well as pulse width.

Each map is periodic and monotonic. Its derivative has a positive analytic bound;
total absolute log compression is capped at 3.6 through C4 and gradually reduced
to 2.85 at C6, constraining the sharper shapes. The model-1
Fourier coefficient budget does not apply to model 2. Diagnostics therefore report
that budget only for model 1 and report map slope and curvature separately.

The shape-sensitive reciprocal detector runs before output leveling. STATEFUL /
INSTANTANEOUS and coupled / independent probes retain identical pressure and
amplitude schedules. Every held boundary check includes deformation, detector,
filter, DC and decimator memory; the preceding audio period is evolved separately.

Public `render`, `probe` and `synthesize` calls default to model 2 and accept an
explicit `model`. New audition recipes save that field. Old recipes without it
select model 1, preserving the first audition's behavior.

## Reproduce the audition

From the repository root:

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.CreaseTest
./gradlew --no-daemon :synth:generateCreaseAudition
./gradlew --no-daemon :synth:generateCreaseAudition -PcreaseFull
```

The default compact audition writes to `testkit/crease-audition-r2/`: three voice
examples, three matched model-1/model-2 pairs, three-step control sweeps, two
registers, held loops and 4×/16× reference pairs. `-PcreaseFull` adds quarter steps,
velocity coverage, mechanism comparisons, interactions and held RELAX probes.
The first audition's output directory is retained separately.

Browser playback uses these Kotlin-engine WAVs. Matched files use the common
audition loudness; separate prelevel WAVs retain original dynamics. The JSON files
are standalone audition recipes, not registered production SnipSnap Patch objects.

Replay a downloaded recipe with:

```sh
./gradlew --no-daemon :synth:generateCreaseAudition -PcreaseRecipe=/absolute/path/recipe.json -PcreaseOutput=/absolute/path/output
```

## Listening and verification

The owner's listening verdict remains the acceptance gate. The revision checks
require substantial gain- and phase-independent harmonic differences between
voices and over time, alongside pitch, aliasing, deterministic replay, numerical
bounds and independently measured held seams. Original model 1 must fail the new
contrast checks, so tiny spectral differences cannot stand in for useful variation.

Pressure should audibly crease the tone; DRAG should reveal lag; SPLIT should
expose differing shapes; RELAX should make their return audible. Numerical
contrasts describe the rendered sound and do not establish musical acceptance.

## Delivery and integration

`Crease.kt` implements model 2; `CreaseV1.kt` preserves model 1. `CreaseTest.kt`
checks the contracts, and `CreaseAuditionGenerator.kt` generates the evidence.
The audition site's static source is kept in its own Sites source repository.

After listening acceptance, follow the explicit MIDI/velocity recipe pattern in
`TanglePatch.kt`. Full integration also needs `Patches`, `Presets`, native
`Velocity` routing, `SynthKits`, shell starter visibility, CLI overrides and the
phone's engine adapter. Generic classification files long finite sounds as LOOP
by length; finite TONAL filing and actual HOLD readiness need separate handling.

The full production JVM gate is `./gradlew --no-daemon test` without an Android
SDK. CI builds Android separately; JVM checks cannot validate future phone UI
wiring. This listening revision uses focused engine, replay, audio and browser
checks; it does not claim a completed repository-wide regression run.
