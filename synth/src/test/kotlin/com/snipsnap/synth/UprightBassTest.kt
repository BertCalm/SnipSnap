package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UprightBassTest {
    private fun rms(a: FloatArray, from: Float = 0f, until: Float = UprightBass.SECONDS): Double {
        val start = (from * Dsp.RATE).toInt().coerceIn(0, a.size)
        val end = (until * Dsp.RATE).toInt().coerceIn(start, a.size)
        var sum = 0.0
        for (i in start until end) sum += a[i].toDouble() * a[i]
        return sqrt(sum / (end - start).coerceAtLeast(1))
    }

    /** A level-independent attack-spectrum proxy, not a hearing threshold. */
    private fun attackEdge(a: FloatArray): Double {
        val start = (Dsp.RATE * .01).toInt()
        val end = (Dsp.RATE * .14).toInt()
        var changes = 0.0
        var energy = 0.0
        for (i in start until end) {
            val d = a[i].toDouble() - a[i - 1]
            changes += d * d
            energy += a[i].toDouble() * a[i]
        }
        return sqrt(changes / max(energy, 1e-30))
    }

    @Test
    fun `settling drops unknown keys and restores nonfinite controls`() {
        assertEquals(listOf("TUNE", "TOUCH", "DAMP", "WOOD"), UprightBass.macrosFor().map { it.name })
        assertEquals(UprightBass.defaults(), UprightBass.settled(mapOf(
            "TUNE" to Float.NaN, "TOUCH" to Float.POSITIVE_INFINITY,
            "DAMP" to Float.NEGATIVE_INFINITY, "WOOD" to Float.NaN, "UNKNOWN" to 1f,
        )))
        assertEquals(0f, UprightBass.settled(mapOf("TOUCH" to -2f)).getValue("TOUCH"))
        assertEquals(1f, UprightBass.settled(mapOf("WOOD" to 3f)).getValue("WOOD"))
        assertContentEquals(UprightBass.render().samples, UprightBass.render(mapOf("UNKNOWN" to 1f)).samples)
    }

    @Test
    fun `the voice has exact E1 to E3 notes and defaults to A1`() {
        assertEquals(41.203445f, UprightBass.frequencyFor(0f), .00001f)
        assertEquals(55f, UprightBass.frequencyFor(UprightBass.DEFAULT_TUNE), .00001f)
        assertEquals(164.81378f, UprightBass.frequencyFor(1f), .0001f)
    }

    @Test
    fun `reference cache never gives ownership of stored PCM to the caller`() {
        val first = UprightBass.render().samples
        val expected = first.copyOf()
        first.fill(0f)
        assertContentEquals(expected, UprightBass.render().samples)
        assertContentEquals(UprightBass.render(velocity = .35f).samples, UprightBass.render(velocity = .35f).samples)
    }

    @Test
    fun `low and high roots survive damping and body corners`() {
        for (tune in listOf(0f, UprightBass.DEFAULT_TUNE, .5f, 1f)) {
            for (damp in listOf(0f, 1f)) for (wood in listOf(0f, 1f)) {
                val m = mapOf("TUNE" to tune, "DAMP" to damp, "WOOD" to wood)
                val snip = UprightBass.render(m, .65f)
                assertEquals(Dsp.RATE, snip.sampleRate)
                assertEquals(1, snip.channels)
                assertEquals((UprightBass.SECONDS * Dsp.RATE).toInt(), snip.samples.size)
                assertTrue(snip.samples.all { it.isFinite() }, "$m finite")
                assertTrue(snip.samples.maxOf { abs(it) } <= .99001f, "$m peak")
                assertTrue(abs(snip.samples.average()) < .001, "$m DC")
                val pitch = Pitch.detect(snip, .05f, .3f)
                assertTrue(pitch != null, "$m no detected root")
                val cents = 1200 * ln(pitch!!.hz / UprightBass.frequencyFor(tune)) / ln(2f)
                assertTrue(abs(cents) < 10f, "$m root error $cents cents")
                assertTrue(pitch.confidence >= .3f, "$m root confidence ${pitch.confidence}")
            }
        }
    }

    @Test
    fun `playing strength changes level and the level independent attack`() {
        for (tune in listOf(0f, UprightBass.DEFAULT_TUNE, .5f, 1f)) {
            val soft = UprightBass.render(mapOf("TUNE" to tune), .35f).samples
            val hard = UprightBass.render(mapOf("TUNE" to tune), 1f).samples
            assertTrue(rms(soft) < rms(hard) * .75, "TUNE=$tune soft level was normalized away")
            assertTrue(attackEdge(hard) > attackEdge(soft) * 1.01, "TUNE=$tune excitation brightness is inert")
        }
        val silent = UprightBass.render(velocity = 0f).samples
        assertTrue(silent.all { it == 0f })
    }

    @Test
    fun `touch changes excitation and damping shortens the string itself`() {
        val softTouch = UprightBass.render(mapOf("TOUCH" to 0f), .65f).samples
        val firmTouch = UprightBass.render(mapOf("TOUCH" to 1f), .65f).samples
        assertTrue(attackEdge(firmTouch) > attackEdge(softTouch) * 1.02, "TOUCH must change spectrum independently of level")
        val round = UprightBass.render(mapOf("DAMP" to .18f), .65f).samples
        val muted = UprightBass.render(mapOf("DAMP" to 1f), .65f).samples
        val roundTail = rms(round, .65f, .9f) / rms(round, .05f, .25f)
        val mutedTail = rms(muted, .65f, .9f) / rms(muted, .05f, .25f)
        assertTrue(mutedTail < roundTail * .2, "DAMP must shorten loss: $roundTail -> $mutedTail")
    }

    @Test
    fun `wood changes the one way readout without replacing the string`() {
        val bare = UprightBass.render(mapOf("WOOD" to 0f), .65f).samples
        val full = UprightBass.render(mapOf("WOOD" to 1f), .65f).samples
        var aa = 0.0
        var ab = 0.0
        for (i in bare.indices) { aa += bare[i].toDouble() * bare[i]; ab += bare[i].toDouble() * full[i] }
        val fit = ab / aa
        var error = 0.0
        var energy = 0.0
        for (i in bare.indices) {
            val d = full[i] - fit * bare[i]
            error += d * d
            energy += full[i].toDouble() * full[i]
        }
        assertTrue(sqrt(error / energy) > .001, "WOOD must change more than gain")
    }

    @Test
    fun `unforced feedback remains below its initial state and an unstable counterexample fails`() {
        val stable = UprightBass.rawForTest(UprightBass.frequencyFor(0f), mapOf("DAMP" to 0f))
        val d = stable.diagnostics
        assertTrue(d.feedbackGain in 0.0..1.0 && d.feedbackGain < 1.0)
        assertTrue(d.lossCoefficient in 0.0..1.0)
        assertTrue(d.fractionalDelay in 0.0..1.0)
        assertTrue(d.unforcedPeak <= d.initialPeak * 1.000001, "unforced string grew beyond initial state")
        val unstable = UprightBass.rawForTest(UprightBass.frequencyFor(0f), mapOf("DAMP" to 0f), feedbackMultiplier = 1.2)
        assertTrue(unstable.diagnostics.unforcedPeak > unstable.diagnostics.initialPeak * 2,
            "energy probe must reject feedback growth rather than normalizing it away")
    }
}
