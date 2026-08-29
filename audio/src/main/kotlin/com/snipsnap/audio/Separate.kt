package com.snipsnap.audio

/**
 * The Split — median-filter mask separation on the [Spectral] door.
 *
 * Fitzgerald's observation, still the honest workhorse of source
 * separation: in a spectrogram, harmonic content draws *horizontal*
 * lines (a note holds its bins across time) and percussive content
 * draws *vertical* ones (a hit covers all bins for a moment). A median
 * filter across time keeps the horizontal and erases the vertical; a
 * median filter across frequency does the opposite. The two filtered
 * spectrograms become soft Wiener-style masks that sum to one per bin,
 * so **the parts sum back to the input** — separation that can prove
 * it lost nothing. No seeds, no thresholds tuned by ear: it is all
 * measurement, and the same input always splits the same way.
 */
object Separate {

    /** Time-median window: ~17 frames ≈ 100 ms — a note holds longer, a hit never does. */
    const val TIME_MEDIAN_FRAMES = 17

    /** Frequency-median window: ~17 bins ≈ 730 Hz — a hit's verticals span far more, a note's partial far less. */
    const val FREQ_MEDIAN_BINS = 17

    private const val EPS = 1e-9f

    data class Split(val harmonic: Snip, val percussive: Snip)

    /** Both parts of [snip]; `harmonic.samples[i] + percussive.samples[i]` reconstructs the input. */
    fun hpss(snip: Snip): Split {
        require(snip.frameCount > 0) { "the source is empty" }
        // One analysis pass: every frame's magnitudes, per channel.
        val mags = Array(snip.channels) { mutableListOf<FloatArray>() }
        Spectral.forEachFrame(snip) { ch, _, m -> mags[ch].add(m.copyOf()) }

        // The two filtered tellings, then Wiener masks that sum to one.
        val masks = Array(snip.channels) { ch -> maskPair(mags[ch]) }
        val harmonicMask = Array(snip.channels) { ch -> masks[ch].first }
        val percussiveMask = Array(snip.channels) { ch -> masks[ch].second }

        val h = Spectral.process(snip) { ch, frame, _ -> harmonicMask[ch][frame] }
        val p = Spectral.process(snip) { ch, frame, _ -> percussiveMask[ch][frame] }
        return Split(h, p)
    }

    fun harmonic(snip: Snip): Snip = hpss(snip).harmonic

    fun percussive(snip: Snip): Snip = hpss(snip).percussive

    // ---- STN: sines / transients / noise (PP2) ----------------------------

    /** Fully sines above this ratio of time-median to the pair. */
    const val SINES_UPPER = 0.8f

    /** The sines ramp starts here — between the two, a raised cosine. */
    const val SINES_LOWER = 0.7f

    /** Fully transient below this ratio. */
    const val TRANSIENT_LOWER = 0.2f

    /** The transient ramp ends here. */
    const val TRANSIENT_UPPER = 0.3f

    data class Stn(val sines: Snip, val transients: Snip, val noise: Snip)

