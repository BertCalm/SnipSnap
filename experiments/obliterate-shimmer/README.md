# OBLITERATE shimmer pre-stage, first listening proof

The characteristic gesture is **the dry hit remains present → an octave room blooms → the approved fuzz, captured fragments and stereo chop cut the halo**.

This is a separate **Python prototype** before the approved Python destruction-core replay. The Kotlin core has merged in PR #714 and its CI passed. This experiment does not add Kotlin shimmer or Android controls, and it does not alter edition 003/004 audio or infer a new listening verdict. Snare keeps the owner's 70% core setting; percussion, stereo nylon and bass keep 35%. The wet send is a separate listening variable, not a proposed change to the approved core AMT law.

## Direction and landing

| Direction | Gesture / nearest sibling | Risk / cost | Choice |
| --- | --- | --- | --- |
| SPLICE the complete OPENSKY room | Long octave room before destruction | Its private `Room` belongs to generated voices and long/held tails; extracting it adds surfaces before a listen | Defer extraction |
| JUXTAPOSE a feedforward octave layer | A pitched shadow under the original hit | Cheap, but no recirculating pitch gesture | Control idea, not selected |
| STACK a bounded four-line room with octave input and feedback | A short halo rises and is broken by the core | Moderate Python cost; granular tuning and folded highs need probes | Selected first proof |

`OpenSky.kt` at `d00051f9bad01ef55faf0ed5b7f4aeabc877fde7` contains internal `OctaveShift` and private `Room`; both were inspected. Its audition notes record a real problem: subtle room settings over a long source obscured what shimmer contributed. This page therefore has a same-gain ordinary-room control, source-end markers, a **Hear tail** button and an isolated return. ORPHICA supplies source fragmentation, but neither sibling supplies this complete any-input pre-stage plus destruction chain. The lightest landing is an isolated experiment, not another engine or new presets.

The original [Epic Chains design](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs%2Fspecs%2F2026-04-05-epic-chains-fx-design.md), §2.3, puts shimmer first and sends roughly half the room return through octave shift. This proof retains that routing idea. It also shifts wet excitation once so a short hit has an octave in its first return. It uses standard four-line Hadamard and complementary Hann-head equations, not a pedal circuit or pitch-synchronous PSOLA. No Omnibus implementation is copied. The source is Josh's retained self-generated audio.

```text
retained mono / stereo PCM at 44.1 kHz
    ├──────────────────────────────── unity dry ───────────────────────┐
    └→ wet-only 150 Hz highpass → four convex lowpass poles → +12 read  │
             → four-delay Hadamard room → wet return × SEND ────────────┤
                   ↑ 0.38 × (50% damped + 50% lowpassed octave return) │
                   └───────────────────────────────────────────────────┘
    → linked attenuation-only pre-core peak guard
    → unchanged approved Python core at 70% snare / 35% other sources
    → common per-source audition gain, or raw output
```

Ordinary room uses the same delays, wet filters and feedback coefficient, with octave reads disabled. No-shimmer passes the exact source object and length to the approved core; the four core-only Float32 hits are bit-identical to edition 004's Python references. Only playback padding aligns its shorter tail with the active pre-stage clips. The active pre-stage appends 66,150 frames (1.5 seconds), then the core appends 33,075 frames. Each listening file contains two complete identical hits, at least 250 ms of silence between complete tails, and 200 ms after the second hit. The clock restarts for each printed hit.

## Controls and bounds

| Control | First-proof value | Actual quantity | Interaction / probe |
| --- | --- | --- | --- |
| SEND | Off, 15%, 30% | Additive gain of the room return; dry remains unity before peak protection | SEND × core AMT changes how the halo is rectified/captured; quiet scaling and same-gain listening |
| OCTAVE | Off for room control; +12 for shimmer | Two complementary Hann read heads, each advancing two input frames per output frame | Frequency probes, filtered/unfiltered high-tone comparison |
| RETURN | 0.38, fixed shape | Feedback coefficient of the four-line tank | Release measured before the tail fade |
| Core AMT | 70% snare, 35% other sources | The accepted destruction intensity | Exact saved baseline payloads; no retuning |

