# Meridian

The characteristic gesture is a maintained swing touching changing fixed materials,
earlier materials continuing to ring, then a finite release. SWEEP is one dry mono
voice, with twelve independent four-mode surfaces and fixed pickups. This is an
accelerated musical analogy, not an Earth-rate simulation or a collision solver.
No support resonator, acoustic feedback, or effects have been added.

The first published Kotlin audition received the owner's positive listening
response on 2026-10-23: “That sounds incredible.” That supports retaining its
character. It does not certify every register or the subsequent held adaptation.

## Saved models

| Recipe | HOLD below .975 | HOLD at or above .975 |
| --- | --- | --- |
| Model 1, including JSON with no model field | Original finite gesture | Original finite gesture |
| Model 2, the new recipe default | Same finite samples as model 1 | Fixed-alignment sustained swing |

Finite HOLD remains a two-to-four-second active gesture followed by its bounded
natural tail and state-preserving release damper. Its macro ranges are retained.
The material noise seed stays pinned to acoustic model 1, independently of the
recipe version. Native MIDI, relative TUNE, physical velocity, and seed context
remain part of the saved recipe.

## Controls

| Control | Finite gesture | Model-2 sustained swing |
| --- | --- | --- |
| TUNE | Relative note, ±12 semitones | Same requested modal root |
| LATITUDE | Signed precession, stationary at .50 | Selects the plane the finite swing reaches after two seconds; that plane then stays fixed |
| LENGTH | Full swing period, .15–1.5 seconds | Same requested period, adjusted slightly to close a root-aligned loop |
| REACH | Excursion, ribbon footprint, pressure and catch hardness | Same contact law in the selected plane |
| SURFACE | Modal spacing, relative decay, participation and contact color | Same material tables |
| MEMORY | Modal T60, .15–6 seconds before material scaling | Same decay, with an exactly reconstructed sustained state |
| HOLD | Active duration | Top step selects the sustained adaptation |

There is no continuing precession in the held loop. Full traversal loops would
need a different state and clock closure and are not supplied by this adaptation.

## How HOLD closes

The source repeats a whole number of swings in a .75–1.5-second loop. Its length
is fitted near an integer number of requested-root periods, then rounded to
whole output frames. The swing clock follows this small adjustment; the modal
frequencies remain at the requested tuning. Diagnostics distinguish the resulting
forcing-grid error from a detector's estimate of the complete audible mixture.

Each contacted surface uses its own deterministic periodic roughness. The two
noise filters start at their analytically solved periodic state. The engine
checks their actual state after one period. Contact weights come from an interior
window of the existing finite geometry, outside its onset and release gates;
catches wrap across the periodic source boundary without adding a start strike.

The uncoupled `Modes.Bank` modes have the stable recurrence
`z[n+1] = a * (z[n] - i * participation * impulse[n])`. One cold source cycle gives
its affine endpoint `c`, so the periodic initial state is `z0 = c / (1 - a^N)`.
The engine adds the homogeneous response of that initial state to a continuously
evolved cold bank. It continues both states across the actual subsequent cycles.
This avoids many seconds of preroll at high MEMORY without copying an output
cycle to manufacture evidence. Positive T60 keeps `|a| < 1`; no limiter supplies
modal stability.

Band limiting, shared 4× decimation, DC removal and one common leveling gain run
over continuous context around both evidence cycles. A .30-second prefix and
decimator postroll keep conditioning boundaries outside them. The first retained
cycle gets only an initial four-millisecond entry fade. The second cycle receives
no fade or wrap crossfade and is marked for sampler repetition. Seam and complete
cycle errors are measured against the actual preceding cycle before the entry
fade. Modal-state and noise-filter recurrence are checked separately.

## Rendering and export

The finite fast path renders each independent surface in surface-ID order into
a double accumulator, preserving the original addition order and final float
rounding. It reuses `Modes.Bank`; it does not remove modes, shorten ringing tails,
or change the output stage. Immutable contact descriptors remain in the bounded
eight-plan motion cache. There is no rendered-audio cache disguising render cost.

