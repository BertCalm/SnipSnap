package com.snipsnap.audio

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutsideTest {

    private val rate = 44_100

    /** A hit: 200 ms of decaying seeded noise — broadband, so its arrival is a sharp point in a correlation. */
    private fun hit(seed: Long = 7, seconds: Float = 0.2f, amp: Float = 0.6f, channels: Int = 1): Snip {
        val rng = Random(seed)
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (amp * (rng.nextGaussian() * 0.3) * Math.exp(-f / (0.05 * rate))).toFloat()
            for (ch in 0 until channels) out[f * channels + ch] = v
        }
        return Snip(out, channels, rate)
    }

    /** [sent] placed [lag] frames into [seconds] of quiet, scaled by [gain], with a hiss floor and an optional echo. */
    private fun room(
        sent: Snip,
        lag: Int,
        gain: Float,
        seconds: Float = 2f,
        echoAt: Int = 0,
        echoGain: Float = 0f,
        hiss: Float = 1e-3f,
        seed: Long = 99,
    ): Snip {
        val rng = Random(seed)
        val mono = if (sent.channels == 1) sent.samples else Cleanup.toMono(sent).samples
        val out = FloatArray((seconds * rate).toInt()) { (rng.nextGaussian() * hiss).toFloat() }
        for (i in mono.indices) {
            val at = lag + i
            if (at in out.indices) out[at] += mono[i] * gain
            if (echoGain != 0f) {
                val e = lag + echoAt + i
                if (e in out.indices) out[e] += mono[i] * gain * echoGain
            }
        }
        return Snip(out, 1, rate)
    }

    private fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
        var dot = 0.0
        var ea = 0.0
        var eb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i].toDouble()
            ea += a[i] * a[i].toDouble()
            eb += b[i] * b[i].toDouble()
        }
        return if (ea < 1e-12 || eb < 1e-12) 0.0 else dot / sqrt(ea * eb)
    }

    // ---- align ----

    @Test
    fun `align finds how late the send came back, and notices when it came back upside down`() {
        val sent = hit()
        val lateBy = 1234
        val back = room(sent, lag = lateBy, gain = 0.3f)

        val a = Outside.align(sent, back)
        assertEquals(lateBy, a.lagFrames, "the lag is the delay, to the frame")
        assertTrue(a.confidence > 0.9f, "a scaled copy correlates near one: ${a.confidence}")
        assertTrue(a.standout > Outside.MIN_STANDOUT, "the arrival stands over the hiss: ${a.standout}")
        assertFalse(a.inverted)
        assertEquals(lateBy * 1000f / rate, a.lagMs(rate), 1e-3f)

        val flipped = Snip(FloatArray(back.samples.size) { -back.samples[it] }, 1, rate)
        val b = Outside.align(sent, flipped)
        assertEquals(lateBy, b.lagFrames, "polarity doesn't move the arrival")
        assertTrue(b.inverted, "and the flip is reported")
    }

    @Test
    fun `align survives a reverberant, resampled, stereo return`() {
        val sent = hit(channels = 2)
        val lateBy = 3000
        // A long echo two hundred frames after the direct arrival, then the whole thing at 48 kHz.
        val back = room(sent, lag = lateBy, gain = 0.5f, echoAt = 200, echoGain = 0.6f)
        val at48 = Resampler.resample(back, 48_000)

        val a = Outside.align(sent, at48)
        assertTrue(abs(a.lagFrames - lateBy) <= 2, "the direct arrival wins over the echo, within a frame or two of resampling: ${a.lagFrames}")
        assertFalse(a.inverted)
    }

    @Test
    fun `align refuses silence, a clipped return, and a return that has nothing to do with the send - in words`() {
        val sent = hit()
        val silence = Snip(FloatArray(rate), 1, rate)
        val nothing = assertFailsWith<Outside.Refused> { Outside.align(sent, silence) }
        assertTrue(nothing.message!!.contains("said nothing"), nothing.message)

        val clipped = room(sent, lag = 100, gain = 0.5f).let { s ->
            Snip(FloatArray(s.samples.size) { if (it == 500) 1f else s.samples[it] }, 1, rate)
        }
        val clip = assertFailsWith<Outside.Refused> { Outside.align(sent, clipped) }
        assertTrue(clip.message!!.contains("clipped"), clip.message)

        val stranger = Snip(FloatArray(2 * rate).also { Random(3).let { r -> for (i in it.indices) it[i] = (r.nextGaussian() * 0.1).toFloat() } }, 1, rate)
        val lost = assertFailsWith<Outside.Refused> { Outside.align(sent, stranger) }
        assertTrue(lost.message!!.contains("didn't answer"), lost.message)
    }

    // ---- reamp ----

    @Test
    fun `reamp cuts at the arrival, keeps the tail while it sounds, and matches the pad's peak`() {
        val sent = hit()
        val lateBy = 2000
        // An echo well after the send's own length: the tail the cut must keep.
        val back = room(sent, lag = lateBy, gain = 0.2f, echoAt = sent.frameCount + 4000, echoGain = 0.5f)

        val out = Outside.reamp(sent, back, mix = 1f)
        assertEquals(lateBy, out.alignment.lagFrames)
        val snip = out.snip
        assertEquals(1, snip.channels, "channels follow the send")
        assertTrue(
            snip.frameCount > sent.frameCount + 4000,
            "the echo is kept: ${snip.frameCount} frames for a ${sent.frameCount}-frame send",
        )
        assertTrue(
            snip.frameCount < sent.frameCount + 4000 + sent.frameCount + rate / 2,
            "and the cut closes once the echo has died: ${snip.frameCount}",
        )
        assertEquals(sent.peak(), snip.peak(), 1e-3f, "peak matched to the pad")
        assertTrue(
            correlation(snip.samples, sent.samples, sent.frameCount) > 0.95,
            "the return's head is the send, lined up",
        )
        // The end is faded, never cut.
        assertTrue(abs(snip.samples.last()) < 1e-3f)
    }

    @Test
    fun `reamp at MIX 0 is the pad, and a mono return spreads to a stereo pad`() {
        val sent = hit(channels = 2)
        val back = room(sent, lag = 500, gain = 0.4f)
        val dry = Outside.reamp(sent, back, mix = 0f).snip
        assertEquals(2, dry.channels)
        for (i in sent.samples.indices) assertEquals(sent.samples[i], dry.samples[i], 1e-6f)

        val wet = Outside.reamp(sent, back, mix = 1f).snip
        assertEquals(2, wet.channels)
        for (f in 0 until 100) assertEquals(wet.samples[f * 2], wet.samples[f * 2 + 1], "a mono return is the same on both sides")
        assertFailsWith<IllegalArgumentException> { Outside.reamp(sent, back, mix = 1.5f) }
    }

    @Test
    fun `an inverted return comes back the right way up`() {
        val sent = hit()
        val back = room(sent, lag = 800, gain = 0.3f)
        val flipped = Snip(FloatArray(back.samples.size) { -back.samples[it] }, 1, rate)
        val out = Outside.reamp(sent, flipped)
        assertTrue(out.alignment.inverted)
        assertTrue(correlation(out.snip.samples, sent.samples, sent.frameCount) > 0.95, "polarity restored")
    }

    // ---- the room ----

    @Test
    fun `the probe is a two-second sweep at half scale, faded, rising in pitch`() {
        val p = Outside.probe(rate)
        assertEquals(1, p.channels)
        assertEquals((Outside.PROBE_SECONDS * rate).toInt(), p.frameCount)
        assertTrue(p.peak() <= Outside.PROBE_LEVEL + 1e-3f)
        assertTrue(p.peak() > Outside.PROBE_LEVEL * 0.9f)
        assertEquals(0f, p.samples.first(), 1e-6f)
        assertTrue(abs(p.samples.last()) < 1e-3f)

        fun crossings(from: Int, to: Int): Int {
            var n = 0
            for (i in from + 1 until to) if ((p.samples[i] >= 0f) != (p.samples[i - 1] >= 0f)) n++
            return n
        }
        val window = rate / 10
        assertTrue(crossings(window, 2 * window) < crossings(p.frameCount - 2 * window, p.frameCount - window), "low first, high last")
    }

    @Test
    fun `impulse recovers a known room - a direct path and an echo - and the trip's latency`() {
        val probe = Outside.probe(rate)
        val lateBy = 1000
        val echoAt = 300
        val back = room(probe, lag = lateBy, gain = 0.4f, echoAt = echoAt, echoGain = 0.5f, seconds = 4f, hiss = 1e-4f)

        val ir = Outside.impulse(probe, back)
        assertTrue(abs(ir.lagFrames - lateBy) <= 2, "the latency is the delay: ${ir.lagFrames}")
        assertTrue(ir.standout > Outside.MIN_STANDOUT)
        val s = ir.snip
        assertEquals(1, s.channels)
        var peakIdx = 0
        for (i in s.samples.indices) if (abs(s.samples[i]) > abs(s.samples[peakIdx])) peakIdx = i
        assertEquals(0.4f, abs(s.samples[peakIdx]), 0.03f, "the direct path's gain reads true")
        assertEquals(0.2f, abs(s.samples[peakIdx + echoAt]), 0.03f, "and the echo's")
        // Between the two taps the room is quiet: the sweep's own ringing stays small.
        var between = 0f
        for (i in peakIdx + 40 until peakIdx + echoAt - 40) between = maxOf(between, abs(s.samples[i]))
        assertTrue(between < 0.04f, "the deconvolution is clean between taps: $between")
        assertTrue(s.frameCount < rate, "a two-tap room's response closes well inside a second: ${s.frameCount}")
    }

    @Test
    fun `a wire is a unit impulse, and silence is refused`() {
        val probe = Outside.probe(rate)
        val wire = room(probe, lag = 0, gain = 1f, seconds = 2.5f, hiss = 0f)
        // The wire feeds the probe straight back at full scale — under the clip line.
        val ir = Outside.impulse(probe, wire)
        assertEquals(0, ir.lagFrames)
        assertEquals(1f, ir.snip.peak(), 0.02f)

        val silent = Snip(FloatArray(3 * rate), 1, rate)
        assertFailsWith<Outside.Refused> { Outside.impulse(probe, silent) }
    }

    @Test
    fun `same send, same return, same bytes`() {
        val sent = hit()
        val back = room(sent, lag = 700, gain = 0.3f)
        val a = Outside.reamp(sent, back).snip
        val b = Outside.reamp(sent, back).snip
        assertTrue(a.samples.contentEquals(b.samples))
    }
}
