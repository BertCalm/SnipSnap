package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Candidate voices for listening round accord-voices-1. Each one hears a mechanical
 * readout the kept voices discard: COURSE the mechanism (which string), ESCAPEMENT
 * the passage direction (tick or tock), REED the pressure (breath). BELL is a table.
 * The kept renders are pinned by AccordNativePinsTest; nothing here is heard.
 */
class AccordCandidateVoicesTest {
    private val candidates = AccordVoice.entries.filter { it !in Accord.KEPT_VOICES }

    private fun c3(voice: AccordVoice) = AccordAcoustics.configuration(voice, AccordAcousticProfile.BASE, 48, .75f)

    private fun event(cycle: Double, mechanism: Int, direction: Int) =
        AccordDynamics.Event(cycle, mechanism, direction, .4, (cycle * 100).toLong() * 15 + mechanism)

    private fun render(voice: AccordVoice, events: List<AccordDynamics.Event>, pressure: Double = 0.0) =
        AccordAcoustics.renderConditioned(c3(voice), events, DoubleArray(2000) { pressure }, 1.0,
            Accord.DEFAULT_SEED, 0.0, 2.0).samples

    private fun upperShare(x: FloatArray, hz: Double = 130.81): Double {
        val n = 8192
        var all = 0.0; var high = 0.0; var start = 0
        while (start + n <= x.size) {
            val re = FloatArray(n); val im = FloatArray(n)
            for (j in 0 until n) re[j] = (x[start + j] * (.5 - .5 * cos(2 * PI * j / (n - 1)))).toFloat()
            Fft.forward(re, im)
            for (bin in 1 until n / 2) {
                val e = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
                all += e; if (bin * Dsp.RATE.toDouble() / n > 1.7 * hz) high += e
            }
            start += n / 2
        }
        return high / max(all, 1e-30)
    }

    private fun energy(x: FloatArray, from: Int = 0, to: Int = x.size) = (from until to).sumOf { x[it].toDouble() * x[it] }

    /** Level-matched third-octave shape (24 bands, 40 Hz to 10 kHz) over the active eight seconds, dB. */
    private fun bands(x: FloatArray): DoubleArray {
        val n = 8192
        val e = DoubleArray(24)
        var total = 0.0; var start = 0
        while (start + n <= 8 * Dsp.RATE) {
            val re = FloatArray(n); val im = FloatArray(n)
            for (j in 0 until n) re[j] = (x[start + j] * (.5 - .5 * cos(2 * PI * j / (n - 1)))).toFloat()
            Fft.forward(re, im)
            for (bin in 1 until n / 2) {
                val hz = bin * Dsp.RATE.toDouble() / n
                if (hz < 40.0 || hz >= 10_000.0) continue
                val p = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
                e[(3.0 * ln(hz / 40.0) / ln(2.0)).toInt().coerceIn(0, 23)] += p; total += p
            }
            start += n / 2
        }
        return DoubleArray(24) { 10 * log10(e[it] / (total + 1e-30) + 1e-12) }
    }

    @Test
    fun `candidates use the edited-performance gain recipe, never an invented factory row`() {
        for (voice in candidates) {
            assertFailsWith<IllegalStateException> { AccordCalibration.gain(voice, AccordAcousticProfile.BASE, 48) }
            val factory = Accord.probe(voice)
            // The same normal/hard recipe a factory row would freeze, computed on demand.
            assertEquals(Accord.referenceCalibrationGain(voice, AccordAcousticProfile.BASE, 48),
                factory.diagnostics.calibrationGain, 1e-12)
            val touches = listOf(.3f, 1f).map { Accord.probe(voice, velocity = it).diagnostics.calibrationGain }
            for (gain in touches) assertEquals(factory.diagnostics.calibrationGain, gain, "$voice levelled a touch")
            assertTrue(factory.samples.peak() <= .94001f, "$voice peak ${factory.samples.peak()}")
            assertEquals(Math.round((8.0 + c3(voice).releaseSeconds) * Dsp.RATE).toInt(), factory.samples.frameCount)
        }
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice at C3`() {
        val shapes = AccordVoice.entries.associateWith { bands(Accord.render(it).samples) }
        for (a in candidates) for (b in AccordVoice.entries) if (a != b) {
            val d = sqrt(shapes.getValue(a).indices.sumOf { (shapes.getValue(a)[it] - shapes.getValue(b)[it]).pow(2) } / 24)
            println("ACCORD third-octave distance $a/$b = $d dB")
            // ETCH's bar for six distinct voices.
            assertTrue(d >= 3.7, "$a and $b are only $d dB apart")
        }
    }

    @Test
    fun `COURSE chooses its string by mechanism and nothing else`() {
        val strings = render(AccordVoice.COURSE, listOf(event(.5, 0, 1), event(.5, 1, 1), event(.5, 2, 1)))
        // Mechanisms 3, 4 and 5 strike the same three strings.
        assertContentEquals(strings, render(AccordVoice.COURSE, listOf(event(.5, 3, 1), event(.5, 4, 1), event(.5, 5, 1))))
        val oneString = render(AccordVoice.COURSE, listOf(event(.5, 0, 1), event(.5, 3, 1), event(.5, 6, 1)))
        var difference = 0.0
        for (i in strings.indices) difference += (strings[i] - oneString[i]).toDouble().pow(2)
        assertTrue(difference > energy(strings) * 1e-4, "three strings and one string rendered alike")
    }

    @Test
    fun `ESCAPEMENT ticks forward and tocks back`() {
        val times = (1..6).map { .25 * it }
        val forward = render(AccordVoice.ESCAPEMENT, times.map { event(it, 0, 1) })
        val backward = render(AccordVoice.ESCAPEMENT, times.map { event(it, 0, -1) })
        val tick = upperShare(forward)
        val tock = upperShare(backward)
        assertTrue(tick > 3 * tock, "tick upper share $tick against tock $tock")
        // A kept voice only widens its backward kernel slightly.
        val woodRatio = upperShare(render(AccordVoice.WOOD, times.map { event(it, 0, 1) })) /
            upperShare(render(AccordVoice.WOOD, times.map { event(it, 0, -1) }))
        assertTrue(woodRatio < 1.5, "WOOD forward/backward ratio $woodRatio")
    }

    @Test
    fun `REED sounds only while pressure blows it, louder and brighter as it blows harder`() {
        assertTrue(render(AccordVoice.REED, emptyList()).all { it == 0f }, "REED sounded without pressure or contact")
        val renders = listOf(.02, .07, .2).map { render(AccordVoice.REED, emptyList(), it) }
        val energies = renders.map { energy(it, Dsp.RATE / 4, Dsp.RATE) }
        val brightness = renders.map { upperShare(it.copyOfRange(Dsp.RATE / 4, Dsp.RATE)) }
        assertTrue(energies.zipWithNext().all { (a, b) -> b > a * 1.5 }, "REED energy did not follow pressure: $energies")
        assertTrue(brightness.zipWithNext().all { (a, b) -> b > a }, "REED did not brighten with pressure: $brightness")
        // Contacts alone only nudge the tongue: no tick onset.
        val nudged = render(AccordVoice.REED, (1..3).map { event(.5 * it, it, 1) })
        assertTrue(energy(nudged) < .05 * energy(renders[1]), "contacts dominate the reed")
    }
}
