package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Patches
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.PluckPresets
import com.snipsnap.synth.PluckVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UprightCommandTest {
    @Test fun `upright auditions preserve explicit bass note strength and macro edits in replay patches`() {
        val dir = createTempDirectory("upright-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, Cli.run(arrayOf("synth", "PLUCK", "UPRIGHT", "--all", "--midi", "28",
                "--velocity", ".35", "--macros", "WOOD=.2", "--out", dir.path),
                PrintStream(output), PrintStream(ByteArrayOutputStream())))
            val wavs = dir.listFiles { file -> file.extension == "wav" }!!.sortedBy { it.name }
            assertEquals(2, wavs.size)
            for ((index, preset) in PluckPresets.forVoice(PluckVoice.UPRIGHT).withIndex()) {
                val expected = preset.copy(macros = preset.macros + mapOf("TUNE" to 0f, "WOOD" to .2f), velocity = .35f)
                val bytes = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
                assertTrue(bytes.contentEquals(wavs[index].readBytes()))
                val saved = assertIs<PluckPatch>(Patches.fromJsonText(
                    wavs[index].resolveSibling("${wavs[index].nameWithoutExtension}.patch").readText()))
                assertEquals(expected, saved)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `invalid note controls refuse before writing files and legacy plucks retain their contract`() {
        val dir = createTempDirectory("upright-cli-refusal").toFile()
        try {
            for (selection in listOf(
                arrayOf("UPRIGHT", "--midi", "27"),
                arrayOf("UPRIGHT", "--midi", "53"),
                arrayOf("UPRIGHT", "--velocity", "NaN"),
                arrayOf("UPRIGHT", "--macros", "HOLD=1"),
                arrayOf("UPRIGHT", "--midi", "33", "--macros", "TUNE=.2"),
                arrayOf("NYLON", "--velocity", ".4"),
            )) {
                val error = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "PLUCK", *selection, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(error)), error.toString())
                assertTrue(dir.listFiles().orEmpty().isEmpty())
            }
        } finally { dir.deleteRecursively() }
    }
}
