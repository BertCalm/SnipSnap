package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitBackup
import com.snipsnap.kit.KitIds
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpansionBackupTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("expbackup").toFile()
    private val knobs = ArtKnobs(KitArt.Style.RIDGES, Palettes.TIDE, null, Mark.Variant.SYMBOL)

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun kit(shelf: File, name: String, playable: Boolean = true): String {
        val dir = File(shelf, name).apply { mkdirs() }
        val file = if (playable) "A01_Hit_01.wav" else "missing.wav"
        if (playable) WavWriter.write(File(dir, file), Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / 7.0)).toFloat() }, 1, 44_100)))
        KitStore.save(Kit(name, listOf(KitPad(slot = 1, sampleFile = file, drumClass = DrumClass.PERC))), dir)
        return KitIds.ensure(dir)
    }

    /** BACKUP as the phone does it: every kit, plus the expansions file at the root. */
    private fun backup(shelf: File, store: ExpansionStore, name: String = "shelf.zip"): File {
        val out = File(temp, name)
        val exp = File(temp, "$name.${ExpansionBackup.FILE_NAME}")
        val extras = if (ExpansionBackup.write(store, shelf, exp)) mapOf(ExpansionBackup.FILE_NAME to exp) else emptyMap()
        KitBackup.backup(shelf, out, extras = extras)
        return out
    }

    @Test
    fun `a backup carries every expansion and a restore rebuilds it around the landed kits`() {
        val shelf = File(temp, "phone-a/Kits")
        val store = ExpansionStore(File(temp, "phone-a/expansions"), Random(1))
        val one = kit(shelf, "ONE")
        val two = kit(shelf, "TWO")
        val e = store.create("NIGHT SHIFT", one, knobs).let { store.add(it.id, two) }.let { store.setArt(it.id, knobs, two) }
        val zip = backup(shelf, store)

        val newShelf = File(temp, "phone-b/Kits")
        val newStore = ExpansionStore(File(temp, "phone-b/expansions"))
        val landed = ShelfImport.land(zip, "shelf.zip", newShelf, expansions = newStore)
        assertEquals(1, landed.expansions)
        val back = newStore.list().single()
        assertEquals("NIGHT SHIFT", back.title)
        assertEquals(e.identifier, back.identifier, "the MPC sees the same pack")
        assertEquals(knobs, back.knobs)
        val byName = landed.kits.associate { (name, dir) -> name to KitIds.read(dir) }
        assertEquals(listOf(byName["ONE"], byName["TWO"]), back.memberKitIds, "the landed kits, in the order they joined")
        assertEquals(byName["TWO"], back.seedKitId)
    }

    @Test
    fun `restoring onto the phone it came from adds nothing twice`() {
        val shelf = File(temp, "phone/Kits")
        val store = ExpansionStore(File(temp, "phone/expansions"), Random(2))
        val one = kit(shelf, "ONE")
        val e = store.create("CRATE", one, knobs)
        val zip = backup(shelf, store)
        val landed = ShelfImport.land(zip, "shelf.zip", shelf, expansions = store)
        assertEquals(0, landed.expansions)
        assertEquals(listOf(e), store.list(), "the expansion already here is left as it is")
        assertTrue(landed.skipped.any { it.contains("CRATE") }, "and the restore says why it was not added: ${landed.skipped}")
    }

    @Test
    fun `a title another expansion holds lands under a fresh one`() {
        val shelf = File(temp, "a/Kits")
        val store = ExpansionStore(File(temp, "a/expansions"), Random(3))
        store.create("CRATE", kit(shelf, "ONE"), knobs)
        val zip = backup(shelf, store)

        val other = ExpansionStore(File(temp, "b/expansions"), Random(4))
        val otherShelf = File(temp, "b/Kits")
        other.create("CRATE", kit(otherShelf, "THEIRS"), knobs)
        val landed = ShelfImport.land(zip, "shelf.zip", otherShelf, expansions = other)
        assertEquals(1, landed.expansions)
        assertEquals(listOf("CRATE", "CRATE 2"), other.list().map { it.title })
    }

    @Test
    fun `a member the backup refused is left out of the rebuilt expansion`() {
        val shelf = File(temp, "c/Kits")
        val store = ExpansionStore(File(temp, "c/expansions"), Random(5))
        val good = kit(shelf, "GOOD")
        val bad = kit(shelf, "BAD", playable = false)
        store.create("MIXED", good, knobs).let { store.add(it.id, bad) }
        val zip = backup(shelf, store)
        val newShelf = File(temp, "d/Kits")
        val newStore = ExpansionStore(File(temp, "d/expansions"))
        val landed = ShelfImport.land(zip, "shelf.zip", newShelf, expansions = newStore)
        val goodId = KitIds.read(landed.kits.single().second)
        assertEquals(listOf(goodId), newStore.list().single().memberKitIds)
    }

    @Test
    fun `the landing line counts the expansions that came back`() {
        assertEquals("2 KITS LANDED ON THE SHELF. 1 EXPANSION BACK ON THE SHELF.", Copy.landed(2, 0, expansions = 1))
        assertEquals("1 KIT LANDED ON THE SHELF. 1 PRESET UNDER YOURS. 2 EXPANSIONS BACK ON THE SHELF. 1 SKIPPED.", Copy.landed(1, 1, presets = 1, expansions = 2))
        assertEquals("1 KIT LANDED ON THE SHELF.", Copy.landed(1, 0), "a restore with none reads as it always did")
    }

    @Test
    fun `a backup without expansions restores exactly as before`() {
        val shelf = File(temp, "e/Kits")
        kit(shelf, "ONE")
        val zip = File(temp, "old.zip")
        KitBackup.backup(shelf, zip)
        val newStore = ExpansionStore(File(temp, "f/expansions"))
        val landed = ShelfImport.land(zip, "old.zip", File(temp, "f/Kits"), expansions = newStore)
        assertEquals(1, landed.kits.size)
        assertEquals(0, landed.expansions)
        assertEquals(emptyList(), newStore.list())
        assertEquals(false, ExpansionBackup.write(ExpansionStore(File(temp, "none")), shelf, File(temp, "none.json")), "no expansions, no file")
    }
}
