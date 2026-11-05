package com.snipsnap.cli

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Lattice
import com.snipsnap.synth.LatticePatch
import com.snipsnap.synth.LatticePresets
import com.snipsnap.synth.LatticeVoice
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LatticeSynthCommandTest {
    @Test
    fun `every Lattice source accepts an explicit note and velocity and saves its complete patch`() {
        val root = createTempDirectory("lattice-cli-voices").toFile()
        try {
            for (voice in LatticeVoice.entries) {
                val dir = File(root, voice.name)
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(
                    arrayOf("synth", "lAtTiCe", voice.name.lowercase(), "--preset", "1",
                        "--midi", "67", "--velocity", ".35", "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), errors.toString())
                val expected = LatticePresets.patchesForVoice(voice).first().copy(
                    midi = 67, velocity = .35f,
                    macros = LatticePresets.patchesForVoice(voice).first().macros + ("TUNE" to .5f),
                )
                val saved = assertSavedRender(expected, dir)
                assertEquals(Lattice.MODEL_VERSION, saved.model)
                assertEquals(67, saved.midi)
                assertEquals(.35f, saved.velocity)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `saved Lattice state survives exact replay and velocity or HOLD edits`() {
        val root = createTempDirectory("lattice-cli-replay").toFile()
        try {
            val patch = LatticePatch("Saved Hollow", LatticeVoice.HOLLOW,
                Lattice.defaults(LatticeVoice.HOLLOW) + mapOf(
                    "TUNE" to .625f, "TURN" to .9f, "COLOR" to .7f,
                    "REJECT" to .25f, "HOLD" to 0f,
                ), midi = 61, velocity = .42f)
            val recipe = File(root, "input.patch").also { it.writeText(patch.toJsonText()) }
            val exact = File(root, "exact")
            assertEquals(0, SynthCommand.run(
                listOf("LATTICE", "HOLLOW", "--patch", recipe.path, "--out", exact.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            assertSavedRender(patch, exact)

            val held = File(root, "held")
            assertEquals(0, SynthCommand.run(
                listOf("LATTICE", "HOLLOW", "--patch", recipe.path, "--velocity", ".3",
                    "--macros", " color=.6,HOLD=1 ", "--out", held.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            val expected = patch.copy(velocity = .3f,
                macros = patch.macros + mapOf("COLOR" to .6f, "HOLD" to 1f))
            val saved = assertSavedRender(expected, held)
            assertEquals(61, saved.midi)
            assertEquals(.625f, saved.macros.getValue("TUNE"), "velocity-only edits retain saved transposition")
            assertTrue(Lattice.isLoop(saved.macros.getValue("HOLD")))

            val note = File(root, "note")
            assertEquals(0, SynthCommand.run(
                listOf("LATTICE", "HOLLOW", "--patch", recipe.path, "--midi", "72", "--out", note.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            assertSavedRender(patch.copy(midi = 72, macros = patch.macros + ("TUNE" to .5f)), note)

            // Native recipes may omit controls whose voice defaults are wanted.
            val sparse = LatticePatch("Sparse Warm", LatticeVoice.WARM, emptyMap(), midi = 57, velocity = .55f)
            val sparseRecipe = File(root, "sparse.patch").also { it.writeText(sparse.toJsonText()) }
            val sparseOutput = File(root, "sparse")
            assertEquals(0, SynthCommand.run(
                listOf("LATTICE", "WARM", "--patch", sparseRecipe.path, "--out", sparseOutput.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            assertSavedRender(sparse, sparseOutput)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `saved TRACE motion survives speed and finite HOLD edits with its authored note`() {
        val root = createTempDirectory("lattice-cli-trace").toFile()
        try {
            val patch = LatticePatch("Saved moving trace", LatticeVoice.TRACE,
                Lattice.defaults(LatticeVoice.TRACE) + mapOf("TUNE" to .625f, "SPEED" to .23f),
                midi = 61, velocity = .42f)
            val input = File(root, "trace.patch").also { it.writeText(patch.toJsonText()) }
            val exact = File(root, "exact")
            assertEquals(0, SynthCommand.run(
                listOf("LATTICE", "TRACE", "--patch", input.path, "--out", exact.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            assertSavedRender(patch, exact)

            val edited = File(root, "edited")
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(
                listOf("LATTICE", "TRACE", "--patch", input.path, "--velocity", ".3",
                    "--macros", "SPEED=.78,HOLD=1", "--out", edited.path),
                PrintStream(output),
            ))
            val expected = patch.copy(velocity = .3f,
                macros = patch.macros + mapOf("SPEED" to .78f, "HOLD" to 1f))
            val saved = assertSavedRender(expected, edited)
            assertEquals(61, saved.midi)
            assertEquals(.625f, saved.macros.getValue("TUNE"))
            assertFalse(Lattice.isLoop(saved.voice, saved.macros.getValue("HOLD")))
            assertFalse("held loop" in output.toString(), "finite TRACE HOLD must not be advertised as a loop")
            assertFalse(patch.render().samples.contentEquals(expected.render().samples),
                "SPEED and HOLD edits must reach the emitted motion")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Lattice range macro and patch selection errors refuse before creating output`() {
        val root = createTempDirectory("lattice-cli-errors").toFile()
        val dir = File(root, "not-created")
        try {
            val recipe = File(root, "warm.patch").also {
                it.writeText(LatticePatch("Warm", LatticeVoice.WARM, Lattice.defaults(LatticeVoice.WARM)).toJsonText())
            }
            for (flags in listOf(
                listOf("--midi", "35"), listOf("--midi", "85"), listOf("--midi", "60.5"),
                listOf("--velocity", "NaN"), listOf("--velocity", "1.01"),
                listOf("--macros", "COLOR=NaN"), listOf("--macros", "NOT_A_CONTROL=.5"),
                listOf("--macros", "TUNE=.7", "--midi", "60"),
                listOf("--patch", recipe.path),
                listOf("--patch", File(root, "missing.patch").path),
                listOf("--instrument"), listOf("--drone"), listOf("--model", "1"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    (listOf("synth", "LATTICE", "HOLLOW") + flags + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), flags.joinToString())
                assertTrue(errors.size() > 0, "the refusal must explain ${flags.joinToString()}")
                assertFalse("Exception" in errors.toString())
                assertFalse(dir.exists(), "invalid ${flags.joinToString()} created output")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertSavedRender(expected: LatticePatch, dir: File): LatticePatch {
        val file = requireNotNull(dir.listFiles { it.extension == "wav" }).single()
        val saved = assertIs<LatticePatch>(Patches.fromJsonText(File(dir, "${file.nameWithoutExtension}.patch").readText()))
        assertEquals(expected.toJsonText(), saved.toJsonText(), "native state must match the emitted sound")
        assertContentEquals(wav(expected.render()), file.readBytes())
        assertContentEquals(wav(saved.render()), file.readBytes(), "the emitted patch must regenerate its WAV")
        return saved
    }

    private fun wav(snip: Snip): ByteArray = ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()
}
