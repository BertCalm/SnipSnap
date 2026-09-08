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
}
