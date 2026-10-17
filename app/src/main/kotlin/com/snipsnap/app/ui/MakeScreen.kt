package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Bench
import com.snipsnap.shell.KeysLayout
import com.snipsnap.shell.Layout

/*
 * MAKE (`docs/SCREEN_PLAN_2026_10.md` §3.6): sound from nothing. A list
 * page of four rows that open the labs as benches, and the INSTRUMENTS
 * bench the shelf's instrument rows moved to.
 */

/**
 * The MAKE list: SYNTH ▸, SNAP ▸, SURFACE ▸, INSTRUMENTS ▸, each a row
 * at least a hit target tall that opens its bench. The plan's subtitle
 * per row ("last: RESIN · KICK, to A09") is not drawn: the labs say what
 * they made in a toast and nowhere structured, so a subtitle would be a
 * parse of a sentence — the labs want a "last made" record first.
 */
@Composable
fun MakeList(onOpen: (Bench) -> Unit) {
    val scheme = LocalScheme.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (bench in listOf(Bench.SYNTH, Bench.SNAP, Bench.SURFACE, Bench.INSTRUMENTS)) {
            ActionButton("${bench.label} ▸", scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = { onOpen(bench) })
        }
    }
}

/**
 * MAKE ▸ INSTRUMENTS: what MAKE INSTRUMENT and MAKE PAD left in the
 * shelf's `Instruments/`, each row opening KEYS. The rows were the
 * shelf's; only where the list is shown moved (plan §3.6) — the files
 * are still written beside the kits.
 */
@Composable
fun InstrumentsBench(
    instruments: List<KitShelf.InstrumentEntry>,
    onOpen: (KitShelf.InstrumentEntry) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = LocalScheme.current
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(72.dp)
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .tapeClick(label = Bench.INSTRUMENTS.backChip, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(Bench.INSTRUMENTS.backChip, TapeType.pixel, scheme.lcdInk.tape)
            }
            Spacer(Modifier.weight(1f))
            TapeText(Bench.INSTRUMENTS.label, TapeType.lcdHeader, scheme.lcdInk.tape)
            Spacer(Modifier.weight(1f))
            TapeText("${instruments.size}", TapeType.lcdSmall, scheme.amber.tape)
        }
        if (instruments.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .lcdPanel(scheme)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(NO_INSTRUMENTS, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
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
                items(instruments, key = { "instrument:" + it.sidecar.name }) { entry ->
                    InstrumentRow(entry, onOpen)
                }
            }
        }
    }
}

/** The empty list's one line: where an instrument comes from. */
private const val NO_INSTRUMENTS = "NO INSTRUMENTS YET. SYNTH'S MAKE INSTRUMENT, OR A PAD SHEET'S, PUTS ONE HERE."

/** One instrument: its name, zones and root, and KEYS ▸. Moved from the shelf unchanged. */
@Composable
private fun InstrumentRow(entry: KitShelf.InstrumentEntry, onOpen: (KitShelf.InstrumentEntry) -> Unit) {
    val scheme = LocalScheme.current
    val i = entry.instrument
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .tapeClick(label = "OPEN ${i.name.uppercase()}") { onOpen(entry) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(i.name, TapeType.markerBig, scheme.ink.tape)
            val looped = if (i.zones.any { it.loopStartFrame > 0 }) "  ·  HOLDS" else ""
            TapeText("${i.zones.size} ${if (i.zones.size == 1) "ZONE" else "ZONES"}  ·  ROOT ${KeysLayout.label(i.rootNote)}$looped", TapeType.pixelSmall, scheme.ink2.tape)
        }
        TapeText("KEYS ▸", TapeType.pixelSmall, scheme.ink2.tape)
    }
}
