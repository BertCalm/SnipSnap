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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.MpcXRay
import com.snipsnap.shell.Layout
import com.snipsnap.shell.MutateSheet
import com.snipsnap.shell.Scheme

/**
 * X-RAY: "what's inside this file," read-only. Reached from the shelf's
 * own X-RAY ▸ INSPECT A FILE button, a shelf-level overlay — same shape
 * as SNIPS/DELETED KITS — never a MenuRow tab.
 *
 * Renders whatever [MpcXRay.read] came back with, however partial: a
 * refusal ([MpcXRay.Reading.unreadable]) reads plainly, an empty slot
 * reads EMPTY rather than being left off the list, and a field this
 * class doesn't have a name for is a count at the foot of the screen,
 * never a guess at what it might be. There is deliberately no action
 * row here — a level, a pan, a tune reads back exactly as this file's
 * own bytes say it is, and no control on this screen can change that.
 */
@Composable
fun XRayScreen(fileName: String, reading: MpcXRay.Reading, onBack: () -> Unit) {
    val scheme = LocalScheme.current
    BackHandler { onBack() }

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
                TapeText("X-RAY", TapeType.lcdHeader, scheme.lcdInk.tape)
                TapeText(fileName.uppercase(), TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            }
            TapeText(reading.kindLabel.uppercase(), TapeType.pixelSmall, scheme.amber.tape, maxLines = 2)
        }

        val unreadable = reading.unreadable
        when {
            unreadable != null -> Box(
                Modifier.fillMaxSize().weight(1f).lcdPanel(scheme).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(unreadable.uppercase(), TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 4)
            }
            reading.programs.isEmpty() -> Box(
                Modifier.fillMaxSize().weight(1f).lcdPanel(scheme).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                val topKeys = (reading.rawTree as? JsonValue.Obj)?.entries?.keys?.size
                TapeText(
                    if (topKeys != null) "JSON, NOT AN MPC PROGRAM. $topKeys TOP-LEVEL FIELDS." else "NOTHING TO SHOW.",
                    TapeType.lcdSmall,
                    scheme.lcdInk.tape,
                    maxLines = 3,
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxWidth().weight(1f).sunkenField(scheme).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                reading.programs.forEachIndexed { pi, program ->
                    item(key = "program-$pi") { ProgramHeader(program) }
                    // Keyed by list position, not `it.slot`: a real MPC file
                    // never repeats a slot number within one program, but a
                    // corrupt or hand-edited one could (a duplicate/missing
                    // Instrument `number` attribute), and a duplicate key
                    // would crash LazyColumn - exactly the "never throws on
                    // content" promise this whole screen exists to keep.
                    itemsIndexed(program.pads, key = { pj, _ -> "pad-$pi-$pj" }) { _, pad -> PadRow(pad) }
                }
            }
        }

        if (reading.unlabeledFieldCount > 0) {
            TapeText(
                "${reading.unlabeledFieldCount} FIELD${if (reading.unlabeledFieldCount == 1) "" else "S"} " +
                    "PRESENT IN THIS FILE, NOT YET LABELED HERE.",
                TapeType.pixelSmall,
                scheme.ink3.tape,
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ProgramHeader(program: MpcXRay.Program) {
    val scheme = LocalScheme.current
    val used = program.pads.count { it.hasSample }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp).lcdPanel(scheme).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TapeText(program.trackName.uppercase(), TapeType.lcd(17), scheme.amber.tape, Modifier.weight(1f), maxLines = 1)
        TapeText(
            (if (program.isKeygroup) "KEYGROUP" else "DRUM") + " · $used/${program.pads.size} LOADED",
            TapeType.pixelSmall,
            scheme.ink.tape,
        )
    }
}

@Composable
private fun PadRow(pad: MpcXRay.Pad) {
    val scheme = LocalScheme.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp).background(scheme.lcd.tape, RoundedCornerShape(3.dp))
            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapeText(MutateSheet.padTag(pad.slot), TapeType.pixel, scheme.amber.tape)
        if (pad.hasSample) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                val main = pad.layers.lastOrNull { it.sampleName != null }
                TapeText(
                    (main?.sampleName ?: "(unnamed)").uppercase() +
                        if (pad.layers.size > 1) "  · ${pad.layers.size} LAYERS" else "",
                    TapeType.pixelSmall,
                    scheme.ink.tape,
                    maxLines = 1,
                )
                TapeText(
                    "L ${fmt(pad.level)}  P ${fmt(pad.pan)}  TUNE ${fmtInt(pad.tuneCoarse)}/${fmtInt(pad.tuneFine)}  MUTE ${fmtInt(pad.muteGroup)}",
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                    maxLines = 1,
                )
            }
        } else {
            TapeText("EMPTY", TapeType.pixelSmall, scheme.ink3.tape, Modifier.weight(1f))
        }
    }
}

/** A field this class read as null wasn't there, or wasn't shaped as expected — an em dash, never a guessed zero. */
private fun fmt(v: Float?): String = v?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "—"
private fun fmtInt(v: Int?): String = v?.toString() ?: "—"

/** Duplicated, not hoisted — see `SnipsScreen.kt`'s own copy and its comment on the house convention for screen-local buttons. */
@Composable
private fun HeaderChip(label: String, scheme: Scheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(3.dp))
            .tapeClick(label = null, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
    }
}
