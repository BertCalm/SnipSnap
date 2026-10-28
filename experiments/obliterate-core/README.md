# OBLITERATE core — approved proof and Kotlin port

The characteristic gesture is **a hit gains an octave edge → its recent past breaks into reversed, pitched fragments → left and right chop on a saved clock**.

The first listening experiment is an offline Python prototype, compared with actual SnipSnap Kotlin renders. Josh kept all four examples. The same destruction core is now ported to a standalone Kotlin input effect; it has not been wired into Android. Shimmer, the first stage of the original five-stage concept, remains a separate next audition.

## Choosing the landing

| Direction / operator | Gesture | Difference from the roster | Risk / cost |
| --- | --- | --- | --- |
| JUXTAPOSE: existing GRAINS → GATE | A cloud followed by rhythmic cuts | Available today; comparison control | Low engineering cost; legacy mono fold and normalization |
| STACK: all five original OBLITERATE stages | A halo collapses into destruction | Broadest version of the reference | More feedback, pitch and tail interactions obscure the first decision |
| DISTIL: octave fuzz → causal reverse/scatter → stereo chop **(selected)** | Attack, fragments, alternating cuts | Tests the specific destruction gesture on any input | Moderate prototype cost; pitched fragment interpolation and bass identity are the main risks |

The nearest siblings are OPENSKY's upward shimmer room and ORPHICA's fragmentation. They generate their own sources through their public synth entry points; neither supplies this complete any-input effect. They are reuse candidates for a later Kotlin extraction, not reasons to create another pitched synth engine. PedalBoard/Pedals already compose fixed PRE/AMP/POST selections over FxChain. Their fixed ordering does not provide this unusual chain or three arbitrary Epic-chain slots.

The lightest useful landing is this experiment. Production landing, if listening supports it, is a dedicated stereo effect with explicit tempo and bounded offline renders, followed by Android selection and export work.

## Historical Python contract

```text
mono or stereo source, 44.1 kHz
  → oversampled octave fuzz + DC removal
  → bounded recent-history reverse/pitched grains
  → saved-clock alternating stereo chop
  → linked attenuation-only peak guard
  → finite stereo output + documented tail
```

Octave rectification and history reads are literal DSP. “Scatter” is seeded scheduling and pitch choice, not a physical model. No particular commercial pedal or complete hardware circuit is simulated.

| Control | Default | Meaning | Required evidence |
| --- | --- | --- | --- |
| AMT | 70% | Drives the three-stage intensity law; 0 is an exact bypass | Bypass equality, middle/low activity, output-level probes and source comparisons |
| BPM | 120 | Fixes chop period and fragment grid before render | Measured period against the configured clock |
| Division | 16 | Sixteenth-note grid | Explicit period in diagnostics and manifest |
| Seed | 20261023 | Repeatable fragment schedule | Fresh-call bit equality |

This is an effect contract: preserve input dynamics and stereo information; do not apply the skill's generic pitched-engine autolevel/HOLD contract. Nonzero processing may alter stereo relationships. Output protection only attenuates; playback matching is a separate downloadable artifact. The control values, filters, grain windows and pitch weights are **shape constants pending listening**, not calibrated physical measurements.

The prototype's public API is `Settings` and `render_core`; its diagnostics carry exact constants, scheduled grain counts, peak guard gain, timing and tail information. Scheduling and history length are bounded. Nonzero renders append exactly 0.75 seconds; bypass preserves input length. Compensated resampling FIRs and the final whole-file peak guard are offline operations. Only the captured grain spans are constrained to precede their event onsets; the complete effect is not a streaming-causal implementation. No feedback circulation, live tempo sync or seamless looping is claimed. Repeating clips resets effect state on each hit.

## Evidence and reproduction

`SourcesAndBaseline.kt` generates the source WAVs and the comparison through the current repository's actual `Grains.render` and `Gate.chop`. `sources.init.gradle` adds an isolated compilation task without changing app or synth production sources. The input manifest records source recipes and hashes. GRAINS scans the complete source, folds stereo to mono and normalizes internally; GATE uses a linked envelope and peak matching. Those are existing processor behaviors, not processing applied by this prototype. The reference keeps native pitch and uses explicit source recipes, amount, seed, BPM and division.

