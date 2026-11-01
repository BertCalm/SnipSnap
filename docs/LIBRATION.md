# Libration — finite instrument

This milestone adapts the supplied Libration engineering proposal to SnipSnap's offline
Kotlin synthesis path. It implements GATHER, TWINS and DEPARTURE for an initial listening
study. Model 3 develops those three voices into twelve finite presets, playability
comparisons, musical phrases and a sixteen-pad layered MPC starter. The owner accepted
the control revision and musical bank. The three voices and twelve presets now enter
the phone's SYNTH picker and FRESH TAPE starter list. The owner accepted finite
phone/MPC behavior on 2026-10-31. The separately versioned
[held instrument](LIBRATION_HOLD.md) adds recurring sustain without changing this
finite kernel or the twelve presets.

The engine, complete patch JSON and native velocity rendering live in `:synth`.
The audition uses actual Kotlin renders, retained source, exact recipes, trajectory
data and SHA-256 hashes. Model 2 contains 42 examples: the three voices and four controlled
comparisons per voice, five-step NUDGE/RESONANCE and three-step WANDER/SWARM sweeps, nine REGION examples, and two
additional roots. All examples are dry 44.1 kHz mono WAVs after the established 4× DSP
and two-stage downsampling path.

## Dynamics and musical time

All saved models use the supplied unsoftened rotating-frame circular restricted three-body
equations, including Coriolis acceleration, with `mu = 0.000954`. L1/L2/L3 use bracketed
bisection; L4/L5 are analytic. An adaptive double-precision Dormand–Prince 5(4) solver
uses relative tolerance `1e-9` and absolute tolerance `1e-11`. It samples at exact
500 Hz musical control times. Gravity is never solved in the audio loop.

Initial states use both triangular oscillatory modes, or an unstable collinear mode
with a small oscillatory component. These are the original model 1 templates; model 2 retains their clocks, neighborhoods and WANDER offsets:

| Family | Simulation units / audio second | Neighborhood | Excursion base | WANDER addition | Maximum velocity impulse |
| --- | ---: | ---: | ---: | ---: | ---: |
| L4 / L5 | 18 | .12 | .0007 | .0030 | .00018 |
| L1 | 1.05 | .035 | .000003 | .000018 | .000055 |
| L2 | 1.12 | .055 | .000003 | .000018 | .000060 |
| L3 | 17 | .22 | .00035 | .0016 | .00010 |

NUDGE modifies initial velocity once. Jacobi measurements start after that impulse.
Stable-ID initialization uses `Dsp.seedFor` with the original seed version, family and particle ID;
SWARM, pitch and velocity do not reseed existing trajectories. The reference model is
conservative: voicing supplies a coherent opening, not a physical attracting force.

### Model 2 control revision

The first listening feedback found NUDGE and RESONANCE difficult to distinguish.
The original NUDGE sweep's median L2 neighborhood exit moved only from 3.252 to
3.108 seconds. Model 2 retains the same initial positions, particle identities and
equations, but widens the initial velocity kick. With `knee = oldMaximum / 2`, the
kick is `knee * expm1(log1p(newMaximum / knee) * NUDGE)`. This compensates for
logarithmic saddle escape times. It changes the integrated dynamics, not an audio timer.

| Family | Model 2 maximum velocity impulse |
| --- | ---: |
| L4 / L5 | .0012 |
| L1 | .020 |
| L2 | .025 |
| L3 | .0030 |

At NUDGE 0/25/50/75/100%, the revised audition's median exits are
3.252/2.998/2.476/1.860/1.160 seconds. That row fixes RESONANCE at 25%.
The protected root continues after the cloud leaves. The wider triangular kicks
remain within the .12 neighborhood over the tested eight-second corner cases.

An exclusion radius of .01 around either primary terminates an encounter; a radius
of 8 terminates far-field motion. Work is capped at 120,000 solver attempts per
particle. The solver also limits duration and cache samples. A terminated or failed
particle keeps its last valid cached state while its excitation fades over .10 s;
it is never teleported or restarted. Its modal memory continues independently.

## Allocation and synthesis

Each active REGION bank has 16 stable slots. SWARM reveals `1 + 15 * SWARM` slots
using fractional activation. The two adjacent banks have equal-power audio weights;
at most 32 source voices are rendered. They never interpolate physical states.

