package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.ApsisConfig
import com.snipsnap.synth.ApsisPatch
import com.snipsnap.synth.ApsisPresets
import com.snipsnap.synth.ApsisVoice
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApsisSynthCommandTest {
    @Test
    fun `new finite and held populations export their own saved identity and native performance`() {
        val directory = createTempDirectory("apsis-populations-cli").toFile()
        try {
            for (voice in listOf(ApsisVoice.GRAIN, ApsisVoice.ANCHOR)) for (held in listOf(false, true)) {
                val patch = ApsisPresets.forVoice(voice).let { if (held) it.last() else it.first() }
                    .copy(config = ApsisConfig(3, Int.MIN_VALUE))
                val source = File(directory, "$voice-$held.json").also { it.writeText(patch.toJsonText()) }
                val output = File(directory, "$voice-$held")
                assertEquals(0, SynthCommand.run(listOf("APSIS", voice.name, "--patch", source.path,
                    "--midi", "57", "--velocity", ".4", "--out", output.path), PrintStream(ByteArrayOutputStream())))
                val expected = patch.copy(midi = 57, velocity = .4f)
                val recipe = requireNotNull(output.listFiles { it.extension == "patch" }).single()
                assertEquals(expected, Patches.fromJsonText(recipe.readText()))
                val wav = requireNotNull(output.listFiles { it.extension == "wav" }).single()
                assertContentEquals(ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray(), wav.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `the CLI exports dry native audio and its exact replayable recipe`() {
        val directory = createTempDirectory("apsis-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(listOf("APSIS", "PASSAGE", "--preset", "2", "--midi", "63",
                "--velocity", ".35", "--seed", Int.MIN_VALUE.toString(), "--macros", "STRETCH=.6,ALIGN=.2",
                "--out", directory.path), PrintStream(output)))
            val expected = ApsisPresets.all()[1].let { it.copy(midi = 63, velocity = .35f,
                macros = it.macros + mapOf("STRETCH" to .6f, "ALIGN" to .2f),
                config = ApsisConfig(seedContext = Int.MIN_VALUE)) }
            val recipe = requireNotNull(directory.listFiles { it.extension == "patch" }).single()
            assertEquals(expected, Patches.fromJsonText(recipe.readText()))
            assertEquals(3, expected.config.modelVersion)
            val wav = requireNotNull(directory.listFiles { it.extension == "wav" }).single()
            assertContentEquals(ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray(), wav.readBytes())
            assertTrue("1 rendered into" in output.toString())
            assertFalse("lands with" in output.toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `saved model one recipes retain their renderer through performance and macro overrides`() {
        val directory = createTempDirectory("apsis-cli-patch").toFile()
        try {
            val patch = ApsisPresets.all()[2].copy(midi = 48, velocity = .4f,
                config = ApsisConfig(modelVersion = 1, seedContext = 78))
            val source = File(directory, "source.json").also { it.writeText(patch.toJsonText()) }
            val destination = File(directory, "output")
            assertEquals(0, SynthCommand.run(listOf("APSIS", "PASSAGE", "--patch", source.path,
                "--midi", "67", "--velocity", ".6", "--macros", "COUPLE=.8", "--out", destination.path), PrintStream(ByteArrayOutputStream())))
            val actual = ApsisPatch.fromJsonText(requireNotNull(destination.listFiles { it.extension == "patch" }).single().readText())
            val expected = patch.copy(midi = 67, velocity = .6f, macros = patch.macros + ("COUPLE" to .8f))
            assertEquals(expected, actual)
            val wav = requireNotNull(destination.listFiles { it.extension == "wav" }).single()
            assertContentEquals(ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray(), wav.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid controls and unsupported routes fail before creating output`() {
        val directory = createTempDirectory("apsis-cli-invalid").toFile()
        val destination = File(directory, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "35"), arrayOf("--midi", "85"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.1"),
                arrayOf("--seed", "1.5"), arrayOf("--seed", "2147483648"),
                arrayOf("--macros", "TUNE=.5"), arrayOf("--macros", "COUPLE=NaN"),
                arrayOf("--macros", "COUPLE=.5,COUPLE=.2"), arrayOf("--macros", "COUPLE=.5", "--macros", "ALIGN=.2"),
                arrayOf("--instrument"), arrayOf("--drone"), arrayOf("--patch", "missing.json"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "APSIS", "PASSAGE", *flags, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.joinToString())
                assertTrue(errors.size() > 0)
                assertFalse(destination.exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
