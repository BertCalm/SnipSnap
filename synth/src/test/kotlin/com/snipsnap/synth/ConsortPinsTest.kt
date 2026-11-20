package com.snipsnap.synth

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The approved one-shot sound, pinned. The owner heard and approved the K1 render (2026-11-20) and merged K2,
 * which is within -50 dB of it on every audition clip. `consort/oneshot-k2-v1.sha256` holds the SHA-256 of the
 * float samples of every provisional preset at soft, default and hard velocity and all four takes, rendered
 * on the merged K2 engine before the held keys were added: the keys work changes no one-shot sample.
 */
class ConsortPinsTest {
    private fun sha(samples: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(samples.size * 4)
        for (x in samples) bytes.putFloat(x)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `every pinned one-shot renders sample for sample as it did before the held keys`() {
        val lines = javaClass.getResourceAsStream("/consort/oneshot-k2-v1.sha256")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val presets = ConsortPresets.all().associateBy { it.name }
        assertEquals(13 * 3 * 4, lines.size)
        for (line in lines) {
            val (name, velocity, take, hash) = line.split('\t')
            val patch = presets.getValue(name).copy(velocity = velocity.toFloat(), take = take.toInt())
            assertEquals(hash, sha(patch.render().samples), "$name at velocity $velocity, take $take")
        }
    }
}
