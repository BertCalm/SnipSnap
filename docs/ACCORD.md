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
The unforced release retains a minimum of .9 seconds for BASE/REFERENCE and
2.2 seconds for the three TINE options. The corrected candidate extends that
capture to at least 1.1 times the longest actual modal t60 for the requested
settings, approximately 66 dB of free modal decay. This changes file length,
not the decay parameters. Native files can therefore outlast the original
8.9/10.2-second Python references.
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

## Level and release policy

The original proposal used one reference gain per voice/profile/note for every
control, seed and passage edit. A 360-case native probe found 89 output-peak
violations under that policy; the worst LONG RING resonance reached 4.825.
All 360 renders remained finite. Some release boundaries also retained substantial
pre-fade signal energy, requiring a longer unforced capture before the final fade.

The corrective policy uses **one gain per saved performance, shared across
velocity**. Its key includes voice/profile, note, controls, seed/initializer,
passage start/duration and rendering model, and excludes velocity. The same
settings at reference velocity .75 and hard velocity 1 establish one gain for
the whole recording. That gain stays constant during playback; there is no
per-hit normalization, automatic gain follower or change to mechanical timing.

This deliberately changes the proposal: moving a control, seed or passage can
change calibration. It protects resonant edits without turning every default
down by roughly 14.6 dB for a rare corner. Soft/hard excitation and the evolving
within-file envelope retain their relationship. Factory eight-second references
use 222 frozen profile/note gains regenerated as calibration version 2 for the
corrected tail policy. A first edit outside that table needs calibration work;
that additional cold cost has not yet been isolated. The corrected 429-case
probe passed, including intermediate touches and the former worst resonance.
That sampled matrix is evidence, not an analytic bound on every possible edit.

## Audition and export

```sh
./gradlew --no-daemon :synth:generateAccordAudition
./gradlew --no-daemon :synth:generateAccordAudition -PaccordQuick=true
./gradlew --no-daemon :synth:generateAccordKit
```

The audition writes to `testkit/accord-audition/`, with native raw and matched
WAVs, complete recipes, metrics, contact rasters/order plots and a local page.
The full set contains 71 native performances covering voice/profile,
note/touch, controls, interactions and mechanical ablations, plus six unchanged
Python prototypes. Velocity comparisons retain their native relative level.
Four mechanical comparisons also have clearly labeled `controlled-gain/`
sidecars: one scalar is shared across that diagnostic group to expose amplitude
differences that performance calibration can change. These are diagnostic
recordings, separate from the `raw/` production levels and optional `matched/`
listening copies.
`-PaccordOutput=...` selects another audition destination.

The dry sixteen-pad starter comes from `SynthKits.accord()` / `AccordKits`.
A01–A08 walk BRUSH through C minor pentatonic from C3; A09–A13 carry WOOD and
the four TINE choices; A14–A16 add soft BRUSH, hard BRUSH and soft WOOD.
The generator writes native MPC 3, its MPC 2 twin, PCM24 mono WAVs, saved
recipes and a ZIP under `testkit/`; `-PaccordKitOutput=...` changes that root.
Every pad explicitly plays once. Long files receive the existing classifier's
LOOP duration class; that is not a seamlessness or sustained-playback claim.
The generated pack passed staging/export preflight. Independent file checks
confirmed all sixteen sample hashes/frame counts, mono 44.1 kHz PCM24 format,
complete model-1/HOLD=0 recipes and ZIP contents. Physical MPC loading remains
unverified for this new pack.

SYNTH now includes the Accord picker, ROOT, TOUCH, LENGTH and explicit TINE
profile controls. Typed drafts, YOURS, navigation and pad reopening preserve the
complete recipe, and SEND/SPREAD retain its authored MIDI note. HOLD is hidden.
The SDK build and instrumentation-source compilation passed for the candidate
before the gain/release correction; that correction requires a new APK build.
Held/keygroup playback remains later work. Compilation does not establish
phone performance, emulator test execution or hardware listening.

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
8 MiB budget, reusable project roughness prefixes have a 24 MiB budget, and
edited-performance calibration retains at most 64 gains.
Eight acoustic modes read the shared contacts/pressure at 4×, then use project
band limiting, decimation and DC removal. Calibration is constant within a
saved performance and shared across its velocities, as described above.

