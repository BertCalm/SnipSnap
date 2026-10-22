# Timing, scheduling, clock, Link, and the MIDI subsystem architecture

Where the milliseconds go, how to place an event on a frame, how to send and follow clock,
and the shape of the subsystem that makes all of it one path. Facts dated 2026-10-22;
Google's latency figures are from March 2021 and are the newest public ones the research
found. Read `docs/MIDI_SYNC.md` in the repo before any clock design: it is the house
record of what is there and of what a design document got wrong by prescribing timing
without running the app.

Sources: [Android Developers Blog, "An update on Android's audio latency", March 2021](https://android-developers.googleblog.com/2021/03/an-update-on-androids-audio-latency.html);
[Google, "A new method to measure touch and audio latency" (WALT), 2016](https://android-developers.googleblog.com/2016/04/a-new-method-to-measure-touch-and-audio.html);
[Oboe](https://github.com/google/oboe), [OboeTester Tap-to-Tone](https://github.com/google/oboe/wiki/OboeTester_TapToTone),
[Oboe AudioStream reference](https://google.github.io/oboe/classoboe_1_1_audio_stream.html);
[SuperpoweredLatency](https://github.com/superpoweredSDK/SuperpoweredLatency);
[Chromium issue 40103372, Chrome tap-to-audio on a Samsung A10](https://issues.chromium.org/issues/40103372);
[cp3.io, sample-accurate MIDI timing in AUv3](https://cp3.io/posts/sample-accurate-midi-timing/);
[vector-dsp, sample-accurate MIDI processing](https://vector-dsp.com/blog/sample-accurate-midi-processing-guide/);
[Ableton Link](https://ableton.github.io/link/), [libpd/abl_link](https://github.com/libpd/abl_link), [jbloit/androidLinkTest](https://github.com/jbloit/androidLinkTest);
[android-ndk group on clock polling jitter](https://groups.google.com/g/android-ndk/c/az_9aQWGZwQ);
[Koala manual, settings and sync](https://manual.koalasampler.com/mobile/8-settings/);
[Planet-H Link to MIDI Bridge](https://www.planet-h.com/link-to-midi-bridge/).

## 1. The numbers (cited, with dates)

| Term | Figure | Source and date |
|---|---|---|
| Screen touch latency | 10 to 35 ms, "20ms being fairly typical on modern Android devices" | Google, March 2021 |
| USB MIDI controller input | about 1 ms | Phil Burk (Google), same post |
| BLE MIDI | 7.5 to 20 ms plus up to one connection interval of jitter | ble-midi.md §3 |
| Round-trip audio, 20 most popular phones, average | 109 ms (Jan 2017) to 39 ms (Jan 2021); spread cut to 28 ms | Google, March 2021 |
| Pro-audio requirement | 20 ms round trip required for `android.hardware.audio.pro`; 10 ms the long-term goal; "Mid 2019: Pixel 3A is the first 10ms Android phone" | Google, March 2021 |
| WebView instrument | about 300 ms tap-to-audio in Chrome vs about 70 ms native on a Samsung A10 | Chromium bug (an argument against any WebView-based surface) |
| One 64-frame buffer at 44.1 kHz | 1.45 ms | arithmetic |
| One clock tick at 120 BPM (24 PPQN) | 20.8 ms; at 300 BPM 8.33 ms | arithmetic |

SnipSnap's own numbers: `latencyMillisOf` (Oboe's `calculateLatencyMillis`) is read on the
UI thread and shown beside VOICES. It is output-side only and the HAL may decline to answer
(-1). Bench items S1 and S2 are the measurements that would replace the table above for the
device in hand.

Tools: OboeTester's Tap-to-Tone (touch plus output), Google WALT (external clock, within
1 ms), SuperpoweredLatency (round trip). `scripts/latency_budget.py` sums a chain from these
figures and prints each term's label.

## 2. Oboe configuration, as the house already does it

`OboeOutput.h`: float stereo, `PerformanceMode::LowLatency`, `SharingMode::Exclusive` (MMAP
where the HAL offers it) falling back to `Shared`, buffer at two bursts (one in flight, one
filling). Keep that. Additions worth proposing:

- Raise the buffer on underruns (Oboe's `LatencyTuner`, or `getXRunCount` watched from the
  UI thread) rather than starting high.
- Tier devices by `android.hardware.audio.low_latency` and `android.hardware.audio.pro`
  feature flags and show the tier in diagnostics.
- Oboe's warning: do not call `getTimestamp` from the data callback on Android before R.
  minSdk 29 is Q, so sample the timestamp from a non-audio thread and hand the mapping to
  the callback through an atomic pair.

## 3. The scheduling pattern (the core of every CLOCK deliverable)

1. **Every event carries a `CLOCK_MONOTONIC` nanosecond timestamp from the edge.** Java
   `MidiReceiver.onSend` gives one; AMidi gives one; `MotionEvent.getEventTimeNanos()` (API
   34) or `getEventTime() * 1_000_000` gives one for touches; the sequencer stamps its own
   from the beat position. `MidiInput` discards it today; that is gap number one.
2. **The engine keeps an absolute frame counter** (frames rendered since the stream started;
   `OrbitEngine`'s `AtomicLong frame` is the pattern) and **a smoothed mapping from
   nanoseconds to frames**: sample `AudioStream::getTimestamp(CLOCK_MONOTONIC, &framePosition,
   &timeNanos)` off the audio thread every few hundred ms, fit `frame = a * ns + b` with a
   slow one-pole on `a` and `b`, publish both atomically. The mapping drifts slowly (the
   audio clock and the system clock are different oscillators); the fit absorbs it.
3. **Live input lands at `frame = mapped(ts) + endpointOffset + modeOffset` within the block.**
   Two modes: `modeOffset = one buffer` gives jitter-free playing (every event is placed
   where it happened, one buffer late, so arrival jitter inside the buffer vanishes);
   `modeOffset = 0` plus "now" gives lowest latency and inherits arrival jitter. Offer both as
   a setting named in words ("steady" and "fastest"); default to steady for BLE and fastest
   for USB and touch, and say why in the setting's description.
4. **Sequencer events are stored in beats or ticks**, never in frames or milliseconds, and
   turned into frame offsets per block from the current tempo and the block's beat span.
   Tempo changes and loop wraps stay correct; this is the AUv3 and JUCE `MidiBuffer` model
   (cp3.io: emitting with "a timestamp of zero or AUEventSampleTimeImmediate" loses timing).
5. **Modulation may step in 16 or 32 sample sub-blocks**; note starts and sample retriggers
   land on the exact frame. `PadCommand` needs a `frameOffset` field and `apply` must honour
   it mid-block; today everything applies at the top of the callback.
6. **One SPSC ring per producer** into the callback (UI, MIDI service thread, sequencer
   thread if it is not the callback itself), each carrying timestamped commands; the
   callback merges by frame. The ring drops on full; size it so it never fills under a
   burst (a chord of 10 notes plus pressure on each is 20 commands in one millisecond), and
   make a dropped note-off impossible by keeping note-offs in a separate ring or by having
   the allocator re-send panic on overflow.

## 4. MIDI clock, transport and song position

- Clock is 24 pulses per quarter note (`F8`), with `FA` start, `FB` continue, `FC` stop and
  `F2` Song Position Pointer (14-bit, in sixteenth notes, 6 clocks each). `MidiWire` steps
  over real-time bytes today without emitting them; clock in starts by emitting them with
  their timestamps.
- **Clock out**: compute each tick's time from the audio clock (frame counter through the
  mapping of §3) and send with `MidiInputPort.send(..., timestamp)` or
  `AMidiInputPort_sendWithTimestamp` a few ticks ahead, from the MIDI-out thread. Never from
  a sleeping thread: a 64-frame poll already adds 1.45 ms of jitter (android-ndk group), and
  Android threads sleep longer than asked.
- **Clock in**: a tempo estimator over tick timestamps (moving average over a window, or a
  PLL with proportional and integral terms on phase error) producing beats-per-nanosecond,
  plus a latency offset the user can set (Koala's "Sync latency compensation" and the Link to
  MIDI Bridge's ±300 ms setting are precedents). **Holdover is a separate mechanism**: when
  ticks stop, the follower coasts at the last rate for a bounded time, then stops; a slew
  limit on the PLL does not answer a stall (MIDI_SYNC.md). A seek on SPP is not arithmetic
  because the engine carries voices across blocks; it is a stop, a reposition, a start.
- **Two clock sources never fight.** Koala's rule: "there can only be one clock source"
  (receive cannot combine with Link). Copy it as a radio button.
- **Master first.** MIDI_SYNC.md's case: it is a complete capability, it forces the single
  transport question and nothing else, it proves the plumbing while the stakes are low, and
  a half-working follower is worse than none. Against it: it does not answer "play along with
  the thing already running". That is Josh's product call; put it in the spec's first line.
- **MTC** (quarter-frame `F1`): receive only, if ever. Not a beatmaking need.

## 5. Ableton Link

Beat, tempo, phase and start/stop sync over a LAN, each peer keeping its own timeline. On
Android it is the C++ library built with the NDK (libpd/abl_link and jbloit/androidLinkTest
are the examples; LinkKit is iOS only). **License: GPLv2+ or a proprietary license from
Ableton.** Bundling it makes the binary GPLv2+; fine if SnipSnap picks GPL-3.0 (GPLv2+ and
GPL-3.0 are compatible through the "or later"), not fine under MIT or Apache-2.0 without
Ableton's commercial license. The license decision comes before the Link decision
(libraries-and-licenses.md §0). Link's own timing rule is the same as §3: ask Link for the
beat at an audio-clock time, never at "now".

## 6. Network MIDI, OSC, WebSocket

RTP-MIDI (AppleMIDI, RFC 6295) has no Android platform support; it is app-level UDP plus
mDNS through `NsdManager`. No maintained open-source Android library was verified
(unverified.md). Windows MIDI Services ships Network MIDI 2.0 (UDP UMP). Lower priority than
Link; an OSC or WebSocket bridge is useful only for a companion editor.

## 7. The MIDI subsystem architecture for SnipSnap

```
[Transports]                        [Kotlin control plane]                  [C++ realtime plane]
USB / BLE / virtual (MidiManager) -> MidiDeviceRegistry                  -> AMidi poll in the Oboe callback
MidiDeviceService (1.0)           ->   hotplug, permissions, fingerprints    (or SpscRing fed by MidiReceiver)
MidiUmpDeviceService (35+)        -> PortRouter: routes, filters, MPE zones -> UMP EventQueue, timestamped
Link (NDK) / clock in             -> MidiLearnStore (DataStore or Room)    -> Scheduler: ns->frame mapping,
sensors, touch surfaces           -> TransportController (one timeline)       sequencer ticks->frames
                                                                            -> voices (PadEngine, SurfaceEngine)
                                                                            -> out ring -> MIDI-out thread -> send
```

1. **Internal event model is UMP.** Translate MIDI 1.0 at the edges (ktmidi's
   `UmpTranslator` rules, adapted). 16-bit velocity, per-note controllers, 256 channels,
   and the same shape for a keyboard, a tilt sensor, a finger and the sequencer.
2. **An endpoint is an endpoint.** `MidiEndpoint { id, name, transport (USB | BLE | VIRTUAL |
   NETWORK | INTERNAL), protocol (MIDI1 | UMP), direction }`. The on-screen pads, the keys,
   the sensors and the sequencer are INTERNAL endpoints, so routing, learn and recording treat
   every source alike. This is "MIDI as glue" between the sampler and the synth paths.
3. **Threading.** Receiver threads copy into a ring and return. The audio thread drains
   rings or polls AMidi, parses, schedules, renders. One MIDI-out thread sends. No
   allocation, lock, JNI or log on the audio thread.
4. **One clock domain and one transport.** CLOCK_MONOTONIC nanoseconds everywhere; one
   hardware-referenced transport the four existing clocks are migrated onto, one at a time,
   ORBIT first because its render cursor is closest to right. Record using corrected times,
   not arrival times.
5. **Lifecycle.** A foreground service while performing (`connectedDevice` to hold BLE
   devices; `mediaPlayback` added only if the app plays with no screen); reopen remembered
   devices on `onDeviceAdded`; close the Java `MidiDevice` on teardown.
6. **MIDI learn** is a mapping store, not screen state (controller-profiles.md §4).
7. **Controller profiles** are declarative JSON (controller-profiles.md §1).
8. **Virtual ports**: "SnipSnap In" and "SnipSnap Out" on every install, a UMP endpoint on
   35+, per-track routing of the sequencer to external gear or apps.
9. **Path to iOS and desktop**: a platform-neutral C++ scheduler and engine, Kotlin
   `expect/actual` for the `MidiAccess` layer (ktmidi covers Android, JVM, Kotlin/Native,
   iOS arm64, Wasm), Link on all three (platforms-and-porting.md).

## 8. What a CLOCK deliverable must contain

- Which of the four clocks it touches, and that it adds no fifth.
- The scheduling mode and the per-endpoint offsets it assumes, labelled proposed.
- The bench items (S1 to S4) it depends on and the new rows it adds, as questions with
  their difficulty named, not as procedures (the MIDI_SYNC.md lesson).
- JVM tests for the pure parts: PLL response to a pulse series with stall and jitter
  fixtures, SPP arithmetic, tick scheduling from a tempo map.
- "Why it is built this way" for Josh.
