package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SutureSynthCommandTest {
    @Test
    fun `Suture factory presets render through the command line as mono vessel gestures`() {
        val dir = kotlin.io.path.createTempDirectory("suture-cli").toFile()
        try {
            val bytes = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(listOf("SUTURE", "BLOOM", "--all", "--out", dir.path), PrintStream(bytes)))
            val wavs = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).sortedBy { it.name }
            assertEquals(2, wavs.size)
            assertTrue(wavs.all { it.name.startsWith("SUTURE_BLOOM_") })
            for (file in wavs) {
                val snip = WavReader.read(file)
                assertEquals(1, snip.channels)
                assertEquals(44_100, snip.sampleRate)
                assertTrue(snip.frameCount > 0 && snip.samples.all { it.isFinite() })
            }
            assertTrue("2 rendered" in bytes.toString())
        } finally {
            dir.deleteRecursively()
        }
    }
}
