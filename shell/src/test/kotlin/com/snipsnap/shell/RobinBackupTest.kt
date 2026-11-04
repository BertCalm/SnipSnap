package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitBackup
import com.snipsnap.kit.KitRobin
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.RobinSettings
import com.snipsnap.kit.XpnImporter
import com.snipsnap.kit.XpnPackager
import com.snipsnap.kit.isRobinChain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Final review #2: a robin pad survives the shelf backup and an .xpn share with every take and its original. */
class RobinBackupTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("robinbackup").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private class Shelf(val root: File, val dir: File, val originals: Map<Int, ByteArray>, val chains: Map<Int, ByteArray>)

    /** Kit "Drums": pads 1 and 2 round-robined by the kit switch at F, six takes. */
    private fun shelf(): Shelf {
        val root = File(temp, "shelf")
        val m = KitBuilderModel.create("Drums", File(root, "Drums"))
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(2, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        val originals = (1..2).associateWith { File(m.kitDir, m.pad(it)!!.sampleFile).readBytes() }
        RobinKit.on(m)
        m.save()
        val chains = (1..2).associateWith { File(m.kitDir, m.pad(it)!!.sampleFile).readBytes() }
        return Shelf(root, m.kitDir, originals, chains)
    }

    private fun assertSameRobins(shelf: Shelf, landed: File) {
        val before = KitStore.load(shelf.dir)
        val after = KitStore.load(landed)
        assertEquals(KitRobin(), after.robin, "the kit switch reads ON")
        for (slot in 1..2) {
            val pad = after.pad(slot)!!
            assertTrue(pad.isRobinChain, "pad $slot is a robin pad again")
            assertEquals(RobinSettings(0.6f, 6, 0, followsKit = true), pad.robin)
            assertEquals(before.pad(slot)!!.chain, pad.chain, "all six takes, same boundaries")
            assertContentEquals(shelf.chains.getValue(slot), File(landed, pad.sampleFile).readBytes(), "pad $slot chain bytes")
        }
        // OFF gives back each untouched original.
        val m = KitBuilderModel.open(landed)
        RobinKit.off(m)
        for (slot in 1..2) {
            assertNull(m.pad(slot)!!.chain)
            assertContentEquals(shelf.originals.getValue(slot), File(landed, m.pad(slot)!!.sampleFile).readBytes(), "pad $slot original")
        }
    }

    @Test
    fun `a shelf backup brings round robin back whole`() {
        val shelf = shelf()
        val backup = KitBackup.backup(shelf.root, File(temp, "backup.zip"))
        val restored = KitBackup.restore(backup.file, File(temp, "restored"), robin = Robin.dealer)
        assertTrue(restored.skipped.isEmpty(), "${restored.skipped}")
        assertSameRobins(shelf, restored.kits.single().directory)
    }

    @Test
    fun `an xpn share brings round robin back whole`() {
        val shelf = shelf()
        val kit = KitStore.load(shelf.dir)
        val xpn = XpnPackager.write(kit, shelf.dir, File(temp, "Drums.xpn"), Exporters.defaultMeta(kit))
        val landed = XpnImporter.import(xpn, File(temp, "in"), robin = Robin.dealer)
        assertSameRobins(shelf, landed.directory)
    }
}
