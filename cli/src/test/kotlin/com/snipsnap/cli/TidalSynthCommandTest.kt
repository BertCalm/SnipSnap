package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Tidal
import com.snipsnap.synth.TidalPatch
import com.snipsnap.synth.TidalPresets
import com.snipsnap.synth.TidalVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TidalSynthCommandTest {
    @Test
    fun `CLI auditions a factory recipe and saves its complete performance`() {
        val directory = createTempDirectory("tidal-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(listOf("TIDAL", "PAIR", "--preset", "1", "--midi", "63",
                "--velocity", ".35", "--seed", Long.MIN_VALUE.toString(), "--model", Tidal.MODEL_VERSION.toString(), "--out", directory.path),
                PrintStream(output)))
            val expected = TidalPresets.forVoice(TidalVoice.PAIR).first().copy(midi = 63, velocity = .35f, seed = Long.MIN_VALUE)
            val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            assertContentEquals(encoded, requireNotNull(directory.listFiles { it.extension == "wav" }).single().readBytes())
            assertEquals(expected, TidalPatch.fromJsonText(requireNotNull(directory.listFiles { it.extension == "patch" }).single().readText()))
            assertEquals(TidalPresets.forVoice(TidalVoice.PAIR), Presets.forVoice(TidalPatch.ENGINE, "PAIR"))
            assertTrue("1 rendered into" in output.toString())
            assertFalse("lands with" in output.toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `saved encounter replay preserves controls and exact seed while overriding performance`() {
        val directory = createTempDirectory("tidal-cli-patch").toFile()
        try {
            for (model in listOf(1, Tidal.MODEL_VERSION)) {
                val patch = TidalPatch("Authored encounter", TidalVoice.STREAM,
                    Tidal.defaults(TidalVoice.STREAM, model) + mapOf("BIND" to .68f, "TILT" to .82f),
                    midi = 57, velocity = .42f, seed = 9_007_199_254_740_993L, model = model)
                val source = File(directory, "encounter-$model.json").also { it.writeText(patch.toJsonText()) }
                val destination = File(directory, "wavs-$model")
                assertEquals(0, SynthCommand.run(listOf("TIDAL", "STREAM", "--patch", source.path,
                    "--midi", "67", "--velocity", ".6", "--out", destination.path), PrintStream(ByteArrayOutputStream())))
                val expected = patch.copy(midi = 67, velocity = .6f)
                assertEquals(expected, TidalPatch.fromJsonText(requireNotNull(destination.listFiles { it.extension == "patch" }).single().readText()))
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
                assertContentEquals(encoded, requireNotNull(destination.listFiles { it.extension == "wav" }).single().readBytes())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid performance and deferred held routes fail before creating output`() {
        val directory = createTempDirectory("tidal-cli-invalid").toFile()
        val destination = File(directory, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "35"), arrayOf("--midi", "85"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.01"), arrayOf("--velocity", "-.01"),
                arrayOf("--seed", "1.5"), arrayOf("--seed", "9223372036854775808"),
                arrayOf("--instrument"), arrayOf("--drone"), arrayOf("--scramble", "3"),
                arrayOf("--model", "0"), arrayOf("--model", (Tidal.MODEL_VERSION + 1).toString()), arrayOf("--seconds", "2"),
                arrayOf("--wet"), arrayOf("--dry"), arrayOf("--patch", "missing.json"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "TIDAL", "PAIR", *flags, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.joinToString())
                assertTrue(errors.size() > 0)
                assertFalse(destination.exists(), "invalid input must not create an audition directory")
            }
            val held = TidalPresets.all().first().toJsonValue().entries.toMutableMap()
            held["macros"] = JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(1.0)))
            val source = File(directory, "held.json").also { it.writeText(Json.write(JsonValue.Obj(held))) }
            assertEquals(2, Cli.run(arrayOf("synth", "TIDAL", "PAIR", "--patch", source.path, "--out", destination.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
            assertFalse(destination.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
