package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

class StereoPerformanceImportTest {
    @Test fun `performance shelf import preserves opposing channels through MPC rate conversion while ordinary import stays mono`() {
        val root = Files.createTempDirectory("stereo-performance").toFile()
        try {
            val audio = Snip(FloatArray(48000 * 2) {
                val x = (.4 * sin(2 * Math.PI * 220 * (it / 2) / 48000)).toFloat()
                if (it % 2 == 0) x else -x
            }, 2, 48000)
            val saved = SnipStore.import(audio, root, 1, preserveStereo = true)
            val wave = WavReader.read(saved.file)
            assertFalse(saved.truncated); assertEquals(48000, saved.resampledFrom)
            assertEquals(44100, wave.sampleRate); assertEquals(2, wave.channels); assertEquals(44100, wave.frameCount)
            assertTrue(wave.samples.maxOf { abs(it) } > .39f)
            assertTrue(wave.samples.indices.step(2).all { abs(wave.samples[it] + wave.samples[it + 1]) < 2e-6f })
            val ordinary = WavReader.read(SnipStore.import(audio, root, 2).file)
            assertEquals(1, ordinary.channels); assertEquals(0f, ordinary.samples.maxOf { abs(it) })
        } finally { root.deleteRecursively() }
    }

    @Test fun `stereo duration cap counts frames and retains both channels`() {
        val root = Files.createTempDirectory("stereo-performance-cap").toFile()
        try {
            val frames = (SnipStore.IMPORT_MAX_SEC * 44100).toInt()
            val audio = Snip(FloatArray((frames + 2) * 2) { if (it % 2 == 0) .2f else -.3f }, 2, 44100)
            val saved = SnipStore.import(audio, root, 1, preserveStereo = true)
            val wave = WavReader.read(saved.file)
            assertTrue(saved.truncated); assertEquals(frames, wave.frameCount); assertEquals(2, wave.channels)
            assertEquals(.2f, wave.samples[wave.samples.size - 2], 2e-6f)
            assertEquals(-.3f, wave.samples.last(), 2e-6f)
        } finally { root.deleteRecursively() }
    }
}
