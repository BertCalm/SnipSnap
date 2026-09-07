package com.snipsnap.shell

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnipStoreTest {

    private fun tone(seconds: Float, rate: Int = 44_100): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { (0.4f * sin(2.0 * Math.PI * 220.0 * it / rate)).toFloat() }
    }

    @Test
    fun `a commit lands one readable WAV in the snips dir`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val f = SnipStore.commit(tone(1f), 44_100, root, nowMillis = 1_000_000L)
            assertTrue(f.isFile && f.parentFile.name == SnipStore.DIR)
            val back = com.snipsnap.audio.WavReader.read(f)
            assertEquals(44_100, back.sampleRate)
            assertTrue(back.frameCount > 0)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `leading dead air is trimmed by the commit chain`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val silence = FloatArray(44_100)                 // 1s of nothing
            val f = SnipStore.commit(silence + tone(0.5f), 44_100, root, 2_000_000L)
            val back = com.snipsnap.audio.WavReader.read(f)
            assertTrue(
                back.frameCount < 44_100,
                "the silent first second should not survive the commit chain: ${back.frameCount}",
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `an import folds to mono, lands at 44100, and keeps its length`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val rate = 48_000
            val n = 2 * rate
            val stereo = FloatArray(n * 2)
            for (f in 0 until n) {
                val t = f.toDouble() / rate
                stereo[2 * f] = (0.4 * sin(2.0 * Math.PI * 440.0 * t)).toFloat()
                stereo[2 * f + 1] = (0.4 * sin(2.0 * Math.PI * 554.0 * t)).toFloat()
            }
            val decoded = com.snipsnap.audio.Snip(stereo, channels = 2, sampleRate = rate)
            val landed = SnipStore.importDecoded(decoded, root, 3_000L)
            assertTrue(!landed.truncated)
            assertEquals(landed.file, SnipStore.newest(root), "an import is the newest snip, like any capture")
            val back = com.snipsnap.audio.WavReader.read(landed.file)
            assertEquals(1, back.channels)
            assertEquals(44_100, back.sampleRate)
            val expected = 2 * 44_100
            assertTrue(
                kotlin.math.abs(back.frameCount - expected) < 2_205,
                "2 s at 48 k should land near 2 s at 44.1 k, got ${back.frameCount}",
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `an import longer than the cap keeps the head and says so`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val rate = 8_000
            val long = tone((SnipStore.IMPORT_MAX_SECONDS + 5).toFloat(), rate)
            val landed = SnipStore.importDecoded(com.snipsnap.audio.Snip(long, 1, rate), root, 4_000L)
            assertTrue(landed.truncated)
            val back = com.snipsnap.audio.WavReader.read(landed.file)
            val cap = SnipStore.IMPORT_MAX_SECONDS * 44_100
            assertTrue(back.frameCount <= cap, "nothing past the cap survives: ${back.frameCount} > $cap")
            assertTrue(back.frameCount > cap - 44_100, "but the whole head does: ${back.frameCount}")
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `an empty decode is refused in words`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val e = kotlin.test.assertFailsWith<IllegalArgumentException> {
                SnipStore.importDecoded(com.snipsnap.audio.Snip(FloatArray(0), 1, 44_100), root, 5_000L)
            }
            assertTrue("nothing to pull out" in e.message!!)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `list is newest first and newest agrees`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val a = SnipStore.commit(tone(0.2f), 44_100, root, 1_000L)
            val b = SnipStore.commit(tone(0.2f), 44_100, root, 2_000L)
            assertEquals(listOf(b, a), SnipStore.list(root))
            assertEquals(b, SnipStore.newest(root))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `an all-silent snip still commits rather than throwing`() {
        // The user pressed SNIP; the honest outcome of a silent minute is a
        // short (possibly minimal) file, not an exception in a service.
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val f = SnipStore.commit(FloatArray(44_100), 44_100, root, 3_000L)
            assertTrue(f.isFile)
            // Small, not the whole silent minute: a real armed session
            // rings 60s, and writing that out as literal zeros every quiet
            // SNIP would be dishonest with "commits small" and wasteful of
            // flash either way.
            val back = com.snipsnap.audio.WavReader.read(f)
            assertTrue(
                back.frameCount in 1 until 44_100 / 4,
                "expected a short fallback slice, got ${back.frameCount} frames",
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a quiet room does not get normalised into a screech`() {
        // Below Cleanup's default -60dB threshold (linear ~0.001) but NOT
        // exact zero, unlike the all-silence test above — this is the
        // buffer shape that actually exercises the fallback's normalize
        // step: real noise-floor hiss, not a clean no-op on true silence.
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val rand = kotlin.random.Random(42)
            val noise = FloatArray(44_100) { (rand.nextFloat() * 2f - 1f) * 0.0003f }
            var inputPeak = 0f
            for (s in noise) inputPeak = maxOf(inputPeak, kotlin.math.abs(s))

            val f = SnipStore.commit(noise, 44_100, root, 4_000L)
            val back = com.snipsnap.audio.WavReader.read(f)
            var outputPeak = 0f
            for (s in back.samples) outputPeak = maxOf(outputPeak, kotlin.math.abs(s))

            assertTrue(
                outputPeak <= inputPeak * 4f,
                "quiet noise should stay quiet, not get normalised toward full scale: " +
                    "input peak $inputPeak, output peak $outputPeak",
            )
        } finally { root.deleteRecursively() }
    }
}
