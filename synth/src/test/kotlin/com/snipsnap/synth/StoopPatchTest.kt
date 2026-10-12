package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The saved recipe includes every independent source, answer and voicing field. */
class StoopPatchTest {
    private val patch = StoopPatch(
        "My Stoop", StoopVoice.TINE, Stoop.defaults(StoopVoice.TINE) + ("LENGTH" to .2f),
        midi = 55, velocity = .65f,
        source = StoopSource(shape = .32f, sub = .17f, fmRatio = 3f, fmIndex = 1.2f, harmonicTilt = .62f, detuneCents = -3f),
        shadowInterval = 7, shadowDelayMs = 155f, shadowArticulation = .18f, shadowReplies = 2,
        chordIntervals = listOf(0, 3, 7),
    )

    @Test
    fun `all families roundtrip the complete recipe through the shared dispatcher`() {
        for (voice in StoopVoice.entries) {
            val original = patch.copy(voice = voice, macros = Stoop.defaults(voice))
            val text = original.toJsonText()
            val restored = StoopPatch.fromJsonText(text)
            assertEquals(original, restored)
            assertEquals(original, Patches.fromJsonText(text))
            assertEquals(text, restored.toJsonText())
        }
        assertContentEquals(patch.render().samples, Patches.fromJsonText(patch.toJsonText()).render().samples)
        assertEquals(setOf("shape", "sub", "fmRatio", "fmIndex", "harmonicTilt", "detuneCents"),
            patch.toJsonValue().entries.getValue("source").obj().keys)
        assertTrue(patch.toJsonValue().entries.keys.containsAll(setOf("midi", "velocity", "model", "shadowInterval", "shadowDelayMs", "shadowArticulation", "shadowReplies", "chordIntervals")))
    }

    @Test
    fun `macro and name editing retain pitch source answers and chord voicing`() {
        val macros = patch.macros + ("TOUCH" to .82f) + ("WEAR" to .12f)
        assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
        val edited = Patches.edited(patch, "My Reply", macros)
        assertEquals(patch.copy(name = "My Reply", macros = macros), edited)
        assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
    }

    @Test
    fun `audition chord metadata describes the actual modified voicing`() {
        val factory = StoopPresets.forVoice(StoopVoice.CHORD).first()
        for ((intervals, description) in listOf(
            listOf(0, 4, 7) to "Major triad: root, major third, fifth",
            listOf(0, 3, 7) to "Minor triad: root, minor third, fifth",
            listOf(0) to "Root only",
        )) {
            val metadata = assertNotNull(StoopPresets.metadataFor(factory.copy(chordIntervals = intervals)))
            assertEquals(intervals, metadata.intervalRecipe)
            assertEquals(description, metadata.intervalDescription)
        }
    }

    @Test
    fun `omitted optional fields and explicit nulls use voice source defaults`() {
        for (voice in StoopVoice.entries) {
            for (optional in listOf("", "\"midi\":null,\"velocity\":null,\"model\":null,\"source\":null,\"shadowInterval\":null,\"shadowDelayMs\":null,\"shadowArticulation\":null,\"shadowReplies\":null,\"chordIntervals\":null,")) {
                val restored = StoopPatch.fromJsonText("""{"engine":"STOOP","version":1,"name":"Default","voice":"${voice.name}",$optional"macros":{"TOUCH":0.6},"futureField":"ignored"}""")
                assertEquals(StoopPatch("Default", voice, mapOf("TOUCH" to .6f)), restored)
            }
        }
        val restored = StoopPatch.fromJsonText("""{"engine":"STOOP","version":1,"name":"Partial","voice":"TINE","macros":{},"source":{"fmRatio":3}}""")
        assertEquals(Stoop.defaultSource(StoopVoice.TINE).copy(fmRatio = 3f), restored.source)
    }

    private fun changed(field: String, value: JsonValue) = JsonValue.Obj(
        LinkedHashMap(patch.toJsonValue().entries).also { it[field] = value })

    @Test
    fun `unsupported formats and malformed field types are refused`() {
        for ((field, value) in listOf(
            "engine" to JsonValue.Str("FLOTILLA"), "voice" to JsonValue.Str("KICK"),
            "version" to JsonValue.Num(2.0), "model" to JsonValue.Num(2.0),
            "midi" to JsonValue.Num(48.5), "velocity" to JsonValue.Str("loud"),
            "source" to JsonValue.Bool(true), "shadowInterval" to JsonValue.Num(7.5),
            "shadowReplies" to JsonValue.Num(1.5), "chordIntervals" to JsonValue.Str("major"),
            "macros" to JsonValue.Arr(emptyList()),
        )) assertFailsWith<JsonException>("accepted malformed $field") { StoopPatch.fromJsonValue(changed(field, value)) }
        assertFailsWith<JsonException> {
            StoopPatch.fromJsonValue(changed("source", JsonValue.Obj(mapOf("shape" to JsonValue.Str("round")))))
        }
        assertFailsWith<JsonException> {
            StoopPatch.fromJsonValue(changed("chordIntervals", JsonValue.Arr(listOf(JsonValue.Num(0.0), JsonValue.Num(3.5)))))
        }
    }

