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
