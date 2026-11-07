# Resonant body model 2 candidate

Status: Luthier CONCEIVE following the initial listening review. Implemented as an explicitly selected candidate; full JVM validation, v1 archival compatibility and comparison replay passed. The owner prefers the model 2 timbres and finds them easier to distinguish. Output-level calibration and overall acceptance remain open.

Repository baseline: `1994c619ebcccff932cf0d69d5b7b61d16862651`. The owner reported weak distinction, then said "That's better, proceed as recommended" after the tighter endpoint A/B. This authorizes a new sound candidate. It is not a listening verdict on model 2.

**Heard, owner, 2026-11-05:** "It is quiet, they are easier to distinguish, I prefer them" after the v2 comparison. Record this as a preferred timbre direction and improved distinction, with a level issue still to resolve. The exact playback section and device route were not specified. Preserve these spectra and decay profiles during level calibration. Version 1 remains the default; this feedback does not set a new gain policy or promote the candidate automatically. The [level review](r1-level-calibration.md) separates processor output level from listening-master gain.

The characteristic gesture is **source attack → root-centred harmonic response or upper-weighted bar response → family-specific decay → bounded tail fade**.

## What changes

The same standalone `:audio` processor gains model version 2. Its authored mode weights and decay profiles give the two banks different spectral balances. DAMP changes mode losses without increasing their injection gain. This is an ideal linear modal filter, not a simulation or measurement of a physical material.

`BodySettings()` and `ResonantBody.MODEL_VERSION` remain on version 1. Select the candidate explicitly with `BodySettings(modelVersion = ResonantBody.CANDIDATE_MODEL_VERSION)`. The unchanged tuning, source-rate, channel, tail, dry/wet and output-guard contracts apply to both versions. Existing BODY, `Modes`, rack code, saved pads and factory presets do not change.

Legacy BODY and the `Modes` modal toolkit are the nearest existing mechanisms. This revision belongs in the existing shared processor, with no new engine or registration surfaces. The chosen direction is to change the spectra and loss laws; moving poles during a render or adding generated/nonlinear excitation would require a separate proof.

## Tables and equations

All values below are authored shape choices awaiting owner listening. The ratio tables retain their existing analytical/attributed basis from the v1 plan. There is no claim of measured material properties.

| Quantity | HARMONIC | BAR |
|---|---|---|
| Ratios, unchanged | 1, 2, 3, 4 | 1, 2.756, 5.404, 8.933 |
| Candidate weights, normalized once | .40, .32, .20, .08 | .04, .4992, .288, .1728 |
| Candidate decay scales | 1, .85, .65, .45 | 1, 1, .90, .80 |

The root-frequency law and nominal root T60 are unchanged:

```text
midi = round(36 + 36*TUNE)
referenceHz = 440 * 2^((midi-69)/12)
rootT60 = 0.05 * 12^RING, with the upper endpoint exactly 0.6 seconds
modeT60 = max(0.01, rootT60 * decayScale / (1 + 1.25*DAMP*log2(ratio)))
r = exp(-ln(1000)/(modeT60*sampleRate))
a = 1 - exp(-ln(1000)/(rootT60*sampleRate))
z[n] = exp(i*theta) * (r*z[n-1] + a*x[n])
```

The coefficient algorithm is version 2. The original algorithm/version 1 retains its exact tables, operation order, damping multiplier 4 and per-mode injection `1-r`.

Every candidate decay scale is positive and at most 1; ratios are at least 1 and DAMP is nonnegative. Thus `modeT60 <= rootT60`, giving `a <= 1-r`. Each rotation has norm 1 and `|z[n]| <= r*|z[n-1]| + a*|x[n]|`, so the existing input-peak state bound remains valid. Nonnegative mode weights summing to 1 and convex bank blending retain that bound. Fixed makeup 4 and the linked attenuation-only delivery guard are unchanged. No limiter supplies the stability argument.

The first mode in both banks remains at the reference frequency with the nominal root T60. Its different weight means the shared fundamental is no longer a constant-strength component through BODY. BAR may imply a different perceived pitch because its reference mode is much weaker. TUNE still promises a reference resonance; it does not promise BAR's perceived fundamental.

