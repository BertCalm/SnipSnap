# MIDI batch C Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three pieces that need no controller and no phone to build: the parser reads pitch bend and pressure (controller plan step 3); PLAY remembers its pad map per connected device (step 2); and a CLOCK CHECK on the MIDI screen gives the owner a way to answer bench S1.

**Architecture:** Parsing in `:kit`, rules and analysis in `:shell` with JVM tests, Android glue in `:app` proved by compile, one UI-thread native read in `app/src/main/cpp` with a host test. Nothing on the audio thread changes.

**Tech Stack:** Kotlin 2.0.21, `kotlin.test`, C++17 with `check.h`, Oboe 1.9.0, Compose.

**Spec:** `docs/MIDI_CLOCK_PROBE_SPEC_2026_10.md` (binding for Tasks 3 to 5) and `docs/MIDI_CONTROLLER_PLAN_2026_10.md` section 3 steps 2 and 3 (binding for Tasks 1 and 2).

## Global Constraints

- **One Gradle invocation at a time** in this worktree. Never start a second while one runs; a concurrent pair has corrupted the Kotlin daemon and broken builds three times.
- **Realtime rule.** Nothing in `PadEngine::onAudioReady` or what it calls allocates, locks, logs or calls JNI. `getTimestamp` is never called from the callback.
- **Clock domain.** `System.nanoTime()` / `CLOCK_MONOTONIC` nanoseconds everywhere.
- **Never hardcode a sample rate.** Use `PadEngine.sampleRate()`.
- **House split.** Rules and analysis in `:kit`/`:shell` with tests; `:app` proved by `./gradlew --offline -q :app:compileDebugKotlin`.
- **Copy.** Every user-visible string is a `Copy` constant in `shell/.../Personality.kt`; labels go in `PersonalityTest`'s `notASentence` set with a comment.
- **No thru.** Nothing that arrived over MIDI is re-sent. Do not touch `MidiOut` in this batch.
- **Naming rule.** No maker names or model numbers in event, engine or preset names (test fixtures and profile docs may name devices).
- **Commit style.** Plain declarative title, no prefix; a body saying why and what was left out; end with exactly:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_011mdfbYzrdmCr1B8F6bq2vp
  ```
- **Proofs:** `./gradlew --offline -q :kit:test` / `:shell:test` (one Gradle); `:app:compileDebugKotlin`; native: `python3 scripts/check_native_sources.py && cmake -S app/src/main/cpp/test -B build/native-tests > /dev/null && cmake --build build/native-tests 2>&1 | tail -3 && ctest --test-dir build/native-tests --output-on-failure 2>&1 | tail -5`.

## Review Focus

1. Pitch bend arrives LSB first: `E0 00 40` is centre (0), `E0 7F 7F` is +8191, `E0 00 00` is -8192. A swapped byte order passes a round-number test and fails these. (Task 1.)
2. The new expression events stream constantly from a pressure pad or touch strip; until something consumes them they must not be posted to the main thread one lambda per byte. (Task 1.)
3. A restored pad map that differs from the current one must panic first, or a note held under the old map releases on the wrong slot and sticks. (Task 2.)
4. A probe started and the screen left mid-check must close its engine. (Task 5.)
5. A route change mid-check (headphones in or out) must stop the check and say so, not compute across it. (Task 5.)

---

### Task 1: Pitch bend and pressure in `MidiWire`

**Files:** `kit/src/main/kotlin/com/snipsnap/kit/MidiWire.kt`, `kit/src/test/kotlin/com/snipsnap/kit/MidiWireTest.kt`, `.claude/skills/midiwright/scripts/wire_fixtures.py`, `shell/src/main/kotlin/com/snipsnap/shell/MidiPads.kt`, `shell/src/main/kotlin/com/snipsnap/shell/MidiKeys.kt`, `app/src/main/kotlin/com/snipsnap/app/MidiInput.kt`, `app/src/main/kotlin/com/snipsnap/app/SnipSnapMidiService.kt`.

**Interfaces — produces:**
```kotlin
/** Bend and pressure: continuous expression on one channel. Nothing plays them yet. */
sealed interface Expression : Channel
/** 14-bit bend, LSB first on the wire, centred: -8192..8191, 0 is centre. */
data class PitchBend(override val channel: Int, val value: Int, override val timestampNanos: Long = 0L) : Expression
/** Channel pressure (D0): 0..127. */
data class ChannelPressure(override val channel: Int, val value: Int, override val timestampNanos: Long = 0L) : Expression
/** Polyphonic key pressure (A0): one note's pressure, 0..127. */
data class PolyPressure(override val channel: Int, val note: Int, val value: Int, override val timestampNanos: Long = 0L) : Expression
```
Program change stays parsed and dropped. Constants `PITCH_BEND = 0xE0`, `POLY_PRESSURE = 0xA0` beside the existing ones; `CHANNEL_PRESSURE` becomes public.

- [ ] **Step 1:** In `wire_fixtures.py`, make `RefWire` emit `("PitchBend", ch, value)`, `("ChannelPressure", ch, value)`, `("PolyPressure", ch, note, value)` (match the tuple shape the file uses; stamps as it already does), update the docstring line that says bend and aftertouch are dropped, and rewrite the three existing fixtures `pitch_bend_dropped_length_honoured`, the channel-pressure one and `poly_aftertouch_two_bytes` to expect the new events before the note. Add: `bend_lsb_first` (`E0 00 40` → 0, `E0 7F 7F` → 8191, `E0 00 00` → -8192), `bend_running_status` (`E3 00 40 10 40` → two bends on channel 3), `bend_split_across_deliveries` (`[[0xE0], [0x00], [0x50]]`), `clock_inside_bend` (`E0 00 F8 40`). Run `python3 .claude/skills/midiwright/scripts/wire_fixtures.py > /dev/null`; expect exit 0.
- [ ] **Step 2:** Failing Kotlin tests in `MidiWireTest`, matching its style: the four LSB-first values as above; running status across bends; a bend split across three deliveries stamped by the last; a clock between a bend's two data bytes (both events, bend intact); `D5 40` → `ChannelPressure(5, 64)`; `A2 3C 7F` → `PolyPressure(2, 60, 127)`; program change `C0 05` still produces nothing and the next note is read. Amend any existing test that asserted bend/pressure produce nothing. Run `:kit:test --tests 'com.snipsnap.kit.MidiWireTest'`; expect unresolved references.
- [ ] **Step 3:** Implement in `complete()`. `PitchBend.value = ((second shl 7) or first) - 8192` where `first` is the LSB data byte. Update the KDoc's "what comes out" sentence.
- [ ] **Step 4:** `MidiPads.handle` and `MidiKeys.handle` gain `is MidiWire.Event.Expression -> {}` with a one-line comment (step 5 of the controller plan gives bend and mod a voice; until then nothing here plays them). No `else`.
- [ ] **Step 5:** One shared filter so nothing unconsumed crosses to the main thread: in `MidiInput.kt` add `internal fun MidiWire.Event.reachesScreens(): Boolean = this !is MidiWire.Event.RealTime && this !is MidiWire.Event.Expression` with a KDoc (the clock belongs to the future follower; expression to MIDI learn, step 4, which removes the second clause); replace the two `filter { it !is MidiWire.Event.RealTime }` sites (`MidiInput.kt:~278`, `SnipSnapMidiService.kt:~38`) with `filter { it.reachesScreens() }`. The raw-byte monitor (`tapRaw`) is unaffected; confirm by reading.
- [ ] **Step 6:** `./gradlew --offline -q :kit:test :shell:test`, then `:app:compileDebugKotlin`. Commit: `Read pitch bend and pressure off the wire, and keep them off the main thread until something plays them`.

---

### Task 2: PLAY remembers its pad map per device

**Files:** create `shell/src/main/kotlin/com/snipsnap/shell/MidiPadMaps.kt` and its test; modify `app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt`.

**Interfaces — produces:**
```kotlin
object MidiPadMaps {
    /** The preferences key a device's pad map is stored under. */
    fun key(device: MidiDeviceId): String = "midi.padmap.${device.fingerprint.key}"
    /** A stored name back to a map; anything unknown is null, never a throw. */
    fun decode(raw: String?): MidiPads.Map?
    /**
     * The map to switch to when [devices] changes: the most recently connected hardware device
     * (last in [devices], skipping Transport.VIRTUAL) that has a stored map. null: keep the current one.
     */
    fun restore(devices: List<MidiDeviceId>, stored: (String) -> String?): MidiPads.Map?
    /** The keys to write when the player picks [map]: every connected hardware device. */
    fun keysToSave(devices: List<MidiDeviceId>): List<String>
}
```
"SNIPSNAP IN" (VIRTUAL) never stores or restores: another app's notes follow whatever PLAY shows.

- [ ] **Step 1:** `MidiPadMapsTest`: decode round-trips both maps by `name`; decode of `"BOGUS"` and of null is null; restore picks the last hardware device with a stored map; restore skips a VIRTUAL device even when it has a stored entry; restore returns null when nothing is stored; keysToSave excludes VIRTUAL and lists each hardware device once. Build `MidiDeviceId`s with `MidiFingerprint.of(...)`. Run; expect unresolved reference.
- [ ] **Step 2:** Implement. KDoc: why per device (the owner's 2026-10-26 decision; fingerprints per device); why last connected wins.
- [ ] **Step 3:** `KitScreen`: read the app's `PREFS` the way other screens do (grep `getSharedPreferences(PREFS` for the idiom). In `onDevicesChanged`, after updating `midiDevices`, `MidiPadMaps.restore(devices) { prefs.getString(it, null) }`; if non-null and different from `midiMap`, call `panic()` FIRST, then set `midiMap`. In the MIDI MAP chip's tap (it already panics), after `midiMap = midiMap.next()`, write `midiMap.name` under every `keysToSave(midiDevices)` key. Comment both sites with the stuck-note reason.
- [ ] **Step 4:** `:shell:test --tests 'com.snipsnap.shell.MidiPadMapsTest'`, then `:app:compileDebugKotlin`. Commit: `Remember PLAY's MIDI pad map for each controller`.

---

### Task 3: The native hardware-position read

**Files:** `app/src/main/cpp/PadEngine.{h,cpp}`, `app/src/main/cpp/jni.cpp`, `app/src/main/cpp/test/engine_tests.cpp` (and `jni_tests.cpp` if its extern block lists `NativePads` entry points), `app/src/main/kotlin/com/snipsnap/app/NativePads.kt`, `app/src/main/kotlin/com/snipsnap/app/PadEngine.kt`.

**Interfaces — produces:** exactly spec section 2.1: `bool hardwarePosition(int64_t* framePosition, int64_t* timeNanos)` (UI thread only; `stream_->getTimestamp(CLOCK_MONOTONIC)`, `ResultWithValue<FrameTimestamp>`, `.value().position`/`.value().timestamp` on `Result::OK`), `int32_t framesPerBurst() const`, `int32_t audioApi() const`; JNI `hardwarePosition(handle, LongArray(2)): Boolean`, `framesPerBurst(handle): Int`, `audioApi(handle): Int`; `PadEngine.kt` `@Synchronized` wrappers returning false/0/-1 when not open.

- [ ] **Step 1:** Failing host test: a constructed, never-started engine's `hardwarePosition` returns false and leaves the two outputs untouched (seed them with 7 and 9); `framesPerBurst()` is 0; `audioApi()` is -1. Build; expect missing members.
- [ ] **Step 2:** Implement. Comment on `hardwarePosition`: UI thread only, never from the callback (Oboe's pre-R note, minSdk 29), and that `stream_` is only reset on the UI thread so no lock is needed.
- [ ] **Step 3:** Native block passes; `:app:compileDebugKotlin`. Commit: `Read the pad engine's hardware playback position off the audio thread`.

---

### Task 4: `ClockProbe`, the analysis

**Files:** create `shell/src/main/kotlin/com/snipsnap/shell/ClockProbe.kt` and `shell/src/test/kotlin/com/snipsnap/shell/ClockProbeTest.kt`; `Copy` constants in `Personality.kt` for every string `describe` builds.

**Interfaces — produces:** exactly spec section 2.2: `ClockProbe.Sample`, `ClockProbe.Result`, `ClockProbe.Verdict`, `analyze(samples, sampleRate)`, `describe(result)`, the five proposed constants.

- [ ] **Step 1:** Write the spec's ten listed tests (synthetic generators as small private helpers in the test: an honest stream at a given rate with a fixed buffer lag and a burst-quantised hardware position; with optional frozen and failed spans). Plus `describe` tests for one line of each verdict, checked against the `Copy` constants. Run; expect unresolved reference.
- [ ] **Step 2:** Implement `analyze` per the spec's rules and order; least-squares slope in `Double`; never divide by a zero span (`TOO_SHORT` first).
- [ ] **Step 3:** `:shell:test --tests 'com.snipsnap.shell.ClockProbeTest' --tests 'com.snipsnap.shell.PersonalityTest'`. Commit: `Add ClockProbe, which tells a lying playback position from an honest one`.

---

### Task 5: CLOCK CHECK on the MIDI screen

**Files:** `app/src/main/kotlin/com/snipsnap/app/ui/MidiScreen.kt`, `shell/src/main/kotlin/com/snipsnap/shell/MidiReport.kt` (+ its test), `Personality.kt` (the button and three message constants).

**Interfaces — consumes:** Task 3's `PadEngine` reads, Task 4's `ClockProbe`. **Produces:** `MidiReport.text(...)` gains an optional `clock: ClockReport?` parameter (a small data class in `MidiReport.kt` holding the describe line, the `Result`, and the context fields from spec 2.3 step 6) rendered as a CLOCK section when present.

- [ ] **Step 1:** `MidiReportTest`: with a `ClockReport` the text has a CLOCK section containing the line, the counts, `sampleRate`, shared/exclusive, burst, API word, route word, model and SDK, and the "Answers S1 for the pad engine's Oboe stream…" sentence; without one there is no CLOCK section. Run; expect failure.
- [ ] **Step 2:** Implement the report section.
- [ ] **Step 3:** `MidiScreen`: the button and flow exactly as spec 2.3 steps 1 to 5 (engine constructed and started on tap at the preferred rate the way `PadHost` constructs one; `loadSnips(listOf(DrumSynth.click(engine.sampleRate(), accent = false)))` and `hitLayers` on sample 0 once a second; 6 s of `withFrameNanos` sampling; `needsRestart()` aborts with `Copy.MIDI_CLOCK_ROUTE_CHANGED`; `close()` at the end and in `onDispose`; the `isShared()` warning line). While running, the button reads `Copy.MIDI_CLOCK_RUNNING` and does nothing on tap. The output route word from `AudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)`: the first of `TYPE_BLUETOOTH_A2DP`, `TYPE_WIRED_HEADPHONES`/`TYPE_WIRED_HEADSET`, `TYPE_USB_HEADSET`/`TYPE_USB_DEVICE` present, else speaker. Pass the `ClockReport` into COPY REPORT.
- [ ] **Step 4:** `:shell:test --tests 'com.snipsnap.shell.MidiReportTest' --tests 'com.snipsnap.shell.PersonalityTest' --tests 'com.snipsnap.shell.ConventionTest'`, then `:app:compileDebugKotlin`. Commit: `Give the MIDI screen a CLOCK CHECK that answers bench S1`.

---

### Task 6: Record batch C

**Files:** `docs/BENCH.md` (S1's "How, now" paragraph per spec 2.4, the pause lifted for S1 only), `docs/MIDI_CLOCK_PROBE_SPEC_2026_10.md` (append `## 6. As built` with hashes and proofs; "Measured on device: nothing"), `docs/MIDI_CONTROLLER_PLAN_2026_10.md` (steps 2 and 3 marked landed with hashes, in the style step 1 uses; step 6's `MidiOutput.kt` corrected to `MidiOut.kt`), `docs/MIDI_GAPS_2026_10.md` (one As built line for batch C), `docs/README.md` (the probe spec in the **MIDI and timing.** paragraph), `.claude/skills/midiwright/references/snipsnap-midi-state.md` (`MidiWire` row: bend and pressure; a `ClockProbe` row; header commit).

- [ ] **Step 1:** Write. Every hash appears in `git log --format=%h origin/claude/mobile-mpc-drum-sampler-t58x74..HEAD`; every test count from `grep -c @Test`.
- [ ] **Step 2:** Commit: `Record MIDI batch C, and tell S1 how to answer itself`.
