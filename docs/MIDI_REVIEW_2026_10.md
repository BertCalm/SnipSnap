# Review: SnipSnap's MIDI input path, and what a Launchpad and MPC sync need first

Job: REVIEW with a WIRE seam (the Launchpad) and a CLOCK seam (the MPC). Read only, commit `b429ec2` (2026-10-21, "Merge pull request #613"). I ran nothing on a device; every number below is read, cited or proposed, and the last section labels which.

## The path today, as read

A note from a USB keyboard travels six hops before it is a sample in a buffer:

1. **Service binder thread.** `MidiInput.Receiver.onSend(msg, offset, count, timestamp)` (`app/src/main/kotlin/com/snipsnap/app/MidiInput.kt:186`) calls `wire.feed(msg, offset, count)` and then `handler.post { ... events.forEach(l::onEvent) }`. The `timestamp` parameter is accepted and never read. This is the only place in the repo that ever sees when a note happened.
2. **`MidiWire.feed`** (`kit/src/main/kotlin/com/snipsnap/kit/MidiWire.kt:72`) parses statefully per port, handles split messages, running status, real-time bytes mid-message, SysEx with lost terminators. The event set is `NoteOn(channel, note, velocity)`, `NoteOff(channel, note)`, `Control(channel, controller, value)`. No timestamp field. Pitch bend, aftertouch, program change and all real-time bytes (`0xF8` clock, `0xFA` start, `0xFC` stop) are stepped over and dropped. 16 tests in `MidiWireTest`.
3. **Main thread.** The posted block checks `alive`, then hands each event to `Listener.onEvent`. On PLAY that is `KitScreen.kt:380`, `MidiPads.handle(event, midiMap, onHit = ::playHeld, onRelease = ::release, onPanic = ::panic)`. On KEYS it is `KeysScreen.kt:118`, `midiKeys.handle(event)` into `InstrumentPlayer`.
4. **`MidiPads.slotForNote`** (`shell/.../MidiPads.kt:54`) picks the slot under the MPC map (note 36 is A02) or IN_ORDER (C1 is A01). `KitScreen.playHeld` (`KitScreen.kt:327`) asks the `VoiceAllocator`, stops what was choked or stolen, and calls `player.hit(pad, velocity, allocation.started.id)`.
5. **`PadEngine.hit`** (`app/.../PadEngine.kt:280`, `@Synchronized`) resolves the pad through `PadHit.resolve` and calls `NativePads.noteOn(...)`, which in `jni.cpp:344` builds a `PadCommand { NoteOn, voiceId, sample, start, end, loopStart, gainL, gainR, pitch, reverse }` and pushes it on `SpscRing<PadCommand, 256> commands_`.
6. **Audio thread.** `PadEngine::onAudioReady` (`app/src/main/cpp/PadEngine.cpp:359`) runs `adoptPendingBank()`, then `while (commands_.pop(c)) apply(c);`, then `render(out, numFrames)`. A NoteOn sets `v.pos = start` and the voice begins at frame 0 of this block.

So the question "when does this note sound" has the answer: at the first block boundary after the main thread got round to the posted Runnable, plus the stream's output latency. Nothing along the way remembers when the key went down. That is the one fact both of your goals depend on, so it is finding number one.

## Findings

### Fix now