## Signal flow and cost

```text
version + TUNE/RING/DAMP → immutable tables → versioned coefficient cache
source at 44.1/48 kHz → independent four-mode banks → static BODY blend
source ──────────────────────────────────────────→ dry/wet MIX
                                                  → wet-tail fade + linked attenuation guard
```

MIX 0 returns the original object before PCM validation or coefficient work, including for model 2. Each active call still owns the same output buffer and eight two-component states per channel; optional wet capture is separate. Both versions use the same 8-entry instance LRU. Keys include the complete ordered table identity, weights, decay scales, damping strength, model/coefficient versions, sample rate, root and controls. No trigonometry, profile selection or new solver runs in the sample loop.

This remains a fixed linear filter below the delivery guard, using native-rate audio. It introduces no nonlinear excitation and needs no pitched-engine oversampling/output normalization. The render budget remains tens of milliseconds per one-shot. Desktop comparison timings are measured separately from physical Nothing Phone (3) timing.

## Control contract

| Control | Default | Candidate behavior and verification |
|---|---:|---|
| TUNE | 1/3 | Same C2–C5 snapped reference; coefficient frequencies and independent impulse spectra verify the mode locations. |
| BODY | 0 | Same static output blend; endpoint spectral/band comparisons prevent a collapsed numerical identity. They do not establish audibility. |
| RING | .5 | Same 50–600 ms nominal root T60 and tail bound; family upper-mode scales vary decay. Independent windows observe actual decay. |
| DAMP | .5 | Reduces upper-mode T60 with multiplier 1.25. Injection is independent of DAMP, removing v1's coupled input boost. Mixed-output brightness is not promised to be monotonic. |
| MIX | .5 | Same linear source/wet blend and one stereo-linked attenuation guard; exact disabled bypass and stereo isolation remain tested. |

## Gain and source tradeoffs

This revision does not synthesize missing inharmonic frequencies from a sustained tonal input. Reducing BAR's common-root weight can make its raw output substantially quieter on those inputs. The Python design harness measured BAR about 5.8 dB below HARMONIC on the default burst and 16.6 dB below it on the sustained harmonic fixture; these are **Python prototype** figures, not Kotlin or owner listening results. The rendered Kotlin comparisons are measured separately before delivery.

The processor performs no per-render normalization or automatic quiet-signal boost. Audition matching keeps the existing gain law `min(.1/Loudness.of(raw), .9/rawPeak)` and records every gain. Raw/unmatched comparisons accompany the matched comparisons so an improved matched timbre cannot conceal reduced wet level. A future gain-policy change would be another explicit model decision.

### Measurements of the Kotlin-rendered PCM

NumPy analysis of the final Kotlin output confirms the prototype's shape changes. These are measurements of saved PCM, not listening judgments. Correlation is scale invariant, at the original sample alignment; the root band is reference Hz ± max(15 Hz, 15% of reference Hz).

| Default source | v1 endpoint correlation | v2 endpoint correlation | v2 HARMONIC/BAR root-band energy |
|---|---:|---:|---:|
| 20 ms noise burst | .669 | .256 | 75.1% / 5.6% |
| Four-harmonic sustained source | .951 | .716 | 95.4% / 66.5% |

The contrast partly comes from keeping HARMONIC root-centred and shifting BAR toward its upper modes. This is not a claim that both families become brighter. The linear source-dependence remains.

| Raw RMS comparison | Burst | Sustained source |
|---|---:|---:|
| v2 BAR relative to v2 HARMONIC | −5.79 dB | −16.61 dB |
| v2 BAR relative to v1 BAR | −8.44 dB | −17.38 dB |
| v2 HARMONIC relative to v1 HARMONIC | −3.75 dB | −1.36 dB |

Thus a stronger matched contrast does not imply a stronger effect at MIX .5. The sustained BAR matching gain rises from ×2.13 to ×14.75. The page includes the actual unmatched outputs as well as matched ones.

