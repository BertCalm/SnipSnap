# Retroactive Snip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Arm a mic session and the app holds the last 60 seconds in memory; press SNIP after the sound happened and it lands in TAPE — the capture feature, with the arrow of time pointed the right way.

**Architecture:** A lock-free, allocation-free-on-write ring in `:audio` (pure JVM, fully tested) fed by a plain reader thread over `AudioRecord` in a microphone-type foreground service. Snips snapshot the ring, run the commit-time `Cleanup` chain, and land as WAVs that TAPE prefers over every other source. The Bubble overlay comes last — the feature must be whole from the notification alone first.

**Tech Stack:** Kotlin 2.0.21 / JVM 17; `AudioRecord` (float, mono, 44.1k); foreground service type `microphone`; Compose foundation-only UI; no MediaProjection anywhere.

**Spec:** `docs/superpowers/specs/2026-09-04-retroactive-snip.md` — read it first, especially "Settled by existing artifacts". **Research:** `docs/CAPTURE_RESEARCH_2026.md` (why mic, and the UNPROCESSED→VOICE_RECOGNITION rule).

## Global Constraints

- **Privacy spine:** the ring lives in memory only; nothing touches disk until SNIP; disarm drops the ring. The UI says so plainly.
- **Ring length is 60 s** — `Copy.SNIPPED = "SNIP! LAST 60s KEPT."` (Personality.kt:59) shipped long ago and is the contract.
- **The write path allocates nothing and takes no locks** (ANDROID_CAPTURE.md "Real-time discipline"). The existing `RingBuffer` is the *model, not the implementation* — its own KDoc says so; leave it untouched.
- Mic source: `MediaRecorder.AudioSource.UNPROCESSED` only when `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` says so; else `VOICE_RECOGNITION` (documented no-noise-suppression guarantee). Mono, 44_100, `ENCODING_PCM_FLOAT`.
- Android 14+: manifest needs `FOREGROUND_SERVICE_MICROPHONE` and the service declares `android:foregroundServiceType="microphone"`. Start the service only from the foreground ARM action (sidesteps Android 15 background-start limits).
- Copy from `Copy` constants only: `SESSION_ARMED` (:58), `SNIPPED` (:59), `BUBBLE_EJECTED` (:60), `CAPTURE_BLOCKED`/`CAPTURE_BLOCKED_BUTTON` (:61-63) all exist. Add nothing without noting it.
- Standing UI rules (failed review on seven screens; treat as law): `.dp.toPx()` for Canvas dims; no IO on composition thread; busy+finally; rethrow `CancellationException` first; ON_STOP stops audio the *screen* owns (NOT the capture service — outliving the screen is its purpose); hit targets ≥ `Layout.MIN_HIT_TARGET`.
- Commit style: one evocative line, blank line, plain body, no prefixes. `git add` only your files — a second session shares this repo.
- Device-dependent behavior (mic loop, notification, overlay) is gated on compile + JVM tests only; the user schedules device runs. Say in each report what remains device-unverified.

## Engine surfaces consumed (verified, exact)

```kotlin
// audio/Cleanup.kt
data class Snip(val samples: FloatArray, val channels: Int, val sampleRate: Int)
Cleanup — "the commit-time cleanup chain": DC offset → trim → normalise → fades
  (read the object for the exact entry function and use it; order is load-bearing)
// audio/CaptureDoctor.kt:347
CaptureDoctor.clean(snip, denoise=false, declip=false, deverb=false): CleanReport
// audio/WavWriter.kt
WavWriter.write(file: File, snip: Snip)
// shell/Personality.kt
Copy.SESSION_ARMED, Copy.SNIPPED, Copy.BUBBLE_EJECTED
Delight.deckSoundsEnabled(level, captureArmed)  // :29 — already takes the flag
// app/ui/TapeScreen.kt:117,147 — loadLongestTape(entry); source resolution to extend
// Motion.BUBBLE_DRAG_SCALE = 1.08f (Schemes.kt) — landed weeks ago, waiting
```

---

### Task 1: `CaptureRing` — the realtime ring (pure JVM)