`prototype.py` adapts the separation of pure rendering, diagnostics and probes from the repository's versioned Luthier scaffold. It does not copy an engine algorithm or the mono `snipkit.finish` output stage. `generate_audition.py` creates Original, 35%, 70% and 100% core renders, stock references at all three amounts, cumulative stages, raw audio, level-matched audio and unchanged mono folds. The generated manifest is authoritative for file hashes, source revision, numerical measurements and Python host runtime. Its matching meter ports the `Loudness.of` formula in float64 on the mono average; it is not bit-exact Kotlin, LUFS or perceived stereo loudness. Stereo matching gain is shared with its mono fold. Headphone loudness can therefore differ between matched treatments.

Generated WAVs and manifests are ignored under `testkit/obliterate-core-inputs/` and `testkit/obliterate-core-audition/`. Use JDK 17, the repository Gradle wrapper and Python 3.12 (tested: 3.12.14). Install `requirements.txt` in your Python environment, then run from the repository root:

```sh
./gradlew --offline --no-daemon -I experiments/obliterate-core/sources.init.gradle :synth:generateObliterateInputs
python3 -m unittest discover -s experiments/obliterate-core -p test_prototype.py -v
python3 experiments/obliterate-core/generate_audition.py
python3 experiments/obliterate-core/validate_audition.py --require-current-head
```

`--offline` assumes Gradle dependencies are cached; omit it for first dependency resolution when needed. The retained input helper permits the inspected base or a descendant whose relevant production module files match the base, and records the actual rendering revision and dirty state. It refuses a changed source baseline rather than silently calling it the same comparison.

### Measured first-proof results

- All 18 focused Python signal tests passed (root rerun: 1.816 s, exit 0). They check bypass, finite silence, settled DC/release, captured span bounds, reverse/pitch reads, stereo/quiet scaling, deterministic fresh calls, clock periods/drift and sample-edge bounds.
- The 6 kHz **fuzz-stage** probe measured residual RMS 0.0003903 at 8× versus 0.0298226 at 1× against a 32× reference, a 37.66 dB improvement. This does not measure alias rejection of the complete effect. Pitched grains still use linear interpolation without a ratio-specific antialias filter.
- The first proof contains 30 unique clips / 120 PCM24 WAVs, 114,675,864 bytes, with two complete hits per clip. Durations are 2.78–5.95 seconds. All source/reference PCM retention, WAV/source hashes, repeated hits, common timing and silent gaps passed the independent validator. Mono rounding error was at most 0.5 PCM24 LSB.
- First-proof full-core renders took approximately 49–131 ms on an unisolated AMD EPYC Python host; concurrent load was uncontrolled. This is not Kotlin or Android performance. Independent worst-budget testing of a 12-second stereo input measured 1.24 seconds and at most 764 scheduled grains across the tested clock extremes.
- The initial 2026-10-24 JVM run was interrupted. It is superseded for the Kotlin port by the completed full-suite result below; the historical Python artifacts remain unchanged.

Production performance remains a gate: target tens of milliseconds for a representative two-second input on a named phone, with cached schedules keyed by clock, duration, amount and seed. Measure a Kotlin implementation before exposing it in the app. This prototype precomputes its event inventory once per render and caps input at 12 seconds / 1,024 events.

### Sonic risks and next landing

Owner listening on 2026-10-24 returned **keep for all four sources**, with these preferred amounts:

| Source | Verdict | Preferred amount |
| --- | --- | --- |
| Snare hit | Keep | 70% |
| One-bar percussion phrase | Keep | 35% |
| Stereo chopped nylon phrase | Keep | 35% |
| Decaying bass note | Keep | 35% |

The exact report and heard source revision are retained in `listening-003-2026-10-24.json`. No written comments or listening-mode selections were supplied. These results accept the core proof on these four examples; they do not establish an A/B win over the stock chain, a particular stereo/mono result, or acceptance of a future shimmer stage or Kotlin port.

