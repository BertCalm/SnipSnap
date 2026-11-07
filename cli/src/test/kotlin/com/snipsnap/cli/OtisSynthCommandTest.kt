package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.synth.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class OtisSynthCommandTest {
    private fun run(args: List<String>) = SynthCommand.run(args, PrintStream(ByteArrayOutputStream()))

    @Test fun `all harmonica performances export their complete replayable score`() {
        val root = createTempDirectory("otis-cli").toFile()
        try {
            assertEquals(0, run(listOf("OTIS", "HARP", "--all", "--out", root.path)))
            val recipes = root.listFiles()!!.filter { it.extension == "patch" }
            assertEquals(5, recipes.size)
            assertEquals(5, root.listFiles()!!.count { it.extension == "wav" })
            for (file in recipes) {
                val patch = assertIs<OtisPatch>(Patches.fromJsonText(file.readText()))
                val wave = WavReader.read(File(root, file.nameWithoutExtension + ".wav"))
                assertEquals(44100, wave.sampleRate)
                assertEquals(1, wave.channels)
                assertEquals(patch.render().frameCount, wave.frameCount)
                if (patch.name == "HARP DRAW") assertEquals(listOf(0, 4, 7, 10), patch.profile.events.map { it.semitones })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `saved chord survives note strength seed and control overrides`() {
        val root = createTempDirectory("otis-cli-replay").toFile()
        try {
            val source = OtisPresets.forVoice(OtisVoice.SQUEEZE).first { it.name == "SQUEEZE DRAW" }
            val recipe = File(root, "input.json").also { it.writeText(source.toJsonText()) }
            val out = File(root, "out")
            assertEquals(0, run(listOf("OTIS", "SQUEEZE", "--patch", recipe.path, "--midi", "57", "--velocity", ".4",
                "--seed", "-203", "--macros", "pulse=.7,length=.3", "--out", out.path)))
            val saved = assertIs<OtisPatch>(Patches.fromJsonText(out.listFiles()!!.single { it.extension == "patch" }.readText()))
            assertEquals(source.profile, saved.profile)
            assertEquals(57, saved.midi)
            assertEquals(.4f, saved.velocity)
            assertEquals(-203, saved.seed)
            assertEquals(.7f, saved.macros["PULSE"])
            assertEquals(.3f, saved.macros["LENGTH"])
            assertEquals(.5f, saved.macros["TUNE"])
        } finally { root.deleteRecursively() }
    }

    @Test fun `unsupported held modes and invalid overrides fail before files are written`() {
        val root = createTempDirectory("otis-cli-invalid").toFile()
        try {
            for (flags in listOf(listOf("--midi", "47"), listOf("--velocity", "NaN"), listOf("--seed", "2147483648"),
                listOf("--macros", "HOLD=1"), listOf("--macros", "TUNE=.2", "--midi", "60"), listOf("--instrument"))) {
                val out = File(root, "out")
                assertFailsWith<CliError> { run(listOf("OTIS", "SQUEEZE") + flags + listOf("--out", out.path)) }
                assertFalse(out.exists(), "invalid request created an output directory: $flags")
            }
        } finally { root.deleteRecursively() }
    }
}
