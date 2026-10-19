package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.synth.Shoal
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
                val expected = ShoalPresets.forVoice(voice).first().copy(midi = 57, velocity = .35f).render()
                assertEquals(1, actual.channels)
                assertEquals(44_100, actual.sampleRate)
                assertEquals(expected.samples.size, actual.samples.size)
                assertTrue(actual.samples.indices.all { abs(actual.samples[it] - expected.samples[it]) <= 2f / 32767f },
                    "$voice CLI changed the root, energy or population recipe")
                assertTrue(file.name.startsWith("SHOAL_${voice.name}_"))
                assertTrue("1 rendered" in output.toString())
                assertFalse("lands with" in output.toString(), "first listen identities must stay dry")
            }
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
            assertTrue(requireNotNull(dir.listFiles()).isEmpty(), "invalid inputs produced audition files")
        } finally {
            dir.deleteRecursively()
        }
    }
}
