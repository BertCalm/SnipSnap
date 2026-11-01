package com.snipsnap.app

/** One serialized owner, no stream. Float-array bridge for the ORBIT worker and finite bounce. */
internal object NativeAquariumBus {
    init { System.loadLibrary("snipsnap_surface") }
    external fun create(rate: Int): Long
    external fun destroy(handle: Long)
    external fun configure(handle: Long, parameters: FloatArray, seed: Long, model: Int,
                          bpm: Double, gain: Float, enabled: Boolean, reset: Boolean): Boolean
    external fun process(handle: Long, samples: FloatArray, bpm: Double, reset: Boolean): Boolean
    external fun faulted(handle: Long): Boolean
    external fun limitedFrames(handle: Long): Long
}
