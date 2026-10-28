package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.synth.FerryPatch
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FerrySynthCommandTest {
    @Test
    fun `passage exports native audio and complete replayable performance recipe`() {
        val directory = kotlin.io.path.createTempDirectory("ferry-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(listOf("FERRY", "PASSAGE", "--preset", "1", "--midi", "57",
                "--velocity", ".6", "--seed", Long.MIN_VALUE.toString(), "--macros", "HANDOFF=.25", "--out", directory.path), PrintStream(output)))
            val recipe = directory.listFiles()!!.single { it.extension == "patch" }
            val saved = Patches.fromJsonText(recipe.readText()) as FerryPatch
            assertEquals(57, saved.midi)
            assertEquals(.6f, saved.velocity)
            assertEquals(Long.MIN_VALUE, saved.seed)
            assertEquals(.25f, saved.macros["HANDOFF"])
            val audio = WavReader.read(directory.listFiles()!!.single { it.extension == "wav" })
            assertEquals(44100, audio.sampleRate)
            assertEquals(176400, audio.frameCount)
            // Saved recipe is a real CLI input, including the exact 64-bit seed.
            val replay = File(directory, "replay")
            assertEquals(0, SynthCommand.run(listOf("FERRY", "PASSAGE", "--patch", recipe.path, "--out", replay.path), PrintStream(output)))
            assertTrue(audio.samples.contentEquals(WavReader.read(replay.listFiles()!!.single { it.extension == "wav" }).samples))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `unsupported held or malformed passage refuses without an export`() {
        val root = kotlin.io.path.createTempDirectory("ferry-invalid").toFile()
        try {
            for (flags in listOf(listOf("--midi", "35"), listOf("--velocity", "NaN"), listOf("--macros", "HOLD=1"), listOf("--seed", "9223372036854775808"))) {
                val destination = File(root, "not-created")
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run((listOf("synth", "FERRY", "PASSAGE") + flags + listOf("--out", destination.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
                assertFalse(destination.exists(), errors.toString())
            }
        } finally { root.deleteRecursively() }
    }
}
