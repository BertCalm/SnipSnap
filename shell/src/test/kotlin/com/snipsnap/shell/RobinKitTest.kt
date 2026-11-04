package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.KitRobin
import com.snipsnap.kit.RobinSettings
import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RobinKitTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("robinkit").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun sha(m: KitBuilderModel, slot: Int): String =
        MessageDigest.getInstance("SHA-256")
            .digest(File(m.kitDir, m.pad(slot)!!.sampleFile).readBytes())
            .joinToString("") { "%02x".format(it) }

    /** 1 kick (0.4 s class), 2 a loop, 3 a 6 s pad. */
    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.update(2) { it.copy(loop = true) }
        val rate = 44100
        m.assign(3, Snip(FloatArray(rate * 6) { (it % 100) / 1000f }, 1, rate), DrumClass.UNKNOWN)
        m.save()
        return m
    }

    @Test
    fun `the kit switch round-robins drums and skips long loops`() {
        val m = model("A")
        val r = RobinKit.on(m)
        assertEquals(listOf(1), r.changed)
        assertEquals(RobinKit.Skip.LOOP, r.skipped[2])
        assertEquals(RobinKit.Skip.LOOP, r.skipped[3])
        assertEquals(RobinSettings(0.6f, 6, 0, true), m.pad(1)!!.robin)
        assertEquals(KitRobin(), m.kit.robin)
    }

    @Test
    fun `the kit switch leaves foreign chains and grids alone`() {
        val m = KitBuilderModel.create("F", File(temp, "F"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.kick(), DrumClass.KICK)
        val frames = com.snipsnap.audio.WavReader.read(File(m.kitDir, m.pad(3)!!.sampleFile)).frameCount.toLong()
        m.update(3) { it.copy(chain = ChainInfo(listOf(0L, frames / 2), cycle = 2, zones = null)) }
        Robin.apply(m, 2, zones = 2)
        m.save()
        val shaGrid = sha(m, 2)
        val shaChain = sha(m, 3)
        val chainBefore = m.pad(3)!!.chain
        val r = RobinKit.on(m)
        assertEquals(RobinKit.Skip.FOREIGN_CHAIN, r.skipped[3])
        assertEquals(RobinKit.Skip.FOREIGN_CHAIN, r.skipped[2])
        assertEquals(listOf(1), r.changed)
        RobinKit.off(m)
        assertEquals(shaGrid, sha(m, 2))
        assertEquals(shaChain, sha(m, 3))
        assertEquals(chainBefore, m.pad(3)!!.chain)
        assertNull(m.kit.robin)
    }

    @Test
    fun `pads that follow the kit re-render and pads with their own setting stay`() {
        val m = KitBuilderModel.create("S", File(temp, "S"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.kick(), DrumClass.KICK)
        Robin.apply(m, 2, RobinSettings(0.2f, 3, 0, followsKit = false))
        Robin.apply(m, 3, RobinSettings(null, 3, 0, followsKit = false))
        m.save()
        RobinKit.on(m)
        val ownSha = sha(m, 2)
        val r = RobinKit.set(m, KitRobin(1f, 8))
        assertEquals(listOf(1), r.changed)
        assertEquals(RobinKit.Skip.OWN_SETTING, r.skipped[2])
        assertEquals(RobinKit.Skip.OWN_SETTING, r.skipped[3])
        assertEquals(8, m.pad(1)!!.chain!!.cycle)
        assertEquals(RobinSettings(1f, 8, 0, true), m.pad(1)!!.robin)
        assertEquals(ownSha, sha(m, 2))
        assertEquals(3, m.pad(2)!!.chain!!.cycle)
        assertEquals(KitRobin(1f, 8), m.kit.robin)
    }

    @Test
    fun `setting the kit back restores identical takes`() {
        val m = model("R")
        RobinKit.on(m)
        val a = KitRobin(0.6f, 6)
        val b = KitRobin(1f, 8)
        RobinKit.set(m, a)
        val first = sha(m, 1)
        RobinKit.set(m, b)
        assertTrue(first != sha(m, 1))
        RobinKit.set(m, a)
        assertEquals(first, sha(m, 1))
    }

    @Test
    fun `on again with equal settings churns nothing`() {
        val m = model("C")
        RobinKit.on(m)
        val before = sha(m, 1)
        val r = RobinKit.on(m)
        assertTrue(r.changed.isEmpty())
        assertEquals(before, sha(m, 1))
    }

    @Test
    fun `kit off brings every original back`() {
        val m = model("O")
        val before = (1..3).associateWith { sha(m, it) }
        RobinKit.on(m)
        assertTrue(before[1] != sha(m, 1))
        val r = RobinKit.off(m)
        assertEquals(listOf(1), r.changed)
        assertEquals(before, (1..3).associateWith { sha(m, it) })
        assertNull(m.pad(1)!!.robin)
        assertNull(m.kit.robin)
    }

    @Test
    fun `the kit switch keeps tonal and note pads in tune and the pad switch still takes them`() {
        val m = KitBuilderModel.create("T", File(temp, "T"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.tom(), DrumClass.TONAL)
        m.assign(3, DrumSynth.tom(), DrumClass.TOM)
        m.update(3) { it.copy(source = it.source + (Spread.NOTE_KEY to "C3")) }
        val r = RobinKit.on(m)
        assertEquals(listOf(1), r.changed)
        assertEquals(RobinKit.Skip.TONAL, r.skipped[2])
        assertEquals(RobinKit.Skip.TONAL, r.skipped[3])
        assertNull(m.pad(2)!!.chain)
        // Deliberately, one pad at a time: still allowed.
        Robin.apply(m, 2, RobinSettings(0.6f, 6, 0, followsKit = false))
        assertTrue(m.pad(2)!!.chain != null)
    }

    @Test
    fun `the ON line never says ON 0 PADS`() {
        val loops = KitBuilderModel.create("L", File(temp, "L"))
        loops.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        loops.update(1) { it.copy(loop = true) }
        loops.assign(2, DrumSynth.tom(), DrumClass.TONAL)
        val none = RobinKit.onLine(RobinKit.on(loops))
        assertTrue("0 PADS" !in none, none)
        assertEquals(Copy.ROBIN_KIT_NONE + " " + Copy.ROBIN_SKIPPED_LOOPS + " " + Copy.ROBIN_SKIPPED_TONAL, none)

        val m = model("Again")
        RobinKit.on(m)
        val again = RobinKit.onLine(RobinKit.on(m))
        assertEquals(Copy.robinKitOn(1) + " " + Copy.ROBIN_SKIPPED_LOOPS, again, "pads already on count as on")
    }

    @Test
    fun `a final save that fails is a stop, not a silent success`() {
        val m = model("Save")
        val dir = m.kitDir
        assertFailsWith<RobinKit.Stopped> {
            RobinKit.runSaving(dir) { model ->
                val r = RobinKit.on(model)
                // kit.json can no longer be written: a folder sits where it goes.
                File(dir, com.snipsnap.kit.KitStore.FILE_NAME).delete()
                File(dir, com.snipsnap.kit.KitStore.FILE_NAME).mkdirs()
                r
            }
        }
    }

    @Test
    fun `a kit that will not open is a plain failure, not a stop`() {
        val m = model("Open")
        File(m.kitDir, com.snipsnap.kit.KitStore.FILE_NAME).writeText("{ not json")
        val e = assertFailsWith<Exception> { RobinKit.onSaving(m.kitDir) }
        assertTrue(e !is RobinKit.Stopped, "nothing was dealt: $e")
    }
}
