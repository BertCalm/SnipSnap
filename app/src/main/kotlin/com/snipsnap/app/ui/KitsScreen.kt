package com.snipsnap.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.PREFS
import com.snipsnap.kit.KitStore
import com.snipsnap.audio.SilenceWatch
import com.snipsnap.app.theme.BinRedGlow
import androidx.compose.ui.platform.LocalContext
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.oilslickSweep
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Ages
import com.snipsnap.shell.Copy
import com.snipsnap.shell.DubStamp
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.ShelfFilter
import com.snipsnap.shell.StarterKits
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.snipsnap.shell.Rooms
import com.snipsnap.app.theme.pressedBevel
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.snipsnap.kit.Names

/** How long a first tap on EMPTY THE BIN NOW (rooms bin) stays armed before it disarms itself — `TakesBinScreen.kt`'s own constant, copied verbatim. */
private const val EMPTY_ROOMS_BIN_ARM_MS = 3_000L

/**
 * The tape shelf: every kit folder on the device, plus the NEW KIT ▸ PICK
 * A STARTER menu (the cold-start answer — the shelf is never uselessly
 * empty when eight starters are one tap away).
 */
@Composable
fun KitsScreen(
    kits: List<KitShelf.Entry>,
    instruments: List<KitShelf.InstrumentEntry>,
    busy: Boolean,
    armed: Boolean,
    onOpen: (KitShelf.Entry) -> Unit,
    onOpenInstrument: (KitShelf.InstrumentEntry) -> Unit,
    onFresh: (StarterKits.Starter) -> Unit,
    onArm: () -> Unit,
    onArmInside: () -> Unit,
    onSnip: () -> Unit,
    onEject: () -> Unit,
    /** BACKUP (X3.3): every kit on one file, handed to the chooser. */
    onBackup: () -> Unit,
    /** CHOP ALL (XX3 wired in): opens the multi-file picker, one new kit per picked .wav. */
    onChopAll: () -> Unit = {},
    /** SNIPS (Task 3): every catch on the phone, one list — play, assign to a pad, open in TAPE, or delete. */
    onSnips: () -> Unit = {},
    /**
     * True while a SNIPS row's → PAD is routing the user here to pick a kit
     * (see `App.kt`'s `pendingSnipAssign`) — swaps the header's own line for
     * a hint instead of the usual "THE SHELF", and adds a one-line nudge
     * toward what happens next. Every kit row's `onOpen` stays the same
     * callback either way; `App` is what decides what opening a kit means
     * while this is true.
     */
    assigningSnip: Boolean = false,
    /**
     * BREED (XX2 wired in): the kit BREED was pressed from, non-null while
     * that press is routing the user here to pick its cross partner (see
     * `App.kt`'s `pendingBreedWith`) — same hand-off shape as
     * [assigningSnip] above, just for a second *kit* instead of a pad.
     * Swaps the header for [Copy.breedPickHeader] (naming this kit) and
     * shows [Copy.BREED_PICK_HINT] once there's a second kit to tap. Every
     * kit row's `onOpen` still stays the same callback; `App` decides
     * whether opening a kit means navigating in or crossing it with this one.
     */
    breedingFrom: KitShelf.Entry? = null,
    /** ROOMS (YY5): what OUTSIDE measured and kept, beside the instruments; hold one to forget it into the bin. */
    rooms: List<Rooms.Room> = emptyList(),
    onForgetRoom: (Rooms.Room) -> Unit = {},
    /** SHARE on a room row: packed as one `.snip-room` and handed to the chooser. */
    onShareRoom: (Rooms.Room) -> Unit = {},
    /** The rooms in the bin, each with its days left; RESTORE brings one back. */
    binnedRooms: List<Rooms.Binned> = emptyList(),
    onRestoreRoom: (Rooms.Binned) -> Unit = {},
    /**
     * EMPTY THE BIN NOW on the rooms bin (name-and-find followups): the
     * same bulk-empty affordance `emptyKitBin`/`KitBuilderModel`'s own EMPTY
     * THE BIN NOW/`SnipStore.emptyBin` already have — closing the last gap
     * where a bin only offered individual restore.
     */
    onEmptyRoomsBin: () -> Unit = {},
    /** DELETE (Task 4): a held kit row's DELETE, confirmed — into the 30-day bin, `KitShelf.deleteKit`'s promise. */
    onDeleteKit: (KitShelf.Entry) -> Unit = {},
    /** RENAME (Task 4): a held kit row's RENAME, confirmed with the typed name — `KitShelf.renameKit`'s collision fallback may freshen it. */
    onRenameKit: (KitShelf.Entry, String) -> Unit = { _, _ -> },
    /**
     * DELETED KITS (Task 2 of the bin-restore plan): how many kits
     * `KitShelf.binnedKits()` currently holds. Gates the `DELETED KITS ▸`
     * row below — shown only when this is positive, so a user who has never
     * deleted a kit never sees a door to an empty room.
     */
    binnedKitsCount: Int = 0,
    onDeletedKits: () -> Unit = {},
    /** X-RAY: opens the system picker, then reads whatever comes back — never lands it, never gated on the shelf holding anything. */
    onXRay: () -> Unit = {},
    /** DOUBLES: pads across the shelf's kits inside a "same sound" distance, the number on every row — read-only, X-RAY's own posture turned on the library. */
    onDoubles: () -> Unit = {},
    /**
     * SHELF SORT (name-and-find followups): [KitShelf.ShelfSort] the shelf
     * is currently ordered by — `App`'s own [KitShelf.ShelfSort.RECENT]
     * default, persisted like the colour scheme. [onToggleSort] flips
     * between [KitShelf.ShelfSort.RECENT] and [KitShelf.ShelfSort.ALPHA];
     * `App` owns both the persistence and the re-fetch, this screen only
     * shows the current state and asks for the flip.
     */
    shelfSort: KitShelf.ShelfSort = KitShelf.ShelfSort.RECENT,
    onToggleSort: () -> Unit = {},
) {
    val scheme = LocalScheme.current
    var menuOpen by remember { mutableStateOf(false) }

    // The dub chips (September UAT, finding 15). Read once per shelf render
    // on IO, not per row in a composable body: a row's chip is a file read,
    // and twenty of them on every recomposition would be twenty disk hits on
    // the main thread. Keyed on the kit list, so a rename, a delete, a
    // restore or a fresh dub re-reads; `context` supplies the same
    // PREF_CARD_TREE the export wizard writes, so ON CARD can only mean the
    // card actually in the phone.
    val context = LocalContext.current
    var dubStatuses by remember { mutableStateOf<Map<String, DubStamp.Status>>(emptyMap()) }
    // When each kit was last edited - the very thing RECENT already sorts by,
    // which until now the shelf never showed (September UAT, finding 16). Read
    // in the same pass: one more lastModified() on a list already being walked.
    var editedAges by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(kits) {
        val read = withContext(Dispatchers.IO) {
            val card = context
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(PREF_CARD_TREE, null)
            val now = System.currentTimeMillis()
            kits.associate {
                it.dir.path to Pair<DubStamp.Status, String?>(
                    DubStamp.status(DubStamp.read(it.dir), card),
                    Ages.agoOrNull(File(it.dir, KitStore.FILE_NAME).lastModified(), now),
                )
            }
        }
        dubStatuses = read.mapValues { it.value.first }
        editedAges = read.mapNotNull { (k, v) -> v.second?.let { k to it } }.toMap()
    }
    // SHELF FILTER (September UAT, finding 16): which dub state the shelf
    // is narrowed to, or null for all of them. Screen-local and NOT
    // persisted, unlike the sort beside it - a sort shows every kit either
    // way, a filter hides them, and a hidden filter restored on next open
    // is how a user decides the app lost their kits.
    var shelfFilter by remember { mutableStateOf<DubStamp.Status?>(null) }
    // ONE condition decides both whether the chip is drawn and whether the
    // filter bites, so the two cannot drift apart. A filter still applied
    // with its chip off screen removes kits the user cannot get back:
    // during SNIPS -> PAD that makes a kit unpickable for no stated reason,
    // and on a shrinking shelf it strands the "TAP SHOW" the empty line
    // promises. The stamps are part of it too - they arrive from IO a
    // moment after the screen does, and a kit without one is claimed by no
    // filter.
    val filterOffered =
        !assigningSnip && breedingFrom == null && kits.size > 1 && dubStatuses.isNotEmpty()
    val shownKits =
        ShelfFilter.apply(kits, ShelfFilter.effective(shelfFilter, filterOffered)) {
            dubStatuses[it.dir.path]
        }

    // DELETE/RENAME (Task 4): screen-level, not per-row — same shape as
    // SnipsScreen's own `confirmDelete`, so only one row's dialog is ever
    // up regardless of how many rows are armed at once.
    var confirmDeleteKit by remember { mutableStateOf<KitShelf.Entry?>(null) }
    var renameTarget by remember { mutableStateOf<KitShelf.Entry?>(null) }
    // Which kit rows are armed (DELETE/RENAME revealed), lifted up here
    // rather than kept in KitRow's own remember: cancelling (or dismissing
    // by tapping outside) either dialog below needs to disarm the row that
    // opened it, which only the screen — the dialog's target is screen
    // state — can reach. A Set, not a single File?, because more than one
    // row can be armed at once (see the comment on confirmDeleteKit above).
    var armedKitDirs by remember { mutableStateOf(setOf<String>()) }

    // EMPTY ROOMS BIN: the two-tap armed confirm `TakesBinScreen.kt`'s own
    // `EMPTY_BIN_ARM_MS`/`LaunchedEffect(armed)` pattern establishes
    // (`DeletedKitsScreen.kt`/`DeletedSnipsScreen.kt` copy it verbatim) —
    // this screen's own copy, since the rooms bin has no screen of its own
    // to hold that state; it lives inline in this LazyColumn instead.
    var roomsBinArmed by remember { mutableStateOf(false) }
    LaunchedEffect(roomsBinArmed) {
        if (roomsBinArmed) {
            delay(EMPTY_ROOMS_BIN_ARM_MS)
            roomsBinArmed = false
        }
    }
    fun doEmptyRoomsBin() {
        if (busy) return
        if (!roomsBinArmed) {
            roomsBinArmed = true
            return
        }
        roomsBinArmed = false
        onEmptyRoomsBin()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    // heightIn, not height: this header carries tappable chips
                    // (SORT, and now SHOW), and a fixed LCD_HEADER_H of 40dp
                    // capped them below MIN_HIT_TARGET however tall they asked
                    // to be - a child cannot exceed a fixed parent. SORT has
                    // been undersized here since it was written; adding a
                    // second control to the same header is what made it worth
                    // reading the constraint rather than the request. Other
                    // tappable headers already take the taller of the two
                    // (SplitScreen.kt's own LCD header).
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .lcdPanel(scheme)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TapeText(
                    when {
                        assigningSnip -> "PICK A KIT FOR THIS SNIP"
                        breedingFrom != null -> Copy.breedPickHeader(breedingFrom.kit.name)
                        else -> "THE SHELF"
                    },
                    TapeType.lcdHeader,
                    scheme.lcdInk.tape,
                )
                // The shelf's two controls, in one row at the header's end.
                //
                // SORT (name-and-find followups): RECENT (the default —
                // most-recently-edited kit.json first, so a returning
                // user's own last kit is right where they left it) vs A-Z.
                // SHOW (September UAT, finding 16) narrows to one dub
                // state. Both are hidden during SNIPS → PAD / BREED's own
                // pick mode (the header's line is a hint there, not "THE
                // SHELF") and with fewer than two kits, where neither an
                // order nor a filter has anything to say.
                if (!assigningSnip && breedingFrom == null && kits.size > 1) {
                    Row(
                        Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // SHOW cycles ALL → DRAFT → DUBBED → ON CARD → ALL
                        // (September UAT, finding 16). One cycling chip rather
                        // than four filter chips: four legal targets would take
                        // most of the row, and this is the same shape as the
                        // SORT chip it sits beside. Offered only once the
                        // stamps are read - see `shownKits` above.
                        if (filterOffered) {
                            Box(
                                Modifier
                                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                                    // null for the same reason SORT passes null:
                                    // the TapeText below already says which state
                                    // is active, and an explicit label would
                                    // REPLACE it for TalkBack.
                                    .tapeClick(label = null) {
                                        shelfFilter =
                                            ShelfFilter.nextFrom(shelfFilter, shownKits.isEmpty())
                                    }
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                TapeText(
                                    Copy.shelfFilter(shelfFilter),
                                    TapeType.pixelSmall,
                                    scheme.amber.tape,
                                    maxLines = 1,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                                // null, not an explicit label: the descendant
                                // TapeText below already says which mode is
                                // active ("SORT ▸ RECENT"/"SORT ▸ A–Z") — an
                                // explicit label here would REPLACE that merged
                                // text for TalkBack (Chrome.kt's own tapeClick
                                // KDoc), leaving a screen-reader user unable to
                                // hear which state they're toggling out of.
                                .tapeClick(label = null, onClick = onToggleSort)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TapeText(
                                if (shelfSort == KitShelf.ShelfSort.RECENT) Copy.SHELF_SORT_RECENT else Copy.SHELF_SORT_ALPHA,
                                TapeType.pixelSmall,
                                scheme.amber.tape,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            // The hint below presupposes a kit row to tap — with none on
            // the shelf yet, the empty-state panel just below carries the
            // real instruction instead (Copy.EMPTY_SHELF_FOR_ASSIGN).
            if (assigningSnip && kits.isNotEmpty()) {
                TapeText(Copy.ASSIGN_PICK_HINT, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            } else if (breedingFrom != null && kits.size > 1) {
                TapeText(Copy.BREED_PICK_HINT, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            }

            if (kits.isEmpty() && instruments.isEmpty() && rooms.isEmpty() && binnedRooms.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .lcdPanel(scheme)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // A user mid SNIPS → PAD hand-off just made the very
                        // snip they're trying to place — EMPTY_SHELF's "NOTHING
                        // TAPED YET" would flatly contradict that. This names
                        // the real blocker (no kit yet) and the real fix.
                        TapeText(
                            if (assigningSnip) Copy.EMPTY_SHELF_FOR_ASSIGN else Copy.EMPTY_SHELF,
                            TapeType.lcdSmall,
                            scheme.lcdInk.tape,
                            maxLines = 3,
                        )
                        // September UAT, finding 3: the app never stated its
                        // own loop anywhere. It does now, on the one screen a
                        // new user is guaranteed to meet, in the words of the
                        // tabs themselves.
                        //
                        // NOT shown mid-hand-off: a user placing a snip has a
                        // blocker to clear, and the tour of an app they are
                        // already using would be in the way of it.
                        //
                        // There is no dismiss and no "seen it" flag. This IS
                        // the empty state - it leaves when the shelf has
                        // something on it and comes back if the shelf empties.
                        // The hint that taught PAD SHEET could be dismissed
                        // and lost, which is how finding 5 happened; a thing
                        // that cannot be dismissed cannot be lost.
                        if (!assigningSnip) {
                            TapeText(
                                Copy.FIRST_RUN_LOOP,
                                TapeType.lcdSmall,
                                scheme.amber.tape,
                                maxLines = 1,
                            )
                            TapeText(
                                Copy.FIRST_RUN_LOOP_NOTE,
                                TapeType.pixelSmall,
                                scheme.lcdInk.tape,
                                maxLines = 2,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .sunkenField(scheme)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // A filter that empties the shelf must say so itself: a
                    // list that simply goes blank reads as lost work, not as
                    // a filter. Names the state, the real count, and the tap
                    // back (September UAT, finding 16).
                    val hiddenBy = shelfFilter
                    if (hiddenBy != null && shownKits.isEmpty()) {
                        item(key = "filter-empty") {
                            TapeText(
                                Copy.shelfFilterEmpty(hiddenBy, kits.size),
                                TapeType.pixelSmall,
                                scheme.ink2.tape,
                                maxLines = 2,
                            )
                        }
                    }
                    items(shownKits, key = { it.dir.name }) { entry ->
                        KitRow(
                            entry = entry,
                            armed = entry.dir.path in armedKitDirs,
                            onArm = { armedKitDirs = armedKitDirs + entry.dir.path },
                            onDisarm = { armedKitDirs = armedKitDirs - entry.dir.path },
                            busy = busy,
                            pickModeActive = assigningSnip || breedingFrom != null,
                            onOpen = onOpen,
                            onRequestDelete = { confirmDeleteKit = it },
                            onRequestRename = { renameTarget = it },
                            dubStatus = dubStatuses[entry.dir.path] ?: DubStamp.Status.DRAFT,
                            editedAge = editedAges[entry.dir.path],
                        )
                    }
                    // The same move ROOMS makes under its own list, and KIT
                    // under its grid: one line, always there, naming the hold.
                    // A legend cannot be dismissed, so the gesture cannot be
                    // forgotten - and RENAME is the only place a kit is ever
                    // named by hand (September UAT, finding 17).
                    item(key = "kits-note") {
                        TapeText(Copy.SHELF_LEGEND, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
                    }
                    // INSTRUMENTS: what MAKE INSTRUMENT and MAKE PAD left beside the kits, playable on KEYS.
                    if (instruments.isNotEmpty()) {
                        item(key = "instruments-header") {
                            TapeText("INSTRUMENTS · PLAY THEM ON KEYS", TapeType.pixelSmall, scheme.ink3.tape, Modifier.padding(top = 6.dp), maxLines = 1)
                        }
                        items(instruments, key = { "instrument:" + it.sidecar.name }) { entry ->
                            InstrumentRow(entry, onOpenInstrument)
                        }
                    }
                    // ROOMS: what OUTSIDE measured and KEEP ROOM kept - any pad plays inside one through MUTATE ▸ ROOM.
                    if (rooms.isNotEmpty() || binnedRooms.isNotEmpty()) {
                        item(key = "rooms-header") {
                            TapeText("ROOMS · OUTSIDE MEASURED THEM. ANY PAD PLAYS IN ONE.", TapeType.pixelSmall, scheme.ink3.tape, Modifier.padding(top = 6.dp), maxLines = 1)
                        }
                        items(rooms, key = { "room:" + it.file.name }) { room ->
                            RoomRow(room, busy, onForgetRoom, onShareRoom)
                        }
                        if (rooms.isNotEmpty()) {
                            item(key = "rooms-note") {
                                TapeText(Copy.ROOMS_LEGEND, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
                            }
                        }
                        // The bin's door on the phone: every forgotten room, its days
                        // left, and RESTORE - the same row TAKES + BIN draws for a pad.
                        if (binnedRooms.isNotEmpty()) {
                            item(key = "rooms-bin-header") {
                                TapeText("IN THE BIN · RESTORE BEFORE THE DAYS RUN OUT", TapeType.pixelSmall, scheme.ink3.tape, Modifier.padding(top = 6.dp), maxLines = 1)
                            }
                            items(binnedRooms, key = { "binned:" + it.room.file.name }) { binned ->
                                BinnedRoomRow(binned, busy) {
                                    // A RESTORE tap disarms EMPTY THE BIN NOW —
                                    // DeletedKitsScreen.kt's own `doRestore`
                                    // reasoning: the restored row vanishing
                                    // shifts every row (and this button) up
                                    // under the finger, so whatever tap lands
                                    // next must not be read as EMPTY's genuine
                                    // second tap.
                                    roomsBinArmed = false
                                    onRestoreRoom(it)
                                }
                            }
                            item(key = "rooms-bin-empty") {
                                EmptyRoomsBinButton(scheme, enabled = !busy, armed = roomsBinArmed, onClick = ::doEmptyRoomsBin)
                            }
                        }
                    }
                }
            }

            // Renamed from "FRESH TAPE": on a cold open this reads as
            // "start recording" (the persona walkthroughs' single most
            // costly misread), when what it actually opens is a menu of
            // starter kits — most of them pre-composed, only BLANK/START
            // EMPTY genuinely empty. The label now predicts that.
            PrimaryAction(
                // 18 chars, deliberately: this sits in a single-line
                // PrimaryAction whose longest proven-safe label is 22
                // ("WRITING — DO NOT EJECT"), and at 200% font scale a
                // longer one ellipsizes. "PICK A STARTER" was the honest
                // subtitle but pushed it to 24 and would have truncated
                // mid-word; the menu it opens says PICK A STARTER as its
                // own heading, so the word isn't lost, just moved to where
                // it always fits.
                label = if (busy) "DUBBING…" else "NEW KIT ▸ STARTERS",
                enabled = !busy,
                onClick = { menuOpen = true },
            )
            // BACKUP: the whole shelf as one file, out the share sheet -
            // the "new phone" story; the same file shared back in lands
            // every kit again.
            ActionButton(
                "BACKUP ▸ EVERY KIT, ONE FILE",
                scheme,
                enabled = !busy && kits.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = onBackup,
            )
            // CHOP ALL (XX3 wired in): a multi-file picker's worth of .wav
            // files, each through the same auto-chop CHOP itself uses with
            // the defaults, one new kit per file — the crate-digging verb.
            // Shelf-level, not CHOP's own action row: CHOP SHOP always
            // works on one already-loaded source (TAPE's last commit, or
            // the open kit's own fallback sample); this has no such source
            // and makes many kits, not many pads in one, so it lives beside
            // BACKUP/SNIPS instead.
            ActionButton(
                "CHOP ALL ▸ EVERY FILE, ONE KIT",
                scheme,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onChopAll,
            )
            // SNIPS: always openable, even with zero snips yet (its own
            // empty state says so) — unlike BACKUP above, this isn't gated
            // on the shelf holding anything.
            ActionButton(
                "SNIPS ▸ EVERY CATCH, ONE LIST",
                scheme,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSnips,
            )
            // X-RAY: reads any MPC file the system picker hands back — never
            // gated on the shelf holding anything, same as SNIPS above,
            // since this never lands what it reads onto the shelf at all.
            ActionButton(
                "X-RAY ▸ INSPECT A FILE",
                scheme,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onXRay,
            )
            // DOUBLES: the same read-only posture as X-RAY, pointed at the
            // shelf itself. Never gated on the shelf holding anything - an
            // empty shelf's screen says NO DOUBLES in words, not a dead row.
            ActionButton(
                "DOUBLES ▸ SAME SOUND, ANY KIT",
                scheme,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDoubles,
            )
            // DELETED KITS: gated on the bin actually holding something —
            // unlike SNIPS/BACKUP above, this is never shown merely dimmed;
            // a user who has never deleted a kit sees no door to an empty
            // room at all (locked behavior, Task 2 of the bin-restore plan).
            if (binnedKitsCount > 0) {
                ActionButton(
                    "DELETED KITS ▸ $binnedKitsCount WAITING",
                    scheme,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDeletedKits,
                )
            }
            ArmControl(
                armed = armed,
                onArm = onArm,
                onArmInside = onArmInside,
                onSnip = onSnip,
                onEject = onEject,
            )
        }

        if (menuOpen) {
            val dismissStarter = { menuOpen = false }
            StarterMenu(
                onPick = { dismissStarter(); onFresh(it) },
                onDismiss = dismissStarter,
            )
            BackHandler(onBack = dismissStarter)
        }

        confirmDeleteKit?.let { target ->
            // Cancel, dismiss-by-tapping-outside (KitDeleteConfirmDialog's
            // scrim is a tapeClick(onCancel)), and now system Back all route
            // through this one function, so all three disarm the row.
            val cancelDelete = { confirmDeleteKit = null; armedKitDirs = armedKitDirs - target.dir.path }
            KitDeleteConfirmDialog(
                onCancel = cancelDelete,
                // Delete also clears the armed flag: the lifted set outlives
                // the row (unlike the old per-row remember, which died with
                // it), so a bin-then-recreate of a same-named kit — or a
                // fresh import landing on the freed name — must not inherit
                // a stale armed DELETE/RENAME from the kit that used to
                // live at this path.
                onConfirm = { cancelDelete(); onDeleteKit(target) },
            )
            // Innermost: Back cancels exactly like CANCEL, never DELETE.
            BackHandler(onBack = cancelDelete)
        }
        renameTarget?.let { target ->
            // Same reasoning as DELETE's cancelDelete above.
            val cancelRename = { renameTarget = null; armedKitDirs = armedKitDirs - target.dir.path }
            KitRenameDialog(
                initialName = target.kit.name,
                onCancel = cancelRename,
                // Same reasoning as DELETE's onConfirm above — renaming to
                // the kit's own current name is a same-dir no-op in
                // KitShelf.renameKit, which would otherwise leave this row
                // armed after a confirm.
                onConfirm = { newName -> cancelRename(); onRenameKit(target, newName) },
            )
            // Innermost: Back cancels exactly like CANCEL, never RENAME.
            BackHandler(onBack = cancelRename)
        }
    }
}

@Composable
private fun InstrumentRow(entry: KitShelf.InstrumentEntry, onOpen: (KitShelf.InstrumentEntry) -> Unit) {
    val scheme = LocalScheme.current
    val i = entry.instrument
    Row(
        Modifier
            .fillMaxWidth()
            .raisedBevel(scheme)
            .tapeClick(label = null) { onOpen(entry) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(i.name, TapeType.markerBig, scheme.ink.tape)
            val looped = if (i.zones.any { it.loopStartFrame > 0 }) "  ·  HOLDS" else ""
            TapeText("${i.zones.size} ${if (i.zones.size == 1) "ZONE" else "ZONES"}  ·  ROOT ${com.snipsnap.shell.KeysLayout.label(i.rootNote)}$looped", TapeType.pixelSmall, scheme.ink2.tape)
        }
        TapeText("KEYS ▸", TapeType.pixelSmall, scheme.ink2.tape)
    }
}

/**
 * A kept room: its name, how it was measured, its length on an LCD.
 * Tapping does nothing - a room is not opened, it is used from a pad's
 * MUTATE card. Holding the words presses the row and reveals FORGET → BIN
 * in the bin's red; a tap on the words lets go. The gesture never sits
 * over the button. SHARE sits beside that slot, always visible whether
 * armed or not - packing a room and forgetting it are different
 * questions, so unlike FORGET, SHARE never needs the hold to reach it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomRow(room: Rooms.Room, busy: Boolean, onForget: (Rooms.Room) -> Unit, onShare: (Rooms.Room) -> Unit) {
    val scheme = LocalScheme.current
    var armed by remember(room.file) { mutableStateOf(false) }
    // Ages.ago, not a second inline copy of the same arithmetic: two lists on
    // one screen must not say the same age two different ways.
    val age = Ages.ago(room.measuredAt, System.currentTimeMillis())
    // Built from the parts the sidecar actually held: a room whose sidecar
    // was lost reads UNMEASURED and its age, never "0 MS · 0% SURE ·  ·".
    val meta = buildList {
        if (room.lagMs > 0f || room.confidence > 0f) {
            add("${room.lagMs.roundToInt()} MS")
            add("${(room.confidence * 100).roundToInt()}% SURE")
        } else {
            add("UNMEASURED")
        }
        if (room.from.isNotBlank()) add(room.from.replace(':', ' ').uppercase(Locale.ROOT))
        add(age)
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (armed) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The hold (and the tap that lets go) live on the words, so the
        // FORGET button beside them is never under a gesture that could
        // swallow its tap.
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                // A plain tap/long-press gesture with no drag or
                // position-derived value — combinedClickable registers
                // real onClick/onLongClick accessibility actions for
                // free, unlike the raw pointerInput this replaces, which
                // left this row focusable but inoperable for TalkBack
                // (audit finding 1).
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onLongClickLabel = "REVEAL FORGET",
                    onLongClick = { armed = true },
                    onClick = { armed = false },
                )
                // mergeDescendants stated explicitly (combinedClickable
                // above already sets it) so this property lands on the
                // same merged node regardless of modifier-chain order.
                .semantics(mergeDescendants = true) {
                    if (armed) stateDescription = "FORGET BUTTON REVEALED"
                },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TapeText(room.name, TapeType.markerBig, scheme.ink.tape)
            TapeText(meta, TapeType.pixelSmall, scheme.ink2.tape)
        }
        Box(
            Modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .border(1.dp, scheme.ink2.tape, RoundedCornerShape(4.dp))
                .let { if (!busy) it.tapeClick(label = null) { onShare(room) } else it }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("SHARE", TapeType.pixelSmall, if (busy) scheme.ink3.tape else scheme.ink.tape)
        }
        if (armed) {
            Box(
                Modifier
                    .width(124.dp)
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .border(2.dp, Brush.linearGradient(listOf(BinRedGlow, BIN_RED_BORDER)), RoundedCornerShape(4.dp))
                    .let { if (!busy) it.tapeClick(label = null) { onForget(room) } else it },
                contentAlignment = Alignment.Center,
            ) {
                TapeText("FORGET → BIN", TapeType.pixel, if (busy) scheme.ink3.tape else BinRedGlow)
            }
        } else {
            Box(Modifier.height(30.dp).lcdPanel(scheme).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                TapeText("%.1f s".format(Locale.ROOT, room.seconds), TapeType.lcdSmall, scheme.amber.tape)
            }
        }
    }
}

/**
 * A room in the bin: its name, the days it has left (the last two in
 * `warn`, as TAKES + BIN counts a pad down), and RESTORE in the LCD's
 * second colour. The row is the bin's own - LCD-dark, one line - so a
 * forgotten room never reads as one still on the shelf.
 */
@Composable
private fun BinnedRoomRow(binned: Rooms.Binned, busy: Boolean, onRestore: (Rooms.Binned) -> Unit) {
    val scheme = LocalScheme.current
    val daysLeft = binned.daysLeft(System.currentTimeMillis())
    val dayColor = if (daysLeft <= 2) scheme.warn.tape else scheme.amber.tape
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(5.dp))
            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapeText(binned.room.name, TapeType.marker, scheme.ink.tape, Modifier.weight(1f), maxLines = 1)
        TapeText("${daysLeft}D LEFT", TapeType.lcdSmall, dayColor)
        Box(
            Modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                .let { if (!busy) it.tapeClick(label = null) { onRestore(binned) } else it }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("RESTORE", TapeType.pixelSmall, if (busy) scheme.ink3.tape else scheme.amber.tape)
        }
    }
}

/**
 * EMPTY THE BIN NOW on the rooms bin — `TakesBinScreen.kt`'s own
 * `EmptyBinButton` (`DeletedKitsScreen.kt`/`DeletedSnipsScreen.kt`'s own
 * verbatim copies), duplicated here rather than exported per this
 * codebase's house convention (every screen-local button stays private to
 * its own file — see this file's own `HeaderChip`, which the rooms bin has
 * no need of, so isn't duplicated here too).
 */
@Composable
private fun EmptyRoomsBinButton(scheme: Scheme, enabled: Boolean, armed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(5.dp))
            .border(2.dp, BIN_RED_BORDER, RoundedCornerShape(5.dp))
            .let { if (enabled) it.tapeClick(label = null, onClick = onClick) else it }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            // The armed-label swap, copied verbatim from `TakesBinScreen.kt`'s
            // own `EmptyBinButton` — no `Copy` constant for this text there
            // either (see that file's own KDoc: a deliberate deviation from
            // the artboard, which shows no confirm affordance to match).
            if (armed) "TAP AGAIN TO CONFIRM — NO TAKEBACKS" else "EMPTY THE BIN NOW — NO TAKEBACKS",
            TapeType.pixel,
            if (enabled) BinRedGlow else scheme.ink3.tape,
            maxLines = 1,
        )
    }
}

/**
 * A kit on the shelf: tap opens it, holding the row arms DELETE + RENAME in
 * place of the DRAFT chip — `RoomRow`'s own long-press/tap shape, but the
 * `pointerInput` sits on the whole Row (not a child `Column` beside a
 * sibling button, as `RoomRow` can afford since a room row has no `onOpen`
 * at all): with `onOpen` gone from a plain `tapeClick` and living inside
 * this same `detectTapGestures`'s `onTap` instead, a tap disarms when armed
 * and opens the kit otherwise, and DELETE/RENAME's own child `tapeClick`s
 * still win for their own bounds (a descendant's consumed tap never bubbles
 * to this Row's `onTap`) — the same reason a trailing icon button inside a
 * clickable list row never also fires the row's own click.
 *
 * [pickModeActive] (SNIPS → PAD's `assigningSnip`, Task 3): long-pressing a
 * kit row while the user is mid-pick would arm a red DELETE right beside
 * the very kit they're trying to select — confusing at best, right next to
 * the header's own "TAP A KIT, THEN LONG-PRESS AN EMPTY PAD" hint that
 * primes exactly that gesture. While true, this row falls back to its
 * pre-Task-4 shape entirely: a plain `tapeClick` that only opens the kit,
 * no `pointerInput`/long-press at all, so DELETE/RENAME are simply
 * unreachable for the duration of a pick rather than merely hidden.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KitRow(
    entry: KitShelf.Entry,
    armed: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    busy: Boolean,
    pickModeActive: Boolean,
    onOpen: (KitShelf.Entry) -> Unit,
    onRequestDelete: (KitShelf.Entry) -> Unit,
    onRequestRename: (KitShelf.Entry) -> Unit,
    /** What this kit's last dub left behind, read against the card in the phone — see [DubStamp]. */
    dubStatus: DubStamp.Status = DubStamp.Status.DRAFT,
    /** When this kit was last edited, as [Ages] words — null until the shelf's read lands. */
    editedAge: String? = null,
) {
    val scheme = LocalScheme.current
    val kit = entry.kit
    val revealActions = armed && !pickModeActive
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (revealActions) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
            .then(
                if (pickModeActive) {
                    Modifier.tapeClick(label = null) { onOpen(entry) }
                } else {
                    // A plain tap/long-press pair with no drag — the
                    // sharpest instance the audit found (finding 1): with
                    // no accessibility action at all, this was the only
                    // way to open a kit from the normal shelf flow, and a
                    // switch-access/TalkBack user couldn't reach it.
                    // combinedClickable registers both as real actions.
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onLongClickLabel = "RENAME OR DELETE",
                        onLongClick = { onArm() },
                        onClick = { if (armed) onDisarm() else onOpen(entry) },
                    )
                },
            )
            // mergeDescendants stated explicitly (both branches above —
            // tapeClick and combinedClickable — already set it) so this
            // property lands on the same merged node regardless of
            // modifier-chain order.
            .semantics(mergeDescendants = true) { if (revealActions) stateDescription = "RENAME AND DELETE REVEALED" }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(kit.name, TapeType.markerBig, scheme.ink.tape)
            val tempo = kit.tempoBpm?.let { "  ·  %.0f BPM".format(java.util.Locale.ROOT, it) } ?: ""
            // The age last, and only once it is known: a row that flashed a
            // placeholder before the read landed would be worse than a row
            // that gains a word.
            val age = editedAge?.let { "  ·  $it" } ?: ""
            TapeText("${kit.pads.size} PADS$tempo$age", TapeType.pixelSmall, scheme.ink2.tape)
        }
        if (revealActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme)
                        .let { if (!busy) it.tapeClick(label = null) { onRequestRename(entry) } else it }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("RENAME", TapeType.pixel, if (busy) scheme.ink3.tape else scheme.amber.tape)
                }
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme)
                        .border(2.dp, Brush.linearGradient(listOf(BinRedGlow, BIN_RED_BORDER)), RoundedCornerShape(4.dp))
                        .let { if (!busy) it.tapeClick(label = null) { onRequestDelete(entry) } else it }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("DELETE", TapeType.pixel, if (busy) scheme.ink3.tape else BinRedGlow)
                }
            }
        } else {
            // What the last dub left behind, read against the card this
            // phone is holding right now (September UAT, finding 15). The
            // wizard records the stamp; DubStamp.status decides what may
            // honestly be claimed, and never claims a card that isn't there.
            TapeText(Copy.dubChip(dubStatus), TapeType.pixelSmall, scheme.ink2.tape)
        }
    }
}

