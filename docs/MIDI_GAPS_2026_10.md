# The MIDI work still to do, read at `84471534a`

A gap scan across the MIDI roadmap, read against the tree on 2026-10-23 (`84471534a`,
"Merge pull request #619"). It follows [`MIDI_REVIEW_2026_10.md`](MIDI_REVIEW_2026_10.md),
which read the same path at `b429ec2` and ordered the first eight steps, and
[`MIDI_SYNC.md`](MIDI_SYNC.md), which is the design record for the clock. Everything in the
first two sections was found by grep at the named commit. Nothing here was measured on a
phone.

## What moved since the review

Fifty-five commits separate `b429ec2` from `84471534a`. One of them is MIDI work:
`03951c3ce` landed the review's steps 1 and 2.

- **The timestamp is kept.** `MidiInput.Receiver.onSend` passes the delivery's stamp into
  `MidiWire.feed`, and every `MidiWire.Event` carries `timestampNanos`. `MidiWireTest`
  pins it in three cases.
- **A pad command lands on a frame.** `PadCommand.frameOffset` exists and
  `PadEngine::onAudioReady` renders the block in pieces, applying each command on its frame
  and carrying what is still due into the next block. `engine_tests.cpp` places a note at
  frame 37 of a 128-frame block.
- **Nothing reads the stamp yet.** `MidiKeys` and `MidiPads` hand the note on without it,
  and `jni.cpp` still sends `frameOffset = 0`. The two halves are built and not joined.

Two specs were written in the same week and neither is implemented: the Bluetooth keyboard
spec ([`MIDI_BLE_SPEC_2026_10.md`](MIDI_BLE_SPEC_2026_10.md); no `BleMidiLink`, no Bluetooth
permission in the manifest) and the review's own step table from step 3 on.

Still true at `84471534a`: no `MidiInputPort` is opened anywhere in `app/src/main`; no
`MidiDeviceService`; no `getTimestamp` call and no frame counter under `app/src/main/cpp`;
real-time bytes are stepped over at `MidiWire.kt:119`; the one foreground service is typed
`microphone|mediaProjection`; there is no LICENSE file.

## The open work, in the order it should land

Steps 1 to 6 need no phone. Each row names its proof so an implementing session knows when
it is done. The gate column names what has to be true first; S1 to S4 are
[`BENCH.md`](BENCH.md)'s clock rows.

| # | Work | Lands | Proof | Gate |
|---|---|---|---|---|
| 1 | **Real-time events.** `MidiWire` emits `Clock`, `Start`, `Stop`, `Continue` with stamps instead of dropping `0xF8` to `0xFC`. Hostile fixture first: a flood of `0xF8` interleaved mid-message must not allocate per byte or stall | `MidiWire.kt`, `MidiWireTest` | `:kit:test` | none |
| 2 | **Absolute frame counter** in `PadEngine`, incremented per callback, read atomically off the audio thread. The `getTimestamp` fit that maps nanoseconds to frames is designed now and held | `PadEngine.{h,cpp}` | engine test with a synthetic clock | counter: none; fit: **S1** |
| 3 | **Join the halves.** `NativePads.noteOn` and `jni.cpp` take a `frameOffset`; `MidiPads` and `MidiKeys` carry `timestampNanos` to the screen's `hit`. Two placements: a fixed one-buffer delay (the default, steady latency) or frame 0 (lowest latency) | `NativePads.kt`, `jni.cpp`, `PadEngine.kt`, `:shell` | JVM tests on the offset arithmetic; engine test end to end | step 2's fit, so **S1** |
| 4 | **MIDI output.** Open each device's input ports, one MIDI-out thread fed by a ring, `send` with timestamps, never from the audio callback | a `MidiOutput.kt` beside `MidiInput` | a loopback cable; the monitor shows what left | none |
| 5 | **Device fingerprint** `(transport, vendorId, productId, port counts, name hash)` on `Opened` and in `onDevicesChanged`; never the display name alone, never a Bluetooth address | `MidiInput.kt`, a pure class in `:shell` | the same key before and after USB permission | none |
| 6 | **Stop-drop guard.** `SpscRing` drops on full and a dropped `Stop` is a stuck pad. A refused stop is kept and retried from the screen-rate drain | `PadEngine.kt`, `NativePads.kt`, `jni.cpp` | a 64-pad roll bench row | none |
| 7 | **Diagnostics screen.** Endpoints with transport and protocol, a live monitor with arrival deltas, `latencyMillis`, burst size, a copyable report | `:app`, a model in `:shell` | the report pastes into an issue | step 5 |
| 8 | **Bluetooth keyboard** per the existing spec: `BleMidiLink`, a `connectedDevice` foreground service, the scanner sheet, reconnection with backoff | per `MIDI_BLE_SPEC_2026_10.md` | its D1 to D9 rows on the Pixel | none |
| 9 | **Virtual port** "SnipSnap In" and "SnipSnap Out" through `MidiDeviceService` | a new service, manifest, `res/xml` | another app opens it and plays a kit | step 4 |
| 10 | **Launchpad profile** as JSON, Programmer-mode SysEx, an LED mirror of the screen grid | `:shell` profile model, `assets/controllers/` | the device lights as the screen does | steps 4, 5, 6 |
| 11 | **Pointer audit** of `SurfaceScreen` (two `pointerInput`s, no `awaitFirstDown`) and gesture-exclusion rects on every pad surface. `KitScreen`, `PadSheetScreen` and `KeysScreen` already trigger on first down | `app/.../ui/` | Compose suites | none |
| 12 | **Clock out, master.** Ticks computed from the frame counter, sent with future timestamps through step 4 | `:kit` scheduling, `:app` send | an MPC follows for eight bars | steps 2, 4, **S1, S2** |
| 13 | **One transport.** ORBIT's render cursor and BEAT's display clock migrate onto the frame mapping | `OrbitEngine.kt`, `BeatPlayer.kt` | `BeatClockTest` pinned; a drift row between the two streams | **S1, S3** |
| 14 | **Clock in, follower.** A PLL in `:kit` with jitter and stall fixtures, holdover as its own mechanism | `:kit`, `:app` | fixtures; **S3, S4** on hardware | step 13 |

