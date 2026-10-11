package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StoopIntegrationTest {
    private fun custom() = StoopPatch(
        "MY CHORD", StoopVoice.CHORD, Stoop.defaults(StoopVoice.CHORD) + ("SHADOW" to .65f),
        midi = 57, velocity = .8f,
        source = Stoop.defaultSource(StoopVoice.CHORD).copy(shape = .83f, fmIndex = .71f, harmonicTilt = .23f),
        shadowInterval = 7, shadowDelayMs = 175f, shadowArticulation = .7f, shadowReplies = 2,
        chordIntervals = listOf(0, 3, 7),
    )

    @Test
    fun `editing and dispatch preserve the complete Stoop recipe`() {
        val patch = custom()
        assertEquals(patch, assertIs<StoopPatch>(Patches.fromJsonText(patch.toJsonText())))
        val moved = patch.macros + ("COLOR" to .2f)
        val edited = assertIs<StoopPatch>(Patches.edited(patch, "NEW CHORD", moved))
        assertEquals(patch.copy(name = "NEW CHORD", macros = moved), edited)
        assertTrue(edited.render().samples.contentEquals(patch.copy(name = "NEW CHORD", macros = moved).render().samples))
    }

    @Test
    fun `velocity regeneration retains authored source shadow and chord state`() {
        val patch = custom()
        val expected = patch.copy(velocity = .35f).render()
        val actual = Velocity.atVelocity(patch, .35f)
        assertTrue(actual.samples.contentEquals(expected.samples), "velocity must render the saved recipe")
        val familyDefault = Stoop.render(patch.voice, patch.macros, patch.midi, velocity = .35f)
        assertFalse(actual.samples.contentEquals(familyDefault.samples), "the custom source and answer must not reset to family defaults")
    }
}
