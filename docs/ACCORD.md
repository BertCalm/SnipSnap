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
one measured edited fixture is recorded below, without a general latency bound.
The corrected 429-case
probe passed, including intermediate touches and the former worst resonance.
That sampled matrix is evidence, not an analytic bound on every possible edit.

## Audition and export

```sh
./gradlew --no-daemon :synth:generateAccordAudition
./gradlew --no-daemon :synth:generateAccordAudition -PaccordQuick=true
./gradlew --no-daemon :synth:generateAccordKit
```

The generic CLI auditions registered factory voices and presets, for example
`snipsnap synth ACCORD TINE --all --out out/accord-tine`. Select one recipe
with `--preset N`. Accord's CLI scope is factory audition only: typed `--patch`
replay and note, touch, control, seed, model or duration override flags are
unsupported. Complete edited recipes remain available through the Kotlin API,
Android editor and generated audition recipes.

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
The SDK build and three unchanged functional instrumentation tests at
`fac839f0` passed with the software-emulator setup documented below. Held/keygroup
playback remains later work. These checks do not establish physical-phone
performance or hardware listening.

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

At source `fac839f098f5d9095e82df0ee3df42b7892941d8`, the standard debug assembly
and instrumentation compilation exited zero in 28 seconds. The opt-in
non-debuggable validation assembly, including vital lint, passed in 4m25s.
All three unchanged Accord device methods then passed, with zero failures or
skips, under the exact-context ART setup below: runner 163.048 seconds,
wall 172.537 seconds. The C++ host suite passed all six tests in 8.98 seconds
on the unchanged merged C++ tree. The final unfiltered nine-module JVM gate
at clean source `00a1f9ff67cecf44076fb2472db2d7dab921ec1f` exited zero in
1h30m58s: **8,010 tests, zero failures, errors or skips**. All 4,403 synth tests
reran; the other eight unchanged module tasks were validly up to date. Native
listening remains pending. Phone latency values are targets, and
physical phone/MPC acceptance remains open. Finite HOLD=0 is the only
supported playback contract.

The current checkout also includes the later complete-preset promotion and
instrument-descriptor fixes at `c57f6161`/`578d9119`, plus the bounded Meridian
distribution export at `00a1f9ff`. Its explicit keyboard-expansion opt-out keeps
the asserted first low note at 36 and the declared range at 36–72; original
generator assertions are unchanged. Accord's DSP, calibration and generated
PCM are unchanged. The functional validation hashes and three-test result
belong to `fac839f0`. Final builds at `00a1f9ff` are recorded below; the rebuilt
APK has not been device-executed. The source comparison confirms unchanged
listed Accord DSP, presets, patches, SynthScreen, C++ and frozen references;
it does not establish complete binary identity or device execution.

The full-suite review also exposed an inherited Keel pitch fixture. Default
BELLY at MIDI 30 reads +5.28 cents with the existing independent NSDF helper;
the house detector returned null because its biased-ACF ratio .804 fell below
.85 and the .575-second capture truncated the .35–.60-second window.
Test-only `986b789b` adds a default HOLD=0 NSDF ±50-cent guard for all nine
voice/note pairs and retains the house ±50-cent/confidence ≥.3 checks with uniform
finite HOLD=.65 in the same window. Source audit and independent review
confirmed envelope/capture changes only; production code is unchanged.
The Keel fixture and Meridian distribution-export cases passed in the final
unfiltered JVM gate at `00a1f9ff`.
The nine-module command ran with no Android SDK/local.properties, test filters,
exclusions or skip switches:

```sh
env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew --no-daemon --max-workers=4 \
  -Dkotlin.daemon.options=autoshutdownIdleSeconds=30 test --continue
```

### Final APK provenance

At source `00a1f9ff67cecf44076fb2472db2d7dab921ec1f`, standard
`:app:assembleDebug :app:compileDebugAndroidTestKotlin` exited zero in
154.627 seconds. The optional non-debuggable `:app:assembleDebug` exited zero
in 157.730 seconds using `scripts/android-aot-validation.init.gradle`.
Both APKs passed ZIP CRC and signature verification. The selected development
APK retains debug signing, source and dependencies, with `debuggable=false`;
the init script does not change the repository's default build settings.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| Standard debug APK | 56,046,124 | `2488f52ee2b9a680aa921091ff93e0d919b4462c330bcfb7778249983da8020e` |
| `SnipSnap-Accord-development.apk` | 46,461,883 | `fd66d3dc1df63ba98401d05f5cfefb3cfe25e83edb14b98688afdc04de75fc82` |

