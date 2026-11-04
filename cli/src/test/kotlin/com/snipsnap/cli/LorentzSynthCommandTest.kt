package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.synth.LorentzPatch
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.*

class LorentzSynthCommandTest {
    @Test
    fun `native collision controls note and touch survive command line replay`() {
        val root = kotlin.io.path.createTempDirectory("lorentz-cli-").toFile()
        try {
            val output = PrintStream(ByteArrayOutputStream())
            assertEquals(0, SynthCommand.run(listOf("LORENTZ", "FUSE", "--midi", "31", "--velocity", ".6",
                "--macros", "GAP=.75,IMPACT=.8", "--out", root.path), output))
            val file = root.listFiles()!!.single { it.extension == "patch" }
            val patch = assertIs<LorentzPatch>(Patches.fromJsonText(file.readText()))
            assertEquals(.6f, patch.velocity)
            assertEquals(7 / 24f, patch.macros["TUNE"])
            assertEquals(.8f, patch.macros["IMPACT"])
            assertEquals(3, patch.model)
            val replay = File(root, "replay")
            assertEquals(0, SynthCommand.run(listOf("LORENTZ", "FUSE", "--patch", file.path, "--out", replay.path), output))
            val a = WavReader.read(root.listFiles()!!.single { it.extension == "wav" })
            val b = WavReader.read(replay.listFiles()!!.single { it.extension == "wav" })
            assertContentEquals(a.samples, b.samples)
        } finally { root.deleteRecursively() }
    }
    @Test
    fun `out of range notes and nonfinite controls refuse before creating output`() {
        val root = kotlin.io.path.createTempDirectory("lorentz-refusal-").toFile()
        try {
            for (flags in listOf(listOf("--midi", "23"), listOf("--velocity", "NaN"), listOf("--macros", "GAP=NaN"))) {
                val out = File(root, "not-created")
                val code = Cli.run((listOf("synth", "LORENTZ", "LORENTZ") + flags + listOf("--out", out.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()))
                assertEquals(2, code)
                assertFalse(out.exists())
            }
        } finally { root.deleteRecursively() }
    }
}
