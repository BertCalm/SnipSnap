# Loop Player 03 — Live Playback and the Android Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the grid audible on a phone — a bounded buffer cache with off-thread baking, a transport that drives a sink in real time, and the Android app that finally gives SnipSnap a screen.

**Architecture:** Tasks 1–3 stay in the pure-JVM `:loop` module and are fully testable headless: a file-backed `SampleSource`, a `Residency` that keeps only the buffers the next two intervals need, and a `LoopEngine` that mixes and writes through the `AudioSink` seam. Tasks 4–7 add the `:app` module — Android Gradle Plugin, an AudioTrack sink, and a Compose grid — with CI updated in the same commit that introduces AGP.

**Tech Stack:** Kotlin 2.0.21, JVM 17, `kotlin.test`, Gradle 8.14.3, Android Gradle Plugin, Jetpack Compose, `AudioTrack` in `ENCODING_PCM_FLOAT` mode.

**Spec:** `docs/superpowers/specs/2026-08-22-loop-player-design.md` (sections 06, 07, 09, 12)

**Depends on:** Plan 01 (`WavReader`, `Resampler`) and Plan 02 (`Session`, `Arrangement`, `BlockBaker`, `Mixer`, `AudioSink`, `Bouncer`, `SessionStore`). Both must be complete and green.

## Global Constraints

