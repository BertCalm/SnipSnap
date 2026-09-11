package com.snipsnap.shell

import kotlin.math.log10

/**
 * STACK THE TAKES: a pad's real prior takes as its velocity zones —
 * recordings the mic actually heard, in place of the softened copies
 * GHOSTS renders. The audio door is `KitBuilderModel.stackTakes`; this
 * object holds the two pieces of arithmetic both the model and the
 * picker screen need to agree on, and the one rule the screen states
 * without acting on.
 *
 * Shown, not fixed: takes differ in level and length, and STACK shows
 * that rather than normalizing it away. A soft-zone take that peaks over
 * the live one is flagged in words ([overLiveDb]) and left exactly as
 * loud as it is — an auto-gain here would be a guessed default.
 */
object StackTakes {

    /** Three soft zones under LIVE: the MPC 2 has four `<Layer>` slots and LIVE takes one. */
    const val MAX_SOFT = 3

    /** Below this a "louder soft take" is rounding, not a warning. */
    const val OVER_LIVE_TOLERANCE_DB = 0.5f

    /** The soft zones' names, softest first — SOFT; SOFT, MID; SOFT, MID, HARD — with LIVE always the zone above them. */
    fun zoneNames(softCount: Int): List<String> {
        require(softCount in 1..MAX_SOFT) { "1..$MAX_SOFT soft zones, got $softCount" }
        return listOf("SOFT", "MID", "HARD").take(softCount)
    }

    /**
     * The velocity windows for [softCount] soft zones plus LIVE, softest
     * first and LIVE last — `addGhostLayers`' own arithmetic, shared so a
     * stacked pad and a ghosted pad split 1..127 the same way: the softest
     * zone starts at 1 (velocity 0 is note-off), the rest tile evenly.
     */
    fun windows(softCount: Int): List<IntRange> {
        require(softCount in 1..MAX_SOFT) { "1..$MAX_SOFT soft zones, got $softCount" }
        val zoneCount = softCount + 1
        return buildList {
            for (v in 0 until softCount) {
                add((if (v == 0) 1 else 128 * v / zoneCount)..(128 * (v + 1) / zoneCount - 1))
            }
            add((128 * softCount / zoneCount)..127)
        }
    }

    /** A peak as dBFS; silence is -∞, never a made-up floor. */
    fun dbfs(peak: Float): Float = if (peak <= 0f) Float.NEGATIVE_INFINITY else 20f * log10(peak)

    /**
     * How many dB a soft take peaks OVER the live one, or null when it
     * doesn't (within [OVER_LIVE_TOLERANCE_DB]). The picker prints this
     * beside the row; nothing acts on it.
     */
    fun overLiveDb(softPeak: Float, livePeak: Float): Float? {
        val over = dbfs(softPeak) - dbfs(livePeak)
        return if (over.isFinite() && over > OVER_LIVE_TOLERANCE_DB) over else null
    }
}
