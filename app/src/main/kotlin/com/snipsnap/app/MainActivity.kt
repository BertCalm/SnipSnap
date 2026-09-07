package com.snipsnap.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * A file shared or opened into the app (F3.1): the manifest's SEND and
     * VIEW filters land here, whether the app was cold (the launching
     * intent) or already up (singleTask, so [onNewIntent]). [App] collects
     * this, clears it, and pulls the audio out.
     */
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The shelf lives in app-private files; the export wizard (M5)
        // is what eventually walks kits out to the card via SAF.
        val shelf = KitShelf(File(filesDir, "Kits"))
        incoming.value = sharedUri(intent)
        setContent {
            App(shelf, incoming)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUri(intent)?.let { incoming.value = it }
    }

    private fun sharedUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
}
