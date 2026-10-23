package com.snipsnap.app

/**
 * Raw, blocking offline bridge to the accepted native Aquarium render model.
 * Call on a render worker, never the audio or main thread. Source is interleaved
 * mono/stereo at its actual rate; output is interleaved stereo at the same rate.
 *
 * parameterValues is the complete 52-value canonical snapshot in metadata order.
 * Every call starts a fresh seeded clock at origin zero. Explicit gain and end
 * fade precede peak validation; no normalization, clipping or resampling occurs.
 * Argument/peak failures throw IllegalArgumentException, allocation failures
 * throw OutOfMemoryError, and processor failures throw IllegalStateException.
 * No input array is modified and a failed render returns no partial output.
 */
object NativeAquarium {
    init { System.loadLibrary("snipsnap_surface") }

    /** Includes source plus tail; queried from native rather than duplicated. */
    external fun maximumOutputFrames(): Int

    external fun renderOffline(
        samples: FloatArray,
        channels: Int,
        rate: Int,
        parameterValues: FloatArray,
        seedLong: Long,
        bpm: Double,
        tailFrames: Int,
        fadeFrames: Int,
        outputGain: Float,
        peakCeiling: Float,
        blockSize: Int,
    ): FloatArray
}
