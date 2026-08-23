# Loop Player 02 — `:loop` Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A headless Kotlin engine that holds a loop-player session, bakes every block to exactly one interval of audio, mixes the engaged tracks, and renders a full phase cycle to a WAV — with no Android and no audio device anywhere in it.

**Architecture:** A new pure-JVM `:loop` module. The session is plain data; the arrangement is a pure function of `(chain sizes, interval index)`; baking turns any block into a `Snip` of exactly `intervalFrames`; mixing sums baked buffers into an output block; an `AudioSink` interface is the only seam where audio leaves. Bounce is that seam with a WAV implementation behind it, which is why playback and export cannot drift apart.

**Tech Stack:** Kotlin 2.0.21, JVM 17, `kotlin.test` on the JUnit Platform, Gradle 8.14.3.

**Spec:** `docs/superpowers/specs/2026-08-22-loop-player-design.md` (sections 04, 05, 06, 07, 08, 10)

**Depends on:** `docs/superpowers/plans/2026-08-22-loop-player-01-audio-io.md` — Task 3 of this plan calls `WavReader.read` and `Resampler.resample`. Plan 01 must be complete and green first.

## Global Constraints

- **Kotlin 2.0.21, `jvmTarget` JVM_17.** Copy `kit/build.gradle.kts` as the template for the new module.
- **`:loop` depends on `:audio`, `:json`, `:kit` and nothing else.** This mirrors `synth/build.gradle.kts`. No Android dependency may enter this module — that is the whole point of the split.
- **No real-time code here.** Nothing in `:loop` may spawn a thread, block on I/O inside a mix call, or allocate inside `Mixer.mix`. Baking is where allocation happens.
- **`Snip(samples: FloatArray, channels: Int, sampleRate: Int)`** — `channels` must be 1 or 2, `sampleRate` > 0, `samples.size % channels == 0`, enforced in its `init`.
- **`Snip.equals` compares `channels`, `sampleRate` and `samples.size` only — never sample values.** Never assert audio correctness with `assertEquals` on two `Snip`s.
- **Six tracks, fixed.** `Session.TRACK_COUNT = 6`. Chains hold 1..8 blocks.
- **`barsPerInterval` is one of 1, 2, 4, 8 and is fixed for the life of a session.** BPM is live; see spec §07.
- **Test style:** `kotlin.test` imports, backtick-quoted test names, private helper factories at the top of the class. Match `audio/src/test/kotlin/com/snipsnap/audio/CleanupTest.kt`.
- **Commit style:** no Conventional Commits. A short evocative sentence, matching e.g. `EQ joins the rack: three plain words, RBJ cookbook underneath`.
- **Test command:** `./gradlew :loop:test --tests 'com.snipsnap.loop.<ClassName>'`

## Deviation from the spec, and why

Spec §05 writes the pattern block as `PatternBlock(steps: List<Step>, kitRef)` with `Step(slot, velocity, microOffset)`. This plan keeps that shape but resolves samples through an interface:

```kotlin
interface SampleSource {
    fun loop(sampleFile: String): Snip?
    fun pad(kit: String, slot: Int): Snip?
}
```

The baker never touches the filesystem directly. Tests use a map-backed fake and need no WAVs on disk; the real implementation reads through `KitStore` and `WavReader`. Without this seam every baker test would require a temp directory and a written kit, which is slow and tests the wrong thing.

---

### Task 1: Module scaffold and the session model

**Files:**
- Create: `loop/build.gradle.kts`
- Modify: `settings.gradle.kts:9` (append after `include(":synth")`)
- Create: `loop/src/main/kotlin/com/snipsnap/loop/Session.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/SessionTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Session`, `Track`, `Block` (sealed), `LoopBlock`, `PatternBlock`, `Step`, and `Session.intervalFrames`. Every later task and all of Plan 03 build on these exact names.

- [ ] **Step 1: Create the module and register it**

Create `loop/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":json"))
    implementation(project(":audio"))
    implementation(project(":kit"))
    testImplementation(kotlin("test"))
}

// Java 17 bytecode so the Android app can consume this module directly.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
```

Append one line to `settings.gradle.kts`:

```kotlin
include(":loop")
```

- [ ] **Step 2: Write the failing test**

Create `loop/src/test/kotlin/com/snipsnap/loop/SessionTest.kt`:

```kotlin
package com.snipsnap.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionTest {

    private fun loopTrack(name: String, blocks: Int) =
        Track(name, (1..blocks).map { LoopBlock("$name-$it.wav") })

    private fun session(vararg sizes: Int, bpm: Float = 90f, bars: Int = 4) =
        Session(
            tracks = sizes.mapIndexed { i, n -> loopTrack("t$i", n) },
            bpm = bpm,
            barsPerInterval = bars,
            sampleRate = 48_000,
        )

    @Test
    fun `computes interval length in frames`() {
        // 4 bars at 90 bpm is 16 beats of 2/3 s = 10.667 s; at 48 kHz that is
        // 512000 frames. This number is the invariant every block bakes to.
        assertEquals(512_000, session(1, 1, 1, 1, 1, 1).intervalFrames)
    }

    @Test
    fun `interval length tracks bpm`() {
        val fast = session(1, 1, 1, 1, 1, 1, bpm = 180f)
        assertEquals(256_000, fast.intervalFrames)
    }

    @Test
    fun `requires exactly six tracks`() {
        val e = assertFailsWith<IllegalArgumentException> { session(1, 1, 1) }
        assertTrue(e.message!!.contains("6"), "message should name the count: ${e.message}")
    }

    @Test
    fun `rejects an empty chain`() {
        assertFailsWith<IllegalArgumentException> {
            Session(
                tracks = List(6) { Track("t$it", emptyList()) },
                bpm = 90f,
                barsPerInterval = 4,
                sampleRate = 48_000,
            )
        }
    }

    @Test
    fun `rejects a chain longer than the cap`() {
        assertFailsWith<IllegalArgumentException> { session(9, 1, 1, 1, 1, 1) }
    }

    @Test
    fun `rejects a bars per interval value that is not a power of two up to eight`() {
        assertFailsWith<IllegalArgumentException> { session(1, 1, 1, 1, 1, 1, bars = 3) }
    }

    @Test
    fun `tracks are engaged by default`() {
        assertTrue(session(1, 1, 1, 1, 1, 1).tracks.all { it.engaged })
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.SessionTest'`
Expected: FAIL — compilation error, `Unresolved reference: Session`.

- [ ] **Step 4: Write minimal implementation**

Create `loop/src/main/kotlin/com/snipsnap/loop/Session.kt`:

