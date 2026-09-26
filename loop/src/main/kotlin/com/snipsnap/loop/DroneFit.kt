package com.snipsnap.loop

import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * How many intervals a drone spans, and how far off its note it plays.
 *
 * A drone loops seamlessly only if every oscillator completes whole cycles
 * in the loop, and its sub-octave saw forces the note onto multiples of
 * 2/L Hz, L the loop in seconds. So a drone is nudged off its note by up
 * to 1/L Hz, and a longer loop nudges it less. This picks the shortest
 * span, in whole intervals, that keeps the nudge inside [MAX_NUDGE_CENTS]
 * (docs/superpowers/specs/2026-09-25-resin-drone-design.md, "The one
 * trade-off").
 *
 * Pitch arithmetic only, on purpose: the grid has to pick a span and
 * re-slice a track on a tempo change without knowing what a synth is. The
 * renderer does the same rounding as [snappedHz], so the note the grid
 * reads out is the note that plays.
 */
object DroneFit {

    /** Intervals a drone may span. The last is [Session.MAX_CHAIN]: a drone is its track's whole chain. */
    val SPANS = listOf(1, 2, 4, 8)

    /** The tuning promise (spec, Decided 1). */
    const val MAX_NUDGE_CENTS = 3.0

    /** Hz of a MIDI note, equal temperament, A4 = 440. */
    fun hz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /**
     * The note a drone of [loopFrames] at [sampleRate] actually plays: the
     * nearest pitch whose sub-octave completes a whole number of cycles in
     * the loop, at least one.
     */
    fun snappedHz(rootHz: Double, loopFrames: Long, sampleRate: Int): Double {
        require(rootHz > 0.0 && loopFrames > 0 && sampleRate > 0) {
            "a drone needs a note, a loop and a rate: $rootHz Hz, $loopFrames frames, $sampleRate Hz"
        }
        val seconds = loopFrames.toDouble() / sampleRate
        val m = Math.round(rootHz / 2 * seconds).coerceAtLeast(1)
        return 2.0 * m / seconds
    }

    fun nudgeCents(rootHz: Double, loopFrames: Long, sampleRate: Int): Double =
        1200.0 * ln(snappedHz(rootHz, loopFrames, sampleRate) / rootHz) / ln(2.0)

    /** The nudge a drone on [rootMidi] spanning [span] intervals plays with in [session]. */
    fun nudgeCents(rootMidi: Int, span: Int, session: Session): Double =
        nudgeCents(hz(rootMidi), span.toLong() * session.intervalFrames, session.sampleRate)

    /**
     * The smallest span whose *actual* nudge is within [MAX_NUDGE_CENTS]
     * (not the worst case: a lucky note needs fewer slices and a cheaper
     * render). When even the longest misses, the longest: it is the
     * closest, and the readout shows the nudge.
     */
    fun spanFor(rootMidi: Int, session: Session): Int =
        SPANS.firstOrNull { abs(nudgeCents(rootMidi, it, session)) <= MAX_NUDGE_CENTS } ?: SPANS.last()

    /** [DroneBlock]s of one drone, spanning [span] intervals: the whole chain of its track. */
    fun slices(recipe: JsonValue, rootMidi: Int, span: Int): List<Block> =
        (0 until span).map { DroneBlock(recipe, rootMidi, slice = it, of = span) }

    /**
     * [session] with every drone track re-sliced to the span its tempo now
     * needs, so the tuning promise holds at every BPM (spec, Decided). A
     * drone track is one whose chain is all [DroneBlock]s of one recipe and
     * root; any other track comes back as the same instance, and so does
     * the session when nothing changed.
     */
    fun refit(session: Session): Session {
        var changed = false
        val tracks = session.tracks.map { track ->
            val drone = droneOf(track) ?: return@map track
            val span = spanFor(drone.rootMidi, session)
            if (span == drone.of && track.chain.size == span) return@map track
            changed = true
            track.copy(chain = slices(drone.recipe, drone.rootMidi, span))
        }
        return if (changed) session.copy(tracks = tracks) else session
    }

    /** The drone a track holds, or null when the track is not one whole drone. */
    fun droneOf(track: Track): DroneBlock? {
        val first = track.chain.first() as? DroneBlock ?: return null
        val same = track.chain.all { it is DroneBlock && it.recipe == first.recipe && it.rootMidi == first.rootMidi }
        return if (same) first else null
    }
}
