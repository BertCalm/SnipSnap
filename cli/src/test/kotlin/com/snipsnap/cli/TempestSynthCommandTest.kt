package com.snipsnap.cli

import com.snipsnap.synth.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.*

class TempestSynthCommandTest {
    @Test fun `new presets export replayable model two patches with macro edits`() {
        val root = kotlin.io.path.createTempDirectory("tempest-cli").toFile()
        try {
            val out = PrintStream(ByteArrayOutputStream())
            val first = File(root, "first")
            assertEquals(0, SynthCommand.run(listOf("TEMPEST", "KICK", "--preset", "1",
                "--macros", "SPACE=.75,TUNE=.5", "--out", first.path), out))
            val file = first.listFiles()!!.single { it.extension == "patch" }
            val p = assertIs<TempestPatch>(Patches.fromJsonText(file.readText()))
            assertEquals(2, p.model)
            assertEquals(.75f, p.macros["SPACE"])
            assertEquals(.5f, p.macros["TUNE"])
            val replay = File(root, "replay")
            assertEquals(0, SynthCommand.run(listOf("TEMPEST", "KICK", "--patch", file.path, "--out", replay.path), out))
            assertContentEquals(first.listFiles()!!.single { it.extension == "wav" }.readBytes(),
                replay.listFiles()!!.single { it.extension == "wav" }.readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun `legacy replay retains model unless explicitly upgraded`() {
        val root = kotlin.io.path.createTempDirectory("tempest-cli-old").toFile()
        try {
            val out = PrintStream(ByteArrayOutputStream())
            val input = File(root, "old.patch")
            // Original schema has no model and must keep its original sound.
            val p = TempestPatch("Original", TempestVoice.METAL, emptyMap(), model = 1)
            val fields = p.toJsonValue().entries - "model"
            input.writeText(com.snipsnap.json.Json.write(com.snipsnap.json.JsonValue.Obj(fields)))
            for ((flags, expected) in listOf(emptyList<String>() to 1, listOf("--macros", "DRIVE=.5") to 1,
                listOf("--model", "2") to 2)) {
                val dest = File(root, "result${expected}-${flags.size}")
                assertEquals(0, SynthCommand.run(listOf("TEMPEST", "METAL", "--patch", input.path) + flags + listOf("--out", dest.path), out))
                val saved = Patches.fromJsonText(dest.listFiles()!!.single { it.extension == "patch" }.readText()) as TempestPatch
                assertEquals(expected, saved.model)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `invalid models controls and reserved HOLD refuse before output`() {
        val root = kotlin.io.path.createTempDirectory("tempest-cli-invalid").toFile()
        try {
            for (flags in listOf(listOf("--model", "4"), listOf("--macros", "HOLD=1"),
                listOf("--macros", "SOURCE=NaN"), listOf("--macros", "SPACE=2"),
                listOf("--macros", "SOURCE=.1,SOURCE=.5"), listOf("--macros", "MISSING=.5"))) {
                val dest = File(root, "not-created")
                assertEquals(2, Cli.run((listOf("synth", "TEMPEST", "KICK") + flags + listOf("--out", dest.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
                assertFalse(dest.exists())
            }
        } finally { root.deleteRecursively() }
    }
}
