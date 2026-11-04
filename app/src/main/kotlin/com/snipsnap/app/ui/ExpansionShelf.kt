package com.snipsnap.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.snipsnap.app.AndroidKitArt
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.BinRedGlow
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.KitIds
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Expansion
import com.snipsnap.shell.Layout

/**
 * The shelf's kits by id, read from each kit's own kit.json rather than the
 * list in hand: that list was read before a kit's first join gave it an id.
 * Runs off the main thread.
 */
internal fun shelfIds(kits: List<KitShelf.Entry>): Map<String, KitShelf.Entry> =
    kits.mapNotNull { k -> KitIds.read(k.dir)?.let { it to k } }.toMap()

/** An expansion's members that are on the shelf right now, in the order they joined; a binned kit is simply not on the shelf. */
internal fun presentMembers(e: Expansion, byId: Map<String, KitShelf.Entry>): List<KitShelf.Entry> =
    e.memberKitIds.mapNotNull { byId[it] }

/** The seed on the shelf: the seed kit when present, else the first present member. */
internal fun seedMember(e: Expansion, byId: Map<String, KitShelf.Entry>): KitShelf.Entry? =
    e.seedKitId?.takeIf { it in e.memberKitIds }?.let { byId[it] } ?: presentMembers(e, byId).firstOrNull()

/**
 * One expansion on the shelf: its tile (the seed kit drawn with its knobs),
 * its title and how many of its kits are on the shelf now. Tapping opens it
 * in EXPORT; holding reveals RENAME and DELETE, as a kit row does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExpansionRow(
    e: Expansion,
    byId: Map<String, KitShelf.Entry>,
    armed: Boolean,
    busy: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onOpen: (Expansion) -> Unit,
    onRequestRename: (Expansion) -> Unit,
    onRequestDelete: (Expansion) -> Unit,
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    val present = presentMembers(e, byId)
    val seed = seedMember(e, byId)
    val tile by produceState<ImageBitmap?>(null, seed?.dir, e.knobs, e.title) {
        value = seed?.let { s ->
            offMain {
                runCatching { AndroidKitArt.bitmap(s.kit, s.dir, e.knobs, TILE_PX, e.title, context.assets).asImageBitmap() }.getOrNull()
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (armed) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
            .combinedClickable(
                enabled = !busy || armed,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClickLabel = "RENAME OR DELETE",
                onLongClick = onArm,
                onClick = { if (armed) onDisarm() else onOpen(e) },
            )
            .semantics(mergeDescendants = true) { if (armed) stateDescription = "RENAME AND DELETE REVEALED" }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(44.dp).lcdPanel(scheme), contentAlignment = Alignment.Center) {
            tile?.let { Image(it, contentDescription = null, modifier = Modifier.size(44.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(e.title, TapeType.markerBig, scheme.ink.tape)
            TapeText(
                if (present.isEmpty()) Copy.EXPANSION_EMPTY else Copy.countOf(present.size, "KIT", "KITS"),
                TapeType.pixelSmall,
                scheme.ink2.tape,
                maxLines = 2,
            )
        }
        if (armed) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Door("RENAME", "RENAME ${e.title.uppercase()}", busy, scheme.amber.tape) { onRequestRename(e) }
                Door("DELETE", "DELETE ${e.title.uppercase()}", busy, BinRedGlow) { onRequestDelete(e) }
            }
        }
    }
}

@Composable
private fun Door(text: String, label: String, busy: Boolean, color: Color, onTap: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .tapeClick(label = label, enabled = !busy, onClick = onTap)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(text, TapeType.pixel, if (busy) scheme.ink3.tape else color)
    }
}

/** DELETE's confirm on an expansion: the expansion goes, its kits stay. The shelf's kit confirm, its own words. */
@Composable
internal fun ExpansionDeleteConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).tapeClick(label = "CANCEL", onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp).raisedBevel(scheme).pointerInput(Unit) { detectTapGestures { } }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText(Copy.EXPANSION_DELETE_CONFIRM, TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton("DELETE", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}

/** The shelf row's tile, drawn small: big enough for its shape, cheap enough for a list. */
private const val TILE_PX = 128
