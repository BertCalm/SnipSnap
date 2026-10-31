# FLOCK — finite oscillator flocks

FLOCK adapts the abandoned XO-Oceanic swarm proposal into SnipSnap's deterministic
offline synthesis path. Twenty-four directly audible oscillators move in
logarithmic pitch and an energy coordinate. Separation, velocity alignment and
cohesion operate over changing local neighborhoods, while PULL restores each
oscillator toward its played pitch or harmonic center.

The finite engine is available in SYNTH, the factory preset roster and FRESH TAPE.
The owner confirmed that revised FLOW is audible. Target-phone playback, render
latency and MPC hardware checks remain open. The
[private audition](https://flock-snipsnap-audition.bertcalm.chatgpt.site) contains
real Kotlin-rendered WAVs, measured trajectories and matched comparisons.

## The three gestures

| Voice | Intended listening question |
| --- | --- |
| GATHER | Does the scattered opening draw into a recognizable note? |
| RIBBON | Does neighbor interaction produce a useful collective pitch shimmer? |
| SPLIT | Do the root, fifth and octave groups form a coherent moving harmonic sound? |

SPLIT assigns eight particles to each of three harmonic centers. It does not
promise that those centers merge or exchange members. Every voice uses the
same simulation and oscillator bank. Nine factory presets retain their saved
seeds and model-2 controls.

## Playing and saving

| Control | Purpose |
| --- | --- |
| SPREAD | Initial pitch range and bounded particle separation |
| PULL | Restoration toward the requested note or harmonic center |
| FLOW | Pace of the group's motion and neighbor velocity alignment |
| SCATTER | Initial disturbance, scaled by playing velocity |
| EDGE | Second- and third-harmonic carrier content |
| HOLD | Finite duration from two to six seconds; **never a sustain loop** |

`FlockPatch` stores MIDI 36–84, velocity 0–1, its rendering model and an exact signed 64-bit
seed alongside the six normalized macros. The seed is a canonical decimal JSON
string because the shared numeric JSON representation uses `Double`. Identical
recipes reproduce identical samples. A new seed produces another performance;
ordinary playback repeats the rendered performance.

SYNTH exposes ROOT (C2–C6) and VELOCITY beside the six macros. Macro edits,
SCRAMBLE, preset saving and SEND TO PAD retain the saved root, velocity, seed
and model; SCRAMBLE also retains HOLD. SPREAD uses the saved root when tuning
the rendered sound across pads, including SPLIT's harmonic groups. FLOCK's
phone controls create finite pads. RIBBON also has an explicit saved HELD choice
for captured-sustain instruments; HOLD itself keeps its finite meaning. See
[the held instrument design](FLOCK_RIBBON_INSTRUMENT.md) for playback and exports.

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
manifest identifies each source. The owner confirmed the revised FLOW difference
was audible; phone and MPC hardware validation remain separate checks.

Patches participate in `Patches`, `PadRecipe` and physical velocity rendering.
The CLI and SYNTH share the nine recipes registered in `Presets`:

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

## Factory kit and exports

FRESH TAPE's fixed FLOCK starter uses `SynthKits.flock()`. A01–A08 play GATHER
up C minor pentatonic from C4 to F5 with the same saved seed; A09–A16 contain
the other eight factory recipes. All sixteen pads are dry TONAL one-shots with
no choke group. Each recipe retains its exact note, velocity, seed and model.

Build the same kit for SnipSnap and both MPC generations:

```sh
./gradlew :synth:generateFlockKit
```

The default output layout is:

```text
testkit/
  SnipSnap Flock Kit/
    SnipSnap Flock Kit.xtd
    SnipSnap Flock Kit_[TrackData]/
      SnipSnap Flock Kit.xpm
      kit.json
      *.wav                     (16 shared mono PCM24 samples, 44.1 kHz)
    recipes/A01.json … A16.json
    manifest.json
    README.txt
  SnipSnap-Flock-Kit.zip
  SnipSnap-Flock-Kit.zip.sha256
```

MPC 3 opens the `.xtd` beside its `_[TrackData]` folder. MPC 2 opens the `.xpm`
inside that folder. Its `kit.json` preserves an editable SnipSnap kit; the
separate recipes and manifest record every pad's state and file hashes.
Use `-PflockKitOutput=/path/to/output` to choose another output root.
HOLD remains finite in every pad of this starter kit.

## Playable RIBBON instrument

RIBBON's separate FINITE/HELD choice stores playback, capture version and
0.1–1.5-second release with the complete source patch. HELD keeps a two-second
arrival, then repeats a roughly six-second captured movement. The phone's native
player and exported keygroups release from their current loop phase after
note-off. The flock equations themselves do not become periodic.

In SYNTH, choose RIBBON, select HELD and use HOLD TO AUDITION. RELEASE changes
the player envelope without rebuilding the capture. MAKE INSTRUMENT renders nine
closely spaced roots, retaining the saved model, seed and source velocity. SEND
TO PAD makes a one-shot with a recorded end fade and keeps the held recipe editable.
The held audition and instrument are dry. Hardware playback remains a listening check.

MAKE INSTRUMENT now also offers TOUCH LAYERS, on by default when opening FLOCK's
MAKE sheet. Three source disturbances per root respond to soft, medium and hard
key touches. The saved velocity remains the hard-touch anchor; single-layer MAKE
and CLI exports remain available. See [the touch-layer design](FLOCK_RIBBON_TOUCH.md)
for sustain matching, MIDI windows, provenance and listening checks.

Generate the default C3–C5 instrument and reference-player listening evidence:

```sh
./gradlew --no-daemon :shell:generateFlockHeldInstrument
./gradlew :cli:run --args='synth FLOCK RIBBON --instrument --release .6 --out /tmp/ribbon'
```

The first command writes `testkit/flock-held-instrument/` with
`SnipSnap-Flock-Ribbon-Held.zip`, a native MPC 3 `.xty`, the MPC 2 `.xpm` twin,
the phone `.instrument.json`, loop-marked PCM24 WAVs, the editable `.patch.json`,
measurements and checksums. Source-velocity listening clips use separate captures;
keyboard touch clips change playback gain on the same rendered source. Use
`-PflockHeldOutput=/absolute/path` to choose another output directory.

See [the held instrument design](FLOCK_RIBBON_INSTRUMENT.md) for the cache,
capture, state, pitch and validation contracts.

## What the comparison removes

The separate sustained RIBBON study is described below; the original comparison
and finite factory recipes retain their existing render models.

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
- Finite performances have no sustain loop. The separate explicit HELD choice
  captures RIBBON into an attack-plus-loop sample and uses player note-off release.

Focused tests cover seeded replay, strict patch decoding, local causal response,
comparison isolation, macro authority, velocity, register extremes, cancellation,
recipe replay, preset promotion and CLI output. Factory integration checks cover
the preset registry, sixteen-pad starter and saved model-1/model-2 recipes with
full-width seeds. The kit generator checks preflight, recipe round-trips and
unchanged exported WAV bytes. Target-phone render latency and phone/MPC playback
remain unverified.

## Experimental RIBBON sustain

`FlockRibbonSustain` is a separate offline listening experiment. It uses the
existing RIBBON simulation and carriers without changing the nine factory recipes
or finite model-1/model-2 audio. HOLD remains a finite duration
control in SYNTH; the experiment validates but ignores it and sets its own timing.

The study compares FLOW 0, the RIBBON default 0.78, and 1 at C4, velocity 1 and
seed 42001, with all other controls fixed. Each pair has an uninterrupted source
and actual repeated-loop playback: a two-second introduction, a nominal six-second
captured body, a 30-second total hold and a 1.5-second release. The last half-second
of the captured body blends toward the natural source immediately before its
start. A deterministic search shifts the capture endpoint by at most 100 ms
to reduce destructive phase cancellation; each actual period and wrap position
is saved. This preserves the waveform context at each wrap; it does not make
the flock equations periodic.

Both comparison files use one linear gain per pair. Raw audio, complete experiment
recipes, source snapshots, traces and SHA256 manifests retain the evidence needed
to replay and inspect them. The manifest records actual boundaries, blend power,
pitch motion and capture drift. FLOW 0 moves at 0.12 times the simulation clock,
so its early capture can retain initial gathering; the long source comparison
makes that limitation audible.

The listening questions are whether the captured period repeats distractingly,
whether blends cause clicks or level scoops, whether FLOW remains distinct, and
whether note release sounds natural. Numerical edge checks do not establish a
listening pass. Sustained phone instruments and MPC keygroups are implemented
above; their physical playback and listening checks remain open.

```sh
./gradlew :synth:generateFlockRibbonSustainAudition
```

The generator writes a standalone listening page and real rendered audio under
`testkit/flock-ribbon-sustain/`. The existing private audition links to this study
at `/sustain/`; its finite listening clips remain unchanged.
