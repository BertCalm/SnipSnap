# Resonant body: R1 implementation plan

Status: R1 desktop implementation and validation complete after the owner's subsequent go-ahead. Sections 1–13 preserve the R0 proposal; the [As built ledger](#as-built-r1) distinguishes implemented behavior, actual measurements, deviations, and remaining phone/listening acceptance gates. The document alone does not authorize later stages.

Repository inspected: `4e36a2bf8cf8bfb07dba35379558b54429b5d73d`. See [repository-review.md](repository-review.md) for located declarations and the manual evidence. The owner named a **Nothing Phone (3)** as the performance target; its actual OS/runtime and all phone results remain to be recorded.

The characteristic gesture is **source attack → chosen resonant body → static blend of two spectra → controlled ringing release**. This is a source processor. It does not synthesize an exciter, track source pitch, simulate a physical object, or become a registered engine.

## 1. Scope and landing

Implement one shared `:audio` processor and a standalone audition generator using the existing `:synth → :audio` dependency. Preserve legacy BODY, every existing engine render, rack execution/order, saved recipe JSON, and delivered kit audio. Existing APIs and file evidence are in the R0 review; proposed names here must be checked for collisions before implementation.

R1 includes two fixed four-mode families, static output crossfade, reference tuning, per-mode decay/damping, dry/wet, exact bypass, independent mono/stereo channels, diagnostics, bounded coefficient caching, tests, and replayable audition assets.

Defer the conventional parallel filter, motion, coefficient morphing, source pitch/formant transformation, HOLD, looping, width, product CLI command, app UI, rack/board adapters, saved pad schema changes, and factory preset bank. The owner must hear the body proof before those expansions. Generated fixtures can establish the technical proof; a real dry capture is required before claiming usefulness for recorded sources.

## 2. Proposed units and data structures

All these names are **new**, not located repository APIs:

| Proposed unit | Responsibility / ownership |
|---|---|
| `BodyFamily` | `HARMONIC` and `BAR`, each with an immutable four-mode table, stable ID, version, ratio list, normalized nonnegative weights, and provenance string. |
| `BodySettings` | Model version, family pair, TUNE/BODY/RING/DAMP/MIX. Validate finite 0..1 values; reject unsupported version/family pairs. Defaults below. |
| `BodyTableId` | Value identity of version, ordered family IDs, mode count and exact ratio/weight definitions. No mutable arrays exposed. |
| `BodyCoefficientKey` | Table identity plus coefficient algorithm version, sample rate, settled root Hz, ring seconds, and damping. |
| `BodyCoefficients` | Immutable per-mode pole radius and precomputed sine/cosine/injection coefficients; one set per family. |
| `ResonantBody` | Processor instance owns an eight-entry coefficient LRU. `process(source, settings)` returns `BodyRender`. No global mutable resonator or source cache. |
| `BodyRender` / `BodyDiagnostics` | Audio plus settled controls/version/table identity, active counts, tail frames, raw state bound/peak, pre-guard mixed peak, and output attenuation. Timing stays outside the deterministic audio/recipe contract. |

Fresh two-component mode state belongs to each render/channel. Coefficients may be shared; state may not. A source is borrowed read-only. At zero MIX return the identical input `Snip` and array in the result before rate/PCM validation, table lookup, cache access, or audio-buffer allocation. Validate the settings themselves first. No pitch analysis exists in this path.

Enabled processing accepts mono/stereo 44,100 or 48,000 Hz, 1 frame through 8 seconds, with finite PCM samples in [-1,1]. Refuse unsupported rates, overlong/empty input, non-finite or out-of-range samples by name; no downmix, resampling, clipping, or silent sanitization. This proof's input range is narrower than `Snip`'s general structural contract. Disabled bypass preserves any structurally valid `Snip` without inspecting PCM. A future adapter must decide how to handle out-of-range float material explicitly.

## 3. Families and root definition

| Family | Ratios relative to reference Hz | Basis / claim |
|---|---|---|
| HARMONIC | 1, 2, 3, 4 | Ideal harmonic series; the zero-stiffness limit of the law documented in existing `Modes.stiffString`. This is an analytical spectrum, not measured wood or a string-body simulation. |
| BAR | 1, 2.756, 5.404, 8.933 | Reuse the four rounded free-free bar ratios attributed to Euler–Bernoulli eigenvalues in `Modes.kt:159–161`. Copy only these documented constants into the new versioned table, with attribution and a test against the agreed R0 values. Do not import or expose `Modes`. The original cited publications were not re-fetched in R0. |

