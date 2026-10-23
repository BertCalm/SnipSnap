# MIDI batch A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the four MIDI steps that need no phone and no bench row: real-time events in the parser, an absolute frame counter in the pad engine, a device fingerprint, and a guard against a dropped stop command. Then record what landed.

**Architecture:** Each step lands beside what exists, in the house split: pure rules and parsing in `:kit` and `:shell` where `./gradlew test` proves them; Android glue in `:app` proved by `:app:compileDebugKotlin`; realtime C++ in `app/src/main/cpp` proved by the host harness in `app/src/main/cpp/test`. Nothing on the Oboe callback allocates, locks, logs or calls JNI. Every event keeps its `CLOCK_MONOTONIC` nanosecond stamp from the edge.

**Tech Stack:** Kotlin 2.0.21 (JVM 17), `kotlin.test`, C++17 with the repo's `check.h` harness and CMake, Android `android.media.midi`, Oboe 1.9.0.

**Spec:** `docs/MIDI_GAPS_2026_10.md` (steps 1, 2, 5, 6 of its table) argued from `docs/MIDI_REVIEW_2026_10.md` ("What has to change first, in order", steps 1, 3, 5, 6). The review is the binding authority where the two disagree.

## Global Constraints

- **Realtime rule.** Nothing in `PadEngine::onAudioReady` or anything it calls allocates, locks, logs, or calls JNI. New engine state read off the audio thread is a `std::atomic` with acquire/release, the pattern `restartNeeded_` already uses.
- **Clock rule.** One domain: `System.nanoTime()` / `CLOCK_MONOTONIC` nanoseconds. `MidiWire` never reads a clock; stamps are passed in.
- **House split.** Semantics on the JVM (`:kit`, `:shell`); the native voice hears only "this sample, these frames, these gains, this speed, at this frame". `:app` has no host unit-test source set; its proof is the compiler.
- **Parser contract** (`MidiWire` KDoc): never more than one message behind, at most two pending data bytes, no input makes it allocate per byte or stall. Real-time bytes (`0xF8`..`0xFF`) land anywhere, including inside a SysEx and between a status byte and its data, and do not cancel running status.
- **Naming rule.** No maker names or model numbers in engine, voice, preset or event names. `Clock`, `Start`, `Continue`, `Stop` are MIDI's own words.
- **Fingerprint rule.** Never key a device on its display name alone (it changes once USB permission is granted) and never on a Bluetooth address (randomised). Vendor id, product id, port counts and a hash of manufacturer+product.
- **Commit style.** Plain declarative title, no prefix. A body that says why and what was left out. End with the two attribution trailers the session's system reminder gives (`Co-Authored-By:` and `Claude-Session:`), as commit `03951c3ce` does.
- **Proof commands** (run from the worktree root, `--offline` works here):
  - `./gradlew --offline -q :kit:test` / `:shell:test`
  - `./gradlew --offline -q :app:compileDebugKotlin`
  - `python3 scripts/check_native_sources.py && cmake --build build/native-tests && ctest --test-dir build/native-tests --output-on-failure` (the build dir is already configured; if it is missing, `cmake -S app/src/main/cpp/test -B build/native-tests` first)
- **Kotlin `when` on a sealed type is exhaustive.** Adding `Event` subtypes breaks every `when (event)` over `MidiWire.Event` that has no `else`: `MidiKeys.handle`, `MidiPads.handle`. Each gets an explicit branch; no bare `else`.
- **Docs are not CI-triggering.** `docs/**` and `**/*.md` are path-ignored by `tests.yml`; a docs-only commit gets no checks by design.

## Review Focus

