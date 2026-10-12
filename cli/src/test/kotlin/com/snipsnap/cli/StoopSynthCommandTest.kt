package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.synth.Stoop
import com.snipsnap.synth.StoopPresets
import com.snipsnap.synth.StoopVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoopSynthCommandTest {
    @Test
    fun `each groove family renders its authored recipe with explicit note and velocity`() {
        val dir = kotlin.io.path.createTempDirectory("stoop-cli").toFile()
        try {
            for (voice in StoopVoice.entries) {
                val output = ByteArrayOutputStream()
                val destination = java.io.File(dir, voice.name)
                assertEquals(0, SynthCommand.run(
                    listOf("STOOP", voice.name, "--preset", "1", "--midi", "60", "--velocity", "0.35", "--out", destination.path),
                    PrintStream(output),
                ))
                val file = requireNotNull(destination.listFiles { file -> file.extension == "wav" }).single()
                val actual = WavReader.read(file)
                val expected = StoopPresets.forVoice(voice).first().copy(midi = 60, velocity = .35f).render()
                assertEquals(1, actual.channels)
                assertEquals(44_100, actual.sampleRate)
                assertEquals(expected.samples.size, actual.samples.size)
                assertTrue(actual.samples.indices.all { abs(actual.samples[it] - expected.samples[it]) <= 2f / 32767f }, "${voice.name}: CLI retains its source and gesture")
                assertTrue(file.name.startsWith("STOOP_${voice.name}_"))
                assertTrue("1 rendered" in output.toString())
                assertFalse("lands with" in output.toString(), "STOOP lands dry")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `note and velocity flags reject invalid and incompatible Stoop auditions`() {
        val dir = kotlin.io.path.createTempDirectory("stoop-cli-refusals").toFile()
        try {
            val base = listOf("STOOP", "BASS", "--out", dir.path)
            for (value in listOf((Stoop.MIDI_MIN - 1).toString(), (Stoop.MIDI_MAX + 1).toString(), "60.5", "NaN")) {
                assertFailsWith<CliError> { SynthCommand.run(base + listOf("--midi", value), PrintStream(ByteArrayOutputStream())) }
            }
            for (value in listOf("-0.1", "1.1", "Infinity", "NaN")) {
                assertFailsWith<CliError> { SynthCommand.run(base + listOf("--velocity", value), PrintStream(ByteArrayOutputStream())) }
            }
            assertFailsWith<CliError> { SynthCommand.run(base + listOf("--midi", "60", "--instrument"), PrintStream(ByteArrayOutputStream())) }
            assertFailsWith<CliError> { SynthCommand.run(base + listOf("--velocity", "0.5", "--drone"), PrintStream(ByteArrayOutputStream())) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
