package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.RobinSettings
import com.snipsnap.kit.isRobinChain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 7: a board kept on a round-robin pad lands on the original, the takes re-render, and the board comes off again. */
class RobinBoardTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("robinboard").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun model(): KitBuilderModel {
        val m = KitBuilderModel.create("Board", File(temp, "Board"))
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        return m
    }

    private fun bytes(m: KitBuilderModel, slot: Int) = File(m.kitDir, m.pad(slot)!!.sampleFile).readBytes()

    @Test
    fun `a board kept around a robin pad keeps the takes and comes off again`() {
        val m = model()
        val before = bytes(m, 1)
        Robin.apply(m, 1, RobinSettings(0.6f, 6))
        Robin.around(m, 1) { BoardSheet.apply(m, 1, 0.5f) }
        assertTrue(m.pad(1)!!.isRobinChain)
        assertTrue(BoardSheet.read(m.pad(1)!!.recipe) != null)
        Robin.around(m, 1) { BoardSheet.remove(m, 1) }
        assertTrue(m.pad(1)!!.isRobinChain)
        assertNull(BoardSheet.read(m.pad(1)!!.recipe))
        Robin.undo(m, 1)
        assertFalse(m.pad(1)!!.isRobinChain)
        assertContentEquals(before, bytes(m, 1))
    }

    @Test
    fun `turning round robin off under a kept board leaves a board that removes cleanly`() {
        val m = model()
        val before = bytes(m, 1)
        Robin.apply(m, 1, RobinSettings(0.6f, 6))
        Robin.around(m, 1) { BoardSheet.apply(m, 1, 0.5f) }
        Robin.undo(m, 1)
        assertFalse(m.pad(1)!!.isRobinChain)
        BoardSheet.remove(m, 1)
        assertContentEquals(before, bytes(m, 1))
    }

    @Test
    fun `a printed robin pad previews from the pre-print take one`() {
        val m = model()
        val original = com.snipsnap.audio.WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile))
        Robin.apply(m, 1, RobinSettings(0.6f, 6))
        // Before any print: the lifted original.
        assertContentEquals(original.samples, BoardSheet.preview(m, 1, 0f).samples)
        Robin.around(m, 1) { BoardSheet.apply(m, 1, 0.5f) }
        assertTrue(!Robin.original(m, 1).samples.contentEquals(original.samples), "take one now carries the board")

        assertContentEquals(original.samples, BoardSheet.preview(m, 1, 0f).samples)
    }
}
