package com.snipsnap.audio

/**
 * What a decode will accept, and how much of it it will keep.
 *
 * The app's decode reads a channel count and a sample rate out of a file
 * a stranger shared, and then does arithmetic with them. Both halves of
 * that sentence are a door, and this is where they live so they can be
 * tested: the decoder itself only exists on a device, but the rules
 * about what it is handed do not have to.
 *
 * The arithmetic matters more than it looks. `MAX_FRAMES * channels` is
 * an `Int` product, and a file can simply claim a hundred channels — at
 * seventy-five the product already overflows negative, and doubling a
 * negative sample count into a byte count overflows it *back to a large
 * positive*, so a length that should have been refused turns into a
 * gigabyte-and-a-half allocation instead. [room] is computed in `Long`
 * and clamped for that reason, and there is a case for it.
 */
object DecodeLimits {

    /** Ten minutes at 48 k: more than any import keeps, little enough to hold. */
    const val MAX_FRAMES = 48_000 * 60 * 10

    /**
     * The door, in words, checked the moment a format is known rather
     * than after a file has been decoded — a ten-minute album side
     * should not be read to the end only to be refused for a channel
     * count that was on the first line of its header.
     */
    fun checkFormat(channels: Int, sampleRate: Int) {
        require(channels in 1..2) { "a $channels-channel file - the deck takes mono or stereo" }
        require(sampleRate > 0) { "that file claims $sampleRate Hz - there is no reading it at that rate" }
    }

    /**
     * Samples still worth keeping, given [kept] already held. Never
     * negative and never overflows, whatever [channels] claims: it is
     * total by construction, so a caller that has not run [checkFormat]
     * yet still cannot turn it into a bad length.
     */
    fun room(channels: Int, kept: Int): Int {
        val cap = MAX_FRAMES.toLong() * channels.toLong()
        // Clamped to the largest cap any accepted format can have - ten
        // minutes of stereo - and not to Int.MAX_VALUE. A caller turns
        // this into a byte count by doubling or quadrupling it, and a
        // clamp that only kept the result non-negative would still hand
        // back a number that overflows on the way to bytes. Nothing above
        // two channels is accepted anyway, so this ceiling never binds on
        // a format that got through [checkFormat].
        val ceiling = MAX_FRAMES.toLong() * 2L
        return (cap - kept.toLong()).coerceIn(0L, ceiling).toInt()
    }
}
