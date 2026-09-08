package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XpnImporterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("xpnimport").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun tone(seed: Int): Snip =
        Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / (10.0 + seed))).toFloat() }, 1, 44_100))

    private fun buildKit(dir: File): Kit {
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(1))
        WavWriter.write(File(dir, "A03_Hat_01.wav"), tone(2))
        WavWriter.write(File(dir, "A02_Snare_soft.wav"), tone(3))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(4))
        val kit = Kit(
            "Round Trip",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK,
                    level = 0.9f, pan = 0.25f, tuneCoarse = -2, tuneFine = 30, oneShot = true,
                ),
                KitPad(
                    slot = 2, sampleFile = "A02_Snare_01.wav",
                    velocityLayers = listOf(
                        KitLayer("A02_Snare_soft.wav", 1, 63),
                        KitLayer("A02_Snare_01.wav", 64, 127),
                    ),
                ),
                KitPad(slot = 3, sampleFile = "A03_Hat_01.wav", muteGroup = 1, oneShot = false),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `pack then import round-trips the kit`() {
        val kitDir = File(temp, "src")
        val kit = buildKit(kitDir)
        val xpn = File(temp, "Round Trip.xpn")
        XpnPackager.write(kit, kitDir, xpn, Exporters.defaultMeta(kit))

        val result = XpnImporter.import(xpn, File(temp, "in"))
        assertEquals("Round Trip", result.kit.name)
        assertEquals(listOf(1, 2, 3), result.kit.pads.map { it.slot })

        val kick = result.kit.pad(1)!!
        assertEquals("A01_Kick_01.wav", kick.sampleFile)
        assertEquals(0.9f, kick.level)
        assertEquals(0.25f, kick.pan)
        assertEquals(-2, kick.tuneCoarse)
        assertEquals(30, kick.tuneFine)
        assertTrue(kick.oneShot)
        assertEquals(DrumClass.UNKNOWN, kick.drumClass, "import never invents a class")

        val snare = result.kit.pad(2)!!
        assertEquals(2, snare.velocityLayers.size)
        assertEquals("A02_Snare_soft.wav", snare.velocityLayers[0].sampleFile)
        assertEquals(1, snare.velocityLayers[0].velStart)
        assertEquals("A02_Snare_01.wav", snare.sampleFile)

        val hat = result.kit.pad(3)!!
        assertEquals(1, hat.muteGroup)
        assertTrue(!hat.oneShot)

        // Sample bytes survived the zip untouched.
        for (f in listOf("A01_Kick_01.wav", "A02_Snare_soft.wav", "A02_Snare_01.wav", "A03_Hat_01.wav")) {
            assertTrue(
                File(kitDir, f).readBytes().contentEquals(File(result.directory, f).readBytes()),
                "$f changed in transit",
            )
        }

        // The landed folder is a working kit: it re-exports.
        val out = KitExporter.exportProgramFolder(result.kit, result.directory, File(temp, "reexport"))
        assertTrue(out.program.isFile)

        // Second import without overwrite refuses; with it, succeeds.
        assertFailsWith<java.io.IOException> { XpnImporter.import(xpn, File(temp, "in")) }
        XpnImporter.import(xpn, File(temp, "in"), overwrite = true)
    }

    /** A vendor-shaped archive: 1-based instruments, deep folders, root XML. */
    private fun foreignArchive(file: File): File {
        val wavA = File(temp, "Boom.wav").also { WavWriter.write(it, tone(5)) }
        val wavB = File(temp, "Tick.wav").also { WavWriter.write(it, tone(6)) }
        val program = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPCVObject>
              <Program type="Drum">
                <ProgramName>Foreign Kit</ProgramName>
                <Instruments>
                  <Instrument number="1">
                    <Volume>0.800000</Volume>
                    <Pan>0.500000</Pan>
                    <TuneCoarse>0</TuneCoarse>
                    <TuneFine>0</TuneFine>
                    <MuteGroup>0</MuteGroup>
                    <OneShot>True</OneShot>
                    <Layers>
                      <Layer number="1">
                        <VelStart>0</VelStart>
                        <VelEnd>127</VelEnd>
                        <SampleName>Boom</SampleName>
                      </Layer>
                    </Layers>
                  </Instrument>
                  <Instrument number="2">
                    <Volume>0.600000</Volume>
                    <MuteGroup>2</MuteGroup>
                    <OneShot>False</OneShot>
                    <Layers>
                      <Layer number="1">
                        <VelStart>0</VelStart>
                        <VelEnd>127</VelEnd>
                        <SampleName>Tick</SampleName>
                      </Layer>
                    </Layers>
                  </Instrument>
                  <Instrument number="3">
                    <Layers>
                      <Layer number="1"><SampleName></SampleName></Layer>
                    </Layers>
                  </Instrument>
                </Instruments>
              </Program>
            </MPCVObject>
        """.trimIndent()

        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("Expansion.xml", "<Expansion/>".toByteArray())
            put("Foreign Kit.xpm", program.toByteArray())
            put("Data/Samples/Deep/Boom.wav", wavA.readBytes())
            put("Data/Samples/Deeper/Still/Tick.wav", wavB.readBytes())
            put("[Previews]/Foreign Kit.xpm.wav", wavA.readBytes()) // must be ignored
        }
        return file
    }

    private fun archiveWithSampleName(file: File, sampleName: String): File {
        val wav = File(temp, "planted.wav").also { WavWriter.write(it, tone(9)) }
        val program = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPCVObject><Program type="Drum"><ProgramName>Evil Kit</ProgramName>
              <Instruments><Instrument number="1"><Layers><Layer number="1">
                <VelStart>0</VelStart><VelEnd>127</VelEnd>
                <SampleName>$sampleName</SampleName>
              </Layer></Layers></Instrument></Instruments></Program></MPCVObject>
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Evil Kit.xpm")); zip.write(program.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("pwned.wav")); zip.write(wav.readBytes()); zip.closeEntry()
        }
        return file
    }

    @Test
    fun `a traversing sample name is flattened to a basename, nothing escapes`() {
        // The traversal collapses to "pwned.wav" and lands inside the kit;
        // nothing is ever written up at the escape target.
        val xpn = archiveWithSampleName(File(temp, "evil.xpn"), "../../../pwned")
        val canary = File(temp, "pwned.wav").also { it.delete() }

        val result = XpnImporter.import(xpn, File(temp, "dest"))
        assertEquals("pwned.wav", result.kit.pad(1)!!.sampleFile)
        assertTrue(File(result.directory, "pwned.wav").isFile, "landed inside the kit folder")
        assertTrue(!canary.exists(), "nothing was written outside the destination")
    }

    private fun xpnWithProgram(file: File, programBody: String, vararg samples: String): File {
        samples.forEach { WavWriter.write(File(temp, "$it.wav"), tone(it.hashCode())) }
        val program = """
            <?xml version="1.0"?><MPCVObject><Program type="Drum">
            <ProgramName>Meta Kit</ProgramName><Instruments>$programBody</Instruments>
            </Program></MPCVObject>
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Meta Kit.xpm")); zip.write(program.toByteArray()); zip.closeEntry()
            samples.forEach {
                zip.putNextEntry(ZipEntry("$it.wav")); zip.write(File(temp, "$it.wav").readBytes()); zip.closeEntry()
            }
        }
        return file
    }

    private fun inst(number: Int, sample: String, extra: String = ""): String =
        """<Instrument number="$number">$extra<Layers><Layer number="1">
           <VelStart>0</VelStart><VelEnd>127</VelEnd><SampleName>$sample</SampleName>
           </Layer></Layers></Instrument>"""

    @Test
    fun `duplicate pad slots are refused by name`() {
        // Two instruments both numbered 0 (0-based) land on slot 1.
        val xpn = xpnWithProgram(File(temp, "dup.xpn"), inst(0, "s") + inst(0, "s"), "s")
        val err = assertFailsWith<IllegalArgumentException> { XpnImporter.import(xpn, File(temp, "dup-out")) }
        assertTrue("duplicate pad slots" in err.message!!, err.message!!)
    }

    @Test
    fun `an out-of-range level is clamped, not refused`() {
        val xpn = xpnWithProgram(
            File(temp, "loud.xpn"), inst(0, "s", "<Volume>9.000000</Volume>"), "s",
        )
        val kit = XpnImporter.import(xpn, File(temp, "loud-out")).kit
        assertEquals(1f, kit.pad(1)!!.level, "9.0 clamped to the valid ceiling")
    }

    @Test
    fun `a layer pointing at a missing sample is refused by name`() {
        val xpn = xpnWithProgram(File(temp, "ghost.xpn"), inst(0, "ghost")) // no ghost.wav planted
        val err = assertFailsWith<IllegalArgumentException> { XpnImporter.import(xpn, File(temp, "ghost-out")) }
        assertTrue("missing" in err.message!! && "ghost" in err.message!!, err.message!!)
    }

    @Test
    fun `an out-of-range velocity window is refused with a reason`() {
        val bad = """<Instrument number="0"><Layers><Layer number="1">
            <VelStart>0</VelStart><VelEnd>200</VelEnd><SampleName>s</SampleName></Layer>
            <Layer number="2"><VelStart>100</VelStart><VelEnd>127</VelEnd>
            <SampleName>s</SampleName></Layer></Layers></Instrument>"""
        val xpn = xpnWithProgram(File(temp, "vel.xpn"), bad, "s")
        // A velocity window of 0..200 is not a value to clamp silently - it's
        // a malformed layer, refused by the KitLayer invariant.
        val err = assertFailsWith<IllegalArgumentException> { XpnImporter.import(xpn, File(temp, "vel-out")) }
        assertTrue("velocity" in err.message!!.lowercase(), err.message!!)
    }

    @Test
    fun `xml variety the old regex would drop still imports every pad`() {
        // Everything a pattern-matcher mishandles at once: reordered and
        // extra attributes on Instrument, a comment, insignificant
        // whitespace, a CDATA sample name, a numeric-entity program name,
        // and attributes on tags the old tag() regex assumed were bare.
        WavWriter.write(File(temp, "kick.wav"), tone(1))
        WavWriter.write(File(temp, "snare.wav"), tone(2))
        val program = """<?xml version="1.0" encoding="UTF-8"?>
            <MPCVObject><Program type="Drum">
              <!-- exported by some other tool -->
              <ProgramName>Kit &#38; &amp; Bass</ProgramName>
              <Instruments>
                <Instrument   number="0"  index="0" >
                  <Volume>0.800000</Volume><Pan>0.5</Pan><MuteGroup>0</MuteGroup>
                  <Layers><Layer number="1" enabled="true">
                    <VelStart>0</VelStart><VelEnd>127</VelEnd>
                    <SampleName><![CDATA[kick]]></SampleName>
                  </Layer></Layers>
                </Instrument>
                <Instrument index="1" number="1">
                  <Volume>0.6</Volume>
                  <Layers><Layer number="1"><SampleName>snare</SampleName>
                    <VelStart>0</VelStart><VelEnd>127</VelEnd></Layer></Layers>
                </Instrument>
              </Instruments>
            </Program></MPCVObject>
        """.trimIndent()
        val xpn = File(temp, "variety.xpn")
        ZipOutputStream(xpn.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Variety.xpm")); zip.write(program.toByteArray()); zip.closeEntry()
            for (s in listOf("kick", "snare")) {
                zip.putNextEntry(ZipEntry("$s.wav")); zip.write(File(temp, "$s.wav").readBytes()); zip.closeEntry()
            }
        }

        val result = XpnImporter.import(xpn, File(temp, "variety-out"))
        assertEquals(2, result.kit.pads.size, "both pads survived the attribute variety, comment and CDATA")
        assertEquals("kick.wav", result.kit.pad(1)!!.sampleFile)
        assertEquals("snare.wav", result.kit.pad(2)!!.sampleFile)
        // The numeric (&#38;) and named (&amp;) entities both resolved - the
        // numeric one the old hand-rolled xmlUnescape never handled at all.
        assertEquals("Kit & & Bass", result.kit.name)
    }

    @Test
    fun `a legit vendor subpath is flattened too`() {
        // Real packs carry names like "Samples/Deep/Kick" - honest, not hostile.
        val xpn = archiveWithSampleName(File(temp, "vendor.xpn"), "Samples/Deep/pwned")
        val result = XpnImporter.import(xpn, File(temp, "vendor-dest"))
        assertEquals("pwned.wav", result.kit.pad(1)!!.sampleFile)
    }

    @Test
    fun `a vendor-shaped archive imports - 1-based numbering, deep folders`() {
        val xpn = foreignArchive(File(temp, "Foreign.xpn"))
        val result = XpnImporter.import(xpn, File(temp, "foreign-in"))

        assertEquals("Foreign Kit", result.kit.name)
        // No number-0 instrument: 1-based, so instrument 1 is pad slot 1.
        assertEquals(listOf(1, 2), result.kit.pads.map { it.slot })
        assertEquals("Boom.wav", result.kit.pad(1)?.sampleFile)
        assertEquals(0.8f, result.kit.pad(1)!!.level)
        assertEquals(2, result.kit.pad(2)?.muteGroup)
        assertTrue(!result.kit.pad(2)!!.oneShot)
        assertTrue(File(result.directory, "Tick.wav").isFile, "deep-foldered sample found by bare name")
    }

    @Test
    fun `keygroup programs and missing samples are refused with reasons`() {
        val kgXpn = File(temp, "Keys.xpn")
        ZipOutputStream(kgXpn.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Programs/Keys.xpm"))
            zip.write("<MPCVObject><Program type=\"Keygroup\"><ProgramName>Keys</ProgramName></Program></MPCVObject>".toByteArray())
            zip.closeEntry()
        }
        val kg = assertFailsWith<IllegalArgumentException> { XpnImporter.import(kgXpn, File(temp, "x")) }
        assertTrue("keygroup" in kg.message!!)

        val missing = File(temp, "Missing.xpn")
        ZipOutputStream(missing.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Kit.xpm"))
            zip.write(
                """<Program type="Drum"><ProgramName>Kit</ProgramName>
                   <Instrument number="1"><Layers><Layer number="1">
                   <VelStart>0</VelStart><VelEnd>127</VelEnd>
                   <SampleName>Ghost</SampleName></Layer></Layers></Instrument></Program>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        val err = assertFailsWith<IllegalArgumentException> { XpnImporter.import(missing, File(temp, "y")) }
        assertTrue("Ghost" in err.message!!)
    }

    @Test
    fun `a write budget bounds total samples, not just one entry`() {
        val kitDir = File(temp, "budget-src")
        val kit = buildKit(kitDir)
        val xpn = File(temp, "Round Trip.xpn")
        XpnPackager.write(kit, kitDir, xpn, Exporters.defaultMeta(kit))
        val totalSampleBytes = listOf("A01_Kick_01.wav", "A02_Snare_soft.wav", "A02_Snare_01.wav", "A03_Hat_01.wav")
            .sumOf { File(kitDir, it).length() }

        // Too tight for the kit's four samples combined, even though each
        // one alone is far under LimitedRead's own per-entry ceiling.
        val tight = assertFailsWith<com.snipsnap.mpc3.LimitedRead.TooLargeException> {
            XpnImporter.import(xpn, File(temp, "tight-out"), budget = XpnImporter.WriteBudget(totalSampleBytes - 1))
        }
        assertTrue("Round Trip.xpn" in tight.message!!)

        // Comfortably enough: the same archive imports clean.
        XpnImporter.import(xpn, File(temp, "roomy-out"), budget = XpnImporter.WriteBudget(totalSampleBytes))
    }

    @Test
    fun `importAll shares one budget across every program, not one each`() {
        // Two independent kits packed into one archive - buildKit always
        // names its kit "Round Trip", so the second copy needs renaming or
        // the pack would see one name twice.
        val kitA = File(temp, "multi-a").also { buildKit(it) }
        val kitB = File(temp, "multi-b").also { dir ->
            buildKit(dir)
            KitStore.save(KitStore.load(dir).copy(name = "Round Trip 2"), dir)
        }
        val pack = PackBuilder.build(
            listOf(kitA, kitB),
            File(temp, "multi-card"),
            ExpansionMeta(title = "Two Kits", identifier = "app.snipsnap.twokits", description = "d"),
            asXpn = true,
        )
        val archive = pack.xpn!!
        val oneKitBytes = listOf("A01_Kick_01.wav", "A02_Snare_soft.wav", "A02_Snare_01.wav", "A03_Hat_01.wav")
            .sumOf { File(kitA, it).length() }

        // Room for one kit's samples, not both: the second program is
        // skipped for blowing the shared budget, not imported anyway.
        val result = XpnImporter.importAll(archive, File(temp, "multi-out"), budget = XpnImporter.WriteBudget(oneKitBytes + 1))
        assertEquals(1, result.kits.size, "only the first program fit the shared budget")
        assertEquals(1, result.skipped.size)
        assertTrue(result.skipped[0].second.contains("MB", ignoreCase = true), result.skipped[0].second)
    }

    @Test
    fun `a program that blows the budget spends its partial write, and leaves nothing behind`() {
        // Five programs, each with one sample far bigger than the whole
        // budget on its own: every one refuses. If a refused program's
        // partial write were never charged against the budget, importAll's
        // skip-and-continue would hand the next program the same unspent
        // allowance - five failed attempts could then leave several times
        // the budget on disk instead of, at most, one budget's worth.
        val kits = (1..5).map { i ->
            val dir = File(temp, "leak-$i")
            dir.mkdirs()
            val big = Cleanup.process(Snip(FloatArray(100_000) { s -> (0.4 * Math.sin(s / (30.0 + i))).toFloat() }, 1, 44_100))
            WavWriter.write(File(dir, "A01_Kick_01.wav"), big)
            KitStore.save(Kit("Leak $i", listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK))), dir)
            dir
        }
        val pack = PackBuilder.build(
            kits,
            File(temp, "leak-card"),
            ExpansionMeta(title = "Leak Pack", identifier = "app.snipsnap.leak", description = "d"),
            asXpn = true,
        )
        val oneSampleBytes = File(kits[0], "A01_Kick_01.wav").length()
        val budget = XpnImporter.WriteBudget(oneSampleBytes / 4) // too small for even one sample

        val destRoot = File(temp, "leak-out")
        val result = XpnImporter.importAll(pack.xpn!!, destRoot, budget = budget)
        assertTrue(result.kits.isEmpty(), "every program's one sample alone exceeds the whole budget")
        assertEquals(5, result.skipped.size)

        val onDisk = destRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(onDisk <= budget.max, "five refused programs left $onDisk bytes on disk - more than the ${budget.max}-byte budget ever allowed")
    }

    @Test
    fun `a program refused partway leaves no kit folder behind at all`() {
        // Four samples; a budget that lets the first couple through before
        // the rest blow it. Writes used to land straight in destDir, so a
        // program refused mid-loop left WAVs on disk with no kit.json to
        // name them - invisible to the shelf's own listing, not to the
        // disk. Everything now stages first and only moves into place on
        // full success, so a refusal must leave no trace of the kit at all.
        val kitDir = File(temp, "orphan-src")
        val kit = buildKit(kitDir)
        val xpn = File(temp, "Round Trip.xpn")
        XpnPackager.write(kit, kitDir, xpn, Exporters.defaultMeta(kit))
        val firstSampleBytes = File(kitDir, "A01_Kick_01.wav").length()

        val destRoot = File(temp, "orphan-out")
        assertFailsWith<com.snipsnap.mpc3.LimitedRead.TooLargeException> {
            XpnImporter.import(xpn, destRoot, budget = XpnImporter.WriteBudget(firstSampleBytes + 1))
        }
        assertTrue(!File(destRoot, "Round Trip").exists(), "a refused program must leave nothing on the shelf, partial or otherwise")
    }

    @Test
    fun `overwriting an existing kit replaces it cleanly, no staging litter left beside it`() {
        val firstDir = File(temp, "swap-src-1")
        val first = buildKit(firstDir)
        val firstXpn = File(temp, "Round Trip.xpn")
        XpnPackager.write(first, firstDir, firstXpn, Exporters.defaultMeta(first))
        val destRoot = File(temp, "swap-out")
        XpnImporter.import(firstXpn, destRoot)

        // A second, different kit sharing the first one's name.
        val secondDir = File(temp, "swap-src-2")
        secondDir.mkdirs()
        val differentTone = Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / 3.0)).toFloat() }, 1, 44_100))
        WavWriter.write(File(secondDir, "A01_Kick_01.wav"), differentTone)
        val second = Kit("Round Trip", listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)))
        KitStore.save(second, secondDir)
        val secondXpn = File(temp, "Round Trip 2.xpn")
        XpnPackager.write(second, secondDir, secondXpn, Exporters.defaultMeta(second))

        val result = XpnImporter.import(secondXpn, destRoot, overwrite = true)
        assertEquals(listOf(1), result.kit.pads.map { it.slot })
        assertTrue(
            File(secondDir, "A01_Kick_01.wav").readBytes().contentEquals(File(destRoot, "Round Trip/A01_Kick_01.wav").readBytes()),
            "the second kit's own sample must be what landed, not the first's",
        )
        assertEquals(setOf("Round Trip"), destRoot.list()!!.toSet(), "no .xpn-replaced-* staging litter left beside the kit")
    }
}
