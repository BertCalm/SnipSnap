package com.snipsnap.loop

/**
 * Where mixed audio goes.
 *
 * The only seam between the engine and the outside world, and the reason
 * playback and bounce cannot drift apart: live output is an Android
 * implementation of this interface, and export is a WAV implementation of the
 * same one, fed by the same mixer over the same intervals.
 *
 * Implementations must accept interleaved stereo floats and must not throw
 * from [write] — a sink that fails should degrade to dropping audio, not take
 * the transport down with it.
 *
 * **The caller owns the array and reuses it.** Both the engine and the bouncer
 * hand the same buffer to [write] every interval, so an implementation must
 * consume it or copy it before returning. Retaining the reference gets you the
 * next interval's audio in place of this one, silently.
 */
interface AudioSink {
    val sampleRate: Int
    val channels: Int

    /** Consume one block of interleaved stereo samples. */
    fun write(block: FloatArray)

    fun close()
}