Both start with authored unnormalized weights `[1, 0.65, 0.4, 0.25]`, divided by their sum. These weights, the fixed makeup below, and all decay laws are **shape hypotheses**, not measured material properties. Keep them in the new model's versioned definition. Both families retain a ratio-1 mode, while upper modes differ strongly. Four is a deliberate override of the earlier tentative 16–32 count, not a truncated claim of equivalence to the commercial reference.

For HARMONIC, test root pitch on broadband-excited clean fixtures with reported detector confidence and octave outcomes. BAR promises the location of its reference resonance only. At mixed BODY positions both sets of upper resonances exist; frequencies do not interpolate. Static blending can cancel or reinforce particular frequencies through phase differences. Do not call it a continuously changing physical material.

TUNE spans MIDI 36–72 in snapped semitone steps: `midi = round(36 + 36*TUNE)`, `referenceHz = 440*2^((midi-69)/12)`. BODY does not alter that reference. The highest proposed R1 mode is below 4.7 kHz even at MIDI 72, comfortably inside both supported rates. Coefficient construction rejects invalid/out-of-band table entries; a future model needing pruning must introduce and test an explicit taper/active-count policy.

## 4. Control contract

All mappings and acceptance targets here are proposed. R1 has no hidden source-transform control or HOLD.

| Control | Normalized default | Physical mapping | Interaction and proof |
|---|---:|---|---|
| TUNE | 1/3 | MIDI 48, C3 ≈130.813 Hz by the law above; range C2–C5. | TUNE × BODY: reference-mode frequency stays fixed across the blend. |
| BODY | 0 | `wet = (1-BODY)*harmonic + BODY*bar`; endpoints may skip the unused bank. | BODY × DAMP: level-matched spectra and impulse responses differ at low/mid/high settings. |
| RING | 0.5 | Root T60 `0.05 * 12^RING` seconds: 50–600 ms, default ≈173.205 ms. | RING × DAMP: root decay follows RING, high-band decay shortens with DAMP. |
| DAMP | 0.5 | Mode T60 `max(0.01, rootT60/(1 + 4*DAMP*log2(ratio)))` seconds. | Distinguish a changed decay slope from simple high-mode attenuation. Floor plateaus must be disclosed in corner results. |
| MIX | 0.5 | Linear amplitude blend of source and wet, followed by one attenuation-only output guard. | MIX=0 exact bypass; MIX × RING retains input timing while bounding the added tail. |

No random family construction or random playback state is needed. Fixture noise has an explicit fixed seed stored in its replay description. A different model version is required if tables, gain, decay, tuning, or fade laws change after accepted auditions.

## 5. Signal flow, stable resonators, and gain

```text
settings ──> validate / settle ──> versioned tables ──> cached coefficients
source ─────> exact MIX=0 bypass
    |
    +─> read PCM at native rate ──> independent HARMONIC and BAR banks
    |                                  |
    |                          static convex BODY blend
    |                                  |
    |                      fixed makeup ×4; wet tail fade
    |                                  |
    +──────────────────────────> linear dry/wet MIX
                                       |
                       one stereo-linked attenuation guard ──> Snip + diagnostics
```

The source rate is the internal rate: 1×, 44.1/48 kHz. This fixed linear filter generates no new frequencies. Do not apply the pitched engines' 4× nonlinear output pipeline, melodic loudness target, or per-render normalization. A nonlinear stage or time-varying filter would reopen that decision and require separate antialiasing work.

Proposed per-mode quadrature recurrence, Double state and coefficients:

```text
theta = 2*pi*referenceHz*ratio / sampleRate
r = exp(-ln(1000)/(modeT60*sampleRate))
z[n] = exp(i*theta) * (r*z[n-1] + (1-r)*x[n]), z[-1] = 0
modeOutput[n] = imag(z[n])
bankOutput[n] = sum(normalizedWeight[k] * modeOutput[k,n])
```

This is an explicit proposed alternative to the existing unnormalized two-pole strike renderer. It is not a claim that `Modes.ring` already implements it. Precompute the real two-component update; no complex object or trigonometry in the sample loop. Use the previous real/imaginary state for both components before overwriting either.

Mathematical bound in exact arithmetic: rotation has norm 1 and `0 < r < 1`, so `|z[n]| ≤ r*|z[n-1]| + (1-r)*|x[n]|`. Starting at zero, a bounded source gives `|z| ≤ sourcePeak`. Nonnegative weights summing to one preserve that bound for each bank and their convex BODY blend. After excitation stops, each state's norm decays by r per frame, giving its specified T60. The bound is independent of a final limiter; Double implementation still needs finite/state-bound tests and a numerical tolerance.

