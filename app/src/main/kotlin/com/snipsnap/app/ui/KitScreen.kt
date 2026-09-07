package com.snipsnap.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.PadPlayer
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KeyPicker
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.MutateSheet
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.TextureKits
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The KIT screen: bank A as the 4×4 grid, physically laid out — A13–A16
 * across the top, A01 bottom-left, exactly the MPC's own geometry (and
 * the geometry `Copy.KONAMI_PADS` assumes).
 */
private val GRID_ROWS = listOf(13..16, 9..12, 5..8, 1..4)

@Composable
fun KitScreen(
    entry: KitShelf.Entry?,
    busy: Boolean,
    onLongPress: (Int) -> Unit,
    onTakesBin: () -> Unit,
    onTexture: (slot: Int, spec: TextureKits.Spec) -> Unit,
    onSetKey: (com.snipsnap.audio.KeySpec?) -> Unit,
    onInKey: () -> Unit,
    onTwins: () -> Unit,
) {
    val scheme = LocalScheme.current

    if (entry == null) {
        Box(
            Modifier
                .fillMaxSize()
                .lcdPanel(scheme)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("NO TAPE IN THE DECK. OPEN ONE ON THE SHELF.", TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
        }
        return
    }

    val player = remember(entry.dir) { PadPlayer() }
    DisposableEffect(entry.dir) { onDispose { player.release() } }
    // PAD SHEET can rewrite a pad's level/pan/audio while this screen isn't
    // showing it; the SoundPool cache doesn't know that on its own, so a
    // fresh `kit` reference (its identity changes on every real edit —
    // see PadSheetScreen's `onKitUpdated`) reloads it. Keyed separately
    // from the dir-scoped effect above so this also covers the very first
    // composition, without a redundant load from that one.
    LaunchedEffect(entry.kit) { player.load(entry) }

    val kit = entry.kit
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Layout.LCD_HEADER_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TapeText(kit.name, TapeType.lcdHeader, scheme.lcdInk.tape, Modifier.weight(1f, fill = false))
            val tempo = kit.tempoBpm?.let { "%.0f BPM  ".format(it) } ?: ""
            val keyed = kit.key?.let { "${KeyPicker.label(it)}  " } ?: ""
            val banks = if (kit.pads.any { it.slot > 16 }) "A+B  " else ""
            TapeText("$keyed$tempo$banks${kit.pads.size} PADS", TapeType.lcdSmall, scheme.amber.tape)
        }

        if (kit.pads.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .lcdPanel(scheme)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(Copy.EMPTY_KIT, TapeType.lcdSmall, scheme.lcdInk.tape)
            }
        }

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
        ) {
            for (row in GRID_ROWS) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
                ) {
                    for (slot in row) {
                        PadCell(
                            slot = slot,
                            pad = kit.pad(slot),
                            onTap = player::play,
                            onLongPress = onLongPress,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // ---- TEXTURE: SCULPT / STRETCH, a pad becoming a tape of its own ----
        // panelKind is a texture kind, KEY_PANEL for the key picker, or null.
        var panelKind by remember(entry.dir) { mutableStateOf<String?>(null) }
        val sources = kit.pads.map { it.slot }.sorted()
        var sourceSlot by remember(entry.dir, sources.firstOrNull()) { mutableStateOf(sources.firstOrNull()) }
        var mode by remember(panelKind) { mutableStateOf(panelKind?.takeIf { it != KEY_PANEL }?.let { TextureKits.modesFor(it).first() } ?: "") }
        val knob = panelKind?.takeIf { it != KEY_PANEL }?.let { TextureKits.knobFor(it, mode) }
        var fraction by remember(panelKind, mode) { mutableFloatStateOf(knob?.defaultFraction ?: 0f) }

        // X2.3 KIT action rows: the artboard (`isKit` in `TapeOS Oilslick.dc.html`)
        // packs EVIL TWINS (W4.3) and the KEY cycler (F5.3) in here alongside
        // TAKES + BIN, and both are real now. Two rows so every word fits:
        // TAKES + BIN · EVIL TWINS · KEY, then the two texture doors; the
        // panel below them scrolls, so a short screen never pushes the grid.
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("TAKES + BIN ▸", scheme, enabled = !busy, modifier = Modifier.weight(1f), onClick = onTakesBin)
                // EVIL TWINS: bank B lit with seeded re-treatments of bank A; a second press rerolls.
                val twinned = kit.pads.any { it.slot > 16 }
                ActionButton(
                    if (twinned) "EVIL TWINS ▸ REROLL" else "EVIL TWINS ▸",
                    scheme,
                    enabled = !busy && kit.pads.any { it.slot in 1..16 },
                    modifier = Modifier.weight(1f),
                    onClick = onTwins,
                )
                // KEY: the kit's key, IN KEY, and the tonal pads' tune readout.
                val keyOpen = panelKind == KEY_PANEL
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme, fill = if (keyOpen) scheme.amber.tape.copy(alpha = 0.85f) else null)
                        .let { if (!busy) it.tapeClick { panelKind = if (keyOpen) null else KEY_PANEL } else it }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("KEY ▸", TapeType.pixel, if (keyOpen) scheme.titleInk.tape else scheme.ink2.tape)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (kind in TextureKits.KINDS) {
                    val open = panelKind == kind
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme, fill = if (open) scheme.amber.tape.copy(alpha = 0.85f) else null)
                            .let { if (!busy && kit.pads.isNotEmpty()) it.tapeClick { panelKind = if (open) null else kind } else it }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText("$kind ▸", TapeType.pixel, if (open) scheme.titleInk.tape else scheme.ink2.tape)
                    }
                }
            }

            if (panelKind == KEY_PANEL) {
                KeyPanel(
                    key = kit.key,
                    readouts = KeyPicker.readouts(kit),
                    busy = busy,
                    onSetKey = onSetKey,
                    onInKey = onInKey,
                )
            }

            val kind = panelKind
            val src = sourceSlot
            if (kind != null && knob != null && src != null) {
                TexturePanel(
                    kind = kind,
                    modes = TextureKits.modesFor(kind),
                    mode = mode,
                    onMode = { mode = it },
                    sourceLabel = "${MutateSheet.padTag(src)} · ${kit.pad(src)?.displayName ?: ""}",
                    onSourceStep = { step ->
                        val i = sources.indexOf(src)
                        if (i >= 0 && sources.size > 1) sourceSlot = sources[(i + step).mod(sources.size)]
                    },
                    knobLabel = knob.label,
                    knobFraction = fraction,
                    knobText = TextureKits.knobLabel(knob, knob.value(fraction)),
                    onKnob = { f -> fraction = (f * 40f).let { Math.round(it) / 40f } },
                    busy = busy,
                    onGo = {
                        val seed = Random.nextLong(0L, 1_000_000L)
                        onTexture(src, TextureKits.spec(kind, mode, fraction, seed))
                    },
                )
            }
        }
    }
}