The initial performance pass removed per-sample immutable-list wrapper access.
The subsequent surface-at-a-time pass keeps bank, noise and filter state local.
The diagnostic path remains independently arranged by sample so that taps and
golden comparisons can expose disagreement between the two traversals. Raw-bit
goldens captured before this work cover legacy finite notes, including HOLD 1.

All seventeen captured model-1 buffers remain bit-identical. In the same warm
JVM profiling protocol, the default median fell from 178.2 to 87.3 ms; fixed
alignment fell from 169.8 to 62.2 ms; the long finite gesture fell from 320.0 to
164.5 ms. These runs include JFR sampling and are not phone benchmarks. The fast
path's main arrays use about 10.5 MB at default duration and 24.3 MB at the maximum
finite duration, excluding output conditioning and cached geometry.

The CLI supports saved patches and explicit model selection:

```sh
./gradlew :cli:run --args='synth MERIDIAN SWEEP --macros HOLD=1 --out testkit/meridian-held'
./gradlew :cli:run --args='synth MERIDIAN SWEEP --model 1 --macros HOLD=1 --out testkit/meridian-legacy'
```

Every CLI WAV has a complete `.patch` recipe. Held model-2 WAVs also carry a RIFF
`smpl` root note and forward-loop bounds. The root includes TUNE; bounds are
exclusive at the Kotlin boundary and converted by the shared writer. Legacy
finite exports keep their previous plain-WAV behavior. `Snip` and `Patch` have
not acquired global loop fields. The engine-specific `renderHeld` result exposes
the two-cycle sample, loop bounds and numerical evidence.

`generateMeridianAudition` remains pinned to model 1. The separate
`generateMeridianPlayabilityAudition` task builds the next comparison pack:

```sh
./gradlew :synth:generateMeridianPlayabilityAudition \
  -PmeridianPlayabilityOutput=/absolute/output/path \
  -PmeridianOriginalAudition=/absolute/path/to/original-audition
```

It retains the original default WAVs unchanged, checks the new finite default's
decoded PCM against them, and supplies eleven held examples with sampler markers
and eight-repeat listening files. Repetition demonstrates playback; it is never
used to prove state closure. Source bytes are retained before rendering and
checked again afterwards.

## Eight-preset bank and MPC starter

The bank adds eight saved SWEEP recipes through the ordinary preset dispatcher.
All use model 2, native MIDI 57 (A3), physical velocity 1, seed context 4810 and
explicit values for all seven macros. FIRST ARC retains the accepted starting
sound exactly. The remaining recipes are new listening candidates; their names
describe intended responses rather than measured physical materials.

| Preset | Gesture and role |
| --- | --- |
| FIRST ARC | Original finite swing, moderate contrast and memory |
| BACK ARC | Same cadence, reach and memory with opposite traversal |
| CLOSE GRAIN | Shallower contact, restrained contrast and shorter ringing |
| DEEP KNOCK | Quick swings, deep engagement and stronger contact contrast |
| STILL PAIR | Finite repeated contact at a stationary alignment |
| LONG TRAIL | Slower, longer finite gesture with persistent earlier responses |
| ROUND THREAD | Fixed-alignment sustain with restrained material contrast |
| FINE STRAND | Slower fixed-alignment sustain with stronger contrast and memory |

The first six are finite; the last two select genuine model-2 HOLD. This round
does not change the round-two DSP signal path. The ordinary CLI selects the bank:

```sh
./gradlew :cli:run --args='synth MERIDIAN SWEEP --all --out testkit/meridian-bank'
./gradlew :cli:run --args='synth MERIDIAN SWEEP --preset 8 --out testkit/meridian-filament'
```

`--preset` takes a one-based numeric index. Recipes preserve native note,
physical velocity, model and seed through JSON, edits and user-preset promotion.

The starter provides three separate, complete MPC packages:

- **SnipSnap Meridian Kit:** sixteen finite one-shot pads in an MPC 3 `.xtd`
  drum track and MPC 2 `.xpm` twin. A01–A08 are native FIRST ARC renders on a
  C-minor-pentatonic walk, MIDI 60, 63, 65, 67, 70, 72, 75 and 77, with varied
  physical velocities. A09–A16 are the eight authored A3 presets. The two held
  sources are finite captures here, with a 20 ms terminal fade. Every WAV has
  its actual root note and no sustain-loop marker.
- **Meridian ROUND THREAD** and **Meridian FINE FILAMENT:** genuine held
  keygroups in MPC 3 `.xty` and MPC 2 `.xpm` formats. Each instrument has
  thirteen native roots, MIDI 36–72 every three semitones, and three physically
  rendered velocity layers, .30, .65 and 1.0. MIDI velocity windows are 0–42,
  43–84 and 85–127. The 78 held WAVs retain their actual second-cycle sustain
  loops, roots and complete saved recipes.

Held keyboard zones cover C2–C5 (MIDI 36–72) only; notes outside that range are
silent. Notes between native roots transpose by at most one semitone, which also
shifts the sampled swing cadence. Native roots use the fitted engine timing.
Both program formats reference the same PCM24 mono 44.1 kHz samples. These
exports use the existing typed drum and keygroup writers; LOOP filing alone
never enables sustain in a drum pad. Loading the packages on MPC hardware remains
unverified.

Regenerate the packages alone or the complete listening page:

```sh
./gradlew :synth:generateMeridianKit \
  -PmeridianKitOutput=/absolute/output/path
./gradlew :synth:generateMeridianBankAudition \
  -PmeridianBankOutput=/absolute/output/path \
  -PmeridianBankReference=/absolute/path/to/meridian-round2-audition
```

The bank page supplies native and matched preset comparisons, saved recipes, a
playable sixteen-pad grid, and a four-bar phrase at 100 BPM. The phrase uses all
sixteen exported pad WAVs with fixed accents and one shared mix gain; full tails
remain in its saved render. It does not normalize each hit independently. The
original default reference is retained and checked against FIRST ARC decoded
PCM. Nineteen source files are captured before rendering and checked afterwards.
Browser interactions and hardware loading require their own verification.

## Android SYNTH and instrument workflow

The current factory name **FINE STRAND** is the recipe called **FINE FILAMENT**
in the October 26 audition and MPC packages. The name avoids the separate
FILAMENT engine added upstream. Its macros, note, velocity, model and seed are
unchanged; saved recipes still retain their original names and sound.

The Android picker adds MERIDIAN as engine 66 while retaining the other 65
engines. SWEEP exposes the eight-preset bank and seven macros. ROOT selects the
native base MIDI note from 36 to 96; VELOCITY changes the physical excitation.
TUNE remains a relative transposition with .5 at the center. Macro edits,
SCRAMBLE, YOURS, navigation and reopened pad recipes preserve the saved model,
native note, physical velocity and noise context.

The playback distinction follows the saved recipe. Model 1 remains finite at
every HOLD value, including 1. Model 2 selects fixed-alignment sustain at
HOLD ≥ .975. The captions identify FINITE or FIXED LOOP and explain that ordinary
SYNTH audition plays a bounded short capture. It does not continue indefinitely
while a key is held.

SEND TO PAD and SPREAD both deliver finite TONAL one-shots with the authored
root, including the TUNE offset. Held sources acquire a saved terminal fade of
at least 20 ms. Reopening a pad retains the full recipe, including rack, board,
pedals and their placement; editing the source does not rebuild the processing
chain from a reduced set of controls. The source's model and HOLD setting remain
saved, so a finite pad capture does not silently convert its source recipe.