Fixed wet makeup is **4**, an audition hypothesis. Thus pre-guard mixed magnitude is bounded by `(1-MIX + 4*MIX)*sourcePeak`, at most 4 for supported PCM. This may be conservative on short taps and too strong on sustained resonant tones; expose the trade with raw output. Never peak-normalize individual modes, banks, impulses, or complete renders.

After wet fading and dry/wet combination, compute one common output factor `a = min(1, 0.98/mixedPeak)` for nonzero mixedPeak, else 1. Apply a equally to every sample/channel. This only attenuates; it neither boosts quiet/noise residue nor independently changes stereo channels. Record a and the unguarded peaks. Zero MIX bypasses it. Below its threshold the whole processor remains linear in input amplitude; above it, source dynamics and dry level are intentionally attenuated. The 0.98 limit is a sample-peak target, not a true-peak/export guarantee. Measure reconstructed peaks separately in auditions.

Silence remains bitwise silence. Do not subtract a render's mean or add a high-pass/normalizer to conceal a failed DC or gain test. A failing raw-DC gate returns the design for revision before approval. This is a filter intended to shape existing audio, not automatically level it like a new melodic engine.

## 6. Time, channels, tail, and allocations

Run channels independently with the same coefficients and zero initial state. Equal inputs must produce equal channels; a silent right channel must stay silent. No stereo spread or crossfeed. There is no lookahead or block buffering, so dry and wet share the same sample clock. Resonator phase/group delay is the effect; do not shift the dry attack to align an impulse peak and call that latency compensation. The first wet sample follows the defined recurrence. Compare it with an analytical impulse response to expose unintended extra sample delays.

For enabled processing append `ceil(rootT60*sampleRate)` frames, processing zero input after the source ends. Fade only wet audio during the last `ceil(0.010*sampleRate)` output frames, using a linear per-frame gain that reaches exactly zero on the last frame. Use the same gain on both channels. The dry source is zero-padded; never fade its original end. This differs from the existing `Cleanup.applyFades` endpoint law, so implement the small explicit wet gain inside this processor, without modifying that shared API.

The tail is at most 0.6 s and contains at least the slowest mode's T60. The fade deliberately modifies the final 10 ms; measure decay before that window. No late ringing is silently cut by an unrelated rack. Zero MIX appends nothing. An output longer than the classifier's 1.5 s boundary may classify as LOOP; report that result instead of forcing a pitched/drum label. There is no serialized pad update, export path, or loop claim in R1.

Stream both bank sums into one output array rather than allocating complete audio per mode or bank. Fresh state is only eight two-component modes per channel, plus output/diagnostics. Proposed allocation target: total allocated bytes per enabled call ≤`3*outputSampleCount*4 + 256 KiB`, excluding source and static cached data; this is a target to measure, not a memory result. No cached PCM or mutable state is retained after the call.

## 7. Cache contract and cost

The four-entry family definitions are immutable versioned constants; they do not need a dynamic table cache. The ordered pair and complete table definitions form `BodyTableId`.

The coefficient key contains `BodyTableId`, coefficient-algorithm version, sample rate, and exact settled root/ring/damping values. Represent numeric key components canonically after validation/settling; do not key on a rounded display string. Table identity includes counts/ratios/weights, preventing reuse across families or version changes. BODY and MIX are deliberately excluded: R1 applies them after the stationary banks, so changing them must hit the same coefficient entry. Source hashes and channel count are excluded because coefficients depend on neither; input/state are never cached. Any future coefficient morph must add its morph position to the key.

Each processor instance owns an eight-entry LRU; synchronize cache mutation and keep returned coefficients immutable. Concurrent renders use independent state. Caching disabled, a cold cache, a warm cache, and a cache with unrelated evicted keys must yield bit-identical PCM in the same runtime. Test rate/root/ring/damping/table/version changes separately and assert misses. A version/table test can use a test-defined valid alternate table; do not ship an invented extra family to test invalidation.

Cost is O((sourceFrames + tailFrames) × activeModes × channels), not O(source duration alone). At BODY=0.5 and maximum RING, a one-second 44.1 kHz mono source needs 70,560 frames ×8 = **564,480 mode updates**, before output scans. This is an arithmetic workload count, not timing. Cache hits remove coefficient construction, not this audio work. Endpoints may use only four modes; the phone gate must use both banks.

## 8. Exact proposed file changes

Paths below are from the repository root. New files and declarations are proposals. No new production dependency, module, or lockfile update is required.

