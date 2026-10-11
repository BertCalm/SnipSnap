package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.RevelPresets
import com.snipsnap.synth.RevelVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RevelCommandTest {
    @Test
    fun `CLI note and velocity overrides reach the same dry configured ensemble`() {
        val directory = java.nio.file.Files.createTempDirectory("revel-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "ReVeL", "ClOsE", "--all", "--midi", "65", "--velocity", ".4", "--out", directory.path),
                PrintStream(output), PrintStream(errors),
            ), errors.toString())
            val wav = directory.listFiles { file -> file.extension == "wav" }!!.single()
            assertEquals("REVEL_CLOSE_01_Close_Pass.wav", wav.name)
            val preset = RevelPresets.forVoice(RevelVoice.CLOSE).single()
            val patch = preset.copy(macros = preset.macros + ("TUNE" to 17f / 24f), velocity = .4f)
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, patch.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "CLI changed note, source energy or microphone configuration")
            val snip = WavReader.read(wav)
            assertEquals(1, snip.channels)
            assertEquals(44_100, snip.sampleRate)
            assertTrue(snip.peak() > 0f)
            assertTrue("1 rendered into" in output.toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid notes velocities and incompatible export shapes write no files`() {
        val directory = java.nio.file.Files.createTempDirectory("revel-cli-invalid").toFile()
        try {
            for (flags in listOf(
                arrayOf("--midi", "47"), arrayOf("--midi", "73"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.1"),
                arrayOf("--midi", "60", "--instrument"), arrayOf("--velocity", ".5", "--drone"),
            )) {
                val errors = ByteArrayOutputStream()
                assertTrue(Cli.run(
                    arrayOf("synth", "revel", "circle", *flags, "--out", directory.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ) != 0, flags.joinToString())
                assertTrue(errors.size() > 0)
                assertTrue(directory.listFiles()!!.isEmpty(), "invalid CLI request wrote audio")
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
