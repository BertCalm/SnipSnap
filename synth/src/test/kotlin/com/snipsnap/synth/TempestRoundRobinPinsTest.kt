package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The TEMPEST ROUND ROBIN kit as the owner kept it by ear in round tempest-rr-1 (16 Nov 2026):
 * every take of every pad, hashed over its float samples (little-endian). Take 0 of each pad must
 * also still be the accepted TEMPEST pad pinned in `accepted-v1.sha256` before takes existed. A
 * failure here means the kept audio changed; re-listen before re-pinning.
 */
class TempestRoundRobinPinsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    private fun lines(resource: String) = javaClass.getResourceAsStream(resource)!!.bufferedReader().readLines()
        .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }

    /** (pad, take) to (frames, sha). */
    private val kept by lazy {
        lines("/tempest/round-robin-v1.sha256").associate { f -> (f[1] to f[2].toInt()) to (f[3].toInt() to f[4]) }
    }

    /** Accepted TEMPEST pad (A01..A16) to sha. */
    private val accepted by lazy {
        lines("/tempest/accepted-v1.sha256").filter { it[0] == "kit" && it[1].startsWith("tempest/") }
            .associate { it[1].removePrefix("tempest/") to it[3] }
    }

    @Test fun `every take of the kept round robin kit hashes as it did when heard`() {
        assertEquals(1, TempestRoundRobinKits.VERSION)
        assertEquals(STRIKES, TempestRoundRobinKits.STRIKES)
        assertEquals(16 * TempestRoundRobinKits.TAKES, kept.size)
        val built = TempestRoundRobinKits.build()
        for ((i, pad) in built.withIndex()) {
            val slot = "A%02d".format(i + 1)
            for ((t, snip) in (listOf(pad.snip) + pad.takes).withIndex()) {
                val (frames, s) = kept.getValue(slot to t)
                assertEquals(frames, snip.frameCount, "${pad.displayName} take $t frames")
                assertEquals(s, sha(snip.samples), "${pad.displayName} take $t")
            }
        }
    }

    @Test fun `take zero of every pad is pinned to the accepted TEMPEST kit`() {
        assertEquals(16, accepted.size)
        for ((slot, s) in accepted) assertEquals(s, kept.getValue(slot to 0).second, slot)
    }

    private companion object {
        val STRIKES = listOf(0, 2, 5, 8)
    }
}
