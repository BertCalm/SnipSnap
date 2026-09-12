package com.snipsnap.audio

import kotlin.math.max
import kotlin.math.min

/** One chopped piece, and where it came from in the source snip. */
data class Slice(
    val snip: Snip,
    /** Start frame in the source. */
    val sourceFrame: Int,
    /** What triggered this cut, when detection made it. */
    val onset: Onset? = null,
)

/** The space after a hit: [slice] runs from where the hit has died down to the next cut; [afterHit] indexes the hit it follows. */
data class Ghost(val afterHit: Int, val slice: Slice)

/**
 * Cuts a captured snip into pad-sized pieces.
 *
 * Two modes, because there are two ways people chop:
 *
 * - [byTransients] — follow the hits. Right for a break or anything percussive.
 * - [intoEqualParts] — divide evenly. Right when the material is a known number
 *   of bars and the grid matters more than the attacks.
 */
object Chopper {

    /** Auto slice-count never exceeds this — two banks of pads. */
    const val AUTO_MAX = 64

    /** A drop past this ratio in the sorted peak curve reads as the knee. */
    const val KNEE_RATIO = 0.5f

    /** How much audio after an onset votes for its loudness, seconds. */
    const val KNEE_WINDOW_SEC = 0.05f

    /**
     * How many slices this audio *wants* — so nobody has to guess `16`.
     *
     * Detect everything, rank each onset by the peak level in the 50 ms
     * behind it (the dB novelty in [Onset.strength] swings ±10 dB with
     * whatever floor a hit rises from; the peak is what the ear ranks by),
     * and cut at the knee: the steepest drop in the sorted curve, where
     * the real hits end and the detector's table scraps begin. No drop
     * past [KNEE_RATIO] means the hits are all of a kind — keep them all,
     * bounded to [AUTO_MAX]. Fewer than three onsets are simply the answer.
     */
    fun autoSliceCount(snip: Snip, config: Transients.Config = Transients.Config()): Int {
        val onsets = Transients.detect(snip, config)
        if (onsets.size <= 2) return onsets.size

        val window = (snip.sampleRate * KNEE_WINDOW_SEC).toInt()
        val peaks = onsets.map { o ->
            var p = 0f
            val from = o.frame * snip.channels
            val to = min(snip.samples.size, (o.frame + window) * snip.channels)
            for (i in from until to) {
                val a = if (snip.samples[i] < 0) -snip.samples[i] else snip.samples[i]
                if (a > p) p = a
            }
            p
        }.sortedDescending()

        val limit = min(peaks.size, AUTO_MAX)
        var knee = limit
        var steepest = KNEE_RATIO
        for (i in 2 until limit) {
            val ratio = peaks[i] / max(1e-9f, peaks[i - 1])
            if (ratio < steepest) {
                steepest = ratio
                knee = i
            }
        }
        return knee
    }

    /**
     * Chop at detected hits.
     *
     * [maxSlices] caps the result at the strongest hits — one bank is 16 pads,
     * and a busy break will happily yield forty.
     */
    fun byTransients(
        snip: Snip,
        maxSlices: Int = 16,
        config: Transients.Config = Transients.Config(),
        cleanup: CleanupConfig? = CleanupConfig(),
        snapToZeroCrossing: Boolean = true,
    ): List<Slice> {
        require(maxSlices >= 0) { "maxSlices must not be negative: $maxSlices" }
        if (maxSlices == 0 || snip.frameCount == 0) return emptyList()

        val onsets = Transients.detectStrongest(snip, maxSlices, config)
        if (onsets.isEmpty()) return emptyList()

        val boundaries = onsets.map { onset ->
            val frame = if (snapToZeroCrossing) {
                Transients.zeroCrossingBefore(snip, onset.frame)
            } else {
                onset.frame
            }
            frame to onset
        }

        return boundaries.mapIndexed { i, (start, onset) ->
            val end = if (i + 1 < boundaries.size) boundaries[i + 1].first else snip.frameCount
            slice(snip, start, end, onset, cleanup)
        }.filter { it.snip.frameCount > 0 }
    }

    /** A ghost starts where the hit's envelope has fallen this far below its peak. */
    const val GHOST_DROP_DB = -18f

    /** A ghost shorter than this is a scrap between two close hits, not a space. */
    const val GHOST_MIN_SEC = 0.06f

