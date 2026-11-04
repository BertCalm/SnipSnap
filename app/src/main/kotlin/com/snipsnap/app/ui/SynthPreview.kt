package com.snipsnap.app.ui

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The finite SYNTH preview request. Its LaunchedEffect owns cancellation; only that live
 * request may publish audio, report a failure or clear its loading indicator. The renderer
 * stays interruptible off-main, including when it returns an already cached source.
 */
internal suspend fun <T> renderSynthPreview(
    debounceMillis: Long,
    shimmerDelayMillis: Long,
    onRendering: (Boolean) -> Unit,
    render: () -> T,
    publish: (T) -> Unit,
    onFailure: (Exception) -> Unit,
    label: String = "",
) = withContext(Dispatchers.Main.immediate) {
    // As in offMain, the explicit main dispatcher matters under Compose's test interceptor:
    // the delayed shimmer and completion callbacks must both write UI state on main.
    coroutineScope {
        val requestJob = coroutineContext[Job]
        delay(debounceMillis)
        val shimmer = launch {
            delay(shimmerDelayMillis)
            coroutineContext.ensureActive()
            onRendering(true)
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val rendered = offMainInterruptible(Dispatchers.Default, render)
            coroutineContext.ensureActive()
            // Device render time for the speed scan (docs/ENGINE_STANDARD_SCAN_2026_11.md,
            // "Render speed"): adb logcat -s SnipSnapRender. Only finished, still-wanted
            // renders are logged, so superseded knob turns do not skew the numbers.
            Log.i(RENDER_TIMING_TAG, "$label ${SystemClock.elapsedRealtime() - started} ms")
            publish(rendered)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A blocking renderer can throw an ordinary exception after its request was
            // superseded. That obsolete failure must not toast over the newer preview.
            coroutineContext.ensureActive()
            onFailure(failure)
        } finally {
            shimmer.cancel()
            // Do not write state from an obsolete/disposed request: the new request owns
            // its loading state, and disposal must not reapply the screen's final frame.
            if (requestJob?.isCancelled == false) onRendering(false)
        }
    }
}

/** Logcat tag for SYNTH preview render times; see [renderSynthPreview]. */
internal const val RENDER_TIMING_TAG = "SnipSnapRender"
