package com.snipsnap.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.rememberDeskBrush
import com.snipsnap.app.theme.tape
import com.snipsnap.app.theme.windowFrame
import com.snipsnap.app.ui.AppScreen
import com.snipsnap.app.ui.ArrangeScreen
import com.snipsnap.app.ui.ChopScreen
import com.snipsnap.app.ui.DeletedKitsScreen
import com.snipsnap.app.ui.DoublesScreen
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
import com.snipsnap.app.ui.OrbitScreen
import com.snipsnap.app.ui.PadCaptureScreen
import com.snipsnap.app.ui.PadSheetScreen
import com.snipsnap.app.ui.PlayScreen
import com.snipsnap.app.ui.PREF_CARD_TREE
import com.snipsnap.app.ui.PREF_EXPORT_FORMAT
import com.snipsnap.app.ui.PrimaryAction
import com.snipsnap.app.ui.PropertiesScreen
import com.snipsnap.app.ui.SnipsScreen
import com.snipsnap.app.ui.SplitScreen
import com.snipsnap.app.ui.StatusBar
import com.snipsnap.app.ui.StubScreen
import com.snipsnap.app.ui.SurfaceScreen
import com.snipsnap.app.ui.SynthScreen
import com.snipsnap.app.ui.TakesBinScreen
import com.snipsnap.app.ui.TAPE_LOAD_MAX_SEC
import com.snipsnap.app.ui.TapeScreen
import com.snipsnap.app.ui.StackTakesScreen
import com.snipsnap.app.ui.TapeSpliceScreen
import com.snipsnap.app.ui.TapeText
import com.snipsnap.app.ui.TitleBar
import com.snipsnap.app.ui.ToastOverlay
import com.snipsnap.app.ui.XRayScreen
import com.snipsnap.app.ui.tapeClick
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Dust
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Breed
import com.snipsnap.shell.Copy
import com.snipsnap.shell.DustPrints
import com.snipsnap.shell.InstantKit
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.LandingNote
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadSheet
import com.snipsnap.shell.Personality
import com.snipsnap.shell.ReadGroove
import com.snipsnap.shell.RecipeReplay
import com.snipsnap.shell.Retrim
import com.snipsnap.shell.RoomPackager
import com.snipsnap.shell.Rooms
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.ShelfImport
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.StarterKits
import com.snipsnap.shell.TextureKits
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

internal const val PREFS = "tapeos"
private const val PREF_SCHEME = "scheme"
private const val PREF_PERSONALITY = "personality"
private const val PREF_TEACH = "teach"
/** The shelf's sort toggle (name-and-find followups) — [KitShelf.ShelfSort], remembered like the scheme. */
private const val PREF_SHELF_SORT = "shelf_sort"
/** The Bubble's overlay-permission offer (Task 5) — asked once, ever. */
private const val PREF_OVERLAY_ASKED = "bubble_overlay_asked"

/**
 * How many times the "hold a pad" hint has been shown, or [PAD_SHEET_FOUND]
 * once the user has actually opened PAD SHEET. The count is kept as a
 * record of how often the app has said it; nothing caps it any more.
 *
 * PAD SHEET — every treatment, shape, tune and mutate control, plus GRAIN
 * FIELD below it — has no tap path at all: a 480ms long-press on a filled
 * pad is the only way in. The UX audit put 40-50% of the app's real depth
 * behind that one gesture, and both the returning-user and day-3
 * walkthroughs independently predicted users plateau without ever finding
 * it. KIT now carries a permanent legend naming the hold (September UAT,
 * finding 4), so the gesture is no longer unhinted and this toast is a
 * nudge rather than the only teacher it used to be.
 *
 * The empty-pad branch already teaches its own long-press by toasting on
 * tap ([KitScreen]'s `onEmptyTapHint`). The filled branch can't copy that
 * trick — a tap on a filled pad must SOUND, instantly, because it's a drum
 * pad — so the hint rides kit-open instead, and only while undiscovered.
 */
private const val PREF_PAD_SHEET_HINTS = "pad_sheet_hints"

/** Sentinel for [PREF_PAD_SHEET_HINTS]: PAD SHEET has been opened, so never hint again. */
private const val PAD_SHEET_FOUND = -1
/** The most a shared kit file may be before it is copied for the shelf: a whole backup fits, a video never needs to. */
private const val LANDING_MAX_BYTES = 512L * 1024 * 1024
/** CHOP ALL's own per-file cache-copy ceiling — sized for one WAV, not a whole kit/backup zip like [LANDING_MAX_BYTES] above (same 96 MB reasoning as MediaDecode's own MAX_WAV_BYTES). */
private const val CHOP_ALL_MAX_FILE_BYTES = 96L * 1024 * 1024

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
 * RE-TRIM (docs/RETRIM.md §3): the PAD SHEET asked TAPE to open on this
 * pad's own tape. [file] is the snip on the SNIPS shelf and [cut] where
 * the pad's audio sits in it (null = the whole file, a pad tagged before
 * the cut keys existed). While one is live, TAPE loads [file] ahead of
 * everything else, opens with IN and OUT on the cut, and its primary
 * button is BACK ONTO [padLabel] instead of KEEP. It dies on BACK ONTO, a
 * plain KEEP, leaving TAPE by the menu row, or a new capture landing.
 */
data class RetrimRequest(
    val kitDir: File,
    val slot: Int,
    /** "A02" — what the header and the button call the pad. */
    val padLabel: String,
    val file: File,
    val cut: com.snipsnap.shell.Retrim.Cut?,
    /** The pad's own colour, for TAPE's header chip: the class colour when the pad has none of its own. */
    val colorHex: String? = null,
    val drumClass: com.snipsnap.audio.DrumClass = com.snipsnap.audio.DrumClass.UNKNOWN,
)