All 24 raw and matched streams are finite and end at zero. Across these cases, raw delivered sample peak is at most .130914 and absolute mean at most .000118; matched peak is at most .9 and absolute mean at most .004168. These listening fixtures do not exercise the delivery guard at full scale; the audio tests cover the state and peak bounds separately. Independent Python equations agree with all 24 Kotlin cases within 7.45×10⁻⁹ absolute sample error. The final measurement report records all 75 declared source/raw/wet/matched file hashes and the original matching gains.

## Compatibility and reproducible comparison

The original audition and its strict implementation-source hash remain archival. Its old verifier intentionally refuses a changed implementation even when the v1 PCM is compatible. It has not been weakened to claim a new checkout has the old source hash.

Because the processor now accepts model 2, the original verifier explicitly requires recipe model 1 and cross-checks the rendered model/coefficient diagnostics. The original test asserts this refusal even though model 2 is otherwise valid. This prevents a v1-labelled recipe from selecting the candidate.

The new task performs a separate compatibility replay against the archived sources/recipes/output hashes:

```sh
./gradlew :synth:generateResonantBodyVersionAudition \
  -PbodyVersionOutput=/fresh/comparison/path \
  -PbodyVersionBaseline=/path/to/original/v1/pack
./gradlew :synth:generateResonantBodyVersionAudition \
  -PbodyVersionOutput=/fresh/comparison/path -PbodyVersionVerify
```

The output directory must be fresh. Original v1 sources, raw/wet PCM and matched WAV bytes are checked independently. Fresh comparison recipes record their actual model and coefficient versions, source hashes, gains, output hashes, implementation ledger, base Git commit and dirty status. Unsupported versions, corrupt assets/recipes, duplicate records, path escapes and missing assets are refused. No baseline file is rewritten.

The 24 new cases use the exact original generated burst, sustained harmonic and impulse sources. Defaults come first, with BODY 0/.5/1 for both versions; C3/C5 impulse and long-ring/zero-damping endpoints follow as diagnostic groups. No owner capture or commercial audio is included. The download is packaged separately into small lossless listening files and an embedded-audio page.

## Verification ledger

| Claim | Status | Evidence |
|---|---|---|
| Version 1 remains the default and explicit version 2 is separate | Verified | `BodySettings`, version diagnostics and alternating-version cache tests. |
| Existing v1 PCM is preserved | Measured, Kotlin | Seven pinned pre-edit delivered/wet PCM canaries and all 73 original audition source/output/wet replays, with matching-file hashes, passed. |
| Candidate numerical health and contrast | Measured, Kotlin | Six candidate tests cover control corners, bounds, silence/stereo, independent decay, root spectra, damping/injection and numerical separation from v1. |
| New comparison replay is exact | Measured, Kotlin | All 24 fresh cases replayed with recorded PCM, matching gain and file hashes; corruption/version/path refusals passed. |
| Full JVM gate | Measured, passed | Exit 0; 5,947 passing outcomes across nine modules, with no failures/errors/skips. |
| Model 2 timbre direction and distinction | Heard, preferred | Owner reports easier distinction and prefers the new sounds on 2026-11-05. Output remains quiet; full level/usefulness acceptance is unresolved. |
| Target phone performance | Unmeasured | Nothing Phone (3) gate remains open. |

Focused gate: `GRADLE_USER_HOME=/workspace/.cache/gradle ./gradlew --no-daemon --max-workers=4 :audio:test --tests '*ResonantBody*' :synth:test --tests '*ResonantBody*' :synth:generateResonantBodyVersionAudition -PbodyVersionOutput=/workspace/library-files/resonant-body-v2-render -PbodyVersionBaseline=/workspace/SnipSnap/testkit/resonant-body-audition`. Exit 0, `BUILD SUCCESSFUL`, 33 seconds. It covered 27 audio tests and 3 synth tests, then the 73-case compatibility gate and 24-case generation/replay. Implementation-content SHA-256: `f77ab5e8129f003dd213e8304af677be7a5a0e7c36baa920469c3a7614f77ce9`.