**Proposed production starting amount: 35%**, with 70% retained as the preferred snare treatment. This follows three of the four source choices. It is a recommendation for the next implementation, not a change to the heard WAVs, current prototype API default of 70%, or automatic source detection. The approved core should remain the comparison reference while it is ported and while shimmer is auditioned separately.

The legacy baseline's normalization and DC are preserved; do not infer that every raw reference meets the core's DC behavior. Fixed nonzero high/low-pass stages mean this proof does not establish a continuously transparent control arbitrarily close to zero. The tested listening choices are 35%, 70% and 100%, with a separate exact bypass.

The standalone Kotlin core is implemented and host-benchmarked below. Next, audition shimmer separately and address render cost before wiring saved clock/seed/versioned recipes into `BoardRecipe.kt` and the Android board selection flow. Existing WIDE/RUSH remain the implementation pattern, not evidence that these OBLITERATE surfaces are done. A freely assignable three-chain rack and OBSCURITY remain later work.

| Ledger | Status |
| --- | --- |
| Verified by source inspection | Current Kotlin GRAINS/GATE APIs, stereo/normalization limitations, existing board ordering |
| Measured in Python / generated PCM | 18 signal tests, full hit/tail/framing integrity, source/WAV hashes, fuzz-only alias reference, clock and bounded scheduling, host render cost |
| Proposed | Shimmer, phone render-budget work, production macro tuning and Android integration |
| Heard | Josh's 2026-10-24 report: keep all four; snare 70%, percussion/pluck/bass 35%; no written comments or listening modes supplied |

## Kotlin port and edition 004

`ObliterateCore.render(snip, settings, stages = 3)` returns `RenderResult(audio, diagnostics)`. `Settings` defaults to AMT **0.35**, BPM 120, division 16 and seed 20261023. AMT 0 returns the exact same `Snip` with no tail; nonzero processing duplicates mono to stereo, preserves stereo channels independently, appends exactly 33,075 frames and applies a linked attenuation-only 0.98 sample-peak ceiling. Inputs are finite PCM in −1..1 at `WavWriter.MPC_SAMPLE_RATE`, one or two channels, at most 12 seconds. Amount, BPM, division, unsigned-32-bit seed and stage count are validated. This is an offline input effect, without engine registration, presets, HOLD, shimmer, feedback, live sync or a seamless-loop claim.

The 8× FIR, Double SOS state and NumPy-compatible SeedSequence/PCG64 schedule preserve the approved reference. Shared Float effects or a different random generator would change that reference. The RNG helper contains its MIT attribution and license; FIR/SOS coefficients were generated with SciPy 1.17.0. Pitch-shifted grains still use linear interpolation without ratio-specific antialias filtering. Exact bypass is distinct from near-zero nonzero processing, which retains fixed filters.

The port was built on upstream snapshot `3032c781f8a557cec6d16c8a3b15b8f1b6cb9ef4`, merged into local checkpoint `126a28edc5168455fe0d798cfd5d65850dbafaca`. The heard Python source is `9b55dc821787dc60827b49d48d927d0ced89d723`; its processor SHA256 is recorded in the new reference manifest. Historical source-generation hashes refer to that older producer, not current main. The retained source WAVs are used directly; do not rerun `SourcesAndBaseline.kt` on changed production sources and call them the same listening set.

### Measured port results

