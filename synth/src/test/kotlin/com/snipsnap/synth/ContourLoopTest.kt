package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A held wavetable note closes with its scan, oscillator pair and nonlinear filter all running. */
class ContourLoopTest {
    @Test
    fun `all six held voices close across register including fast separated driven spectra`() {
        val corners = listOf(
            "static" to mapOf("MOTION" to 0f),
            "ordinary" to emptyMap(),
            "fast separated driven open" to mapOf("MOTION" to 1f, "OFFSET" to 1f, "DRIVE" to 1f, "FILTER" to 1f),
            "fast separated driven closed" to mapOf("SCAN" to 1f, "MOTION" to 1f, "OFFSET" to 1f, "DRIVE" to 1f, "FILTER" to .1f),
        )
        for (voice in ContourVoice.entries) for (tune in listOf(.25f, .5f, .75f)) for ((label, corner) in corners) {
            val macros = ContourEngine.defaults(voice) + corner + mapOf("TUNE" to tune, "HOLD" to 1f)
            val result = ContourEngine.renderLoopMeasured(voice, macros)
            val seam = Keys.seamError(result.samples, result.loopStart)
            val loop = result.samples.copyOfRange(result.loopStart, result.samples.size)
            val hz = ContourEngine.frequencyFor(voice, tune)
            val cents = ContourTestAudio.pitch(Snip(result.samples, 1, Dsp.RATE), hz, .1f, .9f)
            val pitchFit = FineTuning.cents(result.actualHz, result.noteHz)
            println("CONTOUR HOLD $voice TUNE $tune $label: seam $seam before blend ${result.seamBeforeBlend} periodic ${result.periodicError} state ${result.stateError} root $cents cents fit $pitchFit cents RMS ${ContourTestAudio.rms(loop)}")
            assertTrue(result.seamBeforeBlend < 1e-3, "$voice $tune $label: filter/source did not settle before seam blending (${result.seamBeforeBlend})")
            assertTrue(result.periodicError < 1e-3, "$voice $tune $label: full periods differ by ${result.periodicError}")
            assertTrue(result.stateError < 1e-3, "$voice $tune $label: filter/oscillator state differs by ${result.stateError}")
            assertTrue(seam < 1e-3, "$voice $tune $label: seam $seam")
            assertTrue(abs(cents) < 5, "$voice $tune $label: held root $cents cents")
            assertTrue(abs(pitchFit) < .3, "$voice $tune $label: loop frame fit retuned the root by $pitchFit cents")
            assertTrue(loop.all { it.isFinite() && abs(it) <= 1f }, "$voice $tune $label: unsafe loop output")
            assertTrue(ContourTestAudio.rms(loop) > .015, "$voice $tune $label: a silent loop cannot establish closure")
            assertEquals(result.samples.size / 2, result.loopStart)
            assertEquals(DrumClass.LOOP, ContourEngine.drumClassFor(voice, macros))
        }
    }

    @Test
    fun `held rendering reproduces the settled loop alone and leaves finite attacks outside it`() {
        for (voice in ContourVoice.entries) {
            val macros = ContourEngine.defaults(voice) + ("HOLD" to 1f)
            val measured = ContourEngine.renderLoopMeasured(voice, macros)
            val snip = ContourEngine.render(voice, macros)
            assertContentEquals(measured.samples.copyOfRange(measured.loopStart, measured.samples.size), snip.samples, voice.name)
            assertEquals(1, snip.channels)
            assertEquals(Dsp.RATE, snip.sampleRate)
            val second = ContourEngine.render(voice, macros)
            assertContentEquals(snip.samples, second.samples, "$voice: fresh held renders")
            val finite = ContourEngine.render(voice, ContourEngine.defaults(voice) + ("HOLD" to 0f))
            assertTrue(!finite.samples.contentEquals(snip.samples), "$voice: finite articulation became loop-only sustain")
        }
    }

    @Test
    fun `static held spectra stay fixed while moderate motion changes harmonics within the loop`() {
        for (voice in ContourVoice.entries) {
            val base = ContourEngine.defaults(voice) + mapOf("HOLD" to 1f, "FILTER" to 1f, "DRIVE" to 0f)
            val frozen = ContourEngine.render(voice, base + ("MOTION" to 0f)).samples
            val moving = ContourEngine.render(voice, base + ("MOTION" to .35f)).samples
            val hz = ContourEngine.frequencyFor(voice, base.getValue("TUNE")).toDouble()
            fun travel(x: FloatArray): Double {
                val a = ContourTestAudio.harmonics(x, x.size / 8, hz)
                val b = ContourTestAudio.harmonics(x, x.size / 2, hz)
                return ContourTestAudio.distance(a, b)
            }
            val still = travel(frozen)
            val scan = travel(moving)
            println("CONTOUR HOLD $voice: harmonic travel $still dB still, $scan dB at MOTION .35")
            assertTrue(still < .15, "$voice: MOTION 0 spectrum moves $still dB")
            assertTrue(scan > still + .3, "$voice: MOTION .35 changes harmonics only $scan dB ($still still)")
        }
    }
}
