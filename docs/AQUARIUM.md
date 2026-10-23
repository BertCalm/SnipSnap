# Aquarium

Aquarium's six accepted effects now have a native offline renderer and an internal whole-pad operation. The render uses the same corrected model imported in [the foundation](https://github.com/BertCalm/SnipSnap/pull/595). This stage connects finite pad printing, saved sources, replay and exports. The dedicated Aquarium controls and continuous performance bus remain later work.

The implementation was developed against SnipSnap `b429ec2c4bc89d225ac476af234c1a88a46fb07d`. The [integration design](superpowers/specs/2026-10-18-aquarium-integration-design.md) and [staged plan](superpowers/plans/2026-10-18-aquarium-foundation.md) explain the larger feature. The owner's positive October 18 listening verdict covers the underlying audition. No new listening verdict is claimed for these host policies.

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

Existing treatment replay copies the render settings onto the destination pad's own baseline. It does not paste the donor's source hashes or replace the destination with the donor's audio. Replay requires an actual `AquariumOfflineRenderer`; the JVM shell does not substitute another DSP implementation. The app's existing PASTE and NONE paths can replay or remove an internally created Aquarium print. No new treatment chip is added in this stage.

## Copying and exporting

Source-aware single-pad imports carry a standalone Aquarium baseline and remap its restore filenames. Nested source-backed histories need a complete kit-folder copy: for example, Aquarium over a kept board can be edited and removed in its original kit, but a single-pad import explicitly refuses that nested history. Merge and Breed can preflight this refusal before creating their destination.

MPC 2 and MPC 3 exports carry the printed stereo WAVs and normal pad/layer metadata. Flat sidecars drop the source-dependent `aquariumPad` editing wrapper, just as they do for printed boards. They do not invent native MPC effect fields. The existing XPN-based BACKUP also preserves printed sound rather than hidden editing sources. Copy the complete kit directory, including its hidden stores, to preserve source-backed editability.

## Evidence and remaining work

- **Verified:** the shared core's DSP, presets and parameter registry are unchanged by this bridge. Native host CTest passes all three targets, including 23 new offline/JNI cases. The JVM main modules compile. Native tests compare the adapter against the direct accepted processor, including different block partitions, seeds, rates and tempo.
- **Measured on the Linux host:** five fresh full-preset renders of a 3-second mono sine plus 2-second tail at 44.1 kHz took a median 92.1 ms, range 91.6..101.3 ms (`g++ -O2`, 256-frame blocks, BPM 123, gain 0.05). This includes native preparation and output allocation, and excludes JNI and WAV I/O. It is not a phone latency measurement. Four independent velocity files require four renders; future controls must schedule that work off the main thread.
- **Sanitizers:** the accepted-core and new offline/JNI host targets both pass AddressSanitizer and UndefinedBehaviorSanitizer with leak detection enabled.
- **Tests supplied:** render-context serialization and numeric guards; original-source edits, layers, undo and failure paths; stereo MPC exports; strict sampler metadata; and real Android JNI transfers through `AquariumPadRendererTest`. Execution results belong in the change's validation report and CI checks.
- **Heard:** the earlier six-effect audition was accepted. This code does not establish a new listening result.
- **Later:** dedicated controls, continuous bus routing and printing, phone CPU/latency/power measurements, and hardware MPC acceptance.

Biolume retains its feedforward harmonic path and pitch-ring seams; Reef retains its 5000-sample delay ceiling; Fathom retains its complementary one-pole crossovers. The native [README](../app/src/main/cpp/aquarium/README.md) records the accepted model and its limits.

To repeat the host timing measurement from the repository root:

```sh
g++ -std=c++17 -O2 app/src/main/cpp/test/offline_render_benchmark.cpp \
  app/src/main/cpp/aquarium/OfflineRender.cpp \
  app/src/main/cpp/aquarium/AquariumProcessor.cpp -o /tmp/snipsnap-aquarium-benchmark
/tmp/snipsnap-aquarium-benchmark
```

## Why it is built this way

- One native core keeps pad printing and the future bus from drifting into different effects.
- One source store protects both boards and Aquarium without duplicating their snapshot and undo rules.
- Preserved baked sources make edits repeatable even when the old recipe describes only the last treatment.
- Fixed gain keeps the relationship between soft and hard hits. Peak rejection makes excessive output visible instead of changing that relationship silently.
- Finite prints avoid claiming a seamless sustain loop without measuring one.
