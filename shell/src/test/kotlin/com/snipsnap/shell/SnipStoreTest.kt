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
        } finally { root.deleteRecursively() }
    }
}
