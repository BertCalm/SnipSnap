package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The passage-offset round-robin takes the owner kept by ear in listening round accord-rr-1
 * (19 Nov 2026): every ACCORD voice's first preset at C3, at soft (.3), normal (.75) and hard (1)
 * touch, takes 1, 2 and 3 of [Accord.renderTake] (0-based; the owner's takes 2, 3 and 4). Take 0 is
 * the plain render, already pinned by AccordNativePinsTest and AccordVoicesPinsTest; this test
 * checks it is still exactly those pins.
 */
internal object AccordRoundRobinPins {
    val TOUCHES = listOf("soft" to .3f, "normal" to .75f, "hard" to 1f)

    /** The C3 preset each voice's takes were heard on. */
    fun preset(voice: AccordVoice, velocity: Float): AccordPatch =
        AccordPresets.forVoice(voice).first().copy(midi = 48, velocity = velocity)

    fun cases(): List<Pair<String, AccordPatch>> = AccordVoice.entries.flatMap { voice ->
        TOUCHES.flatMap { (touch, velocity) ->
            (1 until Accord.TAKES).map { take -> "${voice.name.lowercase()}-$touch-take-$take" to preset(voice, velocity) }
        }
    }

    fun take(id: String): Int = id.substringAfterLast('-').toInt()

    /** Take 0 of each voice and touch, by the id that already pins it. */
    fun takeZeroPins(): List<Triple<AccordVoice, Float, String>> = AccordVoice.entries.flatMap { voice ->
        val base = if (voice == AccordVoice.TINE) "tine-reference" else "${voice.name.lowercase()}-base"
        listOf(Triple(voice, .3f, "${voice.name.lowercase()}-touch-soft"), Triple(voice, .75f, base),
            Triple(voice, 1f, "${voice.name.lowercase()}-touch-hard"))
    }
}

/** A failure here means kept ACCORD audio changed: re-listen before re-pinning. */
class AccordRoundRobinPinsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** id to (frames, sha of the float samples, little-endian). */
    private fun pins(resource: String) = javaClass.getResourceAsStream(resource)!!.bufferedReader().readLines()
        .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }
        .associate { f -> f[0] to (f[1].toInt() to f[2]) }

    @Test fun `every kept round-robin take hashes as it was heard`() {
        val pinned = pins("/accord/round-robin-v1.sha256")
        val cases = AccordRoundRobinPins.cases()
        assertEquals(7 * 3 * 3, cases.size)
        assertEquals(cases.map { it.first }.toSet(), pinned.keys)
        for ((id, patch) in cases) {
            val snip = Accord.renderTake(patch, AccordRoundRobinPins.take(id))
            val (frames, s) = pinned.getValue(id)
            assertEquals(frames, snip.frameCount, "$id frames")
            assertEquals(s, sha(snip.samples), id)
        }
    }

    @Test fun `take 0 of every voice and touch is still the pinned plain render`() {
        val earlier = pins("/accord/native-kept-v1.sha256") + pins("/accord/native-voices-v1.sha256")
        val zero = AccordRoundRobinPins.takeZeroPins()
        assertEquals(21, zero.size)
        for ((voice, velocity, id) in zero) {
            val snip = Accord.renderTake(AccordRoundRobinPins.preset(voice, velocity), 0)
            val (frames, s) = earlier.getValue(id)
            assertEquals(frames, snip.frameCount, "$id frames")
            assertEquals(s, sha(snip.samples), "$voice touch $velocity take 0 is $id")
        }
    }
}