The generic Luthier pitched-engine mono/autolevel/HOLD contract does not apply to a stereo input effect. Lowpass poles, window length, delay lengths, gains and tail budget are **proposed shape constants**. The prototype accepts finite normalized PCM, one or two channels, at most nine seconds, leaving room for both finite tails. There is no live host sync, held loop, infinite decay or seamless-loop claim.

Every Hadamard row has absolute sum 2. Delay reads and complementary nonnegative head windows are convex; every lowpass pole has infinity-norm gain at most 1. With feedback 0.38, the complete unforced tank's history-supremum contraction bound is **0.76**. Delays make this a return-bound argument, not a claim that every adjacent sample decreases. No clipper or limiter is inside the tank. Wet-input highpass and the final linked attenuation-only 0.98 guard are outside it. The guard never adds gain and is not the stability mechanism.

Four lowpass poles at 4.2 kHz precede every octave read, including feedback reads. A 16 kHz sine probe measured **39.914 dB** less folded output than the unfiltered reader. This is one tone and one stage, not a whole-chain alias-rejection bound; the unchanged core's pitched fragments also retain linear interpolation. The granular shifter is not pitch-synchronous: tested 220/440 Hz tones land near their octaves, while a 233 Hz probe has about 27 cents of dominant-peak error. The test ceiling is 40 cents on these probes. This is a disclosed texture trade, not exact harmonic transposition on all notes.

## Measurements and reproduction

- **13 new Python signal tests** pass: exact bypass, invalid-state refusal, determinism/silence, octave and folded-high probes, dry attack, send/quiet scaling, stereo input/crossfeed, room/shimmer distinction, independent unforced-release windows, causal block-size agreement, DC/release, peak protection and the nine-second input bound.
- The impulse wet RMS in frames 40,000..44,000 was **164.09 dB below** frames 2,000..12,000, before the final fade. Across the 16 source/variant cases, the largest last-100-ms wet peak before fade was **4.50e-15**. The 10 ms wet-only boundary fade is not cutting a substantial surviving tail on these examples.
- There are **19 unique clips / 76 PCM24 WAVs**, **119,547,482 bytes**. Main comparisons have Off, Room 30%, Shimmer 15% and Shimmer 30% for four sources. Nylon additionally has source alone, pre-core shimmer and isolated octave-room return. An independent PCM reader validates hashes, retained core references, repeated hits, silence/full tails, common gains and unchanged mono folds.
- The matching meter is the existing Float64 Python port of SnipSnap's mono meter, not LUFS. A single gain is derived from each core-only reference and peak-guarded across that source's entire comparison set. That same gain applies to all room/shimmer alternatives, isolated returns and mono folds. This preserves the level contribution of shimmer; these are not separately loudness-matched alternatives. Raw output is also downloadable.
- `./gradlew ... --continue test` returned **exit 0** on the unchanged Kotlin/JVM sources. All 42 tasks were up to date from the completed 6,755-test baseline. No new JVM execution, Kotlin shimmer, phone timing or Android integration is claimed. New DSP validation is the Python test suite and artifact probes.

Use the pinned requirements from `experiments/obliterate-core/requirements.txt` (NumPy 2.3.5, SciPy 1.17.0) and the retained actual-WavReader Float32 inputs under `testkit/obliterate-kotlin-audition/`. The original processor is pinned by its recorded SHA256; source regeneration on changed engines is not the same comparison.

```sh
python3 -m unittest discover -s experiments/obliterate-shimmer -p test_prototype.py -v
python3 experiments/obliterate-shimmer/generate_audition.py
python3 experiments/obliterate-shimmer/measure_probes.py
python3 experiments/obliterate-shimmer/validate_audition.py
```

Generated files are ignored under `testkit/obliterate-shimmer-audition/`. The manifest carries the source commit/tree, clean/dirty state, source/input hashes, settings and complete per-render diagnostics. `probes.json` retains the numerical probe report. Host Python timing appears in diagnostics and is not phone performance. Precompute/cache candidates for a future Kotlin implementation are the pitch-head coordinates/windows and fixed room/filter coefficients; a cache would be keyed by model, sample rate and frame budget. The present prototype precomputes fresh bounded arrays and has no persistent cache.

