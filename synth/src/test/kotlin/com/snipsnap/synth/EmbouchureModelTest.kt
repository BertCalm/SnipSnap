package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Model 1 is an immutable saved-recipe contract, backed by the original published WAVs. */
class EmbouchureModelTest {
    @Test
    fun `all original model one voices and presets retain their published PCM16 bytes`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/embouchure-model1-golden.json"))
        val fixture = Json.parse(stream.bufferedReader().use { it.readText() }).obj()
        val clips = fixture.getValue("clips").arr().map { it.obj() }
        assertEquals(14, clips.size)
        assertEquals(6, clips.count { it.getValue("group").str() == "VOICES" })
        assertEquals(8, clips.count { it.getValue("group").str() == "PRESETS" })
        val failures = mutableListOf<String>()
        for (clip in clips) {
            val id = clip.getValue("id").str()
            val patch = assertIs<EmbouchurePatch>(Patches.fromJsonValue(clip.getValue("recipe")))
            assertEquals(1, patch.model, "$id must dispatch to the frozen initial renderer")
            val snip = patch.render()
            assertEquals(clip.getValue("frames").int(), snip.frameCount, "$id historical phrase or loop length changed")
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_16) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val expected = clip.getValue("sha256").str()
            if (hash != expected) failures += "$id: $hash, expected $expected"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `model two keeps every default voice at its original sound anchor`() {
        for (voice in EmbouchureVoice.entries) {
            val macros = Embouchure.defaults(voice)
            val initial = Embouchure.render(voice, macros, model = 1)
            val current = Embouchure.render(voice, macros, model = 2)
            assertContentEquals(initial.samples, current.samples, "$voice default sound changed while strengthening its controls")
        }
    }

    @Test
    fun `ordinary new patches and original factory patches stay on model two`() {
        assertEquals(2, Embouchure.MODEL_VERSION)
        assertEquals(8, EmbouchurePresets.legacy().size)
        assertTrue(EmbouchurePresets.legacy().all { it.model == 2 })
        for (voice in EmbouchureVoice.entries) {
            val fresh = EmbouchurePatch("New player", voice, Embouchure.defaults(voice))
            assertEquals(2, fresh.model)
            assertEquals(fresh, Patches.fromJsonText(fresh.toJsonText()))
            for (model in listOf(-1, 0, 4, 99)) {
                assertRefused<IllegalArgumentException>("unsupported embouchure model $model") { fresh.copy(model = model) }
                val unsupported = JsonValue.Obj(fresh.toJsonValue().entries + ("model" to JsonValue.Num(model.toDouble())))
                assertRefused<JsonException>("unsupported embouchure model $model") { Patches.fromJsonValue(unsupported) }
            }
        }
    }

    @Test
    fun `model two nondefault brass notes and held material keep pre prototype PCM`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/embouchure-model2-brass-golden.json"))
        val clips = Json.parse(stream.bufferedReader().use { it.readText() }).obj().getValue("clips").arr()
        assertEquals(8, clips.size)
        for (entry in clips) {
            val clip = entry.obj()
            val patch = assertIs<EmbouchurePatch>(Patches.fromJsonValue(clip.getValue("recipe")))
            assertEquals(2, patch.model)
            val snip = patch.render()
            assertEquals(clip.getValue("frames").int(), snip.frameCount, patch.name)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_16) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(clip.getValue("sha256").str(), hash, patch.name)
        }
    }

    @Test
    fun `an interrupted model one render, finite or held, stops instead of finishing beside its replacement`() {
        // SynthScreen previews run in runInterruptible: a newer request interrupts the worker, and the frozen
        // renderer must stop on that flag in its loops and before each of its passes, as model two does.
        val voice = EmbouchureVoice.entries.first()
        for ((missing, render) in listOf<Pair<String, () -> Unit>>(
            "EMBOUCHURE model 1 finite render did not stop on the interrupt at its first look in the sample loop" to
                { Embouchure.render(voice, Embouchure.defaults(voice), model = 1) },
            "EMBOUCHURE model 1 held render did not stop on the interrupt at its first look in the first loop pass" to
                { Embouchure.render(voice, Embouchure.defaults(voice) + ("HOLD" to 1f), model = 1) },
        )) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<java.util.concurrent.CancellationException>(missing) { render() }
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `model one's conditioning pass stops on its own when the render is no longer wanted`() {
        // The synthesis loops poll the flag every 8192 samples; the band limit, the decimation and the level
        // that follow them run over the whole buffer, so the pass itself must look before it starts.
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException>(
                "EMBOUCHURE model 1's conditioning pass did not stop on the interrupt at its look before the band limit",
            ) { EmbouchureV1.finish(FloatArray(Dsp.OVERSAMPLE * 4096)) }
        } finally {
            Thread.interrupted()
        }
        assertEquals(4096, EmbouchureV1.finish(FloatArray(Dsp.OVERSAMPLE * 4096)).size, "an uninterrupted pass still conditions")
    }
}
