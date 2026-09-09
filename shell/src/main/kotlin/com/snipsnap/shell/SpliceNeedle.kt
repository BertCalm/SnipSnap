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

    /** How close an onset has to be to win, in frames — [TapeDeckModel.SNAP_POINT_SEC]'s own window. */
    const val ONSET_SNAP_SEC = 0.12

    /** How far either side of the onset-snapped frame a zero crossing is searched, in frames. */
    const val ZERO_SPAN_FRAMES = 64

    /**
     * Where the needle lands from [frame]: the nearer of [onsetsA] and
     * [onsetsB] within [ONSET_SNAP_SEC] of [sampleRate] wins first; then
     * the nearest zero crossing in either [monoA] or [monoB] within
     * [ZERO_SPAN_FRAMES]; clamped to `[0, maxFrame]` throughout.
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
        val onsetSnapped = nearestOnset(clamped, onsetsA, onsetsB, ONSET_SNAP_SEC * sampleRate) ?: clamped
        return nearestZero(onsetSnapped, monoA, monoB, maxFrame).coerceIn(0, maxFrame)
    }

    private fun nearestOnset(frame: Int, onsetsA: IntArray, onsetsB: IntArray, maxDist: Double): Int? {
        var best: Int? = null
        var bestDist = Double.MAX_VALUE
        for (o in onsetsA) {
            val d = abs(o - frame).toDouble()
            if (d < bestDist) {
                bestDist = d
                best = o
            }
        }
        for (o in onsetsB) {
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

    private fun nearestZero(frame: Int, monoA: FloatArray, monoB: FloatArray, maxFrame: Int): Int {
        val from = (frame - ZERO_SPAN_FRAMES).coerceAtLeast(1)
        val to = (frame + ZERO_SPAN_FRAMES).coerceAtMost(maxFrame)
        var best = -1
        var bestDist = Int.MAX_VALUE
        for (i in from..to) {
            if (isZeroCrossing(monoA, i) || isZeroCrossing(monoB, i)) {
                val d = abs(i - frame)
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
        }
        return if (best >= 0) best else frame
    }
}
