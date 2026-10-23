# iOS and desktop MIDI, and the path from an Android-only codebase

Lighter depth than the Android material, by design: Android is the product; iOS is a planned
future target and desktop a possible one. The point of this file is twofold: know what each
platform offers so a port is not a surprise, and mine the other platforms for ideas worth
bringing to Android now. Facts dated 2026-10-22.

Sources: [Windows Experience Blog, Windows MIDI Services GA, 2026-02-17](https://blogs.windows.com/windowsexperience/2026/02/17/making-music-with-midi-just-got-a-real-boost-in-windows-11/);
[Sonicstate on the Windows 11 MIDI update, April 2026](https://sonicstate.com/news/2026/04/02/windows11-midi-updates-add-20-multi-clients/);
[midi.org, MIDI 2.0 coming to Windows 11](https://midi.org/midi-2-0-coming-to-windows-11);
[Polyend, device not detected after the March 2026 Windows update](https://backstage.polyend.com/t/device-not-detected-as-midi-device-after-windows-11-march-2026-cumulative-update/24372);
[Microsoft Windows music dev blog](https://devblogs.microsoft.com/windows-music-dev/tag/midi/feed/);
[cp3.io on AUv3 MIDI timing](https://cp3.io/posts/sample-accurate-midi-timing/);
[ktmidi](https://github.com/atsushieno/ktmidi), [ktmidi on Maven Central](https://central.sonatype.com/artifact/dev.atsushieno/ktmidi-macosarm64).

## 1. iOS and macOS (CoreMIDI)

- **CoreMIDI** is the one MIDI API for both. MIDI 2.0 and UMP arrived with `MIDIEventList`
  (iOS 14, macOS 11); the old `MIDIPacketList` path is MIDI 1.0 byte streams.
- **BLE MIDI**: `CABTMIDICentralViewController` (connect to a peripheral) and
  `CABTMIDILocalPeripheralViewController` (**advertise the device as a BLE MIDI peripheral**,
  which Android's platform API cannot do). An iPad can be a BLE keyboard for a Mac; an
  Android phone cannot without a custom GATT server.
- **USB**: class-compliant devices over Lightning or USB-C with no driver; the camera
  adapter on older devices.
- **Network MIDI**: RTP-MIDI sessions through `MIDINetworkSession` with Bonjour discovery;
  macOS has the Audio MIDI Setup network panel. This is where RTP-MIDI matters, because
  Apple devices expect it.
- **AUv3** is the plugin model: instrument units and MIDI-processor units ("aumi") receive
  sample-timed events in the render block; a plugin emits MIDI through
  `AUMIDIOutputEventBlock` with `timestamp->mSampleTime + offset`, and emitting with "a
  timestamp of zero or AUEventSampleTimeImmediate" loses timing. The scheduling model in
  timing-and-scheduling.md §3 is the same model, which is why building it on Android now
  makes the iOS port a transport swap.
- **Inter-App Audio** is deprecated in favour of AUv3; hosts such as AUM and Audiobus provide
  routing between apps, with Ableton Link everywhere (LinkKit is the iOS SDK). Android has
  no equivalent host culture; AAP (Audio Plugins for Android) is the research attempt.
- **Idea to port now**: AUv3's "aumi" units show that a MIDI effect (an arp, a chord
  generator, a conditional-trig filter) is a first-class object between a controller and a
  voice. SnipSnap's PortRouter can host MIDI effects as INTERNAL endpoints.

## 2. Windows

- **Windows MIDI Services** (GA announced February 17, 2026 for Windows 11 24H2 and 25H2,
  phased enablement): MIDI 1.0 and 2.0, every port and endpoint multi-client (the old
  single-client limitation is gone), built-in loopback endpoints, automatic MIDI 1.0 to 2.0
  translation, Network MIDI 2.0, and the `midi.exe` console. JUCE added support in a preview
  branch.
- **Known issues** at the time of writing: the new USB MIDI2 ACX driver enforces descriptors
  strictly, and some devices failed after the March 2026 update (KB5079473) until switched
  back to the "USB Audio Device" driver (Polyend thread). A port would need a diagnostics
  screen here too.
- **Older paths**: WinMM (MIDI 1.0, single-client per port) and the UWP MIDI API remain for
  older Windows versions.

## 3. Linux

- ALSA sequencer (event-based, timestamped queues) and ALSA raw MIDI; UMP support since
  kernel 6.5; JACK and PipeWire for routing between apps. Everything is multi-client.

## 4. Cross-platform abstraction layers

| Layer | What it is | MIDI 2.0 | License | Fit for SnipSnap |
|---|---|---|---|---|
| ktmidi | Kotlin Multiplatform `MidiAccess`: Android (platform API), JVM desktop (RtMidi, libremidi or ALSA backends), Kotlin/Native (Linux, macOS, Windows), iOS arm64, Wasm; UMP, `UmpTranslator`, SMF and SMF2, MIDI-CI | yes | MIT | the natural `expect/actual` shape; adapt its API, do not vendor |
| RtMidi | small C++ MIDI I/O, Android backend fixed October 2026 (PR #417) | no (1.0 only) | MIT-style | desktop fallback; its Android fixes are a bug list to copy |
| libremidi | C++ MIDI I/O with MIDI 2.0, descended from RtMidi | yes | BSD-style (unverified) | native cross-platform layer under a shared C++ engine |
| JUCE | `MidiBuffer` with sample positions, `MPEInstrument`, `MPEZoneLayout`, `ump::` classes, Windows MIDI Services in preview | yes | AGPLv3 or commercial | read-only reference (AGPL); its MPE classes are the clearest MPE design to study |

## 5. The porting path, concretely

1. **Keep the engine and the scheduler in C++** (they already are: `PadEngine`,
   `SurfaceEngine`, `SpscRing`). Oboe on Android, CoreAudio or an AUv3 render block on
   iOS, a small audio layer on desktop (miniaudio, RtAudio, or JUCE if the license is
   accepted).
2. **Put the control plane behind a Kotlin `MidiAccess` interface in `commonMain`** with
   `actual` implementations: Android (`android.media.midi` plus AMidi), iOS (CoreMIDI, by
   hand or through ktmidi), desktop (libremidi or RtMidi). ktmidi proves the shape works.
3. **Keep the event model UMP** so no platform needs a second model.
4. **Link on all three**: NDK build on Android, LinkKit on iOS, the C++ library on desktop
   (license decision first).
5. **Move the pure Kotlin modules as they are**: `:kit`, `:shell`, `:loop` are JVM-only today;
   anything with `java.io.File` or `java.util.concurrent` needs a KMP-compatible
   replacement, which is a mechanical pass, not a redesign.
6. **Expect the surfaces to be rewritten**: Compose Multiplatform runs on iOS and desktop
   and would carry the pad and key screens, but the pointer details (gesture exclusion,
   `MotionEvent` history) are Android-specific and the iOS equivalents (`UITouch` force,
   `maximumPossibleForce`, 3D Touch's successors) have their own calibration.

## 6. Ideas the other platforms give Android now

- BLE peripheral mode (iOS): a custom GATT server on Android would let SnipSnap be a
  wireless controller for a Mac; P3.
- RTP-MIDI (macOS): the way an iPad or Mac expects to see SnipSnap over Wi-Fi; app-level UDP
  plus `NsdManager` on Android; P3.
- Multi-client ports and loopbacks (Windows MIDI Services): Android's virtual port is the
  equivalent; it is a P0 for SnipSnap precisely because nothing else provides it on Android.
- AUv3 MIDI effects: MIDI effects as routable objects; design the PortRouter so an arp or a
  conditional filter sits between any two endpoints.
