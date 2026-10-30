package com.snipsnap.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeTheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.rememberDeskBrush
import com.snipsnap.app.theme.rememberReduceMotion
import com.snipsnap.app.theme.tape
import com.snipsnap.app.theme.windowFrame
import com.snipsnap.app.ui.AppJobs
import com.snipsnap.app.ui.AppScreen
import com.snipsnap.app.ui.ArrangeScreen
import com.snipsnap.app.ui.BenchNoteDialog
import com.snipsnap.app.ui.ChopScreen
import com.snipsnap.app.ui.BinScreen
import com.snipsnap.app.ui.CaptureSheet
import com.snipsnap.app.ui.ChoiceSheet
import com.snipsnap.app.ui.SheetRow
import com.snipsnap.app.ui.DoublesScreen
import com.snipsnap.app.ui.NoKitPanel
import com.snipsnap.app.ui.ExportScreen
import com.snipsnap.app.ui.ExportSession
import com.snipsnap.app.ui.GROOVE_SWING_DEFAULT
import com.snipsnap.app.ui.GrainFieldScreen
import com.snipsnap.app.ui.GrooveScreen
import com.snipsnap.app.ui.HelpScreen
import com.snipsnap.app.ui.KitLoading
import com.snipsnap.app.ui.KitScreen
import com.snipsnap.app.ui.KeysScreen
import com.snipsnap.app.ui.KitsScreen
import com.snipsnap.app.ui.LocalBusySink
import com.snipsnap.app.ui.LocalJobs
import com.snipsnap.app.ui.LoopBench
import com.snipsnap.app.ui.MakeList
import com.snipsnap.app.ui.MessageBox
import com.snipsnap.app.ui.MidiScreen
import com.snipsnap.app.ui.Dock
import com.snipsnap.app.ui.DoorChip
import com.snipsnap.app.ui.GearSheet
import com.snipsnap.app.ui.InstrumentsBench
import com.snipsnap.app.ui.PlaceRow
import com.snipsnap.app.ui.OrbitScreen
import com.snipsnap.app.ui.OrbitStep
import com.snipsnap.app.ui.PadCaptureScreen
import com.snipsnap.app.ui.PadSheetScreen
import com.snipsnap.app.ui.PREF_CARD_TREE
import com.snipsnap.app.ui.PREF_EXPORT_FORMAT
import com.snipsnap.app.ui.PrimaryAction
import com.snipsnap.app.ui.PropertiesScreen
import com.snipsnap.app.ui.SnipsScreen
import com.snipsnap.app.ui.SplitScreen
import com.snipsnap.app.ui.StageRow
import com.snipsnap.app.ui.SnapScreen
import com.snipsnap.app.ui.SurfaceScreen
import com.snipsnap.app.ui.SynthScreen
import com.snipsnap.app.ui.TakesBinScreen
import com.snipsnap.app.ui.TAPE_LOAD_MAX_SEC
import com.snipsnap.app.ui.TapeScreen
import com.snipsnap.app.ui.StackTakesScreen
import com.snipsnap.app.ui.TapeSpliceScreen
import com.snipsnap.app.ui.TapeText
import com.snipsnap.app.ui.TitleBar
import com.snipsnap.app.ui.rememberDock
import com.snipsnap.app.ui.rememberNavigation
import com.snipsnap.app.ui.XRayScreen
import com.snipsnap.app.ui.tapeClick
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Dust
import com.snipsnap.audio.Scales
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Project
import com.snipsnap.kit.ProjectStore
import com.snipsnap.kit.GrooveStore
import com.snipsnap.loop.Arp
import com.snipsnap.loop.DroneFit
import com.snipsnap.loop.Orbit
import com.snipsnap.loop.OrbitBrush
import com.snipsnap.loop.OrbitHit
import com.snipsnap.loop.OrbitPresets
import com.snipsnap.loop.OrbitSet
import com.snipsnap.loop.OrbitStore
import com.snipsnap.loop.PatternOrbit
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.loop.SessionStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.shell.BenchExport
import com.snipsnap.shell.BenchNotes
import com.snipsnap.shell.Breed
import com.snipsnap.shell.Busy
import com.snipsnap.shell.Bench
import com.snipsnap.shell.Copy
import com.snipsnap.shell.DockModel
import com.snipsnap.shell.Door
import com.snipsnap.shell.Location
import com.snipsnap.shell.Names
import com.snipsnap.shell.Place
import com.snipsnap.shell.ShelfModel
import com.snipsnap.shell.Stage
import com.snipsnap.shell.DustPrints
import com.snipsnap.shell.InstantKit
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.LabelledHits
import com.snipsnap.shell.LandingNote
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadSheet
import com.snipsnap.shell.PadSheetBoxes
import com.snipsnap.shell.Places
import com.snipsnap.shell.ReadGroove
import com.snipsnap.shell.RecipeReplay
import com.snipsnap.shell.CatchModel
import com.snipsnap.shell.Retrim
import com.snipsnap.shell.RoomPackager
import com.snipsnap.shell.Rooms
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.ShelfImport
import com.snipsnap.shell.SirenDroneMaker
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.StarterKits
import com.snipsnap.shell.TextureKits
import com.snipsnap.shell.UserPresets
import com.snipsnap.shell.Workshop
import com.snipsnap.synth.Photo
import com.snipsnap.synth.PhotoChord
import com.snipsnap.synth.PhotoKit
import com.snipsnap.synth.SirenDrone
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val TAG = "App"

internal const val PREFS = "tapeos"
private const val PREF_SCHEME = "scheme"
private const val PREF_TEACH = "teach"
/** HAPTICS (SETUP): the motor under pads, steppers and landings - ON unless the player turned it off. */
private const val PREF_HAPTICS = "haptics"
/** The WORKSHOP (`docs/WORKSHOP.md`): open once the knock on SETUP's title lands, remembered like the scheme until its own CLOSE. */
private const val PREF_WORKSHOP = "workshop"
/** The shelf's sort toggle (name-and-find followups) — [KitShelf.ShelfSort], remembered like the scheme. */
private const val PREF_SHELF_SORT = "shelf_sort"
/** The Bubble's overlay-permission offer (Task 5) — asked once, ever. */
private const val PREF_OVERLAY_ASKED = "bubble_overlay_asked"

/**
 * The benches that have no back chip of their own, so the frame draws one
 * from `Navigation.backChip`. The tab-era screens never needed a chip —
 * a tab is left by another tab — and SETUP and HELP were tabs. The list
 * shrinks as each bench's step of the plan gives it its own header: MAKE's
 * three labs gain `◄ MAKE` with the MAKE step, the gear's three stay here.
 */
private val FRAME_CHIP_BENCHES = setOf(Bench.SETUP, Bench.HELP, Bench.MIDI, Bench.SYNTH, Bench.SNAP, Bench.SURFACE)

/** About how many characters one line of the dock's `now` region holds on a phone; it draws two. */
private const val DOCK_LINE_CHARS = 44

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

/**
 * CATCH A HIT (docs/CATCH.md): one caught cut, ready to land on [kitDir]'s
 * pad — [cut] is `Retrim.cut` of [caught]'s range, cut by TAPE off the
 * tape it already holds so App never re-reads the file per catch.
 */
data class CatchLanding(
    val kitDir: File,
    val tape: File,
    val cut: com.snipsnap.audio.Snip,
    val caught: com.snipsnap.shell.CatchModel.Caught,
)

/**
 * One CATCH A HIT session's bookkeeping, App-side: the writes are queued
 * on [chain] in release order (each landing joins the one before it, so
 * two quick passes on one slot land in the order they were let go, and
 * `open` is published in that order too), and [landed] is the slots that
 * actually landed — the count DONE speaks from, since a write can fail
 * after the screen's model counted the catch.
 */
private class CatchSession {
    var chain: Job? = null
    val landed = LinkedHashSet<Int>()
}

/** X-RAY's own state: the picked file's display name and what [MpcXRay.read] made of it. Non-null IS "the screen is open" — there is no separate boolean to keep in sync with it. */
data class XRayView(val fileName: String, val reading: com.snipsnap.mpc3.MpcXRay.Reading)

