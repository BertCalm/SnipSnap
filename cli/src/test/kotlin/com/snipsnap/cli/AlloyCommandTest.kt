package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.AlloyPresets
import com.snipsnap.synth.AlloyVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlloyCommandTest {
    @Test
    fun `all four Alloy key presets audition the requested pitch and energy`() {
        val dir = createTempDirectory("alloy-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "alloy", "key", "--all", "--midi", "65", "--velocity", ".4", "--out", dir.path),
                PrintStream(output), PrintStream(ByteArrayOutputStream()),
            ))
            val wavs = dir.listFiles { f -> f.extension == "wav" }!!.sortedBy { it.name }
            assertEquals(4, wavs.size)
            for ((i, source) in AlloyPresets.forVoice(AlloyVoice.KEY).withIndex()) {
                val preset = source.copy(midi = 65, velocity = .4f)
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, preset.render()) }.toByteArray()
                assertTrue(expected.contentEquals(wavs[i].readBytes()), "note and energy must reach each generated recipe")
                val audio = WavReader.read(wavs[i])
                assertEquals(1, audio.channels)
                assertEquals(44_100, audio.sampleRate)
                assertTrue(audio.peak() > 0f)
            }
            assertTrue("4 rendered into" in output.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Alloy CLI held selection exports the settled recipe with note overrides`() {
        val dir = createTempDirectory("alloy-held-cli").toFile()
        try {
            val source = AlloyPresets.heldReferences().single { it.voice == AlloyVoice.SOFT }
            val patchFile = dir.resolve("held.patch").apply { writeText(source.toJsonText()) }
            val out = dir.resolve("out")
            assertEquals(0, Cli.run(
                arrayOf("synth", "AlLoY", "SoFt", "--patch", patchFile.path, "--midi", "72", "--velocity", ".35", "--out", out.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()),
            ))
            val wav = out.listFiles { f -> f.extension == "wav" }!!.single()
            assertEquals("ALLOY_SOFT_01_Endless_Ember.wav", wav.name)
            val preset = source.copy(midi = 72, velocity = .35f)
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, preset.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "HOLD or its material excitation was lost")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Alloy CLI saved authored role keeps its configuration through note and energy overrides`() {
        val dir = createTempDirectory("alloy-authored-cli").toFile()
        try {
            val source = AlloyPresets.forVoice(AlloyVoice.KEY).single { it.name == "Long Prism" }
                .copy(midi = 55, velocity = .65f)
            val patchFile = dir.resolve("authored.patch").apply { writeText(source.toJsonText()) }
            val out = dir.resolve("out")
            assertEquals(0, Cli.run(
                arrayOf("synth", "ALLOY", "KEY", "--patch", patchFile.path, "--midi", "72", "--velocity", ".3", "--out", out.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()),
            ))
            val wav = out.listFiles { f -> f.extension == "wav" }!!.single()
            val expected = ByteArrayOutputStream().also {
                WavWriter.write(it, source.copy(midi = 72, velocity = .3f).render())
            }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "saved operator routing and envelope must survive CLI overrides")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Alloy CLI refuses invalid root velocity voice and incompatible export shapes before writing`() {
        val dir = createTempDirectory("alloy-invalid-cli").toFile()
        try {
            for (flags in listOf(
                arrayOf("--midi", "23"), arrayOf("--midi", "97"), arrayOf("--midi", "48.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "Infinity"), arrayOf("--velocity", "1.1"),
                arrayOf("--preset", "5"), arrayOf("--preset", "7"), arrayOf("--midi", "60", "--instrument"), arrayOf("--velocity", ".5", "--drone"),
            )) {
                val errors = ByteArrayOutputStream()
                assertTrue(Cli.run(
                    arrayOf("synth", "alloy", "key", *flags, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ) != 0, flags.joinToString())
                assertTrue(errors.size() > 0)
                assertTrue(dir.listFiles()!!.isEmpty(), "invalid request wrote a partial audition")
            }
            assertTrue(Cli.run(arrayOf("synth", "alloy", "kick", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())) != 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
