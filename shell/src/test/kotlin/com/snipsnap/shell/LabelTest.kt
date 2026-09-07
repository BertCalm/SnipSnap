package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LabelTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("label").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun kit(name: String): File {
        val dir = File(temp, name)
        val m = KitBuilderModel.create(name, dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        return dir
    }

    @Test
    fun `numbers are stable - re-runs change nothing and new kits only append`() {
        kit("Bravo")
        kit("Alpha")
        val info = Label.init(temp, "Dusty Fingers")
        assertEquals("DF", info.prefix, "prefix derives from the name's initials")
        assertEquals(mapOf("Alpha" to 1, "Bravo" to 2), info.catalog, "name order assigns")
        assertEquals("DF-001", info.numberFor("Alpha"))

        // Re-running assigns nothing new and moves nothing.
        assertEquals(info.catalog, Label.assign(temp).catalog)

        // A new kit takes the NEXT number even though its name sorts first.
        kit("Aardvark")
        val grown = Label.assign(temp)
        assertEquals(3, grown.catalog["Aardvark"], "existing numbers never move")
        assertEquals(1, grown.catalog["Alpha"])

        // A kit that leaves keeps its entry, marked in the ledger.
        File(temp, "Bravo").deleteRecursively()
        val after = Label.assign(temp)
        assertEquals(2, after.catalog["Bravo"], "the catalog keeps deleted releases")
        val ledger = File(temp, Label.CATALOG_NAME).readText()
        assertContains(ledger, "DF-002  Bravo  (gone)")
        assertContains(ledger, "DF-001  Alpha")

        assertFailsWith<java.io.IOException>("re-init refused") { Label.init(temp, "Again") }
    }

    @Test
    fun `the inserts wear the catalog number`() {
        val dir = kit("Numbered")
        Label.init(temp, "Side Hustle Tapes", prefix = "SHT")
        assertEquals("SHT-001", Label.forKit(dir))
        assertEquals(null, Label.forKit(File(temp, "nowhere")), "no label, no number, no drama")

        val kit = com.snipsnap.kit.KitStore.load(dir)
        val notes = LinerNotes.render(kit, dir, Label.forKit(dir))
        assertContains(notes, "SHT-001", message = "the notes lead with the number")

        // The spine wears it: the numbered card differs from the plain one.
        val plain = JCard.png(kit, dir)
        val numbered = JCard.png(kit, dir, catalog = "SHT-001")
        assertTrue(!plain.contentEquals(numbered), "the catalog number shows on the spine")
        assertTrue(
            numbered.contentEquals(JCard.png(kit, dir, catalog = "SHT-001")),
            "deterministic with the number on",
        )
    }
}
