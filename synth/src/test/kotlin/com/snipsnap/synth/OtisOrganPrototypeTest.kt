package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class OtisOrganPrototypeTest {
    private val rate = 44_100
    private fun isolated(bar: Int) = OtisOrganSettings(
        drawbars = List(9) { if (it == bar) 1f else 0f },
        click = 0f, percussionLevel = 0f, leakage = 0f, drive = 0f,
        cabinetHz = 20_000.0,
    )

    @Test
    fun `sub octave completes its own cycles without becoming a rectified fundamental`() {
        val samples = OtisOrganPrototype.synthesize(listOf(OtisPrototypeNote(midi = 69)), isolated(0), 71, rate)
        val sub = amplitude(samples, 220.0, .2, 1.2)
        val fundamental = amplitude(samples, 440.0, .2, 1.2)
        val mean = samples.sliceArray((rate / 5) until (rate * 6 / 5)).average()
        assertTrue(sub > .05, "16-foot stop did not sound at 220 Hz: $sub")
        assertTrue(fundamental < sub * .001, "16-foot phase restarted every 440 Hz period: $fundamental / $sub")
        assertTrue(abs(mean) < .0001, "16-foot stop accumulated DC: $mean")
    }

    @Test
    fun `ninth drawbar set includes the five times fundamental tierce`() {
        val samples = OtisOrganPrototype.synthesize(listOf(OtisPrototypeNote(midi = 69)), isolated(6), 72, rate)
        val tierce = amplitude(samples, 2200.0, .2, 1.2)
        assertTrue(tierce > .05, "1 3/5-foot tierce is missing: $tierce")
        assertTrue(amplitude(samples, 1760.0, .2, 1.2) < tierce * .001, "tierce duplicates the 2-foot stop")
    }

    @Test
    fun `overlapping keys suppress percussion until every held key is released`() {
        val first = OtisPrototypeNote(midi = 69, gateSeconds = .3)
        val settings = isolated(0).copy(drawbars = List(9) { 0f }, percussionLevel = .8f)
        val reference = OtisOrganPrototype.synthesize(listOf(first), settings, 73, rate)
        val phrase = OtisOrganPrototype.synthesize(listOf(
            first,
            OtisPrototypeNote(midi = 72, startSeconds = .1, gateSeconds = .5),
            OtisPrototypeNote(midi = 76, startSeconds = .4, gateSeconds = .1),
            OtisPrototypeNote(midi = 69, startSeconds = .8, gateSeconds = .3),
        ), settings, 73, rate)
        for (i in rate / 10 until (rate * .45).toInt()) {
            assertTrue(abs(phrase[i] - reference[i]) < 1e-7, "a legato note retriggered percussion at frame $i")
        }
        assertTrue(rms(phrase, .81, .85) > .02, "percussion did not rearm after all keys were released")
        assertTrue(rms(phrase, .65, .75) < 1e-7, "released keys kept producing percussion")
    }

    @Test
    fun `muted overlapping notes do not hold the percussion circuit closed`() {
        val settings = isolated(0).copy(drawbars = List(9) { 0f }, percussionLevel = .8f)
        val muted = OtisPrototypeNote(midi = 69, gateSeconds = 1.0, velocity = 0f)
        val silent = OtisOrganPrototype.synthesize(listOf(muted), settings, 74, rate)
        assertTrue(silent.all { it == 0f }, "zero velocity produced organ audio")
        val phrase = OtisOrganPrototype.synthesize(listOf(
            muted, OtisPrototypeNote(midi = 72, startSeconds = .2, gateSeconds = .3),
        ), settings, 74, rate)
        assertTrue(rms(phrase, .21, .25) > .02, "a muted held note suppressed audible percussion")
    }

    @Test
    fun `render seeds reproduce click and leakage without retaining state between calls`() {
        val notes = listOf(OtisPrototypeNote(midi = 57, gateSeconds = .4))
        val settings = OtisOrganSettings(rotary = true)
        val first = OtisOrganPrototype.synthesize(notes, settings, 84, rate)
        val other = OtisOrganPrototype.synthesize(notes, settings, 85, rate)
        val repeated = OtisOrganPrototype.synthesize(notes, settings, 84, rate)
        assertContentEquals(first, repeated)
        assertTrue(first.indices.any { first[it] != other[it] }, "different seeds produced the same attack and wheel phases")
        assertTrue(first.all { it.isFinite() }, "organ produced a non-finite sample")
        assertTrue(rms(first, .42, .47) > 1e-4, "oscillators stopped at note-off instead of following release")
        assertTrue(abs(first.last()) < 1e-6, "cabinet tail did not settle")
    }

    @Test
    fun `upper stops above the output band are removed before synthesis`() {
        val samples = OtisOrganPrototype.synthesize(
            listOf(OtisPrototypeNote(midi = 84, gateSeconds = .2)), isolated(8), 90, 8000,
        )
        assertTrue(samples.all { it == 0f }, "an ultrasonic drawbar survived the output Nyquist limit")
    }

    @Test
    fun `revised registration adds upper stop identity after body RMS matching without distortion`() {
        val notes = listOf(OtisPrototypeNote(midi = 69))
        val oldSettings = OtisOrganSettings(
            drawbars = listOf(1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
            click = 0f, percussionLevel = 0f, leakage = 0f, drive = 0f, cabinetHz = 4800.0,
        )
        val revisedSettings = OtisOrganSettings(click = 0f, percussionLevel = 0f, leakage = 0f, drive = 0f)
        val old = bodyMatched(render(1, notes, oldSettings), .3, 1.3)
        val revised = bodyMatched(render(2, notes, revisedSettings), .3, 1.3)
        fun upperFraction(samples: FloatArray): Double = listOf(2, 3, 4, 5, 6, 8).sumOf {
            val a = amplitude(samples, 440.0 * it, .3, 1.3)
            a * a
        } / amplitude(samples, 440.0, .3, 1.3).let { it * it }
        val before = upperFraction(old)
        val after = upperFraction(revised)
        assertTrue(abs(rms(old, .3, 1.3) - rms(revised, .3, 1.3)) < 1e-7, "body comparison is not RMS matched")
        assertTrue(after > .02 && after > before * 4, "upper registration did not gain character at equal RMS: $before -> $after")
        assertTrue(amplitude(revised, 440.0, .3, 1.3) > amplitude(revised, 443.0, .3, 1.3) * 10,
            "the stronger registration moved the saved root")
    }

    @Test
    fun `revised contact has more bite through decimation while the held stop stays the same level`() {
        val notes = listOf(OtisPrototypeNote(midi = 69))
        val settings = isolated(2).copy(click = .35f)
        val old = render(1, notes, settings)
        val oldDry = render(1, notes, settings.copy(click = 0f))
        val revised = render(2, notes, settings)
        val revisedDry = render(2, notes, settings.copy(click = 0f))
        // Subtract a separately rendered stop, so neither a changed attack
        // phase nor a louder sustained registration can masquerade as contact.
        val oldContact = FloatArray(old.size) { old[it] - oldDry[it] }
        val revisedContact = FloatArray(revised.size) { revised[it] - revisedDry[it] }
        val before = rms(oldContact, 0.0, .012) / rms(oldDry, .3, 1.3)
        val after = rms(revisedContact, 0.0, .012) / rms(revisedDry, .3, 1.3)
        assertTrue(abs(rms(oldDry, .3, 1.3) - rms(revisedDry, .3, 1.3)) < 1e-7,
            "contact revision raised the held stop's level")
        assertTrue(after > before * 1.6, "the new contact did not survive body-level matching: $before -> $after")
    }

    @Test
    fun `revised rotor has stronger motion sidebands at equal body RMS and the same root`() {
        val notes = listOf(OtisPrototypeNote(midi = 69, gateSeconds = 3.2))
        val settings = isolated(0).copy(
            rotary = true, rotaryStart = OtisOrganRotary.FAST, rotaryTarget = OtisOrganRotary.FAST,
            rotaryChangeSeconds = 0.0, rotaryDepth = .7f,
        )
        val old = bodyMatched(render(1, notes, settings), .6, 2.6)
        val revised = bodyMatched(render(2, notes, settings), .6, 2.6)
        fun movement(samples: FloatArray): Double {
            val sides = listOf(220.0 - 5.8, 220.0 + 5.8, 220.0 - 6.7, 220.0 + 6.7).sumOf {
                val a = windowedAmplitude(samples, it, .6, 2.6)
                a * a
            }
            val carrier = windowedAmplitude(samples, 220.0, .6, 2.6)
            return sides / (carrier * carrier)
        }
        val before = movement(old)
        val after = movement(revised)
        assertTrue(after > before * 1.25, "rotary movement did not increase at equal RMS: $before -> $after")
        assertTrue(windowedAmplitude(revised, 220.0, .6, 2.6) > windowedAmplitude(revised, 223.0, .6, 2.6) * 5,
            "the revised cabinet transposed its carrier")
    }

    /** Measure the actual native-rate output after the shared 4x decimator. */
    private fun render(model: Int, notes: List<OtisPrototypeNote>, settings: OtisOrganSettings): FloatArray {
        val internal = if (model == 1) OtisOrganPrototypeV1.synthesize(notes, settings, 211, rate * 4)
            else OtisOrganPrototype.synthesize(notes, settings, 211, rate * 4)
        return Dsp.decimate(internal, rate)
    }

    private fun bodyMatched(samples: FloatArray, start: Double, end: Double): FloatArray {
        val gain = .1 / rms(samples, start, end)
        return FloatArray(samples.size) { (samples[it] * gain).toFloat() }
    }

    private fun windowedAmplitude(samples: FloatArray, hz: Double, start: Double, end: Double): Double {
        val from = (start * rate).toInt()
        val to = (end * rate).toInt()
        var real = 0.0
        var imaginary = 0.0
        var weights = 0.0
        for (i in from until to) {
            val weight = .5 - .5 * cos(2.0 * PI * (i - from) / (to - from - 1))
            val angle = 2.0 * PI * hz * i / rate
            real += samples[i] * weight * cos(angle)
            imaginary += samples[i] * weight * sin(angle)
            weights += weight
        }
        return 2.0 * sqrt(real * real + imaginary * imaginary) / weights
    }

    private fun amplitude(samples: FloatArray, hz: Double, start: Double, end: Double): Double {
        val from = (start * rate).toInt()
        val to = (end * rate).toInt()
        var real = 0.0
        var imaginary = 0.0
        for (i in from until to) {
            val angle = 2.0 * PI * hz * i / rate
            real += samples[i] * cos(angle)
            imaginary += samples[i] * sin(angle)
        }
        return 2.0 * sqrt(real * real + imaginary * imaginary) / (to - from)
    }

    private fun rms(samples: FloatArray, start: Double, end: Double): Double {
        val from = (start * rate).toInt()
        val to = (end * rate).toInt()
        return sqrt((from until to).sumOf { samples[it].toDouble() * samples[it] } / (to - from))
    }
}
