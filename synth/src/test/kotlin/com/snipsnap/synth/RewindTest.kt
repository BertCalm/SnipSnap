package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewindTest {

    private val rate = 44100
    private val snare = Thump.render(ThumpVoice.SNARE)

    /** A noise burst after [leadSec] of silence that decays 60 dB over [sec] (a short, clean hit). */
    private fun burst(leadSec: Float = 0.05f, sec: Float = 0.06f): Snip {
        val lead = (leadSec * rate).toInt()
        val n = (sec * rate).toInt()
        val r = Random(7)
        return Snip(FloatArray(lead + n + rate / 10) { i ->
            val t = i - lead
            if (t < 0 || t >= n) 0f else (0.8f * (2f * r.nextFloat() - 1f) * Math.pow(10.0, -3.0 * t / n).toFloat())
        }, 1, rate)
    }

    /** A steady sine, [sec] long, at full level from the first frame. */
    private fun sine(hz: Float = 220f, sec: Float = 2f): Snip =
        Snip(FloatArray((sec * rate).toInt()) { i -> (0.6 * sin(2 * PI * hz * i / rate)).toFloat() }, 1, rate)

    private fun argMax(x: FloatArray, from: Int, until: Int): Int {
        var at = from
        var best = -1f
        for (i in from until minOf(until, x.size)) { val a = abs(x[i]); if (a > best) { best = a; at = i } }
        return at
    }

    private fun peak(x: FloatArray, from: Int, until: Int): Float {
        var p = 0f
        for (i in from until minOf(until, x.size)) p = maxOf(p, abs(x[i]))
        return p
    }

    @Test
    fun `MIX 0 is an exact copy, and a value that is not a number is left at the default`() {
        val off = Rewind.process(snare, mapOf("MIX" to 0f))
        assertContentEquals(snare.samples, off.samples)
        assertEquals(snare.channels, off.channels)
        val nan = Rewind.process(snare, mapOf("MIX" to Float.NaN))
        assertContentEquals(Rewind.process(snare).samples, nan.samples, "a NaN MIX is not the default")
        val silent = Snip(FloatArray(4410), 1, rate)
        assertContentEquals(silent.samples, Rewind.process(silent).samples, "a silent pad is not left alone")
    }

    @Test
    fun `the backwards attack lands two windows after the hit, and TIME moves it from 240 ms to 1 s`() {
        val hit = burst()
        val onset = (0.05f * rate).toInt()
        for ((time, sec) in listOf(0f to Rewind.TIME_MIN_SEC, 0.5f to null, 1f to Rewind.TIME_MAX_SEC)) {
            val w = Rewind.windowFrames(time, rate)
            if (sec != null) assertEquals(sec * rate, w.toFloat(), 2f, "TIME $time: the window is not $sec s")
            val out = Rewind.process(hit, mapOf("MIX" to 1f, "TIME" to time, "REPEAT" to 0f)).samples
            // The dry burst is over (60 dB down) well before the first window ends, so after it only the copy sounds.
            val lands = argMax(out, onset + w, out.size)
            // Twice the window after the hit, less the two edges (the grid starts one edge early, and the attack is
            // read one edge in from its window's start).
            val edge = minOf((Rewind.EDGE_SEC * rate).toInt(), w / 8)
            val expected = onset + 2 * w - 2 * edge
            val ms = 1000f * (lands - expected) / rate
            assertTrue(abs(ms) <= 4f, "TIME $time: the backwards attack lands ${"%.1f".format(ms)} ms from twice the window")
            // It is a swell: a quarter of a burst's length before the landing the copy is far quieter than at it.
            val before = peak(out, lands - (0.03f * rate).toInt() - 64, lands - (0.03f * rate).toInt() + 64)
            assertTrue(before < 0.35f * abs(out[lands]), "TIME $time: the copy does not rise into its attack ($before against ${abs(out[lands])})")
        }
    }

    @Test
    fun `a drum that starts on its first frame keeps its attack's peak in the backwards copy`() {
        // ONSET's snare is over 40 dB up on frame 0 and peaks 0.7 ms later: a closing fade on the attack cost it 6 dB.
        val hit = Onset.render(OnsetVoice.SNARE)
        val w = Rewind.windowFrames(0.5f, rate)
        val out = Rewind.process(hit, mapOf("MIX" to 1f, "TIME" to 0.5f, "REPEAT" to 0f)).samples
        val dry = peak(out, 0, w)
        val back = peak(out, w, out.size)
        assertTrue(back >= 0.9f * dry, "the backwards attack peaks at ${back / dry} of the hit")
    }

    @Test
    fun `REPEAT 0 is one backwards copy, and REPEAT strikes the attack again forward, darker and quieter`() {
        val hit = burst()
        val onset = (0.05f * rate).toInt()
        val w = Rewind.windowFrames(0.5f, rate)
        val once = Rewind.process(hit, mapOf("MIX" to 1f, "TIME" to 0.5f, "REPEAT" to 0f)).samples
        assertTrue(peak(once, onset + 2 * w, once.size) < 1e-6f, "REPEAT 0 left something after the backwards copy")

        val again = Rewind.process(hit, mapOf("MIX" to 1f, "TIME" to 0.5f, "REPEAT" to 1f)).samples
        val back = peak(again, onset + w, onset + 2 * w)
        val edge = minOf((Rewind.EDGE_SEC * rate).toInt(), w / 8)
        val forward = argMax(again, onset + 2 * w - 2 * edge, onset + 3 * w)
        val ms = 1000f * (forward - (onset + 2 * w)) / rate
        // The forward repeat is the burst again: its loudest frame at its start, right where the backwards copy ended.
        assertTrue(ms in 0f..8f, "the forward repeat's attack is ${"%.1f".format(ms)} ms into its window")
        val ratio = abs(again[forward]) / back
        assertTrue(ratio < Rewind.MAX_FEEDBACK && ratio > 0.2f, "the forward repeat is at $ratio of the backwards copy, not under feedback ${Rewind.MAX_FEEDBACK}")
        // And backwards again in the window after that, quieter still.
        val third = peak(again, onset + 3 * w, onset + 4 * w)
        assertTrue(third < abs(again[forward]) && third > 1e-4f, "the third pass is $third against ${abs(again[forward])}")
    }

    @Test
    fun `a held note crosses the window seams without a click`() {
        val note = sine()
        val step = { x: FloatArray -> var s = 0f; for (i in 1 until x.size) s = maxOf(s, abs(x[i] - x[i - 1])); s }
        val dry = step(note.samples)
        for (time in listOf(0f, 0.5f, 1f)) for (repeat in listOf(0f, 1f)) {
            val out = Rewind.process(note, mapOf("MIX" to 1f, "TIME" to time, "REPEAT" to repeat)).samples
            // Dry plus a backwards copy of the same sine can at most double the slope; a click is a jump of the whole wave.
            assertTrue(step(out) <= 2.2f * dry, "TIME $time REPEAT $repeat: a step of ${step(out)} against the sine's $dry")
        }
    }

    @Test
    fun `on a snare, the whole backwards copy at the longest TIME lands inside the rack's tail budget, before its fade`() {
        val m = mapOf("MIX" to 1f, "TIME" to 1f, "REPEAT" to 0f)
        val alone = Rewind.process(snare, m)
        val fadeStarts = snare.frameCount + ((FxChain.MAX_CHAIN_TAIL_SECONDS - FxChain.CAP_FADE_SECONDS) * rate).toInt()
        assertTrue(alone.frameCount <= fadeStarts, "the copy runs ${alone.frameCount - fadeStarts} frames into the rack's fade")
        assertContentEquals(alone.samples, FxChain(rewind = m).process(snare).samples, "the rack's budget touched the copy")
    }

    @Test
    fun `every setting stays finite, inside the peak and inside its own tail cap`() {
        val r = Random(11)
        val note = sine(sec = 0.8f)
        for (src in listOf(snare, burst(), note)) repeat(12) {
            val m = Rewind.scramble(r)
            val out = Rewind.process(src, m)
            assertTrue(out.samples.all { it.isFinite() && abs(it) <= 1f }, "$m: a sample out of range")
            assertTrue(out.frameCount <= src.frameCount + (Rewind.MAX_TAIL_SECONDS * rate).toInt(), "$m: past the tail cap")
            assertTrue(out.peak() <= src.peak() + 1e-5f, "$m: louder than the pad")
        }
    }

    @Test
    fun `stereo is linked, so two equal channels stay equal, and mono stays mono`() {
        val mono = burst()
        val stereo = Snip(FloatArray(mono.frameCount * 2) { i -> mono.samples[i / 2] }, 2, rate)
        val out = Rewind.process(stereo, mapOf("MIX" to 1f, "REPEAT" to 0.5f))
        assertEquals(2, out.channels)
        for (f in 0 until out.frameCount) assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "frame $f: the channels parted")
        val left = FloatArray(out.frameCount) { out.samples[it * 2] }
        assertContentEquals(Rewind.process(mono, mapOf("MIX" to 1f, "REPEAT" to 0.5f)).samples, left, "stereo is not the mono render per channel")
        assertEquals(1, Rewind.process(mono).channels)
    }

    @Test
    fun `the same pad and macros render the same samples`() {
        val m = Rewind.scramble(Random(3))
        assertContentEquals(Rewind.process(snare, m).samples, Rewind.process(snare, m).samples)
    }
}
