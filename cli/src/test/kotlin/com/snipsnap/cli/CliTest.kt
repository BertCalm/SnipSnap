package com.snipsnap.cli

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.MpcFormat
import com.snipsnap.mpc3.MpcFormats
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end runs of the CLI against synthesized material — the same
 * DrumSynth hits the classifier's own tests are tuned on, arranged into a
 * one-bar break, so chop → classify → place has a right answer to land on.
 */
class CliTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("cli").toFile()

    /** Run the CLI capturing stdout/stderr; returns (exit code, stdout, stderr). */
    private fun cli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), PrintStream(out, true), PrintStream(err, true))
        return Triple(code, out.toString(), err.toString())
    }

    /**
     * One bar at 100 BPM: kick on 1 and 3, snare on 2 and 4, closed hats on
     * the off-eighths, an open hat closing the bar. Silence between hits, so
     * the transient chopper has clean work to do.
     */
    private fun writeBreak(file: File): File {
        val rate = 44_100
        val step = (60f / 100f / 4f * rate).toInt()
        val hits: List<Pair<Int, Snip>> = listOf(
            0 to DrumSynth.kick(),
            2 to DrumSynth.closedHat(),
            4 to DrumSynth.snare(),
            6 to DrumSynth.closedHat(),
            8 to DrumSynth.kick(),
            10 to DrumSynth.closedHat(),
            12 to DrumSynth.snare(),
            14 to DrumSynth.openHat(),
        )
        val total = FloatArray(step * 16 + rate / 2)
        for ((stepIx, hit) in hits) {
            assertEquals(1, hit.channels, "test break assumes mono DrumSynth hits")
            assertEquals(rate, hit.sampleRate)
            val at = stepIx * step
            for (i in hit.samples.indices) {
                if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
            }
        }
        WavWriter.write(file, Snip(total, 1, rate))
        return file
    }

    @Test
    fun `chop builds a placed kit and every export format`() {
        val wav = writeBreak(File(temp, "break.wav"))
        val out = File(temp, "out")
        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "TestBreak",
            "--slices", "8", "--balance", "--export", "folder,expansion,xpn,xtd,xpj",
        )
        assertEquals(0, code, "stderr: $stderr")

        val kitDir = File(out, "TestBreak")
        val kit = KitStore.load(kitDir)
        assertEquals("TestBreak", kit.name)
        assertTrue(kit.pads.isNotEmpty())

        // The break's kick belongs on A01, its snare on A02 — that layout is
        // the whole point of classify + auto-place.
        assertEquals(DrumClass.KICK, kit.pad(1)?.drumClass, stdout)
        assertEquals(DrumClass.SNARE, kit.pad(2)?.drumClass, stdout)
        assertEquals(DrumClass.HAT_CLOSED, kit.pad(3)?.drumClass, stdout)
        assertEquals(DrumClass.HAT_OPEN, kit.pad(4)?.drumClass, stdout)
        // Balance ran: not every pad sits at the default level.
        assertTrue(kit.pads.any { it.level != 0.707946f }, "balance left every level at default")

        val card = File(out, "card")
        val xpm = File(card, "TestBreak/TestBreak.xpm")
        val xtd = File(card, "TestBreak.xtd")
        val xpj = File(card, "TestBreak.xpj")
        assertTrue(xpm.isFile)
        assertTrue(File(card, "Expansions/TestBreak/Expansion.xml").isFile)
        assertTrue(File(card, "TestBreak.xpn").isFile)
        assertTrue(xtd.isFile)
        assertTrue(File(card, "TestBreak_[TrackData]").listFiles().orEmpty().isNotEmpty())
        assertTrue(xpj.isFile)
        assertTrue(File(card, "TestBreak_[ProjectData]").listFiles().orEmpty().isNotEmpty())

        assertEquals(MpcFormat.MPC2_XML, MpcFormats.detect(xpm))
        assertEquals(MpcFormat.MPC3_ACVS, MpcFormats.detect(xtd))
        assertEquals("SerialisableTrackData", Acvs.read(xtd).header.objectType)
        assertEquals("SerialisableProjectData", Acvs.read(xpj).header.objectType)

        assertContains(stdout, "A01")
        assertContains(stdout, "KICK")
        assertContains(stdout, "tempo")
    }

    @Test
    fun `grid mode keeps capture order and skips placement`() {
        val wav = writeBreak(File(temp, "grid.wav"))
        val out = File(temp, "grid-out")
        val (code, _, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "GridKit", "--grid", "4",
        )
        assertEquals(0, code, "stderr: $stderr")
        val kit = KitStore.load(File(out, "GridKit"))
        assertEquals(4, kit.pads.size)
        // Capture order: the bar opens on the kick, so slot 1 is the kick
        // quarter regardless of class — and slot ordering is 1..4 dense.
        assertEquals(listOf(1, 2, 3, 4), kit.pads.map { it.slot })
    }

    @Test
    fun `chop refuses to clobber a kit without --overwrite and obeys it with`() {
        val wav = writeBreak(File(temp, "again.wav"))
        val out = File(temp, "again-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Again").first)
        val (code, _, stderr) = cli("chop", wav.path, "--out", out.path, "--name", "Again")
        assertNotEquals(0, code)
        assertContains(stderr, "already exists")
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "Again", "--overwrite").first,
        )
    }

    @Test
    fun `export command fans an existing kit folder out to formats`() {
        val wav = writeBreak(File(temp, "exp.wav"))
        val out = File(temp, "exp-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "ExpKit").first)
        val (code, stdout, stderr) = cli(
            "export", File(out, "ExpKit").path, "--export", "xtd", "--out", out.path,
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "ExpKit.xtd")
        assertEquals(MpcFormat.MPC3_ACVS, MpcFormats.detect(File(out, "card/ExpKit.xtd")))
    }

    @Test
    fun `import unpacks an xpn back into a kit folder`() {
        val wav = writeBreak(File(temp, "imp.wav"))
        val out = File(temp, "imp-out")
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "ImpKit", "--export", "xpn").first,
        )
        val xpn = File(out, "card/ImpKit.xpn")
        assertTrue(xpn.isFile)

        val dest = File(temp, "imp-dest")
        val (code, stdout, stderr) = cli("import", xpn.path, "--out", dest.path)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "ImpKit")
        val imported = KitStore.load(File(dest, "ImpKit"))
        assertEquals(
            KitStore.load(File(out, "ImpKit")).pads.map { it.slot },
            imported.pads.map { it.slot },
        )
    }

    @Test
    fun `classify prints the class next to its features`() {
        val kick = File(temp, "kick.wav")
        WavWriter.write(kick, DrumSynth.kick())
        val (code, stdout, _) = cli("classify", kick.path)
        assertEquals(0, code)
        assertContains(stdout, "KICK")
        assertContains(stdout, "kick.wav")
    }

    @Test
    fun `in-key retunes only tonal pads`() {
        val rate = 44_100
        val step = rate / 2
        // Half a second of silence first — an onset at frame zero has no
        // energy rise to detect. The default tonal() sits on 110 Hz, an A:
        // retuning into C minor must move it.
        val hits = listOf(
            1 to DrumSynth.kick(),
            2 to DrumSynth.tonal(),
        )
        // The tonal slice runs from its hit to the buffer's end; keep that
        // clearly under the classifier's 1.5 s hit-versus-phrase boundary.
        val total = FloatArray(step * 2 + (rate * 1.3f).toInt())
        for ((stepIx, hit) in hits) {
            val at = stepIx * step
            for (i in hit.samples.indices) {
                if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
            }
        }
        val wav = File(temp, "tonal.wav")
        WavWriter.write(wav, Snip(total, 1, rate))

        val out = File(temp, "key-out")
        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "KeyKit",
            "--slices", "2", "--key", "Cm",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "C minor")
        val kit = KitStore.load(File(out, "KeyKit"))
        assertTrue(kit.pads.any { it.drumClass == DrumClass.TONAL }, "no TONAL pad:\n$stdout")
        val tonal = kit.pads.first { it.drumClass == DrumClass.TONAL }
        val kickPad = kit.pads.first { it.drumClass == DrumClass.KICK }
        assertTrue(
            tonal.tuneCoarse != 0 || tonal.tuneFine != 0,
            "tonal pad was not retuned: $stdout",
        )
        assertEquals(0, kickPad.tuneCoarse)
        assertEquals(0, kickPad.tuneFine)
    }

    @Test
    fun `usage errors come back as exit 2 with a message`() {
        assertEquals(2, cli().first)
        val (code, _, stderr) = cli("chop")
        assertEquals(2, code)
        assertContains(stderr, "input file")
        assertEquals(2, cli("chop", "x.wav", "--wat").first)
        assertEquals(2, cli("frobnicate").first)
        val (badFmt, _, fmtErr) = cli("chop", "x.wav", "--export", "midi")
        assertEquals(2, badFmt)
        assertContains(fmtErr, "unknown export format")
    }

    @Test
    fun `help prints usage on stdout`() {
        val (code, stdout, _) = cli("help")
        assertEquals(0, code)
        assertContains(stdout, "chop")
        assertContains(stdout, "classify")
    }
}

