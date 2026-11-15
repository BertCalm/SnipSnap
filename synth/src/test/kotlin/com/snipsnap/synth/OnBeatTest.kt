package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ON BEAT's own claims (the doc on [OnBeat]): AMOUNT 0 is a copy; repeats land on the division of the kit's bar,
 * the dotted eighth included; AMOUNT sets the first repeat's level and the feedback; each repeat is quieter and
 * darker than the last; the tail is at most one bar, and a capped trail fades out rather than stopping; one delay
 * for every channel; peak matched; the same pad renders the same samples.
 */
class OnBeatTest {

    private val rate = 44_100
    private val bpm = 92f

    /** A click on the first frame and a little silence: every repeat is the filter's response, alone. */
    private fun click(frames: Int = 64): Snip = Snip(FloatArray(frames).also { it[0] = 0.8f }, 1, rate)

    /** A 200 Hz burst, far under the loop's low-pass, shorter than any division: its repeats keep their level. */
    private fun burst(seconds: Float = 0.1f): Snip {
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { 0.5f * sin(2.0 * PI * 200.0 * it / rate).toFloat() }, 1, rate)
    }

    private fun rms(x: FloatArray, from: Int, len: Int): Double {
        var s = 0.0
        for (i in from until (from + len).coerceAtMost(x.size)) s += x[i].toDouble() * x[i]
        return sqrt(s / len)
    }

    @Test
    fun `AMOUNT 0 is a copy, the divisions are the grid's and the dotted eighth, and an unknown one is refused`() {
        val pad = Onset.render(OnsetVoice.SNARE)
        assertContentEquals(pad.samples, OnBeat.echo(pad, bpm, "1/8", 0f).samples, "ON BEAT at 0 is not a copy")
        assertEquals(pad.frameCount, OnBeat.echo(pad, bpm, "1/8", 0f).frameCount)
        assertEquals(Wobble.DIVISIONS + "3/16", OnBeat.DIVISIONS)
        assertEquals("3/16", OnBeat.DEFAULT_DIVISION)
        // Three sixteenths at 92 BPM: 0.75 of a beat, 489 ms.
        assertEquals(0.489f, OnBeat.periodSec(bpm, "3/16"), 0.001f)
        assertEquals(1.5f * OnBeat.periodSec(bpm, "1/8"), OnBeat.periodSec(bpm, "3/16"), 1e-5f)
        assertFailsWith<IllegalArgumentException> { OnBeat.echo(pad, bpm, "1/3", 0.7f) }
        assertFailsWith<IllegalArgumentException> { OnBeat.echo(pad, bpm, "1/8", 1.2f) }
    }

    @Test
    fun `repeats land on the division of the kit's bar, the first frame being the downbeat`() {
        for (division in listOf("1/16", "1/8", "3/16", "1/4", "1/2")) {
            val delay = OnBeat.delayFrames(rate, bpm, division)
            val out = OnBeat.echo(click(), bpm, division, 1f).samples
            var k = 1
            while ((k + 1) * delay < out.size && k <= 3) {
                // Each repeat starts on its beat: nothing is louder than the trail's floor before it, and its
                // loudest frame is within a millisecond after (the low-pass, passed k times, rounds the click).
                val from = k * delay - delay / 4
                val to = (k * delay + delay / 4).coerceAtMost(out.size)
                var at = from
                for (f in from until to) if (abs(out[f]) > abs(out[at])) at = f
                assertTrue(at >= k * delay && at - k * delay <= rate / 1000, "$division: repeat $k peaks at $at, not on ${k * delay}")
                for (f in from until k * delay) assertTrue(abs(out[f]) < 0.05f * abs(out[at]), "$division: repeat $k starts early at $f")
                k++
            }
            assertTrue(k >= 2, "$division: no repeat landed")
        }
    }

    @Test
    fun `AMOUNT is the send - the first repeat's level and the feedback together`() {
        for (amount in listOf(0.4f, 0.7f, 1f)) {
            val pad = burst()
            val len = pad.frameCount
            val delay = OnBeat.delayFrames(rate, bpm, "1/8")
            val out = OnBeat.echo(pad, bpm, "1/8", amount).samples
            // The peak match scales the dry and the repeats alike, so the ratios hold.
            val dry = rms(out, 0, len)
            val first = rms(out, delay, len)
            val second = rms(out, 2 * delay, len)
            assertEquals(amount.toDouble(), first / dry, 0.03, "AMOUNT $amount: the first repeat is not at the send")
            assertEquals(OnBeat.feedback(amount).toDouble(), second / first, 0.03, "AMOUNT $amount: the feedback is not the send's")
        }
        assertEquals(0.6f, OnBeat.feedback(1f))
        assertEquals(0.42f, OnBeat.feedback(0.7f), 1e-6f)
    }

    @Test
    fun `each repeat is quieter and darker than the last`() {
        val delay = OnBeat.delayFrames(rate, bpm, "1/8")
        val out = OnBeat.echo(click(), bpm, "1/8", 1f).samples
        var lastPeak = Float.MAX_VALUE
        var lastWidth = 0.0
        for (k in 1..4) {
            var peak = 0f
            var sum = 0.0
            for (f in k * delay until (k * delay + delay / 2)) { val a = abs(out[f]); sum += a; if (a > peak) peak = a }
            // Darker: each pass through the low-pass spreads the click, so its sum grows against its peak.
            val width = sum / peak
            assertTrue(peak < lastPeak, "repeat $k is not quieter: $peak against $lastPeak")
            assertTrue(width > lastWidth, "repeat $k is not darker: width $width against $lastWidth")
            lastPeak = peak
            lastWidth = width
        }
    }

    @Test
    fun `the tail is at most a bar, a capped trail fades out, and a trail that dies first is not cut`() {
        val bar = (OnBeat.barSec(bpm) * rate).toInt()
        // A tap on a hat: about nine repeats would reach -60 dB, so the bar cuts it and the last division fades.
        val hat = Onset.render(OnsetVoice.HAT_CLOSED)
        val tapped = OnBeat.echo(hat, bpm, "3/16", 0.7f)
        assertEquals(hat.frameCount + bar, tapped.frameCount, "the tap's tail is not one bar")
        assertTrue(abs(tapped.samples.last()) < 1e-4f, "the capped trail stops rather than fading: ${tapped.samples.last()}")
        for (division in OnBeat.DIVISIONS) for (amount in listOf(0.3f, 0.7f, 1f)) {
            assertTrue(OnBeat.tailFrames(rate, bpm, division, amount) <= bar, "$division at $amount: more than a bar")
        }
        // A light send dies inside the bar: five repeats of a dotted eighth, 2.45 s, under the bar's 2.61, unfaded.
        val light = 0.2f
        val delay = OnBeat.delayFrames(rate, bpm, "3/16")
        assertEquals(5 * delay, OnBeat.tailFrames(rate, bpm, "3/16", light))
        val out = OnBeat.echo(click(), bpm, "3/16", light).samples
        val fifth = 5 * delay
        assertTrue(abs(out[fifth]) > 0f, "the fifth repeat was faded or cut")
        assertEquals(click().frameCount + 5 * delay, out.size)
    }

    @Test
    fun `one delay for every channel, peak matched, and deterministic`() {
        val mono = OnBeat.echo(Onset.render(OnsetVoice.SNARE), bpm, "1/8", 1f)
        assertEquals(1, mono.channels)
        val left = burst()
        val pair = Snip(FloatArray(left.frameCount * 2) { left.samples[it / 2] }, 2, rate)
        val out = OnBeat.echo(pair, bpm, "1/8", 1f)
        for (f in 0 until out.frameCount) assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "frame $f: the channels parted")

        // A held chord's repeats stack over its peak; the match brings the output back to the pad's.
        val choir = Vox.render(VoxVoice.CHOIR)
        val echoed = OnBeat.echo(choir, bpm, "1/8", 1f)
        assertEquals(choir.peak(), echoed.peak(), 1e-4f)

        assertContentEquals(echoed.samples, OnBeat.echo(choir, bpm, "1/8", 1f).samples, "two renders differ")
    }
}
