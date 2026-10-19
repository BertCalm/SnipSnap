package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NacrePatchTest {
    private fun patch(seed: Long = Nacre.DEFAULT_SEED) = NacrePatch(
        "Saved duet", NacreVoice.PEARL, Nacre.defaults(NacreVoice.PEARL),
        midi = 57, velocity = .65f, seed = seed,
    )

    @Test
    fun `complete recipes preserve the exact seed root energy model and macro edits`() {
        for (seed in listOf(Long.MIN_VALUE, -1L, 0L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val original = patch(seed)
            assertEquals(JsonValue.Str(seed.toString()), original.toJsonValue().entries["seed"])
            assertEquals(original, NacrePatch.fromJsonText(original.toJsonText()))
            assertEquals(original, Patches.fromJsonText(original.toJsonText()))
            val controls = original.macros + ("DUET" to .81f)
            assertEquals(original.copy(macros = controls), original.withMacros(controls))
            assertEquals(original.copy(name = "Edited", macros = controls), Patches.edited(original, "Edited", controls))
        }
        val original = patch(Long.MAX_VALUE)
        val restored = NacrePatch.fromJsonText(original.toJsonText())
        assertContentEquals(original.render().samples, restored.render().samples, "saved seed must recreate the same paired gesture")
    }

    @Test
    fun `pad recipes replay the complete dry duet and partial controls keep engine defaults`() {
        val partial = patch(-7).copy(macros = mapOf("DUET" to .7f, "CURRENT" to 0f))
        val recipe = PadRecipe(partial)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        val expected = Nacre.render(partial.voice, Nacre.defaults(partial.voice) + partial.macros,
            partial.midi, partial.velocity, partial.seed)
        assertContentEquals(expected.samples, restored.render().samples, "recipe lost a control default or explicit render setting")
        val withoutMacros = JsonValue.Obj(partial.toJsonValue().entries - "macros")
        val noControls = NacrePatch.fromJsonValue(withoutMacros) as NacrePatch
        assertEquals(partial.copy(macros = emptyMap()), noControls)
    }

    @Test
    fun `invalid identities missing state and imprecise numeric values refuse before synthesis`() {
        val original = patch()
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(original.toJsonValue().entries + (key to value))
        for ((key, value) in listOf(
            "engine" to JsonValue.Str("GYRE"),
            "voice" to JsonValue.Str("UNKNOWN"),
            "version" to JsonValue.Num(2.0),
        )) assertFailsWith<JsonException>(key) { NacrePatch.fromJsonValue(changed(key, value)) }
        for (key in listOf("midi", "velocity", "seed", "model")) {
            assertFailsWith<JsonException>("missing $key") {
                NacrePatch.fromJsonValue(JsonValue.Obj(original.toJsonValue().entries - key))
            }
            assertFailsWith<JsonException>("null $key") { NacrePatch.fromJsonValue(changed(key, JsonValue.Null)) }
        }
        for (midi in listOf(Nacre.MIN_MIDI - 1.0, Nacre.MAX_MIDI + 1.0, 48.5, Double.NaN)) {
            assertFailsWith<JsonException>("midi $midi") { NacrePatch.fromJsonValue(changed("midi", JsonValue.Num(midi))) }
        }
        for (velocity in listOf(-1e-12, 1.0 + 1e-12, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<JsonException>("velocity $velocity") {
                NacrePatch.fromJsonValue(changed("velocity", JsonValue.Num(velocity)))
            }
        }
        for (model in listOf(0.0, 1.5, Nacre.MODEL_VERSION + 1.0)) {
            assertFailsWith<JsonException>("model $model") { NacrePatch.fromJsonValue(changed("model", JsonValue.Num(model))) }
        }
        for (seed in listOf("9223372036854775808", "1.5", "-01", "+1", " 1", "-0")) {
            assertFailsWith<JsonException>("seed $seed") { NacrePatch.fromJsonValue(changed("seed", JsonValue.Str(seed))) }
        }
        assertFailsWith<JsonException> { NacrePatch.fromJsonValue(changed("seed", JsonValue.Num(7.0))) }
        for (value in listOf(-1e-12, 1.0 + 1e-12, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<JsonException>("macro $value") {
                NacrePatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("TOUCH" to JsonValue.Num(value)))))
            }
        }
        for (value in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { original.copy(velocity = value) }
            assertFailsWith<IllegalArgumentException> { original.copy(macros = mapOf("TOUCH" to value)) }
        }
        assertFailsWith<IllegalArgumentException> { original.copy(midi = Nacre.MIN_MIDI - 1) }
        assertFailsWith<IllegalArgumentException> { original.copy(model = Nacre.MODEL_VERSION + 1) }
        assertFailsWith<IllegalArgumentException> { original.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { original.copy(macros = mapOf("TUNE" to .5f)) }
    }

    @Test
    fun `finite prototype refuses every nonzero HOLD instead of accepting an inaudible loop switch`() {
        val original = patch()
        for (hold in listOf(Float.MIN_VALUE, .1f, 1f)) {
            assertFailsWith<IllegalArgumentException> { original.copy(macros = original.macros + ("HOLD" to hold)) }
        }
        val tinyHold = JsonValue.Obj(original.toJsonValue().entries +
            ("macros" to JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(1e-100)))))
        assertFailsWith<JsonException> { NacrePatch.fromJsonValue(tinyHold) }
    }

    @Test
    fun `native velocity dispatch preserves both the seed and selected articulation`() {
        val original = patch(Long.MAX_VALUE).copy(macros = patch().macros + ("TOUCH" to .8f))
        assertContentEquals(original.copy(velocity = .25f).render().samples, Velocity.atVelocity(original, .25f).samples)
    }

    @Test
    fun `provisional recipes cover close answers crossing still water and both initial venues`() {
        val recipes = NacrePresets.all()
        assertEquals(6, recipes.size)
        assertEquals(6, recipes.map { it.name }.toSet().size)
        assertEquals(recipes, NacrePresets.forVoice(NacreVoice.PEARL))
        assertTrue(Presets.all().none { it.engine == NacrePatch.ENGINE })
        assertTrue(Presets.forVoice(NacrePatch.ENGINE, NacreVoice.PEARL.name).isEmpty())
        for (recipe in recipes) {
            assertEquals(setOf("TOUCH", "DUET", "CURRENT", "VENUE", "BLOOM", "HOLD"), recipe.macros.keys)
            assertEquals(0f, recipe.macros.getValue("HOLD"))
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(recipe.name), recipe.name)
            assertEquals(recipe, Patches.fromJsonText(recipe.toJsonText()))
        }
        for (venue in listOf(0f, 1f)) {
            val location = recipes.filter { it.macros.getValue("VENUE") == venue }
            assertTrue(location.any { it.macros.getValue("CURRENT") == 0f }, "VENUE $venue has no still comparison")
            assertTrue(location.any { it.macros.getValue("CURRENT") >= .5f }, "VENUE $venue has no moving comparison")
            assertTrue(location.any { it.macros.getValue("DUET") <= .2f }, "VENUE $venue has no close duet")
            assertTrue(location.any { it.macros.getValue("DUET") >= .45f }, "VENUE $venue has no answer")
        }
        assertTrue(recipes.any { it.macros.getValue("DUET") >= .85f }, "no crossing gesture")
    }
}