    @Test
    fun `invalid gestures sources replies voicings and macros are refused`() {
        for (midi in listOf(Stoop.MIDI_MIN - 1, Stoop.MIDI_MAX + 1)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        }
        for (value in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
            assertFailsWith<IllegalArgumentException> { patch.copy(shadowArticulation = value) }
            assertFailsWith<IllegalArgumentException> { patch.copy(source = patch.source.copy(shape = value)) }
            assertFailsWith<IllegalArgumentException> { patch.copy(source = patch.source.copy(sub = value)) }
            assertFailsWith<IllegalArgumentException> { patch.copy(source = patch.source.copy(harmonicTilt = value)) }
        }
        for (value in listOf(.24f, 8.1f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(source = patch.source.copy(fmRatio = value)) }
        }
        for (value in listOf(-.1f, 8.1f, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(source = patch.source.copy(fmIndex = value)) }
        }
        for (value in listOf(-20.1f, 20.1f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(source = patch.source.copy(detuneCents = value)) }
        }
        for (delay in listOf(24.9f, 250.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(shadowDelayMs = delay) }
        }
        for (interval in listOf(-25, 25)) assertFailsWith<IllegalArgumentException> { patch.copy(shadowInterval = interval) }
        for (replies in listOf(0, 3)) assertFailsWith<IllegalArgumentException> { patch.copy(shadowReplies = replies) }
        for (intervals in listOf(emptyList(), listOf(3, 7), listOf(0, 7, 7), listOf(0, 4, 7, 12, 16), listOf(0, -25), listOf(0, 25))) {
            assertFailsWith<IllegalArgumentException> { patch.copy(chordIntervals = intervals) }
        }
        for (macros in listOf(mapOf("TUNE" to .5f), mapOf("COLOR" to -.1f), mapOf("TOUCH" to 1.1f), mapOf("WEAR" to Float.NaN))) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = macros) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 0) }
        assertFailsWith<IllegalArgumentException> { StoopPatch.fromJsonValue(changed("source", JsonValue.Obj(mapOf("fmIndex" to JsonValue.Num(1e100))))) }
        assertFailsWith<IllegalArgumentException> { StoopPatch.fromJsonValue(changed("shadowDelayMs", JsonValue.Num(500.0))) }
    }

    @Test
    fun `factory coverage has distinct sources documented voicings and melodic routing`() {
        val expected = mapOf(StoopVoice.BASS to 16, StoopVoice.TINE to 10, StoopVoice.CLAV to 8, StoopVoice.ORGAN to 6, StoopVoice.LEAD to 4, StoopVoice.CHORD to 4)
        val all = StoopPresets.all()
        assertEquals(48, all.size)
        assertEquals(48, all.map { it.name }.toSet().size)
        assertTrue(Presets.all().containsAll(all))
        for ((voice, count) in expected) {
            val group = StoopPresets.forVoice(voice)
            assertEquals(count, group.size)
            assertEquals(group, Presets.forVoice(StoopPatch.ENGINE, voice.name))
            assertTrue(group.map { it.source }.toSet().size >= minOf(4, count), "$voice needs different source registrations")
            for (preset in group) {
                assertTrue(preset.name.length <= 14)
                assertEquals(setOf("TOUCH", "COLOR", "SHADOW", "WEAR", "LENGTH", "HOLD"), preset.macros.keys)
                assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
                val metadata = assertNotNull(StoopPresets.metadataFor(preset))
                assertTrue(preset.midi in metadata.intendedRegister)
                assertTrue(metadata.articulationTags.isNotEmpty() && metadata.description.isNotBlank())
                assertEquals(if (voice == StoopVoice.CHORD) preset.chordIntervals else listOf(0), metadata.intervalRecipe)
                val expectedClass = if (Stoop.isLoop(preset.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
                assertEquals(expectedClass, Stoop.drumClassFor(voice, preset.macros))
            }
        }
        assertEquals(setOf(listOf(0, 7, 12), listOf(0, 4, 7), listOf(0, 3, 7), listOf(0, 7, 14)),
            StoopPresets.forVoice(StoopVoice.CHORD).map { it.chordIntervals }.toSet())
        assertTrue(all.any { it.macros.getValue("WEAR") == 0f && it.macros.getValue("SHADOW") == 0f })
        assertTrue(all.any { it.macros.getValue("WEAR") >= .65f })
        assertTrue(all.any { it.macros.getValue("SHADOW") >= .6f })
        assertTrue(all.any { it.shadowReplies == 2 })
        assertTrue(all.any { Stoop.isLoop(it.macros.getValue("HOLD")) })
    }
}
