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
    fun `a STRIKE FX paste onto a robin pad is refused and the takes stay`() {
        val m = model("StrikePaste")
        Robin.apply(m, 1, RobinSettings(0.5f, 3))
        val before = bytes(m, 1)
        val recipe = com.snipsnap.synth.PadRecipe(layers = com.snipsnap.synth.StrikeFxBank.layers(0.3f, 0.6f)).toJsonValue() as com.snipsnap.json.JsonValue.Obj
        assertFailsWith<IllegalArgumentException> { RecipeReplay.apply(m, 1, recipe, "P") }
        assertTrue(m.pad(1)!!.isRobinChain)
        assertTrue(m.pad(1)!!.velocityLayers.isEmpty())
        assertContentEquals(before, bytes(m, 1))
    }

    @Test
    fun `soft hits are refused on a robin pad`() {
        val m = model("Ghosts")
        Robin.apply(m, 1, RobinSettings(0.5f, 3))
        val before = bytes(m, 1)
        assertFailsWith<IllegalArgumentException> { m.addGhostLayers(1) }
        assertTrue(m.pad(1)!!.isRobinChain)
        assertContentEquals(before, bytes(m, 1))
    }

    @Test
    fun `around fails and keeps what it can when an edit leaves the pad unrenderable`() {
        val m = model("Backstop")
        Robin.apply(m, 1, RobinSettings(0.5f, 3))
        val e = assertFailsWith<IllegalArgumentException> {
            Robin.around(m, 1) { m.update(1) { it.copy(velocityLayers = listOf(com.snipsnap.kit.KitLayer(it.sampleFile, 0, 127))) } }
        }
        assertTrue(e.message!!.contains("round-robin takes"))
    }

    @Test
    fun `every door lands on the original and the takes follow`() {
        val doors: List<Pair<String, (KitBuilderModel, Int) -> Unit>> = listOf(
            "eraPad" to { m, s -> m.eraPad(s, "sp1200") },
            "characterPad" to { m, s -> m.characterPad(s, "crushed", 1f) },
            "keyedPad" to { m, s -> m.keyedPad(s, "bodied", 1f, dials = Keyed.Dials(decay = 0.4f)) },
            "dustPad" to { m, s ->
                val r = java.util.Random(3)
                val print = com.snipsnap.audio.Dust.Print(
                    com.snipsnap.audio.Snip(FloatArray(4_410) { (r.nextGaussian() * 0.01).toFloat() }, 1, 44_100),
                    com.snipsnap.audio.Snip(FloatArray(2_205) { (r.nextGaussian() * 0.05 * Math.exp(-it / 500.0)).toFloat() }, 1, 44_100),
                )
                m.dustPad(s, 0.6f, "tape.wav", print)
            },
            "smearPad" to { m, s -> m.smearPad(s, 0.5f) },
            "pedalPad" to { m, s ->
                m.pedalPad(s, com.snipsnap.synth.PedalBoard(listOf(com.snipsnap.synth.PedalBoard.Pick("driven", 0.7f))))
            },
            "eraPad then unEraPad" to { m, s -> m.eraPad(s, "sp1200"); m.unEraPad(s) },
        )
        for ((name, door) in doors) {
            val plain = model("Plain_" + name.filter { it.isLetter() })
            val robin = model("Robin_" + name.filter { it.isLetter() })
            val slot = 2 // the kick: BODY never refuses (retunePad is the same keyedPad door and refuses a drum)
            val untouched = WavReader.read(File(plain.kitDir, plain.pad(slot)!!.sampleFile))
            door(plain, slot)
            Robin.apply(robin, slot, RobinSettings(0.5f, 5))
            door(robin, slot)
            val pad = robin.pad(slot)!!
            assertTrue(pad.isRobinChain, "$name: still a robin chain")
            assertEquals(5, pad.chain!!.cycle, "$name: cycle kept")
            assertEquals(RobinSettings(0.5f, 5), pad.robin, "$name: settings kept")
            val expected = WavReader.read(File(plain.kitDir, plain.pad(slot)!!.sampleFile))
            assertContentEquals(expected.samples, Robin.original(robin, slot).samples, "$name: take one is the same edit on a plain copy")
            if (name != "eraPad then unEraPad") {
                assertTrue(!untouched.samples.contentEquals(expected.samples), "$name: the edit is audible")
            }
        }
    }

    @Test
    fun `a bin stamp collision bumps the stamp and the newest entry still restores first`() {
        val m = model("Stamp")
        val pad = m.pad(2)!!
        val original = bytes(m, 2)
        // Fill every millisecond around now with decoys under the pad's file name, so the next bin
        // is forced to collide and has to take a later stamp.
        val bin = File(m.kitDir, KitBuilderModel.BIN_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        for (t in now - 5..now + 600) File(bin, "${t}_${pad.sampleFile}").writeBytes(byteArrayOf(1))
        val decoyMax = now + 600
        m.replaceAudio(2, null) { DrumSynth.snare() }
        val newest = m.binContents().first()
        assertEquals(decoyMax + 1, newest.binnedAtMillis, "the stamp stepped past every taken millisecond")
        assertContentEquals(original, newest.file.readBytes(), "newest-first is the real original, not a decoy")
    }
}
