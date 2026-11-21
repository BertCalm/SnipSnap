package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Building blocks for the identity layers of CHAMBER's and FERRY's round-2 candidates (ChamberCandidateLayers,
 * FerryCandidateLayers). Every function is pure and seeded, works at [Dsp.RATE] and only adds into the buffer it is
 * given. Nothing here touches a kept voice.
 */
internal object VoiceLayerKit {
    const val RATE = Dsp.RATE
    /** No partial is written at or above this, so nothing folds back from near Nyquist. */
    private const val TOP_HZ = 16_000.0

    /**
     * Adds one partial from [start] seconds: a sine at [hz] that rises over [attack] seconds on a sine-squared ramp
     * and falls to -60 dB in [t60] seconds, times [gain] (indexed by output frame) when given. With [bend] the pitch
     * starts that fraction above [hz] and falls back with time constant [bendTau].
     */
    fun partial(out: DoubleArray, hz: Double, amp: Double, attack: Double, t60: Double, start: Double = 0.0,
                phase: Double = 0.0, bend: Double = 0.0, bendTau: Double = .05, gain: DoubleArray? = null) {
        if (amp == 0.0 || hz <= 0.0 || hz * (1 + max(0.0, bend)) >= TOP_HZ) return
        val from = (start * RATE).toInt().coerceIn(0, out.size)
        val decay = exp(-ln(1000.0) / (t60 * RATE))
        val ramp = max(1.0, attack * RATE)
        val w = 2 * PI * hz / RATE
        var e = 1.0
        if (bend == 0.0) {
            // A rotating phasor, renormalised now and then: the same sine without a sin() a sample.
            var c = cos(phase); var s = sin(phase)
            val cw = cos(w); val sw = sin(w)
            for (i in from until out.size) {
                val n = i - from
                if (n > ramp && e < 1e-6) break
                val a = if (n < ramp) sin(.5 * PI * n / ramp).let { it * it } else 1.0
                out[i] += amp * a * e * (gain?.get(i) ?: 1.0) * s
                val c2 = c * cw - s * sw
                s = s * cw + c * sw
                c = c2
                if (n and 1023 == 1023) { val r = 1.0 / kotlin.math.sqrt(c * c + s * s); c *= r; s *= r }
                e *= decay
            }
        } else {
            var p = phase
            val fall = exp(-1.0 / (bendTau * RATE))
            var b = bend
            for (i in from until out.size) {
                val n = i - from
                if (n > ramp && e < 1e-6) break
                val a = if (n < ramp) sin(.5 * PI * n / ramp).let { it * it } else 1.0
                out[i] += amp * a * e * (gain?.get(i) ?: 1.0) * sin(p)
                p += w * (1 + b)
                b *= fall
                e *= decay
            }
        }
    }

    /** Two cascaded one-pole lowpasses at [lowpassHz] and two highpasses at [highpassHz], in place. */
    fun band(x: DoubleArray, lowpassHz: Double, highpassHz: Double) {
        val a = 1.0 - exp(-2 * PI * lowpassHz.coerceAtMost(.45 * RATE) / RATE)
        val h = 1.0 - exp(-2 * PI * highpassHz / RATE)
        var l1 = 0.0; var l2 = 0.0; var h1 = 0.0; var h2 = 0.0
        for (i in x.indices) {
            l1 += a * (x[i] - l1); l2 += a * (l1 - l2)
            h1 += h * (l2 - h1)
            val y = l2 - h1
            h2 += h * (y - h2)
            x[i] = y - h2
        }
    }

    /**
     * Adds a burst of seeded noise from [start] lasting [seconds] (a raised-cosine window), band-passed, with its
     * peak at [amp]: a mallet's thock, a hammer's click, a plectrum's tick.
     */
    fun burst(out: DoubleArray, random: Random, start: Double, seconds: Double, amp: Double, lowpassHz: Double, highpassHz: Double = 30.0) {
        if (amp == 0.0) return
        val n = max(4, (seconds * RATE).toInt())
        val ring = (.012 * RATE).toInt()
        val x = DoubleArray(n + ring) { i -> if (i < n) (random.nextDouble() * 2 - 1) * (.5 - .5 * cos(2 * PI * (i + .5) / n)) else 0.0 }
        band(x, lowpassHz, highpassHz)
        val peak = x.maxOf { abs(it) }.coerceAtLeast(1e-12)
        val from = (start * RATE).toInt()
        for (i in x.indices) if (from + i in out.indices) out[from + i] += amp * x[i] / peak
    }

    /**
     * Adds a noise tail from [start]: it rises over [attack] seconds and falls to -60 dB in [t60], band-passed, its
     * peak at [amp]: a hall's boom, a bow's rosin.
     */
    fun tail(out: DoubleArray, random: Random, start: Double, attack: Double, t60: Double, amp: Double,
             lowpassHz: Double, highpassHz: Double, shape: DoubleArray? = null) {
        if (amp == 0.0) return
        val from = (start * RATE).toInt().coerceIn(0, out.size)
        val x = DoubleArray(out.size - from)
        val decay = exp(-ln(1000.0) / (t60 * RATE))
        val ramp = max(1.0, attack * RATE)
        var e = 1.0
        for (i in x.indices) {
            val a = if (i < ramp) sin(.5 * PI * i / ramp).let { it * it } else 1.0
            x[i] = (random.nextDouble() * 2 - 1) * a * e * (shape?.get(from + i) ?: 1.0)
            e *= decay
        }
        band(x, lowpassHz, highpassHz)
        val peak = x.maxOf { abs(it) }.coerceAtLeast(1e-12)
        for (i in x.indices) out[from + i] += amp * x[i] / peak
    }

    /** A sine-squared rise from 0 at [from] to 1 at [to] seconds, for frame [i]. */
    fun rise(i: Int, from: Double, to: Double): Double {
        val t = i.toDouble() / RATE
        if (t <= from) return 0.0
        if (t >= to) return 1.0
        return sin(.5 * PI * (t - from) / (to - from)).let { it * it }
    }

    /**
     * The voice: the identity [layer] with the engine's own [network] underneath at [bodyMix], each first brought to
     * the same loudness, written back into [network]. The engine's leveling and touch run afterwards as before.
     */
    fun mix(network: FloatArray, layer: DoubleArray, bodyMix: Double) {
        val f = FloatArray(network.size) { if (it < layer.size) layer[it].toFloat() else 0f }
        val net = Loudness.of(network, 1, RATE).toDouble()
        val own = Loudness.of(f, 1, RATE).toDouble()
        if (own <= 1e-9) return
        val n = if (net > 1e-9) bodyMix / net else 0.0
        for (i in network.indices) network[i] = (.1 * (n * network[i] + f[i] / own)).toFloat()
    }
}
