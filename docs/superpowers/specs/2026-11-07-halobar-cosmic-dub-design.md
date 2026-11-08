# HALOBAR: mallet voices into cosmic dub

Baseline inspected: `cc47036c43e36d3eca78bd6e70cb15994b08d469`.
The owner approved a Python R1 audition with vibraphone, marimba and xylophone,
then explicitly requested the build. This document records that native build.

The characteristic gesture is a clear strike → ringing material → rhythmic
answers → a moving, finite halo.

## Scope and sibling boundary

A three-voice engine shares one dub stage. Tuned-bar modes and contact are
literal DSP; material weights and decay constants are musical shapes. The
vibraphone motor is amplitude modulation, not a simulation of rotating fans.
Cosmic space is a processing analogy; no physical space model is claimed.

FORK supplies a struck modal electric-piano family with pickup behavior;
TINES supplies compact bells and blocks. HALOBAR's distinct behavior is the
shared, root-reduced send with a bounded three-head answer and moving halo.
It neither adds pickup behavior to FORK nor duplicates the general rack DUB.
No external samples or third-party DSP code were imported.

## Signal flow

```text
Hann contact -> 3 normalized bar modes -> motor (vibraphone) -> dry --------+
                                 |                                       |
                 .35 root + upper modes -> 10 ms send opening              |
                                 |                                       |
                         HP 85 / LP 6800                                  |
                                 v                                       |
                         fixed delay history                              |
                      ^          |                                       |
                      |     LP -> tanh -> feedback <= .84                  |
                      +----------+                                       |
                                 |                                       |
                  heads D, D + half beat, 2D                               |
                         .62 / .25 / .13                                  |
                                 v                                       |
                      band limit / decimate                               |
                                 v                                       |
                    phase allpasses -> finite halo -> SPACE --------------+
                                                                         |
                 mean removal -> HP20 -> house level/peak -> tail fade -> mono
```

Dry and echo are independently band-limited and decimated before summation.
The contact noise is dry only. Phase and halo are outside feedback. The fixed
two-pole loop Butterworth has gain at most one; `tanh(d*x)/d` has slope at most
one. Together with gain <= .84 and a fixed delay this supplies a contraction
bound independently of the final peak ceiling. A deliberately unstable 1.08
feedback diagnostic must defeat the loop-energy regression guard.

## Saved contract

`HalobarPatch` stores engine HALOBAR, a voice, strict macro values and audio model
1. Missing model means 1; unknown models are refused. Editing retains the model.
Direct render calls settle missing/non-finite controls to defaults and clamp
finite controls; serialized patches reject invalid controls and unknown names.
Seeds use `Dsp.seedFor`, with separate source and halo identities.

| Macro | Default | Quantity and required interaction |
|---|---:|---|
| TUNE | .50 | Semitone pitch; C4 midpoint; contact and decay follow register |
| TOUCH | .40 | Contact duration and dry noise; TOUCH × SPACE |
| RATE | .45 | Six delay divisions; RATE × RETURN |
| RETURN | .45 | g=.12+.72r, darker cutoff, saturator drive; RETURN × SPACE |
| SPACE | .35 | Echo 1.55s^.65 and halo .75s^1.2; zero bypasses returns |
| MOTION | .25 | Allpass mix motion and vibraphone motor; MOTION × RETURN |
| HOLD | .30 | Decay factor .5+1.8h; HOLD × RETURN |

Supported pitch is C3–C6 for vibraphone and marimba, A3–C6 for xylophone.
The lower and upper TUNE halves span different intervals, sharing C4 at .5.
Both rendering and shell note stepping use the engine's exact forward/inverse
mapping. Delay times share one 72 BPM clock: dotted quarter, quarter, dotted
eighth, eighth, dotted sixteenth, sixteenth. There is no host tempo argument.

Base T60 seconds, before HOLD/register scaling:

| Voice | Mode ratios | Gains | T60 | Contact soft → hard, seconds |
|---|---|---|---|---|
| Vibraphone | 1:4:10 | 1/.47/.20 | 6.2/1.8/.65 | .0014 → .00012 |
| Marimba | 1:4:10 | 1/.66/.34 | 1.65/.43/.16 | .0023 → .00018 |
| Xylophone | 1:3:6 | 1/1.10/.55 | .62/.18/.075 | .00085 → .00007 |

These are shape constants, not measured instrument data. Marimba fundamental
T60 additionally has the floor `1.6*min(1,(220/hz)^4)`, retaining a real pitched
bass body at low HOLD. Velocity softens contact as well as changing excitation;
`Velocity.atVelocity` uses that native excitation rather than moving a macro.
House output matching retains the timbre change; the sampler supplies live gain.
Zero velocity returns exact silence of the planned duration.

## Duration and computation