/** X-RAY's own state: the picked file's display name and what [MpcXRay.read] made of it. Non-null IS "the screen is open" — there is no separate boolean to keep in sync with it. */
data class XRayView(val fileName: String, val reading: com.snipsnap.mpc3.MpcXRay.Reading)

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

    // SETUP's WHERE YOUR FILES LIVE row (September UAT, finding 23).
    // getExternalFilesDir does real filesystem work - it creates the
    // directory if it is absent, and returns null when external storage is
    // not mounted - so it is resolved on IO exactly once, the same rule
    // ExportScreen's own write path states in so many words. Reading it in
    // the composable branch that draws the row would touch the disk on the
    // main thread on every recomposition of that screen.
    var exportsWhere by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        exportsWhere = withContext(Dispatchers.IO) {
            (context.getExternalFilesDir("exports") ?: context.filesDir).absolutePath
        }
    }

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

    // SHELF SORT (name-and-find followups): RECENT by default — persisted
    // like scheme/personality above. `setShelfSort` both writes the pref
    // and re-fetches `kits` under the new order immediately, the same
    // "flip it, see it" shape STARTER/SCHEME's own toggles already have.
    var shelfSort by remember {
        mutableStateOf(
            prefs.getString(PREF_SHELF_SORT, null)
                ?.let { saved -> KitShelf.ShelfSort.entries.firstOrNull { it.name == saved } }
                ?: KitShelf.ShelfSort.RECENT,
        )
    }

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
    // DELETED KITS (Task 2 of the bin-restore plan): the shelf's own gate
    // for the `DELETED KITS ▸` row — how many kits `KitShelf.binnedKits()`
    // holds right now. `deletedKitsOpen` is in this effect's key list, not
    // just `kits`/`screen`/`roomsRevision`: EMPTY THE BIN NOW changes
    // nothing else this effect already watches, so without that key,
    // emptying the bin and returning to the shelf would leave a stale count
    // (and the row itself) showing after the door behind it is empty.
    var binnedKitsCount by remember { mutableStateOf(0) }
    var deletedKitsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(kits, screen, roomsRevision, deletedKitsOpen) {
        if (screen == AppScreen.KITS) {
            instruments = withContext(Dispatchers.IO) { shelf.instruments() }
            rooms = withContext(Dispatchers.IO) { runCatching { shelf.rooms() }.getOrDefault(emptyList()) }
            binnedRooms = withContext(Dispatchers.IO) { runCatching { shelf.binnedRooms() }.getOrDefault(emptyList()) }
            binnedKitsCount = withContext(Dispatchers.IO) { runCatching { shelf.binnedKits() }.getOrDefault(emptyList()) }.size
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
    // TAPE SPLICE: same shape as GRAIN FIELD above — only reachable from
    // PAD SHEET's own action row, not one of MenuRow's fixed ten, so it's
    // KIT-scoped overlay state too. Opening it closes PAD SHEET the same
    // way GRAIN FIELD's own onGrainField does below.
    var spliceSlot by remember { mutableStateOf<Int?>(null) }
    // STACK THE TAKES: same shape as SPLICE, over the same pad history.
    var stackSlot by remember { mutableStateOf<Int?>(null) }
    // ARRANGE: same shape again, but GROOVE-scoped rather than KIT-scoped —
    // reachable only from GROOVE's own "SONG ▸" button, not one of MenuRow's
    // fixed ten, so a boolean here rather than its own AppScreen entry.
    var arrangeOpen by remember { mutableStateOf(false) }
    // GROOVE's own swing/feel at the moment SONG ▸ was tapped — carried
    // across so ARRANGE plans the groove this screen is actually showing,
    // not the raw base it would otherwise reload from disk itself.
    var arrangeSwing by remember { mutableStateOf<Int?>(null) }
    var arrangeFeel by remember { mutableFloatStateOf(0f) }
    var arrangeFeelTemplate by remember { mutableStateOf<GrooveFeel.Template?>(null) }
    /** ORBIT: GROOVE's other overlay, the circular sequencer — same lifecycle as [arrangeOpen]. */
    var orbitOpen by remember { mutableStateOf(false) }
    // SNIPS (Task 3): a shelf-level overlay, not KIT-scoped like PAD SHEET/
    // TAKES+BIN/PAD CAPTURE/GRAIN FIELD above — reachable from KitsScreen at
    // AppScreen.KITS (the shelf), one level up from those, so it's its own
    // boolean at this scope rather than sharing theirs.
    var snipsOpen by remember { mutableStateOf(false) }
    // X-RAY: same shelf-level shape as snipsOpen above, reached from
    // KitsScreen's own X-RAY ▸ INSPECT A FILE row — but there's no kit
    // it could belong to even in principle (the file picked is never
    // landed anywhere), so it carries its own reading rather than a bare
    // boolean.
    var xray by remember { mutableStateOf<XRayView?>(null) }
    // DOUBLES: shelf-level too, same shape as X-RAY — reachable from
    // KitsScreen's own DOUBLES ▸ row, a bare boolean since the screen
    // measures the shelf itself on mount.
    var doublesOpen by remember { mutableStateOf(false) }
    // DELETED KITS (Task 2 of the bin-restore plan): same shape as
    // `snipsOpen` above — a shelf-level overlay, not KIT-scoped, reachable
    // from `KitsScreen`'s own `DELETED KITS ▸` row at `AppScreen.KITS`.
    // `deletedKitsOpen` itself is declared earlier in this function (with
    // `binnedKitsCount`, above) since the shelf's refresh effect needs it as
    // a key; this comment marks where `snipsOpen`'s own sibling lives.
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
    // BREED (XX2 wired in): the kit BREED was pressed from, stashed here
    // while the user picks its cross partner — same shape as
    // pendingSnipAssign above, just a kit picking a kit instead of a snip
    // picking a pad. Cleared the moment the breed lands (`breed` below), or
    // by the MenuRow tab-switch reset below if the user gives up on the
    // pick without ever tapping a second kit.
    var pendingBreedWith by remember { mutableStateOf<KitShelf.Entry?>(null) }
    // A chop landed ONTO a bank asks KIT to open on that bank, once
    // (KitScreen's `bankRequest`); null again the moment KIT honours it.
    var kitBankRequest by remember { mutableStateOf<Int?>(null) }
    // DO IT AGAIN: what COPY LAST TREATMENT last lifted off a pad. A
    // clipboard, not a hand-off — deliberately NOT cleared by the tab-
    // switch reset below: pasting onto a pad in ANOTHER kit means going
    // through the shelf, and a clipboard that empties on the way there
    // would make the cross-kit case impossible. Replaced by the next COPY.
    var recipeClip by remember { mutableStateOf<RecipeReplay.Clip?>(null) }
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
    // RE-TRIM's request, TAPE's first rung — above SnipStore.newest, which
    // is exactly what `tapeOpenOverride` above can't promise. See
    // RetrimRequest for what clears it.
    var retrim by remember { mutableStateOf<RetrimRequest?>(null) }
    // A RE-TRIM lives on TAPE only. Every way off the screen — the menu
    // row, system back, INSTANT KIT, READ AS GROOVE, a SNIPS → TAPE that
    // re-enters — drops it here, in one place, rather than at each exit.
    LaunchedEffect(screen) {
        if (screen != AppScreen.TAPE) retrim = null
    }
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
    // The real DRM/dead-air refusal (INSIDE's SilenceWatch, below) — kept
    // separate from [micPermissionDenied] so a plain RECORD_AUDIO denial
    // never surfaces Spotify/DRM language describing a problem the user
    // doesn't have.
    var captureBlocked by remember { mutableStateOf(false) }
    // RECORD_AUDIO denied at the system dialog (`capturePermissionLauncher`
    // below) — its own state and its own honest dialog, never
    // [captureBlocked]'s DRM copy.
    var micPermissionDenied by remember { mutableStateOf(false) }
    // INSIDE's dead-air verdict (F3.4): the service watches the stream
    // and flips this while an opted-out app is playing into silence; the
    // TAPE JAM box shows once per verdict, and the verdict clears itself
    // the moment sound arrives.
    val blocked by MicSessionService.blocked.collectAsState()
    LaunchedEffect(blocked) {
        if (blocked) captureBlocked = true
    }
    // MIC's mute verdict: sustained exact digital silence on a plain mic
    // session (MicSessionService.micSilent's own KDoc has the false-
    // positive-vs-real-mute tradeoff). Toasted once per armed session, not
    // once per tick — `micSilent` can flip back to true after a real live
    // block clears it (a HAL blip, then more silence), and a genuinely
    // dead mic would otherwise re-toast every MIC_SILENCE_HOLD_SECONDS for
    // as long as the session stays armed. `micSilentToasted` resets on the
    // next ARM (armed flipping false, the reset in stopReaderAndRecord,
    // then true again), not on every recomposition, so navigating away and
    // back mid-session doesn't replay a warning already shown.
    val micSilent by MicSessionService.micSilent.collectAsState()
    var micSilentToasted by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (!armed) micSilentToasted = false
    }
    LaunchedEffect(micSilent) {
        if (micSilent && !micSilentToasted) {
            micSilentToasted = true
            toast = Copy.MIC_HEARING_NOTHING
        }
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
    // Sessions the reader thread itself ended — the mic (or, for ARM
    // INSIDE, the playback-capture stream) going silent for good, a
    // permission pulled mid-session, or any other read failure — as
    // opposed to `phoneStops` above, which is only the platform stopping
    // a MediaProjection. This used to announce nothing: the notification
    // and this UI both kept claiming ARMED with a flat level meter, which
    // reads exactly like a quiet room. Same baseline-by-count discipline
    // as `phoneStops` so a recreated Activity doesn't replay an old
    // failure. Says TAPE_STOPPED_ITSELF, not PHONE_STOPPED_TAPE: the
    // latter names the lock screen and the stop chip, which are the only
    // two ways a MediaProjection session ends but have nothing to do with
    // a dead AudioRecord — it would send the user to check the wrong
    // thing entirely.
    val sessionDied by MicSessionService.sessionDied.collectAsState()
    var sessionDiedSeen by remember { mutableStateOf(MicSessionService.sessionDied.value) }
    LaunchedEffect(sessionDied) {
        if (sessionDied != sessionDiedSeen) {
            sessionDiedSeen = sessionDied
            toast = Copy.TAPE_STOPPED_ITSELF
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
    // must not trip [micPermissionDenied].
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
            // A denied RECORD_AUDIO is not a DRM/dead-air refusal — see
            // [micPermissionDenied]'s own comment above for why this must
            // never fall into [captureBlocked].
            micPermissionDenied = true
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
        kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
        // The rooms' bin empties itself of what has slept past its days.
        withContext(Dispatchers.IO) { runCatching { shelf.sweepRooms() } }
        // Same promise, for deleted kits (Task 4): the shelf's own bin
        // empties itself of whatever DELETE put there more than 30 days ago.
        withContext(Dispatchers.IO) { runCatching { shelf.sweepDeletedKits() } }
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
        // Same promise, for deleted snips (name-and-find task): SNIPS's own
        // bin empties itself of whatever DELETE put there more than 30 days
        // ago — no kit write involved, so no KitWrites.mutex needed here.
        withContext(Dispatchers.IO) { runCatching { SnipStore.sweepBin(shelf.root) } }
        // Orphaned import-staging left by a crashed/killed import, and
        // regenerable derived output (share-sheet temp copies) — never a
        // live kit or snip. Exports are deliberately NOT swept here: see
        // sweepOrphanedStorage's KDoc.
        withContext(Dispatchers.IO) { runCatching { sweepOrphanedStorage(shelf.root, context.cacheDir) } }
    }

    /**
     * The shelf's sort toggle (name-and-find followups): persists the
     * choice like [PREF_SCHEME]/[PREF_PERSONALITY] above, then re-fetches
     * [kits] under the new order right away — every OTHER refresh in this
     * file already passes [shelfSort] into its own `shelf.list` call, so
     * this is only needed for the toggle's own immediate refresh, not to
     * keep future refreshes in step.
     */
    fun setShelfSort(sort: KitShelf.ShelfSort) {
        shelfSort = sort
        prefs.edit().putString(PREF_SHELF_SORT, sort.name).apply()
        scope.launch {
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
        }
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
                val local = withContext(Dispatchers.IO) { ShareInbox.copyToCache(context, uri, name, LANDING_MAX_BYTES) }
                try {
                    // Any ZIP sniffs the same by its magic bytes alone -
                    // Kind.MPC3's gzip never does, but a room and a kit
                    // file both do, and are told apart only by peeking
                    // inside, which is why this waits for the local copy
                    // above rather than deciding off the head bytes.
                    if (kind == ShelfImport.Kind.XPN && withContext(Dispatchers.IO) { RoomPackager.sniff(local) }) {
                        val room = withContext(Dispatchers.IO) { shelf.landRoom(local, name) }
                        ShareInbox.consume()
                        roomsRevision++
                        toast = Copy.roomLanded(room.name)
                        return@LaunchedEffect
                    }
                    val (entries, skipped) = withContext(Dispatchers.IO) { shelf.land(local, name) }
                    ShareInbox.consume()
                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                    // A clean landing keeps its toast; one with skips opens the
                    // box, which names each skipped kit and the door's reason.
                    val boxed = LandingNote.landed(name, entries.map { it.kit.name }, skipped)
                    if (boxed != null) openNote(boxed) else toast = Copy.landed(entries.size, skipped.size)
                    entries.firstOrNull()?.let { first ->
                        open = first
                        padSheetSlot = null
                        takesBinOpen = false
                        arrangeOpen = false
                        orbitOpen = false
                        screen = AppScreen.KIT
                    }
                    return@LaunchedEffect
                } finally {
                    local.delete()
                }
            }
            val landed = withContext(Dispatchers.IO) {
                val snip = MediaDecode.decode(context, uri)
                SnipStore.import(snip, context.filesDir, System.currentTimeMillis())
            }
            ShareInbox.consume()
            if (open == null) {
                open = withContext(Dispatchers.IO) { shelf.list(shelfSort) }.firstOrNull()
            }
            toast = Copy.imported(landed.seconds, landed.truncated)
            // A new capture landing outranks a RE-TRIM in flight (RetrimRequest).
            retrim = null
            importCount++
            padSheetSlot = null
            takesBinOpen = false
            arrangeOpen = false
            orbitOpen = false
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

    /**
     * Whether a SNIPS → PAD landing has somewhere to go: an empty pad on
     * bank A or B — both are pages the long-press fills (bank B round 2).
     * One predicate for every hint that says so, so a full bank A with
     * an empty B never reads as "THIS KIT IS FULL".
     */
    fun hasLandingPad(kit: com.snipsnap.kit.Kit): Boolean =
        (PadBanks.slots(0).first..PadBanks.slots(1).last).any { kit.pad(it) == null }

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
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            busy = null
            // A snip may be waiting for a home: the empty shelf's own copy
            // sends the user here to make a kit for it (EMPTY_SHELF_FOR_ASSIGN),
            // and `fresh` deliberately does not clear the hand-off. Saying
            // only FRESH_TAPE there dropped the thread on the one route that
            // copy points at, while every other way into a kit with a snip
            // armed says what to do next (September UAT, finding 12).
            toast = if (pendingSnipAssign != null) {
                Copy.snipLanding(hasLandingPad(entry.kit))
            } else {
                Copy.FRESH_TAPE
            }
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
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
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
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
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
        // A remix replaces bank B. The user's own pads there (capture,
        // SNIPS → PAD, a chop landed ONTO it) are not the remix's to
        // replace: `remixBankB` refuses, and this says so first, in the
        // app's words, instead of a TWINS FAILED with the model's.
        if (KitBuilderModel.ownPadsOnBankB(source.kit).isNotEmpty()) {
            toast = Copy.TWINS_KEEP_OWN
            return
        }
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
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            busy = null
            toast = if (hadTwins) Copy.TWINS_REROLLED else Copy.BANK_B_LIT
        }
    }

    /**
     * DUST ALL: every pad of the open kit dusted at `PadSheet.DUST_ALL_AMOUNT`
     * from its own tape, else the kit's (`DustPrints.tapeFor`) — the
     * per-pad door `KitBuilderModel.dustPad` once per pad under one
     * `KitWrites.mutex`, each tape's print read once. Layered and chained
     * pads are left as they are and counted, since every audio rewrite
     * refuses them. The same DUBBING…-shaped busy line as EVIL TWINS: the
     * prints and sixteen convolutions run offline.
     */
    fun dustAll() {
        val source = open ?: return
        if (busy != null) return
        if (DustPrints.kitTape(source.kit) == null) {
            toast = Copy.DUST_NO_TAPE
            return
        }
        val snipsDir = File(context.filesDir, SnipStore.DIR)
        busy = Copy.DUSTING_BUSY
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val m = KitBuilderModel.open(source.dir)
                        val prints = HashMap<String, Dust.Print?>()
                        var dusted = 0
                        var left = 0
                        for (pad in m.kit.pads.toList()) {
                            val tape = DustPrints.tapeFor(m.kit, pad) ?: continue
                            val print = prints.getOrPut(tape) { DustPrints.forTape(File(snipsDir, tape)) } ?: continue
                            try {
                                m.dustPad(pad.slot, PadSheet.DUST_ALL_AMOUNT, tape, print)
                                dusted++
                            } catch (e: IllegalArgumentException) {
                                left++
                            } catch (e: IllegalStateException) {
                                left++
                            }
                        }
                        if (dusted > 0) m.save()
                        Triple(KitShelf.Entry(source.dir, m.kit), dusted, left)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                busy = null
                toast = "DUST FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            busy = null
            val (entry, dusted, left) = result
            if (dusted == 0 && left == 0) {
                toast = Copy.DUST_NO_GHOSTS
                return@launch
            }
            if (open?.dir == source.dir) open = entry
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            toast = Copy.dustedAll(dusted, left)
        }
    }

    /**
     * BREED (XX2 wired in): arms the pick-a-partner hand-off and sends the
     * user to the shelf — the same "pick a kit" hop SNIPS → PAD already
     * uses (`pendingSnipAssign`), just a kit picking a kit instead of a
     * snip picking a pad. Nothing is written here; the cross itself
     * happens in [finishBreed] once a partner is actually tapped.
     */
    fun startBreed() {
        val source = open ?: return
        if (busy != null) return
        pendingBreedWith = source
        screen = AppScreen.KITS
    }

    /**
     * BREED's landing: fired by the shelf's own `onOpen` once a kit is
     * tapped while [pendingBreedWith] is armed (see the KITS branch's
     * wiring below). `:shell`'s tested `Breed.breed`, unmodified, crosses
     * [source]'s own recipes with [partner]'s into a new child kit beside
     * `source` on the shelf (`KitShelf.breed` picks the destination). The
     * whole call runs inside `KitWrites.mutex.withLock` — `Breed.breed`'s
     * own open→mutate→save (via `KitBuilderModel.create`) is exactly the
     * kind of kit write two concurrent presses must never race, the same
     * reasoning `setKey`/`evilTwins` above already follow. Same
     * DUBBING…-shaped busy line as EVIL TWINS: every crossed pad renders
     * offline.
     */
    fun finishBreed(source: KitShelf.Entry, partner: KitShelf.Entry) {
        pendingBreedWith = null
        if (busy != null) return
        busy = Copy.BREEDING_BUSY
        scope.launch {
            val (entry, report) = try {
                withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.breed(source, partner, Random.nextInt()) } }
            } catch (e: Exception) {
                busy = null
                toast = "BREED FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            busy = null
            toast = Copy.bred(entry.kit.name, report.crossed.size, report.audited.size)
            // The freshly bred kit is the whole point of the tap that
            // landed here — open it, the same instant-gratification
            // landing EVIL TWINS gets by staying on the kit it just changed.
            open = entry
            screen = AppScreen.KIT
        }
    }

    /**
     * CHOP ALL (XX3 wired in): every picked `.wav` through the same
     * auto-chop pipeline INSTANT KIT already uses (`InstantKit.build` —
     * `ChopReviewModel.chop` → `sendToGrid()` → `KitBuilderModel.fromChop`,
     * the exact chain SEND TO GRID and INSTANT KIT both already run), one
     * new kit per file, named after the file. Deliberately NOT
     * `ChopAllCommand.run`/`ChopCommand.chop` (`:cli`) themselves: that
     * pipeline decodes with the unbounded `WavReader.read`
     * (`ChopCommand.kt:109`) and fans out export/artwork files the shelf's
     * own kit reader never expects — exactly the OOM/scope-creep risk
     * `ConventionTest`'s Law 4 exists to keep out of `:app`. `InstantKit`
     * is the tested, already-shipped app-safe equivalent of the same idea;
     * this only adds the batch shape `ChopAllCommand` has and `InstantKit`
     * doesn't — one file's failure is named, never fatal, and one summary
     * toast covers the whole run.
     *
     * [uris] come straight off the multi-file picker below — arbitrary
     * `content://` URIs, not `File`s, so [ShareInbox.copyToCache] (already
     * tested, already used for the share-sheet's own single-file door)
     * makes each one real before it's decoded. One file at a time, fully
     * consumed (decoded, chopped, deleted) before the next copy reuses the
     * same cache slot — no batch-wide temp folder needed for that alone.
     */
    fun chopAll(uris: List<Uri>) {
        // A CHOP ALL tap answers "which files", not "which kit" — any
        // BREED pick still armed must not survive it. Cleared here, not
        // only in goToScreen's tab-switch reset, because launching (or
        // cancelling) the picker never goes through goToScreen at all;
        // without this, the shelf stays stuck reading "PICK A KIT TO
        // CROSS WITH X" and every kit row stays in pick mode after this
        // run finishes, for a hand-off the user has already moved past.
        pendingBreedWith = null
        if (uris.isEmpty() || busy != null) return
        busy = Copy.CHOP_ALL_BUSY
        scope.launch {
            var wavCount = 0
            var made = 0
            var failed = 0
            var skipped = 0
            try {
                withContext(Dispatchers.IO) {
                    shelf.root.mkdirs()
                    for (uri in uris) {
                        val displayName = ShareInbox.displayName(context, uri)
                        if (!displayName.endsWith(".wav", ignoreCase = true)) {
                            skipped++
                            continue
                        }
                        wavCount++
                        var local: File? = null
                        try {
                            local = ShareInbox.copyToCache(context, uri, displayName, CHOP_ALL_MAX_FILE_BYTES)
                            val snip = Cleanup.toMono(WavReader.readCapped(local, TAPE_LOAD_MAX_SEC).snip)
                            val kitName = shelf.freshName(local.nameWithoutExtension)
                            val kitDir = File(shelf.root, kitName)
                            KitWrites.mutex.withLock { InstantKit.build(snip, kitName, kitDir) }
                            made++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Law 3, and ChopAllCommand's own promise: named,
                            // never fatal - file forty-one must not stop
                            // file forty-two.
                            failed++
                        } finally {
                            local?.delete()
                        }
                    }
                }
            } finally {
                busy = null
            }
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            toast = if (wavCount == 0) Copy.CHOP_ALL_NO_WAVS else Copy.choppedAll(made, wavCount, skipped, failed)
        }
    }

    // CHOP ALL's own multi-file picker — ACTION_OPEN_DOCUMENT with multiple
    // selection, filtered to WAV up front (audio/* would also surface
    // .mp3/.m4a/.flac, every one of which [chopAll] can only decode as
    // "not a .wav" and count as skipped - a chooser full of files that
    // then all land in the SKIPPED count reads as broken, not honest).
    // [chopAll] still checks each name against `.wav` afterward
    // (ChopAllCommand's own rule), since a picker's MIME filter is a hint
    // to the chooser, not a guarantee of what it returns.
    val chopAllPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> chopAll(uris) }

    /**
     * X-RAY ▸ INSPECT A FILE: whatever the picker hands back is read, never
     * landed anywhere — `MpcXRay.read` doesn't write a byte, so there's no
     * shelf to refresh and no kit to open on the way out, unlike every
     * other picker in this file.
     */
    fun xray(uri: Uri?) {
        if (uri == null || busy != null) return
        busy = Copy.XRAY_BUSY
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { ShareInbox.displayName(context, uri) }
                val reading = withContext(Dispatchers.IO) {
                    val local = ShareInbox.copyToCache(context, uri, name, com.snipsnap.mpc3.MpcXRay.MAX_FILE_BYTES)
                    try {
                        com.snipsnap.mpc3.MpcXRay.read(local)
                    } finally {
                        local.delete()
                    }
                }
                xray = XRayView(name, reading)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = Copy.xrayFailed(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = null
            }
        }
    }
    // Any extension, unlike CHOP ALL's own WAV-only filter above: X-RAY
    // reads .xpm/.xpn/.xtd/.xty/.xpj alike, and MpcXRay.read itself is
    // what decides whether it recognizes the bytes - a MIME filter here
    // would only narrow what the chooser offers, never what this can read.
    val xrayPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> xray(uri) }

    /**
     * SNIPS → PAD's landing (Task 3): fired by the next empty-pad long-press
     * once a kit is open with [pendingSnipAssign] armed. `KitScreen.kt`
     * itself is unmodified for this — the KIT branch's own `onEmptyLongPress`
     * below intercepts before the press ever reaches `padCaptureSlot`/
     * `PadCaptureScreen`. Same open→classify→assign→save shape as
     * `PadCaptureScreen.commitToPad`, including the same `KitWrites.mutex` —
     * this writes the same `kit.json` that screen (and every other kit
     * mutator in this file) does. `source = SnipStore.provenanceTag(file)`
     * is exactly the provenance param Task 2's `KitBuilderModel.assign`
     * added; this is its first live caller. `provenanceTag` tags both
     * `"file"` (display) and, when it parses, `"capturedAtMillis"` (the
     * SNIPS shelf's USED badge's own durable key, unaffected by a later
     * rename — see `SnipStore.isUsedBy`'s own KDoc).
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
                    // `file` is a SNIP, already bounded by SnipStore.IMPORT_MAX_SEC (180s) or
                    // real-time mic capture — readCapped's 600s ceiling is pure defense in depth,
                    // not expected to ever bind (see ConventionTest's Law 4 KDoc).
                    val snip = Cleanup.toMono(WavReader.readCapped(file, TAPE_LOAD_MAX_SEC).snip)
                    val cls = Classifier.classify(snip).drumClass
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(target.dir)
                        // The frame count makes the tag a cut (the whole
                        // snip) RE-TRIM can open TAPE on later.
                        model.assign(
                            slot, snip, cls, cls.name.replace('_', ' '),
                            source = SnipStore.provenanceTag(file, snip.frameCount),
                        )
                        model.save()
                        model.kit
                    }
                }
                // Same identity guard as setKey/evilTwins above: a write
                // that outlived a tab-away-and-reopen must not weld itself
                // onto whichever kit is open now.
                if (open?.dir == target.dir) open = open?.copy(kit = updated)
                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                // PadBanks, not "A%02d": a landing on bank B is B01, not A17.
                toast = "SNIP PLACED ON PAD ${PadBanks.tag(slot)}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened.
                toast = "PLACE FAILED: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * BACK ONTO (docs/RETRIM.md §4): [range] of [request]'s tape, read at
     * the file's own rate and cut as CHOP's slice would be (`Retrim.cut`),
     * replaces the pad's audio through `KitBuilderModel.backOnto` — class,
     * name, level, pan, tune, choke, SHAPE carried; a treatment left with
     * the old file in the bin, and the toast says so. The save archives a
     * take, so UNDO is the TAKES room. Then PAD SHEET reopens on that pad,
     * whose provenance line now reads the new cut.
     */
    fun backOnto(request: RetrimRequest, range: IntRange) {
        if (busy != null) return
        busy = Copy.RETRIM_BUSY
        scope.launch {
            try {
                val (updated, treatment) = withContext(Dispatchers.IO) {
                    val mono = Cleanup.toMono(WavReader.readCapped(request.file, TAPE_LOAD_MAX_SEC).snip)
                    val snip = Retrim.cut(mono, range)
                    val start = range.first.coerceIn(0, mono.frameCount)
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(request.kitDir)
                        val old = model.pad(request.slot)
                            ?: error("${request.padLabel} is empty now - nothing to go back onto")
                        val treatment = Retrim.treatmentLeft(old)
                        model.backOnto(request.slot, snip, Retrim.tag(request.file.name, start, start + snip.frameCount))
                        model.save()
                        model.kit to treatment
                    }
                }
                // Same identity guard as assignPendingSnip: the write must
                // not weld itself onto whichever kit is open now.
                if (open?.dir == request.kitDir) open = open?.copy(kit = updated)
                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                toast = Copy.retrimLanded(request.padLabel, treatment)
                retrim = null
                if (open?.dir == request.kitDir) {
                    screen = AppScreen.KIT
                    padSheetSlot = request.slot
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                toast = Copy.TAPE_TOO_BIG
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened.
                toast = "RE-TRIM FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

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
                    // `file` is `tapeData.sourceFile` — the same file TAPE
                    // itself only ever decoded up to TAPE_LOAD_MAX_SEC, and
                    // `range` is a selection TAPE could only have produced
                    // against that capped view. Re-decoding with the same
                    // cap (not the unbounded `WavReader.read`) reproduces
                    // the identical frame count TAPE showed, so `range`
                    // stays valid — see TAPE_LOAD_MAX_SEC's own KDoc.
                    val snip = InstantKit.slice(WavReader.readCapped(file, TAPE_LOAD_MAX_SEC).snip, range)
                    val r = ReadGroove.read(snip, target.kit, file.nameWithoutExtension)
                    ReadGroove.land(target.dir, r)
                    r
                }
                toast = Copy.grooveRead(reading.hits, reading.bars, Math.round(reading.bpm))
                grooveReload++
                screen = AppScreen.GROOVE
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // TAPE_LOAD_MAX_SEC's own safety net, same as TapeScreen's
                // readMono: an Error, not an Exception, so it needs its own
                // catch or the process dies instead of this toast firing.
                toast = Copy.TAPE_TOO_BIG
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
                    // Same cap-preserving re-decode as readGroove above —
                    // `file` is `tapeData.sourceFile`, `range` only ever
                    // valid against TAPE's own capped view.
                    val snip = InstantKit.slice(WavReader.readCapped(file, TAPE_LOAD_MAX_SEC).snip, range)
                    val f = ReadGroove.feel(snip, target.dir, file.nameWithoutExtension)
                    ReadGroove.keepPocket(f.pocket, context.filesDir)
                    f
                }
                toast = Copy.feelStolen(felt.covered)
                grooveReload++
                screen = AppScreen.GROOVE
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // See readGroove's own catch above: an Error, not an
                // Exception, so it needs its own catch or the process dies.
                toast = Copy.TAPE_TOO_BIG
            } catch (e: IllegalArgumentException) {
                toast = Copy.feelRefused(e.message ?: "the ear refused")
            } catch (e: Exception) {
                toast = "FEEL FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    /**
     * INSTANT KIT (F2.2): the one tap on TAPE — the selection (or the whole
     * deck) chopped with the defaults and landed on the grid without the
     * review, the same DUBBING… shape as a fresh tape. CHOP can still open
     * the result later to argue with the chips.
     *
     * [hadSelection] is TAPE's answer, not this function's guess (September
     * UAT, finding 19): the toast has to say which of the two it chopped,
     * and [range] alone cannot tell them apart — an IN/OUT the user dragged
     * across the whole tape arrives here identical to no selection at all.
     */
    fun instantKit(file: File, range: IntRange, hadSelection: Boolean) {
        if (busy != null) return
        busy = "CHOPPING…"
        scope.launch {
            // `finally` owns the busy overlay: whichever way this leaves
            // (built, refused, or the scope cancelled underneath it), the
            // screen never stays stuck on CHOPPING….
            try {
                val (entry, result) = withContext(Dispatchers.IO) {
                    shelf.instantKit(file, range, File(context.filesDir, SnipStore.DIR))
                }
                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                toast = Copy.instantKit(result.sliceCount, result.chokeSet, wholeTape = !hadSelection)
                open = entry
                screen = AppScreen.KIT
            } catch (e: CancellationException) {
                // Leaving the screen is not a failure; let the scope have it.
                throw e
            } catch (e: OutOfMemoryError) {
                // shelf.instantKit re-decodes `tapeData.sourceFile` through
                // WavReader.readCapped now (see KitShelf.instantKit), but
                // this is still an Error, not an Exception, so it needs its
                // own catch — same as TapeScreen's readMono.
                toast = Copy.TAPE_TOO_BIG
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

    /**
     * SHARE on a room row: the room packed as one `.snip-room` and handed
     * to the chooser - [shareKit]'s own shape, one WAV plus its sidecar
     * instead of a whole kit.
     */
    fun shareRoom(room: Rooms.Room) {
        if (busy != null) return
        busy = Copy.PACKING_BUSY
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { shelf.packRoom(room, ShareOut.shareDir(context)) }
                busy = null
                toast = if (ShareOut.send(context, file, ShareOut.ZIP_MIME, room.name)) {
                    Copy.roomPacked(room.name)
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

    /**
     * EMPTY THE BIN NOW on the rooms bin: every forgotten room gone for
     * good, closing the gap the other three bins already closed
     * (`emptyKitBin`, `KitBuilderModel`'s own EMPTY THE BIN NOW, and
     * `SnipStore.emptyBin`) — `forgetRoom`/`restoreRoom`'s own busy-lock and
     * try/catch/finally shape, verbatim. The armed two-tap confirm itself
     * lives in `KitsScreen.kt` (`TakesBinScreen.kt`'s own `EMPTY_BIN_ARM_MS`
     * pattern) — this only runs once that second tap has already landed.
     */
    fun emptyRoomsBin() {
        if (busy != null) return
        busy = Copy.ROOM_BIN_EMPTY_BUSY
        scope.launch {
            try {
                withContext(Dispatchers.IO) { shelf.emptyRoomsBin() }
                roomsRevision++
                toast = Copy.roomBinEmptied
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = "EMPTY BIN FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    /**
     * DELETE ▸ BIN (Task 4) on a held kit row, confirmed: into the 30-day
     * bin, `KitShelf.deleteKit`'s own promise — the busy lock and
     * try/catch/finally shape are `forgetRoom`'s above, verbatim. The move
     * itself runs under `KitWrites.mutex` (whole-branch review finding):
     * PadSheet's debounced flush save can outlive its own screen via
     * `appScope`, so a save still in flight when the user tabs to KITS and
     * deletes that same kit must not race the directory move — the same
     * invariant every other open→mutate→save call site in this file
     * already keeps.
     *
     * Step 4's guard: [entry] IS reachable while it's the currently open
     * kit (`open` persists across a tab switch back to KITS, and `KitRow`
     * renders on `AppScreen.KITS` regardless of what's open) — so once the
     * move lands, if `open` was pointing at this exact directory, it no
     * longer names anything real and must be cleared; if some OTHER screen
     * was showing it (KIT, or one of its overlays), that screen is forced
     * back to the shelf, the same overlay-reset `MenuRow`'s own `onSelect`
     * runs on every ordinary tab switch — this bypasses `onSelect`, so it
     * repeats that reset by hand rather than leaving one armed.
     */
    fun deleteKit(entry: KitShelf.Entry) {
        if (busy != null) return
        busy = Copy.KIT_DELETE_BUSY
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.deleteKit(entry) } }
                if (ok) {
                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                    toast = Copy.kitDeleted(entry.kit.name)
                    if (open?.dir == entry.dir) {
                        open = null
                        if (screen != AppScreen.KITS) {
                            screen = AppScreen.KITS
                            padSheetSlot = null
                            takesBinOpen = false
                            padCaptureSlot = null
                            grainFieldSlot = null
                            spliceSlot = null
                            stackSlot = null
                            arrangeOpen = false
                            orbitOpen = false
                        }
                    }
                } else {
                    toast = Copy.KIT_DELETE_FAILED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = "DELETE FAILED: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    /**
     * RENAME (Task 4) on a held kit row, confirmed with the typed name:
     * `KitShelf.renameKit`'s own collision-fallback (`ShelfImport.moveOntoShelf`'s
     * shape) may freshen the name that lands, so the toast and the identity
     * guard below both read it off the returned `Entry`, never the typed string.
     *
     * Step 4's guard: same reachability as [deleteKit] above — `open` may
     * be pointing at exactly the directory that just moved to a new path.
     * `open?.dir == entry.dir` compares against the PRE-rename path
     * (`entry`, captured before the `await`); a match means `open` must be
     * repointed at the renamed `Entry`, or it keeps naming a directory that
     * no longer exists — the same identity-guard discipline every
     * `onKitUpdated` closure elsewhere in this file already applies.
     */
    fun renameKit(entry: KitShelf.Entry, newName: String) {
        if (busy != null) return
        busy = Copy.KIT_RENAME_BUSY
        scope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.renameKit(entry, newName) } }
                if (renamed != null) {
                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                    toast = Copy.kitRenamed(renamed.kit.name)
                    if (open?.dir == entry.dir) open = renamed
                } else {
                    toast = Copy.KIT_RENAME_FAILED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = "RENAME FAILED: ${e.message ?: e.javaClass.simpleName}"
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
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            toast = if (moved.isEmpty()) Copy.IN_KEY_NONE else Copy.inKey(moved.size, com.snipsnap.shell.KeyPicker.label(key))
        }
    }

    // Shared by MenuRow's own tab switch and the system Back fallback below —
    // one reset, one navigation, so the two can never drift apart on which
    // overlay/hand-off state a screen change must clear.
    fun goToScreen(target: AppScreen) {
        screen = target
        // Leaving KIT for another tab must not leave the
        // sheet armed to reopen on the same slot next time
        // KIT comes back into view.
        padSheetSlot = null
        takesBinOpen = false
        padCaptureSlot = null
        grainFieldSlot = null
        spliceSlot = null
        stackSlot = null
        arrangeOpen = false
        orbitOpen = false
        // SNIPS is shelf-level, not KIT-scoped, but the same
        // "leaving must not leave an overlay/hand-off armed"
        // reasoning applies: a tab switch away from KITS
        // mid-pick abandons the → PAD hand-off rather than
        // leaving KitsScreen stuck on "PICK A KIT FOR THIS
        // SNIP" forever.
        snipsOpen = false
        pendingSnipAssign = null
        // BREED's own pick, same reasoning as pendingSnipAssign just above:
        // a tab switch away from KITS mid-pick abandons the hand-off rather
        // than leaving KitsScreen stuck naming a cross partner forever.
        pendingBreedWith = null
        // ONTO's ask to open KIT on its bank is for the KIT that follows
        // it; a tab switch abandons it rather than letting it fire on
        // some later visit to some other kit.
        kitBankRequest = null
        tapeOpenOverride = null
        // DELETED KITS is shelf-level too — same reasoning
        // as SNIPS above: a tab switch away from KITS must
        // not leave this overlay armed to reopen on top of
        // whatever tab comes back into view later.
        deletedKitsOpen = false
        // X-RAY is shelf-level too, same reasoning again.
        xray = null
        // DOUBLES likewise.
        doublesOpen = false
    }

    // System Back, root policy: with no KIT-scoped or shelf-level overlay
    // open (every such overlay owns its own BackHandler, mounted only while
    // it's on screen, which always wins over this one — Compose's back
    // dispatcher is LIFO and those are registered deeper/later), Back acts
    // like a tab switch to the shelf. Three exclusions keep this from
    // overshooting a screen that owns its own one-level back door instead
    // of MenuRow's fixed tab set: AppScreen.KITS itself (nothing above it —
    // Back must fall through to the system default, which finishes the
    // Activity, the normal Android expectation for a root screen), SPLIT
    // (its own "◄ KIT" chip below, via `onExit`), and KEYS (its own
    // "◄ SHELF" chip, via `onBack`, which also silences the instrument
    // before leaving — this generic reset does not).
    val anyOverlayOpen = padSheetSlot != null || grainFieldSlot != null || spliceSlot != null || stackSlot != null || takesBinOpen ||
        padCaptureSlot != null || snipsOpen || deletedKitsOpen || doublesOpen || arrangeOpen || orbitOpen || xray != null
    BackHandler(
        enabled = !anyOverlayOpen && screen != AppScreen.KITS &&
            screen != AppScreen.SPLIT && screen != AppScreen.KEYS &&
            note == null && !captureBlocked && !micPermissionDenied,
    ) { goToScreen(AppScreen.KITS) }

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
                    onSelect = ::goToScreen,
                )
                Box(Modifier.weight(1f)) {
                    // Captured once, like `open`'s own `sheetEntry`/`songEntry`
                    // captures elsewhere in this same `when` — a delegated
                    // `mutableStateOf` property doesn't smart-cast, so the
                    // null check below needs a plain local to narrow.
                    val xrayView = xray
                    when (screen) {
                        AppScreen.KITS -> if (xrayView != null) {
                            XRayScreen(
                                fileName = xrayView.fileName,
                                reading = xrayView.reading,
                                onBack = { xray = null },
                            )
                        } else if (snipsOpen) {
                            SnipsScreen(
                                shelf = shelf,
                                onBack = { snipsOpen = false },
                                onToast = { toast = it },
                                onOpenInTape = { file ->
                                    snipsOpen = false
                                    tapeOpenOverride = file
                                    // An explicit SNIPS → TAPE choice, never
                                    // shadowed by an older RE-TRIM's tape.
                                    retrim = null
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
                        } else if (deletedKitsOpen) {
                            DeletedKitsScreen(
                                shelf = shelf,
                                onBack = { deletedKitsOpen = false },
                                onToast = { toast = it },
                                onRestored = {
                                    // The screen already owns its own toast
                                    // and its own list refresh — this only
                                    // needs to catch the shelf back up, the
                                    // same reload `deleteKit`/`renameKit`
                                    // already do after their own move. The
                                    // `binnedKitsCount` effect above is keyed
                                    // on `kits`, so this refresh alone is
                                    // also what re-reads the bin count.
                                    scope.launch {
                                        kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                    }
                                },
                            )
                        } else if (doublesOpen) {
                            DoublesScreen(
                                shelf = shelf,
                                onBack = { doublesOpen = false },
                                onToast = { toast = it },
                                onGoTo = { entry, slot ->
                                    // The shelf's own onOpen, plus the pad:
                                    // land on that kit with its PAD SHEET
                                    // already up on the double in question.
                                    doublesOpen = false
                                    open = entry
                                    screen = AppScreen.KIT
                                    padSheetSlot = slot
                                },
                            )
                        } else {
                            KitsScreen(
                                kits = kits,
                                instruments = instruments,
                                busy = busy != null,
                                armed = armed,
                                onOpen = { entry ->
                                    val breedSource = pendingBreedWith
                                    if (breedSource != null) {
                                        // BREED's pick, same shelf-level hand-off
                                        // shape as SNIPS → PAD just below, but this
                                        // one acts the instant a kit is tapped
                                        // instead of navigating in — there's
                                        // nothing else to configure once both
                                        // kits are chosen.
                                        if (entry.dir == breedSource.dir) {
                                            toast = Copy.BREED_SAME_KIT
                                        } else if (Breed.crossable(breedSource.kit, entry.kit).isEmpty()) {
                                            // Nothing for the coin on either
                                            // side: the child would be a copy
                                            // of A. Said now, the pick still
                                            // armed for another kit.
                                            toast = Copy.BREED_NOTHING_TO_CROSS
                                        } else {
                                            finishBreed(breedSource, entry)
                                        }
                                    } else {
                                        open = entry
                                        screen = AppScreen.KIT
                                        // A kit opened while a SNIPS → PAD pick is
                                        // still pending: tell the user what the
                                        // next empty-pad long-press will do, since
                                        // `KitScreen` itself carries no hint banner
                                        // of its own for this mode. KitScreen can
                                        // show bank B now (September UAT, finding
                                        // 11) but always opens on bank A, and
                                        // nothing here fills an upper bank anyway,
                                        // so an "empty pad" instruction is only
                                        // actually followable if bank A has one; a
                                        // kit that's already full there has nothing
                                        // for the long-press to catch (the v1
                                        // "empty pads only" scope this task's
                                        // brief calls out), so the hint says so
                                        // instead of pointing at a pad that
                                        // doesn't exist.
                                        if (pendingSnipAssign != null) {
                                            toast = Copy.snipLanding(hasLandingPad(entry.kit))
                                        } else {
                                            // Teach the one gesture that opens PAD
                                            // SHEET, while it's still undiscovered.
                                            // Only worth saying when there's a
                                            // filled pad to hold, and never once
                                            // they've found it — see
                                            // PREF_PAD_SHEET_HINTS. Yields to the
                                            // pending-snip hint above rather than
                                            // fighting it for the one toast slot.
                                            val shown = prefs.getInt(PREF_PAD_SHEET_HINTS, 0)
                                            val hasFilledPad = entry.kit.pads.isNotEmpty()
                                            // No showing limit any more (September UAT,
                                            // finding 5): it used to stop after three, so
                                            // three dismissals while busy with something
                                            // else cost the user PAD SHEET permanently.
                                            // It now runs until they actually open the
                                            // sheet, which is the only event that means
                                            // they found it. KIT's legend is the real
                                            // backstop; this is just the nudge.
                                            if (shown != PAD_SHEET_FOUND && hasFilledPad) {
                                                toast = Copy.PAD_SHEET_HINT
                                                prefs.edit().putInt(PREF_PAD_SHEET_HINTS, shown + 1).apply()
                                            }
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
                                onChopAll = { chopAllPickerLauncher.launch(arrayOf("audio/wav", "audio/x-wav")) },
                                onXRay = { xrayPickerLauncher.launch(arrayOf("*/*")) },
                                onDoubles = { doublesOpen = true },
                                onSnips = { snipsOpen = true },
                                assigningSnip = pendingSnipAssign != null,
                                breedingFrom = pendingBreedWith,
                                rooms = rooms,
                                onForgetRoom = ::forgetRoom,
                                onShareRoom = ::shareRoom,
                                binnedRooms = binnedRooms,
                                onRestoreRoom = ::restoreRoom,
                                onEmptyRoomsBin = ::emptyRoomsBin,
                                onDeleteKit = ::deleteKit,
                                onRenameKit = ::renameKit,
                                binnedKitsCount = binnedKitsCount,
                                onDeletedKits = { deletedKitsOpen = true },
                                shelfSort = shelfSort,
                                onToggleSort = { setShelfSort(if (shelfSort == KitShelf.ShelfSort.RECENT) KitShelf.ShelfSort.ALPHA else KitShelf.ShelfSort.RECENT) },
                            )
                        }
                        AppScreen.KIT -> {
                            val sheetSlot = padSheetSlot
                            val sheetEntry = open
                            val fieldSlot = grainFieldSlot
                            val spliceSlotState = spliceSlot
                            val stackSlotState = stackSlot
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
                                // Checked before PAD SHEET below, same
                                // reasoning as GRAIN FIELD above: opening
                                // SPLICE clears `padSheetSlot` at the same
                                // time it sets `spliceSlot` (see onSplice
                                // below), so the two are already mutually
                                // exclusive - this ordering is belt-and-
                                // suspenders should that ever not hold.
                                spliceSlotState != null && sheetEntry != null -> TapeSpliceScreen(
                                    entry = sheetEntry,
                                    slot = spliceSlotState,
                                    onBack = { spliceSlot = null },
                                    onToast = { toast = it },
                                    onKitUpdated = { updatedKit ->
                                        if (open?.dir == sheetEntry.dir) open = open?.copy(kit = updatedKit)
                                        scope.launch {
                                            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                        }
                                    },
                                )
                                // STACK, same ordering reasoning as SPLICE
                                // above: onStack clears `padSheetSlot` as it
                                // sets `stackSlot`.
                                stackSlotState != null && sheetEntry != null -> StackTakesScreen(
                                    entry = sheetEntry,
                                    slot = stackSlotState,
                                    onBack = { stackSlot = null },
                                    onToast = { toast = it },
                                    onKitUpdated = { updatedKit ->
                                        if (open?.dir == sheetEntry.dir) open = open?.copy(kit = updatedKit)
                                        scope.launch {
                                            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                        }
                                    },
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
                                        // RE-TRIM ▸ (docs/RETRIM.md §3): resolve
                                        // the pad's own tape first. A refusal
                                        // toasts its reason and stays on the
                                        // sheet — the reason is the useful
                                        // part, so the button is never grey.
                                        val pad = sheetEntry.kit.pad(sheetSlot)
                                        val resolved = pad?.let {
                                            Retrim.of(it, File(context.filesDir, SnipStore.DIR))
                                        } ?: Retrim.Refused(Copy.RETRIM_NO_TAPE)
                                        when (resolved) {
                                            is Retrim.Refused -> toast = resolved.reason
                                            is Retrim.Ready -> {
                                                retrim = RetrimRequest(
                                                    sheetEntry.dir,
                                                    sheetSlot,
                                                    PadNoteMap.labelForPad(sheetSlot),
                                                    resolved.file,
                                                    resolved.cut,
                                                    colorHex = pad?.colorHex,
                                                    drumClass = pad?.drumClass ?: com.snipsnap.audio.DrumClass.UNKNOWN,
                                                )
                                                padSheetSlot = null
                                                screen = AppScreen.TAPE
                                            }
                                        }
                                    },
                                    onGrainField = { slot ->
                                        // GRAIN closes PAD SHEET on the way
                                        // in — the two overlays never render
                                        // at once (see the `when` ordering
                                        // comment above).
                                        padSheetSlot = null
                                        grainFieldSlot = slot
                                    },
                                    onSplice = { slot ->
                                        // SPLICE closes PAD SHEET on the way
                                        // in, same reasoning as GRAIN above.
                                        padSheetSlot = null
                                        spliceSlot = slot
                                    },
                                    onStack = { slot ->
                                        padSheetSlot = null
                                        stackSlot = slot
                                    },
                                    clipboard = recipeClip,
                                    onRecipeCopied = { recipeClip = it },
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
                                            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
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
                                        // makes KIT's engine reload its bank
                                        // (`LaunchedEffect(entry.kit)`),
                                        // so a restored sample is heard,
                                        // not the stale cached one — but only
                                        // onto the kit this restore actually
                                        // ran against (see the identity guard
                                        // comment on PAD SHEET's own
                                        // onKitUpdated above).
                                        if (open?.dir == sheetEntry.dir) open = open?.copy(kit = updatedKit)
                                        scope.launch {
                                            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
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
                                            // so KIT's engine reloads the pad GRAB
                                            // just filled, not a stale cached (empty)
                                            // sample — guarded the same way, against
                                            // `sheetEntry` captured at this
                                            // composition, not whatever `open` is now.
                                            if (open?.dir == sheetEntry.dir) open = open?.copy(kit = updatedKit)
                                            scope.launch {
                                                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
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
                                    onLongPress = { slot ->
                                        padSheetSlot = slot
                                        // Found it — the hint has done its job and
                                        // retires for good. This is the only event
                                        // that proves discovery, which is why it is
                                        // now the only thing that stops the nudge.
                                        prefs.edit().putInt(PREF_PAD_SHEET_HINTS, PAD_SHEET_FOUND).apply()
                                    },
                                    onTakesBin = { takesBinOpen = true },
                                    onTexture = ::texture,
                                    onSetKey = ::setKey,
                                    onInKey = ::inKey,
                                    onTwins = ::evilTwins,
                                    onBankEmpty = { toast = Copy.bankEmpty(PadBanks.letter(it)) },
                                    bankRequest = kitBankRequest,
                                    onBankRequestConsumed = { kitBankRequest = null },
                                    onBreed = ::startBreed,
                                    // A second kit to cross with has to
                                    // already be on the shelf — BREED can't
                                    // offer a pick with nothing else there.
                                    canBreed = kits.size > 1,
                                    onShare = ::shareKit,
                                    onSplit = { screen = AppScreen.SPLIT },
                                    onDustAll = ::dustAll,
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
                                // Same for a RE-TRIM: a plain KEEP is a
                                // different intent (RetrimRequest).
                                retrim = null
                            },
                            onInstantKit = ::instantKit,
                            onReadGroove = ::readGroove,
                            onStealFeel = ::stealFeel,
                            reloadRequest = importCount,
                            retrim = retrim,
                            onBackOnto = ::backOnto,
                            onCaptureLanded = { retrim = null },
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
                            // Finding 23: three facts the app already kept and
                            // never showed. Read straight from the same prefs
                            // EXPORT writes - one owner each, no second copy.
                            exportFormatLabel = prefs.getString(PREF_EXPORT_FORMAT, null)
                                ?.let { ExportFormat.byId(it) }?.cyclerLabel,
                            filesWhere = exportsWhere,
                            cardName = prefs.getString(PREF_CARD_TREE, null)
                                ?.let { Copy.cardName(Uri.parse(it).lastPathSegment) },
                            onHelp = { screen = AppScreen.HELP },
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
                                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                }
                            },
                            onLandedOnto = { updated, bank ->
                                open = updated
                                kitBankRequest = bank
                                screen = AppScreen.KIT
                                scope.launch {
                                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
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
                                    // KIT's engine reloads the pad SEND TO PAD
                                    // just replaced, not a stale cached sample.
                                    if (open?.dir == synthEntry?.dir) open = open?.copy(kit = updatedKit)
                                    scope.launch {
                                        kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                    }
                                },
                                // App()'s own scope — same reasoning as PAD SHEET/PAD
                                // CAPTURE's own appScope: SEND TO PAD's write must survive
                                // a MenuRow tab switch, not be cancelled by it.
                                appScope = scope,
                            )
                        }
                        // SPLIT: one pad on three faders. Its prints land the
                        // same two ways SURFACE's do, so the same two hooks.
                        AppScreen.SPLIT -> SplitScreen(
                            entry = open,
                            onToast = { toast = it },
                            onPrinted = { importCount++ },
                            onKitUpdated = { updatedKit ->
                                open = open?.copy(kit = updatedKit)
                                scope.launch {
                                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                }
                            },
                            onExit = { screen = AppScreen.KIT },
                        )
                        AppScreen.SURFACE -> SurfaceScreen(
                            entry = open,
                            onToast = { toast = it },
                            // A print is a snip on the shelf: the same reload
                            // request a share-sheet import raises.
                            onPrinted = { importCount++ },
                            // A print on a pad: bump `open.kit`'s identity so
                            // KIT's engine and the surface reload it.
                            onKitUpdated = { updatedKit ->
                                open = open?.copy(kit = updatedKit)
                                scope.launch {
                                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                }
                            },
                        )
                        AppScreen.PLAY -> PlayScreen(entry = open)
                        AppScreen.HELP -> HelpScreen()
                        AppScreen.GROOVE -> {
                            val songEntry = open
                            if (orbitOpen && songEntry != null) {
                                OrbitScreen(
                                    entry = songEntry,
                                    kitsRoot = shelf.root,
                                    onBack = { orbitOpen = false },
                                    onToast = { toast = it },
                                )
                            } else if (arrangeOpen && songEntry != null) {
                                ArrangeScreen(
                                    entry = songEntry,
                                    onBack = { arrangeOpen = false },
                                    onToast = { toast = it },
                                    swingPercent = arrangeSwing,
                                    feel = arrangeFeel,
                                    feelTemplate = arrangeFeelTemplate,
                                )
                            } else {
                                GrooveScreen(
                                    entry = open,
                                    // App's own scope — the same one PadSheetScreen's
                                    // teardown save and ExportScreen's dub write use —
                                    // so a pending debounced E save survives a MenuRow
                                    // tab switch instead of being cancelled by it.
                                    appScope = scope,
                                    onToast = { toast = it },
                                    reloadRequest = grooveReload,
                                    onArrange = { swing, f, tpl -> arrangeSwing = swing; arrangeFeel = f; arrangeFeelTemplate = tpl; arrangeOpen = true },
                                    onOrbit = { orbitOpen = true },
                                )
                            }
                        }
                        AppScreen.ORBIT -> {
                            // The menu row's own door to the rings. GROOVE's
                            // ORBIT ▸ still opens the same screen as an overlay;
                            // this one needs no groove and no scrolling, only a kit.
                            val orbitEntry = open
                            if (orbitEntry == null) {
                                Box(Modifier.fillMaxSize().lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
                                    TapeText(Copy.NO_KIT_FOR_ORBIT, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
                                }
                            } else {
                                OrbitScreen(
                                    entry = orbitEntry,
                                    kitsRoot = shelf.root,
                                    onBack = { screen = AppScreen.GROOVE },
                                    onToast = { toast = it },
                                )
                            }
                        }
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
            note?.let { n ->
                val dismissNote = { note = null }
                MessageBox(n, onDismiss = dismissNote)
                // The topmost thing on screen when it's up — registered
                // last (after every screen-level handler above), so it
                // wins Back over all of them, matching what's drawn on top.
                BackHandler(onBack = dismissNote)
            }
            if (captureBlocked) {
                val dismissBlocked = { captureBlocked = false }
                BlockedDialog(Copy.CAPTURE_BLOCKED, Copy.CAPTURE_BLOCKED_BUTTON, onDismiss = dismissBlocked)
                BackHandler(onBack = dismissBlocked)
            }
            if (micPermissionDenied) {
                val dismissDenied = { micPermissionDenied = false }
                BlockedDialog(Copy.MIC_PERMISSION_DENIED, Copy.CAPTURE_BLOCKED_BUTTON, onDismiss = dismissDenied)
                BackHandler(onBack = dismissDenied)
            }
        }
    }
}

/**
 * The house modal shape shared by two unrelated capture refusals — a real
 * DRM/dead-air block ([Copy.CAPTURE_BLOCKED]) and a plain RECORD_AUDIO
 * denial ([Copy.MIC_PERMISSION_DENIED]) — that must never share the same
 * *words* (a Spotify/DRM message describing a permission problem is a
 * law-3 violation) even though they share this presentation: scrim `Box` +
 * `raisedBevel` `Column`, an inner `tapeClick {}` swallowing taps so the
 * scrim's dismiss doesn't fire through, rather than a Material
 * `AlertDialog` (TapeOS never uses Material's own chrome, see `Chrome.kt`'s
 * `TapeText` KDoc). [message] and [buttonLabel] are the only things that
 * differ between callers.
 */
@Composable
private fun BlockedDialog(message: String, buttonLabel: String, onDismiss: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // Labelled with the same word the visible dismiss button
            // below uses — the scrim does exactly what that button does,
            // and has no descendant text of its own to fall back on.
            .tapeClick(label = buttonLabel, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                .tapeClick(label = null) { }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText(message, TapeType.lcdSmall, scheme.ink.tape, maxLines = 4)
            PrimaryAction(label = buttonLabel, enabled = true, onClick = onDismiss)
        }
    }
}
