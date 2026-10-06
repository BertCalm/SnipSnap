package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SynthReviewTest {
    @Test
    fun `flotilla held notes close across registers and interacting endpoints`() {
        var worst = 0.0
        var worstAt = ""
        for (voice in FlotillaVoice.entries) for (midi in listOf(36, 60, 84)) {
            for (surface in listOf(0f, 1f)) for (skin in listOf(0f, 1f)) {
                val result = Flotilla.renderInternal(voice, mapOf("HOLD" to 1f, "SURFACE" to surface, "SKIN" to skin), midi)
                val loop = requireNotNull(result.loop)
                assertTrue(loop.seam.isFinite(), "$voice MIDI $midi seam ${loop.seam}")
                if (loop.seam > worst) { worst = loop.seam; worstAt = "$voice MIDI $midi SURFACE $surface SKIN $skin" }
                assertTrue(result.snip.samples.all { it.isFinite() })
            }
        }
        println("FLOTILLA worst held seam $worst at $worstAt")
        assertTrue(worst < Keys.MAX_SEAM_ERROR, "$worstAt seam $worst")
    }

    @Test
    fun `tremor one shots end quietly across voices and registers`() {
        var worst = 0f
        var worstAt = ""
        for (voice in TremorVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val result = Tremor.play(voice, mapOf("TUNE" to tune, "CURRENT" to 1f, "CAGE" to 1f, "FAULT" to 1f))
            val peak = result.snip.peak()
            assertTrue(peak > 0f, "$voice TUNE $tune peak $peak")
            val edge = abs(result.snip.samples.last()) / peak
            if (edge > worst) { worst = edge; worstAt = "$voice TUNE $tune" }
        }
        println("TREMOR worst end step $worst of peak at $worstAt")
        assertTrue(worst < 0.001f, "$worstAt end step $worst of peak")
    }
}