| REGION bank | Allocation at SWARM 1 |
| --- | --- |
| L4 | 12 rounded L4 voices; 4 L5 voices at .58 relative amplitude |
| L1 / L2 / L3 | 16 voices in the selected family; every third uses glassy material |
| L5 | 12 glassy L5 voices; 4 L4 voices at .58 relative amplitude |
| Between anchors | Both adjacent 16-slot banks, with their own allocations |

Opposite-cloud slots are 3, 7, 11 and 15. Slot 0 is always in the selected family,
so SWARM zero retains one clear moving voice at an anchor. Cloud gain is compensated
by the square root of the sum of squared active weights.

A persistent dry root and exact harmonic companion anchor the note. Each cloud voice
has a persistent phase accumulator and four band-limited harmonic components. Cached
displacement, speed, local turning and neighborhood exit change spectral redistribution,
excitation pulses and attack smoothing. Pitch movement is bounded by ±12 cents.
Collinear excitation decreases with the largest integrated excursion, rather than a
random departure timer. Leaving does not reset the source's oscillator phase.

An uncoupled `Modes.Bank` receives actual root/cloud excitation. RESONANCE controls
its send, modal T60 and a bounded direct balance. Model 1 retains its original
`.20 * 30^RESONANCE` decay and weak send for exact recipe recall. Model 2 uses
`.25 + 5.75 * RESONANCE` seconds. Each mode's participation is multiplied by twice
its damping rate to compensate resonant gain as decay changes. A bounded square-root
send brings the bank forward at low and middle settings; the continuous root send
and a modest reduction of the direct cloud give that memory room. No feedback
enters the trajectory solver and no springs couple the audio modes.
It is shared within one rendered note. Subsequent pad hits replay rendered audio;
cross-note sympathetic interaction is outside this prototype.

## Recipes, comparisons and verification

`LibrationPatch` saves voice, macros, MIDI, velocity, model and (from model 3) finite
source duration. It participates in
`Patches`, `PadRecipe`, `Velocity` and the factory `Presets` dispatcher.
`LibrationPresets.all()` holds twelve
model 3 presets; `references()` retains the three accepted model 2 starting points.
Saved patches reject
nonfinite or out-of-range values. Direct renderer calls clamp finite macro values.

The first listening page includes FULL, FROZEN, PITCH_ONLY, NO_PITCH and NO_MEMORY.
FROZEN holds all initial descriptors while oscillator phase and excitation pulses
continue. PITCH_ONLY preserves physical pitch modulation while freezing the other
descriptors. NO_PITCH preserves the moving timbre at harmonic carrier frequencies.
NO_MEMORY removes only the modal output; it keeps the direct path unchanged.

Controlled ablations share a single gain derived from the complete voice,
using the project's audition loudness convention. Macro sweeps instead match each
clip's peak-window body loudness so differences in duration/color can be judged
without a simple level advantage. The RESONANCE row holds NUDGE at zero and closes
its source at two seconds; the tail can be heard alone. Other source durations are
six seconds. Native-level files preserve the
pre-normalization signal. The archive includes matched PCM16 WAVs; native PCM24
files remain separately downloadable on the page. Diagnostic JSON includes both
the ordinary patch and the comparison settings, so the audition gain can be replayed.

The ten `LibrationTest` checks cover equilibrium residuals, eight-second triangular
confinement over WANDER/NUDGE corners, post-impulse Jacobi conservation, path-dependent
departure, tighter-tolerance/denser-sampling convergence, exclusion/far-field/work-cap
termination, stable SWARM identities, separate REGION families, deterministic audio,
pitch, silence at zero velocity, patch/recipe recall, five-step departure separation,
audible-range middle-setting tail energy, and cache invalidation.

Trajectory tables are computed before the audio loop. The audition's bounded
session cache is keyed by model, source duration, control rate, REGION, WANDER,
NUDGE and SWARM. RESONANCE, pitch, velocity and ablations reuse the same physical
table. A cached table with mismatched physics is rejected. This is an offline
renderer; complete note rendering was approximately 0.3–2.6 seconds per clip on
this cloud host, excluding compilation/export. Phone knob latency is unmeasured.

Measured over the 42 model 2 audition renders:

