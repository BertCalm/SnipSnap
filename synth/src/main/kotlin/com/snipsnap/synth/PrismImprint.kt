package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Transplant
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A donor's smoothed, fixed-Hz spectral colour sampled onto PRISM's existing
 * carriers. This is an envelope imprint, not source separation, pitch tracking
 * or a reconstruction of the donor. The resulting coefficients belong in the
 * saved [PrismMotion], so replay never needs the original recording.
 *
 * The shared analyzer uses 1024-point windows (~43 Hz bins at 44.1 kHz).
 * Low-register harmonic detail is deliberately not claimed by this adapter.
 */
object PrismImprint {
    fun endpoint(donor: Snip, partialHz: List<Float>, bands: Int = 32): List<Float> {
        require(partialHz.size == Prism.PARTIALS && partialHz.all { it.isFinite() && it > 0f }) {
            "PRISM imprint needs 32 finite positive carrier frequencies"
        }
        require(donor.frameCount > 0 && donor.samples.all { it.isFinite() } && donor.peak() > 1e-7f) {
            "PRISM imprint needs a non-silent finite donor"
        }
        val levels = Transplant.bandLevels(donor, bands)
        val edges = Transplant.bandEdgesHz(bands, donor.sampleRate)
        val centers = List(bands) { k -> ln(sqrt(edges[k].toDouble() * edges[k + 1])) }
        val top = levels.max()
        return partialHz.mapIndexed { index, hz ->
            val x = ln(hz.toDouble())
            val db = when {
                hz >= donor.sampleRate / 2f -> top - 80f
                x <= centers.first() -> levels.first()
                x >= centers.last() -> levels.last()
                else -> {
                    var k = 0
                    while (k < bands - 2 && x >= centers[k + 1]) k++
                    val share = ((x - centers[k]) / (centers[k + 1] - centers[k])).toFloat()
                    levels[k] + (levels[k + 1] - levels[k]) * share
                }
            }
            val weight = 10.0.pow(((db - top).coerceIn(-60f, 0f)) / 20.0).toFloat()
            if (index == 0) weight.coerceAtLeast(.12f) else weight
        }
    }
}
