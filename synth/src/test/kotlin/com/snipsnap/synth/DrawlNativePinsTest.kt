package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native renders the owner kept by ear in DRAWL round 1 (21 Nov 2026, "Sound great, proceed"):
 * for every voice, the default note, a soft and a hard pick, the long slide with full vibrato, and
 * take 1. The cases mirror DrawlAuditionGenerator's clips of the same names, which the round-1
 * listening page played.
 */
internal object DrawlNativePins {
    fun cases(): List<Pair<String, DrawlPatch>> {
        val result = mutableListOf<Pair<String, DrawlPatch>>()
        for (voice in DrawlVoice.entries) {
            val v = voice.name.lowercase()
            result += "$v-mid" to DrawlPatch("$v-mid", voice, mapOf("TUNE" to .5f))
            result += "$v-soft" to DrawlPatch("$v-soft", voice, velocity = .3f)
            result += "$v-hard" to DrawlPatch("$v-hard", voice, velocity = 1f)
            result += "$v-slide1-waver1" to DrawlPatch("$v-slide1-waver1", voice, mapOf("SLIDE" to 1f, "WAVER" to 1f))
            result += "$v-take1" to DrawlPatch("$v-take1", voice, take = 1)
        }
        return result.also { require(it.map { c -> c.first }.distinct().size == it.size) }
    }

    fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }
}

/** A failure here means kept DRAWL audio changed: re-listen before re-pinning. */
class DrawlNativePinsTest {
    /** id to (frames, sha of the float samples, little-endian). */
    private val pinned by lazy {
        javaClass.getResourceAsStream("/drawl/native-kept-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }
            .associate { f -> f[0] to (f[1].toInt() to f[2]) }
    }

    @Test fun `every kept native DRAWL render hashes as it was heard`() {
        val cases = DrawlNativePins.cases()
        assertEquals(DrawlVoice.entries.size * 5, cases.size)
        assertEquals(cases.map { it.first }.toSet(), pinned.keys)
        for ((id, patch) in cases) {
            val snip = patch.render()
            val (frames, s) = pinned.getValue(id)
            assertEquals(frames, snip.frameCount, "$id frames")
            assertEquals(s, DrawlNativePins.sha(snip.samples), id)
        }
    }
}
