package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native renders the owner kept by ear in listening round accord-native-1 (19 Nov 2026): every
 * factory recipe, each voice's anchor at C2, C4 and C5 and at soft and hard touch, and the sixteen
 * pads of the ACCORD starter kit. The cases mirror AccordAuditionGenerator's voices, notes and touch
 * sections and AccordKits.patches(), so the ids name what was heard.
 */
internal object AccordNativePins {
    /** The three voices heard in accord-native-1. Later voices are pinned by AccordVoicesPinsTest. */
    val VOICES = listOf(AccordVoice.BRUSH, AccordVoice.WOOD, AccordVoice.TINE)

    fun cases(): List<Pair<String, AccordPatch>> {
        val result = mutableListOf<Pair<String, AccordPatch>>()
        for (patch in AccordPresets.all().filter { it.voice in VOICES }) {
            result += "${patch.voice.name.lowercase()}-${patch.acousticProfile.name.lowercase()}" to patch
        }
        for (voice in VOICES) {
            val anchor = AccordPresets.forVoice(voice).first()
            for (midi in listOf(36, 60, 72)) result += "${voice.name.lowercase()}-note-$midi" to anchor.copy(midi = midi)
            // Normal touch (.75) is the anchor itself, pinned above.
            for ((touch, velocity) in listOf("soft" to .3f, "hard" to 1f)) {
                result += "${voice.name.lowercase()}-touch-$touch" to anchor.copy(velocity = velocity)
            }
        }
        AccordKits.patches().forEachIndexed { i, patch -> result += "kit-A%02d".format(i + 1) to patch }
        return result.also { require(it.map { c -> c.first }.distinct().size == it.size) }
    }
}

/** A failure here means kept ACCORD audio changed: re-listen before re-pinning. */
class AccordNativePinsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** id to (frames, sha of the float samples, little-endian). */
    private val pinned by lazy {
        javaClass.getResourceAsStream("/accord/native-kept-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }
            .associate { f -> f[0] to (f[1].toInt() to f[2]) }
    }

    @Test fun `every kept native ACCORD render hashes as it was heard`() {
        val cases = AccordNativePins.cases()
        assertEquals(6 + 3 * 5 + 16, cases.size)
        assertEquals(cases.map { it.first }.toSet(), pinned.keys)
        for ((id, patch) in cases) {
            val snip = patch.render()
            val (frames, s) = pinned.getValue(id)
            assertEquals(frames, snip.frameCount, "$id frames")
            assertEquals(s, sha(snip.samples), id)
        }
    }
}