**Files:**
- Create: `audio/src/main/kotlin/com/snipsnap/audio/CaptureRing.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/CaptureRingTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  class CaptureRing(val capacityFrames: Int) {
      fun write(block: FloatArray, count: Int)      // SPSC producer; allocation-free, lock-free
      fun snapshot(frames: Int): FloatArray         // consumer; newest `frames`, oldest-first
      val framesWritten: Long                       // monotonic total
      val filledFrames: Int                         // min(framesWritten, capacity)
  }
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CaptureRingTest {

    @Test
    fun `a snapshot returns the newest frames oldest-first`() {
        val ring = CaptureRing(8)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f), 5)
        assertContentEquals(floatArrayOf(3f, 4f, 5f), ring.snapshot(3))
    }

    @Test
    fun `writing past capacity overwrites the oldest, seamlessly across the wrap`() {
        val ring = CaptureRing(4)
        ring.write(floatArrayOf(1f, 2f, 3f), 3)
        ring.write(floatArrayOf(4f, 5f, 6f), 3)   // 1f and 2f fall off the back
        assertContentEquals(floatArrayOf(3f, 4f, 5f, 6f), ring.snapshot(4))
    }

    @Test
    fun `asking for more than has ever been written returns only what exists`() {
        val ring = CaptureRing(100)
        ring.write(floatArrayOf(7f, 8f), 2)
        assertContentEquals(floatArrayOf(7f, 8f), ring.snapshot(50))
        assertEquals(2, ring.filledFrames)
    }

    @Test
    fun `a block larger than the whole ring keeps its newest tail`() {
        val ring = CaptureRing(3)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f), 5)
        assertContentEquals(floatArrayOf(3f, 4f, 5f), ring.snapshot(3))
    }

    @Test
    fun `sixty seconds of writes never allocates on the write path`() {
        // Behavioural proxy for the realtime rule: hammer writes with a
        // reused block and assert correctness; the no-allocation claim is
        // enforced by construction (write() contains only arraycopy and
        // index math — see its KDoc) and reviewed, not measured here.
        val ring = CaptureRing(44_100 * 60)
        val block = FloatArray(2048) { (it % 100) / 100f }
        repeat(44_100 * 60 / 2048 + 3) { ring.write(block, block.size) }
        val snap = ring.snapshot(2048)
        assertContentEquals(block.toList().takeLast(2048).toFloatArray(), snap)
        assertEquals(44_100L * 60 / 2048 * 2048 + 3 * 2048, ring.framesWritten)
    }

    @Test
    fun `a concurrent reader never sees torn counts, only possibly torn oldest samples`() {
        // SPSC contract: one writer thread, snapshots from another. We can't
        // assert sample-exactness at the moving boundary (the spec accepts
        // one block of ambiguity at the OLDEST edge) — but snapshot must
        // never throw, never return a wrong-sized array, and the NEWEST
        // half must always be internally consistent (monotonic ramp).
        val ring = CaptureRing(44_100)
        val writer = Thread {
            val block = FloatArray(441)
            var v = 0f
            repeat(2_000) {
                for (i in block.indices) { block[i] = v; v += 1f }
                ring.write(block, block.size)
            }
        }
        writer.start()
        var checks = 0
        while (writer.isAlive) {
            val snap = ring.snapshot(1_000)
            if (snap.size == 1_000) {
                val newest = snap.copyOfRange(500, 1_000)
                for (i in 1 until newest.size) {
                    val step = newest[i] - newest[i - 1]
                    check(step == 1f || step < 0f) {
                        "non-monotonic tear in the newest half at $i: $step"
                    }
                }
                checks++
            }
        }
        writer.join()
        check(checks > 0) { "reader never got a full snapshot" }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:test --tests "com.snipsnap.audio.CaptureRingTest"`
Expected: FAIL — `Unresolved reference: CaptureRing`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.snipsnap.audio

