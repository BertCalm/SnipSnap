package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note

/**
 * Mechanical variations of a captured groove — the four clips a chopped
 * kit ships (the container's own slot count): the break as captured,
 * snapped tight, at half time, and thinned to its strong hits. Load the
 * kit, flip patterns.
 *
 * Every transform is pure note-list arithmetic — provably derived, no
 * taste injected.
 */
object GrooveVariations {

    /** The standard four, in slot order: Captured / Tight / Half / Sparse. */
    fun standard(base: Mpc3Clip): List<Mpc3Clip> = listOf(
        base,
        quantize(base, Mpc3Clip.PULSES_PER_16TH, suffix = "Tight"),
        halfTime(base),
        sparse(base),
    )

    /** Note starts snapped to [grid] pulses; the push becomes the pocket. */
    fun quantize(clip: Mpc3Clip, grid: Long, suffix: String = "Tight"): Mpc3Clip {
        require(grid > 0) { "grid must be positive: $grid" }
        val limit = clip.bars * Mpc3Clip.PULSES_PER_BAR
        return clip.copy(
            name = variantName(clip.name, suffix),
            notes = clip.notes.map { n ->
                val snapped = ((n.timePulses + grid / 2) / grid * grid).coerceAtMost(limit - 1)
                n.copy(timePulses = snapped)
            },
        )
    }

    /** Times and lengths doubled — the same feel at half speed. */
    fun halfTime(clip: Mpc3Clip): Mpc3Clip {
        val bars = (clip.bars * 2).coerceAtMost(64)
        val limit = bars * Mpc3Clip.PULSES_PER_BAR
        return Mpc3Clip(
            name = variantName(clip.name, "Half"),
            bars = bars,
            notes = clip.notes.mapNotNull { n ->
                val time = n.timePulses * 2
                if (time >= limit) null
                else n.copy(timePulses = time, lengthPulses = (n.lengthPulses * 2).coerceAtMost(limit - time))
            },
        )
    }

    /**
     * Only the strong hits: notes at or above the median velocity. The
     * ghost notes drop away and the skeleton of the beat remains.
     */
    fun sparse(clip: Mpc3Clip): Mpc3Clip {
        val median = clip.notes.map { it.velocity }.sorted()[clip.notes.size / 2]
        val kept = clip.notes.filter { it.velocity >= median }
        return clip.copy(
            name = variantName(clip.name, "Sparse"),
            notes = kept.ifEmpty { clip.notes },
        )
    }

    /** "X Groove" → "X Tight"; anything else just gains the suffix. */
    private fun variantName(base: String, suffix: String): String =
        if (base.endsWith(" Groove")) base.removeSuffix(" Groove") + " " + suffix
        else "$base $suffix"
}
