package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Embouchure
import com.snipsnap.synth.EmbouchurePresets
import com.snipsnap.synth.EmbouchureVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbouchureSynthCommandTest {
    @Test
    fun `command line preserves presets and explicit MIDI overrides the tune offset`() {
        val root = kotlin.io.path.createTempDirectory("embouchure-cli").toFile()
        try {
            val presets = EmbouchurePresets.forVoice(EmbouchureVoice.REED)
            val untouched = File(root, "untouched")
            assertEquals(0, SynthCommand.run(listOf("EMBOUCHURE", "REED", "--all", "--out", untouched.path),
                PrintStream(ByteArrayOutputStream())))
            val wavs = requireNotNull(untouched.listFiles { it.extension == "wav" }).sortedBy { it.name }
            assertEquals(presets.size, wavs.size)
            for (i in presets.indices) {
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, presets[i].render()) }.toByteArray()
                assertContentEquals(expected, wavs[i].readBytes(), "omitting flags must retain each saved playing gesture")
            }
            val overridden = File(root, "override")
            val midi = Embouchure.midiRange(EmbouchureVoice.REED).last
            assertEquals(0, SynthCommand.run(listOf("EMBOUCHURE", "REED", "--preset", "1", "--midi", "$midi",
                "--velocity", "0.35", "--out", overridden.path), PrintStream(ByteArrayOutputStream())))
            val explicit = presets.first().copy(midi = midi, velocity = .35f,
                macros = presets.first().macros + ("TUNE" to 0f))
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, explicit.render()) }.toByteArray()
            assertContentEquals(expected, requireNotNull(overridden.listFiles { it.extension == "wav" }).single().readBytes(),
                "the top supported MIDI note must be played directly, without adding the preset's TUNE offset")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unsupported voice registers and nonfinite velocity fail before output creation`() {
        val root = kotlin.io.path.createTempDirectory("embouchure-cli-invalid").toFile()
        val destination = File(root, "not-created")
        try {
            for (voice in EmbouchureVoice.entries) {
                val range = Embouchure.midiRange(voice)
                val invalid = listOf(
                    "--midi" to listOf("NaN", "60.5", "2147483648", "${range.first - 1}", "${range.last + 1}"),
                    "--velocity" to listOf("NaN", "Infinity", "-Infinity", "-0.01", "1.001", "1e40"),
                )
                for ((flag, values) in invalid) for (value in values) {
                    val errors = ByteArrayOutputStream()
                    val code = Cli.run(arrayOf("synth", "EMBOUCHURE", voice.name, flag, value, "--out", destination.path),
                        PrintStream(ByteArrayOutputStream()), PrintStream(errors))
                    assertEquals(2, code, "$voice $flag=$value")
                    assertTrue(flag in errors.toString(), "refusal must identify $flag: $errors")
                    assertFalse("Exception" in errors.toString())
                }
            }
            assertFalse(destination.exists(), "invalid input created an output directory")
        } finally {
            root.deleteRecursively()
        }
    }
}
