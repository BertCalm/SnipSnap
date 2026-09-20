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
        assertEquals(2, restored.size)
        for (r in restored) {
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
        assertEquals(3, restored.size)
    }


    @Test
    fun `an extra rides at the archive's root, reads back bounded, and restore leaves it to whoever packed it`() {
        val root = File(temp, "kits3")
        makeKit(root, "Alpha")
        val presets = File(root, "presets.json").apply { writeText("{\"version\": 1, \"presets\": []}") }
        val backup = KitBackup.backup(
            root,
            File(temp, "backup3.zip"),
            extras = mapOf("presets.json" to presets, "absent.json" to File(root, "absent.json")),
        )
        assertEquals(listOf("Alpha"), backup.packed)
        assertEquals(listOf("presets.json"), backup.extras, "the file that was there rode; the one that was not is simply absent")

        val back = KitBackup.extra(backup.file, "presets.json")
        assertTrue(presets.readBytes().contentEquals(back!!), "byte for byte")
        assertEquals(null, KitBackup.extra(backup.file, "absent.json"), "an archive without the entry reads as none")
        val heavy = kotlin.test.assertFailsWith<com.snipsnap.mpc3.LimitedRead.TooLargeException> {
            KitBackup.extra(backup.file, "presets.json", maxBytes = presets.length() - 1)
        }
        assertTrue(heavy.message!!.contains("presets.json"), heavy.message)

        // Restore reads the .xpn entries only: the extra is not a kit and lands nowhere.
        val fresh = File(temp, "fresh3")
        assertEquals(listOf("Alpha"), KitBackup.restore(backup.file, fresh).map { it.kit.name })
        assertTrue(!File(fresh, "presets.json").exists(), "restore leaves the extra to whoever packed it")

        // Never an extra that would read as a kit, or one that would nest - refused before a byte is written.
        kotlin.test.assertFails { KitBackup.backup(root, File(temp, "bad1.zip"), extras = mapOf("Sneaky.xpn" to presets)) }
        kotlin.test.assertFails { KitBackup.backup(root, File(temp, "bad2.zip"), extras = mapOf("deep/presets.json" to presets)) }
        assertTrue(!File(temp, "bad1.zip").exists() && !File(temp, "bad2.zip").exists())

        // Without extras, the archive is what it always was: the kits and nothing else.
        val plain = KitBackup.backup(root, File(temp, "plain3.zip"))
        assertTrue(plain.extras.isEmpty())
        java.util.zip.ZipFile(plain.file).use { zip -> assertEquals(listOf("Alpha.xpn"), zip.entries().toList().map { it.name }) }
    }
}
