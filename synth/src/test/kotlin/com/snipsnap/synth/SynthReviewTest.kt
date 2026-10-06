package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `flotilla public hold render ships a doubled closed period`() {
        for (voice in FlotillaVoice.entries) {
            val snip = Flotilla.render(voice, mapOf("HOLD" to 1f), midi = 60)
            assertTrue(snip.samples.all { it.isFinite() }, "$voice hold produced a non-finite sample")
            assertEquals(0, snip.samples.size % 2, "$voice hold is not a doubled period")
            val period = snip.samples.size / 2
            assertTrue(
                snip.samples.copyOfRange(0, period).contentEquals(snip.samples.copyOfRange(period, snip.samples.size)),
                "$voice hold halves are not the same period",
            )
        }
    }

    @Test
    fun `flotilla wood and cavity decays are the same law on both paths`() {
        for (vessel in listOf(0f, 0.35f, 1f)) for (detune in listOf(0.78f, 1f, 1.43f)) {
            val wood = Flotilla.woodDecaySeconds(vessel, detune)
            val cav = Flotilla.cavityDecaySeconds(vessel, detune)
            assertEquals(((16f - vessel * 7f - 2f) / detune).coerceIn(3.5f, 28f), wood)
            assertEquals(((8f - vessel * 3.5f) / detune).coerceIn(2.8f, 18f), cav)
            assertTrue(wood in 3.5f..28f, "wood $wood at vessel $vessel detune $detune")
            assertTrue(cav in 2.8f..18f, "cavity $cav at vessel $vessel detune $detune")
        }
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

    @Test
    fun `silk velocity moves the voices that read it and leaves the others to PICK`() {
        val shamisenHard = Silk.render(SilkVoice.SHAMISEN)
        val shamisenSoft = Silk.render(SilkVoice.SHAMISEN, emptyMap(), velocity = 0.2f)
        assertTrue(shamisenHard.samples.contentEquals(Silk.render(SilkVoice.SHAMISEN, emptyMap(), velocity = 1f).samples))
        assertFalse(shamisenSoft.samples.contentEquals(shamisenHard.samples), "SHAMISEN ignored the velocity number")

        val oudHard = Silk.render(SilkVoice.OUD)
        val oudSoft = Silk.render(SilkVoice.OUD, emptyMap(), velocity = 0.2f)
        assertFalse(oudSoft.samples.contentEquals(oudHard.samples), "OUD ignored the velocity number")

        val guzhengHard = Silk.render(SilkVoice.GUZHENG)
        val guzhengSoft = Silk.render(SilkVoice.GUZHENG, emptyMap(), velocity = 0.2f)
        assertTrue(guzhengSoft.samples.contentEquals(guzhengHard.samples), "GUZHENG velocity is PICK, not the render number")

        val santurHard = Silk.render(SilkVoice.SANTUR)
        val santurSoft = Silk.render(SilkVoice.SANTUR, emptyMap(), velocity = 0.2f)
        assertTrue(santurSoft.samples.contentEquals(santurHard.samples), "SANTUR velocity is PICK, not the render number")
    }

    @Test
    fun `silk velocity layers pass the number not only the PICK macro`() {
        val patch = SilkPatch("Audit", SilkVoice.SHAMISEN, Silk.defaults(SilkVoice.SHAMISEN))
        val v = 0.25f
        val asked = patch.macros.getValue("PICK")
        val pickOnly = asked * Dsp.lin(v, 0.9f / 3.2f, 1f)
        val viaPick = Silk.render(SilkVoice.SHAMISEN, patch.macros + ("PICK" to pickOnly), velocity = 1f)
        val viaVelocity = Velocity.atVelocity(patch, v)
        assertFalse(
            viaVelocity.samples.contentEquals(viaPick.samples),
            "atVelocity scaled PICK but never handed velocity to Silk.render",
        )
    }
}
