package com.snipsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.snipsnap.app.nav.NavState
import com.snipsnap.app.nav.Screen
import com.snipsnap.app.nav.goTo
import com.snipsnap.app.nav.tickQuip
import com.snipsnap.app.store.KitEntry
import com.snipsnap.app.store.KitLibrary
import com.snipsnap.app.theme.LcdSurface
import com.snipsnap.app.theme.TapeOsTheme
import com.snipsnap.app.ui.KitsScreen
import com.snipsnap.app.ui.SnipSnapWindow
import com.snipsnap.app.ui.tapes
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Schemes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The one Android entry point. Everything it hosts is a binding over
 * `:shell` — this class holds the `Context` so nothing else has to.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = KitLibrary(File(filesDir, "kits"))

        setContent {
            var state by remember { mutableStateOf(NavState(Screen.KITS)) }
            var entries by remember { mutableStateOf(emptyList<KitEntry>()) }
            var openKit by remember { mutableStateOf<KitEntry?>(null) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { library.seedIfEmpty() }
                entries = withContext(Dispatchers.IO) { library.list() }.entries
            }

            LaunchedEffect(state.personality) {
                while (true) {
                    delay(Motion.QUIP_ROTATE_MS.toLong())
                    state = state.tickQuip()
                }
            }

            TapeOsTheme(Schemes.DEFAULT) {
                SnipSnapWindow(
                    state = state,
                    onMenu = { state = state.goTo(it) },
                    tapeCell = "TAPE 0:00",
                    countCell = tapes(entries.size),
                ) {
                    when (state.screen) {
                        Screen.KITS -> KitsScreen(
                            entries = entries,
                            onOpen = { openKit = it; state = state.goTo(Screen.KIT) },
                            // Task 6 Step 4g replaces this with the FRESH
                            // TAPE dialog; the button is inert until then.
                            onNew = { },
                        )
                        else -> LcdSurface(modifier = Modifier.fillMaxSize()) { /* placeholder */ }
                    }
                }
            }
        }
    }
}
