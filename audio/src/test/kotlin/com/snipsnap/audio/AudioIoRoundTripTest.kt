package com.snipsnap.audio

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioIoRoundTripTest {

    /**
     * Left and right carry different frequencies and amplitudes on purpose: a
     * fixture where both channels hold identical content can't expose a
     * channel swap or scramble anywhere in the write -> read -> resample
     * chain. Left: 440 Hz at 0.5. Right: 220 Hz at 0.25.
     */
    private fun stereoTone(frames: Int, rate: Int): Snip {
        val samples = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val t = frame.toDouble() / rate
            samples[frame * 2] = (0.5 * kotlin.math.sin(2.0 * Math.PI * 440.0 * t)).toFloat()
            samples[frame * 2 + 1] = (0.25 * kotlin.math.sin(2.0 * Math.PI * 220.0 * t)).toFloat()
        }
        return Snip(samples, 2, rate)
    }

    @Test
    fun `writes reads and resamples a file to the device rate`() {
        val source = stereoTone(44_100, rate = 44_100)
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

        // Per-channel check at an interior frame, well clear of both buffer
        // edges (the sinc kernel's window is truncated near the edges), against
        // the continuous-time formula the source was generated from. Because
        // left and right differ in both frequency and amplitude, a channel
        // swap or scramble anywhere in the write -> read -> resample chain
        // shows up here as a mismatch against the WRONG channel's expected
        // value, not just a wrong number.
        val frame = 20_000
        val t = frame.toDouble() / baked.sampleRate
        val expectedLeft = (0.5 * kotlin.math.sin(2.0 * Math.PI * 440.0 * t)).toFloat()
        val expectedRight = (0.25 * kotlin.math.sin(2.0 * Math.PI * 220.0 * t)).toFloat()
        val actualLeft = baked.samples[frame * 2]
        val actualRight = baked.samples[frame * 2 + 1]
        assertTrue(
            abs(actualLeft - expectedLeft) < 1e-2f,
            "left channel at frame $frame: expected $expectedLeft got $actualLeft",
        )
        assertTrue(
            abs(actualRight - expectedRight) < 1e-2f,
            "right channel at frame $frame: expected $expectedRight got $actualRight",
        )
    }

    @Test
    fun `reads every wav in the golden corpus if any are present`() {
        // reference/golden/.gitignore blocks audio, so this is empty on a clean
        // clone and populated on a machine that has harvested packs. Skip rather
        // than fail when there is nothing to read.
        val root = File("../reference/golden")
        // The directory itself IS tracked (README.md, .gitignore, and the
        // harvested non-audio reference files live in it) — only the .wav
        // payloads are gitignored. So a missing directory means the test is
        // running from the wrong working directory, not a clean clone; that
        // must fail loudly rather than silently pass as "no wavs found".
        // walkTopDown() on a missing directory returns an empty sequence
        // without throwing, which is exactly the silent failure this guards.
        assertTrue(
            root.isDirectory,
            "golden corpus directory not found at ${root.absolutePath} — " +
                "wrong working directory (expected Gradle's module dir)?",
        )
        val wavs = root.walkTopDown().filter { it.isFile && it.extension.lowercase() == "wav" }.toList()
        if (wavs.isEmpty()) {
            // Legitimate on a clean clone: this verifies ZERO files. Printed
            // so it can't be mistaken for having actually exercised the
            // corpus below.
            println(
                "[AudioIoRoundTripTest] golden corpus at ${root.absolutePath} has no .wav files " +
                    "— 0 files verified by this test run",
            )
            return
        }

        for (wav in wavs) {
            val snip = WavReader.read(wav)
            assertTrue(snip.frameCount > 0, "${wav.name} decoded to no frames")
            assertTrue(snip.sampleRate > 0, "${wav.name} has sampleRate ${snip.sampleRate}")
            // Not a full-scale check: 32-bit float WAVs are legitimately allowed
            // headroom above 1.0, so a peak over full scale is not itself a
            // defect. Integer PCM cannot exceed full scale by construction, so
            // the only genuine corruption signal here that holds for every
            // format is a non-finite sample (NaN/Infinity).
            val nonFinite = snip.samples.indexOfFirst { !it.isFinite() }
            assertTrue(nonFinite < 0, "${wav.name} has a non-finite sample at index $nonFinite")
        }
    }
}