HOLD .98–1 is one finite top, never a wrap. Source duration is the slowest T60
plus 50 ms, capped at eight seconds; its final 100 ms fades. Desired wet tail
uses feedback repeats to -60 dB, head offsets and the .9 second halo. Added tail
is capped at six seconds and total duration at twelve. Capped returns fade over
100 ms. Source and file frame counts are planned before allocation. The real
classifier's >1.5 second LOOP category has no implication of seamless playback.

Bars and nonlinear feedback run at 4x. Delivery-rate phase and convolution are
linear stages. Halo is a deterministic .9 second, low-passed noise FIR with
energy norm .24, partitioned into 4096-frame blocks and 8192-point FFTs. Immutable
kernel spectra are shared; mutable convolution history belongs to each render.
The 6 MiB completed-audio cache copies on input/output and keys voice, settled
controls, velocity and model. The accepted-sound continuation adds an 8 MiB
cache of private, immutable dry/echo/halo stems, keyed by voice, all controls
except positive SPACE, and velocity. SPACE edits only recombine and condition
the stems; dry-only notes bypass preparation reuse and retain their own duration.
Fresh diagnostics bypass both caches. Unknown macro names never affect seed identity.
Cancellation checks bound long source, echo and convolution work.

The source buffer ends after its fade plus a filter flush; long exact-zero
padding is added after decimation. The send filter stops after a 100 ms zero
flush, while feedback runs for the whole tail. This avoids Float subnormal
stalls without changing shared house filters or other engines. The default
vibraphone before/after optimization was sample-identical across 529,200 frames.

Target: fresh-control renders in <=50 ms at p95. Initial native optimization
reduced default twelve-second vibraphone renders from about 2.7 seconds to about
0.32 seconds on this JVM. That remains above target. Completed-render hits do
not establish fresh-control performance. Audition generation measures thirty
fresh renders per voice after eight warm-ups, with the completed-audio cache
bypassed. The resulting manifest is the authority for final machine timings.

## As built

Initial integration, before native listening acceptance:

Implemented the Kotlin engine and model-aware patch decoder, velocity dispatch,
determinism canary, twelve provisional references, sixteen editable kit recipes,
native WAV/HTML/metrics generators, CLI rendering/replay, starter shelf, picker,
musical RATE labels, finite HOLD label and exact shell root editing. References
are explicitly exempted from the global factory roster pending native audition.
README engine counts and picker/catalog order include HALOBAR.

The low-register classification guard found two real boundaries. Marimba C3 at
minimum HOLD needed its bass mode's actual decay retained; silence padding was
not used. Short xylophone C3 classified as a tom, so its supported bottom is A3,
keeping its fast wooden decay. F4/F5 identity and C4-based phrases remain within
the approved sketch's design. Native seed/filter arithmetic differs from Python.

Focused Kotlin verification: all 15 engine/reference/kit tests pass. They cover
three-register root accuracy (<5 cents), confidence, raw/output peaks, DC,
loudness, all controls at 0/.5/1, five interacting pairs, classifier corners,
finite HOLD, cache ownership, velocity, brightness, JSON/model errors, exact
pitch/delay mapping, partitioned convolution against direct impulse weights,
and contracting feedback with an unstable negative control. A hard/bright 4x vs
8x comparison bounds relative RMS residual to .1 and near-Nyquist energy to
1e-6. That diagnostic is not a blanket proof of inaudible aliasing.

Native generation completed successfully: 153 clips, eighteen register files,
three dry/dub phrase pairs, fixed-gain references and a sixteen-pad MPC program.
The native maximum register error was 1.688 cents, minimum confidence .95055;
independent Python house probes on the native PCM16 files recovered the roots
within .1 cent. Maximum output peak across the audition was .990001 and maximum
pre-conditioning peak .98761. The warm median/p95 milliseconds were 291.2/298.1
for vibraphone, 164.3/177.6 for marimba and 136.1/148.8 for xylophone (Temurin 21,
four visible processors, thirty fresh renders per voice). All exceed the 50 ms
budget. These numbers come from the generator, not completed-cache hits.

`:app:assembleDebug` passed with Android SDK 35 and the pinned NDK, producing
a debug APK. The exported sixteen WAVs are mono 44.1 kHz, with editable model-1
recipes, one-shot playback and no WAV sampler-loop chunks. The full JVM suite passed after integrating development base `908fd12`: 9078 tests across nine modules, zero failures/errors. No phone or MPC hardware
result is claimed.

## Final integrated build evidence

All eight other-module JVM suites and all three synth CI shards exited 0, with 9078 tests in 1131 suites across nine JVM modules (0 skipped). The CI-equivalent run covers every synth test class across three exhaustive shards and includes the HALOBAR CLI replay test, shell root stepping, starter coverage and engine/kit probes. Native asset generation and the integrated Android `assembleDebug` also exited 0. No tests were disabled or quarantined. The measured native performance target remains unmet.

