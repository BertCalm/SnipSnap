package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.Executors

/** Cache correctness and its useful boundary, independent of machine-specific timing. */
class BallastPerformanceTest {
    private val voice = BallastVoice.ROOT

    private fun run(m: Map<String, Float>, velocity: Double = 1.0, cache: Boolean = true, frames: Int = 6000): Ballast.Run {
        val hz = Ballast.frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val p = Ballast.oneShotPlan(voice, hz, m)
        val short = Ballast.Plan(p.hzA, p.ratioB, p.wobbleHz, p.steady, frames, p.gateFrames, p.releaseFrames)
        return Ballast.simulate(voice, short, m, velocity, Ballast.Probe(sourceCache = cache))
    }

    @Test
    fun `structural edits reuse bass while producing the same simulation as a fresh source`() {
        Ballast.clearSourceCache()
        val base = Ballast.defaults(voice)
        run(base)
        val first = Ballast.sourceCacheStats()
        for (key in listOf("SYMPATHY", "SPAN", "GLASS", "FRAME")) {
            val m = base + (key to .83f)
            assertContentEquals(run(m, cache = false).raw, run(m).raw, key)
        }
        val last = Ballast.sourceCacheStats()
        assertEquals(first.builds, last.builds, "structural controls do not rebuild the source")
        assertEquals(first.hits + 4, last.hits)
    }

    @Test
    fun `source-affecting settings invalidate the cache and longer prefixes remain exact`() {
        Ballast.clearSourceCache()
        val base = Ballast.defaults(voice)
        run(base)
        for (m in listOf(base + ("TUNE" to .7f), base + ("DRIVE" to .81f), base + ("HOLD" to .4f))) {
            val before = Ballast.sourceCacheStats().builds
            assertContentEquals(run(m, cache = false).raw, run(m).raw)
            assertEquals(before + 1, Ballast.sourceCacheStats().builds)
        }
        val before = Ballast.sourceCacheStats().builds
        assertContentEquals(run(base, velocity = .37, cache = false).raw, run(base, velocity = .37).raw)
        assertEquals(before + 1, Ballast.sourceCacheStats().builds)
        assertContentEquals(run(base, frames = 9000, cache = false).raw, run(base, frames = 9000).raw)
        assertContentEquals(run(base, frames = 4000, cache = false).raw, run(base, frames = 4000).raw)
    }

    @Test
    fun `retained source memory is bounded and returned audio never aliases a cached trace`() {
        Ballast.clearSourceCache()
        val base = Ballast.defaults(voice)
        val expected = run(base).raw
        val changed = run(base).raw
        changed.fill(123f)
        assertContentEquals(expected, run(base).raw)
        // Four traces of this size exceed the byte cap, independently of the four-entry cap.
        repeat(5) { run(base + ("DRIVE" to (.1f + .08f * it)), frames = 850_000) }
        val stats = Ballast.sourceCacheStats()
        assertTrue(stats.entries <= 3)
        assertTrue(stats.bytes <= 24L * 1024 * 1024)
    }

    @Test
    fun `concurrent cache misses and prefix replacements preserve independent renders`() {
        Ballast.clearSourceCache()
        val base = Ballast.defaults(voice)
        val requests = (0 until 8).map { i ->
            Triple(base + ("DRIVE" to if (i % 2 == 0) .42f else .73f), 4000 + i * 500, .62)
        }
        val expected = requests.map { (m, frames, velocity) -> run(m, velocity, cache = false, frames = frames).raw }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val actual = requests.map { (m, frames, velocity) -> pool.submit<FloatArray> { run(m, velocity, frames = frames).raw } }
            actual.forEachIndexed { i, result -> assertContentEquals(expected[i], result.get(), "request $i") }
        } finally {
            pool.shutdownNow()
        }
    }
}
