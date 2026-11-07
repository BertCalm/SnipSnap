package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note

/**
 * Bar-level edits on a pattern, pure functions on [Mpc3Clip] beside
 * [GrooveEdit]: copy a bar, paste it over another, double the pattern or
 * halve it. Each returns the clip it was given, untouched, when it has
 * nothing to do (a clip that cannot double, a paste of the wrong length), so
 * a caller compares the result with what it passed in to know whether
 * anything happened.
 */
object GrooveBars {

    /** A clip holds up to this many bars ([Mpc3Clip]'s own limit). */
    const val MAX_BARS = 64

    /**
     * One bar's notes, with times relative to the start of the bar. Carries
     * the bar's length so a paste can refuse a bar from a different meter.
     */
    data class BarCopy(val pulsesPerBar: Long, val notes: List<Mpc3Note>)

    /** Every note of [bar] (0-based), all pads, shifted to start at 0. */
    fun copyBar(clip: Mpc3Clip, bar: Int): BarCopy {
        require(bar in 0 until clip.bars) { "bar out of range for a ${clip.bars}-bar clip: $bar" }
        val from = bar * clip.pulsesPerBar
        val until = from + clip.pulsesPerBar
        return BarCopy(
            clip.pulsesPerBar,
            clip.notes.filter { it.timePulses in from until until }.map { it.copy(timePulses = it.timePulses - from) },
        )
    }

    /** Whether [copy] fits [clip]'s bar: same length in pulses. */
    fun fits(clip: Mpc3Clip, copy: BarCopy): Boolean = copy.pulsesPerBar == clip.pulsesPerBar

    /**
     * [copy] over [bar]: the bar's own notes, on every pad, are replaced by
     * the copied ones. [clip] unchanged when the bar lengths differ.
     */
    fun pasteBar(clip: Mpc3Clip, bar: Int, copy: BarCopy): Mpc3Clip {
        require(bar in 0 until clip.bars) { "bar out of range for a ${clip.bars}-bar clip: $bar" }
        if (!fits(clip, copy)) return clip
        val from = bar * clip.pulsesPerBar
        val until = from + clip.pulsesPerBar
        val kept = clip.notes.filterNot { it.timePulses in from until until }
        val pasted = copy.notes.map { it.copy(timePulses = it.timePulses + from) }
        return clip.copy(notes = (kept + pasted).sortedBy { it.timePulses })
    }

    /** Whether [doubled] would do something: twice the bars fits in [maxBars]. */
    fun canDouble(clip: Mpc3Clip, maxBars: Int = MAX_BARS): Boolean = clip.bars * 2 <= maxBars.coerceAtMost(MAX_BARS)

    /**
     * Twice the length, the whole pattern played twice: the notes repeat one
     * pattern-length later. [clip] unchanged when twice the bars would pass [maxBars].
     */
    fun doubled(clip: Mpc3Clip, maxBars: Int = MAX_BARS): Mpc3Clip {
        if (!canDouble(clip, maxBars)) return clip
        val shift = clip.lengthPulses
        return clip.copy(
            bars = clip.bars * 2,
            notes = (clip.notes + clip.notes.map { it.copy(timePulses = it.timePulses + shift) }).sortedBy { it.timePulses },
        )
    }

    /** Whether [halved] would do something: an even number of bars, at least two. */
    fun canHalve(clip: Mpc3Clip): Boolean = clip.bars >= 2 && clip.bars % 2 == 0

    /**
     * Half the length: the first half stays and the second half's notes are
     * dropped (a note's length is cut at the new end). [clip] unchanged for
     * an odd number of bars or a single one.
     */
    fun halved(clip: Mpc3Clip): Mpc3Clip {
        if (!canHalve(clip)) return clip
        val bars = clip.bars / 2
        val limit = bars * clip.pulsesPerBar
        return clip.copy(
            bars = bars,
            notes = clip.notes.filter { it.timePulses < limit }
                .map { it.copy(lengthPulses = it.lengthPulses.coerceAtMost(limit - it.timePulses)) },
        )
    }
}

/**
 * A bounded stack of states to go back to: the step editor's undo. Pushing
 * past [limit] forgets the oldest. Plain and pure so the editor's state
 * class owns no logic a JVM test cannot reach.
 */
class StepHistory<T>(val limit: Int = DEFAULT_LIMIT) {
    private val items = ArrayDeque<T>()

    init {
        require(limit >= 1) { "history limit must be positive: $limit" }
    }

    val size: Int get() = items.size

    fun push(item: T) {
        items.addLast(item)
        while (items.size > limit) items.removeFirst()
    }

    /** The most recent state, removed; null when there is none. */
    fun pop(): T? = items.removeLastOrNull()

    fun clear() = items.clear()

    companion object {
        /** How many step edits can be undone. */
        const val DEFAULT_LIMIT = 32
    }
}
