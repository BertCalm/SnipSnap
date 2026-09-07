package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BreakFinderTest {

    private val rate = 44_100

    /** A sustained chord bed with an occasional slow melody note — a verse. */
    private fun padSection(seconds: Float): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val chord = doubleArrayOf(220.0, 277.18, 329.63)
        for (i in 0 until n) {
            var s = 0.0
            for (hz in chord) s += Math.sin(2.0 * Math.PI * hz * i / rate)
            // A slow melody note every two seconds, gently enveloped.
            val phrase = (i / rate.toFloat()) % 2f
            if (phrase < 1f) {
                s += 0.8 * Math.sin(2.0 * Math.PI * 440.0 * i / rate) * Math.exp(-phrase * 3.0)
            }
            out[i] = (0.18 * s).toFloat()
        }
        return out
    }

    /** A dense drum pattern at 100 bpm: kick quarters, snare 2 & 4, hat eighths. */
    private fun breakSection(seconds: Float): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val beat = (60f / 100f * rate).toInt()
        fun place(hit: Snip, at: Int, gain: Float) {
            for (i in hit.samples.indices) {
                val idx = at + i
                if (idx >= n) break
                out[idx] += hit.samples[i] * gain
            }
        }
        val kick = DrumSynth.kick()
        val snare = DrumSynth.snare()
        val hat = DrumSynth.closedHat()
        var t = 0
        var count = 0
        while (t < n) {
            place(kick, t, 0.9f)
            if (count % 2 == 1) place(snare, t, 0.8f)
            place(hat, t, 0.5f)
            place(hat, t + beat / 2, 0.4f)
            t += beat
            count++
        }
        return out
    }

    private fun join(vararg parts: FloatArray): Snip {
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `the break inside a song is candidate number one, where it actually is`() {
        // verse (8s) | break (8s) | outro (6s)
        val song = join(padSection(8f), breakSection(8f), padSection(6f))
        val found = BreakFinder.find(song)
        assertTrue(found.isNotEmpty(), "the dig finds something in a song with a break")

        val best = found.first()
        val mid = (best.startSec + best.endSec) / 2
        assertTrue(mid in 8f..16f, "the top candidate sits inside the actual break, got ${best.startSec}..${best.endSec}")
        assertTrue(best.startSec > 5f, "the verse is not part of the break (starts at ${best.startSec})")
        assertTrue(best.endSec < 19f, "the outro is not part of the break (ends at ${best.endSec})")
        assertTrue(best.durationSec >= 4f, "a real section, not a fill")

        assertEquals(found, BreakFinder.find(song), "the same song always yields the same dig")
    }

    @Test
    fun `pure tone and pure noise are not breaks`() {
        val tone = Snip(
            FloatArray(10 * rate) { i -> (0.5 * Math.sin(2.0 * Math.PI * 220.0 * i / rate)).toFloat() },
            1, rate,
        )
        assertTrue(BreakFinder.find(tone).isEmpty(), "a held tone is not a break")

        val rnd = kotlin.random.Random(3)
        val noise = Snip(FloatArray(10 * rate) { (rnd.nextFloat() * 2 - 1) * 0.5f }, 1, rate)
        assertTrue(BreakFinder.find(noise).isEmpty(), "steady noise has no hits - not a break")

        val silence = Snip(FloatArray(10 * rate), 1, rate)
        assertTrue(BreakFinder.find(silence).isEmpty(), "silence digs up nothing")
    }

    @Test
    fun `a whole-song break is one long candidate`() {
        val drums = Snip(breakSection(10f), 1, rate)
        val found = BreakFinder.find(drums)
        assertEquals(1, found.size, "one continuous section")
        assertTrue(found[0].startSec < 1.5f && found[0].endSec > 8.5f, "covers the material")
    }

    @Test
    fun `the air lands in the pad sections and never overlaps the break`() {
        // verse (8s) | break (8s) | outro (6s) - the air is the verse/outro.
        val song = join(padSection(8f), breakSection(8f), padSection(6f))
        val air = BreakFinder.air(song)
        assertTrue(air.isNotEmpty(), "a song with a verse has air")

        val breaks = BreakFinder.find(song)
        assertTrue(breaks.isNotEmpty())
        for (a in air) {
            val mid = (a.startSec + a.endSec) / 2
            assertTrue(
                mid < 8f || mid > 16f,
                "air sits in the pad sections, got ${a.startSec}..${a.endSec}",
            )
            for (b in breaks) {
                assertTrue(
                    a.endSec <= b.startSec || a.startSec >= b.endSec,
                    "air ${a.startSec}..${a.endSec} overlaps break ${b.startSec}..${b.endSec}",
                )
            }
        }
        assertEquals(air, BreakFinder.air(song), "the same song always yields the same air")
    }

    @Test
    fun `drums wall to wall and silence honestly yield no air`() {
        assertTrue(
            BreakFinder.air(Snip(breakSection(10f), 1, rate)).isEmpty(),
            "a drums-only file has no air - loud percussive material everywhere",
        )
        assertTrue(BreakFinder.air(Snip(FloatArray(10 * rate), 1, rate)).isEmpty(), "silence is not air")
    }
}
