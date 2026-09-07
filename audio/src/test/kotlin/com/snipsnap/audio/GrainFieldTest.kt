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

    @Test
    fun `projector puts a grain's own vector back on its map position`() {
        val snip = toneThenNoise()
        val map = GrainField.analyze(snip)!!
        val projector = map.projector
        assertNotNull(projector)
        // Re-extract the first grain's vector by hand and project it: must land
        // (within clamp/rounding) on that grain's stored coordinates.
        val g = map.grains.first()
        val mono = Cleanup.toMono(snip)
        val window = Snip(mono.samples.copyOfRange(g.startFrame, g.startFrame + GrainField.GRAIN_FRAMES), 1, 44_100)
        val v = Similar.vector(FeatureExtractor.extract(window))
        val (x, y) = projector.project(v)
        assertTrue(kotlin.math.abs(x - g.x) < 1e-3f && kotlin.math.abs(y - g.y) < 1e-3f,
            "round-trip drifted: ($x,$y) vs (${g.x},${g.y})")
    }

    @Test
    fun `foreign audio projects to the matching territory`() {
        val map = GrainField.analyze(toneThenNoise())!!
        val projector = map.projector!!
        // A FRESH tone (different phase/freq-ish but tonal) must land nearer the tone
        // cluster's centroid than the noise cluster's.
        val n = GrainField.GRAIN_FRAMES
        val tone = FloatArray(n) { (0.6 * kotlin.math.sin(2.0 * Math.PI * 233.0 * it / 44_100.0)).toFloat() }
        val v = Similar.vector(FeatureExtractor.extract(Snip(tone, 1, 44_100)))
        val (x, y) = projector.project(v)
        val half = 44_100
        fun centroid(g: List<GrainField.Grain>) = Pair(g.map { it.x }.average(), g.map { it.y }.average())
        val ct = centroid(map.grains.filter { it.startFrame + GrainField.GRAIN_FRAMES <= half })
        val cn = centroid(map.grains.filter { it.startFrame >= half })
        val dTone = kotlin.math.hypot(x - ct.first, y - ct.second)
        val dNoise = kotlin.math.hypot(x - cn.first, y - cn.second)
        assertTrue(dTone < dNoise, "a foreign tone landed in noise territory (dTone=$dTone dNoise=$dNoise)")
    }

    @Test
    fun `projection clamps out-of-range audio into the field`() {
        val map = GrainField.analyze(toneThenNoise())!!
        val loudClick = FloatArray(GrainField.GRAIN_FRAMES).also { it[0] = 1f }
        val v = Similar.vector(FeatureExtractor.extract(Snip(loudClick, 1, 44_100)))
        val (x, y) = map.projector!!.project(v)
        assertTrue(x in 0f..1f && y in 0f..1f)
    }

    @Test
    fun `degenerate axis projects new audio to the 0_5 midpoint, not the grains' index-spread`() {
        // Tile a single HOP_FRAMES-long block bit-for-bit (not by re-evaluating a sinusoid
        // at ever-larger indices, which drifts under floating point argument reduction):
        // every grain window (2x HOP_FRAMES long) is then a repeat of the exact same two
        // blocks, so both PCA axes collapse to EXACTLY zero variance across grains.
        val block = FloatArray(GrainField.HOP_FRAMES) { (0.5 * kotlin.math.sin(2.0 * Math.PI * 3.0 * it / GrainField.HOP_FRAMES)).toFloat() }
        val out = FloatArray(block.size * 20) { block[it % block.size] }
        val map = GrainField.analyze(Snip(out, 1, 44_100))
        assertNotNull(map)
        val projector = map.projector!!
        assertTrue(projector.degenerate1, "fixture failed to reach the degenerate branch on axis 1")
        assertTrue(projector.degenerate2, "fixture failed to reach the degenerate branch on axis 2")
        // The grains themselves still spread by index (never collapse onto one spot)...
        val xs = map.grains.map { it.x }
        assertTrue((xs.max() - xs.min()) > 0.5f)
        // ...but projecting a freshly re-extracted vector for that same identical audio
        // must land on 0.5, since there is no per-grain index to fall back on.
        val window = out.copyOfRange(0, GrainField.GRAIN_FRAMES)
        val v = Similar.vector(FeatureExtractor.extract(Snip(window, 1, 44_100)))
        val (x, y) = projector.project(v)
        assertTrue(x == 0.5f && y == 0.5f, "degenerate axis must project to the midpoint, got ($x,$y)")
    }
}
