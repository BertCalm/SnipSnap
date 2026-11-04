package com.snipsnap.synth

import com.snipsnap.json.Json

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TidalRenderTest {
    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun `production and diagnostic channels preserve frozen accepted performances`() {
        Tidal.clearCaches()
        val fixture = javaClass.getResourceAsStream("/tidal-render-references.json")!!.bufferedReader().use { it.readText() }
        val cases = Json.parse(fixture).obj().getValue("cases").arr()
        assertEquals(12, cases.size)
        for (value in cases) {
            val case = value.obj()
            val id = case.getValue("id").str()
            val patch = TidalPatch.fromJsonValue(case.getValue("patch"))
            val expected = Tidal.probe(patch.voice, patch.macros, patch.midi, patch.velocity,
                patch.seed, patch.model, Tidal.Options(normalize = true))
            val production = Tidal.renderProfiled(patch.voice, patch.macros, patch.midi,
                patch.velocity, patch.seed, patch.model)
            assertEquals(case.getValue("frames").int(), production.snip.samples.size, id)
            assertEquals(case.getValue("prelevelFloat32Sha256").str(), hash(expected.rawMix), "$id dry samples")
            assertEquals(case.getValue("normalizedFloat32Sha256").str(), hash(expected.samples), "$id diagnostic normalization")
            assertEquals(case.getValue("normalizedFloat32Sha256").str(), hash(production.snip.samples), "$id production samples")
            val expectedWorkBytes = if (patch.velocity == 0f) 0L
                else production.snip.samples.size.toLong() * Dsp.OVERSAMPLE * Float.SIZE_BYTES
            assertEquals(expectedWorkBytes, production.profile.oversampledWorkBytes,
                "$id at most one shared oversampled channel buffer")
        }
    }

    @Test
    fun `completed cache canonicalizes controls and never lends its owned samples`() {
        Tidal.clearCaches()
        val original = Tidal.render(TidalVoice.STREAM, midi = 48, seed = 41)
        val expected = original.samples.copyOf()
        original.samples.fill(0f)
        val explicit = Tidal.defaults(TidalVoice.STREAM).entries.reversed().associate { it.key to it.value }
        var checks = 0
        val repeated = Tidal.render(TidalVoice.STREAM, explicit, midi = 48, seed = 41,
            cancelled = { ++checks > 2 })
        assertEquals(2, checks, "equivalent controls should return the completed performance")
        assertTrue(expected.contentEquals(repeated.samples), "mutating the first result changed the cached sound")
        repeated.samples.fill(.9f)
        assertTrue(expected.contentEquals(Tidal.render(TidalVoice.STREAM, midi = 48, seed = 41).samples),
            "mutating a cache hit changed the next result")
        val snapshot = Tidal.audioCacheSnapshot()
        assertEquals(1, snapshot.entries)
        assertEquals(expected.size.toLong() * Float.SIZE_BYTES, snapshot.bytes)
        assertTrue(snapshot.bytes <= snapshot.maxBytes)
    }

    @Test
    fun `all audible recipe inputs separate cache entries and cancelled misses stay absent`() {
        Tidal.clearCaches()
        val base = Tidal.defaults(TidalVoice.STREAM)
        Tidal.render(TidalVoice.STREAM, base, midi = 48, seed = 41)
        fun requireMiss(voice: TidalVoice = TidalVoice.STREAM, macros: Map<String, Float> = base,
            midi: Int = 48, velocity: Float = .75f, seed: Long = 41L, model: Int = 2) {
            var checks = 0
            // A hit completes after two cancellation checks. A miss must begin fresh
            // preparation and reaches the third; cancel there without rendering it.
            assertFailsWith<CancellationException> {
                Tidal.render(voice, macros, midi, velocity, seed, model, cancelled = { ++checks > 2 })
            }
        }
        for (macro in listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL"))
            requireMiss(macros = base + (macro to .123f))
        requireMiss(voice = TidalVoice.PAIR)
        requireMiss(midi = 49)
        requireMiss(velocity = .6f)
        requireMiss(seed = Long.MIN_VALUE)
        requireMiss(model = 1)
        assertEquals(1, Tidal.audioCacheSnapshot().entries)
        assertFailsWith<IllegalArgumentException> { Tidal.render(TidalVoice.STREAM, base + ("HOLD" to .1f)) }
        assertFailsWith<IllegalArgumentException> { Tidal.render(TidalVoice.STREAM, midi = 35) }
        assertFailsWith<IllegalArgumentException> { Tidal.render(TidalVoice.STREAM, velocity = Float.NaN) }
    }

    @Test
    fun `cached and in progress requests both honor cancellation`() {
        Tidal.clearCaches()
        Tidal.render(TidalVoice.PAIR, midi = 60, seed = 3)
        assertFailsWith<CancellationException> {
            Tidal.render(TidalVoice.PAIR, midi = 60, seed = 3, cancelled = { true })
        }
        Tidal.clearAudioCache()
        var checks = 0
        assertFailsWith<CancellationException> {
            Tidal.render(TidalVoice.PAIR, midi = 60, seed = 3, cancelled = { ++checks > 12 })
        }
        assertEquals(0, Tidal.audioCacheSnapshot().entries)
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { Tidal.render(TidalVoice.PAIR) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an identical request waits for the render in flight and takes its completed copy`() {
        // The emulator recipe: a preview and SEND TO PAD of this held patch ran side by side.
        val held = mapOf("HOLD" to 1f)
        Tidal.clearCaches()
        val before = Tidal.freshRenderCount()
        val inside = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            var checks = 0
            var secondChecks = 0
            // A request's first check precedes the cache lookup, and an uncontended entry makes
            // none, so the first request's third check is inside the render that holds this
            // recipe's flight. It holds there until the second request is provably waiting:
            // that request's second check can only come from the flight's wait loop. So the
            // second request is queued behind the first however the threads are scheduled.
            val first = pool.submit(Callable {
                Tidal.render(TidalVoice.STREAM, held, seed = Long.MAX_VALUE,
                    cancelled = {
                        if (++checks == 3) {
                            inside.countDown()
                            check(waiting.await(5, TimeUnit.MINUTES)) { "the second request never waited" }
                        }
                        false
                    })
            })
            assertTrue(inside.await(5, TimeUnit.MINUTES), "the first render never started")
            val second = pool.submit(Callable {
                Tidal.render(TidalVoice.STREAM, held, seed = Long.MAX_VALUE,
                    cancelled = { if (++secondChecks == 2) waiting.countDown(); false })
            })
            val a = first.get(5, TimeUnit.MINUTES).samples
            val b = second.get(5, TimeUnit.MINUTES).samples
            assertEquals(1, Tidal.freshRenderCount() - before, "the identical request synthesized a second field")
            assertContentEquals(a, b)
            assertEquals(0, Tidal.rendersInFlight())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `flights hold back only an equal key and release cancelled interrupted and failed callers`() {
        val cache = TidalAudioCache<String>(64)
        val pool = Executors.newCachedThreadPool()
        fun waitUntil(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!condition()) {
                assertTrue(System.nanoTime() < deadline, what)
                Thread.sleep(1)
            }
        }
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val owner = pool.submit(Callable {
                cache.exclusive<String>("a", {}) {
                    entered.countDown()
                    release.await()
                    throw IllegalStateException("render failed")
                }
            })
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals("b", pool.submit(Callable { cache.exclusive("b", {}) { "b" } }).get(10, TimeUnit.SECONDS),
                "another recipe waited for this one")
            // A waiter stops as soon as its own check cancels it...
            val cancelled = pool.submit(Callable { cache.exclusive("a", { throw CancellationException() }) { "entered" } })
            assertIs<CancellationException>(assertFailsWith<ExecutionException> { cancelled.get(10, TimeUnit.SECONDS) }.cause)
            // ...or when its thread is interrupted, which stays set for runInterruptible.
            var keptInterrupt = false
            val interrupted = Thread {
                try {
                    cache.exclusive("a", {}) { }
                } catch (_: CancellationException) {
                    keptInterrupt = Thread.currentThread().isInterrupted
                }
            }.apply { start() }
            waitUntil("the second caller never waited") { interrupted.state == Thread.State.TIMED_WAITING }
            interrupted.interrupt()
            interrupted.join(10_000)
            assertTrue(keptInterrupt, "an interrupted waiter must stop as a cancellation, still interrupted")
            // When the caller inside fails, a waiter enters and renders for itself.
            val waiting = CountDownLatch(1)
            val next = pool.submit(Callable { cache.exclusive("a", { waiting.countDown() }) { "rendered" } })
            assertTrue(waiting.await(10, TimeUnit.SECONDS))
            release.countDown()
            assertIs<IllegalStateException>(assertFailsWith<ExecutionException> { owner.get(10, TimeUnit.SECONDS) }.cause)
            assertEquals("rendered", next.get(10, TimeUnit.SECONDS))
            assertEquals(0, cache.inFlight())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `audio cache evicts by retained bytes and owns all published buffers`() {
        val cache = TidalAudioCache<String>(32)
        val source = floatArrayOf(1f, 2f, 3f, 4f)
        cache.put("a", source)
        source.fill(0f)
        cache.put("b", FloatArray(4) { 2f })
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f), cache.get("a"))
        cache.put("c", FloatArray(4) { 3f })
        assertNull(cache.get("b"), "the least recently used buffer should leave first")
        assertNotNull(cache.get("a")).fill(7f)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f), cache.get("a"))
        assertEquals(32L, cache.snapshot().bytes)
        cache.put("a", FloatArray(2))
        assertEquals(24L, cache.snapshot().bytes)
        cache.put("oversized", FloatArray(9))
        assertNull(cache.get("oversized"))
        assertEquals(24L, cache.snapshot().bytes)
        assertFailsWith<CancellationException> {
            cache.put("cancelled", FloatArray(2)) { throw CancellationException() }
        }
        assertNull(cache.get("cancelled"))
        cache.clear()
        assertEquals(Tidal.AudioCacheSnapshot(0, 0, 32), cache.snapshot())
    }
}
