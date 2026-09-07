package com.snipsnap.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeTheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.rememberDeskBrush
import com.snipsnap.app.theme.tape
import com.snipsnap.app.theme.windowFrame
import com.snipsnap.app.ui.AppScreen
import com.snipsnap.app.ui.ChopScreen
import com.snipsnap.app.ui.ExportScreen
import com.snipsnap.app.ui.ExportSession
import com.snipsnap.app.ui.GrooveScreen
import com.snipsnap.app.ui.HelpScreen
import com.snipsnap.app.ui.KitScreen
import com.snipsnap.app.ui.KeysScreen
import com.snipsnap.app.ui.KitsScreen
import com.snipsnap.app.ui.MenuRow
import com.snipsnap.app.ui.PadSheetScreen
import com.snipsnap.app.ui.PlayScreen
import com.snipsnap.app.ui.PrimaryAction
import com.snipsnap.app.ui.PropertiesScreen
import com.snipsnap.app.ui.StatusBar
import com.snipsnap.app.ui.StubScreen
import com.snipsnap.app.ui.SynthScreen
import com.snipsnap.app.ui.TakesBinScreen
import com.snipsnap.app.ui.TapeScreen
import com.snipsnap.app.ui.TapeText
import com.snipsnap.app.ui.TitleBar
import com.snipsnap.app.ui.ToastOverlay
import com.snipsnap.app.ui.tapeClick
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Personality
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.StarterKits
import com.snipsnap.shell.TextureKits
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val PREFS = "tapeos"
private const val PREF_SCHEME = "scheme"
private const val PREF_PERSONALITY = "personality"
/** The Bubble's overlay-permission offer (Task 5) — asked once, ever. */
private const val PREF_OVERLAY_ASKED = "bubble_overlay_asked"

