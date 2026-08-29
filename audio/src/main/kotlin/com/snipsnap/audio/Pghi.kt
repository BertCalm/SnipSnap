package com.snipsnap.audio

/**
 * Phase Gradient Heap Integration — "the phase vocoder done right"
 * (Průša & Søndergaard 2017), still the classical baseline the neural
 * papers measure against: reconstruct phase from the **magnitude
 * spectrogram's own gradients**. The log-magnitude's slope across
 * frequency says how phase advances in time; its slope across time
 * says how phase changes across frequency (the Cauchy–Riemann
 * relations of the Gaussian window, [GAMMA]-scaled for our Hann); and
 * the integration walks a heap ordered by magnitude, so phase always
 * spreads from the loudest, most trustworthy bins outward. Bins too
 * quiet to trust get seeded random phases instead of nonsense.
 *
 * Geometry matches [Spectral] exactly — [Spectral.FRAME]/[Spectral.HOP]
 * Hann on both sides — so magnitudes from [Spectral.forEachFrame]
 * invert straight back to audio.
 */
object Pghi {

    /** The Hann window's time-frequency spread: γ ≈ 0.25645·M² (ltfat's constant for Hann). */
    const val GAMMA = 0.25645f * Spectral.FRAME * Spectral.FRAME

    /** Bins below this fraction of the global peak get random phase — there is nothing there to integrate. */
    const val TOLERANCE = 1e-6f

    private const val FRAME = Spectral.FRAME
    private const val HOP = Spectral.HOP
    private const val BINS = Spectral.BINS
    private const val COLA = 1.5f

