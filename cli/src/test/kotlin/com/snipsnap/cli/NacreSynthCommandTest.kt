package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Nacre
import com.snipsnap.synth.NacrePresets
import com.snipsnap.synth.NacreVoice
import com.snipsnap.synth.Presets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NacreSynthCommandTest {
    @Test
    fun `all finite PEARL auditions use provisional recipes and preserve note energy and seed`() {
        val dir = kotlin.io.path.createTempDirectory("nacre-cli").toFile()
        try {
            assertTrue(Presets.forVoice("NACRE", "PEARL").isEmpty())
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(
                listOf("NACRE", "PEARL", "--all", "--midi", "57", "--velocity", ".35", "--out", dir.path),
                PrintStream(output),
            ))
            val recipes = NacrePresets.forVoice(NacreVoice.PEARL)
            val wavs = dir.listFiles { file -> file.extension == "wav" }!!.sortedBy { it.name }
            assertEquals(recipes.size, wavs.size)
            val expected = recipes.first().copy(midi = 57, velocity = .35f)
            val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            assertContentEquals(encoded, wavs.first().readBytes(), "native note and strike overrides must retain the saved seed")
            assertTrue("${recipes.size} rendered" in output.toString())
            assertFalse("lands with" in output.toString(), "the underwater venue is part of the dry engine")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `invalid notes energy and voices refuse before creating output`() {
        val temp = kotlin.io.path.createTempDirectory("nacre-cli-invalid").toFile()
        val dir = File(temp, "not-created")
        try {
            val cases = listOf(
                "--midi" to listOf((Nacre.MIN_MIDI - 1).toString(), (Nacre.MAX_MIDI + 1).toString(), "48.5", "NaN"),
                "--velocity" to listOf("-.01", "1.01", "NaN", "Infinity"),
            )
            for ((flag, values) in cases) for (value in values) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    arrayOf("synth", "NACRE", "PEARL", flag, value, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ))
                assertTrue(flag in errors.toString())
            }
            val errors = ByteArrayOutputStream()
            assertEquals(2, Cli.run(
                arrayOf("synth", "NACRE", "UNKNOWN", "--all", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors),
            ))
            assertTrue("no such engine/voice: NACRE UNKNOWN" in errors.toString())
            assertFalse(dir.exists())
        } finally {
            temp.deleteRecursively()
        }
    }
}
