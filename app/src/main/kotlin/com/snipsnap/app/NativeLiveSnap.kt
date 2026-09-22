package com.snipsnap.app

/**
 * LIVE's own voice (`LiveSnapEngine.cpp`, PHOTO_SPECS.md §8): a wavetable
 * oscillator whose table cross-fades to whatever the camera last read
 * ([pushFrame]) and whose TUNE/BRIGHT/GRIT macros smooth toward each new
 * reading ([setMacros]) — the same `snipsnap_surface` library
 * [NativeSurface] and [NativePads] already load, one more engine in it.
 * A handle is a `Long`; the caller owning it (the LIVE screen's own
 * controller) is responsible for calling these in order and never from
 * two threads at once, the same discipline `SurfaceEngine.kt` documents
 * for [NativeSurface].
 */
object NativeLiveSnap {
    init { System.loadLibrary("snipsnap_surface") }

    external fun create(preferredSampleRate: Int): Long
    external fun destroy(handle: Long)
    external fun start(handle: Long): Boolean
    external fun stop(handle: Long)
    external fun sampleRate(handle: Long): Int
    external fun needsRestart(handle: Long): Boolean
    external fun isShared(handle: Long): Boolean
    external fun latencyMillis(handle: Long): Double

    /** [table] already -1..1, zero-mean, seam-blended — [com.snipsnap.synth.Snap.liveCycle]'s own shape, exactly [com.snipsnap.synth.Snap.TABLE_SIZE] points. */
    external fun pushFrame(handle: Long, table: FloatArray)

    /** Every macro 0..1, [com.snipsnap.synth.Snap.macrosFrom]'s own TUNE/BRIGHT/GRIT (DECAY has nothing to drive here — see LiveSnapEngine's own KDoc). */
    external fun setMacros(handle: Long, tune: Float, bright: Float, grit: Float)
}
