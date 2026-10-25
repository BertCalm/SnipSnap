package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Filament
import com.snipsnap.synth.FilamentPresets
import com.snipsnap.synth.FilamentVoice
import com.snipsnap.synth.Presets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilamentSynthCommandTest {
    @Test
    fun `all renders the approved factory thread recipes dry`() {
        val dir = kotlin.io.path.createTempDirectory("filament-cli-all").toFile()
        try {
            assertEquals(FilamentPresets.forVoice(FilamentVoice.THREAD), Presets.forVoice("FILAMENT", "THREAD"))
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(
                listOf("FILAMENT", "THREAD", "--all", "--out", dir.path), PrintStream(output),
            ))
            val presets = Presets.forVoice("FILAMENT", "THREAD")
            val wavs = dir.listFiles { file -> file.extension == "wav" }!!.sortedBy { it.name }
            assertEquals(presets.size, wavs.size)
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, presets.first().render()) }.toByteArray()
            assertContentEquals(expected, wavs.first().readBytes(), "CLI uses the authored patch render")
            assertTrue("${presets.size} rendered" in output.toString())
            assertFalse("lands with" in output.toString(), "FILAMENT's halo comes from its own escaped input")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `note and velocity overrides preserve the selected curls geometry`() {
        val dir = kotlin.io.path.createTempDirectory("filament-cli-geometry").toFile()
        try {
            val source = FilamentPresets.forVoice(FilamentVoice.CURL)[2]
            val expected = source.copy(midi = 67, velocity = .35f)
            assertEquals(0, SynthCommand.run(
                listOf("FILAMENT", "CURL", "--preset", "3", "--midi", "67", "--velocity", ".35", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            val wav = dir.listFiles { file -> file.extension == "wav" }!!.single()
            assertTrue("Spilled_Silver" in wav.name)
            val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            assertContentEquals(encoded, wav.readBytes(), "native note overrides retain the seed, profile and six controls")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `invalid filament auditions fail before creating output`() {
        val temp = kotlin.io.path.createTempDirectory("filament-cli-invalid").toFile()
        val dir = File(temp, "not-created")
        try {
            val cases = listOf(
                listOf("THREAD", "--midi", (Filament.MIN_MIDI - 1).toString()),
                listOf("THREAD", "--midi", (Filament.MAX_MIDI + 1).toString()),
                listOf("THREAD", "--midi", "60.5"),
                listOf("THREAD", "--velocity", "NaN"),
                listOf("THREAD", "--velocity", "1.01"),
                listOf("UNKNOWN", "--all"),
            )
            for (arguments in cases) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    (listOf("synth", "FILAMENT") + arguments + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ))
                assertTrue(errors.size() > 0, arguments.toString())
                assertFalse("Exception" in errors.toString())
            }
            assertFalse(dir.exists())
        } finally {
            temp.deleteRecursively()
        }
    }
}
