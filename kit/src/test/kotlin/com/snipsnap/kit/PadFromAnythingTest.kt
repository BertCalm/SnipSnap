package com.snipsnap.kit

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.mpc3.MpcFormat
import com.snipsnap.mpc3.MpcFormats
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PadFromAnythingTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("pad").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    /** A decaying 220 Hz note with a second harmonic, one second. */
    private fun note220(seconds: Float = 1f): Snip = Snip(
        FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            ((0.5 * sin(2 * PI * 220.0 * t) + 0.15 * sin(2 * PI * 440.0 * t)) * Math.exp(-2.0 * t)).toFloat()
        },
        1, rate,
    )

    /** A burst of decaying noise: a hit with no note in it. */
    private fun burst(seconds: Float = 0.3f): Snip {
        val rnd = java.util.Random(5)
        return Snip(FloatArray((seconds * rate).toInt()) { i -> ((rnd.nextFloat() * 2f - 1f) * 0.8 * Math.exp(-i / (0.08 * rate))).toFloat() }, 1, rate)
    }

    private fun rms(s: Snip, fromFrame: Int, toFrame: Int): Double {
        var acc = 0.0
        val ch = s.channels
        for (i in fromFrame * ch until toFrame * ch) acc += s.samples[i] * s.samples[i].toDouble()
        return sqrt(acc / ((toFrame - fromFrame) * ch))
    }

    private fun body(r: PadFromAnything.Result): Snip {
        val ch = r.sample.channels
        val from = r.loopStartFrame.toInt()
        return Snip(r.sample.samples.copyOfRange(from * ch, r.sample.samples.size), ch, r.sample.sampleRate)
    }

    @Test
    fun `a pitched hit becomes a pad rooted where it sounds, its note kept, its loop seamless`() {
        val r = PadFromAnything.build("Test Pad", note220(), PadFromAnything.Spec(depth = 40f, bloom = 0.3f))
        assertTrue(r.pitched && r.clear, "a note takes the clear stretch")
        assertEquals(57, r.rootMidi, "220 Hz is A3")
        assertEquals("A3", r.rootName)
        assertEquals(40f, r.depthUsed)
        assertEquals((PadFromAnything.HEAD_SEC * rate).toInt(), r.loopStartFrame.toInt())
        assertEquals(((PadFromAnything.HEAD_SEC + PadFromAnything.LOOP_SEC) * rate).toInt(), r.sample.frameCount, "the head, then the loop")
        assertEquals(PadFromAnything.RELEASE, r.program.volumeRelease)
        assertEquals(r.loopStartFrame, r.program.keygroups.single().layers.single().loopStartFrame)

        // The note survives the stretch: the loop body still sings A3.
        val est = Pitch.detect(body(r))
        assertTrue(est != null && abs(est.hz - 220f) < 220f * 0.03f, "the body keeps its pitch: ${est?.hz}")

        // The seam: the sample's last frame is exactly the frame before the loop start,
        // and the level on both sides of the wrap agrees.
        val ch = r.sample.channels
        val last = r.sample.samples[(r.sample.frameCount - 1) * ch]
        val beforeStart = r.sample.samples[(r.loopStartFrame.toInt() - 1) * ch]
        assertEquals(beforeStart, last, 1e-5f, "the wrap lands where it continues from")
        val win = (0.05f * rate).toInt()
        val endLevel = rms(r.sample, r.sample.frameCount - win, r.sample.frameCount)
        val startLevel = rms(r.sample, r.loopStartFrame.toInt(), r.loopStartFrame.toInt() + win)
        assertTrue(abs(endLevel - startLevel) < 0.3 * startLevel, "no pump across the wrap: $endLevel vs $startLevel")

        // BLOOM: the arrival ramps in.
        assertTrue(rms(r.sample, 0, win) < 0.5 * rms(r.sample, (0.5f * rate).toInt(), (0.5f * rate).toInt() + win), "the pad swells in")

        // Same seed, same bytes.
        val again = PadFromAnything.build("Test Pad", note220(), PadFromAnything.Spec(depth = 40f, bloom = 0.3f))
        assertTrue(again.sample.samples.contentEquals(r.sample.samples))
    }

    @Test
    fun `an unpitched hit is accepted as a drone, not refused`() {
        val r = PadFromAnything.build("Drone", burst())
        assertFalse(r.pitched)
        assertFalse(r.clear, "a drum takes the wash")
        assertEquals(PadFromAnything.DRONE_ROOT, r.rootMidi)
        assertEquals(null, r.detectedHz)
        assertEquals(((PadFromAnything.HEAD_SEC + PadFromAnything.LOOP_SEC) * rate).toInt(), r.sample.frameCount)
        val ch = r.sample.channels
        val last = r.sample.samples[(r.sample.frameCount - 1) * ch]
        val beforeStart = r.sample.samples[(r.loopStartFrame.toInt() - 1) * ch]
        assertEquals(beforeStart, last, 1e-5f, "the wrap lands where it continues from")
        var peak = 0f
        for (v in r.sample.samples) peak = maxOf(peak, abs(v))
        assertEquals(PadFromAnything.PEAK, peak, 1e-3f)
    }

    @Test
    fun `the depth gives where the minute or the loop demand it, and the edges refuse honestly`() {
        assertEquals(40f, PadFromAnything.depthFor(1f, 40f))
        assertEquals(7.5f, PadFromAnything.depthFor(8f, 40f), 1e-4f, "eight seconds at x40 would outrun the minute")
        assertEquals(100f, PadFromAnything.depthFor(0.05f, 8f), "fifty milliseconds must reach five seconds")
        val long = PadFromAnything.build("Long", note220(8f), PadFromAnything.Spec(depth = 40f))
        assertEquals(7.5f, long.depthUsed, 1e-4f)
        assertFailsWith<IllegalArgumentException> { PadFromAnything.build("Blip", burst(0.02f)) }
        assertFailsWith<IllegalArgumentException> { PadFromAnything.build("Epic", note220(31f)) }
        assertFailsWith<IllegalArgumentException> { PadFromAnything.Spec(depth = 4f) }
    }

    @Test
    fun `export lands the dual-generation layout with the loop in both programs`() {
        val card = File(temp, "card")
        val r = PadFromAnything.export("Test Pad", note220(), card)
        val xty = File(card, "Test Pad.xty")
        val dataDir = File(card, "Test Pad_[TrackData]")
        assertTrue(xty.isFile)
        assertTrue(File(dataDir, "${r.sampleStem}.wav").isFile)
        val xpm = File(dataDir, "Test Pad.xpm")
        assertTrue(xpm.isFile, "the MPC 2 twin sits beside the sample")
        assertEquals(MpcFormat.MPC3_ACVS, MpcFormats.detect(xty))
        assertEquals(MpcFormat.MPC2_XML, MpcFormats.detect(xpm))
        val text = xpm.readText()
        assertTrue("<SliceLoop>1</SliceLoop>" in text && "<SliceLoopStart>${r.loopStartFrame}</SliceLoopStart>" in text, "the loop rides the .xpm")
        assertFailsWith<java.io.IOException> { PadFromAnything.export("Test Pad", note220(), card) }
        PadFromAnything.export("Test Pad", note220(), card, overwrite = true)
    }
}
