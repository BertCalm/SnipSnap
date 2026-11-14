package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import kotlin.math.*
import kotlin.test.*

class PatioHollowTest {
    @Test fun `lower bell retains a short resonant tail and replays exactly`() {
        val r = PatioStudy.recipes().single { it.id == "agogo-low" }
        val before = PatioArticulation.render(r)
        val after = PatioHollow.render(r)
        assertTrue(FeatureExtractor.extract(after).centroidHz < FeatureExtractor.extract(before).centroidHz * .92)
        fun fraction(x: FloatArray, from: Double, to: Double): Double =
            ((from * Dsp.RATE).toInt() until min(x.size, (to * Dsp.RATE).toInt())).sumOf { x[it].toDouble().pow(2) } / x.sumOf { it.toDouble().pow(2) }
        assertTrue(fraction(after.samples, .10, .22) > fraction(before.samples, .10, .22) * 1.3)
        assertTrue(fraction(after.samples, .30, .85) < .005, "ring must damp rather than sustain")
        assertContentEquals(after.samples, PatioHollow.render(r).samples)
        assertTrue(after.samples.all { it.isFinite() })
        assertTrue(after.peak() in .01f..1f)
        assertTrue(abs(after.samples.average()) < .001)
    }
    @Test fun `other fifteen pads remain unchanged`() {
        for (r in PatioStudy.recipes().filter { it.id != "agogo-low" })
            assertContentEquals(PatioArticulation.render(r).samples, PatioHollow.render(r).samples, r.id)
    }
}