MAKE INSTRUMENT is available only for an actual model-2 held patch. Original
finite models and current patches below the HOLD threshold show the reason they
cannot make a sustained instrument. Generation renders thirteen native roots,
MIDI 36–72 in steps of three, at physical velocities .30, .65 and 1.0. The 39
layers are rendered serially with cancellation between layers and interruptible
DSP work. TUNE is centered for these absolute keyboard roots so it cannot apply
the same transposition twice. Each sample retains its real onset cycle before
the second-cycle sustain loop. RELEASE controls the existing sample-player
note-off fade.

The typed keygroup package supplies the phone instrument, MPC 3 program and MPC
2 twin, including explicit NoteOn playback for MPC 2. Zones cover MIDI 36–72
only; outside notes are silent. Neighboring notes transpose at most one semitone,
also shifting the sampled swing cadence. Native roots retain the engine's fitted
timing. FRESH TAPE → MERIDIAN offers the existing sixteen-pad starter with its
authored roots and velocities; it does not assign a global kit key.

### Android render and memory diagnostic

`MeridianRenderBenchmark` is an opt-in AndroidJUnit diagnostic, separate from
the Compose workflow checks. It records FIRST ARC, LONG TRAIL, ROUND THREAD and
FINE STRAND at maximum MEMORY. Each case records its first call, three warmups
and seven unprofiled public-render timings. A separate render samples managed
heap occupancy every 10 ms after requested GC, with PSS and native-heap snapshots.
The report also measures the complete thirteen-root, 39-layer keyboard package
with memory sampling enabled. First-call timing can include already warmed
shared process state; sampled process occupancy is not exact peak allocation.
Package timing includes its sampler overhead. UI debounce, preview transfer and
audio output latency are outside this diagnostic.

Use JDK 17 and an installed Android SDK. First identify the attached device with
`adb devices -l`. For a physical phone, select its serial and run only this class:

```sh
ANDROID_SERIAL=PHONE_SERIAL ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.bench.MeridianRenderBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.meridianBenchmark=true \
  -Pandroid.testInstrumentationRunnerArguments.executionContext=physical-phone
adb -s PHONE_SERIAL exec-out run-as com.snipsnap.app.brassgrow \
  cat files/benchmarks/meridian-render.json > meridian-phone-render.json
```

Replace `PHONE_SERIAL` with the exact serial shown by adb. For an emulator, use
its serial and label the execution context separately:

```sh
ANDROID_SERIAL=EMULATOR_SERIAL ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.bench.MeridianRenderBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.meridianBenchmark=true \
  -Pandroid.testInstrumentationRunnerArguments.executionContext=software-emulator-api34
adb -s EMULATOR_SERIAL exec-out run-as com.snipsnap.app.brassgrow \
  cat files/benchmarks/meridian-render.json > meridian-emulator-render.json
```

The JSON records the device, API, ABI, build fingerprint and likely-emulator
flag. Keep emulator numbers separate from physical phone performance.

**Android validation (2026-10-31):** the final debug APK and canonical
instrumentation APK built with exit 0. The final JVM gate returned exit 0 with
**7,319 tests** and zero failures, errors or skips. Unchanged suites reused
Gradle up-to-date results; CLI and shell reran. All three Meridian workflow
tests passed on the exact canonical debug APK (SHA-256 beginning
`f3adf3dc7151`), including saved-preset restoration and finite authored-pad
delivery. This used an API 34 x86_64 software emulator with TCG, no KVM, and a
360 × 640 pixel display at 160 dpi.

A separate ONSET saved-pad update test failed its 30-second callback wait at
`SynthScreenTest.kt:369` on both this APK and untouched upstream
`fde661771fcbb6345a76f76e65537bfd9bb7c7ce`. Both runs used the same
canonical instrumentation APK (SHA-256 beginning `d71205e60b50`) and reproduced
the same assertion without concurrent host builds or tests. This remains a failed check. The
comparison establishes reproduction in this emulator; the precise underlying
cause and physical phone behavior remain unmeasured. The opt-in benchmark was
cancelled without a complete report. Physical phone performance, listening to
the new APK and MPC hardware loading remain unverified.

