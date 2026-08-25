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
