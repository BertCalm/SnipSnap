package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitBackup
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.XpnPackager
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShelfImportTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("shelf-import").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** A one-pad kit on [root], the shape KitBackupTest builds. */
    private fun makeKit(root: File, name: String, pads: Int = 1): File {
        val dir = File(root, name)
        dir.mkdirs()
        val list = (1..pads).map { slot ->
            val snip = Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / (6.0 + slot))).toFloat() }, 1, 44_100))
            val file = "A%02d_Hit_%02d.wav".format(slot, slot)
            WavWriter.write(File(dir, file), snip)
            KitPad(slot = slot, sampleFile = file, drumClass = DrumClass.PERC)
        }
        KitStore.save(Kit(name, list), dir)
        return dir
    }

    private fun zipDir(dir: File, out: File) {
        ZipOutputStream(out.outputStream()).use { zip ->
            dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(dir).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `sniff tells a ZIP, a container, a sound, and nothing apart`() {
        assertEquals(ShelfImport.Kind.XPN, ShelfImport.sniff(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0)))
        assertEquals(ShelfImport.Kind.XPN, ShelfImport.sniff(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 5, 6)), "an empty ZIP is a ZIP")
        assertEquals(ShelfImport.Kind.XPN, ShelfImport.sniff(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 7, 8)), "a spanned ZIP is a ZIP")
        assertEquals(ShelfImport.Kind.UNKNOWN, ShelfImport.sniff(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2)), "PK alone is not")
        assertEquals(ShelfImport.Kind.MPC3, ShelfImport.sniff(byteArrayOf(0x1f, 0x8b.toByte(), 8)))
        assertEquals(ShelfImport.Kind.AUDIO, ShelfImport.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ShelfImport.Kind.AUDIO, ShelfImport.sniff("ID3\u0004".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ShelfImport.Kind.AUDIO, ShelfImport.sniff("\u0000\u0000\u0000\u0018ftypM4A ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ShelfImport.Kind.UNKNOWN, ShelfImport.sniff("hello there".toByteArray()))
        assertEquals(ShelfImport.Kind.UNKNOWN, ShelfImport.sniff(ByteArray(0)))
        assertTrue(ShelfImport.isKit(ShelfImport.Kind.XPN) && ShelfImport.isKit(ShelfImport.Kind.MPC3))
        assertTrue(!ShelfImport.isKit(ShelfImport.Kind.AUDIO) && !ShelfImport.isKit(ShelfImport.Kind.UNKNOWN))
    }

    @Test
    fun `a packed kit lands on the shelf, and a second copy lands beside it under its own name`() {
        val source = File(temp, "source")
        val kitDir = makeKit(source, "FUNK", pads = 2)
        val kit = KitStore.load(kitDir)
        val xpn = File(temp, "FUNK.xpn")
        XpnPackager.write(kit, kitDir, xpn, Exporters.defaultMeta(kit))

        val shelf = File(temp, "shelf")
        val first = ShelfImport.land(xpn, "FUNK.xpn", shelf)
        assertEquals(listOf("FUNK"), first.kits.map { it.first })
        assertTrue(first.skipped.isEmpty())
        val landed = KitStore.load(first.kits.single().second)
        assertEquals("FUNK", landed.name)
        assertEquals(2, landed.pads.size)
        assertTrue(landed.pads.all { File(first.kits.single().second, it.sampleFile).isFile }, "every sample came along")

        val again = ShelfImport.land(xpn, "FUNK.xpn", shelf)
        assertEquals("FUNK 2", again.kits.single().first, "never over the kit already there")
        assertEquals("FUNK 2", KitStore.load(again.kits.single().second).name, "kit.json follows the folder")
        assertEquals(listOf("FUNK", "FUNK 2"), KitStore.list(shelf).map { it.name })
        assertTrue(shelf.listFiles()!!.none { it.name.startsWith(ShelfImport.STAGING_DIR) }, "staging is gone")
    }

    @Test
    fun `a backup lands every kit it holds`() {
        val source = File(temp, "src2")
        makeKit(source, "ALPHA")
        makeKit(source, "BETA")
        val backup = File(temp, "shelf.zip")
        KitBackup.backup(source, backup)

        val shelf = File(temp, "shelf2")
        val landed = ShelfImport.land(backup, "shelf.zip", shelf)
        assertEquals(listOf("ALPHA", "BETA"), landed.kits.map { it.first }.sorted())
        assertEquals(2, KitStore.list(shelf).size)
    }

    @Test
    fun `an MPC track zipped with its folder lands, a bare one is refused by name`() {
        val source = File(temp, "src3")
        val kitDir = makeKit(source, "NATIVE")
        val kit = KitStore.load(kitDir)
        val card = File(temp, "card")
        Mpc3Exporter.exportTrack(kit, kitDir, card)
        val xtd = File(card, "NATIVE.xtd")
        assertTrue(xtd.isFile)

        val zipped = File(temp, "NATIVE.zip")
        zipDir(card, zipped)
        val shelf = File(temp, "shelf3")
        val landed = ShelfImport.land(zipped, "NATIVE.zip", shelf)
        assertEquals(listOf("NATIVE"), landed.kits.map { it.first })
        assertEquals(1, KitStore.load(landed.kits.single().second).pads.size)

        // The bare container: gzip, read as MPC 3, but its samples live in the folder that did not come.
        val bare = File(temp, "bare.xtd")
        xtd.copyTo(bare)
        val e = assertFailsWith<IllegalArgumentException> { ShelfImport.land(bare, "bare.xtd", File(temp, "shelf4")) }
        assertTrue(e.message!!.isNotBlank())
        assertTrue(File(temp, "shelf4").listFiles()?.none { it.name.startsWith(ShelfImport.STAGING_DIR) } ?: true, "nothing half-landed")
    }

    @Test
    fun `a sound, a stranger, an empty ZIP and a ZIP that escapes are refused in words`() {
        val shelf = File(temp, "shelf5")
        val wav = File(temp, "tone.wav")
        WavWriter.write(wav, Snip(FloatArray(4410) { 0.1f }, 1, 44_100))
        val sound = assertFailsWith<IllegalArgumentException> { ShelfImport.land(wav, "tone.wav", shelf) }
        assertTrue(sound.message!!.contains("a sound"), sound.message)

        val stranger = File(temp, "notes.txt").apply { writeText("not a kit") }
        val nothing = assertFailsWith<IllegalArgumentException> { ShelfImport.land(stranger, "notes.txt", shelf) }
        assertTrue(nothing.message!!.contains("nothing on the shelf can read"), nothing.message)

        val empty = File(temp, "empty.zip")
        ZipOutputStream(empty.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
        }
        val noKit = assertFailsWith<IllegalArgumentException> { ShelfImport.land(empty, "empty.zip", shelf) }
        assertTrue(noKit.message!!.contains("no kit inside"), noKit.message)

        val evil = File(temp, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escaped.xtd"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val escape = assertFailsWith<IllegalArgumentException> { ShelfImport.land(evil, "evil.zip", shelf) }
        assertTrue(escape.message!!.contains("escapes"), escape.message)
        assertTrue(!File(temp, "escaped.xtd").exists() && !File(shelf, "escaped.xtd").exists())
    }

    @Test
    fun `two tracks of the same name in one ZIP both land, the second under its own name`() {
        val source = File(temp, "src6")
        val kitDir = makeKit(source, "NATIVE")
        val kit = KitStore.load(kitDir)
        val twice = File(temp, "twice")
        Mpc3Exporter.exportTrack(kit, kitDir, File(twice, "take1"))
        Mpc3Exporter.exportTrack(kit, kitDir, File(twice, "take2"))

        val zipped = File(temp, "twice.zip")
        zipDir(twice, zipped)
        val shelf = File(temp, "shelf6")
        val landed = ShelfImport.land(zipped, "twice.zip", shelf)
        assertEquals(listOf("NATIVE", "NATIVE 2"), landed.kits.map { it.first }, "neither track aborted the other")
        assertTrue(landed.skipped.isEmpty(), landed.skipped.joinToString())
        assertEquals(listOf("NATIVE", "NATIVE 2"), KitStore.list(shelf).map { it.name })
    }

    @Test
    fun `a container heavier than the ceiling is refused before it is read, bare or zipped`() {
        val source = File(temp, "src6")
        val kitDir = makeKit(source, "HEAVY")
        val card = File(temp, "card6")
        Mpc3Exporter.exportTrack(KitStore.load(kitDir), kitDir, card)
        val xtd = File(card, "HEAVY.xtd")
        val weight = xtd.length()
        assertTrue(weight > 64, "the fixture must weigh something")

        // Bare: refused in words, with the ceiling named, before the reader takes the file whole.
        val shelf = File(temp, "shelf6")
        val bare = assertFailsWith<IllegalArgumentException> { ShelfImport.land(xtd, "HEAVY.xtd", shelf, maxContainerBytes = weight - 1) }
        assertTrue(bare.message!!.contains("too large"), bare.message)
        assertTrue(shelf.listFiles()?.none { it.name.startsWith(ShelfImport.STAGING_DIR) } ?: true, "staging is gone")

        // The same ceiling one byte higher lets it through - the check is on the file's weight, nothing else.
        val ok = ShelfImport.land(xtd, "HEAVY.xtd", shelf, maxContainerBytes = weight)
        assertEquals(listOf("HEAVY"), ok.kits.map { it.first })

        // Zipped with its folder: the container inside is refused by the same rule, so the ZIP held no kit.
        val zipped = File(temp, "HEAVY.zip")
        zipDir(card, zipped)
        val inZip = assertFailsWith<IllegalArgumentException> { ShelfImport.land(zipped, "HEAVY.zip", File(temp, "shelf6b"), maxContainerBytes = weight - 1) }
        assertTrue(inZip.message!!.contains("held no kit") && inZip.message!!.contains("too large"), inZip.message)
    }

    @Test
    fun `the unzip budget is one for the whole ZIP, and the refusal comes before the entry lands`() {
        val zip = File(temp, "two.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (name in listOf("a/one.bin", "a/two.bin")) {
                z.putNextEntry(ZipEntry(name))
                z.write(ByteArray(3_000))
                z.closeEntry()
            }
        }

        // 3 KB entries under a 4 KB budget: the first fits, so the ceiling is not per entry...
        val tight = File(temp, "tight").apply { mkdirs() }
        val e = assertFailsWith<IllegalArgumentException> { ShelfImport.unzipSafely(zip, tight, maxBytes = 4_000) }
        assertTrue(e.message!!.contains("inflates past"), e.message)
        assertEquals(3_000L, File(tight, "a/one.bin").length(), "the first entry landed whole")
        // ...and the second stopped mid-stream, at the budget's edge, never fully written.
        assertTrue(File(tight, "a/two.bin").length() < 3_000L, "the second entry was cut short, not landed then refused")

        // Room for both: every byte lands.
        val roomy = File(temp, "roomy").apply { mkdirs() }
        ShelfImport.unzipSafely(zip, roomy, maxBytes = 6_000)
        assertEquals(3_000L, File(roomy, "a/one.bin").length())
        assertEquals(3_000L, File(roomy, "a/two.bin").length())

        // A ZIP made on Windows separates with backslashes: the folder is kept, not flattened into the name.
        val windows = File(temp, "windows.zip")
        ZipOutputStream(windows.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("b\\three.bin"))
            z.write(ByteArray(10))
            z.closeEntry()
            z.putNextEntry(ZipEntry("b\\..\\four.bin"))
            z.write(ByteArray(10))
            z.closeEntry()
        }
        val win = File(temp, "win").apply { mkdirs() }
        val esc = assertFailsWith<IllegalArgumentException> { ShelfImport.unzipSafely(windows, win, maxBytes = 1_000) }
        assertTrue(esc.message!!.contains("escapes"), esc.message)
        assertEquals(10L, File(win, "b/three.bin").length(), "b\\three.bin landed as b/three.bin")
        assertTrue(!File(win, "b\\three.bin").exists() && !File(temp, "four.bin").exists() && !File(win, "four.bin").exists())
    }
}
