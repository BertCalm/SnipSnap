package com.snipsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The shelf lives in app-private files; the export wizard (M5)
        // is what eventually walks kits out to the card via SAF.
        val shelf = KitShelf(File(filesDir, "Kits"))
        setContent {
            App(shelf)
        }
    }
}
