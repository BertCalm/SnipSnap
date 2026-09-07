package com.snipsnap.audio

/**
 * What the signal chain did to the capture before we ever saw it —
 * measured once from the whole recording, then carried into every
 * per-slice judgement that needs the context.
 *
 * The reference capture taught the lesson: a phone across a room
 * rolls off the sub, so a real kick arrives *gutless* — its identity
 * intact to the ear, its `lowRatio` evidence gone — and a classifier
 * that demands the sub files it as a snare. The profile doesn't guess
 * at gear; it measures **how much low band survived**, and only a
 * provably rolled-off capture changes anything downstream.
 */
data class CaptureProfile(
    /** Share of the capture's sub-1 kHz energy that sits below 150 Hz. */
    val subShare: Float,
    /** True when the sub is provably missing — the mic or chain rolled it off. */
    val rolledOff: Boolean,
) {
    companion object {

        /** Below this frequency lives the sub a kick's rule normally trusts. */
        const val SUB_HZ = 150f

        /** The low-band reference ceiling: sub is judged against everything under this. */
        const val LOW_HZ = 1000f

        /**
         * A full-range beat keeps well over this share of its low-band
         * energy under [SUB_HZ]; a phone capture keeps a sliver. The
         * boundary sits far from both, measured: the reference phone
         * capture reads ~0.01, DrumSynth beats read 0.5+.
         */
        const val ROLLED_OFF_BELOW = 0.15f

        /**
         * Measure the whole capture. Deterministic, spectrum-only: the
         * average magnitude spectrum's energy below [SUB_HZ] against
         * its energy below [LOW_HZ].
         */
        fun measure(snip: Snip): CaptureProfile {
            val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
            var sub = 0.0
            var low = 0.0
            var frames = 0
            Spectral.forEachFrame(mono) { _, _, mags ->
                frames++
                for (b in mags.indices) {
                    val hz = Spectral.binHz(b, mono.sampleRate)
                    if (hz > LOW_HZ) return@forEachFrame
                    val e = mags[b].toDouble() * mags[b]
                    low += e
                    if (hz < SUB_HZ) sub += e
                }
            }
            if (low <= 1e-12 || frames == 0) return CaptureProfile(subShare = 0f, rolledOff = false)
            val share = (sub / low).toFloat()
            return CaptureProfile(subShare = share, rolledOff = share < ROLLED_OFF_BELOW)
        }
    }
}
