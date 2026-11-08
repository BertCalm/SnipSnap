package com.snipsnap.synth

import com.snipsnap.audio.Loudness
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

    /** The share of [x]'s energy between [lo] and [hi] Hz (two 2-pole filters), so loudness does not count. */
    private fun share(x: FloatArray, lo: Float, hi: Float): Double {
        val k = kotlin.math.sqrt(2f)
        val a = Dsp.TptSvf(rate)
        val b = Dsp.TptSvf(rate)
        var e = 0.0
        var t = 0.0
        for (v in x) { a.process(v, hi, k); b.process(a.low, lo, k); e += b.high.toDouble() * b.high; t += v.toDouble() * v }
        return e / t
    }

    @Test
    fun `the squash keeps the hit's level where scaling the sum down would lose it`() {
        // The brainstorm's hazard: a sum louder than the hit, scaled back to the hit's peak, pulls the whole hit
        // down. The squash keeps the peak and is louder than that scaling on every kit drum.
        for (voice in listOf(ThumpVoice.KICK, ThumpVoice.SNARE, ThumpVoice.TOM, ThumpVoice.RIM, ThumpVoice.CLAP, ThumpVoice.HAT_CLOSED)) {
            val pad = Thump.render(voice)
            val out = Octaver.process(pad, mapOf("DEPTH" to 1f))
            assertEquals(pad.peak(), out.peak(), 1e-5f)
            val copy = Octaver.copy(pad, 0.5f, 1f, Octaver.GRIT)
            val sum = FloatArray(pad.samples.size) { pad.samples[it] + Octaver.MAX_LEVEL * copy[it] }
            val sp = sum.maxOf { abs(it) }
            val scaled = Snip(FloatArray(sum.size) { sum[it] * pad.peak() / sp }, 1, rate)
            val gain = 20 * log10(Loudness.of(out) / Loudness.of(scaled))
            assertTrue(gain > 1f, "$voice: the squash is only ${"%.1f".format(gain)} dB louder than scaling the sum down")
        }
    }

    @Test
    fun `DRIVE 0 is the clean copy, and a touch of DRIVE stays close to it`() {
        for (pad in listOf(snare, lowTom)) {
            assertContentEquals(Octaver.copy(pad, 0.5f, 1f), Octaver.copy(pad, 0.5f, 1f, 0f))
            val clean = Octaver.copy(pad, 0.5f, 1f)
            val touch = Octaver.copy(pad, 0.5f, 1f, 0.002f)
            val diff = clean.indices.maxOf { abs(clean[it] - touch[it]) }
            assertTrue(diff < 0.05f * pad.peak(), "DRIVE 0.002 moves the copy by $diff")
        }
    }

    @Test
    fun `DRIVE puts the octave where small speakers play, on the low tom and the kick`() {
        // The thicker round's finding: a clean octave of a low tom (56 Hz) or a kick (29 Hz) lands mostly under
        // 60 Hz, which a phone barely plays. Grit and Fuzz move the copy's energy up into 150 to 400 Hz, where a
        // drum's thickness is heard. Measured: low tom +3.7 / +6.3 dB, kick +3.4 / +7.8 dB. The snare's and mid
        // tom's octaves (89 and 78 Hz) already reach that band, so DRIVE adds edge there, not weight (+0.3 to
        // +2.7 dB); for them the test pins only that DRIVE takes nothing from it.
        val low = listOf("low tom" to lowTom, "kick" to Thump.render(ThumpVoice.KICK))
        val high = listOf("mid tom" to Thump.render(ThumpVoice.TOM), "snare" to snare)
        for ((name, pad) in low + high) {
            val clean = share(Octaver.copy(pad, 0.5f, 1f, 0f), 150f, 400f)
            for (d in listOf(Octaver.GRIT, 1f)) {
                val up = 10 * log10(share(Octaver.copy(pad, 0.5f, 1f, d), 150f, 400f) / clean)
                val floor = if (low.any { it.first == name }) 3.0 else 0.0
                assertTrue(up > floor, "$name: DRIVE $d lifts the copy's 150-400 Hz share by ${"%.1f".format(up)} dB, not over $floor")
            }
        }
    }

    @Test
    fun `Fuzz follows the clean copy's envelope, has no DC and ends at zero`() {
        for (pad in listOf(snare, lowTom)) {
            val clean = within20(Octaver.copy(pad, 0.5f, 1f, 0f))
            val fuzz = Octaver.copy(pad, 0.5f, 1f, 1f)
            assertTrue(within20(fuzz) <= 1.25f * clean + 0.01f, "Fuzz rings ${within20(fuzz)} s against the clean copy's $clean s")
            assertTrue(abs(fuzz.average()) < 0.01 * pad.peak(), "Fuzz carries DC: ${fuzz.average()}")
            assertTrue(fuzz.last() == 0f)
            assertTrue(fuzz.all { it.isFinite() })
        }
    }

    @Test
    fun `stereo is linked, the result is deterministic and unknown keys are ignored`() {
        val l = lowTom.samples
        val stereo = Snip(FloatArray(l.size * 2) { i -> if (i % 2 == 0) l[i / 2] else 0.1f * l[i / 2] }, 2, rate)
        val out = Octaver.process(stereo, mapOf("DEPTH" to 1f, "HANG" to 0f, "DRIVE" to 1f))
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
