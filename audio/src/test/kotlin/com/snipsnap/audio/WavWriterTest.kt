package com.snipsnap.audio

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.WavInfo
import com.snipsnap.xpm.XpmWriter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavWriterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("wavwriter").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun snip(frames: Int, channels: Int = 1, value: Float = 0.5f) =
        Snip(FloatArray(frames * channels) { value }, channels, 44_100)

    @Test
    fun `a non-finite sample never crashes the writer or lands in the file`() {
        // roundToInt throws on NaN; a DSP bug that slips a NaN through must
        // not crash the export or write garbage - it becomes silence.
        val poisoned = Snip(floatArrayOf(0.5f, Float.NaN, Float.POSITIVE_INFINITY, -0.25f, Float.NEGATIVE_INFINITY), 1, 44_100)
        for (depth in WavWriter.BitDepth.entries) {
            val file = WavWriter.write(File(temp, "poison_$depth.wav"), poisoned, depth)
            val back = WavReader.read(file)
            assertTrue(back.samples.all { it.isFinite() }, "$depth: output is all finite")
            assertTrue(kotlin.math.abs(back.samples[1]) < 1e-3f, "$depth: the NaN became silence")
            assertTrue(kotlin.math.abs(back.samples[2]) < 1e-3f, "$depth: the +Inf became silence")
        }
    }

    @Test
    fun `round-trips through the reader the exporter uses`() {
        // The writer and the reader have to agree, or SliceEnd comes out wrong
        // and every pad in the kit is truncated.
        val file = WavWriter.write(File(temp, "a.wav"), snip(1000, channels = 2))
        val info = WavInfo.read(file)

        assertEquals(44_100, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(24, info.bitsPerSample)
        assertEquals(1000L, info.frameCount)
        assertTrue(info.isMpcNativeRate)
        assertTrue(info.isMpcNativeDepth)
    }

    @Test
    fun `writes 16 bit when asked`() {
        val file = WavWriter.write(File(temp, "b.wav"), snip(500), WavWriter.BitDepth.PCM_16)
        val info = WavInfo.read(file)

        assertEquals(16, info.bitsPerSample)
        assertEquals(500L, info.frameCount)
    }

    @Test
    fun `file size matches the declared format`() {
        val file = WavWriter.write(File(temp, "c.wav"), snip(100, channels = 2))
        // 44 byte header + 100 frames * 2 channels * 3 bytes
        assertEquals(44L + 600L, file.length())
    }

    @Test
    fun `pads an odd sized data chunk`() {
        // 24-bit with an odd sample count gives an odd data size; RIFF chunks
        // are word-aligned, so a pad byte is required.
        val file = WavWriter.write(File(temp, "odd.wav"), snip(3, channels = 1))
        assertEquals(0L, file.length() % 2)
        assertEquals(3L, WavInfo.read(file).frameCount)
    }

    @Test
    fun `full scale does not wrap to negative`() {
        // Scaling by 32768 would send +1.0 to -32768 — a full-scale click on the
        // loudest sample of every normalized snip.
        val file = WavWriter.write(File(temp, "hot.wav"), snip(10, value = 1.0f), WavWriter.BitDepth.PCM_16)
        val bytes = file.readBytes()

        val firstSample = ((bytes[45].toInt() and 0xFF) shl 8) or (bytes[44].toInt() and 0xFF)
        val signed = firstSample.toShort().toInt()
        assertEquals(32767, signed)
    }

    @Test
    fun `clamps out-of-range input`() {
        val wild = Snip(floatArrayOf(5f, -5f, 0f), 1, 44_100)
        val file = WavWriter.write(File(temp, "wild.wav"), wild, WavWriter.BitDepth.PCM_16)
        val bytes = file.readBytes()

        fun sampleAt(i: Int): Int {
            val off = 44 + i * 2
            return (((bytes[off + 1].toInt() and 0xFF) shl 8) or (bytes[off].toInt() and 0xFF)).toShort().toInt()
        }

        assertEquals(32767, sampleAt(0))
        assertEquals(-32767, sampleAt(1))
        assertEquals(0, sampleAt(2))
    }

    @Test
    fun `refuses a rate the MPC will not take`() {
        val wrongRate = Snip(FloatArray(100), 1, 48_000)
        assertFailsWith<IllegalArgumentException> { WavWriter.write(File(temp, "d.wav"), wrongRate) }

        // ...unless explicitly allowed, for non-MPC uses.
        val file = WavWriter.write(File(temp, "e.wav"), wrongRate, allowNonMpcRate = true)
        assertEquals(48_000, WavInfo.read(file).sampleRate)
    }

    @Test
    fun `writes an empty snip without corrupting the header`() {
        val file = WavWriter.write(File(temp, "empty.wav"), Snip(FloatArray(0), 1, 44_100))
        assertEquals(0L, WavInfo.read(file).frameCount)
    }

    @Test
    fun `creates missing directories`() {
        val nested = File(temp, "kits/kit01/kick.wav")
        WavWriter.write(nested, snip(10))
        assertTrue(nested.exists())
    }

    @Test
    fun `capture to loadable kit, end to end`() {
        // The whole export path in one test: ring buffer -> snapshot -> cleanup
        // -> WAV -> frame count -> .xpm. If the frame counts disagree anywhere,
        // this is where it shows up.
        val rb = RingBuffer.ofSeconds(2f, channels = 1, sampleRate = 44_100)
        val bias = 0.01f // captured audio often carries a little DC
        rb.write(FloatArray(1000) { bias })              // leading silence
        rb.write(
            FloatArray(5000) { i ->                      // the sound
                bias + 0.2f * kotlin.math.sin(2.0 * Math.PI * 220.0 * i / 44_100).toFloat()
            },
        )
        rb.write(FloatArray(1000) { bias })              // trailing silence

        val captured = Snip(rb.snapshot(), channels = 1, sampleRate = 44_100)
        val cleaned = Cleanup.process(captured)

        val kitDir = File(temp, "kit").apply { mkdirs() }
        val wav = WavWriter.write(File(kitDir, "SS_Kick_01.wav"), cleaned)
        val info = WavInfo.read(wav)

        assertEquals(cleaned.frameCount.toLong(), info.frameCount)
        assertTrue(info.frameCount < 7000, "silence should have been trimmed")

        val xpm = XpmWriter().writeTo(
            kitDir,
            DrumProgram("SnipSnap Kit 01", listOf(Pad("SS_Kick_01", info.frameCount))),
        )

        assertTrue(xpm.exists())
        assertTrue("<SliceEnd>${info.frameCount}</SliceEnd>" in xpm.readText())
        assertTrue("<SampleName>SS_Kick_01</SampleName>" in xpm.readText())

        // A loadable kit is the .xpm plus its WAVs, side by side.
        assertEquals(
            setOf("SnipSnap Kit 01.xpm", "SS_Kick_01.wav"),
            kitDir.listFiles()!!.map { it.name }.toSet(),
        )
    }
}
