package com.snipsnap.kit

import com.snipsnap.kit.GrooveEdit.Lane
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note

/**
 * BEAT step 3: what one step can be besides on or off. A step can be rolled
 * (ratcheted, several hits inside it), nudged (its hits moved off the grid
 * a little) and given a length. Pure functions on [Mpc3Clip] beside [GrooveEdit],
 * and like [GrooveEdit.setStepVelocity] none of them ever adds a hit to an
 * empty step: a tap does that.
 *
 * **No new field.** A rolled step is several ordinary notes of one pad at
 * fractional pulses; a nudge is a note's pulse; a length is its
 * `lengthPulses`. So the MPC export, SONG, the project's beat and the undo
 * stack carry all three without learning about them.
 *
 * **One unit.** Every hit a roll writes stays inside the step's own cell
 * ([GrooveEdit.cellOf]: pulses `step*240-59 .. step*240+180`), which is how
 * toggle, velocity and undo already treat "the step": a tap that turns the
 * step off removes the whole roll, a velocity level is set on every hit.
 *
 * **Where a roll lives.** A roll of N is N hits [rollSpacing] (a step over N)
 * apart across the whole step, the way an MPC's note repeat spreads them: x2 at
 * 0 and 120, x3 at 0, 80, 160, x4 at 0, 60, 120, 180. At 120 BPM that is 125,
 * 83 and 63 ms between hits. The cell leans late (a quarter step early, three
 * quarters late) so the last hit of a x4 at +180 is still this step's. Hits are
 * the step's velocity, all the same (no decay), and each is cut at the next hit
 * so they never overlap.
 *
 * **Nudge limits.** The cell reaches only 59 pulses before the line, so EARLY
 * moves 30 pulses on the first press and 29 on the second, then stops; LATE
 * goes up to 180 pulses. A x4 fills 0..180, the whole cell from its line to its
 * end, so it cannot move late at all and can go only 59 pulses early.
 *
 * **Nudge and swing.** A nudge is just the hits' position, so [GrooveEdit.bakeSwing]
 * (which puts every note back on its straight step before it swings) takes a
 * nudge away and folds a roll into one hit. That is the whole rule; the strip
 * says so, and [rollsIn] lets the swing toast say how many rolls it folded.
 * Nudging a step after the swing is baked moves it from where the swing put it.
 */
object StepShape {

    /** The most hits a roll holds. */
    const val MAX_ROLL = 4

    /** The gap between a roll of [n]'s hits, in pulses: the whole step over [n], so x2 is 120, x3 is 80, x4 is 60. */
    fun rollSpacing(n: Int): Long {
        require(n in 1..MAX_ROLL) { "a roll is 1..$MAX_ROLL hits: $n" }
        return GrooveEdit.STEP_PULSES / n
    }

    /** One press of EARLY or LATE: an eighth of a step. */
    val NUDGE_PULSES: Long = GrooveEdit.STEP_PULSES / 8

    /**
     * The lengths a step can be given. STEP is what a new step has. Written to
     * the note's `lengthPulses`: it is what the MPC's note reads; the app's own
     * one-shot pads play the whole sample whatever it is (see the PR).
     */
    enum class NoteLength(val pulses: Long) {
        SHORT(GrooveEdit.STEP_PULSES / 4),
        HALF(GrooveEdit.STEP_PULSES / 2),
        STEP(GrooveEdit.STEP_PULSES),
        TIE_2(GrooveEdit.STEP_PULSES * 2),
        TIE_4(GrooveEdit.STEP_PULSES * 4),
    }

    private fun notesOf(clip: Mpc3Clip, lane: Lane, step: Int, pads: List<Int>): List<Mpc3Note> {
        val note = GrooveEdit.noteFor(lane, pads)
        return clip.notes.filter { it.note == note && GrooveEdit.cellOf(it.timePulses) == step }
    }

    private fun requireStep(clip: Mpc3Clip, step: Int) {
        require(step in 0 until GrooveEdit.stepsInClip(clip)) { "step out of range for a ${clip.bars}-bar clip: $step" }
    }

    /** The pulses a hit of [step] can sit on: its cell, kept inside the clip (a note cannot go before the loop or past its end). */
    fun cellRange(clip: Mpc3Clip, step: Int): LongRange {
        val line = step * GrooveEdit.STEP_PULSES
        return maxOf(0L, line - GrooveEdit.CELL_EARLY + 1)..minOf(clip.lengthPulses - 1, line + GrooveEdit.CELL_LATE)
    }

    /** How many hits the step holds, at distinct pulses: 0 for an off step, 1 for a plain one, 2..[MAX_ROLL] for a roll. */
    fun rollOf(clip: Mpc3Clip, lane: Lane, step: Int, pads: List<Int> = GrooveEdit.DEFAULT_LANE_PADS): Int =
        notesOf(clip, lane, step, pads).map { it.timePulses }.distinct().size

