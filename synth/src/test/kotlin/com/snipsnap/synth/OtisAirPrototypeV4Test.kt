package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Compare emitted sources with the frozen implementation at both real synthesis clocks. */
class OtisAirPrototypeV4Test {
    private val plain = OtisAirSettings(steamInstability = 0f, harpBend = 0f, breath = 0f,
        harpVibrato = 0f, musette = 0f, bellowsPulse = 0f)
    private val edge = OtisAirSettings(steamInstability = 1f, harpBend = 1f, breath = 1f,
        harpVibrato = 1f, musette = 1f, bellowsPulse = 1f, pressure = 1f, release = .2f)

    private data class Case(val voice: OtisPrototypeVoice, val rate: Int,
        val notes: List<OtisPrototypeNote>, val settings: OtisAirSettings)

    private fun note(midi: Int, velocity: Float) = listOf(OtisPrototypeNote(midi, .03, .36, velocity))

    private fun compatibilityCases() = listOf(
        Case(OtisPrototypeVoice.STEAM, 44_100, note(48, .35f), plain),
        Case(OtisPrototypeVoice.STEAM, 176_400, note(72, .9f), edge),
        Case(OtisPrototypeVoice.HARP, 176_400, note(48, .35f), edge),
        Case(OtisPrototypeVoice.HARP, 44_100, note(72, .9f), plain),
        Case(OtisPrototypeVoice.SQUEEZE, 44_100, note(60, .9f), plain),
        Case(OtisPrototypeVoice.SQUEEZE, 176_400, note(72, .35f), edge),
        Case(OtisPrototypeVoice.STEAM, 44_100, listOf(
            OtisPrototypeNote(48, .03, .36, .35f), OtisPrototypeNote(60, .08, .24, .65f),
            OtisPrototypeNote(72, .03, .30, .9f)), edge),
        Case(OtisPrototypeVoice.SQUEEZE, 176_400, listOf(
            OtisPrototypeNote(48, .03, .36, .35f), OtisPrototypeNote(60, .08, .24, .65f),
            OtisPrototypeNote(72, .03, .30, .9f)), edge),
    )

    private fun power(x: FloatArray, hz: Double, rate: Int): Double {
        val first = (.10 * rate).toInt()
        val count = (.18 * rate).toInt()
        var re = 0.0
        var im = 0.0
        for (i in 0 until count) {
            val sample = x[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))
            val phase = 2 * PI * hz * i / rate
            re += sample * cos(phase)
            im += sample * sin(phase)
        }
        return re * re + im * im
    }

    @Test
    fun `harmonic recurrence preserves emitted samples and spectra at native and four times rates`() {
        var worstRelativeRms = 0.0
        var worstAbsoluteError = 0.0
        var worstSpectralDifference = 0.0
        for (case in compatibilityCases()) {
            val old = OtisAirPrototype.synthesize(case.voice, case.notes, case.settings, 20261014, case.rate)
            val current = OtisAirPrototypeV4.synthesize(case.voice, case.notes, case.settings, 20261014, case.rate)
            assertEquals(old.size, current.size, "$case changed the score duration")
            var energy = 0.0
            var error = 0.0
            var maximum = 0.0
            var allFinite = true
            for (i in current.indices) {
                if (!current[i].isFinite()) allFinite = false
                val difference = current[i].toDouble() - old[i]
                energy += old[i].toDouble() * old[i]
                error += difference * difference
                maximum = maxOf(maximum, abs(difference))
            }
            assertTrue(allFinite, "$case emitted a nonfinite sample")
            val relative = sqrt(error / energy.coerceAtLeast(1e-20))
            worstRelativeRms = maxOf(worstRelativeRms, relative)
            worstAbsoluteError = maxOf(worstAbsoluteError, maximum)
            assertTrue(relative < 1e-6 && maximum < 2e-6,
                "$case exceeded the arithmetic budget: relativeRms=$relative max=$maximum")
            val root = 440 * 2.0.pow((case.notes.first().midi - 69) / 12.0)
            var spectralError = 0.0
            var spectralEnergy = 0.0
            for (harmonic in listOf(1, 2, 3, 5)) {
                val a = power(old, root * harmonic, case.rate)
                val b = power(current, root * harmonic, case.rate)
                spectralError += abs(a - b)
                spectralEnergy += a
            }
            val spectralDifference = spectralError / spectralEnergy.coerceAtLeast(1e-20)
            worstSpectralDifference = maxOf(worstSpectralDifference, spectralDifference)
            assertTrue(spectralDifference < 1e-5, "$case changed the emitted harmonic balance: $spectralDifference")
        }
        println("Air model4 compatibility: worstRelativeRms=$worstRelativeRms " +
            "worstAbsoluteSampleError=$worstAbsoluteError worstSpectralDifference=$worstSpectralDifference")
    }

    @Test
    fun `plain pipe and reed roots remain tuned after the optimization`() {
        for (case in compatibilityCases().filter { it.settings == plain }) {
            val source = OtisAirPrototypeV4.synthesize(case.voice, case.notes, case.settings, 20261014, case.rate)
            // These plain sources have no content above 6kHz; sampling every fourth point is
            // only a cheap measurement input for the detector, not an output render shortcut.
            val stride = case.rate / Dsp.RATE
            val measured = FloatArray(source.size / stride) { source[it * stride] }
            val pitch = assertNotNull(Pitch.detect(Snip(measured, 1, Dsp.RATE), .08f, .25f), "$case lost its root")
            val root = 440 * 2.0.pow((case.notes.first().midi - 69) / 12.0)
            val cents = 1200 * ln(pitch.hz / root) / ln(2.0)
            assertTrue(abs(cents) < 5 && pitch.confidence > .8f,
                "$case root moved: $cents cents, confidence=${pitch.confidence}")
        }
    }

    @Test
    fun `optimized buffers stay local and zero work stays silent`() {
        val notes = listOf(OtisPrototypeNote(60, .04, .18, .8f), OtisPrototypeNote(67, .12, .14, .6f))
        for (voice in listOf(OtisPrototypeVoice.STEAM, OtisPrototypeVoice.HARP, OtisPrototypeVoice.SQUEEZE)) {
            val a = OtisAirPrototypeV4.synthesize(voice, notes, edge, 219, 176_400)
            OtisAirPrototypeV4.synthesize(voice, note(72, .9f), plain, 18, 44_100)
            val b = OtisAirPrototypeV4.synthesize(voice, notes, edge, 219, 176_400)
            assertContentEquals(a, b, "$voice leaked a harmonic buffer or source state across renders")
            assertTrue(a.take((.04 * 176_400).toInt()).all { it == 0f }, "$voice sounds before its first note")
            val silent = OtisAirPrototypeV4.synthesize(voice, notes.map { it.copy(velocity = 0f) }, edge, 219, 176_400)
            assertTrue(silent.all { it == 0f }, "$voice generates work at zero velocity")
        }
    }
}