**F1 (lens B). The timestamp dies at `MidiInput.kt:186`.** `onSend` receives `timestamp` in `System.nanoTime()` units (CLOCK_MONOTONIC, the same domain Oboe's `getTimestamp` and `MotionEvent.getEventTimeNanos()` use) and discards it. Every downstream decision then runs on "when the main thread woke up", which under Compose can be anywhere from under a millisecond to a whole display frame (about 16 ms at 60 Hz) late, and it varies hit to hit. A player hears that as jitter, which the research in `touch-instruments.md` says is what hands cannot adapt to; a steady delay they can.

Why it is first: a Launchpad is a pad controller, and pad players hear timing more than keyboard players do. And MPC sync is nothing but timestamps: a clock tick without its arrival time is a count, not a clock. `MidiWire` currently throws the `0xF8` bytes away at line 82, so even keeping the timestamp in `MidiInput` would not get you clock in; it is the first of two changes, not the whole fix.

What to change: `MidiWire.Event` grows a `timestampNanos: Long` (or `feed` takes the delivery timestamp and stamps every event it completes from that delivery; the service gives one timestamp per `onSend`, so that is the resolution you actually have). `MidiInput.Receiver.onSend` passes `timestamp` through. `MidiWireTest` gets a round-trip case. Nothing else needs to change to land this, which is why it is a P0.1 in `roadmap.md` with no dependency. Roadmap number: P0.1.

**F2 (lens B). `PadCommand` has no frame offset, and `onAudioReady` applies every command at the top of the block** (`PadEngine.cpp:362`, `PadEngine.h:37`). Even with F1 landed, the engine has no way to use a timestamp: `apply` sets `v.pos` and the voice starts at frame 0 of whatever block drains it. With Oboe's buffer at two bursts (`OboeOutput.h:59`), and a burst that on most phones is somewhere between 96 and 240 frames at 48 kHz (general knowledge, not a measurement; your device's burst is one `getFramesPerBurst` call away and nothing in the repo prints it), that quantises every note to a 2 to 5 ms grid and lands it half a block late on average. This is the second half of the same fix and the thing that lets the sequencer and MPC clock land notes on a frame rather than a block.

What to change: `PadCommand` gets `int32_t frameOffset` (default 0). `apply` honours it by starting the voice at that frame of the block, which in this engine is cheapest as `v.startAt = frameOffset` and a `render` loop that skips frames before it (one extra `if` per voice per frame, no allocation). The host test harness in `app/src/main/cpp/test/engine_tests.cpp` already drives `onAudioReady` directly (`callback(e, frames)` at line 25), so "push a note at frame 37, read zeros for 37 frames and the sample from 37" is a ten-line test in a file that already exists. Roadmap number: P0.2.

**F3 (lens A). Nothing in the repo can send a byte.** `grep -rn "openInputPort\|MidiInputPort"` over `app/src/main` finds nothing; `MidiInput.openDevice` opens output ports only (`MidiInput.kt:124`). The manifest declares no `MidiDeviceService` and `CMakeLists.txt` links `oboe::oboe` and nothing else, so AMidi is not available either. This is a hard gate on both goals:

- A Launchpad that is not told to enter Programmer mode (a SysEx, `controller-profiles.md` §2) and has no LEDs driven is a dumb 8x8 note grid, and a worse one than the screen because it cannot show which pads have sounds.
- MIDI clock out to the MPC is a stream of `0xF8` bytes 24 times a beat. No port, no clock.

What to change: open each device's input ports too (naming from the device's side: you send into its *input* port; `MidiInput.kt:25` already explains the trap for the receive side, so copy the comment), and put sends on one dedicated thread fed by a queue. `MidiInputPort.send(buffer, offset, count, timestamp)` takes a future timestamp and the system delays delivery, which is the mechanism clock out will use later. Roadmap number: P0.4.

### Try next

**T1 (lens B). The two-ring question before a controller arrives.** `SpscRing` drops on full (`SpscRing.h:17`, by design, right for faders). `commands_` is 256 deep and the UI drains the ended ring every frame, so a USB keyboard never fills it. A Launchpad in a drum roll with LED echo is different in kind: 64 pads can each send note on and off within a few ms, each becoming a `NoteOn` or `Stop` push. A dropped `Stop` is a stuck gate pad until PANIC. Options, in the order I would try them: (a) make `PadEngine.stop` return the push result like `hit` does and have `KitScreen.release` fall back to `allOff` on false; (b) a second, smaller ring for stops; (c) size up and cap the drain per block (the realtime audit in `review-rubric.md` §2 flags an uncapped `while (pop)` as a starvation risk, and `PadEngine.cpp:362` is one). Bench row to add: a 64-pad roll on the device, count stuck voices. Measured nowhere yet.

