package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Bellows
import com.snipsnap.synth.BellowsPresets
import com.snipsnap.synth.BellowsVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BellowsSynthCommandTest {
    @Test
    fun `every voice keeps its saved recipe and explicit MIDI resets tune before velocity rendering`() {
        val root = createTempDirectory("bellows-cli").toFile()
        try {
            for (voice in BellowsVoice.entries) {
                // The second preset exercises the voice's alternative register,
                // direction or playing gesture rather than only its defaults.
                val preset = BellowsPresets.forVoice(voice)[1]
                val nativeDir = File(root, "${voice.name}-native")
                assertEquals(0, SynthCommand.run(
                    listOf("BELLOWS", voice.name, "--preset", "2", "--out", nativeDir.path),
                    PrintStream(ByteArrayOutputStream()),
                ))
                val nativeFile = requireNotNull(nativeDir.listFiles { it.extension == "wav" }).single()
                val nativeReference = ByteArrayOutputStream().also { WavWriter.write(it, preset.render()) }.toByteArray()
                assertContentEquals(nativeReference, nativeFile.readBytes(),
                    "$voice: omitting flags must preserve the stored macros, gesture, model and velocity")

                val overrideDir = File(root, "${voice.name}-override")
                val midi = Bellows.midiRange(voice).last
                val messages = ByteArrayOutputStream()
                assertEquals(0, SynthCommand.run(
                    listOf("BELLOWS", voice.name, "--preset", "2", "--midi", "$midi",
                        "--velocity", ".35", "--out", overrideDir.path),
                    PrintStream(messages),
                ))
                val explicit = preset.copy(midi = midi, velocity = .35f, macros = preset.macros + ("TUNE" to 0f))
                val reference = ByteArrayOutputStream().also { WavWriter.write(it, explicit.render()) }.toByteArray()
                val actual = requireNotNull(overrideDir.listFiles { it.extension == "wav" }).single()
                assertContentEquals(reference, actual.readBytes(),
                    "$voice: MIDI 72 is the played note and .35 is excitation velocity in the original recipe")
                val decoded = WavReader.read(actual)
                assertEquals(44_100, decoded.sampleRate)
                assertEquals(1, decoded.channels)
                assertTrue(decoded.peak() > 0f, "$voice: valid overrides produced silence")
                assertTrue("1 rendered into" in messages.toString())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unsupported MIDI and nonfinite velocity are refused before creating output`() {
        val root = createTempDirectory("bellows-cli-invalid").toFile()
        val destination = File(root, "not-created")
        try {
            for (voice in BellowsVoice.entries) {
                for ((flag, value) in listOf("--midi" to "47", "--midi" to "73", "--velocity" to "NaN")) {
                    val errors = ByteArrayOutputStream()
                    val result = Cli.run(
                        arrayOf("synth", "BELLOWS", voice.name, flag, value, "--out", destination.path),
                        PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                    )
                    assertEquals(2, result, "$voice $flag=$value")
                    assertTrue(flag in errors.toString(), "refusal must identify $flag: $errors")
                    assertFalse("Exception" in errors.toString())
                    assertFalse(destination.exists(), "$voice $flag=$value created an output directory")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