class KeySpecTest {

    @Test
    fun `parses the ways people write keys`() {
        assertEquals(KeySpec(9, com.snipsnap.audio.Scale.MINOR), KeySpec.parse("Am"))
        assertEquals(KeySpec(0, com.snipsnap.audio.Scale.MAJOR), KeySpec.parse("C"))
        assertEquals(KeySpec(6, com.snipsnap.audio.Scale.MINOR_PENTATONIC), KeySpec.parse("F#minpent"))
        assertEquals(KeySpec(3, com.snipsnap.audio.Scale.MAJOR), KeySpec.parse("Eb major"))
        assertEquals(KeySpec(10, com.snipsnap.audio.Scale.MINOR), KeySpec.parse("bb minor"))
        assertEquals(KeySpec(7, com.snipsnap.audio.Scale.CHROMATIC), KeySpec.parse("G chromatic"))
    }

    @Test
    fun `rejects what it cannot read`() {
        assertFailsWith<CliError> { KeySpec.parse("H major") }
        assertFailsWith<CliError> { KeySpec.parse("C mixolydian") }
        assertFailsWith<CliError> { KeySpec.parse("") }
    }

    @Test
    fun `labels read like key signatures`() {
        assertEquals("A minor", KeySpec.parse("Am").label)
        assertEquals("D# major pentatonic", KeySpec.parse("Ebmajpent").label)
    }
}
