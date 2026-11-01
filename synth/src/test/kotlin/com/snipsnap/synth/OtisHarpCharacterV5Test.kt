package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Emitted-audio checks. They establish numerical/gesture behavior, not harmonica likeness. */
class OtisHarpCharacterV5Test {
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE
    private val plain = OtisAirSettings(harpBend = 0f, breath = 0f, harpVibrato = 0f,
        pressure = .8f, release = .35f)

    private fun flat(level: Double = 1.0, clock: Int = rate) = OtisPhrasePressureTrace(
        doubleArrayOf(level, level), doubleArrayOf(0.0, 0.0), doubleArrayOf(level, level), clock, clock)

    private fun render(notes: List<OtisPrototypeNote>, settings: OtisAirSettings = plain,
        trace: OtisPhrasePressureTrace = flat(), seed: Int = 724) =
        OtisHarpCharacterV5.synthesize(notes, settings, seed, trace.rate, trace)

    @Test
    fun `shared pressure changes sustained level and upper reed balance rather than only scaling a note`() {
        val notes = listOf(OtisPrototypeNote(60, gateSeconds = .8))
        val full = render(notes)
        val reduced = render(notes, trace = flat(.45))
        val fullRms = rms(full, .30, .65)
        val reducedRms = rms(reduced, .30, .65)
        val root = hz(60)
        fun upperRatio(samples: FloatArray) = (2..12).sumOf { power(samples, root * it, .30, .65) } /
            power(samples, root, .30, .65)
        val fullRatio = upperRatio(full)
        val reducedRatio = upperRatio(reduced)
        assertTrue(fullRms > 1.5 * reducedRms && reducedRms > .1 * fullRms,
            "pressure should soften the reed without erasing it: RMS $fullRms -> $reducedRms")
        assertTrue(fullRatio > 1.4 * reducedRatio,
            "RMS matching would erase a gain-only change: upper/root $fullRatio -> $reducedRatio")
        println("HARP5 pressure: rawRms=$fullRms->$reducedRms upper/root=$fullRatio->$reducedRatio")
    }

    @Test
    fun `later notes sample shared pressure at absolute internal frame time`() {
        val note = listOf(OtisPrototypeNote(60, .25, .75, .8f))
        val reference = render(note)
        val stride = 176
        val dip = (.60 * rate / stride).toInt()
        val points = DoubleArray(ceil(1.5 * rate / stride).toInt() + 2) { if (it < dip) 1.0 else .45 }
        val trace = OtisPhrasePressureTrace(points, DoubleArray(points.size), points.copyOf(), stride, rate)
        val changed = render(note, trace = trace)
        val firstChangedFrame = (dip - 1) * stride
        assertContentEquals(reference.copyOfRange(0, firstChangedFrame), changed.copyOfRange(0, firstChangedFrame),
            "a future pressure change altered the preceding note")
        assertTrue(rms(changed, .68, .84) < .75 * rms(reference, .68, .84),
            "the pressure dip was deferred until local note time .60 rather than absolute score time .60")
        val root = hz(60)
        fun upperRatio(samples: FloatArray) = (2..10).sumOf { power(samples, root * it, .68, .84) } /
            power(samples, root, .68, .84)
        assertTrue(upperRatio(changed) < .75 * upperRatio(reference), "late-note pressure changed only output gain")
    }

    @Test
    fun `blow and draw chord examples retain independent roots and local source state`() {
        var worstRelative = 0.0
        for (midis in listOf(listOf(60, 64, 67), listOf(67, 71, 74, 77))) {
            val notes = midis.map { OtisPrototypeNote(it, .03, .65, .68f) }
            val together = render(notes)
            val separate = notes.map { render(listOf(it)) }
            val sum = FloatArray(together.size) { frame -> separate.sumOf { it[frame].toDouble() }.toFloat() }
            val reverse = render(notes.reversed())
            for (candidate in listOf(sum, reverse)) {
                val error = sqrt(together.indices.sumOf { (together[it].toDouble() - candidate[it]).pow(2) } /
                    together.sumOf { it.toDouble() * it })
                worstRelative = maxOf(worstRelative, error)
                assertTrue(error < 1e-6, "a chord shares oscillator/noise/filter state or depends on list order: $error")
            }
            for (midi in midis) {
                val atRoot = power(together, hz(midi), .20, .55)
                val nearby = power(together, hz(midi) + 13, .20, .55)
                assertTrue(atRoot > 20 * nearby, "MIDI$midi disappeared inside the chord: $atRoot / $nearby")
            }
        }
        println("HARP5 chord state: worst relative RMS against separate-note sum/reordering=$worstRelative")
    }

