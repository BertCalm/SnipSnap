package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The redefinition's claim, measured: paths are audible where windows were not
 * (docs/superpowers/specs/2026-09-29-glint-paths-design.md §1, §5). The bar is
 * relative to a same-run anchor - three reconstructions of this measure
 * disagreed up to 2x in absolute scale, so the anchor is what makes a number here
 * mean anything.
 */
class GlintSeparationTest {
    private val rate = Dsp.RATE

    /** A window swap at fixed k - the anchor the whole redefinition was measured against. */
    private fun fixed(window: (Double) -> Double, k: Double): FloatArray {
        val n = rate
        var phase = 0.0
        return FloatArray(n) { i ->
            val s = exp(-6.9078 * i / rate) * window(phase) * sin(2 * PI * k * phase)
            phase += 110.0 / rate
            if (phase >= 1.0) phase -= 1.0
            s.toFloat()
        }
    }

    private val anchor: Double by lazy {
        BandDistance.path(fixed({ 1 - it }, 8.0), fixed({ if (it < 0.5) 2 * it else 2 * (1 - it) }, 8.0), rate)
    }
    private val bar: Double get() = max(0.55, 2 * anchor)

    /**
     * The same window swap read whole-note. Report only: the bar is the path-form
     * anchor's, but the vowel pairs are held notes and are measured whole.
     */
    private val anchorWhole: Double by lazy {
        BandDistance.whole(fixed({ 1 - it }, 8.0), fixed({ if (it < 0.5) 2 * it else 2 * (1 - it) }, 8.0), rate)
    }

    private fun render(voice: GlintVoice, m: Map<String, Float>) = Glint.render(voice, m).samples

    /** Every number lands in the result XML's system-out, so a green run still shows its margins. */
    private fun report(what: String, d: Double) = println(
        "GLINT separation $what: distance ${"%.4f".format(d)}, bar ${"%.4f".format(bar)}, " +
            "anchor ${"%.4f".format(anchor)} path form / ${"%.4f".format(anchorWhole)} whole form",
    )

    @Test
    fun `SWEEP up and SWEEP down are different voices`() {
        val m = mapOf("PEAK" to 0.45f, "DECAY" to 0.7f)
        val d = BandDistance.path(render(GlintVoice.SWEEP, m + ("BLOOM" to 1f)), render(GlintVoice.SWEEP, m + ("BLOOM" to 0f)), rate)
        report("SWEEP down vs up (path form)", d)
        assertTrue(d >= bar, "SWEEP down vs up: $d, bar $bar (anchor $anchor)")
    }

    @Test
    fun `STEP is not SWEEP`() {
        // Same pitch: SWEEP's root is A2, STEP's A3, so SWEEP plays TUNE 0.5.
        val d = BandDistance.path(
            render(GlintVoice.SWEEP, mapOf("TUNE" to 0.5f, "BLOOM" to 1f, "DECAY" to 0.9f)),
            render(GlintVoice.STEP, mapOf("TUNE" to 0f, "BLOOM" to 1f, "DECAY" to 0.9f)),
            rate,
        )
        report("SWEEP vs STEP (path form)", d)
        assertTrue(d >= bar, "SWEEP vs STEP: $d, bar $bar (anchor $anchor)")
    }

    @Test
    fun `every neighbouring pair of vowels is a different sound`() {
        val peaks = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val names = listOf("OO", "OH", "AH", "EH", "EE")
        val renders = peaks.map { render(GlintVoice.VOWEL, mapOf("PEAK" to it, "BLOOM" to 0.5f, "DECAY" to 0.9f)) }
        // Measure every pair before asserting any, so one miss cannot hide the other three numbers.
        val distances = (0 until renders.lastIndex).map { BandDistance.whole(renders[it], renders[it + 1], rate) }
        for (i in distances.indices) report("${names[i]} vs ${names[i + 1]} (whole form)", distances[i])
        for (i in distances.indices) {
            assertTrue(distances[i] >= bar, "${names[i]} vs ${names[i + 1]}: ${distances[i]}, bar $bar (anchor $anchor)")
        }
    }
}
