# SnipSnap's MIDI, timing and playing surfaces, verified against the repo

Everything here was read from `github.com/BertCalm/SnipSnap` at commit `b429ec2` on
2026-10-22, then updated the same day by the pull request that landed roadmap steps 1 and 2
(the timestamp kept, `PadCommand.frameOffset`), and again on 2026-10-23 at `e7cfc0072` by MIDI
batch A (real-time events, the frame counter, the device fingerprint, the stop-drop guard); those rows say so. The tree moves fast. Treat each line as "true on that day, re-grep before you
lean on it." Where a line says VERIFY, a grep is cheap and a wrong API name in a spec is
expensive: `docs/MIDI_SYNC.md` records 39 corrections to one design document, and most were
a confidently named method, interface or bench row that did not exist.

## 1. What SnipSnap is, for this skill's purposes

An Android sampler (minSdk 29, targetSdk 35, compileSdk 35; `app/build.gradle.kts:17-29`)
that captures sound, trims it to one-shots, lays them on a 4x4 pad grid and exports kits an
Akai MPC loads. Nine pure-JVM Kotlin modules (`:json :xpm :audio :kit :mpc3 :synth :loop
:cli :shell`) plus `:app`, which joins the Gradle build only when an Android SDK is present
(`settings.gradle.kts`). `:app` has no host unit-test source set; its Compose suites live in
`app/src/androidTest`, and the native engines have host tests under `app/src/main/cpp/test`.
So anything MIDI that can be pure (parsing, clock arithmetic, mapping rules, PLLs) goes in
`:kit` or `:shell` where `./gradlew test` proves it; the Android glue in `:app` is proved by
a compiler and a phone.

The synth engines render offline: synthesis is a pure function from parameters to a `Snip`
(luthier's `snipsnap-conventions.md` §1). The realtime native engines (`PadEngine`,
`SurfaceEngine`, `LiveSnapEngine`, C++ under `app/src/main/cpp`) play rendered samples and
run the SURFACE's own granular and corner controls. "Live performance" in SnipSnap means
expressive control of sample playback and of the SURFACE, plus the render-and-retrigger
loop; it does not mean a realtime synthesis voice exists. A MIDI design that assumes one must
say so and call luthier.

## 2. What exists today

