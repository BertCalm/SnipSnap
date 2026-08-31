package com.snipsnap.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeTheme
import com.snipsnap.app.theme.rememberDeskBrush
import com.snipsnap.app.theme.windowFrame
import com.snipsnap.app.ui.AppScreen
import com.snipsnap.app.ui.ChopScreen
import com.snipsnap.app.ui.ExportScreen
import com.snipsnap.app.ui.ExportSession
import com.snipsnap.app.ui.GrooveScreen
import com.snipsnap.app.ui.HelpScreen
import com.snipsnap.app.ui.KitScreen
import com.snipsnap.app.ui.KitsScreen
import com.snipsnap.app.ui.MenuRow
import com.snipsnap.app.ui.PadSheetScreen
import com.snipsnap.app.ui.PropertiesScreen
import com.snipsnap.app.ui.StatusBar
import com.snipsnap.app.ui.StubScreen
import com.snipsnap.app.ui.TapeScreen
import com.snipsnap.app.ui.TitleBar
import com.snipsnap.app.ui.ToastOverlay
import com.snipsnap.app.ui.longestSampleFile
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Personality
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.StarterKits
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val PREFS = "tapeos"
private const val PREF_SCHEME = "scheme"
private const val PREF_PERSONALITY = "personality"

/**
 * TAPE's COMMIT sets this; CHOP reads it. [sourceFile] is the WAV TAPE was
 * actually scrubbing (the open kit's longest sample at the moment of
 * commit — [range]'s frames only mean something against that specific
 * file). Recorded here in `App`, not `TapeScreen`, because the user can
 * switch to a different kit before ever opening CHOP, and `open` alone
 * wouldn't tell CHOP which file the commit was cut from.
 */
data class TapeCommit(val sourceFile: File, val range: IntRange)

/**
 * The whole M0 app: the SNIPSNAP.EXE window on its desk, the menu row,
 * the status bar, and the three real screens. State is plain Compose
 * state — the screens' actual machines live tested in `:shell`, and the
 * later milestones bind to them; M0's state is navigation and a shelf.
 */
