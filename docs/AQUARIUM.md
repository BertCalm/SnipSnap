# Aquarium

Aquarium's six accepted effects are editable from **PAD → TREATMENT → AQUARIUM**. The PAD panel connects presets, all 52 controls, explicit previews and whole-pad printing to the native offline renderer. It uses the same corrected model imported in [the foundation](https://github.com/BertCalm/SnipSnap/pull/595). The first continuous backend now connects PadEngine playback and its stereo PRINT tap; KIT PERFORMANCE controls and a qualified SURFACE route now connect live playback and stereo printing; other mixers remain later work.

The offline bridge was developed against SnipSnap `b429ec2c4bc89d225ac476af234c1a88a46fb07d`; the PAD panel builds on `fd76ffa382c1bf94d6a82e5a9484e6cd4140e91c`. The [integration design](superpowers/specs/2026-10-18-aquarium-integration-design.md) and [staged plan](superpowers/plans/2026-10-18-aquarium-foundation.md) explain the larger feature. The owner's positive October 18 listening verdict covers the underlying audition. No new listening verdict is claimed for these host policies.

## PAD controls

Open an existing pad's TREATMENT box, then AQUARIUM. The panel occupies that box; PEDALS keeps its existing placement. Fresh drafts start dry. Opening the panel, selecting a preset and moving controls do not play or save audio.

The eleven presets come directly from the accepted native preset resource: DRY, the six individual effects, DEPTH, MOTION, COLOR and FULL. Depth contains Fathom and Surface, Motion contains Drift and Tide, and Color contains Reef and Biolume. Each effect has an independent bypass, primary controls and folded details. The tabs group controls without changing the native six-stage processing order. Readouts use physical units, frequency sliders use logarithmic travel, and selectors offer exact native states. Bypass restores the last nonzero mix in the current draft, or the accepted single-effect preset mix for a fresh dry stage.

PREVIEW renders the main sample only; BEFORE plays its preserved input. Both include the current pad shape and level for audition. KEEP renders every unique referenced WAV and updates all velocity zones. A successful main-sample preview does not guarantee that another layer will pass the peak or source checks. Fresh dry drafts cannot be kept; KEEP DRY on an existing Aquarium print restores its source. REMOVE and one-step UNDO use the same source-backed operation.

PRINT SETTINGS exposes output gain (-40..0 dB travel), tail (0..30 seconds), tempo (0..1000 BPM; zero is free time) and end fade (0..100 ms). Tempo also has ±1 BPM buttons for precise adjustments. A saved gain below the slider's useful travel remains unchanged until that control is edited. The seed and sample-peak ceiling are displayed. Presets and effect controls preserve the complete saved render context, including seed, tempo, gain, tail, fade and ceiling. New drafts take tempo from the kit, or zero when unavailable.

Preview, shaping and mono-to-stereo preparation run on a worker. Preview and kit writes share the kit-write mutex so blocking native calls cannot overlap. Closing the panel, changing its sound, switching pads or boxes, or backgrounding stops audition and invalidates pending delivery. Native work already in progress finishes under the lock; cancellation discards its result. A kit write can therefore wait behind a preview. Closing discards unkept edits; KEEP reloads the saved recipe into the open panel.

## First performance backend

The [PadEngine route and policy contract](superpowers/specs/2026-10-26-aquarium-padengine-performance-design.md) maps each actual playback/recording owner. One continuous processor now follows each PadEngine's summed stereo mix and precedes its unchanged PRINT tap. It starts dry for old kits. KIT now exposes a qualified PERFORMANCE panel and restores its saved live settings when the shared host opens. The shared kit host supplies current kit tempo, while SPLIT, ORBIT audition and instrument handles remain separate instances.

Global bypass retains the configured processor's time and delay history through quiet gaps. Sound/seed/tempo edits fade the wet branch to silence for snapshot adoption, then fade back; gain has its own linear slew. These transitions are 20 ms, with at most a fade-out plus fade-in for a sound edit. They do not interpolate filter/selector laws. The existing +/-1 mix clamp precedes Aquarium. The live host applies fixed gain and stereo-linked sample-peak protection at 0.99 with a 50 ms release; during global bypass transitions the dry branch retains its existing +/-1 ceiling. This differs deliberately from finite PAD rendering's reject-on-excess policy.

Panic, bank replacement, stream recovery and non-finite handling have explicit policies. The callback uses a bounded settings handoff and fixed scratch, with no preparation, allocation, freeing or locks. PRINT records the actual processed buffer, including transitions and protection. Other mixer/print routes, phone CPU/power measurements and physical MPC acceptance remain open.

## PERFORMANCE controls and kit storage

Expand **PERFORMANCE** on KIT for the live Aquarium bus. LIVE ON/BYPASSED, OUTPUT (including mute), RESET TAIL, the eleven accepted presets and all 52 controls use the shared host. Presets preserve the seed, output and explicit global bypass. Tempo follows the kit; no finite tail/fade context is saved here. The panel displays missing streams, queued settings, faults and cumulative protected frames. Individual zero-mix stages retain their paused private history; global bypass keeps the configured processor clocking.