/**
 * TAPE's COMMIT sets this; CHOP reads it. [sourceFile] is the exact WAV
 * TAPE had loaded and was scrubbing when COMMIT fired — under
 * `TapeScreen.loadLongestTape`'s source-priority chain (newest snip →
 * last commit's own source → open kit's longest pad) that's frequently a
 * snip, not a pad sample. [range]'s frames only mean something against
 * that specific file. Recorded here in `App`, not `TapeScreen`, because
 * the user can switch to a different kit before ever opening CHOP, and
 * `open` alone wouldn't tell CHOP which file the commit was cut from.
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
    // KEYS: the instrument open on the grid, from the shelf's INSTRUMENTS list.
    var openInstrument by remember { mutableStateOf<KitShelf.InstrumentEntry?>(null) }
    var instruments by remember { mutableStateOf<List<KitShelf.InstrumentEntry>>(emptyList()) }
    LaunchedEffect(kits, screen) {
        if (screen == AppScreen.KITS) instruments = withContext(Dispatchers.IO) { shelf.instruments() }
    }
    // TAKES + BIN (X2.3): same shape as PAD SHEET above — reachable only
    // from the KIT action row, not one of MenuRow's fixed ten, so it's
    // KIT-scoped overlay state rather than its own AppScreen entry.
    var takesBinOpen by remember { mutableStateOf(false) }
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

    // The armed mic session (retroactive-snip Task 4): `armed` is the
    // service companion's own StateFlow, so this reflects the live session
    // regardless of which screen changed it (the ARM/EJECT buttons here,
    // or the notification's own SNIP action never touches this at all —
    // only ARM/EJECT flip it).
    val armed by MicSessionService.armed.collectAsState()
    var captureBlocked by remember { mutableStateOf(false) }

    // The Bubble's overlay permission (Task 5): SYSTEM_ALERT_WINDOW is
    // optional — it has no runtime-permission dialog, only a Settings
    // screen (ACTION_MANAGE_OVERLAY_PERMISSION). Whatever the user does
    // there (grant, deny, or just back out), this fires once and
    // PREF_OVERLAY_ASKED below makes sure it's never asked again. If the
    // grant lands while a session is already armed, re-running arm() is
    // how MicSessionService.handleArm's re-entry path (ring != null) picks
    // it up and attaches the bubble without touching the AudioRecord.
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context) && MicSessionService.armed.value) {
            MicSessionService.arm(context)
        }
    }

    // RequestMultiplePermissions rather than a single-permission launcher:
    // POST_NOTIFICATIONS only exists on 33+ and is requested alongside
    // RECORD_AUDIO in one system dialog rather than chained one-after-
    // another. The callback below branches on RECORD_AUDIO alone —
    // POST_NOTIFICATIONS only gates the notification's own SNIP shortcut,
    // not whether a session can arm at all, so a lone notification denial
    // must not trip CAPTURE_BLOCKED.
    val capturePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            // This callback runs with the Activity resumed (foreground) —
            // true whether the system dialog actually showed or the
            // permission was already granted and the contract
            // short-circuited straight here — so this satisfies
            // MicSessionService.arm's "call from a foreground context"
            // requirement without a separate checkSelfPermission branch.
            MicSessionService.arm(context)
            toast = Copy.SESSION_ARMED
            // The Bubble's one-time offer — arming must never wait on it,
            // so this runs after arm() is already underway, not before.
            if (!prefs.getBoolean(PREF_OVERLAY_ASKED, false) && !Settings.canDrawOverlays(context)) {
                prefs.edit().putBoolean(PREF_OVERLAY_ASKED, true).apply()
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        } else {
            captureBlocked = true
        }
    }

    fun requestArm() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        capturePermissionLauncher.launch(permissions.toTypedArray())
    }

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

    /**
     * SCULPT / STRETCH / FREEZE from the KIT screen: one pad of the open kit
     * becomes a texture kit of its own on the shelf, which then opens —
     * the same DUBBING… shape as [fresh], because it's the same thing: a
     * new tape rendered offline over a few seconds.
     */
    fun texture(slot: Int, spec: TextureKits.Spec) {
        val source = open ?: return
        if (busy != null) return
        busy = when (spec) {
            is TextureKits.Spec.Sculpt -> "SCULPTING…"
            is TextureKits.Spec.Stretch -> "STRETCHING…"
            is TextureKits.Spec.Freeze -> "FREEZING…"
        }
        scope.launch {
            val entry = try {
                withContext(Dispatchers.IO) { shelf.texture(source, slot, spec) }
            } catch (e: Exception) {
                busy = null
                // Law 3: when it breaks, say exactly what happened.
                toast = "${spec.verb} FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            kits = withContext(Dispatchers.IO) { shelf.list() }
            busy = null
            toast = when (spec) {
                is TextureKits.Spec.Sculpt -> Copy.SCULPTED
                is TextureKits.Spec.Stretch -> Copy.STRETCHED
                is TextureKits.Spec.Freeze -> Copy.FROZEN
            }
            open = entry
        }
    }

    /**
     * KEY, from the KIT screen's panel: the kit's key set or cleared,
     * metadata only - nothing retunes until IN KEY, or a tonal pad is
     * assigned with the key already set.
     */
    fun setKey(key: com.snipsnap.audio.KeySpec?) {
        val source = open ?: return
        if (busy != null) return
        scope.launch {
            val entry = try {
                withContext(Dispatchers.IO) { shelf.setKey(source, key) }
            } catch (e: Exception) {
                toast = "KEY FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            open = entry
            kits = withContext(Dispatchers.IO) { shelf.list() }
            toast = key?.let { Copy.keySet(com.snipsnap.shell.KeyPicker.label(it)) } ?: Copy.KEY_OFF
        }
    }

    /**
     * EVIL TWINS (W4.3): bank B lit with seeded re-treatments of bank A, a
     * new seed every press so the second press rerolls. The same DUBBING…
     * shape as a fresh tape: every twin renders offline over a few seconds.
     */
    fun evilTwins() {
        val source = open ?: return
        if (busy != null) return
        val hadTwins = source.kit.pads.any { it.slot > 16 }
        busy = "TWINNING…"
        scope.launch {
            val (entry, lit) = try {
                withContext(Dispatchers.IO) { shelf.evilTwins(source, Random.nextInt()) }
            } catch (e: Exception) {
                busy = null
                toast = "TWINS FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            open = entry
            kits = withContext(Dispatchers.IO) { shelf.list() }
            busy = null
            toast = if (hadTwins) Copy.TWINS_REROLLED else Copy.BANK_B_LIT
        }
    }

    /** IN KEY: every tonal pad into the kit's key by its tune fields; the toast counts what moved. */
    fun inKey() {
        val source = open ?: return
        if (busy != null) return
        val key = source.kit.key
        if (key == null) {
            toast = Copy.IN_KEY_NEEDS_KEY
            return
        }
        scope.launch {
            val (entry, moved) = try {
                withContext(Dispatchers.IO) { shelf.inKey(source) }
            } catch (e: Exception) {
                toast = "IN KEY FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            open = entry
            kits = withContext(Dispatchers.IO) { shelf.list() }
            toast = if (moved.isEmpty()) Copy.IN_KEY_NONE else Copy.inKey(moved.size, com.snipsnap.shell.KeyPicker.label(key))
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
                        takesBinOpen = false
                    },
                )
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        AppScreen.KITS -> KitsScreen(
                            kits = kits,
                            instruments = instruments,
                            busy = busy != null,
                            armed = armed,
                            onOpen = { open = it; screen = AppScreen.KIT },
                            onOpenInstrument = { openInstrument = it; screen = AppScreen.KEYS },
                            onFresh = ::fresh,
                            onArm = ::requestArm,
                            onSnip = {
                                MicSessionService.snip(context)
                                toast = Copy.SNIPPED
                            },
                            onEject = { MicSessionService.eject(context) },
                        )
                        AppScreen.KIT -> {
                            val sheetSlot = padSheetSlot
                            val sheetEntry = open
                            when {
                                sheetSlot != null && sheetEntry != null -> PadSheetScreen(
                                    entry = sheetEntry,
                                    slot = sheetSlot,
                                    onSlotChange = { padSheetSlot = it },
                                    onBack = { padSheetSlot = null },
                                    onToast = { toast = it },
                                    onNavigateTape = {
                                        // TAPE has no notion of "open on this
                                        // pad's WAV" — it loads whatever
                                        // `TapeScreen.loadLongestTape`'s
                                        // source-priority chain resolves
                                        // (newest snip → last commit's own
                                        // source → open kit's longest pad),
                                        // not necessarily this pad — RE-TRIM
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
                                takesBinOpen && sheetEntry != null -> TakesBinScreen(
                                    entry = sheetEntry,
                                    onBack = { takesBinOpen = false },
                                    onToast = { toast = it },
                                    onKitUpdated = { updatedKit ->
                                        // Same shape as PAD SHEET's own
                                        // onKitUpdated above: bumping
                                        // `open.kit`'s identity is what
                                        // makes KIT's PadPlayer reload
                                        // (`LaunchedEffect(entry.kit)`),
                                        // so a restored sample is heard,
                                        // not the stale cached one.
                                        open = open?.copy(kit = updatedKit)
                                        scope.launch {
                                            kits = withContext(Dispatchers.IO) { shelf.list() }
                                        }
                                    },
                                )
                                else -> KitScreen(
                                    open,
                                    busy = busy != null,
                                    onLongPress = { slot -> padSheetSlot = slot },
                                    onTakesBin = { takesBinOpen = true },
                                    onTexture = ::texture,
                                    onSetKey = ::setKey,
                                    onInKey = ::inKey,
                                    onTwins = ::evilTwins,
                                )
                            }
                        }
                        AppScreen.TAPE -> TapeScreen(
                            entry = open,
                            // TAPE's source-priority fallback below
                            // SnipStore.newest — the file COMMIT last cut
                            // from, so returning to TAPE after a trim
                            // resumes where the user left off rather than
                            // re-resolving the open kit's longest sample.
                            lastCommitSource = lastCommit?.sourceFile,
                            onToast = { toast = it },
                            // TapeScreen now hands back the exact file it was
                            // scrubbing (a snip, the prior commit's source, or
                            // the open kit's longest sample — whichever the
                            // source priority picked) alongside the range, so
                            // this no longer re-derives it via
                            // `longestSampleFile(open)`: under the new
                            // priority that call frequently returns the wrong
                            // file (a pad WAV) for a commit actually cut from
                            // a snip. Synchronous now — no IO re-read needed.
                            onCommit = { file, range -> lastCommit = TapeCommit(file, range) },
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
                        AppScreen.SYNTH -> SynthScreen(
                            entry = open,
                            onToast = { toast = it },
                            onKitUpdated = { updatedKit ->
                                // Same shape as PAD SHEET/CHOP's own
                                // onKitUpdated: bump `open.kit`'s identity so
                                // KIT's PadPlayer reloads the pad SEND TO PAD
                                // just replaced, not a stale cached sample.
                                open = open?.copy(kit = updatedKit)
                                scope.launch {
                                    kits = withContext(Dispatchers.IO) { shelf.list() }
                                }
                            },
                        )
                        AppScreen.PLAY -> PlayScreen(entry = open)
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
                        AppScreen.KEYS -> {
                            val inst = openInstrument
                            if (inst == null) {
                                screen = AppScreen.KITS
                            } else {
                                KeysScreen(
                                    sidecar = inst.sidecar,
                                    instrument = inst.instrument,
                                    onBack = { openInstrument = null; screen = AppScreen.KITS },
                                )
                            }
                        }
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
            if (captureBlocked) {
                CaptureBlockedDialog(onDismiss = { captureBlocked = false })
            }
        }
    }
}

/**
 * RECORD_AUDIO denied: the house modal shape (`KitsScreen`'s own
 * `StarterMenu` — scrim `Box` + `raisedBevel` `Column`, an inner
 * `tapeClick {}` swallowing taps so the scrim's dismiss doesn't fire
 * through) rather than a Material `AlertDialog`; TapeOS never uses
 * Material's own chrome (see `Chrome.kt`'s `TapeText` KDoc).
 */
@Composable
private fun CaptureBlockedDialog(onDismiss: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .tapeClick(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                .tapeClick { }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText(Copy.CAPTURE_BLOCKED, TapeType.lcdSmall, scheme.ink.tape, maxLines = 4)
            PrimaryAction(label = Copy.CAPTURE_BLOCKED_BUTTON, enabled = true, onClick = onDismiss)
        }
    }
}
