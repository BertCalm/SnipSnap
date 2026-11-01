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
import com.snipsnap.shell.TileSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What a seed pad reads as on a chip and on the ART row: the slot, or AUTO for the longest pad. */
internal fun seedLabel(slot: Int?): String = slot?.let { String.format(java.util.Locale.ROOT, "A%02d", it) } ?: Copy.ART_SEED_AUTO

/** The ART row's reading of [knobs]: style · palette · seed pad · mark. */
internal fun artSummary(knobs: ArtKnobs): String =
    Copy.artSummary(knobs.style.name, knobs.palette.name, seedLabel(knobs.seedSlot), knobs.mark.id.uppercase())

/**
 * The picker's previews: one tile per knob set, drawn off the main thread
 * by [render] and kept for the sheet's life, so flipping back to a palette
 * already seen is instant. At most two draw at once, so eight cells
 * opening together do not hold eight kits' worth of pixels at the same
 * moment. A render that throws is an empty cell, never the screen.
 */
internal class ArtPreviews(private val render: (ArtKnobs) -> ImageBitmap) {
    private val cache = ConcurrentHashMap<ArtKnobs, ImageBitmap>()

    /** The preview already drawn for [knobs], without drawing it. */
    fun cached(knobs: ArtKnobs): ImageBitmap? = cache[knobs]

    /** The preview for [knobs], drawn now if it is not cached; null when the drawing failed. */
    suspend fun bitmap(knobs: ArtKnobs): ImageBitmap? =
        cache[knobs] ?: offMain(DRAWING) { runCatching { render(knobs) }.getOrNull() }?.also { cache[knobs] = it }

    companion object {
        /** Big enough to judge a style on a phone, small enough that eight draw in a breath. */
        const val PREVIEW_SIZE = 256

        @OptIn(ExperimentalCoroutinesApi::class)
        private val DRAWING = Dispatchers.IO.limitedParallelism(2)

        /** Previews of [kit] through the phone's own renderer, its pads read once for every preview. */
        fun forKit(assets: AssetManager, kit: Kit, kitDir: File): ArtPreviews {
            val pads by lazy { TileSound.pads(kit, kitDir) }
            return ArtPreviews { knobs -> AndroidKitArt.bitmap(kit, pads, knobs, PREVIEW_SIZE, assets = assets).asImageBitmap() }
        }
    }
}

/** What a preview cell holds: drawing, drawn, or a drawing that failed. */
private sealed class Preview {
    data object Drawing : Preview()
    data class Drawn(val image: ImageBitmap) : Preview()
    data object Failed : Preview()
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
    val previews = remember(kit, kitDir) { ArtPreviews.forKit(context.assets, kit, kitDir) }
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
    // Seeded from the cache, so a preview already drawn shows at once instead of DRAWING… for a frame.
    val preview by produceState<Preview>(previews.cached(knobs)?.let { Preview.Drawn(it) } ?: Preview.Drawing, knobs) {
        value = previews.bitmap(knobs)?.let { Preview.Drawn(it) } ?: Preview.Failed
    }
    Column(
        modifier
            .tapeClick(label = "STYLE: ${knobs.style.name}", onClick = onPick)
            .border(if (picked) 2.dp else 1.dp, if (picked) scheme.amber.tape else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).lcdPanel(scheme), contentAlignment = Alignment.Center) {
            when (val p = preview) {
                Preview.Drawing -> TapeText(Copy.ART_BUSY, TapeType.pixelSmall, scheme.lcdInk.tape)
                is Preview.Drawn -> Image(p.image, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                // Nothing to show: the cell stays an empty panel and the style can still be picked.
                Preview.Failed -> Unit
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
