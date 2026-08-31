package com.snipsnap.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.KitShelf
import com.snipsnap.app.PadPlayer
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.VoiceAllocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PLAY: performance mode over `:shell`'s tested [VoiceAllocator] — real
 * choke groups and one-shots, not just "tap a pad, hear a sample" like
 * KIT. In-window is bank A's 4×4, physically laid out the same way
 * KitScreen's own grid is (this file mirrors that pad composable rather
 * than reusing KitScreen's private `PadCell` — it doesn't extract cleanly
 * without exporting it, and the brief's pad states — a resting "assigned"
 * glow, a scale-down on hit — aren't what KIT's own cell draws). ⟳ opens a
 * fullscreen landscape 8×2-per-bank view of both banks at once, sharing
 * this same [VoiceAllocator] and [PadPlayer] instance — see the KDoc on
 * the fullscreen toggle below for why that's an in-app [Popup], not a
 * second Activity.
 */
private val WINDOW_GRID_ROWS = listOf(13..16, 9..12, 5..8, 1..4)
private val BANK_A_ROWS = listOf(9..16, 1..8)
private val BANK_B_ROWS = listOf(25..32, 17..24)

/** Touch-Y velocity: top of the pad is softest, bottom is full velocity. */
private const val MIN_VELOCITY = 0.35f

private fun velocityFromY(y: Float, height: Float): Float {
    val t = (y / height).coerceIn(0f, 1f)
    return MIN_VELOCITY + (1f - MIN_VELOCITY) * t
}

/**
 * SoundPool has no "voice finished" callback, so there is no exact moment
 * to call [VoiceAllocator.voiceEnded] — reaping happens on a conservative
 * timer instead of "the next noteOn": PLAY's whole point is chokes and
 * mute-group re-triggers, and reaping lazily (only when *something else*
 * is triggered) would leave a pad's own voice-count contribution stuck
 * until another pad happens to be hit, which is wrong far more often than
 * a fixed timer is.
 *
 * Fix round 2 (H1): the reap delay is now per-voice — [PadPlayer.durationMs]
 * reads each pad's real WAV duration (frame count / sample rate, from the
 * header, no decode) at load time, and [reap] schedules for that duration
 * plus [REAP_TAIL_MS] instead of a flat window. [REAP_DELAY_MS_FALLBACK]
 * only applies when a pad's duration is unknown (unreadable header, or a
 * sample that failed to load at all).
 *
 * Fix round 1 (F3, Important) history: concretely, a reaped voice is gone
 * from `VoiceAllocator`'s internal `active` map — `noteOn`'s `muteGroup`
 * filter (`active.values.filter { it.muteGroup == muteGroup }`) can no
 * longer find it, so it cannot be included in the next hit's
 * `Allocation.choked` list. The sample itself is unaffected (this never
 * calls `stopStream`) and keeps sounding; only its *chokeability* is what
 * silently expires early. With H1's duration-aware timer this only
 * remains a blind spot for pads whose duration couldn't be read — real,
 * still worth listening for during on-device testing with such content.
 */
private const val REAP_DELAY_MS_FALLBACK = 6000L

/** Grace period added on top of a known sample duration before reaping — covers scheduling jitter and SoundPool's own playback latency. */
private const val REAP_TAIL_MS = 250L

