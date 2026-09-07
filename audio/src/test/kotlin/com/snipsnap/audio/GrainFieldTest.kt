package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GrainFieldTest {
    // First half pure 220Hz tone, second half white-ish noise (deterministic LCG) — two clearly distinct textures.
    private fun toneThenNoise(): Snip {
        val n = 88_200
        val out = FloatArray(n)
        for (i in 0 until n / 2) out[i] = (0.6 * kotlin.math.sin(2.0 * Math.PI * 220.0 * i / 44_100.0)).toFloat()
        var state = 12345L
        for (i in n / 2 until n) {
            state = state * 6364136223846793005L + 1442695040888963407L
            out[i] = ((state ushr 40).toInt() / 8_388_608f - 1f) * 0.4f
        }
        return Snip(out, 1, 44_100)
    }

    @Test
    fun `coordinates are normalized and grains ordered by time`() {
        val map = GrainField.analyze(toneThenNoise())
        assertNotNull(map)
        assertTrue(map.grains.size >= 20, "expected dozens of grains, got ${map.grains.size}")
        assertTrue(map.grains.all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(map.grains.zipWithNext().all { (a, b) -> a.startFrame < b.startFrame })
    }

    @Test
    fun `distinct textures land in distinct regions`() {
        val map = GrainField.analyze(toneThenNoise())!!
        val half = 44_100
        val tone = map.grains.filter { it.startFrame + GrainField.GRAIN_FRAMES <= half }
        val noise = map.grains.filter { it.startFrame >= half }
        assertTrue(tone.size >= 5 && noise.size >= 5)
        fun centroid(g: List<GrainField.Grain>) = Pair(g.map { it.x }.average(), g.map { it.y }.average())
        fun spread(g: List<GrainField.Grain>, c: Pair<Double, Double>) =
            g.map { kotlin.math.hypot(it.x - c.first, it.y - c.second) }.average()
        val ct = centroid(tone); val cn = centroid(noise)
        val between = kotlin.math.hypot(ct.first - cn.first, ct.second - cn.second)
        assertTrue(
            between > (spread(tone, ct) + spread(noise, cn)),
            "tone and noise clusters overlap: between=$between",
        )
    }

    @Test
    fun `silence yields null`() {
        assertNull(GrainField.analyze(Snip(FloatArray(88_200), 1, 44_100)))
    }

    @Test
    fun `identical grains still spread instead of collapsing`() {
        // Constant-amplitude steady tone: features nearly identical for every grain.
        val n = 88_200
        val out = FloatArray(n) { (0.5 * kotlin.math.sin(2.0 * Math.PI * 330.0 * it / 44_100.0)).toFloat() }
        val map = GrainField.analyze(Snip(out, 1, 44_100))
        assertNotNull(map)
        val xs = map.grains.map { it.x }
        assertTrue((xs.max() - xs.min()) > 0.5f, "degenerate axis must spread by index, got range ${xs.max() - xs.min()}")
    }
}
