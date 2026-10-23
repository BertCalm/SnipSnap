# Aquarium native foundation

A JUCE-free C++17 processor for offline pad rendering and future continuous stereo performance buses. The native layer now includes a bounded offline renderer and a separate JNI entry point. It adds no stream ownership, live-engine routing or UI behavior; app-side pad rendering and export policy sit above this layer.

`AquariumProcessor.h` is the stateful block API and `OfflineRender.h` is the fresh-render API. Production sources are `AquariumProcessor.cpp`, `OfflineRender.cpp`, and `AquariumJni.cpp`. All upstream-derived math and DSP live in the private `snipsnap::aquarium::detail` namespace; none of these functions are added to SnipSnap's `DspMath.h`.

## Basic use

```cpp
#include "aquarium/AquariumProcessor.h"

using namespace snipsnap::aquarium;
Processor aquarium;
auto controls = parametersForPreset(Preset::Full);
if (!aquarium.applySnapshot(controls)) { /* invalid controls */ }
aquarium.reset(1337);
if (!aquarium.prepare(44100.0, 256)) { /* config/allocation failure */ }

// For each disjoint planar stereo block:
auto status = aquarium.process(left, right, frames, currentBpm);

// New offline render or explicit transport restart:
aquarium.reset();
// ... feed source, then zero input for the caller's chosen tail duration ...

// Non-audio thread, after callbacks have stopped:
aquarium.release();
```

A successful `prepare` allocates and initializes all state. Rates from 8 to 192 kHz and maximum block sizes 1..8192 frames are supported. Invalid configurations or allocation failure return `false` and retain the previous processor. `release` frees state and retains the current control snapshot and seed. Re-preparing starts fresh using those controls and seed.

`process`, `reset`, and `applySnapshot` perform no allocation, freeing, locking, logging or string lookup. They run on the single owning audio/render thread; the processor does not implement a UI-to-audio queue. Construct the snapshot on the control thread, transport it using a host-owned handoff, and apply the complete snapshot at a block boundary. Host tests intercept allocations during these calls.

Applying controls preserves envelopes, delay contents and modulation clocks. Continuous buses process successive blocks without automatic resets; processing silence advances tails. Offline renders explicitly reset at their chosen start. The host chooses BPM, clock placement and tail duration. BPM `0` selects the accepted free-running modulation behavior; positive BPM enables active tempo-sync settings. Those host/render details do not belong to the reusable sound-control descriptor.

## Buffer and error contract

Processing uses writable, planar float stereo with disjoint channel buffers. Exact and partial aliasing are rejected. Negative or oversized frame counts, invalid pointers, non-finite BPM or BPM outside 0..1000, and non-finite/input magnitudes above 32 are rejected before buffers or processor state change. After `prepare`, zero frames is a no-op and permits null buffers. As with any pointer API, the host must supply actual writable storage for the declared length.

Output is floating-point, unclipped and unnormalized. This layer has no limiter, automatic tail trimming or loudness matching. Rejected calls return a `ProcessResult` status and leave input and DSP state untouched; callers must handle that status before forwarding a buffer.

## Offline PCM and JNI bridge

`renderOffline` accepts a nonempty interleaved mono or stereo float source at its actual 8..192 kHz sample rate, a complete 52-control snapshot, and an explicit uint32 seed. Each call starts fresh at clock origin zero. Mono is duplicated to L/R before effects; stereo channels remain separate. Output is always interleaved stereo at the same rate. There is no resampling, normalization, clipping or WAV parsing in native code.

`OfflineRenderOptions` supplies BPM, zero-input tail frames, end-fade frames, fixed output gain, peak ceiling and block size. Gain is applied before the fade and final peak check. A zero fade disables fading, one fade frame zeroes the final frame, and longer fades ramp linearly from 1 to 0 over the final requested frames. A non-finite or above-ceiling output rejects the complete render; callers choose a lower explicit gain rather than receiving a clipped or automatically normalized result. Valid block sizes are 1..8192; fade length may not exceed source plus tail.

Source plus tail is capped by `kMaxOfflineOutputFrames = 8,000,000` frames, checked before source copying or output allocation. This is a hard size bound, not a guarantee that an Android heap can hold every maximum-size render. Allocation failure is reported. Native output swaps into the caller's vector only after the entire render passes; source and prior output remain unchanged on failure. Input may not alias the output vector's storage.

