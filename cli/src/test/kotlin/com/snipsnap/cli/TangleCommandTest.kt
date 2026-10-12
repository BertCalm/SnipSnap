package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.TanglePresets
import com.snipsnap.synth.TangleVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TangleCommandTest {
    @Test
    fun `tangle CLI sends requested pitch and velocity to the renderer`() {
        val dir = createTempDirectory("tangle-cli").toFile()
        try {
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "tangle", "bass", "--preset", "1", "--midi", "43", "--velocity", ".4", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors),
            ), errors.toString())
            val wav = dir.listFiles { file -> file.extension == "wav" }!!.single()
            val patch = TanglePresets.forVoice(TangleVoice.BASS).first().copy(midi = 43, velocity = .4f)
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, patch.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "CLI must preserve the requested root and excitation energy")
            val decoded = WavReader.read(wav)
            assertEquals(44_100, decoded.sampleRate)
            assertEquals(1, decoded.channels)
            assertTrue(decoded.peak() > 0f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `invalid tangle note or velocity does not write audio`() {
        val dir = createTempDirectory("tangle-invalid-cli").toFile()
        try {
            for (flags in listOf(
                arrayOf("--midi", "23"), arrayOf("--midi", "85"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.1"),
            )) {
                val errors = ByteArrayOutputStream()
                assertTrue(Cli.run(
                    arrayOf("synth", "tangle", "lead", *flags, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ) != 0)
                assertTrue(errors.size() > 0)
                assertTrue(dir.listFiles()!!.isEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
