package com.snipsnap.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.AudioFocus
import com.snipsnap.app.AudioVoice
import com.snipsnap.app.KitShelf
import com.snipsnap.app.PadEngine
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KeyPicker
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.MutateSheet
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadPeaks
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.TextureKits
import com.snipsnap.shell.VoiceAllocator
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The KIT screen: bank A as the 4×4 grid, physically laid out — A13–A16
 * across the top, A01 bottom-left, exactly the MPC's own geometry (and
 * the geometry `Copy.KONAMI_PADS` assumes).
 */
private val GRID_ROWS = listOf(13..16, 9..12, 5..8, 1..4)

/**
 * [GRID_ROWS] moved onto one bank: bank 0 is the grid as written, bank 1
 * the same sixteen positions starting at slot 17.
 *
 * Until the September UAT's finding 11 this screen only ever drew bank A,
 * which meant EVIL TWINS' sixteen pads on slots 17..32 were playable on
 * PLAY and exported correctly but could not be inspected, treated, tuned,
 * renamed or cleared - ever. PAD SHEET opens from this grid and nowhere
 * else, so a pad this grid could not draw was a pad with no door.
 */
private fun gridRows(bank: Int): List<IntRange> {
    val base = PadBanks.slots(bank).first - 1
    return GRID_ROWS.map { (it.first + base)..(it.last + base) }
}

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
    /**
     * BREED (XX2 wired in): arms the pick-a-partner hand-off (`App.kt`'s
     * `pendingBreedWith`) and sends the user to the shelf to tap kit B —
     * `:shell`'s tested `Breed.breed` crosses this kit's own recipes with
     * theirs into a new child kit. [canBreed] (a second kit actually on the
     * shelf to cross with) gates whether the button even fires.
     */
    onBreed: () -> Unit = {},
    /** Whether the shelf holds a second kit BREED could cross this one with. */
    canBreed: Boolean = false,
    /** SHARE (F6.3): this kit packed as one `.xpn` and handed to the chooser. */
    onShare: () -> Unit,
    /** SPLIT: a pad taken apart into sines, transient and air, on three faders. */
    onSplit: () -> Unit,
    onEmptyLongPress: (Int) -> Unit = {},
    onEmptyTapHint: (Int) -> Unit = {},
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

    // EEE4: the grid plays on the native engine now, same PadEngine PLAY
    // and KEYS use — same bank, same door onto the sound, one fewer player
    // to keep synced with a live edit. A tap has no release, so every hit
    // is forced one-shot in the allocator (see `hit` below) regardless of
    // the pad's own gate metadata, as SoundPool's preview always played
    // out fully too; the allocator still exists so a mute group still
    // chokes on this screen, and so a stolen or choked voice is stopped
    // rather than left ringing.
    val engineContext = LocalContext.current
    val player = remember(entry.dir) { PadEngine(deviceSampleRate(engineContext)) }
    var engineUp by remember(entry.dir) { mutableStateOf(false) }
    DisposableEffect(entry.dir) {
        engineUp = player.start()
        onDispose { player.close() }
    }
    // PAD SHEET can rewrite a pad's level/pan/audio while this screen isn't
    // showing it; the engine's bank doesn't know that on its own, so a
    // fresh `kit` reference (its identity changes on every real edit —
    // see PadSheetScreen's `onKitUpdated`) reloads it. Keyed separately
    // from the dir-scoped effect above so this also covers the very first
    // composition, without a redundant load from that one.
    LaunchedEffect(entry.kit) { withContext(Dispatchers.IO) { player.load(entry) } }
    val allocator = remember(entry.dir) { VoiceAllocator(maxVoices = PadEngine.MAX_VOICES) }
    // The endings ring, drained at screen rate — same idiom as PLAY's own
    // frame loop, so the allocator's bookkeeping (who's oldest, who's
    // still sounding) never drifts from what the engine actually did.
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            for (id in player.drainEnded()) allocator.voiceEnded(id)
            if (player.needsRestart()) engineUp = player.start()
        }
    }
    // W12: every pad's mini-waveform, read off the main thread once per
    // kit edit (the same `kit` identity the engine reload keys on) and
    // kept as columns only. Cleared first, so neither a fresh kit nor an
    // edited one ever shows a shape that isn't its own: names first, the
    // shapes a blink later.
    var padPeaks by remember(entry.dir) { mutableStateOf<Map<Int, List<PeaksPyramid.Column>>>(emptyMap()) }
    LaunchedEffect(entry.kit) {
        padPeaks = emptyMap()
        padPeaks = withContext(Dispatchers.IO) { PadPeaks.forKit(entry.kit, entry.dir) }
    }

    val kit = entry.kit

    fun hit(slot: Int) {
        val pad = kit.pad(slot) ?: return
        if (!engineUp || !player.isUp()) return
        // oneShot forced true, not read off the pad: this screen has no
        // release gesture to call noteOff with, so a gate pad's own
        // metadata would otherwise sit unused - forcing it here says so,
        // rather than leaving a future release gesture to discover it.
        val allocation = allocator.noteOn(slot, 1f, pad.muteGroup, oneShot = true)
        for (voice in allocation.choked + allocation.stolen) player.stop(voice.id)
        if (!player.hit(pad, 1f, allocation.started.id)) allocator.voiceEnded(allocation.started.id)
    }

    fun panic() {
        allocator.allOff()
        player.allOff()
    }
    // Lessons from PLAY: ON_STOP means allOff() - a backgrounded phone
    // should not keep a choke group ringing, or leave the allocator
    // thinking voices are still active while the frame loop isn't ticking
    // to drain their endings. A focus loss asks for the same silence, so
    // KIT's shared AudioFocus registration rides this same effect - see
    // PlayScreen's own copy of this pattern for the full reasoning.
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioVoice = remember(entry.dir) { object : AudioVoice { override fun silence() = panic() } }
    DisposableEffect(lifecycleOwner, entry.dir) {
        AudioFocus.acquire(audioVoice)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    panic()
                    AudioFocus.release(audioVoice)
                }
                Lifecycle.Event.ON_START -> AudioFocus.acquire(audioVoice)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            AudioFocus.release(audioVoice)
        }
    }

    /** Which bank the grid is drawing, 0-based. Bank A until asked otherwise. */
    var bank by remember(entry.dir) { mutableIntStateOf(0) }
    val bankCount = PadBanks.banksUsed(kit.pads.map { it.slot })
    // A kit can lose its upper bank while this screen is open - clearing the
    // twins, or an UNDO. Fall back rather than draw sixteen empty pads the
    // user cannot fill from here.
    LaunchedEffect(bankCount) {
        if (bank >= bankCount) bank = 0
    }
    val showing = bank.coerceAtMost(bankCount - 1)

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
            val banks = if (bankCount > 1) "${PadBanks.letter(showing)}  " else ""
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

        // The door onto bank B (September UAT, finding 11). Only drawn when
        // there is a second bank to reach: nothing here fills one, so an
        // always-present B would be a switch to sixteen pads the user has no
        // way to put anything on. EVIL TWINS is what makes bank B exist.
        if (bankCount > 1) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (b in 0 until bankCount) {
                    val here = b == showing
                    val filled = kit.pads.count { it.slot in PadBanks.slots(b) }
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .let { if (here) it.raisedBevel(scheme) else it.sunkenField(scheme) }
                            .tapeClick(label = null, onClick = { bank = b }),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(
                            "BANK ${PadBanks.letter(b)} · $filled",
                            TapeType.pixel,
                            if (here) scheme.titleInk.tape else scheme.ink2.tape,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
        ) {
            for (row in gridRows(showing)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
                ) {
                    for (slot in row) {
                        PadCell(
                            slot = slot,
                            pad = kit.pad(slot),
                            peaks = padPeaks[slot],
                            onTap = ::hit,
                            onLongPress = onLongPress,
                            onEmptyLongPress = onEmptyLongPress,
                            onEmptyTapHint = onEmptyTapHint,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // PAD SHEET's visible door (September UAT, findings 4 and 5). Twenty
        // treatments plus shape, tune, mutate, layers, takes and GRAIN FIELD
        // sat behind one 480 ms hold with nothing on screen naming it, taught
        // only by a toast that stopped after three showings — so a user who
        // dismissed it three times lost that half of the app for good.
        //
        // The same move ROOMS already makes under its own list: one line,
        // always there. A legend cannot be dismissed, so the gesture cannot
        // be forgotten. The hold stays exactly as it was — this explains it,
        // it does not replace it.
        TapeText(Copy.PAD_SHEET_LEGEND, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)

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
                // "VERSIONS + BIN", not "TAKES + BIN" (name-and-find
                // followups) — matches TakesBinScreen.kt's own renamed
                // header; the destination screen and its Kotlin symbol are
                // unchanged, only this entry-point label.
                ActionButton("VERSIONS + BIN ▸ ROLL BACK OR RESTORE", scheme, enabled = !busy, modifier = Modifier.weight(1f), onClick = onTakesBin)
                // EVIL TWINS: bank B lit with seeded re-treatments of bank A; a second press rerolls.
                val twinned = kit.pads.any { it.slot > 16 }
                ActionButton(
                    if (twinned) "REMIX BANK B ▸ REROLL" else "REMIX BANK B ▸",
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
                        .let { if (!busy) it.tapeClick(label = null) { panelKind = if (keyOpen) null else KEY_PANEL } else it }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("KEY ▸", TapeType.pixel, if (keyOpen) scheme.titleInk.tape else scheme.ink2.tape)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // SPLIT is a screen rather than a panel: three faders, three
                // REVERSE buttons and a print want more room than a drawer
                // under the grid, and the loop wants to keep playing while
                // you work it.
                ActionButton(
                    "SPLIT ▸ SINES · TRANSIENT · AIR",
                    scheme,
                    enabled = !busy && kit.pads.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSplit,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // BREED: this kit's own recipes crossed with a second kit's
                // into a new child, kept beside this one — the shelf-level
                // "pick a kit" hand-off (App.kt's pendingBreedWith) runs
                // next, the same shape SNIPS → PAD already uses to pick a
                // kit for a snip.
                ActionButton(
                    "BREED ▸ CROSS TWO KITS",
                    scheme,
                    enabled = !busy && kit.pads.isNotEmpty() && canBreed,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBreed,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (kind in TextureKits.KINDS) {
                    val open = panelKind == kind
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme, fill = if (open) scheme.amber.tape.copy(alpha = 0.85f) else null)
                            .let { if (!busy && kit.pads.isNotEmpty()) it.tapeClick(label = null) { panelKind = if (open) null else kind } else it }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val doorSubtitle = when (kind) {
                            "SCULPT" -> "GRANULAR TEXTURES"
                            "STRETCH" -> "SLOW & FREEZE"
                            else -> ""
                        }
                        TapeText("$kind ▸ $doorSubtitle", TapeType.pixel, if (open) scheme.titleInk.tape else scheme.ink2.tape)
                    }
                }
            }

            // SHARE: one file out the share sheet - a messenger, Drive, a
            // cable - the kit's own .xpn, which the receiving phone's
            // SnipSnap (or an MPC) reads back.
            ActionButton(
                "SHARE ▸ THIS KIT AS ONE FILE",
                scheme,
                enabled = !busy && kit.pads.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = onShare,
            )

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
                            .let { if (!busy) it.tapeClick(label = null) { onSetKey(KeyPicker.key(r, scaleLabel)) } else it }
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
                        .let { if (!busy) it.tapeClick(label = null) { onSetKey(KeyPicker.key(root, s)) } else it }
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
                        .let { if (!busy) it.tapeClick(label = null) { onMode(m) } else it }
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
    peaks: List<PeaksPyramid.Column>?,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onEmptyLongPress: (Int) -> Unit,
    onEmptyTapHint: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    val shape = RoundedCornerShape(Layout.PAD_RADIUS.dp)
    // The pad's own name. This said "A%02d".format(slot), which was true
    // only while this grid could not show slot 17 - see the bank switch
    // above (September UAT, finding 11).
    val tag = PadBanks.tag(slot)
    val scope = rememberCoroutineScope()

    if (pad == null) {
        Box(
            modifier
                .height(Layout.PAD_H.dp)
                .raisedBevel(scheme, Layout.PAD_RADIUS.dp)
                // The raw pointerInput below fires onTap on release
                // (a hint, not an action) and starts a hand-rolled
                // long-press timer on down — neither registers a
                // Compose click/long-click action, so this cell was
                // reachable by TalkBack focus but silently inert
                // (accessibility audit finding 1, "worse than
                // silence"). A synthesized double-tap can't reproduce
                // the down/hold timing, so it maps onto the two
                // outcomes that timing already produces: a plain click
                // gives the same discoverability hint an early release
                // would, and the long-click action is what actually
                // opens PAD CAPTURE.
                // mergeDescendants: without it, the tag TapeText below
                // stays a second, separately-focusable node — TalkBack
                // would land on this cell twice.
                .semantics(mergeDescendants = true) {
                    contentDescription = "PAD $tag: EMPTY"
                    onClick(label = "HINT") { onEmptyTapHint(slot); true }
                    onLongClick(label = "CAPTURE A SAMPLE") { onEmptyLongPress(slot); true }
                }
                // Same long-press timing as the filled path below, but a
                // blank slot has nothing to preview-play on down — only
                // the long-press (capture) and the released-early tap
                // (discoverability hint) do anything.
                .pointerInput(slot) {
                    while (true) {
                        val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                        val longPress = scope.launch {
                            delay(LONG_PRESS_MS)
                            onEmptyLongPress(slot)
                        }
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                            }
                        }
                        if (longPress.isActive) {
                            longPress.cancel()
                            onEmptyTapHint(slot)
                        }
                    }
                },
            contentAlignment = Alignment.TopEnd,
        ) {
            // Full-opacity ink2, not a dimmed copy — the alpha reduction
            // (was 0.6f) put this, the only text naming which of 16 slots
            // an empty pad is, at 2.09-2.99:1 in every scheme (audit
            // finding 5). ink2 itself now clears 4.5:1 against gray in all
            // 8 schemes at full opacity — see Schemes.kt and ContrastTest.
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape, Modifier.padding(4.dp))
        }
        return
    }

    val cls = pad.colorHex?.removePrefix("#")?.toIntOrNull(16)
        ?: Schemes.classColor(pad.drumClass)
    val glow = remember(slot) { Animatable(0f) }

    Box(
        modifier
            .height(Layout.PAD_H.dp)
            .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
            .border(2.dp, cls.tape, shape)
            .background(cls.tape.copy(alpha = 0.35f * glow.value), shape)
            // TalkBack could already focus this cell and read its name
            // (the pointerInput below registers no click action), but a
            // double-tap did nothing — a false affordance, arguably
            // worse than being skipped entirely (audit finding 1). A
            // synthesized click maps onto onTap (the same hit a quick
            // physical tap produces) and long-click onto onLongPress
            // (opens PAD SHEET), matching the two outcomes the raw
            // gesture below actually distinguishes.
            // mergeDescendants: without it, the tag and name TapeTexts
            // below stay separate nodes — TalkBack would land on this
            // one cell three times instead of once.
            .semantics(mergeDescendants = true) {
                contentDescription = "PAD $tag: ${pad.displayName}"
                onClick(label = "PLAY") { onTap(slot); true }
                onLongClick(label = "OPEN PAD SHEET") { onLongPress(slot); true }
            }
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
        // W12: the pad's own shape, in its class colour under the name —
        // a kick reads as a kick before it's hit. Drawn first so the tag
        // and name sit over it.
        if (peaks != null && peaks.isNotEmpty()) {
            PadWaveform(peaks, cls.tape.copy(alpha = 0.30f), Modifier.matchParentSize())
        }
        // Full-opacity ink2 on its own solid backing chip, not a dimmed
        // copy drawn straight over the cell (audit finding 5, 2.30-3.59:1
        // measured). The chip matters here specifically: PadWaveform above
        // draws class-colour peaks at 30% alpha across the *whole* cell,
        // so without an opaque backdrop the tag's actual background is
        // data-dependent (whatever the waveform happens to render at this
        // corner) and can still fall to ~2.4:1 for a bright class colour
        // like HAT_OPEN even with ink2 fixed — see ContrastTest.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .background(Schemes.darken(scheme.gray, 0.30f).tape, RoundedCornerShape(3.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp),
        ) {
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape)
        }
        TapeText(
            pad.displayName,
            TapeType.marker,
            classTint(cls),
            Modifier.align(Alignment.BottomStart),
            maxLines = 2,
        )
    }
}

/**
 * The mini-waveform behind a pad's name (W12): [PadPeaks.COLUMNS] bars
 * of real min/max magnitude across the cell, each bar centred in its
 * own step and the whole band centred on the middle 60% of the height so
 * the tag above and the name below stay clear. Sized in fractions of the
 * cell, not dp, so the shape scales with the grid on any width.
 */
@Composable
private fun PadWaveform(peaks: List<PeaksPyramid.Column>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = peaks.size
        if (n == 0 || size.width <= 0f || size.height <= 0f) return@Canvas
        val step = size.width / n
        val barWidth = (step * 0.6f).coerceIn(1f, step)
        val inset = (step - barWidth) / 2f
        val mid = size.height * 0.5f
        val amp = size.height * 0.30f
        for ((i, col) in peaks.withIndex()) {
            val top = mid - col.max.coerceIn(-1f, 1f) * amp
            val bottom = mid - col.min.coerceIn(-1f, 1f) * amp
            drawRect(
                color = color,
                topLeft = Offset(i * step + inset, top),
                size = Size(barWidth, (bottom - top).coerceAtLeast(1f)),
            )
        }
    }
}
