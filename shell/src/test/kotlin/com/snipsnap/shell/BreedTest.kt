package com.snipsnap.shell

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BreedTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("breed").toFile()

    private fun starter(id: String, name: String, seed: Int): File {
        val dir = File(temp, name)
        StarterKits.ALL.first { it.id == id }.render(name, dir, seed)
        return dir
    }

    private fun bytesOf(dir: File): Map<String, ByteArray> =
        dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }

    @Test
    fun `every child pad keeps its parent's class, and the same seed breeds the same kit`() {
        val a = starter("factory", "Mother", 0)
        val b = starter("lucky-dip", "Father", 3)
        val child = File(temp, "Child")
        val report = Breed.breed(a, b, child, seed = 1)

        assertTrue(report.crossed.isNotEmpty(), "synth recipes cross: ${report.crossed}")
        val mother = KitStore.load(a)
        for (pad in report.kit.pads) {
            val parent = mother.pad(pad.slot)!!
            val parentClass = Classifier.classify(WavReader.read(File(a, parent.sampleFile))).drumClass
            val childClass = Classifier.classify(WavReader.read(File(child, pad.sampleFile))).drumClass
            assertEquals(parentClass, childClass, "slot ${pad.slot} (${parent.displayName}) keeps its parent's class")
            assertEquals(parent.drumClass, pad.drumClass)
            assertEquals("Mother x Father", pad.source["bredFrom"])
        }
        // Something actually changed for the crossed pads.
        val changed = report.crossed.count { slot ->
            !File(a, mother.pad(slot)!!.sampleFile).readBytes().contentEquals(File(child, report.kit.pad(slot)!!.sampleFile).readBytes())
        }
        assertTrue(changed > 0, "a crossed pad sounds different from its mother")

        val twin = File(temp, "Twin")
        Breed.breed(a, b, twin, seed = 1)
        val childBytes = bytesOf(child)
        val twinBytes = bytesOf(twin)
        assertEquals(childBytes.keys, twinBytes.keys)
        for ((f, bytes) in childBytes) {
            if (f == "kit.json") continue // names differ
            assertTrue(bytes.contentEquals(twinBytes.getValue(f)), "$f: same seed, same bytes")
        }
        val other = File(temp, "Other")
        Breed.breed(a, b, other, seed = 2)
        val otherBytes = bytesOf(other)
        assertTrue(childBytes.any { (f, bytes) -> f != "kit.json" && !bytes.contentEquals(otherBytes.getValue(f)) }, "another seed, another kit")
    }

    @Test
    fun `captured pads without recipes come over as they are, and the parents stay untouched`() {
        val aDir = File(temp, "Caught")
        val m = KitBuilderModel.create("Caught", aDir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        val b = starter("factory", "Bred", 0)
        val before = bytesOf(aDir)
        val bBefore = bytesOf(b)

        val report = Breed.breed(aDir, b, File(temp, "Kept"), seed = 0)
        assertEquals(listOf(1, 2), report.kept, "no recipe on A, no rack on B's factory kit: nothing to cross")
        assertTrue(report.crossed.isEmpty())
        for ((f, bytes) in before) assertTrue(bytes.contentEquals(bytesOf(aDir).getValue(f)), "A untouched: $f")
        for ((f, bytes) in bBefore) assertTrue(bytes.contentEquals(bytesOf(b).getValue(f)), "B untouched: $f")

        assertFailsWith<IllegalArgumentException> { Breed.breed(aDir, b, File(temp, "Kept"), seed = 0) }
        assertFailsWith<IllegalArgumentException> { Breed.breed(aDir, b, File(temp, "Named"), name = "", seed = 0) }
    }

    @Test
    fun `crossable says beforehand exactly which slots breed will cross or audit, and recipePads counts the button's number`() {
        val a = starter("factory", "Mother", 0)
        val b = starter("lucky-dip", "Father", 3)
        val mother = KitStore.load(a)
        val father = KitStore.load(b)
        val would = Breed.crossable(mother, father)
        assertTrue(would.isNotEmpty())
        val report = Breed.breed(a, b, File(temp, "Child"), seed = 1)
        assertEquals(would, (report.crossed + report.audited).sorted(), "the pick's promise is the breed's report")
        assertEquals(would, mother.pads.map { it.slot }.sorted().filter { it !in report.kept })
        // A synth kit: every pad carries a patch, so the button counts them all.
        assertEquals(mother.pads.map { it.slot }.sorted(), Breed.recipePads(mother))

        // Two plain captures: nothing on either side, and the pick can say so before copying A.
        val cDir = File(temp, "Caught")
        val m = KitBuilderModel.create("Caught", cDir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        val dDir = File(temp, "Other")
        val n = KitBuilderModel.create("Other", dDir)
        n.assign(1, DrumSynth.kick(), DrumClass.KICK)
        n.save()
        assertTrue(Breed.crossable(m.kit, n.kit).isEmpty(), "no recipe on either side")
        assertTrue(Breed.recipePads(m.kit).isEmpty())
        // A rack on one side is enough: a treated capture crosses its rack
        // over the other kit's audio, whichever kit BREED is pressed from.
        m.characterPad(1, "crushed", 0.7f)
        assertEquals(listOf(1), Breed.recipePads(m.kit))
        assertEquals(listOf(1), Breed.crossable(m.kit, n.kit), "A's rack")
        assertEquals(listOf(1), Breed.crossable(n.kit, m.kit), "B's rack over A's audio")
        assertTrue(Breed.recipePads(n.kit).isEmpty(), "the button on the plain kit still reads zero")
        assertEquals(Copy.BREED_BUTTON, "BREED ▸ MIX TWO KITS", "the button always opens the picker, so its label is constant")
        assertEquals(Copy.breedSubtitle(0, 2), "NO RECIPES HERE YET. THE OTHER KIT'S CAN CROSS. PARENTS STAY.")
        assertEquals(Copy.breedSubtitle(1, 2), "1 OF 2 PADS HAS A RECIPE. BOTH PARENTS STAY.")
        assertEquals(Copy.breedSubtitle(1, 1), "ITS ONE PAD HAS A RECIPE TO CROSS. BOTH PARENTS STAY.", "a one-pad kit reads in the singular")
        assertEquals(Copy.breedSubtitle(2, 2), "2 OF 2 PADS HAVE RECIPES. BOTH PARENTS STAY.")
    }
}
