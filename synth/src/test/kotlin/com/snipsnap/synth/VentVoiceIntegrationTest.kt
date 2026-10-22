package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VentVoiceIntegrationTest {
    private val legacyVoices = listOf(VentVoice.ROUND, VentVoice.WIRE, VentVoice.TEAR)
    private val cleanVoices = listOf(VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME)

    @Test
    fun `clean voices preserve the complete recipe and replay through the generic dispatcher`() {
        for (voice in cleanVoices) {
            val patch = VentPatch("Saved $voice", voice, Vent.defaultsFor(voice) + ("LENGTH" to .04f),
                midi = 57, velocity = .63f, seed = Int.MIN_VALUE)
            assertEquals(Vent.CLEAN_MODEL, patch.model)
            val restored = VentPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val expected = patch.render().samples
            assertContentEquals(expected, restored.render().samples)
            assertContentEquals(expected,
                Vent.render(voice, patch.macros, patch.midi, patch.velocity, patch.seed).samples)
        }
    }

    @Test
    fun `omitted and null models retain legacy voices and choose the clean model for new voices`() {
        for (voice in legacyVoices + cleanVoices) {
            val expectedModel = if (voice in legacyVoices) Vent.MODEL else Vent.CLEAN_MODEL
            fun decode(modelField: String) = VentPatch.fromJsonText(
                """{"engine":"VENT","version":1,"name":"Partial","voice":"$voice","macros":{"LENGTH":0}$modelField}""",
            )
            val omitted = decode("")
            val explicit = decode(",\"model\":$expectedModel")
            assertEquals(expectedModel, omitted.model)
            assertEquals(explicit, omitted)
            assertEquals(explicit, decode(",\"model\":null"))
            assertEquals(Vent.DEFAULT_MIDI, omitted.midi)
            assertEquals(1f, omitted.velocity)
            assertEquals(0, omitted.seed)
            assertContentEquals(explicit.render().samples, omitted.render().samples)
        }
    }

    @Test
    fun `cross model voices are refused rather than silently reinterpreting saved recipes`() {
        assertTrue(Vent.supportsModel(Vent.MODEL))
        assertTrue(Vent.supportsModel(Vent.CLEAN_MODEL))
        assertFalse(Vent.supportsModel(999))
        for (voice in legacyVoices + cleanVoices) {
            val correctModel = if (voice in legacyVoices) Vent.MODEL else Vent.CLEAN_MODEL
            val wrongModel = if (voice in legacyVoices) Vent.CLEAN_MODEL else Vent.MODEL
            val patch = VentPatch("Compatible", voice, mapOf("LENGTH" to 0f), model = correctModel)
            assertTrue(Vent.supportsVoiceModel(voice, correctModel))
            assertFalse(Vent.supportsVoiceModel(voice, wrongModel))
            assertFailsWith<IllegalArgumentException> { patch.copy(model = wrongModel) }
            assertFailsWith<IllegalArgumentException> { patch.copy(model = 999) }
            assertFailsWith<IllegalArgumentException> {
                Vent.render(voice, patch.macros, model = wrongModel)
            }
            assertFailsWith<IllegalArgumentException> {
                val fields = LinkedHashMap(patch.toJsonValue().entries)
                fields["model"] = JsonValue.Num(wrongModel.toDouble())
                VentPatch.fromJsonValue(JsonValue.Obj(fields))
            }
        }
    }

    @Test
    fun `generic edits and native velocity retain model note and starting history`() {
        for (voice in cleanVoices) {
            val patch = VentPatch("Before", voice, Vent.defaultsFor(voice) + ("LENGTH" to .04f),
                midi = 69, velocity = .72f, seed = -91827, model = Vent.CLEAN_MODEL)
            val macros = patch.macros + ("VIEW" to .81f)
            val edited = Patches.edited(patch, "After", macros)
            assertEquals(patch.copy(name = "After", macros = macros), edited)
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
            assertContentEquals(patch.copy(velocity = .2f).render().samples,
                Velocity.atVelocity(patch, .2f).samples)
        }
    }

    @Test
    fun `optional rack color survives pad recipe replay without changing the synth model`() {
        val patch = VentPatch("Clean source with color", VentVoice.PULSE, mapOf("LENGTH" to 0f),
            midi = 60, velocity = .8f, seed = -17)
        val recipe = PadRecipe(patch, FxChain(crunch = mapOf("BITS" to .4f, "RATE" to .25f)))
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        assertEquals(Vent.CLEAN_MODEL, (restored.patch as VentPatch).model)
        assertTrue(restored.alias)
        assertContentEquals(recipe.render().samples, restored.render().samples)
    }

    @Test
    fun `clean starter recipes join the factory roster and leave controlled audition starts intact`() {
        val names = listOf("Round Orbit Prototype", "Wire Orbit Prototype", "Torn Orbit Prototype")
        for ((voice, name) in legacyVoices.zip(names)) {
            assertEquals(VentPatch(name, voice, Vent.defaultsFor(voice), model = Vent.MODEL),
                VentPresets.forVoice(voice).single())
            assertTrue(Presets.forVoice(VentPatch.ENGINE, voice.name).isEmpty())
        }
        for (voice in cleanVoices) {
            val preset = VentPresets.forVoice(voice).single()
            assertEquals(Vent.CLEAN_MODEL, preset.model)
            assertEquals(Vent.defaultsFor(voice), preset.macros)
            assertEquals(Vent.DEFAULT_MIDI, preset.midi)
            assertEquals(1f, preset.velocity)
            assertEquals(0, preset.seed)
            assertEquals(VentKitPresets.forVoice(voice), Presets.forVoice(VentPatch.ENGINE, voice.name))
            assertEquals(4, Presets.forVoice(VentPatch.ENGINE, voice.name).size)
        }
        assertEquals(legacyVoices + cleanVoices, VentPresets.all().map { it.voice })
        assertEquals(VentKitPresets.all(), Presets.all().filter { it.engine == VentPatch.ENGINE })
    }
}
