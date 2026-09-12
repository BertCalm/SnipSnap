package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** DUST: the tape's own room and floor, read out of its ghosts and put back under a hit (docs/DUST.md). */
class DustTest {

    private val rate = 44_100

    /** Deterministic white noise, so a floor is the same floor every run. */
    private fun noise(n: Int, seed: Long): FloatArray {
        val r = java.util.Random(seed)
        return FloatArray(n) { (r.nextGaussian().toFloat() * 0.5f).coerceIn(-1f, 1f) }
    }

    /**
     * A tape: a kick-like burst every half second, each followed by a room —
     * noise decaying with time constant [roomTau] — over a steady floor at
     * [floorDb]. Zero [roomTau] means a gated tape: silence between the hits.
     */
    private fun tape(seconds: Float = 6f, roomTau: Float = 0.12f, floorDb: Float = -50f, hits: Int = 10): Snip {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val floor = 10.0.pow(floorDb / 20.0).toFloat()
        val bed = noise(n, 7L)
        val room = noise(n, 11L)
        for (i in 0 until n) out[i] = bed[i] * floor
        for (h in 0 until hits) {
            val at = (0.4f * rate).toInt() + h * (rate / 2)
            // 80 ms 60 Hz burst, sharp attack, exponential decay.
            for (i in 0 until (0.08f * rate).toInt()) {
                if (at + i >= n) break
                out[at + i] += 0.8f * sin(2.0 * Math.PI * 60.0 * i / rate).toFloat() * exp(-i / (0.02f * rate))
            }
            if (roomTau > 0f) {
                for (i in 0 until (0.4f * rate).toInt()) {
                    if (at + i >= n) break
                    out[at + i] += 0.25f * room[at + i] * exp(-i / (roomTau * rate))
                }
            }
        }
        return Snip(out, 1, rate)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)

    private fun rms(a: FloatArray, from: Int, to: Int): Float {
        var acc = 0.0
        for (i in from until to) acc += a[i].toDouble() * a[i]
        return sqrt(acc / (to - from)).toFloat()
    }

    private fun kick(): Snip = DrumSynth.kick()

    @Test
    fun `a print reads a decaying room and a unit floor out of the tape, and is the same print twice`() {
        val t = tape()
        val print = assertNotNull(Dust.print(t), "a tape with rooms between its hits has dust")
        assertEquals((Dust.ROOM_MAX_SEC * rate).toInt(), print.room.frameCount)
        // Absolute values summing to one (so no hit ever comes out louder than
        // it went in), and it decays: the opening 50 ms carries more than the
        // 50 ms before the fade.
        val l1 = print.room.samples.fold(0.0) { acc, v -> acc + abs(v) }.toFloat()
        assertEquals(1f, l1, 0.01f)
        val head = rms(print.room.samples, 0, (0.05f * rate).toInt())
        val late = rms(print.room.samples, (0.2f * rate).toInt(), (0.25f * rate).toInt())
        assertTrue(head > late * 2f, "the room decays: head $head, late $late")
        // The floor at unit RMS, as a loop whose seam is a blend.
        assertEquals(1f, rms(print.hiss.samples, 0, print.hiss.frameCount), 0.05f)
        val loop = print.hiss.samples
        val seamJump = abs(loop[0] - loop[loop.size - 1])
        var biggestInside = 0f
        for (i in 1 until loop.size) biggestInside = maxOf(biggestInside, abs(loop[i] - loop[i - 1]))
        assertTrue(seamJump <= biggestInside, "the wrap is no bigger a step than the loop takes inside itself")
        val again = assertNotNull(Dust.print(t))
        assertTrue(print.room.samples.contentEquals(again.room.samples) && print.hiss.samples.contentEquals(again.hiss.samples), "measurement, not luck")
    }

    @Test
    fun `no hits, or nothing between them, is no dust`() {
        assertNull(Dust.print(Snip(FloatArray(rate * 2), 1, rate)), "silence has no hits")
        assertNull(Dust.print(tape(roomTau = 0f, floorDb = -200f)), "a gated tape over digital silence has nothing between the hits: a burst's own decay is a note, not a room")
    }

