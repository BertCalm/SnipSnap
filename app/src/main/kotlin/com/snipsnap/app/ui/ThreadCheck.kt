package com.snipsnap.app.ui

import android.os.Looper
import android.util.Log

/** EXPERIMENT (not for merge): log, with a stack, any composition that runs off the main thread. */
internal fun composedOffMainThread(where: String) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        Log.e("SnipSnapThreadCheck", "$where composed on ${Thread.currentThread().name}", Throwable("composition thread"))
    }
}
