package com.snipsnap.audio

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecodeContractTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("decode").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `an honest decode passes - including a WAV round trip`() {
        for (rate in DecodeContract.FIXTURE_RATES) {
            for (channels in intArrayOf(1, 2)) {
                val expected = DecodeContract.fixture(rate, channels)
                val f = File(temp, "f_${rate}_$channels.wav")
                WavWriter.write(f, expected, allowNonMpcRate = true)
                val report = DecodeContract.verify(WavReader.read(f), expected)
                assertTrue(report.pass, "$rate/$channels: ${report.issues}")
                assertTrue(report.correlation > 0.99, "$rate/$channels lossless corr ${report.correlation}")
            }
        }
    }

    @Test
    fun `codec-legitimate damage passes - delay, mild loss, other rate`() {
        val expected = DecodeContract.fixture(44_100, 1)

        // Encoder delay: ~23ms of leading padding (an AAC priming quantum).
        val delayed = FloatArray(1024 + expected.samples.size)
        expected.samples.copyInto(delayed, 1024)
        val d1 = DecodeContract.verify(Snip(delayed, 1, 44_100), expected)
        assertTrue(d1.pass, "encoder delay must be tolerated: ${d1.issues}")
        assertTrue(d1.offsetFrames in 1000..1050, "offset ${d1.offsetFrames}")

        // Mild spectral loss: a one-pole lowpass and slight gain change.
        val lossy = FloatArray(expected.samples.size)
        var prev = 0f
        for (i in lossy.indices) {
            prev += (expected.samples[i] - prev) * 0.6f
            lossy[i] = prev * 0.85f
        }
        assertTrue(DecodeContract.verify(Snip(lossy, 1, 44_100), expected).pass)

        // Decoder handed back 48k: resampled inside verify.
        val at48 = Resampler.resample(expected, 48_000)
        assertTrue(DecodeContract.verify(at48, expected).pass)
    }

    @Test
    fun `wrong decodes fail with named reasons`() {
        val expected = DecodeContract.fixture(44_100, 2)

        // Wrong channel count.
        val monoized = Cleanup.toMono(expected)
        val r1 = DecodeContract.verify(monoized, expected)
        assertFalse(r1.pass)
        assertTrue(r1.issues.any { "channel" in it })

        // Truncated to half.
        val half = Snip(expected.samples.copyOf(expected.samples.size / 2), 2, 44_100)
        val r2 = DecodeContract.verify(half, expected)
        assertFalse(r2.pass)
        assertTrue(r2.issues.any { "duration" in it })

        // Right shape, wrong content (a detuned tone).
        val wrong = DecodeContract.fixture(44_100, 1)
        val detuned = FloatArray(wrong.samples.size)
        for (i in detuned.indices) {
            detuned[i] = (0.5 * kotlin.math.sin(2 * Math.PI * 470.0 * i / 44_100)).toFloat()
        }
        val r3 = DecodeContract.verify(Snip(detuned, 1, 44_100), wrong)
        assertFalse(r3.pass)
        assertTrue(r3.issues.any { "correlation" in it })
    }

    @Test
    fun `the fixture set writes six canonical files`() {
        val files = DecodeContract.writeFixtures(File(temp, "fixtures"))
        assertEquals(6, files.size)
        assertTrue(files.all { it.isFile && it.length() > 1000 })
        assertTrue(files.any { it.name == "decode_48000hz_stereo.wav" })
        // And they read back as what they claim to be.
        val s = WavReader.read(files.first { it.name == "decode_22050hz_mono.wav" })
        assertEquals(22_050, s.sampleRate)
        assertEquals(1, s.channels)
    }
}
