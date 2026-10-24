package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TidalPatchTest {
    private fun authored(seed: Long = Long.MAX_VALUE) = TidalPatch(
        "Saved encounter", TidalVoice.STREAM,
        Tidal.defaults(TidalVoice.STREAM) + mapOf("APPROACH" to .62f, "TILT" to .78f, "TAIL" to .68f),
        midi = 57, velocity = .42f, seed = seed,
    )

    @Test
    fun `encounter recipes preserve exact seeds performance and controls through edits`() {
        for (seed in listOf(Long.MIN_VALUE, -1L, 0L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val patch = authored(seed)
            assertEquals(JsonValue.Str(seed.toString()), patch.toJsonValue().entries["seed"])
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            val macros = patch.macros + ("BIND" to .82f)
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
            assertEquals(patch.copy(name = "Edited", macros = macros), Patches.edited(patch, "Edited", macros))
        }
        val patch = authored()
        assertContentEquals(patch.render().samples, TidalPatch.fromJsonText(patch.toJsonText()).render().samples,
            "the complete saved encounter must regenerate its audio")
    }

    @Test
    fun `velocity rerenders excitation while retaining authored encounter`() {
        val patch = authored(Long.MIN_VALUE)
        assertContentEquals(patch.copy(velocity = .25f).render().samples, Velocity.atVelocity(patch, .25f).samples)
        assertEquals(.62f, patch.macros["APPROACH"])
        assertEquals(.68f, patch.macros["TAIL"])
    }

    @Test
    fun `unsupported models held reconstruction and malformed recipes fail explicitly`() {
        val patch = authored()
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(patch.toJsonValue().entries + (key to value))
        for (key in listOf("model", "midi", "velocity", "seed")) {
            assertFailsWith<JsonException>("missing $key") {
                TidalPatch.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries - key))
            }
            assertFailsWith<JsonException>("null $key") { TidalPatch.fromJsonValue(changed(key, JsonValue.Null)) }
        }
        for (bad in listOf(35.0, 85.0, 60.25, Double.NaN)) {
            assertFailsWith<JsonException> { TidalPatch.fromJsonValue(changed("midi", JsonValue.Num(bad))) }
        }
        for (bad in listOf(-.000000001, 1.000000001, Double.NaN)) {
            assertFailsWith<JsonException> { TidalPatch.fromJsonValue(changed("velocity", JsonValue.Num(bad))) }
            assertFailsWith<JsonException> {
                TidalPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("TAIL" to JsonValue.Num(bad)))))
            }
        }
        for (bad in listOf("9223372036854775808", "1.5", "-01", "+1", " 1")) {
            assertFailsWith<JsonException> { TidalPatch.fromJsonValue(changed("seed", JsonValue.Str(bad))) }
        }
        for (bad in listOf(0.0, 1.5, 3.0)) {
            assertFailsWith<JsonException> { TidalPatch.fromJsonValue(changed("model", JsonValue.Num(bad))) }
        }
        assertFailsWith<JsonException> { TidalPatch.fromJsonValue(changed("seed", JsonValue.Num(1.0))) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("HOLD" to .01f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<JsonException> {
            TidalPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(1e-100)))))
        }
    }

    @Test
    fun `saved source models remain explicit and partial legacy macros keep legacy defaults`() {
        val legacy = TidalPatch("Original pair", TidalVoice.PAIR, emptyMap(), model = 1)
        val current = legacy.copy(name = "Contact pair", model = 2)
        assertEquals(2, Tidal.MODEL_VERSION)
        assertTrue(Tidal.supportsModel(1) && Tidal.supportsModel(2))
        assertEquals(legacy, TidalPatch.fromJsonText(legacy.toJsonText()))
        assertEquals(current, TidalPatch.fromJsonText(current.toJsonText()))
        assertEquals(1, (Patches.edited(legacy, "Edited", legacy.macros) as TidalPatch).model)
        assertContentEquals(Tidal.render(TidalVoice.PAIR, model = 1).samples, legacy.render().samples)
    }

    @Test
    fun `first listen recipes remain finite and outside the shipped roster`() {
        val recipes = TidalPresets.all()
        assertEquals(6, recipes.size)
        assertEquals(recipes.size, recipes.map { it.name }.distinct().size)
        assertTrue(Presets.all().none { it.engine == TidalPatch.ENGINE })
        for (voice in TidalVoice.entries) {
            assertEquals(3, TidalPresets.forVoice(voice).size)
            assertTrue(Presets.forVoice(TidalPatch.ENGINE, voice.name).isEmpty())
        }
        for (recipe in recipes) {
            assertEquals(setOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL", "HOLD"), recipe.macros.keys)
            assertEquals(0f, recipe.macros.getValue("HOLD"))
            assertEquals(recipe, Patches.fromJsonText(recipe.toJsonText()))
        }
    }
}
