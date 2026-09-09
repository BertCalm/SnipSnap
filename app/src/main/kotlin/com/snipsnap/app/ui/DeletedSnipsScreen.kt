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
import com.snipsnap.app.theme.BinRedGlow
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.SnipStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a first tap on EMPTY THE BIN NOW stays armed before it disarms itself — `TakesBinScreen.kt`'s own constant, copied verbatim (per this codebase's own duplicate-don't-hoist convention, see `SnipsScreen.kt`'s `HeaderChip`). */
private const val EMPTY_BIN_ARM_MS = 3_000L

/**
 * DELETED SNIPS (name-and-find task): the SNIPS-level listing over
 * `SnipStore.binned()` — what SNIPS's own DELETE sends to `snips/.bin/`
 * now gets the same shape TAKES + BIN, ROOMS's own FORGET → BIN, and
 * DELETED KITS already have (a visible listing, RESTORE, an early "EMPTY
 * THE BIN NOW"), closing the same gap for the fourth deletion path in the
 * app that used to have none at all.
 *
 * Reached from SNIPS (its own "DELETED SNIPS ▸" row, shown only when the
 * bin is non-empty) — not the shelf, since `DeletedKitsScreen` is. It's
 * nested one level deeper: `SnipsScreen`'s own `deletedSnipsOpen` renders
 * this INSTEAD of itself, so this screen owns Back entirely on its own
 * while showing (`SnipsScreen`'s dialogs/BackHandler are never composed in
 * that window — see its own KDoc on the early return).
 */
@Composable
fun DeletedSnipsScreen(
    shelf: KitShelf,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onRestored: (File) -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    var binned by remember { mutableStateOf<List<SnipStore.BinnedSnip>>(emptyList()) }
    LaunchedEffect(Unit) {
        binned = withContext(Dispatchers.IO) { SnipStore.binned(shelf.root) }
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

    fun doRestore(target: SnipStore.BinnedSnip) {
        if (busy) return
        // A RESTORE tap disarms EMPTY THE BIN NOW: the restored row
        // vanishing shifts every row (and the EMPTY button) up under the
        // finger, so whatever tap lands next must not be read as EMPTY's
        // genuine second tap — `DeletedKitsScreen.kt`'s own `doRestore`.
        armed = false
        busy = true
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { SnipStore.restore(shelf.root, target) }
                if (restored != null) {
                    binned = binned.filter { it.file != target.file }
                    // The name it actually landed under, off the returned
                    // File — SnipStore.restore's own collision fallback may
                    // have freshened it past what this row showed. Never
                    // target.displayName.
                    onToast(Copy.snipRestored(SnipStore.displayName(restored)))
                    onRestored(restored)
                } else {
                    // Not an exception — a row that outran the tap (a stale
                    // list, a second restore/sweep racing this one). Still
                    // worth a fresh list, `DeletedKitsScreen.kt`'s own shape.
                    binned = withContext(Dispatchers.IO) { SnipStore.binned(shelf.root) }
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
                withContext(Dispatchers.IO) { SnipStore.emptyBin(shelf.root) }
                binned = emptyList()
                onToast(Copy.snipBinEmptied)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("EMPTY BIN", e)
            } finally {
                busy = false
            }
        }
    }

    // Registered always, no-op while busy — the same reasoning
    // `DeletedKitsScreen.kt`'s own BackHandler KDoc gives verbatim: an
    // `enabled = !busy` here would unregister mid-write with nothing else
    // active at this overlay's level, and Back would fall through to
    // Activity.finish().
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
            HeaderChip("◄ SNIPS", scheme, Modifier.width(72.dp), enabled = !busy, onClick = onBack)
            Spacer(Modifier.weight(1f))
            TapeText("DELETED SNIPS", TapeType.lcdHeader, scheme.lcdInk.tape)
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
                // tone (`SnipsScreen.kt`'s "NO SNIPS YET").
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
                // Keyed on the bin file's own name, not the display name —
                // two snips that both read "KICK" (different capture
                // times) still get distinct bin filenames ([SnipStore]'s
                // own binning stamp), the same reasoning
                // `DeletedKitsScreen.kt` gives for keying on `it.dir.name`.
                items(binned, key = { it.file.name }) { row ->
                    DeletedSnipRow(row, busy, onRestore = { doRestore(row) })
                }
            }
        }

        if (binned.isNotEmpty()) {
            EmptyBinButton(scheme, enabled = !busy, armed = armed, onClick = ::doEmptyBin)
        }
    }
}

/** Every [DrumClass] keyed by its own [AutoPlace.nameFor] output — duplicated from `SnipsScreen.kt`'s own `CLASS_BY_NAME` (per this codebase's own duplicate-don't-hoist convention) rather than exported. */
private val CLASS_BY_NAME: Map<String, DrumClass> = DrumClass.entries.associateBy { AutoPlace.nameFor(it) }

/** Coarse relative age, duplicated from `SnipsScreen.kt`'s own `relativeTime` / `DeletedKitsScreen.kt`'s own copy — the fourth, per the house convention stated in `SnipsScreen.kt`'s `HeaderChip`. */
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

/** Duplicated from `SnipsScreen.kt`'s own private `humanSize` — same reasoning. */
private fun humanSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
private fun DeletedSnipRow(row: SnipStore.BinnedSnip, busy: Boolean, onRestore: () -> Unit) {
    val scheme = LocalScheme.current
    // ≤2 days left renders in `scheme.warn`, the same threshold TAKES +
    // BIN, ROOMS, and DELETED KITS's own binned rows all use.
    val dayColor = if (row.daysLeft(System.currentTimeMillis()) <= 2) scheme.warn.tape else scheme.amber.tape
    val nameColor = CLASS_BY_NAME[row.name]?.let { Schemes.classColor(it).tape } ?: scheme.ink.tape
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
            TapeText(row.displayName, TapeType.marker, nameColor, maxLines = 1)
            TapeText(
                "${humanSize(row.sizeBytes)}  ·  ${relativeTime(row.binnedAtMillis)}",
                TapeType.pixelSmall,
                scheme.ink2.tape,
            )
        }
        TapeText("${row.daysLeft(System.currentTimeMillis())}D LEFT", TapeType.lcdSmall, dayColor)
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

/** Duplicated from `SnipsScreen.kt`'s own private `HeaderChip` (and `DeletedKitsScreen.kt`'s/`TakesBinScreen.kt`'s) rather than exported — every screen-local button in this codebase stays private to its own file, per that file's own stated convention. */
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

// Duplicated, not hoisted — deliberately constant across every scheme so a
// delete/empty action reads as "red" even in a scheme with no red anywhere
// else in it. The glow half moved to Schemes.BIN_RED_GLOW / theme.BinRedGlow
// (accessibility audit finding 5) — a single tuned token, not a duplicated
// literal.
private val BIN_RED_BORDER = Color(0xFF6A2020)