| File | Change in the later implementation task |
|---|---|
| `audio/src/main/kotlin/com/snipsnap/audio/ResonantBody.kt` | New public typed processor/settings/result/diagnostics contract, validation, stream processing, wet fade, guard. |
| `audio/src/main/kotlin/com/snipsnap/audio/ResonantBodyModels.kt` | New versioned immutable families, units/provenance, coefficient/key/cache helpers internal to `:audio`. |
| `audio/src/test/kotlin/com/snipsnap/audio/ResonantBodyTest.kt` | New functional, numerical, format, bypass, gain, decay, and state-isolation tests. |
| `audio/src/test/kotlin/com/snipsnap/audio/ResonantBodyCacheTest.kt` | New key invalidation, cache parity, eviction, and concurrent-render tests. |
| `synth/src/test/kotlin/com/snipsnap/synth/ResonantBodyAuditionGenerator.kt` | New public-API client using generated inputs plus an explicit optional capture path. Writes replay records, WAVs, metrics, and page. |
| `synth/src/test/resources/audition/resonant-body-audition.html` | New self-contained local listening-page template; raw/matched labeling and exact cases. |
| `synth/build.gradle.kts` | Add only `generateResonantBodyAudition`, a test-runtime JavaExec task, following the located audition-task pattern. |
| `.gitignore` | Ignore `/testkit/resonant-body-audition/`; retain generator/template source in Git. |
| `docs/plans/source-transformation/r1-implementation-plan.md` | Append an As built ledger with actual changes, test commands/exits, deviations, and listen/phone status. |

For the device gate, the subsequent SDK-equipped task additionally adds `app/src/androidTest/kotlin/com/snipsnap/app/ResonantBodyPerformanceTest.kt` and the minimal benchmark build configuration in `app/build.gradle.kts`. The proposed `bodyBenchmark` build type inherits release settings, is non-debuggable and test-signable, and selects the instrumentation target via a property scoped to the benchmark invocation. Preserve ordinary debug/release behavior. Use the existing Android JUnit runner; a new benchmark library is not required for this initial gate. Compile/test that variant on an SDK-equipped machine before claiming the measurement harness works.

Explicitly unchanged in R1: `Body.kt`, `Modes.kt`, `Grains.kt`, `Dsp.kt`, `FxChain.kt`, `PadRecipe.kt`, the `Patch` registry, existing tests/goldens/fixtures, shell/CLI/app product flows, kit serialization, dependency declarations other than the documented task/build-type wiring, and all locks. No recipe migration or legacy render version bump is needed because none of those paths uses the new processor.

## 9. Meaningful test matrix and initial targets

Targets below are proposed acceptance gates for R1, not reported passes. Failures must be diagnosed; do not loosen a target or add normalization merely to turn a failure green.

| Test | Fixtures / method | Initial acceptance |
|---|---|---|
| Bypass | MIX=0 for short/empty, unsupported positive rates, mono/stereo, signed zero and arbitrary structurally valid PCM. | Same `Snip` and sample-array identity, length/rate/channels; no PCM scan or coefficient work. Settings still validate. |
| Validation | NaN/Inf controls or enabled PCM, unsupported rates/versions, empty/overlong source, out-of-range PCM. | Named refusal before DSP; never partial output or silent coercion. |
| Determinism | Every control corner, both rates/channels; repeated and interleaved renders. | Exact sample-array equality in one runtime; no cross-device bit-identity promise without evidence. |
| Channels | Left-only impulse, equal-channel tone, unequal stereo material. | Zero crossfeed before the common guard; equal channels exact; same format; one common guard factor. |
| Pole/state bound | Impulse, noise, DC and tones exactly at/near every mode; maximum source peak; all control corners. | `0<r<1`; raw complex state norm ≤inputPeak×(1+1e-9)+1e-12 in Double; no non-finite internal or output values. |
| Decay | Impulse and a sustained tone followed by independently rendered silence; state-envelope and band fits before end fade. | State T60 error ≤2% plus one sample; isolated-band fit ≤10% after accounting for overlap/noise floor. Deliberately set r>1 in a test-only mutant to prove the growth test fails. |
| Spectral location | Long impulse/reference transfer function at C2/C3/C5, BODY 0/.25/.5/.75/1. | Expected resonance location within two FFT bins; do not mistake the broad peak of a short-decay mode for exact pole frequency. Check coefficients and spectrum separately. |
| Harmonic pitch | Broadband-excited HARMONIC only, C2/C3/C5, low/default/high decay/damp. | Candidate 25-cent target when detector confidence ≥0.3; list low-confidence and octave errors as failures/limits, not passes. BAR uses reference-mode checks. |
| Silence / DC | Exact silence and zero-mean harmonic/noise fixtures; impulse DC is assessed against the analytical filter, not assumed zero. | Silence exactly zero. For defined zero-mean fixtures, delivered mean magnitude ≤0.005; raw means and any failure disclosed. |
| Gain | On-resonance sustained tones and broadband sources at several levels. | State bound holds before guard; final sample peak ≤0.980001; when guard is inactive, scaling source by 0.5 scales output by 0.5 within float tolerance. No per-render matching. |
| Controls | Low/mid/high and RING×DAMP, TUNE×BODY, MIX×RING corners, same input. | Matched spectral distance >0.01 for selected non-degenerate BODY comparisons and a demonstrable decay change for RING/DAMP; a frozen-control mutant must fail. Floors/degenerate inputs are tested separately. |
| Tail / boundaries | 1-frame source, ordinary hit, eight-second source, both rates/channels. | Exact N+ceil(T60×rate) frames, ≤0.6 s added, no change to dry timeline, final wet frame zero; MIX=0 adds zero. |
| Aliasing / reference | High-frequency multitone and analytical stationary-filter frequency response. | No new spectral lines above a -80 dB relative tolerance on coherent steady fixtures after startup; no out-of-Nyquist pole. Quantify residual/transient leakage rather than labeling every residual aliasing. |
| Cache | Toggle each key component; change only MIX/BODY/source/channel count; evict; render concurrently. | Correct hits/misses, ≤8 entries, exact cached/uncached audio parity, no leaked state. |
| Legacy behavior | Existing BODY tests, modal/effects/recipe suites and the repository test gate. | No updated expected audio, JSON fixtures, goldens, or weakened assertions. |

