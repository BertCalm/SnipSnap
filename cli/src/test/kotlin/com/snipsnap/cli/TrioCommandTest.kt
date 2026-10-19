package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Presets
import com.snipsnap.synth.TrioPatch
import com.snipsnap.synth.TrioVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrioCommandTest {
    @Test
    fun `TRIO auditions preserve each voice recipe while overriding root and playing energy`() {
        val root = createTempDirectory("trio-cli").toFile()
        try {
            for (voice in TrioVoice.entries) {
                val dir = root.resolve(voice.name)
                val preset = Presets.forVoice("TRIO", voice.name).first() as TrioPatch
                assertEquals(0, SynthCommand.run(
                    listOf("trio", voice.name.lowercase(), "--midi", "67", "--velocity", ".35", "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()),
                ))
                val wav = dir.listFiles()!!.single()
                val expected = preset.copy(midi = 67, velocity = .35f)
                assertContentEquals(ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray(), wav.readBytes())
                val audio = WavReader.read(wav)
                assertEquals(1, audio.channels)
                assertEquals(44_100, audio.sampleRate)
                assertEquals(3.5f, audio.durationSeconds, .0001f)
                assertTrue(audio.peak() > 0f)
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `TRIO phrase edits keep the preset model seed articulation and macros`() {
        val root = createTempDirectory("trio-phrase-cli").toFile()
        try {
            val preset = Presets.forVoice("TRIO", "ENSEMBLE").first() as TrioPatch
            assertEquals(0, SynthCommand.run(
                listOf("TRIO", "ENSEMBLE", "--tempo", "144", "--beats", "4", "--out", root.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            val expected = preset.copy(config = preset.config.copy(tempoBpm = 144f, beats = 4))
            assertContentEquals(
                ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray(),
                root.listFiles()!!.single().readBytes(),
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `TRIO invalid or incompatible note and phrase choices fail before creating output`() {
        val root = createTempDirectory("trio-invalid-cli").toFile()
        try {
            val flags = listOf(
                listOf("--midi", "47"), listOf("--midi", "85"), listOf("--midi", "60.5"),
                listOf("--velocity", "NaN"), listOf("--velocity", "Infinity"), listOf("--velocity", "1.1"),
                listOf("--tempo", "59"), listOf("--tempo", "201"), listOf("--tempo", "NaN"),
                listOf("--beats", "0"), listOf("--beats", "5"), listOf("--beats", "1.5"),
                listOf("--tempo", "90", "--beats", "4"),
                listOf("--tempo", "120", "--instrument"), listOf("--beats", "2", "--drone"),
            )
            for ((i, extra) in flags.withIndex()) {
                val dir = root.resolve("invalid-$i")
                val errors = ByteArrayOutputStream()
                assertTrue(Cli.run(
                    arrayOf("synth", "TRIO", "ENSEMBLE", *extra.toTypedArray(), "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ) != 0, extra.joinToString())
                assertTrue(errors.size() > 0)
                assertFalse(dir.exists(), "invalid request created $dir")
            }
            for (flag in listOf("--tempo", "--beats")) {
                val dir = root.resolve("wrong-engine-${flag.drop(2)}")
                assertTrue(Cli.run(
                    arrayOf("synth", "TINES", "BELL", flag, "2", "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()),
                ) != 0)
                assertFalse(dir.exists())
            }
        } finally { root.deleteRecursively() }
    }
}
