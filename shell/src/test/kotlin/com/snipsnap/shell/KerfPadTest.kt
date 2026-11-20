package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A KERF twin on a kit pad, as the pad sheet sees it (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md,
 * round 2c): what is on top, what each undo gives back, and what the pad is called afterwards.
 */
class KerfPadTest {
    private val temp: File = java.nio.file.Files.createTempDirectory("kerfpad").toFile()

    @AfterTest fun cleanUp() { temp.deleteRecursively() }

    private fun hit(hz: Double) = Snip(FloatArray(4410) { i -> (.4 * sin(2.0 * PI * hz * i / 44100.0) * exp(-i / 1400.0)).toFloat() }, 1, WavWriter.MPC_SAMPLE_RATE)

    /** The on-device suite's one-pad kit (`PadSheetUndoScreenTest.kit`), and a three-pad one for neighbours. */
    private fun kit(pads: Int): File {
        val dir = File(temp, "kit$pads-${System.nanoTime()}").apply { mkdirs() }
        val list = (1..pads).map { n ->
            WavWriter.write(File(dir, "hit$n.wav"), hit(120.0 * n))
            KitPad(slot = n, sampleFile = "hit$n.wav", drumClass = DrumClass.TONAL)
        }
        KitStore.save(Kit(name = "KERF PAD TEST", pads = list, tempoBpm = 92f), dir)
        return dir
    }
    private fun pad(dir: File, slot: Int = 1) = KitStore.load(dir).pad(slot)!!
    private fun binned(dir: File, slot: Int = 1) = KitBuilderModel.open(dir).priorTakes(slot).isNotEmpty()

    @Test fun `a lone pad becomes a twin with no neighbours, and its undo is the capture and its old name`() {
        val dir = kit(1); val original = File(dir, "hit1.wav").readBytes()
        KitBuilderModel.open(dir).apply { kerfPad(1); save() }
        val twin = pad(dir)
        assertTrue(PadSheet.isKerfTwin(twin))
        assertFalse(original.contentEquals(File(dir, "hit1.wav").readBytes()))
        val recipe = Breed.recipeOf(twin)!!.kerf!!
        assertNull(recipe.prev); assertNull(recipe.next)
        assertEquals("TONAL", twin.source[KitBuilderModel.KERF_WAS])
        assertNull(PadSheet.kerfUndoRefusal(twin, binned(dir)))

        KitBuilderModel.open(dir).apply { unkerfPad(1); save() }
        val back = pad(dir)
        assertTrue(original.contentEquals(File(dir, "hit1.wav").readBytes()), "the capture, byte for byte")
        assertNull(back.recipe); assertEquals(DrumClass.TONAL, back.drumClass)
        assertFalse(KitBuilderModel.KERF_STAMP in back.source); assertFalse(KitBuilderModel.KERF_WAS in back.source)
        val refused = assertFailsWith<IllegalArgumentException> { KitBuilderModel.open(dir).unkerfPad(1) }
        assertEquals(Copy.KERF_UNDO_NOTHING, refused.message)
    }

    @Test fun `in a kit the pads either side are the twin's neighbours, wrapping at the ends`() {
        val dir = kit(3)
        KitBuilderModel.open(dir).apply { kerfPad(1); save() }
        val recipe = Breed.recipeOf(pad(dir))!!.kerf!!
        assertTrue(recipe.prev != null && recipe.next != null)
        assertFalse(recipe.prev == recipe.next, "pad 3 before it and pad 2 after it are different hits")
        // pads 2 and 3 were measured, not replaced
        assertNull(pad(dir, 2).recipe); assertNull(pad(dir, 3).recipe)
    }

    @Test fun `NONE on a bare twin is the same undo, so the pad sheet's two doors agree`() {
        val dir = kit(1); val original = File(dir, "hit1.wav").readBytes()
        KitBuilderModel.open(dir).apply { kerfPad(1); save() }
        KitBuilderModel.open(dir).apply { untreatPad(1); save() }
        val back = pad(dir)
        assertTrue(original.contentEquals(File(dir, "hit1.wav").readBytes()))
        assertNull(back.recipe); assertEquals(DrumClass.TONAL, back.drumClass); assertFalse(KitBuilderModel.KERF_STAMP in back.source)
    }

