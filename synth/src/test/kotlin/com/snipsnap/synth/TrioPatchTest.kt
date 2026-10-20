package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved scenes retain their complete gesture through edits, export and replay. */
class TrioPatchTest {
    private val config = TrioConfig(seed = -91827, tempoBpm = 120f, beats = 2,
        press = .7f, tremoloHz = 16f, strumMs = 4f)

    @Test
    fun `every scene roundtrips its root velocity controls and complete seeded gesture`() {
        for (voice in TrioVoice.entries) {
            val patch = TrioPatch("Saved $voice", voice, mapOf("TOUCH" to .65f, "SIDES" to .32f),
                midi = 57, velocity = .6f, config = config)
            val text = patch.toJsonText()
            val restored = TrioPatch.fromJsonText(text)
            assertEquals(patch, restored)
            assertEquals(text, restored.toJsonText())
            assertEquals(patch, Patches.fromJsonText(text))
            assertEquals(Trio.MODEL_VERSION, restored.model)
            assertEquals(setOf("model", "seed", "tempoBpm", "beats", "press", "tremoloHz", "strumMs"),
                restored.toJsonValue().entries.getValue("config").obj().keys)
            val editedMacros = patch.macros + ("SPACE" to .8f)
            assertEquals(patch.copy(macros = editedMacros), patch.withMacros(editedMacros))
            assertEquals(patch.copy(name = "Edited $voice", macros = editedMacros),
                Patches.edited(patch, "Edited $voice", editedMacros))
        }
    }

    @Test
    fun `a dry pad recipe rebuilds the same sound and native velocity keeps its seeded scene`() {
        val patch = TrioPatch("One Hit", TrioVoice.ENSEMBLE,
            mapOf("TOUCH" to .55f, "DRAMA" to .85f, "MOTION" to .4f), midi = 62, velocity = .8f, config = config)
        val recipe = PadRecipe(patch = patch)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        assertIs<TrioPatch>(restored.patch)
        assertNull(restored.fx)
        assertContentEquals(recipe.render().samples, restored.render().samples, "recipe must regenerate its WAV")
        assertContentEquals(patch.copy(velocity = .3f).render().samples,
            Velocity.atVelocity(patch, .3f).samples, "velocity must change the gesture, preserving the complete recipe")
        assertEquals(.8f, patch.velocity, "velocity variants must not edit the source patch")
        assertEquals(config, patch.config)
    }

    @Test
    fun `optional fields and partial macro maps keep their documented defaults`() {
        for (optional in listOf("", "\"midi\":null,\"velocity\":null,\"config\":null,")) {
            val text = """{"engine":"TRIO","version":1,"name":"Partial","voice":"TRES",${optional}"macros":{"SPACE":0.7}}"""
            val patch = TrioPatch.fromJsonText(text)
            assertEquals(Trio.DEFAULT_MIDI, patch.midi)
            assertEquals(1f, patch.velocity)
            assertEquals(TrioConfig(model = Trio.LEGACY_MODEL_VERSION), patch.config)
            assertEquals(mapOf("SPACE" to .7f), patch.macros)
        }
        val patch = TrioPatch("Partial", TrioVoice.TRES, mapOf("SPACE" to .7f))
        assertContentEquals(patch.render().samples,
            patch.copy(macros = Trio.defaults(patch.voice) + patch.macros).render().samples)
        for (seed in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            val seeded = patch.copy(config = patch.config.copy(seed = seed))
            assertEquals(seeded, TrioPatch.fromJsonText(seeded.toJsonText()), "seed integer precision")
        }
    }

    @Test
    fun `wrong engine schema model and corrupt typed configuration are refused by name`() {
        val patch = TrioPatch("Saved", TrioVoice.ENSEMBLE, emptyMap())
        fun changed(name: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).also { it[name] = value })
        fun changedConfig(name: String, value: JsonValue): JsonValue.Obj {
            val fields = LinkedHashMap(patch.toJsonValue().entries.getValue("config").obj())
            fields[name] = value
            return changed("config", JsonValue.Obj(fields))
        }
        assertContains(assertFailsWith<JsonException> {
            TrioPatch.fromJsonValue(changed("engine", JsonValue.Str("PLUCK")))
        }.message.orEmpty(), "not a TRIO patch")
        assertContains(assertFailsWith<JsonException> {
            Patches.fromJsonValue(changed("version", JsonValue.Num(2.0)))
        }.message.orEmpty(), "unsupported patch version")
        assertContains(assertFailsWith<JsonException> {
            Patches.fromJsonValue(changed("voice", JsonValue.Str("KICK")))
        }.message.orEmpty(), "unknown voice")
        assertContains(assertFailsWith<JsonException> {
            Patches.fromJsonValue(changedConfig("model", JsonValue.Num((Trio.MODEL_VERSION + 1).toDouble())))
        }.message.orEmpty(), "unsupported TRIO model")
        assertContains(assertFailsWith<JsonException> {
            Patches.fromJsonValue(changedConfig("strumHz", JsonValue.Num(8.0)))
        }.message.orEmpty(), "unknown TRIO config")
        for (key in listOf("seed", "beats", "model")) {
            assertContains(assertFailsWith<JsonException> {
                Patches.fromJsonValue(changedConfig(key, JsonValue.Num(.5)))
            }.message.orEmpty(), "integer", message = key)
        }
        assertContains(assertFailsWith<JsonException> {
            Patches.fromJsonValue(changed("midi", JsonValue.Num(60.5)))
        }.message.orEmpty(), "integer")
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("config", JsonValue.Str("gesture"))) }
    }

    @Test
    fun `invalid physical controls roots and velocities cannot become saved recipes`() {
        val patch = TrioPatch("Saved", TrioVoice.ENSEMBLE, emptyMap())
        for (midi in listOf(Trio.MIN_MIDI - 1, Trio.MAX_MIDI + 1)) {
            assertContains(assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }.message.orEmpty(), "midi")
        }
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertContains(assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }.message.orEmpty(), "velocity")
            assertContains(assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TOUCH" to value)) }.message.orEmpty(), "TOUCH")
            assertFailsWith<IllegalArgumentException> { config.copy(press = value) }
        }
        assertContains(assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }.message.orEmpty(), "unknown macro")
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { config.copy(tempoBpm = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(beats = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(tremoloHz = 26f) }
        assertFailsWith<IllegalArgumentException> { config.copy(strumMs = 0f) }
    }
}
