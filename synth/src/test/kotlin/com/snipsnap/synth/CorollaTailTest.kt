package com.snipsnap.synth

import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A zero endpoint supplied by fadeTail must not hide a still-ringing finite object. */
class CorollaTailTest {
    private val internalRate = Dsp.RATE * Dsp.OVERSAMPLE
    private val window = Dsp.RATE / 5

    private fun rms(samples: FloatArray, from: Int, until: Int): Double {
        var power = 0.0
        for (i in from until until) power += samples[i].toDouble() * samples[i]
        return sqrt(power / (until - from))
    }

    private fun loudestWindowRms(samples: FloatArray, count: Int): Double {
        require(samples.size >= count)
        var power = 0.0
        for (i in 0 until count) power += samples[i].toDouble() * samples[i]
        var highest = power
        for (i in count until samples.size) {
            power += samples[i].toDouble() * samples[i] - samples[i - count].toDouble() * samples[i - count]
            highest = maxOf(highest, power)
        }
        return sqrt(highest / count)
    }

    @Test
    fun `finite voices settle before their ending and stay quiet if simulation continues`() {
        val cases = CorollaVoice.entries.map { it to emptyMap<String, Float>() } +
            (CorollaVoice.CHOIR to mapOf("FIELD" to 1f, "HOLD" to .98f, "CHAMBER" to 1f, "BLOOM" to 0f))
        // Render and release each recorded object in turn: keeping all diagnostics
        // together would retain hundreds of megabytes of internal-rate tap arrays.
        for ((voice, macros) in cases) checkTail(voice, macros)
    }

    private fun checkTail(voice: CorollaVoice, macros: Map<String, Float>) {
        val label = "$voice $macros"
        val natural = Corolla.play(voice, macros, probe = Corolla.Probe(record = true))
        val taps = requireNotNull(natural.taps)
        assertEquals(0, natural.raw.size % Dsp.OVERSAMPLE, "$label ends between output frames")
        for ((name, tap) in listOf("direct" to taps.direct, "neighbors" to taps.neighbors,
            "chamber" to taps.chamber, "contact" to taps.contact, "opening" to taps.opening,
            "energy" to taps.energy, "poweredInput" to taps.poweredInput,
            "contactEnergy" to taps.contactEnergy, "passiveCorrection" to taps.passiveCorrection)) {
            assertEquals(natural.raw.size, tap.size, "$label leaves an untrimmed $name diagnostic")
        }

        val peakEnergy = taps.energy.max().toDouble()
        assertTrue(peakEnergy > 0.0, "$label does not excite the object")
        val quietSamples = (internalRate * .15).toInt()
        val finalEnergy = taps.energy.takeLast(quietSamples).max().toDouble()
        // Stored energy catches an apparent quiet trough caused by pickup cancellation.
        assertTrue(finalEnergy <= peakEnergy * 1.01e-8,
            "$label ends with stored resonance: final/peak ${finalEnergy / peakEnergy}")

        val samples = Corolla.finish(natural.raw, normalize = false, fade = false)
        val loudest = loudestWindowRms(samples, window)
        val ending = rms(samples, samples.size - window, samples.size)
        assertTrue(loudest > 1e-5, "$label has no audible reference body")
        assertTrue(ending <= loudest * 3.2e-4,
            "$label still rings before its final fade: tail/body ${ending / loudest}")

        val duration = natural.raw.size.toDouble() / internalRate
        val continued = Corolla.play(voice, macros, seconds = (duration + 1.0).toFloat())
        val firstMismatch = natural.raw.indices.firstOrNull { natural.raw[it] != continued.raw[it] }
        assertEquals(null, firstMismatch, "$label changes its physical trajectory when capture length is explicit")
        val extended = Corolla.finish(continued.raw, normalize = false, fade = false)
        val after = extended.copyOfRange(samples.size, extended.size)
        val returning = loudestWindowRms(after, window)
        assertTrue(returning <= loudest * 3.2e-4,
            "$label returns after the supposed natural ending: continued/body ${returning / loudest}")
        println("COROLLA TAIL $label: $duration s, unfaded tail ${20 * log10(ending / loudest)} dB, " +
            "continuation ${20 * log10(returning / loudest)} dB, stored energy ${finalEnergy / peakEnergy}")
    }

    @Test
    fun `explicit diagnostic captures retain requested length before and after natural settling`() {
        for (seconds in listOf(.35f, 2.5f, 12f)) {
            val played = Corolla.play(CorollaVoice.TONGUE, mapOf("FIELD" to 0f),
                probe = Corolla.Probe(record = true), seconds = seconds)
            val expected = (seconds * internalRate).toInt()
            assertEquals(expected, played.raw.size, "$seconds s diagnostic was automatically cut or extended")
            assertEquals(expected, requireNotNull(played.taps).energy.size)
            assertEquals(-1, played.loopStart)
        }
    }
}
