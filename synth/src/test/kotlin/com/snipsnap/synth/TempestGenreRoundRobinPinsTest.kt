package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CIRCUIT TAKES, FOUNDRY TAKES and STEP TAKES as built for listening round genre-rr-1 (18 Nov 2026):
 * every take of every pad, hashed over its float samples (little-endian), in
 * `genre-round-robin-v1.sha256`. Take 0 of each pad must also still be the kept genre pad pinned
 * in `accepted-v1.sha256` before takes existed. A failure here means the heard audio changed;
 * re-listen before re-pinning.
 */
class TempestGenreRoundRobinPinsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    private fun lines(resource: String) = javaClass.getResourceAsStream(resource)!!.bufferedReader().readLines()
        .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }

    /** (kit id, pad, take) to (frames, sha). */
    private val pinned by lazy {
        lines("/tempest/genre-round-robin-v1.sha256").associate { f -> Triple(f[1], f[2], f[3].toInt()) to (f[4].toInt() to f[5]) }
    }

    /** Accepted genre pad (kit id, A01..A16) to sha. */
    private val accepted by lazy {
        lines("/tempest/accepted-v1.sha256").filter { it[0] == "kit" && TempestGenreKits.Kit.entries.any { k -> it[1].startsWith("${k.id}/") } }
            .associate { it[1].substringBefore('/') to it[1].substringAfter('/') to it[3] }
    }

    @Test fun `every take of the three genre round robin kits hashes as it did when heard`() {
        assertEquals(1, TempestGenreRoundRobinKits.VERSION)
        assertEquals(STRIKES, TempestGenreRoundRobinKits.STRIKES)
        assertEquals(OVERRIDES, TempestGenreRoundRobinKits.OVERRIDES)
        assertEquals(DRIFT_CENTS, TempestGenreRoundRobinKits.DRIFT_CENTS)
        assertEquals(3 * 16 * TempestGenreRoundRobinKits.TAKES, pinned.size)
        for (kit in TempestGenreRoundRobinKits.Kit.entries) for ((i, pad) in TempestGenreRoundRobinKits.build(kit).withIndex()) {
            val slot = "A%02d".format(i + 1)
            for ((t, snip) in (listOf(pad.snip) + pad.takes).withIndex()) {
                val (frames, s) = pinned.getValue(Triple(kit.source.id, slot, t))
                assertEquals(frames, snip.frameCount, "${kit.name} ${pad.displayName} take $t frames")
                assertEquals(s, sha(snip.samples), "${kit.name} ${pad.displayName} take $t")
            }
        }
    }

    @Test fun `take zero of every pad is pinned to the accepted genre kit`() {
        assertEquals(48, accepted.size)
        for ((key, s) in accepted) assertEquals(s, pinned.getValue(Triple(key.first, key.second, 0)).second, "$key")
    }

    private companion object {
        val STRIKES = listOf(0, 2, 5, 8)
        val OVERRIDES = mapOf("CIRCUIT BODY" to listOf(0, 3, 4, 5), "CIRCUIT TIGHT" to listOf(0, 1, 13, 14),
            "FOUNDRY CRACK" to listOf(0, 1, 3, 4), "STEP PEDAL" to listOf(0, 1, 3, 5))
        val DRIFT_CENTS = listOf(0.0, 4.0, -4.0, 2.0)
    }
}
