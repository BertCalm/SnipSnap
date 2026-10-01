package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the bowed string's tests read off its raw wave: the pitch by autocorrelation, how many times a
 * period the string slips, the harmonics and the sawtooth's asymmetry, the level over a window. All at
 * the rate the bow runs at, on the unfinished wave; none of it is a finished pad's measure.
 *
 * The pitch is the autocorrelation lag with parabolic interpolation, the way the tuning share was
 * pinned, and not an FFT peak: at 65 Hz an FFT bin is wider than the 5-cent bar the tests hold.
 */
internal object BowMeter {

    val RATE = Dsp.RATE * Dsp.OVERSAMPLE

    /** A raw bow render: the wave the bow returns, the string's velocity under the bow, and the bow's sustain velocity. */
    class Run(val out: FloatArray, val bowPoint: FloatArray, val vMax: Float)

    /**
     * The bow played through the spike's envelope - a 20 ms linear attack on the bow's velocity, a hold, then (if
     * [releaseAt] is given) a 50 ms linear release to 0 and a lift - at [pressure] (slope `5 - 4 * pressure`) and
     * [amplitude] (sustain velocity `0.03 + 0.2 * amplitude`, STK's). [noLift] leaves a released bow resting on the
     * string; [liftAtStart] never lets it down.
     */
    fun play(
        f0: Float,
        beta: Float,
        pressure: Float,
        amplitude: Float,
        seconds: Float,
        releaseAt: Float? = null,
        bridgeHz: Float = Strings.Bow.BRIDGE_HZ,
        share: Float = Strings.Bow.SHARE,
        rhoMax: Float = Strings.Bow.RHO_MAX,
        liftAtStart: Boolean = false,
        noLift: Boolean = false,
        builtAt: Float = f0,
        retuneAt: Float? = null,
    ): Run {
        val bow = Strings.Bow(builtAt, beta, bridgeHz, share, RATE, rhoMax)
        val n = (seconds * RATE).toInt()
        val vMax = 0.03f + 0.2f * amplitude
        val slope = 5f - 4f * pressure
        val attackN = (0.020f * RATE).toInt()
        val releaseN = (0.050f * RATE).toInt()
        val releaseStart = if (releaseAt == null) Int.MAX_VALUE else (releaseAt * RATE).toInt()
        val retuneStart = if (retuneAt == null) Int.MAX_VALUE else (retuneAt * RATE).toInt()
        val out = FloatArray(n)
        val bowPoint = FloatArray(n)
        var lifted = liftAtStart
        if (liftAtStart) bow.lift()
        for (i in 0 until n) {
            val env = when {
                i < attackN -> i.toFloat() / attackN
                i < releaseStart -> 1f
                i < releaseStart + releaseN -> 1f - (i - releaseStart).toFloat() / releaseN
                else -> 0f
            }
            if (i == retuneStart) bow.retune(f0)
            if (!noLift && !lifted && releaseStart != Int.MAX_VALUE && i >= releaseStart + releaseN) {
                bow.lift()
                lifted = true
            }
            out[i] = bow.next(vMax * env, slope)
            bowPoint[i] = bow.bowPoint
        }
        return Run(out, bowPoint, vMax)
    }

    fun mean(x: FloatArray, from: Int, to: Int): Double {
        val b = min(to, x.size)
        var s = 0.0
        for (i in from until b) s += x[i]
        return s / (b - from)
    }

    fun rms(x: FloatArray, from: Int, to: Int): Double {
        val b = min(to, x.size)
        var s = 0.0
        for (i in from until b) s += x[i].toDouble() * x[i]
        return sqrt(s / (b - from))
    }

    fun maxAbs(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var m = 0f
        for (i in from until min(to, x.size)) m = max(m, abs(x[i]))
        return m
    }

    fun cents(measuredHz: Double, wantHz: Double) = 1200.0 * log2(measuredHz / wantHz)

