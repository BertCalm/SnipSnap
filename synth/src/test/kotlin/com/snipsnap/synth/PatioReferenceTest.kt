package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import kotlin.math.*
import kotlin.test.*

class PatioReferenceTest {
    @Test fun `reference sources replay with healthy corners`() {
        for (stroke in PatioHand.Stroke.entries) {
            assertContentEquals(PatioHand.render(stroke, reference = true).samples, PatioHand.render(stroke, reference = true).samples)
            for (t in listOf(0f, 1f)) for (h in listOf(0f, 1f)) for (f in listOf(0f, 1f)) {
                healthy(PatioHand.render(stroke, PatioHand.Settings(t, f, h), reference = true).samples)
            }
        }
        for (direction in PatioScrape.Direction.entries) {
            assertContentEquals(PatioScrape.render(direction).samples, PatioScrape.render(direction).samples)
            for (t in listOf(0f, 1f)) for (h in listOf(0f, 1f)) healthy(PatioScrape.render(direction, t, h).samples)
        }
    }
    @Test fun `reference changes shift spectral balance in the intended directions`() {
        val old = FeatureExtractor.extract(PatioHand.render(PatioHand.Stroke.SLAP)).centroidHz
        val new = FeatureExtractor.extract(PatioHand.render(PatioHand.Stroke.SLAP, reference = true)).centroidHz
        assertTrue(new < old * .5f)
        for (d in PatioScrape.Direction.entries) {
            assertTrue(FeatureExtractor.extract(PatioScrape.render(d)).centroidHz > 2000f)
        }
        assertTrue(PatioScrape.render(PatioScrape.Direction.RETURN).frameCount < PatioScrape.render(PatioScrape.Direction.FORWARD).frameCount)
    }
    @Test fun `resting hand removes late head energy`() {
        fun late(v: PatioHand.Stroke): Double {
            val x = PatioHand.render(v, reference = true).samples
            return x.drop(Dsp.RATE / 10).sumOf { it.toDouble().pow(2) } / x.sumOf { it.toDouble().pow(2) }
        }
        assertTrue(late(PatioHand.Stroke.SLAP) < late(PatioHand.Stroke.OPEN) * .3)
    }
    @Test fun `matched scrape return is front loaded and materially shorter`() {
        for (hold in listOf(.15f, .45f, .8f)) {
            val forward = AuditionLevel.level(PatioScrape.render(PatioScrape.Direction.FORWARD, hold = hold, modelVersion = 2)).samples
            val back = AuditionLevel.level(PatioScrape.render(PatioScrape.Direction.RETURN, hold = hold, modelVersion = 2)).samples
            fun energyTime(x: FloatArray, fraction: Double): Double {
                val target = x.sumOf { it.toDouble().pow(2) } * fraction
                var sum = 0.0
                for (i in x.indices) { sum += x[i].toDouble().pow(2); if (sum >= target) return i.toDouble() / Dsp.RATE }
                error("no energy")
            }
            val f90 = energyTime(forward, .9); val r90 = energyTime(back, .9)
            println("PATIO SCRAPE hold=$hold energy90 forward=$f90 return=$r90")
            assertTrue(r90 < f90 * .45)
            assertTrue(energyTime(back, .5) < energyTime(forward, .5) * .4)
        }
        assertContentEquals(PatioScrape.render(PatioScrape.Direction.FORWARD, modelVersion = 1).samples,
            PatioScrape.render(PatioScrape.Direction.FORWARD, modelVersion = 2).samples)
        assertFailsWith<IllegalArgumentException> { PatioScrape.render(PatioScrape.Direction.RETURN, modelVersion = 0) }
    }
    @Test fun `fuller return retains energy beyond the old short catch`() {
        fun t90(x: FloatArray): Double {
            val target = x.sumOf { it.toDouble().pow(2) } * .9
            var energy = 0.0
            for (i in x.indices) { energy += x[i].toDouble().pow(2); if (energy >= target) return i.toDouble() / Dsp.RATE }
            error("silent")
        }
        for (hold in listOf(.15f, .45f, .8f)) {
            val old = AuditionLevel.level(PatioScrape.render(PatioScrape.Direction.RETURN, hold = hold, modelVersion = 2))
            val new = AuditionLevel.level(PatioScrape.render(PatioScrape.Direction.RETURN, hold = hold))
            println("PATIO full return hold=$hold old90=${t90(old.samples)} new90=${t90(new.samples)}")
            assertTrue(t90(new.samples) > t90(old.samples) * 2.5)
        }
        for (d in PatioScrape.Direction.entries) {
            val old = FeatureExtractor.extract(PatioScrape.render(d, modelVersion = 2)).centroidHz
            val new = FeatureExtractor.extract(PatioScrape.render(d)).centroidHz
            assertTrue(new < old && new > 2000f, "$d centroid old=$old new=$new")
        }
    }
    private fun healthy(x: FloatArray) {
        assertTrue(x.all { it.isFinite() }); assertTrue(x.maxOf { abs(it) } in .001f..1f)
        assertTrue(abs(x.average()) < .001); assertTrue(abs(x.last()) < 1e-6f)
    }
}
