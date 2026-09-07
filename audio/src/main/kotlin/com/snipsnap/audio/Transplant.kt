package com.snipsnap.audio

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * TRANSPLANT — A's attack wearing B's long-term spectral envelope: a
 * one-knob vocoder. The donor's whole life is folded into one
 * energy-weighted spectrum and read in [bands] log-spaced bands; so is
 * the attack's; the band-by-band difference becomes one fixed set of
 * per-bin gains applied through the [Spectral] door. Nothing moves in
 * time — every frame of A gets the same colour — so A's onset, decay
 * and length are exactly its own, and only its long-term tone is B's.
 *
 * BANDS is the resolution of the borrowed colour: four bands is a tilt,
 * sixty-four is the donor's formants. Gains are capped at ±[MAX_GAIN_DB]
 * so a band A never had cannot be conjured out of the noise floor; the
 * result is peak-matched to A. Deterministic: no seeds, all measurement.
 */
object Transplant {

    const val MIN_BANDS = 4
    const val MAX_BANDS = 64
    const val DEFAULT_BANDS = 16

    /** The lowest band edge; everything under it rides with band one. */
    const val LOW_HZ = 40f

    /** No band is lifted or cut past this — colour, not conjuring. */
    const val MAX_GAIN_DB = 24f

    /** Bands where the attack holds less than this under its own loudest band are left alone: there is nothing to colour. */
    const val SILENT_BAND_DB = -80f

    private const val MAKEUP_MAX = 4f

    /**
     * The long-term level of [snip] in [bands] log-spaced bands from
     * [LOW_HZ] to Nyquist, dB of energy-weighted mean power, relative to
     * nothing in particular — compare shapes, not values.
     */
    fun bandLevels(snip: Snip, bands: Int = DEFAULT_BANDS): FloatArray {
        require(bands in MIN_BANDS..MAX_BANDS) { "bands wants $MIN_BANDS..$MAX_BANDS, got $bands" }
        require(snip.frameCount > 0) { "the source is empty" }
        val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
        val bandOf = bandOfBin(bands, snip.sampleRate)
        val power = DoubleArray(bands)
        val count = IntArray(bands)
        for (b in 0 until Spectral.BINS) count[bandOf[b]]++
        var frames = 0
        Spectral.forEachFrame(mono) { _, _, mags ->
            frames++
            for (b in 0 until Spectral.BINS) power[bandOf[b]] += mags[b].toDouble() * mags[b]
        }
        return FloatArray(bands) { k ->
            val mean = if (count[k] > 0 && frames > 0) power[k] / (count[k].toDouble() * frames) else 0.0
            (10.0 * log10(mean + 1e-20)).toFloat()
        }
    }

    /** [attack] recoloured to [donor]'s long-term band envelope, [bands] wide. */
    fun apply(attack: Snip, donor: Snip, bands: Int = DEFAULT_BANDS): Snip {
        require(bands in MIN_BANDS..MAX_BANDS) { "bands wants $MIN_BANDS..$MAX_BANDS, got $bands" }
        require(attack.frameCount > 0) { "the attack is empty" }
        require(donor.frameCount > 0) { "the donor is empty" }
        val donorAt = if (donor.sampleRate == attack.sampleRate) donor else Resampler.resample(donor, attack.sampleRate)
        val a = bandLevels(attack, bands)
        val b = bandLevels(donorAt, bands)
        val aTop = a.max()
        val bTop = b.max()
        // Shapes, not levels: both envelopes referenced to their own loudest band.
        val bandGainDb = FloatArray(bands) { k ->
            if (a[k] - aTop < SILENT_BAND_DB) 0f
            else ((b[k] - bTop) - (a[k] - aTop)).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        }
        val gains = binGains(bandGainDb, attack.sampleRate)
        val out = Spectral.process(attack) { _, _, _ -> gains }
        val inPeak = attack.peak()
        val outPeak = out.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val makeup = (inPeak / outPeak).coerceAtMost(MAKEUP_MAX)
            for (i in out.samples.indices) out.samples[i] *= makeup
        }
        return out
    }

    /** Band edges, log-spaced from [LOW_HZ] to Nyquist: edge k of `bands + 1`. */
    fun bandEdgesHz(bands: Int, sampleRate: Int): FloatArray {
        val nyq = sampleRate / 2f
        return FloatArray(bands + 1) { k -> LOW_HZ * (nyq / LOW_HZ).pow(k.toFloat() / bands) }
    }

    private fun bandOfBin(bands: Int, sampleRate: Int): IntArray {
        val edges = bandEdgesHz(bands, sampleRate)
        return IntArray(Spectral.BINS) { b ->
            val hz = Spectral.binHz(b, sampleRate)
            var k = 0
            while (k < bands - 1 && hz >= edges[k + 1]) k++
            k
        }
    }

    /** Band gains → one gain per bin, interpolated in log frequency between band centres, held flat beyond the ends. */
    private fun binGains(bandGainDb: FloatArray, sampleRate: Int): FloatArray {
        val bands = bandGainDb.size
        val edges = bandEdgesHz(bands, sampleRate)
        val centers = FloatArray(bands) { k -> ln(Math.sqrt(edges[k].toDouble() * edges[k + 1]).toFloat()) }
        return FloatArray(Spectral.BINS) { b ->
            val hz = Spectral.binHz(b, sampleRate).coerceAtLeast(1f)
            val x = ln(hz)
            val db = when {
                x <= centers[0] -> bandGainDb[0]
                x >= centers[bands - 1] -> bandGainDb[bands - 1]
                else -> {
                    var k = 0
                    while (k < bands - 2 && centers[k + 1] < x) k++
                    val t = (x - centers[k]) / (centers[k + 1] - centers[k])
                    bandGainDb[k] + (bandGainDb[k + 1] - bandGainDb[k]) * t
                }
            }
            10f.pow(db / 20f)
        }
    }
}