R1 implementing commands from `/workspace/SnipSnap`, using the environment's documented `GRADLE_USER_HOME`:

```sh
./gradlew --no-daemon :audio:test --tests '*ResonantBodyTest' --tests '*ResonantBodyCacheTest' --tests '*BodyTest'
./gradlew --no-daemon :synth:test --tests '*ModesTest' --tests '*FxTest' --tests '*PadRecipeTest' --tests '*VoxGrainsTest'
./gradlew --no-daemon :synth:generateResonantBodyAudition
./gradlew --no-daemon test
```

The generator task does not exist in R0. These commands are future instructions, not commands run for this documentation pass. Do not exclude `:app:test` when no SDK exists; the repository omits `:app` automatically. The full gate includes expensive render tests; allow them to complete and report exit status, counts, failures, and skips separately. A docs-only pass does not require an application build.

## 10. Audition and replay outputs

Produce `testkit/resonant-body-audition/{index.html,manifest.json,metrics.csv,recipes/,sources/,raw/,matched/,diagnostics/}`. Use WAV output and test-side JSON utilities already available to `:synth`; no new codec or DSP dependency. The page is a standalone artifact, not a localhost preview. Keep original dry, each raw wet endpoint/blend, and delivered dry/wet output available. Matched examples are separate files with their gain listed; do not alter raw audio.

Generated inputs: impulse at 0.8 amplitude, 20 ms seeded noise burst with a fixed envelope, one-second harmonic tone at the chosen reference, one-second sustained on-resonance tone, stereo left-only/equal/unequal fixtures, and silence. Define the exact generator algorithm, seed, rate, duration, level and samples hash in the manifest. Add an owner-approved dry tap/capture only with recorded provenance and redistribution scope; no bundled commercial demo audio. Do not make private captures public by default.

Required cases: both endpoints and BODY .25/.5/.75; TUNE C2/C3/C5; RING/DAMP corners; MIX 0/.25/.5/1; maximum resonance with sustained input; exact tail/end fade; mono/stereo isolation; original versus processed capture. Pair corners and record case IDs so claims refer to specific WAVs. No blind large preset bank.

Each replay record includes source-relative path and content hash, format, model/table/coefficient versions, exact normalized and settled settings, frame/tail counts, mode count, guard factor, raw peaks, output hash, and generator commit. Embed or load the same records into the page. Add a replay check that reads a saved record and reproduces the output bytes; reject changed source hashes, unknown versions, or missing files. Schema is an audition-manifest schema only, not `kit.json` or a new `Patch`.

Ask the owner: are the bodies distinct at matched level; are intermediate blends useful; does the dry attack remain useful; are taps too weak or resonant tones over-attenuated; does the result earn a pad? Only owner feedback on named cases enters the Heard ledger.

## 11. Nothing Phone (3) performance gate

Run on the owner's physical Nothing Phone (3); record Android version, ABI, ART/runtime, app/build commit, build variant, charging/power/thermal state, and instrumentation invocation. No emulator or desktop Python/Kotlin time can satisfy this gate. Actual build/OS details are unresolved inputs to collect during the device run, not guessed facts about the phone.

