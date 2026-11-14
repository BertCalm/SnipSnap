package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import com.snipsnap.synth.SplayPatch
import com.snipsnap.synth.SplayPresets
import com.snipsnap.synth.SplayVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLI recovery contracts; running these renders audio and belongs in an approved validation slot. */
class SplaySynthCommandTest {
    @Test
    fun `accepted population slots follow the unchanged historical preset slots`() {
        val directory = createTempDirectory("splay-cli-factory").toFile()
        try {
            // Positions 1..5 retain their old meaning. Factory populations append
            // at 6..10; no existing invocation is silently reinterpreted.
            val first = File(directory, "historical-first")
            assertEquals(0, SynthCommand.run(listOf("SPLAY", "TONGUE", "--preset", "1",
                "--out", first.path), quiet()))
            assertRendered(SplayPresets.all().first(), first)
            for ((index, entry) in com.snipsnap.synth.SplayAcceptedBank.entries.withIndex()) {
                val output = File(directory, entry.id)
                assertEquals(0, SynthCommand.run(listOf("SPLAY", "TONGUE", "--preset", (index + 6).toString(),
                    "--out", output.path), quiet()))
                assertRendered(entry.patch, output)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `saved models retain complete source state and model specific partial defaults`() {
        val directory = createTempDirectory("splay-cli-replay").toFile()
        try {
            for (model in 1..2) {
                // Omitted controls exercise each saved model's own defaults, not current model-2 presets.
                val patch = SplayPatch("Saved $model", SplayVoice.TONGUE, mapOf("POPULATION" to 0f),
                    midi = 57, velocity = .43f, seed = if (model == 1) Int.MIN_VALUE else Int.MAX_VALUE,
                    model = model)
                val source = File(directory, "source-$model.json").also { it.writeText(patch.toJsonText()) }
                val output = File(directory, "out-$model")
                assertEquals(0, SynthCommand.run(listOf("SPLAY", "TONGUE", "--patch", source.path,
                    "--out", output.path), quiet()))
                assertRendered(patch, output)
            }
            assertEquals(5, SplayPresets.all().size)
            assertEquals(com.snipsnap.synth.SplayAcceptedBank.entries.map { it.patch },
                Presets.forVoice("SPLAY", "TONGUE"), "only accepted populations join the factory bank")
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `performance seed and macro edits preserve saved model and remaining controls`() {
        val directory = createTempDirectory("splay-cli-edits").toFile()
        try {
            val patch = SplayPatch("Legacy linear population", SplayVoice.TONGUE,
                mapOf("TRACE" to .3f, "ENERGY" to .4f, "POPULATION" to 0f),
                midi = 48, velocity = .65f, seed = 17, model = 1)
            val source = File(directory, "source.json").also { it.writeText(patch.toJsonText()) }
            val output = File(directory, "output")
            assertEquals(0, SynthCommand.run(listOf("SPLAY", "TONGUE", "--patch", source.path,
                "--midi", "72", "--velocity", ".35", "--seed", Int.MIN_VALUE.toString(),
                "--macros", "CONTACT=.25,POPULATION=0,HOLD=0", "--out", output.path), quiet()))
            val expected = patch.copy(midi = 72, velocity = .35f, seed = Int.MIN_VALUE,
                macros = patch.macros + mapOf("CONTACT" to .25f, "POPULATION" to 0f, "HOLD" to 0f))
            assertRendered(expected, output)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `an explicit acoustic model override retains the supplied macro and performance state`() {
        val directory = createTempDirectory("splay-cli-model").toFile()
        try {
            val patch = SplayPatch("Explicit model change", SplayVoice.TONGUE, mapOf("POPULATION" to 0f),
                midi = 67, velocity = .55f, seed = -43, model = 1)
            val source = File(directory, "source.json").also { it.writeText(patch.toJsonText()) }
            val output = File(directory, "output")
            assertEquals(0, SynthCommand.run(listOf("SPLAY", "TONGUE", "--patch", source.path,
                "--model", "2", "--out", output.path), quiet()))
            assertRendered(patch.copy(model = 2), output)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `unsupported modes and out of range inputs fail before creating output`() {
        val directory = createTempDirectory("splay-cli-invalid").toFile()
        try {
            for ((index, flags) in listOf(
                listOf("--midi", "35"), listOf("--midi", "85"), listOf("--midi", "60.5"),
                listOf("--velocity", "NaN"), listOf("--velocity", "1.000000001"),
                listOf("--velocity", "-0.00000000000000000000000000000000000000000000000001"),
                listOf("--seed", "2147483648"), listOf("--seed", "-2147483649"), listOf("--seed", "1.5"),
                listOf("--model", "0"), listOf("--model", "3"),
                listOf("--macros", "HOLD=1"), listOf("--macros", "HOLD=1e-50"),
                listOf("--macros", "POPULATION=1.000000001"), listOf("--macros", "CONTACT=NaN"),
                listOf("--macros", "TUNE=.5"), listOf("--macros", "ENERGY=.2,ENERGY=.3"),
                listOf("--macros", "CONTACT=.2", "--macros", "ENERGY=.3"),
                listOf("--instrument"), listOf("--drone"), listOf("--seconds", "1"),
                listOf("--scramble", "4"), listOf("--patch", "missing-splay.json"),
            ).withIndex()) {
                val output = File(directory, "invalid-$index")
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run((listOf("synth", "SPLAY", "TONGUE") + flags +
                    listOf("--out", output.path)).toTypedArray(), quiet(), PrintStream(errors)), flags.joinToString())
                assertTrue(errors.size() > 0, flags.joinToString())
                assertFalse(output.exists(), flags.joinToString())
            }
        } finally { directory.deleteRecursively() }
    }

    private fun assertRendered(expected: SplayPatch, output: File) {
        val sidecar = requireNotNull(output.listFiles { it.extension == "patch" }).single()
        assertEquals(expected, Patches.fromJsonText(sidecar.readText()))
        val wav = requireNotNull(output.listFiles { it.extension == "wav" }).single()
        assertContentEquals(ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray(),
            wav.readBytes(), "the sidecar must regenerate the finite native CLI WAV exactly")
        assertNull(WavReader.readSmpl(wav), "SPLAY recovery must not add a sampler HOLD loop")
    }

    private fun quiet() = PrintStream(ByteArrayOutputStream())
}