    private fun autocorrelation(x: FloatArray, from: Int, len: Int, lagFrom: Int, lagTo: Int): DoubleArray {
        val r = DoubleArray(lagTo + 1)
        val m = mean(x, from, from + len)
        var e = 0.0
        for (i in from until from + len) {
            val v = x[i] - m
            e += v * v
        }
        if (e <= 1e-18) return r
        for (lag in lagFrom..lagTo) {
            var s = 0.0
            for (i in from until from + len - lag) s += (x[i] - m) * (x[i + lag] - m)
            r[lag] = s / e
        }
        return r
    }

    /** The pitch near [f0] (within 6 percent) by autocorrelation with parabolic interpolation, and the height of the peak (1 is a perfect repeat). */
    fun pitch(x: FloatArray, from: Int, len: Int, f0: Float): Pair<Double, Double> {
        val p = RATE / f0.toDouble()
        val lo = (p * 0.94).toInt().coerceAtLeast(2)
        val hi = (p * 1.06).toInt() + 1
        val r = autocorrelation(x, from, len, lo - 1, hi + 1)
        var best = lo
        var top = -2.0
        for (lag in lo..hi) if (r[lag] > top) {
            top = r[lag]
            best = lag
        }
        val den = r[best - 1] - 2 * r[best] + r[best + 1]
        val delta = if (den != 0.0) 0.5 * (r[best - 1] - r[best + 1]) / den else 0.0
        return (RATE / (best + delta)) to top
    }

    private fun goertzel(x: FloatArray, from: Int, len: Int, hz: Double): Double {
        val w = 2.0 * PI * hz / RATE
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until len) {
            val win = 0.5 - 0.5 * cos(2.0 * PI * i / (len - 1))
            val s = x[from + i] * win + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun peakPower(x: FloatArray, from: Int, len: Int, hz: Double): Double {
        var best = 0.0
        for (s in -5..5) best = max(best, goertzel(x, from, len, hz * (1.0 + 0.015 * s / 5.0)))
        return best
    }

    private fun db(p: Double, ref: Double) = 10.0 * log10(max(p, 1e-30) / max(ref, 1e-30))

    /** The first [count] harmonics' levels in dB against the first, at multiples of the measured pitch. */
    fun harmonics(x: FloatArray, from: Int, len: Int, measuredHz: Double, count: Int = 8): DoubleArray {
        val first = peakPower(x, from, len, measuredHz)
        return DoubleArray(count) { db(peakPower(x, from, len, measuredHz * (it + 1)), first) }
    }

    /**
     * A sawtooth's two signatures over the last whole period ending at [end]: the ratio of its fastest rise to its
     * fastest fall (a sine is 1; a sawtooth's flyback is many times as steep as its ramp, so the ratio is small) and
     * the fraction of the period the wave moves at under a fifth of its steepest step (a sine is 0.13; a sawtooth's
     * long ramp puts it close to 1).
     */
    fun sawtooth(x: FloatArray, end: Int, periodSamples: Double): Pair<Double, Double> {
        val p = periodSamples.roundToInt()
        val start = end - p - 1
        var up = 0.0
        var down = 0.0
        var steepest = 0.0
        val step = DoubleArray(p)
        for (i in 0 until p) {
            step[i] = (x[start + i + 1] - x[start + i]).toDouble()
            up = max(up, step[i])
            down = min(down, step[i])
            steepest = max(steepest, abs(step[i]))
        }
        var still = 0
        for (v in step) if (abs(v) < 0.2 * steepest) still++
        return (if (down == 0.0) Double.POSITIVE_INFINITY else up / -down) to still.toDouble() / p
    }

    /**
     * Slips per period on the string's velocity under the bow: falling crossings of half the bow's velocity over
     * the last [periods] periods, divided by [periods]. A string in Helmholtz motion sticks once and slips once a
     * period (1.0); a double slip is 2.0.
     */
    fun slipsPerPeriod(bowPoint: FloatArray, end: Int, periodSamples: Double, vBow: Float, periods: Int = 10): Double {
        val start = end - (periodSamples * periods).roundToInt()
        val threshold = 0.5 * vBow
        var count = 0
        for (i in start until end) if (bowPoint[i - 1] >= threshold && bowPoint[i] < threshold) count++
        return count.toDouble() / periods
    }
}