/** The action row's fourth door, beside the texture kinds. */
private const val KEY_PANEL = "KEY"

/**
 * The KEY panel (F5.3): twelve root chips, five scale chips, OFF, IN KEY,
 * and the tune readout of every tonal pad. Tapping a root or a scale sets
 * the key at once (metadata only); IN KEY moves the tonal pads' tune
 * fields into it; a tonal pad assigned while the key is set lands in key
 * on its own. The kick is never touched.
 */
@Composable
private fun KeyPanel(
    key: com.snipsnap.audio.KeySpec?,
    readouts: List<String>,
    busy: Boolean,
    onSetKey: (com.snipsnap.audio.KeySpec?) -> Unit,
    onInKey: () -> Unit,
) {
    val scheme = LocalScheme.current
    val root = key?.rootSemitone ?: KeyPicker.DEFAULT_ROOT
    val scaleLabel = key?.let { KeyPicker.scaleLabel(it.scale) } ?: KeyPicker.DEFAULT_SCALE
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("KEY · ${KeyPicker.label(key)}", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)

        for (row in KeyPicker.ROOTS.indices.chunked(6)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in row) {
                    val selected = key != null && r == root
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme, fill = if (selected) scheme.amber.tape.copy(alpha = 0.85f) else null)
                            .let { if (!busy) it.tapeClick { onSetKey(KeyPicker.key(r, scaleLabel)) } else it }
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(KeyPicker.ROOTS[r], TapeType.pixel, if (selected) scheme.titleInk.tape else scheme.ink2.tape)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in KeyPicker.SCALES) {
                val selected = key != null && s == scaleLabel
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme, fill = if (selected) scheme.amber.tape.copy(alpha = 0.85f) else null)
                        .let { if (!busy) it.tapeClick { onSetKey(KeyPicker.key(root, s)) } else it }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(s, TapeType.pixelSmall, if (selected) scheme.titleInk.tape else scheme.ink2.tape, maxLines = 2)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("OFF", scheme, enabled = !busy && key != null, modifier = Modifier.weight(1f), onClick = { onSetKey(null) })
            ActionButton("IN KEY ▸ RETUNE TONAL PADS", scheme, enabled = !busy && key != null, modifier = Modifier.weight(2f), onClick = onInKey)
        }

        if (readouts.isEmpty()) {
            TapeText("NO TONAL PADS. DRUMS LAND AS CAPTURED.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 1)
        } else {
            for (line in readouts) TapeText(line, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 1)
        }
    }
}