    @Test
    fun `mouth focus preserves C3 C4 C5 roots and the bent entrance settles into its saved note`() {
        var largestCents = 0.0
        var lowestConfidence = 1f
        for ((midi, bend) in listOf(48 to 0f, 60 to 0f, 72 to 0f, 60 to .9f)) {
            val samples = render(listOf(OtisPrototypeNote(midi, gateSeconds = .9)), plain.copy(harpBend = bend))
            val audio = OtisPrototypeV4.condition(samples)
            val detected = assertNotNull(Pitch.detect(audio, .45f, .25f), "MIDI$midi bend$bend lost its fundamental")
            val cents = 1200 * ln(detected.hz / hz(midi)) / ln(2.0)
            largestCents = maxOf(largestCents, abs(cents))
            lowestConfidence = minOf(lowestConfidence, detected.confidence)
            assertTrue(abs(cents) < 6 && detected.confidence > .8f,
                "MIDI$midi bend$bend root moved $cents cents, confidence=${detected.confidence}")
        }
        println("HARP5 settled pitch: worst=$largestCents cents minimumConfidence=$lowestConfidence")
    }

    @Test
    fun `high register excludes upper guard band partials and their possible folds without relying on a limiter`() {
        // Probe the source at 1x as well: removing its taper would emit 18th--24th harmonics here,
        // some above Nyquist, rather than merely testing a final low-pass which hides the defect.
        val clock = Dsp.RATE
        val root = hz(84)
        val samples = render(listOf(OtisPrototypeNote(84, gateSeconds = .85)),
            plain.copy(pressure = 1f), flat(clock = clock))
        val reference = power(samples, root, .40, .75, clock)
        val excluded = (18..24).sumOf { harmonic ->
            val folded = abs((root * harmonic + clock / 2.0) % clock - clock / 2.0)
            power(samples, folded, .40, .75, clock)
        }
        val ratio = excluded / reference
        assertTrue(reference > 1.0 && ratio < 1e-6, "excluded harmonic/fold energy exceeds -60dB: $ratio")
        println("HARP5 high-register excluded harmonic/fold power relative to root=$ratio")
    }

    @Test
    fun `breath tongue and release remain finite reproducible silent before onset and quiet at the tail`() {
        val notes = listOf(60, 64, 67, 71).map { OtisPrototypeNote(it, .08, .30, .9f) }
        val settings = OtisAirSettings(harpBend = 1f, breath = 1f, harpVibrato = 1f,
            pressure = 1f, release = 1f)
        val first = render(notes, settings)
        render(listOf(OtisPrototypeNote(72, gateSeconds = .1)), plain, seed = 15)
        val repeated = render(notes, settings)
        assertContentEquals(first, repeated, "source stores leaked across renders")
        assertEquals(OtisAirCharacterV5.frameCount(OtisPrototypeVoice.HARP, notes, settings, rate), first.size)
        assertTrue(first.all { it.isFinite() && abs(it) < 8f }, "four-note source grew outside its disclosed raw bound")
        assertTrue(first.take((.08 * rate).toInt()).all { it == 0f }, "tongue or noise sounded before the key")
        assertTrue(first.takeLast(256).all { abs(it) < 2e-6f }, "finite breath release was truncated at an audible level")
        assertTrue(render(notes.map { it.copy(velocity = 0f) }, settings).all { it == 0f }, "zero velocity made air noise")
        assertTrue(render(notes, settings, flat(0.0)).all { it == 0f }, "an exhausted source emitted work")
        assertFailsWith<IllegalArgumentException> {
            OtisHarpCharacterV5.synthesize(notes, settings, 724, rate, flat(clock = Dsp.RATE))
        }
    }

    private fun hz(midi: Int) = 440 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double, clock: Int = rate): Double {
        val first = (from * clock).toInt()
        val end = (to * clock).toInt()
        return sqrt((first until end).sumOf { x[it].toDouble() * x[it] } / (end - first))
    }
    private fun power(x: FloatArray, hz: Double, from: Double, to: Double, clock: Int = rate): Double {
        val first = (from * clock).toInt()
        val count = ((to - from) * clock).toInt()
        var real = 0.0
        var imaginary = 0.0
        for (index in 0 until count) {
            val sample = x[first + index] * (.5 - .5 * cos(2 * PI * index / (count - 1)))
            val phase = 2 * PI * hz * index / clock
            real += sample * cos(phase)
            imaginary += sample * sin(phase)
        }
        return real * real + imaginary * imaginary
    }
}