The published development APK and its `Accord-APK-provenance.json` sidecar
record these builds. This final artifact was not run on a device: the three
passing functional methods below remain scoped to the earlier `fac839f0`
artifact and its exact ART setup.

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
  establish no phone p95. A separate cold host measurement of the edited A01
  fixture took 376.15 ms: 50.89 ms mechanics, 231.97 ms shared calibration and
  85.87 ms actual-touch audio. That fixture is TINE Bright Edge, MIDI 63,
  touch .37, CONTACT .61, project initializer/Long.MIN_VALUE seed, start .75
  cycles and source 1.04 seconds; it captures 142,884 frames. This single
  shorter performance is not an all-edit budget or a phone measurement.

## Software-emulator functional validation

The Android 15 AOSP ATD environment used here downgraded compilation of
debuggable packages to verification, including explicit shell requests.
A shell `Success` response
therefore does not prove that the app uses compiled code. The optional
[`android-aot-validation.init.gradle`](../scripts/android-aot-validation.init.gradle)
lets a software-emulator functional run opt into a non-debuggable target while
retaining the debug variant's sources, dependencies and signing. It changes
no default build configuration and disables no tests. With an SDK configured:

```sh
./gradlew --no-daemon --init-script scripts/android-aot-validation.init.gradle \
  :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell cmd package compile -f -m speed com.snipsnap.app.brassgrow
adb -s emulator-5554 shell dumpsys package dexopt > accord-dexopt.txt
```

Verify the target package's actual filter; do not accept a neighboring
package's state or a `verify` result. This check is **necessary but insufficient**
for the instrumentation gate: it confirms an AOT artifact, not that the
instrumented runtime accepts or executes that artifact.

```sh
python3 - <<'PY'
from pathlib import Path
text = Path("accord-dexopt.txt").read_text()
marker = "  [com.snipsnap.app.brassgrow]\n"
if marker not in text:
    raise SystemExit("Target package is missing from dexopt state")
target = text.split(marker, 1)[1].split("\n  [", 1)[0]
if "[status=speed]" not in target:
    raise SystemExit("Target is not speed-compiled:\n" + target)
print(target)
PY
```

The initial `speed` artifact used class-loader context `PCL[]`. Instrumentation
adds three Android test framework JARs and the installed test APK before the
target APK. ART reported the resulting context mismatch and rejected that AOT
artifact for the instrumented loader. Native solver code was present in the
artifact, so package status and machine-code presence alone did not settle
runtime use. The unchanged three-method attempt passed two methods; save/send
still reached the 180-second deadline (241.562 seconds total).

The passing run additionally used the **actual observed initial instrumentation
context**: `android.test.runner.jar`, `android.test.mock.jar`,
`android.test.base.jar`, then the installed test APK, in that order. The full
`PCL[...]` records installed paths and checksums. Official ART tooling compiled
that exact flat context into a staging artifact in 580.888 seconds, with the
compiler watchdog enabled at the finite 1,200,000 ms bound. Original artifacts
were backed up; installation preserved ownership, permissions and SELinux
labels. No context wildcard, verifier bypass or test change was used.

The staged artifact contains native integrate/derivative/energy code of
6,472/2,044/632 bytes. A discovery-only launch checked loading and counted as
zero functional tests. The initial flat loader matched; later child-loader
and image-context warnings remained. An executable OAT mapping was observed
during the actual run, but the precise executing solver program counter was
not captured. These observations support the setup record; they do not prove
every solver call used that artifact. The simple build/filter commands above
alone do not reproduce this additional context-specific setup.

With that setup installed, the unchanged functional command was:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.snipsnap.app.ui.AccordSynthScreenTest \
  com.snipsnap.app.brassgrow.test/androidx.test.runner.AndroidJUnitRunner
```

It reported **OK (3 tests)**, zero failures/skips, runner time 163.048 seconds
and wall time 172.537 seconds. The 180-second send deadline, toast failure
checks and complete recipe assertions were unchanged. This is the functional
gate; discovery output and native-code mappings are not substituted for it.
The validation target APK SHA-256 was
`687e8f41fbc23eb61d880f02da14aa1b922e8cc8b35bd8fd0468f16cd5d5e252`;
the unchanged test APK was
`350be9e79c6e814706a43b38007d0d814c39d8aa7de54dff385b7f059f965c7a`,
both from source `fac839f098f5d9095e82df0ee3df42b7892941d8`.
Rebuilding or reinstalling can change both APK identities and installed paths;
recapture the instrumentation context for a new pair instead of reusing this
session's context string.

Run this gate separately from large JVM/compiler jobs to avoid host memory
pressure. The emulator was stopped before the full JVM retry. This result
establishes the tested bindings, persistence and SEND behavior under the
documented software-emulator setup. It is not a three-test pass for the
default debuggable APK, a physical-phone latency measurement, native listening
acceptance or MPC hardware check.

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
