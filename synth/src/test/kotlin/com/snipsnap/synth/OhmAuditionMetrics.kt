package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Descriptive power and time-energy measurements; these do not establish listening approval. */
internal object OhmAuditionMetrics {
    fun analyze(snip: Snip, targetHz: Double?): Map<String, Any?> {
        val frames = snip.frameCount
        val attackEnd = (.1 * snip.sampleRate).roundToInt().coerceIn(0, frames)
        val lateStart = (.5 * snip.sampleRate).roundToInt().coerceIn(0, frames)
        val root = targetHz?.takeIf { it.isFinite() && it > 0.0 }
        // Mean squared channel samples retain stereo energy even when the channels oppose.
        val frameEnergy = DoubleArray(frames) { frame ->
            var energy = 0.0
            for (channel in 0 until snip.channels) {
                val sample = finiteSample(snip, frame, channel).toDouble()
                energy += sample * sample
            }
            energy / snip.channels
        }
        val totalEnergy = frameEnergy.sum()
        val firstEnergy = (0 until attackEnd).sumOf { frameEnergy[it] }
        val lateEnergy = (lateStart until frames).sumOf { frameEnergy[it] }
        val rms20ms = buildList {
            var from = 0
            var bin = 1
            while (from < frames) {
                // Derive each boundary from time so fractional sample counts do not drift.
                val end = (bin * .02 * snip.sampleRate).roundToInt()
                    .coerceAtLeast(from + 1).coerceAtMost(frames)
                val energy = (from until end).sumOf { frameEnergy[it] }
                add(sqrt(energy / (end - from)))
                from = end
                bin++
            }
        }
        return linkedMapOf(
            "spectral" to linkedMapOf(
                "whole" to spectrum(snip, 0, frames, 8192, root),
                "attack" to spectrum(snip, 0, attackEnd, 4096, root),
                "late" to spectrum(snip, lateStart, frames, 8192, root),
            ),
            "envelope" to linkedMapOf(
                "first100msEnergyShare" to fraction(firstEnergy, totalEnergy),
                "after500msEnergyShare" to fraction(lateEnergy, totalEnergy),
                "rms20ms" to rms20ms,
            ),
        )
    }

    /**
     * Hann-windowed, half-overlapping power spectra, averaged across windows and channels.
     * The final window reaches the segment's end. Short segments are Hann-windowed over
     * their available samples and zero padded. All reported spectral ratios and the
     * centroid use only bin centers in 20–12000 Hz (also bounded by Nyquist).
     */
    private fun spectrum(
        snip: Snip, from: Int, until: Int, fftSize: Int, root: Double?,
    ): Map<String, Any?> {
        val power = DoubleArray(fftSize / 2 + 1)
        val length = until - from
        if (length > 0) {
            val count = minOf(length, fftSize)
            val window = FloatArray(count) { i ->
                if (count <= 2) 1f else (.5 - .5 * cos(2.0 * PI * i / (count - 1))).toFloat()
            }
            val windowEnergy = window.sumOf { it.toDouble() * it }
            val lastStart = until - count
            var start = from
            var windows = 0
            while (true) {
                for (channel in 0 until snip.channels) {
                    val real = FloatArray(fftSize)
                    val imaginary = FloatArray(fftSize)
                    for (i in 0 until count) real[i] = finiteSample(snip, start + i, channel) * window[i]
                    Fft.forward(real, imaginary)
                    for (bin in power.indices) {
                        val re = real[bin].toDouble()
                        val im = imaginary[bin].toDouble()
                        val value = (re * re + im * im) / windowEnergy
                        if (value.isFinite()) power[bin] += value
                    }
                }
                windows++
                if (start == lastStart) break
                start = minOf(start + fftSize / 2, lastStart)
            }
            val divisor = windows.toDouble() * snip.channels
            for (bin in power.indices) power[bin] /= divisor
        }
        val rootLow = root?.div(sqrt(2.0))
        val rootHigh = root?.times(sqrt(2.0))
        val octaveHigh = root?.times(2.0 * sqrt(2.0))
        var total = 0.0
        var weightedHz = 0.0
        var rootPower = 0.0
        var octavePower = 0.0
        var upperPower = 0.0
        for (bin in power.indices) {
            val hz = bin.toDouble() * snip.sampleRate / fftSize
            if (hz < 20.0 || hz > 12000.0) continue
            val energy = power[bin]
            total += energy
            weightedHz += hz * energy
            if (rootLow != null && rootHigh != null && octaveHigh != null) {
                when {
                    hz >= rootLow && hz < rootHigh -> rootPower += energy
                    hz >= rootHigh && hz <= octaveHigh -> octavePower += energy
                    hz > octaveHigh -> upperPower += energy
                }
            }
        }
        return linkedMapOf(
            "centroidHz" to if (total > 0.0) weightedHz / total else null,
            "rootBandShare" to if (root != null) fraction(rootPower, total) else null,
            "octaveBandShare" to if (root != null) fraction(octavePower, total) else null,
            "upperBandShare" to if (root != null) fraction(upperPower, total) else null,
        )
    }

    private fun fraction(part: Double, whole: Double): Double? =
        if (whole > 0.0 && whole.isFinite() && part.isFinite()) (part / whole).coerceIn(0.0, 1.0) else null

    private fun finiteSample(snip: Snip, frame: Int, channel: Int): Float =
        snip.samples[frame * snip.channels + channel].takeIf { it.isFinite() } ?: 0f
}
