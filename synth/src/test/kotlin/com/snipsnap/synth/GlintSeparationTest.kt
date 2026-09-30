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

    /**
     * BLOOM has one destination, PEAK, so SWEEP down (BLOOM 1) and up (BLOOM 0) start apart and
     * settle on the same formant: they differ on the way in and not after landing. Read that way,
     * the whole note and its first quarter must be as far apart as the bar asks, and its last
     * quarter no further apart than a window swap. The path form (the mean of the quarters) would
     * average the landed quarters in with the approach, so it is printed and not asserted.
     */
    @Test
    fun `SWEEP up and SWEEP down differ on the way in and land together`() {
        val m = mapOf("PEAK" to 0.45f, "DECAY" to 0.7f)
        val down = render(GlintVoice.SWEEP, m + ("BLOOM" to 1f))
        val up = render(GlintVoice.SWEEP, m + ("BLOOM" to 0f))
        val whole = BandDistance.whole(down, up, rate)
        val quarters = BandDistance.segmentDistances(down, up, rate)
        report("SWEEP down vs up, whole note", whole)
        report("SWEEP down vs up, first quarter", quarters.first())
        report("SWEEP down vs up, last quarter", quarters.last())
        println(
            "GLINT separation SWEEP down vs up, quarters in time order ${quarters.joinToString(" / ") { "%.4f".format(it) }}, " +
                "path form (their mean) ${BandDistance.path(down, up, rate)}",
        )
        assertTrue(whole >= bar, "SWEEP down vs up, whole note: $whole, bar $bar (anchor $anchor)")
        assertTrue(quarters.first() >= bar, "SWEEP down vs up, first quarter: ${quarters.first()}, bar $bar (anchor $anchor)")
        assertTrue(quarters.last() <= anchor, "SWEEP down vs up, last quarter: ${quarters.last()}, anchor $anchor")
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
        // Spec section 5 asks for the pairs at the root (TUNE 0, 110 Hz); TUNE 0.5 (220 Hz) is VOWEL's
        // default note. Measure every pair at both before asserting any, so one miss cannot hide the others.
        val measured = listOf(0f, 0.5f).flatMap { tune ->
            val renders = peaks.map { render(GlintVoice.VOWEL, mapOf("TUNE" to tune, "PEAK" to it, "BLOOM" to 0.5f, "DECAY" to 0.9f)) }
            (0 until renders.lastIndex).map { i ->
                "${names[i]} vs ${names[i + 1]} at TUNE $tune" to BandDistance.whole(renders[i], renders[i + 1], rate)
            }
        }
        for ((pair, d) in measured) report("$pair (whole form)", d)
        for ((pair, d) in measured) assertTrue(d >= bar, "$pair: $d, bar $bar (anchor $anchor)")
    }
}
