package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircuitCommandTest {

    @Test
    fun `the synth CLI renders Circuit presets through the shared dispatcher`() {
        val dir = createTempDirectory("circuit-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            val code = Cli.run(
                arrayOf("synth", "circuit", "root", "--preset", "1", "--out", dir.path),
                PrintStream(output),
                PrintStream(ByteArrayOutputStream()),
            )
            assertEquals(0, code)
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            assertEquals("CIRCUIT_ROOT_01_THREE_BREATHS.wav", wav.name)
            val rendered = WavReader.read(wav)
            assertEquals(1, rendered.channels)
            assertEquals(44_100, rendered.sampleRate)
            assertTrue(rendered.durationSeconds in 3f..8f)
            assertTrue(rendered.peak() > 0f)
            assertTrue("1 rendered into" in output.toString())
        } finally {
            dir.deleteRecursively()
        }
    }
}
