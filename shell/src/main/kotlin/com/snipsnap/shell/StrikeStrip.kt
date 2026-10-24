package com.snipsnap.shell

/** The thumb strip supplies touch velocity; MIDI keeps the controller's own velocity. */
object StrikeStrip {
    const val DEFAULT_STRENGTH = 0.5f

    /** A note-on snapshot. Moving the strip never changes a voice already playing. */
    data class Hit(val velocity: Float, val gainVelocity: Float)

    /** A fader rises toward hard. The pad-position gesture retains its existing opposite axis. */
    fun strengthAt(y: Float, height: Float): Float =
        if (!y.isFinite() || !height.isFinite() || height <= 0f) DEFAULT_STRENGTH
        else (1f - y / height).coerceIn(0f, 1f)

    fun hit(touchVelocity: Float, enabled: Boolean, strength: Float, volume: Boolean): Hit {
        if (!enabled) return Hit(touchVelocity, touchVelocity)
        val amount = if (strength.isFinite()) strength.coerceIn(0f, 1f) else DEFAULT_STRENGTH
        // The same floor as a pad touch reaches the softest layer at every
        // supported stack depth, without making a soft touch a silent note.
        val velocity = PadHit.SOFTEST + (1f - PadHit.SOFTEST) * amount
        return Hit(velocity, if (volume) velocity else 1f)
    }
}
