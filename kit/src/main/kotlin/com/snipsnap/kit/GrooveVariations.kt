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

    /**
     * The standard four, in slot order: Captured / Tight / Half / Sparse —
     * or, when a [swingPercent] is asked for, the Tight slot becomes the
     * swung clip (the container's four-slot budget is the budget).
     */
    fun standard(base: Mpc3Clip, swingPercent: Int? = null): List<Mpc3Clip> = listOf(
        base,
        swingPercent?.let { swing(base, it) }
            ?: quantize(base, Mpc3Clip.PULSES_PER_16TH, suffix = "Tight"),
        halfTime(base),
        sparse(base),
    )

    /**
     * Swing, the way the hardware applies it: quantize to the 16th grid,
     * then push every even ("and") 16th late by `(percent−50)/50` of a
     * 16th. 50 is straight, 66 is triplet feel, and the classic MPC panel
     * runs 50–75. Velocities are untouched — swing is time, not dynamics.
     */
    fun swing(clip: Mpc3Clip, percent: Int): Mpc3Clip {
        require(percent in 50..75) { "swing wants 50..75 percent, got $percent" }
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val push = (percent - 50L) * s16 / 50L
        val limit = clip.bars * Mpc3Clip.PULSES_PER_BAR
        val tight = quantize(clip, s16, suffix = "Swing $percent")
        return tight.copy(
            notes = tight.notes.map { n ->
                if ((n.timePulses / s16) % 2 == 1L) {
                    n.copy(timePulses = (n.timePulses + push).coerceAtMost(limit - 1))
                } else {
                    n
                }
            },
        )
    }

    /**
     * Seeded jitter on note starts, bounded to ±half a 16th at full
     * [amount] — loose, not sloppy. Same seed, same feel; velocities
     * untouched here too.
     */
    fun humanize(clip: Mpc3Clip, amount: Float, seed: Int): Mpc3Clip {
        require(amount in 0f..1f) { "amount wants 0..1, got $amount" }
        val rnd = kotlin.random.Random(seed)
        val span = Mpc3Clip.PULSES_PER_16TH / 2f * amount
        val limit = clip.bars * Mpc3Clip.PULSES_PER_BAR
        return clip.copy(
            name = variantName(clip.name, "Loose"),
            notes = clip.notes.map { n ->
                val jitter = ((rnd.nextFloat() * 2f - 1f) * span).toLong()
                n.copy(timePulses = (n.timePulses + jitter).coerceIn(0L, limit - 1))
            },
        )
    }

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
