package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Native persistence/routing checks; DSP and held-state behavior live in [CreaseTest]. */
class CreaseIntegrationTest {
    @Test
    fun `native patch JSON preserves every model root velocity and a partial macro map`() {
        for (voice in CreaseVoice.entries) for (model in 1..3) {
            val patch = CreasePatch("Saved $voice", voice, mapOf("FOLD" to .37f),
                midi = 55, velocity = .63f, model = model)
            val fields = patch.toJsonValue().entries
            assertEquals("CREASE", fields.getValue("engine").str())
            assertEquals(1, fields.getValue("version").int(), "patch envelope version is separate from the render model")
            assertEquals(model, fields.getValue("model").int())
            assertEquals(55, fields.getValue("midi").int())
            assertEquals(.63f, fields.getValue("velocity").num().toFloat())
            assertEquals(setOf("FOLD"), fields.getValue("macros").obj().keys)
            assertEquals(patch, CreasePatch.fromJsonText(patch.toJsonText()))
            assertEquals(patch, assertIs<CreasePatch>(Patches.fromJsonText(patch.toJsonText())))
        }
    }

    @Test
    fun `native patches without performance fields start at the native defaults`() {
        val source = CreasePatch("Partial", CreaseVoice.PINCH, mapOf("PRESSURE" to .42f),
            midi = 67, velocity = .35f, model = 1)
        val fields = source.toJsonValue().entries - setOf("model", "midi", "velocity")
        for (nativeFields in listOf(fields, fields + mapOf(
            "model" to JsonValue.Null, "midi" to JsonValue.Null, "velocity" to JsonValue.Null))) {
            val restored = assertIs<CreasePatch>(Patches.fromJsonValue(JsonValue.Obj(nativeFields)))
            assertEquals(Crease.MODEL_VERSION, restored.model)
            assertEquals(Crease.DEFAULT_MIDI, restored.midi)
            assertEquals(.75f, restored.velocity)
            assertEquals(source.macros, restored.macros)
        }
        for (empty in listOf(fields - "macros", fields + ("macros" to JsonValue.Null))) {
            assertEquals(emptyMap(), CreasePatch.fromJsonValue(JsonValue.Obj(empty)).macros)
        }
        // Standalone audition documents use schemaVersion, not the native Patch envelope.
        val auditionEnvelope = (fields - "version") + ("schemaVersion" to JsonValue.Num(3.0))
        assertFailsWith<JsonException> { Patches.fromJsonValue(JsonValue.Obj(auditionEnvelope)) }
    }

