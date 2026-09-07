package com.snipsnap.audio

import kotlin.math.abs

/**
 * The tonal body stretches and bleeds; the attacks stay put.
 *
 * [Separate.hpss] splits the signal into a horizontal (harmonic) part and a
 * vertical (percussive) part whose masks sum to one, so the parts sum back
 * to the input. Only the harmonic part goes through [Pghi.stretch] — the
 * percussive part is never touched.
 *
 * That split matters because of a measurement already on file: [Retime]'s
 * KDoc documents that stretching *both* HPSS halves independently (a vocoder
 * for the harmonic half, short-frame OLA for the percussive) and summing
 * them smeared a kick's attack rise from 33 ms to 51 ms — the two halves
 * drifted out of alignment. SMEAR sidesteps that failure by construction:
 * the percussive half is passed through completely untouched, at its
 * original position and length, so there is nothing for it to drift
 * against.
 */
object Smear {

    /** How far past the harmonic bed's stretched tail to fade it out, killing the truncation edge. */
    private const val TAIL_FADE_MS = 5f

    /** amount 0..1 → harmonic-bed stretch factor 1.0..2.5; 0 = untouched. */
    fun process(snip: Snip, amount: Float): Snip {
        if (amount <= 0f) return snip

        // Pghi.stretch folds to mono internally anyway (it's a mono-only
        // contract, like Retime); fold up front so the HPSS split and the
        // percussive pass-through operate on the same mono signal Pghi
        // will produce, instead of silently diverging on stereo input.
        val mono = Cleanup.toMono(snip)

        val split = Separate.hpss(mono)

        // The percussive half passes through completely untouched - never
        // stretched, never shifted. See the object KDoc: independently
        // time-scaling both HPSS halves and summing them is the rejected
        // Driedger & Müller hybrid Retime's KDoc measured smearing a kick's
        // attack rise from 33 ms to 51 ms. Passing this half through as-is
        // means there is nothing for it to drift out of alignment against.
        val percussive = split.percussive

        val factor = 1f + 1.5f * amount.coerceIn(0f, 1f)
        val stretchedHarmonic = Pghi.stretch(split.harmonic, factor)

        val targetLen = mono.frameCount
        val truncated = FloatArray(targetLen) { i ->
            if (i < stretchedHarmonic.frameCount) stretchedHarmonic.samples[i] else 0f
        }
        val harmonicBed = Cleanup.applyFades(
            Snip(truncated, 1, mono.sampleRate),
            fadeInMs = 0f,
            fadeOutMs = TAIL_FADE_MS,
        )

        val out = FloatArray(targetLen)
        var peak = 0f
        for (i in 0 until targetLen) {
            val mixed = percussive.samples[i] + harmonicBed.samples[i]
            out[i] = mixed
            val a = abs(mixed)
            if (a > peak) peak = a
        }
        // Clamp down only - never boost. hpss masks sum to one, so at
        // factor -> 1 this approaches the input and peak <= 1 already.
        if (peak > 1f) {
            val scale = 1f / peak
            for (i in 0 until targetLen) out[i] *= scale
        }

        return Snip(out, 1, snip.sampleRate)
    }
}
