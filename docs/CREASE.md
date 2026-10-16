# CREASE — first listening prototype

CREASE is an original, offline pitched engine inspired by phase-distortion synthesis.
Its gesture is bend → crease → unfold: pressure changes two periodic phase maps,
and the maps remember and release that pressure at different rates.

This milestone proves three sounds: ROUND, PINCH and RUBBER. Numeric voicing is
provisional. The six-voice/36-preset factory roster and production app, patch,
kit and keyboard-instrument registration remain later work, after listening.

## Controls and performance

The requested MIDI note and velocity are render arguments, leaving the controls
for FOLD, PRESSURE, DRAG, SPLIT, RELAX and HOLD. FOLD sets the base geometry;
PRESSURE sets its excursion; DRAG delays its response; SPLIT separates the two
layers' shapes and timing; RELAX sets their recovery. HOLD's top setting renders
a recurring pressure gesture as a sustained loop.

The prototype accepts MIDI 24–84. The automated pitch matrix covers six notes
across that range; the audition grid covers MIDI 36, 60 and 84 at three
velocities. Higher-register expansion requires another probe pass.

The two oscillators share the requested root. Deformation changes the reading
of each waveform within a cycle. The initial map is a smooth sinusoidal basis
with `sum(k * abs(c[k])) <= 0.9`, guaranteeing a positive derivative floor of
0.1. This bounded family is the first sound experiment; dense tearing is not
an established result. No phase reversal, noise assets or external samples are
used.

The reciprocal detector measures waveform shape before output leveling. The
STATEFUL/INSTANTANEOUS and coupling/independent render modes expose mechanism
comparisons while retaining the same excitation and amplitude envelopes.

## Reproduce the audition

From the repository root:

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.CreaseTest
./gradlew --no-daemon :synth:generateCreaseAudition
```

The generator writes real Kotlin-engine mono WAVs, recipes, control traces and
diagnostics under `testkit/crease-audition/`. Browser playback uses those files;
the browser does not implement a second synthesis model.

The listening matrix includes voice defaults, five-step timbral control sweeps,
history and coupling comparisons, pitch/velocity coverage, interacting controls,
all-high settings, higher-rate references and held loops. Matched files use the
repository's common audition loudness. Separate prelevel files retain original
render dynamics. The JSON files are standalone audition recipes, not registered
SnipSnap production Patch objects.

The first verdict belongs to listening: pressure should crease the tone, drag
should reveal lag, split should reveal differing shapes, and relax should make
their return audible. Numerical checks establish repeatability, bounds and
loop readiness; they do not establish musical acceptance.

The first validated build passed 12 focused tests. Its 54 pitch cases measured
at most 0.223 cents of root error. Default/all-high bounds stayed below a 0.705
weighted coefficient budget with a measured derivative floor above 0.295.
The three default held loops converged in 3–4 periods, with full-state error
below 8e-13 and zero measured float/PCM24 recurrence seam error. The audition
generator separately checks every exported held comparison at PCM16 precision.

## Delivery and integration

`Crease.kt` is the engine, `CreaseTest.kt` checks its contracts, and
`CreaseAuditionGenerator.kt` generates the evidence. The audition site's static
source is kept in its own Sites source repository.

When the prototype's sound is accepted, use the explicit MIDI/velocity recipe
pattern in `TanglePatch.kt`. Full integration also needs `Patches`, `Presets`,
`Velocity`'s native renderer, `SynthKits`, shell starter visibility, CLI overrides
and the phone's engine adapter. The generic classifier files long finite sounds
as LOOP by length, so engine filing should distinguish finite TONAL from actual
HOLD loops explicitly.

The JVM gate is `./gradlew --no-daemon test` when the Android SDK is absent.
CI also builds Android separately; JVM success alone cannot validate future
phone UI wiring.