- **Kotlin 2.0.21 everywhere.** Compose on Kotlin 2.0 needs the separate `org.jetbrains.kotlin.plugin.compose` Gradle plugin — the old `composeOptions { kotlinCompilerExtensionVersion }` block does not apply.
- **`minSdk 29.** Forced by `AudioPlaybackCapture`, per `docs/CONCEPT.md:119`. Do not lower it.
- **`:loop` must stay free of Android APIs.** Tasks 1–3 add nothing to `loop/build.gradle.kts`. If a task tempts you to import `android.*` into `:loop`, the seam is in the wrong place.
- **The audio thread allocates nothing.** `LoopEngine` reuses one output buffer for its lifetime. Baking, decoding and resampling happen on the executor.
- **`Snip.equals` compares `channels`, `sampleRate` and `samples.size` only — never sample values.**
- **Test style:** `kotlin.test`, backtick test names, private helper factories at the top of the class.
- **Commit style:** no Conventional Commits. A short evocative sentence.
- **UI language is TapeOS**, defined in `docs/UI_DESIGN.md`: three bevel styles and no flat buttons, no rounded-corner cards, no drop shadows outside the LCD glow, drawn icons never emoji, monospaced LCD numerals that must not jitter, group boxes with pixel-font legends instead of cards.

---

### Task 1: KitSampleSource — the first thing that reads a session off disk

**Files:**
- Create: `loop/src/main/kotlin/com/snipsnap/loop/KitSampleSource.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/KitSampleSourceTest.kt`

**Interfaces:**
- Consumes: `SampleSource` (Plan 02 Task 3); `WavReader.read(file: File): Snip` (Plan 01 Task 3); `KitStore.load(dir: File): Kit`, `Kit.pads: List<KitPad>`, `KitPad.slot: Int`, `KitPad.sampleFile: String` from `:kit`.
- Produces: `class KitSampleSource(private val dir: File) : SampleSource`. Task 3 and the Android screen construct it with the session folder.

Every test in Plan 02 used a map-backed fake, which was right for testing the baker but means nothing has yet resolved a real filename. This is that.

- [ ] **Step 1: Write the failing test**

Create `loop/src/test/kotlin/com/snipsnap/loop/KitSampleSourceTest.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KitSampleSourceTest {

    private fun tempDir(): File =
        File.createTempFile("snipsnap-src", "").let {
            it.delete(); it.mkdirs(); it.deleteOnExit(); it
        }

    private fun writeWav(dir: File, name: String, frames: Int, level: Float): File {
        val snip = Snip(FloatArray(frames * 2) { level }, 2, 44_100)
        return WavWriter.write(File(dir, name), snip, WavWriter.BitDepth.PCM_24)
    }

    @Test
    fun `resolves a loop by bare filename inside the session folder`() {
        val dir = tempDir()
        writeWav(dir, "break.wav", 1_000, 0.5f)

        val snip = KitSampleSource(dir).loop("break.wav")
        assertNotNull(snip)
        assertEquals(1_000, snip.frameCount)
        assertEquals(2, snip.channels)
    }

    @Test
    fun `returns null for a loop that is not there`() {
        assertNull(KitSampleSource(tempDir()).loop("gone.wav"))
    }

    @Test
    fun `resolves a pad through the kit's own sidecar`() {
        val dir = tempDir()
        val kitDir = File(dir, "Thump Kit").also { it.mkdirs() }
        writeWav(kitDir, "kick.wav", 800, 0.4f)
        KitStore.save(Kit("Thump Kit", listOf(KitPad(slot = 1, sampleFile = "kick.wav"))), kitDir)

        val snip = KitSampleSource(dir).pad("Thump Kit", 1)
        assertNotNull(snip)
        assertEquals(800, snip.frameCount)
    }

    @Test
    fun `returns null for a slot the kit does not fill`() {
        val dir = tempDir()
        val kitDir = File(dir, "Thump Kit").also { it.mkdirs() }
        writeWav(kitDir, "kick.wav", 800, 0.4f)
        KitStore.save(Kit("Thump Kit", listOf(KitPad(slot = 1, sampleFile = "kick.wav"))), kitDir)

        assertNull(KitSampleSource(dir).pad("Thump Kit", 9))
    }

    @Test
    fun `returns null for a kit that is not there`() {
        assertNull(KitSampleSource(tempDir()).pad("No Such Kit", 1))
    }

    @Test
    fun `refuses a path that escapes the session folder`() {
        // sampleFile is meant to be a bare filename. A traversal attempt must
        // not read outside the session, whatever wrote loop.json.
        assertNull(KitSampleSource(tempDir()).loop("../../etc/passwd"))
    }

    @Test
    fun `decodes each file once`() {
        val dir = tempDir()
        writeWav(dir, "break.wav", 1_000, 0.5f)
        val source = KitSampleSource(dir)

        val first = source.loop("break.wav")
        val second = source.loop("break.wav")
        // Baking asks repeatedly across a long cycle; decoding a WAV every time
        // would put file I/O on the path a prefetch is trying to keep short.
        assertNotNull(first)
        assertEquals(true, first === second, "second read should be cached")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.KitSampleSourceTest'`
Expected: FAIL — compilation error, `Unresolved reference: KitSampleSource`.

- [ ] **Step 3: Write minimal implementation**

Create `loop/src/main/kotlin/com/snipsnap/loop/KitSampleSource.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a session's sample references against its own folder.
 *
 * A session is a directory: loop.json plus the WAVs it names plus the kit
 * folders it plays. Everything is a bare filename resolved from here, the same
 * rule KitPad.sampleFile already enforces, so a session can be copied or
 * shared as one unit and still play.
 *
 * Decoded audio is cached. Baking asks for the same block repeatedly across a
 * long cycle, and a prefetch that re-reads the file every time is a prefetch
 * that misses its deadline.
 */
class KitSampleSource(private val dir: File) : SampleSource {

    private val loops = ConcurrentHashMap<String, Snip>()
    private val pads = ConcurrentHashMap<Pair<String, Int>, Snip>()
    private val kitIndex = ConcurrentHashMap<String, Map<Int, String>>()

    override fun loop(sampleFile: String): Snip? {
        if (!isBareName(sampleFile)) return null
        loops[sampleFile]?.let { return it }
        val file = File(dir, sampleFile)
        if (!file.isFile) return null
        val snip = runCatching { WavReader.read(file) }.getOrNull() ?: return null
        return loops.putIfAbsent(sampleFile, snip) ?: snip
    }

    override fun pad(kit: String, slot: Int): Snip? {
        if (!isBareName(kit)) return null
        val key = kit to slot
        pads[key]?.let { return it }

        val slots = kitIndex.getOrPut(kit) { indexKit(kit) }
        val name = slots[slot] ?: return null
        val file = File(File(dir, kit), name)
        if (!file.isFile) return null
        val snip = runCatching { WavReader.read(file) }.getOrNull() ?: return null
        return pads.putIfAbsent(key, snip) ?: snip
    }

    /** slot -> bare filename, read once per kit. */
    private fun indexKit(kit: String): Map<Int, String> {
        val kitDir = File(dir, kit)
        if (!kitDir.isDirectory) return emptyMap()
        val loaded = runCatching { KitStore.load(kitDir) }.getOrNull() ?: return emptyMap()
        return loaded.pads.associate { it.slot to it.sampleFile }
    }

    /**
     * A reference must name something inside this folder and nothing else.
     * loop.json is a file on a phone; it can be edited, synced or corrupted,
     * and a traversal should read nothing rather than something.
     */
    private fun isBareName(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name && name != ".." && name != "."
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.KitSampleSourceTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/KitSampleSource.kt loop/src/test/kotlin/com/snipsnap/loop/KitSampleSourceTest.kt
git commit -m "The folder is the session: bare names in, decoded audio out"
```

---

### Task 2: Residency — a bounded window of baked buffers

**Files:**
- Create: `loop/src/main/kotlin/com/snipsnap/loop/Residency.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/ResidencyTest.kt`

**Interfaces:**
- Consumes: `Session`, `Block`, `Arrangement.blockAt`, `BlockBaker.bake`, `SampleSource` (Plan 02).
- Produces: `class Residency(initial: Session, source: SampleSource, executor: Executor)` with `buffersFor(interval: Int): List<Snip>`, `prefetch(interval: Int)`, `retain(vararg intervals: Int)`, `update(session: Session)`, `session(): Session`, `residentCount(): Int`. Task 3 drives all of these.

Spec §06 sizes this at twelve buffers — current and next for each of six tracks, about 49MB at 4 bars and 90 BPM. Without eviction a long cycle would hold every distinct block at once.

- [ ] **Step 1: Write the failing test**

Create `loop/src/test/kotlin/com/snipsnap/loop/ResidencyTest.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResidencyTest {

    /** Counts how many times a sample was asked for, so we can prove caching. */
    private class CountingSource(private val frames: Int) : SampleSource {
        val loopCalls = AtomicInteger(0)
        override fun loop(sampleFile: String): Snip {
            loopCalls.incrementAndGet()
            return Snip(FloatArray(frames * 2) { 0.25f }, 2, 48_000)
        }
        override fun pad(kit: String, slot: Int): Snip? = null
    }

    private val sameThread = Executor { it.run() }

    private fun session(vararg sizes: Int, bpm: Float = 200f) = Session(
        tracks = sizes.mapIndexed { i, n ->
            Track("t$i", (1..n).map { LoopBlock("t$i-$it.wav") })
        },
        bpm = bpm,
        barsPerInterval = 1,
        sampleRate = 48_000,
    )

    @Test
    fun `bakes on demand and hands back one buffer per track`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val r = Residency(s, CountingSource(s.intervalFrames), sameThread)

        val buffers = r.buffersFor(0)
        assertEquals(6, buffers.size)
        for (b in buffers) assertEquals(s.intervalFrames, b.frameCount)
    }

    @Test
    fun `bakes each distinct block only once`() {
        val s = session(2, 1, 1, 1, 1, 1)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        r.buffersFor(0)
        r.buffersFor(0)
        // Six tracks, six distinct blocks at interval 0 — asked for once each.
        assertEquals(6, src.loopCalls.get())
    }

    @Test
    fun `prefetch fills the cache so the next interval is already there`() {
        val s = session(2, 1, 1, 1, 1, 1)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        val afterFirst = src.loopCalls.get()
        r.prefetch(1)
        val afterPrefetch = src.loopCalls.get()
        r.buffersFor(1)

        assertTrue(afterPrefetch > afterFirst, "prefetch should have baked something")
        assertEquals(afterPrefetch, src.loopCalls.get(), "buffersFor should not have baked again")
    }

    @Test
    fun `retain drops buffers no longer needed`() {
        val s = session(4, 1, 1, 1, 1, 1)
        val r = Residency(s, CountingSource(s.intervalFrames), sameThread)

        r.buffersFor(0)
        r.buffersFor(1)
        r.buffersFor(2)
        assertTrue(r.residentCount() > 6, "should be holding more than one interval's worth")

        r.retain(2, 3)
        // Track 0 contributes a distinct block per interval; the other five
        // share their single block. Two intervals is at most 7 distinct blocks.
        assertTrue(r.residentCount() <= 7, "still holding ${r.residentCount()}")
    }

    @Test
    fun `a bpm change invalidates every baked buffer`() {
        val s = session(1, 1, 1, 1, 1, 1, bpm = 200f)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        val before = src.loopCalls.get()
        r.update(s.copy(bpm = 100f))
        r.buffersFor(0)

        assertTrue(src.loopCalls.get() > before, "buffers are the wrong length now and must re-bake")
        assertEquals(r.session().intervalFrames, r.buffersFor(0)[0].frameCount)
    }

    @Test
    fun `a mute does not invalidate anything`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        val before = src.loopCalls.get()
        r.update(s.copy(tracks = s.tracks.mapIndexed { i, t -> t.copy(engaged = i != 0) }))
        r.buffersFor(0)

        assertEquals(before, src.loopCalls.get(), "muting is a gain flag, not a re-bake")
    }

    @Test
    fun `swapping a block bakes only the new one`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        val before = src.loopCalls.get()
        val swapped = s.copy(
            tracks = s.tracks.mapIndexed { i, t ->
                if (i == 0) t.copy(chain = listOf(LoopBlock("new.wav"))) else t
            },
        )
        r.update(swapped)
        r.buffersFor(0)

        assertEquals(before + 1, src.loopCalls.get(), "only the replaced block should re-bake")
    }

    @Test
    fun `shortening a chain past the playing index still returns six buffers`() {
        val s = session(4, 1, 1, 1, 1, 1)
        val r = Residency(s, CountingSource(s.intervalFrames), sameThread)
        r.buffersFor(3)

        val cut = s.copy(
            tracks = s.tracks.mapIndexed { i, t ->
                if (i == 0) t.copy(chain = t.chain.take(2)) else t
            },
        )
        r.update(cut)
        assertEquals(6, r.buffersFor(3).size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.ResidencyTest'`
Expected: FAIL — compilation error, `Unresolved reference: Residency`.

- [ ] **Step 3: Write minimal implementation**

Create `loop/src/main/kotlin/com/snipsnap/loop/Residency.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The baked buffers currently in memory.
 *
 * Spec section 06 sizes this at twelve — current and next for each of six
 * tracks, roughly 49MB at 4 bars and 90 BPM. A long cycle can reference far
 * more distinct blocks than that, so [retain] is not an optimisation; without
 * it an 840-interval cycle would try to hold every block at once.
 *
 * Keying the cache by [Block] does more work than it looks like. Blocks are
 * data classes, so swapping a track's block produces a value that simply is
 * not in the map and gets baked on demand, while every untouched block stays
 * cached — block-swap invalidation falls out of equality rather than needing
 * its own bookkeeping.
 */
class Residency(
    initial: Session,
    private val source: SampleSource,
    private val executor: Executor,
) {

    private val current = AtomicReference(initial)
    private val baked = ConcurrentHashMap<Block, Snip>()

    fun session(): Session = current.get()

    fun residentCount(): Int = baked.size

    /**
     * One buffer per track for [interval], baking anything missing on the
     * calling thread. Call [prefetch] an interval ahead so this does not have
     * to bake anything.
     */
    fun buffersFor(interval: Int): List<Snip> {
        val s = current.get()
        return s.tracks.map { track ->
            val block = Arrangement.blockAt(track, interval)
            baked.getOrPut(block) { BlockBaker.bake(block, s, source) }
        }
    }

    /** Bake what [interval] will need, on the executor. Returns immediately. */
    fun prefetch(interval: Int) {
        val s = current.get()
        for (track in s.tracks) {
            val block = Arrangement.blockAt(track, interval)
            if (baked.containsKey(block)) continue
            executor.execute {
                // Re-check the session: a BPM change may have landed while this
                // was queued, and a buffer of the old length is worse than none.
                if (current.get().intervalFrames == s.intervalFrames) {
                    baked.putIfAbsent(block, BlockBaker.bake(block, s, source))
                }
            }
        }
    }

    /** Drop every buffer not needed by one of [intervals]. */
    fun retain(vararg intervals: Int) {
        val s = current.get()
        val keep = HashSet<Block>()
        for (i in intervals) {
            for (track in s.tracks) keep.add(Arrangement.blockAt(track, i))
        }
        baked.keys.retainAll(keep)
    }

    /**
     * Swap in an edited session.
     *
     * Only a change to interval length invalidates audio: every baked buffer is
     * exactly one interval long, so a new BPM makes all of them the wrong size
     * at once. Mutes, levels and pans are read by the mixer and need no re-bake;
     * chain edits and block swaps re-key the cache by themselves.
     */
    fun update(session: Session) {
        val previous = current.getAndSet(session)
        if (previous.intervalFrames != session.intervalFrames) baked.clear()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.ResidencyTest'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/Residency.kt loop/src/test/kotlin/com/snipsnap/loop/ResidencyTest.kt
git commit -m "Twelve buffers and no more: the window that keeps the phone alive"
```

---

### Task 3: LoopEngine — the transport that drives a sink

**Files:**
- Create: `loop/src/main/kotlin/com/snipsnap/loop/LoopEngine.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/LoopEngineTest.kt`

**Interfaces:**
- Consumes: `Residency` (Task 2), `Mixer.mix`, `AudioSink` (Plan 02 Task 6).
- Produces: `class LoopEngine(residency: Residency, sink: AudioSink)` with `runFor(intervals: Int)`, `run()`, `stop()`, `position(): Int`, `apply(session: Session)`. Task 7's screen calls `run`, `stop` and `apply`.

This is where spec §07's "bake off-thread, swap atomically at the next interval boundary" becomes code. Edits land in an `AtomicReference` and are picked up at the boundary — never mid-block.

- [ ] **Step 1: Write the failing test**

Create `loop/src/test/kotlin/com/snipsnap/loop/LoopEngineTest.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopEngineTest {

    /** Records every block handed to it, so we can inspect what was played. */
    private class RecordingSink(override val sampleRate: Int = 48_000) : AudioSink {
        override val channels = 2
        val blocks = ArrayList<FloatArray>()
        var closed = false
        override fun write(block: FloatArray) { blocks.add(block.copyOf()) }
        override fun close() { closed = true }
    }

    private class LevelSource(private val frames: Int) : SampleSource {
        override fun loop(sampleFile: String): Snip {
            // Encode the filename's trailing digit as the level, so a recorded
            // block says which block was playing.
            val level = (sampleFile.filter { it.isDigit() }.lastOrNull()?.digitToInt() ?: 0) / 10f
            return Snip(FloatArray(frames * 2) { level }, 2, 48_000)
        }
        override fun pad(kit: String, slot: Int): Snip? = null
    }

    private val sameThread = Executor { it.run() }

    private fun session(vararg sizes: Int) = Session(
        tracks = sizes.mapIndexed { i, n ->
            Track("t$i", (1..n).map { LoopBlock("t$i-b$it.wav") }, engaged = i == 0)
        },
        bpm = 200f,
        barsPerInterval = 1,
        sampleRate = 48_000,
    )

    private fun engine(s: Session): Pair<LoopEngine, RecordingSink> {
        val sink = RecordingSink()
        return LoopEngine(Residency(s, LevelSource(s.intervalFrames), sameThread), sink) to sink
    }

    @Test
    fun `writes one block per interval`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val (e, sink) = engine(s)
        e.runFor(4)

        assertEquals(4, sink.blocks.size)
        for (b in sink.blocks) assertEquals(s.intervalFrames * 2, b.size)
    }

    @Test
    fun `advances the chain between intervals`() {
        // Track 0 is the only engaged one and has two blocks, b1 then b2, which
        // the source turns into levels 0.1 and 0.2.
        val s = session(2, 1, 1, 1, 1, 1)
        val (e, sink) = engine(s)
        e.runFor(4)

        val levels = sink.blocks.map { it[0] }
        assertTrue(abs(levels[0] - 0.1f) < 1e-5f, "interval 0 was ${levels[0]}")
        assertTrue(abs(levels[1] - 0.2f) < 1e-5f, "interval 1 was ${levels[1]}")
        assertTrue(abs(levels[2] - 0.1f) < 1e-5f, "interval 2 should have wrapped, was ${levels[2]}")
    }

    @Test
    fun `reports its position`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val (e, _) = engine(s)
        assertEquals(0, e.position())
        e.runFor(3)
        assertEquals(3, e.position())
    }

    @Test
    fun `reuses one output buffer`() {
        // The audio thread must not allocate. A RecordingSink copies on write,
        // so identical references here prove the engine handed out the same
        // array every interval.
        val s = session(1, 1, 1, 1, 1, 1)
        val sink = object : AudioSink {
            override val sampleRate = 48_000
            override val channels = 2
            val seen = ArrayList<FloatArray>()
            override fun write(block: FloatArray) { seen.add(block) }
            override fun close() {}
        }
        LoopEngine(Residency(s, LevelSource(s.intervalFrames), sameThread), sink).runFor(3)
        assertTrue(sink.seen[0] === sink.seen[1] && sink.seen[1] === sink.seen[2], "engine allocated per block")
    }

    @Test
    fun `an applied edit lands at the next boundary`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val (e, sink) = engine(s)
        e.runFor(1)

        // Mute everything: the next block must be silent, the one already
        // written must not have changed.
        e.apply(s.copy(tracks = s.tracks.map { it.copy(engaged = false) }))
        e.runFor(1)

        assertTrue(abs(sink.blocks[0][0] - 0.1f) < 1e-5f, "first block was rewritten")
        assertEquals(0f, sink.blocks[1][0], "edit did not take effect")
    }

    @Test
    fun `stop ends a run`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val (e, sink) = engine(s)
        e.stop()
        e.runFor(4)
        assertEquals(0, sink.blocks.size, "a stopped engine should write nothing")
    }

    @Test
    fun `keeps the resident window bounded across a long run`() {
        val s = session(4, 3, 2, 1, 1, 1)
        val sink = RecordingSink()
        val residency = Residency(s, LevelSource(s.intervalFrames), sameThread)
        LoopEngine(residency, sink).runFor(24)

        // Two intervals of six tracks is at most twelve distinct blocks.
        assertTrue(residency.residentCount() <= 12, "held ${residency.residentCount()} buffers")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.LoopEngineTest'`
Expected: FAIL — compilation error, `Unresolved reference: LoopEngine`.

- [ ] **Step 3: Write minimal implementation**

Create `loop/src/main/kotlin/com/snipsnap/loop/LoopEngine.kt`:

```kotlin
package com.snipsnap.loop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the grid.
 *
 * One interval per turn of the loop: read the buffers, mix, write, prefetch the
 * next, drop what is no longer needed, advance. The output buffer is allocated
 * once and reused forever — the sink is expected to consume it before returning,
 * exactly as AudioTrack.write does.
 *
 * Edits are handed in through [apply] and picked up at the top of the next
 * interval, never mid-block. That is the whole of spec section 07's "swap
 * atomically at the next interval boundary": a pending reference, read once per
 * interval, at the one instant when nothing is half-written.
 */
class LoopEngine(
    private val residency: Residency,
    private val sink: AudioSink,
) {

    private val interval = AtomicInteger(0)
    private val running = AtomicBoolean(true)
    private val pending = AtomicReference<Session?>(null)

    private var block = FloatArray(residency.session().intervalFrames * 2)

    fun position(): Int = interval.get()

    /** Queue an edited session. Takes effect at the next interval boundary. */
    fun apply(session: Session) { pending.set(session) }

    fun stop() { running.set(false) }

    /** Play until [stop]. Blocking — the caller owns the thread. */
    fun run() {
        while (running.get()) playOne()
    }

    /** Play exactly [intervals] intervals, or until stopped. */
    fun runFor(intervals: Int) {
        require(intervals >= 0) { "intervals must not be negative: $intervals" }
        for (i in 0 until intervals) {
            if (!running.get()) return
            playOne()
        }
    }

    private fun playOne() {
        pending.getAndSet(null)?.let { edited ->
            residency.update(edited)
            // A BPM change resizes the interval, so the reused buffer has to
            // grow or shrink with it. This is the only place it may reallocate.
            val frames = edited.intervalFrames * 2
            if (block.size != frames) block = FloatArray(frames)
        }

        val i = interval.get()
        val session = residency.session()

        Mixer.mix(residency.buffersFor(i), session.tracks, block)
        sink.write(block)

        residency.prefetch(i + 1)
        residency.retain(i, i + 1)
        interval.set(i + 1)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.LoopEngineTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Run the whole module**

Run: `./gradlew :loop:test`
Expected: PASS — Plan 02's 53 plus this plan's 22 so far.

- [ ] **Step 6: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/LoopEngine.kt loop/src/test/kotlin/com/snipsnap/loop/LoopEngineTest.kt
git commit -m "One interval per turn: edits wait for the boundary, buffers never move"
```

---

### Task 4: The `:app` module and the CI change that must ship with it

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts` (append `include(":app")`)
- Modify: `.github/workflows/tests.yml`
- Modify: `gradle.properties` (create if absent)

**Interfaces:**
- Consumes: nothing at runtime yet.
- Produces: a buildable Android module depending on `:loop`, `:kit` and `:audio`; a CI workflow that still runs every JVM test.

`.github/workflows/tests.yml` currently installs Temurin 17 and runs `./gradlew --no-daemon test` — nothing else. The moment AGP appears in the build, that job needs an Android SDK or **every one of the existing tests fails**, and the failure looks nothing like its cause. The workflow change is part of this task, not a follow-up.

- [ ] **Step 1: Check current stable versions before writing anything**

The versions below were current when this plan was written and will have moved. Confirm each and use what is actually current:

```bash
# Android Gradle Plugin + Compose BOM releases
open https://developer.android.com/build/releases/gradle-plugin
open https://developer.android.com/jetpack/compose/bom/bom-mapping
```

Record what you use. AGP must be compatible with Gradle 8.14.3, and the Compose compiler plugin version must match Kotlin 2.0.21.

- [ ] **Step 2: Write the module**

Create `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.0.21"
    // Kotlin 2.0 moved the Compose compiler into its own plugin; the old
    // composeOptions { kotlinCompilerExtensionVersion } block no longer applies.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.snipsnap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snipsnap.app"
        minSdk = 29 // AudioPlaybackCapture; see docs/CONCEPT.md
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":loop"))
    implementation(project(":kit"))
    implementation(project(":audio"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
```

Create `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="SnipSnap"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".LoopActivity"
            android:exported="true"
            android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

Append to `settings.gradle.kts`:

```kotlin
include(":app")
```

Ensure `gradle.properties` contains:

```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2048m
```

- [ ] **Step 3: Update CI in the same commit**

Replace `.github/workflows/tests.yml` with:

```yaml
name: tests

on:
  push:
    branches: ["**"]
  pull_request:

jobs:
  jvm-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      # The Android SDK is required from the moment :app exists: AGP resolves it
      # at configuration time, so without it every module fails to configure and
      # all the pure-JVM tests go red for a reason that has nothing to do with
      # them.
      - uses: android-actions/setup-android@v3
      # Everything except :app, by exclusion rather than by list — a module added
      # later must not be silently untested because nobody updated this line.
      - name: Run JVM test suites
        run: ./gradlew --no-daemon test -x :app:test

  android-build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - uses: android-actions/setup-android@v3
      - name: Assemble the app
        run: ./gradlew --no-daemon :app:assembleDebug
```

The JVM job excludes `:app` rather than running bare `test`, so a broken `:app` can never mask a green engine — which matters, because the engine is the part with the tests.

- [ ] **Step 4: Verify the build still works**

Run: `./gradlew :loop:test`
Expected: PASS — unchanged by the new module.

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Requires `ANDROID_HOME` and an installed SDK; if this fails with `SDK location not found`, install the Android SDK before continuing.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml settings.gradle.kts gradle.properties .github/workflows/tests.yml
git commit -m "An eighth module and a screen at last — CI grows an SDK to match"
```

---

### Task 5: AndroidAudioSink

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/AndroidAudioSink.kt`

**Interfaces:**
- Consumes: `AudioSink` (Plan 02 Task 6).
- Produces: `class AndroidAudioSink(sampleRate: Int) : AudioSink` and `fun deviceSampleRate(context: Context): Int`. Task 7 constructs both. The sink takes only a rate — its device buffer is sized in milliseconds and is deliberately independent of the mix block size.

Spec §06: the sink's native rate is queried once at session start and everything bakes at that rate, so no conversion ever happens in the callback.

- [ ] **Step 1: Write the implementation**

There is no unit test for this task — it needs a device, and a fake would only test the fake. Correctness comes from Task 7's listening check.

Create `app/src/main/kotlin/com/snipsnap/app/AndroidAudioSink.kt`:

```kotlin
package com.snipsnap.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.snipsnap.loop.AudioSink

/**
 * The device's native output rate.
 *
 * Ask once, at session start, and bake everything at whatever it says. Android
 * is commonly 48 kHz while SnipSnap is 44.1 kHz throughout, and converting per
 * callback would be the one piece of DSP in the hot path.
 */
fun deviceSampleRate(context: Context): Int {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
}

/**
 * Plays interleaved stereo floats through AudioTrack.
 *
 * Float mode rather than 16-bit so the mixer's output needs no conversion, and
 * MODE_STREAM with a blocking write so AudioTrack's own buffer is the clock —
 * the engine advances exactly as fast as the device drains, with no timer to
 * drift against.
 *
 * The device buffer is deliberately unrelated to the mix block. An interval is
 * 512,000 frames at the spec's defaults; asking AudioTrack for four of those
 * would be a 16 MB request it will simply refuse. [write] already loops on
 * partial writes, so the two sizes are independent — this asks for about 150 ms,
 * which is enough to ride out a slow bake and short enough that a mute is felt
 * rather than waited for.
 *
 * That short buffer is also what keeps the UI honest: the blocking write paces
 * the engine, so the interval counter cannot run ahead of what is audible.
 */
class AndroidAudioSink(override val sampleRate: Int) : AudioSink {

    override val channels = 2

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setBufferSizeInBytes(bufferBytes(sampleRate))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    init { track.play() }

    override fun write(block: FloatArray) {
        // Blocking: this call is the transport's pacing. It returns when the
        // device has room, which is exactly when the next interval is due.
        var written = 0
        while (written < block.size) {
            val n = track.write(block, written, block.size - written, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return // device gone or stopped; drop rather than spin
            written += n
        }
    }

    override fun close() {
        runCatching { track.stop() }
        track.release()
    }

    private companion object {
        const val BYTES_PER_FLOAT = 4
        const val BUFFER_MILLIS = 150

        fun bufferBytes(sampleRate: Int): Int {
            val wanted = sampleRate * BUFFER_MILLIS / 1000 * 2 * BYTES_PER_FLOAT
            val minimum = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            return maxOf(wanted, minimum)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/AndroidAudioSink.kt
git commit -m "AudioTrack in float, blocking writes — the device becomes the clock"
```

---

### Task 6: The grid, in Compose

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/LoopGrid.kt`

**Interfaces:**
- Consumes: `Session`, `Track`, `Arrangement.indexAt` (Plans 02/03).
- Produces: `@Composable fun LoopGrid(session: Session, interval: Int, onToggleTrack: (Int) -> Unit, onSelectBlock: (Int, Int) -> Unit)`. Task 7 hosts it.

Follows the TapeOS language from `docs/UI_DESIGN.md`: bevels rather than flat fills, square corners, no drop shadows, and no emoji. The playing block is lit; a muted track is dimmed whole.

- [ ] **Step 1: Write the implementation**

Create `app/src/main/kotlin/com/snipsnap/app/LoopGrid.kt`:

```kotlin
package com.snipsnap.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.loop.Arrangement
import com.snipsnap.loop.Session

/**
 * TapeOS palette for this screen.
 *
 * Fixed rather than themed for now — the four schemes in UI_DESIGN.md become a
 * Compose theme object later, and hardcoding one keeps this task about the grid.
 */
private object Tape {
    val Desk = Color(0xFF2B2B2B)
    val Panel = Color(0xFFBFBFB4)
    val BevelLight = Color(0xFFE8E8DE)
    val BevelDark = Color(0xFF6E6E64)
    val Lcd = Color(0xFF17251C)
    val Amber = Color(0xFFE8A33A)
    val Ink = Color(0xFF1C1C18)
    val Dim = Color(0xFF8A8A80)
}

/**
 * The six-column grid.
 *
 * Each column is a track; each cell a block in its chain. The cell playing right
 * now is lit amber, which is the only moving part on the screen — the phasing is
 * meant to be read at a glance, not counted.
 */
@Composable
fun LoopGrid(
    session: Session,
    interval: Int,
    onToggleTrack: (Int) -> Unit,
    onSelectBlock: (trackIndex: Int, blockIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().background(Tape.Desk).padding(8.dp),
        horizontalArrangement = LayoutArrangement.spacedBy(6.dp),
    ) {
        session.tracks.forEachIndexed { t, track ->
            TrackColumn(
                track = track,
                playing = Arrangement.indexAt(track.chain.size, interval),
                onToggle = { onToggleTrack(t) },
                onSelect = { b -> onSelectBlock(t, b) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrackColumn(
    track: com.snipsnap.loop.Track,
    playing: Int,
    onToggle: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = LayoutArrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrackHeader(name = track.name, engaged = track.engaged, onToggle = onToggle)
        track.chain.forEachIndexed { b, _ ->
            BlockCell(
                label = "${b + 1}",
                lit = track.engaged && b == playing,
                onClick = { onSelect(b) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrackHeader(name: String, engaged: Boolean, onToggle: () -> Unit) {
    androidx.compose.material3.Text(
        text = name.uppercase(),
        color = if (engaged) Tape.Panel else Tape.Dim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .background(Tape.Lcd)
            .border(width = 1.dp, color = Tape.BevelDark)
            .clickable { onToggle() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

/**
 * One block.
 *
 * Raised bevel when idle, lit when playing. Square corners and no shadow: the
 * TapeOS rule is that depth comes from bevels, never from blur.
 */
@Composable
private fun BlockCell(
    label: String,
    lit: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Text(
        text = label,
        color = if (lit) Tape.Ink else Tape.Ink.copy(alpha = 0.55f),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .aspectRatio(1.2f)
            .background(if (lit) Tape.Amber else Tape.Panel)
            .border(width = 2.dp, color = if (lit) Tape.BevelLight else Tape.BevelDark)
            .clickable { onClick() }
            .padding(4.dp),
    )
}
```

Add the Material 3 dependency to `app/build.gradle.kts`, inside `dependencies {}`:

```kotlin
    implementation("androidx.compose.material3:material3")
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/LoopGrid.kt app/build.gradle.kts
git commit -m "Six columns on a grey desk: the lit block is the only thing that moves"
```

---

### Task 7: Wire it up and listen to it

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/LoopActivity.kt`

**Interfaces:**
- Consumes: everything above — `SessionStore.load`, `KitSampleSource`, `Residency`, `LoopEngine`, `AndroidAudioSink`, `deviceSampleRate`, `LoopGrid`.
- Produces: a running app.

- [ ] **Step 1: Write the implementation**

Create `app/src/main/kotlin/com/snipsnap/app/LoopActivity.kt`:

```kotlin
package com.snipsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import com.snipsnap.loop.KitSampleSource
import com.snipsnap.loop.LoopEngine
import com.snipsnap.loop.Residency
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionStore
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The loop player screen.
 *
 * Three threads, each with one job: the audio thread runs LoopEngine and blocks
 * on AudioTrack; a small pool bakes blocks ahead of it; the UI thread reads the
 * engine's position and draws. They share exactly two things — an AtomicInteger
 * for position and an AtomicReference for pending edits — which is the whole
 * concurrency story.
 */
class LoopActivity : ComponentActivity() {

    private val bakers = Executors.newFixedThreadPool(2)
    private var engine: LoopEngine? = null
    private var sink: AndroidAudioSink? = null
    private var audioThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dir = File(filesDir, "sessions/current")
        val rate = deviceSampleRate(this)

        // Bake at the device's rate, not the MPC's: nothing converts in the
        // callback because nothing needs to.
        val loaded = runCatching { SessionStore.load(dir) }.getOrNull()
            ?.copy(sampleRate = rate)

        setContent {
            // remember, or every recomposition resets the session to what was
            // loaded from disk and throws away the mutes the user just tapped.
            var session by remember { mutableStateOf(loaded) }
            var interval by remember { mutableIntStateOf(0) }

            val s = session
            if (s != null) {
                LoopGrid(
                    session = s,
                    interval = interval,
                    onToggleTrack = { t ->
                        val next = s.copy(
                            tracks = s.tracks.mapIndexed { i, track ->
                                if (i == t) track.copy(engaged = !track.engaged) else track
                            },
                        )
                        session = next
                        engine?.apply(next)
                    },
                    onSelectBlock = { _, _ -> /* block editing lands in a later plan */ },
                )

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (true) {
                        interval = engine?.position() ?: 0
                        kotlinx.coroutines.delay(50)
                    }
                }
            }
        }

        if (loaded != null) start(loaded, dir)
    }

    private fun start(session: Session, dir: File) {
        val audioSink = AndroidAudioSink(session.sampleRate)
        val residency = Residency(session, KitSampleSource(dir), bakers)
        val loopEngine = LoopEngine(residency, audioSink)

        sink = audioSink
        engine = loopEngine

        audioThread = thread(name = "snipsnap-audio", isDaemon = true) {
            // Prime here, not in onCreate. Baking interval 0 is six WAV decodes,
            // six resamples and possibly six slice-retriggers — seconds of work
            // that must never touch the main thread.
            residency.buffersFor(0)
            residency.prefetch(1)
            loopEngine.run()
        }
    }

    override fun onDestroy() {
        engine?.stop()
        audioThread?.join(1_000)
        sink?.close()
        bakers.shutdownNow()
        super.onDestroy()
    }
}
```

Add the coroutines dependency to `app/build.gradle.kts`, inside `dependencies {}`:

```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
```

- [ ] **Step 2: Build and install**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL, app installed on a connected device or emulator.

- [ ] **Step 3: Put a session on the device**

The app reads `filesDir/sessions/current`. Create one on the host and push it:

```bash
# From a JVM scratch program or a Gradle task, write a session folder with
# SessionStore.save plus its WAVs, then:
adb push ./session-out /sdcard/Download/snipsnap-session
adb shell run-as com.snipsnap.app mkdir -p files/sessions
adb shell "run-as com.snipsnap.app cp -r /sdcard/Download/snipsnap-session files/sessions/current"
```

- [ ] **Step 4: Listen — the check no test can perform**

Launch the app and confirm, by ear and by eye:

1. **It plays.** Audio comes out and does not stutter, click at interval boundaries, or underrun.
2. **It phases.** With chains of 2 and 3, the two tracks realign every 6 intervals. Watch the lit cells and count.
3. **Mute is immediate.** Tapping a track header drops it at the next boundary, within one interval, with no click.
4. **The lit block matches what you hear.** If the highlight runs ahead, the poll rate is not the cause and raising it will not help. The engine increments its counter as soon as `sink.write` returns, so the size of the AudioTrack buffer *is* how far ahead the display can get: a buffer holding several intervals lets `write` return immediately and the counter sprint away from the audio. Check `BUFFER_MILLIS` before touching anything in the UI.
5. **Fitting sounds acceptable on pads and vocals.** This is the risk flagged in spec §12: slice-and-retrigger suits drums and may not suit sustained material. Judge it now, while the fix is still cheap.

Record what you hear. Anything wrong here is a real defect, not a tuning note.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/LoopActivity.kt app/build.gradle.kts
git commit -m "It plays: three threads, two atomics, and a grid that lights up"
```

---

## Done when

- `./gradlew :json:test :xpm:test :audio:test :kit:test :mpc3:test :synth:test :loop:test` is green.
- `./gradlew :app:assembleDebug` succeeds.
- CI runs both jobs and the JVM job cannot be masked by an `:app` failure.
- A session on a device plays, phases audibly, and mutes within one interval.
- `loop/build.gradle.kts` still has no Android dependency.

## Not in this plan

- **Editing on the device.** `onSelectBlock` is wired to nothing. Recording, chopping, assigning blocks and building patterns are the capture side of the app and belong with it.
- **The four TapeOS schemes.** `LoopGrid` hardcodes one palette; the theme object with the token table from `docs/UI_DESIGN.md` is its own piece of work.
- **Tempo control in the UI.** `LoopEngine.apply` handles a BPM change correctly and nothing on screen sends one yet.
- **Bounce from the app.** `Bouncer` and `WavSink` work headlessly; exposing them needs a file picker and a progress surface.
- **Underrun instrumentation.** If step 4 finds stuttering, the first thing to add is a count of how often `buffersFor` had to bake rather than hit the cache.
