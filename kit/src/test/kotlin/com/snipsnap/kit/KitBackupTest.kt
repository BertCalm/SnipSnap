package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KitBackupTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("backup").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun makeKit(root: File, name: String) {
        val dir = File(root, name)
        dir.mkdirs()
        val snip = Cleanup.process(
            Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / 12.0)).toFloat() }, 1, 44_100),
        )
        WavWriter.write(File(dir, "A01_Kick_01.wav"), snip)
        KitStore.save(
            Kit(name, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK))),
            dir,
        )
    }

    /** Entries in the system temp dir whose name starts with one of our prefixes. */
    private fun tempResidue(): Set<String> {
        val sys = File(System.getProperty("java.io.tmpdir"))
        return sys.list()?.filter {
            it.startsWith("kitbackup") || it.startsWith("kitrestore") || it.startsWith("preview")
        }?.toSet() ?: emptySet()
    }

    @Test
    fun `a failed backup or restore leaves no temp residue behind`() {
        val before = tempResidue()

        // Restore of a non-zip throws after the temp dir is created; the
        // finally must still clean it up.
        val notZip = File(temp, "garbage.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        kotlin.test.assertFails { KitBackup.restore(notZip, File(temp, "r1")) }

        // Backup where every kit is blocked throws after temp work; same.
        val root = File(temp, "allbroken")
        makeKit(root, "Broken")
        File(root, "Broken/A01_Kick_01.wav").delete()
        kotlin.test.assertFails { KitBackup.backup(root, File(temp, "b1.zip")) }

        assertEquals(before, tempResidue(), "a failed operation left a temp dir behind")
    }

    @Test
    fun `backup then restore round-trips every clean kit and names the broken one`() {
        val root = File(temp, "kits")
        makeKit(root, "Alpha Kit")
        makeKit(root, "Beta Kit")
        makeKit(root, "Broken Kit")
        File(root, "Broken Kit/A01_Kick_01.wav").delete() // preflight FAIL

        val backup = KitBackup.backup(root, File(temp, "backup.zip"))
        assertEquals(listOf("Alpha Kit", "Beta Kit"), backup.packed.sorted())
        assertTrue("Broken Kit" in backup.skipped, "blocked kits are skipped and named")
        assertTrue(backup.skipped["Broken Kit"]!!.isNotBlank())

        // The "new phone": restore into an empty root.
        val fresh = File(temp, "fresh")
        val restored = KitBackup.restore(backup.file, fresh)
        assertEquals(2, restored.kits.size)
        assertTrue(restored.skipped.isEmpty())
        for (r in restored.kits) {
            assertEquals(1, r.kit.pads.size)
            assertTrue(File(r.directory, "A01_Kick_01.wav").isFile)
            assertEquals(KitStore.load(r.directory), r.kit)
        }
        // Byte-identical samples through the double zip.
        assertTrue(
            File(root, "Alpha Kit/A01_Kick_01.wav").readBytes()
                .contentEquals(File(fresh, "Alpha Kit/A01_Kick_01.wav").readBytes()),
        )
    }

    @Test
    fun `restore shares one write budget across every kit in the backup`() {
        val root = File(temp, "kits2")
        for (name in listOf("Alpha", "Beta", "Gamma")) makeKit(root, name)
        val backup = KitBackup.backup(root, File(temp, "backup2.zip"))
        val oneKitBytes = File(root, "Alpha/A01_Kick_01.wav").length()

        // Each kit's own import writes at least oneKitBytes of raw sample,
        // whatever the archive compressed it to - so three kits guarantee
        // at least 3x oneKitBytes of spend against the shared budget. A
        // budget of 2x can cover any one kit many times over (kit.json plus
        // a single small WAV is nowhere near oneKitBytes of overhead) but
        // cannot possibly cover three - proving the budget is shared across
        // kits, not reset per kit, without depending on the archive's exact
        // compression ratio. MAX_KITS alone only bounds entry count.
        val tight = kotlin.test.assertFailsWith<com.snipsnap.mpc3.LimitedRead.TooLargeException> {
            KitBackup.restore(
                backup.file,
                File(temp, "tight-restore"),
                budget = XpnImporter.WriteBudget(2 * oneKitBytes),
            )
        }
        assertTrue(tight.message!!.contains("MB", ignoreCase = true), tight.message!!)

        // The same backup, roomy budget: still round-trips every kit.
        val restored = KitBackup.restore(backup.file, File(temp, "roomy-restore"), budget = XpnImporter.WriteBudget(10 * oneKitBytes))
        assertEquals(3, restored.kits.size)
    }

    @Test
    fun `a corrupt or unreadable xpn entry is skipped, the rest of the backup still restores`() {
        val root = File(temp, "kits3")
        makeKit(root, "Good One")
        makeKit(root, "Good Two")
        val goodOneDir = File(root, "Good One")
        val goodTwoDir = File(root, "Good Two")
        val goodOneXpn = File(temp, "Good One.xpn")
        val goodTwoXpn = File(temp, "Good Two.xpn")
        XpnPackager.write(KitStore.load(goodOneDir), goodOneDir, goodOneXpn, Exporters.defaultMeta(KitStore.load(goodOneDir)))
        XpnPackager.write(KitStore.load(goodTwoDir), goodTwoDir, goodTwoXpn, Exporters.defaultMeta(KitStore.load(goodTwoDir)))

        // Two different shapes of "bad entry" - both must be skipped, not
        // abort the restore: a readable zip whose program XpnImporter
        // refuses by name (a keygroup, the same shape XpnImporterTest's
        // own malformed-input tests use - this is what a SafeXml-style
        // reader/writer disagreement ultimately surfaces as too, since
        // XpnImporter.importProgram wraps any parse failure into the same
        // IllegalArgumentException), and an entry that is not a readable
        // zip at all (simply corrupt bytes).
        val keygroupXpn = File(temp, "Keygroup Kit.xpn")
        java.util.zip.ZipOutputStream(keygroupXpn.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("Keygroup Kit.xpm"))
            zip.write(
                "<MPCVObject><Program type=\"Keygroup\"><ProgramName>Keygroup Kit</ProgramName></Program></MPCVObject>".toByteArray(),
            )
            zip.closeEntry()
        }
        val garbageXpn = File(temp, "Garbage.xpn").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }

        val crafted = File(temp, "crafted-backup.zip")
        java.util.zip.ZipOutputStream(crafted.outputStream()).use { zip ->
            for ((name, src) in listOf(
                "Good One.xpn" to goodOneXpn,
                "Good Two.xpn" to goodTwoXpn,
                "Keygroup Kit.xpn" to keygroupXpn,
                "Garbage.xpn" to garbageXpn,
            )) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                src.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        val fresh = File(temp, "fresh3")
        val result = KitBackup.restore(crafted, fresh)
        assertEquals(listOf("Good One", "Good Two"), result.kits.map { it.kit.name }.sorted(), "both good kits landed")
        assertEquals(setOf("Keygroup Kit.xpn", "Garbage.xpn"), result.skipped.keys, "both bad entries are named, not silently dropped")
        assertTrue("keygroup" in result.skipped.getValue("Keygroup Kit.xpn"), result.skipped.toString())
        assertTrue(File(fresh, "Good One").isDirectory && File(fresh, "Good Two").isDirectory, "the good kits still landed on disk")
        assertTrue(!File(fresh, "Keygroup Kit").exists() && !File(fresh, "Garbage").exists(), "no debris for the bad entries")
    }

    @Test
    fun `a backup where every entry is bad throws, naming every reason - not a silent no-op`() {
        // Per-entry degrade must not quietly turn into a restore that
        // lands nothing but still reports success to an exit-code-only
        // caller (BackupCommand, a script).
        val keygroupXpn = File(temp, "Keygroup Kit.xpn")
        java.util.zip.ZipOutputStream(keygroupXpn.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("Keygroup Kit.xpm"))
            zip.write(
                "<MPCVObject><Program type=\"Keygroup\"><ProgramName>Keygroup Kit</ProgramName></Program></MPCVObject>".toByteArray(),
            )
            zip.closeEntry()
        }
        val garbageXpn = File(temp, "Garbage.xpn").apply { writeBytes(byteArrayOf(9, 9, 9)) }

        val crafted = File(temp, "all-bad-backup.zip")
        java.util.zip.ZipOutputStream(crafted.outputStream()).use { zip ->
            for ((name, src) in listOf("Keygroup Kit.xpn" to keygroupXpn, "Garbage.xpn" to garbageXpn)) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                src.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        val err = kotlin.test.assertFailsWith<IllegalArgumentException> {
            KitBackup.restore(crafted, File(temp, "all-bad-out"))
        }
        assertTrue("Keygroup Kit.xpn" in err.message!! && "Garbage.xpn" in err.message!!, err.message!!)
    }
}
