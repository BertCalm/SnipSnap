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

    /** verse pads (8s) | dense drum break (8s) | outro pads (5s) — a "song". */
    private fun writeSong(file: File): File {
        val rate = 44_100
        fun pads(seconds: Float): FloatArray {
            val n = (seconds * rate).toInt()
            return FloatArray(n) { i ->
                var s = 0.0
                for (hz in doubleArrayOf(220.0, 277.18, 329.63)) {
                    s += Math.sin(2.0 * Math.PI * hz * i / rate)
                }
                (0.18 * s).toFloat()
            }
        }
        fun breakSec(seconds: Float): FloatArray {
            val n = (seconds * rate).toInt()
            val out = FloatArray(n)
            val beat = (60f / 100f * rate).toInt()
            fun place(hit: Snip, at: Int, gain: Float) {
                for (i in hit.samples.indices) {
                    if (at + i >= n) break
                    out[at + i] += hit.samples[i] * gain
                }
            }
            var t = 0
            var count = 0
            while (t < n) {
                place(DrumSynth.kick(), t, 0.9f)
                if (count % 2 == 1) place(DrumSynth.snare(), t, 0.8f)
                place(DrumSynth.closedHat(), t, 0.5f)
                place(DrumSynth.closedHat(), t + beat / 2, 0.4f)
                t += beat
                count++
            }
            return out
        }
        val parts = listOf(pads(8f), breakSec(8f), pads(5f))
        val total = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(total, at)
            at += p.size
        }
        WavWriter.write(file, Snip(total, 1, rate))
        return file
    }

    @Test
    fun `dig finds the break inside a song and chops it with provenance`() {
        val song = writeSong(File(temp, "Track 07.wav"))
        val out = File(temp, "dig-out")

        val (code, stdout, stderr) = cli("dig", song.path)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "Track 07.wav:")
        assertContains(stdout, "1. 0:0")

        val (chopCode, chopOut, chopErr) = cli("dig", song.path, "--chop", "--break-pad", "--out", out.path)
        assertEquals(0, chopCode, "stderr: $chopErr")
        assertContains(chopOut, "1 break(s) dug and chopped")
        val kitDir = File(out, "Track 07 Break")
        assertTrue(File(kitDir, "kit.json").isFile, "the dug break became a kit")
        val kit = KitStore.load(kitDir)
        assertTrue(kit.pads.isNotEmpty())
        assertTrue(
            kit.pads.all { it.source["song"] == "Track 07.wav" && it.source["at"] != null },
            "every pad knows which song and where: ${kit.pads.first().source}",
        )
        // --break-pad rides dig --chop: the dug break also lands whole,
        // tap-through-able, with the same song provenance as every slice.
        val breakPad = kit.pads.maxBy { it.slot }
        assertTrue(breakPad.chain != null, "the break pad is a chain")
        assertEquals("Break", breakPad.displayName)

        // --air: the same dig also cuts the song's calm stretch into a
        // texture kit - LOOP pads by declaration, provenance stamped.
        val (airCode, airOut, airErr) = cli("dig", song.path, "--air", "--out", out.path)
        assertEquals(0, airCode, "stderr: $airErr")
        assertContains(airOut, "air kit(s) cut")
        val airDir = File(out, "Track 07 Air")
        assertTrue(File(airDir, "kit.json").isFile, "the air became a kit")
        val airKit = KitStore.load(airDir)
        assertTrue(airKit.pads.isNotEmpty())
        assertTrue(
            airKit.pads.all {
                it.drumClass == com.snipsnap.audio.DrumClass.LOOP &&
                    it.source["song"] == "Track 07.wav" && it.source["at"] != null
            },
            "air pads are LOOPs that know their song: ${airKit.pads.first().source}",
        )

        // A song with no break says so instead of inventing one.
        val toneFile = File(temp, "Ambient.wav").also { f ->
            val rate = 44_100
            WavWriter.write(
                f,
                Snip(FloatArray(6 * rate) { i -> (0.4 * Math.sin(2.0 * Math.PI * 220.0 * i / rate)).toFloat() }, 1, rate),
            )
        }
        val (tCode, tOut, _) = cli("dig", toneFile.path)
        assertEquals(0, tCode)
        assertContains(tOut, "no break heard")

        // Drums wall to wall: --air honestly cuts nothing.
        val drumsFile = writeBreak(File(temp, "AllDrums.wav"))
        val (dCode, dOut, _) = cli("dig", drumsFile.path, "--air", "--out", out.path)
        assertEquals(0, dCode)
        assertContains(dOut, "no air heard")
        assertContains(dOut, "no air cut")

        assertEquals(2, cli("dig", File(temp, "missing-folder").path).first)
    }

    @Test
    fun `resample bounces the kit into a new generation, source untouched`() {
        val wav = writeBreak(File(temp, "rs.wav"))
        val out = File(temp, "rs-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Origin", "--slices", "4").first)
        val kitDir = File(out, "Origin")
        val sourceBytes = kitDir.listFiles()!!.filter { it.isFile }
            .associate { it.name to it.readBytes() }

        val (code, stdout, stderr) = cli("resample", kitDir.path, "--out", out.path, "--slices", "4")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "generation 2 from Origin")
        val gen2Dir = File(out, "Origin Gen 2")
        val gen2 = KitStore.load(gen2Dir)
        assertTrue(gen2.pads.isNotEmpty(), "the bounce chopped into pads")
        assertTrue(
            gen2.pads.all { it.source["resampledFrom"] == "Origin" && it.source["generation"] == "2" },
            "lineage stamped: ${gen2.pads.first().source}",
        )

        // The source kit is byte-identical throughout.
        for ((fname, bytes) in sourceBytes) {
            assertTrue(File(kitDir, fname).readBytes().contentEquals(bytes), "$fname untouched")
        }

        // Resampling the resample counts one more pass of the machine.
        assertEquals(0, cli("resample", gen2Dir.path, "--out", out.path, "--slices", "4").first)
        val gen3 = KitStore.load(File(out, "Origin Gen 3"))
        assertTrue(gen3.pads.all { it.source["generation"] == "3" }, "the counter climbs, the name stays rooted")
    }

    @Test
    fun `lineage walks dig to generation 3 from the terminal`() {
        val song = writeSong(File(temp, "Track 09.wav"))
        val out = File(temp, "lin-out")
        assertEquals(0, cli("dig", song.path, "--chop", "--out", out.path).first)
        val breakDir = File(out, "Track 09 Break")
        assertEquals(0, cli("resample", breakDir.path, "--out", out.path, "--slices", "4").first)
        assertEquals(0, cli("resample", File(out, "Track 09 Break Gen 2").path, "--out", out.path, "--slices", "4").first)

        val gen3Dir = File(out, "Track 09 Break Gen 3")
        val (code, stdout, stderr) = cli("lineage", gen3Dir.path, "--png")
        assertEquals(0, code, "stderr: $stderr")
        // The full chain, in order, down to the dug origin.
        val stops = listOf(
            "Track 09 Break Gen 3",
            "resampled from Track 09 Break Gen 2",
            "resampled from Track 09 Break\n",
            "dug from Track 09.wav at ",
        ).map { stdout.indexOf(it) }
        assertTrue(stops.all { it >= 0 }, "every hop prints:\n$stdout")
        assertEquals(stops, stops.sorted(), "hops print in chain order:\n$stdout")
        assertTrue(File(gen3Dir, "${gen3Dir.name}-lineage.png").isFile, "the card lands beside the kit")

        assertEquals(2, cli("lineage", File(temp, "not-a-kit").path).first)
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

        // --save bottles the (now felt) kit's pocket as a .pocket file...
        val pocketPath = File(temp, "fl-swing").path
        val (sCode, sOut, sErr) = cli("feel", kitDir.path, "--save", pocketPath)
        assertEquals(0, sCode, "stderr: $sErr")
        assertContains(sOut, "pocket saved")
        val pocket = File("$pocketPath.pocket")
        assertTrue(pocket.isFile, "the extension arrives on its own")

        // ...and applying the file moves a kit exactly the way the donor
        // kit's own groove would - the round-trip promise.
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "FeelKit2", "--slices", "8", "--groove").first,
        )
        val kit2 = File(out, "FeelKit2")
        assertEquals(0, cli("feel", kit2.path, "--from", pocket.path).first)
        val viaPocket = File(kit2, "groove.json").readBytes()

        assertEquals(
            0,
            cli(
                "chop", wav.path, "--out", out.path, "--name", "FeelKit2",
                "--slices", "8", "--groove", "--overwrite",
            ).first,
        )
        assertEquals(0, cli("feel", kit2.path, "--from", kitDir.path).first)
        assertTrue(
            viaPocket.contentEquals(File(kit2, "groove.json").readBytes()),
            "the bottled pocket applies identically to the donor kit itself",
        )

        val (bothCode, _, bothErr) = cli("feel", kitDir.path, "--from", pocket.path, "--save", "x")
        assertEquals(2, bothCode)
        assertContains(bothErr, "pick one")
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
        assertEquals(6, mids.size, "the standard four plus fill and ghosts left as MIDI")
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
            cli("art", kitDir, "--style", "grid", "--scheme", "petrol", "--out", one.path, "--size", "128").first,
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
        // GG2+GG5: the kit has a snare, so the fill and the ghosts join the set.
        assertContains(stdout, "/fill/ghosted)")
        val grooves = com.snipsnap.kit.GrooveStore.load(File(out, "GrooveKit"))
        assertEquals(
            listOf("GrooveKit Fill", "GrooveKit Ghosted"),
            grooves.takeLast(2).map { it.name },
            "patterns five and six are the fill and the ghosts",
        )

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
    fun `era survives AMT 0 from the terminal - the value Eras treats as a no-op`() {
        val wav = writeBreak(File(temp, "erz.wav"))
        val out = File(temp, "erz-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "EraZeroCli", "--slices", "4").first)
        val kitDir = File(out, "EraZeroCli")

        val (code, stdout, stderr) = cli("era", kitDir.path, "sp1200", "--amount", "0")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "sp1200")
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

        // --band grows the answer into sidemen, and the session lands every one.
        val (bandCode, bandOut, bandErr) = cli("answer", kitDir.path, "--seed", "5", "--band")
        assertEquals(0, bandCode, "stderr: $bandErr")
        assertContains(bandOut, "Stabs")
        val withBand = com.snipsnap.kit.AnswerStore.load(kitDir)!!
        assertTrue(withBand.band.isNotEmpty(), "the band persisted")
        assertTrue(
            withBand.band.all { File(kitDir, it.sampleFile).isFile && it.clip.notes.isNotEmpty() },
            "every sideman has a rendered note and a line",
        )
        val (bpCode, bpOut, bpErr) = cli(
            "project", kitDir.path, "--name", "Band Session", "--out", File(temp, "an-band-proj").path,
        )
        assertEquals(0, bpCode, "stderr: $bpErr")
        assertEquals(
            1 + withBand.band.size, Regex("answer track").findAll(bpOut).count(),
            "bass and every sideman land as their own tracks: $bpOut",
        )

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

        // The expansion export drops the card beside the artwork, and the
        // liner notes beside the card.
        assertEquals(
            0,
            cli("export", kitDir.path, "--export", "expansion", "--out", File(temp, "jc-exp").path).first,
        )
        val expansion = File(temp, "jc-exp/card/Expansions/Card Kit")
        assertTrue(File(expansion, "J-Card.png").isFile, "the expansion carries the insert")
        assertTrue(File(expansion, "liner-notes.txt").isFile, "and the liner notes")
        assertContains(File(expansion, "liner-notes.txt").readText(), "CARD KIT")

        // The notes verb prints the story and writes it beside the kit.
        val (nCode, nOut, nErr) = cli("notes", kitDir.path)
        assertEquals(0, nCode, "stderr: $nErr")
        assertContains(nOut, "Chopped from \"jc.wav\".")
        assertContains(nOut, "THE SOUNDS")
        assertTrue(File(kitDir, "liner-notes.txt").isFile)

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
    fun `doctor names what is wrong with the sound and --fix cures the safe subset`() {
        // A deliberately sick kit, built through the same model the app uses.
        val kitDir = File(temp, "SickCli")
        val m = com.snipsnap.shell.KitBuilderModel.create("SickCli", kitDir)
        val rate = 44_100
        fun boomy(hz: Double, seconds: Float, seed: Int): Snip {
            val rnd = kotlin.random.Random(seed)
            return Snip(
                FloatArray((seconds * rate).toInt()) { i ->
                    (0.5 * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat() + (rnd.nextFloat() * 2 - 1) * 0.01f
                },
                1, rate,
            )
        }
        m.assign(1, boomy(60.0, 0.8f, 1), DrumClass.LOOP)
        m.assign(2, boomy(55.0, 0.9f, 2), DrumClass.LOOP)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.assign(4, DrumSynth.openHat(), DrumClass.HAT_OPEN)
        m.update(3) { it.copy(muteGroup = 0) }
        m.update(4) { it.copy(muteGroup = 0) }
        m.save()

        val (code, stdout, stderr) = cli("doctor", kitDir.path)
        assertEquals(1, code, "a sick kit exits 1 so it scripts like a check; stderr: $stderr")
        assertContains(stdout, "fight for the sub")
        assertContains(stdout, "mute group")

        val (fixCode, fixOut, _) = cli("doctor", kitDir.path, "--fix")
        assertContains(fixOut, "fixed:")
        val (again, againOut, _) = cli("doctor", kitDir.path)
        assertEquals(fixCode, again, "the fix run and the follow-up check agree")
        assertTrue("[fixable]" !in againOut, "nothing fixable remains, advice may: $againOut")

        // A healthy kit is healthy on the first visit.
        val wav = writeBreak(File(temp, "dr.wav"))
        assertEquals(0, cli("chop", wav.path, "--out", File(temp, "dr-out").path, "--name", "DrKit", "--slices", "4").first)
        val (hCode, hOut, _) = cli("doctor", File(temp, "dr-out/DrKit").path)
        assertEquals(0, hCode, "a chopped break is healthy: $hOut")
    }

    @Test
    fun `similar finds another snare like this one across the library`() {
        // A little library: two kits built through the model, snares and all.
        val library = File(temp, "sim-library").apply { mkdirs() }
        fun buildKit(name: String, snareSeed: Int) {
            val m = com.snipsnap.shell.KitBuilderModel.create(name, File(library, name))
            m.assign(1, DrumSynth.kick(), DrumClass.KICK)
            m.assign(2, DrumSynth.snare(seed = snareSeed), DrumClass.SNARE)
            m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
            m.save()
        }
        buildKit("Sim A", snareSeed = 2)
        buildKit("Sim B", snareSeed = 3)

        val (code, stdout, stderr) = cli("similar", File(library, "Sim A").path, library.path, "--pad", "A02", "--top", "3")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "more like Sim A A02")
        val firstMatch = stdout.lines().first { it.trim().startsWith("1.") }
        assertContains(firstMatch, "Sim B A02")
        assertContains(firstMatch, "SNARE")

        // The target itself is never its own best match.
        val matchLines = stdout.lines().filter { it.trim().matches(Regex("^\\d+\\. .*")) }
        assertTrue(matchLines.none { "Sim A A02" in it }, stdout)

        // A bare .wav works as a target too.
        val wav = File(temp, "sim-snare.wav")
        com.snipsnap.audio.WavWriter.write(wav, DrumSynth.snare(seed = 9))
        val (wCode, wOut, _) = cli("similar", wav.path, library.path, "--top", "2")
        assertEquals(0, wCode)
        assertContains(wOut.lines().first { it.trim().startsWith("1.") }, "SNARE")

        // Refusals: a kit target needs --pad; an empty library says so.
        assertEquals(2, cli("similar", File(library, "Sim A").path, library.path).first)
        assertEquals(2, cli("similar", wav.path, File(temp, "sim-empty").apply { mkdirs() }.path).first)
    }

    @Test
    fun `shape edits pad metadata only and reset restores the defaults`() {
        val wav = writeBreak(File(temp, "sh.wav"))
        val out = File(temp, "sh-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "ShapeCli", "--slices", "4").first)
        val kitDir = File(out, "ShapeCli")
        val padFile = File(kitDir, KitStore.load(kitDir).pad(1)!!.sampleFile)
        val audioBefore = padFile.readBytes()

        val (code, stdout, stderr) = cli("shape", kitDir.path, "A01", "--decay", "0.3", "--cutoff", "0.5")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "decay 0.30")
        val shaped = KitStore.load(kitDir).pad(1)!!
        assertEquals(0.3f, shaped.decay)
        assertEquals(0.5f, shaped.cutoff)
        assertEquals(null, shaped.attack, "unset fields stay unset")
        assertTrue(padFile.readBytes().contentEquals(audioBefore), "shape never touches audio")

        assertEquals(0, cli("shape", kitDir.path, "A01", "--reset").first)
        val reset = KitStore.load(kitDir).pad(1)!!
        assertEquals(null, reset.decay)
        assertEquals(null, reset.cutoff)

        // Refusals: out-of-range values, no-op calls, reset with values.
        assertEquals(2, cli("shape", kitDir.path, "A01", "--decay", "1.5").first)
        assertEquals(2, cli("shape", kitDir.path, "A01").first)
        assertEquals(2, cli("shape", kitDir.path, "A01", "--reset", "--decay", "0.5").first)
        assertEquals(2, cli("shape", kitDir.path, "H16", "--decay", "0.5").first)
    }

    @Test
    fun `crate indexes the library, names the dupes, and builds the best-of`() {
        val root = File(temp, "crate-lib").apply { mkdirs() }
        fun buildKit(name: String, kickTwin: Boolean) {
            val m = com.snipsnap.shell.KitBuilderModel.create(name, File(root, name))
            m.assign(1, DrumSynth.kick(), DrumClass.KICK)
            m.assign(2, DrumSynth.snare(seed = if (kickTwin) 9 else 2), DrumClass.SNARE)
            m.save()
        }
        buildKit("Crate One", kickTwin = false)
        buildKit("Crate Two", kickTwin = true)

        val (code, stdout, stderr) = cli("crate", root.path, "--dupes", "--pick", "snare", "--top", "2")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "2 kit(s), 4 pads")
        assertContains(stdout, "4 measured")
        assertContains(stdout, "duplicate pair(s)")
        assertContains(stdout, "best snares")

        // The second run is all cache.
        val (_, again, _) = cli("crate", root.path)
        assertContains(again, "0 measured, 4 from the index")

        // The best-of kit assembles and reports its pads.
        val (bCode, bOut, bErr) = cli("crate", root.path, "--build", "Best Of", "--out", File(temp, "crate-best").path)
        assertEquals(0, bCode, "stderr: $bErr")
        assertContains(bOut, "built:")
        assertTrue(File(temp, "crate-best/Best Of/kit.json").isFile)

        assertEquals(2, cli("crate", root.path, "--pick", "vibraslap").first)
        assertEquals(2, cli("crate", File(temp, "crate-nowhere").path).first)
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
    fun `break-pad adds one chain pad whose slices are the chop's own cuts`() {
        val wav = writeBreak(File(temp, "bp.wav"))
        val out = File(temp, "bp-out")
        val (code, stdout, stderr) =
            cli("chop", wav.path, "--out", out.path, "--name", "BP", "--slices", "8", "--break-pad")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "break pad:")

        val kit = KitStore.load(File(out, "BP"))
        val sliceCount = kit.pads.count { it.chain == null }
        assertTrue(sliceCount >= 2, "the fixture chops into several hits")
        assertContains(stdout, "chain of $sliceCount slices")
        assertEquals(sliceCount + 1, kit.pads.size, "the slices + one break pad")
        val breakPad = kit.pads.maxBy { it.slot }
        assertEquals("Break", breakPad.displayName)
        assertEquals(com.snipsnap.audio.DrumClass.LOOP, breakPad.drumClass)
        val chain = breakPad.chain!!
        assertEquals(sliceCount, chain.sliceCount)
        assertEquals(sliceCount, chain.cycle)
        assertEquals(0L, chain.boundaries[0])

        // The chain's boundaries ARE the chop's cuts: each slice pad's
        // sourceFrame, re-based to the first cut, appears as a boundary.
        val first = breakPad.source["sourceFrame"]!!.toLong()
        val sliceStarts = kit.pads.filter { it.chain == null }
            .map { it.source["sourceFrame"]!!.toLong() - first }
            .sorted()
        assertEquals(sliceStarts, chain.boundaries, "boundaries equal the chop slices")

        // The pad's WAV runs from the first cut to the end of the source.
        val sourceFrames = com.snipsnap.xpm.WavInfo.read(wav).frameCount
        val breakFrames = com.snipsnap.xpm.WavInfo.read(File(out, "BP/${breakPad.sampleFile}")).frameCount
        assertEquals(sourceFrames - first, breakFrames)

        // One slice can't be tapped through - the pad is skipped, not broken.
        val (oneCode, oneOut, _) =
            cli("chop", wav.path, "--out", out.path, "--name", "BP1", "--slices", "1", "--break-pad")
        assertEquals(0, oneCode)
        assertContains(oneOut, "no break pad")
        assertEquals(null, KitStore.load(File(out, "BP1")).pads.single().chain)
    }

    @Test
    fun `arrange lays the song into numbered switchable sequences`() {
        val wav = writeBreak(File(temp, "ar.wav"))
        val out = File(temp, "ar-out")
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "AR", "--slices", "8", "--groove").first,
        )
        val kitDir = File(out, "AR")

        val (code, stdout, stderr) = cli("arrange", kitDir.path, "--out", out.path)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "\"AR Song\"")
        assertContains(stdout, "01  intro")
        assertContains(stdout, "the turn")
        assertContains(stdout, "flip sequences 01..06")
        val xpj = File(out, "card/AR Song.xpj")
        assertTrue(xpj.isFile, "the project lands")

        // The reader accepts the project, and the sequences carry the
        // sections numbered in plan order - the flip order IS the song.
        val project = com.snipsnap.mpc3.Mpc3Project.read(xpj)
        assertTrue(project.isProject)
        val seqNames = (project.data!!["sequences"] as com.snipsnap.json.JsonValue.Arr).items.map { seq ->
            val value = (seq as com.snipsnap.json.JsonValue.Obj).entries["value"] as com.snipsnap.json.JsonValue.Obj
            (value.entries["name"] as com.snipsnap.json.JsonValue.Str).value
        }
        assertEquals(
            listOf("01 intro", "02 theme", "03 variation", "04 the turn", "05 reprise", "06 outro"),
            seqNames,
            "six sections, six sequences, in order",
        )
        // And the .xpj still imports as a kit.
        val fresh = File(temp, "ar-fresh")
        assertEquals(0, cli("import", xpj.path, "--out", fresh.path).first)
        assertTrue(fresh.listFiles { f: File -> File(f, "kit.json").isFile }!!.isNotEmpty())

        // Deterministic per seed: same seed, same project bytes.
        val out2 = File(temp, "ar-out2")
        assertEquals(0, cli("arrange", kitDir.path, "--out", out2.path).first)
        assertTrue(
            xpj.readBytes().contentEquals(File(out2, "card/AR Song.xpj").readBytes()),
            "same seed, same song",
        )

        // --mixdown renders the stitched song beside the project.
        val (mCode, mOut, mErr) = cli("arrange", kitDir.path, "--out", out.path, "--overwrite", "--mixdown")
        assertEquals(0, mCode, "stderr: $mErr")
        assertContains(mOut, "mixdown:")
        assertContains(mOut, "tape stop on the outro")
        assertTrue(File(out, "card/AR Song.wav").length() > 44, "the song WAV lands")

        val (noCode, _, noErr) = cli("arrange", File(out, "nowhere").path)
        assertEquals(2, noCode)
        assertContains(noErr, "kit.json")
    }

    @Test
    fun `a kit leaves as sfz and ds and comes home through import`() {
        val wav = writeBreak(File(temp, "es.wav"))
        val out = File(temp, "es-out")
        assertEquals(
            0,
            cli("chop", wav.path, "--out", out.path, "--name", "Escape", "--slices", "4", "--export", "sfz,ds").first,
        )
        val sfz = File(out, "card/Escape SFZ/Escape.sfz")
        assertTrue(sfz.isFile, "the sfz lands")
        assertTrue(File(out, "card/Escape SFZ/Samples").isDirectory)
        assertTrue(File(out, "card/Escape DecentSampler/Escape.dspreset").isFile, "the dspreset lands")

        val (code, stdout, stderr) = cli("import", sfz.path, "--out", File(temp, "es-home").path)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, ".sfz instrument")
        val home = KitStore.load(File(temp, "es-home/Escape"))
        assertEquals(KitStore.load(File(out, "Escape")).pads.size, home.pads.size, "every pad comes home")
        assertTrue(home.pads.all { it.source["importedFrom"] == "Escape.sfz" }, "provenance stamped")
    }

    @Test
    fun `learn bites a beat onto the kit's own pads`() {
        val wav = writeBreak(File(temp, "lr.wav"))
        val out = File(temp, "lr-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "LR", "--slices", "8").first)
        val kitDir = File(out, "LR")

        val (code, stdout, stderr) = cli("learn", wav.path, "--into", kitDir.path)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "learned:")
        assertContains(stdout, "kick")
        val learned = GrooveStore.load(kitDir).first()
        assertTrue(learned.name.endsWith("Learned"), learned.name)
        assertTrue(learned.notes.isNotEmpty())
        val kit = KitStore.load(kitDir)
        assertTrue(
            learned.notes.all { kit.pad(it.note - 35) != null },
            "every learned hit lands on a pad the kit has",
        )
        // The kick pad opens the beat, right where writeBreak put it.
        val kickSlot = kit.pads.first { it.drumClass == DrumClass.KICK }.slot
        assertEquals(35 + kickSlot, learned.notes.minByOrNull { it.timePulses }!!.note)

        // A held tone is not a beat - the ear refuses, named.
        val toneFile = File(temp, "lr-tone.wav").also { f ->
            val rate = 44_100
            WavWriter.write(
                f,
                Snip(FloatArray(4 * rate) { i -> (0.4 * Math.sin(2.0 * Math.PI * 220.0 * i / rate)).toFloat() }, 1, rate),
            )
        }
        val (tCode, _, tErr) = cli("learn", toneFile.path, "--into", kitDir.path)
        assertEquals(2, tCode)
        assertTrue("tempo" in tErr || "beat" in tErr, tErr)

        val (noCode, _, noErr) = cli("learn", wav.path)
        assertEquals(2, noCode)
        assertContains(noErr, "--into")
    }

    @Test
    fun `album releases the labeled crate across two sides`() {
        val wav = writeBreak(File(temp, "al.wav"))
        val root = File(temp, "al-root")
        assertEquals(0, cli("chop", wav.path, "--out", root.path, "--name", "AL One", "--slices", "4", "--groove").first)
        assertEquals(0, cli("chop", wav.path, "--out", root.path, "--name", "AL Two", "--slices", "4", "--groove").first)
        assertEquals(0, cli("chop", wav.path, "--out", root.path, "--name", "AL Dry", "--slices", "4").first)
        assertEquals(0, cli("label", root.path, "--init", "Side Hustle", "--prefix", "SH").first)

        val out = File(temp, "al-out")
        val (code, stdout, stderr) =
            cli("album", root.path, "--title", "First Tape", "--out", out.path, "--seed", "1")
        assertEquals(0, code, "stderr: $stderr")
        val dest = File(out, "First Tape")
        assertTrue(File(dest, "SIDE A.wav").length() > 44, "side A tape lands")
        assertTrue(File(dest, "SIDE B.wav").length() > 44, "side B tape lands")
        assertTrue(File(dest, "SIDE A").listFiles()!!.any { it.name.startsWith("01 ") }, "per-track WAVs land")
        assertTrue(File(dest, "cover.png").isFile, "the cover lands")

        val tracklist = File(dest, "tracklist.txt").readText()
        assertContains(tracklist, "a Side Hustle release")
        assertContains(tracklist, "SH-002", message = "catalog numbers ride the tracklist")
        assertContains(tracklist, "SIDE B")
        assertContains(tracklist, "left off: AL Dry")
        assertContains(stdout, "2 track(s) across 2 side(s)")

        // Deterministic: the same crate and seed release the same album.
        assertEquals(
            0,
            cli("album", root.path, "--title", "First Tape", "--out", out.path, "--seed", "1", "--overwrite").first,
        )
        assertEquals(tracklist, File(dest, "tracklist.txt").readText(), "same seed, same tape")

        val (noTitle, _, titleErr) = cli("album", root.path)
        assertEquals(2, noTitle)
        assertContains(titleErr, "--title")
    }

    @Test
    fun `beat runs the whole ritual - one song in, a release out`() {
        val song = writeSong(File(temp, "Track 11.wav"))
        val out = File(temp, "beat-out")
        val (code, stdout, stderr) = cli("beat", song.path, "--out", out.path)
        assertEquals(0, code, "stderr: $stderr\nstdout: $stdout")
        assertContains(stdout, "== the release ==")

        val kitDir = File(out, "Track 11 Break")
        assertTrue(File(kitDir, "kit.json").isFile, "the kit landed")
        assertTrue(File(out, "Track 11 Air/kit.json").isFile, "the air rode along")
        assertTrue(File(out, "card/Track 11 Break Song.xpj").isFile, "the song landed")
        assertTrue(File(out, "card/Track 11 Break Song.wav").length() > 44, "the mixdown landed")
        assertTrue(File(kitDir, "liner-notes.txt").isFile, "the notes landed")
        assertTrue(kitDir.listFiles()!!.any { it.name.endsWith("J-Card.png") }, "the card landed")
        assertTrue(KitStore.load(kitDir).pads.any { it.chain != null }, "chains landed (break pad / robins)")

        // The synthetic song has no key - the Answer sat out, and said so.
        assertContains(stdout, "sat out")

        // A keyless tone has no break at all: the ritual aborts honestly.
        val toneFile = File(temp, "beat-tone.wav").also { f ->
            val rate = 44_100
            WavWriter.write(
                f,
                Snip(FloatArray(6 * rate) { i -> (0.4 * Math.sin(2.0 * Math.PI * 220.0 * i / rate)).toFloat() }, 1, rate),
            )
        }
        val (tCode, tOut, _) = cli("beat", toneFile.path, "--out", out.path)
        assertEquals(1, tCode)
        assertContains(tOut, "no break stood out")
    }

    @Test
    fun `learn --pocket bottles a real pocket off the record`() {
        // 100 bpm, two bars: kicks straight on the quarters, hats on the
        // off-8ths pushed 76 pulses (~47ms) late - a rendered swing.
        val rate = 44_100
        val total = FloatArray(6 * rate)
        fun place(hit: Snip, atSec: Double, gain: Float) {
            val start = (atSec * rate).toInt()
            for (i in hit.samples.indices) {
                val idx = start + i
                if (idx < total.size) total[idx] += hit.samples[i] * gain
            }
        }
        val beat = 0.6
        val swingSec = 76.0 * 60 / (100.0 * 960)
        for (bar in 0 until 2) {
            for (q in 0 until 4) {
                val t = (bar * 4 + q) * beat
                place(DrumSynth.kick(), t, 0.9f)
                place(DrumSynth.closedHat(), t + beat / 2 + swingSec, 0.5f)
            }
        }
        val wav = File(temp, "swung.wav")
        WavWriter.write(wav, Snip(total, 1, rate))

        val pocketPath = File(temp, "swung-feel").path
        val (code, stdout, stderr) = cli("learn", wav.path, "--pocket", pocketPath)
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "pocket bottled")
        val pocket = com.snipsnap.kit.PocketStore.read(File("$pocketPath.pocket"))

        // The off-8th positions lean the rendered push; downbeats stay straight.
        val late = listOf(2, 6, 10, 14).mapNotNull { pocket.template.offsets[it] }
        assertTrue(late.isNotEmpty(), "the hats covered off-8th positions")
        assertTrue(late.all { it > 40 }, "the swing survives off the record: $late")
        val straight = listOf(0, 4, 8, 12).mapNotNull { pocket.template.offsets[it] }
        assertTrue(straight.all { Math.abs(it) < 32 }, "downbeats stay straight: $straight")

        val (noneCode, _, noneErr) = cli("learn", wav.path)
        assertEquals(2, noneCode)
        assertContains(noneErr, "--pocket")
    }

    @Test
    fun `mutate breeds one hit from many parents at the terminal`() {
        val wav = writeBreak(File(temp, "mu.wav"))
        val out = File(temp, "mu-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "MU", "--slices", "4").first)
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "MU2", "--slices", "4").first)
        val kitDir = File(out, "MU")
        val padFile = File(kitDir, KitStore.load(kitDir).pad(1)!!.sampleFile)
        val before = padFile.readBytes()

        // Stack with a same-kit pad and another kit's pad in one call.
        val (code, stdout, stderr) =
            cli("mutate", kitDir.path, "A01", "--with", "A02,${File(out, "MU2").path}:A02")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "one hit, 3 parents")
        assertTrue(!before.contentEquals(padFile.readBytes()), "the pad's audio changed")
        val mutated = KitStore.load(kitDir).pad(1)!!
        assertContains(mutated.source["mutatedWith"]!!, "MU2:A02")
        assertTrue(mutated.recipe!!.entries.containsKey("mutate"))

        // Lineage shows the extra parentage.
        val (_, lin, _) = cli("lineage", kitDir.path)
        assertContains(lin, "mutated with")

        assertEquals(0, cli("mutate", kitDir.path, "A01", "--undo").first)
        assertTrue(before.contentEquals(padFile.readBytes()), "undo is byte-identical")

        // Splice from a WAV parent, then the contradictions refuse.
        assertEquals(0, cli("mutate", kitDir.path, "A01", "--with", wav.path, "--splice", "--at", "30").first)
        val (bothCode, _, bothErr) = cli("mutate", kitDir.path, "A02", "--with", "A01", "--splice", "--split")
        assertEquals(2, bothCode)
        assertContains(bothErr, "pick one")
        val (noneCode, _, noneErr) = cli("mutate", kitDir.path, "A02")
        assertEquals(2, noneCode)
        assertContains(noneErr, "--with")

        // The roulette: the crate deals a parent, seeded, recipe stamped.
        val (rCode, rOut, rErr) = cli("mutate", kitDir.path, "A02", "--roulette", "--seed", "3", "--root", out.path)
        assertEquals(0, rCode, "stderr: $rErr")
        assertContains(rOut, "roulette: the crate dealt")
        val dealt = KitStore.load(kitDir).pad(2)!!
        assertTrue(
            (dealt.recipe!!.entries["mutate"] as com.snipsnap.json.JsonValue.Obj)
                .entries.containsKey("roulette"),
            "the spin is in the recipe",
        )
        val (clashCode, _, clashErr) = cli("mutate", kitDir.path, "A02", "--roulette", "--with", "A01")
        assertEquals(2, clashCode)
        assertContains(clashErr, "drop --with")
    }

    @Test
    fun `robin chains a pad from the terminal and undoes it byte-identical`() {
        val wav = writeBreak(File(temp, "rb.wav"))
        val out = File(temp, "rb-out")
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "RB", "--slices", "8").first)
        val kitDir = File(out, "RB")
        val padFile = File(kitDir, KitStore.load(kitDir).pad(1)!!.sampleFile)
        val before = padFile.readBytes()

        val (code, stdout, stderr) = cli("robin", kitDir.path, "A01", "--takes", "4", "--seed", "7")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "chain of 4 takes")
        val chained = KitStore.load(kitDir).pad(1)!!.chain
        assertEquals(4, chained!!.sliceCount)
        assertTrue(padFile.readBytes().size > before.size * 3, "four takes ride in the WAV")

        // A chained pad refuses the other audio doors with a pointer home.
        val (guardCode, _, guardErr) = cli("treat", kitDir.path, "A01", "crushed")
        assertTrue(guardCode != 0)
        assertContains(guardErr, "robin --undo")

        assertEquals(0, cli("robin", kitDir.path, "A01", "--undo").first)
        assertTrue(before.contentEquals(padFile.readBytes()), "undo is byte-identical")
        assertEquals(null, KitStore.load(kitDir).pad(1)!!.chain)

        val (badCode, _, badErr) = cli("robin", kitDir.path, "A01", "--takes", "1")
        assertTrue(badCode != 0)
        assertContains(badErr, "takes")

        // --zones renders the full velocity x robin grid from the same pad.
        val (gCode, gOut, gErr) = cli("robin", kitDir.path, "A01", "--zones", "3", "--takes", "2", "--seed", "5")
        assertEquals(0, gCode, "stderr: $gErr")
        assertContains(gOut, "3 zones x 2 takes")
        val grid = KitStore.load(kitDir).pad(1)!!.chain!!
        assertEquals(6, grid.sliceCount)
        assertEquals(3, grid.zones!!.size)
        assertEquals(0, cli("robin", kitDir.path, "A01", "--undo").first)
        assertTrue(before.contentEquals(padFile.readBytes()), "grid undo is byte-identical too")

        val (bzCode, _, bzErr) = cli("robin", kitDir.path, "A01", "--zones", "9")
        assertTrue(bzCode != 0)
        assertContains(bzErr, "zones")
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
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "PackA", "--slices", "4", "--groove").first)
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
        // A kit with a groove ships its pocket; one without honestly doesn't.
        assertTrue(File(dest, "[Pockets]/PackA.pocket").isFile, "the feel travels with the kit")
        assertTrue(!File(dest, "[Pockets]/PackB.pocket").exists(), "no groove, no pocket")

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
    fun `label runs an imprint from the terminal and the jcard wears the number`() {
        val wav = writeBreak(File(temp, "lb.wav"))
        val root = File(temp, "lb-root")
        assertEquals(0, cli("chop", wav.path, "--out", root.path, "--name", "LB Two", "--slices", "4").first)
        assertEquals(0, cli("chop", wav.path, "--out", root.path, "--name", "LB One", "--slices", "4").first)

        val (code, stdout, stderr) = cli("label", root.path, "--init", "Dusty Fingers")
        assertEquals(0, code, "stderr: $stderr")
        assertContains(stdout, "Dusty Fingers [DF]")
        assertContains(stdout, "DF-001  LB One")
        assertContains(stdout, "DF-002  LB Two")
        assertTrue(File(root, "catalog.txt").isFile, "the ledger lands")

        // Re-running moves nothing; a new kit appends.
        assertEquals(0, cli("chop", wav.path, "--out", root.path, "--name", "LB Also", "--slices", "4").first)
        val (_, again, _) = cli("label", root.path)
        assertContains(again, "DF-001  LB One")
        assertContains(again, "DF-003  LB Also")

        // The J-card under the labeled root wears its number.
        val (jCode, jOut, _) = cli("jcard", File(root, "LB One").path, "--out", File(temp, "lb-cards").path)
        assertEquals(0, jCode)
        assertContains(jOut, "(DF-001)")

        val (noCode, _, noErr) = cli("label", File(temp, "lb-unlabeled").apply { mkdirs() }.path)
        assertEquals(2, noCode)
        assertContains(noErr, "--init")
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
        // Every file-taking verb the CLI has grown belongs in this list -
        // a verb that never met the junk corpus is a verb on trust.
        val invocations: List<Pair<String, (File) -> Triple<Int, String, String>>> = listOf(
            "chop" to { f -> cli("chop", f.path, "--out", out.path, "--overwrite") },
            "classify" to { f -> cli("classify", f.path) },
            "import" to { f -> cli("import", f.path, "--out", out.path, "--overwrite") },
            "keys" to { f -> cli("keys", f.path, "--out", out.path) },
            "diff" to { f -> cli("diff", f.path, f.path) },
            "clean" to { f -> cli("clean", f.path, "--declip", "--deverb", "--denoise") },
            "split" to { f -> cli("split", f.path, "--overwrite") },
            "dissect" to { f -> cli("dissect", f.path, "--out", out.path, "--overwrite") },
            "sculpt" to { f -> cli("sculpt", f.path, "--out", out.path, "--seconds", "1", "--overwrite") },
            "stretch" to { f -> cli("stretch", f.path, "--by", "2", "--overwrite") },
            "stretch --clear" to { f -> cli("stretch", f.path, "--clear", "--by", "2", "--overwrite") },
            "stretch --freeze" to { f -> cli("stretch", f.path, "--freeze", "--seconds", "1", "--overwrite") },
            "retime" to { f -> cli("retime", f.path, "--to", "100", "--from", "90", "--overwrite") },
            "dig" to { f -> cli("dig", f.path, "--chop", "--unearth", "--out", out.path, "--overwrite") },
            "checkup" to { f -> cli("checkup", f.parentFile.path) },
        )
        for (h in hostiles) {
            for ((name, invoke) in invocations) {
                val (code, _, _) = invoke(h)
                assertTrue(code in 0..2, "hostile ${h.name} x $name: exit $code out of range")
            }
        }
    }

    @Test
    fun `every kit-door verb survives a corpus of broken kits`() {
        // Three ways a kit folder goes wrong: garbage where kit.json
        // should be, a kit.json whose WAVs are missing, and a real kit
        // whose pad WAV was replaced with junk bytes.
        val rnd = kotlin.random.Random(43)
        fun junkKit(name: String): File = File(temp, name).apply {
            mkdirs()
            File(this, "kit.json").writeBytes(ByteArray(300) { rnd.nextInt(256).toByte() })
        }
        val garbage = junkKit("kit-garbage")
        val orphaned = File(temp, "kit-orphaned").apply {
            mkdirs()
            File(this, "kit.json").writeText(
                """{"version":1,"name":"Orphaned","pads":[{"slot":1,"sampleFile":"gone.wav","displayName":"Gone"}]}""",
            )
        }
        val wounded = run {
            val wav = writeBreak(File(temp, "wounded-src.wav"))
            val out = File(temp, "kit-wounded-out")
            assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Wounded").first)
            val kitDir = File(out, "Wounded")
            val pad = KitStore.load(kitDir).pads.first()
            File(kitDir, pad.sampleFile).writeBytes(ByteArray(200) { rnd.nextInt(256).toByte() })
            kitDir
        }
        val brokenKits = listOf(garbage, orphaned, wounded)

        val verbs: List<Pair<String, (File) -> Triple<Int, String, String>>> = listOf(
            "clean" to { k -> cli("clean", k.path, "--declip", "--deverb") },
            "clean --undo" to { k -> cli("clean", k.path, "--undo") },
            "doctor" to { k -> cli("doctor", k.path) },
            "robin" to { k -> cli("robin", k.path, "A01") },
            "mutate" to { k -> cli("mutate", k.path, "A01", "--with", "A02") },
            "euclid" to { k -> cli("euclid", k.path) },
            "sculpt" to { k -> cli("sculpt", k.path, "A01", "--out", File(temp, "bk-out").path, "--seconds", "1", "--overwrite") },
            "dissect" to { k -> cli("dissect", k.path, "A01", "--out", File(temp, "bk-out").path, "--overwrite") },
            "arrange" to { k -> cli("arrange", k.path) },
            "export" to { k -> cli("export", k.path, "--export", "folder", "--out", File(temp, "bk-exp").path) },
            "lineage" to { k -> cli("lineage", k.path) },
            "treat" to { k -> cli("treat", k.path, "A01", "warm") },
        )
        for (kit in brokenKits) {
            for ((name, invoke) in verbs) {
                val (code, _, _) = invoke(kit)
                assertTrue(code in 0..2, "broken kit ${kit.name} x $name: exit $code out of range")
            }
        }
    }

    /** How loudly [hz] rings across the whole array — a plain DFT probe. */
    private fun tone(samples: FloatArray, hz: Double, rate: Int): Double {
        var c = 0.0
        var s = 0.0
        for (i in samples.indices) {
            val w = 2.0 * Math.PI * hz * i / rate
            c += samples[i] * Math.cos(w)
            s += samples[i] * Math.sin(w)
        }
        return Math.hypot(c, s) / samples.size
    }

    /** The break under a 50 Hz hum; optionally two clicks planted in the tail. */
    private fun writeDirtyBreak(file: File, clicks: Boolean): File {
        val rate = 44_100
        writeBreak(file)
        val base = com.snipsnap.audio.WavReader.read(file)
        val dirty = FloatArray(base.samples.size) { i ->
            base.samples[i] + 0.05f * Math.sin(2.0 * Math.PI * 50.0 * i / rate).toFloat()
        }
        if (clicks) {
            dirty[dirty.size - rate / 8] = 0.9f
            dirty[dirty.size - rate / 16] = -0.9f
        }
        WavWriter.write(file, Snip(dirty, 1, rate))
        return file
    }

    @Test
    fun `clean scrubs a dirty WAV into a twin and leaves a clean one alone`() {
        val rate = 44_100
        val dirty = writeDirtyBreak(File(temp, "dirty take.wav"), clicks = true)
        val twin = File(temp, "dirty take Clean.wav")

        // --dry names the findings but writes nothing.
        val (dryCode, dryOut, _) = cli("clean", dirty.path, "--dry")
        assertEquals(0, dryCode)
        assertContains(dryOut, "hum notched")
        assertTrue(!twin.exists(), "--dry writes nothing")

        val (code, stdout, _) = cli("clean", dirty.path)
        assertEquals(0, code, stdout)
        assertContains(stdout, "hum notched")
        assertContains(stdout, "click(s) repaired")
        assertTrue(twin.isFile, "the cleaned twin lands beside the original")
        val before = tone(com.snipsnap.audio.WavReader.read(dirty).samples, 50.0, rate)
        val after = tone(com.snipsnap.audio.WavReader.read(twin).samples, 50.0, rate)
        assertTrue(after < before * 0.2, "the twin really lost the hum: $before -> $after")

        // A clean capture is told so, and no twin appears.
        val fine = writeBreak(File(temp, "fine take.wav"))
        val (fineCode, fineOut, _) = cli("clean", fine.path)
        assertEquals(0, fineCode)
        assertContains(fineOut, "clean - nothing done")
        assertTrue(!File(temp, "fine take Clean.wav").exists(), "nothing to write for a clean take")
    }

    @Test
    fun `clean treats a kit's dirty pad, stamps the recipe, and --undo restores every byte`() {
        val rate = 44_100
        val out = File(temp, "cleankit")
        val wav = writeBreak(File(temp, "cksrc.wav"))
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Grimy").first)
        val kitDir = File(out, "Grimy")
        val kit = KitStore.load(kitDir)

        // Dirty one pad by hand: its own hit under two seconds of 50 Hz hum.
        val victim = kit.pads.minBy { it.slot }
        val padFile = File(kitDir, victim.sampleFile)
        val hit = com.snipsnap.audio.WavReader.read(padFile)
        val dirty = FloatArray(2 * rate) { i ->
            (if (i < hit.samples.size) hit.samples[i] else 0f) +
                0.05f * Math.sin(2.0 * Math.PI * 50.0 * i / rate).toFloat()
        }
        WavWriter.write(padFile, Snip(dirty, 1, rate))
        val bytesBefore = kit.pads.associate { it.sampleFile to File(kitDir, it.sampleFile).readBytes() }

        // --dry diagnoses without touching a byte.
        val (dryCode, dryOut, _) = cli("clean", kitDir.path, "--dry")
        assertEquals(0, dryCode)
        assertContains(dryOut, "hum notched")
        assertContains(dryOut, "--dry: nothing written")
        assertTrue(padFile.readBytes().contentEquals(bytesBefore[victim.sampleFile]!!), "--dry leaves the audio alone")

        val (code, stdout, _) = cli("clean", kitDir.path)
        assertEquals(0, code, stdout)
        assertContains(stdout, "pad(s) treated")
        assertContains(stdout, "originals in the bin")
        val treated = KitStore.load(kitDir).pads.first { it.slot == victim.slot }
        assertTrue(treated.recipe?.entries?.containsKey("clean") == true, "the clean recipe rides the pad")
        assertTrue(!padFile.readBytes().contentEquals(bytesBefore[victim.sampleFile]!!), "the pad's audio was rewritten")

        // --undo pulls every treated pad back out of the bin, byte-identical.
        val (undoCode, undoOut, _) = cli("clean", kitDir.path, "--undo")
        assertEquals(0, undoCode, undoOut)
        assertContains(undoOut, "restored")
        for ((f, bytes) in bytesBefore) {
            assertTrue(File(kitDir, f).readBytes().contentEquals(bytes), "$f back byte-identical")
        }
        assertTrue(
            KitStore.load(kitDir).pads.first { it.slot == victim.slot }.recipe == null,
            "the recipe is gone with the treatment",
        )
        // Nothing left to undo now.
        assertEquals(2, cli("clean", kitDir.path, "--undo").first)
    }

    @Test
    fun `chop --clean scrubs the capture before the first slice`() {
        val rate = 44_100
        val hummy = writeDirtyBreak(File(temp, "hummy.wav"), clicks = false)
        val out = File(temp, "chopclean")
        assertEquals(0, cli("chop", hummy.path, "--out", out.path, "--name", "Raw").first)
        val (code, stdout, _) = cli("chop", hummy.path, "--out", out.path, "--name", "Scrubbed", "--clean")
        assertEquals(0, code, stdout)
        assertContains(stdout, "clean:")

        fun humAcross(kitDir: File): Double =
            KitStore.load(kitDir).pads.sumOf {
                tone(com.snipsnap.audio.WavReader.read(File(kitDir, it.sampleFile)).samples, 50.0, rate)
            }
        val raw = humAcross(File(out, "Raw"))
        val scrubbed = humAcross(File(out, "Scrubbed"))
        assertTrue(scrubbed < raw * 0.3, "the scrubbed kit's pads lost the hum: $raw -> $scrubbed")
    }

    @Test
    fun `clean --denoise pulls hiss from under a loud burst where the expander can't`() {
        val rate = 44_100
        // Hiss throughout, a loud 6 kHz burst in the middle: the burst
        // keeps the frame loud, so a level gate stands wide open there.
        val rnd = java.util.Random(21)
        fun writeBurst(file: File): File {
            val rnd2 = java.util.Random(rnd.nextLong())
            val s = FloatArray(3 * rate) { i ->
                val hiss = (rnd2.nextFloat() * 2f - 1f) * 0.01f
                val on = i >= rate / 2 && i < 5 * rate / 2
                hiss + if (on) (0.4 * Math.sin(2.0 * Math.PI * 6000.0 * i / rate)).toFloat() else 0f
            }
            WavWriter.write(file, Snip(s, 1, rate))
            return file
        }
        val a = writeBurst(File(temp, "burst a.wav"))
        val b = writeBurst(File(temp, "burst b.wav"))

        val (codeA, outA, _) = cli("clean", a.path)
        assertEquals(0, codeA, outA)
        assertContains(outA, "gently gated")
        val (codeB, outB, _) = cli("clean", b.path, "--denoise")
        assertEquals(0, codeB, outB)
        assertContains(outB, "spectrally de-noised")

        fun mid(file: File): Double {
            val s = com.snipsnap.audio.WavReader.read(file).samples.copyOfRange(rate, 2 * rate)
            return listOf(500.0, 800.0, 1300.0).sumOf { tone(s, it, rate) }
        }
        val expanded = mid(File(temp, "burst a Clean.wav"))
        val denoised = mid(File(temp, "burst b Clean.wav"))
        val rawMid = mid(a)
        assertTrue(expanded > rawMid * 0.8, "the expander can't touch hiss under the burst: $rawMid -> $expanded")
        assertTrue(denoised < expanded * 0.5, "the deep clean can: $expanded -> $denoised")
    }

    @Test
    fun `clean --deroom fades the roomy pad's tail and names the loop it leaves alone`() {
        val rate = 44_100
        val out = File(temp, "deroomkit")
        val wav = writeBreak(File(temp, "drsrc.wav"))
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Roomy").first)
        val kitDir = File(out, "Roomy")
        val model = com.snipsnap.shell.KitBuilderModel.open(kitDir)
        val pads = model.kit.pads.sortedBy { it.slot }
        val victim = pads[0]
        val loopPad = pads[1]
        model.update(loopPad.slot) { it.copy(drumClass = DrumClass.LOOP) }
        model.save()

        // The victim becomes a roomy one-shot: a tight hit over a
        // shallower decaying noise tail - the knee's textbook patient.
        val rnd = java.util.Random(6)
        val roomy = FloatArray((12 * rate) / 10) { i ->
            val t = i.toDouble() / rate
            (0.8 * Math.sin(2.0 * Math.PI * 180.0 * t) * Math.exp(-40.0 * t)).toFloat() +
                ((rnd.nextFloat() * 2f - 1f) * 0.06 * Math.exp(-7.0 * t)).toFloat()
        }
        val victimFile = File(kitDir, victim.sampleFile)
        WavWriter.write(victimFile, Snip(roomy, 1, rate))
        val bytesBefore = KitStore.load(kitDir).pads.associate { it.sampleFile to File(kitDir, it.sampleFile).readBytes() }

        val (code, stdout, _) = cli("clean", kitDir.path, "--deroom")
        assertEquals(0, code, stdout)
        assertContains(stdout, "room tail faded from")
        assertContains(stdout, "LOOP - the knee leaves it alone")

        val treated = KitStore.load(kitDir).pads.first { it.slot == victim.slot }
        val cleanRecipe = treated.recipe?.entries?.get("clean")
        assertTrue(
            (cleanRecipe as? com.snipsnap.json.JsonValue.Obj)?.entries?.containsKey("deroomKneeMs") == true,
            "the knee rides the recipe",
        )
        fun tailRms(s: FloatArray): Double {
            var acc = 0.0
            var n = 0
            for (i in (0.4f * rate).toInt() until minOf((0.8f * rate).toInt(), s.size)) {
                acc += s[i] * s[i].toDouble()
                n++
            }
            return Math.sqrt(acc / n.coerceAtLeast(1))
        }
        val faded = com.snipsnap.audio.WavReader.read(victimFile).samples
        assertTrue(
            20 * Math.log10(tailRms(faded) / tailRms(roomy)) < -6,
            "the room recedes on disk too",
        )

        val (undoCode, undoOut, _) = cli("clean", kitDir.path, "--undo")
        assertEquals(0, undoCode, undoOut)
        for ((f, bytes) in bytesBefore) {
            assertTrue(File(kitDir, f).readBytes().contentEquals(bytes), "$f back byte-identical")
        }
    }

    @Test
    fun `chop forwards the deep clean and refuses --denoise on its own`() {
        val rate = 44_100
        assertEquals(2, cli("chop", "x.wav", "--denoise").first, "--denoise rides on --clean")

        val rnd = java.util.Random(31)
        val base = com.snipsnap.audio.WavReader.read(writeBreak(File(temp, "hissy tmp.wav"))).samples
        val hissy = FloatArray(base.size) { i -> base[i] + (rnd.nextFloat() * 2f - 1f) * 0.01f }
        val src = File(temp, "hissy break.wav")
        WavWriter.write(src, Snip(hissy, 1, rate))
        val out = File(temp, "denoisechop")
        val (code, stdout, _) = cli("chop", src.path, "--out", out.path, "--name", "Deep", "--clean", "--denoise")
        assertEquals(0, code, stdout)
        assertContains(stdout, "spectrally de-noised")
    }

    @Test
    fun `sculpt grows texture kits - modes proven by probe, seeds regenerable`() {
        val rate = 44_100
        // First half sings 220 Hz, second half 2 kHz - position is audible.
        val src = File(temp, "sculpt src.wav")
        WavWriter.write(
            src,
            Snip(
                FloatArray(rate) { i ->
                    val hz = if (i < rate / 2) 220.0 else 2000.0
                    (0.5 * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat()
                },
                1, rate,
            ),
        )
        val out = File(temp, "sculptout")
        fun probe(s: FloatArray, from: Int, to: Int, hz: Float): Float =
            tone(s.copyOfRange(from, to), hz.toDouble(), rate).toFloat()
        fun padMono(kitDir: File, slot: Int): FloatArray {
            val kit = KitStore.load(kitDir)
            val pad = kit.pads.first { it.slot == slot }
            val snip = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
            return FloatArray(snip.frameCount) { f ->
                (0 until snip.channels).sumOf { ch -> snip.samples[f * snip.channels + ch].toDouble() }
                    .toFloat() / snip.channels
            }
        }

        // The default cloud hovers post-attack (position 0.35): the low half.
        val (code, stdout, _) = cli("sculpt", src.path, "--out", out.path, "--seconds", "2", "--seed", "11")
        assertEquals(0, code, stdout)
        val cloudDir = File(out, "sculpt src Sculpt")
        val cloudKit = KitStore.load(cloudDir)
        assertEquals(4, cloudKit.pads.size, "four seeded takes")
        for (pad in cloudKit.pads) {
            assertEquals(DrumClass.LOOP, pad.drumClass, "every pad a LOOP by declaration")
            assertEquals("sculpt src.wav", pad.source["sculptedFrom"], "provenance stamped")
            val recipe = pad.recipe?.entries?.get("sculpt") as? com.snipsnap.json.JsonValue.Obj
            assertTrue(recipe != null && recipe.entries.containsKey("seed"), "the recipe is regenerable")
        }
        val cloud = padMono(cloudDir, 1)
        assertTrue(
            probe(cloud, 0, cloud.size, 220f) > 5 * probe(cloud, 0, cloud.size, 2000f),
            "the cloud hovers where it was pointed",
        )

        // A scrub crawls the source: opens low, closes high.
        val scrubRun = cli("sculpt", src.path, "--out", out.path, "--name", "Scrubbed", "--mode", "scrub", "--seconds", "4", "--seed", "11")
        assertEquals(0, scrubRun.first, scrubRun.third)
        val scrub = padMono(File(out, "Scrubbed"), 1)
        val quarter = scrub.size / 4
        assertTrue(
            probe(scrub, 0, quarter, 220f) > 5 * probe(scrub, 0, quarter, 2000f),
            "the scrub opens where the source opens",
        )
        assertTrue(
            probe(scrub, scrub.size - quarter, scrub.size, 2000f) > 5 * probe(scrub, scrub.size - quarter, scrub.size, 220f),
            "and closes where it closes",
        )

        // Same seed, same texture - the kit rebuilds byte-identical.
        val firstBytes = File(cloudDir, cloudKit.pads.first { it.slot == 1 }.sampleFile).readBytes()
        val rebuild = cli("sculpt", src.path, "--out", out.path, "--seconds", "2", "--seed", "11", "--overwrite")
        assertEquals(0, rebuild.first, rebuild.third)
        val rebuilt = KitStore.load(cloudDir)
        assertTrue(
            File(cloudDir, rebuilt.pads.first { it.slot == 1 }.sampleFile).readBytes().contentEquals(firstBytes),
            "the texture is a recipe: same seed, same bytes",
        )

        // A swarm is measurably wider than a cloud of the same tone.
        val tone = File(temp, "sculpt tone.wav")
        WavWriter.write(
            tone,
            Snip(FloatArray(rate) { i -> (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / rate)).toFloat() }, 1, rate),
        )
        val tc = cli("sculpt", tone.path, "--out", out.path, "--name", "ToneCloud", "--seconds", "2", "--seed", "5")
        assertEquals(0, tc.first, tc.third)
        val ts = cli("sculpt", tone.path, "--out", out.path, "--name", "ToneSwarm", "--mode", "swarm", "--seconds", "2", "--seed", "5")
        assertEquals(0, ts.first, ts.third)
        fun offOverIn(kit: String): Float {
            val s = padMono(File(out, kit), 1)
            return (probe(s, 0, s.size, 840f) + probe(s, 0, s.size, 1190f)) / probe(s, 0, s.size, 1000f)
        }
        assertTrue(offOverIn("ToneSwarm") > 3 * offOverIn("ToneCloud"), "the swarm spreads")

        // And the texture kit is a real kit: it exports.
        val exp = cli("export", cloudDir.path, "--export", "folder", "--out", File(temp, "sculptexp").path)
        assertEquals(0, exp.first, exp.third)
    }

    @Test
    fun `stretch writes the slow-motion twin and freeze holds an instant`() {
        val rate = 44_100
        val src = File(temp, "stretch src.wav")
        WavWriter.write(
            src,
            Snip(FloatArray(rate / 2) { i -> (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / rate)).toFloat() }, 1, rate),
        )
        val (code, stdout, _) = cli("stretch", src.path, "--by", "8", "--seed", "3")
        assertEquals(0, code, stdout)
        assertContains(stdout, "stretched x8")
        val twin = com.snipsnap.audio.WavReader.read(File(temp, "stretch src Stretched.wav"))
        assertEquals(4 * rate, twin.frameCount, "eight times half a second")
        val m = FloatArray(twin.frameCount) { f -> (twin.samples[f * 2] + twin.samples[f * 2 + 1]) / 2f }
        assertTrue(
            tone(m, 440.0, rate) > 5 * tone(m, 330.0, rate),
            "the wash stays in tune",
        )

        val (fCode, fOut, _) = cli("stretch", src.path, "--freeze", "--seconds", "2", "--seed", "3")
        assertEquals(0, fCode, fOut)
        assertContains(fOut, "frozen at")
        assertEquals(2 * rate, com.snipsnap.audio.WavReader.read(File(temp, "stretch src Frozen.wav")).frameCount)

        // --clear: PGHI phases keep the tone a narrow line.
        val (cCode, cOut, _) = cli("stretch", src.path, "--clear", "--by", "4", "--seed", "3", "--overwrite")
        assertEquals(0, cCode, cOut)
        assertContains(cOut, "(clear)")
        val clear = com.snipsnap.audio.WavReader.read(File(temp, "stretch src Stretched.wav"))
        val cm = FloatArray(clear.frameCount) { f ->
            (0 until clear.channels).sumOf { ch -> clear.samples[f * clear.channels + ch].toDouble() }.toFloat() / clear.channels
        }
        assertTrue(
            tone(cm, 440.0, rate) > 20 * (tone(cm, 415.3, rate) + tone(cm, 466.16, rate)),
            "the clear stretch is a narrow line",
        )
        assertEquals(2, cli("stretch", src.path, "--clear", "--freeze").first, "--clear and --freeze contradict")

        assertEquals(2, cli("stretch", src.path, "--by", "8", "--freeze").first, "--by and --freeze contradict")
        assertEquals(2, cli("stretch", src.path, "--at", "1").first, "--at rides on --freeze")
        assertEquals(2, cli("stretch", src.path, "--by", "1").first, "factor out of range")
    }

    @Test
    fun `euclid lands the textbook pulses on the kit's own pads`() {
        val out = File(temp, "euclidkit")
        val wav = writeBreak(File(temp, "eusrc.wav"))
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Pulse").first)
        val kitDir = File(out, "Pulse")
        val kit = KitStore.load(kitDir)
        val kickNote = 35 + kit.pads.first { it.drumClass == DrumClass.KICK }.slot
        val snareNote = 35 + kit.pads.first { it.drumClass == DrumClass.SNARE }.slot

        val (code, stdout, _) = cli("euclid", kitDir.path)
        assertEquals(0, code, stdout)
        assertContains(stdout, "kick E(3,8): x..x..x.")
        assertContains(stdout, "snare E(2,8)+2: ..x...x.")

        val clips = GrooveStore.load(kitDir)
        assertTrue(clips.size >= 4, "the standard variations ride along: ${clips.size}")
        val base = clips.first { "Euclid" in it.name }
        val bar = 3840L
        val kicks = base.notes.filter { it.note == kickNote }.map { it.timePulses }
        assertEquals(listOf(0L, bar * 3 / 8, bar * 6 / 8), kicks, "the tresillo, onset first")
        val snares = base.notes.filter { it.note == snareNote }.map { it.timePulses }
        assertEquals(listOf(bar / 4, bar * 3 / 4), snares, "the backbeat on 2 and 4")

        // Accents are structural: downbeat leads, quarters anchor, rest speak.
        assertEquals(0.95f, base.notes.first { it.timePulses == 0L && it.note == kickNote }.velocity)
        assertEquals(0.85f, base.notes.first { it.timePulses == bar / 4 }.velocity)
        assertEquals(0.7f, base.notes.first { it.timePulses == bar * 3 / 8 }.velocity)

        // The cinquillo, and rotation moving the tresillo.
        val (c2, out2, _) = cli("euclid", kitDir.path, "--kick", "5,8")
        assertEquals(0, c2, out2)
        assertContains(out2, "E(5,8): x.xx.xx.")
        val (c3, out3, _) = cli("euclid", kitDir.path, "--kick", "3,8,1")
        assertEquals(0, c3, out3)
        assertContains(out3, "E(3,8)+1: .x..x..x")

        // The groove rides the native export like any captured one.
        val exp = cli("export", kitDir.path, "--export", "xtd", "--out", File(temp, "euclidexp").path)
        assertEquals(0, exp.first, exp.third)

        // A kit of textures has no drums to play it: named, not invented.
        WavWriter.write(
            File(temp, "eutone.wav"),
            Snip(FloatArray(44_100) { i -> (0.4 * Math.sin(2.0 * Math.PI * 500.0 * i / 44_100)).toFloat() }, 1, 44_100),
        )
        assertEquals(0, cli("sculpt", File(temp, "eutone.wav").path, "--out", out.path, "--name", "NoDrums", "--seconds", "2").first)
        val (noCode, _, noErr) = cli("euclid", File(out, "NoDrums").path)
        assertEquals(2, noCode)
        assertContains(noErr, "no pad plays it")
    }

    @Test
    fun `dissect lays a sound's anatomy on three pads`() {
        val rate = 44_100
        // A synthetic "kick": held 150 Hz body + broadband attack burst + hiss air.
        val rnd = java.util.Random(9)
        val burst = java.util.Random(10)
        val hit = FloatArray(rate) { i ->
            val t = i.toDouble() / rate
            (0.5 * Math.sin(2.0 * Math.PI * 150.0 * t)).toFloat() +
                (rnd.nextFloat() * 2f - 1f) * 0.04f +
                (if (i < 120) (burst.nextFloat() * 2f - 1f) * 0.8f else 0f)
        }
        val src = File(temp, "anatomy.wav")
        WavWriter.write(src, Snip(hit, 1, rate))
        val out = File(temp, "dissectout")
        val (code, stdout, _) = cli("dissect", src.path, "--out", out.path)
        assertEquals(0, code, stdout)
        assertContains(stdout, "Sines")
        assertContains(stdout, "sum back to the whole")

        val kitDir = File(out, "anatomy Dissected")
        val kit = KitStore.load(kitDir)
        assertEquals(3, kit.pads.size)
        val byName = kit.pads.associateBy { it.displayName }
        assertEquals(DrumClass.TONAL, byName.getValue("Sines").drumClass)
        assertEquals(DrumClass.PERC, byName.getValue("Transient").drumClass)
        assertEquals(DrumClass.LOOP, byName.getValue("Air").drumClass)
        for (pad in kit.pads) {
            assertEquals("anatomy.wav", pad.source["dissectedFrom"], "provenance stamped")
            assertTrue(pad.recipe?.entries?.containsKey("dissect") == true, "recipe stamped")
        }

        // The body sings in Sines; the attack's energy fronts the Transient pad.
        fun mono(pad: String): FloatArray {
            val s = com.snipsnap.audio.WavReader.read(File(kitDir, byName.getValue(pad).sampleFile))
            return FloatArray(s.frameCount) { f ->
                (0 until s.channels).sumOf { ch -> s.samples[f * s.channels + ch].toDouble() }.toFloat() / s.channels
            }
        }
        val sines = mono("Sines")
        val transient = mono("Transient")
        assertTrue(
            tone(sines, 150.0, rate) > 5 * tone(transient, 150.0, rate),
            "the body is sines",
        )
        fun headShare(s: FloatArray): Double {
            var head = 0.0
            var total = 1e-12
            for (i in s.indices) {
                val e = s[i] * s[i].toDouble()
                if (i < rate / 100) head += e
                total += e
            }
            return head / total
        }
        assertTrue(headShare(transient) > 0.5, "the attack fronts the transient pad: ${headShare(transient)}")
    }

    @Test
    fun `split hands back a song's drums and music - and the halves sum to the song`() {
        val rate = 44_100
        fun snappy(len: Int): FloatArray {
            val s = FloatArray(len)
            for ((at, hit) in listOf(
                0.2f to DrumSynth.closedHat(), 0.8f to DrumSynth.snare(),
                1.4f to DrumSynth.closedHat(), 2.0f to DrumSynth.clap(), 2.6f to DrumSynth.closedHat(),
            )) {
                val start = (at * rate).toInt()
                for (i in hit.samples.indices) {
                    val idx = start + i
                    // Tame: the hat's first-difference noise can peak well
                    // past its nominal level, and full scale clips on disk.
                    if (idx < len) s[idx] += hit.samples[i] * 0.4f
                }
            }
            return s
        }
        val n = 3 * rate
        val mixWav = File(temp, "mixsong.wav")
        val drums = snappy(n)
        val mix = FloatArray(n) { i ->
            var c = 0.0
            for (hz in doubleArrayOf(220.0, 329.63)) c += Math.sin(2.0 * Math.PI * hz * i / rate)
            drums[i] + (0.12 * c).toFloat()
        }
        WavWriter.write(mixWav, Snip(mix, 1, rate))

        val (code, stdout, _) = cli("split", mixWav.path)
        assertEquals(0, code, stdout)
        assertContains(stdout, "drums ")
        val dOut = com.snipsnap.audio.WavReader.read(File(temp, "mixsong Drums.wav"))
        val mOut = com.snipsnap.audio.WavReader.read(File(temp, "mixsong Music.wav"))

        // The chord went to Music, and the halves rebuild the song.
        assertTrue(tone(mOut.samples, 220.0, rate) > 5 * tone(dOut.samples, 220.0, rate), "the chord is Music's")
        var worst = 0f
        for (i in mix.indices) {
            val d = Math.abs(dOut.samples[i] + mOut.samples[i] - mix[i])
            if (d > worst) worst = d
        }
        assertTrue(worst < 2e-4f, "the halves sum back to the song: $worst")

        // Drums alone are called what they are.
        val drumsWav = File(temp, "onlydrums.wav")
        WavWriter.write(drumsWav, Snip(snappy(n), 1, rate))
        val (dCode, dStdout, _) = cli("split", drumsWav.path)
        assertEquals(0, dCode, dStdout)
        assertContains(dStdout, "mostly drums")
    }

    @Test
    fun `dig --unearth pulls the break out from under the song`() {
        val rate = 44_100
        // The pads never stop: a loud chord runs the WHOLE song, and the
        // drums play under it in the middle - no clean stretch exists.
        val n = 20 * rate
        val buried = FloatArray(n) { i ->
            var c = 0.0
            for (hz in doubleArrayOf(220.0, 277.18, 329.63)) c += Math.sin(2.0 * Math.PI * hz * i / rate)
            (0.18 * c).toFloat()
        }
        val beatLen = (60f / 100f * rate).toInt()
        var t = 6 * rate
        var count = 0
        while (t < 14 * rate) {
            for ((offset, hit, gain) in listOf(
                Triple(0, DrumSynth.kick(), 0.9f),
                Triple(if (count % 2 == 1) 0 else -1, DrumSynth.snare(), 0.8f),
                Triple(0, DrumSynth.closedHat(), 0.5f),
                Triple(beatLen / 2, DrumSynth.closedHat(), 0.4f),
            )) {
                if (offset < 0) continue
                for (i in hit.samples.indices) {
                    val idx = t + offset + i
                    if (idx < n) buried[idx] += hit.samples[i] * gain
                }
            }
            t += beatLen
            count++
        }
        val song = File(temp, "buried.wav")
        WavWriter.write(song, Snip(buried, 1, rate))

        fun kitBleed(kitDir: File): Double {
            val kit = KitStore.load(kitDir)
            return kit.pads.sumOf { pad ->
                val s = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
                val m = FloatArray(s.frameCount) { f ->
                    (0 until s.channels).sumOf { ch -> s.samples[f * s.channels + ch].toDouble() }.toFloat() / s.channels
                }
                listOf(220.0, 277.18, 329.63).sumOf { tone(m, it, rate) }
            } / kit.pads.size
        }

        // The flagship claim, in its strongest form: the plain dig can't
        // hear the buried break AT ALL - the chord never stops, so no
        // stretch of the mix reads as drums...
        val outA = File(temp, "digplain")
        val plain = cli("dig", song.path, "--chop", "--out", outA.path)
        assertEquals(0, plain.first, plain.third)
        assertContains(plain.second, "no break heard")

        // ...while --unearth digs it out from under the song.
        val outB = File(temp, "digunearth")
        val unearth = cli("dig", song.path, "--chop", "--unearth", "--out", outB.path)
        assertEquals(0, unearth.first, unearth.third)
        val unearthKit = File(outB, "buried Break")
        assertTrue(unearthKit.isDirectory, "unearth finds the buried break: ${unearth.second}")

        // And the pads it hands back really are drums: against a plain
        // chop of the same section of the raw mix, the chord bleed drops.
        val sectionWav = File(temp, "buried section.wav")
        WavWriter.write(sectionWav, Snip(buried.copyOfRange(6 * rate, 14 * rate), 1, rate))
        assertEquals(0, cli("chop", sectionWav.path, "--out", outA.path, "--name", "Muddy").first)
        val bleedA = kitBleed(File(outA, "Muddy"))
        val bleedB = kitBleed(unearthKit)
        assertTrue(
            bleedB < bleedA * 0.32,
            "the chord bleed drops >= 10 dB when the break is unearthed: $bleedA -> $bleedB",
        )
        assertTrue(
            KitStore.load(unearthKit).pads.all { it.source["unearthed"] == "true" },
            "provenance says unearthed",
        )
    }

    @Test
    fun `retime changes the tempo and not the pitch`() {
        val rate = 44_100
        // A 100 BPM break with a 440 Hz bed under it.
        val base = com.snipsnap.audio.WavReader.read(writeBreak(File(temp, "rt base.wav"))).samples
        val bedded = FloatArray(base.size) { i ->
            base[i] * 0.7f + (0.12 * Math.sin(2.0 * Math.PI * 440.0 * i / rate)).toFloat()
        }
        val src = File(temp, "rt song.wav")
        WavWriter.write(src, Snip(bedded, 1, rate))

        val (code, stdout, _) = cli("retime", src.path, "--to", "84", "--from", "100")
        assertEquals(0, code, stdout)
        assertContains(stdout, "pitch kept")
        val out = com.snipsnap.audio.WavReader.read(File(temp, "rt song 84bpm.wav"))
        assertTrue(
            Math.abs(out.frameCount - (bedded.size * (100.0 / 84.0)).toInt()) <= 1,
            "the duration is the ratio's: ${out.frameCount}",
        )
        assertTrue(
            tone(out.samples, 440.0, rate) > 5 * tone(out.samples, 440.0 * 84.0 / 100.0, rate),
            "the bed still sings 440 - no drag",
        )
        assertEquals(2, cli("retime", src.path).first, "a target is required")
        assertEquals(2, cli("chop", src.path, "--keep-pitch").first, "--keep-pitch rides on --fit-tempo")
    }

    @Test
    fun `morph conjures the sound between two parents - not a crossfade`() {
        val rate = 44_100
        val out = File(temp, "morphkit")
        val wav = writeBreak(File(temp, "morphsrc.wav"))
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Morpher").first)
        val kitDir = File(out, "Morpher")
        val model = com.snipsnap.shell.KitBuilderModel.open(kitDir)
        val pad = model.kit.pads.minBy { it.slot }
        fun toneHit(hz: Double): FloatArray = FloatArray(rate) { i ->
            val t = i.toDouble() / rate
            (0.6 * Math.sin(2.0 * Math.PI * hz * t) * Math.exp(-5.0 * t)).toFloat()
        }
        WavWriter.write(File(kitDir, pad.sampleFile), Snip(toneHit(300.0), 1, rate))
        val parent = File(temp, "morph parent.wav")
        WavWriter.write(parent, Snip(toneHit(1200.0), 1, rate))
        val padRef = "A%02d".format(pad.slot)

        fun padMono(): FloatArray {
            val p = KitStore.load(kitDir).pads.first { it.slot == pad.slot }
            val s = com.snipsnap.audio.WavReader.read(File(kitDir, p.sampleFile))
            return FloatArray(s.frameCount) { f ->
                (0 until s.channels).sumOf { ch -> s.samples[f * s.channels + ch].toDouble() }.toFloat() / s.channels
            }
        }
        fun morphTo(amount: String): FloatArray {
            val r = cli("mutate", kitDir.path, padRef, "--morph", "--with", parent.path, "--amount", amount)
            assertEquals(0, r.first, r.third)
            val m = padMono()
            val undo = cli("mutate", kitDir.path, padRef, "--undo")
            assertEquals(0, undo.first, undo.third)
            return m
        }

        // Amount 0 is the pad; amount 1 is the parent.
        val at0 = morphTo("0")
        assertTrue(tone(at0, 300.0, rate) > 5 * tone(at0, 1200.0, rate), "amount 0 stays the pad")
        val at1 = morphTo("1")
        assertTrue(tone(at1, 1200.0, rate) > 5 * tone(at1, 300.0, rate), "amount 1 becomes the parent")

        // Halfway, BOTH parents sing in one hit - between, not either.
        val mid = morphTo("0.5")
        val p300 = tone(mid, 300.0, rate)
        val p1200 = tone(mid, 1200.0, rate)
        assertTrue(minOf(p300, p1200) > 0.2f * maxOf(p300, p1200), "both parents audible: $p300 / $p1200")
        val onsets = com.snipsnap.audio.Transients.detect(Snip(mid, 1, rate))
        assertTrue(onsets.size <= 1, "one hit, not a crossfade of two: ${onsets.size} onsets")

        // The recipe records the mode and the amount.
        val r = cli("mutate", kitDir.path, padRef, "--morph", "--with", parent.path, "--amount", "0.5")
        assertEquals(0, r.first, r.third)
        assertContains(r.second, "morphed 50% toward")
        val recipe = KitStore.load(kitDir).pads.first { it.slot == pad.slot }
            .recipe?.entries?.get("mutate") as? com.snipsnap.json.JsonValue.Obj
        assertTrue(recipe?.entries?.get("mode")?.let { (it as com.snipsnap.json.JsonValue.Str).value } == "morph")
        assertTrue(recipe?.entries?.containsKey("amount") == true, "the amount rides the recipe")
        assertEquals(2, cli("mutate", kitDir.path, padRef, "--amount", "0.5", "--with", parent.path).first, "--amount rides on --morph")
    }

    @Test
    fun `clean --declip names the rebuild and leaves unclipped audio alone`() {
        val rate = 44_100
        val hit = FloatArray(rate) { i ->
            val t = i.toDouble() / rate
            (Math.sin(2.0 * Math.PI * 90.0 * t * (1 - 0.2 * t)) * Math.exp(-4.0 * t)).toFloat()
        }
        val clipped = FloatArray(hit.size) { hit[it].coerceIn(-0.4f, 0.4f) }
        val src = File(temp, "clipped take.wav")
        WavWriter.write(src, Snip(clipped, 1, rate))

        val (code, stdout, _) = cli("clean", src.path, "--declip")
        assertEquals(0, code, stdout)
        assertContains(stdout, "clipping rebuilt")
        val twin = com.snipsnap.audio.WavReader.read(File(temp, "clipped take Clean.wav"))
        assertTrue(twin.samples.maxOf { Math.abs(it) } > 0.45f, "peaks pushed past the ceiling")

        // Without the flag, the same file's flat tops are not touched.
        val (plainCode, plainOut, _) = cli("clean", src.path, "--dry")
        assertEquals(0, plainCode, plainOut)
        assertTrue("clipping" !in plainOut, "declip is an explicit opt-in")

        // --deverb is the same kind of opt-in, and names its leg.
        val (dvCode, dvOut, _) = cli("clean", src.path, "--deverb", "--overwrite")
        assertEquals(0, dvCode, dvOut)
        assertContains(dvOut, "room predicted and subtracted")
    }

    @Test
    fun `checkup scores every capture in the folder and invents nothing for an empty one`() {
        val rate = 44_100
        val refDir = File(temp, "reference")
        refDir.mkdirs()

        // Empty: it says what it's waiting for.
        val (emptyCode, emptyOut, _) = cli("checkup", refDir.path)
        assertEquals(0, emptyCode, emptyOut)
        assertContains(emptyOut, "no captures yet")

        // A hummy beat and a clipped tonal hit - the detectors' own numbers.
        val base = com.snipsnap.audio.WavReader.read(writeBreak(File(temp, "cksrc2.wav"))).samples
        val hummy = FloatArray(base.size) { i ->
            base[i] + 0.05f * Math.sin(2.0 * Math.PI * 50.0 * i / rate).toFloat()
        }
        WavWriter.write(File(refDir, "hummy take.wav"), Snip(hummy, 1, rate))
        val hit = FloatArray(rate) { i ->
            val t = i.toDouble() / rate
            (Math.sin(2.0 * Math.PI * 90.0 * t) * Math.exp(-4.0 * t)).toFloat().coerceIn(-0.4f, 0.4f)
        }
        WavWriter.write(File(refDir, "clipped take.wav"), Snip(hit, 1, rate))

        val (code, stdout, _) = cli("checkup", refDir.path)
        assertEquals(0, code, stdout)
        assertContains(stdout, "50 Hz")
        assertContains(stdout, "pinned at 0.40")
        assertContains(stdout, "nothing was written")
        // Read-only: the captures' bytes are exactly as dropped.
        assertTrue(
            com.snipsnap.audio.WavReader.read(File(refDir, "hummy take.wav")).samples.size == hummy.size,
            "checkup measures, never writes",
        )
    }

    @Test
    fun `the whole visit at once - one recipe naming every leg, undone byte for byte`() {
        val rate = 44_100
        val out = File(temp, "alllegs")
        val wav = writeBreak(File(temp, "alsrc.wav"))
        assertEquals(0, cli("chop", wav.path, "--out", out.path, "--name", "Everything").first)
        val kitDir = File(out, "Everything")
        val kit = KitStore.load(kitDir)
        val victim = kit.pads.minBy { it.slot }

        // One pad with everything wrong at once: hum under it, peaks
        // clipped flat, hiss over it.
        val rnd = java.util.Random(12)
        // The clip comes LAST, the way a mic chain clips: the sum of
        // hit + hum + hiss pinned flat at the converter's ceiling.
        val dirty = FloatArray(2 * rate) { i ->
            val t = i.toDouble() / rate
            val hit = (0.9 * Math.sin(2.0 * Math.PI * 150.0 * t) * Math.exp(-3.0 * t)).toFloat()
            (
                hit +
                    0.04f * Math.sin(2.0 * Math.PI * 50.0 * i / rate).toFloat() +
                    (rnd.nextFloat() * 2f - 1f) * 0.008f
                ).coerceIn(-0.5f, 0.5f)
        }
        WavWriter.write(File(kitDir, victim.sampleFile), Snip(dirty, 1, rate))
        val bytesBefore = KitStore.load(kitDir).pads.associate { it.sampleFile to File(kitDir, it.sampleFile).readBytes() }

        val (code, stdout, _) = cli("clean", kitDir.path, "--declip", "--deverb", "--denoise")
        assertEquals(0, code, stdout)
        assertContains(stdout, "clipping rebuilt")
        assertContains(stdout, "hum notched")
        assertContains(stdout, "room predicted and subtracted")
        assertContains(stdout, "spectrally de-noised")

        val recipe = KitStore.load(kitDir).pads.first { it.slot == victim.slot }
            .recipe?.entries?.get("clean") as? com.snipsnap.json.JsonValue.Obj
        assertTrue(recipe != null, "the clean recipe rides the pad")
        for (key in listOf("humHz", "declipCeiling", "deverbed", "denoised")) {
            assertTrue(recipe!!.entries.containsKey(key), "the recipe names its $key leg")
        }

        // The recipe survives its own store round trip verbatim.
        KitStore.save(KitStore.load(kitDir), kitDir)
        val rereadRecipe = KitStore.load(kitDir).pads.first { it.slot == victim.slot }.recipe
        assertEquals(
            KitStore.load(kitDir).pads.first { it.slot == victim.slot }.recipe, rereadRecipe,
            "recipes are stable through save/load",
        )

        // And one undo pulls every leg back out at once, byte for byte.
        val (undoCode, undoOut, _) = cli("clean", kitDir.path, "--undo")
        assertEquals(0, undoCode, undoOut)
        for ((f, bytes) in bytesBefore) {
            assertTrue(File(kitDir, f).readBytes().contentEquals(bytes), "$f back byte-identical")
        }

        // The kit still exports after all of it - and an .xpn round trip
        // of the treated kit neither crashes nor loses audio (the .xpn
        // format doesn't carry recipes; kit.json does, and says so).
        assertEquals(0, cli("clean", kitDir.path, "--declip", "--deverb", "--denoise").first)
        val exp = cli("export", kitDir.path, "--export", "xpn", "--out", File(temp, "allexp").path)
        assertEquals(0, exp.first, exp.third)
        val xpn = File(temp, "allexp").walkTopDown().first { it.extension == "xpn" }
        val imp = cli("import", xpn.path, "--out", File(temp, "allimp").path, "--overwrite")
        assertEquals(0, imp.first, imp.third)
    }

    @Test
    fun `chop hears a phone capture and puts the gutless kick back on A01`() {
        val rate = 44_100
        // A break of realistic hits (the kick carries knock + click, like
        // a real drum), then the phone treatment: ~24 dB/oct at 150 Hz.
        fun realisticKick(): FloatArray = FloatArray((0.4f * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            (
                0.9 * Math.sin(2.0 * Math.PI * 55.0 * t) * Math.exp(-9.0 * t) +
                    0.2 * Math.sin(2.0 * Math.PI * 220.0 * t) * Math.exp(-25.0 * t) +
                    (if (i < 30) 0.08 * (1.0 - i / 30.0) else 0.0)
                ).toFloat()
        }
        val step = (60f / 100f / 2f * rate).toInt()
        val total = FloatArray(step * 8 + rate / 2)
        for ((ix, hit) in listOf(
            0 to realisticKick(), 1 to DrumSynth.closedHat().samples,
            2 to DrumSynth.snare().samples, 3 to DrumSynth.closedHat().samples,
            4 to realisticKick(), 5 to DrumSynth.closedHat().samples,
            6 to DrumSynth.snare().samples, 7 to DrumSynth.closedHat().samples,
        )) {
            val at = ix * step
            for (i in hit.indices) {
                if (at + i < total.size) total[at + i] += hit[i] * 0.8f
            }
        }
        var phone = total
        repeat(4) {
            val a = Math.exp(-2.0 * Math.PI * 150.0 / rate).toFloat()
            val res = FloatArray(phone.size)
            var yPrev = 0f
            var xPrev = 0f
            for (i in phone.indices) {
                val y = a * (yPrev + phone[i] - xPrev)
                res[i] = y
                yPrev = y
                xPrev = phone[i]
            }
            phone = res
        }
        val src = File(temp, "phone break.wav")
        WavWriter.write(src, Snip(phone, 1, rate))

        val out = File(temp, "phonechop")
        val (code, stdout, _) = cli("chop", src.path, "--out", out.path, "--name", "PhoneBreak")
        assertEquals(0, code, stdout)
        assertContains(stdout, "phone capture heard")
        val kit = KitStore.load(File(out, "PhoneBreak"))
        val kicks = kit.pads.filter { it.drumClass == DrumClass.KICK }
        assertTrue(kicks.isNotEmpty(), "the gutless kicks came home: ${kit.pads.map { it.drumClass }}")
        assertTrue(kit.pads.any { it.slot == 1 && it.drumClass == DrumClass.KICK }, "and A01 is a kick again")
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

    @Test
    fun `pad makes a held instrument from a note or a hit, both generations`() {
        val rate = 44_100
        val note = File(temp, "pad note.wav")
        WavWriter.write(
            note,
            Snip(
                FloatArray(rate) { i ->
                    val t = i.toDouble() / rate
                    ((0.5 * Math.sin(2 * Math.PI * 220.0 * t) + 0.15 * Math.sin(2 * Math.PI * 440.0 * t)) * Math.exp(-2.0 * t)).toFloat()
                },
                1, rate,
            ),
        )
        val out = File(temp, "padout")
        val (code, stdout, _) = cli("pad", note.path, "--out", out.path, "--depth", "20")
        assertEquals(0, code, stdout)
        assertContains(stdout, "A3")
        assertContains(stdout, "the clear stretch x20.0")
        assertContains(stdout, "sings forever")
        assertTrue(File(out, "card/pad note Pad.xty").isFile, "the MPC 3 instrument")
        assertTrue(File(out, "card/pad note Pad_[TrackData]/pad note Pad.xpm").isFile, "the MPC 2 twin")

        // A hit with no note in it is a drone, not a refusal.
        val hit = File(temp, "pad hit.wav")
        val rnd = java.util.Random(3)
        WavWriter.write(hit, Snip(FloatArray(rate / 4) { i -> ((rnd.nextFloat() * 2f - 1f) * 0.8 * Math.exp(-i / (0.06 * rate))).toFloat() }, 1, rate))
        val (droneCode, droneOut, _) = cli("pad", hit.path, "--out", out.path)
        assertEquals(0, droneCode, droneOut)
        assertContains(droneOut, "a drone at C3")

        // The knobs are bounded, and the source must be a sound.
        val (badCode, _, badErr) = cli("pad", note.path, "--out", out.path, "--depth", "3")
        assertTrue(badCode != 0 && "--depth wants" in badErr, badErr)
        val (dupCode, _, dupErr) = cli("pad", note.path, "--out", out.path)
        assertTrue(dupCode != 0 && "already exists" in dupErr, "overwrite discipline: $dupErr")
    }
}

// KeySpec's own tests live in :audio beside the parser (KeySpecTest);
// the chop tests above cover the CLI's use of it.
