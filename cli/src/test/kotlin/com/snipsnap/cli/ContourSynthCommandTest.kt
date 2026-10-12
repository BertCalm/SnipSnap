package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPresets
import com.snipsnap.synth.ContourVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContourSynthCommandTest {
    @Test
    fun `Contour auditions retain structural presets and excitation velocity at the requested note`() {
        val dir = kotlin.io.path.createTempDirectory("contour-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            val presets = ContourPresets.forVoice(ContourVoice.KEY)
            assertEquals(0, SynthCommand.run(
                listOf("CONTOUR", "KEY", "--all", "--midi", "72", "--velocity", ".3", "--out", dir.path),
                PrintStream(output),
            ))
            val wavs = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).sortedBy { it.name }
            assertEquals(presets.size, wavs.size)
            for ((preset, wav) in presets.zip(wavs)) {
                // C5 is one octave above KEY's C4 root: the centered four-octave range maps it to .75.
                val expected = preset.copy(macros = preset.macros + ("TUNE" to .75f), velocity = .3f).render()
                val bytes = ByteArrayOutputStream().also { WavWriter.write(it, expected) }
                assertContentEquals(bytes.toByteArray(), wav.readBytes(), "${preset.name}: table pair and envelope survive CLI overrides")
                val actual = WavReader.read(wav)
                assertEquals(1, actual.channels)
                assertEquals(44_100, actual.sampleRate)
                assertTrue(actual.samples.all { it.isFinite() })
                assertTrue(actual.samples.any { kotlin.math.abs(it) > 1e-4f })
            }
            assertFalse("lands with" in output.toString(), "CONTOUR auditions are dry")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Contour note range and finite velocity are checked before any output is written`() {
        val root = kotlin.io.path.createTempDirectory("contour-cli-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            val low = ContourEngine.rootMidi(ContourVoice.BASS) - ContourEngine.TUNE_SEMITONES
            val high = ContourEngine.rootMidi(ContourVoice.BASS) + ContourEngine.TUNE_SEMITONES
            for ((flag, value) in listOf(
                "--midi" to (low - 1).toString(), "--midi" to (high + 1).toString(),
                "--midi" to "36.5", "--velocity" to "NaN", "--velocity" to "Infinity", "--velocity" to "1.01",
            )) {
                val err = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    arrayOf("synth", "CONTOUR", "BASS", flag, value, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(err),
                ))
                assertTrue(flag in err.toString(), err.toString())
            }
            assertFalse(dir.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