Workload: a generated one-second mono source, 44.1 kHz, BODY=.5, RING=1, all eight modes, including its 0.6 s tail and all production validation/output/guard scans. Also report 48 kHz and stereo, endpoints, and eight-second scaling separately. Sources and settings are prepared outside the timed interval. Processor construction/coefficient misses belong in a separate cold-path measurement; include per-call buffers/state/diagnostics in both paths.

Use `System.nanoTime` around the synchronous public processor call, consume output/checksum after timing to prevent an unused result, and measure 20 warmups followed by 100 sequential warm samples. Report median/p95/max, cache hit state, and all sample timings. Report at least 20 cleared-cache cold calls separately from fresh-process/ART startup; label each accurately. Do not suppress GC or discard slow samples to improve p95. Observe GC/thermal activity and disclose it.

Measure allocation bytes in a separate repeated batch using the available Android runtime allocation counters, including all processing threads, with a baseline empty harness and the counter names recorded. Do not substitute a single Java-heap before/after delta for allocated bytes. The implementation must verify counter availability on the actual OS; if unsupported, collect an allocation trace with Android profiling tools and leave the allocation gate unpassed until that evidence exists. Separate live/peak heap from total allocations, and record both if available.

Proposed warm gate: mono p95 ≤50 ms and the allocation budget in section 6; report stereo scaling, cold timings and peak/live heap without inventing extra acceptance numbers. If the gate fails, profile before increasing modes or adding motion; cache hits alone do not solve frame×mode work. Desktop results may guide optimization but cannot close the phone gate.

## 12. Build sequence and completion gates

1. Review this R1 proposal and its explicit model/gain/tail departures. The current task ends here with R0 documents, not Kotlin.
2. In the authorized implementation task, add the typed `:audio` core and focused tests. Prove bounds, bypass, channels, cache independence and exact tail without wiring existing product paths.
3. Add the generator/template/task and replay checks, then run focused and full repository gates. Append an As built ledger with actual outputs and any deviations. Preserve failed/skipped/unrun distinctions.
4. Produce the audition artifact and request the owner's listen on named cases. A technical desktop proof can be complete while musical acceptance remains pending.
5. In an SDK-equipped environment, compile the benchmark variant and run the Nothing Phone (3) protocol. Record actual numbers. R1 is not performance accepted until this gate passes; no claim of phone readiness from desktop results.
6. Expand only from the evidence: choose a better family/gain law if the sound fails, optimize if timing fails, or plan the conventional branch and integration in R2 if the proof earns them. Do not silently add source transformation to this implementation round.

## 13. Sonic risks and evidence ledger

Risks: sparse four-mode spectra; layering instead of a convincing body at mixed positions; conservative excitation making short taps quiet; long resonance forcing attenuation of sustained sources; ringing that changes a sample's class; short high modes defeating a pitch detector. The raw/matched cases and the independent state/spectrum/decay tests are designed to reveal these, not to assert they are solved.

| Label | Current state |
|---|---|
| Verified code | Declarations, module direction, existing BODY/GRAINS/modal/rack behavior at the inspected commit; see the R0 evidence table. |
| Manual statement | Local supplied versions/pages in the R0 review; text hashes/page counts checked independently of DSP. |
| Proposed | Every new type/file, four-mode family choice, quadrature gain law, normalized control mapping, tail/fade/cache rule, benchmark protocol, threshold, and test/generator task here. The stability bound is an analytical argument for that proposed recurrence, not runtime evidence. |
| Measured | No R1 processor, DSP tests, timing, allocations, spectrum, or audio results exist yet. The 564,480 update count is arithmetic. |
| Heard | Nothing yet. Nothing Phone (3) identifies hardware only; it is not a listening or performance result. |

## Why it is built this way

- The shared `:audio` boundary lets a capture and a synth use the same future body without making the base audio module depend on synth internals.
- Independent fixed banks avoid retuning ringing state. They make R1 cheaper to reason about, while exposing whether a simple blend is musically enough.
- A bounded resonator and fixed gain law keep sustained-input safety separate from the output guard. The guard protects delivery without hiding internal growth.
- Short explicit tails, exact bypass, and no saved-recipe integration protect existing kits while the body sound is still a hypothesis.
- The risky choice is conservative excitation gain: it may make taps too quiet. Raw and matched auditions must settle that before the design grows more controls or filters.

## As built: R1

