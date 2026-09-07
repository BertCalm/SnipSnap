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
 * One detector blind spot is compensated here rather than documented
 * away: a hit at the very first frame has no energy baseline to jump
 * from, and *chopped breaks start on the hit*. When the file opens hot
 * (the first 30ms peaks near the file's own loudest) and the detector
 * heard nothing there, the Ear supplies the downbeat itself.
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

    /** The hot-open guard: the first this-much of the file is checked for a downbeat. */
    const val HOT_OPEN_SEC = 0.03f

    fun listen(snip: Snip): List<Hit> {
        val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
        val frames = mutableListOf<Int>()
        Transients.detect(mono).mapTo(frames) { it.frame }

        // The chopped-break case: the file starts ON a hit, which the
        // energy-derivative detector can't see (no baseline to jump from).
        // A hot opening with no onset heard there IS the downbeat.
        val guard = (HOT_OPEN_SEC * mono.sampleRate).toInt()
        if (mono.frameCount > guard && (frames.firstOrNull() ?: Int.MAX_VALUE) > guard) {
            val headPeak = peakOf(mono.samples, 0, guard)
            val globalPeak = peakOf(mono.samples, 0, mono.samples.size)
            if (globalPeak > 1e-6f && headPeak >= 0.25f * globalPeak) frames.add(0, 0)
        }
        if (frames.isEmpty()) return emptyList()

        val cap = (MAX_WINDOW_SEC * mono.sampleRate).toInt()
        val windows = frames.mapIndexed { i, frame ->
            val end = minOf(frames.getOrNull(i + 1) ?: mono.frameCount, frame + cap, mono.frameCount)
            Snip(mono.samples.copyOfRange(frame, end), 1, mono.sampleRate)
        }
        val peaks = windows.map { w -> peakOf(w.samples, 0, w.samples.size) }
        val loudest = peaks.maxOrNull()?.coerceAtLeast(1e-6f) ?: return emptyList()

        return frames.mapIndexed { i, frame ->
            val c = Classifier.classify(windows[i])
            Hit(
                frame = frame,
                drumClass = c.drumClass,
                velocity = (peaks[i] / loudest).coerceIn(0.05f, 1f),
                confidence = c.confidence,
            )
        }
    }

    private fun peakOf(samples: FloatArray, from: Int, to: Int): Float {
        var p = 0f
        for (i in from until minOf(to, samples.size)) {
            val v = if (samples[i] < 0) -samples[i] else samples[i]
            if (v > p) p = v
        }
        return p
    }
}
