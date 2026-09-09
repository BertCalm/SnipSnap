package com.snipsnap.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.snipsnap.shell.SnipStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wires process-wide seams before any component runs.
 *
 * [MicSessionService.commitSnip] must be assigned here, not in
 * [MainActivity]: a process the OS kills and restarts to deliver a SNIP
 * intent (the service running without the activity ever having been
 * created) still goes through [Application.onCreate] first, but would never
 * reach `MainActivity.onCreate` at all. Wiring it there would leave the
 * seam holding its no-op default on exactly the restart path SNIP most
 * needs to survive.
 *
 * Also tracks [appForeground] — a started-Activity count via
 * [Application.ActivityLifecycleCallbacks], not `lifecycle-process` (not
 * a dependency here). [BubbleOverlay] hides itself while this is true:
 * "SnipSnap itself is foreground" means any of this app's own Activities
 * ([MainActivity], [LoopActivity]) is started, not just the main one, so
 * counting beats a single-Activity check.
 */
class SnipSnapApplication : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        MicSessionService.commitSnip = { samples, sampleRate ->
            SnipStore.commit(samples, sampleRate, filesDir, System.currentTimeMillis())
        }
        // The shared audio-focus owner needs an application Context before
        // any voice's first acquire() - see AudioFocus's own KDoc.
        AudioFocus.install(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        _appForeground.value = startedActivities > 0
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        _appForeground.value = startedActivities > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        private var startedActivities = 0
        private val _appForeground = MutableStateFlow(false)

        /** True while any of this app's own Activities is started. */
        val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()
    }
}
