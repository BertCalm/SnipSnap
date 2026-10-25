package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.synth.BoardType
import com.snipsnap.synth.PedalBoard
import com.snipsnap.synth.PedalSide
import com.snipsnap.synth.Pedals
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pedal library's door on a kit: `pedalPad`, its readers, its undo, its refusals, and the replay that carries a board. */
class PedalPadTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("pedals").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun board(vararg picks: PedalBoard.Pick) = PedalBoard(picks.toList())

    @Test
    fun `a board treats the whole pad, layers included, reads back as its picks, and unEraPad restores it`() {
        val dir = File(temp, "Board")
        val m = KitBuilderModel.create("Board", dir)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.addGhostLayers(2)
        m.save()
        val files = (listOf(m.pad(2)!!.sampleFile) + m.pad(2)!!.velocityLayers.map { it.sampleFile }).distinct()
        assertTrue(files.size > 1, "the fixture is layered")
        val originals = files.associateWith { File(dir, it).readBytes() }
        val originalSnips = files.associateWith { WavReader.read(File(dir, it)) }

        val picks = board(PedalBoard.Pick("compressed", 0.7f, PedalSide.PRE), PedalBoard.Pick("driven", 1f))
        val treated = m.pedalPad(2, picks)
        m.save()
        for ((f, bytes) in originals) assertFalse(File(dir, f).readBytes().contentEquals(bytes), "$f re-rendered")
        for ((f, snip) in originalSnips) {
            // The file on disk is PCM, so it reads back a few parts in ten million off the float
            // render; the chip-for-chip equality below is file for file, this one is the board's shape.
            val expected = picks.process(snip).samples
            val actual = WavReader.read(File(dir, f)).samples
            assertEquals(expected.size, actual.size, "$f changed length")
            val worst = expected.indices.maxOf { kotlin.math.abs(expected[it] - actual[it]) }
            assertTrue(worst < 1e-3f, "$f is not the board over its own original: off by $worst")
        }
        assertEquals(picks, PadSheet.readPedals(treated.recipe), "the recipe is the board")
        assertNull(PadSheet.read(treated.recipe), "read lights no single chip for a board")
        assertEquals("SUSTAIN 70% PRE · DRIVE 100% AMP", KitDiff.recipeName(treated.recipe!!))
        assertTrue(files.all { f -> m.binContents().any { it.originalName == f } }, "every file is in the bin")
        assertEquals(PadSheet.UnTreat.READY, PadSheet.unTreatState(treated, m.binContents().map { it.originalName }.toSet()))

        m.unEraPad(2)
        m.save()
        assertNull(m.pad(2)!!.recipe, "recipe cleared on undo")
        for ((f, bytes) in originals) assertTrue(File(dir, f).readBytes().contentEquals(bytes), "$f restored byte for byte")
        assertEquals(PadSheet.UnTreat.NOTHING, PadSheet.unTreatState(m.pad(2)!!, emptySet()))
    }

    @Test
    fun `a one-pick board through the door is the chip through its door, file for file`() {
        val dir = File(temp, "OnePick")
        val m = KitBuilderModel.create("OnePick", dir)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        m.characterPad(1, "driven", 0.7f)
        m.pedalPad(2, board(PedalBoard.Pick("driven", 0.7f)))
        m.save()
        assertContentEquals(
            WavReader.read(File(dir, m.pad(1)!!.sampleFile)).samples,
            WavReader.read(File(dir, m.pad(2)!!.sampleFile)).samples,
            "DRIVE as a chip and DRIVE as a board of one differ",
        )
    }

    @Test
    fun `a board at AMT 0 everywhere is a no-op, a WIDE pad refuses, and the replay carries a board`() {
        val dir = File(temp, "Refuse")
        val m = KitBuilderModel.create("Refuse", dir)
        val snare = DrumSynth.snare()
        m.assign(1, snare, DrumClass.SNARE)
        m.assign(2, snare, DrumClass.SNARE)
        m.assign(3, snare, DrumClass.SNARE)
        m.save()
        val before = File(dir, m.pad(1)!!.sampleFile).readBytes()

        val untouched = m.pedalPad(1, board(PedalBoard.Pick("driven", 0f), PedalBoard.Pick("compressed", 0f)))
        assertEquals(m.pad(1), untouched)
        assertTrue(File(dir, m.pad(1)!!.sampleFile).readBytes().contentEquals(before), "never rewritten")
        assertEquals(0, m.binContents().size, "nothing binned")

        BoardSheet.apply(m, 3, 0.7f, BoardType.WIDE)
        val e = assertFailsWith<IllegalArgumentException> { m.pedalPad(3, board(PedalBoard.Pick("driven", 1f))) }
        assertTrue("WIDE" in e.message!!, e.message)

        val picks = board(PedalBoard.Pick("compressed", 0.6f, PedalSide.POST), PedalBoard.Pick("driven", 0.9f))
        val treated = m.pedalPad(1, picks)
        m.save()
        val plan = RecipeReplay.plan(treated.recipe)
        assertIs<RecipeReplay.Plan.Pedals>(plan)
        assertEquals(picks, plan.board)
        val done = RecipeReplay.apply(m, 2, treated.recipe!!, "A02")
        m.save()
        assertEquals(treated.recipe, done.pad.recipe, "A02 carries A01's board now")
        assertContentEquals(
            File(dir, m.pad(1)!!.sampleFile).readBytes(),
            File(dir, m.pad(2)!!.sampleFile).readBytes(),
            "twins through the same board are the same bytes",
        )
        assertNotNull(Pedals.byWord("DRIVE"))
    }

    @Test
    fun `an old single-chip recipe reads as a one-pick board on the pedal's default side`() {
        val dir = File(temp, "Old")
        val m = KitBuilderModel.create("Old", dir)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        val chip = m.characterPad(1, "compressed", 0.7f)
        val applied = PadSheet.read(chip.recipe)!!
        assertEquals(PadSheet.Treatment.Character("compressed"), applied.treatment)
        val asBoard = PedalBoard.fromCharacter(applied.treatment.name, applied.amount)
        assertEquals(board(PedalBoard.Pick("compressed", 0.7f, PedalSide.PRE)), asBoard)
        assertNull(PadSheet.readPedals(chip.recipe), "a chip recipe is not a board recipe")
    }
}
