package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.OhmPresets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OhmSynthCommandTest {
    @Test
    fun `CLI retains gathering while applying explicit pitch velocity and seed`() {
        val dir = kotlin.io.path.createTempDirectory("ohm-cli").toFile()
        try {
            assertEquals(0, SynthCommand.run(listOf("OHM", "STRING", "--preset", "5", "--midi", "57",
                "--velocity", "0.4", "--seed", "-89", "--out", dir.path), PrintStream(ByteArrayOutputStream())))
            val expected = OhmPresets.all()[4].copy(midi = 57, velocity = .4f, seed = -89)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            assertContentEquals(bytes, dir.listFiles { f -> f.extension == "wav" }!!.single().readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `invalid notes velocities seeds and voices fail before creating output`() {
        val temp = kotlin.io.path.createTempDirectory("ohm-cli-errors").toFile()
        val dir = File(temp, "absent")
        try {
            for (flags in listOf(listOf("--midi", "47"), listOf("--velocity", "NaN"), listOf("--seed", "1.5"))) {
                assertEquals(2, Cli.run((listOf("synth", "OHM", "STRING") + flags + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
            }
            assertEquals(2, Cli.run(arrayOf("synth", "OHM", "BOW", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
            assertFalse(dir.exists())
        } finally { temp.deleteRecursively() }
    }
}
