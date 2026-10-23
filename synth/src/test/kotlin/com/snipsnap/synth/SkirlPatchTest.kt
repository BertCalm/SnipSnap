package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkirlPatchTest {
    private fun authored(voice: SkirlVoice = SkirlVoice.ROLL) = SkirlPatch(
        "Saved rolling rim", voice,
        Skirl.defaults(voice) + mapOf("LEAN" to .62f, "EDGE" to .78f, "PLATE" to .68f),
        midi = 57, velocity = .42f,
    )

    @Test
    fun `saved runouts retain performance model and seed context through recipe edits`() {
        for (voice in SkirlVoice.entries) {
            val patch = authored(voice)
            val restored = assertIs<SkirlPatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, restored)
            assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            val macros = patch.macros + ("BRAKE" to .82f)
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
            assertEquals(patch.copy(name = "Edited", macros = macros), Patches.edited(patch, "Edited", macros))
            assertContentEquals(patch.render().samples, restored.render().samples,
                "$voice saved performance and derived seed context must regenerate exact audio")
        }
    }

    @Test
    fun `velocity dispatch rerenders contact without replacing the authored runout or note`() {
        for (voice in SkirlVoice.entries) {
            val patch = authored(voice)
            assertContentEquals(patch.copy(velocity = .25f).render().samples, Velocity.atVelocity(patch, .25f).samples)
            assertEquals(.62f, patch.macros["LEAN"])
            assertEquals(.78f, patch.macros["EDGE"])
            assertEquals(57, patch.midi)
            assertEquals(Skirl.MODEL_VERSION, patch.model)
        }
    }

    @Test
    fun `finite runout metadata cannot silently inherit an unsupported model or performance`() {
        val patch = authored()
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(patch.toJsonValue().entries + (key to value))
        for (key in listOf("model", "midi", "velocity")) {
            assertFailsWith<JsonException>("missing $key") {
                SkirlPatch.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries - key))
            }
            assertFailsWith<JsonException>("null $key") { SkirlPatch.fromJsonValue(changed(key, JsonValue.Null)) }
        }
        for (bad in listOf(35.0, 85.0, 60.25, Double.NaN)) {
            assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("midi", JsonValue.Num(bad))) }
        }
        for (bad in listOf(0.0, 1.5, 2.0)) {
            assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("model", JsonValue.Num(bad))) }
        }
        for (bad in listOf(-.000000001, 1.000000001, Double.NaN)) {
            assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("velocity", JsonValue.Num(bad))) }
            assertFailsWith<JsonException> {
                SkirlPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("EDGE" to JsonValue.Num(bad)))))
            }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("UNKNOWN" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("HOLD" to .01f)) }
        assertFailsWith<JsonException> {
            SkirlPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(1e-100)))))
        }
        assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("engine", JsonValue.Str("PITCHWHEEL"))) }
        assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("voice", JsonValue.Str("SCRAPE"))) }
    }

    @Test
    fun `native listening recipes remain finite outside the factory roster`() {
        val recipes = SkirlPresets.all()
        assertEquals(SkirlVoice.entries.size, recipes.size)
        assertEquals(recipes.size, recipes.map { it.name }.distinct().size)
        assertTrue(SkirlPatch.ENGINE in PatchTypes.PROTOTYPES)
        assertTrue(Presets.all().none { it.engine == SkirlPatch.ENGINE })
        for (voice in SkirlVoice.entries) {
            val recipe = SkirlPresets.forVoice(voice).single()
            assertEquals(setOf("TUNE", "LEAN", "BRAKE", "EDGE", "PLATE", "COLLAPSE", "HOLD"), recipe.macros.keys)
            assertEquals(0f, recipe.macros.getValue("HOLD"))
            assertTrue(Presets.forVoice(SkirlPatch.ENGINE, voice.name).isEmpty())
            assertEquals(recipe, Patches.fromJsonText(recipe.toJsonText()))
            assertNull(PadRecipe(recipe).fx)
        }
    }

    @Test
    fun `pitched runouts retain their class through recipe dispatch`() {
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        for (voice in SkirlVoice.entries) {
            val patch = authored(voice)
            val restored = assertIs<SkirlPatch>(PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            assertTrue(Skirl.drumClassFor(voice, patch.macros) !in drums)
            val snip = restored.render()
            assertTrue(Classifier.classify(snip).drumClass !in drums,
                "$voice must remain pitched material after actual rendering and recipe dispatch")
        }
    }
}
