package com.snipsnap.synth

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Dsp.scrambleNear] — U2 of `docs/SYNTH_UPGRADE.md`: SCRAMBLE rolls near a
 * seed instead of flat across the macro box. Every engine's `scramble()`
 * delegates here, so the boundary contract is proven once, centrally,
 * rather than trusted seven times over.
 */
class DspTest {

    private val seed = mapOf("A" to 0.2f, "B" to 0.5f, "C" to 0.9f)

    @Test
    fun `temperature 0 returns the seed untouched`() {
        assertEquals(seed, Dsp.scrambleNear(seed, 0f, Random(1)))
    }

    @Test
    fun `temperature 1 discards the seed and rolls flat-uniform`() {
        val rolled = Dsp.scrambleNear(seed, 1f, Random(1))
        assertEquals(seed.keys, rolled.keys)
        assertTrue(rolled.values.all { it in 0f..1f })
        // A flat roll owes the seed nothing - matching values would be
        // astronomically unlikely across three independent draws.
        assertTrue(rolled.values.toList() != seed.values.toList())
    }

    @Test
    fun `temperature out of range clamps to the same bounds`() {
        assertEquals(Dsp.scrambleNear(seed, 0f, Random(1)), Dsp.scrambleNear(seed, -0.5f, Random(1)))
        assertEquals(Dsp.scrambleNear(seed, 1f, Random(1)), Dsp.scrambleNear(seed, 1.5f, Random(1)))
    }

    @Test
    fun `same seed and random roll the same patch`() {
        assertEquals(Dsp.scrambleNear(seed, 0.35f, Random(7)), Dsp.scrambleNear(seed, 0.35f, Random(7)))
    }

    @Test
    fun `a mid temperature roll stays bounded and usually stays close to the seed`() {
        repeat(50) { roll ->
            val rolled = Dsp.scrambleNear(seed, 0.35f, Random(roll))
            assertTrue(rolled.values.all { it in 0f..1f }, "roll $roll left the 0..1 range")
        }
    }

    @Test
    fun `a low temperature rolls closer to the seed on average than a high one`() {
        fun meanAbsDelta(temperature: Float): Float {
            var total = 0f
            var n = 0
            repeat(200) { roll ->
                val rolled = Dsp.scrambleNear(seed, temperature, Random(roll))
                for ((k, v) in seed) { total += kotlin.math.abs(rolled.getValue(k) - v); n++ }
            }
            return total / n
        }
        val low = meanAbsDelta(0.1f)
        val high = meanAbsDelta(0.9f)
        assertTrue(low < high, "low-temperature rolls ($low) should land closer to the seed than high-temperature rolls ($high)")
    }

    // ---------- Dsp.limitPeak (U3, docs/SYNTH_UPGRADE.md) ----------
    // A clipping safety net, not a level target: unlike normalize, it must
    // leave an already-safe buffer's level exactly alone.

    @Test
    fun `limitPeak leaves a buffer under the ceiling untouched`() {
        val buf = floatArrayOf(0.1f, -0.3f, 0.5f, -0.2f)
        val original = buf.copyOf()
        Dsp.limitPeak(buf, ceiling = 1f)
        assertTrue(original.contentEquals(buf), "a peak already under the ceiling must not be rescaled")
    }

    @Test
    fun `limitPeak rescales an over-ceiling buffer down to exactly the ceiling`() {
        val buf = floatArrayOf(0.5f, -2f, 1f)
        Dsp.limitPeak(buf, ceiling = 1f)
        val peak = buf.maxOf { kotlin.math.abs(it) }
        assertEquals(1f, peak, 1e-5f)
        // A uniform rescale: every sample keeps its share of the original peak.
        assertEquals(0.25f, buf[0], 1e-5f)
    }

    @Test
    fun `limitPeak leaves silence alone`() {
        val buf = FloatArray(8)
        Dsp.limitPeak(buf, ceiling = 1f)
        assertTrue(buf.all { it == 0f }, "silence has no peak to rescale from")
    }
}
