package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Narrow-band energy at a tone, for the PLUCK and TINES tests: a Goertzel
 * filter over the first [seconds] of a mono snip, summed across ±8 Hz in
 * 2 Hz steps so a harmonic that sits a few cents off its nominal frequency
 * (the tuning bound allows five) still lands inside the measure.
 */
internal object PluckSpectra {

    fun toneEnergy(snip: Snip, hz: Float, seconds: Float = 0.25f): Double {
        require(snip.channels == 1) { "PluckSpectra measures mono snips" }
        val n = min(snip.frameCount, (seconds * snip.sampleRate).toInt())
        var total = 0.0
        var offset = -8
        while (offset <= 8) {
            total += goertzel(snip.samples, n, hz + offset, snip.sampleRate)
            offset += 2
        }
        return total
    }

    /** The fundamental's energy against harmonics 2–4 together. */
    fun fundamentalShare(snip: Snip, f0: Float): Double {
        val h1 = toneEnergy(snip, f0)
        val rest = toneEnergy(snip, 2 * f0) + toneEnergy(snip, 3 * f0) + toneEnergy(snip, 4 * f0)
        return h1 / (rest + 1e-12)
    }

    private fun goertzel(x: FloatArray, n: Int, hz: Float, rate: Int): Double {
        val w = 2.0 * PI * hz / rate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s = x[i] + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}
