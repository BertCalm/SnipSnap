package com.snipsnap.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The share sheet's doorstep (F3.1): what another app handed us, held
 * until the composition picks it up.
 *
 * [MainActivity] offers every intent it receives — the one it was
 * created with and every `onNewIntent` after (the activity is
 * `singleTask`, so a share while the app is open lands in the running
 * instance instead of stacking a second one). [App] collects [pending],
 * imports the file, and [consume]s it, so a recomposition or a rotation
 * never imports the same share twice.
 *
 * Only `SEND` with a stream and `VIEW` with a data URI count; the launcher's
 * `MAIN` and everything else offer nothing.
 */
object ShareInbox {

    private val _pending = MutableStateFlow<Uri?>(null)

    /** The shared file waiting to be imported, or null. */
    val pending: StateFlow<Uri?> = _pending.asStateFlow()

    /** Reads a share out of [intent], if it is one; returns whether it was. */
    fun offer(intent: Intent?): Boolean {
        val uri = uriOf(intent) ?: return false
        _pending.value = uri
        return true
    }

    /** The import is done (or refused): nothing waits any more. */
    fun consume() {
        _pending.value = null
    }

    private fun uriOf(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> streamOf(intent)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
