package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * These PCM16 WAV hashes were copied from the original published first-flight
 * manifest, revision 9aba00d6b4480781e5f7549504e4c73a68017996, created 2026-10-15.
 * They are fixed external evidence, not expectations computed by the revised model.
 */
class FlockLegacyGoldenTest {
    private val seed = 301927973707L

    private fun wavHash(probe: Flock.Probe): String {
        val bytes = ByteArrayOutputStream()
        WavWriter.write(bytes, Snip(probe.rawSamples, channels = 1, sampleRate = 44_100), WavWriter.BitDepth.PCM_16)
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `model one regenerates the five originally published FLOW WAVs exactly`() {
        val hashes = listOf(
            "b2a4d22a476cad4875e47e7d7c3d9400d5af954333d16358f7382ed979831ff3",
            "4807fb0b5f43f455fd58fd392f3c1dce9c8bc747cf3af8c90dcea0ab17ca8007",
            "f82c578089d793aabbab3fa828a82bc2e1cf5da068cda11bf53d7a42ea1ec156",
            "d338223bca6267b25d690a886df9466f09c68db193138508fb2d82f407c94b93",
            "3d98ab22ebab0a33b6aedd67ebf77efa95e97873e76f270cb6a309d62cbee4fd",
        )
        val companion = mapOf("SPREAD" to .55f, "PULL" to .55f, "SCATTER" to .55f, "EDGE" to .4f, "HOLD" to .55f)
        for ((i, flow) in listOf(0f, .25f, .5f, .75f, 1f).withIndex()) {
            val probe = Flock.renderDetailed(FlockVoice.RIBBON, companion + ("FLOW" to flow), seed = seed, model = 1)
            assertEquals(hashes[i], wavHash(probe), "original sweep-flow-$i changed")
        }
    }

    @Test
    fun `both models regenerate the three accepted default WAVs exactly`() {
        val hashes = mapOf(
            FlockVoice.GATHER to "142f654a56a47503456af8a6a4cae1d07a4c8c5123aaf63b789d73f9f3672276",
            FlockVoice.RIBBON to "9bc69ebaa5c0780c07f968e6af2ed50247f94aeab98456e6245de56483c926e4",
            FlockVoice.SPLIT to "c71d988493f3104111d5bbf3f99e5aac397d02a9ea9876be96e9f93e2150b64b",
        )
        for ((voice, hash) in hashes) for (model in listOf(1, 2)) {
            val probe = Flock.renderDetailed(voice, seed = seed, model = model)
            assertEquals(hash, wavHash(probe), "$voice default changed in model $model")
        }
    }
}
