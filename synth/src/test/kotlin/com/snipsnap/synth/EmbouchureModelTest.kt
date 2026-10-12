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
    fun `new factory patches use model two while unknown models are refused`() {
        assertEquals(2, Embouchure.MODEL_VERSION)
        assertTrue(EmbouchurePresets.all().all { it.model == 2 })
        for (voice in EmbouchureVoice.entries) {
            val fresh = EmbouchurePatch("New player", voice, Embouchure.defaults(voice))
            assertEquals(2, fresh.model)
            assertEquals(fresh, Patches.fromJsonText(fresh.toJsonText()))
            for (model in listOf(-1, 0, 3, 99)) {
                assertFailsWith<IllegalArgumentException> { fresh.copy(model = model) }
                val unsupported = JsonValue.Obj(fresh.toJsonValue().entries + ("model" to JsonValue.Num(model.toDouble())))
                assertFailsWith<JsonException> { Patches.fromJsonValue(unsupported) }
            }
        }
    }
}
