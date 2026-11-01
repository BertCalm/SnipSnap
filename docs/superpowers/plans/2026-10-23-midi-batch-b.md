# MIDI batch B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SnipSnap sends MIDI: pad hits and KEYS notes leave over every connected device's input ports (behind a SETUP switch) and over a published "SnipSnap Out" port, and other apps can play SnipSnap through a published "SnipSnap In" port.

**Architecture:** A pure encoder in `:kit` (`MidiBytes`) whose tests round-trip through the existing parser; a pure inverse pad map and gate rule in `:shell`; one process-wide sender (`MidiOut`) with a single `HandlerThread` and a destination list; `MidiInput` grows input-port opening and self-exclusion; a `MidiDeviceService` publishes the two ports and routes "SnipSnap In" into the existing input path through a small process-wide bus. Notes that arrived over MIDI never go back out.

**Tech Stack:** Kotlin 2.0.21, `kotlin.test`, `android.media.midi` (`MidiInputPort.send` with timestamps, `MidiDeviceService`), Compose for one SETUP block.

**Spec:** `docs/MIDI_OUT_SPEC_2026_10.md` (binding). Argued from `docs/MIDI_GAPS_2026_10.md` steps 4 and 9.

## Global Constraints

- **Sender thread.** `MidiInputPort.send` runs only on `MidiOut`'s `HandlerThread("midi-out")`. Never on the main thread, never on the audio thread.
- **No thru.** A note delivered by `MidiPads.handle` or `MidiKeys.handle` is never re-sent. Only touch and sequencer origins call `MidiOut`.
- **Self-exclusion.** `MidiInput.openDevice` skips a `MidiDeviceInfo` whose `PROPERTY_SERVICE_INFO` `ServiceInfo.packageName == context.packageName`.
- **Channels.** Pads on `MidiGroove.DRUM_CHANNEL` (9, printed as channel 10); KEYS on channel 0 (printed as 1). Reuse the constant; do not redefine 9.
- **Map.** Pad notes come from `MidiPads.noteForSlot(slot, map)`: PLAY uses its current `midiMap`, the sequencer and the PLAY window grid use `Map.MPC`.
- **Gate.** A hit with no release (sequencer, PLAY window grid) sends its note-off stamped `timestamp + MidiGate.stepNanos(bpm)` with `bpm = kit.tempoBpm ?: KitPreview.DEFAULT_BPM`; one source for "one step".
- **Default.** `MidiOut.hardwareEnabled` defaults to **false** (`PREFS` key `"midi.out"`); "SnipSnap Out" always sends.
- **Clock domain.** Stamps are `System.nanoTime()` nanoseconds. Touch origins stamp `System.nanoTime()` at the call; the sequencer stamps the frame time that fired the hit.
- **House split.** Rules and encoding in `:kit`/`:shell` with tests; `:app` proved by `./gradlew --offline -q :app:compileDebugKotlin` and `./gradlew --offline -q :app:processDebugManifest`.
- **One Gradle at a time** in this worktree. Never start a second invocation while one runs.
- **Copy.** Every UI string is a `Copy` constant in `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt`, upper case, in the file's voice.
- **Naming rule.** No maker names or model numbers in engine, voice, preset or event names.
- **Commit style.** Plain declarative title, no prefix, a body saying why and what was left out, ending with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_011mdfbYzrdmCr1B8F6bq2vp
  ```

## Review Focus

1. A note-on with velocity 0 must encode as a note-off status byte, so a receiver that does not honour the velocity-0 convention still stops the note. (Task 1 test.)
2. A `send` that throws `IOException` on the out thread must drop that destination and never propagate; the next note to the other destinations still goes. (Task 3; proved by reading, disclosed.)
3. PANIC and a pulled cable must send All Notes Off on both channels so a receiver is not left holding a note the app has let go. (Task 5 wiring; Task 3 API.)
4. SnipSnap's own virtual device must never be opened by `MidiInput`, or PLAY hears its own notes. (Task 4; proved by reading the package check; a device test row M2.)
5. The "SnipSnap In" pseudo-device must disappear from `MidiInput.devices` when the last client closes, and its held notes be let go, exactly like a cable pull. (Task 4; reading plus M2.)

---

### Task 1: `MidiBytes`, the encoder that round-trips through `MidiWire`

**Files:**
- Create: `kit/src/main/kotlin/com/snipsnap/kit/MidiBytes.kt`
- Create: `kit/src/test/kotlin/com/snipsnap/kit/MidiBytesTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object MidiBytes {
      /** Three bytes: 0x90|channel, note, velocity. velocity 0 encodes as noteOff. */
      fun noteOn(channel: Int, note: Int, velocity: Int): ByteArray
      /** Three bytes: 0x80|channel, note, 0. */
      fun noteOff(channel: Int, note: Int): ByteArray
      /** Three bytes: 0xB0|channel, controller, value. */
      fun control(channel: Int, controller: Int, value: Int): ByteArray
      /** CC 123 value 0 on [channel]: All Notes Off. */
      fun allNotesOff(channel: Int): ByteArray = control(channel, MidiWire.CC_ALL_NOTES_OFF, 0)
  }
  ```
  Every argument clamped: channel to 0..15, note/controller/value/velocity to 0..127. Never throws.

- [ ] **Step 1: Write the failing tests**

```kotlin
class MidiBytesTest {
    private fun parse(bytes: ByteArray) = MidiWire().feed(bytes)

