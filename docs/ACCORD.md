# Accord: finite mechanical ensemble

Accord renders fifteen self-sustaining mechanisms on one moving support. Their
contacts can move from scattered timing toward concentrated pulses, while tuned
resonators retain the requested note. Gathering is an outcome of the reciprocal
mechanical model, not a scheduled phase alignment or a guarantee for every setting.

This is the finite native model-1 candidate. The owner approved the Python sound
direction: BRUSH is the default, WOOD remains, and TINE retains its Reference,
Soft Attack, Bright Edge and Long Ring alternatives. Native listening, complete
control-domain acceptance and phone/MPC checks remain open.

## Play a saved performance

```kotlin
val patch = AccordPresets.default().copy(midi = 48, velocity = .75f)
val snip = patch.render()

val softer = patch.copy(velocity = .3f)
val variation = patch.copy(seed = 7L, initializer = AccordInitializer.PROJECT_SINE_V1)
```

MIDI is the single pitch authority: 36–72 (C2–C5), with C3/48 as the default.
There is no second TUNE macro. Velocity changes acoustic excitation rather than
the mechanical history. The source lasts .75–8 seconds; the default is eight.
BASE/REFERENCE add .9 seconds of unforced release, and the three TINE options
add 2.2 seconds, giving default files of 8.9 and 10.2 seconds respectively.
The original .25-second source proposal classified short low BRUSH/WOOD cases
as kick or tom. The native minimum is .75 seconds, so even its shortest
nominal file is 1.65 seconds and follows the existing duration-based LOOP class.

`sourceStartCycles` chooses 0–8 canonical reference cycles into the mechanical
history. Its default, eight, is four seconds of mechanics preroll at default
PACE. The acoustic state starts at zero at the selected passage; the preroll
does not hide an audio onset or add a forced gathering envelope.

| Control | Default | Behavior |
|---|---:|---|
| PACE | .50 | Traverse the same mechanics at 1–4 full reference cycles/second; .50 is two. Ordinary center passages can occur twice per full cycle. |
| COUPLING | 1.00 | Reciprocal inertial influence through the support. Zero removes that influence exactly. |
| MISMATCH | .25 | Spread natural rates from equal parameters to a bounded 4% scale; .25 gives the reference 1% scale. |
| CONTACT | .50 | Change contact width, pulse strength and brushing balance without changing event timing. |
| RAFT | .50 | Change support mass/damping and correlated acoustic body properties. Gathering need not increase monotonically. |
| HOLD | 0 | Finite playback only. Positive settled HOLD is refused explicitly. |

Direct rendering fills missing controls, ignores unknown keys, replaces
non-finite values with defaults and clamps finite values. Saved patches follow
strict validation: unknown/out-of-range controls and unsupported HOLD fail.

## Voices and persistence

Six starting recipes are registered: BRUSH, WOOD, and the four TINE profiles.
`AccordPresets.default()` selects BRUSH; `AccordPresets.forVoice(voice)` returns
that voice's recipes. BRUSH/WOOD require acousticProfile BASE. TINE requires
REFERENCE, SOFT_ATTACK, BRIGHT_EDGE or LONG_RING. Its original upper ratio stays
5.96; the archived 6.00 tuning experiment is not the native default.

`AccordPatch` saves name, voice, controls, MIDI, velocity, full signed Long seed,
model, acoustic profile, initializer, source start and duration. JSON stores the
seed as a canonical decimal string, avoiding Double precision loss. Raw stored
controls and velocity are checked before Float conversion. Rename/control edits
retain every performance field.

REFERENCE_SINE_20261022 uses the archived phase/rate vectors and requires seed
20261022. PROJECT_SINE_V1 explicitly selects the project RNG for arbitrary
seeds. Changing note, touch, acoustic profile or CONTACT does not reseed mechanics.
Native roughness uses versioned project noise and a Box–Muller transform;
NumPy filters and noise are preserved as references, not as a byte-identity claim.

## Audition and export

```sh
./gradlew --no-daemon :synth:generateAccordAudition
./gradlew --no-daemon :synth:generateAccordAudition -PaccordQuick=true
./gradlew --no-daemon :synth:generateAccordKit
```

The audition writes to `testkit/accord-audition/`, with native raw and matched
WAVs, complete recipes, metrics, contact rasters/order plots and a local page.
The full set includes voice/profile, note/touch, controls, interactions and
mechanical ablations. Velocity comparisons retain their native relative level.
`-PaccordOutput=...` selects another audition destination.

The dry sixteen-pad starter comes from `SynthKits.accord()` / `AccordKits`.
A01–A08 walk BRUSH through C minor pentatonic from C3; A09–A13 carry WOOD and
the four TINE choices; A14–A16 add soft BRUSH, hard BRUSH and soft WOOD.
The generator writes native MPC 3, its MPC 2 twin, PCM24 mono WAVs, saved
recipes and a ZIP under `testkit/`; `-PaccordKitOutput=...` changes that root.
Every pad explicitly plays once. Long files receive the existing classifier's
LOOP duration class; that is not a seamlessness or sustained-playback claim.

The app picker, Accord held/keygroup playback and phone SDK integration are
outside this finite candidate. No Android SDK was available in the implementing
environment. A general JVM suite does not establish Android or hardware behavior.

## Evidence and current validation

The [engineering design](superpowers/specs/2026-11-01-accord-engine-design.md)
preserves the proposal and contains its current As built record. The complete
[frozen Python handoff](../reference/accord/README.md) contains 38 files,
including six approved WAVs, exact tables, the initializer fixture, scripts and
source/bundle manifests. All 37 listed bundle hashes and the manifest's own
byte identity were verified on copying. Run Python experiments in a scratch
copy; its renderers write beside their source files.

The native solver is RK4 at 256 steps/reference cycle with dense directional
crossings and work diagnostics. Its history cache excludes acoustic settings
and keeps up to six entries/32 MiB total/12 MiB each; audio has a separate
8 MiB budget. Eight acoustic modes read the shared contacts/pressure at 4×,
then use project band limiting, decimation and DC removal. Static calibration
belongs to the fixed profile/note reference, preserving touch and contact dynamics.

Native build/test exit status, numerical/audio probe results and measured stage
timings are pending the coordinated validation run. Phone latency targets in
the design are targets. The native audition requires the owner's listening
verdict, and no HOLD seam or device acceptance is claimed.

## Why it is built this way

- The support pushes back on every mechanism. That reciprocal reaction gives
  changes in contact timing a physical cause.
- Separating mechanics from pitch preserves a playable root and lets note or
  touch edits reuse a prepared history.
- The eight-mode acoustic bank follows the approved Python sound direction;
  it avoids multiplying resonators merely to match the number of mechanisms.
- One fixed reference gain preserves soft/hard touch and the concentration of
  attacks. Per-hit leveling would erase the gesture.
- Explicit initializers and frozen WAVs make the risky native RNG/filter change
  reviewable; source approval does not automatically approve the native result.
