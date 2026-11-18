package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SAHEL TAKES, MAQAM TAKES and PUNJAB TAKES as built for listening round genre-rr-1 (18 Nov 2026):
 * every take of every pad and touch, hashed over its float samples (little-endian), in
 * `genre-round-robin-v1.sha256`. Take 0 of each zone is also held to the kept genre kits by
 * TerraGenreRoundRobinKitsTest. A failure here means the heard audio changed; re-listen before re-pinning.
 */
class TerraGenreRoundRobinPinsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** (kit id, slot, zone, take) to (frames, sha). */
    private val pinned by lazy {
        javaClass.getResourceAsStream("/terra/genre-round-robin-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }
            .associate { f -> listOf(f[1], f[2], f[3], f[4]) to (f[5].toInt() to f[6]) }
    }

    @Test fun `every take of the three genre round robin kits hashes as it was heard`() {
        assertEquals(1, TerraGenreRoundRobinKits.VERSION)
        assertEquals(3 * 16 * 3 * TerraGenreRoundRobinKits.TAKES, pinned.size)
        for (kit in TerraGenreRoundRobinKits.Kit.entries) {
            val built = TerraGenreRoundRobinKits.build(kit)
            assertEquals(16, built.size)
            for ((i, pad) in built.withIndex()) {
                val slot = "A%02d".format(i + 1)
                assertEquals(3, pad.velocityTakes.size)
                for ((z, row) in pad.velocityTakes.withIndex()) {
                    assertEquals(TerraGenreRoundRobinKits.TAKES, row.size)
                    for ((t, snip) in row.withIndex()) {
                        val (frames, s) = pinned.getValue(listOf(kit.source.id, slot, "$z", "$t"))
                        assertEquals(frames, snip.frameCount, "${kit.name} $slot ${pad.displayName} zone $z take $t frames")
                        assertEquals(s, sha(snip.samples), "${kit.name} $slot ${pad.displayName} zone $z take $t")
                    }
                }
            }
        }
    }
}