    /**
     * The anatomy lesson — Fierro & Välimäki's fuzzy STN in its
     * single-resolution telling (the papers refine with a second
     * window size; ours reads once at [Spectral.FRAME]): the ratio of
     * the time-median to the pair says what each bin *is*. Strongly
     * horizontal → sines, strongly vertical → transients, and the
     * in-between — neither a held line nor a broadband instant — is
     * noise. Raised-cosine ramps between the named thresholds keep the
     * masks fuzzy, they still sum to one, and the three parts still
     * sum back to the input.
     */
    fun stn(snip: Snip): Stn {
        require(snip.frameCount > 0) { "the source is empty" }
        val mags = Array(snip.channels) { mutableListOf<FloatArray>() }
        Spectral.forEachFrame(snip) { ch, _, m -> mags[ch].add(m.copyOf()) }

        val frames = mags[0].size
        val sMask = Array(snip.channels) { Array(frames) { FloatArray(Spectral.BINS) } }
        val tMask = Array(snip.channels) { Array(frames) { FloatArray(Spectral.BINS) } }
        val nMask = Array(snip.channels) { Array(frames) { FloatArray(Spectral.BINS) } }
        for (ch in 0 until snip.channels) {
            val (hv, pv) = medians(mags[ch])
            for (f in 0 until frames) {
                for (b in 0 until Spectral.BINS) {
                    val rt = hv[f][b] / (hv[f][b] + pv[f][b] + EPS)
                    val s = ramp(rt, SINES_LOWER, SINES_UPPER)
                    val t = 1f - ramp(rt, TRANSIENT_LOWER, TRANSIENT_UPPER)
                    sMask[ch][f][b] = s
                    tMask[ch][f][b] = t
                    nMask[ch][f][b] = 1f - s - t
                }
            }
        }
        return Stn(
            sines = Spectral.process(snip) { ch, f, _ -> sMask[ch][f] },
            transients = Spectral.process(snip) { ch, f, _ -> tMask[ch][f] },
            noise = Spectral.process(snip) { ch, f, _ -> nMask[ch][f] },
        )
    }

    /** 0 below [lo], 1 above [hi], a raised cosine between. */
    private fun ramp(x: Float, lo: Float, hi: Float): Float = when {
        x <= lo -> 0f
        x >= hi -> 1f
        else -> {
            val u = (x - lo) / (hi - lo)
            (Math.sin(Math.PI / 2.0 * u).let { it * it }).toFloat()
        }
    }

    /** (harmonic masks, percussive masks) per frame for one channel's magnitudes. */
    private fun maskPair(mags: List<FloatArray>): Pair<Array<FloatArray>, Array<FloatArray>> {
        val (hv, pv) = medians(mags)
        val frames = mags.size
        val hMask = Array(frames) { FloatArray(Spectral.BINS) }
        val pMask = Array(frames) { FloatArray(Spectral.BINS) }
        for (f in 0 until frames) {
            for (b in 0 until Spectral.BINS) {
                // Wiener masks with a shared floor: they sum to exactly one.
                val h2 = hv[f][b] * hv[f][b] + EPS
                val p2 = pv[f][b] * pv[f][b] + EPS
                hMask[f][b] = h2 / (h2 + p2)
                pMask[f][b] = p2 / (h2 + p2)
            }
        }
        return hMask to pMask
    }

    /** The two filtered tellings: (time-median, frequency-median) per frame/bin. */
    private fun medians(mags: List<FloatArray>): Pair<Array<FloatArray>, Array<FloatArray>> {
        val frames = mags.size
        val bins = Spectral.BINS
        val hv = Array(frames) { FloatArray(bins) }
        val pv = Array(frames) { FloatArray(bins) }
        val timeWindow = FloatArray(TIME_MEDIAN_FRAMES)
        val freqWindow = FloatArray(FREQ_MEDIAN_BINS)
        for (f in 0 until frames) {
            for (b in 0 until bins) {
                // Median across time at this bin: the harmonic telling.
                var n = 0
                for (k in f - TIME_MEDIAN_FRAMES / 2..f + TIME_MEDIAN_FRAMES / 2) {
                    if (k in 0 until frames) timeWindow[n++] = mags[k][b]
                }
                hv[f][b] = median(timeWindow, n)
                // Median across frequency in this frame: the percussive telling.
                n = 0
                for (k in b - FREQ_MEDIAN_BINS / 2..b + FREQ_MEDIAN_BINS / 2) {
                    if (k in 0 until bins) freqWindow[n++] = mags[f][k]
                }
                pv[f][b] = median(freqWindow, n)
            }
        }
        return hv to pv
    }

    private fun median(window: FloatArray, n: Int): Float {
        val copy = window.copyOf(n)
        copy.sort()
        return copy[n / 2]
    }
}
