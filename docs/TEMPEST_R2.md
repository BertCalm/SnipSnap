# TEMPEST macro revision 2

Status: owner-accepted sound, integrated finite kit, 2026-11-01. Repository base `df675d0`. Model 1 remains frozen in `TempestLegacy.kt`; new patches save model 2. HOLD remains reserved.

## Fix now

- [A/D] Josh heard SPACE and DRIVE changing only at 1.0, a SOURCE family jump from .5 to .75, and FILTER sounding similar across its travel. This is the owner's R1 listening verdict, not an assistant judgment.
- [A/D] R1 multiplied SPACE send and wet, giving a quadratic first-repeat level. Model 2 uses a unity send, wet `.9*sqrt(SPACE)`, and delay time 22–122 ms for KICK / 34–174 ms for METAL. Earlier settings now expose a repeat after the attack instead of hiding it under the body.
- [A/D] R1's drive harmonics passed through a filter often above most source partials. Model 2 filters first, then folds the filtered signal. A fixed wet-branch voltage factor (2 for KICK, 4 for METAL) places actual folding inside the low and middle control range. No per-event normalization is used.
- [A/D] FILTER now spans `.5*f0` to `4*f0`, with an attack multiplier `1+1.2*exp(-t/.025)`. Positive damping corresponds to Q 1.1 LP / Q 1.5 normalized BP. Its travel crosses the note's partials.
- [A/D] SOURCE now follows continuous weights and PM depth, with gradual noise: root .42, harmonic-to-inharmonic color .38, PM .20, noise blend `.12*SOURCE`. Its excitation decay develops continuously: KICK `.035*4^SOURCE` seconds; METAL `.055*3^SOURCE`. A smooth release from .35 to .60 seconds hands the ending to the memory. Richer sources therefore also ring longer. There is no indexed algorithm switch.

## Why it is built this way

- Post-filter folding lets DRIVE's new harmonics reach the output. The feedback path remains linear and lossy.
- A single wet gain exposes the first repeat at low SPACE. Moving its fixed-per-render delay also changes the strike's time shape.
- Pitch-relative cutoff places FILTER where the source has energy.
- Continuous source color and decay give the middle of SOURCE a gesture, while a fixed root contribution preserves a pitch reference.
- Saved render models preserve the earlier recipes while the new controls are auditioned.

## Numerical and compatibility contract

The fold term is `(sin((1+7*d)*voltage*x+.15*d)-sin(.15*d))/(1+abs(sin(.15*d)))`. It is bounded by 1. Its convex mix with the dry input is bounded by `max(abs(x),1)`. A 10 Hz DC blocker follows the asymmetric stage and precedes memory.

The delay loop contains only a fixed integer delay and convex one-pole loss. Feedback is `exp(-ln(1000)*actual_delay_seconds/(.18+.42*SPACE))`, less than .45 across the domain. With input bound X, delay bound is `X/(1-b)`, and the mixed bound is at most `2.64*X`. Drive and resonance stay outside the loop. Repeat interference means whole-render late energy need not be monotonic; the isolated-memory test checks its loss directly.

Missing or null model metadata decodes as model 1. New `TempestPatch` construction defaults to model 2; JSON, edits and velocity dispatch preserve the chosen model. Eight independently retained native PCM24 SHA-256 hashes passed, in addition to float replay checks. Unsupported models are refused by the constructor. Names and macro keys retain their original schema.

## Defaults

| Voice | TUNE | SOURCE | FILTER | DRIVE | SPACE |
| --- | --- | --- | --- | --- | --- |
| KICK | .22 | .10 | .65 | .35 | .15 |
| METAL | .70 | .65 | .55 | .45 | .35 |

## What was measured

44 affected checks passed with exit 0: 12 TEMPEST checks, the TEMPEST determinism canary, patch/recipe registries and preset/prototype consistency. All five sound controls must produce over 3% normalized difference energy at adjacent quarter steps after finishing, replacing the earlier 0.001% activity floor. This is a regression guard, not proof of audibility.

80 actual Kotlin cases were rendered; SOURCE adds .125/.375/.625/.875. Raw peak maximum 1.487040; absolute output mean maximum 2e-08; worst pre-fade last-10-ms RMS relative to raw peak -85.13 dB. Timing: Kotlin JVM on amd64, 17.0.21. Warm METAL default, 100 production renders: median 39.524 ms; p95 46.615 ms. Not a phone measurement.

Python Welch and temporal-energy measurements on the matched Kotlin files were recorded in the recovery backup's evidence records, which did not come back with this port. DRIVE adjacent-quarter spectral distances are about 3.6–5.7 dB, and FILTER about 2.5–7.8 dB across the two voices. KICK SOURCE also changes body decay substantially; spectral distance alone would miss that gesture.

The six higher-rate comparisons now use noise-free SOURCE 0, DRIVE 0/.5/1, FILTER/TUNE 1 and SPACE 0. Fourier fractional alignment avoids adding cubic interpolation error. Residuals include integration/filter differences: METAL cases are below -73 dB; KICK maximum drive is -46.34 dB and remains above the proposed -50 dB investigation threshold. Noise-bearing cases are not compared sample-for-sample across sample rates.

