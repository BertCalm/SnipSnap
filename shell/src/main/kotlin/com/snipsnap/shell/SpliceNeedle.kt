package com.snipsnap.shell

import kotlin.math.abs

/**
 * TAPE SPLICE's needle snap: the same two-stage idea [TapeDeckModel.snapPoint]
 * already uses for a trim boundary — an onset first, then the nearest
 * zero crossing — but choosing across TWO takes at once, since the
 * splice needle's one frame sits over both stacked waveforms at the same
 * pixel position. Whichever take offers the nearer candidate wins; a tie
 * favors [onsetsA]/[monoA] (the head).
 */
object SpliceNeedle {

    /** How close an onset has to be to win, in seconds (converted to frames via the sample rate in [snap]) — [TapeDeckModel.SNAP_POINT_SEC]'s own window. */
    const val ONSET_SNAP_SEC = 0.12

    /** How far either side of the onset-snapped frame a zero crossing is searched, in frames. */
    const val ZERO_SPAN_FRAMES = 64

    /**
     * Where the needle lands from [frame]: the nearer of [onsetsA] and
     * [onsetsB] within [ONSET_SNAP_SEC] seconds (that is,
     * `ONSET_SNAP_SEC * sampleRate` frames) wins first; then the nearest
     * zero crossing in either [monoA] or [monoB] within [ZERO_SPAN_FRAMES]
     * frames; clamped to `[0, maxFrame]` throughout.
     */
    fun snap(
        frame: Int,
        maxFrame: Int,
        monoA: FloatArray,
        onsetsA: IntArray,
        monoB: FloatArray,
        onsetsB: IntArray,
        sampleRate: Int,
    ): Int {
        val clamped = frame.coerceIn(0, maxFrame)
        val onsetSnapped = nearestOnset(clamped, maxFrame, onsetsA, onsetsB, ONSET_SNAP_SEC * sampleRate) ?: clamped
        return nearestZero(onsetSnapped, monoA, monoB, maxFrame).coerceIn(0, maxFrame)
    }

    /**
     * An onset past [maxFrame] — the longer take's, beyond where the
     * shorter one runs out — is not a candidate at all: letting it win and
     * then clamping would park the needle on the edge, not on anything
     * real in either take.
     */
    private fun nearestOnset(frame: Int, maxFrame: Int, onsetsA: IntArray, onsetsB: IntArray, maxDist: Double): Int? {
        var best: Int? = null
        var bestDist = Double.MAX_VALUE
        for (o in onsetsA) {
            if (o !in 0..maxFrame) continue
            val d = abs(o - frame).toDouble()
            if (d < bestDist) {
                bestDist = d
                best = o
            }
        }
        for (o in onsetsB) {
            if (o !in 0..maxFrame) continue
            val d = abs(o - frame).toDouble()
            if (d < bestDist) {
                bestDist = d
                best = o
            }
        }
        return if (best != null && bestDist <= maxDist) best else null
    }

    /** A frame is a zero crossing in [mono] when the sample either side of it changes sign. */
    private fun isZeroCrossing(mono: FloatArray, i: Int): Boolean =
        i in 1 until mono.size && (mono[i - 1] < 0) != (mono[i] < 0)

    /**
     * The nearest zero crossing to [frame] in either take, within
     * [ZERO_SPAN_FRAMES]. Each take is searched on its own and the head's
     * candidate keeps a tie — the same order [nearestOnset] scans in, so
     * the class's "a tie favors the head" contract holds for both stages,
     * not just the first. Within one take an equidistant pair (one each
     * side of the needle) resolves to the earlier frame.
     */
    private fun nearestZero(frame: Int, monoA: FloatArray, monoB: FloatArray, maxFrame: Int): Int {
        val from = (frame - ZERO_SPAN_FRAMES).coerceAtLeast(1)
        val to = (frame + ZERO_SPAN_FRAMES).coerceAtMost(maxFrame)
        val a = nearestZeroIn(monoA, frame, from, to)
        val b = nearestZeroIn(monoB, frame, from, to)
        return when {
            a < 0 && b < 0 -> frame
            a < 0 -> b
            b < 0 -> a
            abs(b - frame) < abs(a - frame) -> b
            else -> a
        }
    }

    /** Nearest zero crossing to [frame] in one take over `[from, to]`, or -1 when there is none. */
    private fun nearestZeroIn(mono: FloatArray, frame: Int, from: Int, to: Int): Int {
        var best = -1
        var bestDist = Int.MAX_VALUE
        for (i in from..to) {
            if (!isZeroCrossing(mono, i)) continue
            val d = abs(i - frame)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }
}