/**
 * The whole M0 app: the SNIPSNAP window on its desk, the menu row,
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
            (Exports.dir(context) ?: context.filesDir).absolutePath
        }
    }

    var schemeId by remember {
        mutableStateOf(
            prefs.getString(PREF_SCHEME, null)
                ?.let { saved -> SchemeId.entries.firstOrNull { it.name == saved } }
                ?: Schemes.DEFAULT.id,
        )
    }
    val scheme = Schemes[schemeId]

    // SHELF SORT (name-and-find followups): RECENT by default — persisted
    // like scheme above. `setShelfSort` both writes the pref
    // and re-fetches `kits` under the new order immediately, the same
    // "flip it, see it" shape STARTER/SCHEME's own toggles already have.
    var shelfSort by remember {
        mutableStateOf(
            prefs.getString(PREF_SHELF_SORT, null)
                ?.let { saved -> KitShelf.ShelfSort.entries.firstOrNull { it.name == saved } }
                ?: KitShelf.ShelfSort.RECENT,
        )
    }

    // Where you are: the four-place shape's navigation model
    // (`docs/SCREEN_PLAN_2026_10.md` §1, §6.1), observed. `location` is read
    // once here so every branch below sees the same value for one
    // composition; the `when` that draws the screen switches on it.
    val nav = rememberNavigation()
    val location = nav.location
    var kits by remember { mutableStateOf<List<KitShelf.Entry>>(emptyList()) }
    // Whether the shelf's lists have been read once: `kitsLoaded` for the kit
    // list, `shelfListsLoaded` for the projects, the rooms and the rooms in the
    // bin (the shelf effect below, which sets it only after the last of the
    // three). An empty list before that is "not read yet", not an empty
    // library, and the shelf says LOADING instead of NOTHING TAPED YET until
    // both are true. All three lists matter, not only the projects: the empty
    // panel is drawn when kits, rooms and binned rooms are all empty, so a
    // flag set while rooms were still on their way would let a library with
    // no kits but some rooms flash it. Set once and never cleared: a later
    // re-read of a list that already holds something is a refresh, and
    // flashing the panel over it on every kit change would be the flicker
    // this removes, moved.
    var kitsLoaded by remember { mutableStateOf(false) }
    var shelfListsLoaded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<KitShelf.Entry?>(null) }
    // The dock (§1): the strip at the foot of every screen. Its `now` is
    // the line the toast used to say, and the door the toast used to carry
    // (J10). They are one value in `DockModel.Now`, so a plain `say` after
    // an `offer` keeps the door under new words and a door can never be
    // stranded under someone else's sentence — which is what the old two
    // vars (`toast`, `offered`) claimed and had to be compared to prove.
    // Nothing here is timed: the line stays until the next one, the door
    // until it is taken. A line is scoped to where it was said (stamped
    // from `nav.location` at the write) and rests when the user moves to
    // another place or stage; a door and its words survive the move.
    val dock = rememberDock { nav.location }
    // HAPTICS (SETUP): the motor under a pad hit, the arm, a stepper and a
    // landing. Remembered like the scheme, ON by default. The gate is read
    // at each call: off by the switch, and off while the dock's capture
    // light says ARMED - the phone's mic hears its own motor (Haptics' KDoc).
    var hapticsOn by remember { mutableStateOf(prefs.getBoolean(PREF_HAPTICS, true)) }
    val haptics = remember(context) {
        Haptics.of(context) { hapticsOn && dock.capture !is DockModel.Capture.Armed }
    }
    // REDUCED MOTION: the system's own "remove animations", kept current while
    // the app is up (it can change under us; see `rememberReduceMotion`). The
    // drawings that loop (the busy bar, the spinner) read it through
    // `LocalReduceMotion` and hold still when it is on.
    val reduceMotion = rememberReduceMotion()
    // MIDI OUT (SETUP): the hardware switch, remembered like HAPTICS, on by
    // default (MIDI_OUT_DEFAULT). `MidiOut.install` reads the same pref; pushing the value
    // once here makes the two agree even if install ran before it was written.
    var midiOutOn by remember { mutableStateOf(prefs.getBoolean(PREF_MIDI_OUT, MIDI_OUT_DEFAULT)) }
    LaunchedEffect(Unit) { MidiOut.hardwareEnabled = midiOutOn }
    val midiSupported = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)
    }
    // The honest little message box (wave FFF): what a landing, a backup
    // or a refusal has to say beyond the dock's one line. Stays until read.
    var note by remember { mutableStateOf<LandingNote.Note?>(null) }
    fun openNote(n: LandingNote.Note) {
        note = n
    }

    /**
     * Where each screen the app navigates by name lands in the four-place
     * shape — `docs/SCREEN_PLAN_2026_10.md` §2's table, as code. A tab-era
     * screen is a place with a stage, or a bench on one. The gear's three
     * (SETUP, HELP, MIDI) and KEYS open as a bench over wherever you are, so Back
     * returns there; the rest land somewhere definite.
     *
     * Exhaustive on purpose: a new [AppScreen] without a landing here does
     * not compile, which is the same guard the enum's own KDoc describes.
     */
    fun locationOf(target: AppScreen): Location = when (target) {
        AppScreen.KITS -> Location(Place.SHELF)
        AppScreen.TAPE -> Location(Place.TAPE, Stage.DECK)
        AppScreen.CHOP -> Location(Place.TAPE, Stage.CUT)
        AppScreen.KIT -> Location(Place.PROJECT, Stage.PADS)
        AppScreen.GROOVE -> Location(Place.PROJECT, Stage.BEAT)
        AppScreen.EXPORT -> Location(Place.PROJECT, Stage.OUT)
        AppScreen.ORBIT -> Location(Place.PROJECT, Stage.BEAT, listOf(Bench.ORBIT))
        AppScreen.SPLIT -> Location(Place.PROJECT, Stage.PADS, listOf(Bench.SPLIT))
        AppScreen.SYNTH -> Location(Place.MAKE, benches = listOf(Bench.SYNTH))
        AppScreen.SNAP -> Location(Place.MAKE, benches = listOf(Bench.SNAP))
        AppScreen.SURFACE -> Location(Place.MAKE, benches = listOf(Bench.SURFACE))
        AppScreen.KEYS -> nav.location.copy(benches = nav.location.benches + Bench.KEYS)
        AppScreen.PROPERTIES -> nav.location.copy(benches = nav.location.benches + Bench.SETUP)
        AppScreen.HELP -> nav.location.copy(benches = nav.location.benches + Bench.HELP)
        AppScreen.MIDI -> nav.location.copy(benches = nav.location.benches + Bench.MIDI)
    }

    /**
     * SEND TO PADS' line: what it did, and which kit is on the grid now,
     * when the two fit the dock's two lines of about 44; what it did alone
     * when they do not.
     */
    fun sentAndOnTheGrid(sent: String, entry: KitShelf.Entry): String =
        "$sent ${Copy.onTheGrid(entry.kit.name)}".takeIf { it.length <= 2 * DOCK_LINE_CHARS } ?: sent

    /**
     * A landing: the screen changes and whatever hand-off state the landing
     * set rides along — a bank request for the KIT that follows, an armed
     * audition for GROOVE. This is what a bare `screen = AppScreen.X` used
     * to mean. [goToScreen], below, is the other kind of move: the user
     * leaving, which abandons all of that.
     */
    fun landOn(target: AppScreen) {
        nav.goTo(locationOf(target))
        dock.moved(nav.location)
    }

    /**
     * An offer: a line, and a door out of it (J10).
     *
     * CHOP and EXPORT are steps 2 and 4 of the loop the app advertises and
     * received zero programmatic navigations — the app automated the one
     * join in the middle and left the two at the ends to the user. This is
     * how both ends get offered without either being forced: the screen
     * does not move, the line says what happened, and going nowhere is a
     * perfectly good answer.
     *
     * The door stands in the dock until it is taken or another offer
     * replaces it (`DockModel.offer`); it does not time out. Declared
     * after [note] because it puts the note down, and a local function
     * cannot reach a local declared below it.
     */
    fun offer(message: String, label: String, target: AppScreen) {
        note = null
        dock.offer(message, Door(label, locationOf(target)))
    }
    var busy by remember { mutableStateOf<Busy?>(null) }
    // The job a screen is carrying on its own (Split, and a recording being
    // read in TAPE and CHOP), reported through `LocalBusySink` and gone when
    // the screen is. Work that outlives its screen is not reported here: the
    // pad editor, whose writes run on `appScope`, begins its jobs on the job
    // board (`jobs`, below, found by the screens as `LocalJobs`), which the
    // app owns and which keeps an entry until its job ends. This is kept
    // apart from `busy` on purpose: `busy` is App's own job and gates App's
    // own work (`if (busy != null) return`), and a screen's job must not lock
    // App's actions out. The dock shows App's first, then the board's, then
    // this.
    var screenBusy by remember { mutableStateOf<Busy?>(null) }
    // The jobs the app owns that outlive the screen that began them, provided
    // to every screen as `LocalJobs`. Like `screenBusy` it never gates App's
    // own work; unlike it, an entry stays until its owner ends it.
    val jobs = remember { AppJobs() }
    // Remembered, so the sink the screens read is one object for the life of
    // the composition: a static local that changed would recompose all of it.
    val busySink = remember { { reported: Busy? -> screenBusy = reported } }
    // The starter kit being rendered, by name, or null: while set, PADS draws
    // the placeholder grid for it and the shelf's NEW KIT button shows its
    // spinner. Set and cleared by `fresh`, alongside `busy`, and by nothing else.
    var pendingKit by remember { mutableStateOf<String?>(null) }
    var lastCommit by remember { mutableStateOf<TapeCommit?>(null) }
    // PAD SHEET: the long-press pad inspector, full-screen over PADS. Not a
    // place or a stage of its own; it's PADS-scoped overlay state, cleared
    // whenever the user leaves for another place or stage (see
    // `clearTransientState` below).
    var padSheetSlot by remember { mutableStateOf<Int?>(null) }

    /**
     * Open PAD SHEET on [slot]: the one door, for every way in — the pad
     * strip's OPEN ▸, the hold on a pad, DOUBLES' `GO ▸`, the RE-TRIM
     * return. One function because the sheet's discovery hint used to be
     * retired at exactly one of three doors (J16), and a door that is the
     * only one cannot miss what the others do. The hint itself is gone with
     * the strip (plan §3.3): a permanent tap door under the grid needs no
     * nudge toward the hold, and the velocity legend stays as the one
     * line that teaches the gesture.
     */
    fun openPadSheet(slot: Int) {
        padSheetSlot = slot
    }
    // KEYS: the instrument open on the grid, from the shelf's INSTRUMENTS list.
    var openInstrument by remember { mutableStateOf<KitShelf.InstrumentEntry?>(null) }
    var instruments by remember { mutableStateOf<List<KitShelf.InstrumentEntry>>(emptyList()) }
    // ROOMS (YY5): kept rooms beside the instruments; the revision bumps after a forget.
    var rooms by remember { mutableStateOf<List<Rooms.Room>>(emptyList()) }
    var binnedRooms by remember { mutableStateOf<List<Rooms.Binned>>(emptyList()) }
    var roomsRevision by remember { mutableStateOf(0) }
    // BIN (the plan's §3.1, over the bin-restore plan's DELETED KITS): the
    // shelf's own gate for the `BIN ▸` row — how many kits and snips the two
    // bins hold right now. `binOpen` is in this effect's key list, not just
    // `kits`/`onShelf`/`roomsRevision`: EMPTY THE BIN NOW changes nothing
    // else this effect already watches, so without that key, emptying a bin
    // and returning to the shelf would leave a stale count (and the row
    // itself) showing after the door behind it is empty.
    var binnedCount by remember { mutableStateOf(0) }
    var binOpen by remember { mutableStateOf(false) }
    // The projects on the shelf (`docs/DIRECTION_2026_10.md` §2): the
    // library's project list, read with the rest of the shelf, and the one
    // that is open. `ShelfModel` is the project half of the shelf beside
    // `KitShelf`; both are over the same root.
    val shelfModel = remember { ShelfModel(shelf.root) }
    var projects by remember { mutableStateOf<List<ShelfModel.ProjectRow>>(emptyList()) }
    var project by remember { mutableStateOf<Project?>(null) }
    // Keyed on the place, not the whole location: a bench opening over the
    // shelf (SNIPS, a kit's X-RAY) is not a reason to re-read the shelf.
    val onShelf = location.place == Place.SHELF
    // The instruments are MAKE's list now (plan §3.6), read when MAKE is the
    // place and re-read when a lab writes one (`roomsRevision` bumps on
    // every shelf asset a lab writes).
    val onMake = location.place == Place.MAKE
    LaunchedEffect(onMake, roomsRevision) {
        if (onMake) instruments = withContext(Dispatchers.IO) { shelf.instruments() }
    }
    LaunchedEffect(kits, onShelf, roomsRevision, binOpen) {
        if (onShelf) {
            // Opening the shelf adopts every unheld kit into a project of
            // its own name (`ProjectMigration.adopt`, idempotent) — the one
            // rule of `docs/DIRECTION_2026_10.md` §2. Keyed on `kits` so a
            // kit that lands while the shelf is open is adopted on the same
            // visit, and the projects list is read after it so the list
            // shows what the migration just made. Logged, not toasted: a
            // migration is nothing the user asked for, so a failure is
            // nothing they can act on here.
            projects = withContext(Dispatchers.IO) {
                runCatching { shelfModel.open() }
                    .onFailure { Log.e(TAG, "shelf open: project migration failed", it) }
                runCatching { shelfModel.projects() }
                    .onFailure { Log.e(TAG, "shelf open: projects unreadable", it) }
                    .getOrDefault(emptyList())
            }
            rooms = withContext(Dispatchers.IO) { runCatching { shelf.rooms() }.getOrDefault(emptyList()) }
            binnedRooms = withContext(Dispatchers.IO) { runCatching { shelf.binnedRooms() }.getOrDefault(emptyList()) }
            // After the last list the empty panel reads, not after the first:
            // see `shelfListsLoaded`. (`binnedCount` below only gates the BIN
            // row, which the empty panel does not read.)
            shelfListsLoaded = true
            binnedCount = withContext(Dispatchers.IO) {
                runCatching { shelf.binnedKits().size }.getOrDefault(0) +
                    runCatching { SnipStore.binned(context.filesDir).size }.getOrDefault(0)
            }
        }
    }
    // PADS' state lives in the navigation model (`nav.padsBank`,
    // `nav.padsSelectedSlot`, `nav.padsOpenBox` — plan §3.3), so a stage
    // switch PADS → BEAT → PADS returns to the same bank, pad and open box.
    // A kit change is still a reset: a bank or a selection is per kit, and
    // the pad sheet's open box used to be `remember(open?.dir)` for the
    // same reason. Keyed on the dir rather than the entry so a kit SAVE
    // (a new entry, same folder) keeps the player where they were.
    LaunchedEffect(open?.dir) { nav.resetPads() }
    // THIS KIT's closed line reads how many versions the kit has
    // (`Places.thisKit`). Counted the way TakesBinScreen counts them —
    // `KitBuilderModel.takes()` under the kit-writes lock — whenever the
    // kit on the grid changes or the shelf re-reads, which every save and
    // restore already triggers.
    var versions by remember { mutableStateOf(0) }
    LaunchedEffect(open?.dir, kits) {
        val dir = open?.dir
        versions = if (dir == null) {
            0
        } else {
            withContext(Dispatchers.IO) {
                KitWrites.mutex.withLock { runCatching { KitBuilderModel.open(dir).takes().size }.getOrDefault(0) }
            }
        }
    }
    // The kit switcher's sheet (plan §3.3): the project's kits by name,
    // open/closed state like the gear's.
    var kitSheetOpen by remember { mutableStateOf(false) }
    // `+ FROM SHELF`: the project a kit is being picked for. SHELF opens in
    // pick mode, the same shelf-level hand-off shape as `pendingSnipAssign`
    // and `pendingBreedWith` below; tapping a kit row adds it and returns.
    var pendingAddTo by remember { mutableStateOf<Project?>(null) }
    // `FROM SNIPS ▸` on an empty pad's strip: the slot a snip is being picked
    // for. SNIPS opens as a bench over PADS; picking a snip lands it here.
    var pendingSnipTarget by remember { mutableStateOf<Int?>(null) }
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
    // AUDITION (spec decision 2): PAD SHEET's own AUDITION ▸ arms a
    // comparison for a slot and switches to GROOVE, where the pattern to
    // audition inside actually lives (spec rule 2). Unlike GRAIN/SPLICE/
    // STACK above, this is consumed once GrooveScreen has built the session
    // from it (its own `onAuditionArmed`), not read continuously — a plain
    // `Int?` one-shot request, same shape as `kitBankRequest` below.
    var auditionArmSlot by remember { mutableStateOf<Int?>(null) }
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
    // Bug fix (tab-switch data loss): GROOVE's FEEL, SWING and selected
    // program used to be `remember(kitDir)` locals INSIDE GrooveScreen, so
    // this screen's bare `when (screen)` below — no `SaveableStateHolder`,
    // no `rememberSaveable` — tore the whole composable down on every tab
    // switch and silently reset all three. Hoisted here instead, same
    // value+setter shape as `exportSession`/`onSessionChange` below, keyed
    // on `open?.dir` so App (which never leaves composition) is what
    // actually survives the tab switch. Keying on the dir — not a bare
    // `remember { }` — is the other half of the fix: these are
    // per-kit-SESSION settings, not per-kit-persisted ones, so opening a
    // DIFFERENT kit must still reset them; a forgotten FEEL must never
    // quietly alter a different kit's export. GrooveScreen's own defaults
    // (`GROOVE_SWING_DEFAULT`, 0, 0) are mirrored here rather than left
    // nullable, matching what a from-scratch GROOVE screen already showed
    // before this fix.
    var grooveFeel by remember(open?.dir) { mutableIntStateOf(0) }
    var grooveSwingPercent by remember(open?.dir) { mutableIntStateOf(GROOVE_SWING_DEFAULT) }
    var grooveProgIndex by remember(open?.dir) { mutableIntStateOf(0) }
    // Follow-up to the three above: `seed` is one setting split across two
    // variables with `grooveFeel`, not a separate one — GrooveScreen's own
    // FeelRow label reads `FEEL · LOOSE 40% · #$seed`, and its feelTemplate
    // is derived from `seed` alone. Leaving `seed` behind meant a tab switch
    // could restore the FEEL percentage while silently rerolling the
    // template underneath it — the readout would show a stale `#n` for a
    // groove that no longer matched what was on screen. `grooveJustLanded`/
    // `groovePreTake` are the post-take EDIT THIS TAKE / UNDO TAKE row and
    // its restore snapshot — the SAME data-loss bug `9a0a9772` fixed for the
    // FEEL stepper, reached through a second door: recording a take,
    // switching tabs, and coming back used to lose the row (and the only
    // way back to the pre-take state) exactly as FEEL used to reset. All
    // three keyed identically to the block above — a KIT CHANGE must still
    // reset them, so a `preTake` snapshot captured under one kit can never
    // be restored onto a different kit that's since been opened.
    var grooveSeed by remember(open?.dir) { mutableIntStateOf(1) }
    var grooveJustLanded by remember(open?.dir) { mutableStateOf(false) }
    var groovePreTake by remember(open?.dir) { mutableStateOf<Mpc3Clip?>(null) }
    // Bug fix (tab-switch data loss), ORBIT's own version of the fix above:
    // this screen used to be reachable through TWO doors — GROOVE's own
    // "ORBIT ▸" overlay and the menu row's own ORBIT tab — and both tore the
    // whole composable down on every tab switch, taking ~30
    // `remember(kitDir)` values with it. It is one bench under BEAT now
    // (`Bench.ORBIT`), drawn once, but leaving the bench still tears it
    // down. Six of those values are the player's actual work rather than
    // in-flight machinery or an open panel, so they're hoisted here exactly
    // like GROOVE's own six above: same value+onChange shape, threaded into
    // the `OrbitScreen` call, keyed on `open?.dir` so a KIT CHANGE still resets every one
    // of them — a `history` entry (an undo snapshot) or a `set` (the rings
    // themselves) from one kit must never be reachable from a different
    // one, which would be worse than the data loss this fixes.
    // - `orbitHistory`: the undo stack. The headline — this used to lose a
    //   whole stack of edits on a tab switch, not just one snapshot the way
    //   GROOVE's post-take row did.
    // - `orbitSelected`/`orbitSolo`: which ring is being edited and which
    //   is soloed — losing either reads as losing your cursor mid-edit.
    // - `orbitSet`: the rings and bar configuration themselves. Hoisting
    //   this isn't only about avoiding a reload from `orbits.json` on every
    //   remount — a fast tab switch right after an edit could otherwise
    //   read the file before `commit`'s own async save lands and silently
    //   roll the rings back. See `OrbitScreen`'s own KDoc on its `set`
    //   parameter for how the reload guard closes that specific race.
    // - `orbitScrambleSeed`: what SCRAMBLE rolled.
    // - `orbitBrush`: what the user dialled in for long-press writes.
    //
    // `bank` and `engine` — named as candidates alongside these by the
    // brief that asked for this fix — are deliberately NOT hoisted: reading
    // their own definitions (`OrbitBank`/`OrbitEngine`) shows both are live
    // transport machinery (decoded/resampled audio buffers, and the
    // audio-thread engine that plays them), the same family as `sink`/
    // `audioThread`, which nobody proposed hoisting either. Hoisting them
    // would fight OrbitScreen's own `DisposableEffect(kitDir) { onDispose {
    // stopPlayback() } }`, which already (and correctly) nulls both out on
    // every unmount — a stale audio handle surviving a tab switch would be
    // a bug, not a feature, exactly as the brief that asked for this fix
    // itself warned.
    var orbitHistory by remember(open?.dir) { mutableStateOf<List<OrbitStep>>(emptyList()) }
    var orbitSelected by remember(open?.dir) { mutableIntStateOf(0) }
    var orbitSolo by remember(open?.dir) { mutableStateOf<Int?>(null) }
    var orbitSet by remember(open?.dir) { mutableStateOf<OrbitSet?>(null) }
    var orbitScrambleSeed by remember(open?.dir) { mutableIntStateOf(1) }
    var orbitBrush by remember(open?.dir) { mutableStateOf(OrbitBrush.WEIGHT) }
    // SNIPS is a bench (`Bench.SNIPS`), not a boolean here: it opens from
    // the shelf as always, and from an empty pad's FROM SNIPS ▸ over PADS
    // (plan §3.3), so it has to be drawable over either place.
    // LOOP (plan-03's front door): the six-track grid lives in its own
    // bench under BEAT now, and its session lives in this one folder. Held
    // here because SNIPS' own → LOOP and SYNTH's drone send both fill it.
    val loopDir = remember(context) { LoopWrites.dir(context) }
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
    // BIN (plan §3.1, over Task 2 of the bin-restore plan): same shape as
    // `snipsOpen` above — a shelf-level overlay, not KIT-scoped, reachable
    // from `KitsScreen`'s own `BIN ▸` row and from SNIPS' own door.
    // `binOpen` itself is declared earlier in this function (with
    // `binnedCount`, above) since the shelf's refresh effect needs it as
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
    // CATCH A HIT's write queue and tally for the live session (docs/CATCH.md).
    val catchSession = remember { CatchSession() }
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
    // A RE-TRIM lives on TAPE only. Every way off the place — the place
    // row, system back, INSTANT KIT, READ AS GROOVE, a SNIPS → TAPE that
    // re-enters — drops it here, in one place, rather than at each exit.
    // The place, not the stage: KEEP moves DECK → CUT and the tape is
    // still the tape.
    val onTape = location.place == Place.TAPE
    LaunchedEffect(onTape) {
        if (!onTape) retrim = null
    }
    // X4.4 TEACH THE MACHINE: off by default, flipped on SETUP's consent
    // row, remembered like the scheme. CHOP reads it; what it gates is
    // feature vectors and labels into the kit's own folder, never audio,
    // and nothing leaves the phone either way (Copy.TEACH_CONSENT).
    var teachEnabled by remember { mutableStateOf(prefs.getBoolean(PREF_TEACH, false)) }
    // The WORKSHOP (docs/WORKSHOP.md): the developer's tools at the foot of
    // SETUP, open once seven taps on that screen's title land inside two
    // seconds of each other, remembered like the scheme until the section's
    // own CLOSE. One knock counter for the app's life — a knock is a
    // gesture, not a setting, so it is not remembered across a restart.
    var workshopOpen by remember { mutableStateOf(prefs.getBoolean(PREF_WORKSHOP, false)) }
    val workshopKnock = remember { Workshop.Knock() }
    // A BENCH NOTE in progress (docs/WORKSHOP.md, WS4): the stamp the
    // title bar's NOTE chip read at the tap — screen, open kit, open pad
    // sheet, time — while its dialog is up; null otherwise. The stamp is
    // read at the tap and not at KEEP because the tap is the moment the
    // note is about, and read once so the context line the dialog shows
    // and the line KEEP writes can never disagree.
    var benchNote by remember { mutableStateOf<BenchNotes.Stamp?>(null) }
    // EXPORT: hoisted here, not local to ExportScreen's own composition —
    // its write runs on `scope` below (App's own, handed down as
    // `appScope`) so it survives a MenuRow tab switch; the session object
    // is what lets a remounted ExportScreen reconnect to a write already
    // in flight instead of racing a second one against the same kit's
    // files. See ExportSession's own KDoc.
    var exportSession by remember { mutableStateOf<ExportSession?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * A kit landing on the grid from somewhere else — INSTANT KIT, SEND TO
     * PADS, a starter, a photo kit, a BREED child, a shared file, DOUBLES'
     * GO ▸ — lands as the open project's active kit (plan §3.2): the grid
     * shows it at once, and the project is brought up to date behind it,
     * holding it if it did not and activating it. With no project open,
     * the kit's own project opens (`ShelfModel.projectForKit`, the
     * migration's rule for a kit that has none). The bookkeeping is on IO
     * after the landing rather than before it, because the kit is already
     * made and the grid is the point; a failed `project.json` write says so
     * and leaves the grid where it is.
     */
    fun landKit(entry: KitShelf.Entry) {
        open = entry
        landOn(AppScreen.KIT)
        val p = project
        scope.launch {
            val updated = try {
                withContext(Dispatchers.IO) {
                    val folder = entry.dir.name
                    val holder = if (p != null) {
                        if (!p.holds(folder)) shelfModel.addKit(p.name, folder)
                        p
                    } else {
                        shelfModel.projectForKit(folder)
                    }
                    shelfModel.activate(holder.name, folder)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "landKit: project bookkeeping failed for ${entry.dir.name}", e)
                dock.say(Copy.KIT_ADD_FAILED)
                return@launch
            }
            // Re-read after the await: only adopt the update if the user
            // has not opened another project meanwhile.
            if (project?.name == updated.name || project == null) project = updated
        }
    }

    // The armed mic session (retroactive-snip Task 4): `armed` is the
    // service companion's own StateFlow, so this reflects the live session
    // regardless of which screen changed it (the ARM/EJECT buttons here,
    // or the notification's own SNIP action never touches this at all —
    // only ARM/EJECT flip it).
    val armed by MicSessionService.armed.collectAsState()
    // The dock's capture light and counter, fed from the same session: the
    // seconds since the session armed, ticked once a second while it is,
    // the way the shelf's RecordingIndicator counts. `armedAtElapsedRealtime`
    // is read inside the loop rather than captured, so a remount shows the
    // true elapsed time at once and not from 00:00.
    LaunchedEffect(armed) {
        if (!armed) {
            dock.capture = DockModel.Capture.Idle
            return@LaunchedEffect
        }
        while (true) {
            val anchor = MicSessionService.armedAtElapsedRealtime
            val seconds = if (anchor == 0L) 0 else ((SystemClock.elapsedRealtime() - anchor) / 1000L).toInt().coerceAtLeast(0)
            dock.capture = DockModel.Capture.Armed(seconds)
            delay(1000)
        }
    }
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
            dock.say(Copy.MIC_HEARING_NOTHING)
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
            // The optimistic line offered CUT; a failed commit has nothing
            // to cut, and a line keeps its door, so the door goes first.
            dock.clear()
            dock.say(message)
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
            dock.say(Copy.PHONE_STOPPED_TAPE)
        }
    }
    // Sessions the reader thread itself ended — the mic (or, for APP
    // AUDIO, the playback-capture stream) going silent for good, a
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
            dock.say(Copy.TAPE_STOPPED_ITSELF)
        }
    }
    // ARM APP AUDIO runs through the same RECORD_AUDIO request as ARM TAPE
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
    // afresh on every ARM APP AUDIO — Android 14+ honours each consent for
    // exactly one projection, so the result is never cached. Dismissed
    // is a refusal in words, not a failure; the mic path stays open.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MicSessionService.armInside(context, result.resultCode, data)
            dock.say(Copy.APP_AUDIO_ARMED)
            offerBubbleOnce()
        } else {
            dock.say(Copy.APP_AUDIO_REFUSED)
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
                    dock.say(Copy.APP_AUDIO_REFUSED)
                } else {
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                }
            } else {
                haptics.arm()
                MicSessionService.arm(context)
                dock.say(Copy.SESSION_ARMED)
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
        kitsLoaded = true
        // The rooms' bin empties itself of what has slept past its days.
        withContext(Dispatchers.IO) { runCatching { shelf.sweepRooms() } }
        // Same promise, for forgotten presets (docs/WORKSHOP.md, WS5's
        // follow-up): the one file at the shelf root sweeps its own bin.
        withContext(Dispatchers.IO) { runCatching { UserPresets.sweepBin(shelf.root) } }
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
        // context.filesDir, not shelf.root: snips live at `<files>/snips`, and
        // the shelf is `<files>/Kits`. This swept a bin that never existed.
        withContext(Dispatchers.IO) { runCatching { SnipStore.sweepBin(context.filesDir) } }
        // Orphaned import-staging left by a crashed/killed import, and
        // regenerable derived output (share-sheet temp copies) — never a
        // live kit or snip. Exports are deliberately NOT swept here: see
        // sweepOrphanedStorage's KDoc.
        withContext(Dispatchers.IO) { runCatching { sweepOrphanedStorage(shelf.root, context.cacheDir) } }
    }

    /**
     * The shelf's sort toggle (name-and-find followups): persists the
     * choice like [PREF_SCHEME] above, then re-fetches
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
    /**
     * True while TAPE has a CATCH live (J7).
     *
     * A share landing bumps the deck's reload and forces `AppScreen.TAPE`,
     * which replaces the model a catch is running against - `catchBusy` and
     * `catching` are both `remember(model)`, so the in-flight catch is
     * orphaned and its results vanish. The import path below already
     * reasons about a dub in flight and explicitly outranks a RE-TRIM; a
     * catch was never in that list.
     */
    var catchInFlight by remember { mutableStateOf(false) }
    var importCount by remember { mutableStateOf(0) }
    // GROOVE's reload request: bumped when TAPE rewrites the open kit's
    // groove (READ AS GROOVE, STEAL THE FEEL), or ORBIT's CLIP ▸ KIT does
    // (OrbitScreen's `onGrooveWritten`), so a GROOVE already up reloads.
    var grooveReload by remember { mutableStateOf(0) }
    // Follow-up, a door the hoist above opened: `grooveJustLanded`/
    // `groovePreTake` surviving a tab switch means they can now ALSO survive
    // a trip through TAPE (or ORBIT) and back — READ AS GROOVE / STEAL THE
    // FEEL below, and OrbitScreen's `clipIntoKit` (CLIP ▸ KIT), all rewrite
    // the open kit's groove.json out from under GROOVE and bump
    // `grooveReload` for exactly that reason, but none of them ever touches
    // `justLanded`. Before this hoist that never mattered: leaving GROOVE
    // for TAPE (or ORBIT) tore the whole composable (and `justLanded` with
    // it) down regardless. Now it doesn't, so without this: land a take,
    // hop to TAPE, steal a different feel into the SAME kit (or hop to
    // ORBIT and CLIP ▸ KIT), hop back — GROOVE would show a stale UNDO TAKE
    // row over a base that isn't this take's own pre-state at all. Tapping
    // it would clobber the other screen's rewrite with an unrelated
    // snapshot and toast [Copy.TAKE_UNDONE], a claim about what happened
    // that wouldn't be true. `grooveReload` only ever increments from those
    // three groove-writing actions — never from GROOVE's own writes, which
    // deliberately avoid bumping it (see `GrooveScreen.stopRecording`'s own
    // KDoc) — so keying on it alone here, not `open?.dir` too, is
    // deliberate: a KIT CHANGE is already covered by the
    // `remember(open?.dir)` block above. `groovePreTake` is left alone,
    // same as `clearJustLanded` itself never nulls `preTake` — `justLanded`
    // alone gates whether the row (and the snapshot it would restore) is
    // ever reachable.
    LaunchedEffect(grooveReload) { grooveJustLanded = false }
    LaunchedEffect(shared, catchInFlight) {
        val uri = shared ?: return@LaunchedEffect
        // DEFERRED, NOT DROPPED. `ShareInbox.consume()` is deliberately not
        // called here: the share stays pending, and because `catchInFlight`
        // is one of this effect's own keys, finishing the catch re-runs it
        // and the import lands then. Nothing is lost and nothing has to be
        // explained to the player - which beats both silently reloading the
        // deck out from under a live catch and refusing the share outright.
        if (catchInFlight) return@LaunchedEffect
        // The status line is borrowed only when nothing else holds it: a
        // sound import writes to the snips dir, and a kit file lands as
        // *new* folders on the shelf under names nothing there holds -
        // neither touches the open kit a dub in flight is writing, so an
        // import runs beside that dub without racing it, and must not wipe
        // the dub's own DUBBING… line on its way out.
        val ownsBusy = busy == null
        if (ownsBusy) busy = Busy(Copy.IMPORT_BUSY)
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
                if (ownsBusy) busy = Busy(Copy.LANDING_BUSY)
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
                        dock.say(Copy.roomLanded(room.name))
                        return@LaunchedEffect
                    }
                    val (entries, skipped, presets) = withContext(Dispatchers.IO) { shelf.land(local, name) }
                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                    // A clean landing keeps its toast; one with skips opens the
                    // box, which names each skipped kit and the door's reason.
                    // A backup's presets are counted in the same line: they
                    // landed under YOURS on SYNTH, which is nowhere near here.
                    val boxed = LandingNote.landed(name, entries.map { it.kit.name }, skipped, presets)
                    if (boxed != null) openNote(boxed) else dock.say(Copy.landed(entries.size, skipped.size, presets))
                    entries.firstOrNull()?.let { first ->
                        padSheetSlot = null
                        takesBinOpen = false
                        arrangeOpen = false
                        landKit(first)
                    }
                    // Clearing a key of this effect cancels it on the next
                    // recomposition. Finish the shelf and navigation first.
                    ShareInbox.consume()
                    return@LaunchedEffect
                } finally {
                    local.delete()
                }
            }
            val landed = withContext(Dispatchers.IO) {
                val snip = MediaDecode.decode(context, uri)
                SnipStore.import(snip, context.filesDir, System.currentTimeMillis())
            }
            if (open == null) {
                open = withContext(Dispatchers.IO) { shelf.list(shelfSort) }.firstOrNull()
            }
            dock.say(Copy.imported(landed.seconds, landed.truncated, landed.resampledFrom))
            // A new capture landing outranks a RE-TRIM in flight (RetrimRequest).
            retrim = null
            importCount++
            padSheetSlot = null
            takesBinOpen = false
            arrangeOpen = false
            landOn(AppScreen.TAPE)
            ShareInbox.consume()
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
    /**
     * Whether a SNIPS → PAD landing has somewhere to go: an empty pad on
     * bank A or B — both are pages the long-press fills (bank B round 2).
     * One predicate for every hint that says so, so a full bank A with
     * an empty B never reads as "THIS KIT IS FULL".
     */
    fun hasLandingPad(kit: com.snipsnap.kit.Kit): Boolean =
        (PadBanks.slots(0).first..PadBanks.slots(1).last).any { kit.pad(it) == null }

    /**
     * A starter kit, rendered and landed on PADS. The render takes a few
     * seconds (a minute, on a slow phone), so the user is taken to PADS at
     * once and finds the shape of the grid there ([pendingKit] makes `KitLoading`
     * draw `PadPlaceholderGrid`, which takes no name, with the loading card over
     * it once the job has run 200 ms; the card and the dock's bar carry the
     * starter's name); the kit replaces the placeholders when it lands.
     *
     * Moved with [landOn], not `goToScreen`: that one also clears the
     * transient state, `pendingSnipAssign` among it, and the snip waiting
     * for a home is exactly what the success line below checks for. (It is
     * also declared further down, out of a local function's reach.)
     */
    fun fresh(starter: StarterKits.Starter) {
        if (busy != null) return
        busy = Busy(Copy.loadingBusy(starter.displayName))
        pendingKit = starter.displayName
        landOn(AppScreen.KIT)
        scope.launch {
            val entry = try {
                withContext(Dispatchers.IO) { shelf.render(starter, Random.nextInt()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingKit = null
                busy = null
                // Nothing landed, so the placeholders have nothing to become -
                // but only a user still looking at them is moved. Someone who
                // went to TAPE or BEAT while it rendered is where they chose to
                // be, and is told, not yanked. From the placeholders it is Back,
                // not a new landing, so the KIT entry `fresh` pushed is popped
                // and not left in the history; the shelf is landed on only if
                // Back did not get there (`Navigation.backOutOf`).
                if (nav.backOutOf(locationOf(AppScreen.KIT), locationOf(AppScreen.KITS))) dock.moved(nav.location)
                // Law 3 in practice, matching ExportScreen's own dub-failed
                // catch: the user gets what failed, not a Java exception's
                // `message` quoted at them — that detail stays in logcat.
                Log.e(TAG, "fresh: kit render failed", e)
                dock.say(Copy.CREATE_FAILED)
                return@launch
            }
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            // A snip may be waiting for a home: the empty shelf's own copy
            // sends the user here to make a kit for it (EMPTY_SHELF_FOR_ASSIGN),
            // and `fresh` deliberately does not clear the hand-off. Saying
            // only FRESH_TAPE there dropped the thread on the one route that
            // copy points at, while every other way into a kit with a snip
            // armed says what to do next (September UAT, finding 12).
            dock.say(if (pendingSnipAssign != null) Copy.snipLanding(hasLandingPad(entry.kit)) else Copy.FRESH_TAPE)
            landKit(entry)
            // Cleared after landKit has put the kit in `open`. It is one run on
            // the main thread, so the placeholders and the dock's bar go in the
            // same frame the kit arrives; the order is only the one that reads
            // as "the kit is here, so the wait is over".
            pendingKit = null
            busy = null
        }
    }

    /**
     * KIT ▸ on SNAP: [photo] cut 4×4 and rendered as sixteen SNAP pads
     * (`PhotoKit.build`), landed on the shelf as a brand-new kit — [fresh]'s
     * own shape, off a picture instead of a starter's seed, never
     * overwriting whatever kit is open.
     */
    fun buildPhotoKit(photo: Photo) {
        if (busy != null) return
        busy = Busy(Copy.SNAP_KIT_BUSY)
        scope.launch {
            val entry = try {
                withContext(Dispatchers.IO) { shelf.landPhotoKit("PHOTO KIT", PhotoKit.build(photo, "PHOTO KIT")) }
            } catch (e: Exception) {
                busy = null
                Log.e(TAG, "buildPhotoKit: failed", e)
                dock.say(Copy.CREATE_FAILED)
                return@launch
            }
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            busy = null
            dock.say(Copy.photoKitMade(entry.kit.name))
            landKit(entry)
        }
    }

    /**
     * RING ▸ from SNAP's PATH: [cells] (a walked photo, one per step) as a
     * fresh pattern ring, appended to whatever `orbits.json` the open kit
     * already has — `OrbitScreen`'s own `addPatternRing`/`addSnipRing`
     * shape (load-or-build, append, save), landed from outside that
     * screen. `PhotoKit.slotFor` is what lets the ring name pads
     * directly: a path only makes musical sense over a kit whose pads are
     * this same picture's cells, which is on the player to arrange (KIT ▸
     * first, then PATH on the same photo) — nothing here checks that a
     * pad slot holds what the cell actually sounds like.
     */
    fun buildPathRing(cells: List<Pair<Int, Int>>, bpm: Float) {
        val e = open ?: return
        if (busy != null) return
        busy = Busy(Copy.SNAP_PATH_BUSY)
        scope.launch {
            try {
                val current = withContext(Dispatchers.IO) {
                    if (OrbitStore.exists(e.dir)) {
                        OrbitStore.load(e.dir)
                    } else {
                        val startBpm = (e.kit.tempoBpm ?: bpm).coerceIn(OrbitSet.MIN_BPM, OrbitSet.MAX_BPM)
                        OrbitPresets.fromKit(e.dir.name, e.kit, startBpm, deviceSampleRate(context))
                    }
                }
                if (current.orbits.size >= OrbitSet.MAX_ORBITS) {
                    busy = null
                    dock.say(Copy.ORBIT_RINGS_FULL)
                    return@launch
                }
                val hits = cells.mapIndexed { i, (column, row) -> OrbitHit(step = i, slot = PhotoKit.slotFor(column, row)) }
                val ring = Orbit(
                    name = "PATH ${current.orbits.size + 1}",
                    steps = cells.size,
                    content = PatternOrbit(kit = e.dir.name, hits = hits),
                    voice = (1..PhotoKit.COLUMNS * PhotoKit.ROWS).toList(),
                )
                val merged = current.copy(orbits = current.orbits + ring)
                withContext(Dispatchers.IO) { OrbitStore.save(merged, e.dir) }
                orbitSet = merged
                orbitSelected = merged.orbits.size - 1
                busy = null
                dock.say(Copy.pathRingMade(ring.name))
                landOn(AppScreen.ORBIT)
            } catch (ex: Exception) {
                busy = null
                Log.e(TAG, "buildPathRing: failed", ex)
                dock.say(Copy.CREATE_FAILED)
            }
        }
    }

    /**
     * CHORD ▸ on SNAP: [photo] read as a chord (`PhotoChord.build` — the
     * picture's hue and brightness pick a root and a quality, its tones
     * rendered one per pad) and landed on the shelf as a brand-new kit,
     * the same never-overwrite shape as [buildPhotoKit] — plus an ORBIT
     * arp ring, built and saved for that SAME new kit before the screen
     * ever shows it, so CHORD always opens ORBIT onto a ring already
     * arpeggiating rather than a silent kit waiting for one. Every `dir`
     * below is the brand-new entry's own, never `open` — the kit this
     * lands is never the one already open.
     */
    fun buildChordKit(photo: Photo) {
        if (busy != null) return
        busy = Busy(Copy.SNAP_CHORD_BUSY)
        scope.launch {
            try {
                val built = withContext(Dispatchers.Default) { PhotoChord.build(photo, "PHOTO CHORD") }
                val newEntry = withContext(Dispatchers.IO) { shelf.landPhotoKit(built.chord.name, built.pads) }
                val current = withContext(Dispatchers.IO) {
                    if (OrbitStore.exists(newEntry.dir)) {
                        OrbitStore.load(newEntry.dir)
                    } else {
                        val startBpm = (newEntry.kit.tempoBpm ?: 92f).coerceIn(OrbitSet.MIN_BPM, OrbitSet.MAX_BPM)
                        OrbitPresets.fromKit(newEntry.dir.name, newEntry.kit, startBpm, deviceSampleRate(context))
                    }
                }
                if (current.orbits.size >= OrbitSet.MAX_ORBITS) {
                    busy = null
                    dock.say(Copy.ORBIT_RINGS_FULL)
                    return@launch
                }
                // The ring wears the chord's name as the chord spells it,
                // while the kit folder beside it is the shouted, sanitized
                // one `freshName` made. They disagree on purpose: a kit name
                // is a filename and has to survive `Names.sanitizeStem`,
                // but a chord name is not free to shout. "Cm" uppercased is
                // "CM", and the lost lower-case m is the whole difference
                // between a minor chord and a major one.
                val seedRing = OrbitPresets.emptyPattern(newEntry.dir.name, built.chord.name, built.slots)
                // UP_DOWN over the chord's own pads, one octave: PhotoChord
                // already spreads its tones across an octave or more of
                // them, so a second would only reach into an empty bank B.
                val ring = Arp.run(current, seedRing, built.slots, Arp.Shape.UP_DOWN)
                val merged = current.copy(orbits = current.orbits + ring)
                withContext(Dispatchers.IO) { OrbitStore.save(merged, newEntry.dir) }
                // orbitSet/orbitSelected are `remember(open?.dir)`: assigning them
                // here, before `open` changes below, lands on the state that is
                // about to be thrown away. ORBIT's own LaunchedEffect(kitDir)
                // reloads orbits.json fresh once `open` points at the new kit.
                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                busy = null
                dock.say(Copy.chordKitMade(newEntry.kit.name, ring.name))
                open = newEntry
                landOn(AppScreen.ORBIT)
            } catch (e: Exception) {
                busy = null
                Log.e(TAG, "buildChordKit: failed", e)
                dock.say(Copy.CREATE_FAILED)
            }
        }
    }

    /**
     * SCULPT / STRETCH / FREEZE from the KIT screen: one pad of the open kit
     * becomes a texture kit of its own on the shelf, which then opens —
     * the same busy-then-land shape as [fresh] (the dock says SCULPTING…,
     * STRETCHING… or FREEZING…, where [fresh] says LOADING <NAME>…), because
     * it's the same thing: a new tape rendered offline over a few seconds.
     */
    fun texture(slot: Int, spec: TextureKits.Spec) {
        val source = open ?: return
        if (busy != null) return
        busy = Busy(when (spec) {
            is TextureKits.Spec.Sculpt -> "SCULPTING…"
            is TextureKits.Spec.Stretch -> "STRETCHING…"
            is TextureKits.Spec.Freeze -> "FREEZING…"
        })
        scope.launch {
            val entry = try {
                withContext(Dispatchers.IO) { shelf.texture(source, slot, spec) }
            } catch (e: Exception) {
                busy = null
                // Law 3: when it breaks, say exactly what happened - the
                // exception's own detail goes to logcat, not the toast.
                Log.e(TAG, "texture: ${spec.verb} failed", e)
                dock.say(Copy.actionFailed(spec.verb))
                return@launch
            }
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            busy = null
            dock.say(
                when (spec) {
                    is TextureKits.Spec.Sculpt -> Copy.SCULPTED
                    is TextureKits.Spec.Stretch -> Copy.STRETCHED
                    is TextureKits.Spec.Freeze -> Copy.FROZEN
                },
            )
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
        // On the job board, not on `busy`: the dock says SETTING THE KEY… if
        // the write is slow (it shows only after the latch's 200 ms), while
        // every other control stays live, as it always was for this write.
        // Begun here, before the launch, and ended in the launch's `finally`.
        val job = jobs.begin(Busy(Copy.KEY_BUSY))
        scope.launch {
            try {
                val entry = try {
                    // KitShelf.setKey is its own open→mutate→save on this kit's
                    // kit.json — the same file PAD SHEET/PAD CAPTURE/SYNTH/TAKES+BIN
                    // write, so it goes through the same KitWrites lock they do.
                    withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.setKey(source, key) } }
                } catch (e: Exception) {
                    Log.e(TAG, "setKey: failed", e)
                    dock.say(Copy.KEY_FAILED)
                    return@launch
                }
                // Same identity guard as the KIT branch's onKitUpdated closures
                // above: this write ran against `source.dir`, not necessarily
                // whichever kit is open by the time it lands (setKey sets no
                // `busy`, so nothing here stops a tab-away-and-reopen mid-write).
                if (open?.dir == source.dir) open = entry
                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                dock.say(key?.let { Copy.keySet(com.snipsnap.shell.KeyPicker.label(it)) } ?: Copy.KEY_OFF)
            } finally {
                job.end()
            }
        }
    }

    /**
     * EVIL TWINS (W4.3): bank B lit with seeded re-treatments of bank A, a
     * new seed every press so the second press rerolls. The same
     * busy-then-land shape as a fresh tape: every twin renders offline over a
     * few seconds.
     */
    fun evilTwins() {
        val source = open ?: return
        if (busy != null) return
        // A remix replaces bank B. The user's own pads there (capture,
        // SNIPS → PAD, a chop landed ONTO it) are not the remix's to
        // replace: `remixBankB` refuses, and this says so first, in the
        // app's words, instead of a TWINS FAILED with the model's.
        if (KitBuilderModel.ownPadsOnBankB(source.kit).isNotEmpty()) {
            dock.say(Copy.TWINS_KEEP_OWN)
            return
        }
        val hadTwins = source.kit.pads.any { it.slot > 16 }
        busy = Busy(Copy.EVIL_TWINS_BUSY)
        scope.launch {
            val (entry, _) = try {
                // Same reasoning as setKey above: evilTwins is an
                // open→mutate→save on kit.json.
                withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.evilTwins(source, Random.nextInt()) } }
            } catch (e: Exception) {
                busy = null
                Log.e(TAG, "evilTwins: failed", e)
                dock.say(Copy.TWINS_FAILED)
                return@launch
            }
            // Same identity guard as setKey above — `busy` blocks a second
            // EVIL TWINS tap, not a MenuRow tab-away-and-reopen mid-write.
            if (open?.dir == source.dir) open = entry
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            busy = null
            dock.say(if (hadTwins) Copy.TWINS_REROLLED else Copy.BANK_B_LIT)
        }
    }

    /**
     * DUST ALL: every pad of the open kit dusted at `PadSheet.DUST_ALL_AMOUNT`
     * from its own tape, else the kit's (`DustPrints.tapeFor`) — the
     * per-pad door `KitBuilderModel.dustPad` once per pad under one
     * `KitWrites.mutex`, each tape's print read once. Layered and chained
     * pads are left as they are and counted, since every audio rewrite
     * refuses them. The same busy-then-land shape as EVIL TWINS: the
     * prints and sixteen convolutions run offline.
     */
    fun dustAll() {
        val source = open ?: return
        if (busy != null) return
        val kitTape = DustPrints.kitTape(source.kit)
        if (kitTape == null) {
            dock.say(Copy.DUST_NO_TAPE)
            return
        }
        val snipsDir = File(context.filesDir, SnipStore.DIR)
        if (!File(snipsDir, kitTape).isFile) {
            dock.say(Copy.dustTapeGone(kitTape))
            return
        }
        busy = Busy(Copy.DUSTING_BUSY)
        scope.launch {
            // What landed before a failure, if one comes: saved and shown,
            // never left as audio in the bin with no recipe in kit.json.
            var partial: KitShelf.Entry? = null
            val result = try {
                withContext(Dispatchers.IO) {
                    // Which tape each pad dusts from: its own when that tape is
                    // still on the shelf, else the kit's. Every print is made
                    // (or read off the cache) here, before the lock — the one
                    // expensive step, and a null answer is remembered too, so
                    // a gated tape is asked once, not once per pad.
                    fun tapeOf(kit: Kit, pad: KitPad): String =
                        DustPrints.tapeFor(kit, pad)?.takeIf { File(snipsDir, it).isFile } ?: kitTape
                    val prints = HashMap<String, Dust.Print?>()
                    for (tape in source.kit.pads.map { tapeOf(source.kit, it) }.toSet()) {
                        prints[tape] = DustPrints.forTape(File(snipsDir, tape))
                    }
                    KitWrites.mutex.withLock {
                        val m = KitBuilderModel.open(source.dir)
                        var dusted = 0
                        var left = 0
                        var noDust = 0
                        try {
                            for (pad in m.kit.pads.toList()) {
                                // Layers and chains are what every audio rewrite
                                // refuses: counted, not attempted. Anything else
                                // that fails is a real failure and says so.
                                if (pad.velocityLayers.isNotEmpty() || pad.chain != null) {
                                    left++
                                    continue
                                }
                                // The tape is read off the kit as it is now, not
                                // the one shown when DUST ALL was tapped: a pad
                                // re-trimmed in between dusts from its new tape.
                                // A tape the plan above never saw is printed
                                // here, under the lock — the rare case.
                                val tape = tapeOf(m.kit, pad)
                                val print = prints.getOrPut(tape) { DustPrints.forTape(File(snipsDir, tape)) }
                                if (print == null) {
                                    noDust++
                                    continue
                                }
                                m.dustPad(pad.slot, PadSheet.DUST_ALL_AMOUNT, tape, print)
                                dusted++
                            }
                        } finally {
                            if (dusted > 0) {
                                m.save()
                                partial = KitShelf.Entry(source.dir, m.kit)
                            }
                        }
                        Triple(KitShelf.Entry(source.dir, m.kit), dusted, left to noDust)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                busy = null
                partial?.let { if (open?.dir == source.dir) open = it }
                openNote(LandingNote.dustFailed(e.message ?: e.javaClass.simpleName))
                return@launch
            }
            busy = null
            val (entry, dusted, counts) = result
            val (left, noDust) = counts
            if (dusted == 0 && left == 0) {
                dock.say(Copy.DUST_NO_GHOSTS)
                return@launch
            }
            if (open?.dir == source.dir) open = entry
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            dock.say(Copy.dustedAll(dusted, left, noDust))
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
        landOn(AppScreen.KITS)
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
     * busy-then-land shape as EVIL TWINS: every crossed pad renders
     * offline.
     */
    fun finishBreed(source: KitShelf.Entry, partner: KitShelf.Entry) {
        pendingBreedWith = null
        if (busy != null) return
        busy = Busy(Copy.BREEDING_BUSY)
        scope.launch {
            val (entry, report) = try {
                withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.breed(source, partner, Random.nextInt()) } }
            } catch (e: Exception) {
                busy = null
                Log.e(TAG, "finishBreed: failed", e)
                dock.say(Copy.BREED_FAILED)
                return@launch
            }
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
            busy = null
            dock.say(Copy.bred(entry.kit.name, report.crossed.size, report.audited.size))
            // The freshly bred kit is the whole point of the tap that
            // landed here — open it, the same instant-gratification
            // landing EVIL TWINS gets by staying on the kit it just changed.
            landKit(entry)
        }
    }

    /**
     * CHOP ALL (XX3 wired in): every picked `.wav` through the same
     * auto-chop pipeline INSTANT KIT already uses (`InstantKit.build` —
     * `ChopReviewModel.chop` → `sendToGrid()` → `KitBuilderModel.fromChop`,
     * the exact chain SEND TO PADS and INSTANT KIT both already run), one
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
        // only in clearTransientState's screen-change reset, because
        // launching (or cancelling) the picker never changes screen at all;
        // without this, the shelf stays stuck reading "PICK A KIT TO
        // CROSS WITH X" and every kit row stays in pick mode after this
        // run finishes, for a hand-off the user has already moved past.
        pendingBreedWith = null
        if (uris.isEmpty() || busy != null) return
        busy = Busy(Copy.CHOP_ALL_BUSY)
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
            dock.say(if (wavCount == 0) Copy.CHOP_ALL_NO_WAVS else Copy.choppedAll(made, wavCount, skipped, failed))
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
        busy = Busy(Copy.XRAY_BUSY)
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
                Log.e(TAG, "xray: failed", e)
                dock.say(Copy.xrayFailed(e.message ?: "the file wouldn't read"))
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
    fun assignPendingSnip(
        file: File,
        slot: Int,
        /** What the dock says once it has landed; the first catch says its own. */
        placedLine: String = Copy.snipPlaced(slot),
        /** After the grid holds the new pad and the dock has spoken: the first catch selects and lights it here. */
        onPlaced: () -> Unit = {},
    ) {
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
                dock.say(placedLine)
                onPlaced()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened - the
                // exception's own detail goes to logcat, not the toast.
                Log.e(TAG, "assignPendingSnip: place failed", e)
                dock.say(Copy.PLACE_FAILED)
            }
        }
    }

    /**
     * SNIPS → LOOP: this snip becomes one track of the six-track grid.
     *
     * The work lives here rather than in `SnipsScreen` because it is IO — a
     * decode, up to eight WAV writes and a sidecar, none of which belongs on
     * the main thread — and because the count it changes is read by the shelf
     * too.
     *
     * The session is re-read from disk on every send rather than kept in
     * memory: LOOP is a separate activity with its own copy, and a track
     * cleared over there has to be what the next send builds on.
     */
    fun sendSnipToLoop(info: SnipStore.Info) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                // Decoded BEFORE the lock: this is the slow part (a whole snip
                // to float32) and it needs nothing from the session. Holding
                // the grid's one writer through it would make a second tap
                // wait for the first tap's decode for no reason.
                //
                // `info.file` is a SNIP, already bounded by
                // SnipStore.IMPORT_MAX_SEC (180s) or real-time capture —
                // readCapped's ceiling is defense in depth, per ConventionTest
                // Law 4, which also requires it over the unbounded read.
                val audio = runCatching { WavReader.readCapped(info.file, TAPE_LOAD_MAX_SEC).snip }
                    .onFailure { e -> Log.e(TAG, "sendSnipToLoop: decode failed", e) }
                    .getOrNull()
                if (audio == null || audio.frameCount <= 0) {
                    return@withContext LoopSend(Copy.LOOP_NOTHING_TO_SEND)
                }

                // Read-modify-write, so the whole of it is one writer's turn.
                // Two quick taps otherwise both read the same session, both
                // pick the same empty track, and the second save drops the
                // first snip while its toast says it landed. See LoopWrites.
                LoopWrites.writing {
                    // A missing sidecar is a first send; a sidecar that will
                    // not parse is not. Starting fresh in that second case
                    // would put a one-track session on top of six tracks
                    // someone built, which is a silent delete — so the two are
                    // told apart here rather than collapsed into one getOrNull.
                    val sidecar = File(loopDir, SessionStore.FILE_NAME)
                    val session = if (sidecar.isFile) {
                        runCatching { SessionStore.load(loopDir) }
                            .onFailure { e -> Log.e(TAG, "sendSnipToLoop: session unreadable", e) }
                            .getOrNull()
                            ?: return@writing LoopSend(Copy.LOOP_UNREADABLE)
                    } else {
                        SessionBuilder.empty(deviceSampleRate(context))
                    }
                    val at = SessionBuilder.nextEmpty(session)
                    // Said in words rather than by a dead button on the row: a
                    // disabled → LOOP would not say WHICH of its reasons applied.
                    if (at < 0) return@writing LoopSend(Copy.loopFull(Session.TRACK_COUNT))

                    // Past the decode, the only way this fails is a write — so
                    // it reports a write failure, never "no audio", which would
                    // send someone looking at the wrong thing.
                    val sent = runCatching {
                        SessionBuilder.send(session, at, info.displayName, info.file.nameWithoutExtension, audio, loopDir)
                    }
                        .onFailure { e -> Log.e(TAG, "sendSnipToLoop: cut failed", e) }
                        .getOrNull()
                        ?: return@writing LoopSend(Copy.LOOP_SEND_FAILED)

                    val saved = runCatching { SessionStore.save(sent.session, loopDir) }
                        .onFailure { e -> Log.e(TAG, "sendSnipToLoop: save failed", e) }
                        .isSuccess
                    if (!saved) return@writing LoopSend(Copy.LOOP_SEND_FAILED)

                    // 1-based: the column a player counts across the grid.
                    val track = sent.trackIndex + 1
                    LoopSend(
                        if (sent.truncated) {
                            Copy.loopTrackTruncated(info.displayName, track, sent.blocks)
                        } else {
                            Copy.loopTrackFilled(info.displayName, track, sent.blocks)
                        },
                    )
                }
            }
            dock.say(outcome.toast)
        }
    }

    /**
     * SYNTH → LOOP: a RESIN or SIREN drone takes the next empty track
     * (docs/superpowers/specs/2026-09-25-resin-drone-design.md;
     * docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md, door 4).
     *
     * [sendSnipToLoop]'s shape without the decode or the WAV writes: a drone
     * is a recipe on the track, rendered only when LOOP bakes it, so this is
     * one read-modify-write of the sidecar under the grid's one writer, with
     * the same refusals (a sidecar that won't read, a full grid).
     *
     * The span is worked out here, against the session just read, rather
     * than trusted from whatever SynthScreen showed in its own preview: a
     * SIREN recipe's own carrier snap depends on DEPTH through the LFO's
     * phase integral, which [DroneFit.spanFor] cannot model (review finding
     * on PR #368), so [SessionBuilder.sendDrone] cannot work it out from
     * `rootMidi` alone the way it can for a RESIN recipe.
     */
    fun sendDroneToLoop(name: String, recipe: JsonValue, rootMidi: Int) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                LoopWrites.writing {
                    val sidecar = File(loopDir, SessionStore.FILE_NAME)
                    val session = if (sidecar.isFile) {
                        runCatching { SessionStore.load(loopDir) }
                            .onFailure { e -> Log.e(TAG, "sendDroneToLoop: session unreadable", e) }
                            .getOrNull()
                            ?: return@writing LoopSend(Copy.LOOP_UNREADABLE)
                    } else {
                        SessionBuilder.empty(deviceSampleRate(context))
                    }
                    val at = SessionBuilder.nextEmpty(session)
                    if (at < 0) return@writing LoopSend(Copy.loopFull(Session.TRACK_COUNT))

                    // Falls back to RESIN's own pitch-only estimate on any
                    // failure — safe (proven no worse than SIREN's true
                    // nudge, docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md's
                    // "After review") — rather than letting a span-fit
                    // surprise turn a drone send into a lost write.
                    val siren = SirenDrone.Spec.fromJson(recipe)
                    val span = if (siren != null) {
                        runCatching { SirenDroneMaker.span(siren, rootMidi, session) }
                            .onFailure { e -> Log.e(TAG, "sendDroneToLoop: SIREN span fit failed, using RESIN's estimate", e) }
                            .getOrDefault(DroneFit.spanFor(rootMidi, session))
                    } else {
                        DroneFit.spanFor(rootMidi, session)
                    }
                    val sent = SessionBuilder.sendDrone(session, at, name, recipe, rootMidi, span)
                    val saved = runCatching { SessionStore.save(sent, loopDir) }
                        .onFailure { e -> Log.e(TAG, "sendDroneToLoop: save failed", e) }
                        .isSuccess
                    if (!saved) return@writing LoopSend(Copy.LOOP_SEND_FAILED)

                    val bars = sent.tracks[at].chain.size * sent.barsPerInterval
                    LoopSend(Copy.droneLanded(Scales.nameOf(rootMidi), at + 1, bars))
                }
            }
            dock.say(outcome.toast)
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
        busy = Busy(Copy.RETRIM_BUSY)
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
                dock.say(Copy.retrimLanded(request.padLabel, treatment))
                retrim = null
                if (open?.dir == request.kitDir) {
                    landOn(AppScreen.KIT)
                    // Through openPadSheet, not a bare assignment: a
                    // RE-TRIM landing back on its pad is one of the three
                    // doors that used to leave the hint nagging (J16).
                    openPadSheet(request.slot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                dock.say(Copy.TAPE_TOO_BIG)
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened - the
                // exception's own detail goes to logcat, not the toast.
                Log.e(TAG, "backOnto: failed", e)
                dock.say(Copy.RETRIM_FAILED)
            } finally {
                busy = null
            }
        }
    }

    /**
     * CATCH A HIT's landing (docs/CATCH.md): the caught cut onto the open
     * kit's pad through the same assign door SNIPS → PAD uses
     * (`CatchModel.land`), under `KitWrites.mutex` like every kit write
     * here. No busy line: catches arrive one after another while the loop
     * runs and each is one short write; the pad's name lighting on the
     * grid is the landing's own signal. The tape is tagged for RE-TRIM
     * only when it is a snip on the shelf — a kit sample TAPE fell back
     * to is no tape to go back to (the same rule `TapeRef.ofSnip` keeps).
     */
    fun catchOnto(landing: CatchLanding) {
        val before = catchSession.chain
        catchSession.chain = scope.launch {
            // Release order is landing order: the write before this one
            // finishes first, whatever the IO dispatcher made of them.
            before?.join()
            try {
                val snipsDir = File(context.filesDir, SnipStore.DIR)
                val tapeName = landing.tape.takeIf { it.parentFile == snipsDir }?.name
                val (updated, pad) = withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(landing.kitDir)
                        // Null: the slot took a pad through another door
                        // since the press — the user's, kept (CatchModel.land).
                        val pad = CatchModel.land(model, tapeName, landing.cut, landing.caught)
                        if (pad != null) model.save()
                        model.kit to pad
                    }
                }
                val tag = PadBanks.tag(landing.caught.slot)
                if (pad == null) {
                    dock.say(Copy.catchTaken(tag, updated.pad(landing.caught.slot)?.displayName ?: "TAKEN"))
                    return@launch
                }
                // Same identity guard as backOnto: the write must not weld
                // itself onto whichever kit is open now.
                if (open?.dir == landing.kitDir) open = open?.copy(kit = updated)
                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                catchSession.landed += pad.slot
                dock.say(if (landing.caught.hit == null) Copy.caughtBetween(tag) else Copy.caught(tag, pad.displayName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened - the
                // exception's own detail goes to logcat, not the toast.
                Log.e(TAG, "catchOnto: failed", e)
                dock.say(Copy.CATCH_FAILED)
            }
        }
    }

    /**
     * DONE on the catch grid: once every queued landing has finished,
     * what actually landed — and, with anything caught, KIT opened on the
     * first catch's bank, the way ONTO opens it after a chop lands
     * (`kitBankRequest`). Waiting on the chain is what keeps DONE honest:
     * a catch whose write failed is not announced, and KIT never opens a
     * beat before the pads it promises are there.
     */
    fun catchDone() {
        val target = open ?: return
        scope.launch {
            catchSession.chain?.join()
            val landed = catchSession.landed.toList()
            catchSession.landed.clear()
            dock.say(Copy.catchDone(landed.size, target.kit.name))
            if (landed.isNotEmpty() && open?.dir == target.dir) {
                kitBankRequest = PadBanks.bankOf(landed.first())
                landOn(AppScreen.KIT)
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
            dock.say(Copy.READ_GROOVE_NEEDS_KIT)
            return
        }
        if (busy != null) return
        busy = Busy(Copy.READ_GROOVE_BUSY)
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
                dock.say(Copy.grooveRead(reading.hits, reading.bars, Math.round(reading.bpm)))
                grooveReload++
                landOn(AppScreen.GROOVE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // TAPE_LOAD_MAX_SEC's own safety net, same as TapeScreen's
                // readMono: an Error, not an Exception, so it needs its own
                // catch or the process dies instead of this toast firing.
                dock.say(Copy.TAPE_TOO_BIG)
            } catch (e: IllegalArgumentException) {
                dock.say(Copy.grooveRefused(e.message ?: "the ear refused"))
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened - the
                // exception's own detail goes to logcat, not the toast.
                Log.e(TAG, "readGroove: failed", e)
                dock.say(Copy.READ_GROOVE_FAILED)
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
            dock.say(Copy.READ_GROOVE_NEEDS_KIT)
            return
        }
        if (busy != null) return
        busy = Busy(Copy.FEEL_BUSY)
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
                dock.say(Copy.feelStolen(felt.covered))
                grooveReload++
                landOn(AppScreen.GROOVE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // See readGroove's own catch above: an Error, not an
                // Exception, so it needs its own catch or the process dies.
                dock.say(Copy.TAPE_TOO_BIG)
            } catch (e: IllegalArgumentException) {
                dock.say(Copy.feelRefused(e.message ?: "the ear refused"))
            } catch (e: Exception) {
                Log.e(TAG, "stealFeel: failed", e)
                dock.say(Copy.FEEL_FAILED)
            } finally {
                busy = null
            }
        }
    }

    /**
     * INSTANT KIT (F2.2): the one tap on TAPE — the selection (or the whole
     * deck) chopped with the defaults and landed on the grid without the
     * review, the same busy-then-land shape as a fresh tape. CHOP can still open
     * the result later to argue with the chips.
     *
     * [hadSelection] is TAPE's answer, not this function's guess (September
     * UAT, finding 19): the toast has to say which of the two it chopped,
     * and [range] alone cannot tell them apart — an IN/OUT the user dragged
     * across the whole tape arrives here identical to no selection at all.
     */
    fun instantKit(file: File, range: IntRange, hadSelection: Boolean) {
        if (busy != null) return
        busy = Busy(Copy.INSTANT_KIT_BUSY)
        scope.launch {
            // `finally` owns the busy overlay: whichever way this leaves
            // (built, refused, or the scope cancelled underneath it), the
            // screen never stays stuck on CHOPPING….
            try {
                val (entry, result) = withContext(Dispatchers.IO) {
                    shelf.instantKit(file, range, File(context.filesDir, SnipStore.DIR))
                }
                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                dock.say(Copy.instantKit(result.sliceCount, result.chokeSet, wholeTape = !hadSelection))
                landKit(entry)
            } catch (e: CancellationException) {
                // Leaving the screen is not a failure; let the scope have it.
                throw e
            } catch (e: OutOfMemoryError) {
                // shelf.instantKit re-decodes `tapeData.sourceFile` through
                // WavReader.readCapped now (see KitShelf.instantKit), but
                // this is still an Error, not an Exception, so it needs its
                // own catch — same as TapeScreen's readMono.
                dock.say(Copy.TAPE_TOO_BIG)
            } catch (e: Exception) {
                // Law 3: when it breaks, say exactly what happened - the
                // exception's own detail goes to logcat, not the toast.
                Log.e(TAG, "instantKit: failed", e)
                dock.say(Copy.INSTANT_KIT_FAILED)
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
        busy = Busy(Copy.PACKING_BUSY)
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { shelf.pack(source, ShareOut.shareDir(context)) }
                busy = null
                dock.say(if (ShareOut.send(context, file, ShareOut.ZIP_MIME, source.kit.name)) Copy.kitPacked(source.kit.name) else Copy.SHARE_NOWHERE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "shareKit: failed", e)
                dock.say(Copy.SHARE_FAILED)
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
        busy = Busy(Copy.ROOM_FORGET_BUSY)
        scope.launch {
            try {
                // The bin may freshen the name ("FUNK ROOM 2") - the toast says the name it went in under.
                val binned = withContext(Dispatchers.IO) { shelf.forgetRoom(room) }
                roomsRevision++
                dock.say(Copy.roomForgotten(binned.room.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "forgetRoom: failed", e)
                dock.say(Copy.ROOM_FORGET_FAILED)
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
        busy = Busy(Copy.PACKING_BUSY)
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { shelf.packRoom(room, ShareOut.shareDir(context)) }
                busy = null
                dock.say(if (ShareOut.send(context, file, ShareOut.ZIP_MIME, room.name)) Copy.roomPacked(room.name) else Copy.SHARE_NOWHERE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "shareRoom: failed", e)
                dock.say(Copy.SHARE_FAILED)
            } finally {
                busy = null
            }
        }
    }

    /**
     * SHARE on a snip row: the snip leaves the app.
     *
     * The gap this closes: a loop-grid bounce lands in SNIPS - its own
     * toast says "IT IS IN SNIPS NOW" - and SNIPS' only outbound action was
     * → LOOP, back into the app it came from. Sounds could leave five ways
     * (EXPORT, a packed kit, a packed room, BACKUP, GROOVE's chart); the
     * thing the user actually made could not leave at all.
     *
     * Staged into the share cache rather than sent where it lies, which is
     * not a preference: `res/xml/share_paths.xml` covers that cache and
     * EXPORT's output and deliberately nothing else, so
     * `FileProvider.getUriForFile` would throw on a path under the files
     * directory - which is exactly where `SnipStore` keeps snips. The copy
     * is also what lets [SnipStore.shareName] give it a readable name
     * instead of the internal `snip_<millis>_<name>.wav`.
     *
     * `overwrite = false` on purpose, with the free name asked for first: a
     * same-named copy already in the cache may still be being read by the
     * app the user sent it to a moment ago, and overwriting it corrupts
     * that transfer with no sign on this end.
     */
    fun shareSnip(info: SnipStore.Info) {
        if (busy != null) return
        busy = Busy(Copy.SHARE_BUSY)
        scope.launch {
            try {
                val staged = withContext(Dispatchers.IO) {
                    val dir = ShareOut.shareDir(context)
                    val name = SnipStore.shareName(info.displayName) { File(dir, it).exists() }
                    info.file.copyTo(File(dir, name), overwrite = false)
                }
                busy = null
                dock.say(if (ShareOut.send(context, staged, ShareOut.WAV_MIME, info.displayName)) Copy.snipReady(info.displayName) else Copy.SHARE_NOWHERE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "shareSnip: failed", e)
                dock.say(Copy.SHARE_FAILED)
            } finally {
                busy = null
            }
        }
    }

    /** RESTORE on a binned room: back onto the shelf, the toast names it. */
    fun restoreRoom(binned: Rooms.Binned) {
        if (busy != null) return
        busy = Busy(Copy.ROOM_RESTORE_BUSY)
        scope.launch {
            try {
                val room = withContext(Dispatchers.IO) { shelf.restoreRoom(binned) }
                roomsRevision++
                dock.say(Copy.roomRestored(room.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "restoreRoom: failed", e)
                dock.say(Copy.ROOM_RESTORE_FAILED)
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
        busy = Busy(Copy.ROOM_BIN_EMPTY_BUSY)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { shelf.emptyRoomsBin() }
                roomsRevision++
                dock.say(Copy.roomBinEmptied)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "emptyRoomsBin: failed", e)
                dock.say(Copy.ROOM_BIN_EMPTY_FAILED)
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
        busy = Busy(Copy.KIT_DELETE_BUSY)
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.deleteKit(entry) } }
                if (ok) {
                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                    dock.say(Copy.kitDeleted(entry.kit.name))
                    if (open?.dir == entry.dir) {
                        open = null
                        // The kit under the open benches is gone; every
                        // overlay over it goes with it, which is what a
                        // tab-style departure clears.
                        if (nav.location.place != Place.SHELF) {
                            landOn(AppScreen.KITS)
                            padSheetSlot = null
                            takesBinOpen = false
                            padCaptureSlot = null
                            grainFieldSlot = null
                            spliceSlot = null
                            stackSlot = null
                            arrangeOpen = false
                        }
                    }
                } else {
                    dock.say(Copy.KIT_DELETE_FAILED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same failure `ok == false` already reports above - one
                // word for both, whether the write refused or threw.
                Log.e(TAG, "deleteKit: failed", e)
                dock.say(Copy.KIT_DELETE_FAILED)
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
        busy = Busy(Copy.KIT_RENAME_BUSY)
        scope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.renameKit(entry, newName) } }
                if (renamed != null) {
                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                    dock.say(Copy.kitRenamed(renamed.kit.name))
                    if (open?.dir == entry.dir) open = renamed
                } else {
                    dock.say(Copy.KIT_RENAME_FAILED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same failure `renamed == null` already reports above - one
                // word for both, whether the write refused or threw.
                Log.e(TAG, "renameKit: failed", e)
                dock.say(Copy.KIT_RENAME_FAILED)
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
            dock.say(Copy.BACKUP_EMPTY)
            return
        }
        busy = Busy(Copy.PACKING_BUSY)
        scope.launch {
            try {
                val (result, presets) = withContext(Dispatchers.IO) {
                    val r = shelf.backup(ShareOut.shareDir(context), System.currentTimeMillis())
                    // How many presets rode along, for the toast: the file is
                    // in the zip byte for byte, so this is the strip's own
                    // count, and a file this build cannot read counts none.
                    val n = if (r.extras.isEmpty()) 0 else runCatching { UserPresets.read(shelf.root).size }.getOrDefault(0)
                    r to n
                }
                busy = null
                if (!ShareOut.send(context, result.file, ShareOut.ZIP_MIME, result.file.nameWithoutExtension)) {
                    dock.say(Copy.SHARE_NOWHERE)
                } else {
                    // Every kit in: the toast. Preflight refused some: the box,
                    // naming each and why - it waits behind the chooser and is
                    // read on the way back.
                    val boxed = LandingNote.backedUp(result.packed, result.skipped, presets)
                    if (boxed != null) openNote(boxed) else dock.say(Copy.backedUp(result.packed.size, result.skipped.size, presets))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "backupShelf: failed", e)
                dock.say(Copy.BACKUP_FAILED)
            } finally {
                busy = null
            }
        }
    }

    /**
     * SEND TO BENCH (docs/WORKSHOP.md): every teach log on the shelf, the
     * bin included, merged into one zip and handed to the chooser - the
     * last mile TEACH THE MACHINE never had. [backupShelf]'s own shape:
     * pack under the share cache on IO, then the chooser, then a toast
     * that counts what is in the file and never says SENT.
     *
     * Features and labels only, as SETUP's consent line promises; nothing
     * here opens a WAV. Asks `gather` before `pack` so an empty shelf is
     * refused in words that say why - and which of the two reasons it is.
     */
    fun sendToBench() {
        if (busy != null) return
        busy = Busy(Copy.PACKING_BUSY)
        scope.launch {
            try {
                val (logs, notes, presets) = withContext(Dispatchers.IO) {
                    Triple(BenchExport.gather(shelf.root), BenchExport.notes(shelf.root), BenchExport.presets(shelf.root))
                }
                if (logs.isEmpty() && notes.isEmpty() && presets.isEmpty()) {
                    dock.say(if (teachEnabled) Copy.BENCH_EMPTY else Copy.BENCH_EMPTY_TEACH_OFF)
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    BenchExport.pack(shelf.root, ShareOut.shareDir(context), ShareOut.stamp(System.currentTimeMillis()))
                }
                busy = null
                if (!ShareOut.send(context, result.file, ShareOut.ZIP_MIME, result.file.nameWithoutExtension)) {
                    dock.say(Copy.SHARE_NOWHERE)
                } else {
                    dock.say(Copy.benchPacked(result.labels, result.ratings, result.notes.size, result.presets.size, result.kits))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sendToBench: failed", e)
                dock.say(Copy.BENCH_FAILED)
            } finally {
                busy = null
            }
        }
    }

    /**
     * SEND HITS TO BENCH (docs/WORKSHOP.md, WS3): the hits labelled on pad
     * sheets, as audio - the one button in the app that sends sound, on
     * purpose, under its own note. [sendToBench]'s own shape, its own zip:
     * the two never share a file, so the bench zip's never-audio promise
     * is kept by construction rather than by care.
     */
    fun sendHitsToBench() {
        if (busy != null) return
        busy = Busy(Copy.PACKING_BUSY)
        scope.launch {
            try {
                val hits = withContext(Dispatchers.IO) { LabelledHits.list(shelf.root) }
                if (hits.isEmpty()) {
                    dock.say(Copy.HITS_EMPTY)
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    LabelledHits.pack(shelf.root, ShareOut.shareDir(context), ShareOut.stamp(System.currentTimeMillis()))
                }
                busy = null
                if (!ShareOut.send(context, result.file, ShareOut.ZIP_MIME, result.file.nameWithoutExtension)) {
                    dock.say(Copy.SHARE_NOWHERE)
                } else {
                    dock.say(Copy.hitsPacked(result.hits.size))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sendHitsToBench: failed", e)
                dock.say(Copy.HITS_FAILED)
            } finally {
                busy = null
            }
        }
    }

    /**
     * The title bar's NOTE chip (docs/WORKSHOP.md, WS4): reads where the
     * phone is — the screen as the menu row names it, the open kit, the
     * pad whose sheet is up — and when, into the stamp the dialog shows
     * and KEEP writes. The same date stamp BACKUP puts on its zip, so a
     * note and the hand-out it rides in read the same clock.
     */
    fun openBenchNote() {
        benchNote = BenchNotes.Stamp(
            at = ShareOut.stamp(System.currentTimeMillis()),
            screen = location.top?.label ?: location.stage?.label ?: location.place.label,
            kit = open?.kit?.name,
            pad = padSheetSlot?.let { PadNoteMap.labelForPad(it) },
        )
    }

    /**
     * KEEP on the BENCH NOTE slip: the stamp and the words, appended to
     * `Bench/notes.jsonl` beside the kits — a folder with no `kit.json`,
     * so the shelf never lists it — on IO like every other write. The
     * toast names the screen the note was taken on and the button that
     * carries it off the phone; SEND TO BENCH reads the file and renders
     * every note in its manifest as a line for `docs/BENCH.md`.
     */
    fun keepBenchNote(entry: BenchNotes.Note) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { BenchNotes.append(BenchNotes.file(shelf.root), listOf(entry)) }
                dock.say(Copy.noted(entry.screen))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "keepBenchNote: failed", e)
                dock.say(Copy.NOTE_FAILED)
            }
        }
    }

    /** IN KEY: every tonal pad into the kit's key by its tune fields; the toast counts what moved. */
    fun inKey() {
        val source = open ?: return
        if (busy != null) return
        val key = source.kit.key
        if (key == null) {
            dock.say(Copy.IN_KEY_NEEDS_KEY)
            return
        }
        // On the job board, like setKey above: it reads and retunes every
        // tonal pad of the kit, which is the longer of the two, so the line
        // matters here.
        val job = jobs.begin(Busy(Copy.IN_KEY_BUSY))
        scope.launch {
            try {
                val (entry, moved) = try {
                    // Same reasoning as setKey above: inKey is an
                    // open→mutate→save on kit.json.
                    withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.inKey(source) } }
                } catch (e: Exception) {
                    Log.e(TAG, "inKey: failed", e)
                    dock.say(Copy.IN_KEY_FAILED)
                    return@launch
                }
                // Same identity guard as setKey above — inKey sets no `busy`
                // either, so nothing stops a tab-away-and-reopen mid-write.
                if (open?.dir == source.dir) open = entry
                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                dock.say(if (moved.isEmpty()) Copy.IN_KEY_NONE else Copy.inKey(moved.size, com.snipsnap.shell.KeyPicker.label(key)))
            } finally {
                job.end()
            }
        }
    }

    // Shared by the place and stage rows, the dock's door and the system
    // Back fallback below — one reset, one navigation, so they can never
    // drift apart on which overlay/hand-off state a departure must clear.
    // The back stack itself is `Navigation`'s (J11): every move through
    // [landOn], [goToScreen] or the rows is pushed there, and Back pops it
    // in the order it was pushed — a bench, then a stage, then a place.

    /**
     * The transient state a screen change must abandon — overlays, armed
     * hand-offs, one-shot requests. Extracted from [goToScreen] so Back
     * runs exactly the same resets: returning to KIT with a pad sheet
     * still armed from two screens ago would be a worse bug than the one
     * the back stack fixes.
     */
    fun clearTransientState() {
        // Leaving KIT for another tab must not leave the
        // sheet armed to reopen on the same slot next time
        // KIT comes back into view.
        padSheetSlot = null
        takesBinOpen = false
        padCaptureSlot = null
        grainFieldSlot = null
        spliceSlot = null
        stackSlot = null
        // A tab switch away from GROOVE before GrooveScreen's own
        // LaunchedEffect consumed the arm (should never be observable — that
        // effect runs the same composition pass this sets `screen` in — but
        // matching every other one-shot request's own defensive reset here
        // costs nothing and rules it out structurally rather than by timing.
        auditionArmSlot = null
        arrangeOpen = false
        // SNIPS is shelf-level, not KIT-scoped, but the same
        // "leaving must not leave an overlay/hand-off armed"
        // reasoning applies: a tab switch away from KITS
        // mid-pick abandons the → PAD hand-off rather than
        // leaving KitsScreen stuck on "PICK A KIT FOR THIS
        // SNIP" forever.
        pendingSnipAssign = null
        pendingSnipTarget = null
        pendingAddTo = null
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
        binOpen = false
        // X-RAY is shelf-level too, same reasoning again.
        xray = null
        // DOUBLES likewise.
        doublesOpen = false
    }

    /** The user leaving for [target]: the landing, and everything transient abandoned. */
    fun goToScreen(target: AppScreen) {
        landOn(target)
        clearTransientState()
    }

    /** The place row: a place opens on the stage it was last on, benches closed. */
    fun goToPlace(place: Place) {
        nav.go(place)
        dock.moved(nav.location)
        clearTransientState()
    }

    /** The stage row: a stage of the current place, benches closed. */
    fun goToStage(stage: Stage) {
        nav.stage(stage)
        dock.moved(nav.location)
        clearTransientState()
    }

    /** The dock's door, taken: it is gone from the dock and the user is where it said. */
    fun takeDockDoor() {
        val door = dock.take() ?: return
        nav.take(door)
        dock.moved(nav.location)
        clearTransientState()
    }

    /**
     * Opening a project (`docs/SCREEN_PLAN_2026_10.md` §3.1): it becomes
     * the open project, its active kit becomes the open kit, and PROJECT
     * opens on PADS. A project whose active kit is gone from the shelf
     * opens with no kit, which PADS already says.
     */
    fun openProject(p: Project) {
        if (busy != null) return
        project = p
        open = kits.firstOrNull { it.dir.name == p.activeKit }
        goToScreen(AppScreen.KIT)
    }

    /**
     * Opening a kit row: the first project that holds it, else a new
     * project named after it — the migration's own rule, for a kit that
     * arrived since the shelf was opened (`ShelfModel.projectForKit`).
     * The kit itself is the one tapped, whatever the project's active kit
     * says: the row the user chose is the kit they want on the grid.
     */
    fun openKit(entry: KitShelf.Entry) {
        if (busy != null) return
        // Raised here, before the launch, so a second tap in the beat before
        // the coroutine starts finds it up and does nothing: `projectForKit`
        // is not synchronised, so two taps on a kit no project holds could
        // make two projects.
        busy = Busy(Copy.OPENING_KIT_BUSY)
        scope.launch {
            val p = try {
                withContext(Dispatchers.IO) { shelfModel.projectForKit(entry.dir.name).activated(entry.dir.name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "openKit: no project for ${entry.dir.name}", e)
                dock.say(Copy.PROJECT_OPEN_FAILED)
                return@launch
            } finally {
                // The landing below sets state directly and calls no guarded
                // handler, so nothing needs the flag down first; it comes down
                // here, in the same place as `newProject`'s, so a failure or a
                // cancel cannot leave it up.
                busy = null
            }
            // Re-read after the await: the shelf may have moved under us.
            project = p
            open = kits.firstOrNull { it.dir == entry.dir } ?: entry
            goToScreen(AppScreen.KIT)
        }
    }

    /**
     * `+ FROM SHELF`: the shelf in pick mode, returning here with the kit
     * tapped added to the open project. With no project open there is
     * nothing to add to, and the pages that offer this door only offer it
     * when one is open.
     */
    fun addFromShelf() {
        val p = project ?: return
        goToScreen(AppScreen.KITS)
        pendingAddTo = p
    }

    /**
     * The kit switcher (plan §3.3): [kitFolder] becomes the open project's
     * active kit, saved through `ShelfModel.activate`, and the grid shows
     * it. A failure says so and leaves the grid where it was.
     */
    fun activateKit(kitFolder: String) {
        if (busy != null) return
        val p = project ?: return
        // After the `project ?: return` above, so a refused call cannot leave
        // the flag up; before the launch, so a second tap finds it.
        busy = Busy(Copy.SWITCHING_KIT_BUSY)
        scope.launch {
            val updated = try {
                withContext(Dispatchers.IO) { shelfModel.activate(p.name, kitFolder) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "activateKit: failed", e)
                dock.say(Copy.PROJECT_OPEN_FAILED)
                return@launch
            } finally {
                // Nothing below calls a guarded handler. Down here, as in
                // `newProject`, so a failure or a cancel cannot leave it up.
                busy = null
            }
            project = updated
            val activated = kits.firstOrNull { it.dir.name == kitFolder }
            open = activated
            // The grid changes under the user's finger: say which kit it is
            // now (F4). A switch that said nothing left the header as the
            // only evidence.
            if (activated != null) dock.say(Copy.onTheGrid(activated.kit.name))
        }
    }

    /**
     * `+ FROM SHELF`'s pick: [entry] joins [target] and becomes its active
     * kit (`ShelfModel.addKit`, then `activate`), and PADS comes back with
     * it on the grid. The kit is referenced, not copied — edit it in one
     * project and every project sees the edit (`DIRECTION_2026_10.md` §2).
     */
    fun addKitToProject(target: Project, entry: KitShelf.Entry) {
        if (busy != null) return
        // Raised here, before the launch, so a second tap finds it up.
        busy = Busy(Copy.ADDING_KIT_BUSY)
        scope.launch {
            val updated = try {
                withContext(Dispatchers.IO) {
                    shelfModel.addKit(target.name, entry.dir.name)
                    shelfModel.activate(target.name, entry.dir.name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "addKitToProject: failed", e)
                dock.say(Copy.KIT_ADD_FAILED)
                return@launch
            } finally {
                // Nothing below calls a guarded handler. Down here, as in
                // `newProject`, so a failure or a cancel cannot leave it up.
                busy = null
            }
            project = updated
            open = kits.firstOrNull { it.dir == entry.dir } ?: entry
            goToScreen(AppScreen.KIT)
        }
    }

    /**
     * NEW PROJECT ▸: an empty project, opened on PADS with no kit, where the switcher offers the shelf.
     * Its name is a roll ([Names.roll]) against the projects already on the shelf, not "New Project 2".
     */
    fun newProject() {
        if (busy != null) return
        // Raised here, before the launch, so a second tap in the beat before
        // the coroutine starts finds it up and does nothing: two quick taps
        // could make two projects.
        busy = Busy(Copy.NEW_PROJECT_BUSY)
        scope.launch {
            val p = try {
                withContext(Dispatchers.IO) {
                    shelfModel.newProject(Names.roll(taken = shelfModel.projects().map { it.name }.toSet()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "newProject: failed", e)
                dock.say(Copy.PROJECT_OPEN_FAILED)
                return@launch
            } finally {
                // Down before the landing below, not after it: `openProject`
                // opens with `if (busy != null) return`, and would refuse the
                // project this job just made.
                busy = null
            }
            openProject(p)
        }
    }

    // The first catch (the cold shelf's way in): a pad PADS is to light once,
    // asked for by the landing and consumed by the grid when it has flashed.
    var lightRequest by remember { mutableStateOf<Int?>(null) }
    var catchingFirst by remember { mutableStateOf(false) }

    /**
     * The first SNIP of a cold shelf - no project, no kit - has a home made
     * for it, instead of EMPTY_SHELF_FOR_ASSIGN's errand: a project (a roll,
     * like NEW PROJECT's), an empty kit in it (the EMPTY GRID starter under
     * a rolled name, as INSTANT KIT names its kits), and the snip on A01
     * through [assignPendingSnip], the one path SNIPS -> PAD uses. PADS comes
     * up with A01 selected and lit, and the dock says where the sound is.
     *
     * The shelf is read from disk, not from [kits] and [projects], which are
     * only as fresh as the last visit to the shelf. A catch that finds
     * anything there leaves the snip where every snip waits, in SNIPS.
     */
    fun catchFirstSound(file: File) {
        if (catchingFirst || busy != null || pendingSnipAssign != null) return
        catchingFirst = true
        scope.launch {
            try {
                val made = try {
                    withContext(Dispatchers.IO) {
                        if (KitStore.list(shelf.root).isNotEmpty() || ProjectStore.list(shelf.root).isNotEmpty()) {
                            return@withContext null
                        }
                        // The kit first: a failure after it leaves a kit the
                        // shelf's own migration adopts, where a failure after
                        // a project would leave an empty project to explain.
                        val entry = shelf.render(StarterKits.byId("blank")!!, Random.nextInt(), shelf.rolledName())
                        val named = shelfModel.newProject(Names.roll(taken = shelfModel.projects().map { it.name }.toSet()))
                        shelfModel.addKit(named.name, entry.dir.name)
                        entry to shelfModel.activate(named.name, entry.dir.name)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "catchFirstSound: the first project and kit were not made", e)
                    dock.say(Copy.CREATE_FAILED)
                    return@launch
                }
                if (made == null) return@launch
                val (entry, created) = made
                kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                project = created
                open = kits.firstOrNull { it.dir == entry.dir } ?: entry
                goToScreen(AppScreen.KIT)
                // The first catch is the loop shown once: record, snip, land.
                // The tape stops here, so the dock's line (the sentence that
                // says where the sound went) has the dock to itself; while
                // armed, the capture region holds that slot. The person who
                // wants more arms it again from the chip, now knowing how.
                MicSessionService.eject(context)
                assignPendingSnip(
                    file, 1,
                    placedLine = Copy.FIRST_CATCH_LANDED,
                    onPlaced = {
                        nav.padsSelectedSlot = 1
                        lightRequest = 1
                    },
                )
            } finally {
                catchingFirst = false
            }
        }
    }
    // A SNIP that lands is the cold shelf's cue. Baselined by file, as the
    // snip errors above are by attempt: whatever the session last wrote
    // before this composition mounted is not a new catch.
    val lastSnip by MicSessionService.lastSnipFile.collectAsState()
    var lastSnipSeen by remember { mutableStateOf(MicSessionService.lastSnipFile.value) }
    LaunchedEffect(lastSnip) {
        val file = lastSnip ?: return@LaunchedEffect
        if (file == lastSnipSeen) return@LaunchedEffect
        lastSnipSeen = file
        catchFirstSound(file)
    }

    // The beat (plan §3.4): one clock for the project, kept here rather than
    // in the screen that draws it, so it plays on when the user leaves BEAT.
    // The dock's chip is its control; GROOVE draws it, edits its program and
    // pushes what it shows into it, and rides its clock for RECORD, BOUNCE
    // and AUDITION. Over the open kit, read live; its settings stand in for
    // GROOVE's own only when the beat is started with GROOVE never opened.
    val hosts = remember { PadHosts(context) }

    fun savePerformance(settings: com.snipsnap.synth.AquariumPerformanceSettings, onResult: (String?) -> Unit) {
        val source = open
        if (source == null || busy != null) {
            onResult("KIT IS BUSY · TRY SAVE AGAIN")
            return
        }
        scope.launch {
            val kit = try {
                withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock { com.snipsnap.shell.AquariumPerformance.save(source.dir, settings) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "savePerformance: failed", e)
                onResult("COULD NOT SAVE PERFORMANCE · TRY AGAIN")
                return@launch
            }
            val entry = KitShelf.Entry(source.dir, kit)
            // Saving stays with its original kit even if navigation moves.
            hosts.peek(source.dir)?.request(entry)
            if (open?.dir == source.dir) open = entry
            onResult(null)
            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
        }
    }

    val grooveSettings by rememberUpdatedState(BeatPlayer.Settings(grooveProgIndex, grooveSwingPercent, grooveFeel, grooveSeed))
    val beat = remember {
        BeatPlayer(hosts = hosts, scope = scope, kitNow = { open }, settings = { grooveSettings })
    }
    // A groove rewritten on disk while GROOVE is not up to reload itself
    // (TAPE's READ AS GROOVE and STEAL THE FEEL, ORBIT's CLIP ▸ KIT, all of
    // which bump `grooveReload`) would leave the beat playing yesterday's.
    // With GROOVE up it reloads from the same request and pushes its own
    // program, which is the one to hear.
    LaunchedEffect(grooveReload) {
        val grooveUp = location.top == null && location.stage == Stage.BEAT && !(arrangeOpen && open != null)
        if (grooveReload > 0 && !grooveUp) beat.reloadFromDisk { dock.say(it) }
    }
    // A different kit is a different beat: the program, the position and the
    // hold on the old kit's pads all belonged to it. Also the end of the
    // app's composition, which is what lets the last host close.
    DisposableEffect(open?.dir) { onDispose { beat.forget() } }
    // The bank follows an edit made while no screen is holding the kit's
    // pads - the pad sheet's TUNE, heard on a beat that is playing under it.
    LaunchedEffect(open?.kit) { open?.let { beat.kitChanged(it) } }
    // Drive one clock for the app's lifetime.
    // Live-bus metadata does not cancel a running print, recording or beat clock.
    // Actual pad/tempo edits retain the transport's existing restart policy.
    LaunchedEffect(beat.playing, open?.dir, open?.kit?.pads, open?.kit?.tempoBpm) { beat.run() }
    // What the dock reads. The bar is written to the beat only when it
    // changes, so this follows at bar granularity and never reads the
    // position, which moves every frame.
    LaunchedEffect(beat) {
        snapshotFlow {
            when {
                beat.recording || beat.countingIn -> DockModel.Transport.Recording(beat.bar)
                beat.playing -> DockModel.Transport.Playing(open?.kit?.tempoBpm ?: KitPreview.DEFAULT_BPM, beat.bar)
                else -> DockModel.Transport.Stopped
            }
        }.collect { dock.transport = it }
    }
    // Backgrounding the app silences the beat: it is its own audio voice, but
    // a focus loss is not the only way the app stops being heard.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, beat) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) beat.silence()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // ORBIT, SPLIT, ARRANGE and LOOP each make the sound they show with an
    // engine of their own, and a second stream on a device falls back to the
    // shared path for the rest of its life, so the beat stops when one opens
    // (v1) and is not offered while one is up. The PADS grid is not among
    // them: it holds the same host the beat plays through.
    val beatMustStop = location.top == Bench.ORBIT || location.top == Bench.SPLIT || location.top == Bench.LOOP ||
        (location.top == null && location.stage == Stage.BEAT && arrangeOpen && open != null)

    /**
     * After every groove write on BEAT, the project's beat follows: the
     * kit-side `groove.json` is copied to the project's `beat/`, so OUT's
     * session card exports what BEAT shows. The kit keeps its file because
     * every per-kit exporter still reads it (`DIRECTION_2026_10.md` §2);
     * reading the beat from the project instead waits for the exporters to
     * take a project. A failed copy is logged, not toasted: the kit-side
     * write — the one the user asked for — has already landed.
     */
    fun mirrorBeat() {
        val p = project ?: return
        val kitDir = open?.dir ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ProjectStore.saveBeat(ProjectStore.dir(shelf.root, p.name), GrooveStore.load(kitDir)) }
                    .onFailure { Log.e(TAG, "mirrorBeat: the project's beat did not follow", it) }
            }
        }
    }

    // The gear's two-row sheet. Open/closed state, not a navigation: the
    // sim counts HELP as one step from anywhere and this is the half of
    // that step that moves nothing.
    var gearOpen by remember { mutableStateOf(false) }
    // The dock's capture choice (mic, or another app), the same shape.
    var captureSheetOpen by remember { mutableStateOf(false) }

    // System Back, root policy: with no KIT-scoped or shelf-level overlay
    // drawn (every such overlay owns its own BackHandler, mounted only while
    // it's on screen, which always wins over this one — Compose's back
    // dispatcher is LIFO and those are registered deeper/later), Back pops
    // `Navigation`'s stack — a bench, then a stage, then a place, the order
    // they were pushed (J11). One exclusion remains, for the screen that
    // owns its own one-level back door: KEYS (its own "◄ SHELF" chip, via
    // `onBack`, which also silences the instrument before leaving — this
    // generic pop does not). SPLIT used to be excluded too, which left Back
    // on it to finish the Activity; as a bench it pops like any other.
    //
    // "Drawn", not "set": a bench (SETUP, HELP, KEYS) opens over whatever
    // is showing without clearing it, so a pad sheet can be armed under
    // SETUP with its own BackHandler out of the composition. Back there
    // must pop SETUP, not be left to a handler that isn't mounted.
    val overlayDrawn = location.top == null && when (location.stage) {
        Stage.PADS -> padSheetSlot != null || grainFieldSlot != null || spliceSlot != null || stackSlot != null || takesBinOpen ||
            padCaptureSlot != null
        Stage.BEAT -> arrangeOpen
        null -> location.place == Place.SHELF && (binOpen || doublesOpen || xray != null)
        else -> false
    }
    val anyOverlayOpen = overlayDrawn || benchNote != null || gearOpen || captureSheetOpen || kitSheetOpen
    //
    // KITS is no longer among the exclusions (J11): with a real stack
    // there is nothing special about the shelf except that it is usually
    // where the stack bottoms out, and `nav.canGoBack` is what decides
    // that now. An empty stack leaves Back to the system default, which
    // finishes the Activity — still the normal Android expectation for a
    // root screen, but reached by being at the bottom of the history
    // rather than by being a particular tab. The comment sits here rather
    // than inside the argument list because `law - BackHandler is
    // registered unconditionally except at allowlisted, self-checked
    // sites` matches the call's argument text against its allowlist
    // verbatim.
    BackHandler(
        enabled = !anyOverlayOpen && nav.canGoBack &&
            location.top != Bench.KEYS &&
            note == null && !captureBlocked && !micPermissionDenied,
    ) {
        val from = nav.location
        nav.back()
        // Popping a bench returns to what was under it, as it was — SETUP
        // over a pad sheet comes back to the pad sheet. Popping a stage or
        // a place is a departure, and abandons what a departure abandons.
        val to = nav.location
        dock.moved(to)
        if (to.place != from.place || to.stage != from.stage) clearTransientState()
    }

    TapeTheme(
        scheme,
        LocalHaptics provides haptics,
        LocalReduceMotion provides reduceMotion,
        LocalBusySink provides busySink,
        LocalJobs provides jobs,
    ) {
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
                // The NOTE chip only while the WORKSHOP is open; a phone
                // that never knocked draws the bar exactly as before. The
                // gear is always there: SETUP and HELP, one tap from anywhere.
                TitleBar(projectName = project?.name, onNote = if (workshopOpen) ::openBenchNote else null, onGear = { gearOpen = true })
                // The frame (`docs/SCREEN_PLAN_2026_10.md` §1): the four
                // places, then the stage row where the place has stages.
                PlaceRow(current = location.place, onSelect = ::goToPlace)
                val stage = location.stage
                if (stage != null) {
                    StageRow(current = stage, onSelect = ::goToStage)
                }
                // A bench that has no back chip of its own gets the frame's,
                // reading what the model says it returns to. The screens
                // that draw their own (KEYS, SPLIT, ORBIT, the pad benches)
                // keep theirs until their own step of the plan rewords them.
                val top = location.top
                if (top != null && top in FRAME_CHIP_BENCHES) {
                    DoorChip(nav.backChip ?: top.backChip, onClick = { nav.closeBench() })
                }
                Box(Modifier.weight(1f)) {
                    // Captured once, like `open`'s own `sheetEntry`/`songEntry`
                    // captures elsewhere in this same `when` — a delegated
                    // `mutableStateOf` property doesn't smart-cast, so the
                    // null check below needs a plain local to narrow.
                    val xrayView = xray
                    // Every screen that exists, where the plan's §2 lands it:
                    // a bench on top draws first, then the bare place or stage.
                    // Each branch's test is written beside its body rather
                    // than as a nested `when` per place, so the fifteen
                    // bodies sit where the tab-era `when (screen)` left them.
                    when {
                        // The beat stops before the screen that makes its own
                        // sound is composed: this frame draws nothing, the
                        // effect stops the beat and closes its host, and the
                        // next frame composes the screen on a free stream.
                        beatMustStop && beat.playing -> SideEffect { beat.stop() }
                        // SNIPS: a bench over the shelf, or over PADS when an
                        // empty pad's FROM SNIPS ▸ opened it for a slot.
                        top == Bench.SNIPS -> SnipsScreen(
                            shelf = shelf,
                            snipsRoot = context.filesDir,
                            onBack = { pendingSnipTarget = null; nav.closeBench() },
                            onBin = {
                                // BIN is the shelf's: land there first, then
                                // open it (the landing clears the overlays).
                                goToScreen(AppScreen.KITS)
                                binOpen = true
                            },
                            onToast = { dock.say(it) },
                            onOpenInTape = { file ->
                                nav.closeBench()
                                tapeOpenOverride = file
                                // An explicit SNIPS → TAPE choice, never
                                // shadowed by an older RE-TRIM's tape.
                                retrim = null
                                landOn(AppScreen.TAPE)
                            },
                            onPickPadFor = { file ->
                                val target = pendingSnipTarget
                                if (target != null) {
                                    // Opened from a pad's strip (plan §3.3):
                                    // the slot is already chosen, so the snip
                                    // lands on it and the bench closes onto
                                    // the grid.
                                    pendingSnipTarget = null
                                    nav.closeBench()
                                    assignPendingSnip(file, target)
                                } else {
                                    // Opened from the shelf: closes SNIPS and
                                    // stashes the file — KitsScreen renders
                                    // next, its header swapped to the
                                    // pick-a-kit hint by `assigningSnip`.
                                    nav.closeBench()
                                    pendingSnipAssign = file
                                }
                            },
                            onSendToLoop = ::sendSnipToLoop,
                            onShare = ::shareSnip,
                        )
                        top == null && location.place == Place.SHELF -> if (xrayView != null) {
                            XRayScreen(
                                fileName = xrayView.fileName,
                                reading = xrayView.reading,
                                onBack = { xray = null },
                            )
                        } else if (binOpen) {
                            BinScreen(
                                shelf = shelf,
                                snipsRoot = context.filesDir,
                                onBack = { binOpen = false },
                                onToast = { dock.say(it) },
                                onKitRestored = {
                                    // The section already owns its own toast
                                    // and its own list refresh — this only
                                    // needs to catch the shelf back up, the
                                    // same reload `deleteKit`/`renameKit`
                                    // already do after their own move. The
                                    // `binnedCount` effect above is keyed
                                    // on `kits`, so this refresh alone is
                                    // also what re-reads the bin count.
                                    scope.launch {
                                        kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                    }
                                },
                                // A snip back on its list changes nothing the
                                // shelf shows; the count re-reads when BIN closes.
                                onSnipRestored = {},
                            )
                        } else if (doublesOpen) {
                            DoublesScreen(
                                shelf = shelf,
                                onBack = { doublesOpen = false },
                                onToast = { dock.say(it) },
                                onGoTo = { entry, slot ->
                                    // The shelf's own onOpen, plus the pad:
                                    // land on that kit with its PAD SHEET
                                    // already up on the double in question.
                                    doublesOpen = false
                                    landKit(entry)
                                    // Through openPadSheet (J16): DOUBLES' GO was
                                    // the second of the three doors that opened
                                    // the sheet without retiring its hint.
                                    openPadSheet(slot)
                                },
                            )
                        } else {
                            KitsScreen(
                                kits = kits,
                                projects = projects,
                                onOpenProject = { openProject(it.project) },
                                openProjectName = project?.name,
                                onNewProject = ::newProject,
                                onCatchFirst = ::requestArm,
                                busy = busy != null,
                                startingKit = pendingKit != null,
                                loading = !(kitsLoaded && shelfListsLoaded),
                                onOpen = { entry ->
                                    val breedSource = pendingBreedWith
                                    val addTo = pendingAddTo
                                    if (addTo != null) {
                                        // `+ FROM SHELF`'s pick (plan §3.3): the kit
                                        // joins the project, becomes its active kit,
                                        // and PADS comes back with it on the grid.
                                        pendingAddTo = null
                                        addKitToProject(addTo, entry)
                                    } else if (breedSource != null) {
                                        // BREED's pick, same shelf-level hand-off
                                        // shape as SNIPS → PAD just below, but this
                                        // one acts the instant a kit is tapped
                                        // instead of navigating in — there's
                                        // nothing else to configure once both
                                        // kits are chosen.
                                        if (entry.dir == breedSource.dir) {
                                            dock.say(Copy.BREED_SAME_KIT)
                                        } else if (Breed.crossable(breedSource.kit, entry.kit).isEmpty()) {
                                            // Nothing for the coin on either
                                            // side: the child would be a copy
                                            // of A. Said now, the pick still
                                            // armed for another kit.
                                            dock.say(Copy.BREED_NOTHING_TO_CROSS)
                                        } else {
                                            finishBreed(breedSource, entry)
                                        }
                                    } else {
                                        // Through the project that holds it (plan
                                        // §3.1): the kit lands on PADS as the open
                                        // project's active kit.
                                        openKit(entry)
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
                                            dock.say(Copy.snipLanding(hasLandingPad(entry.kit)))
                                        }
                                    }
                                },
                                onFresh = ::fresh,
                                onBackup = ::backupShelf,
                                onChopAll = { chopAllPickerLauncher.launch(arrayOf("audio/wav", "audio/x-wav")) },
                                onXRay = { xrayPickerLauncher.launch(arrayOf("*/*")) },
                                onDoubles = { doublesOpen = true },
                                onSnips = { nav.open(Bench.SNIPS) },
                                assigningSnip = pendingSnipAssign != null,
                                addingTo = pendingAddTo?.name,
                                breedingFrom = pendingBreedWith,
                                rooms = rooms,
                                onForgetRoom = ::forgetRoom,
                                onShareRoom = ::shareRoom,
                                binnedRooms = binnedRooms,
                                onRestoreRoom = ::restoreRoom,
                                onEmptyRoomsBin = ::emptyRoomsBin,
                                onDeleteKit = ::deleteKit,
                                onRenameKit = ::renameKit,
                                binnedCount = binnedCount,
                                onBin = { binOpen = true },
                                shelfSort = shelfSort,
                                onToggleSort = { setShelfSort(if (shelfSort == KitShelf.ShelfSort.RECENT) KitShelf.ShelfSort.ALPHA else KitShelf.ShelfSort.RECENT) },
                            )
                        }
                        top == null && location.stage == Stage.PADS -> {
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
                                    onToast = { dock.say(it) },
                                    onRequestArm = ::requestArm,
                                    // A print is a snip on the shelf: the same
                                    // reload request a share-sheet import raises.
                                    onFieldPrinted = { importCount++ },
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
                                    onToast = { dock.say(it) },
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
                                    onToast = { dock.say(it) },
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
                                    onSlotChange = ::openPadSheet,
                                    onBack = { padSheetSlot = null },
                                    onToast = { dock.say(it) },
                                    // The open box is PADS' state (plan §3.3): a
                                    // stage switch keeps it, a kit change resets it.
                                    openBox = nav.padsOpenBox?.name,
                                    onOpenBox = { nav.padsOpenBox = it?.let { name -> PadSheetBoxes.boxFor(name) } },
                                    workshopOpen = workshopOpen,
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
                                            is Retrim.Refused -> dock.say(resolved.reason)
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
                                                landOn(AppScreen.TAPE)
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
                                    onAudition = { slot ->
                                        // AUDITION ▸ (spec decision 2): unlike
                                        // GRAIN/SPLICE/STACK, which stay
                                        // KIT-scoped overlays, this one
                                        // actually switches tabs — the
                                        // comparison plays inside the pattern
                                        // (spec rule 2), which lives on
                                        // GROOVE, not on KIT. `screen =`
                                        // directly, not `goToScreen`, which
                                        // would immediately clear the arm this
                                        // same line sets - same precedent as
                                        // RE-TRIM ▸'s own `onNavigateTape` above.
                                        padSheetSlot = null
                                        auditionArmSlot = slot
                                        landOn(AppScreen.GROOVE)
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
                                    // KEEP ROOM / MAKE INSTRUMENT / MAKE PAD
                                    // all write straight to the shelf
                                    // (rooms/instruments), the same three
                                    // doors `instruments`/`rooms`/
                                    // `binnedRooms`/`binnedKitsCount`'s own
                                    // refresh effect (above, keyed on
                                    // `roomsRevision` among others) already
                                    // reloads for. Bumping the same counter
                                    // here covers all three with one wire.
                                    onShelfAssetWritten = { roomsRevision++ },
                                )
                                takesBinOpen && sheetEntry != null -> TakesBinScreen(
                                    entry = sheetEntry,
                                    onBack = { takesBinOpen = false },
                                    onToast = { dock.say(it) },
                                    onKitUpdated = { updatedKit ->
                                        // Same shape as PAD SHEET's own
                                        // onKitUpdated above: bumping
                                        // `open.kit`'s identity is what
                                        // makes the kit's host reload its bank
                                        // (`PadHost.request`),
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
                                        onToast = { dock.say(it) },
                                        onKitUpdated = { updatedKit ->
                                            // Same shape as PAD SHEET/TAKES+BIN's own
                                            // onKitUpdated: bump `open.kit`'s identity
                                            // so the kit's host reloads the pad GRAB
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
                                else -> KitLoading(pendingKit) {
                                    // A starter still rendering: the page is the placeholder grid for it
                                    // (and, after 200 ms, the loading card over it), whatever kit is open;
                                    // the kit is the page again once it has landed and the card has had its time.
                                    KitScreen(
                                        open,
                                        // The switcher (plan §3.3): the project's line,
                                        // its kit list, and the shelf in pick mode.
                                        switcherLine = project?.let { Places.switcher(it) } ?: Places.NO_KITS,
                                        // With fewer than two kits there is nothing to
                                        // switch between: the chip is the shelf's door,
                                        // not a one-row sheet naming the kit on screen.
                                        onSwitchKit = { if ((project?.kits?.size ?: 0) < 2) addFromShelf() else kitSheetOpen = true },
                                        onAddFromShelf = ::addFromShelf,
                                        // The grid's state, held by the navigation model.
                                        bank = nav.padsBank,
                                        onBankChange = { nav.padsBank = it },
                                        selectedSlot = nav.padsSelectedSlot,
                                        onSelectSlot = { nav.padsSelectedSlot = it },
                                        // The strip's doors: the tap twin of the hold (W#22).
                                        onOpenPad = ::openPadSheet,
                                        onCaptureInto = { slot -> padCaptureSlot = slot },
                                        onFromSnips = { slot ->
                                            pendingSnipTarget = slot
                                            nav.open(Bench.SNIPS)
                                        },
                                        versions = versions,
                                        busy = busy != null,
                                        onLongPress = ::openPadSheet,
                                        onTakesBin = { takesBinOpen = true },
                                        onTexture = ::texture,
                                        onSetKey = ::setKey,
                                        onSavePerformance = ::savePerformance,
                                        onInKey = ::inKey,
                                        onTwins = ::evilTwins,
                                        onBankEmpty = { dock.say(Copy.bankEmpty(PadBanks.letter(it))) },
                                        bankRequest = kitBankRequest,
                                        onBankRequestConsumed = { kitBankRequest = null },
                                        lightRequest = lightRequest,
                                        onLightConsumed = { lightRequest = null },
                                        beat = beat,
                                        onBreed = ::startBreed,
                                        // A second kit to cross with has to
                                        // already be on the shelf — BREED can't
                                        // offer a pick with nothing else there.
                                        canBreed = kits.size > 1,
                                        onSplit = { landOn(AppScreen.SPLIT) },
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
                                            dock.say(if (pendingSnipAssign != null) "LONG-PRESS TO PLACE THIS SNIP" else "LONG-PRESS TO CAPTURE")
                                        },
                                        onNavigateKits = { goToScreen(AppScreen.KITS) },
                                        hasProject = project != null,
                                        onNavigateTape = { goToScreen(AppScreen.TAPE) },
                                        hosts = hosts,
                                    )
                                }
                            }
                        }
                        top == null && location.stage == Stage.DECK -> TapeScreen(
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
                            onToast = { dock.say(it) },
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
                                // KEEP moves the stage (plan §3.2): CUT is the
                                // tape's own second stage, so a kept tape is
                                // simply shown cut, with no offer to take
                                // first. CUT reads `lastCommit`, which is what
                                // was just set, so it always has its source.
                                // DECK is one stage-row tap back.
                                landOn(AppScreen.CHOP)
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
                            onCatch = ::catchOnto,
                            onCatchDone = ::catchDone,
                            // The empty deck's door: SNIPS, a bench, like PADS' FROM SNIPS.
                            onNavigateSnips = { nav.open(Bench.SNIPS) },
                            onCatchInFlight = { catchInFlight = it },
                        )
                        top == Bench.SETUP -> PropertiesScreen(
                            currentScheme = schemeId,
                            onScheme = {
                                schemeId = it
                                prefs.edit().putString(PREF_SCHEME, it.name).apply()
                            },
                            teachEnabled = teachEnabled,
                            onTeach = { on ->
                                teachEnabled = on
                                prefs.edit().putBoolean(PREF_TEACH, on).apply()
                                dock.say(if (on) Copy.TEACHING_ON else Copy.TEACHING_OFF)
                            },
                            hapticsOn = hapticsOn,
                            onHaptics = { on ->
                                hapticsOn = on
                                prefs.edit().putBoolean(PREF_HAPTICS, on).apply()
                                if (on) haptics.tick()
                            },
                            midiOutOn = midiOutOn,
                            onMidiOut = { on ->
                                midiOutOn = on
                                prefs.edit().putBoolean(PREF_MIDI_OUT, on).apply()
                                MidiOut.hardwareEnabled = on
                            },
                            midiSupported = midiSupported,
                            midiPorts = MidiOut.ports.collectAsState().value,
                            // Finding 23: three facts the app already kept and
                            // never showed. Read straight from the same prefs
                            // EXPORT writes - one owner each, no second copy.
                            exportFormatLabel = prefs.getString(PREF_EXPORT_FORMAT, null)
                                ?.let { ExportFormat.byId(it) }?.cyclerLabel,
                            filesWhere = exportsWhere,
                            cardName = prefs.getString(PREF_CARD_TREE, null)
                                ?.let { Copy.cardName(Uri.parse(it).lastPathSegment) },
                            onHelp = { landOn(AppScreen.HELP) },
                            workshopOpen = workshopOpen,
                            // The knock: only counted while the workshop is
                            // closed - an open one has nothing to open.
                            // From three to go the toast counts down; the
                            // seventh says where the section is.
                            onKnock = {
                                if (!workshopOpen) {
                                    val remaining = workshopKnock.tap(System.currentTimeMillis())
                                    if (remaining == 0) {
                                        workshopOpen = true
                                        prefs.edit().putBoolean(PREF_WORKSHOP, true).apply()
                                        dock.say(Copy.WORKSHOP_OPENED)
                                    } else if (Workshop.hints(remaining)) {
                                        dock.say(Copy.workshopKnock(remaining))
                                    }
                                }
                            },
                            onCloseWorkshop = {
                                workshopOpen = false
                                prefs.edit().putBoolean(PREF_WORKSHOP, false).apply()
                                dock.say(Copy.WORKSHOP_CLOSED)
                            },
                            onSendToBench = ::sendToBench,
                            onSendHitsToBench = ::sendHitsToBench,
                        )
                        top == null && location.stage == Stage.CUT -> ChopScreen(
                            entry = open,
                            lastCommit = lastCommit,
                            shelf = shelf,
                            teachEnabled = teachEnabled,
                            workshopOpen = workshopOpen,
                            onToast = { dock.say(it) },
                            onSentToGrid = { newEntry, sent ->
                                haptics.land()
                                landKit(newEntry)
                                // Step 3 → step 4 (J10), and only when the
                                // kit has pads: a door onto an empty EXPORT
                                // is a worse answer than no door.
                                if (newEntry.kit.pads.isNotEmpty()) {
                                    offer(Copy.KIT_OFFER, Copy.KIT_OFFER_DOOR, AppScreen.EXPORT)
                                }
                                // What SEND did is the line and the offer is
                                // the door: the offer replaces the whole
                                // line, so the sentence is said after it, and
                                // a line keeps the door. The new kit is the
                                // grid's now (F4); its name rides along when
                                // both fit the dock's two lines.
                                dock.say(sentAndOnTheGrid(sent, newEntry))
                                scope.launch {
                                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                }
                            },
                            onLandedOnto = { updated, bank, landed ->
                                haptics.land()
                                landKit(updated)
                                kitBankRequest = bank
                                if (updated.kit.pads.isNotEmpty()) {
                                    offer(Copy.KIT_OFFER, Copy.KIT_OFFER_DOOR, AppScreen.EXPORT)
                                }
                                // The landing sentence, after the offer, for
                                // the same reason SEND's is.
                                dock.say(landed)
                                scope.launch {
                                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                }
                            },
                            onNavigateTape = { goToScreen(AppScreen.TAPE) },
                            onNavigateKits = { goToScreen(AppScreen.KITS) },
                        )
                        top == null && location.stage == Stage.OUT -> ExportScreen(
                            entry = open,
                            session = exportSession,
                            onSessionChange = { exportSession = it },
                            // A blocked write is a refusal with reasons, and
                            // the box is what the app already uses for those
                            // (J35). EXPORT is the first screen to raise it
                            // rather than App doing so on a screen's behalf.
                            onNote = ::openNote,
                            // App's own scope — the same one `fresh()`
                            // launches into and `PadSheetScreen`'s teardown
                            // save uses — so a dub survives a MenuRow tab
                            // switch instead of being cancelled by it.
                            appScope = scope,
                            onToast = { dock.say(it) },
                            onNavigateKits = { goToScreen(AppScreen.KITS) },
                            onAddFromShelf = ::addFromShelf,
                            // OUT's second card (plan §3.5): the whole project
                            // as one session, over the open project.
                            project = project,
                            shelfRoot = shelf.root,
                            // SHARE, moved in from KIT's action row: it is an
                            // output. The beat's writers (BOUNCE, MIDI, CHART)
                            // live on BEAT and are listed here as a door.
                            onShareKit = ::shareKit,
                            onBeatWriters = { goToStage(Stage.BEAT) },
                        )
                        top == Bench.SYNTH -> {
                            // Captured here, at this composition, so a SEND TO PAD write that
                            // outlives this screen (appScope, below) can't weld its result onto
                            // whichever kit is open by the time it lands — same identity guard
                            // as the KIT branch's own onKitUpdated closures above.
                            val synthEntry = open
                            SynthScreen(
                                entry = synthEntry,
                                // The lab's work, kept across a trip to the grid (plan §3.6).
                                settings = nav.make.settings(Bench.SYNTH),
                                shelfRoot = shelf.root,
                                onToast = { dock.say(it) },
                                onKitUpdated = { updatedKit ->
                                    // Same shape as PAD SHEET/CHOP's own
                                    // onKitUpdated: bump `open.kit`'s identity so
                                    // the kit's host reloads the pad SEND TO PAD
                                    // just replaced, not a stale cached sample.
                                    if (open?.dir == synthEntry?.dir) open = open?.copy(kit = updatedKit)
                                    scope.launch {
                                        kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                    }
                                },
                                // MAKE INSTRUMENT (RESIN, held) writes beside the kits:
                                // the same re-read PAD SHEET's MAKE INSTRUMENT asks for.
                                onShelfAssetWritten = { roomsRevision++ },
                                // DRONE TO LOOP (RESIN): the grid's sidecar is App's to
                                // write, under the same one writer SNIPS' → LOOP uses.
                                onDroneToLoop = { name, recipe, root -> sendDroneToLoop(name, recipe, root) },
                                // → SURFACE (SIREN, a LOOP): the pad has landed and
                                // surface.json points at it; the same screen switch a
                                // MenuRow tap makes, so SURFACE reads the file as it opens.
                                onOpenSurface = { goToScreen(AppScreen.SURFACE) },
                                // App()'s own scope — same reasoning as PAD SHEET/PAD
                                // CAPTURE's own appScope: SEND TO PAD's write must survive
                                // a MenuRow tab switch, not be cancelled by it.
                                appScope = scope,
                            )
                        }
                        // SPLIT: one pad on three faders. Its prints land the
                        // same two ways SURFACE's do, so the same two hooks.
                        top == Bench.SPLIT -> SplitScreen(
                            entry = open,
                            onToast = { dock.say(it) },
                            onPrinted = { importCount++ },
                            onKitUpdated = { updatedKit ->
                                open = open?.copy(kit = updatedKit)
                                scope.launch {
                                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                }
                            },
                            onExit = { nav.closeBench() },
                        )
                        top == Bench.SNAP -> {
                            // Same identity guard as SYNTH above: a SEND TO PAD
                            // write that outlives this screen lands on the kit
                            // that was open when it started, not whichever is
                            // open when it finishes.
                            val snapEntry = open
                            SnapScreen(
                                entry = snapEntry,
                                // The lab's work, kept across a trip to the grid (plan §3.6).
                                settings = nav.make.settings(Bench.SNAP),
                                busy = busy != null,
                                onToast = { dock.say(it) },
                                onKitUpdated = { updatedKit ->
                                    if (open?.dir == snapEntry?.dir) open = open?.copy(kit = updatedKit)
                                    scope.launch {
                                        kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                    }
                                },
                                onBuildKit = ::buildPhotoKit,
                                onBuildPathRing = ::buildPathRing,
                                onBuildChord = ::buildChordKit,
                                onFieldPrinted = { importCount++ },
                                appScope = scope,
                            )
                        }
                        top == Bench.SURFACE -> SurfaceScreen(
                            entry = open,
                            settings = nav.make.settings(Bench.SURFACE),
                            onToast = { dock.say(it) },
                            // A print is a snip on the shelf: the same reload
                            // request a share-sheet import raises.
                            onPrinted = { importCount++ },
                            // A print on a pad: bump `open.kit`'s identity so
                            // the kit's host and the surface reload it.
                            onKitUpdated = { updatedKit ->
                                open = open?.copy(kit = updatedKit)
                                scope.launch {
                                    kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                }
                            },
                        )
                        // The place under the gear: HELP opens its section.
                        top == Bench.HELP -> HelpScreen(cameFrom = location.place)
                        top == Bench.MIDI -> MidiScreen()
                        top == null && location.stage == Stage.BEAT -> {
                            val songEntry = open
                            if (arrangeOpen && songEntry != null) {
                                ArrangeScreen(
                                    entry = songEntry,
                                    onBack = { arrangeOpen = false },
                                    onToast = { dock.say(it) },
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
                                    onToast = { dock.say(it) },
                                    reloadRequest = grooveReload,
                                    onArrange = { swing, f, tpl -> arrangeSwing = swing; arrangeFeel = f; arrangeFeelTemplate = tpl; arrangeOpen = true },
                                    onOrbit = { nav.open(Bench.ORBIT) },
                                    onLoop = { nav.open(Bench.LOOP) },
                                    beat = beat,
                                    onBeatWritten = ::mirrorBeat,
                                    onNavigateKits = { goToScreen(AppScreen.KITS) },
                                    hasProject = project != null,
                                    onAddFromShelf = ::addFromShelf,
                                    onNavigatePads = { goToStage(Stage.PADS) },
                                    feel = grooveFeel,
                                    onFeelChange = { grooveFeel = it },
                                    swingPercent = grooveSwingPercent,
                                    onSwingPercentChange = { grooveSwingPercent = it },
                                    progIndex = grooveProgIndex,
                                    onProgIndexChange = { grooveProgIndex = it },
                                    seed = grooveSeed,
                                    onSeedChange = { grooveSeed = it },
                                    justLanded = grooveJustLanded,
                                    onJustLandedChange = { grooveJustLanded = it },
                                    preTake = groovePreTake,
                                    onPreTakeChange = { groovePreTake = it },
                                    // AUDITION (spec decision 2): the arm PAD
                                    // SHEET's own AUDITION ▸ set, consumed
                                    // once GrooveScreen has built a session
                                    // from it.
                                    auditionArmSlot = auditionArmSlot,
                                    onAuditionArmed = { auditionArmSlot = null },
                                    // Same shape as PAD SHEET's own
                                    // onKitUpdated above: AUDITION's own
                                    // choose-a-winner write lands through
                                    // this same door.
                                    onKitUpdated = { updatedKit ->
                                        // Same "Frankenstein entry" guard as PAD
                                        // SHEET's own onKitUpdated: this write is
                                        // async (appScope), so by the time it
                                        // lands the user may have switched to a
                                        // different kit - `songEntry` is this
                                        // composition's own stable snapshot, not
                                        // `open` re-read live.
                                        if (open?.dir == songEntry?.dir) open = open?.copy(kit = updatedKit)
                                        scope.launch {
                                            kits = withContext(Dispatchers.IO) { shelf.list(shelfSort) }
                                        }
                                    },
                                )
                            }
                        }
                        top == Bench.ORBIT -> {
                            // The one door to the rings: a bench under BEAT, from
                            // GROOVE's ORBIT ▸ (plan §3.4). It needs only a kit.
                            val orbitEntry = open
                            if (orbitEntry == null) {
                                NoKitPanel(
                                    Copy.NO_KIT_FOR_ORBIT,
                                    hasProject = project != null,
                                    onAddFromShelf = ::addFromShelf,
                                    onNavigateKits = { goToScreen(AppScreen.KITS) },
                                )
                            } else {
                                OrbitScreen(
                                    entry = orbitEntry,
                                    kitsRoot = shelf.root,
                                    onBack = { nav.closeBench() },
                                    onToast = { dock.say(it) },
                                    // CLIP ▸ KIT rewrites the open kit's groove.json
                                    // (OrbitClip.save) the same way TAPE's READ AS
                                    // GROOVE / STEAL THE FEEL do — so it bumps the
                                    // same reload request they do.
                                    onGrooveWritten = { grooveReload++ },
                                    // The six hoisted above, so leaving the bench
                                    // and coming back finds the same session.
                                    history = orbitHistory,
                                    onHistoryChange = { orbitHistory = it },
                                    selected = orbitSelected,
                                    onSelectedChange = { orbitSelected = it },
                                    solo = orbitSolo,
                                    onSoloChange = { orbitSolo = it },
                                    set = orbitSet,
                                    onSetChange = { orbitSet = it },
                                    scrambleSeed = orbitScrambleSeed,
                                    onScrambleSeedChange = { orbitScrambleSeed = it },
                                    brush = orbitBrush,
                                    onBrushChange = { orbitBrush = it },
                                )
                            }
                        }
                        top == Bench.LOOP -> LoopBench(
                            onBack = { nav.closeBench() },
                            onToast = { dock.say(it) },
                            // The empty grid's SNIPS door: SNIPS takes LOOP's place on
                            // the stack, so Back from it returns to BEAT, where the
                            // LOOP ▸ chip is.
                            onGoToSnips = { nav.closeBench(); nav.open(Bench.SNIPS) },
                        )
                        top == Bench.KEYS -> {
                            val inst = openInstrument
                            if (inst == null) {
                                nav.closeBench()
                            } else {
                                KeysScreen(
                                    sidecar = inst.sidecar,
                                    instrument = inst.instrument,
                                    onBack = { openInstrument = null; nav.closeBench() },
                                )
                            }
                        }
                        top == null && location.place == Place.MAKE -> MakeList(onOpen = { nav.open(it) }, hasKit = open != null)
                        top == Bench.INSTRUMENTS -> InstrumentsBench(
                            instruments = instruments,
                            onOpen = { openInstrument = it; landOn(AppScreen.KEYS) },
                            onBack = { nav.closeBench() },
                        )
                        // The pad benches (PAD SHEET, GRAIN FIELD, SPLICE,
                        // STACK, SPREAD, VERSIONS, PAD CAPTURE), BIN, SNIPS,
                        // SONG, LOOP and the rest of the bench roster are
                        // still PADS', SHELF's and BEAT's own overlays above,
                        // and nothing opens them as a bench yet. Their steps
                        // of the plan move them here; until then this draws
                        // nothing rather than something false.
                        else -> Unit
                    }
                }
                // The dock, at the foot of every screen (plan §1). The status
                // bar's three cells live on: where you are is the two rows
                // above, the shelf count is on the SHELF, the busy line is
                // the dock's.
                Dock(
                    dock = dock,
                    busy = busy ?: jobs.current ?: screenBusy,
                    onArm = { captureSheetOpen = true },
                    // The transport is a button wherever a kit is open, and while
                    // the beat plays: the clock is App's, so the chip is the beat's
                    // one control from anywhere. It is still offered on BEAT with
                    // no kit, so its answer can be ADD A KIT first. Not offered
                    // where the beat cannot run (`beatMustStop`).
                    onPlayToggle = if (!beatMustStop && (open != null || beat.playing || (location.stage == Stage.BEAT && location.top == null))) {
                        ({ beat.toggle { dock.say(it) } })
                    } else {
                        null
                    },
                    onSnip = {
                        MicSessionService.snip(context)
                        // Optimistic — the real commit hasn't started yet, let
                        // alone resolved. The `lastSnipError` collector above
                        // corrects this line if that commit fails. The door
                        // is CUT, one tap from the new snip; it is not drawn
                        // when CUT is where the user already is.
                        offer(Copy.SNIPPED, Places.DOOR_CUT, AppScreen.CHOP)
                    },
                    onEject = { MicSessionService.eject(context) },
                    onTakeDoor = ::takeDockDoor,
                )
            }
            if (kitSheetOpen) {
                // The switcher's list (plan §3.3): the project's kits by name,
                // the active one marked, each a tap to put it on the grid,
                // and the shelf's door as the last row.
                val p = project
                ChoiceSheet(
                    rows = p?.kits.orEmpty().map { folder ->
                        val name = kits.firstOrNull { it.dir.name == folder }?.kit?.name ?: folder
                        SheetRow(Places.kitRow(name, folder == p?.activeKit)) { kitSheetOpen = false; activateKit(folder) }
                    } + SheetRow(Places.SHEET_ADD_FROM_SHELF) { kitSheetOpen = false; addFromShelf() },
                    onDismiss = { kitSheetOpen = false },
                )
            }
            if (captureSheetOpen) {
                CaptureSheet(
                    onMic = { captureSheetOpen = false; requestArm() },
                    onInside = { captureSheetOpen = false; requestArmInside() },
                    onDismiss = { captureSheetOpen = false },
                )
            }
            if (gearOpen) {
                GearSheet(
                    onSetup = { gearOpen = false; landOn(AppScreen.PROPERTIES) },
                    onHelp = { gearOpen = false; landOn(AppScreen.HELP) },
                    onMidi = { gearOpen = false; landOn(AppScreen.MIDI) },
                    onDismiss = { gearOpen = false },
                )
            }
            benchNote?.let { stamp ->
                val closeBenchNote = { benchNote = null }
                BenchNoteDialog(
                    context = stamp.context,
                    onCancel = closeBenchNote,
                    onKeep = { text ->
                        keepBenchNote(stamp.note(text))
                        closeBenchNote()
                    },
                )
                // Registered after every screen-level handler above, so
                // Back closes the slip and nothing under it (the
                // MessageBox below is drawn on top of this and registers
                // later still, so it wins when both are up).
                BackHandler(onBack = closeBenchNote)
            }
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
                BlockedDialog(
                    Copy.MIC_PERMISSION_DENIED,
                    Copy.CAPTURE_BLOCKED_BUTTON,
                    onDismiss = dismissDenied,
                    // The words say where the switch is; the button takes
                    // you there. A permission the system will not ask for
                    // again can only be turned on in this app's own page.
                    actionLabel = Places.OPEN_SETTINGS,
                    onAction = {
                        dismissDenied()
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "mic denied: the app's settings page would not open", e)
                            dock.say(Copy.MIC_PERMISSION_DENIED)
                        }
                    },
                )
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
 * differ between callers, and an optional [actionLabel] button above the
 * dismiss when the refusal has somewhere to send the user.
 */