## Disclosed, not fixed

The 20 ms target is still unmet on the recorded desktop measurement; target phone unmeasured. High-drive KICK rate residual, noise-source alias investigation, sustained HOLD, hardware loading and Android picker promotion remain open. The finite sound is owner-accepted. These limits are disclosed rather than passed by changing an approved sound.

The prior 7,041-check full gate is R1 evidence. Model 2 ran affected checks through the root test selector using a temporary focused init script; other modules' expensive render suites were not repeated. No exclusions are installed in the repository. Reproduce the focused gate with `./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.TempestTest --tests com.snipsnap.synth.PatchesTest --tests com.snipsnap.synth.PadRecipeTest --tests com.snipsnap.synth.PresetsTest --tests '*DeterminismTest.TEMPEST*'`, and audition with `./gradlew --no-daemon :synth:generateTempestAudition`.

## Verified / measured / proposed / heard

Verified: source/patch dispatch and legacy replay on base `df675d0` plus these worktree changes. Measured: the named Kotlin tests and attached Python analysis of their exported WAVs. Heard: Josh supplied the R1 critique, then accepted the revision with “Sounds great, proceed as recommended.” The assistant makes no listening claim. Model 2 signal processing and defaults are unchanged during kit integration.

## Accepted finite integration

Six model-2 presets per voice promote the previously auditioned defaults and individual
macro positions into named recipes. They add no effects or new macro combinations.

| KICK | Change from KICK default | METAL | Change from METAL default |
| --- | --- | --- | --- |
| GROUND | none | SHARD | none |
| RUST | SOURCE .5 | WIRE | SOURCE 0 |
| GRIT | SOURCE 1 | GRAIN | SOURCE 1 |
| DULL | FILTER 0 | LOW | FILTER 0 |
| BREAK | DRIVE 1 | BURR | DRIVE 1 |
| AFTER | SPACE .75 | TRAIL | SPACE .75 |

`SynthKits.tempest()` supplies A01–A08 GROUND kicks at E1/G1/A1/B1/D2/E2/G2/A2,
A09 RUST, A10 BREAK, then all six METAL colors. The scale is a new arrangement of the
approved GROUND settings at those notes; no additional listening verdict is inferred for
the complete kit. Every pad is dry, one-shot, mono 44.1 kHz, 1.20 seconds, model 2,
HOLD 0 and carries a complete editable `PadRecipe`. There are no loops or velocity layers.
The shell starter ID is `tempest`, key E minor pentatonic. Android picker changes await an SDK.

TEMPEST is deliberately percussion. Its kit metadata now follows `Classifier.classify(snip)`
on the same rendered audio, including KICK and TOM where measured. This is an explicit
exception to the usual pitched-engine PERC policy, consistent with its two percussion voices.
No classifier or synthesis code was retuned to force a classification. Callers with an existing
snip can use `Tempest.drumClassFor(snip)` to avoid a second render.

CLI examples:

```sh
./gradlew :cli:run --args='synth TEMPEST KICK --all --out testkit/tempest-cli'
./gradlew :cli:run --args='synth TEMPEST METAL --preset 1 --macros SOURCE=.5,SPACE=.75 --out testkit/tempest-edit'
./gradlew :cli:run --args='synth TEMPEST METAL --patch saved.patch --out testkit/tempest-replay'
./gradlew :synth:generateTempestKit
```

The CLI writes WAV + `.patch`, preserves a recalled model through macro edits and supports
explicit `--model 1|2`. New patches use 2. An absent model remains 1 until explicitly upgraded.
Positive HOLD overrides are refused by the CLI because no sustained voice is implemented.

The kit task stages the complete folder, exports an MPC 3 `.xtd` and MPC 2 `.xpm` sharing
sixteen PCM24 WAVs, retains `kit.json`, verifies both exports with `ExportReadBack`, then
writes pad metadata and SHA-256 checksums before making the ZIP. MPC 3: load the root
`SnipSnap Tempest Kit.xtd`. MPC 2: load `SnipSnap Tempest Kit.xpm` from its TrackData folder.
Copy the whole kit folder intact. Reader verification is not hardware-load verification.

The integration run passed **69 focused methods**, zero failures, errors or skipped tests,
with both Gradle processes exiting 0. All sixteen recipe WAVs match exactly; the kit ZIP
checksums, payload and sample headers also pass independent checks.

The integration record is among the recovery backup's evidence records, which did not come back with this port.
Tests pin ten native WAV hashes from the published approved model 2 and retain all eight
model-1 hashes. Preset contracts, sixteen exact recipe replays, both export readers, CLI
current/legacy editing and the shell starter are checked. This is a focused integration gate;
the previous full 7,041-method result remains historical R1 evidence.


## Follow-up: six-voice direction, 2026-11-05

The owner requested a complete drum kit. [TEMPEST_FULL_KIT.md](TEMPEST_FULL_KIT.md) records
four new model-3 listening candidates and a separate full-kit audition. Accepted model-2 audio,
twelve presets and the original starter remain unchanged. New-voice preset promotion waits
for their listening verdict; model 3 is not a retune of the two accepted voices.
