package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MidiGrooveTest {

    private val clip = Mpc3Clip(
        "Bridge Groove", 2,
        listOf(
            Mpc3Note(36, 0, 0.9f, 240),
            Mpc3Note(42, 503, 0.30f, 120),
            Mpc3Note(38, 960, 0.85f, 240),
            Mpc3Note(36, 3840, 1.0f, 480),
            Mpc3Note(46, 5760, 0.55f, 960),
        ),
    )

    @Test
    fun `write then read round-trips every note`() {
        val bytes = MidiGroove.write(clip, 92f)
        assertTrue(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "MThd")
        assertTrue(bytes.contentEquals(MidiGroove.write(clip, 92f)), "same clip, same bytes")

        val back = MidiGroove.read(bytes, "fallback")
        assertEquals("Bridge Groove", back.clip.name, "the track-name meta carries the clip's name")
        assertEquals(2, back.clip.bars)
        assertEquals(clip.notes.map { it.note }, back.clip.notes.map { it.note })
        assertEquals(clip.notes.map { it.timePulses }, back.clip.notes.map { it.timePulses })
        assertEquals(clip.notes.map { it.lengthPulses }, back.clip.notes.map { it.lengthPulses })
        clip.notes.zip(back.clip.notes).forEach { (a, b) ->
            assertTrue(abs(a.velocity - b.velocity) < 0.01f, "velocity survives 7 bits: ${a.velocity} vs ${b.velocity}")
        }
        assertTrue(abs(back.bpm!! - 92f) < 0.01f)
    }

    @Test
    fun `a DAW-style file imports - format 1, division 480, running status`() {
        // Hand-built the way sequencers actually write: a tempo track, then
        // a note track leaning on running status and vel-0 note-offs.
        fun varLen(v: Int): ByteArray {
            require(v in 0..0x3FFF)
            return if (v < 0x80) byteArrayOf(v.toByte())
            else byteArrayOf((0x80 or (v shr 7)).toByte(), (v and 0x7F).toByte())
        }

        val tempo = ByteArrayOutputStream().apply {
            write(varLen(0)) // 120 bpm = 500000 us/quarter
            write(byteArrayOf(0xFF.toByte(), 0x51, 3, 0x07, 0xA1.toByte(), 0x20))
            write(varLen(0))
            write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        }.toByteArray()

        val notes = ByteArrayOutputStream().apply {
            write(varLen(0))
            write(byteArrayOf(0x90.toByte(), 36, 100)) // kick on, ch 0
            write(varLen(240))                          // a 16th at 480 div
            write(byteArrayOf(36, 0))                   // running status: kick off
            write(varLen(0))
            write(byteArrayOf(42, 64))                  // running status: hat on
            write(varLen(120))
            write(byteArrayOf(42, 0))
            write(varLen(0))
            write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        }.toByteArray()

        val file = ByteArrayOutputStream().apply {
            write("MThd".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(0, 0, 0, 6, 0, 1, 0, 2, (480 shr 8).toByte(), (480 and 0xFF).toByte()))
            write("MTrk".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(0, 0, 0, tempo.size.toByte()))
            write(tempo)
            write("MTrk".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(0, 0, 0, notes.size.toByte()))
            write(notes)
        }.toByteArray()

        val back = MidiGroove.read(file, "daw beat")
        assertEquals("daw beat", back.clip.name, "no name meta: the caller's name holds")
        assertEquals(120f, back.bpm)
        // 480-division ticks rescale to our 960: the 16th lands at 480 pulses.
        assertEquals(listOf(36, 42), back.clip.notes.map { it.note })
        assertEquals(listOf(0L, 480L), back.clip.notes.map { it.timePulses })
        assertEquals(listOf(480L, 240L), back.clip.notes.map { it.lengthPulses })
    }

    @Test
    fun `a file with an absurd note count is refused, not accumulated`() {
        // A track that is nothing but note-on/note-off pairs, more than the
        // cap - the shape a hostile file would carry to eat the heap.
        fun varLen(v: Int) = if (v < 0x80) byteArrayOf(v.toByte())
        else byteArrayOf((0x80 or (v shr 7)).toByte(), (v and 0x7F).toByte())

        val track = ByteArrayOutputStream().apply {
            repeat(MidiGroove.MAX_NOTES + 50) {
                write(varLen(0)); write(byteArrayOf(0x99.toByte(), 36, 100)) // on, ch 10
                write(varLen(1)); write(byteArrayOf(0x89.toByte(), 36, 0))   // off
            }
            write(varLen(0)); write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        }.toByteArray()
        val file = ByteArrayOutputStream().apply {
            write("MThd".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, (960 shr 8).toByte(), (960 and 0xFF).toByte()))
            write("MTrk".toByteArray(Charsets.US_ASCII))
            val len = track.size
            write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            write(track)
        }.toByteArray()

        val err = assertFailsWith<IllegalArgumentException> { MidiGroove.read(file, "bomb") }
        assertTrue("${MidiGroove.MAX_NOTES}" in err.message!!, err.message!!)
    }

    @Test
    fun `whatever is not midi is refused with a reason`() {
        assertFailsWith<IllegalArgumentException> {
            MidiGroove.read(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "junk")
        }.also { assertTrue("not a MIDI file" in it.message!!) }

        // A file with a header and no notes is honest about it too.
        val empty = MidiGroove.write(clip, 92f)
        assertFailsWith<IllegalArgumentException> {
            MidiGroove.read(
                empty.copyOfRange(0, 14) + "MTrk".toByteArray(Charsets.US_ASCII) +
                    byteArrayOf(0, 0, 0, 4) + byteArrayOf(0, 0xFF.toByte(), 0x2F, 0),
                "empty",
            )
        }.also { assertTrue("no notes" in it.message!!) }
    }
}
