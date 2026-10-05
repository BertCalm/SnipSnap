package com.snipsnap.synth

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

class AerostatVoicingTest {
    private val voice = AerostatVoice.FLOAT
    private val base = Aerostat.defaults(voice) + mapOf("TUNE" to 0f, "LIFT" to 0f)

    @Test
    fun `the held voice contains air rather than only a repeated tone period`() {
        val samples = Aerostat.render(voice, base + ("HOLD" to 1f), normalize = false).samples
        val period = (Dsp.RATE / Aerostat.frequencyFor(0f)).roundToInt()
        var residual = 0.0
        var energy = 0.0
        for (i in period until samples.size) {
            val d = (samples[i] - samples[i - period]).toDouble()
            residual += d * d
            energy += samples[i].toDouble() * samples[i]
        }
        assertTrue(residual > energy * 0.002, "held air disappeared: ${residual / energy}")
        assertTrue(Aerostat.renderLoopMeasured(base + ("HOLD" to 1f), 1f).seam < Keys.MAX_SEAM_ERROR)
    }

    @Test
    fun `the tube attack remains audible beyond the contact pulse`() {
        val samples = Aerostat.render(voice, base, tap = AerostatTap.TUBE, normalize = false).samples
        fun energy(from: Double, until: Double) =
            ((from * Dsp.RATE).toInt() until (until * Dsp.RATE).toInt()).sumOf {
                samples[it].toDouble() * samples[it]
            }
        assertTrue(energy(0.04, 0.10) > energy(0.0, 0.02) * 0.05, "tube collapsed to a click")
    }

    @Test
    fun `silent event and boundary renders stay finite and bounded`() {
        assertTrue(Aerostat.render(voice, base, velocity = 0f).samples.all { it == 0f })
        for (knob in listOf("STRIKE", "PRESSURE", "INERTIA", "RELEASE", "LIFT", "HOLD")) {
            for (value in listOf(0f, 1f)) {
                val samples = Aerostat.render(voice, base + (knob to value)).samples
                assertTrue(samples.all { it.isFinite() && it in -1f..1f }, "$knob $value")
            }
        }
    }
}