    @Test fun `a note on round-trips through the parser`() =
        assertEquals(listOf(Event.NoteOn(9, 36, 100 / 127f)), parse(MidiBytes.noteOn(9, 36, 100)))
    @Test fun `a note off round-trips`() =
        assertEquals(listOf(Event.NoteOff(0, 60)), parse(MidiBytes.noteOff(0, 60)))
    @Test fun `a controller round-trips`() =
        assertEquals(listOf(Event.Control(3, 64, 127)), parse(MidiBytes.control(3, 64, 127)))
    @Test fun `velocity zero is a note off status, not a note on with zero`() {
        val bytes = MidiBytes.noteOn(0, 60, 0)
        assertEquals(0x80, bytes[0].toInt() and 0xFF)
        assertEquals(listOf(Event.NoteOff(0, 60)), parse(bytes))
    }
    @Test fun `out of range arguments are clamped, never thrown`() {
        assertEquals(listOf(Event.NoteOn(15, 127, 1f)), parse(MidiBytes.noteOn(99, 300, 999)))
        assertEquals(listOf(Event.NoteOn(0, 0, 1 / 127f)), parse(MidiBytes.noteOn(-1, -5, 1)))
    }
    @Test fun `all notes off is controller 123 at zero`() =
        assertEquals(listOf(Event.Control(9, MidiWire.CC_ALL_NOTES_OFF, 0)), parse(MidiBytes.allNotesOff(9)))
    @Test fun `velocity is the inverse of what MidiWire reads, bit for bit`() {
        for (v in 1..127) {
            val on = parse(MidiBytes.noteOn(0, 60, v)).single() as Event.NoteOn
            assertEquals(v, (on.velocity * 127).roundToInt(), "velocity $v")
        }
    }
}
```
(`roundToInt` is the formula `PadHit.midiVelocity` in `:shell` uses; `:kit` cannot depend on `:shell`, so it is inlined here.) Read `MidiWireTest` first for the file's helpers and imports; match them.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew --offline -q :kit:test --tests 'com.snipsnap.kit.MidiBytesTest'`
Expected: unresolved reference `MidiBytes`.

- [ ] **Step 3: Implement `MidiBytes.kt`**

KDoc in `MidiWire`'s register: this is the other half of the wire; the tests feed it through the parser so the two cannot disagree. Use `MidiWire.NOTE_ON`, `NOTE_OFF`, `CONTROL_CHANGE` constants.

- [ ] **Step 4: Run the suite**

Run: `./gradlew --offline -q :kit:test`
Expected: pass.

- [ ] **Step 5: Commit**

Title: `Add MidiBytes, the half of the wire that writes`.

---