/**
 * "DELETE THIS KIT? IT WAITS IN DELETED KITS FOR 30 DAYS." — now names the
 * screen directly, since one exists: DELETED KITS (Task 2 of the bin-restore
 * plan) gives a deleted kit the same shape TAKES + BIN and ROOMS's own
 * FORGET → BIN already had — a visible listing, RESTORE, and an early
 * "EMPTY THE BIN NOW" — so the old refusal to call it "the bin" (this
 * dialog's own prior wording, and `Copy.kitDeleted`'s) no longer applies;
 * that refusal was correct only while no restore path existed. Same scrim +
 * raisedBevel + tap-swallowing shape as `SnipsScreen.kt`'s own
 * `DeleteConfirmDialog` and this file's own `StarterMenu` above —
 * duplicated, not hoisted, per the house convention stated in
 * `SnipsScreen.kt`'s `HeaderChip`.
 */
@Composable
private fun KitDeleteConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // No descendant text of its own (the card is a separate
            // merge boundary via its own no-op tapeClick below) —
            // labelled with the same word the visible CANCEL button uses.
            .tapeClick(label = "CANCEL", onClick = onCancel),
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
            TapeText("DELETE THIS KIT? IT WAITS IN DELETED KITS FOR 30 DAYS.", TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .background(scheme.lcd.tape, RoundedCornerShape(4.dp))
                        .border(2.dp, BIN_RED_BORDER, RoundedCornerShape(4.dp))
                        .tapeClick(label = null, onClick = onConfirm)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("DELETE", TapeType.pixel, BinRedGlow)
                }
            }
        }
    }
}

