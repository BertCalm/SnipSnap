package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Pitchwheel
import com.snipsnap.synth.PitchwheelPatch
import com.snipsnap.synth.PitchwheelVoice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthDraftsTest {
    @Test
    fun `an edited saved wheel returns with its original note velocity and model without a preset highlight`() {
        for (model in 1..Pitchwheel.MODEL_VERSION) {
            val source = PitchwheelPatch("Low wheel", PitchwheelVoice.RECOIL,
                mapOf("ADHESION" to .83f, "HEAT" to .21f), midi = 36, velocity = .3f, model = model)
            val navigation = Navigation()
            navigation.go(Place.MAKE)
            navigation.open(Bench.SYNTH)
            val settings = navigation.make.settings(Bench.SYNTH)
            settings.putAll(mapOf("engine" to "PITCHWHEEL", "voice" to "RECOIL",
                "preset.PITCHWHEEL.RECOIL" to source.name))
            SynthDrafts.write(settings, listOf(source))

            // Moving a slider removes the preset highlight, but the draft and
            // the separately saved sliders still have to recreate that wheel.
            settings.remove("preset.PITCHWHEEL.RECOIL")
            val editedMacros = Pitchwheel.defaults(source.voice) + source.macros + ("PUSH" to .7f)
            for ((name, value) in editedMacros) settings["macro.PITCHWHEEL.RECOIL.$name"] = value.toString()
            navigation.go(Place.PROJECT)
            navigation.go(Place.MAKE)
            navigation.open(Bench.SYNTH)
            val returningSettings = navigation.make.settings(Bench.SYNTH)
            val returning = SynthDrafts.read(returningSettings).single()
            val restoredMacros = editedMacros.mapValues { (name, _) ->
                returningSettings.getValue("macro.PITCHWHEEL.RECOIL.$name").toFloat()
            }
            val restored = Patches.edited(returning, "Edited wheel", restoredMacros)
            val expected = source.copy(name = "Edited wheel", macros = editedMacros)
            assertEquals(expected, restored)
            assertContentEquals(expected.render().samples, restored.render().samples,
                "returning to SYNTH changed saved model $model audio")
        }
    }

    @Test
    fun `voice drafts stay independent and replacing them preserves unrelated navigation settings`() {
        val low = PitchwheelPatch("Low", PitchwheelVoice.CLUNK, emptyMap(), midi = 36, model = 1)
        val high = PitchwheelPatch("High", PitchwheelVoice.TURN, mapOf("HOLD" to 1f), midi = 84, model = 2)
        val settings = mutableMapOf("engine" to "PITCHWHEEL", "macro.PITCHWHEEL.CLUNK.BODY" to "0.7",
            "preset.PITCHWHEEL.TURN" to "High")
        val unrelated = settings.toMap()
        SynthDrafts.write(settings, listOf(low, high))
        assertEquals(setOf(low, high), SynthDrafts.read(settings).toSet())

        SynthDrafts.write(settings, listOf(low.copy(velocity = .4f)))
        assertEquals(listOf(low.copy(velocity = .4f)), SynthDrafts.read(settings))
        for ((key, value) in unrelated) assertEquals(value, settings[key])
        SynthDrafts.write(settings, emptyList())
        assertEquals(unrelated, settings)
    }

    @Test
    fun `stale malformed and mismatched recipe entries cannot replace another voice`() {
        val patch = PitchwheelPatch("Valid wheel", PitchwheelVoice.DRAW, emptyMap(), model = 2)
        val unsupported = JsonValue.Obj(patch.copy(voice = PitchwheelVoice.RECOIL).toJsonValue().entries +
            ("model" to JsonValue.Num(999.0)))
        val invalidMacros = JsonValue.Obj(patch.copy(voice = PitchwheelVoice.PLUCK).toJsonValue().entries +
            ("macros" to JsonValue.Obj(mapOf("BODY" to JsonValue.Num(2.0)))))
        val settings = mutableMapOf(
            "recipe.PITCHWHEEL.DRAW" to patch.toJsonText(),
            "recipe.PITCHWHEEL.CLUNK" to patch.toJsonText(),
            "recipe.PITCHWHEEL.TURN" to "not JSON",
            "recipe.PITCHWHEEL.RECOIL" to Json.write(unsupported),
            "recipe.PITCHWHEEL.PLUCK" to Json.write(invalidMacros),
            "recipe.UNKNOWN.VOICE" to "{\"engine\":\"UNKNOWN\"}",
            "preset.PITCHWHEEL.PLUCK" to "Pitched Tooth",
        )
        assertEquals(listOf(patch), SynthDrafts.read(settings))
        assertTrue(SynthDrafts.read(emptyMap()).isEmpty())
    }
}
