package com.snipsnap.synth

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * WIRE, RUB, AIR, BAR and SKIN as SPLAY voices: the picker, the patch format and the factory presets.
 * Each body's kept setting (its factory preset and its default) renders exactly the same-settings take
 * the owner kept; [SplayBodiesTest] pins every other kept take and the desk files.
 */
class SplayBodyRegistrationTest {
    private val bodies = SplayVoice.entries.filter { it.body != null }
    private val pc16 = SplayAcceptedBank.entries.single { it.mechanisms == 16 }.patch

    private fun sha(x: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(x.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in x) bytes.putFloat(v)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    /** The same-settings takes behind S2, S9, S13, S17 and S20, as pinned in [SplayBodiesTest]. */
    private val kept = mapOf(
        SplayVoice.WIRE to "c3b0e92ac4a329da3f90dc2e91fc43226f217093aec17f211d65a1710fb64291",
        SplayVoice.RUB to "5b97c746a8dc4de33418aefd7b61f472f063eb3fc32d2a9a48368f728d2e9ce4",
        SplayVoice.AIR to "182524e9907689331ec002b6f139455b459840ee1396c327aae9ec73935b4b04",
        SplayVoice.BAR to "50066b54c18f8612d0861e0b3921abe8080b462ac47854c6a3188787f68492cd",
        SplayVoice.SKIN to "0da53b5cea53d105a46b02f8dbc78e067eb3098e105c4fb5ceaf923bceddf0b1",
    )

    @Test fun `the six voices are TONGUE and the five kept bodies, in the bodies' order`() {
        assertEquals(listOf(null) + SplayBody.entries, SplayVoice.entries.map { it.body })
        assertEquals(SplayBody.entries.map { it.name }, bodies.map { it.name })
        for (voice in bodies) {
            assertEquals(Splay.BODY_MODEL, Splay.defaultModel(voice))
            assertTrue(Splay.supports(voice, 3))
            for (model in listOf(1, 2, 4)) assertTrue(!Splay.supports(voice, model), "$voice model $model")
            // The bodies keep TONGUE's model-2 controls, HOLD included (refused at registration).
            assertEquals(Splay.macrosFor(SplayVoice.TONGUE, 2), Splay.macrosFor(voice))
            assertEquals(pc16.macros, Splay.defaults(voice))
        }
        assertEquals(2, Splay.defaultModel(SplayVoice.TONGUE))
        assertTrue(!Splay.supports(SplayVoice.TONGUE, 3))
    }

    @Test fun `each body's factory preset and default render exactly the take the owner kept`() {
        for (voice in bodies) {
            val preset = Presets.forVoice(SplayPatch.ENGINE, voice.name).single() as SplayPatch
            assertEquals(pc16.copy(voice = voice, model = 3), preset)
            assertEquals(preset, SplayFactoryPresets.forVoice(voice).single())
            assertEquals(kept.getValue(voice), sha(preset.render().samples), "$voice preset")
            // A fresh pick in the SYNTH screen: the voice's defaults at the patch's default note and seed.
            val fresh = SplayPatch("$voice", voice, Splay.defaults(voice))
            assertEquals(3, fresh.model)
            assertEquals(kept.getValue(voice), sha(fresh.render().samples), "$voice default")
            assertEquals(kept.getValue(voice), sha(Splay.render(voice, Splay.defaults(voice)).samples))
        }
        assertEquals(10, SplayFactoryPresets.all().size)
    }

    @Test fun `a body patch saves, reopens and replays at model 3`() {
        for (voice in bodies) {
            val patch = SplayPatch("Saved $voice", voice, pc16.macros + ("CONTACT" to .41f),
                midi = 43, velocity = .52f, seed = -7)
            val text = patch.toJsonText()
            assertTrue(Regex("\"model\"\\s*:\\s*3\\b").containsMatchIn(text), text)
            val reopened = Patches.fromJsonText(text) as SplayPatch
            assertEquals(patch, reopened)
            assertEquals(sha(patch.render().samples), sha(reopened.render().samples))
            assertEquals(sha(SplayBodies.render(voice.body!!, patch.macros, 43, .52f, -7).samples), sha(reopened.render().samples))
            val recipe = PadRecipe(patch)
            assertEquals(patch, PadRecipe.fromJsonValue(recipe.toJsonValue()).patch)
        }
    }

    @Test fun `the patch format refuses a body at another model, TONGUE at model 3, and HOLD`() {
        for (voice in bodies) {
            for (model in listOf(1, 2)) assertFailsWith<IllegalArgumentException> { SplayPatch("x", voice, emptyMap(), model = model) }
            val text = SplayPatch("x", voice, emptyMap()).toJsonText()
            val wrong = text.replace(Regex("\"model\"\\s*:\\s*3(\\.0)?"), "\"model\": 2")
            assertTrue(wrong != text, text)
            assertFailsWith<com.snipsnap.json.JsonException> { SplayPatch.fromJsonText(wrong) }
            // HOLD is RUB's and AIR's in the engine, but not on the phone yet: the patch stays finite.
            assertFailsWith<IllegalArgumentException> { SplayPatch("x", voice, mapOf("HOLD" to 1f)) }
            assertFailsWith<IllegalArgumentException> { Splay.renderDetailed(voice) }
            assertFailsWith<IllegalArgumentException> { Splay.render(voice, model = 2) }
        }
        assertFailsWith<IllegalArgumentException> { SplayPatch("x", SplayVoice.TONGUE, emptyMap(), model = 3) }
    }

    @Test fun `scrambling a body stays on its model and stays finite`() {
        for (voice in bodies) {
            val near = SplayFactoryPresets.forVoice(voice).single()
            val tongue = SplayFactoryPresets.forVoice(SplayVoice.TONGUE).first()
            for (n in listOf(near, tongue, null)) {
                val m = Splay.scramble(voice, Random(5), near = n)
                assertEquals(Splay.defaults(voice).keys, m.keys)
                assertEquals(0f, m["HOLD"])
                SplayPatch("scrambled", voice, m)
            }
        }
    }
}