| Ledger | Status |
| --- | --- |
| Verified | Original stage order, real OpenSky APIs and audition lessons at d00051f9; immutable approved core and four retained inputs |
| Measured | 13 Python tests, tank bound and release, one-tone folded-high reduction, PCM provenance/framing/gain checks |
| Proposed | Shimmer amount, octave-input enhancement, four-line tank and shape values; later Kotlin extraction and phone render-budget work |
| Heard | Josh kept the earlier destruction core at snare 70%, other sources 35%. Shimmer has no owner verdict yet. |

## Kotlin port and complete-chain entry point (edition 006)

Josh's 2026-10-31 feedback was **“Sounds cool, proceed”**. The exact quote and scope are retained in `listening-005-2026-10-31.json`. It authorizes continued implementation and records general positive feedback; no per-source verdict, 15%/30% preference, playback level or stereo/mono selection was supplied. The existing 15% prototype default remains provisional. Kotlin listening is still separate from that report.

`ObliterateShimmer.render(snip, Settings(mix = .15, pitched = true), includeWet = false)` is the standalone pre-stage. Its result has audio, diagnostics and an optional isolated wet `Snip`. Zero mix returns the exact original `Snip`, original channels and no tail. Active processing emits stereo with exactly 66,150 appended frames. The optional return changes allocation only; the output samples are identical with it enabled or disabled. It accepts finite normalized mono/stereo floating-point PCM at `WavWriter.MPC_SAMPLE_RATE`, one frame through nine seconds.

`Obliterate.render(snip, Settings(shimmer = ..., core = ...))` composes that public result with the unchanged `ObliterateCore.render`. Core settings retain AMT 0.35, BPM 120, division 16 and seed 20261023 by default. This is a standalone effect API, not a BoardRecipe or Android control. It exposes separate shimmer/core settings, not a newly invented single-board AMT mapping. Combined input remains capped at nine seconds. The tail is 0, 0.75, 1.5 or 2.25 seconds according to which stages are active; both controls at zero return the original `Snip` exactly.

A direct call keeps all non-default state explicit:

```kotlin
val result = Obliterate.render(input, Obliterate.Settings(
    shimmer = ObliterateShimmer.Settings(mix = .15, pitched = true),
    core = ObliterateCore.Settings(amount = .35, bpm = 120.0, division = 16, seed = 20261023L),
))
val processed: Snip = result.audio
```

The port uses Double filter/tank state and the exact SciPy-generated highpass coefficients. Its two integer octave heads and complementary Hann weights retain the prototype's time geometry. Fixed delay rings replace the Python full-render histories: pitched mode stores **37,144 Double history samples (297,152 bytes)**, independently of source duration, plus small filter state and one immutable 4,410-value weight table. This figure excludes render audio buffers and JVM overhead. Audio/output buffers still scale with the explicit finite budget. No persistent audio cache or mutable cross-render tank is introduced.

The new entry point has a public Float32 `Snip` between stages. The heard edition 005 Python chain used Float64 there. The parity pipeline therefore replays the actual Float32 intermediate boundary in Python and reports its difference from the historical Float64 chain separately. Across these examples that boundary alone changed a full-chain sample by at most **2.9802322387695312e-8**. No historical WAV is rewritten or described as a new Kotlin render.

Measured port checks:

- **14 new Kotlin tests** passed, including three independent Python fixtures, exact bypass/signed zeros, bounds, silence, fresh-call equality, dry attack, quiet/send scaling, stereo excitation/crossfeed, room/shimmer distinction, unforced release, linked peak protection, nine-second budget and constant delay-history storage. Composition tests pin all four tail budgets and exact core-only output.
- **16 source/settings cases / 48 PCM comparisons** passed for public pre-stage, isolated wet and full-chain outputs. Maximum absolute Float32 sample error: **1.4901161193847656e-8**; maximum RMS error: **3.06436492881906e-11**. The predeclared limits stay 2e-6 maximum / 2e-7 RMS. Full geometry, deterministic rerenders and non-mutating input checks are also validated.
- The new comparison contains **16 single-hit clips / 32 raw PCM24 stereo/mono WAVs**, **24,112,012 bytes**. Each includes its entire finite tail plus 200 ms silence. Each PCM24 export is independently checked against the retained raw Float32 renderer output, with fold rounding bounded by 1 LSB. This smaller port-only pack preserves the prior listening sets within the host's 256 MiB expanded archive limit.
- Initial [warm host-JVM measurements](initial-host-benchmark.json) gave median pre-stage times of **40.8–62.4 ms**; complete-chain medians **261.8–438.2 ms** (three warmups, seven trials, four retained sources, JDK 17). Host load and GC are uncontrolled; these are not phone timings. The full chain still exceeds the desired tens-of-ms interaction budget. Before app exposure, profile on a named device and consider a bounded cache of pre-stage/fuzz audio keyed by input content, renderer version and the settings that affect that stage; tempo/seed edits need not recompute input conditioning. Such a cache must account for mutable Snip arrays and return copies, rather than keying on object identity.
- The port was built on upstream snapshot `a5caac7fd6c8a2e6d09ec740c60fb995c6bef368`; the approved core and Python stage source are unchanged there. The complete JVM regression finished with **7,449 tests, zero failures, errors or skips, and exit code 0** across all nine JVM modules (JDK 17, no Android SDK in the graph). A task-local scheduling init ran LOOP alone after compilation to protect its existing 50 ms nonblocking check; no test was filtered or excluded. The port inputs stayed unchanged throughout the run. No APK, Android integration or phone-performance claim is made.

