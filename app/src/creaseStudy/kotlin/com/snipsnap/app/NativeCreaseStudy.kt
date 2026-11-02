package com.snipsnap.app

/** Lifecycle calls run on one worker. Gesture calls are lock-free and never close a stream. */
internal object NativeCreaseStudy {
    init { System.loadLibrary("snipsnap_surface") }
    external fun create(layout: IntArray, pcm: ShortArray): Long
    external fun destroy(handle: Long)
    external fun start(handle: Long): Boolean
    external fun stop(handle: Long)
    external fun press(handle: Long, unfold: Boolean): Long
    external fun release(handle: Long, token: Long)
    external fun panic(handle: Long)
    external fun failed(handle: Long): Boolean
}
