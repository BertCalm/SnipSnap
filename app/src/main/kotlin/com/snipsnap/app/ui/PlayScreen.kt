package com.snipsnap.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.Kit
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.StreamFacts
import com.snipsnap.shell.VoiceAllocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// M4: the pads play on the native engine (PadEngine over Oboe). Every
// voice is keyed by the allocator's own id, and the engine reports each
// voice's end on a ring the screen drains every frame - so a one-shot
// leaves the allocator the moment its sample ends, a choke lands on the
// voice it was meant for, and nothing here guesses from a timer any more.

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * PLAY: performance mode over `:shell`'s tested [VoiceAllocator] — real
 * choke groups and one-shots, not just "tap a pad, hear a sample" like
 * KIT. In-window is bank A's 4×4, physically laid out the same way
 * KitScreen's own grid is (this file mirrors that pad composable rather
 * than reusing KitScreen's private `PadCell` — it doesn't extract cleanly
 * without exporting it, and the brief's pad states — a resting "assigned"
 * glow, a scale-down on hit — aren't what KIT's own cell draws). ⟳ opens a
 * fullscreen landscape 8×2-per-bank view of both banks at once, sharing
 * this same [VoiceAllocator] and [PadEngine] instance — see the KDoc on
 * the fullscreen toggle below for why that's an in-app [Popup], not a
 * second Activity. The pad grid itself (`PlayPad`/`PlayBank`/`BankRow` and
 * their row/velocity constants) lives in `PadGrid.kt`, shared with GROOVE.
 */
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
            TapeText(Copy.NO_TAPE_IN_DECK, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
        }
        return
    }

    val engineContext = LocalContext.current
    val player = remember(entry.dir) { PadEngine(deviceSampleRate(engineContext)) }
    var engineUp by remember(entry.dir) { mutableStateOf(false) }
    DisposableEffect(entry.dir) {
        engineUp = player.start()
        onDispose { player.close() }
    }
    // The bank: every WAV read off the main thread, then adopted whole by
    // the callback. Until it lands a hit resolves to nothing and plays
    // nothing - honest silence, never a stale kit's sample.
    LaunchedEffect(entry.kit) {
        withContext(Dispatchers.IO) { player.load(entry) }
    }

    val kit = entry.kit
    // Built at the engine's own polyphony so the status line and the
    // engine cannot drift: at the cap the engine steals exactly the voice
    // the allocator said it would.
    val allocator = remember(entry.dir) { VoiceAllocator(maxVoices = PadEngine.MAX_VOICES) }
    var voiceCount by remember(entry.dir) { mutableIntStateOf(0) }
    // The bench's one number: what the device says a round trip costs.
    // Polled about once a second - it reads a timestamp, so it is cheap
    // but not free, and it does not move fast enough to want a frame.
    var latency by remember(entry.dir) { mutableStateOf(StreamFacts.latency(null)) }
    // One status line for both headers - the fullscreen grid paints the
    // same words as the normal one, so a dead stream cannot read as a
    // quiet one just because you went fullscreen.
    val status = StreamFacts.playStatus(engineUp, voiceCount, PadEngine.MAX_VOICES, latency)
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

    // The endings ring, drained at screen rate; a route change reopens the
    // stream from here too.
    LaunchedEffect(player) {
        var lastLatencyAt = 0L
        while (true) {
            val now = withFrameNanos { it }
            val ended = player.drainEnded()
            if (ended.isNotEmpty()) {
                for (id in ended) allocator.voiceEnded(id)
                voiceCount = allocator.activeCount
            }
            if (player.needsRestart()) engineUp = player.start()
            if (now - lastLatencyAt >= StreamFacts.POLL_NANOS) {
                lastLatencyAt = now
                latency = StreamFacts.latency(player.latencyMillis(), player.isShared())
            }
        }
    }

    fun hit(slot: Int, velocity: Float) {
        val pad = kit.pad(slot) ?: return
        // No stream, no voice: with no callback running nothing would ever
        // report an ending, and VOICES would climb and stick. `isUp` reads
        // the native dead-stream flag, so the window between a route change
        // and the frame loop's restart is covered too.
        if (!engineUp || !player.isUp()) return
        val allocation = allocator.noteOn(slot, velocity, pad.muteGroup, pad.oneShot)
        for (voice in allocation.choked + allocation.stolen) {
            player.stop(voice.id)
            // A pad in its own mute group re-triggering itself shows up
            // here with padSlot == slot - `flash` below already restarts
            // that same Animatable at 1f, so extinguishing it first would
            // just be two competing mutations on one Animatable racing to
            // decide the pad's opening glow value.
            if (voice.padSlot != slot) extinguish(voice.padSlot)
        }
        if (!player.hit(pad, velocity, allocation.started.id)) {
            // Nothing loaded for this pad (or nothing yet): the allocator
            // must not count a voice that never sounded.
            allocator.voiceEnded(allocation.started.id)
        }
        voiceCount = allocator.activeCount
        flash(slot)
    }

    fun release(slot: Int) {
        val stopped = allocator.noteOff(slot)
        if (stopped.isEmpty()) return
        for (voice in stopped) player.stop(voice.id)
        voiceCount = allocator.activeCount
        extinguish(slot)
    }

    fun panic() {
        allocator.allOff()
        player.allOff()
        voiceCount = allocator.activeCount
        for (slot in glow.keys) extinguish(slot)
    }

    // Lessons: ON_STOP means allOff() + stop every voice — a backgrounded
    // phone should not keep a choke group ringing. A focus loss (a call,
    // another app's audio) asks for exactly the same silence, so PLAY's
    // shared AudioFocus registration rides this same effect: acquired the
    // moment PLAY can make sound, released the moment it stops being able
    // to — leaving the screen, or the phone itself taking the interruption.
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

    var fullscreen by remember { mutableStateOf(false) }
    val exitFullscreen = { fullscreen = false }

    // Fullscreen route: an in-app Popup + a runtime orientation flip, not a
    // second Activity. LoopActivity's separate-activity pattern exists
    // because LOOP owns a wholly disk-backed session and its own
    // audio-thread engine that has no reason to live inside App()'s
    // composition; PLAY's whole state — this VoiceAllocator, this
    // PadEngine, every in-flight choke — already lives in App()'s
    // composition, and "playback/state shared across the rotation" is the
    // brief's explicit requirement. A second Activity would mean either a
    // process-wide singleton to smuggle that live state across an Activity
    // boundary, or rebuilding it (a fresh engine losing every loaded
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
                status,
                TapeType.lcdReadout,
                scheme.amber.tape,
                Modifier.padding(horizontal = 8.dp),
            )
            Box(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .tapeClick(label = "PANIC", onClick = ::panic)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("PANIC", TapeType.pixel, scheme.ink2.tape)
            }
            Box(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .tapeClick(label = "FULLSCREEN") { fullscreen = true }
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
                            // PLAY has no clock to place a note against —
                            // the touch timestamp is GROOVE's concern, not
                            // this screen's, so it's simply dropped here.
                            onHit = { s, v, _ -> hit(s, v) },
                            onRelease = { s, _ -> release(s) },
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
                status = status,
                onHit = { s, v, _ -> hit(s, v) },
                onRelease = { s, _ -> release(s) },
                onExit = exitFullscreen,
            )
        }
    }
}

@Composable
private fun FullscreenPlayGrid(
    kit: Kit,
    glow: Map<Int, Animatable<Float, AnimationVector1D>>,
    status: String,
    onHit: (Int, Float, Long) -> Unit,
    onRelease: (Int, Long) -> Unit,
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
                status,
                TapeType.lcdSmall,
                scheme.amber.tape,
                Modifier.padding(horizontal = 8.dp),
            )
            Box(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .raisedBevel(scheme)
                    .tapeClick(label = "EXIT FULLSCREEN", onClick = onExit)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("EXIT FULL", TapeType.pixel, scheme.ink2.tape)
            }
        }

        BankRow(kit, glow, onHit, onRelease, Modifier.weight(1f).fillMaxWidth())
    }
}