After explicitly tightening the original v1 recipe-version check, the three synth audition tests and the 73/24-case compatibility/generation gates passed again in 31 seconds. The final output is `resonant-body-v2-render-final`; its implementation-content SHA-256 is `8f9884d5cebb2c269e94ae42ed656bc2c3c1ae5f2e32b65655f85523765a293b`, and its manifest SHA-256 is `d4266adb0aad96c2a62141ac7b95f039f0b5e8109e96dd29647e0893fd0d7fb2`. A fresh JVM, outside the generating process, independently replayed all 24 comparison cases with exact PCM, matching gain and file hashes (exit 0).

The final full gate exited **0**, `BUILD SUCCESSFUL`, in **58m 56s**:

```sh
GRADLE_USER_HOME=/workspace/.cache/gradle ./gradlew --no-daemon --max-workers=4 \
  -I /workspace/library-files/resonant-body-v2-test-workers.gradle --continue test
```

The local worker override sets only `:synth`'s `Test.maxParallelForks` to 4, as in the original R1 validation protocol. It changes no test filters, assertions or repository/CI configuration. All nine JVM modules account for **5,947 passed, 0 failed, 0 errors, 0 skipped**. The final invocation executed 5,329 tests and reused 618 valid completed module outcomes through Gradle's up-to-date checks. No zero-test run is counted. The Android module is not included without an SDK; no Android or native-code validation is claimed for this Kotlin-only change.

| Module | Tests | Final invocation |
|---|---:|---|
| audio | 470 | Valid completed outcome reused |
| cli | 201 | Executed |
| json | 16 | Valid completed outcome reused |
| kit | 396 | Executed |
| loop | 421 | Executed |
| mpc3 | 93 | Valid completed outcome reused |
| shell | 1,234 | Executed |
| synth | 3,077 | Executed |
| xpm | 39 | Valid completed outcome reused |

The validation summary records the command, per-module results, complete log hash and the final implementation/delivery hashes. Current source-file hashes were checked against the final comparison manifest after the full run. Candidate acceptance and the physical-phone gate remain open.

The final desktop benchmark used the same generated one-second harmonic source, 44.1 kHz mono, BODY .5/RING 1/DAMP .5/MIX .5. Twenty warmups per version preceded 100 interleaved warm calls and 20 separately cleared-coefficient-cache calls per version. V1 warm p50/p95/max were 1.802526/2.124822/2.447820 ms; v2 were 1.793953/2.308264/5.313798 ms. These include the complete synchronous call, output and maximum tail; they are container/JVM timings, not fresh-process ART or Nothing Phone (3) measurements. The longer single v2 maximum is recorded rather than hidden.

### Small delivery and playback checks

The direct before/after FLAC is 339,912 bytes and 15.17152 seconds. For the burst, then the sustained source, it plays v1 HARMONIC, v1 BAR, v2 HARMONIC, v2 BAR, with that order repeated once. It retains complete saved PCM24 clips and short silent gaps. The 811,413-byte standalone page contains 24 matched cases, eight unmatched raw outputs and two dry sources, all embedded. It records original matching gains and the raw BAR level tradeoff; reference tuning is distinguished from perceived pitch.

Matched FLAC playback and the direct sequence decode exactly to the saved PCM24. Raw/dry float WAVs are converted to PCM24 for listening, without added gain or resampling; those playback copies are not float32 archives. Original WAVs and numerical evidence remain preserved. All cached encoding inputs were checked against the final source-file hashes after the verifier refinement.

Chromium playback, 393-pixel mobile layout, old/new sequence order, stopping a queued sequence by pausing, switching players, and the direct FLAC duration passed. There were no page errors or external requests. Browser checks used a temporary localhost server because the managed browser disallows `file://`; that policy was not changed, and this is not a physical-Android playback claim. HTML and JavaScript are self-contained after download.

## Why it is built this way

- Each family now has its own balance of resonances, so BODY changes more than upper frequencies around an equally weighted shared note.
- Separate decay profiles let those resonances persist differently while keeping the same bounded tail length.
- Fixed nominal-root injection lets DAMP change loss without also increasing the input push. Shorter-lived modes can consequently be quieter.
- Version 1 stays available byte for byte, so the owner can judge this candidate against the actual earlier sound.
- The risky choice is BAR's much lighter reference mode: it may improve contrast while weakening tonal inputs or shifting perceived pitch. Raw-level comparisons and owner listening decide whether that trade is useful.
