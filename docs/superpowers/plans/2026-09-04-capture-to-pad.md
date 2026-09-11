# Capture to Pad — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the whole "GRAB the last sound from the ring straight onto a pad" loop on an empty grid: a BLANK kit, long-pressable empty pads, and a meter-forward capture surface that grabs → trims → cleans → assigns to the slot.

**Architecture:** Reuse proven parts. A new `StarterKits.Starter` renders an empty kit through the existing render/menu/fresh path. A new pure DSP function `PadCapture.grabOneShot` (JVM-testable, in `:audio`) turns a raw ring snapshot into a clean one-shot `Snip`. `MicSessionService` exposes the live ring for on-demand snapshotting. A new full-screen overlay `PadCaptureScreen` (same App-level-state pattern as `PadSheetScreen`) shows the live level meter, arms the mic if needed, and on GRAB runs the DSP + the existing single-slot assign (`KitBuilderModel.assign`/`.save`, exactly as `SynthScreen`'s SEND TO PAD does) then hands the new kit back via `onKitUpdated`.

**Tech Stack:** Kotlin, Jetpack Compose, Gradle multi-module (`:audio` / `:shell` / `:kit` pure-JVM, `:app` Android). `kotlin.test` + JUnit5.

## Global Constraints

- **Environment:** `eval "$(fnm env)" && fnm use 20` before any gradle command.
- **Sample rate is 44_100 everywhere.** `WavWriter.write` and `KitBuilderModel.assign` reject any `Snip.sampleRate != 44_100` (no `allowNonMpcRate`). `MicSessionService.SAMPLE_RATE` is already 44_100, so ring snapshots are at 44_100. Every `Snip` built in this plan uses `sampleRate = 44_100`, `channels = 1`.
- **Audio type is `Snip(samples: FloatArray, channels: Int, sampleRate: Int)`** (declared in `audio/.../Cleanup.kt:12`). `CaptureRing.snapshot(frames)` returns a raw mono `FloatArray` — wrap it in `Snip(raw, 1, 44_100)`.
- **`:app` has NO JVM test source** (excluded from the JVM-only build; inventory §9). `:app` tasks gate on `./gradlew :app:compileDebugKotlin` + reviewer inspection — NOT unit tests. Only `:audio`/`:shell`/`:kit` tasks get TDD.
- **Reload is identity-based:** a pad change only reaches `PadPlayer` if the final step calls the surface's `onKitUpdated(updatedKit)` so App does `open = open?.copy(kit = updatedKit)`. Writing files alone is not enough.
- **The single-slot assign template is `SynthScreen`'s SEND TO PAD (`app/.../ui/SynthScreen.kt:249-281`)**: `KitBuilderModel.open(dir)` → `assign(slot, snip, drumClass, displayName)` → `save()` → read `model.kit` → `onKitUpdated`. All on `Dispatchers.IO`.
- **Concurrent-session repo:** `git add` ONLY the files each task changes. Never `-A`/`.`. Do not touch `FOLLOWUP-REPORT.md` or `spike/`.
- **Commit trailers (every commit):**
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MDVos8CfKQ4ewvwrcNUhrS
  ```
- **Do not touch** the TAPE jank/phase code, audio timing, or `TapeVoice`.

---

## File Structure

- `shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt` — **modify**: add a BLANK starter (Task 1).
- `audio/src/main/kotlin/com/snipsnap/audio/PadCapture.kt` — **create**: pure `grabOneShot` DSP (Task 3).
- `app/src/main/kotlin/com/snipsnap/app/MicSessionService.kt` — **modify**: publish the live ring + `snapshotTail` (Task 2).
- `app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt` — **modify**: long-press an empty pad (Task 4).
- `app/src/main/kotlin/com/snipsnap/app/ui/PadCaptureScreen.kt` — **create**: the capture surface (Task 5).
- `app/src/main/kotlin/com/snipsnap/app/App.kt` — **modify**: overlay state + when-branch + wiring (Task 5).
- Tests: `shell/src/test/kotlin/com/snipsnap/shell/BlankStarterTest.kt` (Task 1), `audio/src/test/kotlin/com/snipsnap/audio/PadCaptureTest.kt` (Task 3).

---

## Task 1: BLANK starter (empty grid)

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt` (add one `Starter` to `ALL`, lines 53-94)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/BlankStarterTest.kt`

**Interfaces:**
- Consumes: `Starter(id, displayName, blurb, seeded, key, build)` constructor; `Starter.render(name, dir, seed): Kit` (calls `KitAssembler.assembleArranged(name, build(seed), dir, key)`), which tolerates an empty `arranged` list and produces `Kit(name, emptyList())` + saves `kit.json`.
- Produces: a new member `StarterKits.blank` in `ALL` with `id = "blank"`, rendering a kit whose `pads.isEmpty()`. The FRESH menu (`KitsScreen.StarterMenu`) iterates `StarterKits.ALL`, so this appears as a row automatically — no UI change needed.

- [ ] **Step 1: Write the failing test**

```kotlin
// shell/src/test/kotlin/com/snipsnap/shell/BlankStarterTest.kt
package com.snipsnap.shell

import com.snipsnap.kit.KitStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlankStarterTest {
    @Test
    fun `the blank starter renders an empty, saved kit`() {
        val blank = StarterKits.byId("blank")
        assertNotNull(blank, "expected a 'blank' starter in StarterKits.ALL")
        assertTrue(!blank.seeded, "blank has nothing to reroll")

        val dir = Files.createTempDirectory("blank-kit").toFile()
        val kit = blank.render("BLANK TEST", dir, seed = 0)
        assertTrue(kit.pads.isEmpty(), "a blank kit has no pads")
        // assembleArranged persists kit.json itself
        assertTrue(File(dir, "kit.json").exists(), "kit.json written")
        assertEquals(kit.name, KitStore.load(dir).name)
        assertTrue(KitStore.load(dir).pads.isEmpty())
    }

    @Test
    fun `blank is discoverable in the FRESH menu list`() {
        assertTrue(StarterKits.ALL.any { it.id == "blank" }, "blank must be in ALL so the StarterMenu shows it")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `eval "$(fnm env)" && fnm use 20 && ./gradlew :shell:test --tests "com.snipsnap.shell.BlankStarterTest"`
Expected: FAIL — `byId("blank")` returns null.

- [ ] **Step 3: Add the blank starter**

In `StarterKits.kt`, add as the FIRST entry of `ALL` (so it leads the FRESH menu — capture-first users land on it), matching the existing entries' style:

```kotlin
val blank = Starter(
    id = "blank",
    displayName = "BLANK",
    blurb = "Empty grid — capture straight onto the pads.",
    seeded = false,
    build = { emptyList() },
)
```
Then include `blank` first in the `ALL` list literal (before `factory`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.BlankStarterTest"`
Expected: PASS. Also run full `:shell:test` to confirm no regression in the ALL-dependent tests.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt shell/src/test/kotlin/com/snipsnap/shell/BlankStarterTest.kt
git commit  # message: "feat(kit): BLANK starter — an empty grid to capture into"
```

---

## Task 2: Expose the live ring for on-demand snapshotting

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/MicSessionService.kt` (companion object ~354-399; the reader-thread setup that creates `newRing`; the disarm path)

**Interfaces:**
- Consumes: the existing `CaptureRing` created per arm session in the reader setup (the `newRing` local), and `SAMPLE_RATE = 44_100`.
- Produces: companion API
  ```kotlin
  fun snapshotTail(frames: Int): FloatArray?   // newest `frames` mono samples, or null if not armed / ring empty
  const val SAMPLE_RATE: Int                    // already exists (44_100) — expose/confirm it's companion-visible
  ```
  `snapshotTail` returns `null` when nothing is armed so callers can toast "not listening yet."

**No :app unit test exists** — gate is compile + review.

- [ ] **Step 1: Publish the active ring**

In the companion object, alongside `_armed`/`_level`, add:
```kotlin
// The reader session's ring, exposed so an in-app capture surface can pull
// the last N frames on demand (GRAB to a pad) without committing a 60s snip
// file. Set when a session's reader starts, cleared on disarm. Volatile: the
// UI thread reads it while the reader thread wrote it. CaptureRing.snapshot is
// single-consumer-safe (documented tearing only at the oldest edge).
@Volatile private var activeRing: CaptureRing? = null

/** Newest [frames] mono samples from the live session, or null if not armed. */
fun snapshotTail(frames: Int): FloatArray? {
    val ring = activeRing ?: return null
    if (frames <= 0) return null
    val out = ring.snapshot(frames)
    return if (out.isEmpty()) null else out
}
```

- [ ] **Step 2: Set/clear activeRing**

Where the reader thread is set up and `newRing` is created (the same place `ring = newRing` / reader thread starts), also set `activeRing = newRing`. In the disarm/teardown path (where `_armed.value = false` and `_level.value = 0f` are set), add `activeRing = null`. (If `SAMPLE_RATE` is currently `private` in the companion, widen it to `internal`/public `const val` so Task 5 can build a `Snip` at the right rate — confirm and adjust.)

- [ ] **Step 3: Compile**

Run: `eval "$(fnm env)" && fnm use 20 && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/MicSessionService.kt
git commit  # message: "feat(capture): expose live ring via snapshotTail for pad grab"
```

---

## Task 3: `PadCapture.grabOneShot` — raw snapshot → clean one-shot

**Files:**
- Create: `audio/src/main/kotlin/com/snipsnap/audio/PadCapture.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/PadCaptureTest.kt`

**Interfaces:**
- Consumes: `Snip(samples, channels, sampleRate)`; `Transients.detect(snip, config): List<Onset>` where `Onset(frame, strength)`; `Transients.zeroCrossingBefore(snip, frame, searchFrames): Int`; `Cleanup.process(snip, config): Snip` with `CleanupConfig(trimSilence, normalize, ...)`.
- Produces:
  ```kotlin
  object PadCapture {
      const val MIN_ONESHOT_FRAMES = 1_764   // 40ms @ 44.1k — below this, "nothing to grab"
      fun grabOneShot(raw: FloatArray, sampleRate: Int): Snip?
  }
  ```
  Returns a trimmed, cleaned mono one-shot of the LAST hit in `raw`, or `null` if the result is shorter than `MIN_ONESHOT_FRAMES` (silence / no real hit). Deterministic — no randomness, JVM-testable.

**Design:** wrap `raw` as a mono `Snip`; find the last onset; cut from the zero-crossing just before it to the end; run the commit-time `Cleanup` chain (trims trailing silence, normalizes, fades); reject if too short.

- [ ] **Step 1: Write the failing test**

```kotlin
// audio/src/test/kotlin/com/snipsnap/audio/PadCaptureTest.kt
package com.snipsnap.audio

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PadCaptureTest {
    private fun silence(frames: Int) = FloatArray(frames)

    // A short decaying burst starting at `at`, on a bed of silence.
    private fun hitAt(total: Int, at: Int, lenFrames: Int = 4_410): FloatArray {
        val buf = FloatArray(total)
        for (i in 0 until lenFrames) {
            val idx = at + i
            if (idx >= total) break
            val env = 1f - i.toFloat() / lenFrames
            buf[idx] = (0.8f * env * sin(2.0 * Math.PI * 180.0 * i / 44_100.0)).toFloat()
        }
        return buf
    }

    @Test
    fun `silence yields null — nothing to grab`() {
        assertNull(PadCapture.grabOneShot(silence(44_100), 44_100))
    }

    @Test
    fun `a hit near the end is grabbed as a short one-shot`() {
        // 2s of mostly silence, a hit starting at 1.5s
        val raw = hitAt(total = 88_200, at = 66_150)
        val snip = PadCapture.grabOneShot(raw, 44_100)
        assertNotNull(snip)
        assertEquals(1, snip.channels)
        assertEquals(44_100, snip.sampleRate)
        // Trimmed to roughly the hit, not the whole 2s.
        assertTrue(snip.frameCount in PadCapture.MIN_ONESHOT_FRAMES..(6_000 + 2_000),
            "expected a short one-shot, got ${snip.frameCount} frames")
        // Normalized: peak near full-scale.
        assertTrue(snip.peak() > 0.9f, "expected normalized peak, got ${snip.peak()}")
    }

    @Test
    fun `the last of two hits is the one grabbed`() {
        val raw = hitAt(total = 132_300, at = 22_050)          // hit at 0.5s
            .also { first -> hitAt(total = 132_300, at = 110_250).forEachIndexed { i, v -> if (v != 0f) first[i] = v } } // hit at 2.5s
        val snip = PadCapture.grabOneShot(raw, 44_100)
        assertNotNull(snip)
        // Only the last hit's worth of audio, far shorter than 2.5s of lead-in.
        assertTrue(snip.frameCount < 22_050, "should grab the last hit, not from the first")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `eval "$(fnm env)" && fnm use 20 && ./gradlew :audio:test --tests "com.snipsnap.audio.PadCaptureTest"`
Expected: FAIL — `PadCapture` unresolved.

- [ ] **Step 3: Implement**

```kotlin
// audio/src/main/kotlin/com/snipsnap/audio/PadCapture.kt
package com.snipsnap.audio

/**
 * Turns a raw ring snapshot (mono, newest samples) into a clean one-shot of
 * the LAST hit — the "GRAB" gesture's DSP. Pure and deterministic.
 */
object PadCapture {
    /** Shorter than this after trimming ⇒ treat as silence / no real hit. */
    const val MIN_ONESHOT_FRAMES = 1_764   // 40ms @ 44.1k

    fun grabOneShot(raw: FloatArray, sampleRate: Int): Snip? {
        if (raw.isEmpty()) return null
        val full = Snip(raw, channels = 1, sampleRate = sampleRate)

        // Where does the last hit start? Fall back to the whole buffer's start
        // if no clear onset (Cleanup's silence trim still tightens it).
        val onsets = Transients.detect(full)
        val startRaw = onsets.lastOrNull()?.frame ?: 0
        val start = Transients.zeroCrossingBefore(full, startRaw).coerceIn(0, raw.size)

        val sliced = raw.copyOfRange(start, raw.size)
        if (sliced.isEmpty()) return null

        // Commit-time chain: DC-offset → trim silence → normalize → fades.
        val cleaned = Cleanup.process(Snip(sliced, channels = 1, sampleRate = sampleRate))
        return if (cleaned.frameCount < MIN_ONESHOT_FRAMES) null else cleaned
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :audio:test --tests "com.snipsnap.audio.PadCaptureTest"`
Expected: PASS. If the "last of two hits" assertion is flaky against `Transients` defaults, adjust the synthetic hit amplitudes/spacing so the second onset is unambiguously detected — do NOT loosen the assertion to meaninglessness. Run full `:audio:test` for no regressions.

- [ ] **Step 5: Commit**

```bash
git add audio/src/main/kotlin/com/snipsnap/audio/PadCapture.kt audio/src/test/kotlin/com/snipsnap/audio/PadCaptureTest.kt
git commit  # message: "feat(audio): grabOneShot — trim a ring snapshot to the last hit"
```

---

## Task 4: Long-press an empty pad to capture

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt` (empty-slot `Box`, lines 172-182; the `PadCell`/grid signatures that thread callbacks)

**Interfaces:**
- Consumes: nothing new.
- Produces: a new callback surfaced from `KitScreen` up to its caller:
  ```kotlin
  onEmptyLongPress: (slot: Int) -> Unit    // long-press on a blank slot
  ```
  Task 5 wires it in App to open the capture surface for `slot`.

**No :app unit test** — gate is compile + review.

- [ ] **Step 1: Add the callback + wire the empty slot**

Add `onEmptyLongPress: (Int) -> Unit` to `KitScreen`'s parameters and thread it to the cell composable. In the empty-slot branch (currently a dead `Box`, lines 172-182), add a long-press `pointerInput` that fires `onEmptyLongPress(slot)` after `LONG_PRESS_MS` (reuse the same 480ms constant the filled path uses). A tap on an empty pad should give a discoverability hint rather than nothing — fire an `onToast`-style hint "LONG-PRESS TO CAPTURE" on a short tap (thread an `onEmptyTapHint: (Int) -> Unit` or reuse the existing toast path already available to KitScreen; if KitScreen has no toast hook, add `onEmptyTapHint` and wire it in Task 5 to `onToast`). Keep the filled-pad `pointerInput` (lines 201-222) unchanged.

Concrete shape for the empty `Box` (mirror the filled path's gesture loop, simplified — no glow needed):
```kotlin
if (pad == null) {
    Box(
        modifier
            .height(Layout.PAD_H.dp)
            .raisedBevel(scheme, Layout.PAD_RADIUS.dp)
            .pointerInput(slot) {
                while (true) {
                    val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                    val longPress = scope.launch { delay(LONG_PRESS_MS); onEmptyLongPress(slot) }
                    var fired = false
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                        }
                    }
                    if (longPress.isActive) { longPress.cancel(); onEmptyTapHint(slot) }
                }
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape.copy(alpha = 0.6f), Modifier.padding(4.dp))
    }
    return
}
```
(Confirm `scope`, `awaitFirstDown`, `LONG_PRESS_MS` are in scope where the filled path already uses them; import to match.)

- [ ] **Step 2: Compile**

Run: `eval "$(fnm env)" && fnm use 20 && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Callers in App.kt won't yet pass `onEmptyLongPress` — give the new params NO default so the compiler forces Task 5 to wire them; if that blocks compile because App.kt's `KitScreen(...)` call lacks them, add a temporary `onEmptyLongPress = {}` / `onEmptyTapHint = {}` at the call site and note it for Task 5 to replace. Prefer wiring App.kt in this same task if trivial.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt app/src/main/kotlin/com/snipsnap/app/App.kt
git commit  # message: "feat(kit): long-press an empty pad to capture into it"
```

---

## Task 5: The pad-capture surface + App wiring

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/PadCaptureScreen.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (overlay state, `MenuRow.onSelect` clear-list, the `AppScreen.KIT` `when`-branch, wiring `onEmptyLongPress`)

**Interfaces:**
- Consumes: `MicSessionService.armed: StateFlow<Boolean>`, `MicSessionService.level: StateFlow<Float>`, `MicSessionService.snapshotTail(frames): FloatArray?`, `MicSessionService.SAMPLE_RATE`; `PadCapture.grabOneShot(raw, sampleRate): Snip?`; `Classifier.classify(snip): Classification` (`.drumClass`); `KitBuilderModel.open(dir)` → `assign(slot, snip, drumClass, displayName)` → `save()` → `.kit`; App's `requestArm()`.
- Produces: `PadCaptureScreen(entry, slot, armed, level, onRequestArm, onBack, onToast, onKitUpdated, appScope)` and the App overlay state `padCaptureSlot: Int?`.

**No :app unit test** — gate is compile + review. The GRAB pipeline's *logic* is already tested in Task 3; this task is UI wiring.

- [ ] **Step 1: Write `PadCaptureScreen`**

```kotlin
// app/src/main/kotlin/com/snipsnap/app/ui/PadCaptureScreen.kt
package com.snipsnap.app.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.snipsnap.app.MicSessionService
import com.snipsnap.audio.PadCapture
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Classifier
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.KitShelf
import com.snipsnap.kit.Kit

private const val GRAB_SECONDS = 2
private val GRAB_FRAMES get() = GRAB_SECONDS * MicSessionService.SAMPLE_RATE

@Composable
fun PadCaptureScreen(
    entry: KitShelf.Entry,
    slot: Int,
    armed: Boolean,
    level: Float,
    onRequestArm: () -> Unit,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
    appScope: CoroutineScope,
) {
    var grabbing by remember { mutableStateOf(false) }
    // Layout: title "PAD <slot>", the live meter + elapsed indicator when armed
    // (reuse the same visual language as the ARM control's RecordingIndicator —
    // extract a shared composable if cheap, else mirror it), a GRAB button, and
    // a back affordance. When NOT armed, show a "START MIC" button calling
    // onRequestArm and a one-line "grab the sound that just happened" hint.
    // Use Scheme tokens + the tape aesthetic; do NOT invent new color constants.

    fun grab() {
        if (grabbing) return
        val raw = MicSessionService.snapshotTail(GRAB_FRAMES)
        if (raw == null) { onToast("NOT LISTENING YET"); return }
        grabbing = true
        appScope.launch {
            val updated: Kit? = withContext(Dispatchers.IO) {
                val snip: Snip = PadCapture.grabOneShot(raw, MicSessionService.SAMPLE_RATE) ?: return@withContext null
                val cls = Classifier.classify(snip).drumClass
                val model = KitBuilderModel.open(entry.dir)
                model.assign(slot, snip, cls, "%s".format(cls))   // e.g. "KICK"; refine naming later
                model.save()
                model.kit
            }
            grabbing = false
            if (updated == null) { onToast("NOTHING TO GRAB YET") }
            else { onKitUpdated(updated); onToast("GRABBED → PAD $slot"); onBack() }
        }
    }

    // ... Compose layout invoking grab() from the GRAB button, onRequestArm from
    // "START MIC", onBack from the back chip. Gate GRAB's enabled state on `armed`
    // && !grabbing.
}
```
Build the actual Compose layout to match the app's other screens (look at `PadSheetScreen`/`KitsScreen` for the full-screen scaffold, `raisedBevel`/`lcdPanel`, `TapeText`, `DeckButton`-style buttons). Reuse the ARM control's meter+elapsed indicator (in `KitsScreen`) — extract it to a shared composable if that's clean, otherwise replicate its structure. Keep copy plain (`GRAB`, `START MIC`, `PAD n`).

- [ ] **Step 2: Wire App state + branch**

In `App.kt`: add `var padCaptureSlot by remember { mutableStateOf<Int?>(null) }`; add it to `MenuRow.onSelect`'s clear list (alongside `padSheetSlot`/`takesBinOpen`); in the `AppScreen.KIT` `when`, add a branch BEFORE the `else -> KitScreen(...)`:
```kotlin
padCaptureSlot != null && sheetEntry != null -> {
    val armed by MicSessionService.armed.collectAsState()
    val level by MicSessionService.level.collectAsState()
    PadCaptureScreen(
        entry = sheetEntry,
        slot = padCaptureSlot!!,
        armed = armed,
        level = level,
        onRequestArm = ::requestArm,
        onBack = { padCaptureSlot = null },
        onToast = { toast = it },
        onKitUpdated = { updatedKit ->
            open = open?.copy(kit = updatedKit)
            scope.launch { kits = withContext(Dispatchers.IO) { shelf.list() } }
        },
        appScope = scope,
    )
}
```
Wire `KitScreen(...)`'s `onEmptyLongPress = { slot -> padCaptureSlot = slot }` and `onEmptyTapHint = { toast = Copy.<plain hint or a literal "LONG-PRESS TO CAPTURE"> }`, replacing any temporary stubs from Task 4.

- [ ] **Step 3: Compile**

Run: `eval "$(fnm env)" && fnm use 20 && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Reason through the flow (no device here)**

Confirm by inspection: BLANK kit → open → long-press empty pad → (if unarmed) START MIC → arm → meter moves → GRAB → `snapshotTail` → `grabOneShot` → `assign`/`save` → `onKitUpdated` → `open.copy(kit=…)` → `LaunchedEffect(entry.kit)` reloads `PadPlayer` → pad shows filled + plays. Confirm GRAB is disabled while `!armed` or `grabbing`, and both null-paths (`snapshotTail==null`, `grabOneShot==null`) toast and keep the surface open.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/ui/PadCaptureScreen.kt app/src/main/kotlin/com/snipsnap/app/App.kt
git commit  # message: "feat(capture): pad-capture surface — GRAB the last hit onto a pad"
```

---

## Self-Review (author checklist — done)

**Spec coverage (Phase 1 of `2026-09-04-capture-to-pad.md`):** BLANK starter → Task 1 ✓. Empty-pad long-press → Task 4 ✓. Capture surface (meter + GRAB) → Task 5 ✓. Single-slot assign → Task 5 via `KitBuilderModel.assign` ✓. Arm-from-pad → Task 5 (`onRequestArm`/`requestArm`) ✓. GRAB DSP (trim last hit + cleanup) → Task 3 ✓. Reload → Task 5 `onKitUpdated` ✓. HOLD-TO-REC is explicitly Phase 2 (out of this plan) ✓. CHOP kit-gate is a separate sibling fix (not required for the one-sound→one-pad path) — not in this plan.

**Type consistency:** `Snip(FloatArray, Int, Int)`, `grabOneShot(FloatArray, Int): Snip?`, `snapshotTail(Int): FloatArray?`, `assign(slot, snip, drumClass, displayName)`, `onKitUpdated(Kit)` — consistent across tasks. Sample rate 44_100 throughout.

**Placeholder scan:** Task 5's Compose layout is described structurally rather than pixel-complete (the surface's exact visual arrangement is genuine UI-design latitude, bounded by "match PadSheetScreen scaffold + reuse the ARM meter"); the load-bearing logic (grab pipeline, assign, wiring, null-paths) is concrete. Acceptable — the reviewer gates the visual against the app's existing screens.
