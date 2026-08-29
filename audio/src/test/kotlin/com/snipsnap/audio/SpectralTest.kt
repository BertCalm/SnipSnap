package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectralTest {

    private val rate = 44_100

    private fun beat(): Snip {
        val total = FloatArray(2 * rate)
        val plan = listOf(0.05f to DrumSynth.kick(), 0.6f to DrumSynth.snare(), 1.2f to DrumSynth.closedHat())
        for ((at, hit) in plan) {
            val start = (at * rate).toInt()
            for (i in hit.samples.indices) {
                val idx = start + i
                if (idx < total.size) total[idx] += hit.samples[i] * 0.8f
            }
        }
        return Snip(total, 1, rate)
    }

    @Test
    fun `the FFT puts a tone in its bin and the new inverse round-trips it`() {
        val n = 1024
        val bin = 40
        val re = FloatArray(n) { i -> Math.cos(2.0 * Math.PI * bin * i / n).toFloat() }
        val im = FloatArray(n)
        val orig = re.copyOf()
        Fft.forward(re, im)
        val mags = FloatArray(n) { Math.hypot(re[it].toDouble(), im[it].toDouble()).toFloat() }
        val peak = mags.indices.maxBy { mags[it] }
        assertTrue(peak == bin || peak == n - bin, "the tone lands in bin $bin, not $peak")
        assertTrue(mags[bin] > 100 * mags[bin + 7], "and stands alone: ${mags[bin]} vs ${mags[bin + 7]}")

        Fft.inverse(re, im)
        for (i in 0 until n) {
            assertTrue(Math.abs(re[i] - orig[i]) < 1e-4, "round trip at $i: ${re[i]} vs ${orig[i]}")
            assertTrue(Math.abs(im[i]) < 1e-4)
        }
    }

    @Test
    fun `Parseval holds - the spectrum carries the frame's energy`() {
        val n = 1024
        val rnd = java.util.Random(3)
        val re = FloatArray(n) { (rnd.nextFloat() * 2f - 1f) }
        val im = FloatArray(n)
        val timeEnergy = re.sumOf { (it * it).toDouble() }
        Fft.forward(re, im)
        val freqEnergy = (0 until n).sumOf { (re[it] * re[it] + im[it] * im[it]).toDouble() } / n
        assertTrue(Math.abs(timeEnergy - freqEnergy) < 1e-3 * timeEnergy, "$timeEnergy vs $freqEnergy")
    }

    @Test
    fun `unity STFT reconstructs the beat - edges included`() {
        val snip = beat()
        val back = Spectral.process(snip) { _, _, _ -> null }
        assertEquals(snip.samples.size, back.samples.size)
        var worst = 0f
        for (i in snip.samples.indices) {
            val d = Math.abs(back.samples[i] - snip.samples[i])
            if (d > worst) worst = d
        }
        assertTrue(worst < 1e-4f, "nothing asked for, nothing changed: worst diff $worst")
    }

    @Test
    fun `per-bin gains land where they claim - a spectral lowpass`() {
        // A 300 Hz tone and a 6 kHz tone; zero every bin above 1 kHz.
        val n = 2 * rate
        val snip = Snip(
            FloatArray(n) { i ->
                (0.3 * Math.sin(2.0 * Math.PI * 300.0 * i / rate) +
                    0.3 * Math.sin(2.0 * Math.PI * 6000.0 * i / rate)).toFloat()
            },
            1, rate,
        )
        val cut = FloatArray(Spectral.BINS) { b -> if (Spectral.binHz(b, rate) > 1000f) 0f else 1f }
        val low = Spectral.process(snip) { _, _, _ -> cut }
        fun probe(s: FloatArray, hz: Double): Double {
            var c = 0.0
            var si = 0.0
            for (i in s.indices) {
                val w = 2.0 * Math.PI * hz * i / rate
                c += s[i] * Math.cos(w)
                si += s[i] * Math.sin(w)
            }
            return Math.hypot(c, si) / s.size
        }
        val kept = probe(low.samples, 300.0) / probe(snip.samples, 300.0)
        val gone = probe(low.samples, 6000.0) / probe(snip.samples, 6000.0)
        assertTrue(kept in 0.95..1.05, "the low tone survives: $kept")
        assertTrue(gone < 0.01, "the high tone is gone: $gone")
    }

    @Test
    fun `forEachFrame sees the same spectra process does, in stereo too`() {
        // Left carries a 500 Hz tone, right is silent - the analysis
        // walk must keep the channels apart.
        val n = rate
        val samples = FloatArray(n * 2)
        for (i in 0 until n) samples[i * 2] = (0.4 * Math.sin(2.0 * Math.PI * 500.0 * i / rate)).toFloat()
        val snip = Snip(samples, 2, rate)
        val bin = Math.round(500.0 * Spectral.FRAME / rate).toInt()
        var leftPeak = 0f
        var rightPeak = 0f
        var framesSeen = 0
        Spectral.forEachFrame(snip) { ch, _, mags ->
            framesSeen++
            if (ch == 0) leftPeak = maxOf(leftPeak, mags[bin]) else rightPeak = maxOf(rightPeak, mags[bin])
        }
        assertTrue(framesSeen > 2 * (n / Spectral.HOP), "every frame of both channels visited")
        assertTrue(leftPeak > 1f, "the tone shows in the left analysis: $leftPeak")
        assertTrue(rightPeak < 0.01f, "silence stays silent on the right: $rightPeak")
    }
}
