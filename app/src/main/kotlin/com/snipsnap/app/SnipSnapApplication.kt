package com.snipsnap.app

import android.app.Application
import com.snipsnap.shell.SnipStore

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
 */
class SnipSnapApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        MicSessionService.commitSnip = { samples, sampleRate ->
            SnipStore.commit(samples, sampleRate, filesDir, System.currentTimeMillis())
        }
    }
}
