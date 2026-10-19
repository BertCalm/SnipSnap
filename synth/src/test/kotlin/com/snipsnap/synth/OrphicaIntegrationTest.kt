package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrphicaIntegrationTest {
    private val config = OrphicaConfig(
        material = OrphicaMaterial.STEEL, mode = OrphicaMode.REVERSE,
        midi = 57, velocity = .65f, pluck = .72f, fracture = .68f,
        grainSizeMs = 37f, grainRateHz = 41f, scatter = .79f,
        damping = .32f, seconds = .6f, seed = Long.MAX_VALUE, captureSeconds = .12f,
    )

    @Test
    fun `dispatcher and recipe preserve every saved grain parameter and full precision seed`() {
        val patch = OrphicaPatch("Saved cloud", config, mapOf("SIZE" to .4f))
        val restored = assertIs<OrphicaPatch>(Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch, restored)
        assertEquals(patch.toJsonText(), restored.toJsonText())
        assertEquals(Long.MAX_VALUE, restored.config.seed)

        val recipe = PadRecipe(patch)
        val restoredRecipe = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restoredRecipe)
        assertFalse(restoredRecipe.alias)
        assertEquals(null, restoredRecipe.fx)
        assertContentEquals(recipe.render().samples, restoredRecipe.render().samples)

        val changed = patch.macros + ("FRACTURE" to .3f)
        assertEquals(patch.copy(name = "Edited", macros = changed), Patches.edited(patch, "Edited", changed))
        assertEquals(patch.copy(macros = changed), patch.withMacros(changed))
        assertEquals(config, patch.withMacros(changed).config)
    }

    @Test
    fun `native velocity retains saved note material grain settings macros and seed`() {
        val patch = OrphicaPatch("Velocity cloud", config, mapOf("TUNE" to .6f, "DAMP" to .4f))
        val soft = Velocity.atVelocity(patch, .25f)
        val expected = patch.copy(config = config.copy(velocity = .25f)).render()
        assertContentEquals(expected.samples, soft.samples)
        assertEquals(expected.channels, soft.channels)
        assertEquals(expected.sampleRate, soft.sampleRate)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples))
        assertEquals(config, patch.config)
    }

    @Test
    fun `unrecognized controls and future synthesis models are refused`() {
        val patch = OrphicaPatch("Strict cloud", config)
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("GRAINZ" to .5f)) }
        val changedModel = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also {
            it["model"] = JsonValue.Num((Orphica.MODEL_VERSION + 1).toDouble())
        })
        assertFailsWith<JsonException> { Patches.fromJsonValue(changedModel) }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = Orphica.MODEL_VERSION + 1) }
    }

    @Test
    fun `five provisional dry profiles are discoverable by material and name`() {
        val all = OrphicaPresets.all()
        assertEquals(5, all.size)
        assertEquals(5, all.map { it.name }.toSet().size)
        assertTrue(Presets.all().containsAll(all))
        assertTrue(Presets.forVoice("ORPHICA", "UNKNOWN").isEmpty())
        for (material in OrphicaMaterial.entries) {
            assertEquals(OrphicaPresets.forVoice(material), Presets.forVoice("ORPHICA", material.name))
        }
        for (patch in all) {
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(null, Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
        }
        assertTrue(all.any { it.config.mode == OrphicaMode.CLEAR && it.config.fracture == 0f })
        assertTrue(all.any { it.config.mode == OrphicaMode.FREEZE && it.config.captureSeconds > 0f })
    }
}
