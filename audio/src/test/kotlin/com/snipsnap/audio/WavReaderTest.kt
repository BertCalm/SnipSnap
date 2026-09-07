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

    // Spans the full ±1.0 range (index 0 hits -1.0f, index 200 hits 1.0f
    // exactly) so the round-trip tests below exercise full scale, not just
    // the interior where the tolerance bound has slack to spare.
    private fun ramp(frames: Int, channels: Int = 1) =
        Snip(FloatArray(frames * channels) { (it % 201) / 100f - 1f }, channels, 44_100)

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

    // The encoder rounds x * 32767 to the nearest integer PCM code (error
    // <= 0.5 code) and the decoder divides that code by the same 32767, so
    // the decode error is exactly the rounding: at most half a code.
    private val PCM16_TOLERANCE = 0.5f / 32767f + 1e-7f

    @Test
    fun `a write then a read is the identity - the file that goes through the bin comes back the same`() {
        for (depth in listOf(WavWriter.BitDepth.PCM_16, WavWriter.BitDepth.PCM_24)) {
            val once = roundTrip(ramp(512, channels = 2), depth)
            val first = ByteArrayOutputStream().also { WavWriter.write(it, once, depth) }.toByteArray()
            val twice = WavReader.read(first)
            assertTrue(once.samples.contentEquals(twice.samples), "$depth: reading what was written gives the same floats")
            val second = ByteArrayOutputStream().also { WavWriter.write(it, twice, depth) }.toByteArray()
            assertTrue(first.contentEquals(second), "$depth: writing them again gives the same bytes")
        }
        // Full scale reads back as full scale, both ways.
        val rails = roundTrip(Snip(floatArrayOf(1f, -1f, 0f), 1, 44_100), WavWriter.BitDepth.PCM_16)
        assertEquals(1f, rails.samples[0])
        assertEquals(-1f, rails.samples[1])
        assertEquals(0f, rails.samples[2])
    }

    @Test
    fun `the most negative code - the one the writer never emits - clamps to -1 rather than overshooting`() {
        // 16-bit: -32768, +32767, 0.
        val pcm16 = WavReader.read(wav(1, 16, 1, 44_100, byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F, 0, 0)))
        assertEquals(-1f, pcm16.samples[0], "-32768 is -1.0, not -1.00003")
        assertEquals(1f, pcm16.samples[1])
        assertEquals(0f, pcm16.samples[2])
        // 24-bit: -8388608, +8388607.
        val pcm24 = WavReader.read(wav(1, 24, 1, 44_100, byteArrayOf(0x00, 0x00, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)))
        assertEquals(-1f, pcm24.samples[0], "-8388608 is -1.0")
        assertEquals(1f, pcm24.samples[1])
        // 32-bit: -2147483648, +2147483647, and a non-dyadic code divided in Double.
        val pcm32 = WavReader.read(
            wav(1, 32, 1, 44_100, byteArrayOf(0, 0, 0, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x01, 0, 0, 0x40)),
        )
        assertEquals(-1f, pcm32.samples[0], "-2^31 is -1.0")
        assertEquals(1f, pcm32.samples[1])
        assertEquals((0x40000001 / 2_147_483_647.0).toFloat(), pcm32.samples[2])
        // 8-bit: 0 (the most negative), 255, 128 (silence).
        val pcm8 = WavReader.read(wav(1, 8, 1, 44_100, byteArrayOf(0, 0xFF.toByte(), 0x80.toByte())))
        assertEquals(-1f, pcm8.samples[0])
        assertEquals(1f, pcm8.samples[1])
        assertEquals(0f, pcm8.samples[2])
    }

    @Test
    fun `reads back a 16 bit mono wav`() {
        val original = ramp(512)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_16)
        assertSamplesClose(original, decoded, tolerance = PCM16_TOLERANCE)
    }

    @Test
    fun `reads back a 16 bit stereo wav preserving channel order`() {
        // Left rail high, right rail low: a channel swap or interleave bug shows immediately.
        val original = Snip(FloatArray(400) { if (it % 2 == 0) 0.75f else -0.75f }, 2, 44_100)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_16)
        assertSamplesClose(original, decoded, tolerance = PCM16_TOLERANCE)
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
    fun `extra chunks around the data are skipped, not mis-read`() {
        // Real-world WAVs carry LIST/INFO, fact, JUNK padding and cue chunks
        // before and after the audio. WavReader must step past every unknown
        // chunk and land the same samples as a bare fmt+data twin.
        val payload = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0) // 4 mono 16-bit frames
        val plain = wav(1, 16, 1, 44_100, payload)

        fun chunk(id: String, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(id.toByteArray(Charsets.US_ASCII))
            val n = body.size
            out.write(byteArrayOf((n and 0xFF).toByte(), ((n ushr 8) and 0xFF).toByte(),
                ((n ushr 16) and 0xFF).toByte(), ((n ushr 24) and 0xFF).toByte()))
            out.write(body)
            if (n % 2 == 1) out.write(0) // word alignment
            return out.toByteArray()
        }
        val fmtBody = ByteArray(16).also {
            it[0] = 1; it[2] = 1 // PCM, 1 channel
            it[4] = (44_100 and 0xFF).toByte(); it[5] = ((44_100 ushr 8) and 0xFF).toByte()
            it[6] = ((44_100 ushr 16) and 0xFF).toByte()
            it[14] = 16 // bits
        }
        // byteRate/blockAlign fields left as the reader ignores them here.
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray(Charsets.US_ASCII))
            write(chunk("JUNK", ByteArray(7))) // odd size, before fmt
            write(chunk("fmt ", fmtBody))
            write(chunk("LIST", "INFOIART".toByteArray(Charsets.US_ASCII))) // between fmt and data
            write(chunk("data", payload))
            write(chunk("fact", byteArrayOf(4, 0, 0, 0))) // after data
            write(chunk("cue ", byteArrayOf(0, 0, 0, 0)))
        }.toByteArray()
        val multi = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            val n = body.size
            write(byteArrayOf((n and 0xFF).toByte(), ((n ushr 8) and 0xFF).toByte(),
                ((n ushr 16) and 0xFF).toByte(), ((n ushr 24) and 0xFF).toByte()))
            write(body)
        }.toByteArray()

        val a = WavReader.read(plain)
        val b = WavReader.read(multi)
        assertEquals(a.channels, b.channels)
        assertEquals(a.frameCount, b.frameCount, "the same audio, extra chunks and all")
        assertTrue(a.samples.contentEquals(b.samples), "extra chunks did not corrupt the decode")
    }

    @Test
    fun `a float wav carrying NaN and Inf decodes to finite silence`() {
        // Four float samples: NaN, +Inf, -Inf, and an honest 0.5.
        val payload = ByteArrayOutputStream()
        fun le32(v: Int) {
            payload.write(v and 0xFF); payload.write((v ushr 8) and 0xFF)
            payload.write((v ushr 16) and 0xFF); payload.write((v ushr 24) and 0xFF)
        }
        le32(Float.NaN.toRawBits())
        le32(Float.POSITIVE_INFINITY.toRawBits())
        le32(Float.NEGATIVE_INFINITY.toRawBits())
        le32(0.5f.toRawBits())
        val snip = WavReader.read(wav(3, 32, 1, 44_100, payload.toByteArray()))

        assertTrue(snip.samples.all { it.isFinite() }, "no non-finite sample survives the decode")
        assertEquals(0f, snip.samples[0]); assertEquals(0f, snip.samples[1]); assertEquals(0f, snip.samples[2])
        assertTrue(kotlin.math.abs(snip.samples[3] - 0.5f) < 1e-6f, "the honest sample is untouched")
    }

    @Test
    fun `reads back a 24 bit wav`() {
        // Same structure as the 16-bit tolerance above: encoder rounds
        // x * 8_388_607, decoder divides by 8_388_608, so the bound is
        // (0.5 + |x|) / 8_388_608, reaching 1.5/8_388_608 at full scale.
        val original = ramp(512)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_24)
        assertSamplesClose(original, decoded, tolerance = 1.5f / 8_388_608f)
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

    @Test
    fun `reads 32 bit signed integer pcm`() {
        // Dyadic values so the expected float literals are exact: no sample
        // depth this fine survives Float's 24-bit mantissa anyway, so the
        // real precision ceiling here is Float itself, not the 32-bit word.
        val values = intArrayOf(0, 0x40000000, -0x40000000, Int.MIN_VALUE, Int.MAX_VALUE)
        val payload = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) {
            payload[i * 4] = (v and 0xFF).toByte()
            payload[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            payload[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            payload[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        val decoded = WavReader.read(wav(1, 32, 1, 44_100, payload))
        assertEquals(5, decoded.frameCount)
        val expected = floatArrayOf(0f, 0.5f, -0.5f, -1.0f, 1.0f)
        for (i in expected.indices) {
            assertTrue(
                abs(expected[i] - decoded.samples[i]) < 1e-7f,
                "sample $i: expected ${expected[i]}, got ${decoded.samples[i]}",
            )
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
    fun `decodes the frames present in a data chunk truncated mid write`() {
        // WavWriter writes the declared data size up front, before the
        // sample bytes stream out — a capture killed mid-write leaves a
        // file whose data chunk declares more bytes than the file actually
        // holds. That must decode the frames that ARE there, not fail with
        // "no data chunk".
        val fullPayload = ByteArray(40) { (it + 1).toByte() } // 20 mono 16-bit frames
        val full = wav(1, 16, 1, 44_100, fullPayload)
        val truncated = full.copyOf(full.size - 12) // drop the last 6 frames' worth of bytes

        val decoded = WavReader.read(truncated)
        assertEquals(14, decoded.frameCount, "should decode only the frames physically present")
        val fullyDecoded = WavReader.read(full)
        for (i in decoded.samples.indices) {
            assertEquals(fullyDecoded.samples[i], decoded.samples[i], "sample $i")
        }
    }

    @Test
    fun `does not crash when a hostile data size overflows int arithmetic`() {
        // A corrupt or hostile header can set the declared data size near
        // Int.MAX_VALUE. `body + size` computed as Int would silently wrap
        // negative and slip past a bounds check meant to catch overruns,
        // eventually reading out of the array or blowing the heap. The
        // reader must fall back to the bytes actually present, the same as
        // an ordinary truncated tail, rather than crash.
        val payload = byteArrayOf(1, 2, 3, 4) // 2 mono 16-bit frames
        val bytes = wav(1, 16, 1, 44_100, payload)
        // Data chunk size field: "RIFF"+size+"WAVE" (12) + "fmt "+size+16
        // body bytes (24) + "data" tag (4) = byte offset 40.
        val sizeFieldAt = 40
        val hostileSize = 0x7FFFFFF0
        bytes[sizeFieldAt] = (hostileSize and 0xFF).toByte()
        bytes[sizeFieldAt + 1] = ((hostileSize ushr 8) and 0xFF).toByte()
        bytes[sizeFieldAt + 2] = ((hostileSize ushr 16) and 0xFF).toByte()
        bytes[sizeFieldAt + 3] = ((hostileSize ushr 24) and 0xFF).toByte()

        val decoded = WavReader.read(bytes)
        assertEquals(2, decoded.frameCount, "should fall back to the bytes actually present")
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
