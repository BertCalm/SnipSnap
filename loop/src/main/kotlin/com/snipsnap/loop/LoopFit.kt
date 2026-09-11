package com.snipsnap.loop

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.roundToInt

/** What [BlockBaker.fitLoop] had to do to make a loop the length asked of it. */
enum class LoopFit {
    /** The loop was already exactly the target length. */
    AS_IS,
    /** Within tolerance and a little long: the end was cut, with a tail fade. */
    TRIMMED,
    /** Within tolerance and a little short: silence was added at the end. */
    PADDED,
    /** Out of tolerance: cut at the hits and the hits re-placed on the new grid. */
    SLICED,
}

/**
 * The fit in numbers, so a screen can say what happened to a snip rather
 * than leave the ear to guess: how long it was, how long the ring is, and
 * which of the four things the fit did about the difference.
 */
data class FitReport(
    val fit: LoopFit,
    val sourceFrames: Int,
    val targetFrames: Int,
    /** Slices placed, on a [LoopFit.SLICED] fit; 0 otherwise. */
    val slices: Int = 0,
) {
    init {
        require(targetFrames > 0) { "targetFrames must be positive: $targetFrames" }
    }

    /** Source length relative to the target: positive when the snip was too long. */
    val drift: Double get() = (sourceFrames - targetFrames).toDouble() / targetFrames

    /** The report in words for the ring panel, e.g. `SLICED AT 12 HITS · SQUEEZED 8%`. */
    val label: String
        get() {
            val pct = percent(drift)
            return when (fit) {
                LoopFit.AS_IS -> "FITS AS IS"
                LoopFit.TRIMMED -> "TRIMMED $pct OFF THE END"
                LoopFit.PADDED -> "PADDED $pct WITH SILENCE"
                LoopFit.SLICED -> "SLICED AT $slices HIT${if (slices == 1) "" else "S"} · ${if (drift > 0) "SQUEEZED" else "STRETCHED"} $pct"
            }
        }

    private fun percent(drift: Double): String {
        val whole = (abs(drift) * 100).roundToInt()
        return if (whole == 0) "<1%" else "$whole%"
    }
}

/** A loop fitted to its target, with the report of how. */
data class FittedLoop(val snip: Snip, val report: FitReport)
