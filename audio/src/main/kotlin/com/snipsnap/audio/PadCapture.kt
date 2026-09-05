package com.snipsnap.audio

/**
 * Turns a raw ring snapshot (mono, newest samples) into a clean one-shot of
 * the LAST hit — the "GRAB" gesture's DSP. Pure and deterministic.
 */
object PadCapture {
    /** Shorter than this after trimming ⇒ treat as silence / no real hit. */
    const val MIN_ONESHOT_FRAMES = 1_764   // 40ms @ 44.1k

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
        return if (cleaned.frameCount < MIN_ONESHOT_FRAMES) null else cleaned
    }
}
