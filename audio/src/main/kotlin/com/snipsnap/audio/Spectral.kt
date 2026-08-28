package com.snipsnap.audio

/**
 * The frequency-domain door — everything wave NN needs and nothing it
 * doesn't, on the classifier's own [Fft]: a Hann-windowed STFT whose
 * overlap-add reconstructs the input exactly (to float precision) when
 * nothing is changed, so any difference in the output is a difference
 * someone *asked for*.
 *
 * Geometry: [FRAME]-point frames every [HOP] samples (75% overlap),
 * Hann on analysis *and* synthesis — the standard weighted overlap-add
 * shape, so a per-bin gain applied in the middle can't leave clicks at
 * frame edges. At that hop the squared window tiles to a constant
 * ([COLA]), which is the whole reconstruction proof.
 */
object Spectral {

    /** 1024 points at 44.1 kHz: ~43 Hz bins, ~23 ms frames — fine enough to separate hiss from hats, short enough not to smear transients. */
    const val FRAME = 1024

    /** 75% overlap: gains can move frame to frame without zipper noise. */
    const val HOP = FRAME / 4

    /** Distinct magnitudes per frame (DC..Nyquist). */
    const val BINS = FRAME / 2 + 1

    /** Σ hann²(n − k·HOP) — constant at this hop; synthesis divides by it. */
    private const val COLA = 1.5f

    private val WINDOW = FloatArray(FRAME) { n ->
        (0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / FRAME)).toFloat()
    }

    /** The center frequency of magnitude bin [bin] at [sampleRate]. */
    fun binHz(bin: Int, sampleRate: Int): Float = Fft.binToHz(bin, FRAME, sampleRate)

    /**
     * The analysis half on its own: every frame's [BINS] magnitudes, per
     * channel, cheap enough to run ahead of [process] — a noise profile
     * wants one look at the whole capture before it decides anything.
     */
    fun forEachFrame(snip: Snip, action: (channel: Int, frame: Int, mags: FloatArray) -> Unit) {
        stft(snip, resynth = false) { ch, frame, re, im ->
            action(ch, frame, magnitudes(re, im))
            null
        }
    }

    /**
     * The processing door: each frame's magnitudes go to [gainFor], and
     * the gains that come back (size [BINS], or null for unity) scale
     * the frame's bins before resynthesis. Frames are indexed per
     * channel in time order, so a caller can carry smoothing state.
     */
    fun process(snip: Snip, gainFor: (channel: Int, frame: Int, mags: FloatArray) -> FloatArray?): Snip {
        val out = stft(snip, resynth = true) { ch, frame, re, im ->
            gainFor(ch, frame, magnitudes(re, im))
        }
        return Snip(out!!, snip.channels, snip.sampleRate)
    }

    private fun magnitudes(re: FloatArray, im: FloatArray): FloatArray =
        FloatArray(BINS) { b -> Math.hypot(re[b].toDouble(), im[b].toDouble()).toFloat() }

    /**
     * The shared walk: pad by a frame at both ends (so the edges get
     * full window coverage), window → FFT → optional per-bin gains →
     * inverse FFT → window again → overlap-add, then trim the padding
     * and normalize by [COLA]. With [resynth] off, the inverse half is
     * skipped and null comes back — that's [forEachFrame].
     */
    private fun stft(
        snip: Snip,
        resynth: Boolean,
        gains: (ch: Int, frame: Int, re: FloatArray, im: FloatArray) -> FloatArray?,
    ): FloatArray? {
        val frames = snip.frameCount
        val channels = snip.channels
        val padded = frames + 2 * FRAME
        val out = if (resynth) FloatArray(snip.samples.size) else null
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        for (ch in 0 until channels) {
            val acc = if (resynth) FloatArray(padded) else null
            var frameIx = 0
            var at = 0
            while (at + FRAME <= padded) {
                for (n in 0 until FRAME) {
                    val src = at + n - FRAME
                    val x = if (src in 0 until frames) snip.samples[src * channels + ch] else 0f
                    re[n] = x * WINDOW[n]
                    im[n] = 0f
                }
                Fft.forward(re, im)
                val g = gains(ch, frameIx, re, im)
                if (resynth) {
                    if (g != null) {
                        require(g.size == BINS) { "gains must cover $BINS bins, got ${g.size}" }
                        for (b in 0 until BINS) {
                            val gv = g[b]
                            re[b] *= gv
                            im[b] *= gv
                            // The conjugate mirror moves with its twin, or
                            // the inverse transform stops being real.
                            if (b in 1 until FRAME / 2) {
                                re[FRAME - b] *= gv
                                im[FRAME - b] *= gv
                            }
                        }
                    }
                    Fft.inverse(re, im)
                    for (n in 0 until FRAME) {
                        acc!![at + n] += re[n] * WINDOW[n]
                    }
                }
                at += HOP
                frameIx++
            }
            if (resynth) {
                for (f in 0 until frames) {
                    out!![f * channels + ch] = acc!![f + FRAME] / COLA
                }
            }
        }
        return out
    }
}
