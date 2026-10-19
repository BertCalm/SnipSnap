package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CausticRecipeTest {
    @Test fun `portable recipes replay every diagnostic variant sample for sample`() {
        for (variant in Caustic.Mode.entries) {
            val recipe = CausticRecipe(Caustic.Config(
                source = .73, focus = .245, midi = 57, velocity = .37,
                seconds = .12, aperture = .065, delayCycles = .41, rayCount = 128,
            ), variant)
            val text = recipe.toJsonText()
            val restored = CausticRecipe.fromJsonText(text)
            assertEquals(recipe, restored)
            assertEquals(text, restored.toJsonText())
            val a = recipe.inspect()
            val b = restored.inspect()
            assertContentEquals(a.raw, b.raw, variant.name)
            assertContentEquals(a.samples, b.samples, variant.name)
            assertContentEquals(a.densities, b.densities, variant.name)
        }
    }

    @Test fun `unknown formats versions variants and lossy integer fields are refused`() {
        val original = CausticRecipe().toJsonValue()
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(original.entries).also { it[key] = value },
        )
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(changed("format", JsonValue.Str("OVEN"))) }
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(changed("version", JsonValue.Num(1.5))) }
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(changed("variant", JsonValue.Str("ORBIT"))) }
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(changed("model", JsonValue.Num(2.0))) }
        val config = original.entries.getValue("configuration").obj()
        for (key in listOf("midi", "rayCount")) {
            val fractional = JsonValue.Obj(LinkedHashMap(config).also { it[key] = JsonValue.Num(60.5) })
            assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(changed("configuration", fractional)) }
        }
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(JsonValue.Obj(original.entries - "variant")) }
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(changed("futureControl", JsonValue.Num(.5))) }
    }

    @Test fun `model two characters are explicit and replay every saved field`() {
        for (character in Caustic.Character.entries) {
            val recipe = CausticRecipe(Caustic.Config(model = 2, character = character,
                source = .5, focus = .19, seconds = .12, midi = 57, velocity = .6))
            val text = recipe.toJsonText()
            val restored = CausticRecipe.fromJsonText(text)
            assertEquals(recipe, restored)
            assertEquals(text, restored.toJsonText())
            assertEquals(character.name, restored.toJsonValue().entries.getValue("configuration").obj().getValue("character").str())
            assertContentEquals(recipe.inspect().raw, restored.inspect().raw)
        }
    }

    @Test fun `model one JSON shape remains backward compatible and version fields stay explicit`() {
        val recipe = CausticRecipe(Caustic.Config(model = 1))
        val value = recipe.toJsonValue()
        assertEquals(false, "character" in value.entries.getValue("configuration").obj())
        assertEquals(recipe, CausticRecipe.fromJsonValue(value))
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(JsonValue.Obj(value.entries - "model")) }
        assertFailsWith<IllegalArgumentException> {
            CausticRecipe(Caustic.Config(model = 1, character = Caustic.Character.REED))
        }
        val rich = CausticRecipe(Caustic.Config(model = 2)).toJsonValue()
        val controls = rich.entries.getValue("configuration").obj()
        val malformed = JsonValue.Obj(LinkedHashMap(rich.entries).also {
            it["configuration"] = JsonValue.Obj(LinkedHashMap(controls).also { c -> c["character"] = JsonValue.Str("UNKNOWN") })
        })
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(malformed) }
    }

    @Test fun `invalid render parameters are refused before a recipe can be saved`() {
        val base = Caustic.Config()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.01, 1.01)) {
            assertFailsWith<IllegalArgumentException> { CausticRecipe(base.copy(source = bad)) }
            assertFailsWith<IllegalArgumentException> { CausticRecipe(base.copy(focus = bad)) }
            assertFailsWith<IllegalArgumentException> { CausticRecipe(base.copy(velocity = bad)) }
        }
        assertFailsWith<IllegalArgumentException> { CausticRecipe(base.copy(midi = 23)) }
        assertFailsWith<IllegalArgumentException> { CausticRecipe(base.copy(midi = 97)) }
        assertFailsWith<IllegalArgumentException> { CausticRecipe(base.copy(seconds = .01)) }
        assertFailsWith<IllegalArgumentException> { CausticRecipe(base.copy(aperture = 0.0)) }
        assertFailsWith<IllegalArgumentException> { CausticRecipe(base.copy(delayCycles = 0.0)) }
        assertFailsWith<IllegalArgumentException> { CausticRecipe(base.copy(rayCount = 0)) }
    }
}
