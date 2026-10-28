package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChamberPatchTest {
    private fun authored(seed: Long = Long.MAX_VALUE) = ChamberPatch(
        "Saved chamber", ChamberVoice.AQUARIUM, Chamber.defaults(ChamberVoice.AQUARIUM) + ("STRIKE" to .63f),
        midi = 57, velocity = .42f,
        configuration = ChamberConfig.aquarium().copy(
            dimensions = ChamberDimensions(9f, 8f, 6f), splitHeight = .67f,
            materialMode = ChamberMaterialMode.EXPLICIT_TARGETS,
            placementMode = ChamberPlacementMode.EXPLICIT,
            targets = ChamberSurfaceTargets(.12f, .94f, .20f, .85f, .25f, .90f, .30f, 1f, .28f, .97f),
            tuningRatios = ChamberTuningRatios(1f, 1f, 2.01f, 3.98f),
            instrumentEnabled = ChamberInstrumentEnabled(triangle = false),
        ),
        seed = seed,
    )

    @Test
    fun `complete room recipes preserve exact seeds notes velocity and editable overrides`() {
        for (seed in listOf(Long.MIN_VALUE, -1L, 0L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val patch = authored(seed)
            assertEquals(JsonValue.Str(seed.toString()), patch.toJsonValue().entries["seed"])
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            val macros = patch.macros + ("PLACEMENT" to .14f)
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
            assertEquals(patch.copy(name = "Edited", macros = macros), Patches.edited(patch, "Edited", macros))
        }
        val patch = authored()
        assertContentEquals(patch.render().samples, ChamberPatch.fromJsonText(patch.toJsonText()).render().samples,
            "room recipe must regenerate the actual exported sound")
    }

    @Test
    fun `native event velocity retains authored strike room note and seed`() {
        val patch = authored(Long.MIN_VALUE)
        assertContentEquals(patch.copy(velocity = .25f).render().samples, Velocity.atVelocity(patch, .25f).samples)
        assertEquals(.63f, patch.macros["STRIKE"], "event velocity must not rewrite the STRIKE control")
    }

    @Test
    fun `incomplete or malformed saved performances fail explicitly`() {
        val patch = authored()
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(patch.toJsonValue().entries + (key to value))
        for (key in listOf("model", "midi", "velocity", "seed", "configuration")) {
            assertFailsWith<JsonException>("missing $key") {
                ChamberPatch.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries - key))
            }
            assertFailsWith<JsonException>("null $key") { ChamberPatch.fromJsonValue(changed(key, JsonValue.Null)) }
        }
        for (bad in listOf(35.0, 85.0, 60.25, Double.NaN)) {
            assertFailsWith<JsonException> { ChamberPatch.fromJsonValue(changed("midi", JsonValue.Num(bad))) }
        }
        for (bad in listOf(-.000000001, 1.000000001, Double.NaN)) {
            assertFailsWith<JsonException> { ChamberPatch.fromJsonValue(changed("velocity", JsonValue.Num(bad))) }
            assertFailsWith<JsonException> {
                ChamberPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("STRIKE" to JsonValue.Num(bad)))))
            }
        }
        for (bad in listOf("9223372036854775808", "1.5", "-01", "+1", " 1")) {
            assertFailsWith<JsonException> { ChamberPatch.fromJsonValue(changed("seed", JsonValue.Str(bad))) }
        }
        for (bad in listOf(0.0, 1.5, 4.0)) {
            assertFailsWith<JsonException> { ChamberPatch.fromJsonValue(changed("model", JsonValue.Num(bad))) }
        }
        assertFailsWith<JsonException> { ChamberPatch.fromJsonValue(changed("seed", JsonValue.Num(1.0))) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("HOLD" to .01f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<JsonException> {
            ChamberPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(1e-100)))))
        }
    }

    @Test
    fun `factory recipes remain complete finite and available through the shared roster`() {
        val recipes = ChamberPresets.all()
        assertEquals(6, recipes.size)
        assertEquals(recipes.size, recipes.map { it.name }.distinct().size)
        assertEquals(recipes, Presets.all().filter { it.engine == ChamberPatch.ENGINE })
        for (voice in ChamberVoice.entries) {
            assertEquals(3, ChamberPresets.forVoice(voice).size)
            assertEquals(ChamberPresets.forVoice(voice), Presets.forVoice(ChamberPatch.ENGINE, voice.name))
        }
        for (recipe in recipes) {
            assertEquals(setOf("STRIKE", "BALANCE", "MATERIAL", "DIVIDE", "PLACEMENT", "HOLD"), recipe.macros.keys)
            assertEquals(0f, recipe.macros.getValue("HOLD"))
            assertEquals(recipe, Patches.fromJsonText(recipe.toJsonText()))
        }
    }
}
