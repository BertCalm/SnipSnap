package com.snipsnap.shell

import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GRAB and HOLD land as a tape plus a cut of it — the pad RE-TRIM can go back to. */
class PadTapeTest {

    private val rate = 44_100
    private val temp: File = Files.createTempDirectory("padtape").toFile()
    private val snips = File(temp, SnipStore.DIR)

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    /** A second of room, a kick, half a second, a snare, a tail — the ring's last three seconds. */
    private fun ring(): FloatArray {
        val total = FloatArray(rate * 3)
        fun put(at: Int, hit: FloatArray) { for (i in hit.indices) if (at + i < total.size) total[at + i] += hit[i] * 0.8f }
        put(rate, DrumSynth.kick().samples)
        put(rate * 3 / 2 + rate / 4, DrumSynth.snare().samples)
        return total
    }

    @Test
    fun `grab cuts the last hit out of the tape and the pad is exactly that cut`() {
        val landing = assertNotNull(PadTape.grab(ring(), rate))
        assertTrue(landing.tape.frameCount > 0)
        assertTrue(landing.cut.first > 0, "the last hit is not at the head of the tape")
        assertEquals(landing.tape.frameCount, landing.cut.last + 1, "GRAB runs to the tape's end")
        assertTrue(landing.pad.frameCount >= PadCapture_minFrames(), "long enough to be a hit")
        assertTrue(landing.pad.samples.contentEquals(Retrim.cut(landing.tape, landing.cut).samples))
        // The snare, not the kick: the cut starts after the tape's midpoint.
        assertTrue(landing.cut.first > landing.tape.frameCount / 3, "cut at ${landing.cut.first} of ${landing.tape.frameCount}")
    }

    @Test
    fun `hold keeps the whole tape as the pad`() {
        val landing = assertNotNull(PadTape.hold(ring(), rate))
        assertEquals(0 until landing.tape.frameCount, landing.cut)
        assertEquals(landing.tape.frameCount, landing.pad.frameCount)
    }

    @Test
    fun `silence is nothing to grab, not a tape`() {
        assertNull(PadTape.grab(FloatArray(rate), rate))
        assertNull(PadTape.hold(FloatArray(rate), rate))
        assertNull(PadTape.grab(FloatArray(0), rate))
    }

    @Test
    fun `written to the shelf and tagged, the pad resolves back to its cut and BACK ONTO reproduces it`() {
        val landing = assertNotNull(PadTape.grab(ring(), rate))
        val file = SnipStore.commitPrepared(landing.tape, temp, 5_000L)
        assertEquals(snips, file.parentFile)
        val tag = PadTape.tag(file, landing)
        assertEquals(file.name, tag["file"])
        assertEquals("5000", tag["capturedAtMillis"])
        assertEquals(landing.cut.first.toString(), tag[Retrim.IN_KEY])
        assertEquals((landing.cut.last + 1).toString(), tag[Retrim.OUT_KEY])

        val pad = com.snipsnap.kit.KitPad(slot = 1, sampleFile = "A01_Snare_01.wav", source = tag)
        val ready = assertIs<Retrim.Ready>(Retrim.of(pad, snips))
        assertEquals(file, ready.file)
        val cut = assertNotNull(ready.cut)
        // What TAPE loads is the file; the cut of it is the pad's audio, bit for bit.
        val reread = WavReader.read(file)
        val again = Retrim.cut(reread, cut.inFrame until cut.outFrame)
        assertEquals(landing.pad.frameCount, again.frameCount)
        var maxDiff = 0f
        for (i in again.samples.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(again.samples[i] - landing.pad.samples[i]))
        assertTrue(maxDiff < 1e-3f, "16-bit round trip only: $maxDiff")
    }

    @Test
    fun `commit is prepare then commitPrepared - a SNIP is unchanged by the split`() {
        val a = SnipStore.commit(ring(), rate, File(temp, "a"), 1_000L)
        val b = SnipStore.commitPrepared(SnipStore.prepare(ring(), rate)!!, File(temp, "b"), 1_000L)
        assertTrue(a.readBytes().contentEquals(b.readBytes()))
        assertEquals(a.name, b.name)
        // The silent fallback SNIP keeps is exactly what GRAB refuses.
        assertNotNull(SnipStore.prepare(FloatArray(rate), rate))
        assertNull(SnipStore.prepare(FloatArray(rate), rate, silentFallback = false))
    }

    private fun PadCapture_minFrames(): Int = com.snipsnap.audio.PadCapture.MIN_ONESHOT_MS * rate / 1000
}
