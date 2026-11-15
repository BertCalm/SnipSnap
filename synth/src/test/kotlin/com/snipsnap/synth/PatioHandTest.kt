package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import kotlin.math.*
import kotlin.test.*

class PatioHandTest {
    @Test fun `strokes replay and all control corners remain healthy`() {
        for (v in PatioHand.Stroke.entries) {
            assertContentEquals(PatioHand.render(v).samples, PatioHand.render(v).samples)
            for (t in listOf(0f, 1f)) for (f in listOf(0f, 1f)) for (h in listOf(0f, 1f)) {
                val s = PatioHand.render(v, PatioHand.Settings(t, f, h))
                assertTrue(s.samples.all { it.isFinite() })
                assertTrue(s.peak() in .001f..1f)
                assertTrue(abs(s.samples.average()) < .001)
                assertTrue(abs(s.samples.last()) < 1e-6f)
            }
        }
        assertFailsWith<IllegalArgumentException> { PatioHand.Settings(touch = Float.NaN) }
    }
    @Test fun `slap has a brighter attack and less late body than open`() {
        val open = PatioHand.render(PatioHand.Stroke.OPEN)
        val slap = PatioHand.render(PatioHand.Stroke.SLAP)
        fun attack(s: com.snipsnap.audio.Snip) = FeatureExtractor.extract(s.copy(samples = s.samples.take(Dsp.RATE / 25).toFloatArray())).centroidHz
        fun late(x: FloatArray): Double = x.drop(Dsp.RATE / 10).sumOf { it.toDouble().pow(2) } / x.sumOf { it.toDouble().pow(2) }
        println("PATIO HAND attack centroids: open=${attack(open)} slap=${attack(slap)}; late fractions open=${late(open.samples)} slap=${late(slap.samples)}")
        assertTrue(attack(slap) > attack(open) * 1.5)
        assertTrue(late(slap.samples) < late(open.samples) * .2)
        val previous = PatioCharacter.render(PatioCharacter.Voice.SLAP)
        assertTrue(attack(slap) > attack(previous) * 1.5)
    }
    @Test fun `touch changes spectral balance after matching level and all controls move`() {
        for (v in PatioHand.Stroke.entries) {
            val a = PatioHand.render(v).samples
            for (m in listOf(PatioHand.Settings(tune = .2f), PatioHand.Settings(touch = .2f), PatioHand.Settings(hold = .8f))) {
                assertFalse(a.contentEquals(PatioHand.render(v, m).samples))
            }
            val soft = PatioHand.render(v, PatioHand.Settings(touch = .1f))
            val hard = PatioHand.render(v, PatioHand.Settings(touch = .9f))
            assertTrue(FeatureExtractor.extract(hard).centroidHz > FeatureExtractor.extract(soft).centroidHz)
            assertFalse(a.contentEquals(PatioHand.render(v, seed = 71).samples))
        }
    }
    @Test fun `head cavity and contact have separately decaying finite energy`() {
        for (v in PatioHand.Stroke.entries) {
            val p = PatioHand.parts(v, PatioHand.Settings(), 42)
            for (x in listOf(p.head, p.cavity, p.skin)) {
                val rate = Dsp.RATE * Dsp.OVERSAMPLE
                val attack = x.take(rate / 10).sumOf { it.toDouble().pow(2) }
                val tail = x.takeLast(rate / 10).sumOf { it.toDouble().pow(2) }
                assertTrue(attack > 0 && tail < attack * .001, "$v: $tail / $attack")
            }
        }
    }
}
