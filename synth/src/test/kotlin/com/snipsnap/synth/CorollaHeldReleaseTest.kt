package com.snipsnap.synth

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Note-off must remove the motor's work while the same physical flower keeps ringing. */
class CorollaHeldReleaseTest {
    private val internalRate = Dsp.RATE * Dsp.OVERSAMPLE
    private val held = mapOf("HOLD" to 1f)
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
        var peak = power
        for (i in count until samples.size) {
            power += samples[i].toDouble() * samples[i] - samples[i - count].toDouble() * samples[i - count]
            peak = maxOf(peak, power)
        }
        return sqrt(peak / count)
    }

    @Test
    fun `note off leaves an already passive held object completely unchanged`() {
        for (voice in CorollaVoice.entries) {
            val uninterrupted = Corolla.play(voice, held, probe = Corolla.Probe(powered = false), seconds = 1.2f)
            val released = Corolla.play(voice, held, probe = Corolla.Probe(powered = false),
                seconds = 1.2f, releaseAtSeconds = .4f)
            // With the motor already off, changing release time cannot retune,
            // reset, substitute a different recording or fade the raw pickup.
            assertTrue(uninterrupted.raw.contentEquals(released.raw),
                "$voice note-off changes passive motion or applies an output envelope")
        }
    }

    @Test
    fun `note off preserves the sounding prefix then removes powered work`() {
        val voice = CorollaVoice.ORBIT
        val earlyOff = .7f
        val early = Corolla.play(voice, held, probe = Corolla.Probe(record = true),
            seconds = 2.4f, releaseAtSeconds = earlyOff)
        val later = Corolla.play(voice, held, probe = Corolla.Probe(record = true),
            seconds = 2.4f, releaseAtSeconds = 1.6f)
        val prefix = ceil(earlyOff.toDouble() * internalRate).toInt()
        assertTrue((0 until prefix).all { early.raw[it] == later.raw[it] },
            "a future note-off changes the held attack or sustain")
        val afterRelease = ceil((earlyOff + .252) * internalRate).toInt()
        val earlyWork = requireNotNull(early.taps).poweredInput
        val laterWork = requireNotNull(later.taps).poweredInput
        assertTrue((afterRelease until earlyWork.size).all { earlyWork[it] == 0f },
            "the motor keeps adding work after its release transition")
        assertTrue((afterRelease until (1.4 * internalRate).toInt()).any { laterWork[it] > 0f },
            "the later note-off does not maintain the held object")
        assertTrue(rms(early.raw, afterRelease, afterRelease + internalRate / 10) > 1e-5,
            "note-off deletes the resonating state instead of letting it ring")
    }

    @Test
    fun `every released held voice settles quietly and never returns after its ending`() {
        // Keep recorded objects scoped to one voice to bound diagnostic memory.
        for (voice in CorollaVoice.entries) checkReleasedTail(voice)
    }

    private fun checkReleasedTail(voice: CorollaVoice) {
        val noteOff = 1.5f
        val played = Corolla.play(voice, held, probe = Corolla.Probe(record = true), releaseAtSeconds = noteOff)
        val taps = requireNotNull(played.taps)
        val duration = played.raw.size.toDouble() / internalRate
        assertTrue(duration > noteOff + .25 && duration <= noteOff + Corolla.MAX_FINITE_SECONDS + .001,
            "$voice release duration is not bounded passive settling: $duration")
        assertEquals(-1, played.loopStart, "$voice released gesture still declares a sustain loop")
        assertEquals(0, played.raw.size % Dsp.OVERSAMPLE)
        for (tap in listOf(taps.direct, taps.neighbors, taps.chamber, taps.contact, taps.opening,
            taps.energy, taps.poweredInput, taps.contactEnergy, taps.passiveCorrection))
            assertEquals(played.raw.size, tap.size, "$voice released diagnostic is not trimmed")

        val afterRelease = ceil((noteOff + .252) * internalRate).toInt()
        assertTrue((afterRelease until taps.poweredInput.size).all { taps.poweredInput[it] == 0f },
            "$voice still supplies power after note-off")
        val peakEnergy = taps.energy.max().toDouble()
        val quiet = (internalRate * .15).toInt()
        val finalEnergy = (taps.energy.size - quiet until taps.energy.size).maxOf { taps.energy[it].toDouble() }
        assertTrue(peakEnergy > 0 && finalEnergy <= peakEnergy * 1.01e-8,
            "$voice stops with stored energy ${finalEnergy / peakEnergy}")

        val samples = Corolla.finish(played.raw, normalize = false, fade = false)
        val loudest = loudestWindowRms(samples, window)
        val ending = rms(samples, samples.size - window, samples.size)
        assertTrue(loudest > 1e-5 && ending <= loudest * 3.2e-4,
            "$voice held release ends audibly before its fade: tail/body ${ending / loudest}")

        val continued = Corolla.play(voice, held, seconds = (duration + 1.0).toFloat(), releaseAtSeconds = noteOff)
        assertTrue(played.raw.indices.all { played.raw[it] == continued.raw[it] },
            "$voice capture length changes the released trajectory")
        val extended = Corolla.finish(continued.raw, normalize = false, fade = false)
        val returning = loudestWindowRms(extended.copyOfRange(samples.size, extended.size), window)
        assertTrue(returning <= loudest * 3.2e-4,
            "$voice returns after the natural held ending: continuation/body ${returning / loudest}")
        if (voice == CorollaVoice.TONGUE) {
            val expected = Corolla.finish(played.raw, normalize = false)
            val publicNote = Corolla.renderHeldNote(voice, mapOf("HOLD" to 0f),
                holdSeconds = noteOff, normalize = false)
            assertTrue(expected.contentEquals(publicNote), "public held-note playback does not use the released object")
        }
        println("COROLLA HELD RELEASE $voice: $duration s, tail ${20 * log10(ending / loudest)} dB, " +
            "continuation ${20 * log10(returning / loudest)} dB, energy ${finalEnergy / peakEnergy}")
    }

    @Test
    fun `explicit released captures keep their requested length even before note off`() {
        for (seconds in listOf(.35f, 2.5f)) {
            val played = Corolla.play(CorollaVoice.TONGUE, held, probe = Corolla.Probe(record = true),
                seconds = seconds, releaseAtSeconds = .7f)
            val expected = (seconds * internalRate).toInt()
            assertEquals(expected, played.raw.size)
            assertEquals(expected, requireNotNull(played.taps).energy.size)
            assertEquals(-1, played.loopStart)
        }
    }
}
