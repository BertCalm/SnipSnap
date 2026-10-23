# OBLITERATE core — first listening experiment

The characteristic gesture is **a hit gains an octave edge → its recent past breaks into reversed, pitched fragments → left and right chop on a saved clock**.

This is an offline Python sound-design prototype, compared with actual SnipSnap Kotlin renders. It is not an Android effect implementation. Shimmer, the first stage of the original five-stage OBLITERATE concept, is deliberately deferred until the destructive core earns its place in listening.

## Choosing the landing

| Direction / operator | Gesture | Difference from the roster | Risk / cost |
| --- | --- | --- | --- |
| JUXTAPOSE: existing GRAINS → GATE | A cloud followed by rhythmic cuts | Available today; comparison control | Low engineering cost; legacy mono fold and normalization |
| STACK: all five original OBLITERATE stages | A halo collapses into destruction | Broadest version of the reference | More feedback, pitch and tail interactions obscure the first decision |
| DISTIL: octave fuzz → causal reverse/scatter → stereo chop **(selected)** | Attack, fragments, alternating cuts | Tests the specific destruction gesture on any input | Moderate prototype cost; pitched fragment interpolation and bass identity are the main risks |

The nearest siblings are OPENSKY's upward shimmer room and ORPHICA's fragmentation. They generate their own sources through their public synth entry points; neither supplies this complete any-input effect. They are reuse candidates for a later Kotlin extraction, not reasons to create another pitched synth engine. PedalBoard/Pedals already compose fixed PRE/AMP/POST selections over FxChain. Their fixed ordering does not provide this unusual chain or three arbitrary Epic-chain slots.

The lightest useful landing is this experiment. Production landing, if listening supports it, is a dedicated stereo effect with explicit tempo and bounded offline renders, followed by Android selection and export work.

## Contract

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

Production performance remains a gate: target tens of milliseconds for a representative two-second input on a named phone, with cached schedules keyed by clock, duration, amount and seed. Measure a Kotlin implementation before exposing it in the app. This prototype precomputes its event inventory once per render and caps input at 12 seconds / 1,024 events.

### Sonic risks and next landing

Owner listening must decide whether the bass keeps a useful identity, whether reversed fragments add more than the existing tools, and where stereo cuts become distracting. The legacy baseline's normalization and DC are preserved; do not infer that every raw reference meets the core's DC behavior. Fixed nonzero high/low-pass stages mean this proof does not establish a continuously transparent control arbitrarily close to zero. The tested listening choices are 35%, 70% and 100%, with a separate exact bypass.

If the core is worth keeping: implement and benchmark the dedicated stereo Kotlin processor, add shimmer as a separately auditioned stage, then wire saved clock/seed/versioned recipes into `BoardRecipe.kt` and the Android board selection flow. Existing WIDE/RUSH remain the implementation pattern, not evidence that these OBLITERATE surfaces are done. A freely assignable three-chain rack and OBSCURITY remain later work.

| Ledger | Status |
| --- | --- |
| Verified by source inspection | Current Kotlin GRAINS/GATE APIs, stereo/normalization limitations, existing board ordering |
| Measured in Python / generated PCM | 18 signal tests, full hit/tail/framing integrity, source/WAV hashes, fuzz-only alias reference, clock and bounded scheduling, host render cost |
| Proposed | Kotlin extraction, shimmer, production macro tuning and Android integration |
| Heard | Owner listening pending; no listening judgment inferred from numerical checks |

## Why it is built this way

- Fuzz makes the attack assert itself before fragments blur its identity.
- Recent-history grains make the reverse gesture testable on arbitrary input while keeping memory and work bounded.
- The saved clock lets a sample keep its rhythm after export. It needs an explicit tempo because there is no live host clock in a WAV.
- A stock comparison shows whether the new order contributes enough character to justify another effect.
- Delaying shimmer isolates the main sonic risk: whether fragments and stereo cuts remain useful on bass and short hits.
