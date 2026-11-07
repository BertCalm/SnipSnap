# Resonant body handoff

Prepared 2026-11-07. Branch: `codex/resonant-body-v2`. The commit containing this document completes the candidate work on top of R1 baseline `1994c619ebcccff932cf0d69d5b7b61d16862651`.

## Current state

R1 is a standalone shared Kotlin processor in `:audio`, with tests and reproducible auditions in `:synth`. Model 2 is implemented, numerically validated, and preferred by the owner for its timbres and clearer distinction. **Output-level calibration and overall acceptance remain open.** Model 1 remains the default and exact compatibility baseline.

Continue the existing candidate; do not restart the historical R0 review. Read [the candidate specification](r1-model2-candidate.md), [the level review](r1-level-calibration.md), then [the original implementation ledger](r1-implementation-plan.md) for details.

No app/rack integration, saved-pad migration, factory preset, model 3, or new renderer gain policy has been implemented. Physical Nothing Phone (3) performance and real-capture usefulness remain unmeasured.

## Owner listening feedback

| Stage | Owner feedback | Meaning for continuation |
|---|---|---|
| Initial model 1 audition | “Not very much distinction” | Numerical activity was insufficient for musical acceptance. |
| Tighter model 1 endpoint comparison | “That's better, proceed as recommended” | Authorized the separately versioned model 2 candidate. |
| Model 2 comparison, Nov 5 | “It is quiet, they are easier to distinguish, I prefer them” | Preserve the preferred spectra and decay profiles; resolve level separately. |
| Gain-only comparison, Nov 7 | “I can't tell the difference in short tones, but do in the longer” | The preview used +1.5 dB on short clips and +12 dB on sustained clips. It does not establish a preferred engine gain. |

The last unanswered listening question was whether the louder long-note level is comfortable and useful. The playback section originally described as quiet and the speaker/headphone route were not specified. No gain setting or default promotion has owner acceptance. The assistant has not claimed to hear the clips.

The latest audition plays CURRENT → LOUDER for burst HARMONIC, burst BAR, sustained HARMONIC, then sustained BAR. It changes playback gain only. The separate raw trials use 0/+6/+12/+18 dB. No compression, limiter, resampling, new excitation, or Kotlin change was made for these previews. Source-group gains are an audition choice, not a duration-dependent processing rule.

## Recommended next work

1. Resolve comfortable playback level using the existing gain-only comparison at a fixed device volume. Preserve the preferred model 2 timbres.
2. Decide whether the remaining issue is presentation gain, processor wet gain, or both. A renderer gain change needs an explicit version/recipe decision and renewed boundary/guard validation. Do not infer +12 dB approval from the long-note comment.
3. Evaluate an owner-approved real capture and measure the complete render call on the Nothing Phone (3), including warm and cold/cache-cleared behavior. Desktop timing does not close the phone gate.
4. Decide overall candidate acceptance and default promotion only after those results. App/rack integration remains subsequent work.

One common gain cannot balance every source/family: raw v2 BAR is 5.79 dB below HARMONIC on the default burst and 16.61 dB below it on the sustained fixture. The sampled corpus has headroom, but a separate full-scale resonant stress invokes the linked output guard at +12 dB. Do not generalize the preview's headroom to arbitrary input.

## Implementation map

- `audio/src/main/kotlin/com/snipsnap/audio/ResonantBody.kt`: native-rate mono/stereo processing, static BODY blend, linear MIX, bounded tail/fade and linked attenuation-only guard.
- `ResonantBodyModels.kt` in the same directory: immutable versioned tables, coefficients and complete cache identity.
- `audio/src/test/kotlin/com/snipsnap/audio/ResonantBody{Version,Candidate,Cache,}Test.kt`: v1 canaries, v2 health/decay/spectrum tests, bypass and cache coverage.
- `synth/src/test/kotlin/com/snipsnap/synth/ResonantBodyVersionAudition{Generator,Test}.kt` and `synth/build.gradle.kts`: 24 explicit old/new cases, optional 73-case archival compatibility, strict receipts and replay.
- `scripts/source-transformation/`: portable compact delivery and gain-analysis helpers. Generated audio remains outside Git.

`BodySettings()` and `ResonantBody.MODEL_VERSION` remain version 1. Explicitly select v2 with `BodySettings(modelVersion = ResonantBody.CANDIDATE_MODEL_VERSION)`.

Model 2 retains the four frequency ratios per bank, while changing family weights/decay profiles and separating injection from DAMP. Fixed makeup 4, the .98 linked sample ceiling, source-rate policy and tail contract remain unchanged. BAR's much weaker reference mode can alter perceived pitch; TUNE specifies a reference resonance, not identical perceived pitch across bodies.

## Reproduction

