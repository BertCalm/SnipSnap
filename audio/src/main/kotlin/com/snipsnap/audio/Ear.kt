package com.snipsnap.audio

/**
 * The Ear (LL3) — listening to a recording as a *performance*, not a
 * bag of sounds. Everything before this chopped audio into pads; the
 * Ear hears which drum hits when: the same onset detection the chopper
 * trusts, then the same classifier chop uses on its slices, run on each
 * inter-onset window. No new models — the machinery that already knows
 * what a kick sounds like, pointed at time instead of at samples.
 *
 * Honest by construction: every hit carries the classifier's own
 * confidence, so callers can gate — a hit the ear isn't sure about is
 * *marked*, never invented. Deterministic: same audio, same hearing.
 *
 * One known blind spot, inherited from the detector: a hit at the very
 * first frame has no energy baseline to jump from and can go unheard.
 * Real recordings carry room ahead of beat one; synthetic fixtures
 * should too.
 */
object Ear {

    /** One heard hit: where, what, how hard, and how sure. */
    data class Hit(
        val frame: Int,
        val drumClass: DrumClass,
        /** Window peak against the loudest hit, 0..1. */
        val velocity: Float,
        val confidence: Float,
    )

    /** A window never reaches past this — ring-out isn't the next hit's business. */
    const val MAX_WINDOW_SEC = 0.25f

    fun listen(snip: Snip): List<Hit> {
        val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
        val onsets = Transients.detect(mono)
        if (onsets.isEmpty()) return emptyList()

        val cap = (MAX_WINDOW_SEC * mono.sampleRate).toInt()
        val windows = onsets.mapIndexed { i, o ->
            val end = minOf(
                onsets.getOrNull(i + 1)?.frame ?: mono.frameCount,
                o.frame + cap,
                mono.frameCount,
            )
            Snip(mono.samples.copyOfRange(o.frame, end), 1, mono.sampleRate)
        }
        val peaks = windows.map { w -> w.samples.maxOfOrNull { v -> if (v < 0) -v else v } ?: 0f }
        val loudest = peaks.maxOrNull()?.coerceAtLeast(1e-6f) ?: return emptyList()

        return onsets.mapIndexed { i, o ->
            val c = Classifier.classify(windows[i])
            Hit(
                frame = o.frame,
                drumClass = c.drumClass,
                velocity = (peaks[i] / loudest).coerceIn(0.05f, 1f),
                confidence = c.confidence,
            )
        }
    }
}
