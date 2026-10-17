# FLOCK — finite oscillator-flock proof

FLOCK adapts the abandoned XO-Oceanic swarm proposal into SnipSnap's deterministic
offline synthesis path. Twenty-four directly audible oscillators move in
logarithmic pitch and an energy coordinate. Separation, velocity alignment and
cohesion operate over changing local neighborhoods, while PULL restores each
oscillator toward its played pitch or harmonic center.

This is a listening prototype. The voice names and macro calibrations are
proposals, and numerical checks do not establish that their interaction is
musically useful. The [private audition](https://flock-snipsnap-audition.bertcalm.chatgpt.site)
contains real Kotlin-rendered WAVs, measured trajectories, and matched comparisons.

## The three gestures

| Voice | Intended listening question |
| --- | --- |
| GATHER | Does the scattered opening draw into a recognizable note? |
| RIBBON | Does neighbor interaction produce a useful collective pitch shimmer? |
| SPLIT | Do the root, fifth and octave groups form a coherent moving harmonic sound? |

SPLIT assigns eight particles to each of three harmonic centers. It does not
promise that those centers merge or exchange members. Every voice uses the
same simulation and oscillator bank. Nine provisional presets remain outside
the factory roster and phone picker pending a listening verdict.

## Playing and saving

| Control | Purpose |
| --- | --- |
| SPREAD | Initial pitch range and bounded particle separation |
| PULL | Restoration toward the requested note or harmonic center |
| FLOW | Pace of the group's motion and neighbor velocity alignment |
| SCATTER | Initial disturbance, scaled by playing velocity |
| EDGE | Second- and third-harmonic carrier content |
| HOLD | Finite duration from two to six seconds; **never a sustain loop in model 1** |

`FlockPatch` stores MIDI 36–84, velocity 0–1, its rendering model and an exact signed 64-bit
seed alongside the six normalized macros. The seed is a canonical decimal JSON
string because the shared numeric JSON representation uses `Double`. Identical
recipes reproduce identical samples. A new seed produces another performance;
ordinary playback repeats the rendered performance.

### FLOW revision after the first listen

The original FLOW mapping mainly damped differences between particle velocities.
Its published sweep had nearly identical gathering times, and little change in
the upper half of the control. Model 2 gives FLOW a clock for the existing flock
equations: 0.12 times normal speed at zero, rising to four times at one. Each
voice's original default FLOW is an exact normal-speed anchor, preserving that
default sound sample-for-sample. The neighbor forces and their existing FLOW
weights retain their original formulas. Pitch, carriers and the audio envelope
are not resampled; HOLD duration and the one-second diagnostic impulse stay on
the ordinary playback clock.

Saved model-1 patches still render with the original equations and normal-speed
clock. Tests reproduce the originally published FLOW and default WAV hashes.
New patches select model 2. The original audition generator explicitly renders
model 1 so it remains a reproducible record of the first listening round.

The updated audition preserves the other 46 listening clips unchanged and puts
the five revised FLOW settings beside their original versions. Its mixed-model
manifest identifies each source. This revision is ready to listen to; measured
motion differences do not establish perceptual acceptance.

Patches participate in `Patches`, `PadRecipe` and physical velocity rendering.
The CLI exposes the provisional recipes without adding them to `Presets.all()`:

```sh
./gradlew :cli:run --args='synth FLOCK GATHER --all --midi 60 --velocity .7 --seed 41001 --out /tmp/flock'
./gradlew :synth:generateFlockAudition
./gradlew :synth:generateFlockFlowAudition -PflockBaseline=/absolute/path/to/flock-audition
```

The audition writes to `testkit/flock-audition/`, including raw and matched
mono PCM16 WAVs, recipes, trajectories, source snapshots and hashes, a manifest,
and separate listening archives. Its frontend source lives under
`synth/src/test/resources/audition/flock-audition/`.

The focused FLOW task reads a preserved first-flight pack and writes
`testkit/flock-flow-audition/`, retaining its original audio, recipes and source
snapshots alongside the revised FLOW files. Preserve the baseline directory
before regenerating or replacing it.

## What the comparison removes

FLOCK, FIXED and INDEPENDENT begin with the same oscillator phases, note,
seed, initial particle positions and finite duration. FIXED freezes those
positions. INDEPENDENT retains each particle's drive and pitch restoration
while removing neighbor forces. EDGE and the oscillator synthesis stay shared.

The causal comparison injects one pitch-velocity impulse into particle zero
at one second. Its off/on partners use one common gain, preserving their
identical opening. State trajectories record the response of other particles;
the independent pair checks that the impulse does not reach unrelated agents.
The symmetric decimator can introduce a small audio pre-ring before the
injection time, so the exact audio-prefix check leaves a documented filter
margin. The simulation itself remains causal.

Matched files use fixed linear gain toward loudest-200-ms RMS 0.12, with a
0.90 peak ceiling. Raw files preserve the bank's conservative synthesis gain.
These are listening comparisons, not a dynamics processor or added effect.

## Numerical and host contracts

- Synchronous state updates at 420 Hz; every particle reads the preceding snapshot.
- Each receiver selects up to four nearest particles inside its radius. The
  directed graph is recomputed from the same snapshot; impulse diagnostics retain
  the reference and disturbed graphs to distinguish direct and indirect response.
- Softened separation, explicit time steps, exponential damping, bounded speeds
  and finite position ranges. Forces use the same pitch/energy metric as distance.
- Interpolated log-pitch and energy drive a sine carrier with two bounded upper
  harmonics. Audio renders through the existing four-times path to 44.1 kHz mono.
- A conservative coherent-sum gain budget precedes the shared loudness stage.
  Velocity zero is silent; positive velocity changes initial disturbance and level.
- Cancellation is checked while rendering. No live native synthesis or shared
  state across separately rendered notes is introduced.
- No attack-plus-loop or looping claim. Sustained recurrence and captured-sample
  carriers remain future design work.

Focused tests cover seeded replay, strict patch decoding, local causal response,
comparison isolation, macro authority, velocity, register extremes, cancellation,
recipe replay, preset promotion and CLI output. Target-phone render latency and
the owner's sonic verdict remain unmeasured.
