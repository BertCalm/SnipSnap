package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.RobinSettings
import com.snipsnap.kit.isRobinChain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Final review: a robin pad always finds its way back - VERSIONS, an empty bin, the bin's clock. */
class RobinRecoveryTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("robinrecovery").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(2, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        return m
    }

    private fun bytes(m: KitBuilderModel, slot: Int) = File(m.kitDir, m.pad(slot)!!.sampleFile).readBytes()

    private val on = RobinSettings(0.6f, 6, 0, followsKit = true)

    // ---- #5: EMPTY THE BIN must not wedge a robin pad ----

    @Test
    fun `undo after the bin was emptied brings the original back from take one`() {
        val m = model("Empty")
        val original = bytes(m, 1)
        Robin.apply(m, 1, on)
        m.emptyBin()

        Robin.undo(m, 1)

        val pad = m.pad(1)!!
        assertNull(pad.chain)
        assertNull(pad.robin)
        assertContentEquals(original, bytes(m, 1), "take one is the original, byte for byte")
    }

    @Test
    fun `kit off after the bin was emptied turns every pad off`() {
        val m = model("EmptyKit")
        val one = bytes(m, 1)
        val two = bytes(m, 2)
        RobinKit.on(m)
        m.emptyBin()

        val r = RobinKit.off(m)

        assertEquals(listOf(1, 2), r.changed)
        assertTrue(m.kit.pads.none { it.chain != null })
        assertContentEquals(one, bytes(m, 1))
        assertContentEquals(two, bytes(m, 2))
    }

    // ---- #1: VERSIONS restore of a take saved with round robin on ----

    @Test
    fun `restoring a take saved with round robin on re-deals the takes`() {
        val m = model("Versions")
        Robin.apply(m, 1, on)
        m.save()
        val chainBytes = bytes(m, 1)
        val chainBoundaries = m.pad(1)!!.chain!!.boundaries
        Robin.undo(m, 1)
        m.save() // archives the robin-on kit.json as the newest take
        val robinTake = m.takes().last { KitStore.read(it).pad(1)!!.isRobinChain }

        m.restoreTake(robinTake)

        val pad = m.pad(1)!!
        assertTrue(pad.isRobinChain)
        assertEquals(on, pad.robin)
        val frames = WavReader.read(File(m.kitDir, pad.sampleFile)).frameCount.toLong()
        assertTrue(pad.chain!!.boundaries.last() < frames, "every boundary sits inside the WAV")
        assertEquals(chainBoundaries, pad.chain!!.boundaries)
        assertContentEquals(chainBytes, bytes(m, 1), "the same original and settings deal the same chain")
        // Every take's window is in range, and OFF still works.
        for (i in 0 until pad.chain!!.cycle) pad.chain!!.window(i, frames).also { assertTrue(it.last < frames) }
        Robin.undo(m, 1)
        assertNull(m.pad(1)!!.chain)
    }

    @Test
    fun `restoring a take whose chain still fits leaves the file alone`() {
        val m = model("Fits")
        Robin.apply(m, 1, on)
        m.save()
        m.update(2) { it.copy(level = 0.5f) }
        m.save()
        val chainBytes = bytes(m, 1)
        val binRows = m.binContents().size

        m.restoreTake(m.takes().last())

        assertContentEquals(chainBytes, bytes(m, 1))
        assertEquals(binRows, m.binContents().size, "nothing re-dealt, nothing binned")
    }

    // ---- HIT: a take's window never reads past the WAV ----

    @Test
    fun `a take window past the end of the audio is clamped, never thrown`() {
        val whole = Snip(FloatArray(200) { it / 200f }, 2, 44_100) // 100 frames
        val chain = ChainInfo(listOf(0L, 100L, 160L), cycle = 3)

        assertEquals(100, Robin.take(whole, chain, 0)!!.frameCount)
        assertNull(Robin.take(whole, chain, 1), "a window wholly past the end has nothing to play")
        assertNull(Robin.take(whole, chain, 2))
        val short = ChainInfo(listOf(0L, 60L, 160L), cycle = 3)
        assertEquals(40, Robin.take(whole, short, 1)!!.frameCount, "a window running off the end is cut at the end")
    }

    // ---- #4: the bin keeps what a lifted robin pad needs ----

    @Test
    fun `an edited robin pad keeps its pre-edit original past thirty days`() {
        val m = model("Clock")
        val original = bytes(m, 1)
        Robin.apply(m, 1, on)
        m.treatPad(1, "crushed")
        val later = System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000

        val rows = m.binContents().filter { it.originalName == m.pad(1)!!.sampleFile }
        assertEquals(2, rows.size, "the pre-edit original and the treated take")
        val holds = m.binHolds(nowMillis = later).filter { it.entry.originalName == m.pad(1)!!.sampleFile }
        assertTrue(holds.all { it.keptFor == 1 }, "both rows are kept for pad 1: $holds")

        m.purgeBin(nowMillis = later)
        Robin.undo(m, 1)
        m.untreatPad(1)
        assertContentEquals(original, bytes(m, 1))
    }

    @Test
    fun `an unedited robin pad keeps only its own original`() {
        val m = model("Plain")
        m.treatPad(1, "crushed")
        m.untreatPad(1) // nothing left in the bin for pad 1
        Robin.apply(m, 1, on)
        m.update(1) { it } // no-op
        val pad = m.pad(1)!!
        val holds = m.binHolds().filter { it.entry.originalName == pad.sampleFile }
        assertEquals(1, holds.size)
        assertNotNull(holds.single().keptFor)
    }
}
