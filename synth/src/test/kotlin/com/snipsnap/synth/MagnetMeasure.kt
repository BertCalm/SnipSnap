package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What MAGNET's measured tests read off the dry string at [Magnet.RENDER_RATE]: a copy of the
 * correlation that [StringsPickupTest] keeps privately (one shared copy for MAGNET's tests), and
 * the harmonic read the comb, the humbucker and BLEND claims share. The ring reads (the
 * fundamental's loss per second and the envelope's fall) work on a finished note at the rack's
 * rate and are the sustain spike's, copied unchanged, so a test's numbers are the spike's.
 */
internal object MagnetMeasure {

    /** Amplitude of [y]'s component at [hz], by correlation over [y]'s whole length (exact over whole periods). */
    fun amplitudeAt(y: FloatArray, hz: Double, rate: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in y.indices) {
            val ph = 2 * PI * hz * i / rate
            re += y[i] * cos(ph)
            im += y[i] * sin(ph)
        }
        return 2 * sqrt(re * re + im * im) / y.size
    }

    /**
     * Levels of harmonics 1..[n] of [x] near [f0], between [from] and [to] seconds, in dB against
     * harmonic 1. [BoreMeasure.harmonicsDb] returns dB against the strongest harmonic, which is a
     * different harmonic in two different renders, so a quantity compared across renders (the
     * humbucker's notch against the single coil, BLEND's swing) is taken against harmonic 1 here.
     * Entry 0 is always 0.
     */
    fun relH1(x: FloatArray, f0: Float, from: Float, to: Float, n: Int): List<Double> {
        val h = BoreMeasure.harmonicsDb(x, f0, from, to, n, Magnet.RENDER_RATE)
        val first = h[0]
        return h.map { it - first }
    }

    /** [x] rounded to [places] decimals, for the one-line prints. */
    fun round(x: Double, places: Int = 1): Double {
        val scale = 10.0.pow(places)
        return Math.round(x * scale) / scale
    }

    fun round(xs: List<Double>, places: Int = 1): List<Double> = xs.map { round(it, places) }

    // ---------- the ring: the sustain spike's reads, on a finished note at the rack's rate ----------

    private fun db(ratio: Double): Double = 20.0 * log10(ratio.coerceAtLeast(1e-12))

    /** Hann-windowed DFT magnitude at [hz] over x[a, a+len), by phasor rotation. */
    fun dftMagnitude(x: FloatArray, a: Int, len: Int, hz: Double, rate: Int): Double {
        val step = 2 * PI * hz / rate
        val c = cos(step)
        val s = sin(step)
        var pr = 1.0
        var pi = 0.0
        var re = 0.0
        var im = 0.0
        val wStep = 2 * PI / len
        for (i in 0 until len) {
            val w = 0.5 - 0.5 * cos(wStep * i)
            val v = x[a + i] * w
            re += v * pr
            im -= v * pi
            val npr = pr * c - pi * s
            pi = pr * s + pi * c
            pr = npr
        }
        return sqrt(re * re + im * im)
    }

    /** The slope of the least-squares line through ([x], [y]). */
    fun slope(x: DoubleArray, y: DoubleArray): Double {
        val mx = x.average()
        val my = y.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in x.indices) {
            sxx += (x[i] - mx) * (x[i] - mx)
            sxy += (x[i] - mx) * (y[i] - my)
        }
        return sxy / sxx
    }

    /**
     * The fundamental's loss in dB per second: the level of h1 (Hann-windowed DFT peak within 0.8
     * percent of [f0], over a window of [periods] whole periods) at window centres every 5 ms from
     * [fromSec] to [toSec], as a straight line in dB against time. A positive number, or NaN when the
     * note does not reach [toSec] or the level reaches the floor before it.
     */
    fun h1LossDbPerSec(x: FloatArray, f0: Double, fromSec: Double = 0.1, toSec: Double = 0.4, periods: Int = 8, rate: Int = Dsp.RATE): Double {
        val len = (periods * rate / f0).toInt()
        val ts = ArrayList<Double>()
        val ls = ArrayList<Double>()
        var t = fromSec
        while (t <= toSec + 1e-9) {
            val a = (t * rate).toInt() - len / 2
            if (a < 0 || a + len > x.size) return Double.NaN
            var best = 0.0
            val steps = 33
            for (s in 0 until steps) {
                val rel = -0.008 + 0.016 * s / (steps - 1)
                best = max(best, dftMagnitude(x, a, len, f0 * (1 + rel), rate))
            }
            val d = db(best * 2 / (len * 0.5))
            if (d < -120.0) return Double.NaN
            ts.add(t)
            ls.add(d)
            t += 0.005
        }
        return -slope(ts.toDoubleArray(), ls.toDoubleArray())
    }

    /**
     * The seconds from the envelope's peak until a short-window RMS (40 ms windows hopped by 5 ms)
     * first sits [drop] dB under it; NaN if it never does. The spike's t-20 is `fall(x, 20.0)`.
     */
    fun fallSeconds(x: FloatArray, drop: Double, rate: Int = Dsp.RATE): Double {
        val win = (0.040 * rate).toInt()
        val hop = (0.005 * rate).toInt()
        val count = max(1, (x.size - win) / hop + 1)
        val e = DoubleArray(count)
        for (i in 0 until count) {
            val a = i * hop
            var s = 0.0
            val b = min(x.size, a + win)
            for (k in a until b) s += x[k].toDouble() * x[k]
            e[i] = 10.0 * log10((s / max(1, b - a) + 1e-30).coerceAtLeast(1e-18))
        }
        var peak = 0
        for (i in e.indices) if (e[i] > e[peak]) peak = i
        val limit = e[peak] - drop
        for (i in peak until e.size) if (e[i] <= limit) return (i - peak) * hop.toDouble() / rate
        return Double.NaN
    }
}