- Maximum relative Jacobi drift: `1.47e-14` for the sampled configurations.
- Maximum project pitch-estimator root error: `1.591 cents` over C3/C4/C5 examples.
- Maximum pre-normalization output peak: `.37383`.
- RESONANCE 25/50/75/100% tail RMS at 2.1–2.6 seconds is
  approximately 31.0/20.3/15.6/13.0 dB below the source-body RMS.
- A controlled two-second, 50%-RESONANCE test measured native tail RMS `.01693`
  versus `.0001525` for model 1 (about 41 dB stronger in RMS).
- All 84 exported WAV formats and hashes are checked by `analyze.py`.
- Chromium desktop and 390 px mobile checks cover playback, preserved A/B position,
  controls, notes, downloads, layout overflow and browser errors.

These are finite-time and finite-configuration observations. They are not a proof of
eternal confinement or an owner listening acceptance. Wider macro/root/seed coverage
and phone render-latency measurements remain release work.
Saved model 1 recipes and the first audition archive are retained separately.

Generate:

```sh
./gradlew --no-daemon :synth:test --tests '*LibrationTest'
./gradlew --no-daemon :synth:generateLibrationAudition
# Optional standalone plots and format verification (NumPy, SciPy, Matplotlib):
python3 synth/src/test/resources/audition/libration/analyze.py testkit/libration-audition
```

Replay an exact matched diagnostic:

```sh
./gradlew --no-daemon :synth:replayLibrationAudition \
  -PlibrationRecipe=/absolute/path/recipes/departure-no_memory.json \
  -PlibrationReplayOutput=/absolute/path/replay.wav
```

## Model 3: playability and musical bank

Model 3 preserves model 2 physics and all existing model 1/2 rendering branches.
At the default touch `.8f` and six-second source it produces byte-identical accepted
model 2 PCM24 reference waveforms. Tests pin all three SHA-256 hashes, compare raw
and engine-conditioned float samples, and retain old JSON shapes.

`sourceSeconds` is a saved finite duration in `.2..8.0`. It controls direct-source
closure before rendering the sympathetic tail. It is not a runtime note-off or
sustained HOLD. The closing ramp is `min(.65, .325 * sourceSeconds)` for model 3;
models 1/2 retain the original `.65` ramp and six-second public patch duration.
This avoids a closing ramp longer than a short source while keeping the accepted
long-note behavior. Diagnostic recipes still reproduce their explicit durations.

With `touch = velocity - .8f`, model 3 scales the upper cloud spectrum by
`1 + .60 * touch`, attack smoothing by `1 - .55 * touch`, and root upper harmonics
by `1 + .40 * touch`. Velocity also retains SnipSnap's native output-energy law.
Touch never changes physical initialization, oscillator root or solver clocks.
Tests check articulation residual after level matching, energy separation, source
closure, decaying after-ring, pitch, zero-touch silence and saved duration recall.

The bank contains four presets each for GATHER, TWINS and DEPARTURE. The musical
generator renders three touch levels, three lengths and C2/C4/C6 for one
representative per voice, plus six-note repeat examples. Bass, chords and melody
use every event's full saved patch, exact onset frame and one conservative fixed
mix gain. Complete note tails overlap as independent renders; cross-note physical
or sympathetic coupling is not claimed. Scores and recipes are inspectable JSON.
Touch groups share the hard note's audition gain; other clips match the whole
clip's loudest-200-ms proxy. Native bank files are engine-conditioned output,
whereas the retained model 2 study's raw files remain pre-normalization output.

`LibrationKits` exports twelve presets plus four extra roots as sixteen one-shot
TONAL pads. Each has native soft `.35` and hard `1.0` renders in zones 1–63 and
64–127. The MPC3 `.xtd` and MPC2 `.xpm` share 32 mono 44.1 kHz PCM24 WAVs.
The MPC3 track embeds a four-bar 96 BPM clip. `kit.json` preserves editable hard
recipes and exporter fingerprints; `libration-layers.json` adds full PadRecipes,
source velocities, durations, roots, zone boundaries and hashes for both layers.
Each layer recipe is re-rendered and required to reproduce the exact exported WAV.
Root pitches are baked into samples, with zero pad tuning; drum layer RootNote
retains the writer's native zero. Programs are read back through the existing MPC
reader. That verifies file agreement, not hardware playback.

