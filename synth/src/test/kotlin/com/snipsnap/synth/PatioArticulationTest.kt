package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import kotlin.math.*
import kotlin.test.*

class PatioArticulationTest {
    @Test fun `articulations replay with bounded output and preserve untouched pads`() {
        for (r in PatioStudy.recipes()) {
            val s = PatioArticulation.render(r)
            if (r.id !in PatioArticulation.IDS) assertContentEquals(PatioCleanup.render(r).samples, s.samples)
            else {
                assertContentEquals(s.samples, PatioArticulation.render(r).samples)
                assertTrue(s.samples.all { it.isFinite() })
                assertTrue(s.peak() in .01f..1f)
                assertTrue(abs(s.samples.average()) < .001)
                assertTrue(abs(s.samples.last()) < .001)
            }
        }
    }
    @Test fun `pair identities survive equal pitch and the cleaner processor`() {
        // A pitch-only revision fails this probe: both members render at the same root.
        fun processed(id: String, hz: Float) = PatioCleanup.process(PatioArticulation.source(id, 729, hz), 729)
        val muted = processed("llamador", 230f)
        val answer = processed("repicador", 230f)
        assertTrue(FeatureExtractor.extract(answer).centroidHz > FeatureExtractor.extract(muted).centroidHz * 1.4)
        val low = processed("agogo-low", 700f)
        val high = processed("agogo-high", 700f)
        assertTrue(FeatureExtractor.extract(high).centroidHz > FeatureExtractor.extract(low).centroidHz * 1.2)
        fun tailFraction(x: FloatArray): Double {
            val late = (.12 * Dsp.RATE).toInt()
            return (late until x.size).sumOf { x[it].toDouble().pow(2) } / x.sumOf { it.toDouble().pow(2) }
        }
        assertTrue(tailFraction(high.samples) > tailFraction(low.samples) * 3)
    }
}