- **16 Kotlin tests** passed, including independent Python audio/event fixtures, exact bypass, validation, silence/DC/release, stereo isolation and quiet scaling, seed-dependent schedules including skipped draws, grain-history bounds, clock drift, peak guard, repeat determinism, and maximum input duration.
- **36 same-input render cases** passed: four retained sources × three amounts × three cumulative stages. Maximum absolute Float32 output difference was **1.4901161193847656e-8**, maximum RMS difference **7.176162403617192e-11**. Predeclared limits remain 2e-6 maximum / 2e-7 RMS; geometry, tail and clock checks also pass. This is close numerical agreement, not bit identity or a listening verdict.
- Actual Kotlin `WavReader` decodes PCM24 with divisor 8,388,607 into Float32; the old Python proof used 8,388,608 in Float64. The comparison replays the actual Kotlin-decoded input through both processors to isolate port error. Separately measured decoder-only differences were at most 1.1921e-7 at input and 1.1454e-7 at Python output, RMS 6.66e-9. Edition 004 labels this a Python replay and leaves all 120 edition 003 WAVs unchanged.
- The new page contains **28 unique clips / 112 PCM24 WAVs**, 109,089,680 bytes, with raw/matched stereo/mono, repeated complete hits, unchanged folds and full finite tails. An independent read-only validator checks every hash, the actual decoder boundary, retained edition 003 assets, PCM rounding, common gain and framing. Mono differences are bounded by 1 PCM24 LSB; common-gain reconstruction by 0.942 LSB.
- Warm **host JVM** median public-render times were **104.64–458.06 ms** on the retained sources (3 warmups, 7 trials, JDK 17.0.20.1, Linux amd64, four available CPUs). Other load and GC were uncontrolled. This misses the intended tens-of-ms interaction budget on this host. Phone performance and schedule caching remain unmeasured/unimplemented; no Android readiness claim follows from parity.
- The complete nine-module JVM suite finished with **6,755 tests, zero failures/errors/skips, Gradle exit 0** on 2026-10-26. Invocation: `./gradlew --offline --no-daemon --max-workers=4 -I /workspace/scratch/obliterate-test-execution.init.gradle --continue test`, with no SDK in the graph. The untracked init script changes only scheduling: up to three synth/shell test forks, the existing LOOP latency suite after test compilation and before other test tasks. No test filters or exclusions were used. Earlier runs exposed contention in LOOP's 50 ms guard and the new core's duplicated sample-rate constant; the latter now aliases `WavWriter.MPC_SAMPLE_RATE`. Both pass in the completed run. Native and Android code are outside this change.

### Reproduce the port comparison

Use the retained `testkit/obliterate-core-inputs` source WAVs and original `testkit/obliterate-core-audition` artifacts. They are required evidence, not tracked binary distribution. With the pinned Python requirements and JDK 17:

```sh
python3 experiments/obliterate-core/kotlin_reference.py export
./gradlew --offline --no-daemon -I experiments/obliterate-core/port.init.gradle :synth:renderObliteratePort
python3 experiments/obliterate-core/kotlin_reference.py compare --wavs
python3 experiments/obliterate-core/validate_kotlin_audition.py
./gradlew --no-daemon test
```

Outputs go to ignored `testkit/obliterate-kotlin-audition/`. `reference-inputs.json`, `kotlin-port-results.json` and `parity-report.json` record exact source hashes and clean/dirty state. Fixture regeneration is explicit (`kotlin_reference.py fixtures`) and is not part of normal tests. The original Python processor, default, generator and heard WAVs have not changed. Published recipes and all parity results are downloadable from [edition 004](https://snipsnap-wide-audition.bertcalm.chatgpt.site/obliterate/kotlin/); the [approved proof](https://snipsnap-wide-audition.bertcalm.chatgpt.site/obliterate/) remains available.

| Ledger | Status |
| --- | --- |
| Verified | Standalone public Kotlin API, bounded offline stereo contract, preserved historical artifacts; no Android wiring |
| Measured | 16 port tests, 36 render comparisons, 112 new WAVs, complete 6,755-test JVM run and host timing |
| Proposed | Shimmer audition, render-budget improvements and later versioned board/app integration |
| Heard | Josh kept edition 003 at snare 70%, other sources 35%. Kotlin listening remains pending. |

## Why it is built this way

- Fuzz makes the attack assert itself before fragments blur its identity.
- Recent-history grains make the reverse gesture testable on arbitrary input while keeping memory and work bounded.
- The saved clock lets a sample keep its rhythm after export. It needs an explicit tempo because there is no live host clock in a WAV.
- A stock comparison shows whether the new order contributes enough character to justify another effect.
- Delaying shimmer isolates the main sonic risk: whether fragments and stereo cuts remain useful on bass and short hits.