@Composable
private fun BlockedDialog(
    message: String,
    buttonLabel: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
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
                // Swallows the tap so it doesn't fall through to the
                // scrim's dismiss handler below. A raw pointerInput, not
                // tapeClick: this Column's real children (the message,
                // PrimaryAction) carry their own accessible names below,
                // and clickable()'s own semantics would add a second,
                // nameless actionable node wrapping all of them — worse
                // than the thing this pass is fixing, not better. A bare
                // gesture detector registers no semantics node at all, so
                // it consumes the touch without touching the accessibility
                // tree (KitScreen.kt's PadCell uses the same "pointerInput
                // registers no click action" idiom).
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText(message, TapeType.lcdSmall, scheme.ink.tape, maxLines = 4)
            if (actionLabel != null) PrimaryAction(label = actionLabel, enabled = true, onClick = onAction)
            PrimaryAction(label = buttonLabel, enabled = true, onClick = onDismiss)
        }
    }
}

/**
 * What one SNIPS → LOOP send came to: the line to show, and the new filled-track
 * count when a track was actually filled (null when nothing changed — a full
 * grid, an unreadable snip, a failed write).
 *
 * A tiny type rather than a Pair so the null half cannot be read as "zero
 * tracks", which is what a bare Int would have let it mean on every failure.
 */
private data class LoopSend(val toast: String)