Changes audition immediately. **SAVE TO KIT** stores the full versioned settings in optional `kit.json.aquariumPerformance`; **REVERT**, collapsing the panel or leaving KIT restores the last saved snapshot. A failed save retains the draft for retry. Saved settings return when the kit's host reopens. Saves reopen current metadata under the shared kit-write mutex and use ordinary atomic kit saves/takes, without rewriting sample WAVs. Live-only changes do not reload the sample bank or restart the beat/recording/bounce clock. Actual pad/tempo changes retain the existing transport policy.

Old kits open dry without a write. Unknown/damaged live metadata is preserved during ordinary kit edits, shown as unreadable, and played dry until the owner explicitly saves a replacement. `:kit` stores this optional JSON opaquely, while `:shell` validates it using the synth codec; this avoids a circular dependency.

This panel reaches the shared KIT/PADS, beat/GROOVE and their native PRINT/BOUNCE, including banked count-in clicks. SURFACE can explicitly opt into these saved settings on its own independent stereo bus. SPLIT, ORBIT transport/audition, instruments and the other players remain separate. MPC kit exports do not bake the live bus into samples or create an MPC insert; the optional SnipSnap sidecar can retain settings for reopening in SnipSnap. Kept PAD prints remain baked audio as before. See the [control and storage contract](superpowers/specs/2026-10-28-aquarium-performance-controls-design.md).

## SURFACE → FX

**USE KIT FX** opts this kit's SURFACE into its saved KIT → PERFORMANCE sound and tempo. The choice is stored in surface.json and defaults off for old kits. Global bypass and unreadable-settings fallback are respected. This is SURFACE's own processor/history, not a shared audio master with the beat. An unused route does not clock a hidden wet core.

The existing mono source/legacy chain feeds Aquarium in stereo. Normal finger release and source changes keep effect history running; focus/background release requests a priority panic and waits for fresh touch frames to resume. Stream preparation/recovery uses the actual device rate. Default dry floating overs remain unchanged; the opted-in wet branch uses Aquarium's accepted input clamp and linked sample-peak protection.

Every SURFACE PRINT now keeps both output channels, including dual-mono dry output. TAPE/PAD receive stereo; a wet print is baked audio, and playing it through KIT FX applies the effect again. The FX tab states this. A minute of 48 kHz stereo print reserves 23.04 MB of floats before recording. No offline tail/fade or replayable PAD wrapper is implied. See the [route and capture contract](superpowers/specs/2026-10-31-aquarium-surface-stereo-design.md).

## Finite render contract

`AquariumRenderRecipe` wraps the complete 52-control `AquariumRecipe` and its unsigned seed. JSON writes every render setting, including defaults. Unsupported versions, policies, missing values and invalid numbers refuse.

| Setting | Saved behavior |
| --- | --- |
| Clock | Fresh state and saved seed at source frame zero for each render |
| BPM | Explicit 0..1000; zero supplies no tempo |
| Source format | Actual 8..192 kHz; mono duplicates into stereo, stereo channels remain separate |
| Tail | Explicit zero-input duration, default 2 seconds, range 0..30 seconds |
| End fade | Linear fade ending at zero, default 5 ms, range 0..100 ms; capped at output length |
| Output gain | One fixed multiplier for every velocity layer, default 0.5, range greater than zero through 1 |
| Peak policy | Reject a non-finite or above-ceiling final sample; default sample-peak ceiling 0.99 |
| Loop policy | Finite print; existing WAV sustain loops and malformed sampler metadata refuse |

Duration-to-frame conversion rounds to the nearest frame with ties upward. Gain and fade precede final peak validation. There is no normalization, clipping, limiting or resampling. The ceiling measures samples, not reconstructed true peaks. Excessive output refuses with a request to lower gain or effect settings.

`NativeAquarium.maximumOutputFrames()` supplies the native allocation bound: 8,000,000 output frames, including the tail. Stereo output alone can occupy about 64 MB at that bound, with additional source, JNI, WAV and DSP allocations. The bound does not promise that the largest render fits a particular phone. Allocation failures do not adopt partial output.

`AquariumPadRenderer` is a blocking worker-thread adapter. It opens no Oboe stream. The standalone renderer honors gain, fade and tail even for zero effect mixes. `AquariumPadSheet` instead treats zero mixes as REMOVE: its dry preview and removal return the preserved original, including its original channel count.

## Editing a pad

`AquariumPadSheet.apply` uses the source-backed `PrintedPadStore`, also shared by `BoardSheet`. Existing board storage formats remain readable. Aquarium keeps content-addressed original WAVs and state records under `.aquarium/`, independently of the disposable takes bin.

The envelope records the render context, original source snapshot and current output inventory. The snapshot preserves the exact baked input bytes, their SHA-256 hashes, every velocity-zone mapping and the opaque previous recipe. Ordinary pad controls and provenance stay on the pad. A render does not reconstruct its input from a raw synth patch.

