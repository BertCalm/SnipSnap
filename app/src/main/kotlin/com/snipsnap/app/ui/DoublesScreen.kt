package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Crate
import com.snipsnap.shell.Doubles
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DOUBLES: pads across the shelf's kits inside a "same sound" distance,
 * the number on every row — read-only, X-RAY's posture turned on the
 * user's own library. Reached from the shelf's DOUBLES ▸ row, a
 * shelf-level overlay in the same shape as X-RAY and DELETED KITS.
 *
 * The screen measures once ([Crate.index] over the shelf root — cached
 * by path, mtime and size, so an unchanged shelf costs nothing), then
 * groups at the ring the user picks ([Doubles.WITHIN_STEPS]) entirely in
 * memory. Every cluster shows its own WITHIN, so a wider ring reads as
 * exactly what it is. Each member row's GO ▸ opens that kit on that pad's
 * sheet. Nothing here deletes, moves or merges — those doors already
 * exist elsewhere, and the rule line says so.
 */
@Composable
fun DoublesScreen(
    shelf: KitShelf,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    /** GO ▸: open this shelf entry with this slot's PAD SHEET up. */
    onGoTo: (KitShelf.Entry, Int) -> Unit,
) {
    val scheme = LocalScheme.current
    BackHandler { onBack() }

    // Measured once, on IO: the crate index, the pairwise pass at the
    // widest ring (the one all-pairs scan), and which crate kit folder is
    // which shelf entry — crate entries name kits by folder relative to
    // the root, the shelf's own listing is what GO ▸ needs, and joining
    // them means canonical paths, which is filesystem work that can throw.
    // None of it runs again while the screen is open; the ring steps
    // regroup in memory.
    var measured by remember(shelf.root) { mutableStateOf<Doubles.Measured?>(null) }
    var onShelf by remember(shelf.root) { mutableStateOf<Map<String, KitShelf.Entry>>(emptyMap()) }
    var failed by remember(shelf.root) { mutableStateOf<String?>(null) }
    var within by remember(shelf.root) { mutableStateOf(Doubles.DEFAULT_WITHIN) }

    LaunchedEffect(shelf.root) {
        failed = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val idx = Crate.index(shelf.root)
                val pairs = Doubles.measure(idx)
                val byCanonical = shelf.list(KitShelf.ShelfSort.RECENT).associateBy { it.dir.canonicalPath }
                val byKitDir = idx.entries.map { it.kitDir }.distinct().mapNotNull { kitDir ->
                    runCatching { byCanonical[File(shelf.root, kitDir).canonicalPath] }.getOrNull()?.let { kitDir to it }
                }.toMap()
                pairs to byKitDir
            }
        }
        result.onSuccess { (pairs, byKitDir) ->
            measured = pairs
            onShelf = byKitDir
            onToast(Copy.doublesMeasured(pairs.index.extracted, pairs.index.fromCache))
        }.onFailure { e ->
            failed = "COULDN'T MEASURE THE SHELF: ${(e.message ?: e.javaClass.simpleName).uppercase()}"
        }
    }

    val idx = measured?.index
    val pairs = measured
    val clusters = remember(pairs, within) { pairs?.let { Doubles.clusters(it, within) } ?: emptyList() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChip("◄ SHELF", scheme, Modifier.width(72.dp), onClick = onBack)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                TapeText("DOUBLES", TapeType.lcdHeader, scheme.lcdInk.tape)
                TapeText(
                    idx?.let { "${it.entries.size} PADS MEASURED" } ?: Copy.DOUBLES_BUSY,
                    TapeType.pixelSmall,
                    scheme.ink3.tape,
                    maxLines = 1,
                )
            }
            TapeText("${clusters.size} ${if (clusters.size == 1) "GROUP" else "GROUPS"}", TapeType.pixelSmall, scheme.amber.tape, maxLines = 1)
        }

        TapeText(Copy.DOUBLES_RULE, TapeType.pixelSmall, scheme.ink3.tape, Modifier.fillMaxWidth().padding(horizontal = 4.dp), maxLines = 2)

        // The ring, as a dial you read: three steps, the current one lit,
        // regrouped in memory - the measuring never runs again.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (step in Doubles.WITHIN_STEPS) {
                ActionButton(
                    "WITHIN ${Doubles.fmt(step)}",
                    scheme,
                    enabled = idx != null,
                    lit = step == within,
                    modifier = Modifier.weight(1f),
                    onClick = { within = step },
                )
            }
        }

        val reason = failed
        when {
            reason != null -> Box(Modifier.fillMaxWidth().weight(1f).lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
                TapeText(reason, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 4)
            }
            idx == null -> Box(Modifier.fillMaxWidth().weight(1f).lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
                TapeText(Copy.DOUBLES_BUSY, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 1)
            }
            clusters.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
                TapeText(Copy.noDoubles(within), TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
            }
            else -> LazyColumn(
                Modifier.fillMaxWidth().weight(1f).sunkenField(scheme).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                clusters.forEachIndexed { ci, cluster ->
                    item(key = "group-$ci") { ClusterHeader(cluster, scheme) }
                    // Keyed by the entry's own file path - unique per pad
                    // across the whole index, and stable across ring steps.
                    for (e in cluster.entries) {
                        item(key = "pad-${e.file}") {
                            val target = onShelf[e.kitDir]
                            MemberRow(
                                e,
                                onShelf = target != null,
                                scheme = scheme,
                                onGo = {
                                    if (target != null) onGoTo(target, e.slot) else onToast(Copy.DOUBLES_KIT_NOT_ON_SHELF)
                                },
                            )
                        }
                    }
                }
            }
        }

        TapeText(Copy.DOUBLES_TRIM_CAVEAT, TapeType.pixelSmall, scheme.ink3.tape, Modifier.fillMaxWidth().padding(horizontal = 4.dp), maxLines = 2)
    }
}

@Composable
private fun ClusterHeader(cluster: Doubles.Cluster, scheme: Scheme) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp).lcdPanel(scheme).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TapeText(Doubles.headline(cluster), TapeType.pixelSmall, scheme.amber.tape, Modifier.weight(1f), maxLines = 2)
    }
}

/** One pad of a cluster: where it lives, and GO ▸ - dimmed, never dead, when the shelf's list doesn't hold its kit. */
@Composable
private fun MemberRow(e: Crate.Entry, onShelf: Boolean, scheme: Scheme, onGo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(3.dp))
            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TapeText(Doubles.memberLine(e), TapeType.pixelSmall, scheme.ink.tape, Modifier.weight(1f), maxLines = 1)
        ActionButton(
            "GO ▸",
            scheme,
            enabled = true,
            dimmed = !onShelf,
            accessibilityLabel = "OPEN ${e.kitName.uppercase()} ON ${e.label}",
            modifier = Modifier.width(64.dp),
            onClick = onGo,
        )
    }
}

/** Duplicated from `XRayScreen.kt`'s / `DeletedKitsScreen.kt`'s own private `HeaderChip` rather than exported — every screen-local button in this codebase stays private to its own file, per the house convention those files state. */
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
