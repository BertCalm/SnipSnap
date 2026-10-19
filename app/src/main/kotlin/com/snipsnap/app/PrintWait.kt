package com.snipsnap.app

/**
 * Waits for a print the engine has been asked to stop to finish, polling
 * [stopping] - the engine's own printState, which takes the engine's lock
 * only for a moment. Used by [SurfaceEngine.stopPrint] and
 * [PadEngine.stopPrint] so the wait for the audio callback's last write
 * happens outside their lock, rather than holding up the main thread's
 * per-frame calls on it. The same bound as the native bridge's own wait
 * (jni.cpp's `stopPrinting`): a stream that has stopped calling back gives
 * up after half a second rather than waiting for ever.
 */
internal fun awaitPrintStopped(stopping: () -> Boolean) {
    var polls = 0
    while (polls++ < PRINT_STOP_POLLS && stopping()) Thread.sleep(PRINT_STOP_POLL_MILLIS)
}

private const val PRINT_STOP_POLLS = 100
private const val PRINT_STOP_POLL_MILLIS = 5L
