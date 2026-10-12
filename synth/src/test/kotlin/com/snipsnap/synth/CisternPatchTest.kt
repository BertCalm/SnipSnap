package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CisternPatchTest {
    private val patch = CisternPatch(
        "Upper Surface", CisternVoice.FIRST, Cistern.defaults(CisternVoice.FIRST), midi = 72, velocity = 0.4f,
    )

    @Test
    fun `every voice round-trips all six macros and explicit note velocity model`() {
        for (voice in CisternVoice.entries) {
            val source = patch.copy(voice = voice, macros = Cistern.defaults(voice))
            val text = source.toJsonText()
            assertEquals(source, CisternPatch.fromJsonText(text))
            assertEquals(source, Patches.fromJsonText(text))
            assertEquals(text, Patches.fromJsonText(text).toJsonText())
            assertEquals(
                setOf("STRIKE", "SUSPENSION", "DROP", "SKIN", "DRAIN", "HOLD"), source.macros.keys,
            )
            assertEquals(1.0, (source.toJsonValue().entries.getValue("model") as JsonValue.Num).value)
        }
    }

    @Test
    fun `missing or null optional state uses defaults and unknown metadata is ignored`() {
        for (optional in listOf("", """, "midi":null, "velocity":null, "model":null""")) {
            val text = """{"engine":"CISTERN","version":1,"name":"Surface","voice":"FIRST","macros":{"STRIKE":0.2},"future":true$optional}"""
            val decoded = CisternPatch.fromJsonText(text)
            assertEquals(Cistern.DEFAULT_MIDI, decoded.midi)
            assertEquals(1f, decoded.velocity)
            assertEquals(Cistern.MODEL_VERSION, decoded.model)
            assertEquals(mapOf("STRIKE" to 0.2f), decoded.macros)
        }
    }

    @Test
    fun `editing retains explicit pitch and velocity and fills omitted macros at rendering`() {
        val source = CisternPatch("Low", CisternVoice.POOL, mapOf("DROP" to 0.7f), midi = 36, velocity = 0.3f)
        val edited = Patches.edited(source, "My Low", source.macros + ("DRAIN" to 0.8f)) as CisternPatch
        assertEquals(source.copy(name = "My Low", macros = source.macros + ("DRAIN" to 0.8f)), edited)
        assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
        assertContentEquals(
            Cistern.render(source.voice, Cistern.defaults(source.voice) + edited.macros, source.midi, source.velocity).samples,
            edited.render().samples,
        )
    }

    @Test
    fun `invalid version model voice note velocity and macro fields are refused`() {
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).apply { put(key, value) })
        assertFailsWith<JsonException> { CisternPatch.fromJsonValue(changed("engine", JsonValue.Str("FLOTILLA"))) }
        assertFailsWith<JsonException> { CisternPatch.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { CisternPatch.fromJsonValue(changed("model", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { CisternPatch.fromJsonValue(changed("voice", JsonValue.Str("KICK"))) }
        for (midi in listOf(Cistern.MIDI_MIN - 1, Cistern.MIDI_MAX + 1)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
            assertFailsWith<IllegalArgumentException> { CisternPatch.fromJsonValue(changed("midi", JsonValue.Num(midi.toDouble()))) }
        }
        for (velocity in listOf(-0.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = velocity) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 0) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to 0.5f)) }
        for (value in listOf(-0.1f, 1.1f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("STRIKE" to value)) }
        }
    }

    @Test
    fun `a stored pad recipe regenerates the explicit note through its rack`() {
        val rack = FxChain(reverse = true)
        val recipe = PadRecipe(patch, rack)
        val decoded = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, decoded)
        val dry = patch.render()
        val expected = rack.process(dry)
        assertContentEquals(expected.samples, decoded.render().samples)
        assertFalse(dry.samples.contentEquals(expected.samples))
    }

    @Test
    fun `production velocity layers rerender strike energy at the saved note`() {
        val reference = patch.render()
        val spec = Velocity.brightnessSpec(patch)
        assertTrue(Velocity.canUseAtVelocity(reference, patch, fx = null))
        assertFalse(Velocity.canUseAtVelocity(reference, patch, FxChain(reverse = true)))
        val native = Cistern.render(patch.voice, patch.macros, patch.midi, velocity = 0.25f)
        val layer = Velocity.layerAt(reference, patch, null, 0.25f, spec)
        assertContentEquals(Velocity.peakMatch(reference, native).samples, layer.samples)
        assertFalse(Velocity.soften(reference, 0.75f).samples.contentEquals(layer.samples))
    }
}
