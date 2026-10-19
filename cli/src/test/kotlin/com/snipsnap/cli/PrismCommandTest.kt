package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismPresets
import com.snipsnap.synth.PrismVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrismCommandTest {
    @Test
    fun `additive CLI note and velocity overrides reach the selected saved template`() {
        val dir = createTempDirectory("prism-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "pRiSm", "pLuCk", "--preset", "1", "--midi", "65", "--velocity", ".4", "--out", dir.path),
                PrintStream(output), PrintStream(ByteArrayOutputStream()),
            ))
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            assertEquals("PRISM_PLUCK_01_Partial_Tines.wav", wav.name)
            val preset = PrismPresets.forVoice(PrismVoice.PLUCK).first()
            val expectedPatch = preset.copy(velocity = .4f,
                macros = preset.macros + ("TUNE" to (65 - Prism.rootMidi(preset.voice)) / Prism.TUNE_SEMITONES.toFloat()))
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, expectedPatch.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "requested root, energy and template reach the render")
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
    fun `additive CLI preserves held preset note and energy overrides`() {
        val dir = createTempDirectory("prism-held-cli").toFile()
        try {
            assertEquals(0, Cli.run(
                arrayOf("synth", "prism", "veil", "--preset", "2", "--midi", "60", "--velocity", ".35", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()),
            ))
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            assertEquals("PRISM_VEIL_01_Amber_Veil.wav", wav.name)
            val preset = PrismPresets.forVoice(PrismVoice.VEIL)[1]
            val expectedPatch = preset.copy(velocity = .35f,
                macros = preset.macros + ("TUNE" to (60 - Prism.rootMidi(preset.voice)) / Prism.TUNE_SEMITONES.toFloat()))
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, expectedPatch.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "HOLD and note state survive preset selection")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `expanded bank CLI selection retains the final veil template with note and velocity overrides`() {
        val dir = createTempDirectory("prism-bank-cli").toFile()
        try {
            val presets = PrismPresets.forVoice(PrismVoice.VEIL)
            val preset = presets.last()
            assertTrue(presets.size > 2, "select a newly authored preset beyond the original pair")
            assertTrue(preset.template != Prism.templateFor(preset.voice), "exercise a saved custom template")
            val output = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "prism", "veil", "--preset", presets.size.toString(),
                    "--midi", "67", "--velocity", ".45", "--out", dir.path),
                PrintStream(output), PrintStream(ByteArrayOutputStream()),
            ))
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            val expectedPatch = preset.copy(velocity = .45f,
                macros = preset.macros + ("TUNE" to (67 - Prism.rootMidi(preset.voice)) / Prism.TUNE_SEMITONES.toFloat()))
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, expectedPatch.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "the expanded bank keeps its authored envelope and group motion")
            assertTrue("1 rendered into" in output.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `additive CLI rejects unsupported notes energy and export shapes before writing`() {
        val dir = createTempDirectory("prism-invalid-cli").toFile()
        try {
            for ((voice, flags) in listOf(
                "key" to arrayOf("--midi", "47"), "key" to arrayOf("--midi", "73"),
                "bass" to arrayOf("--midi", "23"), "bass" to arrayOf("--midi", "49"),
                "glass" to arrayOf("--midi", "59"), "glass" to arrayOf("--midi", "85"),
                "key" to arrayOf("--velocity", "NaN"), "key" to arrayOf("--velocity", "1.1"),
                "key" to arrayOf("--midi", "60", "--instrument"),
                "key" to arrayOf("--velocity", ".5", "--drone"),
            )) {
                val errors = ByteArrayOutputStream()
                assertTrue(Cli.run(arrayOf("synth", "prism", voice, *flags, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)) != 0, flags.joinToString())
                assertTrue(errors.size() > 0)
                assertTrue(dir.listFiles()!!.isEmpty(), "invalid auditions write nothing")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