    @Test
    fun `apply puts the room's tail and a hiss bed under the hit, scaled by amount, never clipping, the same bytes twice`() {
        val print = assertNotNull(Dust.print(tape()))
        val hit = kick()
        assertSame(hit, Dust.apply(hit, print, 0f), "amount 0 is the hit itself")

        val dusted = Dust.apply(hit, print, 0.7f)
        assertEquals(hit.frameCount + (Dust.ROOM_PREDELAY_SEC * rate).toInt() + print.room.frameCount - 1, dusted.frameCount, "the pre-delay and the room's length are added")
        assertEquals(hit.channels, dusted.channels)
        // The attack is the hit's own: inside the room's pre-delay, what was
        // added is only the bed, about HISS_DB_AT_FULL under the hit's peak.
        val open = (Dust.ROOM_PREDELAY_SEC * rate).toInt()
        val added = FloatArray(open) { dusted.samples[it] - hit.samples[it] }
        val hitPeak = hit.samples.maxOf { abs(it) }
        val ratio = rms(added, 0, open) / hitPeak
        assertTrue(ratio < 0.05f, "before the room arrives only the bed is added: added/peak = $ratio")
        // After the hit ends there is a tail, and more amount is more tail.
        val tailFrom = hit.frameCount + (0.01f * rate).toInt()
        val tailTo = hit.frameCount + (0.06f * rate).toInt()
        val tail = rms(dusted.samples, tailFrom, tailTo)
        assertTrue(tail > 0f, "there is a room after the hit")
        val more = rms(Dust.apply(hit, print, 1f).samples, tailFrom, tailTo)
        assertTrue(more > tail, "amount 1 carries more room than 0.7: $more vs $tail")
        var peak = 0f
        for (v in Dust.apply(hit, print, 1f).samples) peak = maxOf(peak, abs(v))
        assertTrue(peak <= 0.999f, "never clips: $peak")
        assertTrue(dusted.samples.contentEquals(Dust.apply(hit, print, 0.7f).samples), "deterministic")
    }

    @Test
    fun `a quiet hit gets more floor than a loud one, and stereo stays stereo`() {
        val print = assertNotNull(Dust.print(tape()))
        val loud = kick()
        val quiet = Snip(FloatArray(loud.samples.size) { loud.samples[it] * 0.1f }, 1, rate)
        // Compare the bed where only hiss remains: the last 40 ms before the fade.
        fun bedOf(hit: Snip): Float {
            val d = Dust.apply(hit, print, 1f)
            val to = d.frameCount - (Dust.FADE_OUT_SEC * rate).toInt() - 1
            return rms(d.samples, to - (0.04f * rate).toInt(), to) / maxOf(1e-9f, hit.samples.maxOf { abs(it) })
        }
        assertTrue(bedOf(quiet) > bedOf(loud) * 1.5f, "relative to its own peak, the quiet hit sits on more floor")

        // A hot hit keeps its own attack byte for byte: the clip guard scales the dust, never the hit.
        val hot = Snip(FloatArray(loud.samples.size) { (loud.samples[it] / loud.samples.maxOf { v -> abs(v) }) * 0.999f }, 1, rate)
        val dustedHot = Dust.apply(hot, print, 1f)
        val hotPeakAt = hot.samples.indices.maxBy { abs(hot.samples[it]) }
        assertEquals(hot.samples[hotPeakAt], dustedHot.samples[hotPeakAt], 0.001f, "the hit's own peak is untouched")
        assertTrue(dustedHot.samples.maxOf { abs(it) } <= 0.999f)

        val stereo = Snip(FloatArray(loud.frameCount * 2) { loud.samples[it / 2] }, 2, rate)
        val d = Dust.apply(stereo, print, 0.5f)
        assertEquals(2, d.channels)
        for (f in 0 until d.frameCount) assertEquals(d.samples[f * 2], d.samples[f * 2 + 1], "identical channels dust identically")
    }

    @Test
    fun `a print at another rate is resampled to the hit's`() {
        val print = assertNotNull(Dust.print(tape()))
        val hit48 = Resampler.resample(kick(), 48_000)
        val d = Dust.apply(hit48, print, 0.5f)
        assertEquals(48_000, d.sampleRate)
        assertTrue(d.frameCount > hit48.frameCount)
    }
}