## Evidence and remaining limits

The implementation was read and developed against base commit
`4e36a2bf8cf8bfb07dba35379558b54429b5d73d` plus the local Meridian prototype.
Generated manifests and the portable source patch identify the exact dirty tree.

The focused gate checks model-1 waveform goldens, missing-model JSON compatibility,
the model-2 cutover, determinism, real consecutive cycles, filter and modal state,
PCM16 seams, note corners, bounds, silence, cancellation and CLI sampler metadata
with byte-identical recipe replay. On 2026-10-24 the complete JVM gate returned
exit 0 with 5,851 tests and zero failures, errors or skips, including 3,038 synth
tests. Unchanged tasks reused Gradle's up-to-date results; the synth, CLI and
shell suites ran against this tree. No Android build ran because there is no SDK.
The generated audition includes the full gate command and per-module counts.

The next audition's separate warm public-render run measured medians of 85.6 ms
for the finite model-2 default and 75.8 ms for the held default, each after three
warmups and over seven renders. Its eleven held examples had zero PCM16 seam
error, modal-state relative errors below 2.4e-12, and closed noise-filter states.
Their local `Pitch.detect` observations ranged from -5.6 to +12.2 cents with
confidence .72–.97. The forcing-grid fit stayed within .022 cents of the requested
root; the swing-period adjustment ranged from -.545% to +.019%. These are measured
JVM and estimator observations, not a new listening verdict.
Reported modal-energy maxima sample the state every 256 internal frames; raw
output peak measurements scan every internal output sample.

Whole-mixture pitch and octave-estimator limits disclosed in the first audition
remain open. A fitted forcing grid and correctly tuned modal roots are narrower
claims than a clear perceived note at every material corner. Additional probes
at the lowest effective note, C1 (32.7 Hz), with maximum MEMORY/REACH/SURFACE and
both LENGTH endpoints remained bounded and closed their states/seams; the generic
pitch detector returned no estimate there. They do not establish C1 pitch clarity.
The bank's focused gate passed 55 tests across synth, CLI and shell, covering
factory metadata, gain- and phase-independent preset distinction, exact replay,
native note and velocity, sixteen finite pad exports, and all 78 held layers in
both program formats. On 2026-10-26 the complete JVM gate for this bank returned
exit 0 with **5,864 tests**, including 3,047 synth tests, and zero failures,
errors or skips. Unchanged tasks reused Gradle up-to-date results; synth, CLI
and shell ran against this tree. The published pack retains the command, full
log and per-module counts separately from the earlier round-two evidence.

At the bank pass, Android picker integration and phone performance were still
open because no SDK was available. The Android chapter above records the later
integration and its separate validation status. Physical phone performance,
owner listening approval of the held adaptation and new presets, shorter stabs
and full traversal loops remain open. The new bank and packages do not close
the earlier perceived-pitch or phone-performance questions.

## Why it is built this way

- Keeping the finite signal path and seed identity preserves the sound already
  heard and protects saved recipes.
- Independent surfaces let previous materials remain audible when contact moves.
- Fixed alignment gives HOLD a bounded repeating gesture while preserving the
  same excitation and material mechanisms.
- Reconstructing the stable modal state closes sustained ringing without a long
  warmup; independent subsequent cycles test that reconstruction.
- Root-aligned duration trades a small cadence adjustment for a closer low-note
  forcing grid. Whether the repeating gesture remains useful is a listening gate.

The bank and package choices follow the same constraints:

- A fixed note, velocity and seed make preset comparisons about the gesture and
  material response; FIRST ARC preserves the sound already accepted.
- Native renders for the pad melody keep swing timing independent of note.
- Finite drum captures and separate held keygroups follow the playback contracts
  supplied by the existing MPC exporters.
- Three physical velocity layers preserve changes in contact and material
  response; sampler gain alone would miss those differences.
- Saved schedules, recipes and source hashes make the musical example replayable
  without treating a successful render as a listening verdict.
