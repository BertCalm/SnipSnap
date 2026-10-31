package com.snipsnap.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The write behind GRAB and HOLD, on a real kit folder: a pad that was filled
 * after the capture screen opened is not replaced, and an empty pad on the same
 * kit still takes the capture. The mic is out of reach of a test (no ring to
 * snapshot), so this calls the write itself, which is the code the screen runs
 * under the kit lock.
 */
@RunWith(AndroidJUnit4::class)
class PadCaptureWriteTest {

    private val folders = mutableListOf<File>()

    @After
    fun cleanUp() {
        folders.forEach { it.deleteRecursively() }
    }

    /** A kit folder with a kick on pad 3 and nothing else. */
    private fun kitWithPadThree(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "capture-write-test-${System.nanoTime()}").apply { mkdirs() }
        folders += dir
        WavWriter.write(File(dir, "kick.wav"), DrumSynth.kick())
        KitStore.save(
            Kit(name = "CAPTURE WRITE TEST", pads = listOf(KitPad(slot = 3, sampleFile = "kick.wav", drumClass = DrumClass.KICK))),
            dir,
        )
        return dir
    }

    /** Every file under [dir], by path relative to it, so a stray WAV or folder shows. */
    private fun contents(dir: File): List<String> =
        dir.walkTopDown().filter { it != dir }.map { it.relativeTo(dir).path }.sorted().toList()

    @Test
    fun a_pad_filled_meanwhile_is_left_as_it_is_and_nothing_is_written() = runBlocking {
        val dir = kitWithPadThree()
        val filesBefore = contents(dir)
        val kitJsonBefore = File(dir, KitStore.FILE_NAME).readBytes()
        val kickBefore = File(dir, "kick.wav").readBytes()

        val result = writeCaptureToPad(dir, slot = 3, snip = DrumSynth.snare(), drumClass = DrumClass.SNARE, source = emptyMap())

        assertSame("pad 3 held a sample, so the write refuses", CaptureWrite.SlotTaken, result)
        assertEquals("no WAV, no folder", filesBefore, contents(dir))
        assertArrayEquals("kit.json is untouched", kitJsonBefore, File(dir, KitStore.FILE_NAME).readBytes())
        assertArrayEquals("the pad's sample is untouched", kickBefore, File(dir, "kick.wav").readBytes())
        assertEquals("the pad is still the kick", "kick.wav", KitStore.load(dir).pad(3)?.sampleFile)
    }

    @Test
    fun an_empty_pad_on_the_same_kit_takes_the_capture() = runBlocking {
        val dir = kitWithPadThree()

        val result = writeCaptureToPad(dir, slot = 4, snip = DrumSynth.snare(), drumClass = DrumClass.SNARE, source = mapOf("file" to "tape.wav"))

        assertTrue("pad 4 was empty, so the capture lands", result is CaptureWrite.Landed)
        val saved = KitStore.load(dir)
        val landed = saved.pad(4)
        assertNotNull("pad 4 is on disk", landed)
        assertTrue("its WAV is in the folder", File(dir, landed!!.sampleFile).isFile)
        assertEquals("the capture carries its provenance", "tape.wav", landed.source["file"])
        assertEquals("pad 3 is still the kick", "kick.wav", saved.pad(3)?.sampleFile)
        assertEquals(
            "the kit it hands back holds the same pads it saved",
            saved.pads.map { it.slot }.toSet(),
            (result as CaptureWrite.Landed).kit.pads.map { it.slot }.toSet(),
        )
    }

    @Test
    fun a_second_capture_on_the_pad_the_first_filled_is_refused() = runBlocking {
        val dir = kitWithPadThree()

        val first = writeCaptureToPad(dir, slot = 4, snip = DrumSynth.snare(), drumClass = DrumClass.SNARE, source = emptyMap())
        val afterFirst = contents(dir)
        val second = writeCaptureToPad(dir, slot = 4, snip = DrumSynth.hat(0.1f, 40.0), drumClass = DrumClass.HAT_CLOSED, source = emptyMap())

        assertTrue(first is CaptureWrite.Landed)
        assertSame("the first capture holds the pad", CaptureWrite.SlotTaken, second)
        assertEquals("the second wrote nothing", afterFirst, contents(dir))
    }
}
