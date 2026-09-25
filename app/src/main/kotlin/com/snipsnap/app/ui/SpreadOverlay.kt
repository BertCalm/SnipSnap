package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.Spread

/**
 * What SPREAD measured before its panel opened: the audio to spread, its
 * pitch ([Spread.detect], null when unclear) and the root the panel
 * opens on ([Spread.defaultRoot]).
 */
internal class SpreadSource(val snip: Snip, val midi: Float?, val root: Int)

/** Bank rows as the MPC draws them: the top row first, the bank's first pad bottom-left. */
private val BANK_ROWS = listOf(12..15, 8..11, 4..7, 0..3)

/**
 * SPREAD's panel, shared by SYNTH and the pad sheet: ROOT and OCT, the
 * scale, how many pads and which bank, MONO and REPLACE FULL, and a live
 * preview of the bank — every pad's note, the roots in their lighter
 * shade, and what each pad left alone is keeping. The preview is
 * [Spread.plan] over [kit]; the caller plans again over the kit on disk
 * when it writes.
 */
@Composable
internal fun SpreadOverlay(
    kit: Kit?,
    source: SpreadSource,
    /** The sound's pad colour, #rrggbb. */
    colorHex: String,
    scheme: Scheme,
    busy: Boolean,
    onSpread: (Spread.Options) -> Unit,
    onCancel: () -> Unit,
) {
    var root by remember { mutableIntStateOf(source.root) }
    var scaleIndex by remember { mutableIntStateOf(0) }
    var padCount by remember { mutableIntStateOf(Spread.MAX_PADS) }
    var bank by remember { mutableIntStateOf(0) }
    var mono by remember { mutableStateOf(false) }
    var replaceFull by remember { mutableStateOf(false) }

    val options = Spread.Options(
        rootMidi = root,
        scale = Spread.SCALES[scaleIndex],
        padCount = padCount,
        bank = bank,
        mono = mono,
        replaceFull = replaceFull,
    )
    val plan = remember(kit, source, options) { kit?.let { Spread.plan(it, source.midi, options) } }
    val noteColor = colorHex.removePrefix("#").toInt(16)
    val rootColor = Spread.rootTint(colorHex).removePrefix("#").toInt(16)

    // Same catch-all as SlotChooserOverlay's: a tap in a gap must not fall
    // through to the screen underneath.
    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).pointerInput(Unit) { detectTapGestures { } }.padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapeText("SPREAD — ONE SOUND, ONE SCALE", TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.weight(1f))
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                        .tapeClick(label = "CANCEL", enabled = !busy, onClick = onCancel)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(if (busy) "…" else "CANCEL", TapeType.pixel, scheme.amber.tape)
                }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (source.midi == null) {
                    TapeText(Copy.SPREAD_NO_PITCH, TapeType.pixelSmall, scheme.amber.tape, maxLines = 3)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton("OCT −", scheme, enabled = !busy && root - 12 >= Spread.ROOT_MIN, modifier = Modifier.weight(1f)) { root -= 12 }
                    ActionButton("−", scheme, enabled = !busy && root > Spread.ROOT_MIN, accessibilityLabel = "ROOT DOWN", modifier = Modifier.weight(1f)) { root-- }
                    TapeText("ROOT ${Scales.nameOf(root)}", TapeType.pixel, scheme.lcdInk.tape, Modifier.weight(1.4f))
                    ActionButton("+", scheme, enabled = !busy && root < Spread.ROOT_MAX, accessibilityLabel = "ROOT UP", modifier = Modifier.weight(1f)) { root++ }
                    ActionButton("OCT +", scheme, enabled = !busy && root + 12 <= Spread.ROOT_MAX, modifier = Modifier.weight(1f)) { root += 12 }
                }

                // Five labels don't fit one row at the pixel face; three over two do.
                for (row in listOf(0..2, 3..4)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in row) {
                            SegmentButton(Spread.scaleLabel(Spread.SCALES[i]), active = scaleIndex == i, enabled = !busy, modifier = Modifier.weight(1f)) {
                                scaleIndex = i
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton("−", scheme, enabled = !busy && padCount > Spread.MIN_PADS, accessibilityLabel = "FEWER PADS", modifier = Modifier.weight(1f)) { padCount-- }
                    TapeText("$padCount PADS", TapeType.pixel, scheme.lcdInk.tape, Modifier.weight(1.4f))
                    ActionButton("+", scheme, enabled = !busy && padCount < Spread.MAX_PADS, accessibilityLabel = "MORE PADS", modifier = Modifier.weight(1f)) { padCount++ }
                    for (b in 0 until Spread.BANKS) {
                        SegmentButton("BANK ${PadBanks.letter(b)}", active = bank == b, enabled = !busy, modifier = Modifier.weight(1.2f)) { bank = b }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SegmentButton("MONO", active = mono, enabled = !busy, modifier = Modifier.weight(1f)) { mono = !mono }
                    SegmentButton("REPLACE FULL", active = replaceFull, enabled = !busy, modifier = Modifier.weight(1f)) { replaceFull = !replaceFull }
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                    for (row in BANK_ROWS) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                            for (i in row) {
                                val slot = PadBanks.slots(bank).first + i
                                PreviewCell(
                                    slot = slot,
                                    label = previewLabel(slot, plan, kit),
                                    rimColor = plan?.noteAt(slot)?.let { if (plan.isRoot(it)) rootColor else noteColor },
                                    scheme = scheme,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            val count = plan?.notes?.size ?: 0
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Layout.PRIMARY_ACTION_H.dp)
                    .raisedBevel(scheme, fill = noteColor.tape.copy(alpha = 0.85f))
                    .tapeClick(label = "SPREAD", enabled = !busy && count > 0) { onSpread(options) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(
                    when {
                        busy -> "…"
                        count == 0 -> "NO PAD FREE"
                        else -> "SPREAD ▸ $count ${if (count == 1) "PAD" else "PADS"}"
                    },
                    TapeType.pixel,
                    scheme.titleInk.tape,
                )
            }
        }
    }
}

/** What a preview pad reads: its note, FULL (keeping what it holds), OUT (past the tune range), or blank past the spread. */
private fun previewLabel(slot: Int, plan: Spread.Plan?, kit: Kit?): String {
    plan?.noteAt(slot)?.let { return it.name }
    return when (plan?.skipped?.get(slot)) {
        Spread.Skip.FULL -> "FULL · ${kit?.pad(slot)?.displayName ?: ""}"
        Spread.Skip.OUT_OF_REACH -> "OUT"
        null -> ""
    }
}

@Composable
private fun PreviewCell(slot: Int, label: String, rimColor: Int?, scheme: Scheme, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Layout.PAD_RADIUS.dp)
    val landing = rimColor != null
    Box(
        modifier
            .height(Layout.PAD_H.dp)
            .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
            .border(2.dp, (rimColor ?: scheme.ink3).tape.copy(alpha = if (landing) 1f else 0.35f), shape)
            .padding(5.dp),
    ) {
        TapeText(PadBanks.tag(slot), TapeType.pixelSmall, scheme.ink2.tape.copy(alpha = 0.7f), Modifier.align(Alignment.TopEnd))
        TapeText(
            label,
            if (landing) TapeType.pixel else TapeType.pixelSmall,
            (rimColor ?: scheme.ink3).tape,
            Modifier.align(Alignment.BottomStart),
            maxLines = 2,
        )
    }
}
