package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShoalIntegrationTest {
    private fun patch(voice: ShoalVoice = ShoalVoice.RIPPLE, model: Int = Shoal.MODEL_VERSION,
        material: ShoalMaterial = if (model < 3) ShoalMaterial.HARMONIC else ShoalMaterial.WOOD) = ShoalPatch(
        "Saved population", voice, Shoal.defaults(voice) + mapOf("CURRENT" to .62f, "STRAY" to .31f),
        midi = 57, velocity = .63f, model = model, material = material,
    )

    @Test
    fun `patch and pad recipe JSON reproduce the saved root velocity model and population`() {
        for (voice in ShoalVoice.entries) for (model in listOf(1, 2, 3)) {
            val source = patch(voice, model)
            val restored = assertIs<ShoalPatch>(Patches.fromJsonText(source.toJsonText()))
            assertEquals(source, restored)
            assertContentEquals(source.render().samples, restored.render().samples, "$voice model$model serialized replay")
        }
        val source = patch()
        val rack = FxChain(reverse = true)
        val recipe = PadRecipe(source, rack)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        val dry = source.render()
        val replayed = restored.render()
        assertContentEquals(rack.process(dry).samples, replayed.samples)
        assertFalse(dry.samples.contentEquals(replayed.samples), "recipe effects were ignored")
        for (material in ShoalMaterial.entries) {
            val saved = patch(material = material)
            assertEquals(saved, Patches.fromJsonText(saved.toJsonText()), "$material material lost in patch JSON")
            val recipeWithMaterial = PadRecipe(saved)
            assertEquals(recipeWithMaterial, PadRecipe.fromJsonText(recipeWithMaterial.toJsonText()))
        }
    }

    @Test
    fun `macro edits and native velocity retain explicit pitch and synthesis model`() {
        for (model in listOf(1, 2, 3)) {
            val source = patch(model = model)
            val changed = source.macros + ("COHESION" to .82f)
            val edited = assertIs<ShoalPatch>(Patches.edited(source, "Edited population", changed))
            assertEquals(source.copy(name = "Edited population", macros = changed), edited)
            assertEquals(source.copy(macros = changed), source.withMacros(changed))
            assertEquals(PadRecipe(edited), PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()))

            val soft = Velocity.atVelocity(edited, .27f)
            assertContentEquals(edited.copy(velocity = .27f).render().samples, soft.samples,
                "model$model velocity did not regenerate the saved instrument")
            val original = edited.render()
            assertFalse(original.samples.contentEquals(soft.samples), "model$model native velocity has no effect")
            assertFalse(Velocity.soften(original, .73f).samples.contentEquals(soft.samples),
                "model$model velocity fell back to filtering the completed sample")
            assertTrue(Velocity.canUseAtVelocity(original, edited, null))
        }
        for (material in ShoalMaterial.entries) {
            val source = patch(material = material)
            val changed = source.macros + ("DENSITY" to .7f)
            assertEquals(source.copy(name = "Material edit", macros = changed), Patches.edited(source, "Material edit", changed))
            assertEquals(source.copy(macros = changed), source.withMacros(changed))
        }
        val wire = patch(material = ShoalMaterial.WIRE)
        val native = Velocity.atVelocity(wire, .4f)
        assertContentEquals(Shoal.render(wire.voice, wire.macros, wire.midi, .4f, model = 3,
            material = ShoalMaterial.WIRE).samples, native.samples, "native velocity discarded material selection")
    }

    @Test
    fun `partial JSON uses voice defaults and rejects invalid saved public parameters`() {
        val source = patch(ShoalVoice.GLASS)
        val fields = LinkedHashMap(source.toJsonValue().entries)
        for (key in listOf("midi", "velocity", "model", "material")) fields.remove(key)
        fields["macros"] = JsonValue.Obj(mapOf("GLOW" to JsonValue.Num(.4)))
        val restored = assertIs<ShoalPatch>(ShoalPatch.fromJsonValue(JsonValue.Obj(fields)))
        assertEquals(Shoal.rootMidi(source.voice), restored.midi)
        assertEquals(1f, restored.velocity)
        assertEquals(Shoal.LEGACY_MODEL_VERSION, restored.model, "an old recipe must not silently change synthesis model")
        assertEquals(ShoalMaterial.HARMONIC, restored.material)
        assertEquals(mapOf("GLOW" to .4f), restored.macros)
        assertContentEquals(Shoal.render(restored.voice, restored.macros, restored.midi, restored.velocity,
            model = Shoal.LEGACY_MODEL_VERSION).samples, restored.render().samples)
        val nullModel = assertIs<ShoalPatch>(ShoalPatch.fromJsonValue(JsonValue.Obj(fields + ("model" to JsonValue.Null))))
        assertEquals(restored, nullModel)
        assertEquals(Shoal.MODEL_VERSION, patch().model, "newly created recipes should use the current model")
        assertEquals(ShoalMaterial.WOOD, patch().material, "new patches should begin with the approved wooden body")
        for (model in listOf(1, 2, 3)) {
            val absent = assertIs<ShoalPatch>(ShoalPatch.fromJsonValue(JsonValue.Obj(fields + ("model" to JsonValue.Num(model.toDouble())))))
            val nullMaterial = assertIs<ShoalPatch>(ShoalPatch.fromJsonValue(JsonValue.Obj(fields + mapOf(
                "model" to JsonValue.Num(model.toDouble()), "material" to JsonValue.Null))))
            assertEquals(if (model < 3) ShoalMaterial.HARMONIC else ShoalMaterial.WOOD, absent.material)
            assertEquals(absent, nullMaterial)
        }

        for (midi in listOf(Shoal.MIN_MIDI - 1, Shoal.MAX_MIDI + 1)) {
            val error = assertFailsWith<IllegalArgumentException> { source.copy(midi = midi) }
            assertTrue(error.message.orEmpty().contains("midi", ignoreCase = true), "MIDI reached an unrelated guard")
        }
        for (velocity in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val error = assertFailsWith<IllegalArgumentException> { source.copy(velocity = velocity) }
            assertTrue(error.message.orEmpty().contains("velocity"), "velocity reached an unrelated guard")
        }
        assertFailsWith<IllegalArgumentException> { source.copy(model = Shoal.MODEL_VERSION + 1) }
        assertFailsWith<IllegalArgumentException> { source.copy(macros = mapOf("TUNE" to .5f)) }
        assertFailsWith<JsonException> {
            ShoalPatch.fromJsonValue(JsonValue.Obj(fields + ("midi" to JsonValue.Num(57.5))))
        }
        assertFailsWith<JsonException> {
            ShoalPatch.fromJsonValue(JsonValue.Obj(fields + mapOf("model" to JsonValue.Num(3.0), "material" to JsonValue.Str("AIR"))))
        }
        for (model in listOf(1, 2)) {
            assertFailsWith<IllegalArgumentException> { patch(model = model, material = ShoalMaterial.WOOD) }
        }
        for (material in ShoalMaterial.entries.filter { it != ShoalMaterial.HARMONIC }) {
            assertFailsWith<IllegalArgumentException>("$material may not silently export an approximate harmonic HOLD") {
                patch(material = material).copy(macros = mapOf("HOLD" to 1f))
            }
        }
    }

    @Test
    fun `first listen recipes remain discoverable dry and pitched through kit assembly`() {
        val presets = ShoalPresets.all()
        assertTrue(presets.isNotEmpty())
        assertEquals(presets.size, presets.map { it.name }.distinct().size)
        assertTrue(Presets.all().containsAll(presets))
        for (voice in ShoalVoice.entries) {
            val group = ShoalPresets.forVoice(voice)
            assertTrue(group.isNotEmpty(), "$voice has no first-listen recipe")
            assertEquals(group, Presets.forVoice(ShoalPatch.ENGINE, voice.name))
            assertEquals(ShoalMaterial.entries.toSet(), group.map { it.material }.toSet(), "$voice lacks a factory material example")
            assertTrue(group.any { Shoal.isLoop(it.macros.getValue("HOLD")) }, "$voice has no moving HOLD example")
            for (preset in group) {
                assertEquals(Shoal.MODEL_VERSION, preset.model, "${preset.name} is not a current factory recipe")
                if (Shoal.isLoop(preset.macros.getValue("HOLD")))
                    assertEquals(ShoalMaterial.HARMONIC, preset.material, "${preset.name} pretends a modal body has a validated HOLD")
                assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
            }
        }

        val arranged = SynthKits.shoal()
        assertEquals(16, arranged.size)
        for ((i, nullable) in arranged.withIndex()) {
            val pad = assertNotNull(nullable, "pad ${i + 1} missing")
            val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            assertEquals(null, recipe.fx, "pad ${i + 1} needs an effect to establish its identity")
            val saved = assertIs<ShoalPatch>(recipe.patch)
            assertEquals(Shoal.MODEL_VERSION, saved.model)
            assertTrue(Shoal.supportsMaterial(saved.model, saved.material))
            val expected = if (Shoal.isLoop(saved.macros["HOLD"] ?: 0f)) DrumClass.LOOP else DrumClass.TONAL
            assertEquals(expected, pad.drumClass)
            assertEquals(44_100, pad.snip.sampleRate)
            assertEquals(1, pad.snip.channels)
            assertTrue(pad.snip.samples.all { it.isFinite() && it in -1f..1f })
            if (i == 0 || i == arranged.lastIndex) assertContentEquals(pad.snip.samples, recipe.render().samples)
        }
        val saved = arranged.map { assertIs<ShoalPatch>(PadRecipe.fromJsonValue(assertNotNull(assertNotNull(it).recipe)).patch) }
        assertTrue(saved.take(8).all { it.voice == ShoalVoice.GLASS && it.material == ShoalMaterial.WOOD })
        assertEquals(2, saved.count { Shoal.isLoop(it.macros.getValue("HOLD")) })
    }
}
