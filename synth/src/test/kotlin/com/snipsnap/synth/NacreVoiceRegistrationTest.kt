package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** HARMONICS, SHELL and BUBBLE as NACRE voices: picker entries, saved models and four presets each. */
class NacreVoiceRegistrationTest {
    private fun hash(samples: FloatArray): String {
        val b = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putFloat(s)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    private val voices = listOf(NacreVoice.HARMONICS, NacreVoice.SHELL, NacreVoice.BUBBLE)

    @Test
    fun `the voices and their saved models`() {
        assertEquals(listOf(NacreVoice.PEARL, NacreVoice.HARMONICS, NacreVoice.SHELL, NacreVoice.BUBBLE, NacreVoice.GROTTO), NacreVoice.entries)
        assertEquals(8, Nacre.defaultModel(NacreVoice.GROTTO))
        assertEquals(3, Nacre.defaultModel(NacreVoice.PEARL))
        assertEquals(5, Nacre.defaultModel(NacreVoice.HARMONICS))
        assertEquals(6, Nacre.defaultModel(NacreVoice.SHELL))
        assertEquals(7, Nacre.defaultModel(NacreVoice.BUBBLE))
        for (model in 1..8) assertEquals(model in 1..4, NacreModels.supports(NacreVoice.PEARL, model), "PEARL model $model")
        for (voice in voices) for (model in 1..8) {
            assertEquals(model == Nacre.defaultModel(voice), NacreModels.supports(voice, model), "$voice model $model")
        }
        assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.PEARL, emptyMap(), model = 5) }
        assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.SHELL, emptyMap(), model = 5) }
        assertFailsWith<IllegalArgumentException> { NacreModels.render(NacreVoice.HARMONICS, model = 3) }
        assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.PEARL, emptyMap(), model = 7) }
        assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.BUBBLE, emptyMap(), model = 6) }
        assertFailsWith<IllegalArgumentException> { NacreModels.render(NacreVoice.BUBBLE, model = 3) }
        val bubble = NacrePatch("x", NacreVoice.BUBBLE, emptyMap())
        assertEquals(bubble, NacrePatch.fromJsonText(bubble.toJsonText()))
        val mismatched = LinkedHashMap(bubble.toJsonValue().entries).apply { put("model", JsonValue.Num(5.0)) }
        assertFailsWith<IllegalArgumentException> { NacrePatch.fromJsonValue(JsonValue.Obj(mismatched)) }
    }

    @Test
    fun `a voice renders the same through every entry point`() {
        for (voice in voices) {
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
            // Bubble Drop: hard C3 at the default controls, the third strike of U1 (nacre-voices-2).
            NacreVoice.BUBBLE to "85752e096e6e5f026755f360f5de4b5fcd364623349706a560fba7aacbd80d6b",
        )
        assertEquals(voices, kept.keys.toList())
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
