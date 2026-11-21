package com.snipsnap.synth

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sustaining pads the owner chose (2026-11-21, "I like c"): HOLD's top step on OBOE, FLUTE, DUDUK LAMENT
 * and FULL QUARTET, the loops behind clips C1 to C4, pinned by the SHA-256 of their float samples
 * (`consort/sustain-c-v1.sha256`).
 */
class ConsortSustainPinsTest {
    private fun sha(x: FloatArray): String {
        val b = java.nio.ByteBuffer.allocate(x.size * 4)
        for (v in x) b.putFloat(v)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `the kept sustaining pads render sample for sample as heard`() {
        val all = ConsortPresets.all()
        val rows = listOf("PLAIN OBOE", "PLAIN FLUTE", "DUDUK LAMENT", "FULL QUARTET").map { name ->
            val p = all.first { it.name == name }
            name to sha(p.withMacros(p.macros + ("HOLD" to 1f)).render().samples)
        }
        System.getenv("CONSORT_PINS_WRITE")?.let { File(it).writeText(rows.joinToString("") { "${it.first}\t${it.second}\n" }); return }
        val pinned = javaClass.getResourceAsStream("/consort/sustain-c-v1.sha256")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { it.split('\t').let { (a, b) -> a to b } }
        assertEquals(pinned, rows)
    }
}
