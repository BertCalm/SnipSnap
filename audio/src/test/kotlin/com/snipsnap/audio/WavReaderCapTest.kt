package com.snipsnap.audio

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The frame-capped read: the share sheet's WAV door is byte-gated, but a
 * byte budget does not bound a decoded allocation (96 MB of 8-bit mono
 * is ~96M frames as floats). The cap is judged from the header, before
 * the float buffer exists.
 */
class WavReaderCapTest {

    private fun wavBytes(frames: Int): ByteArray {
        val snip = Snip(FloatArray(frames) { 0.25f }, channels = 1, sampleRate = 44_100)
        return ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_16) }.toByteArray()
    }

    @Test
    fun `a header over the frame cap is refused before decoding`() {
        val bytes = wavBytes(frames = 1_000)
        val e = assertFailsWith<IllegalArgumentException> { WavReader.read(bytes, maxFrames = 999) }
        assertTrue(e.message!!.contains("1000"), "message names the real frame count: ${e.message}")
    }

    @Test
    fun `a header at the frame cap decodes whole`() {
        val out = WavReader.read(wavBytes(frames = 1_000), maxFrames = 1_000)
        assertEquals(1_000, out.frameCount)
    }

    @Test
    fun `the uncapped read keeps its old contract`() {
        assertEquals(1_000, WavReader.read(wavBytes(frames = 1_000)).frameCount)
    }
}
