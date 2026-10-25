# MIDI out and a published port: SnipSnap plays other machines

Engineering spec for an implementing session. Job: WIRE. Steps 4 and 9 of
[`MIDI_GAPS_2026_10.md`](MIDI_GAPS_2026_10.md)'s table (the review's step 4): nothing in the
repo can send a byte, and other apps cannot see SnipSnap as a MIDI device. No CLOCK work is
in scope; the timing rule that applies is that every byte leaves with the stamp its cause
carried, in the one clock domain. The nanoseconds-to-frames fit, clock out and any follower
still wait on bench S1 to S3.

Repo read at `61757cfb6` (2026-10-23, the head of `claude/midi-batch-a`, PR #649), which
this builds on: it needs `MidiFingerprint`, `MidiDeviceId` and `MidiWire.Event.RealTime`
from that branch. Every name below was found by grep unless marked **(new)**.

## 1. What exists, and what the gap is

- `app/src/main/kotlin/com/snipsnap/app/MidiInput.kt` opens every device `MidiManager`
  lists, opens each device's **output** ports (what the device sends) and connects a
  `MidiWire` per port. It never calls `openInputPort`. It is screen-scoped: `KeysScreen.kt:116`
  and `KitScreen.kt:384` each construct one and open and close it with the lifecycle. Each
  `Opened` carries a `MidiDeviceId(name, fingerprint)`.
- `shell/.../MidiPads.kt` maps a note to a pad slot under two maps (`MPC`, the exported kit's
  own notes from `PadNoteMap.DEFAULT`; `IN_ORDER`, C1 on A01). It has no inverse.
- `kit/.../MidiGroove.kt` writes Standard MIDI Files with `DRUM_CHANNEL = 9` (channel 10) and
  raw status bytes `0x90 | channel`. Nothing shares an encoder with it.
- Notes originate in four places: PLAY's pad grid (`KitScreen.playHeld`/`release`, also fed
  by MIDI in through `MidiPads.handle` at `KitScreen.kt:382`), the fullscreen grid
  (`KitScreen.kt:1042`, same two functions), the sequencer (`BeatPlayer.hit`, line 362, no
  release; one-shots), and KEYS (`InstrumentPlayer.noteOn`/`noteOff`, reached from touch at
  `KeysScreen.kt:218-219` and from MIDI at `KeysScreen.kt:103-104`).
- The manifest declares `android.software.midi` optional, two services (`MicSessionService`,
  `SnipTileService`) and no `MidiDeviceService`. `res/xml/` has no `midi_device_info.xml`.
- SETUP is `PropertiesScreen.kt`; a setting is a `GroupBox` with ON/OFF bevels (the HAPTICS
  block at lines 214-247 is the pattern), state lives in `App.kt` beside `PREF_HAPTICS` in the
  `PREFS = "tapeos"` preferences, and every string the UI says is a `Copy` constant in
  `shell/.../Personality.kt`.
- `SnipSnapApplication.onCreate` installs process-wide singletons (`AudioFocus.install(this)`).

The gap is two things: no code holds a `MidiInputPort` (the device-side name for where you
send), and no `MidiDeviceService` publishes SnipSnap as a device.

## 2. What goes out, and what never does

**Goes out**, as MIDI 1.0 bytes, stamped with the cause's own `System.nanoTime()`:

| Origin | Message | Channel | Note | Velocity | Note-off |
|---|---|---|---|---|---|
| PLAY pad, touched (grid or fullscreen) | note on | 10 (`MidiGroove.DRUM_CHANNEL`) | `MidiPads.noteForSlot(slot, map)` under PLAY's current map **(new)** | `PadHit.midiVelocity(v)` | on `release(slot)` |
| Sequencer hit (`BeatPlayer.hit`) | note on | 10 | same, under `Map.MPC` | same | stamped one step after the on; the system delivers it then |
| KEYS key, touched | note on | 1 | the key's own note | same | on touch release |

**Never goes out:** anything that arrived over MIDI. A note that `MidiPads.handle` or
`MidiKeys.handle` delivered is played, not forwarded; there is no thru. A keyboard would
otherwise hear its own notes back, and a controller's LEDs would light from the notes meant
for its pads. Clock, program change and controllers are out of scope for this spec.