    /**
     * GHOST CHOP: the spaces between [hits] — the material every chop
     * throws away. For each hit, its peak in the first 50 ms; then the
     * first 5 ms window whose RMS has fallen [GHOST_DROP_DB] below that
     * peak is where the ghost starts, and it runs to the next hit's cut
     * (the last to the end of [snip]). A ghost shorter than
     * [GHOST_MIN_SEC] is skipped — a tight, gated break has none, and
     * that is the honest answer rather than sixteen pads of scraps. Cut
     * with [cleanup] like any slice.
     */
    fun ghosts(
        snip: Snip,
        hits: List<Slice>,
        cleanup: CleanupConfig? = SLICE_CLEANUP,
    ): List<Ghost> {
        if (hits.isEmpty() || snip.frameCount == 0) return emptyList()
        val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
        val rate = snip.sampleRate
        val peakWindow = (0.05f * rate).toInt().coerceAtLeast(1)
        val rmsWindow = (0.005f * rate).toInt().coerceAtLeast(1)
        val minFrames = (GHOST_MIN_SEC * rate).toInt()
        val drop = Math.pow(10.0, GHOST_DROP_DB / 20.0).toFloat()
        val out = ArrayList<Ghost>()
        for ((i, hit) in hits.withIndex()) {
            val start = hit.sourceFrame
            val next = if (i + 1 < hits.size) hits[i + 1].sourceFrame else snip.frameCount
            var peak = 0f
            for (f in start until min(next, start + peakWindow)) peak = max(peak, kotlin.math.abs(mono.samples[f]))
            if (peak <= 0f) continue
            var ghostStart = -1
            var f = start + peakWindow
            while (f + rmsWindow <= next) {
                var acc = 0.0
                for (k in f until f + rmsWindow) acc += mono.samples[k].toDouble() * mono.samples[k]
                if (Math.sqrt(acc / rmsWindow) < peak * drop) {
                    ghostStart = f
                    break
                }
                f += rmsWindow
            }
            if (ghostStart < 0 || next - ghostStart < minFrames) continue
            out += Ghost(i, slice(snip, ghostStart, next, onset = null, cleanup = cleanup))
        }
        return out
    }

    /**
     * Divide into [parts] equal pieces.
     *
     * The last piece absorbs the remainder, so the pieces reassemble into the
     * original rather than dropping a few frames off the end.
     */
    fun intoEqualParts(
        snip: Snip,
        parts: Int,
        cleanup: CleanupConfig? = null,
    ): List<Slice> {
        require(parts > 0) { "parts must be positive: $parts" }
        if (snip.frameCount == 0) return emptyList()

        val each = snip.frameCount / parts
        if (each == 0) return emptyList()

        return (0 until parts).map { i ->
            val start = i * each
            val end = if (i == parts - 1) snip.frameCount else start + each
            slice(snip, start, end, onset = null, cleanup = cleanup)
        }
    }

    /**
     * Extract frames `[start, end)` as its own snip.
     *
     * Cleanup defaults to *not* trimming: a chop boundary is already where the
     * user or the detector wanted it, and trimming would move it.
     */
    fun slice(
        snip: Snip,
        start: Int,
        end: Int,
        onset: Onset? = null,
        cleanup: CleanupConfig? = null,
    ): Slice {
        val from = max(0, start)
        val to = min(snip.frameCount, end)
        if (to <= from) return Slice(Snip(FloatArray(0), snip.channels, snip.sampleRate), from, onset)

        val out = FloatArray((to - from) * snip.channels)
        System.arraycopy(snip.samples, from * snip.channels, out, 0, out.size)

        var piece = Snip(out, snip.channels, snip.sampleRate)
        if (cleanup != null) piece = Cleanup.process(piece, cleanup)

        return Slice(piece, from, onset)
    }

    /**
     * Cleanup defaults for chopped pieces.
     *
     * Trimming is off — the cut points are the intent. Fades stay on, because a
     * slice boundary lands mid-waveform by definition and will click without
     * them.
     */
    val SLICE_CLEANUP = CleanupConfig(
        trimSilence = false,
        normalize = false,
        removeDcOffset = true,
        fadeInMs = 1f,
        fadeOutMs = 3f,
    )
}
