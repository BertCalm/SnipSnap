package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measurements of a sustained stretch for the hold-humanization tests. They answer the owner's
 * note "holds are unnatural": is the held part static, or does it repeat inside itself? Nothing
 * here is heard.
 */
internal object HoldDriftProbes {
    private const val HOP = 441

    /** About 50 ms, as a whole number of periods of [hz], so the note's own waveform does not ripple the level. */
    fun windowFor(hz: Double): Int = ((.05 * hz).roundToInt().coerceAtLeast(1) * Dsp.RATE / hz).roundToInt()

    /**
     * Level every [hop] samples (10 ms unless told otherwise) over a [window] of whole periods,
     * in dB about its median and floored 40 dB below it. With [circular] the stretch is a loop
     * and the windows wrap, so the trajectory has no edge.
     */
    fun levelDb(x: FloatArray, hz: Double, circular: Boolean = true, hop: Int = HOP): DoubleArray {
        val window = windowFor(hz)
        val count = if (circular) x.size / hop else (x.size - window) / hop
        val rms = DoubleArray(count) { f ->
            var sum = 0.0
            for (i in 0 until window) { val v = x[(f * hop + i) % x.size].toDouble(); sum += v * v }
            sqrt(sum / window)
        }
        val median = rms.sorted()[count / 2].coerceAtLeast(1e-12)
        return DoubleArray(count) { 20 * log10(max(rms[it] / median, .01)) }
    }

    fun range(values: DoubleArray): Double = values.max() - values.min()

    /**
     * How closely the trajectory of a loop matches itself moved along by any lag from 0.1 s to
     * half the loop: the smallest RMS difference, in the trajectory's own unit. A static hold
     * and a hold that repeats inside its loop both measure 0.
     */
    fun recurrence(loop: DoubleArray): Double {
        var best = Double.MAX_VALUE
        for (lag in 10..loop.size / 2) {
            var sum = 0.0
            for (i in loop.indices) { val d = loop[i] - loop[(i + lag) % loop.size]; sum += d * d }
            best = minOf(best, sqrt(sum / loop.size))
        }
        return best
    }

    /** Brightness every 10 ms: how much of the frame's energy sits in its sample-to-sample change, dB. */
    fun brightnessDb(x: FloatArray, hz: Double): DoubleArray {
        val window = windowFor(hz)
        return DoubleArray(x.size / HOP) { f ->
            var energy = 0.0
            var change = 0.0
            for (i in 0 until window) {
                val a = x[(f * HOP + i) % x.size].toDouble()
                val d = x[(f * HOP + i + 1) % x.size] - a
                energy += a * a
                change += d * d
            }
            10 * log10((change + 1e-30) / (energy + 1e-30))
        }
    }

    class Pitch(val cents: DoubleArray, val weight: DoubleArray) {
        /** Level-weighted mean, so the silence between strikes does not vote. */
        val mean: Double get() = cents.indices.sumOf { cents[it] * weight[it] } / weight.sum()
        fun rangeWhereLoud(): Double {
            val floor = weight.max() * .25
            val loud = cents.indices.filter { weight[it] >= floor }.map { cents[it] }
            return loud.max() - loud.min()
        }
    }

    /**
     * The fundamental's pitch every 10 ms, in cents from [hz]: the signal is shifted down by
     * [hz] and averaged over a whole number of periods (about 50 ms), which nulls the harmonics;
     * the turning of what is left is the pitch offset.
     */
    fun pitch(x: FloatArray, hz: Double): Pitch {
        val window = windowFor(hz)
        val re = DoubleArray(x.size + 1)
        val im = DoubleArray(x.size + 1)
        for (i in x.indices) {
            val a = 2 * PI * hz * i / Dsp.RATE
            re[i + 1] = re[i] + x[i] * cos(a)
            im[i + 1] = im[i] - x[i] * sin(a)
        }
        val frames = (x.size - window) / HOP
        val phase = DoubleArray(frames)
        val level = DoubleArray(frames)
        for (f in 0 until frames) {
            val r = re[f * HOP + window] - re[f * HOP]
            val i = im[f * HOP + window] - im[f * HOP]
            phase[f] = atan2(i, r)
            level[f] = r * r + i * i
        }
        val cents = DoubleArray(frames - 1)
        val weight = DoubleArray(frames - 1)
        for (f in 0 until frames - 1) {
            var d = phase[f + 1] - phase[f]
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            val offsetHz = d / (2 * PI) * Dsp.RATE / HOP
            cents[f] = 1200 * ln(1 + offsetHz / hz) / ln(2.0)
            weight[f] = minOf(level[f], level[f + 1])
        }
        return Pitch(cents, weight)
    }

    fun tile(x: FloatArray, times: Int): FloatArray = FloatArray(x.size * times) { x[it % x.size] }

    fun hz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
}
