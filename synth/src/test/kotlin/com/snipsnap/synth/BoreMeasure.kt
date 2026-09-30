package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What BORE's tests read off the *raw* loop output, before the band limit and the
 * level (so [Dsp.levelTo] cannot lift a silent render to the loudness target and hide
 * the defect - the `Fork.bank` rule). Every measure is one R1's own probe used, on the
 * same signals, so a bound in a test is a bound on a number that was seen.
 *
 * Pitch is the peak of the autocorrelation, refined between samples; harmonic levels
 * are a Hann-windowed DFT peak-picked within 0.7% of each multiple (a rectangular
 * window leaks: PluckSpectra's Goertzel is right for a tone's presence and wrong for
 * a ratio between neighbours); aperiodicity is the energy left over when a note is
 * subtracted from itself one period later.
 */
internal object BoreMeasure {

    val RAW_RATE = Dsp.RATE * Dsp.OVERSAMPLE

    fun acRms(x: FloatArray, from: Float, to: Float, rate: Int = RAW_RATE): Double {
        val a = (from * rate).toInt()
        val b = minOf(x.size, (to * rate).toInt())
        var mean = 0.0
        for (i in a until b) mean += x[i]
        mean /= (b - a)
        var s = 0.0
        for (i in a until b) { val d = x[i] - mean; s += d * d }
        return sqrt(s / (b - a).coerceAtLeast(1))
    }

    /** The period, in samples, of the note near [hz], between [from] and [to] seconds. */
    fun period(x: FloatArray, hz: Float, from: Float, to: Float, rate: Int = RAW_RATE): Double {
        val a = (from * rate).toInt()
        val n = minOf(x.size, (to * rate).toInt()) - a
        val t = rate / hz
        val lo = (t * 0.90).toInt()
        val hi = (t * 1.10).toInt() + 1
        val r = DoubleArray(hi - lo + 1)
        for (lag in lo..hi) {
            var s = 0.0
            for (i in a until a + n - hi) s += x[i].toDouble() * x[i + lag]
            r[lag - lo] = s
        }
        var best = 0
        for (i in r.indices) if (r[i] > r[best]) best = i
        val i0 = best.coerceIn(1, r.size - 2)
        val d = (r[i0 - 1] - r[i0 + 1]) / (2 * (r[i0 - 1] - 2 * r[i0] + r[i0 + 1]))
        return lo + i0 + d
    }

    fun cents(x: FloatArray, hz: Float, from: Float, to: Float, rate: Int = RAW_RATE): Double =
        1200 * ln(rate / period(x, hz, from, to, rate) / hz) / ln(2.0)

    /** Levels of harmonics 1..[n], in dB against the strongest of them (so the leader reads 0). */
    fun harmonicsDb(x: FloatArray, hz: Float, from: Float, to: Float, n: Int = 6, rate: Int = RAW_RATE): List<Double> {
        val a = (from * rate).toInt()
        val len = minOf(x.size, (to * rate).toInt()) - a
        val f0 = rate / period(x, hz, from, to, rate)
        val w = DoubleArray(len) { 0.5 - 0.5 * cos(2 * PI * it / len) }
        val mags = DoubleArray(n)
        for (k in 1..n) {
            var best = 0.0
            for (s in -14..14) {
                val f = k * f0 * (1 + s * 0.0005)
                val step = 2 * PI * f / rate
                var re = 0.0
                var im = 0.0
                var i = 0
                while (i < len) {
                    val v = x[a + i] * w[i]
                    val ph = step * i
                    re += v * cos(ph)
                    im += v * sin(ph)
                    i += 2
                }
                best = maxOf(best, sqrt(re * re + im * im))
            }
            mags[k - 1] = best
        }
        val strongest = mags.max()
        return mags.map { 20 * log10(it / strongest + 1e-12) }
    }

    /** Energy left when the note is subtracted from itself one period on, over its own energy, in dB: more negative is a purer tone. */
    fun aperiodicityDb(x: FloatArray, hz: Float, from: Float, to: Float, rate: Int = RAW_RATE): Double {
        val lag = period(x, hz, from, to, rate)
        val a = (from * rate).toInt()
        val b = minOf(x.size, (to * rate).toInt())
        var e = 0.0
        var s = 0.0
        for (i in a until b) {
            val j = i - lag
            val k = j.toInt()
            val f = j - k
            val prev = x[k] * (1 - f) + x[k + 1] * f
            val d = x[i] - prev
            e += d * d
            s += x[i].toDouble() * x[i]
        }
        return 10 * log10(e / (2 * s) + 1e-18)
    }

    /** Seconds until a note first reaches [fraction] of its steady level, read on [step]-second windows; [steadyFrom]..[steadyTo] is where "steady" is measured. */
    fun onsetSeconds(x: FloatArray, steadyFrom: Float, steadyTo: Float, fraction: Double = 0.8, step: Float = 0.01f, rate: Int = RAW_RATE): Float {
        val steady = acRms(x, steadyFrom, steadyTo, rate)
        var t = 0f
        while (t + step <= steadyFrom) {
            if (acRms(x, t, t + step, rate) >= fraction * steady) return t
            t += step
        }
        return steadyFrom
    }

    /**
     * A reed's bite, in dB: the energy from [BITE_BAND_LOW_HZ] to [BITE_BAND_HIGH_HZ] against the
     * fundamental's, over [seconds] of a *rendered* note from [fromSec] on (past the attack). Higher
     * is buzzier. Measured on the render, not the raw loop, because the bite is an output stage
     * ([Bore.biteGain]). A Goertzel sweep in 16 Hz steps, each summing +-8 Hz, tiles the band.
     */
    fun biteDb(snip: Snip, f0: Float, fromSec: Float = 0.55f, seconds: Float = 0.25f): Double {
        val start = (fromSec * snip.sampleRate).toInt()
        require(snip.frameCount - start >= (seconds * snip.sampleRate).toInt()) { "biteDb: the render ends before the window" }
        val slice = Snip(snip.samples.copyOfRange(start, snip.frameCount), channels = 1, sampleRate = snip.sampleRate)
        var band = 0.0
        var hz = BITE_BAND_LOW_HZ
        while (hz < BITE_BAND_HIGH_HZ) { band += PluckSpectra.toneEnergy(slice, hz, seconds); hz += 16f }
        val fundamental = PluckSpectra.toneEnergy(slice, f0, seconds)
        return 10 * log10(band / (fundamental + 1e-12) + 1e-18)
    }

    const val BITE_BAND_LOW_HZ = 1_000f
    const val BITE_BAND_HIGH_HZ = 4_000f
}
