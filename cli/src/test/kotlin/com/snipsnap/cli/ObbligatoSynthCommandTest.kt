package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.ObbligatoPresets
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ObbligatoSynthCommandTest {
    @Test
    fun `all relationship auditions save complete recipes with the applied note velocity and seed`() {
        val destination = kotlin.io.path.createTempDirectory("obbligato-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "oBbLiGaTo", "aIr_ReEd", "--all", "--midi", "72", "--velocity", ".35",
                    "--seed", "-29", "--out", destination.path),
                PrintStream(output), PrintStream(ByteArrayOutputStream()),
            ))
            val wavs = requireNotNull(destination.listFiles { it.extension == "wav" }).sortedBy { it.name }
            val recipes = requireNotNull(destination.listFiles { it.extension == "patch" }).sortedBy { it.name }
            val presets = ObbligatoPresets.all()
            assertEquals(4, wavs.size)
            assertEquals(wavs.size, recipes.size)
            for (index in presets.indices) {
                val expected = presets[index].copy(midi = 72, velocity = .35f, seed = -29)
                val saved = assertIs<ObbligatoPatch>(Patches.fromJsonText(recipes[index].readText()))
                assertEquals(wavs[index].nameWithoutExtension, recipes[index].nameWithoutExtension)
                assertEquals(expected, saved, "recipe retains relationship macros, model and complete playing state")
                assertEquals(Obbligato.MODEL, saved.model)
            }
            val saved = Patches.fromJsonText(recipes.first().readText())
            val expectedWav = ByteArrayOutputStream().also { WavWriter.write(it, saved.render()) }.toByteArray()
            assertContentEquals(expectedWav, wavs.first().readBytes(), "saved recipe reproduces its audition exactly")
            assertTrue("4 rendered into" in output.toString())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `omitting playing overrides keeps the selected factory recipe`() {
        val destination = kotlin.io.path.createTempDirectory("obbligato-cli-defaults").toFile()
        try {
            assertEquals(0, SynthCommand.run(
                listOf("OBBLIGATO", "AIR_REED", "--preset", "3", "--out", destination.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            val recipe = requireNotNull(destination.listFiles { it.extension == "patch" }).single()
            assertEquals(ObbligatoPresets.all()[2], Patches.fromJsonText(recipe.readText()))
            assertEquals("Fight", Patches.fromJsonText(recipe.readText()).name)
            assertEquals(1, requireNotNull(destination.listFiles { it.extension == "wav" }).size)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `invalid playing state and unsupported audition shapes fail before creating output`() {
        val root = kotlin.io.path.createTempDirectory("obbligato-cli-invalid").toFile()
        val destination = File(root, "not-created")
        try {
            for (flags in listOf(
                listOf("--midi", "${Obbligato.midiRange().first - 1}"),
                listOf("--midi", "${Obbligato.midiRange().last + 1}"),
                listOf("--midi", "60.5"), listOf("--midi", "2147483648"),
                listOf("--velocity", "NaN"), listOf("--velocity", "Infinity"),
                listOf("--velocity", "-.01"), listOf("--velocity", "1.001"),
                listOf("--seed", "1.5"), listOf("--seed", "2147483648"),
                listOf("--scramble", "7"), listOf("--instrument"), listOf("--drone"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    (listOf("synth", "OBBLIGATO", "AIR_REED") + flags + listOf("--out", destination.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), flags.joinToString())
                assertTrue(errors.size() > 0)
                assertFalse("Exception" in errors.toString(), "invalid input should produce a CLI error: $errors")
                assertFalse(destination.exists(), "invalid input created an output directory: $flags")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `expanding a saved narrow patch applies model and root together in either flag order`() {
        val root = kotlin.io.path.createTempDirectory("obbligato-cli-expanded").toFile()
        try {
            val original = ObbligatoPatch("Saved narrow note", Obbligato.defaults(), model = 10)
            val text = original.toJsonText()
            val input = File(root, "source.patch").also { it.writeText(text) }
            val cells = listOf(
                48 to listOf("--model", "11", "--midi", "48"),
                84 to listOf("--midi", "84", "--model", "11"),
            )
            for ((midi, flags) in cells) {
                val destination = File(root, "wide-$midi")
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run((listOf("synth", "OBBLIGATO", "AIR_REED", "--patch", input.path) +
                    flags + listOf("--out", destination.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), errors.toString())
                val saved = destination.listFiles { it.extension == "patch" }!!.single()
                assertEquals(original.copy(model = 11, midi = midi), Patches.fromJsonText(saved.readText()))
                assertEquals(1, destination.listFiles { it.extension == "wav" }!!.size)
            }
            assertEquals(text, input.readText(), "upgrading an audition rewrites its saved source")
            val refused = File(root, "refused")
            val errors = ByteArrayOutputStream()
            assertEquals(2, Cli.run(arrayOf("synth", "OBBLIGATO", "AIR_REED", "--patch", input.path,
                "--model", "10", "--midi", "48", "--out", refused.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
            assertTrue("60..72" in errors.toString(), "a saved narrow renderer loses its range diagnostic")
            assertFalse(refused.exists())
        } finally {
            root.deleteRecursively()
        }
    }

}
