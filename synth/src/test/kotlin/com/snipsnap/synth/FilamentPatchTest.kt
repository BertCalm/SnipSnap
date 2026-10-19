package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Saved recipes retain the requested note, source effort and deterministic geometry. */
class FilamentPatchTest {
    @Test
    fun `finite and held recipes replay every source and geometry setting through the dispatcher`() {
        for ((index, voice) in FilamentVoice.entries.withIndex()) {
            val config = FilamentConfig(seed = 719 + index, profile = FilamentProfile.entries[index + 1],
                perfectRetention = voice == FilamentVoice.THREAD)
            val macros = Filament.defaults(voice) + mapOf("INJECT" to .63f, "BEND" to .72f,
                "HOLD" to if (voice == FilamentVoice.PULSE) 1f else 0f)
            val original = FilamentPatch("Saved $voice", voice, macros, midi = 57, velocity = .65f, config = config)
            val restored = FilamentPatch.fromJsonText(original.toJsonText())
            assertEquals(original, restored)
            assertEquals(original, Patches.fromJsonText(original.toJsonText()))
            assertContentEquals(original.render().samples, restored.render().samples, "$voice recipe replay")
            assertEquals(setOf("INJECT", "BEND", "LENGTH", "BUNDLE", "COLLECT", "HOLD"), restored.macros.keys)
            val editedMacros = macros + ("LENGTH" to .81f)
            assertEquals(original.copy(name = "Edited", macros = editedMacros), Patches.edited(original, "Edited", editedMacros))
        }
    }

    @Test
    fun `omitted or null optional recipe state uses the documented original defaults`() {
        for (voice in FilamentVoice.entries) for (optionals in listOf("",
            """, "midi":null, "velocity":null, "config":null, "model":null""")) {
            val text = """{"engine":"FILAMENT","version":1,"name":"Partial","voice":"$voice","macros":{"LENGTH":0.2},"future":true$optionals}"""
            val p = FilamentPatch.fromJsonText(text)
            assertEquals(Filament.rootMidi(voice), p.midi)
            assertEquals(1f, p.velocity)
            assertEquals(FilamentConfig(), p.config)
            assertEquals(1, p.model)
            assertEquals(mapOf("LENGTH" to .2f), p.macros)
        }
        val partialConfig = """{"engine":"FILAMENT","version":1,"name":"Partial","voice":"CURL","config":{"seed":29,"profile":null,"perfectRetention":null}}"""
        assertEquals(FilamentConfig(seed = 29), FilamentPatch.fromJsonText(partialConfig).config)
    }

    @Test
    fun `malformed and unsupported recipe identities and deterministic configuration are refused`() {
        val p = FilamentPatch("Valid", FilamentVoice.THREAD, Filament.defaults(FilamentVoice.THREAD))
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(p.toJsonValue().entries + (key to value))
        for ((key, value) in listOf("engine" to JsonValue.Str("TIDE"), "voice" to JsonValue.Str("MISSING"),
            "version" to JsonValue.Num(2.0), "model" to JsonValue.Num(99.0))) {
            assertFailsWith<JsonException>("accepted $key=$value") { FilamentPatch.fromJsonValue(changed(key, value)) }
        }
        for (config in listOf(JsonValue.Bool(true),
            JsonValue.Obj(mapOf("profile" to JsonValue.Str("UNKNOWN"))),
            JsonValue.Obj(mapOf("seed" to JsonValue.Num(1.5))),
            JsonValue.Obj(mapOf("perfectRetention" to JsonValue.Str("true"))))) {
            assertFailsWith<JsonException>("accepted malformed config $config") {
                FilamentPatch.fromJsonValue(changed("config", config))
            }
        }
        for (bad in listOf(-.00000001, 1.00000001)) {
            assertFailsWith<JsonException> { FilamentPatch.fromJsonValue(changed("velocity", JsonValue.Num(bad))) }
            assertFailsWith<JsonException> {
                FilamentPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("BEND" to JsonValue.Num(bad)))))
            }
        }
        assertFailsWith<JsonException> { FilamentPatch.fromJsonValue(changed("midi", JsonValue.Num(60.5))) }
    }

    @Test
    fun `invalid direct patches cannot hide out of range notes velocities or controls`() {
        val p = FilamentPatch("Valid", FilamentVoice.THREAD, Filament.defaults(FilamentVoice.THREAD))
        for (midi in listOf(Filament.MIN_MIDI - 1, Filament.MAX_MIDI + 1)) {
            assertFailsWith<IllegalArgumentException> { p.copy(midi = midi) }
        }
        for (bad in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { p.copy(velocity = bad) }
            assertFailsWith<IllegalArgumentException> { p.copy(macros = mapOf("BEND" to bad)) }
        }
        assertFailsWith<IllegalArgumentException> { p.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { p.copy(macros = mapOf("TUNE" to .5f)) }
        assertFailsWith<IllegalArgumentException> { p.copy(model = 99) }
    }

    @Test
    fun `native velocity routing preserves the saved note and complete strand configuration`() {
        for (voice in FilamentVoice.entries) {
            val patch = FilamentPatch("Velocity", voice, Filament.defaults(voice), midi = 67, velocity = .8f,
                config = FilamentConfig(seed = 17, profile = FilamentProfile.LATE))
            val actual = Velocity.atVelocity(patch, .25f)
            assertContentEquals(patch.copy(velocity = .25f).render().samples, actual.samples,
                "$voice native velocity ignored the saved deterministic configuration")
        }
    }

    @Test
    fun `provisional dry library remains reproducible and outside factory routing`() {
        val presets = FilamentPresets.all()
        assertEquals(12, presets.size)
        assertEquals(presets.size, presets.map { it.name }.distinct().size)
        assertTrue(Presets.all().none { it.engine == FilamentPatch.ENGINE })
        for (voice in FilamentVoice.entries) {
            val group = FilamentPresets.forVoice(voice)
            assertEquals(4, group.size)
            assertEquals(presets.filter { it.voice == voice }, group)
            assertTrue(group.any { Filament.isLoop(it.macros.getValue("HOLD")) }, "$voice has no held recipe")
            assertTrue(Presets.forVoice(FilamentPatch.ENGINE, voice.name).isEmpty())
        }
        for (p in presets) {
            assertEquals(Filament.defaults(p.voice).keys, p.macros.keys)
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name), p.name)
        }
    }
}
