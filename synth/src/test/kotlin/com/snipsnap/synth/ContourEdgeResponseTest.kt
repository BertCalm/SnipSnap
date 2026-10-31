package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** An EDGE response belongs to the saved recipe; old JSON never opts into a new law. */
class ContourEdgeResponseTest {
    @Test
    fun `fresh affected voices opt into contrast while the pluck and legacy recipes keep their responses`() {
        for (voice in ContourVoice.entries) {
            val expected = if (voice == ContourVoice.PLUCK) ContourEdgeResponse.FOREGROUND
                else ContourEdgeResponse.CONTRAST
            assertEquals(expected, ContourConfig.forVoice(voice).edgeResponse)
            assertEquals(ContourEdgeResponse.LEGACY, ContourConfig.legacyForVoice(voice).edgeResponse)
        }
    }

    @Test
    fun `factory range revision changes only the saved response of the seven affected recipes`() {
        val unchanged = setOf("Soft Digital", "Bright Margin")
        // This is the nine-recipe listening revision, not the later bank expansion.
        val presets = ContourPresets.original()
        assertEquals(9, presets.size)
        assertEquals(9, presets.map { it.name }.distinct().size)
        assertEquals(unchanged, presets.map { it.name }.filter { it in unchanged }.toSet())
        for (patch in presets) {
            val slug = patch.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            val resource = "/contour-edge-audition/baseline/$slug.json"
            val text = requireNotNull(javaClass.getResourceAsStream(resource)) {
                "Missing previous factory recipe: $resource"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val previous = ContourPatch.fromJsonText(text)
            assertEquals(ContourEdgeResponse.FOREGROUND, previous.config.edgeResponse,
                "${patch.name}: reference must retain the previous saved response")
            val expectedResponse = if (patch.name in unchanged) ContourEdgeResponse.FOREGROUND
                else ContourEdgeResponse.CONTRAST
            assertEquals(expectedResponse, patch.config.edgeResponse, patch.name)
            assertEquals(previous.copy(config = previous.config.copy(edgeResponse = expectedResponse)), patch,
                "${patch.name}: the range revision must preserve its other controls, palette, configuration and velocity")
        }
    }

    @Test
    fun `missing and null response fields decode to legacy even when the rest of the recipe is modern`() {
        for (voice in ContourVoice.entries) {
            val patch = ContourPatch("Saved $voice", voice,
                ContourEngine.defaults(voice) + ("EDGE" to .91f),
                ContourConfig.forVoice(voice).copy(edgeSustain = .05f, loopSeconds = 1f), .63f)
            val source = patch.toJsonValue().entries
            val config = source.getValue("config").obj()
            val expected = patch.copy(config = patch.config.copy(edgeResponse = ContourEdgeResponse.LEGACY))
            for (oldConfig in listOf(config - "edgeResponse", config + ("edgeResponse" to JsonValue.Null))) {
                val oldJson = JsonValue.Obj(source + ("config" to JsonValue.Obj(oldConfig)))
                val decoded = assertIs<ContourPatch>(ContourPatch.fromJsonValue(oldJson))
                assertEquals(expected, decoded, "$voice old saved config must not inherit fresh response")
                assertContentEquals(expected.render().samples, decoded.render().samples)
            }
            val withoutConfig = assertIs<ContourPatch>(ContourPatch.fromJsonValue(JsonValue.Obj(source - "config")))
            assertEquals(ContourConfig.legacyForVoice(voice), withoutConfig.config)
        }
    }

    @Test
    fun `all explicit responses survive saved patch recipe and macro edits`() {
        val voice = ContourVoice.KEY
        for (response in ContourEdgeResponse.entries) {
            val patch = ContourPatch("Response $response", voice, ContourEngine.defaults(voice),
                ContourConfig.forVoice(voice).copy(edgeResponse = response, loopSeconds = 1f))
            val restored = assertIs<ContourPatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, restored)
            assertEquals(1, restored.model, "the saved response does not reseed the render model")
            val recipe = PadRecipe(patch)
            assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
            val edited = assertIs<ContourPatch>(Patches.edited(restored, "Edited", patch.macros + ("EDGE" to .75f)))
            assertEquals(response, edited.config.edgeResponse)
            assertEquals(patch.config, edited.config)
        }
    }

    @Test
    fun `unknown saved response identities are rejected instead of silently changing the sound`() {
        val patch = ContourPatch("Invalid response", ContourVoice.KEY, ContourEngine.defaults(ContourVoice.KEY))
        val source = patch.toJsonValue().entries
        val invalid = patch.toJsonValue().entries.getValue("config").obj() +
            ("edgeResponse" to JsonValue.Str("UNKNOWN"))
        val error = assertFailsWith<JsonException> {
            ContourPatch.fromJsonValue(JsonValue.Obj(source + ("config" to JsonValue.Obj(invalid))))
        }
        assertEquals("unknown contour edgeResponse UNKNOWN", error.message)
    }
}