The next integration surfaces were verified on the same upstream snapshot. `BoardRecipe` version 1 accepts exactly board/type/amount and dispatches only WIDE/RUSH; it cannot store this stage's clock, seed, wet send or model identity. `BoardSheet.preview/apply` already render from immutable pre-board sources and define AMT zero as an unchanged source. This standalone pair instead bypasses only when both stage controls are zero, so a board control law must be explicit before wiring it into that surface. `PadSheetScreen`'s board captions also have two-type WIDE/else-RUSH branches that must become explicit for another type. Preserve existing version-1 recipes and their audio; add complete versioned state, all-zone KEEP/REMOVE/UNDO checks and an SDK-backed build when implementing that later step. No such wiring is claimed here.

Reproduce with the retained Float32 sources and pinned Python environment:

```sh
python3 experiments/obliterate-shimmer/kotlin_reference.py export
./gradlew --no-daemon -I experiments/obliterate-shimmer/shimmer-port.init.gradle :synth:renderShimmerPort
python3 experiments/obliterate-shimmer/kotlin_reference.py compare
python3 experiments/obliterate-shimmer/generate_kotlin_audition.py
python3 experiments/obliterate-shimmer/validate_kotlin_audition.py
./gradlew --no-daemon test
```

A fresh checkout can download and extract the small input archive linked on edition 006, then use `kotlin_reference.py export --inputs /path/to/extracted/sources.json`. This restores the exact Float32 inputs without rerendering changed source engines. The complete retention validator additionally requires the older heard audition set.

Fixtures are regenerated only by explicit `kotlin_reference.py fixtures`, not by ordinary tests. Generated evidence lives in ignored `testkit/obliterate-shimmer-kotlin/`; source hashes, clean/dirty state, the intermediate-boundary policy, settings, renderer hashes and benchmark trials are retained in its JSON files. The [complete-chain comparison](https://snipsnap-obliterate-kotlin.bertcalm.chatgpt.site/shimmer/kotlin/) retains its own edition-006 note bank. The [heard Python study](https://snipsnap-obliterate-kotlin.bertcalm.chatgpt.site/shimmer/) remains available.

| Ledger | Status |
| --- | --- |
| Verified | Public Kotlin shimmer and whole-chain APIs, fixed finite budgets, unchanged core, bounded ring storage and separate settings |
| Measured | 14 Kotlin tests, 16 settings cases / 48 PCM comparisons, 32 new WAVs and host-JVM timing |
| Proposed | 15% default as a production choice, a board AMT law, caching/phone optimization and Android integration |
| Heard | General positive feedback on edition 005; no amount preference or Kotlin listening verdict supplied |

## Why it is built this way

- The dry branch keeps the original hit available while the new room supplies material for fuzz and fragments.
- Octave excitation and recirculation make the rising halo a real signal path, rather than a label on an ordinary room.
- A same-gain ordinary-room control and isolated return let the audition reveal whether pitch is contributing anything useful.
- Bounded feedback and a measured finite release keep a sampler hit from becoming an uncontrolled ambient tail.
- Keeping the accepted core and source WAVs fixed makes this listening decision specific to shimmer.
