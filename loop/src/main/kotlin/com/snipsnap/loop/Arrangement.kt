package com.snipsnap.loop

/**
 * Where every track is at a given moment.
 *
 * The whole transport is one integer. A track with n blocks plays index
 * `interval % n`, so there are no per-track cursors, nothing to keep in sync,
 * and nothing that can drift. Playback position is serialisable, resumable and
 * testable without a single sample of audio.
 */
object Arrangement {

    /** Which block of an n-long chain plays at [interval]. */
    fun indexAt(chainSize: Int, interval: Int): Int {
        require(chainSize > 0) { "chainSize must be positive: $chainSize" }
        require(interval >= 0) { "interval must not be negative: $interval" }
        return interval % chainSize
    }

    fun blockAt(track: Track, interval: Int): Block =
        track.chain[indexAt(track.chain.size, interval)]

    /**
     * How many intervals before the whole grid returns to its starting state.
     *
     * The least common multiple of the chain lengths — and the length of a
     * bounce. It grows fast: chains of 5, 7, 8 and 3 give 840 intervals, which
     * at 4 bars each is a 3,360-bar render. Show this number before committing
     * to one.
     */
    fun cycleIntervals(session: Session): Int =
        session.tracks.fold(1) { acc, t -> lcm(acc, t.chain.size) }

    private fun lcm(a: Int, b: Int): Int = a / gcd(a, b) * b

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
