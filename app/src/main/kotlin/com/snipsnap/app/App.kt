package com.snipsnap.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
import com.snipsnap.app.ui.GrainFieldScreen
import com.snipsnap.app.ui.GrooveScreen
import com.snipsnap.app.ui.HelpScreen
import com.snipsnap.app.ui.KitScreen
import com.snipsnap.app.ui.KeysScreen
import com.snipsnap.app.ui.KitsScreen
import com.snipsnap.app.ui.MenuRow
import com.snipsnap.app.ui.MessageBox
import com.snipsnap.app.ui.PadCaptureScreen
import com.snipsnap.app.ui.PadSheetScreen
import com.snipsnap.app.ui.PlayScreen
import com.snipsnap.app.ui.PrimaryAction
import com.snipsnap.app.ui.PropertiesScreen
import com.snipsnap.app.ui.SnipsScreen
import com.snipsnap.app.ui.StatusBar
import com.snipsnap.app.ui.StubScreen
import com.snipsnap.app.ui.SurfaceScreen
import com.snipsnap.app.ui.SynthScreen
import com.snipsnap.app.ui.TakesBinScreen
import com.snipsnap.app.ui.TapeScreen
import com.snipsnap.app.ui.TapeText
import com.snipsnap.app.ui.TitleBar
import com.snipsnap.app.ui.ToastOverlay
import com.snipsnap.app.ui.tapeClick
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.InstantKit
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.LandingNote
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Personality
import com.snipsnap.shell.ReadGroove
import com.snipsnap.shell.Rooms
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.ShelfImport
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.StarterKits
import com.snipsnap.shell.TextureKits
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val PREFS = "tapeos"
private const val PREF_SCHEME = "scheme"
private const val PREF_PERSONALITY = "personality"
private const val PREF_TEACH = "teach"
/** The Bubble's overlay-permission offer (Task 5) — asked once, ever. */
private const val PREF_OVERLAY_ASKED = "bubble_overlay_asked"
/** The most a shared kit file may be before it is copied for the shelf: a whole backup fits, a video never needs to. */
private const val LANDING_MAX_BYTES = 512L * 1024 * 1024

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
    // The honest little message box (wave FFF): what a landing, a backup
    // or a refusal has to say beyond a toast's one line. Stays until read.
    var note by remember { mutableStateOf<LandingNote.Note?>(null) }
    /** The box in place of the toast, never beside it: opening one puts any toast down. */
    fun openNote(n: LandingNote.Note) {
        toast = null
        note = n
    }
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
    // ROOMS (YY5): kept rooms beside the instruments; the revision bumps after a forget.
    var rooms by remember { mutableStateOf<List<Rooms.Room>>(emptyList()) }
    var binnedRooms by remember { mutableStateOf<List<Rooms.Binned>>(emptyList()) }
    var roomsRevision by remember { mutableStateOf(0) }
    LaunchedEffect(kits, screen, roomsRevision) {
        if (screen == AppScreen.KITS) {
            instruments = withContext(Dispatchers.IO) { shelf.instruments() }
            rooms = withContext(Dispatchers.IO) { runCatching { shelf.rooms() }.getOrDefault(emptyList()) }
            binnedRooms = withContext(Dispatchers.IO) { runCatching { shelf.binnedRooms() }.getOrDefault(emptyList()) }
        }
    }
    // Pad Sheet v2: the open workshop box, remembered per kit so the next
    // pad opens on the same bench; a new kit starts with every box closed.
    var padSheetBox by remember(open?.dir) { mutableStateOf<String?>(null) }
    // TAKES + BIN (X2.3): same shape as PAD SHEET above — reachable only
    // from the KIT action row, not one of MenuRow's fixed ten, so it's
    // KIT-scoped overlay state rather than its own AppScreen entry.
    var takesBinOpen by remember { mutableStateOf(false) }
    // PAD CAPTURE (capture-to-pad): same shape as PAD SHEET/TAKES+BIN — a
    // long-press on an *empty* pad opens this instead, so it's KIT-scoped
    // overlay state too, not one of MenuRow's fixed ten.
    var padCaptureSlot by remember { mutableStateOf<Int?>(null) }
    // GRAIN FIELD: same shape as PAD SHEET/TAKES+BIN/PAD CAPTURE above — only
    // reachable from PAD SHEET's own action row, not one of MenuRow's fixed
    // ten, so it's KIT-scoped overlay state too. Opening it closes PAD SHEET
    // (PadSheetScreen's own onGrainField clears padSheetSlot first) so the
    // two overlays are never both non-null for the same KIT composition.
    var grainFieldSlot by remember { mutableStateOf<Int?>(null) }
    // SNIPS (Task 3): a shelf-level overlay, not KIT-scoped like PAD SHEET/
    // TAKES+BIN/PAD CAPTURE/GRAIN FIELD above — reachable from KitsScreen at
    // AppScreen.KITS (the shelf), one level up from those, so it's its own
    // boolean at this scope rather than sharing theirs.
    var snipsOpen by remember { mutableStateOf(false) }
    // SNIPS → PAD's kit-gate fix: the file a SNIPS row's → PAD asked to
    // place on a pad, stashed here while the user picks a kit. SNIPS is only
    // ever reached from the shelf, where no kit is open by definition, so
    // this is what lets → PAD navigate instead of gating on `open` — the
    // exact TAPE/CHOP kit-gate bug fixed earlier this project, avoided here
    // by routing through kit-pick → arm-the-next-empty-pad-long-press
    // instead of disabling the button (see `assignPendingSnip` below, and
    // the KITS/KIT branches' wiring further down). Cleared the moment the
    // assign lands, or by the MenuRow tab-switch reset below if the user
    // gives up on the pick without ever long-pressing a pad.
    var pendingSnipAssign by remember { mutableStateOf<File?>(null) }
    // SNIPS → TAPE: the file a SNIPS row's → TAPE asked to open, offered to
    // TapeScreen's own `lastCommitSource` fallback slot rather than folded
    // into `lastCommit` — CHOP reads `lastCommit` as the *real* last COMMIT's
    // source/range, and clobbering it with a mere navigation would corrupt
    // that history for a later CHOP session. Honest caveat, not a hard
    // guarantee: `TapeScreen.loadLongestTape`'s own priority always tries
    // `SnipStore.newest` FIRST, so this only actually wins when the chosen
    // row's file can't be read as that newest snip — in the common case
    // (the row a user just captured, sent straight to TAPE) it's usually the
    // same file regardless.
    var tapeOpenOverride by remember { mutableStateOf<File?>(null) }
    // X4.4 TEACH THE MACHINE: off by default, flipped on SETUP's consent
    // row, remembered like the scheme. CHOP reads it; what it gates is
    // feature vectors and labels into the kit's own folder, never audio,
    // and nothing leaves the phone either way (Copy.TEACH_CONSENT).
    var teachEnabled by remember { mutableStateOf(prefs.getBoolean(PREF_TEACH, false)) }
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
    // INSIDE's dead-air verdict (F3.4): the service watches the stream
    // and flips this while an opted-out app is playing into silence; the
    // TAPE JAM box shows once per verdict, and the verdict clears itself
    // the moment sound arrives.
    val blocked by MicSessionService.blocked.collectAsState()
    LaunchedEffect(blocked) {
        if (blocked) captureBlocked = true
    }
    // SNIP's own toast below (`onSnip`) fires optimistically, before
    // MicSessionService.handleSnip's commit has even started — this is the
    // correction if that promise doesn't hold. Baselined by attempt id, the
    // same way `phoneStops` below baselines by count: whatever failure (if
    // any) is already sitting in `lastSnipError` at the moment this
    // composition mounts must NOT immediately re-toast — only a NEW attempt
    // id failing after that point does.
    val lastSnipError by MicSessionService.lastSnipError.collectAsState()
    var lastSnipErrorSeen by remember { mutableStateOf(MicSessionService.lastSnipError.value?.first) }
    LaunchedEffect(lastSnipError) {
        val (attempt, message) = lastSnipError ?: return@LaunchedEffect
        if (attempt != lastSnipErrorSeen) {
            lastSnipErrorSeen = attempt
            toast = message
        }
    }
    // Sessions the phone ended (lock screen, the status-bar stop chip):
    // a routine end, toasted once per tick. The count seen at first
    // composition is the baseline, so an Activity recreated mid-session
    // doesn't re-toast a stop it already reported.
    val phoneStops by MicSessionService.phoneStops.collectAsState()
    var phoneStopsSeen by remember { mutableStateOf(MicSessionService.phoneStops.value) }
    LaunchedEffect(phoneStops) {
        if (phoneStops != phoneStopsSeen) {
            phoneStopsSeen = phoneStops
            toast = Copy.PHONE_STOPPED_TAPE
        }
    }
    // ARM INSIDE runs through the same RECORD_AUDIO request as ARM TAPE
    // (playback capture needs it too); this remembers which button asked,
    // so the permission callback below knows whether to arm the mic or
    // go on to the projection consent.
    var armInsidePending by remember { mutableStateOf(false) }

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

    // The Bubble's one-time offer, shared by both arms — arming must never
    // wait on it, so callers run it after arm() is already underway.
    fun offerBubbleOnce() {
        if (!prefs.getBoolean(PREF_OVERLAY_ASKED, false) && !Settings.canDrawOverlays(context)) {
            prefs.edit().putBoolean(PREF_OVERLAY_ASKED, true).apply()
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    // The projection consent (INSIDE): the system's own dialog, asked
    // afresh on every ARM INSIDE — Android 14+ honours each consent for
    // exactly one projection, so the result is never cached. Dismissed
    // is a refusal in words, not a failure; the mic path stays open.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MicSessionService.armInside(context, result.resultCode, data)
            toast = Copy.INSIDE_ARMED
            offerBubbleOnce()
        } else {
            toast = Copy.INSIDE_REFUSED
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
        val inside = armInsidePending
        armInsidePending = false
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            // This callback runs with the Activity resumed (foreground) —
            // true whether the system dialog actually showed or the
            // permission was already granted and the contract
            // short-circuited straight here — so this satisfies
            // MicSessionService.arm's "call from a foreground context"
            // requirement without a separate checkSelfPermission branch.
            if (inside) {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                if (manager == null) {
                    toast = Copy.INSIDE_REFUSED
                } else {
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                }
            } else {
                MicSessionService.arm(context)
                toast = Copy.SESSION_ARMED
                offerBubbleOnce()
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

    fun requestArmInside() {
        armInsidePending = true
        requestArm()
    }

    LaunchedEffect(Unit) {
        kits = withContext(Dispatchers.IO) { shelf.list() }
        // The rooms' bin empties itself of what has slept past its days.
        withContext(Dispatchers.IO) { runCatching { shelf.sweepRooms() } }
        // Same promise as sweepRooms() above, for pad ejects: BIN_KEEP_DAYS was
        // always the intent (KitBuilder.kt's own docs), but nothing ever called
        // purgeBin() outside the manual "EMPTY THE BIN NOW" button — every ejected
        // pad has been living forever. This is the fix: run it once per launch,
        // per kit, same as Rooms already does for itself.
        withContext(Dispatchers.IO) {
            runCatching {
                KitStore.list(shelf.root).forEach { kitDir ->
                    runCatching { KitBuilderModel.open(kitDir).purgeBin() }
                }
            }
        }
        // Orphaned import-staging left by a crashed/killed import, and
        // regenerable derived output (share-sheet temp copies) — never a
        // live kit or snip. Exports are deliberately NOT swept here: see
        // sweepOrphanedStorage's KDoc.
        withContext(Dispatchers.IO) { runCatching { sweepOrphanedStorage(shelf.root, context.cacheDir) } }
    }

    // IMPORT (F3.1/F3.2): a file shared in from another app, waiting on
    // ShareInbox's doorstep. Decoded off the main thread (MediaDecode: a
    // WAV straight through the reader, anything else through the
    // platform's codec), landed as a snip (SnipStore.import: mono, the
    // MPC rate, capped), then TAPE - which finds the newest snip first
    // by its own source priority. With no kit open the first on the
    // shelf is opened for the deck's cassette label and fallback; with an
    // empty shelf the deck plays the snip on its own — TAPE no longer
    // needs a kit for one. `importCount` is TAPE's reload request, for a
    // share that arrives while TAPE is already on screen.
    val shared by ShareInbox.pending.collectAsState()
    var importCount by remember { mutableStateOf(0) }
    // GROOVE's reload request: bumped when TAPE rewrites the open kit's
    // groove (READ AS GROOVE, STEAL THE FEEL) so a GROOVE already up reloads.
    var grooveReload by remember { mutableStateOf(0) }
    LaunchedEffect(shared) {
        val uri = shared ?: return@LaunchedEffect
        // The status line is borrowed only when nothing else holds it: a
        // sound import writes to the snips dir, and a kit file lands as
        // *new* folders on the shelf under names nothing there holds -
        // neither touches the open kit a dub in flight is writing, so an
        // import runs beside that dub without racing it, and must not wipe
        // the dub's own DUBBING… line on its way out.
        val ownsBusy = busy == null
        if (ownsBusy) busy = Copy.IMPORT_BUSY
        // The share's own name, for the refusal box; blank until the provider answers.
        var sharedName = ""
        try {
            // The bytes decide what the share is: a kit file (a .xpn, a
            // backup, an MPC track zipped with its folder) lands on the
            // shelf through ShelfImport; everything else is a sound for
            // the deck. The MIME a messenger attaches is not consulted.
            val name = withContext(Dispatchers.IO) { ShareInbox.displayName(context, uri) }
            sharedName = name
            val kind = withContext(Dispatchers.IO) { ShelfImport.sniff(ShareInbox.head(context, uri, ShelfImport.SNIFF_BYTES)) }
            if (ShelfImport.isKit(kind)) {
                if (ownsBusy) busy = Copy.LANDING_BUSY
                val (entries, skipped) = withContext(Dispatchers.IO) {
                    val local = ShareInbox.copyToCache(context, uri, name, LANDING_MAX_BYTES)
                    try {
                        shelf.land(local, name)
                    } finally {
                        local.delete()
                    }
                }
                ShareInbox.consume()
                kits = withContext(Dispatchers.IO) { shelf.list() }
                // A clean landing keeps its toast; one with skips opens the
                // box, which names each skipped kit and the door's reason.
                val boxed = LandingNote.landed(name, entries.map { it.kit.name }, skipped)
                if (boxed != null) openNote(boxed) else toast = Copy.landed(entries.size, skipped.size)
                entries.firstOrNull()?.let { first ->
                    open = first
                    padSheetSlot = null
                    takesBinOpen = false
                    screen = AppScreen.KIT
                }
                return@LaunchedEffect
            }
            val landed = withContext(Dispatchers.IO) {
                val snip = MediaDecode.decode(context, uri)
                SnipStore.import(snip, context.filesDir, System.currentTimeMillis())
            }
            ShareInbox.consume()
            if (open == null) {
                open = withContext(Dispatchers.IO) { shelf.list() }.firstOrNull()
            }
            toast = Copy.imported(landed.seconds, landed.truncated)
            importCount++
            padSheetSlot = null
            takesBinOpen = false
            screen = AppScreen.TAPE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShareInbox.consume()
            // Law 3: when it breaks, say exactly what happened - the
            // decoder's own words when it has them (a refusal, an IO
            // error, a permission the provider withdrew), the house line
            // only when there are none. Locale.ROOT: a toast's casing
            // must not depend on the phone's language.
            // In the box, not a toast: a refusal is read at the reader's pace.
            val reason = e.message?.takeIf { it.isNotBlank() } ?: Copy.IMPORT_NOT_AUDIO
            openNote(LandingNote.refused(sharedName, reason))
        } finally {
            if (ownsBusy) busy = null
        }
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
                // KitShelf.setKey is its own open→mutate→save on this kit's
                // kit.json — the same file PAD SHEET/PAD CAPTURE/SYNTH/TAKES+BIN
                // write, so it goes through the same KitWrites lock they do.
                withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.setKey(source, key) } }
            } catch (e: Exception) {
                toast = "KEY FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            // Same identity guard as the KIT branch's onKitUpdated closures
            // above: this write ran against `source.dir`, not necessarily
            // whichever kit is open by the time it lands (setKey sets no
            // `busy`, so nothing here stops a tab-away-and-reopen mid-write).
            if (open?.dir == source.dir) open = entry
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
            val (entry, _) = try {
                // Same reasoning as setKey above: evilTwins is an
                // open→mutate→save on kit.json.
                withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.evilTwins(source, Random.nextInt()) } }
            } catch (e: Exception) {
                busy = null
                toast = "TWINS FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            // Same identity guard as setKey above — `busy` blocks a second
            // EVIL TWINS tap, not a MenuRow tab-away-and-reopen mid-write.
            if (open?.dir == source.dir) open = entry
            kits = withContext(Dispatchers.IO) { shelf.list() }
            busy = null
            toast = if (hadTwins) Copy.TWINS_REROLLED else Copy.BANK_B_LIT
        }
    }

    /**
     * SNIPS → PAD's landing (Task 3): fired by the next empty-pad long-press
     * once a kit is open with [pendingSnipAssign] armed. `KitScreen.kt`
     * itself is unmodified for this — the KIT branch's own `onEmptyLongPress`
     * below intercepts before the press ever reaches `padCaptureSlot`/
     * `PadCaptureScreen`. Same open→classify→assign→save shape as
     * `PadCaptureScreen.commitToPad`, including the same `KitWrites.mutex` —
     * this writes the same `kit.json` that screen (and every other kit
     * mutator in this file) does. `source = mapOf("file" to file.name)` is
     * exactly the provenance param Task 2's `KitBuilderModel.assign` added;
     * this is its first live caller.
     */
    fun assignPendingSnip(file: File, slot: Int) {
        val target = open ?: return
        // Cleared synchronously, before the write even starts — a second
        // empty-pad long-press elsewhere while this one is mid-flight must
        // fall through to the normal capture surface, not race this write
        // for the same pending file.
        pendingSnipAssign = null
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val snip = Cleanup.toMono(WavReader.read(file))
                    val cls = Classifier.classify(snip).drumClass
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(target.dir)
                        model.assign(slot, snip, cls, cls.name.replace('_', ' '), source = mapOf("file" to file.name))
                        model.save()
                        model.kit
                    }
                }
                // Same identity guard as setKey/evilTwins above: a write
                // that outlived a tab-away-and-reopen must not weld itself
                // onto whichever kit is open now.
                if (open?.dir == target.dir) open = open?.copy(kit = updated)
                kits = withContext(Dispatchers.IO) { shelf.list() }
                toast = "SNIP PLACED ON PAD A%02d".format(slot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened.
                toast = "PLACE FAILED: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * INSTANT KIT (F2.2): the one tap on TAPE — the selection (or the whole
     * deck) chopped with the defaults and landed on the grid without the
     * review, the same DUBBING… shape as a fresh tape. CHOP can still open
     * the result later to argue with the chips.
     */
    /**
     * READ AS GROOVE (wave ZZ): the tape read as a rhythm instead of a
     * sound. The Ear hears the selection (or the whole deck), the open
     * kit's own pads play it, and GROOVE opens on it. Refusals are the
     * Ear's own words; no kit open is one too.
     */
    fun readGroove(file: File, range: IntRange) {
        val target = open
        if (target == null) {
            toast = Copy.READ_GROOVE_NEEDS_KIT
            return
        }
        if (busy != null) return
        busy = Copy.READ_GROOVE_BUSY
        scope.launch {
            try {
                val reading = withContext(Dispatchers.IO) {
                    val snip = InstantKit.slice(WavReader.read(file), range)
                    val r = ReadGroove.read(snip, target.kit, file.nameWithoutExtension)
                    ReadGroove.land(target.dir, r)
                    r
                }
                toast = Copy.grooveRead(reading.hits, reading.bars, Math.round(reading.bpm))
                grooveReload++
                screen = AppScreen.GROOVE
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                toast = Copy.grooveRefused(e.message ?: "the ear refused")
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened.
                toast = "READ FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    /**
     * STEAL THE FEEL (wave ZZ): the same reading kept as timing and
     * accent alone, bottled on the shelf's own rack and poured over the
     * open kit's pattern as PROG E, so A–D stay untouched.
     */
    fun stealFeel(file: File, range: IntRange) {
        val target = open
        if (target == null) {
            toast = Copy.READ_GROOVE_NEEDS_KIT
            return
        }
        if (busy != null) return
        busy = Copy.FEEL_BUSY
        scope.launch {
            try {
                val felt = withContext(Dispatchers.IO) {
                    val snip = InstantKit.slice(WavReader.read(file), range)
                    val f = ReadGroove.feel(snip, target.dir, file.nameWithoutExtension)
                    ReadGroove.keepPocket(f.pocket, context.filesDir)
                    f
                }
                toast = Copy.feelStolen(felt.covered)
                grooveReload++
                screen = AppScreen.GROOVE
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                toast = Copy.feelRefused(e.message ?: "the ear refused")
            } catch (e: Exception) {
                toast = "FEEL FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    fun instantKit(file: File, range: IntRange) {
        if (busy != null) return
        busy = "CHOPPING…"
        scope.launch {
            // `finally` owns the busy overlay: whichever way this leaves
            // (built, refused, or the scope cancelled underneath it), the
            // screen never stays stuck on CHOPPING….
            try {
                val (entry, result) = withContext(Dispatchers.IO) { shelf.instantKit(file, range) }
                kits = withContext(Dispatchers.IO) { shelf.list() }
                toast = Copy.instantKit(result.sliceCount, result.chokeSet)
                open = entry
                screen = AppScreen.KIT
            } catch (e: CancellationException) {
                // Leaving the screen is not a failure; let the scope have it.
                throw e
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened.
                toast = "INSTANT KIT FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    /**
     * SHARE (F6.3): the open kit packed as one `.xpn` into the share cache
     * and handed to the chooser. Preflight's refusal (a broken kit) comes
     * back in words, like EXPORT's.
     */
    fun shareKit() {
        val source = open ?: return
        if (busy != null) return
        busy = Copy.PACKING_BUSY
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { shelf.pack(source, ShareOut.shareDir(context)) }
                busy = null
                toast = if (ShareOut.send(context, file, ShareOut.ZIP_MIME, source.kit.name)) {
                    Copy.kitPacked(source.kit.name)
                } else {
                    Copy.SHARE_NOWHERE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = "SHARE FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    /**
     * BACKUP (X3.3): every kit on the shelf as one file, handed to the
     * chooser - Drive, a cable, a messenger to yourself. The same file
     * shared back in lands every kit again through the shelf's door.
     */
    /** FORGET → BIN on a held room: into the bin for 30 days, the toast says so. */
    fun forgetRoom(room: Rooms.Room) {
        // Under the app's one busy lock, like every other file move: a second
        // tap, or another job in flight, must not race the bin.
        if (busy != null) return
        busy = Copy.ROOM_FORGET_BUSY
        scope.launch {
            try {
                // The bin may freshen the name ("FUNK ROOM 2") - the toast says the name it went in under.
                val binned = withContext(Dispatchers.IO) { shelf.forgetRoom(room) }
                roomsRevision++
                toast = Copy.roomForgotten(binned.room.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = "FORGET FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    /** RESTORE on a binned room: back onto the shelf, the toast names it. */
    fun restoreRoom(binned: Rooms.Binned) {
        if (busy != null) return
        busy = Copy.ROOM_RESTORE_BUSY
        scope.launch {
            try {
                val room = withContext(Dispatchers.IO) { shelf.restoreRoom(binned) }
                roomsRevision++
                toast = Copy.roomRestored(room.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = "RESTORE FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    fun backupShelf() {
        if (busy != null) return
        if (kits.isEmpty()) {
            toast = Copy.BACKUP_EMPTY
            return
        }
        busy = Copy.PACKING_BUSY
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { shelf.backup(ShareOut.shareDir(context), System.currentTimeMillis()) }
                busy = null
                if (!ShareOut.send(context, result.file, ShareOut.ZIP_MIME, result.file.nameWithoutExtension)) {
                    toast = Copy.SHARE_NOWHERE
                } else {
                    // Every kit in: the toast. Preflight refused some: the box,
                    // naming each and why - it waits behind the chooser and is
                    // read on the way back.
                    val boxed = LandingNote.backedUp(result.packed, result.skipped)
                    if (boxed != null) openNote(boxed) else toast = Copy.backedUp(result.packed.size, result.skipped.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = "BACKUP FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
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
                // Same reasoning as setKey above: inKey is an
                // open→mutate→save on kit.json.
                withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.inKey(source) } }
            } catch (e: Exception) {
                toast = "IN KEY FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            // Same identity guard as setKey above — inKey sets no `busy`
            // either, so nothing stops a tab-away-and-reopen mid-write.
            if (open?.dir == source.dir) open = entry
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
                        padCaptureSlot = null
                        grainFieldSlot = null
                        // SNIPS is shelf-level, not KIT-scoped, but the same
                        // "leaving must not leave an overlay/hand-off armed"
                        // reasoning applies: a tab switch away from KITS
                        // mid-pick abandons the → PAD hand-off rather than
                        // leaving KitsScreen stuck on "PICK A KIT FOR THIS
                        // SNIP" forever.
                        snipsOpen = false
                        pendingSnipAssign = null
                        tapeOpenOverride = null
                    },
                )
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        AppScreen.KITS -> if (snipsOpen) {
                            SnipsScreen(
                                shelf = shelf,
                                onBack = { snipsOpen = false },
                                onToast = { toast = it },
                                onOpenInTape = { file ->
                                    snipsOpen = false
                                    tapeOpenOverride = file
                                    screen = AppScreen.TAPE
                                },
                                onPickPadFor = { file ->
                                    // Closes SNIPS and stashes the file — the
                                    // shelf's own KitsScreen renders next
                                    // (still AppScreen.KITS, just with
                                    // `snipsOpen` now false), its header
                                    // swapped to the pick-a-kit hint by
                                    // `assigningSnip` below.
                                    snipsOpen = false
                                    pendingSnipAssign = file
                                },
                            )
                        } else {
                            KitsScreen(
                                kits = kits,
                                instruments = instruments,
                                busy = busy != null,
                                armed = armed,
                                onOpen = { entry ->
                                    open = entry
                                    screen = AppScreen.KIT
                                    // A kit opened while a SNIPS → PAD pick is
                                    // still pending: tell the user what the
                                    // next empty-pad long-press will do, since
                                    // `KitScreen` itself carries no hint banner
                                    // of its own for this mode. But KitScreen
                                    // only ever renders bank A (slots 1..16 —
                                    // see its own GRID_ROWS), so an "empty
                                    // pad" instruction is only actually
                                    // followable if bank A has one; a kit
                                    // that's already full there has nothing
                                    // for the long-press to catch (the v1
                                    // "empty pads only" scope this task's
                                    // brief calls out), so the hint says so
                                    // instead of pointing at a pad that
                                    // doesn't exist.
                                    if (pendingSnipAssign != null) {
                                        val hasEmptyPad = (1..16).any { entry.kit.pad(it) == null }
                                        toast = if (hasEmptyPad) {
                                            "LONG-PRESS AN EMPTY PAD TO PLACE THIS SNIP"
                                        } else {
                                            "THIS KIT IS FULL — PICK ANOTHER"
                                        }
                                    }
                                },
                                onOpenInstrument = { openInstrument = it; screen = AppScreen.KEYS },
                                onFresh = ::fresh,
                                onArm = ::requestArm,
                                onArmInside = ::requestArmInside,
                                onSnip = {
                                    MicSessionService.snip(context)
                                    // Optimistic — the real commit hasn't started yet,
                                    // let alone resolved. Immediate feedback is good UX,
                                    // but it's a promise: the `lastSnipError` collector
                                    // above corrects this toast if that commit fails.
                                    toast = Copy.SNIPPED
                                },
                                onEject = { MicSessionService.eject(context) },
                                onBackup = ::backupShelf,
                                onSnips = { snipsOpen = true },
                                assigningSnip = pendingSnipAssign != null,
                                rooms = rooms,
                                onForgetRoom = ::forgetRoom,
                                binnedRooms = binnedRooms,
                                onRestoreRoom = ::restoreRoom,
                            )
                        }
                        AppScreen.KIT -> {
                            val sheetSlot = padSheetSlot
                            val sheetEntry = open
                            val fieldSlot = grainFieldSlot
                            when {
                                // Checked before the PAD SHEET branch below:
                                // opening GRAIN clears `padSheetSlot` at the
                                // same time it sets `grainFieldSlot` (see
                                // onGrainField below), so in practice the two
                                // conditions are already mutually exclusive —
                                // this ordering is belt-and-suspenders should
                                // that ever not hold.
                                fieldSlot != null && sheetEntry != null -> GrainFieldScreen(
                                    entry = sheetEntry,
                                    slot = fieldSlot,
                                    onBack = { grainFieldSlot = null },
                                    onToast = { toast = it },
                                    onRequestArm = ::requestArm,
                                )
                                sheetSlot != null && sheetEntry != null -> PadSheetScreen(
                                    entry = sheetEntry,
                                    slot = sheetSlot,
                                    onSlotChange = { padSheetSlot = it },
                                    onBack = { padSheetSlot = null },
                                    onToast = { toast = it },
                                    openBox = padSheetBox,
                                    onOpenBox = { padSheetBox = it },
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
                                    onGrainField = { slot ->
                                        // GRAIN closes PAD SHEET on the way
                                        // in — the two overlays never render
                                        // at once (see the `when` ordering
                                        // comment above).
                                        padSheetSlot = null
                                        grainFieldSlot = slot
                                    },
                                    onKitUpdated = { updatedKit ->
                                        // A write that outlived its screen must not be welded
                                        // onto whichever kit is open NOW (QA: the "Frankenstein
                                        // entry" — late GRAB on kit A landing after kit B was
                                        // opened produced Entry(dir=B, kit=A)). `sheetEntry` was
                                        // captured above at this composition, not re-read live.
                                        if (open?.dir == sheetEntry.dir) open = open?.copy(kit = updatedKit)
                                        // The shelf refresh stays unconditional — the write
                                        // happened on disk regardless of what's open now.
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
                                        // not the stale cached one — but only
                                        // onto the kit this restore actually
                                        // ran against (see the identity guard
                                        // comment on PAD SHEET's own
                                        // onKitUpdated above).
                                        if (open?.dir == sheetEntry.dir) open = open?.copy(kit = updatedKit)
                                        scope.launch {
                                            kits = withContext(Dispatchers.IO) { shelf.list() }
                                        }
                                    },
                                )
                                padCaptureSlot != null && sheetEntry != null -> {
                                    // Only `armed` is collected here — it changes
                                    // rarely. `level` is deliberately NOT collected
                                    // at this scope; PadCaptureScreen's own leaf
                                    // meter composable collects it, so the ~21Hz
                                    // tick recomposes just that leaf, not this
                                    // whole `when` branch (header + GRAB button).
                                    val captureArmed by MicSessionService.armed.collectAsState()
                                    PadCaptureScreen(
                                        entry = sheetEntry,
                                        slot = padCaptureSlot!!,
                                        armed = captureArmed,
                                        onRequestArm = ::requestArm,
                                        onBack = { padCaptureSlot = null },
                                        onToast = { toast = it },
                                        onKitUpdated = { updatedKit ->
                                            // Same shape as PAD SHEET/TAKES+BIN's own
                                            // onKitUpdated: bump `open.kit`'s identity
                                            // so KIT's PadPlayer reloads the pad GRAB
                                            // just filled, not a stale cached (empty)
                                            // sample — guarded the same way, against
                                            // `sheetEntry` captured at this
                                            // composition, not whatever `open` is now.
                                            if (open?.dir == sheetEntry.dir) open = open?.copy(kit = updatedKit)
                                            scope.launch {
                                                kits = withContext(Dispatchers.IO) { shelf.list() }
                                            }
                                        },
                                        // App()'s own scope — same reasoning as
                                        // PadSheetScreen's own appScope above: the
                                        // GRAB write must survive a MenuRow tab
                                        // switch mid-write, not be cancelled by it.
                                        appScope = scope,
                                    )
                                }
                                else -> KitScreen(
                                    open,
                                    busy = busy != null,
                                    onLongPress = { slot -> padSheetSlot = slot },
                                    onTakesBin = { takesBinOpen = true },
                                    onTexture = ::texture,
                                    onSetKey = ::setKey,
                                    onInKey = ::inKey,
                                    onTwins = ::evilTwins,
                                    onShare = ::shareKit,
                                    onEmptyLongPress = { slot ->
                                        // SNIPS → PAD's landing: a pick still
                                        // armed intercepts the press here,
                                        // before it ever reaches the normal
                                        // capture surface below — KitScreen.kt
                                        // itself needs no changes for this.
                                        val pending = pendingSnipAssign
                                        if (pending != null) {
                                            assignPendingSnip(pending, slot)
                                        } else {
                                            padCaptureSlot = slot
                                        }
                                    },
                                    onEmptyTapHint = {
                                        toast = if (pendingSnipAssign != null) {
                                            "LONG-PRESS TO PLACE THIS SNIP"
                                        } else {
                                            "LONG-PRESS TO CAPTURE"
                                        }
                                    },
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
                            // `tapeOpenOverride` (a SNIPS → TAPE request) is
                            // the more recent user intent whenever both are
                            // set, so it's offered before a possibly much
                            // older real commit — see that var's own KDoc
                            // for the honest limit of what it actually
                            // guarantees against `SnipStore.newest` above it.
                            lastCommitSource = tapeOpenOverride ?: lastCommit?.sourceFile,
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
                            onCommit = { file, range ->
                                lastCommit = TapeCommit(file, range)
                                // A real COMMIT is genuine, fresher intent than
                                // whatever SNIPS → TAPE request (if any) is
                                // still sitting in `tapeOpenOverride` — clearing
                                // it here is what keeps that var from
                                // permanently shadowing `lastCommit` for the
                                // rest of the session once it's ever been set.
                                tapeOpenOverride = null
                            },
                            onInstantKit = ::instantKit,
                            onReadGroove = ::readGroove,
                            onStealFeel = ::stealFeel,
                            reloadRequest = importCount,
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
                            teachEnabled = teachEnabled,
                            onTeach = { on ->
                                teachEnabled = on
                                prefs.edit().putBoolean(PREF_TEACH, on).apply()
                                toast = if (on) Copy.TEACHING_ON else Copy.TEACHING_OFF
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
                        AppScreen.SYNTH -> {
                            // Captured here, at this composition, so a SEND TO PAD write that
                            // outlives this screen (appScope, below) can't weld its result onto
                            // whichever kit is open by the time it lands — same identity guard
                            // as the KIT branch's own onKitUpdated closures above.
                            val synthEntry = open
                            SynthScreen(
                                entry = synthEntry,
                                onToast = { toast = it },
                                onKitUpdated = { updatedKit ->
                                    // Same shape as PAD SHEET/CHOP's own
                                    // onKitUpdated: bump `open.kit`'s identity so
                                    // KIT's PadPlayer reloads the pad SEND TO PAD
                                    // just replaced, not a stale cached sample.
                                    if (open?.dir == synthEntry?.dir) open = open?.copy(kit = updatedKit)
                                    scope.launch {
                                        kits = withContext(Dispatchers.IO) { shelf.list() }
                                    }
                                },
                                // App()'s own scope — same reasoning as PAD SHEET/PAD
                                // CAPTURE's own appScope: SEND TO PAD's write must survive
                                // a MenuRow tab switch, not be cancelled by it.
                                appScope = scope,
                            )
                        }
                        AppScreen.SURFACE -> SurfaceScreen(
                            entry = open,
                            onToast = { toast = it },
                            // A print is a snip on the shelf: the same reload
                            // request a share-sheet import raises.
                            onPrinted = { importCount++ },
                            // A print on a pad: bump `open.kit`'s identity so
                            // KIT's PadPlayer and the surface reload it.
                            onKitUpdated = { updatedKit ->
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
                            reloadRequest = grooveReload,
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
            // A toast raised while the box is up (a share landing behind it)
            // is not drawn beside it; its dwell runs out unseen.
            ToastOverlay(if (note == null) toast else null)
            note?.let { n -> MessageBox(n, onDismiss = { note = null }) }
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
