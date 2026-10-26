# The MIDI roadmap for SnipSnap: gaps and opportunities, prioritized

Tied to the repo at commit `b429ec2` (2026-10-22). Each item names the file it changes or
the file it lands beside, the proof, and the bench row it depends on. "Producer value per
build round" is the ranking rule, the same one luthier's gap scan uses: a cheap item that
reaches every session outranks a clever one that reaches a few.

## P0: foundation (the notes arrive on time, from anything, and leave)

| # | Item | Lands | Proof | Depends on |
|---|---|---|---|---|
| 1 | **Keep the timestamp.** Landed (clock half too: `MidiWire` emits `Clock`, `Start`, `Continue`, `Stop` as `Event.RealTime`, `f95cd75c9`): `MidiInput.Receiver.onSend` passes `timestamp` into `MidiWire.feed`; every `MidiWire.Event` carries `timestampNanos` | `MidiInput.kt`, `MidiWire.kt` | `MidiWireTest` round-trips timestamps (done); a monitor showing arrival deltas waits on P0.7 | nothing |
| 2 | **A lock-free path to the callback** with a frame offset. Landed in the engine: `PadCommand.frameOffset`, honoured mid-block, carried across blocks. Still open: the JNI and `NativePads.noteOn` parameter, and MIDI input joining `SpscRing` from the service thread (or AMidi polling in the callback) | `PadEngine.{h,cpp}` (done), `jni.cpp`, `NativePads.kt` | `app/src/main/cpp/test` places a command at frame 37 and reads the sample starting there (done) | nothing |
| 3 | **The ns-to-frame mapping**: counter landed (`framesRendered_` in `PadEngine`, `aa2583bf9`); the fit is still open. Absolute frame counter, `getTimestamp` sampled off the audio thread, smoothed fit published atomically | `PadEngine.cpp`, `OboeOutput.h` | engine test with a synthetic clock (the counter's test is done: 0, then 192, survives stop and start); bench S1 for the real one | S1 |
| 4 | **MIDI output**: open `MidiInputPort`s, one MIDI-out thread fed by a ring, `send` with timestamps. Landed (`6967038ec` to `9c4a7cda4`, `MidiOut`; a queue on one `HandlerThread` rather than a ring; the loopback row is unrun) | `MidiOut.kt` beside `MidiInput.kt` (`MidiOut` opens the input ports for the process; `MidiInput` opens output ports only) | a loopback cable between two ports on a device; the monitor shows what left | nothing |
| 5 | **A published virtual port** ("SnipSnap In", "SnipSnap Out") through `MidiDeviceService`. Landed (`3cbc0f4ce`, `SnipSnapMidiService`; no other app has opened it yet) | new `MidiDeviceService` subclass in `:app`, manifest, `res/xml/midi_device_info.xml` | Koala or G-Stomper opens it and plays a kit | nothing |
| 6 | **Pads fire on down, tracked by pointer id, with gesture exclusion and immersive mode** (audit `PlayScreen`, `PadSheetScreen`, `SurfaceScreen`; `KeysScreen` is already right) | `app/.../ui/` | Compose suites in `androidTest`; a tap-to-tone bench row | nothing |
| 7 | **A diagnostics screen**: endpoints with transport and protocol, live monitor, `latencyMillis`, build fingerprint, a copyable report. Landed: `MidiScreen` behind the gear, then MIDI, `MidiMonitor` and `MidiReport` in `:shell` (`MidiMonitorTest`, `MidiReportTest`), `MidiInput(tapRaw = true)` with `Listener.onRaw` and `onRefused`, `MidiInput.endpoints` with port names. Not in it: `latencyMillis` (the screen runs no engine) and burst size. Compiled by `android-build` only; not yet run on a phone | `:app`, a model in `:shell` | the report pastes into an issue | nothing |
| 8 | **A foreground service holding devices** during a session (`connectedDevice` type; `mediaPlayback` only when background playback is in scope) | manifest, `:app` | a 30-minute lock-screen cycle keeps a BLE keyboard connected | nothing |

From the review's T-items, landed: T1 the stop-drop guard (`27d059a65`, `e7cfc0072`; compiled and read, the roll bench row unrun) and T2 the device fingerprint (`bd95b1baa`); T3 is P0.3's counter above.

## P1: performance essentials

| # | Item | Lands | Proof | Depends on |
|---|---|---|---|---|
| 1 | **MIDI learn** with profiles, relative encoders, 14-bit CC, NRPN, pickup, feedback | mapping model in `:shell`, store in `:app` | JVM tests on decoding and takeover; a knob moves a macro | P0.4 for feedback |
| 2 | **One transport**: migrate ORBIT's render cursor onto the hardware-referenced frame mapping, then BEAT off the display clock | `OrbitEngine.kt`, `BeatPlayer.kt`, `BeatClock.kt` | `BeatClockTest` numbers stay pinned; a bench row for drift between the two output streams | S1, S3 |
| 3 | **MIDI clock out** with start, stop, continue and SPP, computed from the audio clock, sent with timestamps | `:kit` (tick scheduling), `:app` (send) | an MPC follows SnipSnap for eight bars; S2 sets the offset | P0.3, P0.4, S1, S2 |
| 4 | **Ableton Link** via the NDK, after the license decision | `app/src/main/cpp`, a `LinkTransport` in `:app` | two phones on one LAN agree on the downbeat | the LICENSE file |
| 5 | **In-app BLE scanner** with persistent connection, reconnection with backoff, Android 13 warnings, MTU-chunked SysEx | `:app` beside `MidiInput`, manifest permissions | a BLE keyboard plays KEYS and survives a lock-screen cycle | P0.8 |
| 6 | **Velocity modes** (fixed, position, area, accelerometer, hybrid) with calibration and a curve editor; **16 levels**; **pressure-driven note repeat** | functions in `:shell` beside `PadHit`, UI in `:app` | JVM tests per mode; a bench row with a real finger | P0.6 |
| 7 | **Launchpad X and Mini MK3 profiles** in Programmer mode mirroring the screen grid | `:shell` profile model, `assets/controllers/*.json`, send path | the device in hand lights as the screen does | P0.4 |
| 8 | **MIDI clock in** as a follower, with estimator, latency offset and holdover | `:kit` (PLL with fixtures), `:app` | fixtures for stall and jitter; S3, S4 on hardware | P1.2, S3, S4 |

## P2: expression and sequencing depth

| # | Item | Lands | Proof |
|---|---|---|---|
| 1 | **The event set grows**: pitch bend, channel and poly aftertouch, program change in `MidiWire`, with hostile-input tests; a UMP internal queue | `MidiWire.kt`, a `Ump.kt` in `:kit` | `MidiWireTest` fixtures from `scripts/wire_fixtures.py` |
| 2 | **MPE in and out**, per-note modulation sources on the sample voice (pitch ratio, gain, loop window) and the SURFACE's corners | `:shell` zone model, `PadEngine` per-voice targets | JVM zone allocation tests; an MPE keyboard bends one note of a chord |
| 3 | **Isomorphic and scale grids** with slide rails and rounding, sharing one layout code path | `:shell` layout maths, `:app` screen | layout tables tested; the Launchpad mirror still matches |
| 4 | **Step sequencer depth**: probability, conditional trigs, ratchets, micro-timing, p-locks, Euclidean, polymeter beyond ORBIT's rings | `:loop` beside `OrbitPatterns`, `Arp.kt` | pure-function test tables |
| 5 | **Sensor modulation** with tilt-engage gating, calibration, smoothing, as learn sources | `:shell` source model, `:app` sensor glue | JVM filter tests; a bench row for feel |
| 6 | **Scala and MTS** through MTS-ESP's ideas (0BSD), tuning column in `Scales` | `:audio`, `:kit` | `.scl` round-trip tests |
| 7 | **SMF import and export** beyond grooves (tempo map, CC lanes) and **last-N-bars MIDI capture** | `MidiGroove.kt`, a MIDI ring beside `RingBuffer` | SMF round-trip; a capture after the fact plays back what was played |

## P3: frontier

| # | Item | Why it waits |
|---|---|---|
| 1 | `MidiUmpDeviceService` with 16-bit velocity, per-note controllers and a MIDI-CI responder (ktmidi-ci patterns) | few UMP peers exist; Android 15+ only |
| 2 | Mic pitch-to-MIDI, onset-to-pad, slice-to-MIDI groove export | needs P2.1 and the `:audio` detectors judged for live latency |
| 3 | Push 2 support including the display over USB bulk | USB host code beside the MIDI API; one device |
| 4 | KMP `MidiAccess` for iOS and desktop; BLE peripheral mode via a custom GATT server; RTP-MIDI | after the Android foundation is proven on devices |

## Gaps this skill would not fill

- A realtime synthesis voice driven by MIDI: SnipSnap's engines render offline by design;
  luthier owns whether that ever changes.
- A WebView or web-based surface: the latency numbers rule it out.
- A generic "works with any controller" promise: profiles per device, honestly listed.
- MTC: not a beatmaking need.

## How to use this file

A deliverable's "what next" section names the item it advances by number, the ones it
unblocks, and any bench row it adds. A gap scan starts from the P0 table and asks what has
landed since `b429ec2` (grep the minimum set in snipsnap-midi-state.md §7).