    @Test fun `pedals ride a twin and come off it, leaving the twin on top again`() {
        val dir = kit(1); val original = File(dir, "hit1.wav").readBytes()
        KitBuilderModel.open(dir).apply { kerfPad(1); save() }
        val twinBytes = File(dir, "hit1.wav").readBytes(); val label = pad(dir).drumClass
        KitBuilderModel.open(dir).apply { pedalPad(1, com.snipsnap.synth.PedalBoard(listOf(com.snipsnap.synth.PedalBoard.Pick("driven", 0.7f)))); save() }
        val ridden = pad(dir)
        assertTrue(Breed.recipeOf(ridden)!!.kerf != null, "the pedals keep the twin under them")
        assertFalse(PadSheet.isKerfTwin(ridden), "with pedals riding it, the twin is not what is on top")
        assertEquals(Copy.KERF_UNDO_NOTHING, PadSheet.kerfUndoRefusal(ridden, binned(dir)))
        assertFailsWith<IllegalArgumentException> { KitBuilderModel.open(dir).unkerfPad(1) }

        KitBuilderModel.open(dir).apply { untreatPad(1); save() }   // the pedals' NONE
        val again = pad(dir)
        assertTrue(twinBytes.contentEquals(File(dir, "hit1.wav").readBytes()), "the twin's own render is back")
        assertTrue(PadSheet.isKerfTwin(again), "and the twin is on top again")
        assertEquals(label, again.drumClass, "still called what KERF called it")
        KitBuilderModel.open(dir).apply { unkerfPad(1); save() }
        assertTrue(original.contentEquals(File(dir, "hit1.wav").readBytes()), "then the twin's own undo gives the capture")
        assertEquals(DrumClass.TONAL, pad(dir).drumClass)
    }

    @Test fun `BLEED and SLOP re-cut a twin from its numbers, with no new take, so the undo still gives the capture`() {
        val dir = kit(3); val original = File(dir, "hit1.wav").readBytes()
        KitBuilderModel.open(dir).apply { kerfPad(1); save() }
        val made = File(dir, "hit1.wav").readBytes(); val takes = KitBuilderModel.open(dir).priorTakes(1).size
        val before = Breed.recipeOf(pad(dir))!!.kerf!!

        KitBuilderModel.open(dir).apply { recutKerfPad(1, bleed = 2.0, slop = 0.0); save() }
        val recut = Breed.recipeOf(pad(dir))!!.kerf!!
        assertEquals(2.0, recut.bleed); assertEquals(0.0, recut.slop)
        assertEquals(before.patch, recut.patch, "no new measure"); assertEquals(before.draw, recut.draw, "the knife's draw is the pad's own")
        assertFalse(made.contentEquals(File(dir, "hit1.wav").readBytes()), "the audio is the new cut")
        assertEquals(com.snipsnap.audio.WavReader.read(File(dir, "hit1.wav")).frameCount, recut.render().frameCount, "the saved numbers make the saved audio")
        assertEquals(takes, KitBuilderModel.open(dir).priorTakes(1).size, "no new take in the bin")
        assertTrue(PadSheet.isKerfTwin(pad(dir)), "still a bare twin")

        // the same values again write nothing; then back to the first cut, byte for byte
        KitBuilderModel.open(dir).apply { recutKerfPad(1, 1.0, 0.5); save() }
        assertTrue(made.contentEquals(File(dir, "hit1.wav").readBytes()), "the first cut again, from the same numbers")

        KitBuilderModel.open(dir).apply { unkerfPad(1); save() }
        assertTrue(original.contentEquals(File(dir, "hit1.wav").readBytes()), "after re-cuts, the undo is still the capture")
    }

