package com.snipsnap.shell

import com.snipsnap.audio.Snip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeaksTest {

    private fun bruteForce(mono: FloatArray, from: Int, to: Int): PeaksPyramid.Column {
        val f = from.coerceAtLeast(0)
        val t = to.coerceAtMost(mono.size)
        if (t <= f) return PeaksPyramid.Column(0f, 0f)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in f until t) {
            if (mono[i] < lo) lo = mono[i]
            if (mono[i] > hi) hi = mono[i]
        }
        return PeaksPyramid.Column(lo, hi)
    }

    @Test
    fun `every query is exactly the brute-force answer`() {
        val rng = Random(7)
        // Deliberately not a multiple of the block size.
        val mono = FloatArray(44_100 * 3 + 137) { rng.nextFloat() * 2f - 1f }
        val peaks = PeaksPyramid.build(mono, baseBlock = 64)

        val cases = listOf(
            Triple(0, mono.size, 350),          // whole tape, screen-width
            Triple(0, mono.size, 3),            // extreme zoom out
            Triple(10_000, 10_400, 100),        // zoomed in past the block size
            Triple(3, 130, 40),                 // sub-block, ragged edges
            Triple(mono.size - 500, mono.size + 900, 60), // off the end
            Triple(-300, 200, 25),              // off the start
        )
        for ((from, to, count) in cases) {
            val cols = peaks.columns(from, to, count)
            assertEquals(count, cols.size)
            val span = (to - from).toDouble()
            cols.forEachIndexed { c, col ->
                val f = from + (span * c / count).toInt()
                val t = from + (span * (c + 1) / count).toInt()
                assertEquals(
                    bruteForce(mono, f, t), col,
                    "case ($from,$to,$count) column $c range [$f,$t)",
                )
            }
        }
    }

    @Test
    fun `range queries climb the pyramid without losing a sample`() {
        val rng = Random(11)
        val mono = FloatArray(100_000) { rng.nextFloat() * 2f - 1f }
        val peaks = PeaksPyramid.build(mono)
        repeat(200) {
            val a = rng.nextInt(mono.size)
            val b = rng.nextInt(mono.size)
            val from = minOf(a, b)
            val to = maxOf(a, b) + 1
            assertEquals(bruteForce(mono, from, to), peaks.rangeMinMax(from, to))
        }
    }

    @Test
    fun `empty and stereo inputs behave`() {
        val empty = PeaksPyramid.build(FloatArray(0))
        assertEquals(PeaksPyramid.Column(0f, 0f), empty.rangeMinMax(0, 10))
        assertEquals(3, empty.columns(0, 100, 3).size)

        // Stereo mixes down; a hard-left, hard-right pair averages.
        val stereo = Snip(floatArrayOf(1f, -1f, 0.5f, 0.5f), channels = 2, sampleRate = 44_100)
        val p = PeaksPyramid.fromSnip(stereo)
        assertEquals(2, p.frameCount)
        assertEquals(PeaksPyramid.Column(0f, 0.5f), p.rangeMinMax(0, 2))
    }

    @Test
    fun `build is linear-ish and query is cheap`() {
        // Not a benchmark — a smoke check that 60s of tape builds and
        // queries without a quadratic surprise.
        val mono = FloatArray(44_100 * 60) { (it % 100 - 50) / 50f }
        val t0 = System.nanoTime()
        val peaks = PeaksPyramid.build(mono)
        val built = (System.nanoTime() - t0) / 1_000_000
        assertTrue(built < 2_000, "build took ${built}ms")

        val t1 = System.nanoTime()
        repeat(600) { peaks.columns(0, mono.size, 390) }
        val queried = (System.nanoTime() - t1) / 1_000_000
        assertTrue(queried < 2_000, "600 full-width queries took ${queried}ms")
    }
}
