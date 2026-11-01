package com.snipsnap.app.ui

import android.content.res.AssetManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snipsnap.app.AndroidKitArt
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.Kit
import com.snipsnap.shell.ArtKnobs
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Mark
import com.snipsnap.shell.Palettes
import com.snipsnap.shell.Scheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What a seed pad reads as on a chip and on the ART row: the slot, or AUTO for the longest pad. */
internal fun seedLabel(slot: Int?): String = slot?.let { "A%02d".format(it) } ?: Copy.ART_SEED_AUTO

/** The ART row's reading of [knobs]: style · palette · seed pad · mark. */
internal fun artSummary(knobs: ArtKnobs): String =
    Copy.artSummary(knobs.style.name, knobs.palette.name, seedLabel(knobs.seedSlot), knobs.mark.id.uppercase())

/**
 * The picker's previews: one [PREVIEW_SIZE] tile per knob set, drawn off
 * the main thread through the phone's own renderer (the same code the
 * export writes with) and kept for the sheet's life, so flipping back to
 * a palette already seen is instant.
 */
internal class ArtPreviews(private val assets: AssetManager, private val kit: Kit, private val kitDir: File) {
    private val cache = ConcurrentHashMap<ArtKnobs, ImageBitmap>()

    suspend fun bitmap(knobs: ArtKnobs): ImageBitmap =
        cache[knobs] ?: offMain {
            AndroidKitArt.bitmap(kit, kitDir, knobs, PREVIEW_SIZE, assets = assets).asImageBitmap()
        }.also { cache[knobs] = it }

    companion object {
        /** Big enough to judge a style on a phone, small enough that eight draw in a breath. */
        const val PREVIEW_SIZE = 256
    }
}

/**
 * The art picker in its sheet: the eight styles drawn live for this kit
 * with the knobs at hand, then the palette, seed pad and mark. Every tap
 * hands [onKnobs] the changed set and the sheet stays open; the caller
 * saves it.
 */
@Composable
internal fun ArtPickerSheet(
    kit: Kit,
    kitDir: File,
    knobs: ArtKnobs,
    onKnobs: (ArtKnobs) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(Copy.ART_SHEET_TITLE, onDismiss) {
        ArtPickerContent(kit, kitDir, knobs, onKnobs, Modifier.weight(1f))
    }
}

@Composable
private fun ArtPickerContent(kit: Kit, kitDir: File, knobs: ArtKnobs, onKnobs: (ArtKnobs) -> Unit, modifier: Modifier) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    val previews = remember(kit, kitDir) { ArtPreviews(context.assets, kit, kitDir) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (pair in KitArt.Style.entries.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (style in pair) {
                    StyleCell(previews, knobs.copy(style = style), picked = style == knobs.style, scheme, Modifier.weight(1f)) {
                        onKnobs(knobs.copy(style = style))
                    }
                }
            }
        }
        ChipRow(Copy.ART_PALETTE, scheme) {
            for (p in Palettes.ALL) {
                Chip(p.name, "${Copy.ART_PALETTE}: ${p.name}", picked = p == knobs.palette, scheme) { onKnobs(knobs.copy(palette = p)) }
            }
        }
        ChipRow(Copy.ART_SEED_PAD, scheme) {
            Chip(Copy.ART_SEED_AUTO, "${Copy.ART_SEED_PAD}: ${Copy.ART_SEED_AUTO}", picked = knobs.seedSlot == null, scheme) {
                onKnobs(knobs.copy(seedSlot = null))
            }
            for (pad in kit.pads.sortedBy { it.slot }) {
                val label = seedLabel(pad.slot)
                Chip(label, "${Copy.ART_SEED_PAD}: $label", picked = knobs.seedSlot == pad.slot, scheme) { onKnobs(knobs.copy(seedSlot = pad.slot)) }
            }
        }
        ChipRow(Copy.ART_MARK, scheme) {
            for (v in Mark.Variant.entries) {
                val word = v.id.uppercase()
                Chip(word, "${Copy.ART_MARK}: $word", picked = v == knobs.mark, scheme) { onKnobs(knobs.copy(mark = v)) }
            }
        }
    }
}

/** A style's live preview with its name under it; the picked style is framed in the scheme's amber. */
@Composable
private fun StyleCell(previews: ArtPreviews, knobs: ArtKnobs, picked: Boolean, scheme: Scheme, modifier: Modifier, onPick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, knobs) { value = previews.bitmap(knobs) }
    Column(
        modifier
            .tapeClick(label = "STYLE: ${knobs.style.name}", onClick = onPick)
            .border(if (picked) 2.dp else 1.dp, if (picked) scheme.amber.tape else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).lcdPanel(scheme), contentAlignment = Alignment.Center) {
            val img = image
            if (img == null) {
                TapeText(Copy.ART_BUSY, TapeType.pixelSmall, scheme.lcdInk.tape)
            } else {
                Image(img, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            }
        }
        TapeText(
            if (picked) "▸ ${knobs.style.name}" else knobs.style.name,
            TapeType.pixel.copy(textAlign = TextAlign.Center),
            if (picked) scheme.amber.tape else scheme.ink.tape,
            Modifier.fillMaxWidth(),
        )
    }
}

/** A knob's heading over a row of chips that scrolls sideways when it outgrows the phone. */
@Composable
private fun ChipRow(heading: String, scheme: Scheme, chips: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText(heading, TapeType.pixelSmall, scheme.ink2.tape)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips()
        }
    }
}

@Composable
private fun Chip(text: String, label: String, picked: Boolean, scheme: Scheme, onPick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .border(if (picked) 2.dp else 0.dp, if (picked) scheme.amber.tape else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            .tapeClick(label = label, onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(text, TapeType.pixel, if (picked) scheme.amber.tape else scheme.ink.tape)
    }
}

/**
 * EXPORT's ART row: what the tile will be, as a door into the picker. Same
 * shape as FORMAT above it: a heading, a bevelled row, a caption.
 */
@Composable
internal fun ArtRow(knobs: ArtKnobs, enabled: Boolean, scheme: Scheme, onOpen: () -> Unit) {
    val summary = artSummary(knobs)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText(Copy.ART_ROW, TapeType.pixelSmall, scheme.ink2.tape)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .raisedBevel(scheme)
                .tapeClick(label = "${Copy.ART_ROW}: $summary", enabled = enabled, onClick = onOpen)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText(
                "$summary ▸",
                TapeType.pixel.copy(textAlign = TextAlign.Center),
                if (enabled) scheme.ink.tape else scheme.ink3.tape,
                Modifier.fillMaxWidth(),
                maxLines = 2,
            )
        }
        TapeText(Copy.ART_ROW_NOTE, TapeType.pixelSmall, if (enabled) scheme.ink2.tape else scheme.ink3.tape, Modifier.fillMaxWidth(), maxLines = 2)
    }
}
