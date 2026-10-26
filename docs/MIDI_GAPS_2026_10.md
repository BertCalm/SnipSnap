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
| ~~1~~ | ~~**Real-time events.** `MidiWire` emits `Clock`, `Start`, `Stop`, `Continue` with stamps instead of dropping `0xF8` to `0xFC`. Hostile fixture first: a flood of `0xF8` interleaved mid-message must not allocate per byte or stall~~ Done in `f95cd75c9` | `MidiWire.kt`, `MidiWireTest` | `:kit:test` | none |
| 2 | **Absolute frame counter** in `PadEngine`, incremented per callback, read atomically off the audio thread. The `getTimestamp` fit that maps nanoseconds to frames is designed now and held | `PadEngine.{h,cpp}` | engine test with a synthetic clock | counter: none; fit: **S1**. Counter done in `aa2583bf9`; the fit is held |
| 3 | **Join the halves.** `NativePads.noteOn` and `jni.cpp` take a `frameOffset`; `MidiPads` and `MidiKeys` carry `timestampNanos` to the screen's `hit`. Two placements: a fixed one-buffer delay (the default, steady latency) or frame 0 (lowest latency) | `NativePads.kt`, `jni.cpp`, `PadEngine.kt`, `:shell` | JVM tests on the offset arithmetic; engine test end to end | step 2's fit, so **S1** |
| ~~4~~ | ~~**MIDI output.** Open each device's input ports, one MIDI-out thread fed by a ring, `send` with timestamps, never from the audio callback~~ Done in `6967038ec` to `9c4a7cda4` (`MidiOut`; the loopback row is not run) | `MidiOut.kt` beside `MidiInput` (`MidiOut` opens the input ports for the process; `MidiInput` opens output ports only) | a loopback cable; the monitor shows what left | none |
| ~~5~~ | ~~**Device fingerprint** `(transport, vendorId, productId, port counts, name hash)` on `Opened` and in `onDevicesChanged`; never the display name alone, never a Bluetooth address | `MidiInput.kt`, a pure class in `:shell` | the same key before and after USB permission~~ Done in `bd95b1baa` | none |
| ~~6~~ | ~~**Stop-drop guard.** `SpscRing` drops on full and a dropped `Stop` is a stuck pad. A refused stop is kept and retried from the screen-rate drain | `PadEngine.kt`, `NativePads.kt`, `jni.cpp` | a 64-pad roll bench row~~ Done in `27d059a65` and `e7cfc0072`; the bench row is not run | none |
| 7 | **Diagnostics screen.** Endpoints with transport and protocol, a live monitor with arrival deltas, `latencyMillis`, burst size, a copyable report. Landed: `MidiScreen` behind the gear, then MIDI, `MidiMonitor` and `MidiReport` in `:shell` (`MidiMonitorTest`, `MidiReportTest`), `MidiInput(tapRaw = true)` with `Listener.onRaw` and `onRefused`, `MidiInput.endpoints` with port names. Not in it: `latencyMillis` (the screen runs no engine) and burst size. Compiled by `android-build` only; not yet run on a phone | `:app`, a model in `:shell` | the report pastes into an issue | step 5 |
| 8 | **Bluetooth keyboard** per the existing spec: `BleMidiLink`, a `connectedDevice` foreground service, the scanner sheet, reconnection with backoff | per `MIDI_BLE_SPEC_2026_10.md` | its D1 to D9 rows on the Pixel | none |
| ~~9~~ | ~~**Virtual port** "SnipSnap In" and "SnipSnap Out" through `MidiDeviceService`~~ Done in `3cbc0f4ce`; another app opening it is not run | a new service, manifest, `res/xml` | another app opens it and plays a kit | step 4 |
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

Landed in `f95cd75c9` to `e7cfc0072`, one commit per task plus one fix: steps 1, 2
(counter), 5 and 6 of the table above.

- **Step 1, real-time events: `f95cd75c9`.** `MidiWire.Event` is split into `Event.Channel`
  (`NoteOn`, `NoteOff`, `Control`, each with `channel`) and `Event.RealTime` (`Clock`, `Start`,
  `Continue`, `Stop`, carrying the stamp only). `0xF8`, `0xFA`, `0xFB` and `0xFC` are emitted;
  `0xF9`, `0xFD`, `0xFE` and `0xFF` are still dropped. `MidiKeys` and `MidiPads` ignore
  `RealTime`. The fixture script `RefWire` in `wire_fixtures.py` was extended to match.
  Proof: `MidiWireTest`, 24 cases, including a flood of 1000 clocks under running status, a
  clock between a status byte and its data, and a clock inside a SysEx.
- **Step 2, frame counter: `aa2583bf9`.** `std::atomic<int64_t> framesRendered_` in
  `PadEngine`, increased by `numFrames` in every callback and never reset;
  `framesRendered()` on the C++ side, `NativePads.framesRendered(handle)` through JNI, and
  `PadEngine.framesRendered()` in Kotlin. Proof: `engine_tests.cpp` (0 before any callback;
  128 then 64 reads 192; the count survives stop and start) and one case in `jni_tests.cpp`.
- **Step 5, device fingerprint: `bd95b1baa`.** `shell/.../MidiFingerprint.kt` holds
  `MidiFingerprint`, `Transport`, `key`, `of(...)` and `MidiDeviceId`. `MidiInput.Listener`
  now announces `onDevicesChanged(List<MidiDeviceId>)`; `MidiInput.devices` and `Opened.id`
  carry the same. `KeysScreen` and `KitScreen` print `.name` as before. Nothing reads
  `PROPERTY_BLUETOOTH_DEVICE`, so no Bluetooth address enters a key. Proof:
  `MidiFingerprintTest`, 9 cases after the final-review fixes, and `:app:compileDebugKotlin`.
