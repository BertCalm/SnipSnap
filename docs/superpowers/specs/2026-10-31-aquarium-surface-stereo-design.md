# Aquarium on SURFACE, with stereo PRINT

Read against SnipSnap `fde661771` on 2026-10-31. Extends the merged
[PadEngine backend](2026-10-26-aquarium-padengine-performance-design.md) and
[KIT PERFORMANCE controls](2026-10-28-aquarium-performance-controls-design.md).

The characteristic gesture is choose a kit → opt SURFACE into its Aquarium sound
→ play its touch surface through a continuous environment → keep the gesture as stereo audio.

## Route and sound ownership

SURFACE's new FX tab offers USE KIT FX and RESET TAIL. The route defaults off and
is saved per kit as the optional boolean `surface.json.aquariumFromKit`. Missing
fields in old version-1 files mean false; a present value must be a boolean.

The option references the kit's existing complete AquariumPerformanceSettings.
It does not duplicate the 52 controls, seed, output gain, policies or global
bypass into surface.json. KIT → PERFORMANCE remains their editor. Tempo follows
the current kit, or free time at zero. Unreadable/future kit settings remain
preserved and resolve dry with the existing warning. Loading another kit first
disconnects the old insert; the new route cannot activate until that kit's
surface settings are ready. Fresh opt-in and a different kit request a reset.

Turning SURFACE's route off selects a zero-mix/default snapshot, avoiding hidden
wet-core work. Turning it on restores the saved sound and starts fresh history
at its saved seed. If the kit's global bypass is set, SURFACE respects it; that
bypass continues clocking the configured processor just as the KIT bus does.

This is an independent processor on SURFACE's own stream. It shares control
values with KIT, not audio, effect history, transport phase or a print buffer.
Playing a beat beside SURFACE still uses two stream owners. There is no universal
app master insert, and SURFACE PRINT does not collect the beat's separate output.
ORBIT transport/audition, SPLIT, instruments and the other mixers remain separate.

## Signal and print contract

```text
four source slots, folded to mono as before
    → existing loop/GRAIN/SWARM and corner/modulator controls
    → existing crush, drive, filter, echo, spring and gate
    → duplicate the legacy mono output into interleaved L/R
    → accepted continuous Aquarium PerformanceBus, actual stream rate + kit BPM
    → the same stereo buffer to Oboe and PRINT
```

The existing source/legacy DSP is unchanged. With the route off, default finite
playback is bit-exact, including the legacy floating overs above full scale.
There is no new clamp in renderMono. The accepted PerformanceBus clamps the wet
processor's input to +/-1, then applies its fixed output gain and stereo-linked
0.99 sample-peak guard. A hot SURFACE chain can therefore change character on
insertion in addition to the six effect settings. During bypass/crossfade the
original dry branch retains its old range, which can exceed 1. This is not
reconstructed true-peak protection or a post-resampling ceiling guarantee.

All SURFACE prints are now interleaved stereo, including dual-mono dry prints.
The native arm reserves two channels, JNI transfers the number of samples rather
than frames, and Kotlin constructs a two-channel Snip at the actual engine rate.
The existing record/stop/Done/take ownership handshake remains intact. A minute
at 48 kHz reserves 23.04 MB of float samples on the caller thread, twice the old
mono reservation. Oversized callbacks still use the existing 4096-frame mono
scratch and the bus's fixed 256-frame scratch; no new callback allocation occurs.

PRINT keeps the actual performance, including changes, transitions, tails and
protection. It adds no offline Aquarium tail/fade policy and creates no editable
source-backed PAD wrapper. Existing TAPE and PAD landing paths receive stereo;
normal WAV writing/resampling/PCM clipping still applies. A wet print is already
baked. Playing it again through an enabled KIT Aquarium applies the effect again;
the FX tab says so. MPC export carries the recorded audio, not this live insert.

## Continuous history, release and recovery