The generator measures three sequential runs of ROUND KEYS, FINE KEYS and OPEN
EXIT on the Linux JVM after bank rendering. It records physical precomputation,
warm audio render (reused trajectories), cold total, particle/control-state counts
and an approximate numeric-table payload. The estimate excludes JVM object/array
overhead. The generation-session physical cache is capped at sixteen tables;
audio cache entries retain samples only. This is not a production phone cache or
a phone latency measurement. Exact measurements live in `music/profile.json`.

```sh
./gradlew --no-daemon :synth:test --tests '*LibrationTest' --tests '*LibrationPerformanceTest'
./gradlew --no-daemon :synth:generateLibrationMusicalBank
```

The previous model 2 control generator is explicitly pinned to model 2. Its frozen
42-clip output and the original model 1 archive remain unchanged. The musical
page links to that separate study. New artifacts go to `testkit/libration-musical`.

## Phone integration and render session

SYNTH exposes GATHER, TWINS and DEPARTURE, four factory chips per voice, five sound
controls, and independent ROOT (MIDI 36–84), TOUCH (0–1) and LENGTH (.2–8 seconds).
LENGTH uses a square law for finer short-note editing and saves milliseconds.
HOLD is not offered as a slider. Reopened models 1/2 retain their original six-second
duration; their LENGTH control is disabled. Timbral edits and SCRAMBLE preserve saved
root, touch, duration and model. The existing `SynthDrafts`, YOURS preset store and
`PadRecipe` path carry those fields through navigation, save, recall and SEND TO PAD.
Authored roots also prevent the pitch detector from adding fine tuning on pad delivery
or SPREAD.

`LibrationRenderSession` belongs to the screen. It retains at most two physical
populations and 4 MiB of final mono Float audio; neither cache enters a saved recipe.
Physical keys include model, control rate, source duration and the four trajectory
macros. ROOT, TOUCH and RESONANCE reuse physics but render fresh audio. Final audio keys
also include voice, all resolved macros, root and touch; names do not affect sound.
Returned arrays are copies, so processing cannot change a later recall. Audio larger
than the budget is rendered without retention. The existing 100 ms debounce and
`runInterruptible` cancel slider work; interrupted renders do not enter either cache.
This keeps recalled notes cheap without changing the approved DSP or shortening tails.
New audio still costs hundreds of milliseconds on the cloud JVM; physics reuse alone
does not meet a tens-of-ms preview target.

FRESH TAPE → LIBRATION builds the sixteen-pad bank through `SynthKits.libration()`.
Pads retain one-shot playback, native soft/hard zones, zero tuning and pad level `.4`.
The main `PadRecipe` describes the hard strike; `source.softRecipe` carries the complete
soft PadRecipe inline, so a phone-created kit or backup has both without an external
audition sidecar. Standard MPC3 and MPC2 export paths preserve those WAVs and zones.
The published audition starter additionally includes its 96 BPM clip and proof files;
it remains the frozen listening artifact for that milestone.

### Measuring on a phone