The owner authorized R1 after reviewing R0. `ResonantBody.kt` and `ResonantBodyModels.kt` now implement the typed `:audio` processor, original quadrature recurrence, four-mode HARMONIC/BAR families, native-rate mono/stereo handling, 50–600 ms base decay, wet-only end fade, linked attenuation guard, bounded instance-owned coefficient cache, diagnostics, and exact zero-mix bypass. All new tables/control/gain laws remain model version 1. Existing BODY, GRAINS, Modes, Dsp, rack and saved-pad code are unchanged.

Two implementation details extend the original file list without changing the sound contract:

- Optional `captureWet=true` returns an additional pre-guard wet buffer for analysis. Ordinary calls still allocate only the delivered output buffer and render-local state. A test verifies that requesting wet capture does not change delivered samples.
- `ResonantBodyAuditionTest.kt` verifies the test-side IEEE-float WAV writer and checks that replay refuses changed sources, unsupported versions, escaping paths, missing outputs, and nonempty destinations. Raw sources/wet/delivered audio use float WAV to retain exact PCM and values above full scale; matched listening files use the existing PCM24 writer.

The generator emits 73 cases by default, with original sources, raw wet/delivered files, matched comparisons, recipe records, diagnostics, CSV metrics, a self-contained listening page, and a desktop benchmark. Each pack records the base Git commit, whether the checkout was dirty, and a hash of implementation source/template contents; it does not falsely identify uncommitted code as the base commit's implementation. No owner capture or third-party sample is included. Regeneration requires a fresh directory; replay verification preserves an existing pack.

### Disclosed detector limitation

On the focused 27-case HARMONIC impulse grid (C2/C3/C5 × low/default/high RING × low/default/high DAMP, detector starting at 2 ms), 17 cases returned a confident estimate within 25 cents. Ten returned no confident pitch: all three DAMP settings at C2 with low or default RING; C2/high RING/high DAMP; and all three DAMP settings at C3/low RING. These are **detector refusals, not pitch passes**. Spectrum/reference-resonance checks pass separately. R1 therefore does not establish reliable pitch detection at every corner, nor a universal perceived-pitch claim.

The initial test asserted a confident estimate at every corner and failed. The implemented test retains the 25-cent requirement on every confident result, reports refusals explicitly, and fails if refusal appears outside the disclosed C2/short-decay group or confident coverage drops below 17/27. The 50 ms minimum was retained to preserve the proposed percussive range; no gain normalization or changed pole tuning was introduced to force a detector result. The audition's pitch section and CSV expose the actual estimate/confidence/null outcome on its separately recorded fixtures. Listening still has to establish musical pitch usefulness.

Diagnosis: the existing detector compares autocorrelation candidates with its global search maximum, including short-lag values. A strongly decaying low note can have much more correlation at a short lag than at its fundamental period. Inspection of the emitted fixtures found adequate nonzero energy and short-lag correlation maxima even in refused cases, so these are not simply silence/underflow failures. This does not establish perceived pitch or justify modifying the shared detector in R1.

### Validation record

Focused invocation: `GRADLE_USER_HOME=/workspace/.cache/gradle ./gradlew --no-daemon --max-workers=4 :audio:test --tests '*ResonantBodyTest' --tests '*ResonantBodyCacheTest' --tests '*BodyTest' :synth:test --tests '*ResonantBodyAuditionTest' --tests '*ModesTest' --tests '*FxTest' --tests '*PadRecipeTest' --tests '*VoxGrainsTest' :synth:generateResonantBodyAudition`. Exit 0: 22 audio tests and 165 synth tests, no failures or skips. Its 73 saved cases replayed exact PCM. The final full-gate run below also covers subsequent test/diagnostic refinements.

An initial full attempt caught a repeated native-rate literal in the new processor. It was stopped after diagnosis; the code now uses `WavWriter.MPC_SAMPLE_RATE`, and the unchanged convention test passed on the next run. The unfinished synth suite was subsequently rescheduled with four isolated JVM forks to use the environment's four available CPUs; completed module results were retained. No test filters or assertions were removed from the full gate.

The scheduling override is local to the validation invocation, not a repository or CI configuration change. Reproduce it with:

```sh
cat > /tmp/resonant-body-test-workers.gradle <<'GRADLE'
import org.gradle.api.tasks.testing.Test
gradle.beforeProject { p ->
    p.tasks.withType(Test).configureEach {
        if (p.path == ':synth') maxParallelForks = 4
    }
}
GRADLE
GRADLE_USER_HOME=/workspace/.cache/gradle ./gradlew --no-daemon --max-workers=4 -I /tmp/resonant-body-test-workers.gradle --continue test :synth:generateResonantBodyAudition
```