    @Test fun `on a lone twin BLEED moves nothing and SLOP moves the cut, which is why the sheet shows only SLOP there`() {
        val dir = kit(1)
        KitBuilderModel.open(dir).apply { kerfPad(1); save() }
        val made = File(dir, "hit1.wav").readBytes()
        assertNull(Breed.recipeOf(pad(dir))!!.kerf!!.prev)
        KitBuilderModel.open(dir).apply { recutKerfPad(1, bleed = 2.0, slop = 0.5); save() }
        assertTrue(made.contentEquals(File(dir, "hit1.wav").readBytes()), "no hit before it: BLEED has nothing to bleed")
        KitBuilderModel.open(dir).apply { recutKerfPad(1, bleed = 2.0, slop = 0.0); save() }
        assertFalse(made.contentEquals(File(dir, "hit1.wav").readBytes()), "SLOP changes the cut (its edge, at least) with no neighbours")
    }

    @Test fun `a twin with something riding it is not re-cut, and a twin is not made twice`() {
        val dir = kit(1)
        KitBuilderModel.open(dir).apply { kerfPad(1); save() }
        KitBuilderModel.open(dir).apply { pedalPad(1, com.snipsnap.synth.PedalBoard(listOf(com.snipsnap.synth.PedalBoard.Pick("driven", 0.7f)))); save() }
        val ridden = File(dir, "hit1.wav").readBytes()
        assertEquals(Copy.KERF_RIDDEN, assertFailsWith<IllegalArgumentException> { KitBuilderModel.open(dir).recutKerfPad(1, 2.0, 0.0) }.message)
        assertEquals(Copy.KERF_ALREADY, assertFailsWith<IllegalArgumentException> { KitBuilderModel.open(dir).kerfPad(1) }.message, "pedals or not, its audio is a render")
        assertTrue(ridden.contentEquals(File(dir, "hit1.wav").readBytes()), "both refusals touched nothing")
    }

    @Test fun `the whole kit becomes twins, a twin already is counted and serves as a neighbour from its own numbers`() {
        val dir = kit(4)
        KitBuilderModel.open(dir).apply { kerfPad(2); save() }
        val twoNumbers = Breed.recipeOf(pad(dir, 2))!!.kerf!!.patch
        val twoBytes = File(dir, "hit2.wav").readBytes()
        val all = KitBuilderModel.open(dir).run { kerfKit().also { save() } }
        assertEquals(listOf(1, 3, 4), all.made.map { it.slot })
        assertEquals(listOf(2), all.already); assertTrue(all.left.isEmpty())
        assertTrue(twoBytes.contentEquals(File(dir, "hit2.wav").readBytes()), "the twin already was left as it was")
        assertEquals(twoNumbers, Breed.recipeOf(pad(dir, 1))!!.kerf!!.next, "pad 1's next is pad 2's own measurement, not a measure of its render")
        assertEquals(twoNumbers, Breed.recipeOf(pad(dir, 3))!!.kerf!!.prev)
        for (n in 1..4) assertTrue(PadSheet.isKerfTwin(pad(dir, n)), "pad $n")
        val again = KitBuilderModel.open(dir).kerfKit()
        assertTrue(again.made.isEmpty()); assertEquals(listOf(1, 2, 3, 4), again.already)
    }

    @Test fun `a pad too short to measure is refused in words, touching nothing`() {
        val dir = File(temp, "short").apply { mkdirs() }
        WavWriter.write(File(dir, "tick.wav"), Snip(FloatArray(300) { .3f }, 1, WavWriter.MPC_SAMPLE_RATE))
        KitStore.save(Kit(name = "SHORT", pads = listOf(KitPad(slot = 1, sampleFile = "tick.wav")), tempoBpm = 92f), dir)
        val before = File(dir, "tick.wav").readBytes()
        val refused = assertFailsWith<IllegalArgumentException> { KitBuilderModel.open(dir).kerfPad(1) }
        assertEquals(Copy.KERF_TOO_SHORT, refused.message)
        assertTrue(before.contentEquals(File(dir, "tick.wav").readBytes()))
    }
}
