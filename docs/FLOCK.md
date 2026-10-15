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
| FLOW | Neighbor velocity alignment and response |
| SCATTER | Initial disturbance, scaled by playing velocity |
| EDGE | Second- and third-harmonic carrier content |
| HOLD | Finite duration from two to six seconds; **never a sustain loop in model 1** |

`FlockPatch` stores MIDI 36–84, velocity 0–1, model 1 and an exact signed 64-bit
seed alongside the six normalized macros. The seed is a canonical decimal JSON
string because the shared numeric JSON representation uses `Double`. Identical
recipes reproduce identical samples. A new seed produces another performance;
ordinary playback repeats the rendered performance.

Patches participate in `Patches`, `PadRecipe` and physical velocity rendering.
The CLI exposes the provisional recipes without adding them to `Presets.all()`:

```sh
./gradlew :cli:run --args='synth FLOCK GATHER --all --midi 60 --velocity .7 --seed 41001 --out /tmp/flock'
./gradlew :synth:generateFlockAudition
```

The audition writes to `testkit/flock-audition/`, including raw and matched
mono PCM16 WAVs, recipes, trajectories, source snapshots and hashes, a manifest,
and separate listening archives. Its frontend source lives under
`synth/src/test/resources/audition/flock-audition/`.

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
