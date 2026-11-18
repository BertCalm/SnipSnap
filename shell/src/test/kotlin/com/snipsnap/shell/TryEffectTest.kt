package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.synth.PedalBoard
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Try before you keep: the preview is what KEEP gives, and the real kit is never written. */
class TryEffectTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("try-effect").toFile()
    private val scratchRoot = File(temp, "cache/${TryEffect.SCRATCH_DIR}")

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val drive = PedalBoard(listOf(PedalBoard.Pick("driven", 1f)))
    private val echo = PedalBoard(listOf(PedalBoard.Pick("delayed", 0.7f)))

    private fun kit(name: String): File {
        val dir = File(temp, name)
        val m = KitBuilderModel.create(name, dir)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        return dir
    }

    private fun snapshot(dir: File): Map<String, List<Byte>> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }

    @Test
    fun `what you hear is what keep gives, and the kit is not touched`() {
        val dir = kit("Keep")
        val before = snapshot(dir)

        val heard = assertNotNull(TryEffect.hear(dir, scratchRoot, 2, 60f) { it.pedalPad(2, drive) })
        assertEquals(before, snapshot(dir), "a preview writes nothing in the kit, its bin included")
        assertFalse(File(scratchRoot, "Keep").exists(), "the scratch copy is gone")

        val real = KitBuilderModel.open(dir)
        real.pedalPad(2, drive)
        real.save()
        val kept = WavReader.read(File(dir, real.kit.pad(2)!!.sampleFile))
        assertContentEquals(kept.samples, heard.samples, "KEEP gives exactly the sound the preview played")
    }

    @Test
    fun `a pad that already has an effect previews the swap the way the real kit would make it`() {
        val dir = kit("Swap")
        val m = KitBuilderModel.open(dir)
        m.pedalPad(2, drive)
        m.save()
        val before = snapshot(dir)

        // The same landing an apply runs: restore from the bin, then the new board. The copy has the
        // bin, so the restore finds the original and the echo goes on the clean sound, not the driven one.
        val land: (KitBuilderModel) -> Unit = { f ->
            assertEquals(PadSheet.UnTreat.READY, PadSheet.unTreatState(f.pad(2)!!, f.binContents().map { it.originalName }.toSet()))
            f.unEraPad(2)
            f.pedalPad(2, echo)
        }
        val heard = assertNotNull(TryEffect.hear(dir, scratchRoot, 2, 60f, land))
        assertEquals(before, snapshot(dir), "the driven pad is untouched until KEEP")

        val real = KitBuilderModel.open(dir)
        land(real)
        real.save()
        assertContentEquals(WavReader.read(File(dir, real.kit.pad(2)!!.sampleFile)).samples, heard.samples)
    }

    @Test
    fun `a landing that changes nothing has nothing to hear`() {
        val dir = kit("Nothing")
        assertNull(TryEffect.hear(dir, scratchRoot, 2, 60f) { })
        assertNull(TryEffect.hear(dir, scratchRoot, 9, 60f) { it.pedalPad(2, drive) }, "an empty slot has nothing to play")
        assertFalse(File(scratchRoot, "Nothing").exists())
    }

    @Test
    fun `a refusal inside the landing leaves no copy behind and the kit as it was`() {
        val dir = kit("Refused")
        val before = snapshot(dir)
        assertFailsWith<IllegalStateException> {
            TryEffect.hear(dir, scratchRoot, 2, 60f) { f ->
                f.pedalPad(2, drive)
                error("refused after a write")
            }
        }
        assertEquals(before, snapshot(dir))
        assertFalse(File(scratchRoot, "Refused").exists(), "the copy is cleaned up when the landing throws")
        assertTrue(dir.isDirectory)
    }
}
