package com.snipsnap.shell

import kotlin.math.roundToInt

/**
 * What the audio stream will admit about itself, as a line short enough
 * for a status bar - the bench's one number.
 *
 * "Does it feel tight?" is the milestone's exit test and it is a
 * judgement, but a judgement is much easier to make beside a figure, and
 * a figure is the only part of the answer that survives being written
 * down. The device reports its own round-trip latency (Oboe's
 * `calculateLatencyMillis`); this turns it into words, including the
 * words for "the device would not say".
 */
object StreamFacts {

    /** The readout for a stream that is not open at all. */
    const val NO_STREAM = "NO STREAM"

    /**
     * How often a screen asks the device again, in nanoseconds. Once a
     * second is faster than the number moves, and asking costs a
     * timestamp read - cheap, but not free enough to want a frame.
     *
     * It lives here rather than beside each readout so PLAY and SURFACE
     * cannot come to disagree about how fresh their figures are.
     */
    const val POLL_NANOS = 1_000_000_000L

    /**
     * [millis] as the phone reports it, or null when it will not say.
     *
     * A zero or negative reading counts as no answer rather than as a
     * miraculous one: real round-trip latency is never zero, so a zero
     * means the device left the field unfilled. [shared] appends the word
     * either way - which path the stream took is worth knowing even when
     * the number is missing, because the shared path is a mixer stage
     * slower by construction.
     */
    fun latency(millis: Double?, shared: Boolean = false): String {
        val known = millis != null && millis.isFinite() && millis > 0.0
        val head = if (known) "${millis!!.roundToInt()} MS" else "— MS"
        return if (shared) "$head SHARED" else head
    }

    /**
     * PLAY's whole status line: the voice count beside the figure, or
     * [NO_STREAM] when [up] is false. A stream that is not open has no
     * voices and no latency, and a count standing at nought reads like a
     * quiet engine rather than an absent one - so the words for that case
     * are the same wherever the line is painted, the fullscreen grid
     * included.
     */
    fun playStatus(up: Boolean, voices: Int, maxVoices: Int, latency: String): String =
        if (up) "VOICES $voices/$maxVoices · $latency" else NO_STREAM
}