1. A delivery that is one `0xF8` byte, with a note-on split either side of it across three deliveries: the note must still assemble, stamped by the third delivery, and the `Clock` stamped by the second. (Task 1 test "a clock between a status byte and its data...")
2. A thousand `0xF8` bytes in one delivery: a thousand `Clock` events, `runningStatus` untouched, the next data bytes still read under it. (Task 1 test "a flood of clocks...")
3. `0xFE` active sensing and `0xFF` reset, and the undefined `0xF9` and `0xFD`: still stepped over and dropped, so a keyboard that sends active sensing every 300 ms does not produce an event every 300 ms. (Task 1 test "active sensing, reset and the undefined real-time bytes are still dropped")
4. The frame counter after a `stop()` and `start()` cycle: it must not go backwards. (Task 2 test "frames rendered never goes backwards across stop and start")
5. A `stop` refused by a full ring, and then `allOff` also refused in the same frame: the next `drainEnded` must issue the `allOff` and clear the pending stops, not replay the stops after the `allOff`. (Task 4; proved by compile plus the C++ `pushCommand` contract, disclosed in the report as unprovable on the JVM.)

---

### Task 1: Real-time events in `MidiWire`

**Files:**
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/MidiWire.kt` (KDoc header lines 22-26, `Event` at 57-89, `feed` branch `b >= 0xF8 -> {}` at 119, companion)
- Modify: `kit/src/test/kotlin/com/snipsnap/kit/MidiWireTest.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/MidiKeys.kt:44-57` and `shell/src/main/kotlin/com/snipsnap/shell/MidiPads.kt:69-83` (one branch each)
- Modify: `.claude/skills/midiwright/scripts/wire_fixtures.py` (the reference parser `RefWire` and the `FIXTURES` list)

**Interfaces:**
- Consumes: `MidiWire.Event { val timestampNanos: Long }`, `MidiWire.feed(bytes, offset, count, timestampNanos): List<Event>`.
- Produces, for later clock work:
  ```kotlin
  sealed interface Event {
      val timestampNanos: Long
      /** A channel message: a note or a controller on one of sixteen channels. */
      sealed interface Channel : Event { val channel: Int }
      data class NoteOn(override val channel: Int, val note: Int, val velocity: Float, override val timestampNanos: Long = 0L) : Channel
      data class NoteOff(override val channel: Int, val note: Int, override val timestampNanos: Long = 0L) : Channel
      data class Control(override val channel: Int, val controller: Int, val value: Int, override val timestampNanos: Long = 0L) : Channel
      /** A system real-time message: one byte, no channel, the transport's clock and its buttons. */
      sealed interface RealTime : Event
      data class Clock(override val timestampNanos: Long = 0L) : RealTime
      data class Start(override val timestampNanos: Long = 0L) : RealTime
      data class Continue(override val timestampNanos: Long = 0L) : RealTime
      data class Stop(override val timestampNanos: Long = 0L) : RealTime
  }
  ```
  Companion constants: `TIMING_CLOCK = 0xF8`, `START = 0xFA`, `CONTINUE = 0xFB`, `STOP = 0xFC`. `0xF9`, `0xFD`, `0xFE`, `0xFF` stay dropped.

- [ ] **Step 1: Extend the fixture script's contract and add two fixtures**

In `wire_fixtures.py`, make `RefWire` emit `("clock", ts)`, `("start", ts)`, `("continue", ts)`, `("stop", ts)` for `F8`, `FA`, `FB`, `FC` at the position they arrive (still without disturbing the message around them), and keep dropping `F9`, `FD`, `FE`, `FF`. Update the module docstring's rule line. Add fixtures:
- `clock_between_status_and_data`: deliveries `[[0x90], [0xF8], [60, 100]]` with stamps 1, 2, 3 → `("clock", 2), ("note_on", 0, 60, 100, 3)` (adapt tuple shape to what the script already uses; stamps may need adding to the script's event tuples if it has none; if so add them as the last element everywhere).
- `clock_flood`: one delivery of `[0x91, 60, 100] + [0xF8] * 1000 + [62, 100]` → one note-on, 1000 clocks, one note-on under running status.

Run: `python3 .claude/skills/midiwright/scripts/wire_fixtures.py > /dev/null`
Expected: exit 0 (the script validates every fixture against `RefWire` and refuses to print on disagreement).

- [ ] **Step 2: Write the failing Kotlin tests**

Add to `MidiWireTest`, in the file's existing style (`bytes(...)`, backtick names, `assertEquals` on lists):

```kotlin
@Test
fun `clock, start, continue and stop come out as events with the delivery's stamp`() {
    val wire = MidiWire()
    assertEquals(
        listOf(Event.Start(7L), Event.Clock(7L), Event.Clock(7L), Event.Continue(7L), Event.Stop(7L)),
        wire.feed(bytes(0xFA, 0xF8, 0xF8, 0xFB, 0xFC), timestampNanos = 7L),
    )
}

