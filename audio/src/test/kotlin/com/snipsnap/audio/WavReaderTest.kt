package com.snipsnap.audio

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavReaderTest {

    /** Round-trip helper: encode with the writer we already trust, decode with the new reader. */
    private fun roundTrip(snip: Snip, depth: WavWriter.BitDepth): Snip {
        val out = ByteArrayOutputStream()
        WavWriter.write(out, snip, depth)
        return WavReader.read(out.toByteArray())
    }

    private fun ramp(frames: Int, channels: Int = 1) =
        Snip(FloatArray(frames * channels) { (it % 200) / 200f - 0.5f }, channels, 44_100)

    /**
     * Snip.equals compares format and length only, never sample values — so a
     * naive assertEquals would pass on completely different audio.
     */
    private fun assertSamplesClose(expected: Snip, actual: Snip, tolerance: Float) {
        assertEquals(expected.channels, actual.channels, "channels")
        assertEquals(expected.sampleRate, actual.sampleRate, "sampleRate")
        assertEquals(expected.frameCount, actual.frameCount, "frameCount")
        for (i in expected.samples.indices) {
            val d = abs(expected.samples[i] - actual.samples[i])
            assertTrue(d <= tolerance, "sample $i: expected ${expected.samples[i]}, got ${actual.samples[i]}")
        }
    }

    @Test
    fun `reads back a 16 bit mono wav`() {
        val original = ramp(512)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_16)
        // 16-bit quantisation step is 1/32768; allow one step.
        assertSamplesClose(original, decoded, tolerance = 1f / 32768f)
    }

    @Test
    fun `reads back a 16 bit stereo wav preserving channel order`() {
        // Left rail high, right rail low: a channel swap or interleave bug shows immediately.
        val original = Snip(FloatArray(400) { if (it % 2 == 0) 0.75f else -0.75f }, 2, 44_100)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_16)
        assertSamplesClose(original, decoded, tolerance = 1f / 32768f)
    }

    /** Hand-build a WAV so we can produce depths WavWriter cannot emit. */
    private fun wav(
        formatCode: Int,
        bits: Int,
        channels: Int,
        sampleRate: Int,
        payload: ByteArray,
    ): ByteArray {
        val blockAlign = channels * (bits / 8)
        val out = ByteArrayOutputStream()
        fun tag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        tag("RIFF"); le32(36 + payload.size); tag("WAVE")
        tag("fmt "); le32(16)
        le16(formatCode); le16(channels); le32(sampleRate)
        le32(sampleRate * blockAlign); le16(blockAlign); le16(bits)
        tag("data"); le32(payload.size); out.write(payload)
        return out.toByteArray()
    }

    @Test
    fun `reads back a 24 bit wav`() {
        val original = ramp(512)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_24)
        assertSamplesClose(original, decoded, tolerance = 1f / 8_388_608f)
    }

    @Test
    fun `reads 8 bit unsigned pcm`() {
        // 8-bit WAV is unsigned with 128 as silence.
        val payload = byteArrayOf(128.toByte(), 255.toByte(), 0, 192.toByte())
        val decoded = WavReader.read(wav(1, 8, 1, 44_100, payload))
        assertEquals(4, decoded.frameCount)
        assertTrue(abs(0f - decoded.samples[0]) <= 1f / 128f, "silence")
        assertTrue(decoded.samples[1] > 0.9f, "full positive")
        assertTrue(decoded.samples[2] < -0.9f, "full negative")
        assertTrue(decoded.samples[3] > 0.4f, "half positive")
    }

    @Test
    fun `reads 32 bit float pcm`() {
        val values = floatArrayOf(0f, 0.5f, -0.25f, 1f)
        val payload = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) {
            val b = v.toRawBits()
            payload[i * 4] = (b and 0xFF).toByte()
            payload[i * 4 + 1] = ((b ushr 8) and 0xFF).toByte()
            payload[i * 4 + 2] = ((b ushr 16) and 0xFF).toByte()
            payload[i * 4 + 3] = ((b ushr 24) and 0xFF).toByte()
        }
        val decoded = WavReader.read(wav(3, 32, 1, 48_000, payload))
        assertEquals(48_000, decoded.sampleRate)
        for (i in values.indices) {
            assertEquals(values[i], decoded.samples[i], "sample $i")
        }
    }
}
