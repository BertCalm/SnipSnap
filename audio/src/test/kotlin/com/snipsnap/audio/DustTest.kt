package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
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
    private fun tape(seconds: Float = 6f, roomTau: Float = 0.12f, floorDb: Float = -50f, hits: Int = 10, clicks: Int = 0): Snip {
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
        // Clicks: one-sample spikes 300 ms after a hit, well into the floor, one per hit until [clicks].
        for (c in 0 until clicks) {
            val at = (0.4f * rate).toInt() + c * (rate / 2) + (0.3f * rate).toInt()
            if (at < n) out[at] = 0.6f
        }
        return Snip(out, 1, rate)
    }

    /** Where the crackle lands in [out] past [from]: runs of samples over [over], counted once each. */
    private fun crackles(out: FloatArray, from: Int, over: Float): Int {
        var count = 0
        var inside = false
        for (i in from until out.size) {
            val hot = abs(out[i]) > over
            if (hot && !inside) count++
            inside = hot || (inside && i % 8 != 0 && abs(out[i]) > over / 4)
        }
        return count
    }

    /** A print with no hiss and a bare impulse for a room, so what [Dust.apply] adds is only what the test puts there. */
    private fun bare(crackle: Snip = Snip(FloatArray(0), 1, rate)): Dust.Print =
        Dust.Print(hiss = Snip(FloatArray(rate / 4), 1, rate), room = Snip(floatArrayOf(1f), 1, rate), crackle = crackle)

    /** A hit: one spike at [peak], then [seconds] of nothing — so every sample after 20 ms is the dust's. */
    private fun spike(peak: Float, seconds: Float): Snip = Snip(FloatArray((seconds * rate).toInt()).also { it[0] = peak }, 1, rate)

    @Test
    fun `a tape's clicks become crackle grains at unit peak, and a clean tape has none`() {
        val p = assertNotNull(Dust.print(tape(clicks = 6)))
        assertEquals(6, p.grains, "one grain per planted click")
        assertEquals(Dust.grainFrames(rate), p.grainFrames)
        for (i in 0 until p.grains) {
            val g = p.grain(i)
            assertEquals(1f, g.samples.maxOf { abs(it) }, 1e-5f, "grain $i at unit peak")
            assertEquals(0f, g.samples.first(), 1e-6f, "windowed: nothing at the edges")
        }
        assertEquals(0, assertNotNull(Dust.print(tape())).grains, "a clean tape has no crackle")
        val again = assertNotNull(Dust.print(tape(clicks = 6)))
        assertTrue(again.crackle.samples.contentEquals(p.crackle.samples), "the same tape, the same crackle")
        // The cached shape survives a level change and a rate change.
        val scaled = Dust.Print(p.hiss, p.room, Snip(FloatArray(p.crackle.samples.size) { p.crackle.samples[it] * 0.3f }, 1, rate)).levelled()
        assertEquals(1f, scaled.grain(2).samples.maxOf { abs(it) }, 1e-5f)
        val other = p.at(48_000)
        assertEquals(6, other.grains)
        assertEquals(Dust.grainFrames(48_000), other.grainFrames)
        assertEquals(1f, other.grain(0).samples.maxOf { abs(it) }, 1e-5f)
    }

    @Test
    fun `crackle sprinkles by amount, is the same sprinkle twice, and a print without it adds none`() {
        val crackle = assertNotNull(Dust.print(tape(clicks = 6))).crackle
        val hit = spike(0.8f, 2f)
        val after = (0.02f * rate).toInt()
        val full = Dust.apply(hit, bare(crackle), 1f)
        // ~24 expected across two seconds at full (Curves.cracklePerSec), a few merged where two land close.
        val atFull = crackles(full.samples, after, 0.02f)
        assertTrue(atFull in 16..26, "crackles at full: $atFull")
        val light = crackles(Dust.apply(hit, bare(crackle), 0.3f).samples, after, 0.02f)
        assertTrue(light < atFull && light <= 6, "the square of the amount thins them: $light at 0.3 vs $atFull at 1")
        assertTrue(Dust.apply(hit, bare(crackle), 1f).samples.contentEquals(full.samples), "the same hit, the same sprinkle")
        assertEquals(0, crackles(Dust.apply(hit, bare(), 1f).samples, after, 0.02f), "no crackle in the print, none under the hit")
        // The crackle sits CRACKLE_DB under the hit's peak (plus the quiet lift), never over it.
        val loudest = full.samples.drop(after).maxOf { abs(it) }
        assertTrue(loudest <= 0.8f * 10f.pow((Dust.CRACKLE_DB + Dust.HISS_QUIET_LIFT_DB * 0.2f) / 20f) * 1.01f, "crackle level: $loudest")
    }

    @Test
    fun `each ingredient rides its own curve of the one amount`() {
        assertEquals(Dust.HISS_DB_AT_FULL, Dust.Curves.hissDb(1f))
        assertEquals(Dust.HISS_DB_AT_FULL - Dust.HISS_RANGE_DB / 2, Dust.Curves.hissDb(0.5f))
        assertEquals(Dust.HISS_DB_AT_FULL - Dust.HISS_RANGE_DB, Dust.Curves.hissDb(0f))
        assertEquals(Dust.ROOM_GAIN / 2, Dust.Curves.roomGain(0.5f))
        assertEquals(Dust.CRACKLE_PER_SEC_AT_FULL / 4, Dust.Curves.cracklePerSec(0.5f))
        var last = Float.NEGATIVE_INFINITY
        for (step in 0..20) {
            val a = step / 20f
            assertTrue(Dust.Curves.hissDb(a) > last && Dust.Curves.roomGain(a) >= 0f && Dust.Curves.cracklePerSec(a) >= 0f)
            last = Dust.Curves.hissDb(a)
        }
        // Heard: the hiss bed under a hit at half amount measures hissDb(0.5), lifted for the hit's own quietness.
        val hiss = Snip(noise(rate / 4, 3L), 1, rate)
        val print = Dust.Print(hiss, Snip(floatArrayOf(1f), 1, rate)).levelled()
        val hit = spike(0.8f, 1f)
        val out = Dust.apply(hit, print, 0.5f)
        val bed = rms(out.samples, (0.2f * rate).toInt(), (0.9f * rate).toInt())
        val expected = 0.8f * 10f.pow((Dust.Curves.hissDb(0.5f) + Dust.HISS_QUIET_LIFT_DB * 0.2f) / 20f)
        assertEquals(expected, bed, expected * 0.1f, "the bed at half amount")
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

        // A hot hit keeps its own peak: the clip guard scales the dust, never the hit — and still dusts it.
        val hot = Snip(FloatArray(loud.samples.size) { (loud.samples[it] / loud.samples.maxOf { v -> abs(v) }) * 0.999f }, 1, rate)
        val dustedHot = Dust.apply(hot, print, 1f)
        val hotPeakAt = hot.samples.indices.maxBy { abs(hot.samples[it]) }
        // The peak sample may carry up to MIN_DUST_SHARE of the dust, held at the ceiling: within the dust's own size, never below.
        assertEquals(hot.samples[hotPeakAt], dustedHot.samples[hotPeakAt], 0.01f, "the hit's own peak is untouched")
        assertTrue(dustedHot.samples.maxOf { abs(it) } <= 0.999f)
        val hotTail = rms(dustedHot.samples, hot.frameCount + (0.01f * rate).toInt(), hot.frameCount + (0.06f * rate).toInt())
        assertTrue(hotTail > 0f, "a hot hit still gets its room")
        // A capture that clipped — samples at full scale — is still dusted, at least MIN_DUST_SHARE of it, never above its own peak.
        val clipped = Snip(FloatArray(loud.samples.size) { (loud.samples[it] * 3f).coerceIn(-1f, 1f) }, 1, rate)
        val dustedClipped = Dust.apply(clipped, print, 1f)
        val clippedTail = rms(dustedClipped.samples, clipped.frameCount + (0.01f * rate).toInt(), clipped.frameCount + (0.06f * rate).toInt())
        val reference = rms(Dust.apply(Snip(FloatArray(clipped.samples.size) { clipped.samples[it] * 0.5f }, 1, rate), print, 1f).samples, clipped.frameCount + (0.01f * rate).toInt(), clipped.frameCount + (0.06f * rate).toInt())
        assertTrue(clippedTail >= reference * 0.5f * Dust.MIN_DUST_SHARE * 0.9f, "the room survives a clipped hit: $clippedTail vs $reference")
        assertTrue(dustedClipped.samples.maxOf { abs(it) } <= 1f, "never above the hit's own peak")

        val stereo = Snip(FloatArray(loud.frameCount * 2) { loud.samples[it / 2] }, 2, rate)
        val d = Dust.apply(stereo, print, 0.5f)
        assertEquals(2, d.channels)
        for (f in 0 until d.frameCount) assertEquals(d.samples[f * 2], d.samples[f * 2 + 1], "identical channels dust identically")
    }

    @Test
    fun `a print at another rate is resampled to the hit's, back on its contract, its loop still seamless`() {
        val print = assertNotNull(Dust.print(tape()))
        val at48 = print.at(48_000)
        assertEquals(48_000, at48.sampleRate)
        assertEquals(1f, at48.room.samples.fold(0.0) { a, v -> a + abs(v) }.toFloat(), 0.01f, "L1 back to one after resampling")
        assertEquals(1f, rms(at48.hiss.samples, 0, at48.hiss.frameCount), 0.05f, "unit RMS back after resampling")
        assertEquals(Math.round(print.hiss.frameCount * 48_000.0 / rate).toInt(), at48.hiss.frameCount, "the loop's length follows the rate")
        // No taper at the seam: the loop's first and last 5 ms are as loud as its middle.
        val edge = (0.005f * 48_000).toInt()
        val mid = rms(at48.hiss.samples, at48.hiss.frameCount / 2 - edge, at48.hiss.frameCount / 2 + edge)
        assertTrue(rms(at48.hiss.samples, 0, edge) > mid * 0.5f && rms(at48.hiss.samples, at48.hiss.frameCount - edge, at48.hiss.frameCount) > mid * 0.5f, "no dip at the seam")
        val hit48 = Resampler.resample(kick(), 48_000)
        val d = Dust.apply(hit48, print, 0.5f)
        assertEquals(48_000, d.sampleRate)
        assertTrue(d.frameCount > hit48.frameCount)
    }
}
