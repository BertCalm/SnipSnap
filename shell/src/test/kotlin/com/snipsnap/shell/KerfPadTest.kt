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
