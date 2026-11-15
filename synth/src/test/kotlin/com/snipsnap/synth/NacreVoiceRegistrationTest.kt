package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** HARMONICS and SHELL as NACRE voices: picker entries, saved models and four presets each. */
class NacreVoiceRegistrationTest {
    private fun hash(samples: FloatArray): String {
        val b = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putFloat(s)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `the voices and their saved models`() {
        assertEquals(listOf(NacreVoice.PEARL, NacreVoice.HARMONICS, NacreVoice.SHELL), NacreVoice.entries)
        assertEquals(3, Nacre.defaultModel(NacreVoice.PEARL))
        assertEquals(5, Nacre.defaultModel(NacreVoice.HARMONICS))
        assertEquals(6, Nacre.defaultModel(NacreVoice.SHELL))
        for (model in 1..4) assertTrue(NacreModels.supports(NacreVoice.PEARL, model))
        for (voice in listOf(NacreVoice.HARMONICS, NacreVoice.SHELL)) for (model in 1..7) {
            assertEquals(model == Nacre.defaultModel(voice), NacreModels.supports(voice, model), "$voice model $model")
        }
        assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.PEARL, emptyMap(), model = 5) }
        assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.SHELL, emptyMap(), model = 5) }
        assertFailsWith<IllegalArgumentException> { NacreModels.render(NacreVoice.HARMONICS, model = 3) }
    }

    @Test
    fun `a voice renders the same through every entry point`() {
        for (voice in listOf(NacreVoice.HARMONICS, NacreVoice.SHELL)) {
            val direct = NacreVoices.render(NacreVoices.sourceFor(voice), emptyMap(), 52, .7f, 3L).samples
            assertEquals(hash(direct), hash(Nacre.render(voice, emptyMap(), 52, .7f, 3L).samples))
            assertEquals(hash(direct), hash(NacrePatch("x", voice, emptyMap(), 52, .7f, 3L).render().samples))
            assertEquals(Nacre.macrosFor(NacreVoice.PEARL).map { it.name }, Nacre.macrosFor(voice).map { it.name })
        }
    }

    @Test
    fun `four presets each, short names, the first is the kept default sound`() {
        val kept = mapOf(
            NacreVoice.HARMONICS to "4e1a00f71c0161fbdf7c3b249e7c0faf82ca204a6246d4fd1c9e1329ec3a18fc",
            NacreVoice.SHELL to "d2d7992fc59a6fdbee9d1a8dff72ba9111d4fb38d2f265644936fcf6e1ad8d46",
        )
        for ((voice, keptHash) in kept) {
            val presets = NacrePresets.forVoice(voice)
            assertEquals(4, presets.size, "$voice presets")
            for (p in presets) {
                assertTrue(p.name.length <= 14, "${p.name} is longer than 14 letters")
                assertEquals(Nacre.defaultModel(voice), p.model)
                assertEquals(p, NacrePatch.fromJsonText(p.toJsonText()))
                assertEquals(p, Presets.byName("NACRE", voice.name, p.name))
            }
            assertEquals(keptHash, hash(presets.first().render().samples), "${presets.first().name} is not the kept sound")
            val renders = presets.map { hash(it.render().samples) }
            assertEquals(renders.size, renders.toSet().size, "$voice presets repeat a sound")
            assertEquals(presets, Presets.forVoice("NACRE", voice.name))
        }
        val names = NacrePresets.all().map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertNotEquals(NacrePresets.forVoice(NacreVoice.PEARL), NacrePresets.all())
    }
}