- **Step 6, stop-drop guard: `27d059a65`, with the fix `e7cfc0072`.** `jni.cpp` `stopVoice`
  and `allOff` return `jboolean`; `PadEngine.stop` and `allOff` return `Boolean`. A refused
  stop is kept in an `ArrayDeque`; a refused all-off is kept and supersedes the kept stops.
  `drainEnded()` flushes first: the all-off, then the stops in order, stopping at the first
  refusal. A successful all-off clears everything kept. One `Log.w` the first time anything is
  kept. Proof: a full `SpscRing` refusing a push is pinned by `ring_keeps_order_and_drops_when_full`; the Kotlin retry above it is proved by compile and reading.
- **Final-review fixes: `758e4a659`.** F1: `PadEngine.hit` and `hitLayers` call `flushPending()`
  right after the `isUp()` guard, so a kept all-off is retried before any later hit is queued
  and nothing played after PANIC is cut by it. F3: `jni_pads_stop_voice_reports_a_full_ring_and_recovers`
  pushes 255 stops (all `JNI_TRUE`), sees the 256th refused, and sees one callback make room.
  F6: `MidiInput.Receiver.onSend` drops real-time events before the main-thread post, so
  clock bytes no longer cross threads for nothing. Also: a static_assert that the frame
  counter is lock-free, blank fingerprint strings read as absent, and the `MidiWire.kt`
  KDoc reworded and rewrapped.

Disclosed, not done:

- **The fit waits on S1.** Nothing here maps nanoseconds to frames; the counter is the half
  that needs no phone.
- **`frameOffset` is still 0 from Kotlin.** `jni.cpp` sends no offset, so a stamp cannot yet
  place a note. Step 3 of the table is not in this batch.
- **The stop guard has no JVM test.** `:app` has no host test source set.
- **KEYS does not keep a refused stop.** `InstrumentPlayer.kt` calls `NativePads` directly:
  `:90` (steal) and `:109` (noteOff) ignore the new `stopVoice` result, and `:117` ignores
  the `allOff` result. KEYS runs at most eight voices (`InstrumentEngine.MAX_VOICES`), so it
  cannot fill a 255-slot ring and a refusal there is not reachable today.
- **SPLIT and ORBIT never drain.** `ui/SplitScreen.kt` and `ui/OrbitScreen.kt` construct a
  `PadEngine` directly and never call `drainEnded`, so a kept stop or all-off there waits
  until the engine closes. That is acceptable today: three SPLIT voices or one ORBIT
  audition cannot fill a 255-slot ring. Since the final-review fix, any later `hit` or
  `hitLayers` flushes it first.
- **Departure from the binding review, needs owner sign-off.** The review's T2 named a
  `portIndex` in the fingerprint. This batch keys per device with port counts instead:
  nothing on an event yet says which port of a two-port controller it came from.

Deferred minors, one line each: the fixture script's note tuples carry no stamp.

Heard: nothing; this session had no phone.

## Ledger

- **Verified** at `84471534a`: everything in the first two sections, by grep.
- **Measured on device**: nothing.
- **Cited**: the review's and the Bluetooth spec's own citations, read 2026-10-22. The 96 to
  240 frame burst range in the review is general knowledge, not a measurement.
- **Proposed**: the step order, the two-placement default, the fingerprint shape, the guard
  design.
- **Untestable here**: S1 to S4, the Pixel's burst size and output delay, a Launchpad's port
  counts.
- **Compile checks**: the per-task proofs ran `-q` with `tail`, which hides warnings; the
  final review's non-quiet `:kit`/`:shell` compile and the fix wave's non-quiet
  `:app:compileDebugKotlin` showed three pre-existing warnings in untouched files and none in
  this branch's files. The native build was checked only through `tail`.
- **Measured, and not this batch's**: the full JVM suite on the author's macOS arm64 machine
  at the end of the batch ran 3127 tests with 5 failures, all golden SHA-256 audio fixtures
  in `OrigamiTest` (2), `OspreyLegacyTest`, `OspreyV3Test` and `TidalTest`. The same five
  fail on the untouched base `84471534a` on the same machine, and CI's x86 Linux run of that
  base (tests run 39991096898, 2026-10-23) passed them. The batch changed no file under
  `synth`, `audio`, `json`, `xpm` or `mpc3`, and those engines import nothing it touched.
  31 of the 35 golden-hash synth tests pass on arm64; these three engines' float paths do
  not. Disclosed, not fixed: the fixtures are platform-fragile, and CI is their authority.

## As built: batch B (2026-10-23)

Landed in `6967038ec` to `9c4a7cda4`: steps 4 and 9 of the table above (MIDI output and the
published virtual port). The per-commit record, the proof for each and what was left undone
are in [`MIDI_OUT_SPEC_2026_10.md`](MIDI_OUT_SPEC_2026_10.md) section 13. Nothing was heard on
a phone.

## As built: batch C (2026-10-26)

Landed in `7bb2f82e5` to `924979517`: bend and pressure on the wire, the pad map per
controller, and a CLOCK CHECK on the MIDI screen that answers bench S1. The record, the proof
and what was left undone are in [`MIDI_CLOCK_PROBE_SPEC_2026_10.md`](MIDI_CLOCK_PROBE_SPEC_2026_10.md)
section 6 and [`MIDI_CONTROLLER_PLAN_2026_10.md`](MIDI_CONTROLLER_PLAN_2026_10.md) steps 2 and 3.
Nothing was measured on a phone.
