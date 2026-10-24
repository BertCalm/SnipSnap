package com.snipsnap.shell

/** The thumb strip supplies touch velocity; MIDI keeps the controller's own velocity. */
object StrikeStrip {
    const val DEFAULT_STRENGTH = 0.5f
    private const val FIRST_MIDI_VELOCITY = 1f / 127f

    /** A note-on snapshot. Moving the strip never changes a voice already playing. */
    data class Hit(val velocity: Float, val gainVelocity: Float)

    /** A fader rises toward hard. The pad-position gesture retains its existing opposite axis. */
    fun strengthAt(y: Float, height: Float): Float =
        if (!y.isFinite() || !height.isFinite() || height <= 0f) DEFAULT_STRENGTH
        else (1f - y / height).coerceIn(0f, 1f)

    fun hit(touchVelocity: Float, enabled: Boolean, strength: Float, volume: Boolean): Hit {
        if (!enabled) return Hit(touchVelocity, touchVelocity)
        val amount = if (strength.isFinite()) strength.coerceIn(0f, 1f) else DEFAULT_STRENGTH
        // Give evenly spaced layers evenly spaced thumb travel. Reusing
        // the touch gain floor here would squeeze the first of four layers
        // into only the bottom 7% of the strip. MIDI 1 also avoids the
        // velocity-zero gap in SOFT HITS' first layer.
        val velocity = FIRST_MIDI_VELOCITY + (1f - FIRST_MIDI_VELOCITY) * amount
        val gainVelocity = if (volume) PadHit.SOFTEST + (1f - PadHit.SOFTEST) * amount else 1f
        return Hit(velocity, gainVelocity)
    }
}
