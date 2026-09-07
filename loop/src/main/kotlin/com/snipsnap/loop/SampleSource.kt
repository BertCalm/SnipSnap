package com.snipsnap.loop

import com.snipsnap.audio.Snip

/**
 * Where the baker gets its audio.
 *
 * An interface rather than a File argument so baking can be tested without a
 * directory of WAVs, and so the app can cache decoded samples however it likes
 * without the engine knowing.
 *
 * Both methods return null for "not found". A missing sample bakes to silence
 * rather than throwing: one deleted file should not stop the grid.
 */
interface SampleSource {
    /** A loop block's audio, by bare filename. */
    fun loop(sampleFile: String): Snip?

    /** A pattern step's audio: one pad of a named kit, slot 1-based. */
    fun pad(kit: String, slot: Int): Snip?
}
