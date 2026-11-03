package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.RobinSettings
import com.snipsnap.kit.isRobinChain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 4: every audio edit works on a round-robin pad by lifting the chain, editing the original, re-rendering the takes. */
class RobinEditsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("robinedits").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(2, DrumSynth.kick(), DrumClass.KICK)
        m.assign(3, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        return m
    }

    private fun bytes(m: KitBuilderModel, slot: Int) = File(m.kitDir, m.pad(slot)!!.sampleFile).readBytes()

    @Test
    fun `a treatment on a robin pad lands on the original and the takes re-render`() {
        val m = model("Treat")
        val plain = model("TreatPlain")
        plain.treatPad(1, "crushed")
        val treatedAlone = WavReader.read(File(plain.kitDir, plain.pad(1)!!.sampleFile))

        Robin.apply(m, 1, RobinSettings(0.6f, 6))
        m.treatPad(1, "crushed")

        val pad = m.pad(1)!!
        assertTrue(pad.isRobinChain)
        assertEquals(6, pad.chain!!.cycle)
        assertEquals(RobinSettings(0.6f, 6), pad.robin)
        assertContentEquals(treatedAlone.samples, Robin.original(m, 1).samples)
        assertNotNull(pad.recipe, "the treatment's recipe rides the robin pad")
    }

    @Test
    fun `edits on a robin pad undo in order even within one millisecond`() {
        val m = model("Order")
        val untouched = bytes(m, 1)
        Robin.apply(m, 1, RobinSettings(0.5f, 4))
        m.treatPad(1, "crushed")
        m.untreatPad(1)
        assertTrue(m.pad(1)!!.isRobinChain)
        Robin.undo(m, 1)
        assertContentEquals(untouched, bytes(m, 1))
        assertNull(m.pad(1)!!.chain)
        assertNull(m.pad(1)!!.robin)
    }

    @Test
    fun `a legacy robin pad keeps the edit's recipe`() {
        val m = model("Legacy")
        Robin.apply(m, 1, takes = 3, seed = 2)
        m.treatPad(1, "crushed")
        val pad = m.pad(1)!!
        assertEquals(3, pad.chain!!.cycle)
        assertNull(pad.robin!!.amount)
        assertTrue(pad.recipe?.entries?.keys != setOf("robin"), "the treatment's recipe survives the legacy re-render")
    }

    @Test
    fun `a failed edit puts the takes back`() {
        val m = model("Fail")
        Robin.apply(m, 1, RobinSettings(0.5f, 3))
        val before = bytes(m, 1)
        assertFailsWith<IllegalArgumentException> { m.untreatPad(1) } // nothing treated: the bin holds no earlier take
        assertTrue(m.pad(1)!!.isRobinChain)
        assertContentEquals(before, bytes(m, 1), "same settings, same seed, same takes")
    }

    @Test
    fun `a foreign chain still refuses edits`() {
        val m = model("Foreign")
        Robin.apply(m, 1, takes = 2, seed = 1, zones = 2)
        val pad = m.pad(1)!!
        assertTrue(pad.chain != null && !pad.isRobinChain)
        val e = assertFailsWith<IllegalArgumentException> { m.treatPad(1, "crushed") }
        assertEquals("pad 1 is a round-robin chain - `robin --undo` before treating it", e.message)
        assertFailsWith<IllegalArgumentException> { m.replaceAudio(1, null) { it } }
        assertFailsWith<IllegalArgumentException> { m.eraPad(1, "sp1200") }
        assertFailsWith<IllegalArgumentException> { m.untreatPad(1) }
    }

    @Test
    fun `layer doors keep refusing a robin pad`() {
        val m = model("Layers")
        Robin.apply(m, 1, RobinSettings(0.5f, 3))
        assertFailsWith<IllegalArgumentException> { m.strikeFxLayers(1, 0.3f, 0.3f) }
        assertTrue(m.pad(1)!!.isRobinChain)
    }

    @Test
    fun `replaceAudio on a robin pad rewrites the original and keeps the takes`() {
        val m = model("Replace")
        Robin.apply(m, 1, RobinSettings(0.5f, 4))
        val kick = DrumSynth.kick()
        m.replaceAudio(1, null) { kick }
        assertTrue(m.pad(1)!!.isRobinChain)
        assertEquals(4, m.pad(1)!!.chain!!.cycle)
        assertEquals(kick.frameCount, Robin.original(m, 1).frameCount)
    }

    @Test
    fun `recipe replay on a robin pad re-renders its takes`() {
        val m = model("Replay")
        val source = model("ReplaySrc")
        source.treatPad(1, "crushed")
        val recipe = checkNotNull(source.pad(1)!!.recipe)
        val fresh = model("ReplayFresh")
        RecipeReplay.apply(fresh, 1, recipe, "P")

        Robin.apply(m, 1, RobinSettings(0.5f, 5))
        RecipeReplay.apply(m, 1, recipe, "P")
        val pad = m.pad(1)!!
        assertTrue(pad.isRobinChain)
        assertEquals(5, pad.chain!!.cycle)
        assertContentEquals(WavReader.read(File(fresh.kitDir, fresh.pad(1)!!.sampleFile)).samples, Robin.original(m, 1).samples)
    }

    @Test
    fun `retrim and mutate work on a robin pad`() {
        val m = model("RetrimMutate")
        Robin.apply(m, 1, RobinSettings(0.5f, 3))

        val cut = Retrim.tag("snip_1_X.wav", 10, 90)
        val recut = m.backOnto(1, DrumSynth.kick(), cut)
        assertTrue(recut.isRobinChain)
        assertEquals(3, m.pad(1)!!.chain!!.cycle)
        assertEquals(DrumSynth.kick().frameCount, Robin.original(m, 1).frameCount)
        val tape = File(temp, "snips").apply { mkdirs() }
        File(tape, "snip_1_X.wav").writeBytes(ByteArray(64))
        assertTrue(Retrim.of(m.pad(1)!!, tape) is Retrim.Ready)

        val partner = MutateSheet.Partner.Pad(2)
        assertNull(MutateSheet.refusalBefore(m.pad(1)!!, partner, true))
        val heard = MutateSheet.preview(m, 1, partner, Mutate.Mode.MORPH, 0.5f)
        MutateSheet.apply(m, 1, partner, Mutate.Mode.MORPH, 0.5f)
        assertTrue(m.pad(1)!!.isRobinChain)
        assertEquals(heard.frameCount, Robin.original(m, 1).frameCount)
        assertEquals(MutateSheet.lengthMs(Robin.original(m, 1)), MutateSheet.lengthMs(m, 1))
    }

    @Test
    fun `retrim and mutate still refuse a foreign chain`() {
        val m = model("ForeignRM")
        Robin.apply(m, 1, takes = 2, seed = 1, zones = 2)
        assertEquals(MutateSheet.Refusal.Chained, MutateSheet.refusalBefore(m.pad(1)!!, MutateSheet.Partner.Pad(2), true))
        assertTrue(Retrim.of(m.pad(1)!!, File(temp, "snips")) is Retrim.Refused)
    }

    @Test
    fun `same-millisecond bin entries restore newest first`() {
        val m = model("Stamp")
        Robin.apply(m, 3, RobinSettings(0.5f, 3))
        repeat(4) { m.treatPad(3, "crushed"); m.untreatPad(3) }
        val stamps = m.binContents().map { it.binnedAtMillis }
        assertEquals(stamps.sortedDescending(), stamps)
        assertEquals(stamps.size, stamps.toSet().size, "no two bin entries share a stamp")
    }
}
