package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.snipsnap.app.theme.BinRedGlow
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How long a first tap on EMPTY THE BIN NOW stays armed before it disarms itself — `TakesBinScreen.kt`'s own constant, copied verbatim. */
private const val EMPTY_BIN_ARM_MS = 3_000L

/**
 * DELETED KITS (Task 2 of the bin-restore plan): the shelf-level listing
 * over `KitShelf.binnedKits()` — what `KitsScreen`'s DELETE ▸ BIN sends to
 * `.bin/` now gets the same shape TAKES + BIN and ROOMS's own FORGET → BIN
 * already had (a visible listing, RESTORE, an early "EMPTY THE BIN NOW"),
 * closing the gap `KitsScreen.kt`'s and `Personality.kt`'s own prior KDocs
 * used to describe accurately: a deleted kit had neither.
 *
 * Reached from the shelf (`KitsScreen`'s own `DELETED KITS ▸` row, shown
 * only when the bin is non-empty), never KIT-scoped — `App.kt`'s
 * `deletedKitsOpen` mirrors `snipsOpen` exactly, a shelf-level overlay
 * boolean rather than an `AppScreen` entry of its own.
 *
 * No identity guard against `App`'s `open` kit is needed here, unlike
 * `deleteKit`/`renameKit` in `App.kt`: a binned kit's directory sits under
 * `.bin/`, which `KitStore.list` never walks, so nothing currently open on
 * the shelf can ever BE one of these rows, and `restoreKit`'s own
 * `freshShelfName` collision fallback can only land under a shelf name that
 * doesn't already exist — it can never silently repoint a directory `open`
 * is still holding.
 */
@Composable
fun DeletedKitsScreen(
    shelf: KitShelf,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onRestored: (KitShelf.Entry) -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    var binned by remember { mutableStateOf<List<KitShelf.BinnedKit>>(emptyList()) }
    LaunchedEffect(Unit) {
        binned = withContext(Dispatchers.IO) { shelf.binnedKits() }
    }

    var busy by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }

    // The armed EMPTY THE BIN NOW confirm quietly stands down if the second
    // tap never comes — `TakesBinScreen.kt`'s own `LaunchedEffect(armed)`,
    // copied verbatim.
    LaunchedEffect(armed) {
        if (armed) {
            delay(EMPTY_BIN_ARM_MS)
            armed = false
        }
    }

    fun failure(action: String, e: Exception) {
        onToast("$action FAILED: ${e.message ?: e.javaClass.simpleName}")
    }

    fun doRestore(target: KitShelf.BinnedKit) {
        if (busy) return
        // A RESTORE tap disarms EMPTY THE BIN NOW: the restored row vanishing
        // shifts every row (and the EMPTY button) up under the finger, so
        // whatever tap lands next must not be read as EMPTY's genuine second
        // tap — the same "no takebacks" promise the button itself makes.
        armed = false
        busy = true
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.restoreKit(target) } }
                if (restored != null) {
                    binned = binned.filter { it.dir != target.dir }
                    // The name it actually landed under, off the returned
                    // Entry — `restoreKit` rewrites kit.json to match, and a
                    // collision may have freshened it past what this row
                    // showed. Never `target.name`.
                    onToast(Copy.kitRestored(restored.kit.name))
                    onRestored(restored)
                } else {
                    // Not an exception — a row that outran the tap (a stale
                    // list, a second restore/sweep racing this one). Still
                    // worth a fresh list, `TakesBinScreen.kt`'s own
                    // `doRestoreFromBin` shape.
                    binned = withContext(Dispatchers.IO) { shelf.binnedKits() }
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

    fun doEmptyBin() {
        if (busy) return
        if (!armed) {
            armed = true
            return
        }
        armed = false
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { KitWrites.mutex.withLock { shelf.emptyKitBin() } }
                binned = emptyList()
                onToast(Copy.kitBinEmptied)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("EMPTY BIN", e)
            } finally {
                busy = false
            }
        }
    }

    // The chip below carries `enabled = !busy`; this must NOT. A disabled
    // handler unregisters, and this screen is one of App's overlays, so the
    // root handler already excludes itself whenever `deletedKitsOpen` is set —
    // between them there would be ZERO enabled callbacks mid-write and the
    // dispatcher would fall through to Activity.finish(), exiting the app in
    // the middle of a RESTORE or an EMPTY THE BIN NOW. Registered always,
    // no-op while busy, matching SplitScreen's `if (!working) onExit()`.
    // Swallowing isn't a trap: MenuRow stays rendered above this screen.
    BackHandler { if (!busy) onBack() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChip("◄ SHELF", scheme, Modifier.width(72.dp), enabled = !busy, onClick = onBack)
            Spacer(Modifier.weight(1f))
            TapeText("DELETED KITS", TapeType.lcdHeader, scheme.lcdInk.tape)
            Spacer(Modifier.weight(1f))
            TapeText("${binned.size}", TapeType.lcdSmall, scheme.amber.tape)
        }

        if (binned.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .lcdPanel(scheme)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Plain, not the tape-metaphor voice — SNIPS's own locked
                // tone (`SnipsScreen.kt`'s "NO SNIPS YET"), not the tape
                // shelf's usual quips.
                TapeText("NOTHING DELETED.", TapeType.lcdSmall, scheme.lcdInk.tape)
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
                // Keyed on the bin directory's own name, not `it.name` — a
                // kit named FACTORY deleted twice leaves two bin entries
                // that both read "FACTORY" (kit.json's own name, not unique
                // in the bin), but `deleteKit`'s own collision loop already
                // guarantees the directory names are.
                items(binned, key = { it.dir.name }) { row ->
                    DeletedKitRow(row, busy, onRestore = { doRestore(row) })
                }
            }
        }

        if (binned.isNotEmpty()) {
            EmptyBinButton(scheme, enabled = !busy, armed = armed, onClick = ::doEmptyBin)
        }
    }
}

