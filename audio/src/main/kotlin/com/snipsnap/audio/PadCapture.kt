package com.snipsnap.audio

/**
 * Turns a raw ring snapshot (mono, newest samples) into a clean one-shot of
 * the LAST hit — the "GRAB" gesture's DSP. Pure and deterministic.
 */
object PadCapture {
    /** Shorter than this after trimming ⇒ treat as silence / no real hit — a rate-honest ms, not a baked frame count. */
    const val MIN_ONESHOT_MS = 40

    /** Seconds cap for a single HOLD — far beyond any one-shot, half the ring. */
    const val MAX_HOLD_SECONDS = 30

    /**
     * Deprecated 44.1kHz-baked alias of [MIN_ONESHOT_MS] — kept only because
     * `PadCaptureTest` asserts a frame-count range against it directly. New
     * code should derive the gate from [MIN_ONESHOT_MS] at the sample rate
     * actually in play (`MIN_ONESHOT_MS * sampleRate / 1000`) instead of
     * comparing against this fixed-rate constant.
     */
    @Deprecated("frame-rate-specific; derive from MIN_ONESHOT_MS at the actual sample rate instead")
    const val MIN_ONESHOT_FRAMES = MIN_ONESHOT_MS * 44_100 / 1000

    /**
     * Deprecated 44.1kHz-baked alias of [MAX_HOLD_SECONDS] — kept because
     * `PadCaptureScreen`'s HOLD-length UI cap only ever runs at the app's
     * fixed [com.snipsnap.app] mic rate (44.1kHz today); computing it here
     * once, at the same constant that rate has always been, is simpler than
     * threading a sample rate into a screen that has none to offer.
     */
    @Deprecated("frame-rate-specific; compute against the live sample rate where one is available")
    const val MAX_HOLD_FRAMES = MAX_HOLD_SECONDS * 44_100

    /** [MIN_ONESHOT_MS] at [sampleRate] — the honest, rate-aware form of [MIN_ONESHOT_FRAMES]. */
    private fun minOneshotFrames(sampleRate: Int): Int = (MIN_ONESHOT_MS.toLong() * sampleRate / 1000).toInt()

    fun grabOneShot(raw: FloatArray, sampleRate: Int): Snip? {
        if (raw.isEmpty()) return null
        val full = Snip(raw, channels = 1, sampleRate = sampleRate)

        // Where does the last hit start? Fall back to the whole buffer's start
        // if no clear onset (Cleanup's silence trim still tightens it).
        val onsets = Transients.detect(full)
        val startRaw = onsets.lastOrNull()?.frame ?: 0
        val start = Transients.zeroCrossingBefore(full, startRaw).coerceIn(0, raw.size)

        val sliced = raw.copyOfRange(start, raw.size)
        if (sliced.isEmpty()) return null

        // Commit-time chain: DC-offset → trim silence → normalize → fades.
        val cleaned = Cleanup.process(Snip(sliced, channels = 1, sampleRate = sampleRate))
        return if (cleaned.frameCount < minOneshotFrames(sampleRate)) null else cleaned
    }

    /**
     * Cleans a hold-defined slice: the user's press/release chose the window, so
     * keep ALL of it (no onset hunt) — just the commit-time chain (DC → trim
     * silence → normalize → fades). Null if held in silence.
     */
    fun holdClip(raw: FloatArray, sampleRate: Int): Snip? {
        if (raw.isEmpty()) return null
        val cleaned = Cleanup.process(Snip(raw, channels = 1, sampleRate = sampleRate))
        return if (cleaned.frameCount < minOneshotFrames(sampleRate)) null else cleaned
    }
}
