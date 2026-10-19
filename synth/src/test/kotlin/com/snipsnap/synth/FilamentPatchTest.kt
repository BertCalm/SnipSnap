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
        for (model in listOf(1, 2)) for ((index, voice) in FilamentVoice.entries.withIndex()) {
            val config = FilamentConfig(seed = 719 + index, profile = FilamentProfile.entries[index + 1],
                perfectRetention = voice == FilamentVoice.THREAD)
            val macros = Filament.defaults(voice) + mapOf("INJECT" to .63f, "BEND" to .72f,
                "HOLD" to if (voice == FilamentVoice.PULSE) 1f else 0f)
            val original = FilamentPatch("Saved $voice", voice, macros, midi = 57, velocity = .65f, config = config, model = model)
            val restored = FilamentPatch.fromJsonText(original.toJsonText())
            assertEquals(original, restored)
            assertEquals(original, Patches.fromJsonText(original.toJsonText()))
            val replay = restored.render().samples
            assertContentEquals(original.render().samples, replay, "$voice recipe replay")
            assertContentEquals(Filament.render(voice, macros, midi = 57, velocity = .65f, config = config, model = model).samples,
                replay, "$voice patch must pass its saved model to the engine")
            assertEquals(setOf("INJECT", "BEND", "LENGTH", "BUNDLE", "COLLECT", "HOLD"), restored.macros.keys)
            val editedMacros = macros + ("LENGTH" to .81f)
            assertEquals(original.copy(name = "Edited", macros = editedMacros), Patches.edited(original, "Edited", editedMacros))
        }
    }

    @Test
    fun `new direct patches select model two while both saved models remain supported`() {
        val p = FilamentPatch("New", FilamentVoice.THREAD, Filament.defaults(FilamentVoice.THREAD))
        assertEquals(2, p.model)
        for (model in listOf(1, 2)) {
            val saved = p.copy(model = model)
            assertEquals(saved, FilamentPatch.fromJsonText(saved.toJsonText()))
            assertEquals(model, (Patches.edited(saved, "Edited", saved.macros + ("BEND" to .4f)) as FilamentPatch).model)
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
        for (model in listOf(1, 2)) for (voice in FilamentVoice.entries) {
            val patch = FilamentPatch("Velocity", voice, Filament.defaults(voice), midi = 67, velocity = .8f,
                config = FilamentConfig(seed = 17, profile = FilamentProfile.LATE), model = model)
            val actual = Velocity.atVelocity(patch, .25f)
            assertContentEquals(patch.copy(velocity = .25f).render().samples, actual.samples,
                "$voice model$model native velocity ignored the saved deterministic configuration")
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
            assertEquals(2, p.model)
            assertEquals(Filament.defaults(p.voice).keys, p.macros.keys)
            assertTrue(p.macros != Filament.defaults(p.voice), "${p.name} duplicates the voice defaults")
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name), p.name)
        }
        assertEquals(presets.size, presets.map { listOf(it.voice, it.macros, it.config) }.distinct().size)
    }

    @Test
    fun `original recipes retain their exact first listening snapshots independently of new defaults`() {
        val originals = FilamentPresets.original()
        val macroNames = listOf("INJECT", "BEND", "LENGTH", "BUNDLE", "COLLECT", "HOLD")
        // Frozen first-listen data, including names, control order, seed and profile.
        val expected = """
            THREAD|Glass Thread|0.25,0.15,0.25,0.0,0.2,0.0|0|VOICE
            THREAD|Narrow Arrival|0.12,0.12,0.18,0.0,0.1,0.0|0|VOICE
            THREAD|Four Routes|0.45,0.35,0.6,1.0,0.35,0.0|17|VOICE
            THREAD|Held Thread|0.25,0.3,0.4,0.35,0.4,1.0|0|VOICE
            PULSE|Pulse Passage|0.65,0.3,0.65,0.2,0.35,0.0|0|VOICE
            PULSE|Long Passage|0.75,0.4,0.95,0.35,0.35,0.0|0|LATE
            PULSE|Early Glints|0.65,0.7,0.65,0.25,0.8,0.0|0|EARLY
            PULSE|Held Procession|0.55,0.45,0.7,0.5,0.55,1.0|0|VOICE
            CURL|Loose Curl|0.45,0.65,0.45,0.15,0.5,0.0|0|VOICE
            CURL|Bend Glow|0.5,0.85,0.55,0.45,0.75,0.0|0|COIL
            CURL|Spilled Silver|0.75,0.9,0.8,0.9,0.9,0.0|29|LATE
            CURL|Held Curl|0.45,0.65,0.6,0.65,0.65,1.0|0|VOICE
        """.trimIndent().lines()
        val actual = originals.map { p ->
            assertEquals(1, p.model)
            assertEquals(48, p.midi)
            assertEquals(1f, p.velocity)
            assertEquals(false, p.config.perfectRetention)
            assertEquals(macroNames, p.macros.keys.toList())
            assertEquals(p, FilamentPatch.fromJsonText(p.toJsonText()))
            "${p.voice}|${p.name}|${macroNames.joinToString(",") { p.macros.getValue(it).toString() }}|${p.config.seed}|${p.config.profile}"
        }
        assertEquals(expected, actual)
        assertEquals(originals.map { it.name }, FilamentPresets.all().map { it.name }, "names identify comparable recipes across models")
    }
}
