package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What MAGNET's measured tests read off the dry string at [Magnet.RENDER_RATE]: a copy of the
 * correlation that [StringsPickupTest] keeps privately (one shared copy for MAGNET's tests), and
 * the harmonic read the comb, the humbucker and BLEND claims share.
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
}
