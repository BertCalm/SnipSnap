package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitIds
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Severity
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpansionExportTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("expexport").toFile()
    private val shelf = File(temp, "Kits").apply { mkdirs() }
    private val store = ExpansionStore(File(temp, "expansions"), Random(9))
    private val knobs = ArtKnobs(KitArt.Style.BLOOM, Palettes.VENT, null, Mark.Variant.STACKED)

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun playable(name: String, seed: Int): String {
        val dir = File(shelf, name)
        val m = KitBuilderModel.create(name, dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        return KitIds.ensure(dir, Random(seed))
    }

    private fun blocked(name: String, seed: Int): String {
        val dir = File(shelf, name).apply { mkdirs() }
        KitStore.save(Kit(name, listOf(KitPad(slot = 1, sampleFile = "missing.wav", drumClass = DrumClass.KICK))), dir)
        return KitIds.ensure(dir, Random(seed))
    }

    private fun model(e: Expansion, tile: ((Kit, File, ArtKnobs, String) -> ByteArray)? = null): ExpansionExportModel {
        val index = ExpansionMembers.index(shelf)
        val members = ExpansionMembers.present(e, index)
        val seed = ExpansionMembers.seed(e, index)
        return if (tile == null) ExpansionExportModel(e, members, seed) else ExpansionExportModel(e, members, seed, tileRenderer = tile)
    }

    private fun expansion(title: String, vararg ids: String): Expansion {
        val e = store.create(title, ids.first(), knobs)
        ids.drop(1).forEach { store.add(e.id, it) }
        return store.get(e.id)!!
    }

    @Test
    fun `an expansion lands as one folder named after its title with a tile and a J-Card per member`() {
        val e = expansion("Night Shift", playable("Kit One", 1), playable("Kit Two", 2))
        val result = model(e).write(File(temp, "card"), overwrite = false)
        result as ExpansionExportModel.WriteResult.Done
        val dir = result.result.directory
        assertEquals("Night Shift", dir.name)
        val xml = File(dir, "Expansion.xml").readText()
        assertTrue(xml.contains(e.identifier), "the identifier fixed at birth")
        assertTrue(xml.contains("SnipSnap"), "the manufacturer")
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".png") && it.parentFile == dir }, "the tile")
        for (name in listOf("Kit One", "Kit Two")) {
            assertTrue(File(dir, "[J-Cards]/$name.png").isFile, "$name's J-Card")
            val findings = assertNotNull(result.readBack[name], "$name was read back")
            assertTrue(findings.none { it.severity == Severity.FAIL }, "$name reads back clean: $findings")
        }
        assertEquals(emptyList(), result.skipped)
    }

    @Test
    fun `the xpn form writes the archive twin`() {
        val e = expansion("Night Shift", playable("Kit One", 1))
        val m = model(e).also { it.form = ExpansionExportModel.Form.XPN }
        val result = m.write(File(temp, "card"), overwrite = false) as ExpansionExportModel.WriteResult.Done
        assertEquals("Night Shift.xpn", result.result.xpn!!.name)
    }

    @Test
    fun `a blocked member is skipped and named, the rest land`() {
        val e = expansion("Mixed", playable("Good One", 1), blocked("Broken", 2), playable("Good Two", 3))
        val result = model(e).write(File(temp, "card"), overwrite = false) as ExpansionExportModel.WriteResult.Done
        assertEquals(listOf("Broken"), result.skipped.map { it.first })
        assertEquals(listOf("Good One", "Good Two"), result.result.packed)
        assertTrue(!File(result.result.directory, "[J-Cards]/Broken.png").exists(), "no J-Card for a kit that was not packed")
        val pre = model(e).runPreflight()
        assertTrue(pre.entries.single { it.key.kit.name == "Broken" }.value.any { it.severity == Severity.FAIL })
    }

    @Test
    fun `an expansion whose every member is blocked writes nothing and names them`() {
        val e = expansion("All Broken", blocked("B1", 1), blocked("B2", 2), blocked("B3", 3))
        val result = model(e).write(File(temp, "card"), overwrite = false)
        result as ExpansionExportModel.WriteResult.Nothing
        assertEquals(listOf("B1", "B2", "B3"), result.skipped.map { it.first })
        assertTrue(!File(temp, "card/Expansions/All Broken").exists(), "no half-written folder")
    }

    @Test
    fun `an empty expansion refuses to export`() {
        val gone = playable("Gone", 1)
        val e = expansion("Empty", gone)
        File(shelf, "Gone").deleteRecursively()
        val m = model(e)
        assertTrue(m.empty)
        assertNull(m.tile())
        assertEquals(ExpansionExportModel.WriteResult.Nothing(emptyList()), m.write(File(temp, "card"), overwrite = false))
    }

    @Test
    fun `a second export writes the same tile and identifier`() {
        val e = expansion("Twice", playable("Kit One", 1), playable("Kit Two", 2))
        val a = model(e).write(File(temp, "a"), overwrite = false) as ExpansionExportModel.WriteResult.Done
        val b = model(e).write(File(temp, "b"), overwrite = false) as ExpansionExportModel.WriteResult.Done
        fun tile(dir: File) = dir.listFiles()!!.single { it.isFile && it.name.endsWith(".png") }.readBytes()
        assertTrue(tile(a.result.directory).contentEquals(tile(b.result.directory)))
        assertEquals(File(a.result.directory, "Expansion.xml").readText(), File(b.result.directory, "Expansion.xml").readText())
    }

    @Test
    fun `the tile draws the seed kit with the expansion's title`() {
        val one = playable("Kit One", 1)
        val two = playable("Kit Two", 2)
        val e = store.setArt(expansion("Seeded", one, two).id, knobs, seedKitId = two)
        val seen = mutableListOf<Triple<String, ArtKnobs, String>>()
        val m = model(e) { k, d, kn, label -> seen += Triple(k.name, kn, label); KitArt.png(k, d, kn, label = label) }
        m.knobs = knobs.copy(style = KitArt.Style.RIDGES)
        assertNotNull(m.tile())
        assertEquals(Triple("Kit Two", knobs.copy(style = KitArt.Style.RIDGES), "Seeded"), seen.single())
    }

    @Test
    fun `an existing folder asks before it is replaced`() {
        val e = expansion("Again", playable("Kit One", 1))
        val root = File(temp, "card")
        assertTrue(model(e).write(root, overwrite = false) is ExpansionExportModel.WriteResult.Done)
        val again = model(e).write(root, overwrite = false)
        assertTrue(again is ExpansionExportModel.WriteResult.WouldOverwrite, "$again")
        assertTrue(model(e).write(root, overwrite = true) is ExpansionExportModel.WriteResult.Done)
    }
}
