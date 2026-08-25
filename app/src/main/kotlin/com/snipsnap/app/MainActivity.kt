package com.snipsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.audio.PadPlayer
import com.snipsnap.app.nav.NavState
import com.snipsnap.app.nav.Screen
import com.snipsnap.app.nav.goTo
import com.snipsnap.app.nav.tickQuip
import com.snipsnap.app.store.FileSettings
import com.snipsnap.app.store.KitEntry
import com.snipsnap.app.store.KitLibrary
import com.snipsnap.app.theme.LcdSurface
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.TapeOsTheme
import com.snipsnap.app.theme.toColor
import com.snipsnap.app.ui.KitScreen
import com.snipsnap.app.ui.KitsScreen
import com.snipsnap.app.ui.NewTapeDialog
import com.snipsnap.app.ui.PropsScreen
import com.snipsnap.app.ui.SnipSnapWindow
import com.snipsnap.app.ui.tapes
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Delight
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Schemes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one Android entry point. Everything it hosts is a binding over
 * `:shell` — this class holds the `Context` so nothing else has to.
 */
class MainActivity : ComponentActivity() {

    private lateinit var padSound: PadPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = KitLibrary(File(filesDir, "kits"))
        val settings = FileSettings(File(filesDir, "tape.properties"))
        padSound = PadPlayer()

        setContent {
            val scope = rememberCoroutineScope()
            var schemeId by remember { mutableStateOf(settings.schemeId) }
            var state by remember { mutableStateOf(NavState(Screen.KITS, settings.personality)) }
            var entries by remember { mutableStateOf(emptyList<KitEntry>()) }
            var openKit by remember { mutableStateOf<KitEntry?>(null) }
            var newTape by remember { mutableStateOf(false) }
            var toast by remember { mutableStateOf<String?>(null) }

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

            LaunchedEffect(toast) {
                if (toast != null) {
                    delay(Motion.TOAST_DWELL_MS.toLong())
                    toast = null
                }
            }

            TapeOsTheme(Schemes[schemeId]) {
                SnipSnapWindow(
                    state = state,
                    onMenu = { state = state.goTo(it) },
                    tapeCell = "TAPE 0:00",
                    countCell = tapes(entries.size),
                ) {
                    // A local Box, not a second fillMaxSize() sibling of
                    // SnipSnapWindow: this slot is already past the window's
                    // system-inset padding, so a toast anchored here sits
                    // just above the real status bar row instead of
                    // floating over the system bars outside the window.
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (state.screen) {
                            Screen.KITS -> KitsScreen(
                                entries = entries,
                                onOpen = { openKit = it; state = state.goTo(Screen.KIT) },
                                onNew = { newTape = true },
                            )
                            Screen.KIT -> {
                                val entry = openKit
                                if (entry == null) {
                                    KitsScreen(
                                        entries,
                                        onOpen = { openKit = it; state = state.goTo(Screen.KIT) },
                                        onNew = { newTape = true },
                                    )
                                } else {
                                    val model = remember(entry.dir) { KitBuilderModel.open(entry.dir) }
                                    KitScreen(model = model, sound = padSound, onHit = {})
                                }
                            }
                            Screen.PROPS -> PropsScreen(
                                current = schemeId,
                                personality = state.personality,
                                onScheme = { schemeId = it; settings.schemeId = it },
                                onPersonality = { state = state.copy(personality = it); settings.personality = it },
                            )
                            else -> LcdSurface(modifier = Modifier.fillMaxSize()) { /* placeholder */ }
                        }

                        toast?.let { message -> ToastBanner(message) }
                    }
                }

                if (newTape) {
                    NewTapeDialog(
                        existing = entries.map { it.name },
                        onDismiss = { newTape = false },
                        onConfirm = { name ->
                            newTape = false
                            scope.launch {
                                // create() can reject a name verifyKitName just
                                // accepted: verifyKitName compares kit *names*,
                                // create() validates the *folder*, and
                                // Names.sanitizeStem collapses underscore runs —
                                // so "A_B" passes the dialog and then collides on
                                // disk. It can also fail on plain IO. Either way
                                // an uncaught throw here kills the app from a
                                // button press, so the failure becomes a toast.
                                val made = runCatching {
                                    withContext(Dispatchers.IO) { library.create(name) }
                                }
                                entries = withContext(Dispatchers.IO) { library.list().entries }
                                if (Delight.toastsEnabled(state.personality)) {
                                    toast = made.fold(
                                        onSuccess = { Copy.kitNameResponse(name) ?: Copy.FRESH_TAPE },
                                        onFailure = { "COULDN'T MAKE THAT TAPE." },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        padSound.release()
        super.onDestroy()
    }
}

/**
 * A one-line status toast, sat just above the status bar — an "oil-rim
 * card" per the handoff (`design/HANDOFF.md`), dismissed on its own by the
 * [Motion.TOAST_DWELL_MS] timer in [MainActivity.onCreate].
 *
 * Anchored inside [SnipSnapWindow]'s content slot (not a full-screen
 * sibling of the window), so "above the status bar" is measured from the
 * window's own status bar row rather than from the physical screen edge —
 * the window already consumes system insets, this slot does not need to.
 */
@Composable
private fun ToastBanner(text: String) {
    val s = LocalScheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(s.gray.toColor())
                .border(2.dp, s.lcdInk.toColor(), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BasicText(
                text = text,
                style = TextStyle(
                    color = s.ink.toColor(),
                    fontFamily = TapeFonts.pixel,
                    fontSize = 9.sp,
                ),
            )
        }
    }
}
