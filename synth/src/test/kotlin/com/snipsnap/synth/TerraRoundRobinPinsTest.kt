package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The TERRA ROUND ROBIN kit as rendered for round terra-rr-1: every take of every pad and touch,
 * hashed over its float samples (little-endian), in `round-robin-v1.sha256`. Take 0 of each zone is
 * also held to the kept kit by TerraRoundRobinKitsTest. A failure here means the takes' audio
 * changed; re-listen before re-pinning.
 */
class TerraRoundRobinPinsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** (slot, zone, take) to (frames, sha). */
    private val pinned by lazy {
        javaClass.getResourceAsStream("/terra/round-robin-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }
            .associate { f -> Triple(f[1], f[2].toInt(), f[3].toInt()) to (f[4].toInt() to f[5]) }
    }

    @Test fun `every take of the round robin kit hashes as it did when rendered for the round`() {
        assertEquals(1, TerraRoundRobinKits.VERSION)
        assertEquals(16 * 3 * TerraRoundRobinKits.TAKES, pinned.size)
        val built = TerraRoundRobinKits.build()
        assertEquals(16, built.size)
        for ((i, pad) in built.withIndex()) {
            val slot = "A%02d".format(i + 1)
            assertEquals(3, pad.velocityTakes.size)
            for ((z, row) in pad.velocityTakes.withIndex()) {
                assertEquals(TerraRoundRobinKits.TAKES, row.size)
                for ((t, snip) in row.withIndex()) {
                    val (frames, s) = pinned.getValue(Triple(slot, z, t))
                    assertEquals(frames, snip.frameCount, "$slot ${pad.displayName} zone $z take $t frames")
                    assertEquals(s, sha(snip.samples), "$slot ${pad.displayName} zone $z take $t")
                }
            }
        }
    }
}