Why the MPC map: an MPC receiving "SnipSnap Out" or a USB cable plays the same pad SnipSnap
played, because `PadNoteMap.DEFAULT` is the exported kit's own map. A round trip is
`noteForSlot(slotForNote(n)) == n` and the test says so.

Proposed, not measured: the sequencer gate of one step. `MidiGroove` writes each groove
note's own length; a live hit has none, and one sixteenth is what a drum program ignores and
a synth hears as a short note. A later setting can lengthen it.

## 3. Endpoints

An endpoint is somewhere bytes can go. Two kinds in this spec:

1. **A device's input ports.** `MidiOut.install` registers its own `MidiManager.DeviceCallback`
   and, for every device but SnipSnap's own, opens `device.openInputPort(i)` for each
   `i in 0 until info.inputPortCount` (the naming trap: you send into the device's *input*;
   write the comment every time). `MidiOut` is the opener, for the life of the process, so the
   ports exist whichever screen is up; `MidiInput` (screen-scoped) opens output ports only.
   The two hold separate `MidiDevice` handles on one device, which `MidiManager` allows (ports
   are exclusive, devices are not), and never open the same port.
2. **The published port "SnipSnap Out".** `SnipSnapMidiService` **(new)** extends
   `MidiDeviceService`. Its `midi_device_info.xml` **(new)** declares one input port
   "SnipSnap In" and one output port "SnipSnap Out", manufacturer and product "SnipSnap".
   The system starts the service when any app opens the device; while it lives, its
   `outputPortReceivers[0]` is an endpoint, registered on `onCreate` and removed on
   `onDestroy`.

Identity for a hardware endpoint is the device's `MidiFingerprint` plus the port index; a
persisted routing table, when one exists, keys on `"${fingerprint.key}/in$index"`. The live
destination id also carries `MidiDeviceInfo.id` (`"${fingerprint.key}/${info.id}/in$index"`),
because two identical units share a fingerprint. Nothing is
persisted in this spec.

**SnipSnap must not open itself.** Its own virtual device appears in `MidiManager`'s list
like any other. `MidiInput.openDevice` skips any `MidiDeviceInfo` whose
`properties.getParcelable<ServiceInfo>(PROPERTY_SERVICE_INFO)?.packageName` equals the app's
package; otherwise PLAY would read "SnipSnap Out" and hear its own notes, and KEYS would send
into "SnipSnap In" and play itself.

## 4. The published port's other half: "SnipSnap In"

What another app sends into "SnipSnap In" arrives at the receiver `SnipSnapMidiService`
returns from `onGetInputPortReceivers()`, on a binder thread, as a byte stream. It is an
endpoint of the other direction, and it joins the existing input path rather than growing a
new one: the receiver feeds a `MidiWire` and posts the stamped events to a process-wide
`VirtualMidi` **(new)** object's listeners. `MidiInput.open` subscribes to `VirtualMidi` and
delivers those events to its own `Listener.onEvent` exactly as it delivers a USB device's,
and `MidiInput.devices` lists it as `MidiDeviceId("SNIPSNAP IN", fingerprint)` with
`Transport.VIRTUAL`, vendor and product 0, one input port, one output port, and the name
hash of "SnipSnap"/"SnipSnap In", **only while another app holds the port open**: the
service's `onCreate`/`onDestroy` are the signal, since the system binds it on the first
open and unbinds after the last close. `MidiInput.onDeviceRemoved` fires on unbind, so a
held note from another app is let go the way a pulled cable's is.

## 5. The sender: `MidiOut` (new), process-wide

`app/src/main/kotlin/com/snipsnap/app/MidiOut.kt`, an `object` installed from
`SnipSnapApplication.onCreate` beside `AudioFocus.install(this)`.

- **One thread.** A `HandlerThread("midi-out")`. `MidiInputPort.send` may block; it never
  runs on the main thread or the audio thread. Callers post pre-encoded bytes plus a stamp.
- **The encoder is pure and lives in `:kit`.** `MidiBytes` **(new)**,
  `kit/src/main/kotlin/com/snipsnap/kit/MidiBytes.kt`: `noteOn(channel, note, velocity127)`,
  `noteOff(channel, note)`, `control(channel, controller, value)` returning `ByteArray`,
  arguments clamped to their seven bits and the channel to 0..15. Its tests feed each result
  through `MidiWire.feed` and expect the matching event back: the two halves of the wire
  agree by construction.