/**
 * The always-listening ring the Retroactive Snip reads from.
 *
 * Single producer (the capture thread), single consumer (the snip action).
 * The WRITE path is the realtime one and does nothing but `arraycopy` and
 * index arithmetic — no allocation, no locks, no logging — per
 * `docs/ANDROID_CAPTURE.md`'s realtime discipline. [RingBuffer] is the
 * offline model this was grown from; it stays lock-based and offline.
 *
 * Consistency contract: [framesWritten] is `@Volatile` and published
 * AFTER the samples land, so a [snapshot] reads a cursor that is never
 * ahead of real data. A write that lands DURING a snapshot can overwrite
 * the snapshot's OLDEST samples mid-copy — accepted and documented: one
 * block of ambiguity at the far edge of a 60-second window is inaudible,
 * and the alternative is a lock on the realtime path.
 */
class CaptureRing(val capacityFrames: Int) {

    private val buf = FloatArray(capacityFrames)

    @Volatile
    var framesWritten: Long = 0L
        private set

    val filledFrames: Int
        get() = minOf(framesWritten, capacityFrames.toLong()).toInt()

    fun write(block: FloatArray, count: Int) {
        val n = minOf(count, capacityFrames)
        val skip = count - n                       // block bigger than the ring: keep its tail
        var pos = ((framesWritten + skip) % capacityFrames).toInt()
        val first = minOf(n, capacityFrames - pos)
        System.arraycopy(block, skip, buf, pos, first)
        if (n > first) System.arraycopy(block, skip + first, buf, 0, n - first)
        framesWritten += count                     // volatile publish, after the copy
    }

