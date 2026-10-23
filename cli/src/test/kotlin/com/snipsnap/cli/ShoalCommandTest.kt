package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.synth.Shoal
import com.snipsnap.synth.ShoalMaterial
import com.snipsnap.synth.ShoalPresets
import com.snipsnap.synth.ShoalVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShoalCommandTest {
    @Test
    fun `both first listen voices export the requested root and native energy dry`() {
        val dir = kotlin.io.path.createTempDirectory("shoal-cli").toFile()
        try {
            for (voice in ShoalVoice.entries) {
                val output = ByteArrayOutputStream()
                val destination = File(dir, voice.name)
                assertEquals(0, SynthCommand.run(
                    listOf("SHOAL", voice.name, "--preset", "1", "--midi", "57", "--velocity", "0.35", "--out", destination.path),
                    PrintStream(output),
                ))
                val file = requireNotNull(destination.listFiles { file -> file.extension == "wav" }).single()
                val actual = WavReader.read(file)
                val recipe = ShoalPresets.forVoice(voice).first().copy(midi = 57, velocity = .35f)
                val expected = recipe.render()
                assertEquals(1, actual.channels)
                assertEquals(44_100, actual.sampleRate)
                assertEquals(expected.samples.size, actual.samples.size)
                assertTrue(actual.samples.indices.all { abs(actual.samples[it] - expected.samples[it]) <= 2f / 32767f },
                    "$voice CLI changed the root, energy or population recipe")
                assertTrue(file.name.startsWith("SHOAL_${voice.name}_${recipe.material.name}_"))
                assertTrue("1 rendered" in output.toString())
                assertFalse("lands with" in output.toString(), "first listen identities must stay dry")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `material override reaches native synthesis and names its output`() {
        val dir = kotlin.io.path.createTempDirectory("shoal-cli-material").toFile()
        try {
            val voice = ShoalVoice.RIPPLE
            val presets = ShoalPresets.forVoice(voice)
            val index = presets.indexOfFirst { !Shoal.isLoop(it.macros["HOLD"] ?: 0f) }
            assertTrue(index >= 0)
            assertEquals(0, SynthCommand.run(
                listOf("SHOAL", voice.name, "--preset", "${index + 1}", "--material", "wire",
                    "--midi", "48", "--velocity", "0.45", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            val file = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).single()
            val actual = WavReader.read(file)
            val expected = presets[index].copy(midi = 48, velocity = .45f,
                model = Shoal.MODEL_VERSION, material = ShoalMaterial.WIRE).render()
            assertEquals(expected.samples.size, actual.samples.size)
            assertTrue(actual.samples.indices.all { abs(actual.samples[it] - expected.samples[it]) <= 2f / 32767f },
                "--material failed to regenerate the requested body")
            assertTrue(file.name.startsWith("SHOAL_RIPPLE_WIRE_"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `root and energy flags reject invalid inputs before rendering`() {
        val dir = kotlin.io.path.createTempDirectory("shoal-cli-refusals").toFile()
        try {
            val base = listOf("SHOAL", "GLASS", "--out", dir.path)
            for (value in listOf((Shoal.MIN_MIDI - 1).toString(), (Shoal.MAX_MIDI + 1).toString(), "57.5", "NaN")) {
                assertFailsWith<CliError> { SynthCommand.run(base + listOf("--midi", value), PrintStream(ByteArrayOutputStream())) }
            }
            for (value in listOf("-0.1", "1.1", "Infinity", "NaN")) {
                assertFailsWith<CliError> { SynthCommand.run(base + listOf("--velocity", value), PrintStream(ByteArrayOutputStream())) }
            }
            assertFailsWith<CliError> { SynthCommand.run(base + listOf("--midi", "57", "--instrument"), PrintStream(ByteArrayOutputStream())) }
            assertFailsWith<CliError> { SynthCommand.run(base + listOf("--material", "STONE"), PrintStream(ByteArrayOutputStream())) }
            val wrongEngine = assertFailsWith<CliError> {
                SynthCommand.run(listOf("PRISM", "GLASS", "--material", "WOOD", "--out", dir.path), PrintStream(ByteArrayOutputStream()))
            }
            assertTrue(wrongEngine.message.orEmpty().contains("only supported by SHOAL"))
            assertTrue(requireNotNull(dir.listFiles()).isEmpty(), "invalid inputs produced audition files")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unsupported material hold in all presets is refused before creating any outputs`() {
        val dir = kotlin.io.path.createTempDirectory("shoal-cli-held-material").toFile()
        try {
            assertTrue(ShoalPresets.forVoice(ShoalVoice.GLASS).any { Shoal.isLoop(it.macros["HOLD"] ?: 0f) })
            val destination = File(dir, "audition")
            val error = assertFailsWith<CliError> {
                SynthCommand.run(listOf("SHOAL", "GLASS", "--all", "--material", "WOOD", "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()))
            }
            assertTrue(error.message.orEmpty().contains("HOLD"))
            assertFalse(destination.exists(), "unsupported --all wrote a partial material audition")
        } finally {
            dir.deleteRecursively()
        }
    }
}
