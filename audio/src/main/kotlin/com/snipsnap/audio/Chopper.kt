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