Ordinary touches and individual source-slot swaps share Aquarium history. A
finger lift feeds the fading legacy source and its existing tails onward; it
does not panic or restart the room. Sound/tempo changes retain the accepted bus's
20 ms wet fade/adopt/fade policy; gain and global bypass retain their own ramps.
This is snapshot editing, not continuously interpolated filter automation.

Focus/background release additionally requests a priority panic. The callback
discards at most the touch ring's usable capacity, forces its gate down and
panics Aquarium. A full queue cannot revive an old queued touch after release.
A fresh screen-rate control frame resumes a touch from cleared Aquarium history;
an input arriving inside the discard window may wait for the next screen frame.
The source chain's existing dry echo/spring behavior remains its own policy.

Prepare Aquarium only on the control thread, after the actual stream rate is
known and before requestStart. Release only after callbacks have stopped. As for
PadEngine, optional preparation failure permits default dry playback, while a
requested wet stream fails startup instead of silently playing dry. Recovery
prepares fresh history at the new rate and retains the last accepted controls.
Protected-frame diagnostics reset on preparation. Physical-device recovery and
callback deadlines remain unmeasured.

Native configuration uses the same strict fixed-snapshot JNI decoder as
NativePads, including UInt32 seed bounds, exact registry length, discrete control
domains, BPM/gain/boolean ranges and pending/region exception checks. Kotlin
retains the latest refused request, keeps reset sticky, and retries from its
existing frame-rate control calls. No JSON parsing, lookup, preparation, locks,
allocation or free is added to the audio callback.

## Persisting the route safely

All SURFACE sidecar edits now stage the latest immutable settings snapshot per
directory. Worker writes read that directory's snapshot under the existing
KitWrites mutex. A fast kit switch cannot redirect a pending write to the new
kit's values, and reversed completion of two edits cannot restore an older
snapshot. A directory removed before the worker writes is refused rather than
recreated by SurfaceStore.save. The helper changes no kit.json or source WAV.
A failed write uses the existing visible settings-not-saved report; the in-memory
choice remains available for another edit. Routing cannot be toggled during PRINT
or its take/landing handshake.

## Evidence ledger

- **Verified/read:** the named base's mono source chain, existing stereo Oboe
  stream, mono print tap, surface.json codec, KitWrites locking and the two print
  landing paths. Accepted Aquarium DSP/parameters/presets/render model are unchanged.
- **Measured locally:** all five native targets pass, including six new SURFACE
  integration cases and two new JNI cases. The dedicated six-case target passes
  ASan/UBSan with leak detection. The header-only Oboe harness now emits its base
  virtual ABI/RTTI for full UBSan instrumentation; it still cannot open a stream
  and never substitutes a working audio device. Callback interception reports zero
  allocations and frees through adoption, priority panic and oversized stereo print.
  The 85 focused JVM cases pass, including five new routing/storage cases.
- **Negative controls:** moving only PRINT before Aquarium fails the named exact
  post-bus stereo test. Removing only the pending-write directory guard fails the
  removed-directory test. Both mutations are restored before the passing checks.
- **Device checks supplied:** real Oboe/JNI dry/stereo frame counts, actual effect
  width retained through WAV writing, wet mute across panic/resume, global bypass,
  closed-handle refusal, and the actual FX tab persisting opt-in/off without changing
  kit metadata/source audio. Execution results belong in the PR/CI report.
- **Unmeasured/unheard:** physical phone CPU/latency/power, device-loss recovery and
  MPC listening. No new listening verdict is inferred from host or emulator checks.

## Why it is built this way

- Reusing the accepted continuous bus preserves one set of DSP laws and transition
  policies while allowing SURFACE to make stereo motion from its mono source.
- Printing the same stereo buffer sent to Oboe prevents a wide performance from
  collapsing into a different mono recording.
- An explicit per-kit route leaves existing performances dry until chosen and
  avoids copying sound settings into a second editor/store.
- Ordinary release lets the room ring; priority panic handles an interrupted
  performance without allowing queued old touches to restart it.
- Per-directory pending snapshots keep asynchronous saves attached to the kit
  that was edited, including when the player switches kits before IO completes.
