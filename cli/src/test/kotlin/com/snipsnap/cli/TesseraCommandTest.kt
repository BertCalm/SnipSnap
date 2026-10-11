package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Tessera
import com.snipsnap.synth.TesseraPresets
import com.snipsnap.synth.TesseraVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TesseraCommandTest {
    @Test
    fun `the CLI resolves Tessera names and renders requested pitch and hammer velocity`() {
        val dir = createTempDirectory("tessera-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            val code = Cli.run(
                arrayOf("synth", "tessera", "course", "--all", "--midi", "65", "--velocity", ".4", "--out", dir.path),
                PrintStream(output), PrintStream(ByteArrayOutputStream()),
            )
            assertEquals(0, code)
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            assertEquals("TESSERA_COURSE_01_Paired_Wire.wav", wav.name)
            val preset = TesseraPresets.forVoice(TesseraVoice.COURSE).single()
            val macros = preset.macros + ("TUNE" to (65 - Tessera.ROOT_MIDI) / Tessera.TUNE_SEMITONES.toFloat())
            val expected = ByteArrayOutputStream().also {
                WavWriter.write(it, Tessera.render(preset.voice, macros, velocity = .4f))
            }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "both overrides reach the deterministic engine render")
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
    fun `a selected Tessera held preset exports its settled buffer at the top note`() {
        val dir = createTempDirectory("tessera-held-cli").toFile()
        try {
            assertEquals(
                0,
                Cli.run(
                    arrayOf("synth", "TeSsErA", "ChAmBeR", "--preset", "2", "--midi", "72", "--velocity", ".35", "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()),
                ),
            )
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            assertEquals("TESSERA_CHAMBER_01_Held_Chamber.wav", wav.name)
            val rendered = WavReader.read(wav)
            assertTrue(rendered.peak() > 0f)
            val preset = TesseraPresets.forVoice(TesseraVoice.CHAMBER)[1]
            assertTrue(Tessera.isLoop(preset.macros.getValue("HOLD")))
            val expected = ByteArrayOutputStream().also {
                WavWriter.write(it, Tessera.render(preset.voice, preset.macros + ("TUNE" to 1f), velocity = .35f))
            }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "the selected held material survives WAV export")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Tessera auditions reject out-of-range notes and nonfinite velocity`() {
        val dir = createTempDirectory("tessera-invalid-cli").toFile()
        try {
            for (flags in listOf(arrayOf("--midi", "47"), arrayOf("--midi", "73"), arrayOf("--velocity", "NaN"))) {
                val errors = ByteArrayOutputStream()
                val code = Cli.run(
                    arrayOf("synth", "tessera", "wood", *flags, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                )
                assertTrue(code != 0, flags.joinToString())
                assertTrue(errors.size() > 0)
                assertTrue(dir.listFiles()!!.isEmpty(), "invalid values do not create an audition")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