/**
 * A plain single-line rename field — no rename-input pattern exists
 * elsewhere in the app to copy (FRESH TAPE auto-names through
 * `KitShelf.freshName`, never asking), so this is `BasicTextField` styled to
 * the row's own theme rather than Material's `TextField`. A modal dialog
 * rather than an inline field in the row itself: the row lives inside a
 * `LazyColumn` under a `Column` with no `imePadding`, so an inline field
 * risks the keyboard covering it; a centered dialog (this file's own
 * `StarterMenu`/`KitDeleteConfirmDialog` shape) sidesteps that entirely.
 * RENAME is disabled while the typed name fails `Names.isMpcSafe` — the
 * same refusal `KitShelf.renameKit` would give, surfaced before the tap
 * instead of after.
 */
@Composable
private fun KitRenameDialog(initialName: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    val scheme = LocalScheme.current
    var name by remember { mutableStateOf(initialName) }
    val safe = Names.isMpcSafe(name)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // No descendant text of its own — labelled with the same
            // word the visible CANCEL button below uses.
            .tapeClick(label = "CANCEL", onClick = onCancel),
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
            TapeText("RENAME KIT", TapeType.lcdSmall, scheme.ink.tape)
            Box(
                Modifier
                    .fillMaxWidth()
                    .sunkenField(scheme)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TapeType.marker.copy(color = scheme.ink.tape),
                    cursorBrush = SolidColor(scheme.ink.tape),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!safe) {
                TapeText(
                    "NAMES CAN'T HOLD / \\ : * ? \" < > | OR END IN A DOT/SPACE.",
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                    maxLines = 2,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton("RENAME", scheme, enabled = safe, modifier = Modifier.weight(1f), onClick = { onConfirm(name) })
            }
        }
    }
}

