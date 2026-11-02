package com.snipsnap.shell

import com.snipsnap.kit.ExpansionMeta
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpansionStoreTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("expansions").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val knobs = ArtKnobs(KitArt.Style.FIELD, Palettes.RISO, 2, Mark.Variant.SYMBOL)

    private fun store() = ExpansionStore(File(temp, "expansions"), Random(11))

    @Test
    fun `create, add, remove, rename and delete round trip through disk`() {
        val store = store()
        val born = store.create("Dusty Crate", "k1", knobs)
        assertEquals(6, born.id.length)
        assertEquals("app.snipsnap.dustycrate.${born.id}", born.identifier)
        assertEquals(listOf("k1"), born.memberKitIds)
        assertEquals("k1", born.seedKitId)
        assertEquals(knobs, born.knobs)
        assertEquals("SnipSnap", born.manufacturer)

        store.add(born.id, "k2")
        store.add(born.id, "k2")
        assertEquals(listOf("k1", "k2"), store.get(born.id)!!.memberKitIds, "adding twice is one membership")
        assertEquals(store.get(born.id), ExpansionStore(File(temp, "expansions")).get(born.id), "a fresh store reads the same")

        val renamed = store.rename(born.id, "Dusty Crate II")
        assertEquals("Dusty Crate II", renamed.title)
        assertEquals(born.identifier, renamed.identifier, "the identifier is fixed at birth")

        val left = store.remove(born.id, "k1")
        assertEquals(listOf("k2"), left.memberKitIds)
        assertNull(left.seedKitId, "the seed left with its kit")

        val art = ArtKnobs(KitArt.Style.RIDGES, Palettes.TIDE, null, Mark.Variant.STACKED)
        assertEquals(art, store.setArt(born.id, art, "k2").knobs)
        assertEquals("k2", store.get(born.id)!!.seedKitId)
        assertEquals(listOf(born.id), store.containing("k2").map { it.id })

        assertTrue(store.delete(born.id))
        assertNull(store.get(born.id))
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `a title another expansion has is refused, whatever its case`() {
        val store = store()
        val night = store.create("Night Shift", "k1", knobs)
        val other = store.create("Day Shift", "k2", knobs)
        assertEquals(ExpansionStore.NameCheck.Taken, store.checkTitle("night shift"))
        assertEquals(ExpansionStore.NameCheck.Ok, store.checkTitle("Night Shift", exceptId = night.id))
        assertEquals(ExpansionStore.NameCheck.Unsafe, store.checkTitle("A/B"))
        assertEquals(ExpansionStore.NameCheck.Unsafe, store.checkTitle("Ends."))
        assertEquals(ExpansionStore.NameCheck.Unsafe, store.checkTitle("   "))
        assertFailsWith<IllegalArgumentException> { store.rename(other.id, "NIGHT SHIFT") }
        assertFailsWith<IllegalArgumentException> { store.create("night shift", "k3", knobs) }
    }

    @Test
    fun `the identifier is fixed at birth and safe for the MPC`() {
        assertEquals("app.snipsnap.cafbeats.a1b2c3", ExpansionStore.identifier("Café Beats!", "a1b2c3"))
        assertEquals("app.snipsnap.pack.zz9zz9", ExpansionStore.identifier("!!!", "zz9zz9"))
        val store = store()
        for (title in listOf("Cafe Beats", "Night Shift", "808 & Friends", "___")) {
            val e = store.create(title, "k", knobs)
            ExpansionMeta(e.title, identifier = e.identifier)
        }
    }

    @Test
    fun `prune drops kits that are gone for good and keeps the rest`() {
        val store = store()
        val a = store.create("Alpha", "k1", knobs)
        val b = store.create("Bravo", "k1", knobs)
        store.add(b.id, "k2")
        assertEquals(2, store.prune(setOf("k2")))
        assertEquals(emptyList(), store.get(a.id)!!.memberKitIds)
        assertEquals(listOf("k2"), store.get(b.id)!!.memberKitIds)
        assertEquals("Alpha", store.get(a.id)!!.title, "an emptied expansion keeps its name")
        assertEquals(knobs, store.get(a.id)!!.knobs, "and its knobs")
    }

    @Test
    fun `a corrupt expansion json is skipped, not fatal`() {
        val store = store()
        val good = store.create("Good", "k1", knobs)
        File(store.root, "broken").apply { mkdirs() }.resolve(ExpansionStore.FILE).writeText("not json")
        val later = File(store.root, "later").apply { mkdirs() }.resolve(ExpansionStore.FILE)
        later.writeText(File(store.root, "${good.id}/${ExpansionStore.FILE}").readText().replace("\"version\": 1", "\"version\": 2"))
        assertEquals(listOf(good.id), store.list().map { it.id })
    }
}
