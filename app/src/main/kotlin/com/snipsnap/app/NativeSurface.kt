package com.snipsnap.app

/**
 * The raw JNI surface of `libsnipsnap_surface.so` (`app/src/main/cpp`).
 * Handles are opaque; [SurfaceEngine] is the Kotlin object that owns one.
 * Every call here comes from a thread that is never the audio thread -
 * [SurfaceEngine] serialises them; the native side moves the values to
 * the audio thread through a lock-free ring and atomics, never a lock.
 */
object NativeSurface {
    init {
        System.loadLibrary("snipsnap_surface")
    }

    external fun create(preferredSampleRate: Int): Long
    external fun destroy(handle: Long)
    external fun start(handle: Long): Boolean
    external fun stop(handle: Long)
    external fun sampleRate(handle: Long): Int
    external fun needsRestart(handle: Long): Boolean
    external fun isShared(handle: Long): Boolean
    external fun loadSample(handle: Long, mono: FloatArray, sourceRate: Int)
    external fun control(
        handle: Long, mode: Int,
        x: Float, y: Float, z: Float, tilt: Float,
        a: Float, b: Float, c: Float, d: Float,
        gate: Boolean,
    )
    external fun setCorner(handle: Long, index: Int, pitch: Float, cutoff: Float, resonance: Float, drive: Float)
    external fun armPrint(handle: Long, maxFrames: Int): Boolean
    external fun printState(handle: Long): Int
    external fun stopPrint(handle: Long): FloatArray?
}
