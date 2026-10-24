# Android MIDI: the platform API, USB, virtual ports, UMP and AMidi

Facts dated 2026-10-22 unless a line says otherwise. The Android MIDI stack is mature but
has a long tail of version-specific bugs; the version gates below are the ones that matter
against SnipSnap's minSdk 29 (Android 10, Q).

Sources: [android.media.midi package](https://developer.android.com/reference/android/media/midi/package-summary),
[MidiManager](https://developer.android.com/reference/android/media/midi/MidiManager),
[MidiUmpDeviceService](https://developer.android.com/reference/android/media/midi/MidiUmpDeviceService),
[NDK AMidi guide](https://developer.android.com/ndk/guides/audio/midi),
[AMidi reference](https://developer.android.com/ndk/reference/group/midi),
[Google android-midi group](https://groups.google.com/g/android-midi),
[RtMidi PR #417, Android backend fixes, merged 2026-10-15](https://github.com/thestk/rtmidi/pull/417),
[AOSP MidiService.java](https://android.googlesource.com/platform/frameworks/base/+/master/services/midi/java/com/android/server/midi/MidiService.java).

## 0. Version gates against minSdk 29

| Feature | API | Android | SnipSnap status |
|---|---|---|---|
| `android.media.midi` (USB host, BLE central, virtual) | 23 | 6.0 | in use (`MidiInput`) |
| NDK AMidi (`<amidi/AMidi.h>`, `libamidi`) | 29 | 10 | available everywhere, unused |
| USB open hang on devices with fewer input than output ports | 29 to 32 | 10 to 12 | **affects minSdk 29 to 32 users**; workaround in §2 |
| MIDI 2.0 over USB, `getDefaultProtocol`, `TRANSPORT_UNIVERSAL_MIDI_PACKETS`, Executor-based `registerDeviceCallback`, `getDevicesForTransport` | 33 | 13 | `MidiInput` already branches on 33 |
| BLE disconnect and long-SysEx fixes | 13 to 14 | see ble-midi.md | not wired |
| `MidiUmpDeviceService` (virtual MIDI 2.0 device) | 35 | 15 | not wired; compileSdk is 35 so it compiles |
| Large-screen orientation and resize opt-outs ignored | 36 | 16 | touch-instruments.md §1 |

Android 16 (June 10, 2025) and 17 (API 37, June 16, 2026) shipped no MIDI API changes the
research could find. Re-check the API diff report on each release; `unverified.md` lists it.

## 1. The Java API, the way SnipSnap should use it

- **Entry point**: `context.getSystemService(Context.MIDI_SERVICE) as MidiManager`, gated by
  `PackageManager.FEATURE_MIDI`. Declare `<uses-feature android:name="android.software.midi"
  android:required="false"/>` (the manifest already does) and check at runtime (`MidiInput`
  already does). Device makers may omit the feature; the app still opens its screens.
- **Enumeration**: `getDevices()` is deprecated at 33. Use
  `getDevicesForTransport(TRANSPORT_MIDI_BYTE_STREAM)` for MIDI 1.0 and
  `getDevicesForTransport(TRANSPORT_UNIVERSAL_MIDI_PACKETS)` for UMP. A MIDI 2.0 USB device can
  appear under both; de-duplicate by `MidiDeviceInfo.getId()`.
- **Hot-plug**: `registerDeviceCallback(transport, Executor, DeviceCallback)` on 33+, the
  Handler form below. Callbacks: `onDeviceAdded`, `onDeviceRemoved`, `onDeviceStatusChanged`
  (port busy flags). One `unregisterDeviceCallback` serves both forms.
- **Opening**: `openDevice(info, listener, handler)` is asynchronous; the listener may fire
  after the caller closed (`MidiInput` handles that by closing a late device). Then
  `device.openOutputPort(i)` to **receive** (connect a `MidiReceiver`) and
  `device.openInputPort(i)` to **send** (`MidiInputPort.send`).
- **The naming trap**: direction is from the device's point of view. A keyboard's notes come
  out of its *output* port; you send LED colours into its *input* port. AMidi inherits the
  same names. Write the comment every time; the three people who will read the code will all
  trip on it.
- **Sending**: `MidiInputPort.send(buffer, offset, count)` or `send(buffer, offset, count,
  timestamp)` with timestamps in `System.nanoTime()` (CLOCK_MONOTONIC) units. The system
  delays delivery until the timestamp, which is how clock out can be scheduled from a
  non-realtime thread without jitter from that thread's wake-ups.
- **Receiving**: `MidiReceiver.onSend(msg, offset, count, timestamp)` runs on the service's
  binder thread. Never allocate, block or touch UI state there; copy into a lock-free queue
  with the timestamp and return. The timestamp is the service's receive time in the same
  nanosecond domain; keep it.
- **Framing**: the byte stream is not message-aligned. One delivery can hold several
  messages or half of one; SysEx spans deliveries. `MidiWire` is the stateful parser and it
  is per port because running status is per stream. Google's `MidiTools` sample has a
  `MidiFramer` that does the same job if a second reference is wanted.
- **Port busy**: a port opened by another app may refuse; `onDeviceStatusChanged` says which
  ports are in use. Show it in words.

### Persisting a device identity

The display name of a USB device differs before and after the app has USB permission
(RtMidi's 2026 work found this). Persist `(vendorId, productId, portIndex, nameHash)` from
`MidiDeviceInfo.getProperties()` (`PROPERTY_USB_DEVICE` carries the `UsbDevice` on USB
transports; `PROPERTY_MANUFACTURER`, `PROPERTY_PRODUCT`, `PROPERTY_NAME`, `PROPERTY_SERIAL_NUMBER`,
`PROPERTY_BLUETOOTH_DEVICE`). Never persist a BLE MAC: Android randomizes them.

### USB permission and the device filter

Android asks for USB permission per device. Add an intent filter for
`android.hardware.usb.action.USB_DEVICE_ATTACHED` with a `device_filter.xml` that matches USB
audio class 1 subclass 3 (MIDI streaming) so an "Always open SnipSnap" grant survives
replugging, and so plugging a controller in can launch the app. The `MidiManager` path still
needs the system's own permission dialog on first use of a given device.

## 2. USB: host mode, OTG, hubs, class compliance, the hang

- **Class compliance**: Android supports class-compliant USB MIDI only. Devices needing vendor
  quirks (the original Roland UM-ONE is the usual example) stay unsupported "unless the
  quirks are rewritten in Java" (android-midi group). Say so in the diagnostics screen rather
  than failing silently.
- **Power**: the phone bus-powers the device through OTG. Keyboards with lights and pad
  controllers with LEDs can exceed what a phone supplies; a powered hub or a self-powered
  device fixes "detected then drops out".
- **Host role fights**: some USB-C devices also try to be host. Polyend reported a Tracker Mini
  and a Galaxy S23 on Android 13 refusing each other while a OnePlus 7 on Android 12 worked
  ([Polyend Backstage](https://backstage.polyend.com/t/usb-midi-unavailable-possible-host-client-issue-or-android-13/5675)).
  Tell users to try the cable the other way round and a hub.
- **Samsung USB audio routing**: Galaxy Tab threads from 2019 to 2024 report MIDI keyboards not
  detected; the commonly cited workaround is Developer options, "Disable USB audio routing",
  with mixed results ([Samsung Community](https://eu.community.samsung.com/t5/tablets/midi/td-p/1776190)).
  A class-compliant audio interface that also carries MIDI shares one cable and avoids the
  routing question.
- **Virtual cables**: a device with several USB MIDI cables shows as several ports on one
  `MidiDeviceInfo`. List each port and let the user name it.

### The Android 10 to 12 open hang (RtMidi PR #417, tested on Android 10 hardware)

Opening a USB MIDI device with **fewer input ports than output ports** hangs the open, and
the device "stays dead for every app until it's unplugged". Google fixed it in Android 13.
The test device was a Keith McMillen SoftStep (1 in, 3 out). RtMidi's fix: read the USB
descriptors before opening and refuse that combination on affected versions, or give up
after 5 seconds. For SnipSnap: on `Build.VERSION.SDK_INT in 29..32`, compare
`info.inputPortCount` and `info.outputPortCount`, warn in words when input < output, and put
the open behind a timeout so the UI never waits on it. (A `MidiDeviceInfo` port count comes
from the same descriptors, so no raw USB access is needed for the check; VERIFY on a device
that it reports before the hang.)

Two more findings from the same PR worth copying: closing from a thread not attached to the
JVM aborted the process (close on a known thread), and failing to close the Java `MidiDevice`
leaked service connections (`MidiInput.release` already closes it; keep that in any rewrite).

## 3. Virtual ports: `MidiDeviceService` (MIDI 1.0)

Publishing a port is what lets other apps play SnipSnap's kits and lets SnipSnap drive other
synth apps. None of the leading Android grooveboxes documents doing it (app-landscape.md §1).

1. Subclass `MidiDeviceService`. Override `onGetInputPortReceivers()` to return one
   `MidiReceiver` per published input port (what other apps send to you) and use
   `getOutputPortReceivers()` to send out of your published output ports.
2. Declare it in the manifest with `android:permission="android.permission.BIND_MIDI_DEVICE_SERVICE"`,
   an intent filter for `android.media.midi.MidiDeviceService`, and a
   `<meta-data android:name="android.media.midi.MidiDeviceService" android:resource="@xml/midi_device_info"/>`
   whose XML lists the device name, manufacturer, product and named ports.
3. The service is started by the system when any app opens the device, including SnipSnap's
   own UI through `MidiManager`. Route the receivers into the same input path as a USB
   device: a virtual endpoint is an endpoint.
4. AOSP's `MidiService` notes that users cannot open virtual devices created by other
   Android users (multi-user phones); not a SnipSnap concern, but it explains one class of
   "device missing" report on shared tablets.
5. Name the ports plainly: "SnipSnap In" (what you play into it), "SnipSnap Out" (its
   sequencer and pads going out). Google's samples (`MidiSynthExample`) are the reference.

## 4. MIDI 2.0 and UMP on Android

- **Android 13**: MIDI 2.0 over USB host. `MidiDeviceInfo.getDefaultProtocol()` reports
  `PROTOCOL_UMP_MIDI_2_0`, `PROTOCOL_UMP_MIDI_1_0_*` variants, or `PROTOCOL_UNKNOWN` (MIDI 1.0
  byte stream). AMidi has the matching `AMIDI_DEVICE_PROTOCOL_*` constants including the
  `_AND_JRTS` (Jitter Reduction timestamp) forms.
- **Android 15**: `MidiUmpDeviceService` publishes a virtual UMP device, guarded by
  `BIND_MIDI_DEVICE_SERVICE`. Its ports are bidirectional ("Unlike MIDI 1.0, MIDI 2.0 ports are
  bidirectional"), and "only complete UMPs should be sent": whole 32, 64, 96 or 128-bit
  packets, never a split word.
- **Negotiation is the app's job**: Android has no MIDI-CI service. A UMP device that wants
  Property Exchange or Profiles answers MIDI-CI SysEx itself. Atsushi Eno wrote in April 2024
  that there were "no MIDI 2.0 apps that offer MIDI-CI Properties on Android yet"
  ([post](https://atsushieno.github.io/2024/04/12/midi2-on-android.html)); `ktmidi-ci`
  implements the responder side.
- **Spec state**: the June 2023 update brought UMP and MIDI 2.0 Protocol v1.1 (M2-104-UM),
  MIDI-CI v1.2 (M2-101-UM) and the MIDI Clip File (M2-116-U). Whether Android implements
  UMP 1.1 Stream messages is **unverified** (a midi.org comment questioned it).
- **Device reality, late 2026**: MIDI 2.0 controllers exist but are rare; Android apps
  publishing UMP ports are research projects (AAP, ResidentMIDIKeyboard). MIDI 2.0 is a
  differentiator, not a compatibility need. A UMP-native internal event model costs little
  and buys 16-bit velocity, 32-bit controllers, per-note pitch bend and per-note controllers,
  note-on attributes (pitch 7.9 for microtonal notes) and JR timestamps.

### UMP in one table

| Message type | Size | Carries |
|---|---|---|
| 0x0 Utility | 32 bits | NOOP, JR Clock, JR Timestamp |
| 0x1 System common and real-time | 32 | clock F8, start FA, continue FB, stop FC, SPP F2 |
| 0x2 MIDI 1.0 channel voice | 32 | the 1.0 messages, 7-bit data, 16 groups x 16 channels |
| 0x3 Data 64 (SysEx 7) | 64 | SysEx in 6-byte chunks with start, continue, end |
| 0x4 MIDI 2.0 channel voice | 64 | note on/off with 16-bit velocity and attribute, per-note pitch bend, per-note controllers (registered and assignable), 32-bit CC, RPN/NRPN as 32-bit, program change with bank |
| 0x5 Data 128 (SysEx 8, mixed data set) | 128 | 8-bit SysEx |
| 0xD Flex data | 128 | tempo, time signature, metadata, lyrics |
| 0xF Stream | 128 | endpoint discovery, function blocks, protocol negotiation |

`ktmidi`'s `UmpTranslator` is the reference for 1.0 to 2.0 and back (MIT; adapt, do not
vendor: libraries-and-licenses.md).

## 5. NDK AMidi: polling MIDI inside the audio callback

- **Availability**: NDK r20b+, API 29+, which is every SnipSnap install. Include
  `<amidi/AMidi.h>`, link `amidi`, obtain the device with `AMidiDevice_fromJava(env, javaMidiDevice)`
  on a JNI thread, then `AMidiOutputPort_open(device, portIndex, &port)`.
- **Receive**: `AMidiOutputPort_receive(port, &opcode, buf, maxBytes, &numBytes, &timestamp)` is
  **non-blocking** and returns one message at a time (opcode `AMIDI_OPCODE_DATA` or
  `AMIDI_OPCODE_FLUSH`). Google suggests calling it from the audio callback with "very little
  performance impact". The timestamp is CLOCK_MONOTONIC nanoseconds. This removes the JNI hop
  and the main-thread hop from the hot path entirely: the callback drains the port, parses
  (a C++ port of `MidiWire`'s rules, or a UMP parser), maps timestamp to frame, and
  schedules in the same block.
- **Send**: `AMidiInputPort_send(port, buf, numBytes)` and
  `AMidiInputPort_sendWithTimestamp(port, buf, numBytes, timestamp)`. **AMidi blocks when
  writing.** Send from one dedicated MIDI-out thread fed by a `SpscRing`, never from the
  audio callback.
- **Semantics**: AMidi "is a simple data transport mechanism" that imposes no MIDI
  semantics; a parser is still needed. `AMidiDevice_getDefaultProtocol` exposes the UMP
  protocol on 33+.
- **Lifecycle**: `AMidiDevice_release` on the same thread model as the Java close; keep the
  Java `MidiDevice` alive while the native handle exists.
- **Recommendation for SnipSnap**: AMidi input polled in `PadEngine::onAudioReady` (and the
  surface's callback) on every install, with the Java `MidiReceiver` path kept only for the
  UI monitor and MIDI learn (which want events on a normal thread). Sample:
  [android/ndk-samples native-midi](https://github.com/android/ndk-samples/tree/master/native-midi).

## 6. Gotchas list (copy into any WIRE review)

1. Input and output port names are from the device's side.
2. Byte streams are not message-aligned; parse statefully per port.
3. Receiver callbacks are on a binder thread; copy and return.
4. Opening is asynchronous and may complete after close.
5. Display names change once USB permission is granted; persist ids.
6. Android 10 to 12: input < output port count can hang the open.
7. Close the Java `MidiDevice`; otherwise the service connection leaks.
8. The feature may be absent; the app must still open its screens.
9. A port another app holds reports busy; say so.
10. `AMidiInputPort_send` blocks; `AMidiOutputPort_receive` does not.
11. UMP ports take whole packets only.
12. There is no MIDI-CI in the platform; a UMP device answers it alone.
