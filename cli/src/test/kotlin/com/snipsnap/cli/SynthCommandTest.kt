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

    @Test
    fun `a RESIN preset becomes a drone exactly as long as the grid would make it`() {
        val dir = File.createTempFile("synthdrone", "").let { it.delete(); it.mkdirs(); it }
        try {
            val bytes = ByteArrayOutputStream()
            val code = SynthCommand.run(
                listOf("RESIN", "BASS", "--drone", "--root", "A1", "--motion", "0.6", "--rate", "2", "--loop", "2", "--out", dir.path),
                PrintStream(bytes),
            )
            assertEquals(0, code)
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            val snip = com.snipsnap.audio.WavReader.read(wav)
            // A fresh session: 1 bar at 90 BPM, 44.1 kHz; A1 spans 4 of them.
            val session = com.snipsnap.loop.SessionBuilder.empty(44_100)
            assertEquals(2 * 4 * session.intervalFrames, snip.frameCount)
            val printed = bytes.toString()
            assertTrue("A1 · 4 BARS · -1.97¢" in printed, "stdout names the note, span and nudge: $printed")
            assertTrue("2 BREATHS" in printed, printed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `drone flags are refused where they can't mean anything`() {
        val out = PrintStream(ByteArrayOutputStream())
        fun refusal(vararg args: String): String? =
            (runCatching { SynthCommand.run(args.toList() + listOf("--out", "/tmp"), out) }.exceptionOrNull() as? CliError)?.message
        assertTrue(refusal("RESIN", "BASS", "--root", "A1")?.contains("--drone") == true)
        assertTrue(refusal("VELVET", "BASS", "--drone")?.contains("RESIN") == true)
        assertTrue(refusal("RESIN", "BASS", "--drone", "--instrument") != null)
        assertTrue(refusal("RESIN", "BASS", "--drone", "--all") != null)
        assertTrue(refusal("RESIN", "BASS", "--drone", "--root", "A5")?.contains("A1..A3") == true)
        assertTrue(refusal("RESIN", "BASS", "--drone", "--rate", "3") != null)
        assertTrue(refusal("RESIN", "BASS", "--drone", "--motion", "1.5") != null)
        assertTrue(refusal("RESIN", "BASS", "--drone", "--bars", "3") != null)
        assertTrue(refusal("RESIN", "BASS", "--drone", "--bpm", "300") != null)
    }
}