Every unique referenced WAV renders before any pad reference changes. New audio uses immutable filenames; another pad sharing the old file remains untouched. Repeated edits start from the saved original, so changing an effect does not compound the previous wet print. Embedded root notes survive. Round-robin chains refuse.

REMOVE restores the pre-Aquarium files, zones and recipe while keeping current pad controls. One-step UNDO restores the previous kept or removed state. Source/current hashes and the expected pad are checked before adoption; stale or damaged state refuses. Rendering or saving can leave unreferenced immutable files. This is not a crash-atomic transaction spanning audio, undo and `kit.json`; the caller still saves the model through the normal kit-writing protocol.

Existing treatment replay copies the render settings onto the destination pad's own baseline. It does not paste the donor's source hashes or replace the destination with the donor's audio. Replay requires an actual `AquariumOfflineRenderer`; the JVM shell does not substitute another DSP implementation. The app's existing PASTE and NONE paths can replay or remove an Aquarium print. The new Aquarium entry stays inside TREATMENT rather than adding another treatment row.

## Copying and exporting

Source-aware single-pad imports carry a standalone Aquarium baseline and remap its restore filenames. Nested source-backed histories need a complete kit-folder copy: for example, Aquarium over a kept board can be edited and removed in its original kit, but a single-pad import explicitly refuses that nested history. Merge and Breed can preflight this refusal before creating their destination.

MPC 2 and MPC 3 exports carry the printed stereo WAVs and normal pad/layer metadata. Flat sidecars drop the source-dependent `aquariumPad` editing wrapper, just as they do for printed boards. They do not invent native MPC effect fields. The existing XPN-based BACKUP also preserves printed sound rather than hidden editing sources. Copy the complete kit directory, including its hidden stores, to preserve source-backed editability.

## Evidence and remaining work

- **Verified:** the shared core's DSP, presets and parameter registry are unchanged by the PAD and performance hosts. The offline bridge's native evidence includes 23 offline/JNI cases against the original three targets. The performance host adds a fourth native target. Native tests compare against the direct accepted processor, including different block partitions, seeds, rates and tempo; actual current run results belong in the PR and CI checks.
- **Measured on the Linux host:** five fresh full-preset renders of a 3-second mono sine plus 2-second tail at 44.1 kHz took a median 92.1 ms, range 91.6..101.3 ms (`g++ -O2`, 256-frame blocks, BPM 123, gain 0.05). This includes native preparation and output allocation, and excludes JNI and WAV I/O. It is not a phone latency measurement. Four independent velocity files require four renders; the PAD panel schedules this work off the main thread.
- **Sanitizers:** the accepted-core and new offline/JNI host targets both pass AddressSanitizer and UndefinedBehaviorSanitizer with leak detection enabled.
- **Tests supplied:** render-context serialization and numeric guards; original-source edits, layers, undo and failure paths; stereo MPC exports; strict sampler metadata; and real Android JNI transfers through `AquariumPadRendererTest`. Execution results belong in the change's validation report and CI checks.
- **Heard:** the earlier six-effect audition was accepted. This code does not establish a new listening result.
- **Panel tests supplied:** all presets and controls, physical-unit accessibility, narrow layout and action states, real JNI preview/KEEP across different-rate velocity layers, saved context, remove/undo, stale-preview cancellation and failure refusal. JVM and emulator execution results belong in the change's validation report and CI checks.
- **Performance checks supplied:** native continuous-clock and exact dry tests, stereo post-mix/PRINT equality, bounded transitions and queue behavior, linked protection, panic/restart/rate behavior, callback allocation interception, strict settings serialization and real Android live-control/print tests. Execution results belong in the current PR and CI checks.
- **Later:** remaining continuous mixer/bounce routes, phone CPU/latency/power measurements, and hardware MPC acceptance.

Biolume retains its feedforward harmonic path and pitch-ring seams; Reef retains its 5000-sample delay ceiling; Fathom retains its complementary one-pole crossovers. The native [README](../app/src/main/cpp/aquarium/README.md) records the accepted model and its limits.

To repeat the host timing measurement from the repository root:

```sh
g++ -std=c++17 -O2 app/src/main/cpp/test/offline_render_benchmark.cpp \
  app/src/main/cpp/aquarium/OfflineRender.cpp \
  app/src/main/cpp/aquarium/AquariumProcessor.cpp -o /tmp/snipsnap-aquarium-benchmark
/tmp/snipsnap-aquarium-benchmark
```

## Why it is built this way

- One native core keeps pad printing and the continuous bus from drifting into different effects.
- One source store protects both boards and Aquarium without duplicating their snapshot and undo rules.
- Preserved baked sources make edits repeatable even when the old recipe describes only the last treatment. Staged previews stay silent until requested and cannot play an obsolete draft.
- Finite PAD prints use fixed gain and reject excessive peaks to preserve soft/hard relationships. The live host separately declares its linked output-protection policy.
- Finite prints avoid claiming a seamless sustain loop without measuring one.