@Test
fun `a clock between a status byte and its data is heard, and the note still assembles under the later stamp`() {
    val wire = MidiWire()
    assertEquals(emptyList(), wire.feed(bytes(0x90), timestampNanos = 1L))
    assertEquals(listOf(Event.Clock(2L)), wire.feed(bytes(0xF8), timestampNanos = 2L))
    assertEquals(listOf(Event.NoteOn(0, 60, 100 / 127f, 3L)), wire.feed(bytes(60, 100), timestampNanos = 3L))
}

@Test
fun `a flood of clocks leaves running status alone and costs one event each`() {
    val wire = MidiWire()
    val flood = IntArray(1000) { 0xF8 }
    val events = wire.feed(bytes(0x91, 60, 100, *flood, 62, 100))
    assertEquals(1002, events.size)
    assertEquals(Event.NoteOn(1, 60, 100 / 127f), events.first())
    assertTrue(events.subList(1, 1001).all { it == Event.Clock() })
    assertEquals(Event.NoteOn(1, 62, 100 / 127f), events.last())
}

@Test
fun `a clock inside a SysEx is heard and the SysEx stays skipped`() {
    assertEquals(listOf(Event.Clock()), MidiWire().feed(bytes(0xF0, 0x7E, 0xF8, 0x01, 0xF7)))
}

@Test
fun `active sensing, reset and the undefined real-time bytes are still dropped`() {
    assertEquals(emptyList(), MidiWire().feed(bytes(0xFE, 0xFF, 0xF9, 0xFD)))
}
```

Also amend the existing tests at line 45 (`real-time bytes in the middle of a message are stepped over...`) and line 186 (`real-time bytes inside a SysEx are stepped over...`) if they feed `0xF8`..`0xFC` and assert an empty or notes-only list: they must now expect the `Clock` (or other `RealTime`) events in position. Read each before changing it; keep its intent (running status untouched; SysEx data still skipped). A test that feeds only `0xFE` or `0xFF` is unchanged.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew --offline -q :kit:test --tests 'com.snipsnap.kit.MidiWireTest'`
Expected: compile failure on `Event.Clock` (unresolved reference).

- [ ] **Step 4: Implement the event types and the branch**

In `MidiWire.kt`: introduce `Channel` and `RealTime` as in Interfaces; move `channel` off `Event` onto `Channel`; replace `b >= 0xF8 -> {}` with a branch that appends the matching `RealTime` event stamped `stamp` for `TIMING_CLOCK`, `START`, `CONTINUE`, `STOP` and does nothing for the other four. Running status, `status`, `data0`, `needed`, `inSysEx` are untouched by that branch. Update the KDoc header: "What comes out is what a player and a transport need: notes, controllers, and the clock's own four bytes" (your words, same register) and the sentence at line 17-19 about real-time bytes.

- [ ] **Step 5: Give `MidiKeys` and `MidiPads` their branch**

In each `when (event)`, add `is MidiWire.Event.RealTime -> {}` with a one-line comment: the transport's clock is not a key; nothing here plays it yet. No `else`.

- [ ] **Step 6: Run both suites**

