package com.snipsnap.app

/**
 * The raw JNI surface of the pads' voice in `libsnipsnap_surface.so`
 * (`app/src/main/cpp/PadEngine.*`). Handles are opaque; [PadEngine] is
 * the Kotlin object that owns one. Every call here runs off the audio
 * thread; the native side moves commands and endings through lock-free
 * rings, a bank through a pointer handshake, never a lock.
 */
object NativePads {
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
    external fun beginBank(handle: Long)
    external fun addSample(handle: Long, interleaved: FloatArray, channels: Int, rate: Int): Int
    external fun commitBank(handle: Long)
    external fun noteOn(
        handle: Long, voiceId: Int, sample: Int,
        startFrame: Long, endFrame: Long, gainL: Float, gainR: Float, pitch: Double,
    ): Boolean
    external fun stopVoice(handle: Long, voiceId: Int, fadeMs: Float)
    external fun allOff(handle: Long, fadeMs: Float)
    external fun drainEnded(handle: Long): IntArray
}
