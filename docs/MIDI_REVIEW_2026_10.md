# Review: the MIDI input path, and what has to change first

Written 2026-10-22 against commit `b429ec2`, by the midiwright skill
(`.claude/skills/midiwright`), read only: nothing here was run on a phone.
The question asked was "I want to add a Launchpad and eventually sync to my
MPC; what has to change first and why?" The subject is `MidiInput.kt` in
`:app`, `MidiWire.kt` in `:kit`, and the road from a parsed note to
`PadEngine`'s callback.

This file replaces the review that was written as an eval output of the
skill; that file was not in the upload that installed the skill, so the
review was regenerated here from the same repo state, with the same step
list, so a session can read the steps from a file rather than a prompt.

## What is good, in two lines

`MidiWire` is a real stream parser: split messages, running status, real
time bytes mid-message and a SysEx that lost its 0xF7 are all handled, and
`MidiWireTest` pins each one. `MidiInput` gets the lifecycle right: a device
going away silences what it turned on before the shorter list is announced,
and an event already in flight when its device went is dropped, not played.

## The realtime audit (run first)

`PadEngine::onAudioReady` (`app/src/main/cpp/PadEngine.cpp`) adopts a
pending bank, drains `commands_` with `while (commands_.pop(c)) apply(c);`,
renders, and records the print tap. Nothing on that path allocates, locks,
logs or calls JNI; `endVoice` pushes to a second `SpscRing`. Two notes:

- The drain is uncapped. A producer that floods the ring (256 deep) costs the
  callback 255 `apply` calls in one block. Each is cheap, so this is a
  **Try next**, not a Fix now: cap the drain per block once a second producer
  (a MIDI thread) joins the ring.
- `freeVoice` steals under the cap and reports the stolen id. Fine.

## Findings

### Fix now

**F1 (lens B). The timestamp is discarded at the edge.**
`MidiInput.Receiver.onSend(msg, offset, count, timestamp)` calls
`wire.feed(msg, offset, count)` and posts the events to the main thread
(`MidiInput.kt`, the `Receiver` inner class). The `timestamp` argument is
the Android MIDI service's `CLOCK_MONOTONIC` nanoseconds for that delivery,
the same domain Oboe's `getTimestamp` and `MotionEvent` use. Once it is
dropped, every note's "when" becomes "when the main thread ran the posted
block", which is a display frame's worth of jitter (up to 16 ms at 60 Hz,
more under load) before the note even reaches the engine. A Launchpad will
feel like it is played through a sieve; an MPC sync has nothing to lock to.
*Try:* carry it. `MidiWire.feed` takes the delivery's timestamp and stamps
each event it completes; `MidiWire.Event` carries `timestampNanos`.

**F2 (lens B). Every command lands on a block boundary.**
`PadCommand` (`PadEngine.h`) has no frame field, and the callback applies
every command it finds at the top of the block, before `render`. At the
house setting (two bursts; a 96 frame burst at 48 kHz is 2 ms, a 240 frame
burst is 5 ms) that quantises every note, from a finger or a cable, to the
block grid. Sample accurate placement is impossible however good the
timestamp is. *Try:* `PadCommand.frameOffset`, honoured mid-block: the
callback renders up to the offset, applies, renders the rest.

### Try next

**T1 (lens B).** With F1 and F2 done, the engine still has no way to turn a
nanosecond into a frame. That needs an absolute frame counter and a smoothed
`ns -> frame` fit sampled from `AudioStream::getTimestamp` off the audio
thread (Oboe's pre-R rule; minSdk 29 is Q). Bench S1 in `docs/BENCH.md` asks
whether the hardware position tells the truth; this is where that answer is
spent.

**T2 (lens A).** `MidiInput` opens output ports only. No `MidiInputPort` is
ever opened, so nothing can light a Launchpad or send a clock tick. The
MIDI-out thread and its ring are a separate, parallel piece of work.

**T3 (lens B).** `BeatPlayer` converts wall time to steps at display rate
with a 100 ms clamp. It is the GROOVE and BEAT clock today and cannot be the
sync foundation; `docs/MIDI_SYNC.md` says why and this review agrees.

### Big idea

**B1 (roadmap P0.5).** A `MidiDeviceService` publishing "SnipSnap In" and
"SnipSnap Out" lets other apps play the kit and be played by it. It depends
on T2 for the out side and on nothing for the in side.

### Disclosed, not fixed

`docs/MIDI_SYNC.md` already records the four clocks and that no
hardware-referenced transport exists among them. `MidiWire` drops pitch
bend, aftertouch and program change by design, with the lengths honoured;
that is the documented contract, not a defect, until expression work needs
them.

## What has to change first, in order

Each step is provable where it lands. Steps 1 and 2 need no phone.

1. **Keep the timestamp.** `MidiInput.Receiver.onSend` passes `timestamp` to
   `MidiWire.feed`, and every `MidiWire.Event` carries `timestampNanos`.
   Proof: `MidiWireTest` round-trips a timestamp, including through a message
   split across deliveries (the delivery that completes the message stamps
   it) and across running status. Lands in `MidiInput.kt`, `MidiWire.kt`.
2. **A frame offset on the command.** `PadCommand.frameOffset`, honoured
   mid-block by `PadEngine::onAudioReady` and `render`, with a command past
   the block's end carried into the next block. Proof: a host test in
   `app/src/main/cpp/test` places a note at frame 37 of a 128 frame block and
   reads silence before it and the sample from it. Lands in
   `PadEngine.{h,cpp}`. The JNI entry points keep `frameOffset` at 0 until
   step 3 can compute one.
3. **The ns-to-frame mapping.** Absolute frame counter in the engine,
   `getTimestamp` sampled off the audio thread, a smoothed fit published
   atomically, and the UI side turning an event's `timestampNanos` into a
   `frameOffset` with a "steady" (one buffer late, jitter free) and a
   "fastest" (frame 0) mode. Depends on bench S1.
4. **MIDI output.** `MidiInputPort`s opened, one MIDI-out thread fed by a
   ring, `send` with timestamps. Proof: a loopback between two ports on a
   device.
5. **A virtual port** through `MidiDeviceService`.
6. **A diagnostics screen**: endpoints, a live monitor with arrival deltas
   from step 1, `latencyMillis`, a copyable report.
7. **BLE**: scan, `openBluetoothDevice`, permissions, a `connectedDevice`
   foreground service, reconnection with backoff. A separate spec.
8. **A Launchpad profile** (step 4 first) and **clock out** (steps 3 and 4,
   bench S1 and S2 first). Clock in as a follower waits on S3 and S4.

## What I measured, what I only read

- **Verified** (read, commit `b429ec2`): `MidiInput.kt` drops `timestamp`;
  `PadCommand` has no frame field; `onAudioReady` drains at the top;
  `SpscRing` drops on full; `openStereoFloatOutput` sets two bursts;
  `BeatPlayer` clamps at `STEP_MAX_NANOS = 100_000_000L`.
- **Measured on device**: nothing. No phone in this session.
- **Cited**: Oboe's rule that `getTimestamp` is not callable from the
  data callback before Android R, as recorded in the skill's
  `references/timing-and-scheduling.md` from the Oboe documentation; the
  app builds against Oboe 1.9.0.
- **Proposed**: the "steady" and "fastest" modes, one buffer of delay as the
  steady default for BLE and USB.
- **Untestable here**: the output-side delay (bench S2), whether the
  hardware position lies on the device in hand (S1), drift between the
  app's two output streams (S3).
