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
}
