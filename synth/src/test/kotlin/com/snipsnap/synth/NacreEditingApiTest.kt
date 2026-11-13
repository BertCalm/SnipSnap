package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Public editing APIs select a saved renderer without changing the default model-3 call sites. */
class NacreEditingApiTest {
    @Test
    fun savedModelControlsExposeContactOnlyForModelFour() {
        val voice = NacreVoice.PEARL
        val retained = listOf("TOUCH", "DUET", "CURRENT", "VENUE", "BLOOM", "HOLD")
        for (model in 1..3) {
            assertEquals(retained, Nacre.macrosFor(voice, model).map { it.name })
            assertEquals(NacreModels.macrosFor(voice, model), Nacre.macrosFor(voice, model))
            assertEquals(NacreModels.defaults(voice, model), Nacre.defaults(voice, model))
            assertFalse("CONTACT" in Nacre.defaults(voice, model))
        }
        assertEquals(3, Nacre.MODEL_VERSION)
        assertEquals(Nacre.macrosFor(voice), Nacre.macrosFor(voice, 3))
        assertEquals(Nacre.defaults(voice), Nacre.defaults(voice, 3))
        assertEquals(retained.dropLast(1) + listOf("CONTACT", "HOLD"),
            Nacre.macrosFor(voice, 4).map { it.name })
        val contact = Nacre.macrosFor(voice, 4).single { it.name == "CONTACT" }
        assertEquals(0f, contact.default)
        assertEquals(Nacre.defaults(voice) + ("CONTACT" to 0f), Nacre.defaults(voice, 4))
        assertTrue(Nacre.defaults(voice, 4).getValue("HOLD") == 0f)
    }

    @Test
    fun publicModelControlApiRefusesUnsupportedSavedVersions() {
        for (model in listOf(Int.MIN_VALUE, -1, 0, 5, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { Nacre.macrosFor(NacreVoice.PEARL, model) }
            assertFailsWith<IllegalArgumentException> { Nacre.defaults(NacreVoice.PEARL, model) }
        }
    }
}
