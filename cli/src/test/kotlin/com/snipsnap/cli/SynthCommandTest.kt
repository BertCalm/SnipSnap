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
            assertTrue("A1 - 4 bars - -1.97 cents" in printed, "stdout names the note, span and nudge: $printed")
            assertTrue(printed.all { it.code < 128 }, "stdout is ASCII, like every other line the CLI prints: $printed")
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

    /**
     * Hostile values for every flag the drone and the held instrument grew,
     * through the real front door (Cli.run, which turns a stray exception
     * into a generic exit 1). A bad flag is a person's typo, not a bad file:
     * it must be refused as one, exit 2 with the flag named, never the
     * catch-all, never an exception.
     */
    @Test
    fun `hostile drone and instrument flags are refused by name`() {
        val dir = File.createTempFile("synthhostile", "").let { it.delete(); it.mkdirs(); it }
        try {
            // A cheap valid drone the bad flag rides on: LEAD A3, one short bar.
            val base = listOf("synth", "RESIN", "LEAD", "--out", dir.path)
            val drone = base + listOf("--drone", "--root", "A3", "--bpm", "220")
            val cases: List<Pair<String, List<String>>> = listOf(
                "--root" to listOf("", "Z9", "H2", "A1000", "C-2", "G#9", "A", "1", "A1.5", "A#b1", "\u0000"),
                "--motion" to listOf("", "NaN", "-0.01", "1.0001", "1e9", "Infinity", "-Infinity", "x"),
                "--rate" to listOf("", "0", "3", "-1", "1.0", "2147483648", "four"),
                "--bpm" to listOf("", "NaN", "39.9", "220.1", "1e40", "-90", "fast"),
                "--bars" to listOf("", "0", "3", "16", "-1", "1.0"),
                "--loop" to listOf("", "0", "17", "-1", "999999999999", "1.5"),
                "--preset" to listOf("", "0", "-1", "999", "1.5", "one"),
            )
            for ((flag, values) in cases) for (v in values) {
                val args = drone.filterIndexed { i, a -> !(a == flag || (i > 0 && drone[i - 1] == flag)) } + listOf(flag, v)
                val out = ByteArrayOutputStream()
                val err = ByteArrayOutputStream()
                val code = Cli.run(args.toTypedArray(), PrintStream(out, true), PrintStream(err, true))
                val said = err.toString()
                assertEquals(2, code, "$flag '$v': exit $code, stderr: $said")
                assertTrue(flag.removePrefix("--") in said, "$flag '$v' refused without naming it: $said")
                assertTrue("Exception" !in said, "$flag '$v' leaked an exception: $said")
            }
            // The held instrument's own two, on the same terms.
            val held = base + listOf("--instrument")
            for (flag in listOf("--attack", "--release")) for (v in listOf("", "NaN", "-1", "0", "1e9", "Infinity", "slow")) {
                val err = ByteArrayOutputStream()
                val code = Cli.run((held + listOf(flag, v)).toTypedArray(), PrintStream(ByteArrayOutputStream(), true), PrintStream(err, true))
                val said = err.toString()
                assertEquals(2, code, "$flag '$v': exit $code, stderr: $said")
                assertTrue("Exception" !in said, "$flag '$v' leaked an exception: $said")
            }
            assertEquals(0, dir.listFiles()!!.size, "a refused run wrote something")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `odd but valid drone flags render a drone that reads back`() {
        val dir = File.createTempFile("synthodd", "").let { it.delete(); it.mkdirs(); it }
        try {
            // Spellings a person might type that mean something real.
            for ((flag, v) in listOf("--root" to "a3", "--root" to " A3 ", "--root" to "Bb3", "--motion" to "0", "--motion" to "1", "--motion" to "-0")) {
                dir.listFiles()!!.forEach { it.delete() }
                val args = arrayOf("synth", "RESIN", "LEAD", "--out", dir.path, "--drone", "--bpm", "220") + arrayOf(flag, v)
                val err = ByteArrayOutputStream()
                val code = Cli.run(args, PrintStream(ByteArrayOutputStream(), true), PrintStream(err, true))
                assertEquals(0, code, "$flag '$v': $err")
                val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
                val snip = com.snipsnap.audio.WavReader.read(wav)
                assertTrue(snip.frameCount > 0 && snip.samples.all { it.isFinite() }, "$flag '$v' wrote a broken WAV")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
