package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How long a first tap on EMPTY THE BIN NOW stays armed before it disarms itself. */
private const val EMPTY_BIN_ARM_MS = 3_000L

/**
 * TAKES + BIN (X2.3): the safety screen. Every [KitBuilderModel.save] already
 * archives a take before it writes, and every delete already lands in the
 * bin instead of vanishing — this screen is only the read+restore surface
 * over engine behaviour that's tested in `KitBuilderTest.kt`, not new rules
 * of its own.
 *
 * Routed as KIT-scoped overlay state in `App`, the same way PAD SHEET is —
 * not an [AppScreen] entry. MenuRow's items are fixed at ten and TAKES+BIN
 * isn't one of them; it's only reachable from the KIT action row, exactly
 * like PAD SHEET is only reachable by long-pressing a pad.
 *
 * Opens its own [KitBuilderModel] on [entry]'s folder, same as PAD SHEET,
 * and reports every successful restore back up via [onKitUpdated] so
 * `App`'s copy of the kit stays in step. KIT's engine bank needs no
 * special invalidation here: `App` renders this screen *instead of*
 * `KitScreen` (see `App.kt`'s `AppScreen.KIT` branch), so `KitScreen`
 * leaves composition while this screen is up and its `DisposableEffect`
 * closes the old `PadEngine`; a fresh one loads from `entry.kit` the
 * moment KIT recomposes on the way back (`LaunchedEffect(entry.kit)`,
 * keyed on structural equality, not identity — any real content change
 * from a restore reloads it). `restoreFromBin` alone never touches
 * `kit.json` (it only moves a WAV back onto disk), so it doesn't call
 * [onKitUpdated]; nothing in the live kit refers to that file yet.
 */