Beyond these sit the expression and sequencing items (pitch bend and aftertouch in the
parser, MPE, isomorphic grids, per-step probability and conditions, sensors, Scala), and
none of them is blocked except by the rows above.

## The gates that are not code

S1 to S4 are open and the bench is paused. S1 (does the HAL's playback position tell the
truth) gates the nanoseconds-to-frames fit, which gates every clock item. Until S1 is run on
the Pixel, the honest ceiling is step 11; step 12 onward is design only.

One product question decides how long the clock road is. "SnipSnap drives the MPC" is step
12 on step 2, two bench rows, and is complete on its own. "SnipSnap plays along with the
MPC" adds steps 13 and 14 and two more bench rows. `MIDI_SYNC.md` recommends master first.
This document assumes master unless the owner says the real need is playing along with an
MPC that is already running.

## Housekeeping

- `docs/README.md` did not link `MIDI_SYNC.md`, `MIDI_REVIEW_2026_10.md` or
  `MIDI_BLE_SPEC_2026_10.md` at `84471534a`. The batch below adds the paragraph.
- There is no LICENSE file. Ableton Link is GPLv2+ or paid and cannot be bundled without
  that decision. Nothing above needs it yet. Said once.

## Why it is ordered this way

- **Real-time events come first** because every clock item is nothing but stamped `0xF8`
  bytes, and the parser's contract (one message behind at most, no per-byte allocation) has
  to survive twenty-four of them a beat before anything is built on it. The fixture is the
  risky part, not the four event types.
- **The frame counter lands before the fit** because counting is arithmetic and the fit is
  a measurement the phone has not given. Splitting them ships half the work now with no
  bench dependency.
- **Output before Bluetooth and the virtual port** because the Launchpad, the virtual port
  and clock out all need a send path, and a loopback cable proves it without new hardware.
- **The Launchpad before the MPC** because every piece of it is provable on the JVM or the
  host harness, and it exercises open, send, cable pull and LEDs while the stakes are a dark
  pad rather than a drifted recording.
- **The stop-drop guard is in the first batch** because a controller roll is the first input
  that can fill the ring, and a dropped `Stop` is the one failure a player cannot undo
  without PANIC.

## As built: batch A (2026-10-23)

Filled in by the session that lands steps 1, 2 (counter), 5 and 6. Until then this section
is the plan: see `superpowers/plans/2026-10-23-midi-batch-a.md`.

## Ledger

- **Verified** at `84471534a`: everything in the first two sections, by grep.
- **Measured on device**: nothing.
- **Cited**: the review's and the Bluetooth spec's own citations, read 2026-10-22. The 96 to
  240 frame burst range in the review is general knowledge, not a measurement.
- **Proposed**: the step order, the two-placement default, the fingerprint shape, the guard
  design.
- **Untestable here**: S1 to S4, the Pixel's burst size and output delay, a Launchpad's port
  counts.
