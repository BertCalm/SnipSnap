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
import com.snipsnap.app.ui.PadCaptureScreen
import com.snipsnap.app.ui.PadSheetScreen
import com.snipsnap.app.ui.PlayScreen
import com.snipsnap.app.ui.PrimaryAction
import com.snipsnap.app.ui.PropertiesScreen
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
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.Copy
import com.snipsnap.shell.InstantKit
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
        try {
            // The bytes decide what the share is: a kit file (a .xpn, a
            // backup, an MPC track zipped with its folder) lands on the
            // shelf through ShelfImport; everything else is a sound for
            // the deck. The MIME a messenger attaches is not consulted.
            val name = withContext(Dispatchers.IO) { ShareInbox.displayName(context, uri) }
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
                toast = Copy.landed(entries.size, skipped.size)
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
            val reason = e.message?.takeIf { it.isNotBlank() }
            toast = if (reason != null) "IMPORT REFUSED: ${reason.uppercase(java.util.Locale.ROOT).trimEnd('.')}." else Copy.IMPORT_NOT_AUDIO
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
            val (entry, _) = try {
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
                toast = if (ShareOut.send(context, result.file, ShareOut.ZIP_MIME, result.file.nameWithoutExtension)) {
                    Copy.backedUp(result.packed.size, result.skipped.size)
                } else {
                    Copy.SHARE_NOWHERE
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
                        padCaptureSlot = null
                        grainFieldSlot = null
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
                            onArmInside = ::requestArmInside,
                            onSnip = {
                                MicSessionService.snip(context)
                                toast = Copy.SNIPPED
                            },
                            onEject = { MicSessionService.eject(context) },
                            onBackup = ::backupShelf,
                            rooms = rooms,
                            onForgetRoom = ::forgetRoom,
                            binnedRooms = binnedRooms,
                            onRestoreRoom = ::restoreRoom,
                        )
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
                                            // sample.
                                            open = open?.copy(kit = updatedKit)
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
                                    onEmptyLongPress = { slot -> padCaptureSlot = slot },
                                    onEmptyTapHint = { toast = "LONG-PRESS TO CAPTURE" },
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