@Composable
fun TakesBinScreen(
    entry: KitShelf.Entry,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onKitUpdated: (com.snipsnap.kit.Kit) -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    var model by remember(entry.dir) { mutableStateOf<KitBuilderModel?>(null) }
    var loadFailed by remember(entry.dir) { mutableStateOf(false) }
    LaunchedEffect(entry.dir) {
        loadFailed = false
        // Locked, not just opened — same reasoning as PadSheetScreen's own
        // mount open: KitScreen's pad-grid long-press has no busy gate, so
        // this mount can race a SET KEY/EVIL TWINS/IN KEY write already in
        // flight on `entry.dir`. Opening under the same
        // KitWrites.mutex.withLock every writer already uses means this
        // snapshot is always taken fully before or fully after that write,
        // never mid-write — see Law 5 in ConventionTest.kt.
        val opened = withContext(Dispatchers.IO) {
            KitWrites.mutex.withLock { runCatching { KitBuilderModel.open(entry.dir) }.getOrNull() }
        }
        if (opened == null) loadFailed = true else model = opened
    }

    var busy by remember(model) { mutableStateOf(false) }
    var armed by remember(model) { mutableStateOf(false) }
    var takeFiles by remember(model) { mutableStateOf<List<File>>(emptyList()) }
    var binEntries by remember(model) { mutableStateOf<List<KitBuilderModel.BinEntry>>(emptyList()) }
    var kitSnapshot by remember(model) { mutableStateOf(entry.kit) }

    /**
     * `m.takes()`/`m.binContents()` are pure directory scans — they read
     * `kitDir` off disk, never `m.kit` — so they're safe to call on the
     * long-lived [m] even when [m]'s own in-memory `kit` is stale (this
     * screen never writes through [m] directly; see [doRestoreTake]).
     * [kitOverride] is the ONLY thing that ever writes [kitSnapshot]: [m]'s
     * own `kit` never changes after mount (this screen never mutates [m]),
     * so a caller that passes no override — [doRestoreFromBin], [doEmptyBin],
     * neither of which touch `kit.json` — must leave [kitSnapshot] alone
     * rather than resetting it back to the stale mount-time snapshot. Only
     * [doRestoreTake], which DOES change what's live, and the initial load
     * below, which has nothing to preserve yet, pass one.
     */
    suspend fun refreshLists(m: KitBuilderModel, kitOverride: com.snipsnap.kit.Kit? = null) {
        val (t, b) = withContext(Dispatchers.IO) { m.takes() to m.binContents() }
        takeFiles = t
        binEntries = b
        if (kitOverride != null) kitSnapshot = kitOverride
    }

    LaunchedEffect(model) { model?.let { refreshLists(it, it.kit) } }

    // The armed EMPTY THE BIN NOW confirm quietly stands down if the second
    // tap never comes — a stale "tap again" that's still armed a minute
    // later would be a trap, not a safety net.
    LaunchedEffect(armed) {
        if (armed) {
            delay(EMPTY_BIN_ARM_MS)
            armed = false
        }
    }

    fun failure(action: String, e: Exception) {
        onToast("$action FAILED: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * `restoreTake` leaves the model [KitBuilderModel.dirty] but unsaved
     * (see its own KDoc) — this screen is the one door that calls it, so
     * it also owns turning that into a real, persisted `save()`, the same
     * way PAD SHEET's `commitPadEditNow` always pairs a mutate with a save.
     *
     * Runs against a FRESH model opened under the lock (see
     * [withFreshKit]'s KDoc), never the long-lived [model] — mirrors
     * PadSheetScreen's own Law 5 fix. Unlike PAD SHEET, [model] itself is
     * NOT swapped afterward: this screen keys `busy`/`armed`/`takeFiles`/
     * `binEntries`/`kitSnapshot` on [model]'s identity (`remember(model)`),
     * and `restoreTake` fully replaces `kit` from the take file's own
     * content regardless of which model instance it runs against — so
     * [model]'s own in-memory `kit` was never load-bearing for this write
     * in the first place. Keeping [model] as-is avoids resetting that
     * bookkeeping for no benefit; [refreshLists] is fed the fresh result
     * explicitly instead.
     *
     * [expectedMtime] is [take]'s own `lastModified()` at the moment this
     * row was built (`TakeRow.lastModifiedMillis`), not re-read now: a
     * captured `File` reference alone isn't a safe identity check here.
     * [KitBuilderModel.archiveTake] rotates — `takes().dropLast(MAX_TAKES)`
     * deletes the oldest, and the next archive's name is derived from
     * `takes().lastOrNull()` — so `take_NNN.json` filenames get RECYCLED:
     * the same path can, in the gap between this row being listed and this
     * lock being acquired, come to hold a completely different archive.
     * `take.isFile` alone can't see that (the file is still there, just
     * not the one this row meant); comparing the mtime this row actually
     * saw is the identity check `restoreTake` itself relies on (its own
     * KDoc: `take.lastModified()` IS the T it rolls back to), the same
     * discipline the pad-edit sites apply via `sampleFile`.
     */
    fun doRestoreTake(take: File, label: String, expectedMtime: Long) {
        if (busy) return
        val m = model ?: return
        val kitDir = m.kitDir
        scope.launch {
            busy = true
            try {
                var restoredKit: com.snipsnap.kit.Kit? = null
                withFreshKit(kitDir) { fresh ->
                    if (take.isFile && take.lastModified() == expectedMtime && fresh.takes().any { it == take }) {
                        restoredKit = fresh.restoreTake(take)
                    }
                }
                val kit = restoredKit
                if (kit != null) {
                    onKitUpdated(kit)
                    refreshLists(m, kit)
                    onToast(Copy.takeRestored(label))
                } else {
                    // The row's own take was rotated out or overwritten
                    // between the list being built and this lock landing —
                    // the same "someone beat you to it" shape BIN_ITEM_GONE
                    // already names for the bin side of this screen.
                    refreshLists(m)
                    onToast(Copy.BIN_ITEM_GONE)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("RESTORE", e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * The entry-keyed overload, not the name-keyed one — `treatPad`/`eraPad`
     * can bin several copies of the same `originalName` (each treat-then-
     * rewrite cycle bins the previous version under that same filename), and
     * this row's own [KitBuilderModel.BinEntry] is a specific one of those,
     * distinguished on screen by its own countdown. The name-keyed overload
     * always resolves to the newest, which is not necessarily the row that
     * was tapped.
     */
    fun doRestoreFromBin(entry: KitBuilderModel.BinEntry) {
        if (busy) return
        val m = model ?: return
        scope.launch {
            busy = true
            try {
                val restoredFile = withContext(Dispatchers.IO) { m.restoreFromBin(entry) }
                if (restoredFile != null) {
                    refreshLists(m)
                    onToast(Copy.BACK_FROM_BIN)
                } else {
                    // Not an exception — just a row that outran the tap (see
                    // `Copy.BIN_ITEM_GONE`'s KDoc). Still worth a fresh list.
                    refreshLists(m)
                    onToast(Copy.BIN_ITEM_GONE)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("RESTORE", e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * Destructive, and the artboard's own `binEmptyNow` doesn't arm a
     * confirm before firing — an app that actually ships this needs one,
     * so this is a deliberate deviation from the prototype: a same-screen
     * armed double-tap (button text swaps, ~3s window) rather than a modal
     * dialog, since the artboard shows no confirm affordance to match.
     */
    fun doEmptyBin() {
        if (busy) return
        if (!armed) {
            armed = true
            return
        }
        armed = false
        val m = model ?: return
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { m.emptyBin() }
                refreshLists(m)
                onToast(Copy.BIN_EMPTIED)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("EMPTY BIN", e)
            } finally {
                busy = false
            }
        }
    }

    if (model == null) {
        // Still opening, or the folder wouldn't parse — the header's own
        // back arrow is the one piece of chrome that must work regardless
        // (mirrors PAD SHEET's own load-failure shell). Uses KIT_WONT_OPEN,
        // not EMPTY_SHELF: EMPTY_SHELF claims no kit exists on the shelf at
        // all, which is false here — a specific, already-open kit's folder
        // just wouldn't parse.
        // Still opening or the folder wouldn't parse — nothing dirty here
        // yet, so Back matches this shell's own bare `onBack()`, not the
        // `enabled = !busy` chip below (there's no `busy` operation to wait
        // on in this state).
        BackHandler { onBack() }
        Box(Modifier.fillMaxSize().lcdPanel(scheme).padding(14.dp)) {
            HeaderChip("◄ KIT", scheme, Modifier.align(Alignment.TopStart).width(64.dp)) { onBack() }
            if (loadFailed) {
                TapeText(Copy.KIT_WONT_OPEN, TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.align(Alignment.Center), maxLines = 3)
            }
        }
        return
    }

    val takeRows = remember(takeFiles) {
        val total = takeFiles.size + 1
        buildList {
            add(TakeRow(label = "T$total", whenText = null, current = true, file = null, lastModifiedMillis = 0L))
            takeFiles.asReversed().forEachIndexed { i, f ->
                // Read once, kept: this is the row's own identity check for
                // `doRestoreTake` (see its KDoc) as well as the display text
                // below, so both read the exact same instant this list was
                // built rather than re-reading the file's mtime later, after
                // a rotation may have already changed what's at this path.
                val mtime = f.lastModified()
                add(TakeRow(label = "T${total - 1 - i}", whenText = agoLabel(mtime), current = false, file = f, lastModifiedMillis = mtime))
            }
        }
    }

    val binRows = remember(binEntries, kitSnapshot) {
        val now = System.currentTimeMillis()
        binEntries.map { be ->
            val daysSince = (now - be.binnedAtMillis) / 86_400_000.0
            // Ceil, not round-to-nearest — "0D LEFT" must mean genuinely
            // purge-eligible (matches purgeBin's own `< cutoff` check), not
            // "rounded down from up to ~12h still standing."
            val daysLeft = (KitBuilderModel.BIN_KEEP_DAYS - daysSince).let { left -> if (left <= 0) 0 else ceil(left).toInt() }
            // "Recoverable from the kit": a currently-assigned pad whose
            // sample (main or a GHOSTS layer) shares this bin entry's
            // original name. In practice this rarely matches — a file only
            // reaches the bin once nothing in the live kit still points at
            // it (`deleteIfUnreferenced`) — so `null` (neutral ink3) is the
            // common, honest case, not a bug.
            val matchedPad = kitSnapshot.pads.firstOrNull { p ->
                p.sampleFile == be.originalName || p.velocityLayers.any { it.sampleFile == be.originalName }
            }
            val classColor = matchedPad?.let { p ->
                (p.colorHex?.removePrefix("#")?.toIntOrNull(16) ?: Schemes.classColor(p.drumClass)).tape
            }
            BinRow(be, daysLeft, classColor)
        }
    }

    // Mirrors the header chip's own `enabled = !busy` below — a RESTORE/
    // EMPTY THE BIN write in flight must not be interrupted by Back any
    // more than by the chip itself.
    // Always registered, no-op while busy — NOT `enabled = !busy`. This screen
    // is one of App's overlays, so the root handler excludes itself whenever
    // `takesBinOpen` is set; a disabled handler here would leave ZERO enabled
    // callbacks mid-write and the dispatcher would fall through to
    // Activity.finish() — Back would exit the app in the middle of a RESTORE.
    // Swallowing is safe rather than a trap: MenuRow renders above this screen
    // the whole time, so a tab is always there to leave by. SplitScreen's
    // `BackHandler { if (!working) onExit() }` is the same shape.
    BackHandler { if (!busy) onBack() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChip("◄ KIT", scheme, Modifier.width(64.dp), enabled = !busy, onClick = onBack)
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .lcdPanel(scheme)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TapeText("TAKES + THE BIN", TapeType.lcdHeader, scheme.lcdInk.tape)
                TapeText(kitSnapshot.name, TapeType.lcdSmall, scheme.amber.tape)
            }
        }

        // Brief's explicit placement ("small under the LCD header"), not the
        // artboard's (`isTakes` in `TapeOS Oilslick.dc.html` puts this line
        // last, after both cards) — the artboard governs card/row structure
        // everywhere else in this screen, but this one bullet is a specific,
        // deliberate placement call the brief's author made, not a casual
        // restatement of the artboard, so it wins here.
        TapeText(Copy.TAKES_BIN_RULE, TapeType.pixelSmall, scheme.ink3.tape, Modifier.fillMaxWidth(), maxLines = 2)

        Column(
            // The padding lands *after* verticalScroll so it insets the
            // scrolled content, not the viewport — the TAKES pill sits at
            // offset y=-8.dp off its card's top edge (see PillCard), and
            // without this the viewport clips it the moment this Column
            // scrolls at all.
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillCard("TAKES — EVERY SAVE REMEMBERED", scheme, scheme.accent.tape, scheme.accent.tape) {
                for (row in takeRows) {
                    TakeRowLine(row, scheme, busy) { file, label, mtime -> doRestoreTake(file, label, mtime) }
                }
                if (takeRows.size == 1) {
                    TapeText(Copy.TAKES_EMPTY, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
                }
            }

            PillCard("THE BIN — KEPT 30 DAYS", scheme, BIN_RED_BORDER, BIN_RED_GLOW) {
                if (binRows.isEmpty()) {
                    TapeText(Copy.BIN_EMPTY_STATE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
                } else {
                    for (row in binRows) {
                        BinRowLine(row, scheme, busy) { doRestoreFromBin(it) }
                    }
                }
                EmptyBinButton(scheme, enabled = !busy && binRows.isNotEmpty(), armed = armed, onClick = ::doEmptyBin)
            }
        }
    }
}

private data class TakeRow(
    val label: String,
    val whenText: String?,
    val current: Boolean,
    val file: File?,
    /** [file]'s `lastModified()` as read when this row was built — [doRestoreTake]'s own identity check, see its KDoc. Meaningless (0L) on the `current`/no-`file` row. */
    val lastModifiedMillis: Long,
)

private data class BinRow(val entry: KitBuilderModel.BinEntry, val daysLeft: Int, val classColor: Color?)

/**
 * The one correct shape for a kit mutation issued by a screen whose own
 * [KitBuilderModel] was opened earlier, outside any lock — mirrors
 * PadSheetScreen's own `withFreshKit` (same file-local-duplication
 * convention this file's own [HeaderChip] KDoc already follows, rather
 * than exporting a cross-screen shared function). Locks, opens a FRESH
 * model on [kitDir], runs [block] against it, and saves once — but only if
 * [block] actually left the fresh model [KitBuilderModel.dirty], which is
 * how a caller's [block] can implement "my target vanished/was reassigned"
 * by simply mutating nothing, rather than reporting it a second way.
 */
private suspend fun withFreshKit(kitDir: File, block: (KitBuilderModel) -> Unit) {
    withContext(Dispatchers.IO) {
        KitWrites.mutex.withLock {
            val fresh = KitBuilderModel.open(kitDir)
            block(fresh)
            if (fresh.dirty) fresh.save()
        }
    }
}

/** Coarse relative age off a take's real archive timestamp (its file mtime) — no invented "what changed" text. */
private fun agoLabel(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val diffMs = (nowMillis - millis).coerceAtLeast(0)
    val minutes = diffMs / 60_000
    val hours = diffMs / 3_600_000
    val days = diffMs / 86_400_000
    return when {
        minutes < 1 -> "JUST NOW"
        minutes < 60 -> "$minutes MIN AGO"
        hours < 24 -> "$hours HR AGO"
        days == 1L -> "YESTERDAY"
        else -> "${days}D AGO"
    }
}

@Composable
private fun TakeRowLine(row: TakeRow, scheme: Scheme, busy: Boolean, onRestore: (File, String, Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(if (row.current) scheme.raised.tape else scheme.lcd.tape, RoundedCornerShape(5.dp))
            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapeText(row.label, TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.width(28.dp))
        Spacer(Modifier.weight(1f))
        row.whenText?.let { TapeText(it, TapeType.pixelSmall, scheme.ink3.tape) }
        if (row.current) {
            TapeText("NOW", TapeType.pixelSmall, scheme.amber.tape)
        } else if (row.file != null) {
            val file = row.file
            Box(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                    .let { if (!busy) it.tapeClick(label = null) { onRestore(file, row.label, row.lastModifiedMillis) } else it }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("RESTORE", TapeType.pixelSmall, if (busy) scheme.ink3.tape else scheme.amber.tape)
            }
        }
    }
}

@Composable
private fun BinRowLine(row: BinRow, scheme: Scheme, busy: Boolean, onRestore: (KitBuilderModel.BinEntry) -> Unit) {
    // ≤2 days left renders in `scheme.warn` (the handoff's "≤2 days amber");
    // everything else stays in the LCD's second colour, same as the artboard.
    val dayColor = if (row.daysLeft <= 2) scheme.warn.tape else scheme.amber.tape
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
        Box(
            Modifier
                .width(8.dp)
                .heightIn(min = 8.dp)
                .background(row.classColor ?: scheme.ink3.tape, RoundedCornerShape(2.dp)),
        )
        TapeText(row.entry.originalName, TapeType.marker, scheme.ink.tape, Modifier.weight(1f), maxLines = 1)
        TapeText("${row.daysLeft}D LEFT", TapeType.lcdSmall, dayColor)
        Box(
            Modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                .let { if (!busy) it.tapeClick(label = null) { onRestore(row.entry) } else it }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("BACK", TapeType.pixelSmall, if (busy) scheme.ink3.tape else scheme.amber.tape)
        }
    }
}

@Composable
private fun EmptyBinButton(scheme: Scheme, enabled: Boolean, armed: Boolean, onClick: () -> Unit) {
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
            // The armed state is a deviation from the artboard (see
            // `doEmptyBin`'s KDoc) so it earns its own label here rather
            // than a `Copy` constant for prototype text that doesn't exist.
            if (armed) "TAP AGAIN TO CONFIRM — NO TAKEBACKS" else "EMPTY THE BIN NOW — NO TAKEBACKS",
            TapeType.pixel,
            if (enabled) BIN_RED_GLOW else scheme.ink3.tape,
            maxLines = 1,
        )
    }
}

/** A bordered field card with a floating pill label, top-left — the TAKES/BIN card shape from the artboard. */
@Composable
private fun PillCard(
    label: String,
    scheme: Scheme,
    pillBorder: Color,
    pillInk: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(scheme.field.tape, RoundedCornerShape(6.dp))
            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(6.dp)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            content = content,
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = 12.dp, y = (-8).dp)
                .background(scheme.raised.tape, RoundedCornerShape(999.dp))
                .border(1.dp, pillBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            TapeText(label, TapeType.pixelSmall, pillInk, maxLines = 1)
        }
    }
}

/**
 * Duplicated from `PadSheetScreen`'s own private `HeaderChip` rather than
 * exported — every other screen-local button in this codebase (see that
 * file's `ActionButton`, `ToggleChip`) stays private to its own file too,
 * so this follows the existing convention instead of introducing the
 * first cross-screen shared button.
 */
@Composable
private fun HeaderChip(
    label: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(3.dp))
            .let { if (enabled) it.tapeClick(label = null, onClick = onClick) else it }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (enabled) scheme.ink.tape else scheme.ink3.tape)
    }
}

// Duplicated, not hoisted (brief's own call: "do it if a clean one-liner,
// else duplicate with a comment") — PadSheetScreen.kt and ExportScreen.kt
// each already carry this same pair privately; this is the third. BIN red
// is deliberately constant across every scheme (HANDOFF.md X2 / TAKES+BIN),
// so a delete action reads as "red" even in a scheme with no red anywhere
// else in it.
private val BIN_RED_BORDER = Color(0xFF6A2020)
private val BIN_RED_GLOW = Color(0xFFC86050)
