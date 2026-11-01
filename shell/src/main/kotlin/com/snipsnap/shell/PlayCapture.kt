package com.snipsnap.shell

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * A short memory of what was just played, so a good take can be kept after
 * the fact: [grab] turns the last few seconds of notes into a clip.
 *
 * Notes arrive as they are played (a touch or a keyboard, it makes no
 * difference here) with the time they happened, in nanoseconds on any one
 * clock. Only a short stretch is kept ([retainNanos]); older notes are
 * forgotten as new ones come in, so this never grows while the player
 * leaves it running.
 *
 * There is no tempo on KEYS to play against, so a grab is written against
 * [DEFAULT_BPM]: the notes keep their real timing relative to each other,
 * a DAW at that tempo plays them as they were played, and at another tempo
 * they scale. The clip starts at its first note, so the silence before the
 * player began is not carried along.
 *
 * Main thread only, like the screen that feeds it.
 */
class PlayCapture(private val retainNanos: Long = RETAIN_NANOS) {

    private class Played(val note: Int, val start: Long, var end: Long, val velocity: Float)

    private val done = ArrayDeque<Played>()
    private val held = LinkedHashMap<Int, Played>()

    /** [note] went down at [nanos]. A note already down is let go first: it was struck again. */
    fun noteOn(note: Int, velocity: Float, nanos: Long) {
        noteOff(note, nanos)
        held[note] = Played(note, nanos, nanos, velocity.coerceIn(0f, 1f))
        forgetBefore(nanos)
    }

    /** [note] came up at [nanos]; a note that was never down is ignored. */
    fun noteOff(note: Int, nanos: Long) {
        val played = held.remove(note) ?: return
        played.end = max(nanos, played.start)
        done.addLast(played)
        forgetBefore(nanos)
    }

    /** Every held note comes up at [nanos]: the keyboard went, or a panic. */
    fun releaseAll(nanos: Long) {
        for (note in held.keys.toList()) noteOff(note, nanos)
    }

    /** Nothing in the last [windowNanos] to grab at [nowNanos]. */
    fun isEmpty(nowNanos: Long, windowNanos: Long = WINDOW_NANOS): Boolean = grab(nowNanos, windowNanos = windowNanos) == null

    /**
     * The last [windowNanos] up to [nowNanos] as a clip at [bpm], or null when
     * nothing was played in it. A note held across the window's start begins
     * at it, and one still down ends at [nowNanos].
     */
    fun grab(nowNanos: Long, bpm: Float = DEFAULT_BPM, windowNanos: Long = WINDOW_NANOS): Mpc3Clip? {
        require(bpm > 0f) { "bpm must be positive: $bpm" }
        val from = nowNanos - windowNanos
        val inWindow = (done.asSequence() + held.values.asSequence().map { Played(it.note, it.start, nowNanos, it.velocity) })
            .filter { it.end > from || (it.end == it.start && it.start >= from) }
            .map { Played(it.note, max(it.start, from), it.end, it.velocity) }
            .sortedBy { it.start }
            .toList()
        if (inWindow.isEmpty()) return null
        val origin = inWindow.first().start
        val pulsesPerNano = bpm / 60.0 * Mpc3PPQ / 1e9
        fun pulses(nanos: Long) = ((nanos - origin) * pulsesPerNano).roundToLong()
        val notes = inWindow.take(MAX_NOTES).map {
            val at = pulses(it.start)
            Mpc3Note(it.note, at, it.velocity, max(1L, pulses(it.end) - at))
        }
        val last = notes.maxOf { it.timePulses }
        val bars = ceil((last + 1).toDouble() / Mpc3Clip.PULSES_PER_BAR).toInt().coerceIn(1, 64)
        return Mpc3Clip(NAME, bars, notes.filter { it.timePulses < bars * Mpc3Clip.PULSES_PER_BAR })
    }

    private fun forgetBefore(nanos: Long) {
        val cutoff = nanos - retainNanos
        while (done.isNotEmpty() && (done.first().end < cutoff || done.size > MAX_NOTES)) done.removeFirst()
    }

    companion object {
        const val NAME = "KEYS GRAB"

        /** MIDI's pulses per quarter note in a clip. */
        private const val Mpc3PPQ = 960.0

        /** The tempo a grab is written at, KEYS having none of its own. */
        const val DEFAULT_BPM = 120f

        /** What a grab takes: the last sixteen seconds, eight bars at the default tempo. */
        const val WINDOW_NANOS = 16_000_000_000L

        /** What is remembered: a little over a window, so a grab just after a pause still has its notes. */
        const val RETAIN_NANOS = 60_000_000_000L

        /** More notes than a hand plays in a minute, and a bound on the memory. */
        const val MAX_NOTES = 4096
    }
}
