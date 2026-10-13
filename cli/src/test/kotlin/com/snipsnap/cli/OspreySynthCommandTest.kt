package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.OspreyPresets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OspreySynthCommandTest {
    @Test
    fun `all finite audition recipes receive note energy seed and duration overrides`() {
        val directory = createTempDirectory("osprey-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(arrayOf(
                "synth", "oSpReY", "sWeLl", "--all", "--midi", "63", "--velocity", ".65",
                "--seed", "-71", "--seconds", ".25", "--out", directory.path,
            ), PrintStream(output), PrintStream(errors)), errors.toString())
            val files = directory.listFiles { it.extension == "wav" }!!.sortedBy { it.name }
            val presets = OspreyPresets.all()
            assertEquals(15, files.size)
            for ((index, preset) in presets.withIndex()) {
                val patch = preset.copy(midi = 63, velocity = .65f, seed = -71, durationSeconds = .25f)
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, patch.render()) }.toByteArray()
                assertContentEquals(expected, files[index].readBytes(), "all recipe fields reach ${preset.name}")
                val actual = WavReader.read(files[index])
                assertEquals(2, actual.channels)
                assertEquals(44_100, actual.sampleRate)
                assertEquals(.25, actual.durationSeconds.toDouble(), .0001)
                assertTrue(actual.peak() > 0f)
            }
            assertTrue("15 rendered into" in output.toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid finite audition controls and unsupported held shapes fail before writing`() {
        val directory = createTempDirectory("osprey-cli-invalid").toFile()
        val destination = File(directory, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "35"), arrayOf("--midi", "85"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.01"),
                arrayOf("--seed", "1.5"), arrayOf("--seed", "2147483648"),
                arrayOf("--seconds", ".24"), arrayOf("--seconds", "12.01"),
                arrayOf("--seconds", "NaN"), arrayOf("--seconds", "Infinity"),
                arrayOf("--instrument"), arrayOf("--drone"), arrayOf("--scramble", "3"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "OSPREY", "SWELL", *flags, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.joinToString())
                assertTrue(errors.size() > 0)
                assertFalse(destination.exists(), "invalid input must not create an audition directory")
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
