package com.snipsnap.audio

/**
 * The dead-air detector for the INSIDE capture source.
 *
 * An app that opts out of playback capture (`ALLOW_CAPTURE_BY_NONE`)
 * doesn't refuse the session — the platform hands the recorder exact
 * digital zeros instead, and nothing in the API says so. The only honest
 * way to know is to watch the stream: silence that goes on for whole
 * seconds while the phone reports music playing is a block, not a pause
 * (`docs/ANDROID_CAPTURE.md`, "Where it breaks").
 *
 * This is the stream half of that test, built for the reader thread:
 * [feed] does one pass over the block and index arithmetic, no
 * allocation, no locks. It returns true once per [holdFrames] of
 * unbroken silence — a periodic tick, not a one-shot — so the caller can
 * ask the platform "is music playing?" on each tick and never has to
 * poll it per block. Any sample above [threshold] resets the run.
 *
 * "Silence" here means digital zero, or as near as makes no difference:
 * a blocked stream carries exactly 0.0f, while a real quiet passage
 * still has dither and room in it. The default [threshold] sits far
 * below anything a converter produces.
 */
class SilenceWatch(
    val holdFrames: Int,
    val threshold: Float = DEFAULT_THRESHOLD,
) {
    init {
        require(holdFrames > 0) { "holdFrames must be positive, was $holdFrames" }
        require(threshold >= 0f) { "threshold must not be negative, was $threshold" }
    }

    /** Frames of unbroken silence seen since the last sound or the last tick. */
    var silentFrames: Long = 0L
        private set

    /** True while the stream is inside a silent run (of any length). */
    val silent: Boolean
        get() = silentFrames > 0L

    /**
     * Consumes [count] samples of [block]. Returns true when the silent
     * run reaches [holdFrames]; the run's count then restarts at zero so
     * the next tick comes another [holdFrames] later if the silence
     * holds. Returns false on any block that carries sound, and the run
     * is over.
     */
    fun feed(block: FloatArray, count: Int): Boolean {
        // A branch, not an allocation: the message is only built on failure.
        require(count >= 0) { "count must not be negative, was $count" }
        val n = minOf(count, block.size)
        var i = 0
        while (i < n) {
            val x = block[i]
            if (x > threshold || x < -threshold || x != x) {
                silentFrames = 0L
                return false
            }
            i++
        }
        silentFrames += n
        if (silentFrames >= holdFrames) {
            silentFrames = 0L
            return true
        }
        return false
    }

    /** Forgets any run in progress — for a session that just (re)started. */
    fun reset() {
        silentFrames = 0L
    }

    companion object {
        /** Well under one 24-bit code (about 1.2e-7): dither is sound, a blocked stream is not. */
        const val DEFAULT_THRESHOLD = 1e-8f

        fun forSeconds(seconds: Double, sampleRate: Int): SilenceWatch {
            require(seconds > 0.0) { "seconds must be positive, was $seconds" }
            require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
            return SilenceWatch((seconds * sampleRate).toInt().coerceAtLeast(1))
        }
    }
}
