package com.snipsnap.app

/**
 * The Kotlin owner of one native LIVE engine (PHOTO_SPECS.md §8): a
 * wavetable oscillator cross-fading to whatever camera frame [pushFrame]
 * last delivered, its TUNE/BRIGHT/GRIT macros smoothed toward each new
 * reading. See `app/src/main/cpp/LiveSnapEngine.h` for the threading
 * rules; this class keeps to the UI thread and never touches audio
 * memory — the exact shape [SurfaceEngine] already keeps for its own
 * native engine.
 *
 * Close it. A [LiveSnapVoice] holds a native stream; [close] is
 * idempotent and every other call after it is a no-op rather than a
 * crash.
 */
class LiveSnapVoice(preferredSampleRate: Int) {

    private var handle: Long = NativeLiveSnap.create(preferredSampleRate)
    private val open get() = handle != 0L

    @Synchronized
    fun start(): Boolean = open && NativeLiveSnap.start(handle)

    @Synchronized
    fun stop() {
        if (open) NativeLiveSnap.stop(handle)
    }

    /** The device refused an exclusive stream and the shared fallback is playing (a mixer stage more latency). */
    @Synchronized
    fun isShared(): Boolean = open && NativeLiveSnap.isShared(handle)

    /** A route change closed the stream; the caller reopens with [start]. */
    @Synchronized
    fun needsRestart(): Boolean = open && NativeLiveSnap.needsRestart(handle)

    /**
     * The stream's round-trip latency in ms, or null when there is none
     * or the device will not say. Cheap but not free, so poll about
     * once a second, not per frame — [SurfaceEngine.latencyMillis]'s own
     * caution.
     */
    @Synchronized
    fun latencyMillis(): Double? =
        if (open) NativeLiveSnap.latencyMillis(handle).takeIf { it > 0.0 } else null

    /**
     * One camera frame's line, [com.snipsnap.synth.Snap.liveCycle]'s own
     * shape (already -1..1, zero-mean, seam-blended) — call from the
     * analyzer's own background thread, never the audio thread; the
     * engine's own pointer handshake is what makes that safe.
     */
    @Synchronized
    fun pushFrame(table: FloatArray) {
        if (open) NativeLiveSnap.pushFrame(handle, table)
    }

    /** [com.snipsnap.synth.Snap.macrosFrom]'s own TUNE/BRIGHT/GRIT, every one 0..1. */
    @Synchronized
    fun setMacros(tune: Float, bright: Float, grit: Float) {
        if (open) NativeLiveSnap.setMacros(handle, tune, bright, grit)
    }

    @Synchronized
    fun close() {
        if (!open) return
        NativeLiveSnap.stop(handle)
        NativeLiveSnap.destroy(handle)
        handle = 0L
    }
}