`NativeAquarium.renderOffline` is a blocking worker-thread JNI call, separate from the existing stream bridge. It uses checked array-region copies rather than pinning Java arrays. `NativeAquarium.maximumOutputFrames()` queries the native limit so Kotlin does not duplicate it. Wrong arguments, invalid controls or peak rejection throw `IllegalArgumentException`; allocation failures throw `OutOfMemoryError`; processor/non-finite-output failures throw `IllegalStateException`. Pending JVM copy/allocation exceptions are preserved, and no native exception crosses JNI.

The raw renderer receives finite PCM only and has no sustain-loop metadata. The app-side pad wrapper separately refuses embedded sustain loops and supplies a finite printed source; that host policy is not inferred or implemented by the native PCM renderer. Future live buses continue to use the stateful `Processor` API instead of invoking a fresh offline render per callback.

The host `aquarium_offline_tests` target covers dry stereo/mono, direct-core parity with seed/tempo/tails, actual-rate block partitioning, explicit gain/fade, strict bounds, peak rejection, allocation failures and JNI copy/exception handling. The existing callback-allocation tests remain separate from the deliberately allocating offline path.

## Parameters and render model

`kRenderModelVersion = 1` identifies the accepted processing model. The canonical `parameters.json` is the sole source for all 52 control names, ranges, defaults and optional integer-selector `step`. Its insertion order is the v1 snapshot order. `generate_tables.py` generates fixed native tables; the Kotlin resource is packaged from the same JSON. All six default stage mixes are zero.

`ParameterSnapshot` holds the full 52-value array and initializes canonical defaults. `applySnapshot` atomically rejects non-finite, out-of-range or fractional discrete-selector values; it preserves both the previous snapshot and DSP history on failure. The control-thread `setParameter` helper rejects unknown/non-finite/fractional-selector input, while clamping finite values to their declared range. The host UI performs any desired selector quantization before building a snapshot.

Seed is a separate `uint32_t` and defaults to 1337. Seed zero uses the accepted deterministic nonzero xorshift mapping. `reset(seed)` is explicit; applying a snapshot never changes the seed or resets history. A seed affects modulation and supports repeatable renders within the same platform/math build. Bit-identical results across different compiler, CPU and math-library implementations are not promised.

`presets.json` retains the eleven accepted audition presets: `dry`, six isolated effects, `depth` (Fathom + Surface), `motion` (Drift + Tide), `color` (Reef + Biolume), and `full`. Native preset helpers use generated fixed tables; no JSON parsing runs in the callback. Changes to presets do not silently change the model's canonical defaults.

## Provenance and accepted sound

The original XO_OX source is pinned to XOmnibus commit `a9bdc7803d899f0b1688fe0c0abc4ce481a76c3e`, with immutable originals and MIT license retained in `upstream/`. `upstream-provenance.json` records those hashes. The license and copyright notices remain in all derived headers.

`AUDITION_CHANGES.md` records the DSP corrections approved in the listening prototype: neutral/directional Surface shelves, Fathom envelope/stereo fixes, rate-defined deterministic Drift, Tide phase/sample-and-hold fixes, and bounded Biolume state corrections. No further waveform, feedback or crossover redesign is introduced here. The integration adaptation only adds the private namespace and a direct fixed-size parameter assignment method, removing the internal named setter from the audio core. `UPSTREAM_CHANGES.diff` makes the complete original-to-native changes reviewable.

`audition-validation.json` is the retained historical report from the accepted audition (214 checks), not a claim about current Android callback timing. The host Aquarium tests exercise this public API and compare its sample output against retained accepted reference code.

Known sound limitations remain unchanged:

- **Biolume is feedforward harmonic shimmer.** There is no connected octave feedback loop. Its rough unwindowed 2× pitch ring retains grain seams around every 100 ms at 48 kHz, and its high-pass path retains the input-minus-cascaded-lowpass approximation.
- **Reef retains the 5000-sample delay ceiling**, so high-rate operation may clamp longer delays and change acoustics.
- **Fathom retains the complementary cascaded one-pole split**, rather than a textbook Linkwitz–Riley topology, and its upward compression remains source-level-sensitive.
- Native host validation does not establish device CPU/power cost, Android latency, live bus routing or export parity for a future app integration.

## Regeneration and validation

Run from this directory:

```sh
python3 generate_tables.py --check
python3 regenerate_core.py --check
```

Omit `--check` to regenerate checked-in output after an intentional change. Core extraction runs in a temporary directory and verifies canonical names/ranges/defaults rather than overwriting metadata. `extract_audition.py` is retained historical extraction input; invoke it through `regenerate_core.py` so its temporary output cannot overwrite the canonical JSON.

The normal native source checker runs the generated-table staleness check. No Python process is needed in an Android production build or audio callback.