```kotlin
package com.snipsnap.loop

import kotlin.math.roundToInt

/**
 * One block in a track's chain.
 *
 * The two kinds differ only in how they become audio. Once baked they are
 * both a buffer of exactly [Session.intervalFrames] — which is what lets one
 * engine play both without knowing the difference.
 */
sealed class Block

/** A captured or chopped WAV, fitted to the interval at bake time. */
data class LoopBlock(val sampleFile: String) : Block() {
    init {
        require(sampleFile.isNotBlank()) { "sampleFile must not be blank" }
        require(!sampleFile.contains('/') && !sampleFile.contains('\\')) {
            "sampleFile must be a bare filename, was '$sampleFile'"
        }
    }
}

/** One hit in a pattern: which pad, how hard, nudged how far off the grid. */
data class Step(
    /** Which 16th of the interval, 0-based. */
    val step: Int,
    /** Pad slot in the referenced kit, 1-based, matching KitPad.slot. */
    val slot: Int,
    val velocity: Float = 1f,
    /** Frames off the grid, positive or negative. Swing and human feel. */
    val microOffset: Int = 0,
) {
    init {
        require(step >= 0) { "step must not be negative: $step" }
        require(slot >= 1) { "slot is 1-based: $slot" }
        require(velocity in 0f..1f) { "velocity out of range: $velocity" }
    }
}

/** A sequence of hits against a kit, rendered to audio at bake time. */
data class PatternBlock(val kit: String, val steps: List<Step>) : Block() {
    init { require(kit.isNotBlank()) { "kit must not be blank" } }
}

/** One column of the grid: a name, a chain, and whether it is heard. */
data class Track(
    val name: String,
    val chain: List<Block>,
    val engaged: Boolean = true,
    val level: Float = 1f,
    val pan: Float = 0f,
) {
    init {
        require(chain.isNotEmpty()) { "track '$name' has an empty chain" }
        require(chain.size <= Session.MAX_CHAIN) {
            "track '$name' has ${chain.size} blocks, cap is ${Session.MAX_CHAIN}"
        }
        require(level >= 0f) { "level must not be negative: $level" }
        require(pan in -1f..1f) { "pan out of range: $pan" }
    }
}

/**
 * The whole grid.
 *
 * Every engaged track plays at once, and at each interval boundary every track
 * advances one block along its own chain. Chains of different lengths drift
 * against each other and only realign after the least common multiple of their
 * lengths — which is the entire point of the feature.
 *
 * [bpm] is live. [barsPerInterval] is not: every baked buffer is exactly one
 * interval long, so changing it would invalidate all of them at once and
 * require truncation rules nobody wants mid-performance.
 */
data class Session(
    val tracks: List<Track>,
    val bpm: Float,
    val barsPerInterval: Int,
    val sampleRate: Int,
) {
    init {
        require(tracks.size == TRACK_COUNT) {
            "a session has exactly $TRACK_COUNT tracks, got ${tracks.size}"
        }
        require(bpm in 40f..220f) { "bpm out of range: $bpm" }
        require(barsPerInterval in VALID_BARS) {
            "barsPerInterval must be one of $VALID_BARS, was $barsPerInterval"
        }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
    }

    /** Frames in one interval. Every block bakes to exactly this many. */
    val intervalFrames: Int
        get() = (barsPerInterval * BEATS_PER_BAR * (60.0 / bpm) * sampleRate).roundToInt()

    /** 16ths in one interval — the resolution a pattern block is written on. */
    val stepsPerInterval: Int get() = STEPS_PER_BAR * barsPerInterval

    companion object {
        const val TRACK_COUNT = 6
        const val MAX_CHAIN = 8
        const val STEPS_PER_BAR = 16
        const val BEATS_PER_BAR = 4
        val VALID_BARS = listOf(1, 2, 4, 8)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.SessionTest'`
Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts loop/build.gradle.kts loop/src/main/kotlin/com/snipsnap/loop/Session.kt loop/src/test/kotlin/com/snipsnap/loop/SessionTest.kt
git commit -m "A seventh module: six tracks, chains, and the length of a bar"
```

---

### Task 2: Arrangement — the whole transport is one integer

**Files:**
- Create: `loop/src/main/kotlin/com/snipsnap/loop/Arrangement.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/ArrangementTest.kt`

**Interfaces:**
- Consumes: `Session`, `Track`, `Block` from Task 1.
- Produces: `Arrangement.blockAt(track: Track, interval: Int): Block`, `Arrangement.indexAt(chainSize: Int, interval: Int): Int`, `Arrangement.cycleIntervals(session: Session): Int`. Task 7 uses `cycleIntervals` to size a bounce; Plan 03's UI uses `indexAt` to light the playing block.

This is the feature's core idea reduced to arithmetic, and it is the part most worth over-testing: it is a pure function, it needs no audio, and everything else assumes it is right.

- [ ] **Step 1: Write the failing test**

Create `loop/src/test/kotlin/com/snipsnap/loop/ArrangementTest.kt`:

```kotlin
package com.snipsnap.loop

import kotlin.test.Test
import kotlin.test.assertEquals

class ArrangementTest {

    private fun track(blocks: Int) = Track("t", (1..blocks).map { LoopBlock("b$it.wav") })

    private fun session(vararg sizes: Int) =
        Session(sizes.map { track(it) }, bpm = 90f, barsPerInterval = 4, sampleRate = 48_000)

    @Test
    fun `a single block chain repeats forever`() {
        for (i in 0 until 50) assertEquals(0, Arrangement.indexAt(1, i))
    }

    @Test
    fun `a two block chain alternates`() {
        assertEquals(listOf(0, 1, 0, 1, 0, 1), (0..5).map { Arrangement.indexAt(2, it) })
    }

    @Test
    fun `a three block chain cycles`() {
        assertEquals(listOf(0, 1, 2, 0, 1, 2), (0..5).map { Arrangement.indexAt(3, it) })
    }

    @Test
    fun `chains of two and three realign every six intervals`() {
        // The phasing claim, stated as a test: they agree at 0 and not again
        // until 6.
        val agree = (0..12).filter { Arrangement.indexAt(2, it) == 0 && Arrangement.indexAt(3, it) == 0 }
        assertEquals(listOf(0, 6, 12), agree)
    }

    @Test
    fun `the sketch's six tracks repeat after twelve intervals`() {
        // Chains of 2, 3, 2, 1, 4, 2 — LCM(2,3,1,4) = 12. This is the worked
        // example in spec section 01.
        assertEquals(12, Arrangement.cycleIntervals(session(2, 3, 2, 1, 4, 2)))
    }

    @Test
    fun `all single block chains give a cycle of one`() {
        assertEquals(1, Arrangement.cycleIntervals(session(1, 1, 1, 1, 1, 1)))
    }

    @Test
    fun `coprime chains multiply out`() {
        // 5, 7 and 8 are pairwise coprime except 8 and 8: LCM(5,7,8,3,1,1) = 840.
        assertEquals(840, Arrangement.cycleIntervals(session(5, 7, 8, 3, 1, 1)))
    }

    @Test
    fun `the state at one cycle length matches the state at zero`() {
        val s = session(2, 3, 2, 1, 4, 2)
        val cycle = Arrangement.cycleIntervals(s)
        for (t in s.tracks) {
            assertEquals(
                Arrangement.blockAt(t, 0),
                Arrangement.blockAt(t, cycle),
                "track ${t.name} did not return to its first block",
            )
        }
    }

