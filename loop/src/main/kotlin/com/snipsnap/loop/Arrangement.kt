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
     * What [track] bakes from at [interval]: its block, and for a pattern
     * the pattern blocks either side of it in the chain. The chain wraps,
     * so its last pattern rings into its first exactly as it rings into
     * any other, and a one-block chain is its own neighbour: a loop of one
     * pattern carries its own tails round to its downbeat.
     */
    fun neighbourhoodAt(track: Track, interval: Int): Neighbourhood {
        val n = track.chain.size
        val k = indexAt(n, interval)
        val block = track.chain[k]
        if (block !is PatternBlock) return Neighbourhood(block)
        return Neighbourhood(
            block,
            before = track.chain[(k + n - 1) % n] as? PatternBlock,
            after = track.chain[(k + 1) % n] as? PatternBlock,
        )
    }

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

/**
 * One interval of a track, as the baker sees it.
 *
 * A pattern's interval is not its own hits alone. A hit late in the
 * previous pattern rings on into it, a hit the next pattern nudges early
 * lands at its end, and a closed hat on its downbeat chokes the open hat
 * still ringing from the bar before. So a pattern bakes from [before] and
 * [after] as well as [block], and the cache keys on all three: the same
 * pattern between different neighbours is a different interval of audio.
 *
 * Only patterns carry: a loop, a drone or a silence has no hits to ring
 * across a boundary, and is baked from itself alone (both neighbours null).
 */
data class Neighbourhood(
    val block: Block,
    val before: PatternBlock? = null,
    val after: PatternBlock? = null,
)
