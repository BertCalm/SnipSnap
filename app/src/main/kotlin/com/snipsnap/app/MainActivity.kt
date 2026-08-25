package com.snipsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.snipsnap.app.audio.PadPlayer
import com.snipsnap.app.nav.NavState
import com.snipsnap.app.nav.Screen
import com.snipsnap.app.nav.goTo
import com.snipsnap.app.nav.tickQuip
import com.snipsnap.app.store.FileSettings
import com.snipsnap.app.store.KitEntry
import com.snipsnap.app.store.KitLibrary
import com.snipsnap.app.theme.LcdSurface
import com.snipsnap.app.theme.TapeOsTheme
import com.snipsnap.app.ui.KitScreen
import com.snipsnap.app.ui.KitsScreen
import com.snipsnap.app.ui.NewTapeDialog
import com.snipsnap.app.ui.PropsScreen
import com.snipsnap.app.ui.SnipSnapWindow
import com.snipsnap.app.ui.StarterMenu
import com.snipsnap.app.ui.ToastBanner
import com.snipsnap.app.ui.ToastMessage
import com.snipsnap.app.ui.newTapeToast
import com.snipsnap.app.ui.starterKitName
import com.snipsnap.app.ui.tapes
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.StarterKits
import java.io.File
import kotlin.random.Random
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
            var starterMenu by remember { mutableStateOf(false) }
            var toast by remember { mutableStateOf<ToastMessage?>(null) }
            var nextToastId by remember { mutableStateOf(0) }
            var renderingKit by remember { mutableStateOf<String?>(null) }

            // Renders a starter into a fresh, auto-named kit folder and
            // lands on the grid. Shared by the first-run auto-open and
            // every later NEW KIT tap, since both are "pick a starter, get
            // a playable kit" with no other difference.
            fun renderStarter(starter: StarterKits.Starter) {
                val name = starterKitName(starter, entries.map { it.name })
                val seed = if (starter.seeded) Random.nextInt() else 0
                renderingKit = name
                scope.launch {
                    // Dispatchers.Default, not IO: rendering a kit's worth
                    // of pads is compute, and IO's pool is sized for
                    // threads parked on blocking calls. Measured at ~24s on
                    // an emulator against 39ms on a warm desktop JVM — cold
                    // ART interpreting tight float loops — so this is a
                    // real wait the UI must own, not a blip to hide.
                    val made = runCatching {
                        withContext(Dispatchers.Default) { library.createFromStarter(starter, name, seed) }
                    }
                    // Reset unconditionally, before branching on the
                    // result — a throw from createFromStarter must not
                    // leave the shelf stuck showing "MAKING X" forever.
                    renderingKit = null
                    entries = withContext(Dispatchers.IO) { library.list().entries }
                    made.fold(
                        onSuccess = { model ->
                            openKit = entries.firstOrNull { it.dir == model.kitDir }
                            if (openKit != null) state = state.goTo(Screen.KIT)
                        },
                        onFailure = {
                            newTapeToast(created = false, name = name, personality = state.personality)?.let {
                                nextToastId += 1
                                toast = ToastMessage(nextToastId, it)
                            }
                        },
                    )
                }
            }

            LaunchedEffect(Unit) {
                // Listing is cheap — it only looks for folders holding a
                // kit.json — so ask first, and open the starter menu only
                // if the shelf is bare. Nothing auto-seeds any more: the
                // wait becomes chosen, not imposed.
                entries = withContext(Dispatchers.IO) { library.list().entries }
                if (entries.isEmpty()) {
                    starterMenu = true
                }
            }

            LaunchedEffect(state.personality) {
                while (true) {
                    delay(Motion.QUIP_ROTATE_MS.toLong())
                    state = state.tickQuip()
                }
            }

            // Keyed on the toast's own id, not the message text: two
            // consecutive identical toasts (e.g. CREATE_FAILED twice) are
            // different ids, so the second one restarts its own dwell timer
            // instead of being a no-op assignment that a text-keyed effect
            // would silently swallow.
            LaunchedEffect(toast?.id) {
                if (toast != null) {
                    delay(Motion.TOAST_DWELL_MS.toLong())
                    toast = null
                }
            }

            val scheme = Schemes[schemeId]
            TapeOsTheme(scheme) {
                // The desk (SnipSnapWindow.kt) deliberately bleeds the
                // scheme's gradient behind the system bars, so the system
                // bar icons have to track that desk's own luma per scheme —
                // the seam where "the desk bleeds behind the bars" (Task 3)
                // meets "the scheme is user-switchable" (Task 6).
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = Scheme.luma(scheme.desk1) > 128
                        isAppearanceLightNavigationBars = Scheme.luma(scheme.desk2) > 128
                    }
                }

                // System back returns to the shelf from anywhere else;
                // from the shelf itself, back keeps its default (exit).
                // Does not dismiss NewTapeDialog — the dialog isn't part
                // of Screen, so this handler can't see it; the dialog's own
                // scrim tap and CANCEL button remain the way out of it.
                BackHandler(enabled = state.screen != Screen.KITS) {
                    state = state.goTo(Screen.KITS)
                }

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
                            Screen.KITS, Screen.KIT -> {
                                // KIT with nothing open falls back to the
                                // same shelf as KITS — one call site for
                                // both, rather than the shelf duplicated
                                // verbatim across two branches.
                                val entry = if (state.screen == Screen.KIT) openKit else null
                                if (entry == null) {
                                    KitsScreen(
                                        entries = entries,
                                        renderingKit = renderingKit,
                                        onOpen = { openKit = it; state = state.goTo(Screen.KIT) },
                                        onNew = { starterMenu = true },
                                    )
                                } else {
                                    val model = remember(entry.dir) { library.open(entry) }
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

                        toast?.let { message -> ToastBanner(message.text) }
                    }
                }

                if (starterMenu) {
                    StarterMenu(
                        onPick = { starter ->
                            starterMenu = false
                            renderStarter(starter)
                        },
                        onBlank = {
                            starterMenu = false
                            newTape = true
                        },
                        onDismiss = { starterMenu = false },
                    )
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
                                newTapeToast(made.isSuccess, name, state.personality)?.let {
                                    nextToastId += 1
                                    toast = ToastMessage(nextToastId, it)
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
