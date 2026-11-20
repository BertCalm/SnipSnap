package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native renders of REED, COURSE, ESCAPEMENT and BELL that the owner kept by ear in listening
 * round accord-voices-1 (19 Nov 2026): each voice's preset (C3, normal touch) and the same recipe at
 * C2, C4 and C5 (the X1 clips), at soft and hard touch (X2), and with the light support, RAFT 0, that
 * keeps the pendulums dispersed (X3) for REED, COURSE and BELL; ESCAPEMENT's X3 was not rated. Then
 * the sixteen pads of the ACCORD VOICES starter, which are the X1 renders pad for pad. The kept
 * BRUSH, WOOD and TINE renders stay in AccordNativePinsTest, untouched.
 */
internal object AccordVoicesPins {
    /** The voices whose gathered-then-dispersed clip (X3) was kept; ESCAPEMENT's AL3 is unrated. */
    val DISPERSED_KEPT = listOf(AccordVoice.REED, AccordVoice.COURSE, AccordVoice.BELL)

    fun cases(): List<Pair<String, AccordPatch>> {
        val result = mutableListOf<Pair<String, AccordPatch>>()
        for (voice in AccordKits.NEW_VOICES) {
            val preset = AccordPresets.forVoice(voice).single()
            val id = voice.name.lowercase()
            // The preset is the C3 normal-touch render, heard in X1, X2 and X3.
            result += "$id-base" to preset
            for (midi in listOf(36, 60, 72)) result += "$id-note-$midi" to preset.copy(midi = midi)
            for ((touch, velocity) in listOf("soft" to .3f, "hard" to 1f)) {
                result += "$id-touch-$touch" to preset.copy(velocity = velocity)
            }
            if (voice in DISPERSED_KEPT) result += "$id-dispersed" to preset.copy(macros = preset.macros + ("RAFT" to 0f))
        }
        AccordKits.voicesPatches().forEachIndexed { i, patch -> result += "voices-kit-A%02d".format(i + 1) to patch }
        return result.also { require(it.map { c -> c.first }.distinct().size == it.size) }
    }
}

/** A failure here means kept ACCORD audio changed: re-listen before re-pinning. */
class AccordVoicesPinsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** id to (frames, sha of the float samples, little-endian). */
    private val pinned by lazy {
        javaClass.getResourceAsStream("/accord/native-voices-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }
            .associate { f -> f[0] to (f[1].toInt() to f[2]) }
    }

    @Test fun `every kept render of the four new ACCORD voices hashes as it was heard`() {
        val cases = AccordVoicesPins.cases()
        assertEquals(4 * 6 + 3 + 16, cases.size)
        assertEquals(cases.map { it.first }.toSet(), pinned.keys)
        for ((id, patch) in cases) {
            val snip = patch.render()
            val (frames, s) = pinned.getValue(id)
            assertEquals(frames, snip.frameCount, "$id frames")
            assertEquals(s, sha(snip.samples), id)
        }
    }

    @Test fun `the ACCORD VOICES pads are the X1 renders and the earlier pins are untouched`() {
        val pins = pinned
        for ((n, voice) in AccordKits.NEW_VOICES.withIndex()) {
            val id = voice.name.lowercase()
            val notes = listOf("$id-note-36", "$id-base", "$id-note-60", "$id-note-72")
            for ((k, note) in notes.withIndex()) {
                assertEquals(pins.getValue(note), pins.getValue("voices-kit-A%02d".format(n * 4 + k + 1)), note)
            }
        }
        // accord-native-1's 37 lines stay where they were, and no id is pinned twice.
        val kept = javaClass.getResourceAsStream("/accord/native-kept-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }.map { it.substringBefore(' ') }
        assertEquals(37, kept.size)
        assertEquals(emptySet(), kept.toSet() intersect pins.keys)
    }
}
