package com.snipsnap.audio

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioIoRoundTripTest {

    private fun tone(frames: Int, hz: Double, rate: Int, channels: Int = 1) =
        Snip(
            FloatArray(frames * channels) { i ->
                0.5f * kotlin.math.sin(2.0 * Math.PI * hz * (i / channels) / rate).toFloat()
            },
            channels,
            rate,
        )

    @Test
    fun `writes reads and resamples a file to the device rate`() {
        val source = tone(44_100, hz = 440.0, rate = 44_100, channels = 2)
        val file = File.createTempFile("snipsnap-roundtrip", ".wav")
        file.deleteOnExit()

        WavWriter.write(file, source, WavWriter.BitDepth.PCM_24)
        val decoded = WavReader.read(file)
        assertEquals(2, decoded.channels)
        assertEquals(44_100, decoded.sampleRate)
        assertEquals(source.frameCount, decoded.frameCount)

        val baked = Resampler.resample(decoded, 48_000)
        assertEquals(48_000, baked.sampleRate)
        assertEquals(2, baked.channels)
        assertTrue(abs(baked.frameCount - 48_000) <= 1, "got ${baked.frameCount} frames")
        // A 440 Hz tone at half scale should still peak near 0.5 after conversion.
        assertTrue(abs(baked.peak() - 0.5f) < 0.02f, "peak ${baked.peak()}")
    }

    @Test
    fun `reads every wav in the golden corpus if any are present`() {
        // reference/golden/.gitignore blocks audio, so this is empty on a clean
        // clone and populated on a machine that has harvested packs. Skip rather
        // than fail when there is nothing to read.
        val root = File("../reference/golden")
        val wavs = root.walkTopDown().filter { it.isFile && it.extension.lowercase() == "wav" }.toList()
        if (wavs.isEmpty()) return

        for (wav in wavs) {
            val snip = WavReader.read(wav)
            assertTrue(snip.frameCount > 0, "${wav.name} decoded to no frames")
            assertTrue(snip.sampleRate > 0, "${wav.name} has sampleRate ${snip.sampleRate}")
            assertTrue(snip.peak() <= 1.001f, "${wav.name} peaks above full scale at ${snip.peak()}")
        }
    }
}
