package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Recipes are the public persistence contract of this provisional engine. */
class WickPatchTest {
    @Test
    fun `finite and held recipes save all controls requested note and velocity and regenerate exactly`() {
        for (voice in WickVoice.entries) for (hold in listOf(0f, 1f)) {
            val patch = WickPatch("Saved $voice", voice, Wick.defaults(voice) + ("HOLD" to hold), midi = 57, velocity = .65f)
            val restored = WickPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertContentEquals(patch.render().samples, restored.render().samples, "$voice HOLD$hold recipe replay")
            assertEquals(setOf("SPARK", "FEED", "CASCADE", "TIMBRE", "RECOVERY", "HOLD"), patch.macros.keys)
            val edited = patch.macros + ("TIMBRE" to .81f)
            assertEquals(patch.copy(name = "Edited", macros = edited), Patches.edited(patch, "Edited", edited))
        }
    }

    @Test
    fun `omitted or null optional state uses defaults and partial controls retain the voice defaults`() {
        for (voice in WickVoice.entries) {
            for (optional in listOf("", """, "midi":null, "velocity":null""")) {
                val text = """{"engine":"WICK","version":1,"name":"Partial","voice":"$voice","macros":{"TIMBRE":0.2},"future":true$optional}"""
                val patch = WickPatch.fromJsonText(text)
                assertEquals(Wick.rootMidi(voice), patch.midi)
                assertEquals(1f, patch.velocity)
                assertEquals(mapOf("TIMBRE" to .2f), patch.macros)
                assertContentEquals(Wick.render(voice, Wick.defaults(voice) + patch.macros).samples, patch.render().samples)
            }
        }
        val minimal = """{"engine":"WICK","version":1,"name":"Default","voice":"PLUCK"}"""
        assertEquals(emptyMap(), WickPatch.fromJsonText(minimal).macros)
    }

    @Test
    fun `invalid recipe identity note velocity and macro values are refused`() {
        val patch = WickPatch("Valid", WickVoice.PLUCK, Wick.defaults(WickVoice.PLUCK))
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(patch.toJsonValue().entries + (key to value))
        for ((key, value) in listOf("engine" to JsonValue.Str("TIDE"), "voice" to JsonValue.Str("MISSING"), "version" to JsonValue.Num(2.0))) {
            assertFailsWith<JsonException> { WickPatch.fromJsonValue(changed(key, value)) }
        }
        for (midi in listOf(Wick.MIN_MIDI - 1, Wick.MAX_MIDI + 1)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
            assertFailsWith<IllegalArgumentException> { WickPatch.fromJsonValue(changed("midi", JsonValue.Num(midi.toDouble()))) }
        }
        for (value in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("SPARK" to value)) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }
    }

    @Test
    fun `native velocity route preserves the saved pitch and rerenders excitation`() {
        for (voice in WickVoice.entries) {
            val patch = WickPatch("Velocity", voice, Wick.defaults(voice), midi = 67, velocity = .8f)
            assertContentEquals(patch.copy(velocity = .25f).render().samples, Velocity.atVelocity(patch, .25f).samples)
        }
    }

    @Test
    fun `provisional library remains outside factory routing and every recipe is valid and recoverable`() {
        val recipes = WickPresets.all()
        assertEquals(25, recipes.size)
        assertEquals(recipes.size, recipes.map { it.name }.distinct().size)
        assertTrue(Presets.all().none { it.engine == WickPatch.ENGINE })
        for (voice in WickVoice.entries) {
            assertEquals(if (voice == WickVoice.BUBBLE) 5 else 4, WickPresets.forVoice(voice).size)
            assertEquals(recipes.filter { it.voice == voice }, WickPresets.forVoice(voice))
            assertTrue(Presets.forVoice(WickPatch.ENGINE, voice.name).isEmpty())
            assertTrue(WickPresets.forVoice(voice).any { Wick.isLoop(it.macros.getValue("HOLD")) }, "$voice has no held audition recipe")
        }
        for (recipe in recipes) {
            assertEquals(Wick.defaults(recipe.voice).keys, recipe.macros.keys)
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(recipe.name), recipe.name)
            assertEquals(recipe, Patches.fromJsonText(recipe.toJsonText()))
        }
    }
}
