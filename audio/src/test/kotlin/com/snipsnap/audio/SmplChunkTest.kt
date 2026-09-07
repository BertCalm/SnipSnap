package com.snipsnap.audio

import java.io.ByteArrayOutputStream
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SmplChunkTest {

    private val rate = 44_100

    private fun tone(frames: Int, channels: Int = 1): Snip =
        Snip(FloatArray(frames * channels) { (0.5 * sin(2.0 * Math.PI * 220.0 * (it / channels) / rate)).toFloat() }, channels, rate)

    private fun bytes(snip: Snip, smpl: SmplChunk?, depth: WavWriter.BitDepth = WavWriter.BitDepth.PCM_24): ByteArray =
        ByteArrayOutputStream().apply { WavWriter.write(this, snip, depth, smpl) }.toByteArray()

    private fun leInt(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    @Test
    fun `root and loop round-trip, the audio is untouched, and the RIFF size is honest`() {
        val snip = tone(10_000, channels = 2)
        val sheet = SmplChunk(57, SmplChunk.Loop(1_000, 10_000))
        val withSheet = bytes(snip, sheet)
        val plain = bytes(snip, null)

        assertEquals(sheet, WavReader.readSmpl(withSheet))
        assertNull(WavReader.readSmpl(plain), "a plain file carries no sheet")
        assertContentEquals(WavReader.read(plain).samples, WavReader.read(withSheet).samples, "the audio is the same either way")
        assertEquals(plain.size + 8 + SmplChunk.HEADER_BYTES + SmplChunk.LOOP_BYTES, withSheet.size)
        assertEquals(withSheet.size - 8L, leInt(withSheet, 4), "RIFF size counts everything after itself")
        // The chunk sits between fmt and data.
        assertEquals("smpl", String(withSheet, 36, 4, Charsets.US_ASCII))
        assertEquals(22_675L, leInt(withSheet, 44 + 8), "sample period in ns at 44.1 k")
    }

    @Test
    fun `a root alone writes no loop and reads back as none`() {
        val snip = tone(500)
        val sheet = SmplChunk(60)
        val out = bytes(snip, sheet, WavWriter.BitDepth.PCM_16)
        assertEquals(sheet, WavReader.readSmpl(out))
        assertEquals(bytes(snip, null, WavWriter.BitDepth.PCM_16).size + 8 + SmplChunk.HEADER_BYTES, out.size)
    }

    @Test
    fun `refusals in words - a root off the keyboard, a loop that ends first, a loop past the sample`() {
        assertFailsWith<IllegalArgumentException> { SmplChunk(128) }
        assertFailsWith<IllegalArgumentException> { SmplChunk(-1) }
        assertFailsWith<IllegalArgumentException> { SmplChunk.Loop(500, 500) }
        assertFailsWith<IllegalArgumentException> { SmplChunk.Loop(-1, 5) }
        val past = SmplChunk(60, SmplChunk.Loop(100, 600))
        assertFailsWith<IllegalArgumentException> { bytes(tone(500), past) }
    }

    @Test
    fun `a malformed sheet reads as none, never as a wrong one`() {
        val out = bytes(tone(300), SmplChunk(64, SmplChunk.Loop(10, 300)))
        // Corrupt the root note into an impossible value.
        val bad = out.copyOf()
        bad[44 + 12] = 0xFF.toByte()
        bad[44 + 13] = 0xFF.toByte()
        assertNull(WavReader.readSmpl(bad))
        assertNull(WavReader.readSmpl(ByteArray(3)))
        // A loop that runs past the audio the file holds: the chunk's
        // inclusive end at offset 48 pushed past the last frame.
        val past = out.copyOf()
        past[44 + 48] = 0x2C // 300 = 0x012C: an inclusive end of 300 means 301 frames
        past[44 + 49] = 0x01
        assertNull(WavReader.readSmpl(past), "a loop past the sample is not a loop")
    }

    @Test
    fun `the RIFF size counts the pad byte an odd 24-bit data chunk needs`() {
        val odd = tone(301) // 24-bit mono, odd frame count: 903 data bytes plus one pad
        val plain = bytes(odd, null)
        assertEquals(0, plain.size % 2, "the file itself is word-aligned")
        assertEquals(plain.size - 8L, leInt(plain, 4))
        val sheeted = bytes(odd, SmplChunk(60, SmplChunk.Loop(0, 301)))
        assertEquals(sheeted.size - 8L, leInt(sheeted, 4))
        assertEquals(301, WavReader.read(plain).frameCount)
    }
}
