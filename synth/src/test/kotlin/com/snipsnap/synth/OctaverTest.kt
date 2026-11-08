package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OctaverTest {

    private val rate = 44100
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val lowTom = Thump.render(ThumpVoice.TOM, mapOf("TUNE" to 0.2f))

    /** A sine of [hz] that starts after [leadSec] of silence and decays at 30 dB a second (a long, clean hit). */
    private fun tone(hz: Float, leadSec: Float = 0f, sec: Float = 0.5f): Snip {
        val lead = (leadSec * rate).toInt()
        val n = (sec * rate).toInt()
        return Snip(FloatArray(lead + n) { i ->
            if (i < lead) 0f else {
                val t = (i - lead).toFloat() / rate
                (0.8 * sin(2 * PI * hz * t) * Math.pow(10.0, -1.5 * t)).toFloat()
            }
        }, 1, rate)
    }

    /** The strongest frequency of [x] between [lo] and [hi] Hz, on a 1 Hz grid. */
    private fun peakHz(x: FloatArray, lo: Int, hi: Int): Int {
        var best = 0.0
        var at = lo
        for (f in lo..hi) {
            var re = 0.0
            var im = 0.0
            val w = 2 * PI * f / rate
            for (n in x.indices) { re += x[n] * cos(w * n); im += x[n] * sin(w * n) }
            val p = re * re + im * im
            if (p > best) { best = p; at = f }
        }
        return at
    }

    /** How long [x] stays within 20 dB of its peak, in seconds. */
    private fun within20(x: FloatArray): Float {
        val pk = x.maxOf { abs(it) }
        return x.indices.last { abs(x[it]) >= pk * 0.1f }.toFloat() / rate
    }

    @Test
    fun `DEPTH 0 is an exact copy, and degenerate and non-finite inputs are safe`() {
        assertContentEquals(snare.samples, Octaver.process(snare, mapOf("DEPTH" to 0f)).samples)
        val one = Snip(floatArrayOf(0.5f), 1, rate)
        assertContentEquals(one.samples, Octaver.process(one, mapOf("DEPTH" to 1f)).samples)
        val silent = Snip(FloatArray(4410), 1, rate)
        assertContentEquals(silent.samples, Octaver.process(silent, mapOf("DEPTH" to 1f)).samples)
        val nan = Octaver.process(snare, mapOf("DEPTH" to Float.NaN, "TONE" to Float.POSITIVE_INFINITY, "HANG" to Float.NaN))
        assertContentEquals(Octaver.process(snare, Octaver.defaults()).samples, nan.samples, "a non-finite macro is not its default")
        assertTrue(nan.samples.all { it.isFinite() })
    }

    @Test
    fun `the copy is an octave down, on a tone and on the kit's snare and low tom`() {
        val copy = Octaver.copy(tone(220f), 0.5f, 1f)
        assertEquals(110, peakHz(copy, 60, 400), "a 220 Hz tone's copy")
        for ((name, pad) in listOf("snare" to snare, "low tom" to lowTom)) {
            val dry = peakHz(pad.samples, 60, 400)
            val sub = peakHz(Octaver.copy(pad, 0.5f, 0.5f), 30, 400)
            assertTrue(abs(sub - dry / 2f) <= 2f, "$name: dry $dry Hz, copy $sub Hz, not half")
        }
    }

    @Test
    fun `the length is kept, the peak is matched and the copy ends at zero`() {
        for (pad in listOf(snare, lowTom, tone(220f))) {
            val out = Octaver.process(pad, mapOf("DEPTH" to 1f))
            assertEquals(pad.samples.size, out.samples.size)
            assertEquals(pad.peak(), out.peak(), 1e-5f)
            val copy = Octaver.copy(pad, 0.5f, 1f)
            assertTrue(copy.last() == 0f, "the copy is not faded to zero at the pad's end: ${copy.last()}")
        }
    }

    @Test
    fun `the copy starts on the hit's onset, not twice as late`() {
        val pad = tone(220f, leadSec = 0.1f)
        val onset = (0.1f * rate).toInt()
        val copy = Octaver.copy(pad, 1f, 1f)
        assertTrue((0 until onset).all { copy[it] == 0f }, "the copy sounds before the hit")
        val early = (onset until onset + rate / 100).maxOf { abs(copy[it]) }
        assertTrue(early > 0.1f * pad.peak(), "the copy is not under the hit's first 10 ms: $early")
        // A sine starts at zero, so the first frame within 40 dB of the peak is the one after the silence.
        assertTrue(Octaver.onset(FloatArray(pad.frameCount) { abs(pad.samples[it]) }) in onset..onset + 1)
    }

    @Test
    fun `HANG 0 holds the copy to the hit's envelope and HANG 1 lets it ring twice as long`() {
        val dry = within20(lowTom.samples)
        val held = within20(Octaver.copy(lowTom, 0.5f, 0f))
        val free = within20(Octaver.copy(lowTom, 0.5f, 1f))
        assertTrue(abs(held - dry) <= 0.01f, "HANG 0: the copy lasts $held s against the hit's $dry s")
        assertTrue(free >= 1.4f * dry, "HANG 1: the copy lasts $free s, not much longer than the hit's $dry s")
    }

    @Test
    fun `TONE darkens and opens the copy`() {
        fun centroid(x: FloatArray): Double {
            var num = 0.0
            var den = 0.0
            for (f in 50..4000 step 25) {
                var re = 0.0
                var im = 0.0
                val w = 2 * PI * f / rate
                for (n in x.indices) { re += x[n] * cos(w * n); im += x[n] * sin(w * n) }
                val p = re * re + im * im
                num += f * p
                den += p
            }
            return num / den
        }
        val dark = centroid(Octaver.copy(snare, 0f, 0.5f))
        val open = centroid(Octaver.copy(snare, 1f, 0.5f))
        assertTrue(open > 1.5 * dark, "TONE barely moves the copy: centroid $dark Hz dark, $open Hz open")
        assertEquals(Octaver.TONE_DARK_HZ, Octaver.toneHz(0f), 0.01f)
        assertEquals(Octaver.TONE_OPEN_HZ, Octaver.toneHz(1f), 0.1f)
    }

    @Test
    fun `the hit gives up at most 6 dB to the peak match on the kit's drums`() {
        // The brainstorm's hazard, pinned: the sum is louder than the hit, and matching it back pulls the hit down.
        for (voice in listOf(ThumpVoice.KICK, ThumpVoice.SNARE, ThumpVoice.TOM, ThumpVoice.RIM, ThumpVoice.CLAP, ThumpVoice.HAT_CLOSED)) {
            val pad = Thump.render(voice)
            val copy = Octaver.copy(pad, 0.5f, 0.5f)
            val sum = FloatArray(pad.samples.size) { pad.samples[it] + copy[it] }
            val drop = 20 * log10(sum.maxOf { abs(it) } / pad.peak())
            assertTrue(drop in 0f..6f, "$voice: the hit drops ${"%.1f".format(drop)} dB at DEPTH 1")
        }
    }

    @Test
    fun `stereo is linked, the result is deterministic and unknown keys are ignored`() {
        val l = lowTom.samples
        val stereo = Snip(FloatArray(l.size * 2) { i -> if (i % 2 == 0) l[i / 2] else 0.1f * l[i / 2] }, 2, rate)
        val out = Octaver.process(stereo, mapOf("DEPTH" to 1f, "HANG" to 0f))
        for (f in 0 until stereo.frameCount) {
            assertEquals(0.1f * out.samples[f * 2], out.samples[f * 2 + 1], 1e-5f, "frame $f: the image moved")
        }
        assertContentEquals(Octaver.process(snare, mapOf("DEPTH" to 0.7f)).samples, Octaver.process(snare, mapOf("DEPTH" to 0.7f)).samples)
        assertContentEquals(
            Octaver.process(snare, mapOf("DEPTH" to 0.7f)).samples,
            Octaver.process(snare, mapOf("DEPTH" to 0.7f, "NOT_A_MACRO" to 1f)).samples,
        )
    }
}