An opt-in Android instrumentation benchmark measures ROUND KEYS, FINE KEYS and OPEN
EXIT with one untimed warmup each and three runs by default. It separates empty caches,
physics reuse with fresh audio, and a final-audio cache recall. Timings use a monotonic
clock and exclude the UI debounce, playback and export. JSON records device/OS details
and each complete patch. Run on a connected **physical phone**, with other work idle:

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.LibrationRenderBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.librationProfile=true
adb exec-out run-as com.snipsnap.app cat cache/libration-render-profile.json > libration-phone-profile.json
# Separately exercise the picker, controls, recall and pad delivery:
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.LibrationSynthScreenTest
```

The benchmark is skipped in ordinary Android suite runs unless explicitly enabled.
No physical phone is attached to this cloud workspace. Android compilation can verify
the glue; it cannot establish phone latency or listening acceptance.

### Hardware acceptance

The owner reported on 2026-10-31 that phone and MPC playback are fine and
authorized the next milestone. This is owner listening and playability acceptance
of the finite instrument. No device model, OS version or phone timing report was
provided, so the report does not establish measured render latency.

Load the [published starter](https://libration-snipsnap-audition.bertcalm.chatgpt.site/libration-mpc-starter.zip)
with its adjacent TrackData folder. On MPC3 set the project to 96 BPM and play the
embedded four-bar clip. On MPC2 load the `.xpm` inside TrackData and play the pads.
Check hits around velocities 63/64, rapid alternating soft/hard repeats, overlapping
departing tails and a full-bank level pass. Confirm root pitches and one-shot endings
before changing pad tuning or levels. Record the hardware model/OS and any problem
pad, velocity and action. Program read-back checks do not replace this pass.

## App build verification (2026-10-31)

The integration merges application base
`fde661771fcbb6345a76f76e65537bfd9bb7c7ce`. It uses the current shared preview
cancellation and engine catalogue, with 66 picker entries and 55 FRESH TAPE
starters. Picker boundary tests include LIBRATION before the return to LATTICE.
The approved engine, trajectories, patches, presets, kit and render-session
sources remain byte-identical to the accepted integration snapshot.

`:app:assembleDebug` and `:app:assembleDebugAndroidTest` completed successfully
in 6m 34s on the cloud host with JDK 21, Kotlin 2.0.21, compile SDK 35 and the
repository's pinned NDK. The debug APK's v2 signature verifies; it targets
Android 10 (API 29) or newer. The build used
`-Pkotlin.daemon.jvmargs=-Xmx4g` for the large SYNTH screen. The three new
performance controls live in a separate composable.

The app and device-test APKs came from an isolated checkout with byte-identical
changed Kotlin/build sources. The full nine-module JVM invocation completed
successfully in 1h 21m 4s: 7,288 tests, zero failures, errors or skips. All 42
tasks executed. The 3,938 synth tests include the preserved reference waveforms
and the cache, invalidation and cancellation checks; all 1,658 shell tests
passed, including native velocity layers, saved performance fields, export
read-back and the current picker/catalogue conventions.

Phone UI tests and the opt-in benchmark compiled; neither was executed locally
without a connected device. Physical phone timing remains unmeasured. The owner
subsequently accepted phone/MPC behavior as recorded above. The approved
`Libration.kt` renderer remains SHA-256
`033ba2696d06cc6ff81c1f9ed9c3665a662ac56b4b1b581c2d0f62529ac4239c`.

## Remaining scope

The original finite kernel still refuses positive HOLD and the twelve presets
continue to save HOLD zero. Explicit `heldModel=1` patches route HOLD one to the
new [continuous held renderer](LIBRATION_HOLD.md); NOTE routes to the unchanged
finite kernel. The original sixteen-pad starter remains finite. The six-design,
36-preset expansion, physical-phone profiling and faster fresh renders remain
later work. The general audio classifier's duration rule calls long finite
examples LOOP; their engine intent is TONAL and their WAVs play once.

## Evidence ledger

- Verified: the implementation uses the repository's real patch, recipe, native
  kit assembly, MPC export and read-back APIs at base revision
  `9801adecfec0269c2935a148e437156125f525f4`. The published audition retains its own
  reconstruction patch; the app handoff patch additionally captures the factory,
  cache, picker and profiling integration. The `DeterminismTest` canary recalls short soft blended-region notes
  through saved PadRecipes for all three voices.
- Measured: exact reference PCM hashes, root estimates, numerical drift, native
  touch energy and articulation residual, source closure/after-ring, layer replay,
  WAV formats, browser behavior and cloud JVM render cost. Artifact manifests and
  verification reports contain the measurements and methods.
- Proposed: preset roles, musical usefulness and the names' material associations.
- Heard: the owner accepted the control revision and twelve-preset musical bank,
  then reported phone and MPC behavior acceptable on 2026-10-31. Hardware model,
  OS details and measured phone latency were not supplied.

## Why it is built this way

- Source length closes the excitation before rendering the tail. Cutting a finished
  long WAV would erase the memory the instrument is meant to expose.
- Touch changes attack and upper partials around the accepted `.8f` setting. This
  adds articulation while preserving the previously accepted default waveforms.
- Soft and hard kit layers retain their native energy and complete recipes. Peak
  matching each layer would hide touch dynamics and weaken the regeneration claim.
- Each phrase uses one fixed mix gain and lets complete tails overlap. This makes
  masking and repetition audible in a musical setting before phone integration.
- A bounded screen cache reuses physical work and revisited audio while keeping
  the saved recipe independent. The phone benchmark separates those gains from
  the still-expensive fresh render, so the next speed decision has evidence.
