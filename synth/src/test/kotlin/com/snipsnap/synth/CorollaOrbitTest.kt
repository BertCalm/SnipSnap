package com.snipsnap.synth

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ORBIT should circulate at a perceptible pace and start free-pitched responders
 * once, without depending on a rapid train of magnetic passage impacts. */
class CorollaOrbitTest {
    private val voice = CorollaVoice.ORBIT
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    private fun rms(x: FloatArray, fromSec: Double, untilSec: Double): Double {
        val from = (fromSec * rate).toInt()
        val until = (untilSec * rate).toInt().coerceAtMost(x.size)
        return sqrt((from until until).sumOf { x[it].toDouble() * x[it] } / (until - from))
    }

    @Test
    fun `the orbit clock stays slow across its control range and has a true off step`() {
        assertEquals(0.0, Corolla.coreHz(0f, voice))
        val rates = (1..100).map { Corolla.coreHz(it / 100f, voice) }
        assertTrue(rates.all { it > 0.0 && it <= 2.0 },
            "ORBIT reaches a motor-buzz clock instead of slow circulation: ${rates.last()}")
        assertTrue(rates.zipWithNext().all { (a, b) -> b > a }, "FIELD reverses or stalls the orbit rate")
        assertTrue(rates.first() < .10, "the slow end cannot make a gradual turn")
        val default = Corolla.coreHz(Corolla.defaults(voice).getValue("FIELD"), voice)
        assertTrue(default in .5..1.5, "the default orbit cannot be heard as circulation: $default Hz")
        assertTrue(rates.last() >= 1.5, "the fast end has no useful motion range")
    }

    @Test
    fun `field release starts isolated responding petals before the first orbit passage`() {
        val field = Corolla.defaults(voice).getValue("FIELD")
        for (hold in listOf(0f, 1f)) {
            val played = Corolla.play(voice, mapOf("FIELD" to field, "CONTACT" to 0f, "HOLD" to hold),
                probe = Corolla.Probe(record = true, coupling = false, chamber = false,
                    pull = false, opening = false), seconds = .8f)
            val taps = requireNotNull(played.taps)

            // With mounting links, contact and performer pull removed, only the
            // bounded initial field release can wake these responding coordinates.
            // This window is well before the first neighbor's slow core passage.
            val firstPassage = 1 / (6 * Corolla.coreHz(field, voice))
            assertTrue(firstPassage > .04)
            assertTrue(rms(taps.neighbors, .012, .04) > 1e-5,
                "HOLD $hold waits for a recurring field impact to wake its neighbors")
            val direct = rms(taps.direct, .3, .7)
            val responding = rms(taps.neighbors, .3, .7)
            assertTrue(direct > 1e-4 && responding > direct * .01,
                "HOLD $hold does not maintain responding modes after its one release: $responding / $direct")
            assertTrue(taps.poweredInput.sumOf { it.toDouble() } > 0.0)
            assertTrue(played.raw.all { it.isFinite() })
        }
    }

    @Test
    fun `an unstruck orbit with no field seed stays silent even with maintenance enabled`() {
        for (hold in listOf(0f, 1f)) {
            val played = Corolla.play(voice, mapOf("FIELD" to 0f, "CONTACT" to 0f, "HOLD" to hold),
                probe = Corolla.Probe(record = true, pull = false), seconds = .1f)
            assertTrue(played.raw.all { it == 0f }, "HOLD $hold creates sound from an unseeded zero state")
            assertTrue(requireNotNull(played.taps).poweredInput.all { it == 0f })
        }
    }
}
