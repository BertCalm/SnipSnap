package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Skirl
import com.snipsnap.synth.SkirlPatch
import com.snipsnap.synth.SkirlPresets
import com.snipsnap.synth.SkirlVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkirlSynthCommandTest {
    @Test
    fun `finite provisional recipes retain native note velocity and renderer beside the WAV`() {
        val directory = createTempDirectory("skirl-cli").toFile()
        try {
            for (voice in SkirlVoice.entries) {
                val destination = File(directory, voice.name)
                val output = ByteArrayOutputStream()
                assertEquals(0, SynthCommand.run(listOf("SKIRL", voice.name, "--preset", "1", "--midi", "60",
                    "--velocity", ".4", "--out", destination.path), PrintStream(output)))
                val expected = SkirlPresets.forVoice(voice).first().copy(midi = 60, velocity = .4f)
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
                assertContentEquals(encoded, requireNotNull(destination.listFiles { it.extension == "wav" }).single().readBytes())
                assertEquals(expected, SkirlPatch.fromJsonText(requireNotNull(destination.listFiles { it.extension == "patch" }).single().readText()))
                assertTrue(Presets.forVoice(SkirlPatch.ENGINE, voice.name).isEmpty(), "provisional starts are outside the factory roster")
                assertTrue("1 rendered into" in output.toString())
                assertFalse("lands with" in output.toString())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `saved runout replay preserves mechanical controls and model through performance overrides`() {
        val directory = createTempDirectory("skirl-cli-patch").toFile()
        try {
            val patch = SkirlPatch("Authored runout", SkirlVoice.ROLL,
                Skirl.defaults(SkirlVoice.ROLL) + mapOf("LEAN" to .68f, "BRAKE" to .52f, "EDGE" to .72f),
                midi = 57, velocity = .35f, model = Skirl.MODEL_VERSION)
            val source = File(directory, "runout.json").also { it.writeText(patch.toJsonText()) }
            val destination = File(directory, "wav")
            assertEquals(0, SynthCommand.run(listOf("SKIRL", "ROLL", "--patch", source.path,
                "--midi", "65", "--velocity", ".6", "--out", destination.path), PrintStream(ByteArrayOutputStream())))
            val expected = patch.copy(midi = 65, velocity = .6f)
            assertEquals(expected, SkirlPatch.fromJsonText(requireNotNull(destination.listFiles { it.extension == "patch" }).single().readText()))
            val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            assertContentEquals(encoded, requireNotNull(destination.listFiles { it.extension == "wav" }).single().readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid performance and unavailable HOLD fail before creating output`() {
        val directory = createTempDirectory("skirl-cli-invalid").toFile()
        val destination = File(directory, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "35"), arrayOf("--midi", "85"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.01"), arrayOf("--velocity", "-.01"),
                arrayOf("--instrument"), arrayOf("--drone"), arrayOf("--scramble", "3"),
                arrayOf("--seed", "3"), arrayOf("--model", "2"), arrayOf("--seconds", "2"),
                arrayOf("--patch", "missing.json"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "SKIRL", "ROLL", *flags, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.joinToString())
                assertTrue(errors.size() > 0)
                assertFalse(destination.exists(), "invalid input must not create an audition directory")
            }
            val held = SkirlPresets.forVoice(SkirlVoice.ROLL).first().toJsonValue().entries.toMutableMap()
            held["macros"] = JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(1.0)))
            val source = File(directory, "held.json").also { it.writeText(Json.write(JsonValue.Obj(held))) }
            assertEquals(2, Cli.run(arrayOf("synth", "SKIRL", "ROLL", "--patch", source.path, "--out", destination.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
            assertFalse(destination.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
