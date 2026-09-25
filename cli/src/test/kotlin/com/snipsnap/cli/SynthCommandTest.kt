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
    fun `a RESIN preset becomes a held instrument`() {
        val dir = File.createTempFile("synthinst", "").let { it.delete(); it.mkdirs(); it }
        try {
            val bytes = ByteArrayOutputStream()
            val code = SynthCommand.run(
                listOf("RESIN", "BRASS", "--preset", "1", "--instrument", "--attack", "0.5", "--release", "0.8", "--out", dir.path),
                PrintStream(bytes),
            )
            assertEquals(0, code)
            assertEquals(1, dir.listFiles { f -> f.name.endsWith(".xty") }!!.size, "one MPC 3 track")
            val data = dir.listFiles { f -> f.isDirectory && f.name.endsWith("_[TrackData]") }!!.single()
            assertEquals(9, data.listFiles { f -> f.extension == "wav" }!!.size, "nine zones")
            val (_, instrument) = com.snipsnap.kit.InstrumentStore.list(dir).single()
            assertTrue(instrument.zones.all { it.loopStartFrame > 0 }, "every zone holds")
            assertEquals(0.8f, instrument.release)
            assertTrue("9 zones, each holds" in bytes.toString(), "stdout says what was made: $bytes")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `--instrument is refused for an engine that cannot hold`() {
        val out = PrintStream(ByteArrayOutputStream())
        val e = runCatching {
            SynthCommand.run(listOf("TINES", "BELL", "--instrument", "--out", "/tmp"), out)
        }.exceptionOrNull()
        assertTrue(e is CliError, "expected CliError, got $e")
        assertTrue(e.message?.contains("RESIN") == true, "the refusal names the engine that can: ${e.message}")
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
