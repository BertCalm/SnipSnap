package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SplayPatchTest {
    private fun patch(seed: Int = 41001, model: Int = 1) = SplayPatch(
        "Saved seams", SplayVoice.TONGUE,
        Splay.defaults(SplayVoice.TONGUE, model = model) + ("POPULATION" to 0f),
        midi = 57, velocity = .68f, seed = seed, model = model,
    )

    @Test
    fun `patch and pad recipe round trips retain the exact performance and edits`() {
        for (model in listOf(1, 2)) for (seed in listOf(Int.MIN_VALUE, -1, 0, 41001, Int.MAX_VALUE)) {
            val p = patch(seed, model)
            assertEquals(p, SplayPatch.fromJsonText(p.toJsonText()))
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
            assertEquals(p, PadRecipe.fromJsonText(PadRecipe(p).toJsonText()).patch)
        }
        val p = patch()
        val macros = p.macros + ("CONTACT" to .81f)
        assertEquals(p.copy(macros = macros), p.withMacros(macros))
        assertEquals(p.copy(name = "Edited", macros = macros), Patches.edited(p, "Edited", macros))
        assertContentEquals(p.render().samples, SplayPatch.fromJsonText(p.toJsonText()).render().samples)
    }

    @Test
    fun `invalid saved roots velocity seeds model and positive hold refuse without rounding`() {
        val p = patch()
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(p.toJsonValue().entries + (key to value))
        for (key in listOf("midi", "velocity", "seed", "model")) {
            assertFails("missing $key") { SplayPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries - key)) }
            assertFails("null $key") { SplayPatch.fromJsonValue(changed(key, JsonValue.Null)) }
        }
        for (bad in listOf(35.0, 85.0, 60.25, Double.NaN)) assertFails {
            SplayPatch.fromJsonValue(changed("midi", JsonValue.Num(bad)))
        }
        for (bad in listOf(-.000000001, 1.000000001, Double.NaN)) assertFails {
            SplayPatch.fromJsonValue(changed("velocity", JsonValue.Num(bad)))
        }
        for (bad in listOf(Int.MIN_VALUE.toDouble() - 1, Int.MAX_VALUE.toDouble() + 1, 1.5, Double.NaN)) assertFails {
            SplayPatch.fromJsonValue(changed("seed", JsonValue.Num(bad)))
        }
        assertFails { SplayPatch.fromJsonValue(changed("seed", JsonValue.Str("41001"))) }
        for (bad in listOf(0.0, 1.5, 3.0)) assertFails {
            SplayPatch.fromJsonValue(changed("model", JsonValue.Num(bad)))
        }
        for (bad in listOf(-.000000001, 1.000000001, Double.NaN)) assertFails {
            SplayPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("TRACE" to JsonValue.Num(bad)))))
        }
        // The tiny value would round to Float zero, but still requests unsupported sustain.
        for (bad in listOf(1e-100, .01, 1.0)) assertFails {
            SplayPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(bad)))))
        }
        assertFailsWith<IllegalArgumentException> { p.copy(macros = p.macros + ("HOLD" to .01f)) }
        assertFailsWith<IllegalArgumentException> { p.copy(macros = mapOf("NOISE" to .5f)) }
        assertFailsWith<IllegalArgumentException> { p.copy(macros = mapOf("TRACE" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { p.copy(name = " ") }
    }

    @Test
    fun `physical velocity adapter preserves the authored controls note and seed`() {
        val p = patch()
        assertContentEquals(p.copy(velocity = .37f).render().samples, Velocity.atVelocity(p, .37f).samples)
        assertTrue(Velocity.atVelocity(p, 0f).samples.all { it == 0f })
    }

    @Test
    fun `five provisional recipes stay finite complete and outside the factory roster`() {
        val presets = SplayPresets.all()
        assertEquals(5, presets.size)
        assertEquals(5, presets.map { it.name }.toSet().size)
        assertEquals(presets, SplayPresets.forVoice(SplayVoice.TONGUE))
        val factory = Presets.forVoice(SplayPatch.ENGINE, SplayVoice.TONGUE.name)
        assertEquals(SplayAcceptedBank.entries.map { it.patch }, factory)
        assertTrue(factory.none { it.name in presets.map { p -> p.name } }, "historical provisional names stay outside factory")
        for (p in presets) {
            assertEquals(Splay.defaults(p.voice).keys, p.macros.keys)
            assertEquals(0f, p.macros["HOLD"])
            assertEquals(41001, p.seed)
            assertEquals(Splay.MODEL_VERSION, p.model)
        }
        assertEquals(15f / 99f, presets.first { it.name == "First Seams" }.macros["POPULATION"])
        assertEquals(3f / 99f, presets.first { it.name == "Small Mechanism" }.macros["POPULATION"])
    }
}