**T2 (lens A). Device identity for a profile.** `MidiInput.nameOf` (`MidiInput.kt:201`) falls back through `PROPERTY_NAME`, `PROPERTY_PRODUCT`, `PROPERTY_MANUFACTURER`. A Launchpad profile needs to match on something steadier than a display name (the name can change once USB permission is granted, per RtMidi's 2026 Android work cited in `android-midi-api.md` §1). No code in `app/src/main/kotlin` reads `PROPERTY_USB_DEVICE`, vendor id or product id today (grepped). Add a fingerprint `(vendorId, productId, portIndex, nameHash)` to `Opened` and expose it on `Listener.onDevicesChanged` so the profile loader has something to key on. Cheap, no dependency.

**T3 (lens B). The engine has no absolute frame counter.** `grep -rn "getTimestamp\|framePosition" app/src/main/cpp` finds nothing; the only frame counts are the print tap's. The ns-to-frame mapping (`timing-and-scheduling.md` §3 step 2) needs `frames rendered since start` incremented in `onAudioReady` and `AudioStream::getTimestamp` sampled off the audio thread (Oboe's pre-R warning; minSdk 29 includes Q, and `OboeOutput.h:70` already respects it for `calculateLatencyMillis`). This is the piece that turns a kept timestamp into a frame offset, and it is the piece that bench item S1 gates: if the HAL's reported position lies (the `TapeVoice` 800 ms case in `BENCH.md` S1), the mapping is built on sand. Land the counter now (no bench dependency, it is arithmetic), hold the `getTimestamp` fit until S1 is answered on your phone. Roadmap number: P0.3.

### Big idea

**B1. Reorder the goals: Launchpad before MPC, and master before follower.** The Launchpad needs F1, F2, F3 and T2 and nothing from the bench. The MPC needs all of those plus a transport that can be read, and `docs/MIDI_SYNC.md` (read at its "How far to trust this document" and "The recommendation" sections) and `docs/BENCH.md` S1 to S3 say that transport cannot be designed without the phone. The four clocks the state file lists (ORBIT frames written, loop intervals, GROOVE off `withFrameNanos` with the 100 ms clamp in `BeatClock.kt:26`, pads nothing) have no hardware-referenced member; that is roadmap P1.2 and it depends on S1 and S3.

