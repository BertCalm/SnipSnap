package com.snipsnap.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A share that launched the app cold arrives with onCreate; one
        // that found it running arrives through onNewIntent below. Both
        // go to the same doorstep, and App does the importing.
        ShareInbox.offer(intent)
        // The shelf lives in app-private files; the export wizard (M5)
        // is what eventually walks kits out to the card via SAF.
        val shelf = KitShelf(File(filesDir, "Kits"))
        setContent {
            App(shelf)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareInbox.offer(intent)
    }
}