The machine-readable [validation ledger](../../../reference/halobar/native-validation.json) records the tested tree, source hashes, APK hash, precise native timings and open listening/device limits.

## Development-base integration at 3154473

After the full 9,078-test run, development base `315447328` was merged, retaining
the newer EROSION, effects and app changes. Registration overlaps were resolved
with 78 picker engines and 73 starters. HALOBAR draft restoration now retains
the saved model and latest control edits. Its three engine/patch/reference source
hashes and shared DSP dependencies are unchanged from the full-regression tree.

The current tree passed 202 focused tests for host integration, root stepping,
menu conventions, the HALOBAR engine, JSON dispatch, effects and CLI replay. A
dedicated starter test saves/reopens all sixteen pads, verifies the C-minor key,
exports/readbacks both MPC formats, and reproduces first/last WAVs exactly.
Native assets were regenerated and Android `assembleDebug` passed again.
The full legacy suite was not repeated on this later development-base merge;
the validation ledger keeps the two scopes separate. Phone and MPC hardware
behavior, native listening acceptance and the 50 ms fresh-render target remain open.

## Accepted native sound and SPACE edit latency (2026-11-08)

The owner approved the published native audition with “Sound great, continue.”
Its twelve unchanged recipes now enter the factory roster, and the app/CLI use
the shared dispatcher. The original contact, bar, feedback, phase, convolution
and output arithmetic remain in place. The cached preparation is private and
immutable; final mixing always owns its output. A new test checks SPACE replay,
array ownership, all control dependencies, velocity and dry-only duration.

| Voice | Fresh median / p95 ms | SPACE edit median / p95 ms |
|---|---:|---:|
| Vibraphone | 310.1 / 341.1 | 14.5 / 16.9 |
| Marimba | 175.9 / 181.0 | 9.2 / 10.5 |
| Xylophone | 145.9 / 156.0 | 8.1 / 10.1 |

The measurements distinguish full fresh calculation from prepared-stem reuse.
Only positive SPACE editing meets the 50 ms desktop target. First calculation
and other controls still require preparation. The full fresh target remains
open; no phone timing follows from desktop measurements.

On development base `22ab69f22`, the complete `./gradlew --no-daemon test`
run covered 9,134 tests and exited 1 for one missing HALOBAR preset-authoring
helper. All 4,915 synth checks passed. The correction makes promotion emit
the complete saved JSON constructor and adds a voice-filtered promotion bank;
accepted recipes and DSP are unchanged. All 4,219 tests across the eight other
JVM modules reran successfully, alongside 53 affected synth/recipe checks.
The full synth suite was not repeated after the source-only correction. Latest
case coverage is 9,134 passing cases across the two scopes, not a claim that
the original complete command exited 0. Android `assembleDebug` and
`compileDebugAndroidTestKotlin` exited 0 after the correction. Both HALOBAR
and RIVET registrations survive the integration: 79 picker engines and 76
starters. The device-test compilation includes the merged step-editor callback
fix; it does not establish an on-device test result.

All 179 audition WAVs, 153 patch files and sixteen MPC WAVs are byte-identical
to the approved originals. Fresh diagnostics and normal rendering match the
195-case Float32 fixture from `c54f01b44`; 192 cases exercise the normal 4x
renderer and three are 8x diagnostics. The [latest acceptance ledger](../../../reference/halobar/native-acceptance.json)
records the exact tested tree, source hashes, APK hash, timing samples and scope.
`NativeReplay.java` and its TSV fixture make the same-runtime parity check
repeatable. Physical phone/MPC behavior and browser QA remain unverified.

## Evidence ledger

- Verified: real repository APIs, registrations and initial focused test exit
  code, against the baseline named above.
- Measured: Python R1 report is frozen under `reference/halobar`; native probes
  are emitted independently by `generateHalobarAudition`.
- Shape: mode weights, decay/contact laws, processing gains and halo envelope.
- Heard: owner approved the Python R1 audition, then the published native
  audition on 2026-11-08 (“Sound great, continue”). This is overall sound
  acceptance; physical phone/MPC results remain unverified. The twelve unchanged
  native recipes now enter the factory roster.

## Why it is built this way

- Distinct bar decays and contact times supply three material gestures through
  one instrument, rather than three effect presets over an identical source.
- A reduced-root send keeps mono repeats from obscuring the played note.
- A fixed, filtered feedback loop has a stability argument before output level.
- Motion and finite convolution supply space without entering that loop.
- Finite notes keep the export honest; native latency and hardware listening
  remain explicit limits rather than implied acceptance.