The corrected native pipeline exited zero in 3m36s: 43 focused tests passed,
all 429 full-length output cases passed, 24 regenerated calibration entries
matched their source calculations exactly, the six deterministic 4×/8×
comparisons completed, and the audition and sixteen-pad kit were generated.
Audition sample hashes and archive contents were independently checked;
the six copied prototypes match the frozen originals, and maximum PCM24
encoding error was 5.96e-8.
The earlier 360-case failure remains the reason for the correction above.

Android `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` exited
zero in 5m24s before the correction, including all four native ABIs; the C++
host suite passed all six tests. A corrected APK, complete JVM suite, Compose
emulator execution and native listening remain pending. Phone latency values
in the design are targets, and phone/MPC acceptance remains open. Finite
HOLD=0 is the only supported playback contract.

## Native measurement methods and limits

The generated reports are under `testkit/accord-validation/report.json` and
`testkit/accord-rate-reference/report.json`; their generators are repeatable
with `:synth:generateAccordValidation` and `:synth:generateAccordRateReference`.

- The 429-case matrix uses eight-second sources, all six acoustic profiles,
  four registers, a three-step mechanical grid, PACE/CONTACT extremes,
  measured period-derived resonances, selected project seeds and passage
  starts. It measures actual production Float output before WAV encoding,
  with no generator normalization. All outputs were finite; maximum peak was
  .850000024 against the .94001 requirement, and maximum absolute mean was
  2.514e-5 against .005.
- Release checks inspect the conditioned continuation **before** the final
  fade. The last 100 ms RMS is compared with active-source RMS; the worst was
  −57.35 dB against a −50 dB target. The 4 ms fade therefore does not conceal
  a failed measured tail boundary.
- All 48 repeated performance contexts shared exactly the same gain across
  their positive velocities. The 48 intermediate .5/.9 touches passed, and
  a 21-step 0–1 sweep of the former worst resonance had monotonic peak and
  RMS. Zero touch returns exact silence without calibration. These probes
  do not prove every intermediate velocity at every possible setting.
- The rate study compares deterministic WOOD, TINE Reference and TINE Bright
  Edge at C5, hard touch, CONTACT=1 and PACE 2/4 cycles per second. An
  independent 4× path agrees within 2.91e-10 absolute. At 8×, matching the
  native contact support gives aligned 20 Hz–10 kHz residuals of −89.32 to
  −69.78 dB after one global delay/gain fit. Independently rounding nominal
  widths gives −51.45 to −40.84 dB: that includes a changed contact shape,
  so it is not an alias measurement. Separately, sampled-Hann versus exact
  continuous-Hann transform error is at worst −78.59 dB relative to unit DC
  over 20 Hz–10 kHz. This isolated folding probe precedes fractional event
  interpolation and acoustic filtering; it is not an all-engine alias bound.
  BRUSH noise is excluded from the independent rate comparison.
- On the Linux amd64 JDK 21.0.13 desktop with four visible processors, a
  factory BRUSH render took 537.8 ms cold, 272.1 ms with prepared mechanics,
  and 2.10 ms for exact completed-audio recall including owned copies.
  These are one wall-time sample per path, with byte-identical PCM; they
  establish neither a phone p95 nor the cold cost of an edited performance.

## Why it is built this way

- The support pushes back on every mechanism. That reciprocal reaction gives
  changes in contact timing a physical cause.
- Separating mechanics from pitch preserves a playable root and lets note or
  touch edits reuse a prepared history.
- The eight-mode acoustic bank follows the approved Python sound direction;
  it avoids multiplying resonators merely to match the number of mechanisms.
- One constant gain shared across a performance's velocities preserves touch
  and attack concentration. Recalibration after an edit trades the proposal's
  cross-setting level invariance for headroom without per-hit leveling.
- Explicit initializers and frozen WAVs make the risky native RNG/filter change
  reviewable; source approval does not automatically approve the native result.
