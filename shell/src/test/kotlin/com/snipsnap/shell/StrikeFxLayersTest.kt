package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.StrikeFxBank
import com.snipsnap.synth.isBanked
import com.snipsnap.synth.TinesPresets
import com.snipsnap.synth.TinesVoice
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrikeFxLayersTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("strikefx").toFile()

    private class Fixture(val dir: File, val m: KitBuilderModel)

    /** Pad 1 a captured snare, pad 2 a synth pad (recipe carries a patch), pad 3 a printed board. */
    private fun fixture(name: String): Fixture {
        val dir = File(temp, name)
        val m = KitBuilderModel.create(name, dir)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        val patch = TinesPresets.forVoice(TinesVoice.BELL).first()
        m.assign(2, patch.render(), DrumClass.TONAL)
        m.update(2) { it.copy(recipe = PadRecipe(patch = patch).toJsonValue()) }
        m.assign(3, DrumSynth.kick(), DrumClass.KICK)
        m.update(3) { it.copy(recipe = JsonValue.Obj(mapOf("aquariumPad" to JsonValue.Num(99.0)))) }
        return Fixture(dir, m)
    }

    private fun bytesOf(f: File) = f.readBytes()

    @Test
    fun `keep writes three ascending named layers and the recipe`() {
        val (_, m) = fixture("keep").let { it.dir to it.m }
        val pad = m.strikeFxLayers(1, 0.42f, 0.9f)
        assertEquals(listOf("DRY", "ROOM", "ECHO"), pad.velocityLayers.map { it.displayName })
        assertEquals(StackTakes.windows(2), pad.velocityLayers.map { it.velStart..it.velEnd })
        assertEquals(pad.sampleFile, pad.velocityLayers.last().sampleFile)
        val recipe = Breed.recipeOf(pad)!!
        assertEquals(0.42f, StrikeFxBank.roomOf(recipe))
        assertEquals(0.9f, StrikeFxBank.slapOf(recipe))
        assertEquals(3, recipe.layers!!.size)
    }

    @Test
    fun `keep preserves the source bytes and the source format`() {
        val f = fixture("bytes")
        val before = bytesOf(File(f.dir, f.m.kit.pad(1)!!.sampleFile))
        val pad = f.m.strikeFxLayers(1, 0.42f, 0.9f)
        assertContentEquals(before, bytesOf(File(f.dir, pad.velocityLayers[0].sampleFile)))
        val src = WavReader.read(File(f.dir, pad.velocityLayers[0].sampleFile))
        pad.velocityLayers.drop(1).forEach { l ->
            val s = WavReader.read(File(f.dir, l.sampleFile))
            assertEquals(src.channels, s.channels)
            assertEquals(src.sampleRate, s.sampleRate)
        }
    }

    @Test
    fun `keep leaves every other recipe field as it was`() {
        val f = fixture("recipe")
        val beforeRecipe = Breed.recipeOf(f.m.kit.pad(2)!!)!!
        assertTrue(beforeRecipe.patch != null)
        val after = Breed.recipeOf(f.m.strikeFxLayers(2, 0.42f, 0.9f))!!
        assertEquals(beforeRecipe, after.copy(layers = null))
    }

    @Test
    fun `audition writes nothing`() {
        val f = fixture("audition")
        val listing = f.dir.listFiles()!!.map { it.name }.sorted()
        val out = f.m.strikeFxAudition(1, 0.2f, 0.5f)
        assertEquals(3, out.size)
        assertEquals(listing, f.dir.listFiles()!!.map { it.name }.sorted())
        assertEquals(emptyList(), f.m.kit.pad(1)!!.velocityLayers)
    }

    @Test
    fun `keep over soft hits replaces their files`() {
        val f = fixture("ghosts")
        f.m.addGhostLayers(1, softZones = 2)
        val main = f.m.kit.pad(1)!!.sampleFile
        val ghosts = f.m.kit.pad(1)!!.velocityLayers.map { it.sampleFile }.filter { it != main }
        assertEquals(2, ghosts.size)
        val pad = f.m.strikeFxLayers(1, 0.42f, 0.9f)
        assertEquals(3, pad.velocityLayers.size)
        ghosts.forEach { assertFalse(File(f.dir, it).exists(), it) }
    }

    @Test
    fun `keep over soft hits leaves exactly the bank's files behind`() {
        val f = fixture("orphans")
        f.m.addGhostLayers(1, softZones = 1)
        val pad = f.m.strikeFxLayers(1, 0.42f, 0.9f)
        val wavs = f.dir.listFiles()!!.filter { it.name.startsWith(pad.sampleStem + "_v") }.map { it.name }.toSet()
        assertEquals(pad.velocityLayers.map { it.sampleFile }.toSet() - pad.sampleFile, wavs)
    }

    @Test
    fun `a second keep reads the DRY layer and rewrites the others in place`() {
        val f = fixture("rekeep")
        val first = f.m.strikeFxLayers(1, 0.42f, 0.9f)
        val dryBytes = bytesOf(File(f.dir, first.velocityLayers[0].sampleFile))
        val firstEcho = bytesOf(File(f.dir, first.sampleFile))
        val second = f.m.strikeFxLayers(1, 0.1f, 0.3f)
        assertEquals(first.velocityLayers.map { it.sampleFile }, second.velocityLayers.map { it.sampleFile })
        assertContentEquals(dryBytes, bytesOf(File(f.dir, second.velocityLayers[0].sampleFile)))
        assertFalse(firstEcho.contentEquals(bytesOf(File(f.dir, second.sampleFile))), "the echo was re-rendered")
        assertEquals(0.1f, StrikeFxBank.roomOf(Breed.recipeOf(second)!!))
    }

    @Test
    fun `clear restores the single sample and removes the bank from the recipe`() {
        val f = fixture("clear")
        val before = bytesOf(File(f.dir, f.m.kit.pad(1)!!.sampleFile))
        val recipeBefore = f.m.kit.pad(1)!!.recipe
        val kept = f.m.strikeFxLayers(1, 0.42f, 0.9f)
        val soft = kept.velocityLayers.dropLast(1).map { it.sampleFile }
        val cleared = f.m.clearStrikeFxLayers(1)
        assertTrue(cleared.velocityLayers.isEmpty())
        assertContentEquals(before, bytesOf(File(f.dir, cleared.sampleFile)))
        soft.forEach { assertFalse(File(f.dir, it).exists()) }
        assertEquals(recipeBefore, cleared.recipe)
    }

    @Test
    fun `clear on a synth pad keeps its patch`() {
        val f = fixture("clearsynth")
        val recipeBefore = f.m.kit.pad(2)!!.recipe
        f.m.strikeFxLayers(2, 0.42f, 0.9f)
        assertEquals(recipeBefore, f.m.clearStrikeFxLayers(2).recipe)
    }

    @Test
    fun `clear refuses a pad with no bank`() {
        val f = fixture("clearnone")
        assertFailsWith<IllegalArgumentException> { f.m.clearStrikeFxLayers(1) }
    }

    @Test
    fun `a printed board refuses strike fx with the existing copy`() {
        val f = fixture("printed")
        val before = f.dir.listFiles()!!.map { it.name }.sorted()
        for (verb in listOf<() -> Any>(
            { f.m.strikeFxLayers(3, 0.42f, 0.9f) },
            { f.m.strikeFxAudition(3, 0.42f, 0.9f) },
            { f.m.clearStrikeFxLayers(3) },
        )) {
            val e = assertFailsWith<IllegalArgumentException> { verb() }
            assertTrue(e.message!!.startsWith("REMOVE "), e.message)
        }
        assertEquals(before, f.dir.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `the strip reaches all three layers of a kept pad`() {
        val f = fixture("strip")
        val pad = f.m.strikeFxLayers(1, 0.42f, 0.9f)
        val layers = listOf(0f, 0.5f, 1f).map {
            PadHit.layerIndex(pad, StrikeStrip.hit(1f, true, it, false).velocity)
        }
        assertEquals(listOf(0, 1, 2), layers)
    }

    @Test
    fun `keep over stacked takes replaces them and deletes their files`() {
        val f = fixture("stacked")
        f.m.treatPad(1, "reversed")
        f.m.treatPad(1, "punched")
        val prior = f.m.priorTakes(1)
        val stacked = f.m.stackTakes(1, listOf(prior[1], prior[0]))
        val main = stacked.sampleFile
        val old = stacked.velocityLayers.map { it.sampleFile }.filter { it != main }
        assertEquals(2, old.size)
        val pad = f.m.strikeFxLayers(1, 0.42f, 0.9f)
        assertEquals(listOf("DRY", "ROOM", "ECHO"), pad.velocityLayers.map { it.displayName })
        old.forEach { assertFalse(File(f.dir, it).exists(), it) }
        assertTrue(pad.velocityLayers.all { File(f.dir, it.sampleFile).isFile })
    }

    @Test
    fun `keep on a treated pad keeps its treatment and adds the bank`() {
        val f = fixture("treated")
        f.m.treatPad(1, "reversed")
        val before = f.m.kit.pad(1)!!
        val recipeBefore = Breed.recipeOf(before)!!
        assertEquals("reversed", recipeBefore.treatment)
        val mainBytes = bytesOf(File(f.dir, before.sampleFile))
        val pad = f.m.strikeFxLayers(1, 0.42f, 0.9f)
        val recipe = Breed.recipeOf(pad)!!
        assertEquals("reversed", recipe.treatment)
        assertEquals(recipeBefore.fx, recipe.fx)
        assertEquals(3, recipe.layers!!.size)
        assertContentEquals(mainBytes, bytesOf(File(f.dir, pad.velocityLayers[0].sampleFile)))
    }

    @Test
    fun `an era style recipe is refused and nothing changes`() {
        val f = fixture("era")
        val era = JsonValue.Obj(mapOf("era" to JsonValue.Str("tape"), "amount" to JsonValue.Num(0.5)))
        f.m.update(1) { it.copy(recipe = era) }
        val before = f.dir.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val e = assertFailsWith<IllegalArgumentException> { f.m.strikeFxLayers(1, 0.42f, 0.9f) }
        assertEquals(Copy.STRIKE_FX_KEEPS_HISTORY, e.message)
        assertEquals(era, f.m.kit.pad(1)!!.recipe)
        assertTrue(f.m.kit.pad(1)!!.velocityLayers.isEmpty())
        assertEquals(before, f.dir.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    private fun snapshot(dir: File) = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }

    @Test
    fun `a round-robin chain pad is refused by all three verbs and no file changes`() {
        val f = fixture("chain")
        Robin.apply(f.m, 1, takes = 2, seed = 1)
        f.m.update(1) { it.copy(recipe = null) }
        f.m.save()
        assertTrue(f.m.kit.pad(1)!!.chain != null)
        val before = snapshot(f.dir)
        for (verb in listOf<() -> Any>(
            { f.m.strikeFxLayers(1, 0.42f, 0.9f) },
            { f.m.strikeFxAudition(1, 0.42f, 0.9f) },
            { f.m.clearStrikeFxLayers(1) },
        )) {
            val e = assertFailsWith<IllegalArgumentException> { verb() }
            assertTrue("round-robin chain" in e.message!!, e.message)
        }
        assertEquals(before, snapshot(f.dir))
    }

    @Test
    fun `audition refuses an era style recipe with the same words as keep and writes nothing`() {
        val f = fixture("eraaudition")
        val era = JsonValue.Obj(mapOf("era" to JsonValue.Str("tape"), "amount" to JsonValue.Num(0.5)))
        f.m.update(1) { it.copy(recipe = era) }
        val before = snapshot(f.dir)
        val e = assertFailsWith<IllegalArgumentException> { f.m.strikeFxAudition(1, 0.42f, 0.9f) }
        assertEquals(Copy.STRIKE_FX_KEEPS_HISTORY, e.message)
        assertEquals(before, snapshot(f.dir))
    }

    @Test
    fun `a keep over layers whose recipe lost its bank bins the replaced files instead of deleting them`() {
        val f = fixture("lostbank")
        val first = f.m.strikeFxLayers(1, 0.42f, 0.9f)
        val layerFiles = first.velocityLayers.map { it.sampleFile }
        val dryBytes = bytesOf(File(f.dir, layerFiles[0]))
        val roomBytes = bytesOf(File(f.dir, layerFiles[1]))
        // An MPC round trip without the sidecar: three layers named DRY/ROOM/ECHO, no recipe.
        f.m.update(1) { it.copy(recipe = null) }
        f.m.strikeFxLayers(1, 0.1f, 0.3f)
        val bin = File(f.dir, KitBuilderModel.BIN_DIR).listFiles()!!.filter { it.isFile && it.extension == "wav" }
        assertTrue(bin.any { it.name.endsWith("_" + layerFiles[0]) && it.readBytes().contentEquals(dryBytes) }, "the former DRY is in the bin")
        assertTrue(bin.any { it.name.endsWith("_" + layerFiles[1]) && it.readBytes().contentEquals(roomBytes) }, "the former ROOM is in the bin")
    }

    @Test
    fun `a bred child of a banked pad is one sample and its recipe carries no bank`() {
        val a = fixture("bredA")
        a.m.strikeFxLayers(1, 0.42f, 0.9f)
        a.m.strikeFxLayers(2, 0.42f, 0.9f)
        a.m.save()
        val bDir = File(temp, "bredB")
        val b = KitBuilderModel.create("bredB", bDir)
        b.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        b.save()
        val childDir = File(temp, "bredChild")
        Breed.breed(a.dir, bDir, childDir, seed = 1)
        val child = KitBuilderModel.open(childDir).kit
        for (slot in listOf(1, 2)) {
            val pad = child.pad(slot)!!
            assertTrue(pad.velocityLayers.isEmpty(), "pad $slot")
            val recipe = Breed.recipeOf(pad)
            assertTrue(recipe == null || !recipe.isBanked, "pad $slot carries no bank")
            assertFalse(pad.recipe?.entries?.containsKey("layers") == true, "pad $slot")
        }
        assertTrue(Breed.recipeOf(child.pad(2)!!)!!.patch != null, "the synth pad keeps its patch")
        // The sheet and the shell agree: nothing to CLEAR on the child.
        assertFailsWith<IllegalArgumentException> { KitBuilderModel.open(childDir).clearStrikeFxLayers(2) }
    }

    @Test
    fun `a kept bank survives a standalone export and import and clears back to the source bytes`() {
        val f = fixture("roundtrip")
        val original = bytesOf(File(f.dir, f.m.kit.pad(1)!!.sampleFile))
        f.m.strikeFxLayers(1, 0.42f, 0.9f)
        f.m.save()
        val exported = com.snipsnap.kit.KitExporter.exportProgramFolder(f.m.kit, f.dir, File(temp, "roundtrip-out")).directory
        val opened = KitBuilderModel.open(exported)
        assertEquals(listOf("DRY", "ROOM", "ECHO"), opened.kit.pad(1)!!.velocityLayers.map { it.displayName })
        val cleared = opened.clearStrikeFxLayers(1)
        assertTrue(cleared.velocityLayers.isEmpty())
        assertContentEquals(original, bytesOf(File(exported, cleared.sampleFile)))
    }
}