/** Coarse relative age, same shape as `SnipsScreen.kt`'s own `relativeTime` / `TakesBinScreen.kt`'s own `agoLabel` — duplicated, not hoisted, both private to their own files already; this is the third copy, per the house convention stated in `SnipsScreen.kt`'s `HeaderChip`. */
private fun relativeTime(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
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
private fun DeletedKitRow(row: KitShelf.BinnedKit, busy: Boolean, onRestore: () -> Unit) {
    val scheme = LocalScheme.current
    // ≤2 days left renders in `scheme.warn`, the same threshold TAKES + BIN
    // and ROOMS's own binned row both use.
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(row.name, TapeType.marker, scheme.ink.tape, maxLines = 1)
            // "3D AGO", not the brief's literal "3 DAYS AGO" — the brief's
            // own instruction to reuse SNIPS' relative-time helper wins over
            // its own prose example; `relativeTime` above is that helper,
            // duplicated verbatim, and it emits "D AGO".
            TapeText(
                "${row.padCount} ${if (row.padCount == 1) "PAD" else "PADS"}  ·  ${relativeTime(row.binnedAtMillis)}",
                TapeType.pixelSmall,
                scheme.ink2.tape,
            )
        }
        // "${daysLeft}D LEFT" matches TAKES + BIN's and ROOMS's own binned
        // rows (`BinRowLine`/`BinnedRoomRow`) rather than the brief's literal
        // "27 DAYS LEFT" — no reuse directive covers this one, so this picks
        // the format the app's other two bins already use rather than a
        // third, novel one. A row at 0 days left still shows and still
        // restores — the sweep, not this screen, decides when it's actually
        // gone (locked behavior); this just reads whatever `daysLeft` says.
        TapeText("${row.daysLeft}D LEFT", TapeType.lcdSmall, dayColor)
        Box(
            Modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                .let { if (!busy) it.tapeClick(label = null, onClick = onRestore) else it }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("RESTORE", TapeType.pixelSmall, if (busy) scheme.ink3.tape else scheme.amber.tape)
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
            // `TakesBinScreen.kt`'s own armed-label swap, copied verbatim.
            if (armed) "TAP AGAIN TO CONFIRM — NO TAKEBACKS" else "EMPTY THE BIN NOW — NO TAKEBACKS",
            TapeType.pixel,
            if (enabled) BinRedGlow else scheme.ink3.tape,
            maxLines = 1,
        )
    }
}

/** Duplicated from `SnipsScreen.kt`'s own private `HeaderChip` (and `TakesBinScreen.kt`'s) rather than exported — every screen-local button in this codebase stays private to its own file, per that file's own stated convention. */
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

// Duplicated, not hoisted — `TakesBinScreen.kt`'s own `BIN_RED_BORDER`,
// deliberately constant across every scheme so a delete/empty action reads
// as "red" even in a scheme with no red anywhere else in it. The glow half
// moved to Schemes.BIN_RED_GLOW / theme.BinRedGlow (accessibility audit
// finding 5) — a single tuned token, not a duplicated literal.
private val BIN_RED_BORDER = Color(0xFF6A2020)