/** The primary action per the handoff: 52dp, rimmed, dark fill. */
@Composable
fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = LocalScheme.current
    val rim =
        if (scheme.id == SchemeId.OILSLICK) {
            Modifier.border(2.dp, oilslickSweep(), RoundedCornerShape(6.dp))
        } else {
            Modifier.border(2.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
        }
    Box(
        Modifier
            .fillMaxWidth()
            .height(Layout.PRIMARY_ACTION_H.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
            .then(rim)
            .then(if (enabled) Modifier.tapeClick(label = null, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            label,
            TapeType.displayBig,
            if (enabled) scheme.lcdInk.tape else scheme.lcdInk.tape.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun StarterMenu(onPick: (StarterKits.Starter) -> Unit, onDismiss: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // No CANCEL button exists in this menu at all (picking a
            // starter or tapping outside are the only ways out), so this
            // scrim is the only accessible dismiss path — it needs its
            // own explicit label.
            .tapeClick(label = "CANCEL", onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .raisedBevel(scheme)
                // Swallow taps so the scrim's dismiss doesn't fire through.
                .tapeClick(label = null) { }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TapeText("PICK A STARTER", TapeType.display, scheme.ink.tape)
            for (starter in StarterKits.ALL) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .sunkenField(scheme)
                        .tapeClick(label = null) { onPick(starter) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TapeText(starter.displayName, TapeType.display, scheme.ink.tape)
                        if (starter.seeded) {
                            Spacer(Modifier.width(6.dp))
                            TapeText("TAPS REROLL", TapeType.pixelSmall, scheme.ink2.tape)
                        }
                    }
                    TapeText(starter.blurb, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
                }
            }
        }
    }
}

/**
 * The capture session's entry point — the shelf's third way a kit begins,
 * alongside FRESH TAPE (machine-invented) and IMPORT (brought in): a
 * capture. Idle: two primary-styled buttons, ARM TAPE (the room, through
 * the mic) and ARM INSIDE (another app's audio, with the projection
 * consent). Armed, whichever source: EJECT in the
 * bin-red pair ([BIN_RED_BORDER]/[BinRedGlow], `TakesBinScreen`'s own
 * convention — a session-ending action reads as "red" even in a scheme
 * with no red anywhere else) beside a small in-app SNIP; the notification
 * action is the out-of-app path, this is the in-app one.
 */
@Composable
private fun ArmControl(
    armed: Boolean,
    onArm: () -> Unit,
    onArmInside: () -> Unit,
    onSnip: () -> Unit,
    onEject: () -> Unit,
) {
    val scheme = LocalScheme.current
    if (!armed) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                PrimaryAction(label = "LISTEN", enabled = true, onClick = onArm)
            }
            Box(Modifier.weight(1f)) {
                PrimaryAction(label = "LISTEN INSIDE ▸ OTHER APPS' AUDIO", enabled = true, onClick = onArmInside)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordingIndicator()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // SNIP takes the wider half, STOP the narrower. It used to be the
            // other way round, which was wrong twice over: SNIP carries the
            // longer label (it names its own window, STOP names nothing) and
            // would ellipsize at large font scale in the smaller box, and
            // SNIP is also the reason the user is on this screen at all —
            // STOP is the way out. A destructive-styled control being the
            // easier one to hit was never the intent.
            Box(
                Modifier
                    .weight(1f)
                    .height(Layout.PRIMARY_ACTION_H.dp)
                    .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                    .border(2.dp, BIN_RED_BORDER, RoundedCornerShape(6.dp))
                    .tapeClick(label = null, onClick = onEject),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("STOP", TapeType.displayBig, BinRedGlow)
            }
            Box(
                Modifier
                    .weight(2f)
                    .height(Layout.PRIMARY_ACTION_H.dp)
                    .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                    .border(2.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
                    .tapeClick(label = null, onClick = onSnip),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("SNIP ▸ UP TO 60s", TapeType.displayBig, scheme.amber.tape)
            }
        }
    }
}

/**
 * The armed session's liveness readout — a live input-level bar paired
 * with a wall-clock elapsed counter, so a silent room can't be misread as
 * a dead indicator: "ARMED" alone doesn't say whether anything is still
 * happening, but "flat meter + a ticking counter" reads unmistakably as
 * *recording, hearing nothing*, which is exactly the diagnostic a truly
 * silent mic should produce.
 *
 * [MicSessionService.level] is collected right here, not hoisted up into
 * [ArmControl] — it updates at ~21 Hz (`READ_BLOCK_FRAMES` @ 44.1kHz), so
 * this is the smallest composable scope that should recompose on every
 * tick.
 *
 * On a MIC session, [LevelBar] also tints its empty field the instant
 * [level] reads exact digital silence (below [SilenceWatch.DEFAULT_THRESHOLD])
 * rather than waiting for [MicSessionService.micSilent]'s multi-second
 * verdict — see [LevelBar]'s own KDoc for why an instantaneous read is
 * honest here even though it's far less specific than the verdict.
 */
@Composable
private fun RecordingIndicator() {
    val scheme = LocalScheme.current
    val level by MicSessionService.level.collectAsState()
    val source by MicSessionService.source.collectAsState()
    // Gated to MIC: on INSIDE, exact digital silence is routine whenever
    // nothing happens to be playing (BlockWatch already handles the real
    // "this app is blocking capture" case with a platform second opinion,
    // isMusicActive, that this cheap per-block read doesn't have) — tinting
    // the meter here for INSIDE would flag the ordinary case as a warning.
    val possiblyMuted = source == MicSessionService.Source.MIC && level <= SilenceWatch.DEFAULT_THRESHOLD

    // The counter anchors to MicSessionService.armedAtElapsedRealtime (a
    // SystemClock timestamp set once, at ARM) rather than counting its own
    // ticks — a locally-counted "start at 0, ++ each second" would reset
    // to 00:00 every time this composable remounts (navigate off the
    // shelf and back while still armed), which reads as a lie about a
    // session that's actually still rolling. Recomputed once up front so a
    // remount shows the true elapsed time immediately, not after the
    // first second-long delay.
    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val anchor = MicSessionService.armedAtElapsedRealtime
            // anchor == 0L means nothing has set it in this process — the
            // service's own default, not a real arm time. Reading a real
            // elapsedRealtime() against that default would read as device
            // uptime (hours), not session time, so treat it as "unknown,
            // show zero" instead of doing that subtraction.
            elapsedSeconds = if (anchor == 0L) {
                0
            } else {
                ((SystemClock.elapsedRealtime() - anchor) / 1000L).toInt().coerceAtLeast(0)
            }
            delay(1000)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .sunkenField(scheme)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LevelBar(level, scheme, possiblyMuted, Modifier.weight(1f).fillMaxHeight())
        TapeText(formatElapsed(elapsedSeconds), TapeType.pixelSmall, scheme.amber.tape)
    }
}

/**
 * [LevelBar]'s empty-field tint when the current block reads exact
 * digital silence on a MIC session — a deliberate, scheme-independent
 * constant rather than a scheme token (the house convention
 * `BIN_RED_BORDER`, below, already uses for the same reason: this should
 * read as "something's off" no matter which scheme is active). A
 * different color from the bin-red pair on purpose — this is a warning
 * about a live session, not an ending of one, so it shouldn't borrow the
 * red reserved for STOP/EMPTY THE BIN/DELETE.
 */
private val MUTE_WARN_FIELD = Color(0xFF4A2E14)

/**
 * [level] is the raw 0f..1f peak; drawn with a sqrt gamma, which reads
 * better than raw linear peak at low levels.
 *
 * [possiblyMuted] tints the empty field a warning color instead of the
 * scheme's own [Scheme.field] the instant the current block reads exact
 * digital silence. This is deliberately a *different, weaker* claim than
 * [MicSessionService.micSilent]'s toast: this is a per-block observation
 * ("this instant's input is exact zero" — true every time it's shown, and
 * can legitimately flicker on for one harmless block between real sounds
 * during ordinary quiet), not a verdict ("this has held long enough to
 * mean something", which is what the toast is for). An instant, weaker
 * signal here is honest precisely because it never claims more than the
 * single block it was computed from.
 */
@Composable
private fun LevelBar(level: Float, scheme: Scheme, possiblyMuted: Boolean = false, modifier: Modifier = Modifier) {
    val filled = sqrt(level.coerceIn(0f, 1f))
    val fieldColor = if (possiblyMuted) MUTE_WARN_FIELD else scheme.field.tape
    Canvas(
        // Canvas-drawn, invisible to the a11y tree by default (audit
        // finding 4). This is a static label rather than a live
        // stateDescription on purpose: level updates at ~21Hz
        // (RecordingIndicator's own KDoc), and TalkBack announcing a
        // number 21 times a second would be noise, not feedback — the
        // adjacent elapsed-time text is the meter this control's own
        // KDoc already designed as the slow-changing, screen-reader-
        // legible proof that the mic is live ("flat meter + a ticking
        // counter reads unmistakably as recording, hearing nothing").
        modifier.semantics { contentDescription = "MIC LEVEL METER" },
    ) {
        drawRect(color = fieldColor, size = size)
        if (filled > 0f) {
            drawRect(color = scheme.amber.tape, size = Size(size.width * filled, size.height))
        }
    }
}

/** mm:ss, uncapped past 59 minutes (RING_SECONDS is 60s; a session runs far longer than the ring holds). */
private fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
}

// Duplicated, not hoisted — TakesBinScreen.kt's own BIN_RED_BORDER
// comment states the house convention explicitly: do it if a clean
// one-liner, else duplicate with a comment. BIN red is deliberately
// constant across every scheme so a session-ending action (EJECT here,
// EMPTY THE BIN there) reads as "red" regardless of the active scheme.
// The glow half moved to Schemes.BIN_RED_GLOW / theme.BinRedGlow
// (accessibility audit finding 5) — a single tuned token, not a duplicated
// literal.
private val BIN_RED_BORDER = Color(0xFF6A2020)
