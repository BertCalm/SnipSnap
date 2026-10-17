package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Recipes preserve the finite prototype's note, geometry and explicit beam precedence. */
class FacetPatchTest {
    private fun raw(patch: FacetPatch) = Facet.probe(patch.voice, patch.macros,
        midi = patch.midi, velocity = patch.velocity, seed = patch.seed,
        initialAlignment = patch.initialAlignment, beams = patch.beams, beamMode = patch.beamMode,
        options = FacetOptions(seconds = .5f)).raw

    @Test
    fun `default and sparse override recipes regenerate the same bytes after dispatch`() {
        val patches = listOf(
            FacetPatch("Factory", FacetVoice.BLOOM, Facet.defaults(FacetVoice.BLOOM)),
            FacetPatch("Saved alignment", FacetVoice.SHIMMER, mapOf("CHROMA" to .23f, "REVOLVE" to 0f),
                midi = 57, velocity = .65f, seed = Int.MIN_VALUE, initialAlignment = .3125f,
                beams = listOf(FacetBeam(7, intensity = .3f, elevation = .8f, color = .2f, width = .65f)),
                beamMode = "OVERRIDE"),
        )
        for (patch in patches) {
            val text = patch.toJsonText()
            val decoded = FacetPatch.fromJsonText(text)
            assertEquals(patch, decoded)
            assertEquals(patch, Patches.fromJsonText(text))
            assertEquals(text, decoded.toJsonText())
            assertTrue(setOf("midi", "velocity", "seed", "initialAlignment", "beams", "beamMode", "modelVersion")
                .all { it in patch.toJsonValue().entries })
            val recipe = PadRecipe(patch = patch)
            val restored = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, restored)
            assertContentEquals(raw(patch), raw(restored.patch as FacetPatch), "${patch.name} saved render")
        }
    }

    @Test
    fun `editing macros retains saved beam overrides and snapshots caller collections`() {
        val macros = linkedMapOf("ILLUMINATE" to .3f, "FACETS" to .4f)
        val beams = mutableListOf(FacetBeam(3, intensity = .2f, color = .8f))
        val patch = FacetPatch("Original", FacetVoice.BLOOM, macros, midi = 61, velocity = .4f,
            seed = -193, initialAlignment = .75f, beams = beams, beamMode = "OVERRIDE")
        val saved = patch.toJsonText()
        macros["ILLUMINATE"] = .9f
        beams.clear()
        assertEquals(saved, patch.toJsonText(), "caller mutation changed a saved recipe")
        val editedMacros = patch.macros + ("FACETS" to .65f)
        val edited = Patches.edited(patch, "Edited", editedMacros)
        assertEquals(patch.copy(name = "Edited", macros = editedMacros), edited)
        assertEquals(patch.copy(macros = editedMacros), patch.withMacros(editedMacros))
        assertEquals(patch.beams, (edited as FacetPatch).beams)
        assertEquals("OVERRIDE", edited.beamMode)
    }

    @Test
    fun `invalid controls overrides and held recipes fail explicitly instead of hiding edits`() {
        val patch = FacetPatch("Valid", FacetVoice.BLOOM, emptyMap())
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("CHROMA" to value)) }
            assertFailsWith<IllegalArgumentException> { patch.copy(initialAlignment = value) }
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
            assertFailsWith<IllegalArgumentException> {
                patch.copy(beamMode = "OVERRIDE", beams = listOf(FacetBeam(0, intensity = value)))
            }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(beams = listOf(FacetBeam(0))) }
        assertFailsWith<IllegalArgumentException> { patch.copy(beamMode = "MIX") }
        assertFailsWith<IllegalArgumentException> {
            patch.copy(beamMode = "OVERRIDE", beams = listOf(FacetBeam(2), FacetBeam(2)))
        }
        for (id in listOf(-1, 12)) assertFailsWith<IllegalArgumentException> {
            patch.copy(beamMode = "OVERRIDE", beams = listOf(FacetBeam(id)))
        }
        for (hold in listOf(.99f, 1f)) {
            val failure = assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("HOLD" to hold)) }
            assertTrue(failure.message.orEmpty().contains("finite prototype"), "HOLD rejection must explain the finite scope")
        }

        fun changed(field: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).apply { put(field, value) })
        for ((field, value) in listOf(
            "modelVersion" to JsonValue.Num(2.0),
            "midi" to JsonValue.Num(48.5),
            "seed" to JsonValue.Num(1.5),
            "beamMode" to JsonValue.Str("MIX"),
            "initialAlignment" to JsonValue.Num(1.01),
            "macros" to JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(.99))),
            "beams" to JsonValue.Arr(listOf(JsonValue.Obj(mapOf("id" to JsonValue.Num(0.0))))),
        )) assertFailsWith<JsonException>("accepted invalid saved $field") {
            Patches.fromJsonValue(changed(field, value))
        }
    }
}
