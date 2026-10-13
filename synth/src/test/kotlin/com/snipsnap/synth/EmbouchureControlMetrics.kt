package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Gain-independent response measurements; these numbers do not establish an audibility verdict. */
internal object EmbouchureControlMetrics {
    data class Reading(
        val onset: DoubleArray,
        val body: DoubleArray,
        val bodyHarmonicsDb: DoubleArray,
        val bodyHarmonicShares: DoubleArray,
        val bodyRootShare: Double,
        val bodyNonharmonicShare: Double,
        val attack20Seconds: Double,
        val attack80Seconds: Double,
        val energy90SpanSeconds: Double,
    )

    private fun erb(hz: Double) = 21.4 * log10(1 + .00437 * hz)
    private fun inverseErb(value: Double) = (10.0.pow(value / 21.4) - 1) / .00437
    private val edges = DoubleArray(49) { i -> inverseErb(erb(35.0) + i * (erb(16000.0) - erb(35.0)) / 48) }

    private fun power(snip: Snip, from: Double, to: Double): DoubleArray {
        val n = 8192
        val first = (from * snip.sampleRate).toInt().coerceIn(0, snip.frameCount)
        val end = (to * snip.sampleRate).toInt().coerceIn(first, snip.frameCount)
        val count = end - first
        val out = DoubleArray(n / 2)
        val window = FloatArray(n) { i -> (.5 - .5 * cos(2 * PI * i / (n - 1))).toFloat() }
        val starts = if (count < n) listOf(first) else (first..end - n step n / 2).toList()
        for (start in starts) {
            val re = FloatArray(n) { i -> if (start + i < end) snip.samples[start + i] * window[i] else 0f }
            val im = FloatArray(n)
            Fft.forward(re, im)
            for (k in 1 until n / 2) out[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        }
        return out
    }

    private fun bands(power: DoubleArray, rate: Int): DoubleArray {
        val out = DoubleArray(48)
        for (k in 1 until power.size) {
            val hz = k.toDouble() * rate / (power.size * 2)
            val band = (0 until out.size).firstOrNull { hz >= edges[it] && hz < edges[it + 1] } ?: continue
            out[band] += power[k]
        }
        val energy = out.sum().coerceAtLeast(1e-30)
        for (i in out.indices) out[i] /= energy
        return out
    }

    fun measure(snip: Snip, gestureSeconds: Double, rootHz: Double): Reading {
        require(snip.channels == 1)
        val hop = (snip.sampleRate * .01).toInt()
        val envelope = DoubleArray((snip.frameCount + hop - 1) / hop) { bin ->
            val first = bin * hop
            val end = minOf(snip.frameCount, first + hop)
            sqrt((first until end).sumOf { snip.samples[it].toDouble().pow(2) } / (end - first))
        }
        val peak = envelope.maxOrNull() ?: 0.0
        require(peak > 1e-12) { "control response probe is silent" }
        fun attack(fraction: Double) = envelope.indexOfFirst { it >= peak * fraction } * hop.toDouble() / snip.sampleRate
        val envelopeEnergy = envelope.sumOf { it * it }
        fun quantile(fraction: Double): Double {
            var sum = 0.0
            for (i in envelope.indices) {
                sum += envelope[i] * envelope[i]
                if (sum >= envelopeEnergy * fraction) return i * hop.toDouble() / snip.sampleRate
            }
            return snip.durationSeconds.toDouble()
        }
        val onset = power(snip, 0.0, .25)
        val body = power(snip, .35 * gestureSeconds, .75 * gestureSeconds)
        val harmonics = DoubleArray(12)
        var spectralEnergy = 0.0
        var nonharmonic = 0.0
        // Track harmonic balance relative to each point's measured root, so tiny root drift
        // cannot masquerade as a changed contact spectrum solely by crossing a fixed ERB edge.
        val width = maxOf(12.0, rootHz * .035)
        for (k in 1 until body.size) {
            val hz = k.toDouble() * snip.sampleRate / (body.size * 2)
            if (hz !in 35.0..16000.0) continue
            spectralEnergy += body[k]
            val harmonic = Math.round(hz / rootHz).toInt()
            if (harmonic >= 1 && abs(hz - harmonic * rootHz) <= width) {
                if (harmonic <= harmonics.size) harmonics[harmonic - 1] += body[k]
            } else nonharmonic += body[k]
        }
        val harmonicDb = DoubleArray(harmonics.size) { i ->
            10 * log10(harmonics[i].coerceAtLeast(1e-30) / harmonics[0].coerceAtLeast(1e-30))
        }
        val harmonicShares = DoubleArray(harmonics.size) { harmonics[it] / spectralEnergy.coerceAtLeast(1e-30) }
        return Reading(bands(onset, snip.sampleRate), bands(body, snip.sampleRate), harmonicDb, harmonicShares,
            harmonics[0] / spectralEnergy.coerceAtLeast(1e-30), nonharmonic / spectralEnergy.coerceAtLeast(1e-30),
            attack(.2), attack(.8), quantile(.95) - quantile(.05))
    }

    /** Spectral gain is removed; jointly negligible bands cannot inflate the comparison. */
    fun weightedDb(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size)
        var weighted = 0.0
        var totalWeight = 0.0
        for (i in a.indices) {
            val weight = (a[i] + b[i]) / 2
            if (weight <= 1e-5) continue
            val delta = 10 * log10((b[i] + 1e-12) / (a[i] + 1e-12))
            weighted += weight * delta * delta
            totalWeight += weight
        }
        return sqrt(weighted / totalWeight.coerceAtLeast(1e-30))
    }

    fun attackDifferenceSeconds(a: Reading, b: Reading) = maxOf(
        abs(a.attack20Seconds - b.attack20Seconds), abs(a.attack80Seconds - b.attack80Seconds))
}
