package com.snipsnap.cli

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.MidiGroove
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
    fun `feel transfers a donor's pocket onto a kit's patterns`() {
        val wav = writeBreak(File(temp, "fl.wav"))
        val out = File(temp, "fl-out")
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "FeelKit", "--slices", "8", "--groove").first,
        )
        val kitDir = File(out, "FeelKit")

        // Donor: a hard-swung .mid — every offbeat 16th pushed 76 pulses.
        val s16 = com.snipsnap.mpc3.Mpc3Clip.PULSES_PER_16TH
        val donorClip = com.snipsnap.mpc3.Mpc3Clip(
            "Swing Donor", 1,
            (0 until 16).map { com.snipsnap.mpc3.Mpc3Note(42, it * s16 + if (it % 2 == 1) 76L else 0L, 0.8f) },
        )
        val donor = File(temp, "donor.mid").apply { writeBytes(MidiGroove.write(donorClip, 100f)) }

        val (code, stdout, stderr) = cli("feel", kitDir.path, "--from", donor.path)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "Swing Donor")

        val felt = GrooveStore.load(kitDir).first()
        assertTrue(felt.name.endsWith("Feel"), felt.name)
        assertTrue(
            felt.notes.filter { (it.timePulses / s16) % 2 == 1L }.all { it.timePulses % s16 == 76L },
            "every offbeat leans the donor's 76 pulses: ${felt.notes.map { it.timePulses }}",
        )

        val (badCode, _, badErr) = cli("feel", kitDir.path)
        assertEquals(2, badCode)
        assertContains(badErr, "--from")
    }

    @Test
    fun `grooves leave as MIDI and a DAW beat arrives as one`() {
        val wav = writeBreak(File(temp, "md.wav"))
        val out = File(temp, "md-out")
        assertEquals(
            0,
            cli(
                "chop", wav.path, "--out", out.path, "--name", "MidKit",
                "--slices", "8", "--groove", "--export", "mid",
            ).first,
        )
        // One .mid per stored pattern, each a file any DAW opens.
        val mids = File(out, "card").listFiles { f: File -> f.extension == "mid" }.orEmpty()
        assertEquals(4, mids.size, "the standard four left as MIDI")
        val back = MidiGroove.read(mids.first { it.name == "MidKit Groove.mid" })
        assertTrue(back.clip.notes.isNotEmpty())
        assertTrue(back.bpm != null)

        // The other direction: a .mid becomes an existing kit's groove.
        val beat = File(temp, "beat.mid")
        beat.writeBytes(
            MidiGroove.write(
                com.snipsnap.mpc3.Mpc3Clip(
                    "Outside Beat", 1,
                    listOf(com.snipsnap.mpc3.Mpc3Note(36, 0, 0.9f), com.snipsnap.mpc3.Mpc3Note(38, 960, 0.8f)),
                ),
                120f,
            ),
        )
        val (code, stdout, stderr) = cli("import", beat.path, "--into", File(out, "MidKit").path)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "Outside Beat")
        val stored = GrooveStore.load(File(out, "MidKit"))
        assertEquals(4, stored.size)
        assertEquals("Outside Beat", stored[0].name)

        val (lostCode, _, lostErr) = cli("import", beat.path)
        assertEquals(2, lostCode)
        assertContains(lostErr, "--into")
    }

    @Test
    fun `merge earns a bank B from a second kit`() {
        val wav = writeBreak(File(temp, "mg.wav"))
        val out = File(temp, "mg-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "MergeA", "--slices", "4").first)
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "MergeB", "--slices", "4").first)

        val (code, stdout, stderr) = cli(
            "merge", File(out, "MergeA").path, File(out, "MergeB").path, "--out", out.path,
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "MergeA (bank A) + MergeB (bank B)")

        val merged = KitStore.load(File(out, "MergeA AB"))
        assertEquals(8, merged.pads.size)
        assertTrue(merged.pads.count { it.slot in 1..16 } == 4 && merged.pads.count { it.slot in 17..32 } == 4)
        assertTrue(merged.pad(17)!!.sampleFile.startsWith("B01_"), merged.pad(17)!!.sampleFile)
        // Both sources still load untouched.
        assertEquals(4, KitStore.load(File(out, "MergeA")).pads.size)
        assertEquals(4, KitStore.load(File(out, "MergeB")).pads.size)
    }

    @Test
    fun `chop-all digs a whole folder, naming failures instead of dying`() {
        val crate = File(temp, "crate").apply { mkdirs() }
        writeBreak(File(crate, "one.wav"))
        writeBreak(File(crate, "two.wav"))
        File(crate, "broken.wav").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(crate, "notes.txt").writeText("not audio")
        val out = File(temp, "crate-out")

        val (code, stdout, stderr) = cli("chop-all", crate.path, "--out", out.path, "--slices", "8")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "chopped 2 of 3 files")
        // Default names come from the files (plus whatever tempo was heard).
        val kitDirs = out.listFiles { f: File -> File(f, "kit.json").isFile }.orEmpty()
        assertEquals(2, kitDirs.size, stdout)
        assertTrue(kitDirs.any { it.name.startsWith("one") }, stdout)
        assertTrue(kitDirs.any { it.name.startsWith("two") }, stdout)
        assertContains(stdout, "broken.wav")
        assertContains(stdout, "skipped 1 non-wav")

        val (nameCode, _, nameErr) = cli("chop-all", crate.path, "--name", "X")
        assertEquals(2, nameCode)
        assertContains(nameErr, "drop --name")

        // Nothing chopped at all: that's a failure worth an exit code.
        val junkOnly = File(temp, "junk-crate").apply { mkdirs() }
        File(junkOnly, "bad.wav").writeBytes(byteArrayOf(9, 9))
        assertEquals(1, cli("chop-all", junkOnly.path, "--out", out.path).first)
    }

    @Test
    fun `no slice count means the audio answers`() {
        val wav = writeBreak(File(temp, "auto.wav"))
        val out = File(temp, "auto-out")
        val (code, stdout, stderr) = cli("chop", wav.path, "--out", out.path, "--name", "AutoKit")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "auto slice count:")
        val kit = KitStore.load(File(out, "AutoKit"))
        assertTrue(kit.pads.size in 6..10, "the 8-hit break asks for about 8, got ${kit.pads.size}")
    }

    @Test
    fun `swing rides the groove into the exports`() {
        val wav = writeBreak(File(temp, "sw.wav"))
        val out = File(temp, "sw-out")
        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "SwingKit",
            "--slices", "8", "--groove", "--swing", "62", "--export", "xtd",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "captured/swing 62/half/sparse")
        assertContains(Acvs.read(File(out, "card/SwingKit.xtd")).payloadText, "SwingKit Swing 62")

        val (badCode, _, badErr) = cli("chop", wav.path, "--out", out.path, "--swing", "62")
        assertEquals(2, badCode, "--swing without --groove is a usage error")
        assertContains(badErr, "rides on --groove")

        val (rangeCode, _, rangeErr) = cli(
            "chop", wav.path, "--out", out.path, "--groove", "--swing", "95",
        )
        assertEquals(2, rangeCode)
        assertContains(rangeErr, "50..75")
    }

    @Test
    fun `expansion exports carry the waveform tile by default`() {
        val wav = writeBreak(File(temp, "tile.wav"))
        val out = File(temp, "tile-out")
        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "TileKit",
            "--slices", "8", "--export", "expansion,xpn",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "cover art: waveform tile")

        val art = File(out, "card/Expansions/TileKit/TileKit.png")
        assertTrue(art.isFile, "the browser tile landed")
        val head = art.readBytes().copyOfRange(0, 4)
        assertTrue(head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte(), "a real PNG")
        java.util.zip.ZipFile(File(out, "card/TileKit.xpn")).use { zip ->
            assertTrue(zip.getEntry("artwork.png") != null, "the archive carries it too")
        }

        // The verdict's runner-up is one flag away; opting out is another.
        val rings = File(temp, "rings-out")
        assertEquals(
            0,
            cli(
                "export", File(out, "TileKit").path, "--export", "expansion",
                "--art", "rings", "--out", rings.path,
            ).first,
        )
        assertTrue(File(rings, "card/Expansions/TileKit/TileKit.png").isFile)

        val bare = File(temp, "bare-out")
        val (bareCode, bareOut, _) = cli(
            "export", File(out, "TileKit").path, "--export", "expansion",
            "--no-art", "--out", bare.path,
        )
        assertEquals(0, bareCode)
        assertTrue("cover art" !in bareOut, "opted out quietly")
        assertTrue(!File(bare, "card/Expansions/TileKit/TileKit.png").exists())

        val (clashCode, _, clashErr) = cli(
            "export", File(out, "TileKit").path, "--export", "expansion",
            "--art", "rings", "--no-art",
        )
        assertEquals(2, clashCode)
        assertContains(clashErr, "contradict")
    }

    @Test
    fun `art renders cover tiles for a kit, all styles or one`() {
        val wav = writeBreak(File(temp, "art.wav"))
        val out = File(temp, "art-out")
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "ArtKit", "--slices", "8").first,
        )
        val kitDir = File(out, "ArtKit").path
        val tiles = File(temp, "tiles")

        val (code, stdout, stderr) = cli("art", kitDir, "--out", tiles.path, "--size", "128")
        assertEquals(0, code, "stderr: $stderr")
        for (style in listOf("waveform", "grid", "slices", "rings")) {
            val png = File(tiles, "ArtKit_$style.png")
            assertTrue(png.isFile && png.length() > 500, "$style tile landed non-trivially")
            assertContains(stdout, png.path)
        }

        val one = File(temp, "one-tile")
        assertEquals(
            0,
            cli("art", kitDir, "--style", "grid", "--scheme", "snack-bar", "--out", one.path, "--size", "128").first,
        )
        assertEquals(listOf("ArtKit_grid.png"), one.list()!!.toList())

        val (badCode, _, badErr) = cli("art", kitDir, "--style", "cubist")
        assertEquals(2, badCode)
        assertContains(badErr, "unknown style")
    }

    @Test
    fun `diff compares two MPC files and exits one when they differ`() {
        val wav = writeBreak(File(temp, "df.wav"))
        val out = File(temp, "df-out")
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "DiffA", "--slices", "8", "--export", "xtd").first,
        )
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "DiffB", "--slices", "8", "--export", "xtd").first,
        )
        val a = File(out, "card/DiffA.xtd").path
        val b = File(out, "card/DiffB.xtd").path

        val same = cli("diff", a, a)
        assertEquals(0, same.first, "a file agrees with itself: ${same.third}")
        assertContains(same.second, "same structure, same values")

        val (code, stdout, _) = cli("diff", a, b, "--values")
        assertEquals(1, code, "different kits differ")
        assertContains(stdout, "MPC 3 (ACVS")
        assertContains(stdout, "values differ")
        assertContains(stdout, "\"DiffA\" -> \"DiffB\"")

        val (badCode, _, badErr) = cli("diff", a, wav.path)
        assertEquals(1, badCode, "a wav is not an MPC file")
        assertContains(badErr, "not an MPC 3 container")
    }

    @Test
    fun `preview flag renders the kit playing its own beat into the pack`() {
        val wav = writeBreak(File(temp, "prev.wav"))
        val out = File(temp, "prev-out")
        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "PrevKit",
            "--slices", "8", "--preview", "--export", "expansion,xpn",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "preview: rendered the kit playing its own beat")

        // The expansion carries it by the real packs' pairing convention.
        val previewWav = File(out, "card/Expansions/PrevKit/[Previews]/PrevKit.xpm.wav")
        assertTrue(previewWav.isFile, "expansion preview landed")
        assertTrue(previewWav.length() > 50_000, "preview is real audio, not a stub")
        val rendered = com.snipsnap.audio.WavReader.read(previewWav)
        assertEquals(2, rendered.channels)
        assertTrue(rendered.peak() > 0.05f, "the preview is audible")

        // And the .xpn archive has the same entry inside.
        java.util.zip.ZipFile(File(out, "card/PrevKit.xpn")).use { zip ->
            val entry = zip.getEntry("[Previews]/PrevKit.xpm.wav")
            assertTrue(entry != null && entry.size > 50_000, "xpn preview entry present")
        }
    }

    @Test
    fun `groove flag embeds the capture's own rhythm in native exports`() {
        val wav = writeBreak(File(temp, "groove.wav"))
        val out = File(temp, "groove-out")
        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "GrooveKit",
            "--slices", "8", "--groove", "--export", "xtd,xpj",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "groove: \"GrooveKit Groove\"")

        val xtd = File(out, "card/GrooveKit.xtd")
        val payload = Acvs.read(xtd).payloadText
        assertContains(payload, "GrooveKit Groove")
        val xpj = Acvs.read(File(out, "card/GrooveKit.xpj")).payloadText
        assertContains(xpj, "GrooveKit Groove")

        // Y1: the groove persisted beside kit.json, and provenance rode in.
        assertTrue(File(out, "GrooveKit/groove.json").isFile, "groove.json saved at chop time")
        val kit = KitStore.load(File(out, "GrooveKit"))
        val pad = kit.pads.first()
        assertEquals("groove.wav", pad.source["file"], "chopped pads remember their source")
        assertTrue(pad.source.containsKey("sourceFrame"))

        // Export later, without --groove context: still carries the rhythm.
        val later = File(temp, "groove-later")
        assertEquals(
            0,
            cli("export", File(out, "GrooveKit").path, "--export", "xtd", "--out", later.path).first,
        )
        assertContains(Acvs.read(File(later, "card/GrooveKit.xtd")).payloadText, "GrooveKit Groove")
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
    fun `melodic chop lays a scrambled scale out low to high`() {
        val rate = 44_100
        // 330, 110, 220 Hz — deliberately scrambled.
        val freqs = listOf(330.0, 110.0, 220.0)
        val total = FloatArray(rate * 3)
        freqs.forEachIndexed { i, f ->
            val tone = com.snipsnap.audio.DrumSynth.tonal(seconds = 0.9f, freq = f)
            for (j in tone.samples.indices) {
                if (i * rate + j < total.size) total[i * rate + j] += tone.samples[j] * 0.8f
            }
        }
        val wav = File(temp, "scale.wav")
        WavWriter.write(wav, Snip(total, 1, rate))

        val out = File(temp, "mel-out")
        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "Mel", "--grid", "3", "--melodic",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "melodic:")
        val kit = KitStore.load(File(out, "Mel"))
        val padHz = (1..3).map { slot ->
            com.snipsnap.audio.Pitch.detect(
                com.snipsnap.audio.WavReader.read(File(out, "Mel/${kit.pad(slot)!!.sampleFile}")),
            )!!.hz
        }
        assertEquals(padHz.sorted(), padHz, "pads must ascend in pitch: $padHz")

        assertEquals(2, cli("chop", wav.path, "--melodic", "--no-place").first)
    }

    @Test
    fun `ghosts flag layers pads and remix builds bank B`() {
        val wav = writeBreak(File(temp, "gr.wav"))
        val out = File(temp, "gr-out")
        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "GR", "--slices", "8", "--ghosts",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "ghost notes:")
        val kit = KitStore.load(File(out, "GR"))
        assertTrue(kit.pads.any { it.velocityLayers.size == 2 }, "one-shot pads gained soft zones")

        val (rCode, rOut, rErr) = cli("remix", File(out, "GR").path, "--seed", "9")
        assertEquals(0, rCode, "stderr: $rErr")
        assertContains(rOut, "evil twins")
        val remixed = KitStore.load(File(out, "GR"))
        assertTrue(remixed.pads.any { it.slot > 16 }, "bank B populated")
    }

    @Test
    fun `keys turns one pitched note into an instrument`() {
        val note = File(temp, "note.wav")
        // A clean 220 Hz decaying note - A3.
        val n = 44_100
        WavWriter.write(
            note,
            Snip(
                FloatArray(n) { i ->
                    val t = i.toDouble() / n
                    (0.5 * Math.sin(2 * Math.PI * 220 * i / n.toDouble()) * Math.exp(-2.0 * t)).toFloat()
                },
                1, n,
            ),
        )
        val out = File(temp, "keys-out")
        val (code, stdout, stderr) = cli("keys", note.path, "--name", "TestBass", "--out", out.path)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "A3")
        assertTrue(File(out, "card/TestBass.xty").isFile)
        assertTrue(File(out, "card/TestBass_[TrackData]/TestBass.xpm").isFile)

        // Noise refuses with the reason on stderr.
        val noise = File(temp, "noise.wav")
        val rng = kotlin.random.Random(5)
        WavWriter.write(noise, Snip(FloatArray(n) { (rng.nextFloat() * 2 - 1) * 0.5f }, 1, n))
        val (badCode, _, badErr) = cli("keys", noise.path, "--out", out.path)
        assertTrue(badCode != 0)
        assertContains(badErr, "pitch")
    }

    @Test
    fun `era runs the kit through a machine and undo brings it back`() {
        val wav = writeBreak(File(temp, "er.wav"))
        val out = File(temp, "er-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "EraCli", "--slices", "4").first)
        val kitDir = File(out, "EraCli")
        val kit = KitStore.load(kitDir)
        val before = kit.pads.associate { it.sampleFile to File(kitDir, it.sampleFile).readBytes() }

        val (code, stdout, stderr) = cli("era", kitDir.path, "sp1200", "--amount", "0.8")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "sp1200")
        for ((f, bytes) in before) {
            assertTrue(!File(kitDir, f).readBytes().contentEquals(bytes), "$f aged")
        }

        assertEquals(0, cli("era", kitDir.path, "--undo").first)
        for ((f, bytes) in before) {
            assertTrue(File(kitDir, f).readBytes().contentEquals(bytes), "$f restored byte-identical")
        }

        val (badCode, _, badErr) = cli("era", kitDir.path, "victrola")
        assertEquals(2, badCode)
        assertContains(badErr, "unknown era")
    }

    @Test
    fun `wear ages the kit at export time and the originals never change`() {
        val wav = writeBreak(File(temp, "wr.wav"))
        val out = File(temp, "wr-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "WornCli", "--slices", "4").first)
        val kitDir = File(out, "WornCli")

        val (statusCode, statusOut, _) = cli("wear", kitDir.path)
        assertEquals(0, statusCode)
        assertContains(statusOut, "not aging")

        assertEquals(0, cli("wear", kitDir.path, "--on").first)
        val (playsCode, playsOut, _) = cli("wear", kitDir.path, "--plays", "1000")
        assertEquals(0, playsCode)
        assertContains(playsOut, "mile")

        val kit = KitStore.load(kitDir)
        val originals = kit.pads.associate { it.sampleFile to File(kitDir, it.sampleFile).readBytes() }

        // A worn export and a --no-wear export of the same kit must differ -
        // and neither may touch the kit's own files.
        assertEquals(0, cli("export", kitDir.path, "--export", "folder", "--out", File(temp, "wr-worn").path).first)
        assertEquals(
            0,
            cli("export", kitDir.path, "--export", "folder", "--out", File(temp, "wr-new").path, "--no-wear").first,
        )
        for (pad in kit.pads) {
            val worn = File(temp, "wr-worn/card/${kit.name}/${pad.sampleFile}").readBytes()
            val pristine = File(temp, "wr-new/card/${kit.name}/${pad.sampleFile}").readBytes()
            assertTrue(!worn.contentEquals(pristine), "${pad.sampleFile} exported worn")
            assertTrue(
                File(kitDir, pad.sampleFile).readBytes().contentEquals(originals.getValue(pad.sampleFile)),
                "${pad.sampleFile} stayed pristine in the kit folder",
            )
        }

        // Wiping the ledger is a new tape: the default export matches --no-wear.
        assertEquals(0, cli("wear", kitDir.path, "--reset").first)
        assertEquals(0, cli("export", kitDir.path, "--export", "folder", "--out", File(temp, "wr-back").path).first)
        for (pad in kit.pads) {
            assertTrue(
                File(temp, "wr-back/card/${kit.name}/${pad.sampleFile}").readBytes()
                    .contentEquals(File(temp, "wr-new/card/${kit.name}/${pad.sampleFile}").readBytes()),
                "${pad.sampleFile} back to the new-tape sound",
            )
        }

        // A deliberate --wear override ages even a fresh tape - past the earned ceiling.
        assertEquals(
            0,
            cli("export", kitDir.path, "--export", "folder", "--out", File(temp, "wr-forced").path, "--wear", "1.5").first,
        )
        val forced = File(temp, "wr-forced/card/${kit.name}/${kit.pads.first().sampleFile}").readBytes()
        assertTrue(!forced.contentEquals(File(temp, "wr-new/card/${kit.name}/${kit.pads.first().sampleFile}").readBytes()))

        // Refusals: nonsense wear, contradictions, plays on a stopped deck.
        assertEquals(2, cli("export", kitDir.path, "--export", "folder", "--out", File(temp, "wr-x").path, "--wear", "9").first)
        assertEquals(
            2,
            cli("export", kitDir.path, "--export", "folder", "--out", File(temp, "wr-x").path, "--wear", "1", "--no-wear").first,
        )
        assertEquals(0, cli("wear", kitDir.path, "--off").first)
        assertEquals(2, cli("wear", kitDir.path, "--plays", "5").first)
    }

    @Test
    fun `answer derives the B-side and project lands it as a keys track`() {
        val wav = writeBreak(File(temp, "an.wav"))
        val out = File(temp, "an-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "AnswerCli", "--slices", "4", "--key", "Am").first)
        val kitDir = File(out, "AnswerCli")

        val (code, stdout, stderr) = cli("answer", kitDir.path, "--seed", "5")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "A minor")
        assertTrue(File(kitDir, "answer.json").isFile, "the answer persists beside the kit")
        val answer = com.snipsnap.kit.AnswerStore.load(kitDir)!!
        assertTrue(File(kitDir, answer.sampleFile).isFile, "the rendered bass note lands in the kit folder")
        assertTrue(answer.clip.notes.isNotEmpty())

        // Same seed, same answer - byte-stable on disk.
        val first = File(kitDir, "answer.json").readBytes()
        assertEquals(0, cli("answer", kitDir.path, "--seed", "5").first)
        assertTrue(File(kitDir, "answer.json").readBytes().contentEquals(first), "same seed reproduces the file")
        assertEquals(0, cli("answer", kitDir.path, "--seed", "6").first)
        assertTrue(!File(kitDir, "answer.json").readBytes().contentEquals(first), "a new seed rerolls")

        // One session: the break's kit track plus the answer's keys track.
        val (pCode, pOut, pErr) = cli("project", kitDir.path, "--name", "Answer Session", "--out", File(temp, "an-proj").path)
        assertEquals(0, pCode, "stderr: $pErr")
        assertContains(pOut, "answer track")
        assertTrue(File(temp, "an-proj/card/Answer Session.xpj").isFile)

        // No key, no answer - an honest refusal, not a guess.
        val out2 = File(temp, "an-out2")
        assertEquals(0, cli("chop", wav.path, "--out", out2.path, "--name", "KeylessCli", "--slices", "4").first)
        val (badCode, _, badErr) = cli("answer", File(out2, "KeylessCli").path)
        assertEquals(2, badCode)
        assertContains(badErr, "key")
    }

    @Test
    fun `sidea presses two kits into one postable beat tape folder`() {
        val wav = writeBreak(File(temp, "sa.wav"))
        val out = File(temp, "sa-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Tape Kit A", "--slices", "4").first)
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Tape Kit B", "--slices", "8").first)

        val (code, stdout, stderr) = cli(
            "sidea", File(out, "Tape Kit A").path, File(out, "Tape Kit B").path,
            "--title", "Night Drive", "--bars", "2", "--out", File(temp, "sa-tape").path,
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "tape stop")

        val tapeDir = File(temp, "sa-tape/Night Drive")
        assertTrue(File(tapeDir, "Night Drive.wav").isFile, "the continuous tape")
        assertTrue(File(tapeDir, "cover.png").isFile, "the labelled cover")
        assertTrue(File(tapeDir, "Night Drive.xpj").isFile, "the session rides along")
        val tracklist = File(tapeDir, "tracklist.txt").readText()
        assertContains(tracklist, "Tape Kit A")
        assertContains(tracklist, "Tape Kit B")
        assertContains(tracklist, "0:00")

        // Refusals: a title is required, and existing output needs --overwrite.
        assertEquals(2, cli("sidea", File(out, "Tape Kit A").path).first)
        assertEquals(
            2,
            cli(
                "sidea", File(out, "Tape Kit A").path, "--title", "Night Drive",
                "--out", File(temp, "sa-tape").path,
            ).first,
        )
    }

    @Test
    fun `jcard renders the insert and rides expansions and packs`() {
        val wav = writeBreak(File(temp, "jc.wav"))
        val out = File(temp, "jc-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Card Kit", "--slices", "4").first)
        val kitDir = File(out, "Card Kit")

        val (code, stdout, stderr) = cli("jcard", kitDir.path, "--out", File(temp, "jc-card").path)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "j-card")
        val card = File(temp, "jc-card/Card Kit J-Card.png")
        assertTrue(card.isFile, "the insert renders")
        assertTrue(card.readBytes().also { first ->
            assertEquals(0, cli("jcard", kitDir.path, "--out", File(temp, "jc-card").path).first)
            assertTrue(card.readBytes().contentEquals(first), "deterministic card")
        }.isNotEmpty())

        // The expansion export drops the card beside the artwork.
        assertEquals(
            0,
            cli("export", kitDir.path, "--export", "expansion", "--out", File(temp, "jc-exp").path).first,
        )
        val expansion = File(temp, "jc-exp/card/Expansions/Card Kit")
        assertTrue(File(expansion, "J-Card.png").isFile, "the expansion carries the insert")

        // And the pack builder gets per-kit cards for free.
        assertEquals(
            0,
            cli("pack", kitDir.path, "--title", "Card Pack", "--out", File(temp, "jc-pack").path).first,
        )
        assertTrue(
            File(temp, "jc-pack/card/Expansions/Card Pack/[J-Cards]/Card Kit.png").isFile,
            "the pack carries every kit's insert",
        )
    }

    @Test
    fun `treat crushes one pad from the terminal and undoes it`() {
        val wav = writeBreak(File(temp, "tr.wav"))
        val out = File(temp, "tr-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "TR", "--slices", "8").first)
        val kitDir = File(out, "TR")
        val padFile = File(kitDir, KitStore.load(kitDir).pad(2)!!.sampleFile)
        val before = padFile.readBytes()

        val (code, stdout, stderr) = cli("treat", kitDir.path, "A02", "crushed", "--amount", "0.8")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "recipe recorded")
        assertTrue(!before.contentEquals(padFile.readBytes()))

        assertEquals(0, cli("treat", kitDir.path, "A02", "--undo").first)
        assertTrue(before.contentEquals(padFile.readBytes()))

        val (badCode, _, badErr) = cli("treat", kitDir.path, "A02", "sparkled")
        assertTrue(badCode != 0)
        assertContains(badErr, "crushed")
    }

    @Test
    fun `project builds one session from several kits`() {
        val wav = writeBreak(File(temp, "sess.wav"))
        val out = File(temp, "sess-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Sess A", "--groove").first)
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Sess B").first)

        val (code, stdout, stderr) = cli(
            "project", File(out, "Sess A").path, File(out, "Sess B").path,
            "--name", "CLI Session", "--out", out.path,
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "kit track:        Sess A")
        assertContains(stdout, "kit track:        Sess B")
        val xpj = File(out, "card/CLI Session.xpj")
        assertTrue(xpj.isFile)
        val read = com.snipsnap.mpc3.Mpc3Project.read(xpj)
        assertTrue(read.isProject)
        assertTrue(read.trackNames.containsAll(listOf("Sess A", "Sess B")))
        // Kit A's groove (all four variations were saved) rides along.
        assertContains(Acvs.read(xpj).payloadText, "Sess A Groove")
    }

    @Test
    fun `project --mixdown renders the session as one WAV beside the xpj`() {
        val wav = writeBreak(File(temp, "mx.wav"))
        val out = File(temp, "mx-out")
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "MixKit", "--slices", "8", "--groove").first,
        )
        val (code, stdout, stderr) = cli(
            "project", File(out, "MixKit").path, "--name", "Mix Session", "--out", out.path, "--mixdown",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "mixdown:")
        val mixWav = File(out, "card/Mix Session.wav")
        assertTrue(mixWav.isFile, "the session's own WAV landed")
        val mix = com.snipsnap.audio.WavReader.read(mixWav)
        assertEquals(2, mix.channels)
        assertTrue(mix.peak() > 0.05f, "the session is audible")
    }

    @Test
    fun `pack puts several kits under one tile and import receives them all`() {
        val wav = writeBreak(File(temp, "pk.wav"))
        val out = File(temp, "pk-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "PackA", "--slices", "4").first)
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "PackB", "--slices", "4").first)

        val (code, stdout, stderr) = cli(
            "pack", File(out, "PackA").path, File(out, "PackB").path,
            "--title", "Crate Vol 1", "--out", out.path, "--xpn",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "2 kit(s) under one tile")

        val dest = File(out, "card/Expansions/Crate Vol 1")
        assertTrue(File(dest, "Programs/PackA.xpm").isFile && File(dest, "Programs/PackB.xpm").isFile)
        assertTrue(File(dest, "[Previews]/PackA.xpm.wav").isFile)
        assertTrue(File(dest, "CrateVol1.png").isFile, "the pack tile wears the pack's name")

        // The one-file twin comes back as every kit it carried.
        val fresh = File(temp, "pk-fresh")
        val (impCode, impOut, _) = cli("import", File(out, "card/Crate Vol 1.xpn").path, "--out", fresh.path)
        assertEquals(0, impCode)
        assertContains(impOut, "2 kit(s) from pack")
        assertTrue(File(fresh, "PackA/kit.json").isFile && File(fresh, "PackB/kit.json").isFile)

        val (noTitle, _, titleErr) = cli("pack", File(out, "PackA").path)
        assertEquals(2, noTitle)
        assertContains(titleErr, "--title")
    }

    @Test
    fun `backup and restore round-trip a folder of kits`() {
        val wav = writeBreak(File(temp, "bk.wav"))
        val out = File(temp, "bk-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "BK One").first)
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "BK Two").first)

        val zip = File(temp, "bk.zip")
        val (bCode, bOut, bErr) = cli("backup", out.path, "--out", zip.path)
        assertEquals(0, bCode, "stderr: $bErr")
        assertContains(bOut, "2 kit(s)")

        val fresh = File(temp, "bk-fresh")
        val (rCode, rOut, rErr) = cli("restore", zip.path, "--out", fresh.path)
        assertEquals(0, rCode, "stderr: $rErr")
        assertContains(rOut, "restored 2 kit(s)")
        assertEquals(
            KitStore.load(File(out, "BK One")).pads.map { it.slot },
            KitStore.load(File(fresh, "BK One")).pads.map { it.slot },
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
    fun `fit-tempo repitches the loops and restamps the kit`() {
        val rate = 44_100
        val step = (60f / 100f / 4f * rate).toInt()
        // The usual break for a confident tempo, then a long sustained tone
        // that classifies LOOP (past the 1.5s hit-versus-phrase boundary).
        val hits = listOf(
            0 to DrumSynth.kick(), 2 to DrumSynth.closedHat(), 4 to DrumSynth.snare(),
            6 to DrumSynth.closedHat(), 8 to DrumSynth.kick(), 10 to DrumSynth.closedHat(),
            12 to DrumSynth.snare(), 14 to DrumSynth.closedHat(),
        )
        val loopAt = step * 16
        val total = FloatArray(loopAt + (rate * 1.9f).toInt())
        for ((stepIx, hit) in hits) {
            val at = stepIx * step
            for (i in hit.samples.indices) {
                if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
            }
        }
        val loopTone = DrumSynth.tonal(seconds = 1.8f, freq = 220.0)
        for (i in loopTone.samples.indices) {
            if (loopAt + i < total.size) total[loopAt + i] += loopTone.samples[i] * 0.8f
        }
        val wav = File(temp, "ft.wav")
        WavWriter.write(wav, Snip(total, 1, rate))
        val out = File(temp, "ft-out")

        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "FitKit",
            "--slices", "9", "--fit-tempo", "90",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "-> 90bpm")
        assertContains(stdout, "repitched")

        val kit = KitStore.load(File(out, "FitKit"))
        assertEquals(90f, kit.tempoBpm, "the fitted kit is at the target tempo now")
        val loopPad = kit.pads.first { it.drumClass == DrumClass.LOOP }
        assertTrue("Loop_90bpm" in loopPad.sampleFile, "the stem carries the new tempo: ${loopPad.sampleFile}")

        // Asking to fit material with no tempo is an honest error. Half a
        // second of silence first so the lone tone is choppable at all.
        val toneOnly = File(temp, "ft-tone.wav")
        val lone = DrumSynth.tonal(seconds = 1.0f, freq = 220.0)
        val buf = FloatArray(rate / 2 + lone.samples.size)
        lone.samples.copyInto(buf, rate / 2)
        WavWriter.write(toneOnly, Snip(buf, 1, rate))
        val (badCode, _, badErr) = cli(
            "chop", toneOnly.path, "--out", out.path, "--name", "FitNone", "--fit-tempo", "90",
        )
        assertEquals(2, badCode)
        assertContains(badErr, "confident source tempo")
    }

    @Test
    fun `the capture names its own key with --key auto, or just remembers it`() {
        val rate = 44_100
        val step = (rate * 0.7f).toInt()
        // An A minor arpeggio leaning on its root: A, C, E, A - the doubled
        // root is what separates A minor from its relative C major.
        val total = FloatArray(step * 5)
        listOf(110.0, 130.81, 164.81, 220.0).forEachIndexed { i, hz ->
            val tone = DrumSynth.tonal(seconds = 0.6f, freq = hz)
            val at = (i + 1) * step
            for (j in tone.samples.indices) {
                if (at + j < total.size) total[at + j] += tone.samples[j] * 0.8f
            }
        }
        val wav = File(temp, "triad.wav")
        WavWriter.write(wav, Snip(total, 1, rate))
        val out = File(temp, "auto-key-out")

        // Asked for: the guess names the key and retunes into it.
        val (code, stdout, stderr) = cli(
            "chop", wav.path, "--out", out.path, "--name", "AutoKey", "--slices", "4", "--key", "auto",
        )
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "sounds like A minor")
        assertTrue(KitStore.load(File(out, "AutoKey")).key != null, "key stamped")

        // Unasked: the confident guess is remembered, but nothing retunes.
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "Remembered", "--slices", "4").first,
        )
        val remembered = KitStore.load(File(out, "Remembered"))
        assertTrue(remembered.key != null, "confident guess remembered in kit.json")
        assertTrue(remembered.pads.all { it.tuneCoarse == 0 && it.tuneFine == 0 }, "metadata only, no retune")

        // Drums have no key: asking for auto is an honest error.
        val drums = writeBreak(File(temp, "nokey.wav"))
        val (badCode, _, badErr) = cli(
            "chop", drums.path, "--out", out.path, "--name", "NoKey", "--slices", "8", "--key", "auto",
        )
        assertEquals(2, badCode)
        assertContains(badErr, "couldn't hear a key")
    }

    @Test
    fun `every command exits cleanly on a corpus of hostile files`() {
        val rnd = kotlin.random.Random(42)
        fun junk(name: String, size: Int): File = File(temp, name).apply {
            writeBytes(ByteArray(size) { rnd.nextInt(256).toByte() })
        }
        // A spread of malformed inputs: random noise, a fake gzip, a fake
        // zip, an empty file, and a truncated real WAV.
        val realWav = writeBreak(File(temp, "hostile-real.wav"))
        val truncatedWav = File(temp, "trunc.wav").apply {
            writeBytes(realWav.readBytes().copyOf(40))
        }
        val fakeGzip = File(temp, "fake.xtd").apply { writeBytes(byteArrayOf(0x1f, 0x8b.toByte()) + ByteArray(200) { 0 }) }
        val fakeZip = File(temp, "fake.xpn").apply { writeBytes("PK".toByteArray() + ByteArray(200) { 0x7F }) }
        val hostiles = listOf(
            junk("noise.bin", 500), junk("noise.xpn", 500), junk("noise.xtd", 500),
            junk("noise.mid", 300), junk("noise.wav", 300),
            File(temp, "empty.bin").apply { writeBytes(ByteArray(0)) },
            truncatedWav, fakeGzip, fakeZip,
        )
        val out = File(temp, "hostile-out")

        // Each command that reads a file, against each hostile file: the run
        // must return a clean exit (never throw, never exit outside 0..2).
        val invocations: List<(File) -> Triple<Int, String, String>> = listOf(
            { f -> cli("chop", f.path, "--out", out.path, "--overwrite") },
            { f -> cli("classify", f.path) },
            { f -> cli("import", f.path, "--out", out.path, "--overwrite") },
            { f -> cli("keys", f.path, "--out", out.path) },
            { f -> cli("diff", f.path, f.path) },
        )
        for (h in hostiles) {
            for (invoke in invocations) {
                val (code, _, _) = invoke(h)
                assertTrue(code in 0..2, "hostile ${h.name}: exit $code out of range")
            }
        }
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

// KeySpec's own tests live in :audio beside the parser (KeySpecTest);
// the chop tests above cover the CLI's use of it.
