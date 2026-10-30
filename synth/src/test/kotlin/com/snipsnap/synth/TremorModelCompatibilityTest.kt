package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Saved recipes and velocity layers select the acoustics the recipe was authored with. */
class TremorModelCompatibilityTest {
    @Test
    fun `new patches and factory presets select the current model while both models roundtrip`() {
        assertEquals(2, Tremor.MODEL)
        assertEquals(Tremor.MODEL, TremorPatch("New drum", TremorVoice.HIDE, emptyMap()).model)
        for (voice in TremorVoice.entries) for (model in Tremor.LEGACY_MODEL..Tremor.MODEL) {
            val original = TremorPatch("Saved $voice", voice, mapOf("STRIKE" to .6f, "HOLD" to .2f), model)
            val json = original.toJsonText()
            assertEquals(model, original.toJsonValue().entries.getValue("model").int())
            assertEquals(original, TremorPatch.fromJsonText(json))
            assertEquals(original, Patches.fromJsonText(json))
            assertEquals(json, Patches.fromJsonText(json).toJsonText())
        }
        assertTrue(TremorPresets.all().isNotEmpty())
        assertTrue(TremorPresets.all().all { it.model == Tremor.MODEL })
    }

    @Test
    fun `omitted or null model restores the legacy sound and writes its selection explicitly`() {
        // This is the published model-one HIDE buffer at default macros and full touch.
        // It pins the saved file's sound independently of the current renderer's defaults.
        val expectedHash = "0cd7838fbc58e827f495d8022c262583e752edc61aa595504c58eac9f457799f"
        for (modelField in listOf("", "\"model\":null,")) {
            val oldJson = """{"engine":"TREMOR","version":1,"name":"Old hide","voice":"HIDE",$modelField"macros":{},"extra":"ignored"}"""
            val restored = TremorPatch.fromJsonText(oldJson)
            assertEquals(Tremor.LEGACY_MODEL, restored.model)
            assertTrue(restored.macros.isEmpty(), "a partial saved macro map must remain partial")
            assertEquals(expectedHash, sampleHash(restored.render().samples), "old JSON selected different acoustics")
            assertEquals(Tremor.LEGACY_MODEL, restored.toJsonValue().entries.getValue("model").int())
            assertEquals(restored, Patches.fromJsonText(restored.toJsonText()))
            val current = restored.copy(model = Tremor.MODEL)
            assertTrue(!restored.render().samples.contentEquals(current.render().samples),
                "new and legacy recipes still select the same renderer")
        }
    }

    @Test
    fun `common edits preserve the saved model and its partial macros`() {
        val macros = mapOf("STRIKE" to .7f, "CAGE" to .8f)
        for (model in Tremor.LEGACY_MODEL..Tremor.MODEL) {
            val original = TremorPatch("Saved wire", TremorVoice.WIRE, mapOf("CAGE" to .3f), model)
            val withMacros = original.withMacros(macros)
            assertEquals(original.copy(macros = macros), withMacros)
            val edited = Patches.edited(original, "Renamed wire", macros)
            assertEquals(original.copy(name = "Renamed wire", macros = macros), edited)
            assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
        }
    }

    @Test
    fun `patch rendering and native velocity keep each saved model for finite and held recipes`() {
        for (voice in listOf(TremorVoice.HIDE, TremorVoice.WIRE)) for (hold in listOf(0f, 1f)) {
            val macros = Tremor.defaults(voice) + ("HOLD" to hold)
            val hardByModel = mutableListOf<FloatArray>()
            for (model in Tremor.LEGACY_MODEL..Tremor.MODEL) {
                val patch = TremorPatch("Saved $voice", voice, macros, model)
                val hard = patch.render().samples
                hardByModel += hard
                assertContentEquals(Tremor.render(voice, macros, model = model).samples, hard,
                    "$voice HOLD $hold model $model patch render lost its saved model")
                for (velocity in listOf(.2f, .55f, 1f)) {
                    assertContentEquals(
                        Tremor.render(voice, macros, velocity = velocity, model = model).samples,
                        Velocity.atVelocity(patch, velocity).samples,
                        "$voice HOLD $hold velocity $velocity lost model $model",
                    )
                }
            }
            assertTrue(!hardByModel[0].contentEquals(hardByModel[1]), "$voice HOLD $hold models select identical audio")
        }
    }

    @Test
    fun `unsupported and malformed model fields fail rather than select a different sound`() {
        val patch = TremorPatch("Saved hide", TremorVoice.HIDE, emptyMap())
        fun changed(value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).apply { put("model", value) },
        )
        for (model in listOf(0, Tremor.MODEL + 1)) {
            val error = assertFailsWith<JsonException> { TremorPatch.fromJsonValue(changed(JsonValue.Num(model.toDouble()))) }
            assertTrue(error.message.orEmpty().contains("unsupported tremor model $model"))
            assertFailsWith<IllegalArgumentException> { patch.copy(model = model) }
        }
        for (value in listOf(JsonValue.Num(1.5), JsonValue.Str("1"), JsonValue.Bool(true), JsonValue.Arr(emptyList()))) {
            assertFailsWith<JsonException> { Patches.fromJsonValue(changed(value)) }
        }
    }

    private fun sampleHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4)
        for (sample in samples) bytes.putFloat(sample)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
