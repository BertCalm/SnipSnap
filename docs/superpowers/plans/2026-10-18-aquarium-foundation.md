# Aquarium foundation and integration plan

**Date:** 2026-10-18  
**Design:** [Aquarium integration — pads and a shared environment](../specs/2026-10-18-aquarium-integration-design.md)  
**Base:** `45ba33832950530bb0148435378b4556045c6cf6`  
**Current implementation scope:** the foundation, offline bridge and PAD control panel are complete. Stage 2 now has its first continuous backend: PadEngine playback and stereo PRINT, with typed/JNI control APIs, bounded transitions and an explicit output policy. The shared-host PERFORMANCE controls and kit persistence are now implemented; SURFACE now has its explicit per-kit route and stereo PRINT; other mixers and physical-device checks remain work to do.

## Accepted direction

The owner heard the [private audition](https://snipsnap-aquarium-pedals-audition.bertcalm.chatgpt.site) and answered **“It's all good” on 2026-10-18** after the six-effect and pad/bus/both question. Proceed with all six, both placements and the corrected audition behavior. Preserve Depth = Fathom + Surface, Motion = Drift + Tide, Color = Reef + Biolume. Existing [PEDALS](../specs/2026-10-04-pedal-board-design.md) stages and the seventh treatment row stay intact.

The source audition used XO_OX-XOmnibus commit `a9bdc7803d899f0b1688fe0c0abc4ce481a76c3e` and SnipSnap source material from `8266ae915ecef98967c4d1d1b16ffa1ac92b1d81`. Import the corrected extraction rather than reverting its filter, Fathom, random-clock or Biolume state repairs. Keep license/provenance, the reproducible extraction and the admitted Biolume pitch-ring seams.

## Stage 0 — foundation (complete)

### 0A. Import the audited DSP

- Retain pinned upstream originals/license and corrected standard-C++17 headers under an Aquarium-specific native directory.
- Include provenance, upstream and corrected hashes, extraction instructions, parameter registry and the fixes/limits record.
- Provide `AquariumProcessor.h` / `.cpp` as the narrow boundary independent of Oboe/JNI/files. Preserve six-stage order, left/right processing, 8–192 kHz preparation, a 1–8192-frame maximum block capacity, explicit seed/reset and transactionally validated complete snapshots. Processing is `noexcept` and allocation-free.
- Start dry. All six zero-mix stages and the full dry chain must be bit-exact bypasses.
- Preserve the accepted model's sound. Any wrapper-required code change gets an explicit comparison; do not quietly “improve” the pitch shifter, crossover topology or Reef delay limit.
- Compile the core in the Android native target, while keeping current playback/JNI routes and print taps unmodified. Merely compiling this core does not connect an effect to playback.

### 0B. Freeze the canonical recipe contract

- Add a standalone Aquarium recipe/settings value in the pure JVM layer; keep existing `PadRecipe`, `FxChain`, patches and constructors unchanged.
- Store independent format/render-model versions, explicit seed and all 52 canonical physical-unit parameter values. Tempo belongs to the later complete render envelope or live host context, outside this settings value.
- Expand sparse authoring input once using model-1 defaults; write complete deterministic JSON, not preset-name references or omitted default values.
- Validate version/model, IDs, finite values, ranges, registry-declared selector steps and seed. Whole selector states come only from optional `step: 1` metadata, consistently in Kotlin and native code. Refuse malformed saved input rather than clamping it to another sound.
- Package `app/src/main/cpp/aquarium/parameters.json` itself as the JVM resource, rather than duplicating its literals. Check Kotlin/native registry parity, defaults, steps and order, including the canonical `aqua_fathom_highXover` and effective 2000 Hz Biolume preset declaration. The boundary's rejection of fractional selector states is intentional; accepted audition presets remain unchanged.
- Keep source WAVs/hash, actual render BPM, placement, tail/fade policy and prior pad metadata outside the reusable DSP settings; they join in a later host envelope. Do not add an Aquarium field to `PadRecipe` in this change.

### 0C. Verify the native core and compatibility

Build/run the Aquarium host target through the repository's native test CMake entry point. Do not add an Android-only test dependency. Run AddressSanitizer and UndefinedBehaviorSanitizer on the imported core where the host toolchain supports them.

Required behavior checks:

- Exact all-dry bypass, including independent left/right inputs.
- Identical repeated render after reset/reprepare with the same seed; different seeds actually change the random effects.
- Identical output under fixed and irregular block partitions of the same input.
- Surface zero-dB neutrality, shelf direction/endpoints/midpoint, bell center and supported high cutoff behavior.
- Fathom stereo-linked gain, complementary neutral split and loud-to-quiet release response.
- Operative Drift-rate and Tide stereo-phase/sample-and-hold controls.
- Finite impulse/tail behavior at 44.1/48/96/192 kHz; sustained high-drive Biolume saturation cases.
- Reject invalid host arguments outside processing; confirm parameters/state allocations occur before processing.
- Canonical recipe round trip, registry parity, exact defaults and rejection of unknown/non-finite/out-of-range saved settings.

The prior audition's **214 checks plus 144 independent sanitizer probe configurations** are provenance, not a substitute for running the imported implementation's checks or a claim that the new target has that exact count.

Run the existing native engine/JNI target and the affected JVM suites after foundation changes. Check `git diff` for accidental app-route, legacy rack-order, constructor, preset, pedal-row or recipe-version changes. Broaden testing only when a failure or changed shared dependency requires it.

**Exit:** the corrected core and settings are reviewable, reproducible and tested in SnipSnap; existing playback/output remain unchanged. No visible feature door is added. Record actual commands/results in the change report rather than marking Android/device checks passed from host success.

## Stage 1 — offline bridge and whole-pad semantics (complete)

The [offline bridge](https://github.com/BertCalm/SnipSnap/pull/622) supplies a real native bridge to the same DSP and a host operation with preserved inputs. See [the current contract](../../AQUARIUM.md).

1. Implement offline `Snip` rendering: duplicate mono into L/R, retain stereo, use actual source sample rate, saved tempo/seed, fresh state and arbitrary bounded block partitioning.
2. Define a versioned complete pad-render envelope naming the exact baked input/hash and actual BPM, and preserving its prior recipe/metadata. Do not replace the source with its raw synth patch or assume existing last-operation recipes describe a full history.
3. Choose and save explicit tail length, end treatment, peak/headroom and loop policy. Avoid silently inheriting `FxChain`'s one-second cap or the demonstration renderer's tail duration.
4. Apply a pad operation to all referenced velocity-layer files under existing whole-pad guards. Amount edits read the preserved source, never the previous wet output.
5. Stage derived files before switching kit references; failures preserve a usable kit. Hook replay/undo/copy only when the complete source-dependent operation is supported.
6. Verify deterministic recipe replay, channel preservation, repeated amount edits, layer coverage, failure behavior and ending/loop boundaries. Export and decode a real treated stereo WAV through the normal MPC program paths; do not invent native MPC Aquarium fields.

**Exit:** a working internal pad operation and bridge, with portable rendered WAVs and truthful replay/undo semantics. Existing recipes still render as before. A control appears only when this operation is reachable and complete.

## Stage 2 — continuous stereo bus and printing

This is separate from per-pad rendering. Reuse Stage 0's processor/settings, with one continuous state per output session.

**First backend:** [PadEngine and PRINT](../specs/2026-10-26-aquarium-padengine-performance-design.md), built against `fd791f583579dda3dcab3d2ea9e34f9975b07a12`. The route inventory, state/transition policy, linked sample-peak protection and per-instance native/JNI/Kotlin API are implemented. It starts dry and is exercised by the host/device tests; the subsequent KIT PERFORMANCE panel configures and saves this shared host. The route table explicitly leaves SURFACE stereo conversion, ORBIT/other transport mixers and offline bounces for later work. Current execution/measurement results belong in the change's validation report, and no phone timing or hardware listening verdict is inferred from a host run.

1. Inventory each output/recording path before claiming global scope: PadEngine, SURFACE, live preview where relevant, loop/ORBIT playback, prints and offline bounces. Name the supported routes.
2. Insert after the complete voice sum and before the route's output and print taps. Never reset on note-on or run one processor per voice.
3. Upgrade any participating mono print/storage path before offering stereo Aquarium there. In particular, SURFACE's current mono-before-L/R-duplication path must not discard wet stereo.
4. Keep the active processor clocking across quiet gaps and tail time. Define restart, panic, route/sample-rate change, bypass/re-enable and background/device-loss transitions.
5. Handoff validated settings outside the callback; add smoothing/bounded transitions. Verify no allocation, strings/JSON, file access, locks or thrown exceptions in the callback.
6. Define and measure output headroom/protection. The core has no limiter, and arbitrary settings can exceed unity substantially; audition loudness matching is not a live-output policy.
7. Check mixed-signal dynamics versus per-pad processing, common Tide clock, ongoing Drift, tail continuity, exact static dry bypass, click-free supported control changes, heard-versus-printed stereo equality and route recovery.
8. Build the Android app and profile actual callback cost, memory and stream behavior on a phone. Keep build success, host DSP evidence and physical-device observations distinct.

**Exit:** explicitly supported performance routes and their prints/bounces process the same continuous stereo signal, with measured host behavior. Unsupported routes do not silently appear to obey a global switch.

## Stage 3 — connected TapeOS panel and end-to-end checks

The PAD portion is implemented ahead of Stage 2: TREATMENT → AQUARIUM opens a qualified AQUARIUM · PAD panel with all accepted presets, all 52 controls, main-sample BEFORE/PREVIEW and whole-pad KEEP/REMOVE/UNDO. Render context survives sound edits; pending preview delivery is invalidated by edits, closing and navigation. No PERFORMANCE scope is offered until its routes work. See [the PAD workflow](../../AQUARIUM.md#pad-controls) and the change's validation results for checks actually run.

- Complete the PERFORMANCE portion after Stage 2, with explicit scope and matching print routes.
- Use Depth/Motion/Color tabs with the two original effect headings, independent bypass, plain primary controls and folded details. Do not change PEDALS placement or add an inactive treatment chip.
- Derive control values and closed summaries from the canonical recipe. Save resolved values; presets and pair controls write that same settings value.
- Use existing TapeOS tokens/type, dark sound displays and >=44 dp targets. Verify phone-width labels and accessibility semantics, visible busy state, source/undo feedback and persistent settings.
- Confirm a captured pad and a synthesized pad can both be treated, edited, reopened and exported; the exported sound is the rendered WAV.
- Confirm a shared processed performance prints/bounces what is heard and reopens with its saved settings. A dry-pad export is not described as containing the live bus treatment.

**Exit:** the whole feature is connected for its declared scopes. Update current product documentation with what actually runs and record outstanding physical phone/MPC checks. The existing positive audition is not replaced with an invented requirement for another approval before every mechanical step.

## Model changes and retained limits

Biolume's feedforward harmonic path retains an unwindowed 2x pitch-ring seam about every 100 ms at 48 kHz, its approximate high-pass and no connected octave-feedback loop. Reef retains the 5000-sample ceiling. Fathom retains complementary one-pole crossovers and level-sensitive upward compression. Tide keeps its audition shapes; neither the concept's lunar waveform nor the pedal's starts-open behavior is added here.

Preserve those model-1 facts in the port. If a later improvement changes the accepted sound, make the model/version and comparison explicit, retain saved behavior as appropriate, and collect useful listening evidence for that difference. Do not infer mobile safety/performance or hardware acceptance from the owner's sonic verdict.


### SURFACE route milestone, 2026-10-31

The [SURFACE stereo contract](../specs/2026-10-31-aquarium-surface-stereo-design.md) extends the accepted bus to SURFACE after its existing mono chain. FX → USE KIT FX is an explicit per-kit opt-in to the saved PERFORMANCE controls; SURFACE owns independent history. PRINT now keeps the actual stereo output through native/JNI/Kotlin and the existing TAPE/PAD landing paths. Ordinary release keeps tails; priority panic handles focus/background release. Sidecar writes retain each directory's latest snapshot. Remaining transport mixers, phone timing/power/recovery and physical MPC acceptance are still open.