| Piece | Where | What it does | Verified detail |
|---|---|---|---|
| `MidiInput` | `app/src/main/kotlin/com/snipsnap/app/MidiInput.kt` (253 lines) | Opens every MIDI device's output ports, parses on the service thread, posts typed events to the main thread | `FEATURE_MIDI` check; API 33+ uses `registerDeviceCallback(TRANSPORT_MIDI_BYTE_STREAM, executor, cb)` and `getDevicesForTransport`, older uses the deprecated pair; `openDevice` async with a main-thread `Handler`; one `MidiWire` per port; `Receiver.onSend` passes its `timestamp` into `MidiWire.feed` and posts the stamped events to the main thread (steps 1 and 2 PR; before it the stamp was dropped); `onDeviceRemoved` lets every note go before the shorter device list is announced; `onDevicesChanged(List<MidiDeviceId>)` carries a `MidiFingerprint` (transport, vendor and product ids, port counts, name hash; `shell/.../MidiFingerprint.kt`) beside each name, `MidiInput.devices` and `Opened.id` expose it, and no Bluetooth address is read (`bd95b1baa`); `close()` is idempotent and closes the Java `MidiDevice` |
| `MidiWire` | `kit/src/main/kotlin/com/snipsnap/kit/MidiWire.kt` (232 lines) | Stateful MIDI 1.0 byte-stream parser | Handles split messages, running status, real-time bytes mid-message, SysEx skipped byte by byte with status-byte termination; `feed(bytes, offset, count, timestampNanos)` emits `Event.Channel` (`NoteOn(channel, note, velocity 0..1, timestampNanos)`, `NoteOff`, `Control`) and `Event.RealTime` (`Clock`, `Start`, `Continue`, `Stop`, stamp only; `0xF8`, `0xFA` to `0xFC`; `0xF9`, `0xFD` to `0xFF` still dropped; `f95cd75c9`), each stamped with the delivery that completed it (0 when the caller gave none); pitch bend, aftertouch and program change are length-honoured and dropped; velocity 0 is note-off; tested by `MidiWireTest` (24 cases); `MidiKeys` and `MidiPads` ignore `RealTime` |
| `MidiGroove` | `kit/src/main/kotlin/com/snipsnap/kit/MidiGroove.kt` (262 lines) | Standard MIDI File read and write for grooves | `write(clip: Mpc3Clip, bpm)`, `read(bytes, name): Imported(clip, bpm?)`; tested by `MidiGrooveTest` |
| `MidiKeys` | `shell/src/main/kotlin/com/snipsnap/shell/MidiKeys.kt` | KEYS's rules for a MIDI note | Keyboard velocity used as is (no touch-Y floor); sustain pedal (CC 64) holds let-go notes; CC 120 and 123 are panic and clear the pedal; every channel listened to; `Voice { noteOn, noteOff, allOff }` |
| `MidiPads` | `shell/src/main/kotlin/com/snipsnap/shell/MidiPads.kt` | PLAY's rule for which pad a note hits | Two maps: `MPC` (note 36 is A02, the exported kit's own answer) and `IN_ORDER` (C1 on A01, a semitone a pad); `IN_ORDER_FIRST_NOTE = 36` |
| `PadHit` | `shell/src/main/kotlin/com/snipsnap/shell/PadHit.kt` | Pad semantics resolved on the JVM | `velocityAt(y, height, floor = SOFTEST)` top-of-pad softest, `SOFTEST = 0.20f`, `CENTER` for TalkBack clicks, `midiVelocity(v) = round(v * 127)`, `resolve(pad, velocity, hitIndex)` to a native `Hit` |
| `KeyHit` | `shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt` | Keygroup semantics (zone, speed, loop, release) for one key press | Native voice only hears "this sample, from here, looping back to there, at this gain and speed" |
| `VoiceAllocator` | `shell/src/main/kotlin/com/snipsnap/shell/VoiceAllocator.kt` | Choke, steal and note-off decisions behind an interface | Eight voices on KEYS, a ninth steals the oldest (`app/README.md`) |
| `PadEngine` (JVM) | `app/src/main/kotlin/com/snipsnap/app/PadEngine.kt` | The Kotlin face of the native pad player | `hit(pad, velocity, voiceId, swap)`, `hitLayers(...)`, `stop(...)` and `allOff(fadeMs)` return `Boolean` (false: the ring refused it, kept and retried from `drainEnded()`; a kept all-off supersedes kept stops; `27d059a65`, `e7cfc0072`), `framesRendered()`; no `noteOn` |
| `PadEngine` (C++) | `app/src/main/cpp/PadEngine.{h,cpp}` | Up to `kMaxVoices` sample players mixed to stereo | `PadCommand { NoteOn, Stop, AllOff, SetGain }` with sample, start, end, loopStart, gains, pitch, reverse, fadeMs, `frameOffset`, generation; `onAudioReady` takes the ring into a held set (`held_`, 256 deep, audio thread only) and renders the block in pieces, applying each command on the frame its `frameOffset` names, an offset past the block carrying into the next; the JNI entry points in `jni.cpp` still leave `frameOffset` at 0, so nothing on the Kotlin side schedules ahead yet (roadmap step 3 computes one from a timestamp); `std::atomic<int64_t> framesRendered_` counts frames per callback, never reset, read by `framesRendered()` (`aa2583bf9`); `jni.cpp` `stopVoice` and `allOff` return `jboolean` from `pushCommand` |
| `SpscRing<T, N>` | `app/src/main/cpp/SpscRing.h` | Single-producer single-consumer ring, UI pushes, callback pops, lock-free, heap-free | Power-of-two capacity, acquire/release atomics, **drops on full** (right for control values, wrong for note-offs unless the consumer drains fast) |
| `openStereoFloatOutput` | `app/src/main/cpp/OboeOutput.h` | The one way the app opens an output | Float stereo, `LowLatency`, `Exclusive` then `Shared` on refusal, buffer at two bursts, `Usage::Media`, `ContentType::Music`; `latencyMillisOf` is UI-thread only (Oboe's pre-R warning) |
| `NativePads`, `NativeSurface` | `app/src/main/kotlin/com/snipsnap/app/NativePads.kt`, `NativeSurface.kt` | JNI bridges | Both expose `latencyMillis(handle)`, `isShared`, `needsRestart`; the surface takes `control(...)`, `setCorner(...)`, `setGrain(...)` |
| `BeatPlayer` + `BeatClock` | `app/src/main/kotlin/com/snipsnap/app/BeatPlayer.kt`, `shell/.../BeatClock.kt` | The project's beat: a `withFrameNanos` loop on the main thread converting wall time to steps | `STEP_MAX_NANOS = 100_000_000L` clamps a stale frame gap; a step is a sixteenth; notes fire through `PadHost` immediately on the frame that crosses them; `GrooveScreen.recordHit` compares a touch's `uptimeMillis` to this loop's nanoseconds |
| `OrbitEngine` | `loop/src/main/kotlin/com/snipsnap/loop/OrbitEngine.kt` | The circular sequencer's engine | `AtomicLong frame` counts frames **written to the sink**; `position()` is a render cursor; writes through `AudioSink.write(block)` (blocking) |
| `LoopEngine` | `loop/src/main/kotlin/com/snipsnap/loop/LoopEngine.kt` | The loop grid's engine | `AtomicInteger interval`, one interval's resolution; the grid has no launcher front door (`docs/MIDI_SYNC.md` "What this does not commit to") |
| `AudioSink` | `loop/src/main/kotlin/com/snipsnap/loop/AudioSink.kt` | The one seam between `:loop` and output | `write(block: FloatArray)` must not throw; live output is an Android implementation (an `AudioTrack` path separate from the Oboe path, per MIDI_SYNC.md), export is a WAV implementation of the same interface |
| `KeysScreen` | `app/src/main/kotlin/com/snipsnap/app/ui/KeysScreen.kt` | The on-screen keyboard | `pointerInput` with `awaitFirstDown(requireUnconsumed = false)`, then `awaitPointerEvent` tracking the first pointer's id; velocity from touch Y through `PadHit.velocityAt`; TalkBack `onClick` plays at `PadHit.CENTER`; OCT shift; eight voices shared with a MIDI keyboard |
| `PlayScreen`, `PadSheetScreen`, `SurfaceScreen`, `LoopGrid` | `app/src/main/kotlin/com/snipsnap/app/ui/`, `app/.../LoopGrid.kt` | The pad grid, the pad editor, the SURFACE, the loop grid | VERIFY each screen's pointer handling before changing it; `PlayScreen` documents row and velocity in its header |
| Manifest | `app/src/main/AndroidManifest.xml:28-31` | `android.software.midi` declared `required="false"` | No Bluetooth permissions; foreground service type `microphone|mediaProjection` only (no `mediaPlayback`) |
| Oboe | `app/build.gradle.kts:106`, `app/src/main/cpp/CMakeLists.txt` | `com.google.oboe:oboe:1.9.0` as a prefab package | No MIDI library of any kind in the build; no `libamidi` linked |

## 3. The four clocks (from `docs/MIDI_SYNC.md`, re-checked)

| Transport | "now" is | Held in | Exposed as |
|---|---|---|---|
| ORBIT, `OrbitEngine` | frames written to the sink | `AtomicLong frame` | `position()`, a render cursor that leads the hardware by the sink's buffering |
| Loop grid, `LoopEngine` | intervals written to the sink | `AtomicInteger interval` | `position()`, one interval's resolution |
| BEAT and GROOVE, `BeatPlayer` | wall time since play started | `withFrameNanos` loop | Compose state; the loop moved out of `GrooveScreen` into `BeatPlayer` after MIDI_SYNC.md was written, so that document's `GrooveScreen` greps are stale while its finding stands |
| PLAY and pads, `PadEngine` | frames rendered | `std::atomic<int64_t> framesRendered_` | `framesRendered()`; nothing reads it yet, and no `getTimestamp` fit maps it to nanoseconds |

There is no hardware-referenced transport shared among them, two separate output streams
(the `:loop` `AudioTrack` path and the Oboe path), and round-trip latency is measured
(`latencyMillis`) and then only printed beside VOICES. `docs/MIDI_SYNC.md` is the design
record: read its "How far to trust this document" section first, then "What time it is,
according to whom", then "The recommendation: master first, follower second". It lists what
a seek needs (the engine carries voices across blocks), why a PLL's slew limit does not
answer a stall (holdover is its own mechanism), and why a readable transport and a
JVM-testable `NativePads` are two seams, not one.

## 4. Bench items that gate clock work (`docs/BENCH.md`, lines 433 to 500)

- **S1** Does the hardware playback position tell the truth? (The `TapeVoice` 800 ms
  `playbackHeadPosition` quirk is the known offender.)
- **S2** What is this phone's output-side delay? (Needs an acoustic reference.)
- **S3** Does rate hold, against a reference and between the app's own two streams?
  (Needs a second device sending beat clock.)
- **S4** How long should a follower coast when the clock stops? (After the follower exists.)

All four need the phone. The bench is paused by its owner. No follower code starts before
S1 to S3 are answered; master-side clock out needs S1 and S2.

## 5. The gaps, in the order the roadmap ranks them

1. ~~Timestamps are discarded at `MidiInput.Receiver.onSend`.~~ Done: every `MidiWire.Event`
   carries `timestampNanos`. Nothing yet *reads* it: `MidiKeys` and `MidiPads` hand the
   note on without its stamp, and the engine has no ns-to-frame mapping (gap 11).
2. **No MIDI output.** No `MidiInputPort` is ever opened; nothing can send a note, a clock
   tick or an LED colour.
3. **No virtual port.** No `MidiDeviceService`; other apps cannot play SnipSnap or be played
   by it.
4. **No BLE.** No scan, no `openBluetoothDevice`, no Bluetooth permissions in the manifest.
5. ~~Commands land on block boundaries.~~ Done in the engine: `PadCommand.frameOffset` is
   honoured mid-block (host test places a note at frame 37). Not yet reachable from Kotlin:
   `NativePads.noteOn` has no offset parameter and `jni.cpp` leaves it 0.
6. **The beat is on the display clock.** `BeatPlayer` converts wall time to steps at frame
   rate with a 100 ms clamp; notes can arrive batched and late. Not a foundation for sync.
7. **Pitch bend, aftertouch and program change are dropped** in `MidiWire` by design. Any
   expression work extends the event set there first (and its hostile-input tests).
8. **No foreground service that holds a device** (the one service is typed
   `microphone|mediaProjection`), so a long session with a BLE device would depend on the
   screen staying on. `connectedDevice` is the honest type for holding one; `mediaPlayback`
   only if the app ever plays in the background.
9. **No diagnostics screen**; the `MidiInput` logcat tag is the only window.
10. **The loop grid has no front door**; giving it a transport is work on a screen users
    cannot reach until that changes.
11. **No ns-to-frame mapping.** The stamp from gap 1 and the offset from gap 5 are not yet
    joined: the engine has no absolute frame counter and no smoothed `getTimestamp` fit, so
    a timestamp cannot become a `frameOffset`. Roadmap P0.3; waits on bench S1.
    ~~...the engine has no absolute frame counter...~~ Counter done (`aa2583bf9`): `framesRendered`
    exists; the `getTimestamp` fit and the Kotlin `frameOffset` remain open.
12. ~~Real-time bytes `0xF8` to `0xFC` are dropped.~~ Done: `Event.RealTime` (`f95cd75c9`).
13. ~~A device has no identity but its display name.~~ Done: `MidiFingerprint` (`bd95b1baa`).
14. ~~A refused stop is a stuck pad until PANIC.~~ Done: kept and retried at screen rate
    (`27d059a65`, `e7cfc0072`); compiled and read, the 64-pad roll row is unrun.

## 6. Where new MIDI work lands

| Kind of code | Module and path | Proof |
|---|---|---|
| Parsing, UMP translation, SysEx chunking, clock arithmetic, PLL, SMF | `kit/src/main/kotlin/com/snipsnap/kit/` beside `MidiWire` and `MidiGroove` | `./gradlew :kit:test` with hostile-input fixtures (`scripts/wire_fixtures.py` makes them) |
| Rules: what a note means to a screen, MIDI learn mappings, controller profiles, pad maps, scale and layout maths | `shell/src/main/kotlin/com/snipsnap/shell/` beside `MidiKeys`, `MidiPads`, `PadHit`, `VoiceAllocator` | `./gradlew :shell:test` |
| Android glue: `MidiManager`, BLE scan, `MidiDeviceService`, foreground service, sensors, `MotionEvent` details | `app/src/main/kotlin/com/snipsnap/app/` beside `MidiInput` | compiler (`android-build` in CI or `:app:assembleDebug` with an SDK) plus a phone; Compose suites in `app/src/androidTest` |
| Realtime: frame-offset commands, AMidi polling, ns-to-frame mapping | `app/src/main/cpp/` beside `PadEngine`, `SpscRing`, `OboeOutput.h` | host tests in `app/src/main/cpp/test` (CMake) |

Keep the house split: semantics on the JVM, the native voice only hears "this sample, these
frames, these gains, this speed" (and, once added, "at this frame"). Keep `MidiWire`'s
contract that no input can make it allocate or stall. Keep `MidiInput`'s rule that a device
going away silences what it turned on before anything else happens.

## 7. The minimum grep set before any MIDI spec

```
git -C <repo> rev-parse --short HEAD
grep -rn "timestamp" app/src/main/kotlin/com/snipsnap/app/MidiInput.kt
grep -rn "class PadCommand" -A 30 app/src/main/cpp/PadEngine.h | grep -n "frame\|offset\|when"
grep -rn "commands_.pop" app/src/main/cpp/PadEngine.cpp
grep -rn "withFrameNanos\|STEP_MAX_NANOS" app/src/main/kotlin shell/src/main/kotlin
grep -rn "openInputPort\|MidiDeviceService\|openBluetoothDevice\|BLUETOOTH" app/src/main
grep -rn "foregroundServiceType" app/src/main/AndroidManifest.xml
grep -rn "sealed interface Event" -A 30 kit/src/main/kotlin/com/snipsnap/kit/MidiWire.kt
grep -rn "S1\|S2\|S3\|S4" docs/BENCH.md | head
```

If any line of this file disagrees with what the grep returns, the grep wins; update this
file and its date.