So "sync to my MPC" is really two products. *SnipSnap drives the MPC* (master, P1.3) needs P0.3, P0.4, S1 and S2; it is complete on its own and MIDI_SYNC.md makes the case well. *SnipSnap plays along with the MPC* (follower, P1.8) needs a PLL in `:kit` with stall and jitter fixtures, a holdover rule that is separate from the PLL (MIDI_SYNC.md's correction), and S3 and S4. Which one you mean is a product call I cannot make for you; if the honest answer is "play along", say so in the spec's first line and expect the longer road. Either way, `MidiWire` has to start emitting real-time bytes with timestamps (F1's second half), which is a small change with a hostile-input test to go with it.

### Disclosed, not fixed

- The main-thread hop is documented as deliberate (`MidiInput.kt:32`): a MIDI note is "no further from the engine than a finger is". That is true today and it is the right reason for the hop to exist for *screen state* (glow, allocator, choke groups live on the main thread by design). It is not a reason to lose the timestamp on the way. Keep the hop; carry the time through it.
- Pitch bend, aftertouch and program change are dropped by design (`MidiWire.kt:25`). A Launchpad Pro MK3's poly aftertouch would want them (P2.1); the X and Mini MK3 send velocity only, so this is not on the Launchpad's critical path unless you have a Pro.
- Commands land on block boundaries; `MIDI_SYNC.md` already records this. F2 is the fix, named here because it is now on your critical path rather than a survey note.
- The `onDeviceRemoved` ordering (silence first, then shrink the list, `MidiInput.kt:142`) and the `alive` flag that drops in-flight events after a cable pull (`MidiInput.kt:155`) are right and should survive any rewrite; so should closing the Java `MidiDevice` in `release` (RtMidi found that leaking it leaks service connections).
- `MidiWire.feed` allocates one `ArrayList` per delivery (`MidiWire.kt:77`). Fine on the binder thread; it would not be fine if the parser were ever moved into the audio callback (the AMidi path in `android-midi-api.md` §5). Not a bug, a boundary to remember.
- No LICENSE file in the repo (checked `ls LICENSE*`). Not a MIDI finding, but Ableton Link is GPLv2+ or paid, so if "sync" ever means Link the license decision comes first. Flagged once; I will not raise it again.

## What has to change first, in order

| Step | Change | Where | Proof | Unblocks |
|---|---|---|---|---|
| 1 | Keep the timestamp: `MidiWire.Event.timestampNanos`, `onSend` passes it through; `MidiWire` emits `Clock`, `Start`, `Stop`, `Continue` events with timestamps instead of dropping them | `MidiWire.kt`, `MidiInput.kt`, `MidiWireTest` | `./gradlew :kit:test` | everything below |
| 2 | `PadCommand.frameOffset`, honoured in `apply` and `render` | `PadEngine.h`, `PadEngine.cpp`, `jni.cpp`, `NativePads.kt`, `PadEngine.kt` | `engine_tests.cpp` places a note at frame 37 | sample-accurate notes, sequencer on frames |
| 3 | Absolute frame counter in the engine; the `getTimestamp` fit waits for S1 | `PadEngine.{h,cpp}` | engine test with a synthetic clock | ns to frame mapping |
| 4 | Output: open input ports, one MIDI-out thread, a send queue | beside `MidiInput.kt` | loopback cable, two ports | Launchpad LEDs and mode SysEx, clock out |
| 5 | Device fingerprint on `Opened` and in `onDevicesChanged` | `MidiInput.kt` | a device shows the same id before and after USB permission | profile matching |
| 6 | Stop-drop guard (T1) | `PadEngine.kt`, `KitScreen.kt` | a roll bench row | a Launchpad that never sticks |
| 7 | Launchpad profile as JSON, LED mirror of the screen grid on the out thread | `:shell` profile model, `assets/controllers/` | the device lights as the screen does | the Launchpad |
| 8 | Master clock out from the frame counter; follower only after S3 | `:kit` tick scheduling, `:app` send | an MPC follows for eight bars | the MPC |

Steps 1 to 6 need no phone and no bench. Step 3's second half and step 8 do.

## Ledger

- **Verified** (grepped or read at `b429ec2`): everything in "The path today"; `timestamp` unused at `MidiInput.kt:186`; no `openInputPort`, `MidiInputPort`, `MidiDeviceService`, `openBluetoothDevice` or Bluetooth permission anywhere in `app/src/main`; `PadCommand` has no frame or offset field; `while (commands_.pop(c)) apply(c);` at `PadEngine.cpp:362`; no `getTimestamp` or frame counter in `app/src/main/cpp`; `foregroundServiceType="microphone|mediaProjection"` only; `BENCH.md` S1 to S4 at lines 438 to 495; `MidiWireTest` has 16 cases; `engine_tests.cpp` drives `onAudioReady` directly; no LICENSE file; `oboe::oboe` is the only native dependency in `CMakeLists.txt`.
- **Measured on device**: nothing. I cannot hold the phone.
- **Cited**: Oboe's pre-R `getTimestamp` rule (Oboe docs); the 20 ms touch figure (Google, March 2021, old); the USB display-name change and the leaked-connection finding (RtMidi PR #417, October 2026); Launchpad Programmer mode and LED conventions (`controller-profiles.md`, which says to re-read the vendor PDF before hard-coding any byte). The 96 to 240 frame burst range is general knowledge, not a citation; treat it as a placeholder until your phone reports its own.
- **Proposed**: the `timestampNanos` field shape; `frameOffset` as a skip in `render`; the stop-drop guard options; the step order.
- **Untestable here**: your phone's burst size and output delay (S2); whether its HAL's playback position lies (S1); drift between the Oboe path and the `:loop` `AudioTrack` path (S3); how a Launchpad roll behaves against the 256-deep ring. Each is a bench row, not a guess.

## Why it is built this way

- **The timestamp is kept because a player's hands do not care about average latency; they care about the same latency every time.** The main-thread hop is a steady delay only if the engine can place the note where the key actually went down. Without the timestamp the hop is a random delay, and the random part is what the research says players cannot learn to play through.
- **The frame offset lives in the command and not in the screen** because the screen (Compose, the allocator, the glow) is the right place to decide *what* a note means and the wrong place to decide *when*; the engine already has the only clock that matters, the one the loudspeaker is on. This is the house split (`PadEngine.h:32`) extended by one word: "this sample, these frames, these gains, this speed, at this frame."
- **Output goes on its own thread** because `MidiInputPort.send` can block and `AMidiInputPort_send` is documented to; one blocked send inside the audio callback is an audible dropout, and inside a Compose frame it is a hitch in every animation. A queue and a thread are cheap; the alternative is an intermittent bug nobody can reproduce.
- **The Launchpad comes before the MPC** because every piece of it is provable on the JVM or the host test harness, and every piece of the MPC's clock rests on three bench questions the repo already says cannot be answered without the phone. Shipping the controller proves the plumbing (open, send, cable pull, LEDs) while the stakes are a dark pad rather than a drifted recording.
- **The risky decision** is step 1's second half: making `MidiWire` emit real-time bytes. It is three new event types, but clock arrives 24 times a beat on a stream that today only ever sees notes, and the parser's "never allocates or stalls" contract has to survive a flood of `0xF8`. Write the hostile fixture (`scripts/wire_fixtures.py` makes one) before the event types.

## If you want a sharper next pass

Say which Launchpad (X, Mini MK3, Pro MK3; the SysEx device byte and whether you get aftertouch differ), the phone and its Android version (the Android 10 to 12 USB open hang bites devices with fewer input ports than output ports; I have not verified a Launchpad's port counts, and `MidiDeviceInfo` on your phone will say), and whether "sync to my MPC" means the MPC follows SnipSnap or SnipSnap follows the MPC. The third answer changes step 8 from a week to a season.

---

## Status (added 2026-10-22 by the session that implemented steps 1 and 2)

Landed on branch `ccr-99e7fd8e-ehicsb`, scoped by the prompt "no MIDI output, BLE or clock
work in this PR":

- **Step 1, done except its clock half.** `MidiWire.Event.timestampNanos` exists on every
  event, `feed` takes the delivery's stamp, `MidiInput.Receiver.onSend` passes it through,
  and `MidiWireTest` round-trips it (19 cases now). `MidiWire` still drops `0xF8`, `0xFA`,
  `0xFB` and `0xFC`: emitting `Clock`, `Start`, `Stop` and `Continue` is clock work and
  waits for the hostile fixture this review asks for first.
- **Step 2, done in the engine.** `PadCommand.frameOffset` is honoured mid-block by
  `PadEngine::onAudioReady`, for every command type and not only NoteOn, and carried across
  blocks; `engine_tests.cpp` places a note at frame 37. `jni.cpp`, `NativePads.kt` and
  `PadEngine.kt` still send 0: a Kotlin parameter nothing can yet fill, in a module this
  session could not compile, is left for step 3 to add with the number that fills it.
- Steps 3 to 8 are open. `.claude/skills/midiwright/references/snipsnap-midi-state.md` says
  what the tree has now; this review stays as read at `b429ec2`.

**Update 2026-10-23 (batch A, recorded in `MIDI_GAPS_2026_10.md`).** The clock half of step 1
landed in `f95cd75c9`: `MidiWire` emits `Clock`, `Start`, `Continue` and `Stop`, with the
hostile fixture this review asked for. The counter half of step 3, which the review's T3 named,
landed in `aa2583bf9`; the `getTimestamp` fit is still held for S1, and `frameOffset` is still
0 from Kotlin. T2, the device fingerprint, landed in `bd95b1baa`. T1, the stop-drop guard,
landed in `27d059a65` and `e7cfc0072` as the review's option (a) with a retry, not the
fall-back to `allOff`; it is compiled and read, not run, and the 64-pad roll bench row is
unrun. Nothing in this batch was heard on a phone.
