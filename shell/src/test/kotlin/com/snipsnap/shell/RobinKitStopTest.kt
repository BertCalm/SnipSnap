package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitRobin
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.isRobinChain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A kit-wide round robin that stops part way (pad 3 fails after pads 1 and 2 are done) must leave
 * kit.json describing exactly the files on disk, and OFF afterwards must still bring every original
 * back. Each case checks EVERY pad against its WAV, not just the failing one.
 */
class RobinKitStopTest {
    private val temp: File = java.nio.file.Files.createTempDirectory("robinkitstop").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun kit(name: String): File {
        val m = KitBuilderModel.create(name, File(temp, name))
        for (slot in 1..3) m.assign(slot, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        return m.kitDir
    }

    private fun bytes(dir: File): Map<Int, ByteArray> {
        val k = KitStore.load(dir)
        return k.pads.associate { it.slot to File(dir, it.sampleFile).readBytes() }
    }

    /**
     * The bin row holding [slot]'s original goes missing and its chain WAV stops reading, so neither the
     * bin nor take one can give the original back and lifting that pad's chain throws (a lost row alone
     * no longer does: undo then cuts take one from the chain). The returned call puts both back.
     */
    private fun loseBinnedOriginal(dir: File, slot: Int): () -> Unit {
        val model = KitBuilderModel.open(dir)
        val name = model.pad(slot)!!.sampleFile
        val rows = model.binContents().filter { it.originalName == name }
        assertTrue(rows.isNotEmpty(), "the bin holds pad $slot's original")
        val saved = rows.map { it.file to it.file.readBytes() }
        rows.forEach { it.file.delete() }
        val chain = File(dir, name)
        val chainBytes = chain.readBytes()
        chain.writeBytes("not a wav".toByteArray())
        return {
            saved.forEach { (f, b) -> f.writeBytes(b) }
            chain.writeBytes(chainBytes)
        }
    }

    /** kit.json agrees with the WAV for every pad: a chain's boundaries fit its file and match its cycle; a plain pad is the original. */
    private fun assertConsistent(dir: File, originals: Map<Int, ByteArray>) {
        val kit = KitStore.load(dir)
        for (pad in kit.pads) {
            val file = File(dir, pad.sampleFile)
            val chain = pad.chain
            if (chain == null) {
                assertNull(pad.robin, "plain pad ${pad.slot} carries robin settings")
                assertTrue(originals.getValue(pad.slot).contentEquals(file.readBytes()), "plain pad ${pad.slot} isn't its original")
            } else {
                assertTrue(pad.isRobinChain, "pad ${pad.slot} chain without robin settings")
                val frames = WavReader.read(file).frameCount.toLong()
                assertEquals(0L, chain.boundaries.first(), "pad ${pad.slot}")
                assertEquals(chain.cycle, chain.boundaries.size, "pad ${pad.slot} boundaries vs cycle")
                assertEquals(pad.robin!!.takes, chain.cycle, "pad ${pad.slot} takes vs cycle")
                assertTrue(chain.boundaries.zipWithNext().all { (a, b) -> a < b }, "pad ${pad.slot} boundaries ascend")
                assertTrue(chain.boundaries.last() < frames, "pad ${pad.slot} boundaries inside its $frames frames")
            }
        }
    }

    private fun assertOffRestores(dir: File, originals: Map<Int, ByteArray>) {
        RobinKit.offSaving(dir)
        val kit = KitStore.load(dir)
        assertNull(kit.robin)
        for (pad in kit.pads) {
            assertNull(pad.chain)
            assertTrue(originals.getValue(pad.slot).contentEquals(File(dir, pad.sampleFile).readBytes()), "pad ${pad.slot} original")
        }
    }

    @Test
    fun `a stopped ON leaves the dealt pads described and OFF undoes them`() {
        val dir = kit("on")
        val originals = bytes(dir)
        val pad3 = KitStore.load(dir).pad(3)!!
        File(dir, pad3.sampleFile).delete()
        assertFailsWith<RobinKit.Stopped> { RobinKit.onSaving(dir) }
        val kit = KitStore.load(dir)
        assertNotNull(kit.robin, "the switch reads ON so OFF can undo what was dealt")
        assertTrue(kit.pad(1)!!.isRobinChain && kit.pad(2)!!.isRobinChain)
        File(dir, pad3.sampleFile).writeBytes(originals.getValue(3))
        assertConsistent(dir, originals)
        assertOffRestores(dir, originals)
    }

    @Test
    fun `a stopped SET leaves every pad consistent and OFF restores`() {
        val dir = kit("set")
        val originals = bytes(dir)
        RobinKit.onSaving(dir)
        val lost = loseBinnedOriginal(dir, 3)
        assertFailsWith<RobinKit.Stopped> { RobinKit.setSaving(dir, KitRobin(1f, 8)) }
        lost()
        assertEquals(KitRobin(1f, 8), KitStore.load(dir).robin)
        assertConsistent(dir, originals)
        assertOffRestores(dir, originals)
    }

    @Test
    fun `a stopped OFF leaves every pad consistent and a second OFF finishes`() {
        val dir = kit("off")
        val originals = bytes(dir)
        RobinKit.onSaving(dir)
        val lost = loseBinnedOriginal(dir, 3)
        assertFailsWith<RobinKit.Stopped> { RobinKit.offSaving(dir) }
        lost()
        val mid = KitStore.load(dir)
        assertTrue(mid.pad(3)!!.isRobinChain, "pad 3 is still a chain")
        assertConsistent(dir, originals)
        assertOffRestores(dir, originals)
    }
}
