package com.snipsnap.synth

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The approved one-shot sound, pinned. The owner heard and approved the K1 render (2026-11-20) and merged K2,
 * which is within -50 dB of it on every audition clip. `consort/oneshot-k2-v1.sha256` holds the SHA-256 of the
 * float samples of every provisional preset at soft, default and hard velocity and all four takes, rendered
 * on the merged K2 engine before the held keys were added: the keys work changes no one-shot sample.
 *
 * Version 2 (2026-11-21): the faster tanh ([Consort.fastTanh]), adopted by the owner, moved three of the 156:
 * PLAIN CLARINET at velocity 1 takes 2 and 3, and DRONE AND FIFTH at velocity 0.3 take 2, by -177 to -182 dB
 * against the signal, at most 0.09 of a 24-bit step (80 samples of 321,532 round to the neighbouring 24-bit
 * value). The other 153 lines are version 1's. `CONSORT_PINS_WRITE_ONESHOT=<file>` rewrites the list.
 */
class ConsortPinsTest {
    private fun sha(samples: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(samples.size * 4)
        for (x in samples) bytes.putFloat(x)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `every pinned one-shot renders sample for sample as it did before the held keys`() {
        val lines = javaClass.getResourceAsStream("/consort/oneshot-k2-v2.sha256")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val presets = ConsortPresets.all().associateBy { it.name }
        assertEquals(13 * 3 * 4, lines.size)
        val write = System.getenv("CONSORT_PINS_WRITE_ONESHOT")
        val fresh = StringBuilder()
        for (line in lines) {
            val (name, velocity, take, hash) = line.split('\t')
            val patch = presets.getValue(name).copy(velocity = velocity.toFloat(), take = take.toInt())
            val now = sha(patch.render().samples)
            if (write != null) { fresh.append("$name\t$velocity\t$take\t$now\n"); continue }
            assertEquals(hash, now, "$name at velocity $velocity, take $take")
        }
        if (write != null) java.io.File(write).writeText(fresh.toString())
    }
}
