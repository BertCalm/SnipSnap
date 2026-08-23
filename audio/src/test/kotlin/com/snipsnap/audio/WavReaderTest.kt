package com.snipsnap.audio

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** Build a WAV with a metadata chunk sitting between fmt and data. */
    private fun wavWithChunkBefore(
        chunkId: String,
        chunkBody: ByteArray,
        formatCode: Int = 1,
        fmtExtra: ByteArray = ByteArray(0),
        bits: Int = 16,
        payload: ByteArray = byteArrayOf(0, 0, 0, 64),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun tag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        val fmtSize = 16 + fmtExtra.size
        tag("RIFF"); le32(4 + 8 + fmtSize + 8 + chunkBody.size + 8 + payload.size); tag("WAVE")
        tag("fmt "); le32(fmtSize)
        le16(formatCode); le16(1); le32(44_100)
        le32(44_100 * 2); le16(2); le16(bits)
        out.write(fmtExtra)
        tag(chunkId); le32(chunkBody.size); out.write(chunkBody)
        if (chunkBody.size % 2 == 1) out.write(0) // word-align pad
        tag("data"); le32(payload.size); out.write(payload)
        return out.toByteArray()
    }

    @Test
    fun `skips a LIST metadata chunk before data`() {
        val decoded = WavReader.read(wavWithChunkBefore("LIST", "INFOsome tag".toByteArray()))
        assertEquals(2, decoded.frameCount)
        assertEquals(44_100, decoded.sampleRate)
    }

    @Test
    fun `skips an odd length chunk and stays word aligned`() {
        // A 5-byte chunk is followed by a pad byte; miss it and every later
        // chunk id reads one byte off and the data chunk is never found.
        val decoded = WavReader.read(wavWithChunkBefore("fact", byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(2, decoded.frameCount)
    }

    @Test
    fun `resolves extensible format to its subformat`() {
        // cbSize=22, validBits=16, channelMask=3, then a 16-byte GUID whose
        // first two bytes carry the real format code.
        // fmt body: 16 cbSize, 18 validBits, 20..23 channelMask, 24..39 GUID.
        // extra[] starts at body+16, so the GUID begins at extra[8].
        val extra = ByteArray(24)
        extra[0] = 22; extra[2] = 16; extra[4] = 3
        extra[8] = 1 // SubFormat GUID's first two bytes = PCM
        val decoded = WavReader.read(
            wavWithChunkBefore("LIST", "INFO".toByteArray(), formatCode = 0xFFFE, fmtExtra = extra)
        )
        assertEquals(2, decoded.frameCount)
    }

    @Test
    fun `rejects a file that is not a wav`() {
        val e = assertFailsWith<IllegalArgumentException> {
            WavReader.read("this is plainly not audio".toByteArray())
        }
        assertTrue(e.message!!.contains("RIFF"), "message should name the problem: ${e.message}")
    }

    @Test
    fun `rejects a wav with no data chunk`() {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); out.write(byteArrayOf(20, 0, 0, 0))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(byteArrayOf(16, 0, 0, 0))
        out.write(ByteArray(16))
        val e = assertFailsWith<IllegalArgumentException> { WavReader.read(out.toByteArray()) }
        assertTrue(e.message!!.contains("data"), "message should name the problem: ${e.message}")
    }

    @Test
    fun `rejects a fmt chunk truncated inside the header`() {
        // Declares a 16-byte fmt body but the file physically ends one byte
        // short of that: body+16 == bytes.size+1, which the walker's bounds
        // check tolerates (it allows exactly one missing pad byte), so parsing
        // proceeds into the fmt fields and reading bits at body+14 runs off
        // the end of the array.
        val out = ByteArrayOutputStream()
        fun tag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        tag("RIFF"); le32(4 + 8 + 16); tag("WAVE")
        tag("fmt "); le32(16)
        // Only 15 of the declared 16 body bytes actually follow.
        le16(1); le16(1); le32(44_100); le32(44_100 * 2); le16(2)
        // bits field (2 bytes) truncated to 1 byte: file ends here.
        out.write(0)
        val e = assertFailsWith<IllegalArgumentException> { WavReader.read(out.toByteArray()) }
        assertTrue(e.message!!.contains("fmt"), "message should name the problem: ${e.message}")
    }

    @Test
    fun `rejects a fmt chunk with zero channels`() {
        val decoded = wav(1, 16, 1, 44_100, byteArrayOf(0, 0, 0, 0))
        // Overwrite the channel count field (fmt body offset 2, right after
        // RIFF+WAVE+fmt tag+size = 12 + 8 = 20, so channels is at 22..23) with 0.
        decoded[22] = 0
        decoded[23] = 0
        val e = assertFailsWith<IllegalArgumentException> { WavReader.read(decoded) }
        assertTrue(e.message!!.contains("channels"), "message should name the problem: ${e.message}")
        assertTrue(e.message!!.contains("0"), "message should name the actual value: ${e.message}")
    }
}
