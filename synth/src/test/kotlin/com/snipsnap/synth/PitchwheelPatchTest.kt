package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Saved notes retain the complete independent object's recipe, not the last rendered state. */
class PitchwheelPatchTest {
    private val patch = PitchwheelPatch("My Wheel", PitchwheelVoice.RECOIL,
        Pitchwheel.defaults(PitchwheelVoice.RECOIL) + ("HEAT" to .15f), midi = 36, velocity = .65f)

    @Test
    fun `all voice recipes roundtrip macros root velocity and model through the shared dispatcher`() {
        for (voice in PitchwheelVoice.entries) {
            val source = patch.copy(voice = voice, macros = Pitchwheel.defaults(voice))
            val text = source.toJsonText()
            val restored = PitchwheelPatch.fromJsonText(text)
            assertEquals(source, restored)
            assertEquals(source, Patches.fromJsonText(text))
            assertEquals(text, restored.toJsonText())
        }
        val restored = Patches.fromJsonText(patch.toJsonText())
        assertContentEquals(patch.render().samples, restored.render().samples,
            "saved recipes produce different independent wheels")
    }

    @Test
    fun `editing a saved wheel retains root gesture velocity and model`() {
        val macros = patch.macros + ("PUSH" to .7f) + ("BODY" to .8f)
        val edited = Patches.edited(patch, "My Hollow Wheel", macros)
        assertEquals(patch.copy(name = "My Hollow Wheel", macros = macros), edited)
        assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
        assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
    }

    @Test
    fun `omitted optional note fields read established defaults and partial macros survive`() {
        for (optional in listOf("", "\"midi\":null,\"velocity\":null,\"model\":null,")) {
            val text = """{"engine":"PITCHWHEEL","version":1,"name":"Wheel","voice":"DRAW",$optional"macros":{"ADHESION":0.6},"extra":"ignored"}"""
            val restored = PitchwheelPatch.fromJsonText(text)
            assertEquals(Pitchwheel.DEFAULT_MIDI, restored.midi)
            assertEquals(1f, restored.velocity)
            assertEquals(Pitchwheel.MODEL_VERSION, restored.model)
            assertEquals(mapOf("ADHESION" to .6f), restored.macros)
            val settled = Pitchwheel.settled(restored.voice, restored.macros)
            assertEquals(.6f, settled.getValue("ADHESION"))
            assertEquals(Pitchwheel.defaults(restored.voice).getValue("TOOTH"), settled.getValue("TOOTH"))
        }
    }

    @Test
    fun `invalid models common fields and engine parameters are refused`() {
        fun changed(name: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).also { it[name] = value })
        assertFailsWith<JsonException> { PitchwheelPatch.fromJsonValue(changed("model", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { PitchwheelPatch.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { PitchwheelPatch.fromJsonValue(changed("voice", JsonValue.Str("KICK"))) }
        assertFailsWith<JsonException> { PitchwheelPatch.fromJsonValue(changed("engine", JsonValue.Str("RESIN"))) }
        assertFailsWith<JsonException> { PitchwheelPatch.fromJsonValue(changed("midi", JsonValue.Num(48.5))) }
        for (midi in listOf(Pitchwheel.MIDI_MIN - 1, Pitchwheel.MIDI_MAX + 1)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        }
        for (velocity in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = velocity) }
        }
        for (macros in listOf(mapOf("REVERB" to .5f), mapOf("PUSH" to -.1f),
            mapOf("BODY" to 1.1f), mapOf("HEAT" to Float.NaN))) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = macros) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 0) }
    }

    @Test
    fun `provisional defaults are discoverable and never select drum choke routing`() {
        val presets = PitchwheelPresets.all()
        assertTrue(presets.isNotEmpty())
        assertTrue(Presets.all().containsAll(presets))
        for (voice in PitchwheelVoice.entries) {
            val group = PitchwheelPresets.forVoice(voice)
            assertTrue(group.isNotEmpty(), "$voice has no dry starting recipe")
            assertEquals(group, Presets.forVoice(PitchwheelPatch.ENGINE, voice.name))
            for (preset in group) {
                assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
                val expected = if (Pitchwheel.isLoop(preset.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
                assertEquals(expected, Pitchwheel.drumClassFor(voice, preset.macros))
            }
        }
    }
}