@Composable
fun App(shelf: KitShelf) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var schemeId by remember {
        mutableStateOf(
            prefs.getString(PREF_SCHEME, null)
                ?.let { saved -> SchemeId.entries.firstOrNull { it.name == saved } }
                ?: Schemes.DEFAULT.id,
        )
    }
    var personality by remember {
        mutableStateOf(
            prefs.getString(PREF_PERSONALITY, null)
                ?.let { saved -> Personality.entries.firstOrNull { it.name == saved } }
                ?: Personality.FULL,
        )
    }
    val scheme = Schemes[schemeId]

    var screen by remember { mutableStateOf(AppScreen.KITS) }
    var kits by remember { mutableStateOf<List<KitShelf.Entry>>(emptyList()) }
    var open by remember { mutableStateOf<KitShelf.Entry?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var lastCommit by remember { mutableStateOf<TapeCommit?>(null) }
    // PAD SHEET: the long-press pad inspector, full-screen over KIT. Not an
    // AppScreen of its own — MenuRow's nine items are fixed and this isn't
    // one of them; it's KIT-scoped overlay state instead, cleared whenever
    // the user navigates to another tab (see `MenuRow`'s `onSelect` below).
    var padSheetSlot by remember { mutableStateOf<Int?>(null) }
    // X4.4 TEACH THE MACHINE: off by default. The consent row itself lives
    // in PropertiesScreen (⚙), which is out of scope for this pass — this
    // is the plain boolean the brief calls for, wired for CHOP to read,
    // with no UI to flip it yet. See the CHOP report for this deviation.
    var teachEnabled by remember { mutableStateOf(false) }
    // EXPORT: hoisted here, not local to ExportScreen's own composition —
    // its write runs on `scope` below (App's own, handed down as
    // `appScope`) so it survives a MenuRow tab switch; the session object
    // is what lets a remounted ExportScreen reconnect to a write already
    // in flight instead of racing a second one against the same kit's
    // files. See ExportSession's own KDoc.
    var exportSession by remember { mutableStateOf<ExportSession?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        kits = withContext(Dispatchers.IO) { shelf.list() }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(Motion.TOAST_DWELL_MS.toLong())
            toast = null
        }
    }

    fun fresh(starter: StarterKits.Starter) {
        if (busy != null) return
        busy = "DUBBING ${starter.displayName}…"
        scope.launch {
            val entry = try {
                withContext(Dispatchers.IO) { shelf.render(starter, Random.nextInt()) }
            } catch (e: Exception) {
                busy = null
                // Law 3: when it breaks, say exactly what happened.
                toast = "DUB FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            kits = withContext(Dispatchers.IO) { shelf.list() }
            busy = null
            toast = Copy.FRESH_TAPE
            open = entry
            screen = AppScreen.KIT
        }
    }

    TapeTheme(scheme, personality) {
        Box(
            Modifier
                .fillMaxSize()
                .background(rememberDeskBrush(scheme))
                // targetSdk 35 means Android 15 hands us the whole window and
                // reserves nothing for the status/navigation bars. The desk
                // brush is meant to bleed edge to edge, so it stays outside
                // this — only the window frame is pushed into the safe area,
                // or the titlebar renders under the clock.
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(Layout.OUTER_MARGIN.dp),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowFrame(scheme)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TitleBar()
                MenuRow(
                    current = screen,
                    onSelect = {
                        screen = it
                        // Leaving KIT for another tab must not leave the
                        // sheet armed to reopen on the same slot next time
                        // KIT comes back into view.
                        padSheetSlot = null
                    },
                )
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        AppScreen.KITS -> KitsScreen(
                            kits = kits,
                            busy = busy != null,
                            onOpen = { open = it; screen = AppScreen.KIT },
                            onFresh = ::fresh,
                        )
                        AppScreen.KIT -> {
                            val sheetSlot = padSheetSlot
                            val sheetEntry = open
                            if (sheetSlot != null && sheetEntry != null) {
                                PadSheetScreen(
                                    entry = sheetEntry,
                                    slot = sheetSlot,
                                    onSlotChange = { padSheetSlot = it },
                                    onBack = { padSheetSlot = null },
                                    onToast = { toast = it },
                                    onNavigateTape = {
                                        // TAPE has no notion of "open on this
                                        // pad's WAV" (it always scrubs the
                                        // open kit's longest sample, same as
                                        // ChopScreen's own fallback) — RE-TRIM
                                        // is honest about that gap: it opens
                                        // TAPE, not necessarily on this pad.
                                        padSheetSlot = null
                                        screen = AppScreen.TAPE
                                    },
                                    onKitUpdated = { updatedKit ->
                                        open = open?.copy(kit = updatedKit)
                                        scope.launch {
                                            kits = withContext(Dispatchers.IO) { shelf.list() }
                                        }
                                    },
                                    // App()'s own scope — the same one fresh()
                                    // launches into — outlives PadSheetScreen's
                                    // composition, so a pending debounced save
                                    // handed to it at teardown actually
                                    // completes instead of being cancelled by
                                    // the very navigation that triggers it.
                                    appScope = scope,
                                )
                            } else {
                                KitScreen(open, onLongPress = { slot -> padSheetSlot = slot })
                            }
                        }
                        AppScreen.TAPE -> TapeScreen(
                            entry = open,
                            onToast = { toast = it },
                            onCommit = { range ->
                                // The file is derived, not asked of TapeScreen (see
                                // `TapeCommit`) — `open` here is the same kit entry
                                // TapeScreen was scrubbing when COMMIT fired.
                                val source = open
                                if (source != null) {
                                    scope.launch {
                                        val file = withContext(Dispatchers.IO) { longestSampleFile(source) }
                                        if (file != null) lastCommit = TapeCommit(file, range)
                                    }
                                }
                            },
                        )
                        AppScreen.PROPERTIES -> PropertiesScreen(
                            currentScheme = schemeId,
                            onScheme = {
                                schemeId = it
                                prefs.edit().putString(PREF_SCHEME, it.name).apply()
                            },
                            personality = personality,
                            onPersonality = {
                                personality = it
                                prefs.edit().putString(PREF_PERSONALITY, it.name).apply()
                            },
                        )
                        AppScreen.CHOP -> ChopScreen(
                            entry = open,
                            lastCommit = lastCommit,
                            shelf = shelf,
                            teachEnabled = teachEnabled,
                            onToast = { toast = it },
                            onSentToGrid = { newEntry ->
                                open = newEntry
                                screen = AppScreen.KIT
                                scope.launch {
                                    kits = withContext(Dispatchers.IO) { shelf.list() }
                                }
                            },
                        )
                        AppScreen.EXPORT -> ExportScreen(
                            entry = open,
                            session = exportSession,
                            onSessionChange = { exportSession = it },
                            // App's own scope — the same one `fresh()`
                            // launches into and `PadSheetScreen`'s teardown
                            // save uses — so a dub survives a MenuRow tab
                            // switch instead of being cancelled by it.
                            appScope = scope,
                            onToast = { toast = it },
                        )
                        AppScreen.HELP -> HelpScreen()
                        AppScreen.GROOVE -> GrooveScreen(
                            entry = open,
                            // App's own scope — the same one PadSheetScreen's
                            // teardown save and ExportScreen's dub write use —
                            // so a pending debounced E save survives a MenuRow
                            // tab switch instead of being cancelled by it.
                            appScope = scope,
                            onToast = { toast = it },
                        )
                        else -> StubScreen(screen)
                    }
                }
                StatusBar(
                    screenLabel = screen.label,
                    shelfLabel = "KITS: ${kits.size}",
                    busy = busy,
                )
            }
            ToastOverlay(toast)
        }
    }
}
