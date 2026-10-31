package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.ScannedChainPresets
import com.snipsnap.synth.ScannedChainVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScannedChainCommandTest {
    @Test
    fun `scanned auditions retain the saved shape and physics while overriding note and energy`() {
        val dir = kotlin.io.path.createTempDirectory("scanned-chain-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(arrayOf("synth", "scanned_chain", "bloom", "--all",
                "--midi", "65", "--velocity", ".35", "--out", dir.path),
                PrintStream(output), PrintStream(errors)), errors.toString())
            val wavs = assertNotNullFiles(dir).sortedBy { it.name }
            val presets = ScannedChainPresets.forVoice(ScannedChainVoice.BLOOM)
            assertEquals(presets.size, wavs.size)
            for ((preset, wav) in presets.zip(wavs)) {
                val expectedPatch = preset.copy(midi = 65, velocity = .35f)
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, expectedPatch.render()) }
                assertContentEquals(expected.toByteArray(), wav.readBytes(), "CLI preserves ${preset.name}'s complete recipe")
                val actual = WavReader.read(wav)
                assertEquals(44_100, actual.sampleRate)
                assertEquals(1, actual.channels)
                assertTrue(actual.samples.all { it.isFinite() })
                assertTrue(actual.peak() > 1e-4f)
            }
            assertTrue("${presets.size} rendered into" in output.toString())
            assertFalse("lands with" in output.toString(), "the audition remains dry")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `invalid scanned note energy and sustain requests write no audition files`() {
        val root = kotlin.io.path.createTempDirectory("scanned-chain-cli-refusals").toFile()
        val destination = File(root, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "35"), arrayOf("--midi", "97"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "Infinity"),
                arrayOf("--velocity", "-.01"), arrayOf("--velocity", "1.01"),
                arrayOf("--midi", "60", "--instrument"), arrayOf("--midi", "60", "--drone"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "SCANNED_CHAIN", "PLUCK", *flags,
                    "--out", destination.path), PrintStream(ByteArrayOutputStream()), PrintStream(errors)),
                    flags.joinToString())
                assertTrue(errors.size() > 0, "invalid requests provide a reason")
                assertFalse(destination.exists(), "validation happens before output side effects")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertNotNullFiles(dir: File): List<File> =
        requireNotNull(dir.listFiles { file -> file.extension == "wav" }).toList()
}
