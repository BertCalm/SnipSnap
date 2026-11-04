package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitIds
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.OneNote
import com.snipsnap.kit.Severity
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
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
    fun `the card gets the folder or the xpn, never both`() {
        val e = expansion("Card Bound", playable("Kit One", 1))
        val folder = model(e).write(File(temp, "f"), overwrite = false) as ExpansionExportModel.WriteResult.Done
        assertEquals(listOf(folder.result.directory), ExpansionExportModel.cardItems(folder.result, ExpansionExportModel.Form.FOLDER))
        val m = model(e).also { it.form = ExpansionExportModel.Form.XPN }
        val xpn = m.write(File(temp, "x"), overwrite = false) as ExpansionExportModel.WriteResult.Done
        assertEquals(listOf(xpn.result.xpn!!), ExpansionExportModel.cardItems(xpn.result, ExpansionExportModel.Form.XPN))
    }

    @Test
    fun `the written tile follows a seed change`() {
        val one = playable("Kit One", 1)
        val two = playable("Kit Two", 2)
        val e = expansion("Reseeded", one, two)
        val index = ExpansionMembers.index(shelf)
        val seen = mutableListOf<String>()
        val m = ExpansionExportModel(e, ExpansionMembers.present(e, index), ExpansionMembers.seed(e, index), tileRenderer = { k, d, kn, label ->
            seen += k.name
            KitArt.png(k, d, kn, label = label)
        })
        m.seed = index.getValue(two)
        m.write(File(temp, "seed"), overwrite = false)
        assertEquals("Kit Two", seen.last(), "the tile is drawn from the seed the picker chose")
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

    private val instrumentsDir = File(temp, "Instruments")

    /** A two-zone held instrument on the shelf, as MAKE INSTRUMENT leaves one. */
    private fun instrument(name: String): String {
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = listOf(48, 60).map { midi ->
            val stem = "${name.filter { !it.isWhitespace() }}_$midi"
            samples[stem] = Snip(FloatArray(6_000) { f -> (0.3 * Math.sin(2.0 * Math.PI * 220.0 * f / 44_100)).toFloat() }, 1, 44_100)
            Keygroup(midi - 6, midi + 5, midi, listOf(VelocityLayer(stem, 6_000, 0, 127, loopStartFrame = 3_000)))
        }
        OneNote.writePackage(name, KeygroupProgram(name, keygroups, volumeRelease = .5f), samples, instrumentsDir, overwrite = false)
        return name
    }

    @Test
    fun `instruments join an expansion, survive a reload and pack beside its kits`() {
        val kit = playable("Alpha", 1)
        val e = expansion("Keys Vol 1", kit)
        store.addInstrument(e.id, instrument("Glass Keys"))
        store.addInstrument(e.id, "Glass Keys")
        store.addInstrument(e.id, "Gone Keys")
        val reloaded = ExpansionStore(store.root).get(e.id)!!
        assertEquals(listOf("Glass Keys", "Gone Keys"), reloaded.instrumentNames, "joined once each, in order")
        val present = ExpansionMembers.instruments(reloaded, instrumentsDir)
        assertEquals(listOf(InstrumentStore.sidecar(instrumentsDir, "Glass Keys")), present, "a deleted instrument is simply not there")

        val index = ExpansionMembers.index(shelf)
        val model = ExpansionExportModel(reloaded, ExpansionMembers.present(reloaded, index), ExpansionMembers.seed(reloaded, index),
            tileRenderer = { _, _, _, _ -> byteArrayOf(1) }, jCardRenderer = { _, _, _ -> byteArrayOf(2) }, instruments = present)
        val done = model.write(File(temp, "out"), overwrite = false) as ExpansionExportModel.WriteResult.Done
        assertEquals(listOf("Alpha"), done.result.packed)
        assertEquals(listOf("Glass Keys"), done.result.instruments)
        assertTrue(File(done.result.directory, "Programs/Glass Keys.xpm").isFile)
        assertTrue(File(done.result.directory, "Samples/Glass Keys/GlassKeys_60.wav").isFile)

        store.removeInstrument(e.id, "Gone Keys")
        assertEquals(listOf("Glass Keys"), store.get(e.id)!!.instrumentNames)

        // A file written before instruments could join has no such key: it reads as none.
        val file = File(store.root, "${e.id}/${ExpansionStore.FILE}")
        file.writeText(file.readText().replace(Regex(",\\s*\"instruments\"\\s*:\\s*\\[[^\\]]*\\]"), ""))
        assertTrue("instruments" !in file.readText())
        assertEquals(emptyList(), store.get(e.id)!!.instrumentNames)
        assertEquals(listOf(kit), store.get(e.id)!!.memberKitIds)
    }

    @Test
    fun `an expansion born from an instrument has no kit, no seed and still writes`() {
        val e = store.createForInstrument("Bass Only", instrument("Low Bass"), knobs)
        assertEquals(emptyList(), e.memberKitIds)
        assertNull(e.seedKitId)
        val present = ExpansionMembers.instruments(e, instrumentsDir)
        val model = ExpansionExportModel(e, emptyList(), null, instruments = present)
        assertTrue(!model.empty)
        assertNull(model.tile(), "no kit draws the tile yet")
        val done = model.write(File(temp, "out"), overwrite = false) as ExpansionExportModel.WriteResult.Done
        assertEquals(listOf("Low Bass"), done.result.instruments)
        assertTrue(File(done.result.directory, "Programs/Low Bass.xpm").isFile)
    }
}
