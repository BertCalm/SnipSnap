package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.cos
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

    /** The upper-bank analytic envelope resolves partial beating that the slower
     * spectral-frame motion test cannot see. Keep it at audio rate: decimating an
     * unfiltered envelope would alias unrelated harmonic differences into roughness. */
    private fun upperEnvelopePower(samples: FloatArray): Pair<Double, Double> {
        fun paddedSize(n: Int): Int {
            var size = 1
            while (size < n) size = size shl 1
            return size
        }
        val size = paddedSize(samples.size)
        val real = samples.copyOf(size)
        val imaginary = FloatArray(size)
        Fft.forward(real, imaginary)
        for (i in real.indices) {
            val hz = i.toDouble() * Dsp.RATE / size
            if (hz in 400.0..1800.0) {
                // Keep twice the positive-frequency band only: inverse complex
                // magnitude is its analytic envelope, with no carrier rectification.
                real[i] *= 2f
                imaginary[i] *= 2f
            } else {
                real[i] = 0f
                imaginary[i] = 0f
            }
        }
        Fft.inverse(real, imaginary)

        // Discard both the attack and FFT band-filter boundary transients. Eight
        // seconds covers several default turns and resolves sub-Hz beating.
        val from = 2 * Dsp.RATE
        val count = 8 * Dsp.RATE
        val envelope = DoubleArray(count) { i ->
            val r = real[from + i].toDouble()
            val im = imaginary[from + i].toDouble()
            sqrt(r * r + im * im)
        }
        val mean = envelope.average()
        require(mean > 1e-8) { "ORBIT has no audible upper bank" }
        var trendCross = 0.0
        var trendSquare = 0.0
        for (i in envelope.indices) {
            val t = i.toDouble() / (count - 1) - .5
            envelope[i] = envelope[i] / mean - 1.0
            trendCross += t * envelope[i]
            trendSquare += t * t
        }
        val slope = trendCross / trendSquare
        val modulationSize = paddedSize(count)
        val modulation = FloatArray(modulationSize)
        for (i in envelope.indices) {
            val t = i.toDouble() / (count - 1) - .5
            val hann = .5 - .5 * cos(2 * PI * i / (count - 1))
            modulation[i] = ((envelope[i] - slope * t) * hann).toFloat()
        }
        val phase = FloatArray(modulationSize)
        Fft.forward(modulation, phase)
        var total = 0.0
        var slow = 0.0
        var rough = 0.0
        for (i in 1..modulationSize / 2) {
            val hz = i.toDouble() * Dsp.RATE / modulationSize
            if (hz < .5 || hz >= 95.0) continue
            val power = modulation[i].toDouble() * modulation[i] + phase[i].toDouble() * phase[i]
            total += power
            if (hz < 5.0) slow += power
            if (hz >= 20.0) rough += power
        }
        require(total > 1e-8) { "ORBIT has no measurable upper-bank motion" }
        return slow / total to rough / total
    }

    @Test
    fun `sustained upper petals circulate without rapid partial beating`() {
        val played = Corolla.play(voice, mapOf("TUNE" to .5f, "HOLD" to 1f), seconds = 12f)
        val samples = Corolla.finish(played.raw, normalize = false, fade = false)
        val (slow, rough) = upperEnvelopePower(samples)
        assertTrue(slow > .60,
            "ORBIT's upper-bank motion is not mostly slow circulation: slow share $slow, rough share $rough")
        assertTrue(rough < .20,
            "ORBIT retains audible rapid partial beating despite a slow core: rough share $rough")
    }
}
