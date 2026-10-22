package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPresets
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Presets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlockSynthCommandTest {
    @Test
    fun `factory voices render their recipes with note energy and full Long seed`() {
        val directory = createTempDirectory("flock-cli").toFile()
        try {
            for (voice in FlockVoice.entries) {
                val destination = File(directory, voice.name)
                val all = voice == FlockVoice.GATHER
                val seed = if (all) Long.MAX_VALUE else Long.MIN_VALUE
                val errors = ByteArrayOutputStream()
                val output = ByteArrayOutputStream()
                val args = listOf("synth", "fLoCk", voice.name.lowercase(), "--midi", "63",
                    "--velocity", ".35", "--seed", seed.toString(), "--out", destination.path) +
                    if (all) listOf("--all") else listOf("--preset", "1")
                assertEquals(0, Cli.run(args.toTypedArray(), PrintStream(output), PrintStream(errors)), errors.toString())
                val files = destination.listFiles { it.extension == "wav" }!!.sortedBy { it.name }
                val presets = FlockPresets.forVoice(voice).let { if (all) it else it.take(1) }
                assertEquals(presets.size, files.size)
                for ((index, preset) in presets.withIndex()) {
                    val patch = preset.copy(midi = 63, velocity = .35f, seed = seed)
                    val expected = ByteArrayOutputStream().also { WavWriter.write(it, patch.render()) }.toByteArray()
                    assertContentEquals(expected, files[index].readBytes(), "${voice.name}: all performance fields reach the renderer")
                }
                assertTrue("${files.size} rendered into" in output.toString())
                assertFalse("lands with" in output.toString())
                assertEquals(FlockPresets.forVoice(voice), Presets.forVoice(FlockPatch.ENGINE, voice.name))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `recipe and saved preset promotion preserve the complete seeded performance`() {
        val directory = createTempDirectory("flock-saved").toFile()
        try {
            for (seed in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
                val patch = FlockPatch("MY FLOCK", FlockVoice.SPLIT,
                    Flock.defaults(FlockVoice.SPLIT) + ("HOLD" to .1f), midi = 71, velocity = .4f, seed = seed)
                val recipe = PadRecipe(patch)
                assertEquals(patch, PadRecipe.fromJsonText(recipe.toJsonText()).patch)
                UserPresets.save(directory, patch, nowMillis = 12L)
                assertEquals(patch, UserPresets.read(directory).single().patch)
                val literal = if (seed == Long.MIN_VALUE) "Long.MIN_VALUE" else "${seed}L"
                val line = UserPresets.rosterLine(patch)
                assertTrue(line.startsWith("p(FlockVoice.SPLIT, \"MY FLOCK\", $literal, "))
                assertTrue("\"HOLD\" to 0.1f" in line)
                assertTrue("midi = 71, velocity = 0.4f, model = ${Flock.MODEL_VERSION}" in line)
                assertEquals("FlockPresets.kt", UserPresets.rosterFile(patch.engine))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid controls and unsupported held routes fail before writing`() {
        val directory = createTempDirectory("flock-cli-invalid").toFile()
        val destination = File(directory, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "35"), arrayOf("--midi", "85"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.01"), arrayOf("--velocity", "-.01"),
                arrayOf("--seed", "1.5"), arrayOf("--seed", "9223372036854775808"),
                arrayOf("--seed", "-9223372036854775809"),
                arrayOf("--instrument"), arrayOf("--drone"), arrayOf("--scramble", "3"),
                arrayOf("--model", "1"), arrayOf("--seconds", "2"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "FLOCK", "GATHER", *flags, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.joinToString())
                assertTrue(errors.size() > 0)
                assertFalse(destination.exists(), "invalid input must not create an audition directory")
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
