package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenSkyPatchTest {
    private fun custom(voice: OpenSkyVoice = OpenSkyVoice.SOAR) = OpenSkyPatch(
        "Saved Sky", voice, mapOf("RISE" to .31f, "WIDTH" to .68f, "GLOW" to .18f, "AIR" to .57f),
        midi = 57, velocity = .63f, gateSeconds = .27f, wet = true,
        timbre = OpenSkyTimbre(
            pulseMix = .42f, pulseWidth = .36f, sineMix = .29f, octaveMix = .24f,
            centerWeightScale = 1.7f, detuneScale = .83f, toneScale = 1.21f,
            attackScale = .72f, sustainLevel = .76f, decaySeconds = .21f,
            releaseScale = .57f, bellBrightnessScale = 1.43f, bellDecayScale = 1.61f,
        ),
    )

    private fun changed(patch: OpenSkyPatch, field: String, value: JsonValue) =
        JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also { it[field] = value })

    @Test
    fun `full source articulation note wet state and model survive dispatch saving and macro editing`() {
        for (voice in OpenSkyVoice.entries) {
            val source = custom(voice)
            val restored = assertIs<OpenSkyPatch>(Patches.fromJsonText(source.toJsonText()))
            assertEquals(source, restored, "$voice loses part of its saved source recipe")
            assertEquals(source.toJsonText(), restored.toJsonText())
            assertEquals(source, OpenSkyPatch.fromJsonText(source.toJsonText()))
            val encoded = restored.toJsonValue().entries
            assertEquals("OPENSKY", encoded.getValue("engine").str())
            assertEquals(1, encoded.getValue("version").int())
            assertEquals(OpenSky.MODEL_VERSION, encoded.getValue("model").int())
            assertEquals(TIMBRE_FIELDS, encoded.getValue("timbre").obj().keys)

            val editedMacros = source.macros + mapOf("AIR" to .83f, "WIDTH" to .12f)
            assertEquals(source.copy(macros = editedMacros), source.withMacros(editedMacros))
            assertEquals(source.copy(name = "Edited Sky", macros = editedMacros),
                Patches.edited(source, "Edited Sky", editedMacros))
            assertEquals(.27f, source.gateSeconds, "editing mutated the source patch")

            if (voice == OpenSkyVoice.SOAR) {
                val original = source.render()
                assertContentEquals(original.samples, restored.render().samples)
                assertContentEquals(original.samples, OpenSky.render(source.voice, source.macros, source.midi,
                    source.velocity, source.gateSeconds, source.wet, timbre = source.timbre).samples)
                assertFalse(original.samples.contentEquals(source.copy(timbre = OpenSkyTimbre()).render().samples),
                    "stored source and envelope changes never reach the renderer")
            }
        }
    }

    @Test
    fun `missing saved fields and partial timbre objects refuse to invent a replacement sound`() {
        val source = custom()
        for (field in listOf("engine", "version", "name", "voice", "macros", "model", "midi",
            "velocity", "gateSeconds", "wet", "timbre")) {
            val missing = JsonValue.Obj(LinkedHashMap(source.toJsonValue().entries).also { it.remove(field) })
            assertFailsWith<JsonException>("missing $field accepted") { OpenSkyPatch.fromJsonValue(missing) }
            assertFailsWith<JsonException>("null $field accepted") {
                OpenSkyPatch.fromJsonValue(changed(source, field, JsonValue.Null))
            }
        }
        val timbre = source.toJsonValue().entries.getValue("timbre").obj()
        for (field in TIMBRE_FIELDS) {
            val missing = JsonValue.Obj(LinkedHashMap(timbre).also { it.remove(field) })
            val nullValue = JsonValue.Obj(LinkedHashMap(timbre).also { it[field] = JsonValue.Null })
            assertFailsWith<JsonException>("missing timbre.$field accepted") {
                OpenSkyPatch.fromJsonValue(changed(source, "timbre", missing))
            }
            assertFailsWith<JsonException>("null timbre.$field accepted") {
                OpenSkyPatch.fromJsonValue(changed(source, "timbre", nullValue))
            }
        }
        val unknown = JsonValue.Obj(timbre + ("unknownSource" to JsonValue.Num(.5)))
        assertFailsWith<JsonException>("unknown timbre source silently discarded") {
            OpenSkyPatch.fromJsonValue(changed(source, "timbre", unknown))
        }
        val macros = source.toJsonValue().entries.getValue("macros").obj()
        for (field in macros.keys) {
            val missing = JsonValue.Obj(macros - field)
            assertFailsWith<JsonException>("missing macro $field invents a new saved control value") {
                OpenSkyPatch.fromJsonValue(changed(source, "macros", missing))
            }
        }
    }

    @Test
    fun `unsupported tags fractions wrong types and unbounded decoded numbers are rejected`() {
        val source = custom()
        val invalid = listOf(
            "engine" to JsonValue.Str("FATHOM"),
            "voice" to JsonValue.Str("KICK"),
            "version" to JsonValue.Num(2.0),
            "version" to JsonValue.Num(1.5),
            "model" to JsonValue.Num((OpenSky.MODEL_VERSION + 1).toDouble()),
            "model" to JsonValue.Num(1.5),
            "midi" to JsonValue.Num(60.5),
            "midi" to JsonValue.Num(-1.0),
            "midi" to JsonValue.Num(128.0),
            "midi" to JsonValue.Num(Double.POSITIVE_INFINITY),
            "velocity" to JsonValue.Num(-.01),
            "velocity" to JsonValue.Num(1.01),
            "velocity" to JsonValue.Num(Double.NaN),
            "velocity" to JsonValue.Num(Double.POSITIVE_INFINITY),
            "velocity" to JsonValue.Num(1e300),
            "gateSeconds" to JsonValue.Num(.01),
            "gateSeconds" to JsonValue.Num(8.01),
            "gateSeconds" to JsonValue.Num(Double.NaN),
            "gateSeconds" to JsonValue.Num(Double.NEGATIVE_INFINITY),
            "wet" to JsonValue.Num(1.0),
            "timbre" to JsonValue.Arr(emptyList()),
            "macros" to JsonValue.Obj(mapOf("REVERB" to JsonValue.Num(.5))),
            "macros" to JsonValue.Obj(mapOf("AIR" to JsonValue.Num(1.1))),
            "macros" to JsonValue.Obj(mapOf("WIDTH" to JsonValue.Num(Double.NaN))),
        )
        for ((field, value) in invalid) {
            assertFailsWith<JsonException>("$field = $value accepted") {
                OpenSkyPatch.fromJsonValue(changed(source, field, value))
            }
        }
        val sourceTimbre = source.toJsonValue().entries.getValue("timbre").obj()
        for (field in TIMBRE_FIELDS) for (value in listOf(JsonValue.Num(Double.NaN),
            JsonValue.Num(Double.POSITIVE_INFINITY), JsonValue.Num(1e300), JsonValue.Str("0.5"))) {
            val timbre = JsonValue.Obj(LinkedHashMap(sourceTimbre).also { it[field] = value })
            assertFailsWith<JsonException>("timbre.$field = $value accepted") {
                OpenSkyPatch.fromJsonValue(changed(source, "timbre", timbre))
            }
        }
        val invalidTimbre = mapOf("pulseMix" to 1.1, "pulseWidth" to .1, "sineMix" to -.1,
            "octaveMix" to .9, "centerWeightScale" to .1, "detuneScale" to .1,
            "toneScale" to 2.1, "attackScale" to 6.0, "sustainLevel" to .1,
            "decaySeconds" to 0.0, "releaseScale" to 4.0, "bellBrightnessScale" to .1,
            "bellDecayScale" to 4.0)
        for ((field, value) in invalidTimbre) {
            val timbre = JsonValue.Obj(LinkedHashMap(sourceTimbre).also { it[field] = JsonValue.Num(value) })
            assertFailsWith<JsonException>("timbre.$field out of range accepted") {
                OpenSkyPatch.fromJsonValue(changed(source, "timbre", timbre))
            }
        }
    }

    @Test
    fun `in memory saved recipes enforce the same note gate velocity macro and model bounds`() {
        val source = custom()
        assertFailsWith<IllegalArgumentException> { source.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { source.copy(model = OpenSky.MODEL_VERSION + 1) }
        for (midi in listOf(-1, 128)) assertFailsWith<IllegalArgumentException> { source.copy(midi = midi) }
        for (velocity in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { source.copy(velocity = velocity) }
        }
        for (gate in listOf(.01f, 8.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { source.copy(gateSeconds = gate) }
        }
        for (macros in listOf(mapOf("TUNE" to .5f), mapOf("RISE" to -.01f),
            mapOf("AIR" to 1.01f), mapOf("GLOW" to Float.NaN), source.macros - "WIDTH")) {
            assertFailsWith<IllegalArgumentException> { source.copy(macros = macros) }
        }
        // Endpoints are useful saved recipes, including silence and the extremes of MIDI.
        assertEquals(0f, source.copy(midi = 0, velocity = 0f, gateSeconds = .05f).velocity)
        assertEquals(127, source.copy(midi = 127, velocity = 1f, gateSeconds = 8f).midi)
    }

    @Test
    fun `partial macro replacement snapshots engine defaults while retaining the custom source and note state`() {
        val source = custom().copy(wet = false)
        val partial = mapOf("AIR" to .8f)
        val expected = source.copy(macros = OpenSky.defaults(source.voice) + partial)
        val replaced = source.withMacros(partial)
        assertEquals(expected, replaced)
        assertEquals(OpenSky.defaults(source.voice).getValue("WIDTH"), replaced.macros.getValue("WIDTH"))
        assertFalse(source.macros.getValue("WIDTH") == replaced.macros.getValue("WIDTH"),
            "withMacros retained the old omitted control rather than applying its replacement semantics")
        assertEquals(expected.copy(name = "Partial Edit"), Patches.edited(source, "Partial Edit", partial))
        assertEquals(expected, Patches.fromJsonText(replaced.toJsonText()))
        assertContentEquals(OpenSky.render(source.voice, expected.macros, source.midi, source.velocity,
            source.gateSeconds, source.wet, timbre = source.timbre).samples, replaced.render().samples)
        for (invalid in listOf(mapOf("REVERB" to .4f), mapOf("AIR" to 1.1f), mapOf("WIDTH" to Float.NaN))) {
            assertFailsWith<IllegalArgumentException> { source.withMacros(invalid) }
        }
    }

    @Test
    fun `cancelled patch rendering stops promptly and leaves the saved recipe untouched`() {
        val source = custom().copy(gateSeconds = 8f)
        val before = source.toJsonText()
        assertFailsWith<CancellationException> { source.render(cancelled = { true }) }
        var checks = 0
        assertFailsWith<CancellationException> { source.render(cancelled = { ++checks >= 3 }) }
        assertEquals(3, checks, "cancellation waits past the next render checkpoint")
        assertEquals(before, source.toJsonText())
        assertTrue(source.copy(gateSeconds = .05f, wet = false).render().samples.isNotEmpty())
    }

    private companion object {
        val TIMBRE_FIELDS = setOf("pulseMix", "pulseWidth", "sineMix", "octaveMix", "centerWeightScale",
            "detuneScale", "toneScale", "attackScale", "sustainLevel", "decaySeconds", "releaseScale",
            "bellBrightnessScale", "bellDecayScale")
    }
}