The generation command needs a fresh default output directory. To verify an existing pack instead, add `-PbodyVerify` (and `-PbodyOutput=/path/to/pack` when using another location).

Final full gate: exit **0**, `BUILD SUCCESSFUL`, 45m 48s. The nine JVM modules account for **5,838 passed, 0 failed, 0 skipped**. In that final invocation, audio and synth executed 3,467 tests; the other 2,371 passing module outcomes were valid Gradle results reused from completed passes. No zero-test run is counted as validation. The new decay-slope and mode-count cache assertions were compiled and included.

| Module | Tests | Failures/errors | Skipped |
|---|---:|---:|---:|
| audio | 455 | 0 | 0 |
| cli | 197 | 0 | 0 |
| json | 16 | 0 | 0 |
| kit | 389 | 0 | 0 |
| loop | 421 | 0 | 0 |
| mpc3 | 93 | 0 | 0 |
| shell | 1,216 | 0 | 0 |
| synth | 3,012 | 0 | 0 |
| xpm | 39 | 0 | 0 |

Fresh-process replay invocation: `GRADLE_USER_HOME=/workspace/.cache/gradle ./gradlew --no-daemon --max-workers=4 :synth:generateResonantBodyAudition -PbodyVerify`. Exit **0**, 14s, all **73** source/settings/output PCM replays and saved-file hashes verified. The emitted case list includes the ten pitch-detector refusals discussed above. Independent SciPy decoding of the float WAVs and `node --check` of the page script also passed; this is not a claim of browser/device listening.

Final pack implementation-content SHA-256: `7bfa99f27653f376ca85dfea8c5d1db77eafed3ab034333f6a3f60adfddfdc79`. The recorded Git commit is the base checkout; the manifest flags the uncommitted working tree and binds the actual processor/generator/template sources with this content hash. The downloadable package includes a source patch for that baseline.

### Measured desktop cost and gain tradeoff

Linux container, Java runtime `21.0.12.1+1-1-deb13u1-Debian`, JVM-17 target bytecode, generated one-second mono C3 sine at 44.1 kHz, BODY=.5/RING=1/DAMP=.5/MIX=.5, eight modes, and 70,560 output frames including the maximum tail. Twenty warmups preceded 100 warm samples; all timings are in the pack. Nearest-rank p50 **2.740192 ms**, p95 **4.223935 ms**, max **6.227382 ms**. Twenty separately cleared-coefficient-cache calls had median **2.838291 ms** and max **3.411327 ms**; this is not fresh-process/ART startup timing.

The separate HotSpot ThreadMXBean batch measured **282,696 allocated bytes per ordinary call**, against the proposed desktop workload allocation ceiling of **1,108,864 bytes**. It measures the synchronous processing thread's allocations, not peak live heap. The Nothing Phone (3) gate remains unmeasured. Across the 73 audition cases, delivered sample peak was at most **0.98000002** and absolute delivered mean at most **0.000834**; true-peak measurements are recorded separately and no true-peak ceiling is promised.

Raw gain varies with excitation, as the conservative sustained-input law predicted. Examples from the generated fixtures (peak values before the delivery guard):

| Fixture / controls | Source peak | Wet peak | Observation |
|---|---:|---:|---|
| 20 ms noise burst, default HARMONIC body | 0.48020 | 0.02576 | Wet peak is about 25.4 dB below the source. |
| Impulse into a C3 body, default RING/DAMP | 0.80000 | 0.00568 | A single impulse produces very little raw wet level. |
| Impulse into a C3 body, maximum RING/default DAMP | 0.80000 | 0.00175 | Longer decay reduces impulse onset under this normalization. |
| Sustained harmonic fixture, default HARMONIC body | 0.14662 | 0.13091 | Sustained excitation reaches much more wet level than an impulse. |
| Full-scale square, maximum resonance | 1.00000 | 1.25513 | The common delivery attenuation factor is 0.78080. |

These measurements do not decide musical usefulness. Raw files preserve the tradeoff; matched files apply their explicitly listed audition gain. A stronger short-hit effect may need a revised gain/excitation policy after listening. No per-render normalization was inserted into the processor to hide this result.

### Pending acceptance

- No Android SDK or physical Nothing Phone (3) is attached. The SDK-equipped instrumentation/build-variant step in section 8 remains deferred; no uncompiled app benchmark or product UI was added. The exact device workload and protocol remain in section 11. Desktop timings cannot close that gate.
- No owner-approved dry capture or listening verdict is available. The generated fixture proof is implemented, but musical acceptance on real captures remains open.
- No R2 filter/motion/integration or R3 source transformer was implemented. Current standalone proof adds no pad-recipe migration or factory preset bank.
