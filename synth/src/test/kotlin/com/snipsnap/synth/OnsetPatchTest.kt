package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnsetPatchTest {
    @Test
    fun `all four voices and five audition blends save reopen and regenerate unchanged`() {
        for (voice in OnsetVoice.entries) {
            for (blend in listOf(0f, .25f, .5f, .75f, 1f)) {
                val patch = OnsetPatch("Saved $voice $blend", voice, Onset.defaults(voice) + ("BLEND" to blend))
                val restored = OnsetPatch.fromJsonText(patch.toJsonText())
                assertEquals(patch, restored)
                assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
                assertEquals(patch.toJsonText(), restored.toJsonText())
                assertEquals(3, restored.modelVersion)
                assertEquals(MACROS, restored.macros.keys)
                val original = Onset.render(voice, patch.macros, modelVersion = 3)
                val regenerated = restored.render()
                assertContentEquals(original.samples, regenerated.samples, "$voice $blend saved render")
                assertEquals(original.sampleRate, regenerated.sampleRate)
                assertEquals(original.channels, regenerated.channels)

                val recipe = PadRecipe(patch)
                val reopened = PadRecipe.fromJsonText(recipe.toJsonText())
                assertEquals(recipe, reopened)
                assertNull(reopened.fx)
                assertFalse(reopened.alias)
                assertContentEquals(original.samples, reopened.render().samples, "$voice $blend pad recipe")
            }
        }
    }

    @Test
    fun `saved controls are immutable snapshots independent of the slider map`() {
        val sliders = Onset.defaults(OnsetVoice.KICK).toMutableMap()
        val original = sliders.toMap()
        val patch = OnsetPatch("Snapshot", OnsetVoice.KICK, sliders)
        val saved = patch.toJsonText()
        sliders["BLEND"] = 1f
        sliders.remove("TUNE")
        assertEquals(original, patch.macros)
        assertEquals(saved, patch.toJsonText())
        assertFailsWith<UnsupportedOperationException> {
            (patch.macros as MutableMap<String, Float>)["BLEND"] = 0f
        }
        val editedSliders = (original + ("BLEND" to .75f)).toMutableMap()
        val edited = patch.copy(macros = editedSliders)
        editedSliders.clear()
        assertEquals(original + ("BLEND" to .75f), edited.macros)
        assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
        assertEquals(edited.hashCode(), Patches.fromJsonText(edited.toJsonText()).hashCode())
    }

    @Test
    fun `legacy kick models survive editing and retain their original renderer`() {
        for (model in listOf(1, 2, 3)) {
            for (voice in listOf(OnsetVoice.KICK, OnsetVoice.HAT_CLOSED)) {
                val patch = OnsetPatch("Legacy $model", voice, modelVersion = model)
                val controls = patch.macros + ("BLEND" to .75f) + ("BODY" to .83f)
                val edited = Patches.edited(patch, "Edited $model", controls) as OnsetPatch
                assertEquals(patch.copy(name = "Edited $model", macros = controls), edited)
                assertEquals(patch.copy(macros = controls), patch.withMacros(controls))
                assertEquals(model, edited.modelVersion)
                val reopened = Patches.fromJsonText(edited.toJsonText())
                assertEquals(edited, reopened)
                assertContentEquals(Onset.render(voice, controls, modelVersion = model).samples, reopened.render().samples)
            }
        }
        val first = OnsetPatch("First", OnsetVoice.KICK, modelVersion = 1)
        val accepted = first.copy(modelVersion = 2)
        assertFalse(first.render().samples.contentEquals(accepted.render().samples), "saved model tag must select the renderer")
    }

    @Test
    fun `saved files require explicit valid renderer version and complete strict controls`() {
        val patch = OnsetPatch("Saved", OnsetVoice.KICK)
        fun changed(name: String, value: JsonValue?) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).also { if (value == null) it.remove(name) else it[name] = value },
        )
        for (value in listOf(null, JsonValue.Null, JsonValue.Num(0.0), JsonValue.Num(4.0),
            JsonValue.Num(2.5), JsonValue.Num(Double.NaN), JsonValue.Num(Double.POSITIVE_INFINITY), JsonValue.Str("3"))) {
            assertFailsWith<JsonException>("modelVersion=$value") {
                OnsetPatch.fromJsonValue(changed("modelVersion", value))
            }
        }
        for ((field, value) in listOf(
            "engine" to JsonValue.Str("THUMP"),
            "voice" to JsonValue.Str("CLAP"),
            "version" to JsonValue.Num(2.0),
            "version" to JsonValue.Num(1.5),
            "name" to JsonValue.Str(" "),
            "macros" to JsonValue.Null,
            "macros" to JsonValue.Arr(emptyList()),
            "macros" to JsonValue.Obj(emptyMap()),
        )) assertFailsWith<JsonException>(field) { OnsetPatch.fromJsonValue(changed(field, value)) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("macros", null)) }

        val controls = patch.toJsonValue().entries.getValue("macros").obj()
        for (value in listOf(JsonValue.Num(-.01), JsonValue.Num(1.00000000001), JsonValue.Num(Double.NaN),
            JsonValue.Num(Double.POSITIVE_INFINITY), JsonValue.Str("0.5"), JsonValue.Null)) {
            val invalid = JsonValue.Obj(controls + ("BLEND" to value))
            assertFailsWith<JsonException>("BLEND=$value") { Patches.fromJsonValue(changed("macros", invalid)) }
        }
        for (invalid in listOf(controls - "TUNE", controls + ("REVERB" to JsonValue.Num(.5)))) {
            assertFailsWith<JsonException> { Patches.fromJsonValue(changed("macros", JsonValue.Obj(invalid))) }
        }
        val extra = JsonValue.Obj(patch.toJsonValue().entries + ("futureLabel" to JsonValue.Str("ignored")))
        assertEquals(patch, Patches.fromJsonValue(extra), "unrelated additive metadata remains readable")
    }

    @Test
    fun `constructors refuse incomplete nonfinite controls and voices absent from old models`() {
        val patch = OnsetPatch("Saved", OnsetVoice.KICK)
        assertEquals(Onset.defaults(patch.voice), patch.macros)
        for (model in listOf(0, 4, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(modelVersion = model) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("BLEND" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("UNKNOWN" to .5f)) }
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("TUNE" to value)) }
        }
        for (voice in listOf(OnsetVoice.SNARE, OnsetVoice.HAT_OPEN)) {
            for (model in listOf(1, 2)) {
                assertFailsWith<IllegalArgumentException> { OnsetPatch("Absent", voice, modelVersion = model) }
                val value = JsonValue.Obj(OnsetPatch("Current", voice).toJsonValue().entries +
                    ("modelVersion" to JsonValue.Num(model.toDouble())))
                assertFailsWith<JsonException> { Patches.fromJsonValue(value) }
            }
        }
    }

    @Test
    fun `twelve dry presets expose the unchanged audition endpoints and midpoint`() {
        val all = OnsetPresets.all()
        assertEquals(12, all.size)
        assertEquals(12, all.map { it.name }.toSet().size)
        assertTrue(Presets.all().containsAll(all))
        assertTrue(Presets.forVoice("ONSET", "UNKNOWN").isEmpty())
        for (voice in OnsetVoice.entries) {
            val presets = OnsetPresets.forVoice(voice)
            assertEquals(presets, Presets.forVoice("ONSET", voice.name))
            assertEquals(listOf(0f, .5f, 1f), presets.map { it.macros.getValue("BLEND") })
            for (patch in presets) {
                assertEquals(patch.name.uppercase(), patch.name)
                assertTrue(patch.name.length <= 14, "${patch.name} exceeds the factory name limit")
                assertEquals(MACROS, patch.macros.keys)
                assertEquals(Onset.defaults(voice).filterKeys { it != "BLEND" }, patch.macros.filterKeys { it != "BLEND" })
                assertEquals(3, patch.modelVersion)
                assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
                assertEquals(patch, Presets.byName("ONSET", voice.name, patch.name))
                assertNull(Presets.landingFor("ONSET", voice.name, patch.macros))
            }
        }
    }

    @Test
    fun `scramble yields complete reproducible controls and can preserve a saved seed`() {
        for (voice in OnsetVoice.entries) {
            val patch = OnsetPatch("Seed", voice, Onset.defaults(voice) + ("BLEND" to .75f))
            assertEquals(patch.macros, Onset.scramble(voice, Random(17), temperature = 0f, near = patch))
            for (temperature in listOf(.35f, 1f)) {
                val controls = Onset.scramble(voice, Random(17), temperature)
                assertEquals(controls, Onset.scramble(voice, Random(17), temperature))
                assertEquals(MACROS, controls.keys)
                assertTrue(controls.values.all { it.isFinite() && it in 0f..1f })
                assertEquals(controls, OnsetPatch("Scrambled", voice, controls).macros)
            }
        }
    }

    @Test
    fun `velocity layers leave saved blend and renderer intact through the soften fallback`() {
        for (voice in OnsetVoice.entries) {
            val patch = OnsetPatch("Velocity", voice, modelVersion = if (voice == OnsetVoice.KICK) 1 else 3)
            assertNull(Velocity.brightnessSpec(patch), "$voice has no measured monotonic brightness macro")
            assertContentEquals(Velocity.soften(patch.render(), .75f).samples, Velocity.atVelocity(patch, .25f).samples)
            assertEquals(.5f, patch.macros.getValue("BLEND"))
            assertEquals(if (voice == OnsetVoice.KICK) 1 else 3, patch.modelVersion)
        }
    }

    private companion object {
        val MACROS = setOf("TUNE", "DECAY", "BLEND", "SNAP", "BODY", "EDGE")
    }
}
