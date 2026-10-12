package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Relay
import com.snipsnap.synth.RelayPresets
import com.snipsnap.synth.RelayVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayCommandTest {
    @Test
    fun `Relay auditions retain the factory phrase and override root and excitation`() {
        val root = createTempDirectory("relay-cli").toFile()
        try {
            for ((voice, midi) in listOf(RelayVoice.KEY to 65, RelayVoice.BASS to 29)) {
                val dir = File(root, voice.name)
                val output = ByteArrayOutputStream()
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(
                    arrayOf("synth", "relay", voice.name.lowercase(), "--all", "--midi", "$midi", "--velocity", ".35", "--out", dir.path),
                    PrintStream(output), PrintStream(errors),
                ), errors.toString())
                val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
                val source = RelayPresets.forVoice(voice).single()
                val expectedPatch = source.copy(
                    macros = source.macros + ("TUNE" to (midi - Relay.rootMidi(voice)) / Relay.TUNE_SEMITONES.toFloat()),
                    velocity = .35f,
                )
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, expectedPatch.render()) }
                assertContentEquals(expected.toByteArray(), wav.readBytes(), "the saved schedule, pitch and velocity reach the renderer")
                val rendered = WavReader.read(wav)
                assertEquals(1, rendered.channels)
                assertEquals(44_100, rendered.sampleRate)
                assertTrue(rendered.peak() > 0f)
                assertTrue("1 rendered into" in output.toString())
                assertFalse("lands with" in output.toString(), "the identity audition stays dry")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Relay omitting overrides preserves the complete preset`() {
        val dir = createTempDirectory("relay-default-cli").toFile()
        try {
            assertEquals(0, SynthCommand.run(
                listOf("RELAY", "CHIME", "--preset", "1", "--out", dir.path), PrintStream(ByteArrayOutputStream()),
            ))
            val expected = ByteArrayOutputStream().also {
                WavWriter.write(it, RelayPresets.forVoice(RelayVoice.CHIME).single().render())
            }
            assertContentEquals(expected.toByteArray(), dir.listFiles { f -> f.extension == "wav" }!!.single().readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Relay rejects notes outside its voice range and invalid excitation before output`() {
        val root = createTempDirectory("relay-invalid-cli").toFile()
        val dir = File(root, "not-created")
        try {
            for ((voice, flags) in listOf(
                "key" to arrayOf("--midi", "47"),
                "key" to arrayOf("--midi", "73"),
                "bass" to arrayOf("--midi", "23"),
                "bass" to arrayOf("--midi", "49"),
                "key" to arrayOf("--velocity", "NaN"),
                "key" to arrayOf("--velocity", "1.01"),
                "key" to arrayOf("--midi", "60", "--instrument"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    arrayOf("synth", "relay", voice, *flags, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), errors.toString())
                assertTrue(flags[0] in errors.toString(), "the refusal names the invalid flag")
            }
            assertFalse(dir.exists(), "invalid values have no output side effects")
        } finally {
            root.deleteRecursively()
        }
    }
}