    @Test
    fun `shortening a chain past the playing index wraps instead of throwing`() {
        // Spec section 07: the current block finishes, then i % newSize takes
        // over. At interval 5 a 4-block chain is on index 1; cut to 2 blocks
        // and index 1 is still valid, cut to 1 and it must fall back to 0.
        assertEquals(1, Arrangement.indexAt(4, 5))
        assertEquals(1, Arrangement.indexAt(2, 5))
        assertEquals(0, Arrangement.indexAt(1, 5))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.ArrangementTest'`
Expected: FAIL — compilation error, `Unresolved reference: Arrangement`.

- [ ] **Step 3: Write minimal implementation**

Create `loop/src/main/kotlin/com/snipsnap/loop/Arrangement.kt`:

```kotlin
package com.snipsnap.loop

/**
 * Where every track is at a given moment.
 *
 * The whole transport is one integer. A track with n blocks plays index
 * `interval % n`, so there are no per-track cursors, nothing to keep in sync,
 * and nothing that can drift. Playback position is serialisable, resumable and
 * testable without a single sample of audio.
 */
object Arrangement {

    /** Which block of an n-long chain plays at [interval]. */
    fun indexAt(chainSize: Int, interval: Int): Int {
        require(chainSize > 0) { "chainSize must be positive: $chainSize" }
        require(interval >= 0) { "interval must not be negative: $interval" }
        return interval % chainSize
    }

    fun blockAt(track: Track, interval: Int): Block =
        track.chain[indexAt(track.chain.size, interval)]

    /**
     * How many intervals before the whole grid returns to its starting state.
     *
     * The least common multiple of the chain lengths — and the length of a
     * bounce. It grows fast: chains of 5, 7, 8 and 3 give 840 intervals, which
     * at 4 bars each is a 3,360-bar render. Show this number before committing
     * to one.
     */
    fun cycleIntervals(session: Session): Int =
        session.tracks.fold(1) { acc, t -> lcm(acc, t.chain.size) }

    private fun lcm(a: Int, b: Int): Int = a / gcd(a, b) * b

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.ArrangementTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/Arrangement.kt loop/src/test/kotlin/com/snipsnap/loop/ArrangementTest.kt
git commit -m "Two against three: the phasing, proved in arithmetic"
```

---

### Task 3: SampleSource and baking a loop block that already fits

**Files:**
- Create: `loop/src/main/kotlin/com/snipsnap/loop/SampleSource.kt`
- Create: `loop/src/main/kotlin/com/snipsnap/loop/BlockBaker.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/BlockBakerTest.kt`

**Interfaces:**
- Consumes: `Session`, `LoopBlock` (Task 1); `Snip`, `Resampler.resample(snip: Snip, targetRate: Int): Snip` (Plan 01 Task 5).
- Produces: `interface SampleSource { fun loop(sampleFile: String): Snip?; fun pad(kit: String, slot: Int): Snip? }` and `BlockBaker.bake(block: Block, session: Session, source: SampleSource): Snip`. Tasks 4–8 and Plan 03 all call `bake` with this exact signature.

- [ ] **Step 1: Write the failing test**

Create `loop/src/test/kotlin/com/snipsnap/loop/BlockBakerTest.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlockBakerTest {

    /** Map-backed source: baking should never need a file on disk. */
    private class FakeSource(
        private val loops: Map<String, Snip> = emptyMap(),
        private val pads: Map<Pair<String, Int>, Snip> = emptyMap(),
    ) : SampleSource {
        override fun loop(sampleFile: String): Snip? = loops[sampleFile]
        override fun pad(kit: String, slot: Int): Snip? = pads[kit to slot]
    }

    private fun session(bpm: Float = 90f, bars: Int = 4, rate: Int = 48_000) =
        Session(
            tracks = List(6) { Track("t$it", listOf(LoopBlock("a.wav"))) },
            bpm = bpm,
            barsPerInterval = bars,
            sampleRate = rate,
        )

    private fun dc(frames: Int, level: Float, channels: Int = 2, rate: Int = 48_000) =
        Snip(FloatArray(frames * channels) { level }, channels, rate)

    @Test
    fun `bakes a loop that is already exactly one interval`() {
        val s = session()
        val source = FakeSource(loops = mapOf("a.wav" to dc(s.intervalFrames, 0.5f)))
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, source)

        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(48_000, baked.sampleRate)
        assertTrue(abs(baked.samples[1000] - 0.5f) < 1e-6f, "audio should pass through untouched")
    }

    @Test
    fun `pads a loop that is a little short`() {
        val s = session()
        // 1% short: inside the 2% tolerance, so trim/pad rather than slice.
        val short = dc((s.intervalFrames * 0.99).toInt(), 0.5f)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to short)))

        assertEquals(s.intervalFrames, baked.frameCount)
        assertTrue(abs(baked.samples[1000] - 0.5f) < 1e-6f, "head should survive")
        assertEquals(0f, baked.samples[baked.samples.size - 1], "tail should be silence")
    }

    @Test
    fun `trims a loop that is a little long`() {
        val s = session()
        val long = dc((s.intervalFrames * 1.01).toInt(), 0.5f)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to long)))
        assertEquals(s.intervalFrames, baked.frameCount)
    }

    @Test
    fun `resamples a loop recorded at a different rate`() {
        val s = session(rate = 48_000)
        // Same musical length, wrong rate: 10.667 s at 44.1 kHz.
        val at441 = dc((10.6667 * 44_100).toInt(), 0.5f, rate = 44_100)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to at441)))

        assertEquals(48_000, baked.sampleRate)
        assertEquals(s.intervalFrames, baked.frameCount)
    }

    @Test
    fun `bakes silence when the sample is missing`() {
        val s = session()
        val baked = BlockBaker.bake(LoopBlock("gone.wav"), s, FakeSource())
        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(0f, baked.peak(), "a missing sample is silence, not a crash")
    }

    @Test
    fun `always returns stereo so the mixer never has to branch`() {
        val s = session()
        val mono = dc(s.intervalFrames, 0.5f, channels = 1)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to mono)))
        assertEquals(2, baked.channels)
        assertEquals(s.intervalFrames, baked.frameCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.BlockBakerTest'`
Expected: FAIL — compilation error, `Unresolved reference: SampleSource`.

- [ ] **Step 3: Write minimal implementation**

Create `loop/src/main/kotlin/com/snipsnap/loop/SampleSource.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip

/**
 * Where the baker gets its audio.
 *
 * An interface rather than a File argument so baking can be tested without a
 * directory of WAVs, and so the app can cache decoded samples however it likes
 * without the engine knowing.
 *
 * Both methods return null for "not found". A missing sample bakes to silence
 * rather than throwing: one deleted file should not stop the grid.
 */
interface SampleSource {
    /** A loop block's audio, by bare filename. */
    fun loop(sampleFile: String): Snip?

    /** A pattern step's audio: one pad of a named kit, slot 1-based. */
    fun pad(kit: String, slot: Int): Snip?
}
```

Create `loop/src/main/kotlin/com/snipsnap/loop/BlockBaker.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip

/**
 * Turns any block into exactly one interval of audio.
 *
 * This is the whole reason one engine can play both kinds of block. After
 * baking, a loop file and a pattern are indistinguishable: a stereo buffer of
 * [Session.intervalFrames] frames at the session's rate. The audio thread never
 * learns there were two kinds.
 *
 * Baking is off-thread work by definition — it decodes, resamples, allocates
 * and slices. Nothing here belongs in a callback.
 */
object BlockBaker {

    /** How far off the interval a loop can be before it gets sliced instead of trimmed. */
    const val FIT_TOLERANCE = 0.02

    fun bake(block: Block, session: Session, source: SampleSource): Snip = when (block) {
        is LoopBlock -> bakeLoop(block, session, source)
        is PatternBlock -> silence(session)
    }

    private fun bakeLoop(block: LoopBlock, session: Session, source: SampleSource): Snip {
        val raw = source.loop(block.sampleFile) ?: return silence(session)
        val atRate = Resampler.resample(raw, session.sampleRate)
        val stereo = toStereo(atRate)
        return conform(stereo, session.intervalFrames)
    }

    /** Trim or zero-pad to exactly [targetFrames]. */
    private fun conform(snip: Snip, targetFrames: Int): Snip {
        if (snip.frameCount == targetFrames) return snip
        val out = FloatArray(targetFrames * snip.channels)
        System.arraycopy(snip.samples, 0, out, 0, minOf(snip.samples.size, out.size))
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** Everything downstream is stereo, so the mixer never branches on channel count. */
    internal fun toStereo(snip: Snip): Snip {
        if (snip.channels == 2) return snip
        val out = FloatArray(snip.frameCount * 2)
        for (f in 0 until snip.frameCount) {
            val v = snip.samples[f]
            out[f * 2] = v
            out[f * 2 + 1] = v
        }
        return Snip(out, 2, snip.sampleRate)
    }

    internal fun silence(session: Session): Snip =
        Snip(FloatArray(session.intervalFrames * 2), 2, session.sampleRate)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.BlockBakerTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/SampleSource.kt loop/src/main/kotlin/com/snipsnap/loop/BlockBaker.kt loop/src/test/kotlin/com/snipsnap/loop/BlockBakerTest.kt
git commit -m "One interval, always: the baker flattens both kinds of block"
```

---

### Task 4: Fitting a loop that does not already fit

**Files:**
- Modify: `loop/src/main/kotlin/com/snipsnap/loop/BlockBaker.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/BlockBakerTest.kt`

**Interfaces:**
- Consumes: `BlockBaker.bake` from Task 3; `Chopper.byTransients(snip, maxSlices, config, cleanup, snapToZeroCrossing): List<Slice>`, `Chopper.SLICE_CLEANUP`, and `Slice(snip, sourceFrame, onset)` from `:audio`.
- Produces: same `bake` signature. Loop blocks outside `FIT_TOLERANCE` are now sliced at their transients and re-placed on the interval grid.

Captured audio is never the right length. A four-bar break grabbed off a video is 4.3 bars at 87 BPM, and `Tempo.kt` says outright that its estimate is "for filename labeling only, no FFT, no beat grid" — so nothing can conform it for free. Slicing at onsets and re-placing the slices unmodified is the Recycle/REX approach and reuses detection SnipSnap already ships.

- [ ] **Step 1: Write the failing test**

Append to `BlockBakerTest.kt`, inside the class:

```kotlin
    /** Four evenly spaced clicks: unambiguous onsets for the detector. */
    private fun clicks(frames: Int, count: Int, rate: Int = 48_000): Snip {
        val out = FloatArray(frames * 2)
        val every = frames / count
        for (c in 0 until count) {
            val at = c * every
            for (i in 0 until 600) {
                val f = at + i
                if (f >= frames) break
                // Short decaying burst — a transient the onset detector will find.
                val v = (1f - i / 600f) * if (i % 3 == 0) 0.9f else -0.9f
                out[f * 2] = v
                out[f * 2 + 1] = v
            }
        }
        return Snip(out, 2, rate)
    }

    @Test
    fun `slices and re-places a loop that is far too long`() {
        val s = session()
        // 40% too long: well outside tolerance, so this must be sliced.
        val tooLong = clicks((s.intervalFrames * 1.4).toInt(), count = 4)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to tooLong)))

        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(2, baked.channels)
        assertTrue(baked.peak() > 0.3f, "slices should still be audible, peak was ${baked.peak()}")
    }

    @Test
    fun `slices and re-places a loop that is far too short`() {
        val s = session()
        val tooShort = clicks((s.intervalFrames * 0.6).toInt(), count = 4)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to tooShort)))

        assertEquals(s.intervalFrames, baked.frameCount)
        assertTrue(baked.peak() > 0.3f, "slices should still be audible, peak was ${baked.peak()}")
    }

    @Test
    fun `keeps the first hit at the start of the interval`() {
        val s = session()
        val tooLong = clicks((s.intervalFrames * 1.4).toInt(), count = 4)
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to tooLong)))

        // Downbeat integrity: whatever else fitting does, the first slice must
        // land at or near frame 0 or every loop starts late.
        var firstLoud = -1
        for (f in 0 until baked.frameCount) {
            if (abs(baked.samples[f * 2]) > 0.2f) { firstLoud = f; break }
        }
        assertTrue(firstLoud in 0..2_000, "first hit landed at frame $firstLoud")
    }

    @Test
    fun `falls back to trim and pad when there are no transients to slice on`() {
        val s = session()
        // A steady tone 40% too long has no onsets; slicing has nothing to work
        // with and it must not return silence.
        val tone = Snip(
            FloatArray((s.intervalFrames * 1.4).toInt() * 2) { 0.4f },
            2,
            48_000,
        )
        val baked = BlockBaker.bake(LoopBlock("a.wav"), s, FakeSource(loops = mapOf("a.wav" to tone)))

        assertEquals(s.intervalFrames, baked.frameCount)
        assertTrue(baked.peak() > 0.3f, "fallback must not produce silence")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.BlockBakerTest'`
Expected: FAIL on `keeps the first hit at the start of the interval` and both slicing tests — Task 3's `conform` simply truncates, so a 40%-too-long loop loses its last hits and a 40%-too-short one ends in silence rather than being spread across the interval.

- [ ] **Step 3: Write minimal implementation**

In `BlockBaker.kt`, add imports:

```kotlin
import com.snipsnap.audio.Chopper
import kotlin.math.abs
import kotlin.math.roundToInt
```

Replace the body of `bakeLoop`:

```kotlin
    private fun bakeLoop(block: LoopBlock, session: Session, source: SampleSource): Snip {
        val raw = source.loop(block.sampleFile) ?: return silence(session)
        val atRate = Resampler.resample(raw, session.sampleRate)
        val stereo = toStereo(atRate)
        val target = session.intervalFrames
        if (stereo.frameCount == 0) return silence(session)

        val drift = abs(stereo.frameCount - target).toDouble() / target
        return if (drift <= FIT_TOLERANCE) conform(stereo, target) else retrigger(stereo, target)
    }

    /**
     * Fit by slicing at transients and re-placing the slices on the new grid.
     *
     * The slices themselves are untouched — same pitch, same length, same decay.
     * Only their positions scale. Stretched out, the gaps between hits grow;
     * squeezed, hits overlap and sum. That is what Recycle did, what an MPC's
     * chop-and-program does, and what SnipSnap's own Chopper was already built
     * for.
     */
    private fun retrigger(snip: Snip, targetFrames: Int): Snip {
        val slices = Chopper.byTransients(
            snip,
            maxSlices = 32,
            cleanup = Chopper.SLICE_CLEANUP,
        )
        // Sustained material has no onsets to cut on. Trimming is a worse fit
        // but an honest one; silence would be a bug.
        if (slices.isEmpty()) return conform(snip, targetFrames)

        val out = FloatArray(targetFrames * 2)
        val scale = targetFrames.toDouble() / snip.frameCount
        for (slice in slices) {
            val at = (slice.sourceFrame * scale).roundToInt()
            if (at >= targetFrames) continue
            val room = (targetFrames - at) * 2
            val n = minOf(slice.snip.samples.size, room)
            val base = at * 2
            for (i in 0 until n) out[base + i] += slice.snip.samples[i]
        }
        return Snip(out, 2, snip.sampleRate)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.BlockBakerTest'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/BlockBaker.kt loop/src/test/kotlin/com/snipsnap/loop/BlockBakerTest.kt
git commit -m "Nothing captured is ever four bars: slice it, move it, keep the downbeat"
```

---

### Task 5: Baking a pattern block

**Files:**
- Modify: `loop/src/main/kotlin/com/snipsnap/loop/BlockBaker.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/BlockBakerTest.kt`

**Interfaces:**
- Consumes: `BlockBaker.bake` (Task 4), `PatternBlock`, `Step`, `Session.stepsPerInterval` (Task 1), `SampleSource.pad` (Task 3).
- Produces: same `bake` signature, now rendering `PatternBlock` instead of returning silence.

`synth/Groove.kt` renders a generated pattern by mixing pads into one buffer at computed step offsets — but it mixes into `FloatArray(total)` mono, force-converting every pad with `Cleanup.toMono`. This renderer borrows the placement idea and re-derives it for interleaved stereo, with user-authored steps and per-step velocity.

- [ ] **Step 1: Write the failing test**

Append to `BlockBakerTest.kt`, inside the class:

```kotlin
    private fun blip(frames: Int, level: Float) =
        Snip(FloatArray(frames * 2) { level }, 2, 48_000)

    @Test
    fun `renders a pattern block to one interval`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.5f)))
        val block = PatternBlock("kit", listOf(Step(step = 0, slot = 1)))
        val baked = BlockBaker.bake(block, s, src)

        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(2, baked.channels)
        assertTrue(abs(baked.samples[0] - 0.5f) < 1e-6f, "hit should land on frame 0")
    }

    @Test
    fun `places a step at its sixteenth of the interval`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.5f)))
        // 64 steps in a 4-bar interval; step 32 is the halfway point.
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(32, 1))), s, src)

        val expected = s.intervalFrames / 2
        assertTrue(abs(baked.samples[expected * 2] - 0.5f) < 1e-6f, "no hit at the midpoint")
        assertEquals(0f, baked.samples[0], "nothing should be on the downbeat")
    }

    @Test
    fun `scales a hit by its velocity`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.8f)))
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(0, 1, velocity = 0.5f))), s, src)
        assertTrue(abs(baked.samples[0] - 0.4f) < 1e-6f, "got ${baked.samples[0]}")
    }

    @Test
    fun `sums overlapping hits`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.3f)))
        val baked = BlockBaker.bake(
            PatternBlock("kit", listOf(Step(0, 1), Step(0, 1))),
            s,
            src,
        )
        assertTrue(abs(baked.samples[0] - 0.6f) < 1e-6f, "got ${baked.samples[0]}")
    }

    @Test
    fun `applies a micro offset`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(500, 0.5f)))
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(0, 1, microOffset = 480))), s, src)

        assertEquals(0f, baked.samples[0], "nudged hits should not be on the grid")
        assertTrue(abs(baked.samples[480 * 2] - 0.5f) < 1e-6f, "hit should be 480 frames late")
    }

    @Test
    fun `skips a step whose pad is missing`() {
        val s = session()
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(0, 99))), s, FakeSource())
        assertEquals(s.intervalFrames, baked.frameCount)
        assertEquals(0f, baked.peak(), "a missing pad is silence, not a crash")
    }

    @Test
    fun `clips a hit that would run past the end of the interval`() {
        val s = session()
        val long = blip(s.intervalFrames, 0.5f)
        val src = FakeSource(pads = mapOf(("kit" to 1) to long))
        // Last 16th: most of this pad has nowhere to go.
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(63, 1))), s, src)
        assertEquals(s.intervalFrames, baked.frameCount)
    }

    @Test
    fun `ignores a step beyond the interval's resolution`() {
        val s = session()
        val src = FakeSource(pads = mapOf(("kit" to 1) to blip(1_000, 0.5f)))
        // 64 steps exist; step 64 is off the end.
        val baked = BlockBaker.bake(PatternBlock("kit", listOf(Step(64, 1))), s, src)
        assertEquals(0f, baked.peak())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.BlockBakerTest'`
Expected: FAIL — every pattern test except `skips a step whose pad is missing` and `ignores a step beyond the interval's resolution`, because `bake` currently returns silence for `PatternBlock`.

- [ ] **Step 3: Write minimal implementation**

In `BlockBaker.kt`, replace the `PatternBlock` arm:

```kotlin
        is PatternBlock -> silence(session)
```

with:

```kotlin
        is PatternBlock -> bakePattern(block, session, source)
```

Add the renderer next to `bakeLoop`:

```kotlin
    /**
     * Render a pattern to one interval.
     *
     * Groove.kt does something like this for a generated pattern, but it mixes
     * mono and picks its own hits. This takes authored steps, keeps stereo, and
     * honours velocity and micro-offsets — a pattern block has to reproduce
     * exactly what the user wrote, every cycle.
     */
    private fun bakePattern(block: PatternBlock, session: Session, source: SampleSource): Snip {
        val target = session.intervalFrames
        val out = FloatArray(target * 2)
        val stepFrames = target.toDouble() / session.stepsPerInterval

        for (step in block.steps) {
            if (step.step >= session.stepsPerInterval) continue
            val pad = source.pad(block.kit, step.slot) ?: continue
            val stereo = toStereo(Resampler.resample(pad, session.sampleRate))

            val at = (step.step * stepFrames).roundToInt() + step.microOffset
            if (at >= target) continue
            val from = if (at < 0) -at else 0
            val base = (at + from) * 2
            val room = (target - at - from) * 2
            val n = minOf(stereo.samples.size - from * 2, room)
            if (n <= 0) continue

            for (i in 0 until n) out[base + i] += stereo.samples[from * 2 + i] * step.velocity
        }
        return Snip(out, 2, session.sampleRate)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.BlockBakerTest'`
Expected: PASS, 18 tests.

- [ ] **Step 5: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/BlockBaker.kt loop/src/test/kotlin/com/snipsnap/loop/BlockBakerTest.kt
git commit -m "Patterns bake too: authored steps, stereo, velocity honoured"
```

---

### Task 6: Mixer and the sink seam

**Files:**
- Create: `loop/src/main/kotlin/com/snipsnap/loop/Mixer.kt`
- Create: `loop/src/main/kotlin/com/snipsnap/loop/AudioSink.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/MixerTest.kt`

**Interfaces:**
- Consumes: `Track`, `Session` (Task 1); `Snip` from `:audio`.
- Produces: `Mixer.mix(buffers: List<Snip>, tracks: List<Track>, out: FloatArray)` and `interface AudioSink { val sampleRate: Int; val channels: Int; fun write(block: FloatArray); fun close() }`. Task 7 implements `AudioSink` for WAV; Plan 03 implements it for AudioTrack.

- [ ] **Step 1: Write the failing test**

Create `loop/src/test/kotlin/com/snipsnap/loop/MixerTest.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MixerTest {

    private fun buf(frames: Int, level: Float) =
        Snip(FloatArray(frames * 2) { level }, 2, 48_000)

    private fun tracks(vararg engaged: Boolean) =
        engaged.mapIndexed { i, on ->
            Track("t$i", listOf(LoopBlock("a.wav")), engaged = on)
        }

    @Test
    fun `sums engaged tracks`() {
        val out = FloatArray(20)
        Mixer.mix(List(3) { buf(10, 0.2f) }, tracks(true, true, true), out)
        for (v in out) assertTrue(abs(v - 0.6f) < 1e-6f, "got $v")
    }

    @Test
    fun `skips muted tracks`() {
        val out = FloatArray(20)
        Mixer.mix(List(3) { buf(10, 0.2f) }, tracks(true, false, true), out)
        for (v in out) assertTrue(abs(v - 0.4f) < 1e-6f, "got $v")
    }

    @Test
    fun `applies track level`() {
        val out = FloatArray(20)
        val t = listOf(Track("t", listOf(LoopBlock("a.wav")), level = 0.5f))
        Mixer.mix(listOf(buf(10, 0.8f)), t, out)
        for (v in out) assertTrue(abs(v - 0.4f) < 1e-6f, "got $v")
    }

    @Test
    fun `pans hard left`() {
        val out = FloatArray(20)
        val t = listOf(Track("t", listOf(LoopBlock("a.wav")), pan = -1f))
        Mixer.mix(listOf(buf(10, 0.5f)), t, out)
        for (f in 0 until 10) {
            assertTrue(abs(out[f * 2] - 0.5f) < 1e-6f, "left should be untouched")
            assertEquals(0f, out[f * 2 + 1], "right should be silent")
        }
    }

    @Test
    fun `clears the output buffer before summing`() {
        // The audio thread reuses one buffer forever. Stale audio from the last
        // block must not survive into this one.
        val out = FloatArray(20) { 9f }
        Mixer.mix(listOf(buf(10, 0.1f)), tracks(true), out)
        for (v in out) assertTrue(abs(v - 0.1f) < 1e-6f, "stale sample survived: $v")
    }

    @Test
    fun `all tracks muted gives silence`() {
        val out = FloatArray(20) { 9f }
        Mixer.mix(List(2) { buf(10, 0.5f) }, tracks(false, false), out)
        for (v in out) assertEquals(0f, v)
    }

    @Test
    fun `rejects a buffer and track count mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            Mixer.mix(listOf(buf(10, 0.1f)), tracks(true, true), FloatArray(20))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.MixerTest'`
Expected: FAIL — compilation error, `Unresolved reference: Mixer`.

- [ ] **Step 3: Write minimal implementation**

Create `loop/src/main/kotlin/com/snipsnap/loop/AudioSink.kt`:

```kotlin
package com.snipsnap.loop

/**
 * Where mixed audio goes.
 *
 * The only seam between the engine and the outside world, and the reason
 * playback and bounce cannot drift apart: live output is an Android
 * implementation of this interface, and export is a WAV implementation of the
 * same one, fed by the same mixer over the same intervals.
 *
 * Implementations must accept interleaved stereo floats and must not throw
 * from [write] — a sink that fails should degrade to dropping audio, not take
 * the transport down with it.
 *
 * **The caller owns the array and reuses it.** Both the engine and the bouncer
 * hand the same buffer to [write] every interval, so an implementation must
 * consume it or copy it before returning. Retaining the reference gets you the
 * next interval's audio in place of this one, silently.
 */
interface AudioSink {
    val sampleRate: Int
    val channels: Int

    /** Consume one block of interleaved stereo samples. */
    fun write(block: FloatArray)

    fun close()
}
```

Create `loop/src/main/kotlin/com/snipsnap/loop/Mixer.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import kotlin.math.min

/**
 * Sums the engaged tracks into one output block.
 *
 * This is the only code that runs on the audio thread, so it does exactly one
 * thing: reads floats and adds them. No decoding, no allocation, no branching
 * on block type — the baker already made every buffer the same shape.
 *
 * Constant-power panning would need two sqrt calls per track per block; a
 * linear law costs two multiplies and is inaudibly different at the small pan
 * amounts a six-track grid actually uses.
 */
object Mixer {

    fun mix(buffers: List<Snip>, tracks: List<Track>, out: FloatArray) {
        require(buffers.size == tracks.size) {
            "got ${buffers.size} buffers for ${tracks.size} tracks"
        }
        out.fill(0f)

        for (t in tracks.indices) {
            val track = tracks[t]
            if (!track.engaged || track.level == 0f) continue

            val src = buffers[t].samples
            val n = min(src.size, out.size)
            val leftGain = track.level * min(1f, 1f - track.pan)
            val rightGain = track.level * min(1f, 1f + track.pan)

            var i = 0
            while (i + 1 < n) {
                out[i] += src[i] * leftGain
                out[i + 1] += src[i + 1] * rightGain
                i += 2
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.MixerTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/Mixer.kt loop/src/main/kotlin/com/snipsnap/loop/AudioSink.kt loop/src/test/kotlin/com/snipsnap/loop/MixerTest.kt
git commit -m "The seam: one mixer, one sink interface, two destinations"
```

---

### Task 7: Bounce — the cycle rendered through the sink

**Files:**
- Create: `loop/src/main/kotlin/com/snipsnap/loop/Bouncer.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/BouncerTest.kt`

**Interfaces:**
- Consumes: `Arrangement.cycleIntervals`, `Arrangement.blockAt` (Task 2); `BlockBaker.bake` (Task 5); `Mixer.mix`, `AudioSink` (Task 6); `WavWriter.write(file: File, snip: Snip, depth: WavWriter.BitDepth, allowNonMpcRate: Boolean): File` from `:audio`.
- Produces: `Bouncer.render(session: Session, source: SampleSource, intervals: Int = 0): Snip`, `Bouncer.toSink(session: Session, source: SampleSource, sink: AudioSink, intervals: Int)`, `class WavSink(file: File, sampleRate: Int) : AudioSink`.

- [ ] **Step 1: Write the failing test**

Create `loop/src/test/kotlin/com/snipsnap/loop/BouncerTest.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BouncerTest {

    private class FakeSource(private val loops: Map<String, Snip>) : SampleSource {
        override fun loop(sampleFile: String): Snip? = loops[sampleFile]
        override fun pad(kit: String, slot: Int): Snip? = null
    }

    /** A short session so a full cycle is quick to render. */
    private fun session(vararg sizes: Int) = Session(
        tracks = sizes.mapIndexed { i, n ->
            Track("t$i", (1..n).map { LoopBlock("t$i-$it.wav") })
        },
        bpm = 200f,
        barsPerInterval = 1,
        sampleRate = 48_000,
    )

    private fun dc(frames: Int, level: Float) =
        Snip(FloatArray(frames * 2) { level }, 2, 48_000)

    private fun sourceFor(session: Session, level: Float): SampleSource {
        val map = HashMap<String, Snip>()
        for (t in session.tracks) {
            for (b in t.chain) map[(b as LoopBlock).sampleFile] = dc(session.intervalFrames, level)
        }
        return FakeSource(map)
    }

    @Test
    fun `renders one full cycle by default`() {
        val s = session(2, 3, 1, 1, 1, 1) // LCM(2,3) = 6
        val out = Bouncer.render(s, sourceFor(s, 0.1f))
        assertEquals(6 * s.intervalFrames, out.frameCount)
        assertEquals(48_000, out.sampleRate)
        assertEquals(2, out.channels)
    }

    @Test
    fun `renders an explicit number of intervals`() {
        val s = session(2, 3, 1, 1, 1, 1)
        val out = Bouncer.render(s, sourceFor(s, 0.1f), intervals = 2)
        assertEquals(2 * s.intervalFrames, out.frameCount)
    }

    @Test
    fun `sums all six tracks`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val out = Bouncer.render(s, sourceFor(s, 0.1f))
        // Six tracks at 0.1 each, centre-panned, is 0.6.
        assertTrue(abs(out.samples[100] - 0.6f) < 1e-5f, "got ${out.samples[100]}")
    }

    @Test
    fun `writes a wav a reader can decode`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val file = File.createTempFile("snipsnap-bounce", ".wav")
        file.deleteOnExit()

        WavSink(file, s.sampleRate).use { sink ->
            Bouncer.toSink(s, sourceFor(s, 0.1f), sink, intervals = 2)
        }

        val decoded = WavReader.read(file)
        assertEquals(48_000, decoded.sampleRate)
        assertEquals(2, decoded.channels)
        assertEquals(2 * s.intervalFrames, decoded.frameCount)
    }

    @Test
    fun `a muted track contributes nothing`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val muted = s.copy(tracks = s.tracks.mapIndexed { i, t -> t.copy(engaged = i == 0) })
        val out = Bouncer.render(muted, sourceFor(s, 0.1f))
        assertTrue(abs(out.samples[100] - 0.1f) < 1e-5f, "got ${out.samples[100]}")
    }

    @Test
    fun `the second interval differs from the first when a chain advances`() {
        // Track 0 has two blocks with different levels: interval 0 and interval
        // 1 must not be identical, or the chain never advanced.
        val s = session(2, 1, 1, 1, 1, 1)
        val map = HashMap<String, Snip>()
        map["t0-1.wav"] = dc(s.intervalFrames, 0.5f)
        map["t0-2.wav"] = dc(s.intervalFrames, 0.1f)
        for (t in s.tracks.drop(1)) {
            for (b in t.chain) map[(b as LoopBlock).sampleFile] = dc(s.intervalFrames, 0f)
        }
        val out = Bouncer.render(s, FakeSource(map), intervals = 2)

        val first = out.samples[100]
        val second = out.samples[(s.intervalFrames + 100) * 2]
        assertTrue(abs(first - 0.5f) < 1e-5f, "first interval was $first")
        assertTrue(abs(second - 0.1f) < 1e-5f, "second interval was $second")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.BouncerTest'`
Expected: FAIL — compilation error, `Unresolved reference: Bouncer`.

- [ ] **Step 3: Write minimal implementation**

Create `loop/src/main/kotlin/com/snipsnap/loop/Bouncer.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.Closeable
import java.io.File

/**
 * Renders a session offline.
 *
 * Deliberately the same path as playback: bake the blocks for an interval, mix
 * them, hand the block to a sink. Only the sink differs. If bounce and playback
 * ever disagree, it is a bug in one sink, not two engines that drifted.
 */
object Bouncer {

    /**
     * Render [intervals] of [session], or one full phase cycle when 0.
     *
     * A cycle is the least common multiple of the chain lengths, which grows
     * fast — chains of 5, 7, 8 and 3 give 840 intervals. Call
     * [Arrangement.cycleIntervals] and show the number before rendering one.
     */
    fun render(session: Session, source: SampleSource, intervals: Int = 0): Snip {
        val count = if (intervals > 0) intervals else Arrangement.cycleIntervals(session)
        val sink = BufferSink(session.sampleRate)
        toSink(session, source, sink, count)
        return sink.toSnip()
    }

    fun toSink(session: Session, source: SampleSource, sink: AudioSink, intervals: Int) {
        require(intervals > 0) { "intervals must be positive: $intervals" }
        val block = FloatArray(session.intervalFrames * 2)

        // Bake once per distinct block rather than once per interval: a 1-block
        // track on an 840-interval cycle would otherwise be baked 840 times.
        val cache = HashMap<Block, Snip>()

        for (i in 0 until intervals) {
            val buffers = session.tracks.map { track ->
                val b = Arrangement.blockAt(track, i)
                cache.getOrPut(b) { BlockBaker.bake(b, session, source) }
            }
            Mixer.mix(buffers, session.tracks, block)
            sink.write(block)
        }
    }

    /** Accumulates blocks in memory so [render] can hand back a Snip. */
    private class BufferSink(override val sampleRate: Int) : AudioSink {
        override val channels = 2
        private val chunks = ArrayList<FloatArray>()

        override fun write(block: FloatArray) { chunks.add(block.copyOf()) }
        override fun close() {}

        fun toSnip(): Snip {
            val total = chunks.sumOf { it.size }
            val out = FloatArray(total)
            var at = 0
            for (c in chunks) { System.arraycopy(c, 0, out, at, c.size); at += c.size }
            return Snip(out, 2, sampleRate)
        }
    }
}

/**
 * A sink that writes a WAV.
 *
 * Buffers the whole render and writes on [close] because WavWriter takes a
 * finished Snip. A full cycle at 4 bars and 90 BPM is roughly 4 MB per
 * interval — fine for a bounce, which is not something you do sixty times a
 * second.
 */
class WavSink(
    private val file: File,
    override val sampleRate: Int,
    private val depth: WavWriter.BitDepth = WavWriter.BitDepth.PCM_24,
) : AudioSink, Closeable {

    override val channels = 2
    private val chunks = ArrayList<FloatArray>()

    override fun write(block: FloatArray) { chunks.add(block.copyOf()) }

    override fun close() {
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (c in chunks) { System.arraycopy(c, 0, out, at, c.size); at += c.size }
        // allowNonMpcRate: a bounce follows the device rate, not the MPC's.
        WavWriter.write(file, Snip(out, 2, sampleRate), depth, allowNonMpcRate = true)
        chunks.clear()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.BouncerTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/Bouncer.kt loop/src/test/kotlin/com/snipsnap/loop/BouncerTest.kt
git commit -m "Twelve intervals to a file: the same path playback will take"
```

---

### Task 8: Session persistence

**Files:**
- Create: `loop/src/main/kotlin/com/snipsnap/loop/SessionStore.kt`
- Test: `loop/src/test/kotlin/com/snipsnap/loop/SessionStoreTest.kt`

**Interfaces:**
- Consumes: `Session`, `Track`, `Block`, `LoopBlock`, `PatternBlock`, `Step` (Task 1); `Json.parse(text: String): JsonValue`, `Json.write(value: JsonValue): String`, `JsonValue.Obj/Arr/Str/Num/Bool`, `JsonException` from `:json`.
- Produces: `SessionStore.save(session: Session, dir: File): File`, `SessionStore.load(dir: File): Session`, `SessionStore.FILE_NAME`, `SessionStore.VERSION`. Plan 03's UI calls both.

Mirrors `KitStore` exactly: a folder holds the audio, a JSON sidecar holds the arrangement, and the pair travels as one unit.

- [ ] **Step 1: Write the failing test**

Create `loop/src/test/kotlin/com/snipsnap/loop/SessionStoreTest.kt`:

```kotlin
package com.snipsnap.loop

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionStoreTest {

    private fun tempDir(): File =
        File.createTempFile("snipsnap-session", "").let {
            it.delete(); it.mkdirs(); it.deleteOnExit(); it
        }

    private fun session() = Session(
        tracks = listOf(
            Track("Drums", listOf(LoopBlock("kick.wav"), LoopBlock("break.wav"))),
            Track("Percussion", listOf(LoopBlock("shaker.wav")), engaged = false),
            Track("Bass", listOf(LoopBlock("sub.wav")), level = 0.8f, pan = -0.25f),
            Track("Lead", listOf(PatternBlock("Keys", listOf(Step(0, 1), Step(8, 3, 0.6f, 120))))),
            Track("Pads", listOf(LoopBlock("wash.wav"))),
            Track("Vocal Samples", listOf(LoopBlock("adlib.wav"))),
        ),
        bpm = 92f,
        barsPerInterval = 4,
        sampleRate = 48_000,
    )

    @Test
    fun `round trips a session through disk`() {
        val dir = tempDir()
        SessionStore.save(session(), dir)
        val loaded = SessionStore.load(dir)
        assertEquals(session(), loaded)
    }

    @Test
    fun `writes the sidecar next to the audio`() {
        val dir = tempDir()
        val file = SessionStore.save(session(), dir)
        assertEquals(SessionStore.FILE_NAME, file.name)
        assertEquals(dir, file.parentFile)
        assertTrue(file.readText().contains("\"version\""), "sidecar should carry a version")
    }

    @Test
    fun `preserves every step field`() {
        val dir = tempDir()
        SessionStore.save(session(), dir)
        val block = SessionStore.load(dir).tracks[3].chain[0] as PatternBlock

        assertEquals("Keys", block.kit)
        assertEquals(2, block.steps.size)
        assertEquals(Step(8, 3, 0.6f, 120), block.steps[1])
    }

    @Test
    fun `preserves engaged level and pan`() {
        val dir = tempDir()
        SessionStore.save(session(), dir)
        val loaded = SessionStore.load(dir)

        assertTrue(!loaded.tracks[1].engaged, "mute state lost")
        assertEquals(0.8f, loaded.tracks[2].level)
        assertEquals(-0.25f, loaded.tracks[2].pan)
    }

    @Test
    fun `fails clearly when there is no sidecar`() {
        assertFailsWith<IOException> { SessionStore.load(tempDir()) }
    }

    @Test
    fun `refuses a version it does not know`() {
        // Hand-written rather than a string replacement on saved output: the
        // exact spacing Json.write emits is not this test's business, and a
        // test that breaks when the formatter changes is a trap.
        val dir = tempDir()
        File(dir, SessionStore.FILE_NAME).writeText(
            """{"version":99,"bpm":90,"barsPerInterval":4,"sampleRate":48000,"tracks":[]}""",
        )

        val e = assertFailsWith<IllegalStateException> { SessionStore.load(dir) }
        assertTrue(e.message!!.contains("99"), "message should name the version: ${e.message}")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.SessionStoreTest'`
Expected: FAIL — compilation error, `Unresolved reference: SessionStore`.

- [ ] **Step 3: Write minimal implementation**

Create `loop/src/main/kotlin/com/snipsnap/loop/SessionStore.kt`:

```kotlin
package com.snipsnap.loop

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.IOException

/**
 * A session on disk: a folder of audio plus a JSON sidecar.
 *
 * The same shape KitStore uses for kit.json, for the same reason — every
 * sample reference is a bare filename resolved against this folder, so the
 * folder can be copied, shared or backed up as one thing and still play.
 */
object SessionStore {

    const val FILE_NAME = "loop.json"
    const val VERSION = 1

    fun save(session: Session, dir: File): File {
        dir.mkdirs()
        require(dir.isDirectory) { "not a directory: $dir" }
        val file = File(dir, FILE_NAME)
        file.writeText(Json.write(toJson(session)) + "\n", Charsets.UTF_8)
        return file
    }

    @Throws(IOException::class)
    fun load(dir: File): Session {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) throw IOException("no $FILE_NAME in $dir")
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    private fun num(v: Number) = JsonValue.Num(v.toDouble())

    private fun toJson(session: Session): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "version" to num(VERSION),
            "bpm" to num(session.bpm),
            "barsPerInterval" to num(session.barsPerInterval),
            "sampleRate" to num(session.sampleRate),
            "tracks" to JsonValue.Arr(session.tracks.map { trackJson(it) }),
        ),
    )

    private fun trackJson(track: Track): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(track.name),
            "engaged" to JsonValue.Bool(track.engaged),
            "level" to num(track.level),
            "pan" to num(track.pan),
            "chain" to JsonValue.Arr(track.chain.map { blockJson(it) }),
        ),
    )

    private fun blockJson(block: Block): JsonValue = when (block) {
        is LoopBlock -> JsonValue.Obj(
            linkedMapOf(
                "type" to JsonValue.Str("loop"),
                "sampleFile" to JsonValue.Str(block.sampleFile),
            ),
        )
        is PatternBlock -> JsonValue.Obj(
            linkedMapOf(
                "type" to JsonValue.Str("pattern"),
                "kit" to JsonValue.Str(block.kit),
                "steps" to JsonValue.Arr(
                    block.steps.map {
                        JsonValue.Obj(
                            linkedMapOf(
                                "step" to num(it.step),
                                "slot" to num(it.slot),
                                "velocity" to num(it.velocity),
                                "microOffset" to num(it.microOffset),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private fun fromJson(root: JsonValue): Session {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw IllegalStateException("$FILE_NAME has no version")
        check(version == VERSION) { "$FILE_NAME is version $version, this build reads $VERSION" }

        return Session(
            tracks = obj["tracks"]?.arr().orEmpty().map { trackFrom(it) },
            bpm = (obj["bpm"]?.num() ?: 90.0).toFloat(),
            barsPerInterval = obj["barsPerInterval"]?.int() ?: 4,
            sampleRate = obj["sampleRate"]?.int() ?: 48_000,
        )
    }

    private fun trackFrom(value: JsonValue): Track {
        val t = value.obj()
        return Track(
            name = t["name"]?.str() ?: "",
            chain = t["chain"]?.arr().orEmpty().map { blockFrom(it) },
            engaged = t["engaged"]?.bool() ?: true,
            level = (t["level"]?.num() ?: 1.0).toFloat(),
            pan = (t["pan"]?.num() ?: 0.0).toFloat(),
        )
    }

    private fun blockFrom(value: JsonValue): Block {
        val b = value.obj()
        return when (val type = b["type"]?.str()) {
            "loop" -> LoopBlock(b["sampleFile"]?.str() ?: throw IllegalStateException("loop block has no sampleFile"))
            "pattern" -> PatternBlock(
                kit = b["kit"]?.str() ?: throw IllegalStateException("pattern block has no kit"),
                steps = b["steps"]?.arr().orEmpty().map { stepFrom(it) },
            )
            else -> throw IllegalStateException("unknown block type '$type'")
        }
    }

    private fun stepFrom(value: JsonValue): Step {
        val s = value.obj()
        return Step(
            step = s["step"]?.int() ?: 0,
            slot = s["slot"]?.int() ?: 1,
            velocity = (s["velocity"]?.num() ?: 1.0).toFloat(),
            microOffset = s["microOffset"]?.int() ?: 0,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :loop:test --tests 'com.snipsnap.loop.SessionStoreTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the full build**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. All pre-existing tests, Plan 01's 21, and this plan's 53 — 7 `SessionTest`, 9 `ArrangementTest`, 18 `BlockBakerTest`, 7 `MixerTest`, 6 `BouncerTest`, 6 `SessionStoreTest`.

- [ ] **Step 6: Commit**

```bash
git add loop/src/main/kotlin/com/snipsnap/loop/SessionStore.kt loop/src/test/kotlin/com/snipsnap/loop/SessionStoreTest.kt
git commit -m "loop.json beside the audio: a session you can close and come back to"
```

---

## Done when

- `./gradlew test` is green across all seven modules.
- A session built in code can be baked, mixed and bounced to a decodable WAV with no audio device present.
- `Arrangement.cycleIntervals` returns 12 for the spec's worked example.
- `SessionStore` round-trips both block types with every field intact.
- `loop/build.gradle.kts` depends only on `:json`, `:audio`, `:kit`.

## Not in this plan

Deferred to Plan 03, because they need a device or a UI:

- **Live re-baking and the atomic swap.** `Bouncer.toSink` bakes into a plain `HashMap` because an offline render has no concurrent reader. Live playback needs a `Residency` holding current and next per track, with an off-thread baker and a lock-free handoff.
- **The Android sink.** `AudioSink` implemented over AudioTrack or Oboe, and the device sample rate query that feeds `Session.sampleRate`.
- **`KitSampleSource`.** The real `SampleSource` reading through `KitStore` and `WavReader`. Every test here uses a map-backed fake, which is correct for testing the baker but means nothing yet reads a session's audio off disk.
- **CI.** `.github/workflows/tests.yml` needs an Android SDK before `:app` lands, or all existing tests go red.