    fun snapshot(frames: Int): FloatArray {
        val total = framesWritten                  // one volatile read; consistent basis
        val n = minOf(frames.toLong(), minOf(total, capacityFrames.toLong())).toInt()
        val out = FloatArray(n)
        var pos = ((total - n) % capacityFrames).toInt()
        if (pos < 0) pos += capacityFrames
        val first = minOf(n, capacityFrames - pos)
        System.arraycopy(buf, pos, out, 0, first)
        if (n > first) System.arraycopy(buf, 0, out, first, n - first)
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :audio:test --tests "com.snipsnap.audio.CaptureRingTest"`
Expected: PASS, 6 tests. The concurrent test is the one that matters; if it
flakes, the tear is in the NEWEST half and the implementation is wrong —
fix the publish order, do not weaken the test.

- [ ] **Step 5: Commit**

```bash
git add audio/src/main/kotlin/com/snipsnap/audio/CaptureRing.kt \
        audio/src/test/kotlin/com/snipsnap/audio/CaptureRingTest.kt
git commit -m "The ring that is always listening

Sixty seconds of the recent past, held in memory and never on disk. The
write path is arraycopy and index math, nothing else — the realtime
discipline the capture doc pinned before any Android code existed. A
snapshot may tear at its oldest edge during a concurrent write; that is
one inaudible block at the far end of a minute, priced against a lock on
the audio thread."
```

---

### Task 2: `MicSessionService` — the armed session

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/MicSessionService.kt`
- Modify: `app/src/main/AndroidManifest.xml` (the comment at :5 has been waiting: "Capture (M1) adds its foreground-service and overlay entries")
- Test: none (framework service; the ring it feeds is Task 1-tested). Say so in the commit body.

**Interfaces:**
- Consumes: `CaptureRing` (Task 1).
- Produces:
  ```kotlin
  class MicSessionService : Service() {
      companion object {
          const val ACTION_ARM = "com.snipsnap.app.ARM"
          const val ACTION_SNIP = "com.snipsnap.app.SNIP"
          const val ACTION_EJECT = "com.snipsnap.app.EJECT"
          const val RING_SECONDS = 60
          val armed: kotlinx.coroutines.flow.StateFlow<Boolean>   // UI observes
          val lastSnipFile: kotlinx.coroutines.flow.StateFlow<java.io.File?>
          fun arm(context: Context); fun snip(context: Context); fun eject(context: Context)
      }
  }
  ```

- [ ] **Step 1: Manifest entries**

Inside `<manifest>`:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
Inside `<application>` (replace the M1 comment at :4-5 with a one-liner noting it landed):
```xml
<service
    android:name=".MicSessionService"
    android:exported="false"
    android:foregroundServiceType="microphone" />
```

- [ ] **Step 2: Write the service**

Requirements (framework code, so it must be self-evidently careful):
- On `ACTION_ARM`: build the `AudioRecord` — source `UNPROCESSED` if `(getSystemService(AudioManager::class.java)).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"`, else `VOICE_RECOGNITION`; mono, 44_100, `ENCODING_PCM_FLOAT`; buffer ≥ 4× `getMinBufferSize`. `startForeground` with type `FOREGROUND_SERVICE_TYPE_MICROPHONE` and a notification: title `Copy.SESSION_ARMED`, one action button "SNIP" firing `ACTION_SNIP` via `PendingIntent.getService`, ongoing, low importance channel "capture".
- Reader thread (plain `thread(name = "MicSession")`): preallocate ONE `FloatArray(2048)`; loop `record.read(block, 0, block.size, AudioRecord.READ_BLOCKING)` → `ring.write(block, n)` while armed. Nothing else in the loop — no allocation, no logging per iteration.
- `ACTION_SNIP`: on a background thread (NOT the reader): `ring.snapshot(RING_SECONDS * 44_100)` → hand to `SnipStore.commit(...)` (Task 3) → publish `lastSnipFile`. The ring keeps rolling — one session, many snips.
- `ACTION_EJECT` (and `onDestroy`): stop the reader, release the record, `stopForeground`, drop the ring reference (the privacy spine: disarm forgets). Publish `armed=false`.
- `arm()` must be called only from a foreground context (the ARM button) — document why (Android 15 background-start limits, per docs/CAPTURE_RESEARCH_2026.md).
- KDoc the service with the privacy contract verbatim: memory only until SNIP; disarm drops the ring.

- [ ] **Step 3: Compile gate**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/MicSessionService.kt app/src/main/AndroidManifest.xml
git commit -m "TAPE ROLLING becomes true: the armed mic session

A microphone-type foreground service, an AudioRecord on the unprocessed
source where the device grants it, and one plain thread feeding the ring.
The notification carries a SNIP button so capture works from a pocket
before any overlay exists. Disarm drops the ring — nothing the user did
not snip ever touches disk.

No unit test: framework plumbing; the ring it feeds is CaptureRingTest's."
```

---

### Task 3: `SnipStore` — the commit path

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/SnipStore.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/SnipStoreTest.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/MicSessionService.kt` (wire `ACTION_SNIP` to it)

**Interfaces:**
- Consumes: `Cleanup` (the commit-time chain — read the object, use its entry point in its documented order), `CaptureDoctor.clean(snip): CleanReport` (:347), `WavWriter.write`.
- Produces:
  ```kotlin
  object SnipStore {
      const val DIR = "snips"
      fun commit(samples: FloatArray, sampleRate: Int, root: File, nowMillis: Long): File
      fun list(root: File): List<File>            // newest first
      fun newest(root: File): File?
  }
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.shell

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnipStoreTest {

    private fun tone(seconds: Float, rate: Int = 44_100): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { (0.4f * sin(2.0 * Math.PI * 220.0 * it / rate)).toFloat() }
    }

    @Test
    fun `a commit lands one readable WAV in the snips dir`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val f = SnipStore.commit(tone(1f), 44_100, root, nowMillis = 1_000_000L)
            assertTrue(f.isFile && f.parentFile.name == SnipStore.DIR)
            val back = com.snipsnap.audio.WavReader.read(f)
            assertEquals(44_100, back.sampleRate)
            assertTrue(back.frameCount > 0)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `leading dead air is trimmed by the commit chain`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val silence = FloatArray(44_100)                 // 1s of nothing
            val f = SnipStore.commit(silence + tone(0.5f), 44_100, root, 2_000_000L)
            val back = com.snipsnap.audio.WavReader.read(f)
            assertTrue(
                back.frameCount < 44_100,
                "the silent first second should not survive the commit chain: ${back.frameCount}",
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `list is newest first and newest agrees`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val a = SnipStore.commit(tone(0.2f), 44_100, root, 1_000L)
            val b = SnipStore.commit(tone(0.2f), 44_100, root, 2_000L)
            assertEquals(listOf(b, a), SnipStore.list(root))
            assertEquals(b, SnipStore.newest(root))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `an all-silent snip still commits rather than throwing`() {
        // The user pressed SNIP; the honest outcome of a silent minute is a
        // short (possibly minimal) file, not an exception in a service.
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val f = SnipStore.commit(FloatArray(44_100), 44_100, root, 3_000L)
            assertTrue(f.isFile)
        } finally { root.deleteRecursively() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SnipStoreTest"`
Expected: FAIL — `Unresolved reference: SnipStore`.

- [ ] **Step 3: Implement**

`commit`: wrap the samples in `Snip(samples, 1, sampleRate)` → run the
commit-time `Cleanup` chain (read `Cleanup`'s object for its entry —
DC → trim → normalise → fades, order is load-bearing) → run
`CaptureDoctor.clean(...)` with defaults and IGNORE the report's knobs for
v1 (the doctor's diagnosis fields can ride into a later screen; do not
denoise by default) → `WavWriter.write` to
`root/snips/snip_<nowMillis>.wav` (mkdirs). If the cleaned snip is empty
(all-silence trim), write the minimal untrimmed-but-normalised original
rather than throwing — a service must not crash on a quiet room.
`list`: the dir's `snip_*.wav` sorted by the timestamp in the name,
newest first (name, not mtime — same lesson as GrooveEdit's E-naming:
content identity beats filesystem metadata). `newest` = `list().firstOrNull()`.
Then wire `MicSessionService`'s `ACTION_SNIP` to
`SnipStore.commit(snapshot, 44_100, filesDir, System.currentTimeMillis())`
and publish the returned file.

- [ ] **Step 4: Run tests**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SnipStoreTest"` then `./gradlew test` (report real number) and `./gradlew :app:compileDebugKotlin`.
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/SnipStore.kt \
        shell/src/test/kotlin/com/snipsnap/shell/SnipStoreTest.kt \
        app/src/main/kotlin/com/snipsnap/app/MicSessionService.kt
git commit -m "SNIP! and the last minute becomes a file

The snapshot runs the commit-time cleanup chain — DC, trim, normalise,
fades, in the order Cleanup documents as load-bearing — and lands as a
timestamped WAV. A silent minute commits small rather than crashing a
foreground service; the doctor's deeper repairs stay opt-in for a later
screen."
```

---

### Task 4: ARM in the app, and TAPE prefers the newest snip

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (arm state + permission flow)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt` (the ARM control on the shelf)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/TapeScreen.kt` (source priority)
- Test: none new (UI + wiring; SnipStore/CaptureRing carry the logic). Say so.

**Interfaces:**
- Consumes: `MicSessionService.{arm,snip,eject,armed,lastSnipFile}`, `SnipStore.newest`, `Copy.SESSION_ARMED`/`SNIPPED`/`CAPTURE_BLOCKED`(+`_BUTTON`), `Delight.deckSoundsEnabled(level, captureArmed)`.
- Produces: an armed session reachable from the shelf; TAPE that opens on the newest snip.

- [ ] **Step 1: The ARM control**

On KITS (the shelf — where sessions begin, same reasoning as IMPORT's
placement ruling): a primary-styled ARM TAPE button. Tap →
`rememberLauncherForActivityResult(RequestPermission())` for RECORD_AUDIO
(and POST_NOTIFICATIONS on 33+, chained or via RequestMultiplePermissions —
read how the codebase does launchers and match). Granted →
`MicSessionService.arm(context)`, toast `Copy.SESSION_ARMED`. Denied →
`Copy.CAPTURE_BLOCKED` dialog with `Copy.CAPTURE_BLOCKED_BUTTON` — the
constants exist (:61-63). While `armed` collects true: the button becomes
EJECT (bin-red pair convention), and a small in-app SNIP button appears
beside it (the notification action is the out-of-app path). Wire
`Delight.deckSoundsEnabled(personality, captureArmed = armed)` wherever
deck sounds currently consult it — the flag has been waiting (:29-30).

- [ ] **Step 2: TAPE's source priority**

In TapeScreen's load (see :117 `loadLongestTape`): new priority —
`SnipStore.newest(context.filesDir)` → lastCommit source → open kit's
longest sample. A snip loads exactly like any WAV (WavReader + toMono is
already the path). Observing `lastSnipFile` while TAPE is open reloads to
the fresh snip ONLY when the deck is idle (not playing, no selection) —
never yank a tape out from under an edit; note this rule in a comment.

- [ ] **Step 3: Gates**

Run: `./gradlew test` (report real number) and `./gradlew :app:assembleDebug`.
Expected: green. Device behavior (arming, notification SNIP, pocket test)
is explicitly user-scheduled — list it in the report as unverified.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/App.kt \
        app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt \
        app/src/main/kotlin/com/snipsnap/app/ui/TapeScreen.kt
git commit -m "The spine closes: mic to ring to snip to TAPE

ARM lives on the shelf where sessions begin. While the tape rolls the deck
sounds hush themselves — Delight.deckSoundsEnabled has carried the flag
since before capture existed. TAPE now opens on the newest snip, and never
swaps tapes under a live edit."
```

---

### Task 5: The Bubble

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/BubbleOverlay.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/MicSessionService.kt` (show/hide with session), `app/src/main/AndroidManifest.xml` (`SYSTEM_ALERT_WINDOW`)
- Test: none (overlay; device-gated). Say so.

**Interfaces:**
- Consumes: `MicSessionService` actions; `Motion.BUBBLE_DRAG_SCALE` (1.08f — landed with the token work, unused since); `Copy.SNIPPED`, `Copy.BUBBLE_EJECTED`; the artboard `design/Bubble.dc.html` + HANDOFF row ("ring = tape fill, tap=snip, drag-down=eject (hot zone bottom, y>660)").

- [ ] **Step 1: The overlay**

`SYSTEM_ALERT_WINDOW` permission is OPTIONAL: if not granted, the session
works from the notification alone — offer the grant once from the ARM flow
(`Settings.canDrawOverlays` → `ACTION_MANAGE_OVERLAY_PERMISSION`), never
nag. When granted and armed, the service attaches a small ComposeView-in-
WindowManager bubble (read the artboard for its look: a ring that fills as
`ring.filledFrames / capacity` fills, OILSLICK-scheme colors via the same
Scheme plumbing the app uses): tap = `ACTION_SNIP` + `Copy.SNIPPED` toast;
drag = follow finger at `Motion.BUBBLE_DRAG_SCALE`; release in the bottom
hot zone (y > 660/844 of screen height, per the artboard's proportion —
compute from real metrics, don't hardcode 660 px) = `ACTION_EJECT` +
`Copy.BUBBLE_EJECTED`. Hide the bubble whenever SnipSnap itself is
foreground (the in-app controls own that surface). Standing rules apply
(dp, hit target ≥ MIN_HIT_TARGET for the bubble itself).

- [ ] **Step 2: Gates**

Run: `./gradlew test` and `./gradlew :app:assembleDebug`.
Expected: green; every interactive behavior device-gated and listed as
unverified in the report.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/BubbleOverlay.kt \
        app/src/main/kotlin/com/snipsnap/app/MicSessionService.kt \
        app/src/main/AndroidManifest.xml
git commit -m "The bubble: one tap between hearing it and keeping it

A floating ring that fills as the tape does, riding over whatever app is
playing. Tap snips the minute that just happened; drag it to the bottom
and the session ejects. Optional by design — the notification path is the
feature, the bubble is the delight."
```

---

## Verification

After Task 5: `./gradlew test && ./gradlew :app:assembleDebug`. Then the
device pass (user-scheduled): ARM on the shelf → permission prompts →
notification appears → play any sound in the room → SNIP from the
notification → open SnipSnap → TAPE holds the last minute → trim → COMMIT
→ CHOP → kit. Then the bubble grant and the same loop from another app.

## Known follow-ups (specs already sketched, not this plan)
- **The Split** (HPSS on a snip → drum kit + melodic instrument), **The
  Keeper** (self-reviewing takes), **Room as Patch**, **Overheard Kit** —
  all consume snips; none block this.
- Playback capture — separate spec, gated on running the never-run probe.
- `CaptureDoctor`'s denoise/declip/deverb as a snip-review surface.
- Audio-route-change resilience for the reader thread (spec'd as a v1 no-op).