### Task 2: The pad map's inverse, the gate rule, and the SETUP copy

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/MidiPads.kt` (add `noteForSlot`)
- Create: `shell/src/main/kotlin/com/snipsnap/shell/MidiGate.kt`
- Modify: `shell/src/test/kotlin/com/snipsnap/shell/MidiPadsTest.kt`
- Create: `shell/src/test/kotlin/com/snipsnap/shell/MidiGateTest.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` (`Copy` constants, beside `SETUP_HAPTICS_NOTE`)

**Interfaces:**
- Produces:
  ```kotlin
  // MidiPads
  /** The note that plays [slot] (1..PadNoteMap.PAD_COUNT) under [map]; null outside the pads. */
  fun noteForSlot(slot: Int, map: Map): Int?
  // MidiGate
  object MidiGate {
      /** One step (a sixteenth) at [bpm], in nanoseconds: the gate a hit with no release gets. */
      fun stepNanos(bpm: Float): Long = (1_000_000_000.0 / BeatClock.stepsPerSecond(bpm)).toLong()
  }
  // Copy
  const val SETUP_MIDI_OUT_HEADING = "MIDI OUT"
  const val SETUP_MIDI_OUT_NOTE = "PADS AND KEYS GO OUT TO EVERY PLUGGED-IN DEVICE. OTHER APPS CAN ALWAYS OPEN SNIPSNAP OUT."
  const val SETUP_MIDI_OUT_NONE = "NOTHING PLUGGED IN. SNIPSNAP OUT IS STILL PUBLISHED."
  const val SETUP_MIDI_OUT_UNSUPPORTED = "THIS PHONE HAS NO MIDI."
  const val SETUP_MIDI_OUT_PORT_OK = "OUT"
  const val SETUP_MIDI_OUT_PORT_BUSY = "BUSY"
  ```

- [ ] **Step 1: Write the failing tests**

In `MidiPadsTest`, matching its style:
```kotlin
@Test fun `noteForSlot is slotForNote backwards for every pad under both maps`() {
    for (map in MidiPads.Map.entries) for (slot in 1..PadNoteMap.PAD_COUNT) {
        val note = MidiPads.noteForSlot(slot, map)
        assertNotNull(note, "$map slot $slot has a note")
        assertEquals(slot, MidiPads.slotForNote(note, map), "$map slot $slot round-trips")
    }
}
@Test fun `the kick pad A02 goes out as note 36 under the MPC map`() = assertEquals(36, MidiPads.noteForSlot(2, MidiPads.Map.MPC))
@Test fun `a slot outside the pads has no note`() {
    assertNull(MidiPads.noteForSlot(0, MidiPads.Map.MPC)); assertNull(MidiPads.noteForSlot(PadNoteMap.PAD_COUNT + 1, MidiPads.Map.IN_ORDER))
}
```
`MidiGateTest`:
```kotlin
@Test fun `one step at 120 bpm is an eighth of a second`() = assertEquals(125_000_000L, MidiGate.stepNanos(120f))
@Test fun `one step at 60 bpm is a quarter second`() = assertEquals(250_000_000L, MidiGate.stepNanos(60f))
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew --offline -q :shell:test --tests 'com.snipsnap.shell.MidiPadsTest' --tests 'com.snipsnap.shell.MidiGateTest'`
Expected: unresolved references.

- [ ] **Step 3: Implement**

`noteForSlot`: `Map.MPC` reads `PadNoteMap.DEFAULT[slot - 1]`; `Map.IN_ORDER` is `IN_ORDER_FIRST_NOTE + slot - 1`; both null outside `1..PadNoteMap.PAD_COUNT` (and null if the result is outside 0..127). KDoc: the outgoing side of the same map, so an MPC on the other end plays the pad SnipSnap played. `MidiGate` as the interface shows, with a KDoc naming the one place "one step" is defined. `Copy` constants as listed.

- [ ] **Step 4: Run the suite**

Run: `./gradlew --offline -q :shell:test`
Expected: pass.

- [ ] **Step 5: Commit**

Title: `Give the pad map an outgoing side, one step a length, and SETUP its MIDI OUT words`.

---

### Task 3: `MidiOut`, the sender, and the input ports `MidiInput` opens for it

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/MidiOut.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/SnipSnapApplication.kt` (`MidiOut.install(this)` beside `AudioFocus.install(this)`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/MidiInput.kt` (`Opened` gains input ports; `openDevice` opens them, registers destinations, skips the app's own package; `release` closes them and unregisters; `closeDevice` calls `MidiOut.allNotesOff()` before `onDeviceRemoved`)

**Interfaces:**
- Consumes: `MidiBytes` (Task 1), `MidiFingerprint`/`MidiDeviceId` (batch A).
- Produces:
  ```kotlin
  object MidiOut {
      data class Port(val name: String, val open: Boolean)      // for SETUP: "<device> · OUT" or "BUSY"
      class Destination(val id: String, val name: String, val send: (ByteArray, Long) -> Unit)
      fun install(context: Context)                               // reads PREFS "midi.out", starts the thread lazily
      var hardwareEnabled: Boolean                                // main thread; persisted by App.kt, not here
      val ports: StateFlow<List<Port>>                            // every registered destination plus refused ones
      fun add(d: Destination, hardware: Boolean = true)
      fun remove(id: String)
      fun reportRefused(id: String, name: String)                 // MidiInput: a port that would not open
      fun noteOn(channel: Int, note: Int, velocity: Float, timestampNanos: Long)
      fun noteOff(channel: Int, note: Int, timestampNanos: Long)
      fun allNotesOff()                                           // CC 123 on channels 9 and 0, now
  }
  ```
  `velocity: Float` in 0..1 is converted with `PadHit.midiVelocity`. Destinations added with `hardware = false` (the published port, Task 4) send regardless of `hardwareEnabled`. Destination ids: `"${fingerprint.key}/in$index"` for hardware; `"snipsnap-out"` for the published port.

- [ ] **Step 1: Implement `MidiOut.kt`**

`HandlerThread("midi-out")` started in `install`. `noteOn`/`noteOff`/`allNotesOff` encode on the caller's thread with `MidiBytes` and `handler.post` a closure that iterates a `CopyOnWriteArrayList<Destination>` and calls `send(bytes, timestamp)` for each destination that is not hardware-gated off, catching `IOException` per destination: log once, `remove(id)`. KDoc the thread rule and the no-thru rule (callers are touch and sequencer only). `ports` recomputed on add/remove/reportRefused.

- [ ] **Step 2: Wire `MidiInput`**

`Opened` gains `val inputPorts: List<MidiInputPort>`. In `openDevice`: first, skip when `info.properties.getParcelable<ServiceInfo>(MidiDeviceInfo.PROPERTY_SERVICE_INFO)?.packageName == context.packageName` (API-33 typed overload behind `SDK_INT`, deprecated below, the way `fingerprintOf` already branches; keep a `context` reference for the package name). After the output ports: for each `p in 0 until info.inputPortCount`, `runCatching { device.openInputPort(p) }.getOrNull()`; null → `MidiOut.reportRefused(id, "${name} ${p}")` and a `Log.w`; else `MidiOut.add(Destination(id, name, port::send-shaped lambda))` where the lambda is `{ bytes, ts -> port.send(bytes, 0, bytes.size, ts) }`. Comment the naming trap (you send into the device's input). In `release`: `MidiOut.remove(id)` and `runCatching { port.close() }` for each. In `closeDevice`: `MidiOut.allNotesOff()` before `listener?.onDeviceRemoved()` (a receiver must not keep a note the app let go).

- [ ] **Step 3: Install**

`MidiOut.install(this)` in `SnipSnapApplication.onCreate` with a two-line comment.

- [ ] **Step 4: Compile**

Run: `./gradlew --offline -q :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Title: `Open every device's input ports and give the app one thread that sends`. Body: the thread rule, the default-off switch and why, the self-exclusion, what is disclosed (no JVM test for `:app`; `IOException` path proved by reading).

---

### Task 4: The published device: "SnipSnap In" and "SnipSnap Out"

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/SnipSnapMidiService.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/VirtualMidi.kt`
- Create: `app/src/main/res/xml/midi_device_info.xml`
- Modify: `app/src/main/AndroidManifest.xml` (service element after `SnipTileService`, with a comment in the file's register)
- Modify: `app/src/main/kotlin/com/snipsnap/app/MidiInput.kt` (subscribe to `VirtualMidi` in `open`, unsubscribe in `close`; list the pseudo-device while bound)

**Interfaces:**
- Consumes: `MidiOut.add/remove` (Task 3), `MidiWire`, `MidiFingerprint`.
- Produces:
  ```kotlin
  object VirtualMidi {
      interface Listener { fun onEvent(event: MidiWire.Event); fun onBound(id: MidiDeviceId); fun onUnbound() }
      val boundId: MidiDeviceId?                       // non-null while another app holds the device
      fun subscribe(l: Listener); fun unsubscribe(l: Listener)
      // called by the service, main thread:
      internal fun bound(id: MidiDeviceId); internal fun unbound(); internal fun deliver(events: List<MidiWire.Event>)
  }
  ```
  `midi_device_info.xml`:
  ```xml
  <devices>
      <device manufacturer="SnipSnap" product="SnipSnap" tags="pads,keys">
          <input-port name="SnipSnap In" />
          <output-port name="SnipSnap Out" />
      </device>
  </devices>
  ```
  Manifest:
  ```xml
  <service android:name=".SnipSnapMidiService"
      android:exported="true"
      android:permission="android.permission.BIND_MIDI_DEVICE_SERVICE">
      <intent-filter><action android:name="android.media.midi.MidiDeviceService" /></intent-filter>
      <meta-data android:name="android.media.midi.MidiDeviceService" android:resource="@xml/midi_device_info" />
  </service>
  ```

- [ ] **Step 1: `SnipSnapMidiService`**

`onGetInputPortReceivers()` returns one `MidiReceiver` whose `onSend` feeds a `MidiWire` (stateful, this port's own) with the delivery's timestamp, filters `Event.RealTime` the way `MidiInput.Receiver` does, and posts the rest to the main thread as `VirtualMidi.deliver(events)`. `onCreate`: `MidiOut.add(Destination("snipsnap-out", "SNIPSNAP OUT") { bytes, ts -> outputPortReceivers[0].send(bytes, 0, bytes.size, ts) }, hardware = false)` and `VirtualMidi.bound(MidiDeviceId("SNIPSNAP IN", MidiFingerprint.of(VIRTUAL, 0, 0, 1, 1, "SnipSnap", "SnipSnap In", null)))`. `onDestroy`: `MidiOut.remove("snipsnap-out")`, `VirtualMidi.unbound()`. KDoc: the system binds on first open and unbinds after last close; that is the only lifecycle this service has.

- [ ] **Step 2: `VirtualMidi` and `MidiInput`**

`VirtualMidi` as the interface shows, main-thread only, `CopyOnWriteArrayList` of listeners. `MidiInput.open` subscribes a listener that forwards `onEvent` to its own `Listener.onEvent`, and on `onBound`/`onUnbound` recomputes `devices` (now `opened.values.map { it.id } + listOfNotNull(VirtualMidi.boundId)`) and calls `onDevicesChanged`; on `onUnbound` it calls `onDeviceRemoved()` first, the same order `closeDevice` uses. `MidiInput.close` unsubscribes.

- [ ] **Step 3: Manifest and XML**

As the interface shows.

- [ ] **Step 4: Compile and process the manifest**

Run: `./gradlew --offline -q :app:compileDebugKotlin :app:processDebugManifest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Title: `Publish SnipSnap as a MIDI device with an In and an Out port`.

---

### Task 5: The origins send

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt` (`playHeld`, `release`, `panic`, the window grid's hit function near line 310-321, the fullscreen grid callbacks at ~1042)
- Modify: `app/src/main/kotlin/com/snipsnap/app/BeatPlayer.kt` (`hit`, ~362-376)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KeysScreen.kt` (touch `onPress`/`onRelease` at ~218-219 only; the `MidiKeys.Voice` at ~103-104 is MIDI in and stays silent)

**Interfaces:**
- Consumes: `MidiOut.noteOn/noteOff/allNotesOff`, `MidiPads.noteForSlot`, `MidiGate.stepNanos`, `MidiGroove.DRUM_CHANNEL`, `KitPreview.DEFAULT_BPM`.

- [ ] **Step 1: PLAY**

`playHeld(slot, velocity, fromMidi: Boolean)` and `release(slot, fromMidi: Boolean)`: when `!fromMidi`, after the local hit, `MidiOut.noteOn(MidiGroove.DRUM_CHANNEL, note, velocity, System.nanoTime())` with `note = MidiPads.noteForSlot(slot, midiMap) ?: return`; `release` sends `noteOff` likewise. `MidiPads.handle(..., onHit = { s, v -> playHeld(s, v, fromMidi = true) }, onRelease = { s -> release(s, fromMidi = true) }, ...)`; the fullscreen grid passes `fromMidi = false`. The window grid's hit (no release gesture; read its header comment first) sends `noteOn` and a `noteOff` stamped `now + MidiGate.stepNanos(kit.tempoBpm ?: KitPreview.DEFAULT_BPM)` under `Map.MPC`. `panic()` adds `MidiOut.allNotesOff()`.

- [ ] **Step 2: Sequencer**

In `BeatPlayer.hit`, after the local hit succeeds: `note = MidiPads.noteForSlot(slot, MidiPads.Map.MPC) ?: return`; `now = System.nanoTime()` (the frame time is not in scope here; say so in a comment and leave the frame-stamp for the clock batch); `MidiOut.noteOn(DRUM_CHANNEL, note, velocity, now)`; `MidiOut.noteOff(DRUM_CHANNEL, note, now + MidiGate.stepNanos(entry.kit.tempoBpm ?: KitPreview.DEFAULT_BPM))`. Check how `tempoBpm` is typed (nullable or not) and adapt.

- [ ] **Step 3: KEYS**

`onPress = { v -> note?.let { player.noteOn(it, v); MidiOut.noteOn(0, it, v, System.nanoTime()) } }` and the matching `noteOff` on release. Leave the `MidiKeys.Voice` object untouched (no thru). KEYS' `allOff` path (`audioVoice.silence` → `midiKeys.allOff()`): add `MidiOut.allNotesOff()` where KEYS lets every note go on `ON_STOP`.

- [ ] **Step 4: Compile**

Run: `./gradlew --offline -q :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Title: `Let pads and keys leave over MIDI, and never echo what came in`.

---

### Task 6: The SETUP switch

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (`PREF_MIDI_OUT = "midi.out"` beside `PREF_HAPTICS`; `midiOutOn` state beside `hapticsOn`, read from `prefs`, written on change, pushed into `MidiOut.hardwareEnabled`; passed to `PropertiesScreen` at ~3735)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PropertiesScreen.kt` (parameters `midiOutOn: Boolean`, `onMidiOut: (Boolean) -> Unit`, `midiSupported: Boolean`, `midiPorts: List<MidiOut.Port>`; one `GroupBox` after HAPTICS)

- [ ] **Step 1: The block**

Legend `Copy.SETUP_MIDI_OUT_HEADING`, summary ON/OFF, the HAPTICS ON/OFF bevel row (`tapeClick(label = "MIDI OUT ON/OFF")`), then `Copy.SETUP_MIDI_OUT_NOTE`, then one `TapeText` per `midiPorts` entry as `"${name} · ${if (open) Copy.SETUP_MIDI_OUT_PORT_OK else Copy.SETUP_MIDI_OUT_PORT_BUSY}"`, or `Copy.SETUP_MIDI_OUT_NONE` when empty. When `!midiSupported`: no bevels, only `Copy.SETUP_MIDI_OUT_UNSUPPORTED`. `midiPorts` comes from `MidiOut.ports.collectAsState()` in `App.kt`; `midiSupported` from `context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)`.

- [ ] **Step 2: Compile**

Run: `./gradlew --offline -q :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

Title: `Give SETUP a MIDI OUT switch and the list of where notes go`.

---

### Task 7: Record batch B

**Files:**
- Modify: `docs/MIDI_OUT_SPEC_2026_10.md` (append `## 13. As built (2026-10-23)`: one bullet per task with its hash and proof; disclosed: no JVM test for `:app`, the sequencer stamps `System.nanoTime()` not the frame time, bench rows M1 to M4 unrun; "Heard: nothing")
- Modify: `docs/MIDI_GAPS_2026_10.md` (strike rows 4 and 9 with hashes the way rows 1, 5, 6 are struck; one As built line pointing at the spec)
- Modify: `docs/README.md` (add the spec to the **MIDI and timing.** paragraph)
- Modify: `.claude/skills/midiwright/references/snipsnap-midi-state.md` (header commit and date; `MidiInput` row; §5 gaps 2 and 3 struck with hashes; a `MidiOut`/`SnipSnapMidiService` row in §2) and `roadmap.md` (P0.4 and P0.5 marked landed with hashes)

- [ ] **Step 1: Write**
- [ ] **Step 2: Check** `grep -c "MIDI_" docs/README.md` ≥ 5; every hash in the As built appears in `git log --format=%h 61757cfb6..HEAD`.
- [ ] **Step 3: Commit** Title: `Record MIDI batch B`.