/**
 * The TEXTURE panel: one pad of this kit → a texture kit of its own on
 * the shelf. SOURCE steps through the assigned pads, the mode chips are
 * the door's own (CLOUD · SCRUB · SWARM, or SLOW · FREEZE), the knob is
 * the mode's one (LENGTH / BY / HOLD), and GO renders four seeded takes
 * through `TextureKits` — the CLI's `sculpt` / `stretch`, on a thumb.
 * A new seed every GO, so a second press is a second tape, not a copy.
 */
@Composable
private fun TexturePanel(
    kind: String,
    modes: List<String>,
    mode: String,
    onMode: (String) -> Unit,
    sourceLabel: String,
    onSourceStep: (Int) -> Unit,
    knobLabel: String,
    knobFraction: Float,
    knobText: String,
    onKnob: (Float) -> Unit,
    busy: Boolean,
    onGo: () -> Unit,
) {
    val scheme = LocalScheme.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("TEXTURE · $kind · FOUR TAKES, ONE NEW TAPE", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("◄", scheme, enabled = !busy, onClick = { onSourceStep(-1) })
            TapeText("SOURCE  $sourceLabel", TapeType.pixel, scheme.ink.tape, Modifier.weight(1f), maxLines = 1)
            ActionButton("►", scheme, enabled = !busy, onClick = { onSourceStep(1) })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in modes) {
                val selected = m == mode
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme, fill = if (selected) scheme.amber.tape.copy(alpha = 0.85f) else null)
                        .let { if (!busy) it.tapeClick { onMode(m) } else it }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(m, TapeType.pixel, if (selected) scheme.titleInk.tape else scheme.ink2.tape)
                }
            }
        }

        StepperSlider(
            label = knobLabel,
            fraction = knobFraction,
            valueText = knobText,
            fillColor = scheme.amber.tape,
            scheme = scheme,
            enabled = !busy,
            onFractionChange = onKnob,
            onFractionCommit = {},
        )

        ActionButton(
            if (busy) "DUBBING…" else "$kind ▸ NEW TAPE",
            scheme,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onGo,
        )
    }
}

/** HANDOFF: tint for text on dark = the class colour mixed 55% to white. */
private fun classTint(rgb: Int): Color {
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    fun up(c: Int) = c + ((255 - c) * 0.55f).toInt()
    return Color(0xFF shl 24 or (up(r) shl 16) or (up(g) shl 8) or up(b))
}

/** A long press that opens PAD SHEET, timed from the design's own 480ms. */
private const val LONG_PRESS_MS = 480L

@Composable
private fun PadCell(
    slot: Int,
    pad: KitPad?,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    val shape = RoundedCornerShape(Layout.PAD_RADIUS.dp)
    val tag = "A%02d".format(slot)

    if (pad == null) {
        Box(
            modifier
                .height(Layout.PAD_H.dp)
                .raisedBevel(scheme, Layout.PAD_RADIUS.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape.copy(alpha = 0.6f), Modifier.padding(4.dp))
        }
        return
    }

    val cls = pad.colorHex?.removePrefix("#")?.toIntOrNull(16)
        ?: Schemes.classColor(pad.drumClass)
    val glow = remember(slot) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .height(Layout.PAD_H.dp)
            .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
            .border(2.dp, cls.tape, shape)
            .background(cls.tape.copy(alpha = 0.35f * glow.value), shape)
            // The press fires the hit immediately — a pad that waited for
            // release to sound would already be wrong as a drum pad. The
            // 480ms hold on top of that (still down) opens PAD SHEET; a
            // quick tap never reaches it. `PointerInputScope` isn't itself
            // a `CoroutineScope` (only `Density`), so the delay timer rides
            // the same `rememberCoroutineScope()` the glow animation uses.
            .pointerInput(slot) {
                while (true) {
                    val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                    onTap(slot)
                    scope.launch {
                        glow.snapTo(1f)
                        glow.animateTo(0f, tween(Motion.PAD_GLOW_MS))
                    }
                    val longPressJob = scope.launch {
                        delay(LONG_PRESS_MS)
                        onLongPress(slot)
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                        }
                    }
                    longPressJob.cancel()
                }
            }
            .padding(5.dp),
    ) {
        TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape.copy(alpha = 0.7f), Modifier.align(Alignment.TopEnd))
        TapeText(
            pad.displayName,
            TapeType.marker,
            classTint(cls),
            Modifier.align(Alignment.BottomStart),
            maxLines = 2,
        )
    }
}