- **Destinations** are a `CopyOnWriteArrayList<Destination>` where `Destination(id: String,
  send: (ByteArray, Long) -> Unit)`. `MidiOut` itself adds one per opened input port and
  removes them when the device goes; `SnipSnapMidiService` adds "SnipSnap Out" in `onCreate` and removes it
  in `onDestroy`. A `send` that throws (`IOException` when the port is gone) is caught on the
  out thread, logged once per destination, and the destination is dropped.
- **The switch.** `MidiOut.hardwareEnabled: Boolean`, read from `PREFS` key `"midi.out"`,
  default **true** (`MIDI_OUT_DEFAULT`; the owner's call on 2026-10-25, reversing the
  original false). When false, hardware destinations are skipped and "SnipSnap Out" still
  sends. Rationale for on: a player who plugs in an MPC or a synth expects it to play. The
  cost, named when the default was false, stands: a keyboard that re-sends what it receives
  plays each pad hit back into SnipSnap once (no thru, so never a loop), and the player turns
  it off in SETUP.
- **API**, main thread: `noteOn(channel, note, velocity, timestampNanos)`,
  `noteOff(channel, note, timestampNanos)`, `allNotesOff()` (CC 123 only on channels with a
  note outstanding, used by PANIC, by disposal and by the silence paths so a receiver is never
  left holding a note the app has let go), `drumOnGated(note, velocity, timestampNanos,
  gateNanos)` (the off is a cancellable `postDelayed` on the out thread, flushed by
  `allNotesOff` and by a retrigger of the same note).

## 6. Lifecycle

- A hardware input port opens when `MidiOut`'s own device callback sees a device (at
  `install`, then as devices arrive), and closes when that device is removed; it does not
  depend on any screen. A port that refuses to open (another app holds it;
  `openInputPort` returns null) is logged with the device name and port index and the device
  is still used for what did open.
- "SnipSnap Out" exists while `SnipSnapMidiService` is bound. `MidiOut` holds no reference to
  the service after `onDestroy`.
- The out thread starts on first `install` and lives for the process; it has nothing to do
  when the list is empty.
- No foreground service, no new permission. `BIND_MIDI_DEVICE_SERVICE` is the system's
  permission on the service element, not one the app requests.

## 7. Threading, stated once

| Thread | Does | Never |
|---|---|---|
| main | encodes (one small `ByteArray`), posts to the out thread, registers destinations | sends |
| `midi-out` | `MidiInputPort.send`, catching `IOException` | touches Compose state |
| binder (service receivers) | `MidiWire.feed`, posts events to main | blocks, allocates beyond the event list |
| audio | unchanged | anything from this spec |

The one clock domain holds: a touch's stamp is `MotionEvent.getEventTimeNanos()` where the
screen has it, else `System.nanoTime()` at the call; a sequencer hit's stamp is the
`withFrameNanos` time that fired it; the sequencer's note-off is that plus one step in
nanoseconds, which the system holds and delivers on time.

## 8. SETUP

One `GroupBox` in `PropertiesScreen` after HAPTICS, legend `Copy.SETUP_MIDI_OUT_HEADING`
("MIDI OUT"), ON/OFF bevels in the HAPTICS pattern, summary ON or OFF, and a note line
`Copy.SETUP_MIDI_OUT_NOTE`: "PADS AND KEYS GO OUT TO EVERY PLUGGED-IN DEVICE. OTHER APPS CAN
ALWAYS OPEN SNIPSNAP OUT." State `midiOutOn` lives in `App.kt` beside `hapticsOn`, read from
and written to `PREFS`, and pushed into `MidiOut.hardwareEnabled`.

