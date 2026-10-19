package com.snipsnap.cli

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.CreasePresets
import com.snipsnap.synth.CreaseVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreaseSynthCommandTest {
    @Test
    fun `all six approved positions render through the native CLI without changing their saved sound`() {
        val root = createTempDirectory("crease-cli-bank").toFile()
        try {
            for (voice in CreaseVoice.entries) {
                val destination = File(root, voice.name)
                val output = ByteArrayOutputStream()
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(
                    arrayOf("synth", "cReAsE", voice.name.lowercase(), "--all", "--out", destination.path),
                    PrintStream(output), PrintStream(errors),
                ), errors.toString())
                val files = requireNotNull(destination.listFiles { it.extension == "wav" }).sortedBy { it.name }
                val approved = CreasePresets.forVoice(voice)
                assertEquals(2, files.size)
                for ((index, preset) in approved.withIndex()) {
                    assertContentEquals(wav(preset.render()), files[index].readBytes(),
                        "${preset.name}: native Patch registration must retain the approved root, velocity, model and controls")
                }
                assertTrue("2 rendered into" in output.toString())
                assertFalse("lands with" in output.toString(), "CREASE remains dry")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `explicit note velocity and model overrides reach every voice together`() {
        val root = createTempDirectory("crease-cli-overrides").toFile()
        try {
            for ((index, voice) in CreaseVoice.entries.withIndex()) {
                val destination = File(root, voice.name)
                val model = index + 1
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(
                    arrayOf("synth", "CREASE", voice.name, "--preset", "2", "--midi", "67",
                        "--velocity", ".35", "--model", "$model", "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), errors.toString())
                val file = requireNotNull(destination.listFiles { it.extension == "wav" }).single()
                val expected = CreasePresets.forVoice(voice)[1].toPatch().copy(midi = 67, velocity = .35f, model = model)
                assertContentEquals(wav(expected.render()), file.readBytes(), "$voice: all three explicit fields must reach model $model")
                val decoded = WavReader.read(file)
                assertEquals(44_100, decoded.sampleRate)
                assertEquals(1, decoded.channels)
                assertTrue(decoded.peak() > 0f)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `changing only the model keeps Corner Bass saved low root and velocity`() {
        val root = createTempDirectory("crease-cli-model").toFile()
        try {
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "CREASE", "ROUND", "--preset", "1", "--model", "2", "--out", root.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors),
            ), errors.toString())
            val file = requireNotNull(root.listFiles { it.extension == "wav" }).single()
            val expected = CreasePresets.forVoice(CreaseVoice.ROUND).first().toPatch().copy(model = 2)
            assertEquals(36, expected.midi)
            assertContentEquals(wav(expected.render()), file.readBytes(), "model selection must retain the original C2 note and saved excitation")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid note velocity model and unsupported instrument flags fail before output creation`() {
        val root = createTempDirectory("crease-cli-invalid").toFile()
        val destination = File(root, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "23"), arrayOf("--midi", "85"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "Infinity"), arrayOf("--velocity", "1.01"),
                arrayOf("--model", "0"), arrayOf("--model", "4"), arrayOf("--model", "1.5"),
                arrayOf("--instrument"), arrayOf("--drone"), arrayOf("--seconds", "1"), arrayOf("--seed", "1"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    arrayOf("synth", "CREASE", "ROUND", *flags, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), flags.joinToString())
                assertTrue(errors.size() > 0, "the refusal must explain ${flags.joinToString()}")
                assertFalse("Exception" in errors.toString())
                assertFalse(destination.exists(), "invalid ${flags.joinToString()} created an output directory")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun wav(snip: Snip): ByteArray = ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()
}