Run: `./gradlew --offline -q :kit:test :shell:test`
Expected: BUILD SUCCESSFUL, no failures. `MidiWireTest` now has 24 cases.

- [ ] **Step 7: Commit**

Title: `Let MidiWire hand on the clock's four real-time bytes`. Body: why (clock in and out are nothing but stamped `0xF8`; the review's step 1 second half), what the flood test proves, what stays dropped and why, and that nothing consumes the events yet.

---

### Task 2: An absolute frame counter in `PadEngine`

**Files:**
- Modify: `app/src/main/cpp/PadEngine.h` (public section near `needsRestart()`, private members near `restartNeeded_`)
- Modify: `app/src/main/cpp/PadEngine.cpp` (`onAudioReady`, after the render loop)
- Modify: `app/src/main/cpp/jni.cpp` (one new entry point beside `Java_com_snipsnap_app_NativePads_drainEnded`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/NativePads.kt` (one `external fun`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/PadEngine.kt` (one `@Synchronized` getter beside `drainEnded`)
- Test: `app/src/main/cpp/test/engine_tests.cpp`; declare the new JNI symbol in `app/src/main/cpp/test/jni_tests.cpp`'s extern block if that file lists every `NativePads` entry point (read lines 36-43 first; add a one-line test there only if a sibling entry point has one).

**Interfaces:**
- Produces: `int64_t PadEngine::framesRendered() const` (acquire load), the count of frames `onAudioReady` has written since construction, monotonic, never reset. `NativePads.framesRendered(handle: Long): Long`; `PadEngine.framesRendered(): Long` returning 0 when not open. Later clock work pairs this with `AudioStream::getTimestamp` sampled off the audio thread (held for bench S1).

- [ ] **Step 1: Write the failing tests**

In `engine_tests.cpp`, beside the `PadEngine` tests, using `callback(e, frames)`:

```cpp
TEST(pad_engine_counts_the_frames_it_has_rendered) {
    PadEngine e(kRate);
    CHECK(e.framesRendered() == 0);
    callback(e, 128);
    callback(e, 64);
    CHECK(e.framesRendered() == 192);
}

TEST(pad_engine_frames_rendered_never_goes_backwards_across_stop_and_start) {
    PadEngine e(kRate);
    callback(e, 128);
    e.stop();
    const int64_t before = e.framesRendered();
    // start() has no stream here (the Oboe stub refuses); the count must survive it either way.
    e.start();
    callback(e, 32);
    CHECK(e.framesRendered() == before + 32);
}
```
Use the harness's `CHECK` form that other tests use for integers (read two neighbouring tests first and match them).

- [ ] **Step 2: Build to verify it fails**

Run: `cmake --build build/native-tests 2>&1 | tail -5`
Expected: error, `framesRendered` is not a member.

- [ ] **Step 3: Implement**

`std::atomic<int64_t> framesRendered_{0};` as a private member; `int64_t framesRendered() const { return framesRendered_.load(std::memory_order_acquire); }` public; in `onAudioReady`, after the piece loop and before `print_.record`, `framesRendered_.fetch_add(numFrames, std::memory_order_release);`. A comment of two lines: this is the engine's only clock, the one the loudspeaker is on, and the nanoseconds-to-frames fit that will read it waits for bench S1. Do not touch it in `start()` or `stop()`.

JNI: `JNIEXPORT jlong JNICALL Java_com_snipsnap_app_NativePads_framesRendered(JNIEnv*, jobject, jlong handle)` returning `pads(handle)->framesRendered()`. Kotlin: `external fun framesRendered(handle: Long): Long` in `NativePads`; `@Synchronized fun framesRendered(): Long = if (open) NativePads.framesRendered(handle) else 0L` in `PadEngine.kt` with a KDoc line saying what it is for.

- [ ] **Step 4: Run the proofs**

Run: `python3 scripts/check_native_sources.py && cmake --build build/native-tests 2>&1 | tail -3 && ctest --test-dir build/native-tests --output-on-failure 2>&1 | tail -5`
Expected: both test binaries pass.
Run: `./gradlew --offline -q :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Title: `Count the frames the pad engine has rendered`. Body: why a counter now and the fit later (S1), why never reset, where the JVM reads it.

---

### Task 3: A device fingerprint on every connected MIDI device

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/MidiFingerprint.kt`
- Create: `shell/src/test/kotlin/com/snipsnap/shell/MidiFingerprintTest.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/MidiInput.kt` (`Listener.onDevicesChanged`, `Opened`, `deviceNames`, `openDevice`, `nameOf`'s companion)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KeysScreen.kt:119,231,238-243` and `app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt:387,1010` (the two listeners and `midiLine`)

**Interfaces:**
- Produces, in `:shell`:
  ```kotlin
  /** What a MIDI device is, steadily enough to key a controller profile or a learned mapping on. */
  data class MidiFingerprint(
      val transport: Transport,
      val vendorId: Int,        // 0 when the transport has none
      val productId: Int,       // 0 when the transport has none
      val inputPorts: Int,
      val outputPorts: Int,
      val nameHash: Int,        // of manufacturer + product, never of the display name alone
  ) {
      enum class Transport { USB, BLUETOOTH, VIRTUAL, UNKNOWN }
      /** A stable string for persistence, e.g. `usb:1235:0113:2:1:7f3a2b1c`. */
      val key: String
      companion object {
          fun of(transport: Transport, vendorId: Int, productId: Int, inputPorts: Int, outputPorts: Int,
                 manufacturer: String?, product: String?, name: String?): MidiFingerprint
      }
  }
  /** One connected device as the screens see it: what to print, and what to key on. */
  data class MidiDeviceId(val name: String, val fingerprint: MidiFingerprint)
  ```
  `nameHash` = `((manufacturer ?: "").hashCode() * 31) + (product ?: name ?: "").hashCode()`: the display name enters only when there is no product string.
- Changes in `:app`: `Listener.onDevicesChanged(devices: List<MidiDeviceId>)` replaces the `List<String>` form; `MidiInput.devices: List<MidiDeviceId>` replaces `deviceNames`; `Opened` gains `val id: MidiDeviceId`; `midiLine(devices: List<MidiDeviceId>)` prints `devices.map { it.name }` as before.

- [ ] **Step 1: Write the failing `:shell` tests**

```kotlin
class MidiFingerprintTest {
    private fun usb(manufacturer: String? = "Novation", product: String? = "Launchpad X", name: String? = "Launchpad X MIDI 1") =
        MidiFingerprint.of(MidiFingerprint.Transport.USB, 0x1235, 0x0113, 2, 2, manufacturer, product, name)

    @Test fun `the same device gives the same key`() = assertEquals(usb().key, usb().key)
    @Test fun `a different product id is a different key`() =
        assertNotEquals(usb().key, MidiFingerprint.of(MidiFingerprint.Transport.USB, 0x1235, 0x0114, 2, 2, "Novation", "Launchpad X", "Launchpad X MIDI 1").key)
    @Test fun `the display name changing after a permission grant does not change the key`() =
        assertEquals(usb(name = "USB-MIDI").key, usb(name = "Launchpad X MIDI 1").key)
    @Test fun `with no product string the display name is all there is, and it is used`() =
        assertNotEquals(usb(product = null, name = "A").key, usb(product = null, name = "B").key)
    @Test fun `a bluetooth device has no vendor or product and its key says so`() {
        val f = MidiFingerprint.of(MidiFingerprint.Transport.BLUETOOTH, 0, 0, 0, 1, null, null, "Keys")
        assertTrue(f.key.startsWith("bluetooth:0:0:0:1:"))
    }
    @Test fun `the key is the transport, the ids, the port counts and the hash in hex`() =
        assertEquals("usb:1235:113:2:2:${Integer.toHexString(usb().nameHash)}", usb().key)
}
```
(Hex without leading zeros, lower case, from `Integer.toHexString`; transport in lower case.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --offline -q :shell:test --tests 'com.snipsnap.shell.MidiFingerprintTest'`
Expected: unresolved reference `MidiFingerprint`.

- [ ] **Step 3: Implement `MidiFingerprint.kt`**

As the interface says. KDoc the class with the fingerprint rule and why (RtMidi's October 2026 Android work found the USB display name changes once permission is granted; Bluetooth addresses randomise). Put `MidiDeviceId` in the same file.

- [ ] **Step 4: Run the `:shell` tests**

Run: `./gradlew --offline -q :shell:test`
Expected: pass.

- [ ] **Step 5: Wire `MidiInput`**

In `openDevice`'s success branch, build the fingerprint from `info`: transport from `info.type` (`TYPE_USB`, `TYPE_BLUETOOTH`, `TYPE_VIRTUAL`, else `UNKNOWN`); vendor and product ids from `info.properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE)` when present (use the API-33 typed overload behind an `SDK_INT` check, the deprecated one below, mirroring how `open` already branches on `TIRAMISU`), else 0; `info.inputPortCount`, `info.outputPortCount`; `PROPERTY_MANUFACTURER`, `PROPERTY_PRODUCT`, `PROPERTY_NAME`. Never read `PROPERTY_BLUETOOTH_DEVICE`. Store `MidiDeviceId(nameOf(info), fingerprint)` on `Opened`; `devices` maps it; both `onDevicesChanged` calls pass `devices`. Update the class KDoc sentence about the LCD.

- [ ] **Step 6: Follow the two screens**

`KeysScreen` and `KitScreen`: the listener override takes `List<MidiDeviceId>`; `midiDevices` state holds it; `midiLine(devices: List<MidiDeviceId>)` maps to names. Nothing else on the screens changes.

- [ ] **Step 7: Compile**

Run: `./gradlew --offline -q :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

Title: `Give every connected MIDI device a fingerprint the screens can key on`. Body: the rule and the two sources it comes from, why the name still prints, what will key on it (profiles, learn), what is not persisted yet.

---

### Task 4: A refused stop is kept and retried

**Files:**
- Modify: `app/src/main/cpp/jni.cpp` (`Java_com_snipsnap_app_NativePads_stopVoice` and `Java_com_snipsnap_app_NativePads_allOff` return `jboolean` from `pushCommand`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/NativePads.kt:43-44` (`stopVoice`, `allOff` return `Boolean`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/PadEngine.kt:390-404` (`stop`, `allOff`, `drainEnded`)
- Modify: `app/src/main/cpp/test/jni_tests.cpp` extern declarations if they list `stopVoice`/`allOff` (read first).
- Note: `InstrumentPlayer.kt:117` calls `NativePads.allOff` directly for KEYS. It gets the new return type and ignores it; say so in the commit body (eight voices never fill a 256-deep ring; disclosed, not fixed).

**Interfaces:**
- Consumes: `bool PadEngine::pushCommand(PadCommand)` (false when the ring is full), `PadEngine.drainEnded()` called at screen rate by the frame loop.
- Produces: `PadEngine.stop(voiceId, fadeMs): Boolean` and `PadEngine.allOff(fadeMs): Boolean`, true when the command was queued now, false when it was kept to retry; callers need not change. `drainEnded()` retries what was kept before draining.

- [ ] **Step 1: Make the native returns honest**

`stopVoice` and `allOff` in `jni.cpp` return `pads(handle)->pushCommand(c) ? JNI_TRUE : JNI_FALSE` as `noteOn` already does. `NativePads.stopVoice` and `allOff` return `Boolean`. Update `jni_tests.cpp` declarations if present.

Run: `python3 scripts/check_native_sources.py && cmake --build build/native-tests 2>&1 | tail -3 && ctest --test-dir build/native-tests 2>&1 | tail -3`
Expected: pass.

- [ ] **Step 2: Keep what was refused**

In `PadEngine.kt`, private state: `private val pendingStops = ArrayList<Pair<Int, Float>>()` and `private var pendingAllOff: Float? = null`, both touched only under the class's `@Synchronized`. `stop` pushes; on false, appends `(voiceId, fadeMs)` to `pendingStops` and returns false. `allOff` pushes; on false, sets `pendingAllOff = fadeMs`, clears `pendingStops` (an all-off supersedes them) and returns false. `drainEnded` first calls a private `flushPending()`: if `pendingAllOff != null` try it and clear on success; then pop from the front of `pendingStops` while `NativePads.stopVoice` returns true; stop at the first false. Then drain as before. One `Log.w(TAG, ...)` the first time anything is kept, never per frame. KDoc on `stop` and `allOff`: why a refused stop is kept (a dropped stop is a stuck pad until PANIC; the ring drops on full by design because that is right for faders), and why the retry rides on `drainEnded` (it already runs at screen rate; no new loop).

- [ ] **Step 3: Compile**

Run: `./gradlew --offline -q :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

Title: `Keep a stop the command ring refused, and retry it at screen rate`. Body: the ring's drop-on-full contract and why it is right for faders and wrong for stops; the order rule (an all-off supersedes kept stops; kept stops never replay after it); why this has no JVM test (`:app` has no host test source set; the C++ side's `pushCommand` contract is the tested half); the KEYS call site disclosed.

---

### Task 5: Record what landed

**Files:**
- Modify: `docs/MIDI_GAPS_2026_10.md` ("As built: batch A" section; strike rows 1, 2 (counter half), 5, 6 of the table the way `snipsnap-midi-state.md` §5 strikes rows)
- Modify: `docs/MIDI_REVIEW_2026_10.md` ("Status" section: append a dated paragraph for steps 1 (clock half), 3 (counter half), 5, 6)
- Modify: `docs/README.md` (add a **MIDI and timing.** paragraph after **On a device.**, linking `MIDI_SYNC.md`, `MIDI_REVIEW_2026_10.md`, `MIDI_BLE_SPEC_2026_10.md`, `MIDI_GAPS_2026_10.md`, in that register)
- Modify: `.claude/skills/midiwright/references/snipsnap-midi-state.md` (header date and commit; `MidiWire` and `PadEngine` rows; §5 gaps 2, 7, 9 unchanged, strike the fingerprint and stop-drop items where they appear or add them struck; update the §7 grep set's `sealed interface Event` line to `-A 30`)
- Modify: `.claude/skills/midiwright/references/roadmap.md` (P0 rows 1 and 3 and the T-items the review named: mark what landed with the commit hash, the way the file already marks P0.1 and P0.2)

- [ ] **Step 1: Write the As built**

Under "As built: batch A (2026-10-23)" in `MIDI_GAPS_2026_10.md`: one bullet per task with its commit hash (`git log --format='%h %s' -5`), what it proves (the test names), and the three things disclosed: the fit is held for S1, `frameOffset` is still 0 from Kotlin, the stop guard has no JVM test. Then "Heard: nothing; this session had no phone."

- [ ] **Step 2: The other four files**

As listed. Keep each edit to the facts; no new prose sections beyond the README paragraph.

- [ ] **Step 3: Check the links resolve**

Run: `for f in docs/MIDI_SYNC.md docs/MIDI_REVIEW_2026_10.md docs/MIDI_BLE_SPEC_2026_10.md docs/MIDI_GAPS_2026_10.md; do test -f "$f" && echo "ok $f"; done; grep -c "MIDI_" docs/README.md`
Expected: four `ok` lines and a count of at least 4.

- [ ] **Step 4: Commit**

Title: `Record MIDI batch A, and point the docs index at the MIDI notes`. Body: what the batch landed and what it left, in one paragraph; the skill references updated so the ground truth matches the tree.