    /**
     * The step rolled as [n] hits (1 clears the roll), replacing every note the
     * step has. The hits take the step's loudest velocity, start where its
     * earliest hit was (so a nudge survives, pulled back only as far as keeps
     * the last hit inside the cell) and are cut at the next hit; a plain hit
     * is a full step long again. [clip] unchanged when the step is off or is
     * already [n] hits.
     */
    fun setRoll(clip: Mpc3Clip, lane: Lane, step: Int, n: Int, pads: List<Int> = GrooveEdit.DEFAULT_LANE_PADS): Mpc3Clip {
        requireStep(clip, step)
        val spacing = rollSpacing(n)
        val cell = notesOf(clip, lane, step, pads)
        if (cell.isEmpty() || rollOf(clip, lane, step, pads) == n) return clip
        val range = cellRange(clip, step)
        val start = cell.minOf { it.timePulses }.coerceIn(range.first, maxOf(range.first, range.last - spacing * (n - 1)))
        val velocity = cell.maxOf { it.velocity }
        val note = GrooveEdit.noteFor(lane, pads)
        val length = if (n == 1) GrooveEdit.STEP_PULSES else spacing
        val hits = (0 until n).map { i ->
            val t = start + i * spacing
            Mpc3Note(note, t, velocity, length.coerceAtMost(clip.lengthPulses - t).coerceAtLeast(1))
        }
        val others = clip.notes.filterNot { it.note == note && GrooveEdit.cellOf(it.timePulses) == step }
        return clip.copy(notes = (others + hits).sortedBy { it.timePulses })
    }

    /** How far the step's earliest hit is from its step line, in pulses (negative is early); null for an off step. */
    fun offsetOf(clip: Mpc3Clip, lane: Lane, step: Int, pads: List<Int> = GrooveEdit.DEFAULT_LANE_PADS): Int? =
        notesOf(clip, lane, step, pads).minOfOrNull { it.timePulses }?.let { (it - step * GrooveEdit.STEP_PULSES).toInt() }

    /**
     * Every hit of the step moved by [delta] pulses together (a roll keeps its
     * shape), as far as the cell allows: the earliest stops at the cell's start
     * and the latest at its end, the first step cannot go before the loop.
     * [clip] unchanged when the step is off or already at the stop.
     */
    fun nudge(clip: Mpc3Clip, lane: Lane, step: Int, delta: Long, pads: List<Int> = GrooveEdit.DEFAULT_LANE_PADS): Mpc3Clip {
        requireStep(clip, step)
        val cell = notesOf(clip, lane, step, pads)
        if (cell.isEmpty()) return clip
        val range = cellRange(clip, step)
        val d = delta.coerceIn(range.first - cell.minOf { it.timePulses }, range.last - cell.maxOf { it.timePulses })
        if (d == 0L) return clip
        val note = GrooveEdit.noteFor(lane, pads)
        val moved = clip.notes.map {
            if (it.note == note && GrooveEdit.cellOf(it.timePulses) == step) {
                val t = it.timePulses + d
                it.copy(timePulses = t, lengthPulses = it.lengthPulses.coerceAtMost(clip.lengthPulses - t))
            } else it
        }
        return clip.copy(notes = moved.sortedBy { it.timePulses })
    }

    /** CENTRE: the step's earliest hit back on its step line (the straight position, swing and all taken out of this step). */
    fun centre(clip: Mpc3Clip, lane: Lane, step: Int, pads: List<Int> = GrooveEdit.DEFAULT_LANE_PADS): Mpc3Clip {
        val offset = offsetOf(clip, lane, step, pads) ?: return clip
        return nudge(clip, lane, step, -offset.toLong(), pads)
    }

    /**
     * The length the step reads as: the one of [NoteLength] nearest its hits'
     * longest. Null for an off step and for a roll, whose hits are as long as
     * the gap between them.
     */
    fun lengthOf(clip: Mpc3Clip, lane: Lane, step: Int, pads: List<Int> = GrooveEdit.DEFAULT_LANE_PADS): NoteLength? {
        val cell = notesOf(clip, lane, step, pads)
        if (cell.isEmpty() || cell.map { it.timePulses }.distinct().size > 1) return null
        val longest = cell.maxOf { it.lengthPulses }
        return NoteLength.entries.minBy { kotlin.math.abs(it.pulses - longest) }
    }

    /**
     * Sets every hit of the step to [length], cut where the clip ends and, in a
     * roll, at the next hit. Nothing else about it moves. [clip] unchanged when
     * the step is off or already that long.
     */
    fun setLength(clip: Mpc3Clip, lane: Lane, step: Int, length: NoteLength, pads: List<Int> = GrooveEdit.DEFAULT_LANE_PADS): Mpc3Clip {
        requireStep(clip, step)
        val cell = notesOf(clip, lane, step, pads)
        if (cell.isEmpty()) return clip
        val gap = cell.map { it.timePulses }.distinct().sorted().zipWithNext { a, b -> b - a }.minOrNull() ?: Long.MAX_VALUE
        val note = GrooveEdit.noteFor(lane, pads)
        val notes = clip.notes.map {
            if (it.note == note && GrooveEdit.cellOf(it.timePulses) == step) {
                it.copy(lengthPulses = length.pulses.coerceAtMost(gap).coerceAtMost(clip.lengthPulses - it.timePulses).coerceAtLeast(1))
            } else it
        }
        return if (notes == clip.notes) clip else clip.copy(notes = notes)
    }

    /** How many rolled steps (any pad) [clip] holds: what [GrooveEdit.bakeSwing] would fold into single hits. */
    fun rollsIn(clip: Mpc3Clip): Int =
        clip.notes.groupBy { it.note to GrooveEdit.cellOf(it.timePulses) }.values.count { g -> g.map { it.timePulses }.distinct().size > 1 }
}