/** Bank-aware pad tag ("A01".."A16", "B01".."B16") — the format the export/CLI side already uses for slot 17+. */
private fun padTag(slot: Int): String = "%c%02d".format('A' + (slot - 1) / 16, (slot - 1) % 16 + 1)

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun PlayScreen(entry: KitShelf.Entry?) {
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
    LaunchedEffect(entry.kit) { player.load(entry) }

    val kit = entry.kit
    // Fix round 1 (F2, Important): VoiceAllocator's own default (32) outran
    // PadPlayer's real SoundPool budget (16) — past 16 concurrent streams
    // SoundPool silently reclaims its own oldest one, independent of what
    // this allocator still thinks is active, so the status line lied and a
    // later stopStream could target an id SoundPool had already reused.
    // Constructed at PadPlayer.MAX_STREAMS (the real ceiling) instead — the
    // status line below reads the same constant, so the two can't drift.
    val allocator = remember(entry.dir) { VoiceAllocator(maxVoices = PadPlayer.MAX_STREAMS) }
    // voiceId -> SoundPool streamId, so a choked/stolen/released voice can
    // actually be stopped — the whole reason PadPlayer grew a return value.
    val streamIds = remember(entry.dir) { mutableStateMapOf<Int, Int>() }
    var voiceCount by remember(entry.dir) { mutableIntStateOf(0) }
    val glow = remember(kit) {
        kit.pads.associate { it.slot to Animatable(0f) }
    }
    val scope = rememberCoroutineScope()

    fun flash(slot: Int) {
        val anim = glow[slot] ?: return
        scope.launch {
            anim.snapTo(1f)
            anim.animateTo(0f, tween(Motion.PAD_GLOW_MS))
        }
    }

    fun extinguish(slot: Int) {
        val anim = glow[slot] ?: return
        scope.launch { anim.snapTo(0f) }
    }

    fun reap(voice: VoiceAllocator.Voice) {
        val delayMs = player.durationMs(voice.padSlot)?.plus(REAP_TAIL_MS) ?: REAP_DELAY_MS_FALLBACK
        scope.launch {
            delay(delayMs)
            streamIds.remove(voice.id)
            allocator.voiceEnded(voice.id)
            voiceCount = allocator.activeCount
        }
    }

    fun hit(slot: Int, velocity: Float) {
        val pad = kit.pad(slot) ?: return
        val allocation = allocator.noteOn(slot, velocity, pad.muteGroup, pad.oneShot)
        val streamId = player.play(slot, velocity)
        streamIds[allocation.started.id] = streamId

        for (voice in allocation.choked + allocation.stolen) {
            streamIds.remove(voice.id)?.let(player::stopStream)
            // A pad in its own mute group re-triggering itself shows up
            // here with padSlot == slot — `flash` below already restarts
            // that same Animatable at 1f, so extinguishing it first would
            // just be two competing mutations on one Animatable racing to
            // decide the pad's opening glow value.
            if (voice.padSlot != slot) extinguish(voice.padSlot)
        }
        voiceCount = allocator.activeCount
        flash(slot)
        if (pad.oneShot) reap(allocation.started)
    }

    fun release(slot: Int) {
        val stopped = allocator.noteOff(slot)
        if (stopped.isEmpty()) return
        for (voice in stopped) {
            streamIds.remove(voice.id)?.let(player::stopStream)
        }
        voiceCount = allocator.activeCount
        extinguish(slot)
    }

    fun panic() {
        val stopped = allocator.allOff()
        for (voice in stopped) {
            streamIds.remove(voice.id)?.let(player::stopStream)
        }
        voiceCount = allocator.activeCount
        for (slot in glow.keys) extinguish(slot)
    }

    // Lessons: ON_STOP means allOff() + stop every stream — a backgrounded
    // phone should not keep a choke group ringing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, entry.dir) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) panic()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var fullscreen by remember { mutableStateOf(false) }
    val exitFullscreen = { fullscreen = false }

    // Fullscreen route: an in-app Popup + a runtime orientation flip, not a
    // second Activity. LoopActivity's separate-activity pattern exists
    // because LOOP owns a wholly disk-backed session and its own
    // audio-thread engine that has no reason to live inside App()'s
    // composition; PLAY's whole state — this VoiceAllocator, this
    // PadPlayer, every in-flight choke — already lives in App()'s
    // composition, and "playback/state shared across the rotation" is the
    // brief's explicit requirement. A second Activity would mean either a
    // process-wide singleton to smuggle that live state across an Activity
    // boundary, or rebuilding it (a fresh SoundPool losing every loaded
    // sample, a fresh allocator losing every active choke) — both worse
    // than staying in one composition. MainActivity already declares
    // `configChanges="orientation|screenSize|keyboardHidden"` in the
    // manifest, so flipping `requestedOrientation` at runtime reflows the
    // window instead of recreating the Activity — nothing here needs
    // `rememberSaveable` or process-death handling on top of what already
    // exists.
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // Fix round 1 (F1, CRITICAL): the prior version gated the *restore* on
    // `if (fullscreen)` inside onDispose, but onDispose for this key runs
    // precisely because `fullscreen` just flipped to false and the read
    // inside it is live — so the restore never fired on any exit path, and
    // the app stuck landscape app-wide (MainActivity is only portrait-
    // locked by these very requestedOrientation writes) until process
    // death. Now: an effect that unconditionally sets the orientation to
    // match the current `fullscreen` value on every change, plus a
    // teardown-only DisposableEffect(Unit) that unconditionally restores
    // portrait — covering leaving PLAY entirely while still fullscreen.
    LaunchedEffect(fullscreen, activity) {
        activity?.requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

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
            TapeText(
                "VOICES $voiceCount/${PadPlayer.MAX_STREAMS}",
                TapeType.lcdReadout,
                scheme.amber.tape,
                Modifier.padding(horizontal = 8.dp),
            )
            Box(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .tapeClick(::panic)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("PANIC", TapeType.pixel, scheme.ink2.tape)
            }
            Box(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .tapeClick { fullscreen = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("⟳ FULL", TapeType.pixel, scheme.ink2.tape)
            }
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
            for (row in WINDOW_GRID_ROWS) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
                ) {
                    for (slot in row) {
                        PlayPad(
                            slot = slot,
                            pad = kit.pad(slot),
                            glow = glow[slot],
                            onHit = ::hit,
                            onRelease = ::release,
                            modifier = Modifier.weight(1f).height(Layout.PAD_H.dp),
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
            TapeText("TAP = PLAY · PADS IN A GROUP CHOKE EACH OTHER", TapeType.pixelSmall, scheme.ink3.tape)
        }
    }

    if (fullscreen) {
        Popup(
            onDismissRequest = exitFullscreen,
            properties = PopupProperties(focusable = true),
        ) {
            FullscreenPlayGrid(
                kit = kit,
                glow = glow,
                voiceCount = voiceCount,
                onHit = ::hit,
                onRelease = ::release,
                onExit = exitFullscreen,
            )
        }
    }
}

@Composable
private fun FullscreenPlayGrid(
    kit: Kit,
    glow: Map<Int, Animatable<Float, AnimationVector1D>>,
    voiceCount: Int,
    onHit: (Int, Float) -> Unit,
    onRelease: (Int) -> Unit,
    onExit: () -> Unit,
) {
    val scheme = LocalScheme.current

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.win.tape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Layout.STATUS_BAR_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TapeText(kit.name, TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.weight(1f, fill = false))
            TapeText(
                "VOICES $voiceCount/${PadPlayer.MAX_STREAMS}",
                TapeType.lcdSmall,
                scheme.amber.tape,
                Modifier.padding(horizontal = 8.dp),
            )
            Box(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .tapeClick(onExit)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("EXIT FULL", TapeType.pixel, scheme.ink2.tape)
            }
        }

        BankRow(kit, glow, onHit, onRelease, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * Both banks, side by side. `Layout.MIN_HIT_TARGET` is non-negotiable
 * (Lessons), so this measures the available width first: wide enough for
 * both banks at the floor width, share it with `weight` so pads grow to
 * fill a roomy landscape screen; too narrow (a compact phone's landscape
 * width, minus insets, can land under 8 pads' worth of 44dp), fall back to
 * fixed floor-width banks inside a horizontal scroll — `weight` and
 * `horizontalScroll` can't be combined in the same axis, hence the split.
 */
@Composable
private fun BankRow(
    kit: Kit,
    glow: Map<Int, Animatable<Float, AnimationVector1D>>,
    onHit: (Int, Float) -> Unit,
    onRelease: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bankFloor = (Layout.MIN_HIT_TARGET * 8 + Layout.PAD_GAP * 7).dp
    BoxWithConstraints(modifier) {
        if (maxWidth >= bankFloor * 2 + Layout.PAD_GAP.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                PlayBank(kit, BANK_A_ROWS, glow, onHit, onRelease, Modifier.weight(1f).fillMaxHeight())
                PlayBank(kit, BANK_B_ROWS, glow, onHit, onRelease, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Row(
                Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
            ) {
                PlayBank(kit, BANK_A_ROWS, glow, onHit, onRelease, Modifier.width(bankFloor).fillMaxHeight())
                PlayBank(kit, BANK_B_ROWS, glow, onHit, onRelease, Modifier.width(bankFloor).fillMaxHeight())
            }
        }
    }
}

/** One bank's 8×2 block — [rows] is top-row-first, matching KIT's own bottom-up numbering. */
@Composable
private fun PlayBank(
    kit: Kit,
    rows: List<IntRange>,
    glow: Map<Int, Animatable<Float, AnimationVector1D>>,
    onHit: (Int, Float) -> Unit,
    onRelease: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
    ) {
        for (row in rows) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
            ) {
                for (slot in row) {
                    PlayPad(
                        slot = slot,
                        pad = kit.pad(slot),
                        glow = glow[slot],
                        onHit = onHit,
                        onRelease = onRelease,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
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

/**
 * PLAY's own pad cell — mirrors KitScreen's `PadCell` (same tag/name
 * layout, same class-colour source) but implements the handoff's actual
 * two pad states instead of KIT's single hit-flash: *assigned* (a resting
 * outer wash) and *lit* (a stronger inset wash, scaled down 3%). Compose
 * has no free-standing blurred box-shadow to match the handoff's literal
 * "14px"/"26px" glow radii; both states are flat alpha fills over the pad
 * itself, same approximation KitScreen's own cell already uses for its
 * hit flash. No long-press here — PLAY is for playing, not the pad
 * inspector — and release fires `onRelease` so gate (non-one-shot) pads
 * actually stop.
 */
@Composable
private fun PlayPad(
    slot: Int,
    pad: KitPad?,
    glow: Animatable<Float, AnimationVector1D>?,
    onHit: (Int, Float) -> Unit,
    onRelease: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    val shape = RoundedCornerShape(Layout.PAD_RADIUS.dp)
    val tag = padTag(slot)

    if (pad == null || glow == null) {
        Box(
            modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .raisedBevel(scheme, Layout.PAD_RADIUS.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape.copy(alpha = 0.6f), Modifier.padding(4.dp))
        }
        return
    }

    val cls = pad.colorHex?.removePrefix("#")?.toIntOrNull(16) ?: Schemes.classColor(pad.drumClass)
    val g = glow.value

    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .scale(1f - 0.03f * g)
            .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
            // assigned: resting outer wash (~class@60%, flattened — see KDoc above)
            .background(cls.tape.copy(alpha = 0.12f), shape)
            // lit: inset wash at class@40%, driven by the trigger/choke glow
            .background(cls.tape.copy(alpha = 0.40f * g), shape)
            .border(2.dp, cls.tape, shape)
            .pointerInput(slot) {
                while (true) {
                    val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    onHit(slot, velocityFromY(down.position.y, height))
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                        }
                    }
                    onRelease(slot)
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