From the repository root, use the checked-in Gradle wrapper with Java 17 or newer. The validated environment ran Java 21 targeting 17. `GRADLE_USER_HOME=/workspace/.cache/gradle` was environment-specific; use your configured cache elsewhere. Android SDK absence excludes `:app` from the JVM gate.

```sh
# Focused tests after DSP/generator changes.
./gradlew --no-daemon :audio:test --tests '*ResonantBody*' \
  :synth:test --tests '*ResonantBody*'

# Generate into a fresh directory, then replay the saved comparison.
./gradlew --no-daemon :synth:generateResonantBodyVersionAudition \
  -PbodyVersionOutput=/tmp/resonant-body-comparison
./gradlew --no-daemon :synth:generateResonantBodyVersionAudition \
  -PbodyVersionOutput=/tmp/resonant-body-comparison -PbodyVersionVerify

# Full JVM gate when implementation changes justify it.
./gradlew --no-daemon test
```

For the archival 73-case gate, add `-PbodyVersionBaseline=/path/to/original/v1/pack` to generation. That pack is not committed. Without it, the new task still generates and verifies 24 cases but cannot claim a new archival-compatibility run. The original v1 task's source-hash gate correctly rejects the old pack on changed source; use the version-comparison task for compatibility and retain the strict original gate.

The original task remains `:synth:generateResonantBodyAudition`, with `-PbodyOutput=/fresh/path`, optional `-PbodyVerify`, and optional owner-approved `-PbodyCapture=/path/to/private.wav`. No private capture or commercial demo is bundled.

Compact delivery instructions are in [the script README](../../../scripts/source-transformation/README.md). Python 3.9+, NumPy, SciPy and FFmpeg are required. The helpers consume saved Kotlin renders; gain analysis includes explicitly labelled Python equation probes. They do not alter the renderer. Use fresh artifact output directories.

## Validation already completed

The final Kotlin implementation/generator ledger is SHA-256 `8f9884d5cebb2c269e94ae42ed656bc2c3c1ae5f2e32b65655f85523765a293b`. Those four source files have not changed since validation; documentation and portable packaging were added afterward.

- Full JVM gate: exit 0, 58m 56s, **5,947 passing outcomes, 0 failures/errors/skips** across nine modules. Final invocation executed 5,329 and reused 618 valid completed outcomes. The workspace-only init script set synth test forks to four; no tests were filtered. No Android/native/phone claim.
- All **73 archival v1 cases** preserved source/output/wet PCM and matched WAV bytes. Seven pinned pre-edit delivered/wet canaries also pass.
- All **24 comparison cases** replayed exactly, including an independent fresh JVM. Version, hash, corruption and path refusal checks pass.
- Desktop v2 warm render p50/p95/max: **1.794/2.308/5.314 ms** for the specified one-second 44.1 kHz mono maximum-tail fixture. These are container/JVM timings.
- Compact pages passed Chromium playback, 393-pixel layout and switching/sequence checks with no page errors or external requests. FLAC decodes were verified against expected PCM24. This is not physical Android validation.

The historical machine-readable summary is [validation/r1-model2-validation.json](validation/r1-model2-validation.json). Full validation details and the module table are in the candidate specification. The full gate was not repeated for subsequent documentation or gain-only copies.

Final comparison manifest SHA-256: `d4266adb0aad96c2a62141ac7b95f039f0b5e8109e96dd29647e0893fd0d7fb2`. Receipts record base commit `1994c619…` and a dirty working tree because rendering preceded this handoff commit; their implementation-file hashes bind the actual tested source. Do not rewrite those historical receipts to imply a different run.

## Existing artifacts outside Git

These paths belong to the current workspace and do not arrive in a fresh clone:

| Path under `/workspace/library-files/` | Contents |
|---|---|
| `resonant-body-v2-render-final/` | Generated source WAVs, Kotlin-rendered raw/wet float WAVs, matched PCM24, recipes, manifest, metrics and benchmark. |
| `resonant-body-v2-before-after.flac` / `resonant-body-v2-comparison.html` | Original old/new listening delivery, 339,912 / 811,413 bytes. |
| `resonant-body-v2-louder-listen.flac` / `resonant-body-v2-level-comparison.html` | Latest gain-only delivery, 192,678 / 643,526 bytes. |
| `resonant-body-v2-level-probe/` | Gain report, raw-gain and listening-master asset records, browser checks. |
| `resonant-body-v2-mobile/` | Before/after delivery records, browser checks and validation summary. |
| `resonant-body-v2-full-gate.log`, `resonant-body-v2-fresh-replay.log` | Historical verification logs. |

The original 73-case pack is ignored at `/workspace/SnipSnap/testkit/resonant-body-audition/`. Preserve it separately when retaining archival evidence. New scripts make compact deliveries reproducible without retaining encoding caches. Prefer the sub-megabyte FLAC/HTML files for handoff; the earlier 22–23 MB bundles were too large for the owner's download path. No hosted audition URL is promised by this handoff.
