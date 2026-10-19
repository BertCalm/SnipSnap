package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OhmPatchTest {
    @Test
    fun `recipe replay and edits retain the entire encounter`() {
        val patch = OhmPatch("Saved gathering", Ohm.defaults() + ("LENGTH" to .1f),
            midi = 57, velocity = .65f, seed = -905, gesture = OhmGesture.GATHER)
        val restored = Patches.fromJsonText(patch.toJsonText()) as OhmPatch
        assertEquals(patch, restored)
        val edited = patch.macros + ("COMMUNE" to .73f)
        assertEquals(patch.copy(name = "Edited", macros = edited), Patches.edited(patch, "Edited", edited))
        assertContentEquals(patch.render().samples, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).render().samples)
    }

    @Test
    fun `saved gestures models and invalid controls are refused precisely`() {
        val patch = OhmPatch("Valid", emptyMap())
        for ((field, value) in listOf(
            "voice" to JsonValue.Str("BOW"), "version" to JsonValue.Num(2.0),
            "model" to JsonValue.Num(9.0), "midi" to JsonValue.Num(47.0),
            "velocity" to JsonValue.Num(-.1), "gesture" to JsonValue.Str("UNKNOWN"),
        )) {
            assertFailsWith<JsonException>(field) {
                OhmPatch.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries + (field to value)))
            }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("MEADOW" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("COMMUNE" to Float.NaN)) }
        assertTrue(OhmPresets.all().all { Patches.fromJsonText(it.toJsonText()) == it })
    }

    @Test
    fun `native velocity uses saved source gesture and seed`() {
        val patch = OhmPatch("Velocity", Ohm.defaults() + ("LENGTH" to 0f), midi = 67,
            seed = -37, gesture = OhmGesture.GATHER)
        assertContentEquals(patch.copy(velocity = .35f).render().samples, Velocity.atVelocity(patch, .35f).samples)
    }
}
