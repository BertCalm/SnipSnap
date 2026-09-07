package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransplantTest {

    private val rate = 44_100

    /** The donor: a dark hum (110 Hz, harmonics falling 1/n) with one narrow formant of noise around 1.5 kHz. A second long. */
    private fun hum(): Snip {
        val rnd = java.util.Random(5)
        val phases = DoubleArray(40) { rnd.nextDouble() * 2 * Math.PI }
        return Snip(
            FloatArray(rate) { i ->
                val t = i.toDouble() / rate
                var s = 0.0
                for (n in 1..30) s += Math.sin(2 * Math.PI * 110.0 * n * t) / n
                for (k in 0 until 40) s += 0.12 * Math.sin(2 * Math.PI * (1400.0 + k * 5.0) * t + phases[k])
                (0.25 * s).toFloat()
            },
            1, rate,
        )
    }

    /** Short-time RMS, 10 ms frames. */
    private fun envelope(s: Snip): DoubleArray {
        val mono = if (s.channels == 1) s.samples else Cleanup.toMono(s).samples
        val win = rate / 100
        return DoubleArray(mono.size / win) { f ->
            var acc = 0.0
            for (i in f * win until (f + 1) * win) acc += mono[i].toDouble() * mono[i]
            Math.sqrt(acc / win)
        }
    }

    private fun corr(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        val ma = a.take(n).average()
        val mb = b.take(n).average()
        var dot = 0.0
        var ea = 0.0
        var eb = 0.0
        for (i in 0 until n) {
            dot += (a[i] - ma) * (b[i] - mb)
            ea += (a[i] - ma) * (a[i] - ma)
            eb += (b[i] - mb) * (b[i] - mb)
        }
        return dot / Math.sqrt(ea * eb + 1e-30)
    }

    private fun shape(s: Snip, bands: Int): DoubleArray = Transplant.bandLevels(s, bands).map { it.toDouble() }.toDoubleArray()

    @Test
    fun `the result's onset is the snare's, its band envelope the hum's`() {
        val snare = DrumSynth.snare()
        val donor = hum()
        val out = Transplant.apply(snare, donor, 16)
        assertEquals(snare.frameCount, out.frameCount, "A's length, exactly")
        assertEquals(snare.channels, out.channels)

        val envA = envelope(snare)
        val envOut = envelope(out)
        val envB = envelope(donor)
        val withA = corr(envOut, envA)
        val withB = corr(envOut, envB)
        assertTrue(withA > 0.95, "the onset and decay are the snare's: $withA")
        assertTrue(withA > withB, "and not the hum's: $withA vs $withB")

        val bandOut = shape(out, 16)
        val bandA = shape(snare, 16)
        val bandB = shape(donor, 16)
        val toneB = corr(bandOut, bandB)
        val toneA = corr(bandOut, bandA)
        assertTrue(toneB > 0.85, "the long-term tone is the hum's: $toneB")
        assertTrue(toneB > toneA, "closer to the hum than to the snare it started as: $toneB vs $toneA")

        assertTrue(Math.abs(out.peak() - snare.peak()) < 0.02f, "peak matched: ${out.peak()} vs ${snare.peak()}")
    }

    @Test
    fun `BANDS is the resolution of the borrowed colour - sixty-four finds the formant four cannot`() {
        val snare = DrumSynth.snare()
        val donor = hum()
        val coarse = Transplant.apply(snare, donor, 4)
        val fine = Transplant.apply(snare, donor, 64)
        val target = shape(donor, 64)
        val coarseFit = corr(shape(coarse, 64), target)
        val fineFit = corr(shape(fine, 64), target)
        assertTrue(fineFit > coarseFit, "more bands, closer to the donor's fine shape: $fineFit vs $coarseFit")
        // Both still keep the snare's time.
        assertTrue(corr(envelope(fine), envelope(snare)) > 0.95)
        assertTrue(corr(envelope(coarse), envelope(snare)) > 0.95)
    }

    @Test
    fun `stereo stays stereo, the same inputs give the same bytes, and the bounds hold`() {
        val snare = DrumSynth.snare()
        val stereo = Snip(FloatArray(snare.frameCount * 2) { i -> snare.samples[i / 2] * if (i % 2 == 0) 1f else 0.7f }, 2, rate)
        val a = Transplant.apply(stereo, hum(), 12)
        val b = Transplant.apply(stereo, hum(), 12)
        assertEquals(2, a.channels)
        assertEquals(stereo.frameCount, a.frameCount)
        assertTrue(a.samples.contentEquals(b.samples), "all measurement, no seed")

        assertFailsWith<IllegalArgumentException> { Transplant.apply(snare, hum(), 3) }
        assertFailsWith<IllegalArgumentException> { Transplant.apply(snare, hum(), 65) }
        assertFailsWith<IllegalArgumentException> { Transplant.apply(Snip(FloatArray(0), 1, rate), hum()) }
        assertFailsWith<IllegalArgumentException> { Transplant.apply(snare, Snip(FloatArray(0), 1, rate)) }
        val edges = Transplant.bandEdgesHz(8, rate)
        assertEquals(9, edges.size)
        assertEquals(Transplant.LOW_HZ, edges.first(), 1e-3f)
        assertEquals(rate / 2f, edges.last(), 1e-1f)
    }
}
