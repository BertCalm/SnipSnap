package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.kit.InstrumentStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstrumentEngineTest {

    private val rate = 44_100

    /** A 220 Hz tone, half a second, looping from a tenth of a second (a whole number of periods in). */
    private fun looped(): InstrumentEngine.Loaded {
        val periods = 22 // 22 periods of 220 Hz = 0.1 s exactly at 44.1k (4410 frames)
        val loopStart = periods * rate / 220
        val n = rate / 2
        val sample = FloatArray(n) { (0.8 * Math.sin(2 * Math.PI * 220.0 * it / rate)).toFloat() }
        val zone = InstrumentStore.Zone(0, 127, 57, "a.wav", n.toLong(), loopStart.toLong())
        return InstrumentEngine.Loaded(InstrumentStore.Instrument("Loop", 0.2f, listOf(zone)), rate, listOf(sample))
    }

    /** A decaying 220 Hz pluck, no loop, a quarter second. */
    private fun oneShot(): InstrumentEngine.Loaded {
        val n = rate / 4
        val sample = FloatArray(n) { (0.8 * Math.sin(2 * Math.PI * 220.0 * it / rate) * Math.exp(-it / (0.05 * rate))).toFloat() }
        val zone = InstrumentStore.Zone(40, 80, 57, "p.wav", n.toLong(), 0)
        return InstrumentEngine.Loaded(InstrumentStore.Instrument("Pluck", 0.1f, listOf(zone)), rate, listOf(sample))
    }

    private fun render(engine: InstrumentEngine, seconds: Float, outRate: Int = rate): Snip {
        val frames = (seconds * outRate).toInt()
        val out = FloatArray(frames * 2)
        var at = 0
        val block = FloatArray(512)
        while (at < frames) {
            val n = minOf(256, frames - at)
            engine.render(block, n)
            System.arraycopy(block, 0, out, at * 2, n * 2)
            at += n
        }
        return Snip(out, 2, outRate)
    }

    private fun rms(s: Snip, from: Float, to: Float): Double {
        val a = (from * s.frameCount).toInt()
        val b = (to * s.frameCount).toInt()
        var acc = 0.0
        for (i in a until b) acc += s.samples[i * 2].toDouble() * s.samples[i * 2]
        return Math.sqrt(acc / (b - a))
    }

    @Test
    fun `a held note sustains through the loop, and lets go over the release`() {
        val engine = InstrumentEngine(looped(), rate)
        assertTrue(engine.noteOn(57))
        val held = render(engine, 2f)
        // Well past the half-second sample, the loop is still sounding at the same level.
        val early = rms(held, 0.05f, 0.15f)
        val late = rms(held, 0.9f, 1.0f)
        assertTrue(late > 0.9 * early, "the loop sustains: $early -> $late")
        assertEquals(listOf(57), engine.activeNotes)

        engine.noteOff(57)
        val gone = render(engine, 0.5f)
        assertTrue(rms(gone, 0f, 0.1f) > 0.1, "still sounding at the start of the release")
        assertTrue(rms(gone, 0.45f, 0.5f) < 1e-4, "silent after the 0.2 s release")
        assertTrue(engine.activeNotes.isEmpty(), "the voice is reaped")
    }

    @Test
    fun `a note above the root plays higher by the right ratio, at the device's rate`() {
        for (outRate in intArrayOf(44_100, 48_000)) {
            val engine = InstrumentEngine(looped(), outRate)
            engine.noteOn(57 + 7) // a fifth up: 220 * 1.498 = 329.6 Hz
            val out = render(engine, 1f, outRate)
            val mono = Snip(FloatArray(out.frameCount) { out.samples[it * 2] }, 1, outRate)
            val est = Pitch.detect(mono)!!
            assertTrue(abs(est.hz - 329.6f) < 4f, "at $outRate Hz: ${est.hz}")
            // And the root itself lands where the sample is, whatever the device rate.
            val root = InstrumentEngine(looped(), outRate).also { it.noteOn(57) }
            val r = render(root, 1f, outRate)
            val rm = Snip(FloatArray(r.frameCount) { r.samples[it * 2] }, 1, outRate)
            assertTrue(abs(Pitch.detect(rm)!!.hz - 220f) < 3f, "root at $outRate: ${Pitch.detect(rm)!!.hz}")
        }
    }

    @Test
    fun `an unlooped sample plays to its end and stops, and notes outside every zone are silent`() {
        val engine = InstrumentEngine(oneShot(), rate)
        assertTrue(engine.noteOn(57))
        val out = render(engine, 0.5f)
        assertTrue(rms(out, 0f, 0.05f) > 0.1)
        // Fractions of the half-second render: 0.6..1.0 is 0.3..0.5 s, past the sample's quarter second.
        assertTrue(rms(out, 0.6f, 1.0f) < 1e-6, "nothing after the sample's quarter second")
        assertTrue(engine.activeNotes.isEmpty())
        assertFalse(engine.noteOn(30), "below the zone")
        assertFalse(engine.noteOn(90), "above the zone")
        assertTrue(engine.activeNotes.isEmpty())
    }

    @Test
    fun `polyphony is capped by stealing the oldest, and eight notes never crack`() {
        val engine = InstrumentEngine(looped(), rate, maxVoices = 4)
        for (n in 0 until 6) engine.noteOn(50 + n)
        assertEquals(listOf(52, 53, 54, 55), engine.activeNotes, "the two oldest were stolen")
        val loud = InstrumentEngine(looped(), rate)
        for (n in 0 until 8) loud.noteOn(45 + n, 1f)
        val out = render(loud, 0.2f)
        assertTrue(out.samples.all { it <= 1f && it >= -1f }, "soft clipped inside full scale")
        assertTrue(out.peak() > 0.5f, "and still loud")
    }

    @Test
    fun `a looped note lets go even when the instrument asks for an endless release`() {
        val base = looped()
        val endless = InstrumentEngine.Loaded(base.instrument.copy(release = Float.POSITIVE_INFINITY), rate, base.samples)
        val engine = InstrumentEngine(endless, rate)
        assertTrue(engine.noteOn(57))
        render(engine, 0.2f)
        engine.noteOff(57)
        // Capped at MAX_RELEASE_SECONDS: the fade has an end, so the voice does too.
        render(engine, InstrumentStore.MAX_RELEASE_SECONDS + 1f)
        assertTrue(engine.activeNotes.isEmpty(), "an infinite release left the note sounding")
    }

    /** A ramp, frame i holding i / 1000, looping from [loopStart]: every frame is its own address. */
    private fun ramp(n: Int, loopStart: Int): InstrumentEngine.Loaded {
        val sample = FloatArray(n) { it / 1000f }
        val zone = InstrumentStore.Zone(0, 127, 57, "r.wav", n.toLong(), loopStart.toLong())
        return InstrumentEngine.Loaded(InstrumentStore.Instrument("Ramp", 0.2f, listOf(zone)), rate, listOf(sample))
    }

    /** What the engine puts out for a sample value: the voice level, which the ramp stays well under the soft clip for. */
    private fun heard(frame: Float) = frame / 1000f * InstrumentEngine.VOICE_LEVEL

    /**
     * The loop is the file's [loopStart, end): the MPC's own, and the one a file that closes exactly is cut for. The
     * read plays the LAST frame and then the loop's first; the period is end - loopStart. (It used to wrap at end - 1:
     * the last frame was never played and each wrap slipped a frame.) A ten-frame ramp looping from frame 4 reads
     * 0 1 2 3 4 5 6 7 8 9, then 4 5 6 7 8 9, then again.
     */
    @Test
    fun `a loop plays its last frame and wraps to its first, a period of end minus loopStart`() {
        val engine = InstrumentEngine(ramp(10, 4), rate)
        assertTrue(engine.noteOn(57))
        val out = render(engine, 30f / rate)
        val want = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 4, 5, 6, 7, 8, 9, 4, 5, 6, 7, 8, 9, 4, 5, 6, 7, 8, 9, 4, 5)
        for (i in want.indices) assertEquals(heard(want[i].toFloat()), out.samples[i * 2], 1e-6f, "output frame $i should read file frame ${want[i]}")
        assertEquals(listOf(57), engine.activeNotes, "still sounding")
    }

    /** The frame between the last and the first is interpolated across the seam, not clamped at the last. */
    @Test
    fun `half way between a loop's last frame and its first the read is their mean`() {
        val half = InstrumentEngine(ramp(10, 4), rate)
        assertTrue(half.noteOn(57 - 12)) // an octave down: half a file frame per output frame
        val out = render(half, 40f / rate)
        // File position 0.5 * i. Frame 9 + 0.5 sits between the last frame (9) and the loop's first (4): their mean is 6.5.
        val atSeam = 19 // position 9.5
        assertEquals(heard(6.5f), out.samples[atSeam * 2], 1e-6f, "the seam is interpolated into the loop's first frame")
        // And it continues from the first frame: position 10 wraps to 4, position 10.5 is 4.5.
        assertEquals(heard(4f), out.samples[20 * 2], 1e-6f)
        assertEquals(heard(4.5f), out.samples[21 * 2], 1e-6f)
    }

    /**
     * Between frames the read is a four-point cubic, the phone's own (PadEngine.cpp). A parabola, frame i holding
     * i * i / 10000, is read exactly between frames where a straight line is not; and across the seam the taps are the
     * loop's own: two on from the last frame is the loop's second, and the frame before the loop's first is the file's.
     * (The ramp above cannot tell these apart: it is symmetric about the seam.)
     */
    @Test
    fun `between frames the read is a cubic through the loop's own neighbours`() {
        val sample = FloatArray(10) { it * it / 10_000f }
        val zone = InstrumentStore.Zone(0, 127, 57, "p.wav", 10L, 4L)
        val loaded = InstrumentEngine.Loaded(InstrumentStore.Instrument("Parabola", 0.2f, listOf(zone)), rate, listOf(sample))
        val engine = InstrumentEngine(loaded, rate)
        assertTrue(engine.noteOn(57 - 12)) // half a file frame per output frame
        val out = render(engine, 40f / rate)
        val level = InstrumentEngine.VOICE_LEVEL
        assertEquals(0.001225f * level, out.samples[7 * 2], 1e-7f, "3.5 is on the parabola (a straight line reads 0.00125)")
        assertEquals(0.0049f * level, out.samples[19 * 2], 1e-7f, "9.5: frames 8 and 9, then the loop's 4 and 5")
        assertEquals(0.0016f * level, out.samples[20 * 2], 1e-7f, "10 wraps to 4")
        assertEquals(0.002025f * level, out.samples[21 * 2], 1e-7f, "4.5: frames 3, 4, 5, 6 - the parabola again")
    }

    /**
     * The point of the fix, as a listener hears it: 22 periods of 220 Hz is 4410 frames and the loop [4410, 22050) is
     * 88 periods exactly, so a held note is the unbroken sine for as long as it is held. With the loop one frame short
     * every wrap slipped the phase by a frame (0.03 rad), and the note walked out of the sine it was cut from.
     */
    @Test
    fun `a loop that closes exactly plays as one unbroken sine across many wraps`() {
        val engine = InstrumentEngine(looped(), rate)
        assertTrue(engine.noteOn(57))
        val seconds = 3f // 22050-frame file, 17640-frame loop: seven wraps
        val out = render(engine, seconds)
        var worst = 0.0
        for (i in 0 until out.frameCount) {
            val want = InstrumentEngine.VOICE_LEVEL * 0.8 * Math.sin(2 * Math.PI * 220.0 * i / rate)
            worst = maxOf(worst, abs(out.samples[i * 2] - want))
        }
        assertTrue(worst < 2e-5, "the held note leaves its sine by $worst")
    }

    /** A note fast enough to cross the end several times in one frame keeps sounding and stays finite. */
    @Test
    fun `a fast note over a short loop wraps repeatedly and never dies`() {
        val engine = InstrumentEngine(ramp(10, 8), rate) // a two-frame loop: frames 8 and 9
        assertTrue(engine.noteOn(57 + 24)) // two octaves up: four file frames per output frame
        val out = render(engine, 64f / rate)
        assertTrue(out.samples.all { it.isFinite() }, "finite")
        assertTrue(out.samples.any { it != 0f }, "sounding")
        assertEquals(listOf(57 + 24), engine.activeNotes, "still held")
        // After the first pass every read is inside the two-frame loop: file values 0.008 to 0.009.
        for (i in 4 until 64) assertTrue(out.samples[i * 2] in heard(7.99f)..heard(9.01f), "frame $i read ${out.samples[i * 2]}")
    }

    /** A loop of fewer than two frames (one that starts on the last frame) is not a loop: it plays once and ends, as it always did. */
    @Test
    fun `a loop of fewer than two frames plays once and ends`() {
        val engine = InstrumentEngine(ramp(10, 9), rate)
        assertTrue(engine.noteOn(57))
        render(engine, 30f / rate)
        assertTrue(engine.activeNotes.isEmpty(), "the one-frame loop ended")
    }

    @Test
    fun `an output rate that is not a rate is refused at the door`() {
        // Refused before any render: with the door gone, a looped note at rate 0 wraps forever and a negative rate reads
        // before the sample's first frame.
        for (outRate in intArrayOf(0, -rate)) {
            val refused = assertFailsWith<IllegalArgumentException>("output rate $outRate: the engine's door must refuse it") {
                InstrumentEngine(looped(), outRate)
            }
            assertEquals("output rate must be positive, got $outRate", refused.message)
        }
    }
}