    private val WINDOW = FloatArray(FRAME) { n ->
        (0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / FRAME)).toFloat()
    }

    /**
     * Audio from magnitudes alone. [mags] must be the frames
     * [Spectral.forEachFrame] produces for a mono snip of [outFrames]
     * frames (the padded walk included); phases are integrated, the
     * quiet remainder seeded by [seed].
     */
    fun invert(mags: List<FloatArray>, outFrames: Int, sampleRate: Int, seed: Long = 0): Snip {
        require(mags.isNotEmpty()) { "no frames, no audio" }
        val frames = mags.size
        val phases = integrate(mags, seed)

        val acc = FloatArray(outFrames + 2 * FRAME)
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        for (f in 0 until frames) {
            val at = f * HOP
            if (at + FRAME > acc.size) break
            for (b in 0 until BINS) {
                val m = mags[f][b]
                val p = phases[f][b]
                re[b] = (m * Math.cos(p.toDouble())).toFloat()
                im[b] = (m * Math.sin(p.toDouble())).toFloat()
                if (b in 1 until FRAME / 2) {
                    re[FRAME - b] = re[b]
                    im[FRAME - b] = -im[b]
                }
            }
            im[0] = 0f
            im[FRAME / 2] = 0f
            Fft.inverse(re, im)
            for (n in 0 until FRAME) {
                acc[at + n] += re[n] * WINDOW[n]
            }
        }
        val out = FloatArray(outFrames)
        for (i in 0 until outFrames) out[i] = acc[i + FRAME] / COLA
        return Snip(out, 1, sampleRate)
    }

    /**
     * The clear stretch: the magnitude spectrogram resampled along
     * time by [factor], phases re-invented by the integration — a
     * sine stays a narrow line instead of the paulstretch wash.
     */
    fun stretch(source: Snip, factor: Float, seed: Long = 0): Snip {
        require(factor in 1f..100f) { "factor wants 1..100, got $factor" }
        val mono = if (source.channels == 1) source else Cleanup.toMono(source)
        require(mono.frameCount > 0) { "the source is empty" }
        val mags = mutableListOf<FloatArray>()
        Spectral.forEachFrame(mono) { _, _, m -> mags.add(m.copyOf()) }
        val outFrames = (mono.frameCount * factor.toDouble()).toInt()
        val outCount = ((mags.size - 1) * factor.toDouble()).toInt() + 1
        val stretched = ArrayList<FloatArray>(outCount)
        for (j in 0 until outCount) {
            val x = j / factor.toDouble()
            val i0 = x.toInt().coerceAtMost(mags.size - 1)
            val i1 = (i0 + 1).coerceAtMost(mags.size - 1)
            val frac = (x - i0).toFloat()
            stretched.add(FloatArray(BINS) { b -> mags[i0][b] + (mags[i1][b] - mags[i0][b]) * frac })
        }
        return invert(stretched, outFrames, mono.sampleRate, seed)
    }

    /** Phases for every frame/bin: gradients integrated loudest-first. */
    private fun integrate(mags: List<FloatArray>, seed: Long): Array<FloatArray> {
        val frames = mags.size
        val slog = Array(frames) { f -> FloatArray(BINS) { b -> Math.log(mags[f][b] + 1e-12).toFloat() } }

        // The Cauchy-Riemann gradients, central differences, γ-scaled.
        // Calibrated empirically against known-phase signals on this
        // exact geometry: time takes γ/(aM), frequency takes −aM/γ —
        // each direction scaled by the RECIPROCAL of its own spread.
        val tCoeff = (GAMMA / (HOP.toFloat() * FRAME)) / 2f
        val fCoeff = -(HOP.toFloat() * FRAME / GAMMA) / 2f
        fun tgrad(f: Int, b: Int): Float {
            val up = slog[f][(b + 1).coerceAtMost(BINS - 1)]
            val dn = slog[f][(b - 1).coerceAtLeast(0)]
            return tCoeff * (up - dn) + (2.0 * Math.PI * HOP * b / FRAME).toFloat()
        }
        fun fgrad(f: Int, b: Int): Float {
            val nx = slog[(f + 1).coerceAtMost(frames - 1)][b]
            val pv = slog[(f - 1).coerceAtLeast(0)][b]
            // The -π: our frames reference the frame START, so an event
            // at the window's center carries a linear phase of -π per
            // bin — the term centered-window derivations absorb.
            return fCoeff * (nx - pv) - Math.PI.toFloat()
        }

        var peak = 0f
        for (f in 0 until frames) for (b in 0 until BINS) if (mags[f][b] > peak) peak = mags[f][b]
        val floor = peak * TOLERANCE

        val phases = Array(frames) { FloatArray(BINS) }
        val assigned = Array(frames) { BooleanArray(BINS) }
        val rng = java.util.Random(seed)
        val heap = java.util.PriorityQueue<Int>(compareByDescending { mags[it / BINS][it % BINS] })

        // Seed every significant local region from the loudest bin down;
        // the quiet remainder gets honest random phase.
        var seedF = 0
        var seedB = 0
        for (f in 0 until frames) for (b in 0 until BINS) {
            if (mags[f][b] > mags[seedF][seedB]) {
                seedF = f
                seedB = b
            }
        }
        if (peak > 0f) {
            assigned[seedF][seedB] = true
            heap.add(seedF * BINS + seedB)
        }
        while (heap.isNotEmpty()) {
            val idx = heap.poll()
            val f = idx / BINS
            val b = idx % BINS
            val p = phases[f][b]
            fun visit(nf: Int, nb: Int, dp: Float) {
                if (nf !in 0 until frames || nb !in 0 until BINS) return
                if (assigned[nf][nb] || mags[nf][nb] <= floor) return
                phases[nf][nb] = p + dp
                assigned[nf][nb] = true
                heap.add(nf * BINS + nb)
            }
            visit(f + 1, b, (tgrad(f, b) + tgrad((f + 1).coerceAtMost(frames - 1), b)) / 2f)
            visit(f - 1, b, -(tgrad(f, b) + tgrad((f - 1).coerceAtLeast(0), b)) / 2f)
            visit(f, b + 1, (fgrad(f, b) + fgrad(f, (b + 1).coerceAtMost(BINS - 1))) / 2f)
            visit(f, b - 1, -(fgrad(f, b) + fgrad(f, (b - 1).coerceAtLeast(0))) / 2f)
            // A significant bin unreached from the seed's island starts
            // its own, so disjoint events each get coherent phase.
            if (heap.isEmpty()) {
                var nf = -1
                var nb = -1
                var best = floor
                for (ff in 0 until frames) for (bb in 0 until BINS) {
                    if (!assigned[ff][bb] && mags[ff][bb] > best) {
                        best = mags[ff][bb]
                        nf = ff
                        nb = bb
                    }
                }
                if (nf >= 0) {
                    phases[nf][nb] = (rng.nextFloat() * 2f - 1f) * Math.PI.toFloat()
                    assigned[nf][nb] = true
                    heap.add(nf * BINS + nb)
                }
            }
        }
        for (f in 0 until frames) for (b in 0 until BINS) {
            if (!assigned[f][b]) phases[f][b] = (rng.nextFloat() * 2f - 1f) * Math.PI.toFloat()
        }
        return phases
    }
}
