package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ChamberWorkingResponseTest {
    @Test
    fun `new recipes and the public renderer use the heard response at C6`() {
        assertEquals(Chamber.REGISTER_MODEL, Chamber.MODEL_VERSION)
        for (voice in ChamberVoice.entries) {
            val patch = ChamberPatch("New room", voice, Chamber.defaults(voice) + ("MATERIAL" to 1f), midi = 84)
            assertEquals(Chamber.REGISTER_MODEL, patch.model)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val expected = patch.copy(model = Chamber.REGISTER_MODEL).render().samples
            assertContentEquals(expected, patch.render().samples)
            assertContentEquals(expected, Chamber.render(voice, patch.macros, midi = 84).samples)
        }
    }

    @Test
    fun `promoted response and saved wider response retain the published complete recordings`() {
        // Little-endian raw Float32 hashes captured by the complete 4.5-second
        // Kotlin study at 7b37efa9f and its efb365650 baseline, before promotion.
        // The default path must reproduce the recording Josh judged, rather
        // than merely agreeing with another call to today's implementation.
        val recordings = listOf(
            Triple(ChamberVoice.HYBRID, 5, "fd188ecd6e5dbc306af1b179b772105ebbcd8713fc3fe0eded49d5d023e89d4c"),
            Triple(ChamberVoice.AQUARIUM, 5, "254e3b7e763c3e2a8afcb2e28234ab13daa339507ad75490c15c11839acb22bc"),
            Triple(ChamberVoice.HYBRID, 4, "19109fcfa5f0bed1510950a8c73ff7c9623d95d94206ddfc5cae21c0fba59bd9"),
            Triple(ChamberVoice.AQUARIUM, 4, "49a11bae3bc25a3df0c3d40a94503463423d47e269dfe784b72dd4cdf2aa0445"),
        )
        for ((voice, model, expected) in recordings) {
            val controls = Chamber.defaults(voice) + ("MATERIAL" to 1f)
            val options = Chamber.ProbeOptions(seconds = 4.5f, normalize = false, recordStems = false)
            val probe = if (model == Chamber.REGISTER_MODEL) {
                Chamber.probe(voice, controls, midi = 84, options = options)
            } else {
                Chamber.probe(voice, controls, midi = 84, model = model, options = options)
            }
            val bytes = ByteBuffer.allocate(probe.rawMix.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            probe.rawMix.forEach { bytes.putFloat(it) }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual, "$voice renderer $model changed the published C6 recording")
        }
    }

    @Test
    fun `editing a complete saved recipe never replaces its renderer`() {
        for (model in 1..Chamber.MAX_MODEL_VERSION) {
            val patch = ChamberPatch("Authored room", ChamberVoice.AQUARIUM,
                Chamber.defaults(ChamberVoice.AQUARIUM), midi = 79, velocity = .42f,
                seed = Long.MIN_VALUE + 19, model = model,
                configuration = ChamberConfig.aquarium().copy(splitHeight = .64f))
            val restored = Patches.fromJsonText(patch.toJsonText()) as ChamberPatch
            assertEquals(patch, restored)
            val controls = restored.macros + ("STRIKE" to .63f)
            assertEquals(patch.copy(macros = controls), restored.withMacros(controls))
            assertEquals(patch.copy(name = "Edited", macros = controls), Patches.edited(restored, "Edited", controls))
            val recipe = PadRecipe.fromJsonText(PadRecipe(restored).toJsonText())
            assertEquals(patch, recipe.patch)
        }
    }
}
