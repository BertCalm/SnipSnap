package com.snipsnap.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthCommandTest {

    @Test
    fun `renders every preset of a voice to wav`() {
        val dir = File.createTempFile("synthcmd", "").let { it.delete(); it.mkdirs(); it }
        try {
            val out = PrintStream(ByteArrayOutputStream())
            val code = SynthCommand.run(listOf("TINES", "BELL", "--all", "--out", dir.path), out)
            assertEquals(0, code)
            val wavs = dir.listFiles { f -> f.name.endsWith(".wav") }!!
            assertTrue(wavs.size >= 12, "expected a wav per BELL preset, got ${wavs.size}")
            assertTrue(wavs.all { it.length() > 1000 }, "every wav should carry audio")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an unknown engine is refused by name`() {
        val out = PrintStream(ByteArrayOutputStream())
        val e = runCatching {
            SynthCommand.run(listOf("THEREMIN", "AIR", "--out", "/tmp"), out)
        }.exceptionOrNull()
        assertTrue(e is CliError, "unknown engine should raise CliError, got $e")
        assertTrue(
            e.message?.contains("THEREMIN") == true,
            "expected the unknown engine name in the refusal, got: ${e.message}",
        )
    }
}
