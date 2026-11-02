package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitIds
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpansionMembersTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("members").toFile()
    private val shelf = File(temp, "Kits").apply { mkdirs() }
    private val bin = File(shelf, ".bin").apply { mkdirs() }
    private val store = ExpansionStore(File(temp, "expansions"), Random(5))
    private val knobs = ArtKnobs(KitArt.Style.BLOOM, Palettes.VENT, null, Mark.Variant.STACKED)

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** A kit on the shelf with an id, the way joining an expansion leaves it. */
    private fun kit(name: String, seed: Int): Pair<File, String> {
        val dir = File(shelf, name).apply { mkdirs() }
        KitStore.save(Kit(name, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav"))), dir)
        return dir to KitIds.ensure(dir, Random(seed))
    }

    /** What the shelf's DELETE does: the folder moves into the bin. */
    private fun binIt(dir: File): File = File(bin, "${dir.name}-1700000000000").also { assertTrue(dir.renameTo(it)) }

    @Test
    fun `membership survives rename, bin and restore`() {
        val (d1, k1) = kit("One", 1)
        val (d2, k2) = kit("Two", 2)
        val (_, k3) = kit("Three", 3)
        val e = store.create("Crate", k2, knobs).let { store.add(it.id, k1); store.add(it.id, k3) }
        assertEquals(listOf(k2, k1, k3), e.memberKitIds)

        // Rename: a new folder and a new name, the same id.
        val renamed = File(shelf, "Two Renamed")
        assertTrue(d2.renameTo(renamed))
        KitStore.save(KitStore.load(renamed).copy(name = "Two Renamed"), renamed)
        var index = ExpansionMembers.index(shelf)
        assertEquals(listOf(k2, k1, k3), ExpansionMembers.present(e, index).map { it.kitId })
        assertEquals("Two Renamed", ExpansionMembers.present(e, index).first().kit.name)

        // Bin the seed: hidden from the expansion, and the first remaining member seeds.
        val binned = binIt(renamed)
        index = ExpansionMembers.index(shelf)
        assertEquals(listOf(k1, k3), ExpansionMembers.present(e, index).map { it.kitId })
        assertEquals(k1, ExpansionMembers.seed(e, index)!!.kitId)

        // Restore: back in, in birth order, and seeding again.
        assertTrue(binned.renameTo(renamed))
        index = ExpansionMembers.index(shelf)
        assertEquals(listOf(k2, k1, k3), ExpansionMembers.present(e, index).map { it.kitId })
        assertEquals(k2, ExpansionMembers.seed(e, index)!!.kitId)
        assertTrue(d1.isDirectory)
    }

    @Test
    fun `permanent deletion leaves every expansion`() {
        val (d1, k1) = kit("One", 1)
        val (_, k2) = kit("Two", 2)
        val a = store.create("Alpha", k1, knobs)
        val b = store.create("Bravo", k1, knobs).also { store.add(it.id, k2) }
        val binned = binIt(d1)
        assertEquals(setOf(k1, k2), ExpansionMembers.liveIds(shelf, bin), "a binned kit is still live: it can come back")
        assertEquals(0, store.prune(ExpansionMembers.liveIds(shelf, bin)!!))
        assertTrue(binned.deleteRecursively())
        assertEquals(2, store.prune(ExpansionMembers.liveIds(shelf, bin)!!))
        assertEquals(emptyList(), store.get(a.id)!!.memberKitIds)
        assertEquals(listOf(k2), store.get(b.id)!!.memberKitIds)
        assertNull(ExpansionMembers.seed(store.get(a.id)!!, ExpansionMembers.index(shelf)), "an empty expansion has no seed")
    }

    @Test
    fun `a kit that will not read keeps its memberships`() {
        val (_, k1) = kit("One", 1)
        val (d2, k2) = kit("Two", 2)
        val e = store.create("Pair", k1, knobs).let { store.add(it.id, k2) }
        File(d2, KitStore.FILE_NAME).writeText("not json")
        assertNull(ExpansionMembers.liveIds(shelf, bin), "a shelf that did not read whole cannot say what is gone")
        assertNull(ExpansionMembers.pruneSafely(store, shelf, bin))
        assertEquals(listOf(k1, k2), store.get(e.id)!!.memberKitIds)
    }

    @Test
    fun `a kit without an id is a member of nothing`() {
        val (_, k1) = kit("One", 1)
        val plain = File(shelf, "Plain").apply { mkdirs() }
        KitStore.save(Kit("Plain", listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav"))), plain)
        val index = ExpansionMembers.index(shelf)
        assertEquals(setOf(k1), index.keys)
    }
}
