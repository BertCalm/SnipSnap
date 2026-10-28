package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Focused prototype gates. Orbital reference fixtures live in ApsisOrbitTest. */
class ApsisTest {
    private val options = Apsis.ProbeOptions(normalize = false, seconds = 1.2f,
        recordStems = true, recordTraces = false)

    @Test
    fun `the same saved model and seed produce sample identical source and bodies`() {
        val macros = Apsis.defaults(ApsisVoice.PASSAGE)
        val configuration = ApsisConfig(seedContext = 31)
        val a = Apsis.probe(ApsisVoice.PASSAGE, macros, 60, .7f, configuration, options)
        val b = Apsis.probe(ApsisVoice.PASSAGE, macros, 60, .7f, configuration, options)
        assertContentEquals(a.samples, b.samples)
        assertContentEquals(a.center, b.center)
        assertContentEquals(a.source, b.source)
        for (i in a.bodies.indices) assertContentEquals(a.bodies[i], b.bodies[i])
    }

    @Test
    fun `zero playing energy cannot awaken the unforced network`() {
        val p = Apsis.probe(ApsisVoice.PASSAGE, Apsis.defaults(ApsisVoice.PASSAGE) + ("COUPLE" to 1f),
            60, 0f, options = options)
        assertTrue(p.samples.all { it == 0f })
        assertEquals(0.0, p.diagnostics.maxStateNorm)
    }

    @Test
    fun `extreme populations stay finite and the unforced network cannot gain energy`() {
        for (voice in ApsisVoice.entries) for (midi in listOf(36, 84)) {
            val macros = listOf("STRETCH", "DISTANCE", "BODIES", "ALIGN", "COUPLE").associateWith { 1f } + ("HOLD" to 0f)
            val p = Apsis.probe(voice, macros, midi, 1f,
                options = options.copy(seconds = 8f, recordStems = false))
            val d = p.diagnostics
            assertTrue(p.samples.all { it.isFinite() }, "$voice produced non-finite audio")
            assertTrue(d.maxStateNorm.isFinite() && d.maxStateNorm >= 0, "$voice invalid state norm")
            val numericalTolerance = 1e-10 * max(1.0, d.maxStateNorm * d.maxStateNorm)
            assertTrue(d.maxUnforcedEnergyRise <= numericalTolerance,
                "$voice unforced energy rose by ${d.maxUnforcedEnergyRise}")
            assertTrue(d.sourceEndSeconds < 8.0, "$voice drive never ended inside finite render")
            assertTrue(d.finalEnergy.isFinite() && d.finalEnergy >= 0.0)
            assertTrue(d.finalEnergy < d.maxStateNorm * d.maxStateNorm * .1 + numericalTolerance,
                "$voice MIDI $midi did not decay after finite power ended")
            println("APSIS $voice MIDI $midi extreme: norm=${d.maxStateNorm} passiveRise=${d.maxUnforcedEnergyRise} final=${d.finalEnergy}")
        }
    }

    @Test
    fun `an unexcited recipient is reached only through the center network`() {
        val macros = Apsis.defaults(ApsisVoice.PASSAGE) + mapOf("COUPLE" to 1f, "BODIES" to .4f)
        val selected = options.copy(seconds = 3f, directExcitationDisabled = 1, soloBody = 1)
        val connected = Apsis.probe(ApsisVoice.PASSAGE, macros, options = selected)
        val bypassed = Apsis.probe(ApsisVoice.PASSAGE, macros,
            options = selected.copy(ablation = Apsis.Ablation.NO_COUPLING))
        assertTrue(rms(connected.samples) > 1e-9, "the recipient never answered")
        assertTrue(bypassed.samples.all { it == 0f }, "a disconnected recipient still had direct drive")
    }

    @Test
    fun `each movement control changes the low to middle dry render`() {
        // This is an engagement smoke gate, not a claim that a numeric distance proves audibility.
        val base = Apsis.defaults(ApsisVoice.PASSAGE)
        for (control in listOf("STRETCH", "DISTANCE", "BODIES", "ALIGN", "COUPLE")) {
            val low = Apsis.probe(ApsisVoice.PASSAGE, base + (control to 0f), options = options.copy(seconds = 3f)).samples
            val middle = Apsis.probe(ApsisVoice.PASSAGE, base + (control to .5f), options = options.copy(seconds = 3f)).samples
            val distance = relativeDifference(low, middle)
            println("APSIS $control low-middle raw distance=$distance")
            // Round-one minimum was .030; .01 guards the original nearly inaudible
            // .001 COUPLE response without treating waveform distance as a listening verdict.
            assertTrue(distance > .01, "$control failed to engage: $distance")
        }
    }

    @Test
    fun `reference uncoupled tones retain the requested low middle and high roots`() {
        for (midi in listOf(36, 60, 84)) {
            val p = Apsis.probe(ApsisVoice.PASSAGE, Apsis.defaults(ApsisVoice.PASSAGE) + mapOf(
                "COUPLE" to 0f, "STRETCH" to 0f), midi, .8f,
                options = options.copy(seconds = 1f, recordStems = false))
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(Snip(p.samples, channels = 1, sampleRate = Dsp.RATE), wanted, .15f, .35f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            println("APSIS reference MIDI $midi: $cents cents")
            assertTrue(abs(cents) < 20, "MIDI $midi reference root moved $cents cents")
        }
    }

    @Test
    fun `held motion closes two genuine cycles and preserves a six second gesture`() {
        val macros = Apsis.defaults(ApsisVoice.PASSAGE) + mapOf("HOLD" to 1f, "DISTANCE" to .425f)
        val p = Apsis.probe(ApsisVoice.PASSAGE, macros, 60, .8f,
            options = options.copy(seconds = null, recordStems = false))
        assertTrue(p.loopFrames >= 6 * Dsp.RATE, "the intended six-second motion was shortened")
        assertEquals(p.loopFrames, p.samples.size)
        assertEquals(p.samples.size, p.previousCycle.size)
        assertTrue(p.previousCycle.any { it != 0f }, "the previous cycle contains no actual sound")
        assertTrue(p.seamError.isFinite() && p.seamError < Keys.MAX_SEAM_ERROR, "exported seam=${p.seamError}")
        val mismatch = relativeDifference(p.previousCycle, p.rawMix)
        println("APSIS HOLD seam=${p.seamError} state=${p.stateError} fullCycleMismatch=$mismatch cycles=${p.diagnostics.prerollCycles}")
        assertTrue(mismatch < .01, "independent complete cycles disagree: $mismatch")
        assertTrue(p.stateError.isFinite() && p.stateError < 1e-3, "internal state did not recur: ${p.stateError}")
        assertTrue(p.diagnostics.converged, "the bounded preroll did not converge")
        val independentlyMeasured = Keys.seamError(p.previousCycle + p.rawMix, p.previousCycle.size)
        assertTrue(independentlyMeasured < Keys.MAX_SEAM_ERROR,
            "genuine pre-wrap audio did not close: $independentlyMeasured")
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size.coerceAtLeast(1))
    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var difference = 0.0
        var energy = 0.0
        for (i in a.indices) {
            val d = a[i].toDouble() - b[i]
            difference += d * d
            energy += a[i].toDouble() * a[i] + b[i].toDouble() * b[i]
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }
}
