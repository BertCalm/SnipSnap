package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The prototype's distance measures (prototype/ember/measures.py, from CLUTTER's), shared by EMBER's tests: the RMS
 * difference of level-matched third-octave spectrograms, every band floored 80 dB under the loudest.
 */
internal object EmberMeasures {

    private val thirdOct = DoubleArray(22) { 80.0 * 2.0.pow(it / 3.0) }

    private fun spectroDb(x: FloatArray, frameMs: Int, totalS: Double): Array<DoubleArray> {
        val rate = Ember.RATE
        val take = min(x.size, (totalS * rate).toInt())
        var rms = 0.0
        for (i in 0 until take) rms += x[i].toDouble() * x[i]
        rms = sqrt(rms / max(1, take)) + 1e-12
        val hop = frameMs * rate / 1000
        val win = 4 * hop
        var size = 1
        while (size < win) size = size shl 1
        val padded = DoubleArray(max(take, win + hop)) { if (it < take) x[it] / rms else 0.0 }
        val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / (win - 1)) }
        val rows = ArrayList<DoubleArray>()
        var i = 0
        while (i < max(1, padded.size - win)) {
            val re = FloatArray(size); val im = FloatArray(size)
            for (k in 0 until win) re[k] = (padded[i + k] * hann[k]).toFloat()
            Fft.forward(re, im)
            rows.add(DoubleArray(thirdOct.size) { b ->
                val lo = thirdOct[b] / 2.0.pow(1.0 / 6); val hi = thirdOct[b] * 2.0.pow(1.0 / 6)
                var e = 0.0
                for (bin in 0..size / 2) { val f = bin.toDouble() * rate / size; if (f >= lo && f < hi) e += re[bin].toDouble().pow(2) + im[bin].toDouble().pow(2) }
                10 * log10(e + 1e-12)
            })
            i += hop
        }
        val top = rows.maxOf { it.max() }
        return Array(rows.size) { r -> DoubleArray(thirdOct.size) { max(rows[r][it], top - 80.0) } }
    }

    private fun rmsDiff(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        val n = min(a.size, b.size)
        var s = 0.0; var c = 0
        for (r in 0 until n) for (k in a[r].indices) { s += (a[r][k] - b[r][k]).pow(2); c++ }
        return sqrt(s / c)
    }

    /** The take distance: the first 150 ms in 5 ms frames. */
    fun distanceDb(a: FloatArray, b: FloatArray) = rmsDiff(spectroDb(a, 5, 0.15), spectroDb(b, 5, 0.15))

    /** The whole render in 10 ms frames: DAMP, DISTANCE and the moving voices live past the attack. */
    fun fullDistanceDb(a: FloatArray, b: FloatArray): Double {
        val n = max(a.size, b.size)
        val pa = a.copyOf(n); val pb = b.copyOf(n)
        return rmsDiff(spectroDb(pa, 10, n.toDouble() / Ember.RATE), spectroDb(pb, 10, n.toDouble() / Ember.RATE))
    }

    fun rmsDb(x: FloatArray, t0: Double, t1: Double): Double {
        val a = (t0 * Ember.RATE).toInt(); val b = min(x.size, (t1 * Ember.RATE).toInt())
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return 10 * log10(s / max(1, b - a) + 1e-20)
    }
}
