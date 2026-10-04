package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class AerostatReviewTest {
    @Test
    fun `soft velocity reaches the engine through the patch dispatcher`() {
        val macros = Aerostat.defaults(AerostatVoice.FLOAT)
        val patch = AerostatPatch("Soft", AerostatVoice.FLOAT, macros)
        assertContentEquals(
            Aerostat.render(AerostatVoice.FLOAT, macros, velocity = 0.3f).samples,
            Velocity.atVelocity(patch, 0.3f).samples,
        )
    }

    @Test
    fun `held notes retain the tuning bar across every semitone`() {
        var worst = 0.0
        var worstMidi = 0
        for (semi in 0..Aerostat.TUNE_SEMITONES) {
            val m = Aerostat.defaults(AerostatVoice.FLOAT) + mapOf("TUNE" to semi / 24f, "HOLD" to 1f, "LIFT" to 0f)
            val loop = Aerostat.renderLoopMeasured(m, 1f)
            val want = Aerostat.frequencyFor(m.getValue("TUNE")).toDouble()
            // The fundamental of the actual periodic output, via its integer period.
            val period = (Dsp.RATE / want).toInt()
            val lo = (period - 2).coerceAtLeast(1)
            val hi = period + 2
            val lag = (lo..hi).minBy { k ->
                var diff = 0.0
                for (i in k until loop.loop.size) { val d = (loop.loop[i] - loop.loop[i - k]).toDouble(); diff += d * d }
                diff / (loop.loop.size - k)
            }
            val cents = abs(1200.0 * ln(Dsp.RATE.toDouble() / lag / want) / ln(2.0))
            if (cents > worst) { worst = cents; worstMidi = Aerostat.ROOT_MIDI + semi }
            assertTrue(loop.seam < Keys.MAX_SEAM_ERROR)
        }
        println("AEROSTAT worst held pitch $worst cents at MIDI $worstMidi")
        assertTrue(worst < 10.0, "MIDI $worstMidi is $worst cents off")
    }
}
