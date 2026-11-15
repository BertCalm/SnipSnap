package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The EROSION ROUND ROBIN kit as the owner kept it by ear in round erosion-rr-1 (15 Nov 2026):
 * every take of every pad and touch, hashed over its float samples (little-endian). Take 0 of
 * each zone must also still be the accepted zone pinned in `contrast-v1.sha256` before takes
 * existed. A failure here means the kept audio changed; re-listen before re-pinning.
 */
class ErosionRoundRobinPinsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    private fun lines(resource: String) = javaClass.getResourceAsStream(resource)!!.bufferedReader().readLines()
        .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }

    /** (pad, zone, take) to (frames, sha). */
    private val kept by lazy {
        lines("/erosion/round-robin-v1.sha256").associate { f ->
            Triple(f[1], f[2].toInt(), f[3].toInt()) to (f[4].toInt() to f[5])
        }
    }

    /** (pad, zone) to sha of the accepted contrast kit's zone sample. */
    private val accepted by lazy {
        lines("/erosion/contrast-v1.sha256").filter { it[0] == "zone" }.associate { (_, name, z, s) -> (name to z.toInt()) to s }
    }

    @Test fun `every take of the kept round robin kit hashes as it did when heard`() {
        assertEquals(1, ErosionRoundRobinKits.VERSION)
        assertEquals(listOf(0, 2, 3, 5), ErosionRoundRobinKits.STRIKES)
        assertEquals(8 * 3 * ErosionRoundRobinKits.TAKES, kept.size)
        val built = ErosionRoundRobinKits.build()
        assertEquals(ErosionBank.entries().map { it.name }, built.map { it.displayName })
        for (pad in built) {
            val name = pad.displayName!!
            assertEquals(3, pad.velocityTakes.size)
            for ((z, row) in pad.velocityTakes.withIndex()) {
                assertEquals(ErosionRoundRobinKits.TAKES, row.size)
                for ((t, snip) in row.withIndex()) {
                    val (frames, s) = kept.getValue(Triple(name, z, t))
                    assertEquals(frames, snip.frameCount, "$name zone $z take $t frames")
                    assertEquals(s, sha(snip.samples), "$name zone $z take $t")
                }
            }
        }
    }

    @Test fun `take zero of every zone is pinned to the accepted contrast kit`() {
        assertEquals(24, accepted.size)
        for ((key, s) in accepted) assertEquals(s, kept.getValue(Triple(key.first, key.second, 0)).second, "${key.first} zone ${key.second}")
    }
}