Below the switch, one line per destination: the device name and `OUT` or `BUSY`, from
`MidiOut.destinations` (names) and `MidiInput`'s refused-port log turned into a word. If
nothing is connected: `Copy.SETUP_MIDI_OUT_NONE` ("NOTHING PLUGGED IN. SNIPSNAP OUT IS
STILL PUBLISHED."). This is the diagnostics screen's first row; the rest of that screen
(a live monitor, latency, a copyable report) lands with the Bluetooth spec, which already
designs its SETUP section.

## 9. Failure in words

| What goes wrong | What the player sees |
|---|---|
| A device's input port is held by another app | SETUP lists the device as BUSY; notes still play locally |
| `send` throws because the cable came out mid-note | the destination is dropped; `MidiInput.onDeviceRemoved` already silences; nothing else |
| The phone has no `android.software.midi` | `MidiInput.supported` is false; the SETUP block shows the HAPTICS-style note "THIS PHONE HAS NO MIDI" (`Copy.SETUP_MIDI_OUT_UNSUPPORTED`) and no switch |
| Another app sends garbage into "SnipSnap In" | `MidiWire`'s contract: a status byte resynchronises; nothing allocates per byte beyond one event |

## 10. How we would know it works

JVM, in CI:

- `MidiBytesTest`: every encoder output fed through `MidiWire` returns the matching event;
  out-of-range note, velocity and channel are clamped, never thrown; velocity 0 encodes as a
  note-off status, not a note-on with 0 (so a receiver that does not treat 0x90 v0 as off is
  still right).
- `MidiPadsTest`: `noteForSlot` is the inverse of `slotForNote` for every slot under both
  maps; an unfilled or out-of-range slot is null.
- `:app:compileDebugKotlin` and `:app:processDebugResources` (the service element and the
  `meta-data` resource must parse and link; `processDebugManifest` alone does not resolve
  `@xml/midi_device_info`).

On a phone (bench rows this spec adds; none run here):

- **M1** Loopback: a USB cable from the phone to an interface and back, or two apps on one
  phone. PLAY a pad; the receiving side shows note 36 on channel 10 for A02, velocity from
  the pad's position.
- **M2** Another app opens "SnipSnap Out" and plays a kit; a third app plays into "SnipSnap
  In" and PLAY lights the pad.
- **M3** With MIDI OUT off in SETUP, a USB keyboard hears nothing; on, it hears the pads.
- **M4** Pull the cable mid-hold on KEYS: the receiver's note ends (`allNotesOff` on removal).
- **M5** A receiver that echoes CC 123, or a router joining "SnipSnap Out" to "SnipSnap In":
  PANIC, or an incoming All Notes Off, produces exactly one extra CC 123 and then silence.
- **M6** A synth app on "SnipSnap Out" hears a window-grid tap as a one-step note, not a blip,
  and a quick retrigger of the same pad is not cut short by the first hit's off.

## 11. Ledger

- **Verified** (grep, `61757cfb6`): everything in section 1; `DRUM_CHANNEL = 9` in
  `MidiGroove.kt:26`; no `openInputPort`, `MidiDeviceService`, `midi_device_info` anywhere;
  the four note origins and their line numbers; the HAPTICS pattern and `PREF_HAPTICS`.
- **Measured on device**: nothing.
- **Cited**: `MidiInputPort.send(buffer, offset, count, timestamp)` delivers at the stamp
  (Android reference, via the midiwright `android-midi-api.md` §1, read 2026-10-22);
  `MidiDeviceService` manifest shape (same, §3); the virtual device lifecycle, bound on first
  open and unbound after last close (same, §3, from AOSP `MidiService`).
- **Proposed**: the one-step sequencer gate; channels 10 and 1; default-on hardware output
  (owner's decision, 2026-10-25);
  the destination id shape; every `Copy` line.
- **Untestable here**: whether a given keyboard re-sends received notes (the reason for the
  default); `openInputPort` refusing for a busy port; how long the system holds a
  future-stamped send without drift (M1 measures it).
- **License**, once: no LICENSE file in the repo; nothing here vendors code.

## 12. Why it is built this way

1. **One sender thread, pre-encoded bytes.** `send` can block. A blocked main thread is a
   hitch in every animation; a blocked audio thread is a dropout. One thread with a queue is
   cheap, and the alternative is a bug that shows up only with one brand of interface.
2. **The encoder is pure and round-trips through the parser.** `MidiBytes` and `MidiWire`
   are two halves of one wire; testing one through the other means a disagreement about
   running status, velocity 0 or channel bits cannot exist in the code without a red test.
3. **The MPC map goes out.** SnipSnap exports kits the MPC plays on `PadNoteMap.DEFAULT`'s
   notes. Sending those same notes means the phone and the hardware agree about which pad is
   which with no table to maintain.
4. **No thru, and SnipSnap never opens itself.** The two feedback loops a MIDI app can build
   by accident are a controller hearing its own notes and an app reading its own output
   port. Both are refused by construction, not by a setting. A third loop, an All Notes Off
   answered by another All Notes Off, is refused the same way: `MidiOut` remembers the notes
   it started (`outstanding`) and sends CC 123 only on channels that have one, so an echo
   finds nothing and dies after one round. Every note SnipSnap started is still ended by
   PANIC, a cable pull, a local silence and a screen's disposal.
5. **The risky decision** is the hardware output default. It shipped off in the first draft
   and is on now, by the owner's call (2026-10-25): an MPC plugged in plays at once. The cost
   moves to the keyboard owner whose keyboard echoes what it receives, who hears each pad
   twice until SETUP turns it off. `MIDI_OUT_DEFAULT` is the one constant either way.

## 13. As built (2026-10-23)

Landed in `6967038ec` to `9c4a7cda4`, eleven commits on top of `31b436701`. Test counts are
`grep -c @Test` on the files named; this recording task ran no Gradle.

- **The encoder: `6967038ec`.** `kit/.../MidiBytes.kt` writes note on, note off, control change
  and All Notes Off as small `ByteArray`s; out-of-range channel, note and velocity are clamped.
  Velocity 0 encodes as a note-off status. Proof: `MidiBytesTest`, 7 cases, each output fed back
  through `MidiWire`.
- **The outgoing pad map, the step length and the words: `02ac5beba`.** `MidiPads.noteForSlot(slot,
  map)` is the inverse of `slotForNote`; it is null outside slots 1 to 128, and null for the
  `IN_ORDER` slots 93 to 128, whose notes would pass 127. `MidiGate.stepNanos(bpm)` is one step
  in nanoseconds. Six `Copy.SETUP_MIDI_OUT_*` lines: heading, note, none, unsupported, `OUT`,
  `BUSY`; the heading and the two port words are listed as labels in `PersonalityTest`. Proof:
  `MidiPadsTest` (9 cases, 3 of them new: the inverse for every pad under both maps, A02 as note
  36 under the MPC map, a slot outside the pads), `MidiGateTest` (2 cases, both new: 125 ms at
  120 bpm, 250 ms at 60 bpm), `PersonalityTest` (26 cases).
- **The sender and the input ports: `25ff062fe`.** `MidiOut` is an object with one
  `HandlerThread("midi-out")`, a destination list, `hardwareEnabled` (default false then, `MIDI_OUT_DEFAULT` = true since 2026-10-25, read once
  from pref `midi.out`) and a `ports` `StateFlow`. `MidiInput` opens each device's input ports as
  well as its output ports, registers a destination per open port and reports a refused port;
  `closeDevice` sends All Notes Off; a device with only an input port is now opened too.
  (Superseded by the final-review fixes below: `MidiOut` is now the input-port opener.)
  `SnipSnapApplication` calls `MidiOut.install`. Proof: `:app:compileDebugKotlin` and reading.
- **Self-exclusion: `cf53ea014`.** `MidiInput` skips the app's own device by `TYPE_VIRTUAL` plus
  the manufacturer and product strings, which are the constants `MIDI_DEVICE_MANUFACTURER` and
  `MIDI_DEVICE_PRODUCT` in `MidiOut.kt`. It replaces a check on a hidden key. Proof: compile and
  reading.
- **BUSY rows, failure, one install: `e82b0468a`.** A BUSY row is cleared when its device is
  released. A failed `send` is caught as `Exception` per destination (not only `IOException`),
  logged once and the destination dropped. `install` does nothing on its second call. Proof:
  compile and reading.
- **The published device: `3cbc0f4ce`.** `SnipSnapMidiService` (a `MidiDeviceService`),
  `VirtualMidi` (the seam between the service and `MidiInput`), `res/xml/midi_device_info.xml`
  with an input port "SnipSnap In" and an output port "SnipSnap Out", and the manifest service
  element. The service's destination is added with `hardware = false`, so it sends whatever the
  switch says. `MidiInput.devices` lists the virtual device while another app holds it.
  Proof: compile and reading. (Correction: Task 4's report shows `:app:processDebugManifest`
  ran and passed; it does not resolve `@xml/midi_device_info`, which `processDebugResources`
  does.)
- **Pads and keys leave: `d1376f3b0`.** The PLAY and fullscreen grids send note on at the
  touch and note off at the release on channel 10, under the pad map the player chose
  (`midiMap`); `BeatPlayer.hit` always uses the MPC map, and with no release to read it sends
  the note-off one `MidiGate.stepNanos` later, stamped ahead (superseded: `drumOnGated`, below).
  A KEYS touch sends on channel 1. `KitScreen` takes a
  `fromMidi` flag, so a note that came in is never sent out. PANIC sends All Notes Off (as
  built then on both channels; since the final-review fixes only where a note is outstanding).
  `MidiInput.open` reports an already-bound virtual device to a screen that opens later. Proof:
  compile and reading.
- **KEYS silence on the wire: `4a418242c`.** `MidiKeys.Voice.allOff` in `KeysScreen` calls
  `MidiOut.allNotesOff()` as well as silencing the local voices. Proof: compile and reading.
- **PLAY's note-off pairs with its note-on: `3fd81b58a`.** `KitScreen` keeps `sentNotes`, slot to
  the note that went out; the off is sent from that record, before the allocator's early return,
  and PANIC clears it. Proof: compile and reading.
- **SETUP: `c48bc2fb4`.** `App.kt` holds `midiOutOn`, reads and writes pref `midi.out` and
  pushes it into `MidiOut.hardwareEnabled`; `PropertiesScreen` gains the MIDI OUT `GroupBox` in
  the HAPTICS pattern, with the destination list from `MidiOut.ports`. Proof: compile and reading.
- **Naming: `9c4a7cda4`.** `MidiOut.drumOn` and `drumOff` wrap the channel-10 sends so the pad
  screens carry no `.noteOn(` of their own and `ConventionTest`'s allocator law passes. Proof:
  compile and reading; the law is the existing test, not run here.

- **Final-review fixes: `ed43e2068`, `57f7f3f87`.** C1: `MidiOut` keeps `outstanding`, a
  main-thread `HashSet<Int>` of `(channel shl 8) or note`; `allNotesOff` sends CC 123 only on
  channels with a note in it, so an echoed All Notes Off dies after one round. C2: `MidiOut.install`
  registers its own device callback and opens hardware input ports for the process lifetime;
  `MidiInput` opens output ports only, and `MidiInput.isOwnDevice` is the one self-exclusion.
  Hardware output therefore exists on every screen, and SETUP's list is reachable from any of
  them. I1: `drumOnGated` schedules a cancellable off on the out thread (`KitScreen` window grid,
  `BeatPlayer.hit`). I2: `KitScreen` and `KeysScreen` call `allNotesOff` on disposal, and leaving
  fullscreen ends every held pad's note. I3: `MidiGroove.DRUM_CHANNEL`, `keysOn`/`keysOff`, one
  `PREF_MIDI_OUT`, `KitPreview.playedBpm` for the gate. I4: the window grid and KEYS touch send
  only when the local sound happened. M1: the window grid sends under `midiMap`. M2: destination
  ids carry `info.id`; a failed send removes its own entry, not whatever has its id. M4: the two
  port names are `Copy.MIDI_PORT_OUT_NAME` and `MIDI_PORT_IN_NAME`. M5: the manifest correction
  above. M6: `MidiOut.ports` is collected in the SETUP call, not at the top of `App`. Proof:
  `:kit:test`, `:shell:test`, `:app:compileDebugKotlin`, `:app:processDebugResources`. Left for
  the ledger: M3 (OUT shown while the switch is off), the gesture loops' `try/finally`, a
  ConventionTest law.

**Disclosed, not done.**

- There is no JVM test for `:app`. The `:app` half rests on the compiler and on reading; the
  `:kit` and `:shell` halves above have tests.
- The sequencer stamps `System.nanoTime()` at the hit, not the frame time, because the frame
  time is not in scope in `BeatPlayer.hit`. The clock work is the place to fix it.
- A hit that makes no local sound sends nothing: the engine refused it, or the stream is down.
  The wire follows the phone's speaker, not the touch.
- Bench rows M1 to M6 are unrun.
- The `tags` attribute in `midi_device_info.xml` is parsed by the system at runtime only; no test
  reads it.
- The manufacturer and product strings exist twice, as the Kotlin constants and in the XML,
  because the XML cannot read Kotlin. Nothing checks they agree except a reviewer.

**Heard: nothing; this session had no phone.**
