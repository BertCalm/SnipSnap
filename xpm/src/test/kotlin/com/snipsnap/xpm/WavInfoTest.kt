package com.snipsnap.xpm

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WavInfoTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("wavtest").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `reads a plain 16 bit stereo file`() {
        val file = wav(frames = 1000, channels = 2, bits = 16, rate = 44_100)
        val info = WavInfo.read(file)

        assertEquals(44_100, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(1000L, info.frameCount)
        assertTrue(info.isMpcNativeRate)
        assertTrue(info.isMpcNativeDepth)
    }

    @Test
    fun `reads 24 bit mono`() {
        val info = WavInfo.read(wav(frames = 512, channels = 1, bits = 24, rate = 44_100))
        assertEquals(512L, info.frameCount)
        assertEquals(24, info.bitsPerSample)
        assertTrue(info.isMpcNativeDepth)
    }

    @Test
    fun `skips chunks sitting between fmt and data`() {
        // Android's recorders and plenty of desktop encoders park LIST/fact
        // chunks ahead of the audio. Assuming fixed offsets would read garbage.
        val file = wav(frames = 300, channels = 2, bits = 16, rate = 44_100, extraChunk = "LIST" to 26)
        assertEquals(300L, WavInfo.read(file).frameCount)
    }

    @Test
    fun `handles an odd sized chunk needing word alignment`() {
        val file = wav(frames = 300, channels = 1, bits = 16, rate = 44_100, extraChunk = "fact" to 5)
        assertEquals(300L, WavInfo.read(file).frameCount)
    }

    @Test
    fun `falls back to file length when the data size is bogus`() {
        // An interrupted recording leaves a declared size of 0 with real audio
        // behind it. Trusting the header there would export silent pads.
        val file = wav(frames = 800, channels = 2, bits = 16, rate = 44_100, declaredDataSize = 0)
        assertEquals(800L, WavInfo.read(file).frameCount)
    }

    @Test
    fun `flags rates and depths the MPC will not take natively`() {
        val info = WavInfo.read(wav(frames = 100, channels = 2, bits = 32, rate = 48_000))
        assertFalse(info.isMpcNativeRate)
        assertFalse(info.isMpcNativeDepth)
    }

    @Test
    fun `rejects files that are not wavs`() {
        val notWav = File(temp, "nope.wav").apply { writeText("this is not a wav file at all") }
        assertFailsWith<IOException> { WavInfo.read(notWav) }

        val truncated = File(temp, "tiny.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IOException> { WavInfo.read(truncated) }
    }

    @Test
    fun `feeds slice end straight into a program`() {
        val file = wav(frames = 4321, channels = 1, bits = 24, rate = 44_100)
        val info = WavInfo.read(file)
        val xml = XpmWriter().write(
            DrumProgram("Kit", listOf(Pad(file.nameWithoutExtension, info.frameCount))),
        )
        assertTrue("<SliceEnd>4321</SliceEnd>" in xml)
    }

    /** Minimal but valid WAV, with optional junk chunk and optional lying data size. */
    private fun wav(
        frames: Int,
        channels: Int,
        bits: Int,
        rate: Int,
        extraChunk: Pair<String, Int>? = null,
        declaredDataSize: Int? = null,
    ): File {
        val bytesPerFrame = channels * (bits / 8)
        val dataBytes = frames * bytesPerFrame
        val body = ByteArrayOutputStream()

        body.writeTag("WAVE")

        body.writeTag("fmt ")
        body.writeLeInt(16)
        body.writeLeShort(1) // PCM
        body.writeLeShort(channels)
        body.writeLeInt(rate)
        body.writeLeInt(rate * bytesPerFrame)
        body.writeLeShort(bytesPerFrame)
        body.writeLeShort(bits)

        if (extraChunk != null) {
            val (tag, size) = extraChunk
            body.writeTag(tag)
            body.writeLeInt(size)
            body.write(ByteArray(size))
            if (size % 2 == 1) body.write(0) // word alignment pad byte
        }

        body.writeTag("data")
        body.writeLeInt(declaredDataSize ?: dataBytes)
        body.write(ByteArray(dataBytes))

        val out = ByteArrayOutputStream()
        out.writeTag("RIFF")
        out.writeLeInt(body.size())
        out.write(body.toByteArray())

        val name = "t${frames}_${channels}_${bits}_$rate${extraChunk?.first ?: ""}${declaredDataSize ?: ""}.wav"
        return File(temp, name.replace(" ", "")).apply { writeBytes(out.toByteArray()) }
    }

    private fun ByteArrayOutputStream.writeTag(tag: String) = write(tag.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeLeInt(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLeShort(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
    }
}