    @Test
    fun `native constructors reject invalid public performance and macro values`() {
        val patch = CreasePatch("Valid", CreaseVoice.ROUND, emptyMap())
        for (model in listOf(0, 4)) refused<IllegalArgumentException>("model") { patch.copy(model = model) }
        for (midi in listOf(23, 85)) refused<IllegalArgumentException>("MIDI") { patch.copy(midi = midi) }
        for (velocity in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            refused<IllegalArgumentException>("velocity") { patch.copy(velocity = velocity) }
        }
        refused<IllegalArgumentException>("blank") { patch.copy(name = " ") }
        refused<IllegalArgumentException>("unknown macro TUNE") { patch.copy(macros = mapOf("TUNE" to .5f)) }
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            refused<IllegalArgumentException>("macro FOLD") { patch.copy(macros = mapOf("FOLD" to value)) }
        }
    }

    @Test
    fun `native decoder refuses malformed or unsupported fields at the appropriate guard`() {
        val fields = CreasePatch("Valid", CreaseVoice.ROUND, emptyMap()).toJsonValue().entries
        val invalid = listOf(
            Triple("model", JsonValue.Num(4.0), "model"),
            Triple("model", JsonValue.Num(2.5), "integer"),
            Triple("model", JsonValue.Str("3"), "number"),
            Triple("midi", JsonValue.Num(23.0), "MIDI"),
            Triple("midi", JsonValue.Num(85.0), "MIDI"),
            Triple("midi", JsonValue.Num(55.5), "integer"),
            Triple("velocity", JsonValue.Num(-.1), "velocity"),
            Triple("velocity", JsonValue.Num(1.1), "velocity"),
            Triple("velocity", JsonValue.Num(Double.NaN), "velocity"),
            Triple("velocity", JsonValue.Num(Double.POSITIVE_INFINITY), "velocity"),
            Triple("velocity", JsonValue.Bool(true), "number"),
            Triple("version", JsonValue.Num(3.0), "patch version"),
            Triple("engine", JsonValue.Str("TANGLE"), "not a CREASE patch"),
            Triple("voice", JsonValue.Str("UNKNOWN"), "voice"),
            Triple("macros", JsonValue.Arr(emptyList()), "macros must be an object"),
            Triple("macros", JsonValue.Obj(mapOf("NOPE" to JsonValue.Num(.5))), "unknown macro"),
            Triple("macros", JsonValue.Obj(mapOf("FOLD" to JsonValue.Num(1.1))), "macro FOLD"),
            Triple("macros", JsonValue.Obj(mapOf("FOLD" to JsonValue.Str(".5"))), "number"),
        )
        for ((field, value, reason) in invalid) {
            refused<JsonException>(reason) { CreasePatch.fromJsonValue(JsonValue.Obj(fields + (field to value))) }
        }
    }

    @Test
    fun `macro edits and pad recipes retain the explicit note excitation and historical model`() {
        val source = CreasePatch("Low Reed", CreaseVoice.PINCH, mapOf("FOLD" to .35f),
            midi = 43, velocity = .54f, model = 2)
        val moved = mapOf("PRESSURE" to .71f, "RELAX" to .62f)
        val typed = source.withMacros(moved)
        assertEquals(source.copy(macros = moved), typed)
        val edited = assertIs<CreasePatch>(Patches.edited(source, "Moved Reed", moved))
        assertEquals(source.copy(name = "Moved Reed", macros = moved), edited)
        val saved = PadRecipe(edited)
        val restored = PadRecipe.fromJsonText(saved.toJsonText())
        assertEquals(saved, restored)
        assertNull(restored.fx, "factory CREASE patches land dry")
        assertContentEquals(edited.render().samples, restored.render().samples)
    }

    @Test
    fun `the native six preset bank retains the exact approved R3 sounds through JSON`() {
        // Native normalized Float32 hashes from the approved baf449c R3 listening manifest.
        // Matched audition WAV gain is deliberately outside this native render contract.
        val approved = mapOf(
            "Corner Bass" to "61a8f7e57f79b497b69162a49482796d3f67baa11fb13e50a2467c6a437c5848",
            "Paper Keys" to "e5115373f1e157a9aceb8f78893a4d560eddf884fcee7eb8fd0765e230381a7e",
            "Reed Mark" to "10b38495aa17ef4e1c2430dbf848530be5c076f38faef33e6bfcfcaff7511b8d",
            "Return Stroke" to "2b2ecb19569d7971078d40c7473531d7fbab7ba3176946fc053374ef018bf5b1",
            "Late Mark" to "e6700be0a4db9f32ee7750819e38de859da4dace70709b482626bc97850a9a04",
            "Ink Ribbon" to "8d2831c9ea2e7825ed0b42f07f69c57bca2605dbce364c7bd725b5f87beb64ee",
        )
        val native = CreasePresets.patches()
        assertEquals(approved.keys, native.map { it.name }.toSet())
        assertEquals(6, native.size, "only the six approved recipes enter the factory roster")
        assertEquals(native, Presets.all().filter { it.engine == "CREASE" })
        for (voice in CreaseVoice.entries) {
            assertEquals(2, CreasePresets.patchesForVoice(voice).size)
            assertEquals(CreasePresets.patchesForVoice(voice), Presets.forVoice("CREASE", voice.name))
        }
        for (patch in native) {
            val audition = CreasePresets.all().single { it.name == patch.name }
            assertEquals(audition.voice, patch.voice)
            assertEquals(audition.macros, patch.macros)
            assertEquals(audition.midi, patch.midi)
            assertEquals(audition.velocity, patch.velocity)
            assertEquals(audition.model, patch.model)
            assertEquals(3, patch.model)
            assertEquals(Crease.defaults(patch.voice, 3).keys, patch.macros.keys)
            assertEquals(patch, Presets.byName("CREASE", patch.voiceName, patch.name))
            assertNull(Presets.landingFor("CREASE", patch.voiceName, patch.macros))
            val restored = assertIs<CreasePatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, restored)
            assertEquals(approved.getValue(patch.name), hash(restored.render().samples), patch.name)
        }
        assertTrue(Presets.forVoice("CREASE", "UNKNOWN").isEmpty())
        assertNull(Presets.byName("CREASE", "ROUND", "UNKNOWN"))
    }

    @Test
    fun `velocity routes native excitation while retaining root model and timbral controls`() {
        for (model in 1..3) {
            val patch = CreasePatch("Native $model", CreaseVoice.PINCH,
                mapOf("FOLD" to .62f, "PRESSURE" to .8f, "DRAG" to .6f, "SPLIT" to .65f, "RELAX" to .7f),
                midi = 55, velocity = .9f, model = model)
            val expected = Crease.render(patch.voice, patch.macros, patch.midi, .35f, model).samples
            val rendered = Velocity.atVelocity(patch, .35f).samples
            assertContentEquals(expected, rendered, "model$model native velocity")
            assertContentEquals(expected, Velocity.atVelocity(patch, .35f, spec = null).samples,
                "model$model cached-spec path must also use native velocity")
            val hard = patch.render()
            assertFalse(rendered.contentEquals(hard.samples), "velocity did not reach the engine")
            assertFalse(rendered.contentEquals(Velocity.soften(hard, .65f).samples), "native velocity became post-filtering")
            val foldSpent = patch.withMacros(patch.macros + ("FOLD" to patch.macros.getValue("FOLD") * .35f)).render()
            assertFalse(rendered.contentEquals(foldSpent.samples), "velocity was spent on FOLD")
            assertFalse(rendered.contentEquals(Crease.render(patch.voice, patch.macros, 48, .35f, model).samples),
                "the saved root was reset")
        }
    }

    @Test
    fun `native velocity zero silences a held patch rather than filtering its saved excitation`() {
        val patch = CreasePatch("Held", CreaseVoice.ROUND, mapOf("HOLD" to 1f), midi = 55, velocity = .8f, model = 3)
        val silent = Velocity.atVelocity(patch, 0f)
        assertTrue(silent.samples.isNotEmpty() && silent.samples.all { it == 0f })
        assertEquals(1, silent.channels)
        assertEquals(Crease.RATE, silent.sampleRate)
        assertEquals(.8f, patch.velocity, "zone rendering must not mutate the saved performance")
    }

    @Test
    fun `native placement follows HOLD rather than finite note length for every model`() {
        for (model in 1..3) for (voice in CreaseVoice.entries) {
            assertEquals(DrumClass.TONAL, Crease.drumClassFor(voice, model = model))
            assertEquals(DrumClass.TONAL, Crease.drumClassFor(voice, mapOf("HOLD" to .98f, "RELAX" to 1f), model))
            assertEquals(DrumClass.LOOP, Crease.drumClassFor(voice, mapOf("HOLD" to Crease.HOLD_LOOP), model))
            assertEquals(DrumClass.LOOP, Crease.drumClassFor(voice, mapOf("HOLD" to 1f), model))
        }
        for (patch in CreasePresets.patches()) {
            assertEquals(DrumClass.TONAL, Crease.drumClassFor(patch.voice, patch.macros, patch.model), patch.name)
        }
    }

    @Test
    fun `scrambling changes only complete finite timbral recipes while typed copies retain performance`() {
        for (model in 1..3) {
            val source = CreasePatch("Saved", CreaseVoice.RUBBER, mapOf("FOLD" to .5f, "HOLD" to 1f),
                midi = 43, velocity = .61f, model = model)
            val macros = Crease.scramble(source.voice, Random(718), temperature = .8f, near = source)
            assertEquals(macros, Crease.scramble(source.voice, Random(718), temperature = .8f, near = source))
            assertEquals(Crease.defaults(source.voice, model).keys, macros.keys)
            assertTrue(macros.values.all { it.isFinite() && it in 0f..1f })
            assertTrue(macros.getValue("HOLD") < Crease.HOLD_LOOP, "a random press must not choose a loop")
            assertEquals(source.copy(macros = macros), source.withMacros(macros))
        }
    }

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private inline fun <reified T : Throwable> refused(reason: String, block: () -> Unit) {
        val error = assertFailsWith<T>(block = block)
        assertTrue(error.message.orEmpty().contains(reason), "wrong refusal: expected $reason, got ${error.message}")
    }
}
