package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Skitter
import com.snipsnap.synth.SkitterPresets
import com.snipsnap.synth.SkitterVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkitterCommandTest {
    @Test
    fun `Skitter CLI note energy and noise seed reach the saved audition`() {
        val dir = createTempDirectory("skitter-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(
                0,
                Cli.run(
                    arrayOf("synth", "sKiTtEr", "bAsS", "--preset", "1", "--midi", "65", "--velocity", ".4", "--seed", "17", "--out", dir.path),
                    PrintStream(output), PrintStream(ByteArrayOutputStream()),
                ),
            )
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            val preset = SkitterPresets.forVoice(SkitterVoice.BASS).first().copy(midi = 65, velocity = .4f, seed = 17)
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, preset.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "root, velocity and seed reach the deterministic render")
            val rendered = WavReader.read(wav)
            assertEquals(1, rendered.channels)
            assertEquals(44_100, rendered.sampleRate)
            assertTrue(rendered.peak() > 0f)
            assertTrue("1 rendered into" in output.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Skitter CLI scramble is repeatable and preserves note energy and noise seed`() {
        val dir = createTempDirectory("skitter-scramble-cli").toFile()
        try {
            val preset = SkitterPresets.forVoice(SkitterVoice.FRAY).first().copy(midi = 60, velocity = .55f, seed = -29)
            val expected = preset.copy(macros = Skitter.scramble(preset.voice, Random(7), near = preset))
            val expectedBytes = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            repeat(2) { run ->
                val destination = dir.resolve("run-$run")
                assertEquals(
                    0,
                    Cli.run(
                        arrayOf("synth", "SKITTER", "FRAY", "--preset", "1", "--midi", "60", "--velocity", ".55", "--seed", "-29", "--scramble", "7", "--out", destination.path),
                        PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()),
                    ),
                )
                val wav = destination.listFiles { f -> f.extension == "wav" }!!.single()
                assertTrue("Scrambled" in wav.name)
                assertTrue(expectedBytes.contentEquals(wav.readBytes()), "the same scramble seed regenerates the same variation")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Skitter CLI rejects invalid notes velocities seeds and incompatible audition shapes`() {
        val dir = createTempDirectory("skitter-invalid-cli").toFile()
        try {
            for (flags in listOf(
                arrayOf("--midi", (Skitter.MIDI_MIN - 1).toString()),
                arrayOf("--midi", (Skitter.MIDI_MAX + 1).toString()),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.1"),
                arrayOf("--seed", "1.5"), arrayOf("--seed", "2147483648"),
                arrayOf("--scramble", "oops"),
                arrayOf("--midi", "60", "--instrument"), arrayOf("--velocity", ".5", "--drone"),
            )) {
                val errors = ByteArrayOutputStream()
                assertTrue(
                    Cli.run(
                        arrayOf("synth", "SKITTER", "CHIRP", *flags, "--out", dir.path),
                        PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                    ) != 0,
                    flags.joinToString(),
                )
                assertTrue(errors.size() > 0)
                assertTrue(dir.listFiles()!!.isEmpty(), "invalid requests do not write auditions")
            }
            for (flag in listOf("--seed", "--scramble")) {
                val errors = ByteArrayOutputStream()
                assertTrue(
                    Cli.run(
                        arrayOf("synth", "TINES", "BELL", flag, "7", "--out", dir.path),
                        PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                    ) != 0,
                )
                assertTrue("only supported by SKITTER" in errors.toString())
                assertTrue(dir.listFiles()!!.isEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
