package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Engine identity and complete saved recipes follow SnipSnap's existing patch/pad doors. */
class OrigamiPatchTest {
    @Test
    fun `all voices retain geometry note velocity model and full 64bit seed when saved`() {
        for (model in listOf(1, 2, 3, 4)) for ((index, voice) in OrigamiVoice.entries.withIndex()) {
            val patch = OrigamiPatch("Saved ${voice.name}", voice,
                Origami.defaults(voice) + mapOf("CREASE" to .31f, "FOLD" to .78f),
                midi = 48 + index * 12, velocity = .65f, seed = Long.MAX_VALUE - index, model = model)
            assertEquals("ORIGAMI", patch.engine)
            assertEquals(voice.name, patch.voiceName)
            assertEquals(patch, OrigamiPatch.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(JsonValue.Str(patch.seed.toString()), patch.toJsonValue().entries["seed"])
            val macros = patch.macros + ("TURN" to .72f)
            val edited = assertIs<OrigamiPatch>(Patches.edited(patch, "Edited paper", macros))
            assertEquals(patch.copy(name = "Edited paper", macros = macros), edited)
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
            assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
        }
        for (seed in listOf(Long.MIN_VALUE, -9007199254740993L, 9007199254740993L)) {
            val patch = OrigamiPatch("Large seed", OrigamiVoice.STILL, emptyMap(), seed = seed)
            assertEquals(seed, OrigamiPatch.fromJsonText(patch.toJsonText()).seed)
        }
    }

    @Test
    fun `a saved pad regenerates the same complete dry finite note`() {
        val patch = OrigamiPatch("Paper cloud", OrigamiVoice.STILL,
            Origami.defaults(OrigamiVoice.STILL) + ("MOTION" to .63f),
            midi = 60, velocity = .72f, seed = -9007199254740993L)
        val recipe = PadRecipe(patch)
        val reopened = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, reopened)
        assertContentEquals(recipe.render().samples, reopened.render().samples,
            "serialized seed and geometry must reproduce the original pad")
    }

    @Test
    fun `velocity adapter retains saved note seed model and geometry`() {
        val patch = OrigamiPatch("Quiet paper", OrigamiVoice.FAN,
            Origami.defaults(OrigamiVoice.FAN) + mapOf("CREASE" to .39f, "MOTION" to .63f),
            midi = 72, velocity = .8f, seed = Long.MIN_VALUE + 23L)
        val expected = patch.copy(velocity = .35f).render()
        val adapted = Velocity.atVelocity(patch, .35f)
        // Independent renders own different arrays; Snip equality intentionally uses array identity.
        assertEquals(expected.channels, adapted.channels)
        assertEquals(expected.sampleRate, adapted.sampleRate)
        assertEquals(expected.frameCount, adapted.frameCount)
        assertContentEquals(expected.samples, adapted.samples,
            "velocity adaptation must retain the patch's explicit MIDI, seed, model and controls")
        val zero = Velocity.atVelocity(patch, 0f)
        assertEquals(expected.frameCount, zero.frameCount)
        assertTrue(zero.samples.all { it == 0f }, "zero velocity through the common adapter is audible")
    }

    @Test
    fun `sparse recipes keep implicit tune and generated source defaults`() {
        val patch = OrigamiPatch("Sparse paper", OrigamiVoice.CRANE, mapOf("FOLD" to .3f))
        assertEquals(patch, OrigamiPatch.fromJsonText(patch.toJsonText()))
        val omitted = JsonValue.Obj(patch.toJsonValue().entries - setOf("model", "midi", "velocity", "seed"))
        assertEquals(patch.copy(model = 1), Patches.fromJsonValue(omitted),
            "omitted model belongs to the original patch format, even as new defaults change")
        assertEquals(4, patch.model, "new recipes choose the revised model explicitly")
        assertEquals(null, patch.midi)
        assertEquals(1f, patch.velocity)
        assertEquals(0L, patch.seed)
    }

    @Test
    fun `legacy explicit missing and null models preserve original saved audio`() {
        val text = """{"engine":"ORIGAMI","version":1,"name":"Original paper","voice":"STILL","macros":{"CREASE":0.5,"FOLD":0.75,"MOTION":0.0},"model":1,"midi":60,"velocity":0.85,"seed":"20261012"}"""
        val original = OrigamiPatch.fromJsonText(text)
        assertEquals(1, original.model)
        val expected = Origami.render(original.voice, original.macros, original.velocity,
            original.midi, original.seed, model = 1).samples
        val fields = original.toJsonValue().entries
        for (value in listOf(JsonValue.Obj(fields), JsonValue.Obj(fields - "model"),
            JsonValue.Obj(fields + ("model" to JsonValue.Null)))) {
            val restored = assertIs<OrigamiPatch>(Patches.fromJsonValue(value))
            assertEquals(original, restored)
            assertContentEquals(expected, restored.render().samples,
                "legacy model-1 recipes must not silently acquire the softer model-2 source")
            val edited = assertIs<OrigamiPatch>(Patches.edited(restored, "Edited original", restored.macros))
            assertEquals(1, edited.model)
            assertContentEquals(expected, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).render().samples)
        }
    }

    @Test
    fun `saved second and third models preserve their version through editing and pad replay`() {
        for (model in listOf(2, 3)) {
            val text = """{"engine":"ORIGAMI","version":1,"name":"Previous paper","voice":"FAN","macros":{"CREASE":0.5,"FOLD":0.75,"MOTION":0.75},"model":$model,"midi":60,"velocity":0.85,"seed":"20261012"}"""
            val original = OrigamiPatch.fromJsonText(text)
            assertEquals(model, original.model)
            val expected = Origami.render(original.voice, original.macros, original.velocity,
                original.midi, original.seed, model = model).samples
            val restored = assertIs<OrigamiPatch>(Patches.fromJsonText(original.toJsonText()))
            val edited = assertIs<OrigamiPatch>(Patches.edited(restored, "Edited previous paper", restored.macros))
            val pad = PadRecipe.fromJsonText(PadRecipe(edited).toJsonText())
            assertEquals(model, edited.model)
            assertEquals(model, assertIs<OrigamiPatch>(pad.patch).model)
            assertContentEquals(expected, restored.render().samples)
            assertContentEquals(expected, pad.render().samples,
                "model-$model recipes must not silently acquire the newest fold behavior")
        }
    }

    @Test
    fun `invalid persisted identities notes models controls and velocity are refused`() {
        val patch = OrigamiPatch("Paper", OrigamiVoice.CRANE, emptyMap())
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(patch.toJsonValue().entries + (key to value))
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 99) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("HOLD" to .5f)) }
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("FOLD" to value)) }
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
        }
        for (midi in listOf(47, 73, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
            assertFailsWith<IllegalArgumentException> { Patches.fromJsonValue(changed("midi", JsonValue.Num(midi.toDouble()))) }
        }
        for (value in listOf(0.0, 99.0, 1.5)) {
            assertFailsWith<JsonException> { Patches.fromJsonValue(changed("model", JsonValue.Num(value))) }
        }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("midi", JsonValue.Num(60.5))) }
        assertFailsWith<JsonException> { OrigamiPatch.fromJsonValue(changed("engine", JsonValue.Str("PRISM"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("voice", JsonValue.Str("SAW"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("version", JsonValue.Num(99.0))) }
        for (seed in listOf(JsonValue.Num(12.0), JsonValue.Str("9223372036854775808"), JsonValue.Str("1.5"))) {
            assertFailsWith<JsonException> { Patches.fromJsonValue(changed("seed", seed)) }
        }
    }
}
