package com.snipsnap.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.AudioFocus
import com.snipsnap.app.AudioVoice
import com.snipsnap.app.KitShelf
import com.snipsnap.app.MidiInput
import com.snipsnap.app.PadEngine
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.amberOnChrome
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.MidiWire
import com.snipsnap.shell.Breed
import com.snipsnap.shell.Copy
import com.snipsnap.shell.DustPrints
import com.snipsnap.shell.KeyPicker
import com.snipsnap.shell.PadHit
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Look
import com.snipsnap.shell.MidiPads
import com.snipsnap.shell.Motion
import com.snipsnap.shell.MutateSheet
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadPeaks
import com.snipsnap.shell.PadSheetBoxes
import com.snipsnap.shell.PadStrip
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.Places
import com.snipsnap.shell.Provenance
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.StreamFacts
import com.snipsnap.shell.TextureKits
import com.snipsnap.shell.VoiceAllocator
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The KIT screen's grid: one bank as the 4×4, physically laid out — A13–A16
 * across the top, A01 bottom-left, exactly the MPC's own geometry. Bank 0
 * is the grid as written, bank 1 the same sixteen positions starting at
 * slot 17. The rows are PadGrid's [windowRows], the one definition every
 * portrait grid in the app draws from, so KIT cannot drift from PLAY,
 * GROOVE or CATCH.
 *
 * Until the September UAT's finding 11 this screen only ever drew bank A,
 * which meant EVIL TWINS' sixteen pads on slots 17..32 were playable on
 * PLAY and exported correctly but could not be inspected, treated, tuned,
 * renamed or cleared - ever. PAD SHEET opens from this grid and nowhere
 * else, so a pad this grid could not draw was a pad with no door.
 */
private fun gridRows(bank: Int): List<IntRange> = windowRows(bank)

@Composable
fun KitScreen(
    entry: KitShelf.Entry?,
    /**
     * The kit switcher's line, built by App through `Places.switcher`
     * (`KITCHEN ▾ · 2 KITS IN THIS PROJECT`, or `NO KITS YET`). It is not
     * drawn: the header's name chip says the kit, and this line is the
     * chip's accessible name (`Places.switchKitName`), where the project's
     * count lives. With [entry] null the whole page is one empty panel and
     * there is no chip (docs/SCREEN_PLAN_2026_10.md section 3.3).
     */
    switcherLine: String,
    /** The name chip: lists the project's kits, or - with nothing to switch between - opens the shelf. */
    onSwitchKit: () -> Unit,
    /** + FROM SHELF: SHELF in pick mode, returning here. */
    onAddFromShelf: () -> Unit,
    /**
     * The bank the grid draws, 0-based, hoisted so the navigation model
     * holds it across a bench and back (section 3.3's last checklist line)
     * instead of `remember(entry.dir)` losing it with the composition.
     */
    bank: Int,
    onBankChange: (Int) -> Unit,
    /** The pad the strip reads, or null before any is tapped. Tapping selects; the strip names it. */
    selectedSlot: Int?,
    onSelectSlot: (Int) -> Unit,
    /** The strip's OPEN ▸: the tap door into PAD SHEET for this slot. */
    onOpenPad: (Int) -> Unit,
    /** The strip's CAPTURE ▸ on an empty pad: PAD CAPTURE aimed at this slot. */
    onCaptureInto: (Int) -> Unit,
    /** The strip's FROM SNIPS ▸ on an empty pad: SNIPS in pick mode aimed at this slot. */
    onFromSnips: (Int) -> Unit,
    /** How many versions the kit holds, for THIS KIT's closed line. */
    versions: Int,
    busy: Boolean,
    onLongPress: (Int) -> Unit,
    onTakesBin: () -> Unit,
    onTexture: (slot: Int, spec: TextureKits.Spec) -> Unit,
    onSetKey: (com.snipsnap.audio.KeySpec?) -> Unit,
    onInKey: () -> Unit,
    onTwins: () -> Unit,
    /** Flipped to a bank (0-based) with nothing on it: say what fills it (`Copy.bankEmpty`) as the blanks come up. */
    onBankEmpty: (Int) -> Unit = {},
    /**
     * A one-shot ask to open on this bank (0-based) — a chop landed ONTO
     * bank B wants B on screen, not A. Consumed through
     * [onBankRequestConsumed] the moment it is honoured, so a later flip
     * by hand is never fought, and a stale ask never re-fires.
     */
    bankRequest: Int? = null,
    onBankRequestConsumed: () -> Unit = {},
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
    /** SPLIT: a pad taken apart into sines, transient and air, on three faders. */
    onSplit: () -> Unit,
    /** DUST ALL: every pad under the kit's own tape's hiss and room (`docs/DUST.md`), the per-pad chip at one amount. */
    onDustAll: () -> Unit = {},
    onEmptyLongPress: (Int) -> Unit = {},
    onEmptyTapHint: (Int) -> Unit = {},
    /** NO_PROJECT_OPEN's own route: the shelf is where a project is opened or started. No default — a screen that forgets to wire this fails the compile, not the user. */
    onNavigateKits: () -> Unit,
    /** Whether a project is open: the cold page says one of two things, and this picks which. */
    hasProject: Boolean,
    /** PROJECT_HAS_NO_KIT's second route: TAPE, where a kit's first sound is recorded. */
    onNavigateTape: () -> Unit,
) {
    val scheme = LocalScheme.current

    if (entry == null) {
        // One panel, one story, and no switcher: with no kit there is
        // nothing to switch between, and the switcher's own NO KITS YET
        // would be a second empty saying the same thing. A project with no
        // kit gets + FROM SHELF (the same pick mode the switcher's chip
        // opens) and TAPE; no project at all gets the shelf.
        if (hasProject) {
            EmptyStatePanel(
                Copy.PROJECT_HAS_NO_KIT,
                listOf(EmptyStateRoute(Places.ADD_FROM_SHELF, onAddFromShelf), EmptyStateRoute("TAPE ▸", onNavigateTape)),
            )
        } else {
            EmptyStatePanel(Copy.NO_PROJECT_OPEN, listOf(EmptyStateRoute("SHELF ▸", onNavigateKits)))
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
    // Built at the engine's own polyphony so the status line and the
    // engine cannot drift: at the cap the engine steals exactly the voice
    // the allocator said it would.
    val allocator = remember(entry.dir) { VoiceAllocator(maxVoices = PadEngine.MAX_VOICES) }
    // Only the fullscreen grid's status line reads these two (inside its
    // Popup, so this body never recomposes on a voice coming or going):
    // what the allocator counts, and what the device says a round trip
    // costs. The latency is polled about once a second - it reads a
    // timestamp, so it is cheap but not free, and it does not move fast
    // enough to want a frame.
    var voiceCount by remember(entry.dir) { mutableIntStateOf(0) }
    var latency by remember(entry.dir) { mutableStateOf(StreamFacts.latency(null)) }
    // The endings ring, drained at screen rate - the allocator's
    // bookkeeping (who's oldest, who's still sounding) never drifts from
    // what the engine actually did, and a route change reopens the stream
    // from here too.
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

    // The glow the pad cells cannot draw for themselves: a hit that did not
    // arrive as a finger on a cell (a MIDI note, the fullscreen grid) has no
    // cell gesture to light it. The cells light from their own touch; this
    // map is the echo for everything else, and FullscreenPlayGrid's glow.
    val glow = remember(kit) { kit.pads.associate { it.slot to Animatable(0f) } }
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

    fun hit(slot: Int, velocity: Float = 1f) {
        val pad = kit.pad(slot) ?: return
        if (!engineUp || !player.isUp()) return
        // oneShot forced true, not read off the pad: this screen has no
        // release gesture to call noteOff with, so a gate pad's own
        // metadata would otherwise sit unused - forcing it here says so,
        // rather than leaving a future release gesture to discover it.
        // The velocity is the grid's, not a constant (J37): SOFT HITS
        // builds real velocity layers and `PadHit.resolve` has always
        // picked one by velocity, so a hard-coded 1f meant the layers could
        // be built and never heard.
        val allocation = allocator.noteOn(slot, velocity, pad.muteGroup, oneShot = true)
        for (voice in allocation.choked + allocation.stolen) player.stop(voice.id)
        if (!player.hit(pad, velocity, allocation.started.id)) allocator.voiceEnded(allocation.started.id)
        voiceCount = allocator.activeCount
    }

    // The two paths below are PLAY's, folded in (section 5, decision 4): a
    // MIDI key and the fullscreen grid both HAVE a release, so a gate pad
    // honours its own metadata here - noteOn reads `pad.oneShot` and
    // `release` calls noteOff - where the window's tap cannot.
    fun playHeld(slot: Int, velocity: Float) {
        val pad = kit.pad(slot) ?: return
        // No stream, no voice: with no callback running nothing would ever
        // report an ending, and the voice count would climb and stick.
        // `isUp` reads the native dead-stream flag, so the window between
        // a route change and the frame loop's restart is covered too.
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
    // Read through rememberUpdatedState wherever something outlives one
    // composition (the focus callback, the lifecycle observer, the cable
    // pull): `glow` is keyed on the kit, so a `panic` captured once would
    // keep extinguishing a previous kit's animatables.
    val onPanic by rememberUpdatedState<() -> Unit>(::panic)

    // A MIDI keyboard on the pads: `:shell`'s MidiPads picks the slot
    // (under the map the chip in the footer sets) and the note goes through
    // this screen's own `playHeld` and `release`, so a keyboard note
    // chokes, steals and glows exactly as a finger does. A kit can change
    // under an open screen (a re-read sidecar), so the handler is read
    // through rememberUpdatedState rather than captured once by the
    // listener. The keyboard itself is opened and closed with the
    // lifecycle, below, so no note can reach `playHeld` after ON_STOP's
    // panic.
    var midiMap by remember(entry.dir) { mutableStateOf(MidiPads.Map.MPC) }
    var midiDevices by remember(entry.dir) { mutableStateOf<List<String>>(emptyList()) }
    val onMidi by rememberUpdatedState<(MidiWire.Event) -> Unit>({ event ->
        MidiPads.handle(event, midiMap, onHit = ::playHeld, onRelease = ::release, onPanic = ::panic)
    })
    val midi = remember(entry.dir) { MidiInput(engineContext) }
    val midiListener = remember(entry.dir) {
        object : MidiInput.Listener {
            override fun onEvent(event: MidiWire.Event) = onMidi(event)
            override fun onDevicesChanged(names: List<String>) { midiDevices = names }
            // The cable is out: a gated pad it held would ring until PANIC.
            override fun onDeviceRemoved() = onPanic()
        }
    }

    // Lessons from PLAY: ON_STOP means allOff() - a backgrounded phone
    // should not keep a choke group ringing, or leave the allocator
    // thinking voices are still active while the frame loop isn't ticking
    // to drain their endings. A focus loss asks for the same silence, so
    // KIT's shared AudioFocus registration rides this same effect: acquired
    // the moment the grid can make sound, released the moment it stops
    // being able to - leaving the screen, or the phone itself taking the
    // interruption. The MIDI keyboard rides the same events as KEYS' does:
    // open while the screen can make sound (addObserver replays ON_START),
    // closed the moment it must not, the readout cleared on the way down.
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioVoice = remember(entry.dir) { object : AudioVoice { override fun silence() = onPanic() } }
    DisposableEffect(lifecycleOwner, entry.dir) {
        AudioFocus.acquire(audioVoice)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    onPanic()
                    AudioFocus.release(audioVoice)
                    midi.close()
                    midiDevices = emptyList()
                }
                Lifecycle.Event.ON_START -> {
                    AudioFocus.acquire(audioVoice)
                    midi.open(midiListener)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            AudioFocus.release(audioVoice)
            midi.close()
        }
    }

    val bankCount = PadBanks.banksUsed(kit.pads.map { it.slot })
    // Two banks are always reachable, filled or not: an empty B is a page
    // the user fills the same three ways A is filled (hold a pad to
    // capture, SNIPS → PAD, a chop landed ONTO it) plus the twins. A kit
    // that loses a bank above B (an UNDO past a third bank) falls back.
    val reachable = maxOf(bankCount, 2)
    LaunchedEffect(reachable) {
        if (bank >= reachable) onBankChange(0)
    }
    LaunchedEffect(bankRequest) {
        val asked = bankRequest ?: return@LaunchedEffect
        onBankChange(asked.coerceIn(0, reachable - 1))
        onBankRequestConsumed()
    }
    val showing = bank.coerceAtMost(reachable - 1)

    // FULL: an in-app Popup + a runtime orientation flip, not a second
    // Activity. the loop's old separate-Activity pattern existed because
    // LOOP owns a wholly disk-backed session and its own audio-thread
    // engine that has no reason to live inside App()'s composition; this
    // screen's whole state - this VoiceAllocator, this PadEngine, every
    // in-flight choke - already lives in App()'s composition, and
    // "playback/state shared across the rotation" is the brief's explicit
    // requirement. A second Activity would mean either a process-wide
    // singleton to smuggle that live state across an Activity boundary, or
    // rebuilding it (a fresh engine losing every loaded sample, a fresh
    // allocator losing every active choke) - both worse than staying in
    // one composition. MainActivity already declares
    // `configChanges="orientation|screenSize|keyboardHidden"` in the
    // manifest, so flipping `requestedOrientation` at runtime reflows the
    // window instead of recreating the Activity.
    var fullscreen by remember { mutableStateOf(false) }
    val exitFullscreen = { fullscreen = false }
    val activity = remember(engineContext) { engineContext.findActivity() }
    // Fix round 1 (F1, CRITICAL, from PLAY): the restore must not read
    // `fullscreen` live inside onDispose - that effect runs precisely
    // because `fullscreen` just flipped, so a live read saw false and the
    // restore never fired, sticking the app landscape until process death.
    // This effect is keyed on the value itself, so the lambda below holds
    // the value it was started with: onDispose restores portrait on the
    // flip back AND on leaving the screen while still fullscreen. Keyed
    // this way it also writes nothing on a plain entry, so opening PADS
    // never fights the orientation another screen set.
    DisposableEffect(fullscreen, activity) {
        // A plain val, not the state delegate: onDispose must see the value
        // this effect started with, not whatever `fullscreen` has become.
        val wentFullscreen = fullscreen
        if (wentFullscreen) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            if (wentFullscreen) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The header carries the kit (UX review S6): the name chip is the
        // switcher - it used to be a row of its own above this one, saying
        // the name a second and third time - then the pad count, the
        // shelf's door, and the fullscreen grid. A floor of one hit target
        // tall, not a height: the 40dp this was clamped every chip in it.
        // The key, tempo and bank, when there are any, sit on a line of
        // their own beneath, so a long kit name never has to share the row
        // with them.
        Column(
            Modifier
                .fillMaxWidth()
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The name and the count share what the two chips leave; the
                // count is measured first, so a long name ellipsises rather
                // than pushing it off.
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .weight(1f, fill = false)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme)
                            .tapeClick(label = Places.switchKitName(switcherLine), onClick = onSwitchKit)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        TapeText(kit.name.uppercase(java.util.Locale.ROOT) + " ▸", TapeType.pixel, scheme.ink.tape, maxLines = 1)
                    }
                    TapeText(Copy.countOf(kit.pads.size, "PAD", "PADS"), TapeType.lcdSmall, scheme.amber.tape, maxLines = 1)
                }
                // The visible `+ KIT` is the room the header has; the
                // accessible name is the door's full one.
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .widthIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme)
                        .tapeClick(label = Places.ADD_FROM_SHELF, onClick = onAddFromShelf)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(Places.ADD_KIT, TapeType.pixel, scheme.amberOnChrome, maxLines = 1)
                }
                // PLAY's FULL, at the header's right end (section 3.3):
                // the fullscreen grid is the part of PLAY that was
                // different from KIT, and this is its door. A plain word:
                // the circle arrow before it had no glyph in either face
                // and drew as a circle.
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .widthIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme)
                        .tapeClick(label = "FULLSCREEN") { fullscreen = true }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("FULL", TapeType.pixel, scheme.ink2.tape, maxLines = 1)
                }
            }
            val tempo = kit.tempoBpm?.let { "%.0f BPM  ".format(java.util.Locale.ROOT, it) } ?: ""
            val keyed = kit.key?.let { KeyPicker.label(it) + "  " } ?: ""
            val banks = if (bankCount > 1 || showing > 0) PadBanks.letter(showing) + "  " else ""
            val facts = (keyed + tempo + banks).trim()
            if (facts.isNotEmpty()) {
                TapeText(facts, TapeType.lcdSmall, scheme.amber.tape, Modifier.padding(bottom = 6.dp), maxLines = 1)
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

        // The door onto bank B (September UAT, finding 11). Always drawn,
        // even with only bank A filled: a second page that only appears
        // once something is on it is a page nobody finds, and the BREED /
        // bank B round found exactly that. An empty bank reads EMPTY, and
        // flips like a full one — its blanks take a capture, a SNIPS → PAD
        // landing or a chop, the same as A's — with a toast saying so.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (b in 0 until reachable) {
                val here = b == showing
                val filled = kit.pads.count { it.slot in PadBanks.slots(b) }
                // Empty by its count, not by `bankCount`: a sparse kit with
                // pads on A and C has a B with nothing on it, and that B
                // takes the same toast path rather than flipping to blanks.
                // Bank A is never "empty" this way — an empty kit is bank A
                // with nothing on it, and the grid is where it gets filled.
                val empty = b > 0 && filled == 0
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .let { if (here) it.raisedBevel(scheme) else it.sunkenField(scheme) }
                        // Same text the TapeText below shows — it already
                        // reads as a state ("BANK B · EMPTY"/"BANK A · 12").
                        .tapeClick(label = "BANK ${PadBanks.letter(b)} · ${if (empty) "EMPTY" else filled.toString()}", onClick = {
                            onBankChange(b)
                            if (empty && !here) onBankEmpty(b)
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(
                        "BANK ${PadBanks.letter(b)} · ${if (empty) "EMPTY" else filled.toString()}",
                        TapeType.pixel,
                        when {
                            here -> scheme.titleInk.tape
                            empty -> scheme.ink3.tape
                            else -> scheme.ink2.tape
                        },
                        maxLines = 1,
                    )
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
                            selected = slot == selectedSlot,
                            echo = glow[slot],
                            // A tap on a filled pad plays it AND selects it,
                            // so the strip below names what was just heard.
                            onTap = { s, v -> hit(s, v); onSelectSlot(s) },
                            onLongPress = onLongPress,
                            onEmptyLongPress = onEmptyLongPress,
                            // An empty pad is selectable too: its strip is
                            // where CAPTURE and FROM SNIPS live. The hint
                            // toast still fires - it is the same early
                            // release - so the old discovery is kept.
                            onEmptyTapHint = { s -> onEmptyTapHint(s); onSelectSlot(s) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // The pad strip (section 3.3): one line for the selected pad, and
        // the tap door into PAD SHEET that the September UAT's findings 4
        // and 5 asked for. It replaces the first of the two legend lines
        // that used to sit here ("HOLD A PAD ..."): the hold stays exactly
        // as it was, but a permanent OPEN ▸ door now says what a hold does,
        // so the sentence is no longer the only teacher. Before any pad is
        // tapped the strip rests on STRIP_RESTING_LINE.
        PadStripLine(
            slot = selectedSlot,
            pad = selectedSlot?.let { kit.pad(it) },
            onOpen = onOpenPad,
            onCapture = onCaptureInto,
            onFromSnips = onFromSnips,
        )

        // ---- THIS KIT: the eight actions, one closed group (section 3.3; SHARE went to OUT) ----
        // Closed by default and reading what it holds, the way the pad
        // sheet's boxes do. Nine buttons on six rows was the densest thing
        // on the hub and most of them are weekly, not hourly, actions.
        var thisKitOpen by remember { mutableStateOf(false) }
        val bankBEmpty = kit.pads.none { it.slot in PadBanks.slots(1) }

        // ---- TEXTURE: SCULPT / STRETCH, a pad becoming a tape of its own ----
        // panelKind is a texture kind, KEY_PANEL for the key picker, or null.
        var panelKind by remember(entry.dir) { mutableStateOf<String?>(null) }
        val sources = kit.pads.map { it.slot }.sorted()

        // SOURCE: the pad the player picked, remembered against the kit
        // alone (J25).
        //
        // This was keyed on `sources.firstOrNull()` — the *value* of the
        // kit's lowest assigned slot — so any chop landing or capture that
        // filled a lower pad silently re-pointed the stepper at the new
        // arrival. SCULPT's NEW TAPE has no per-pad confirm, so the next GO
        // rendered over a pad nobody chose: pick A07, a capture lands on
        // A01, GO renders A01.
        //
        // That key was doing two jobs, which is why this is a replacement
        // and not a deletion. Re-pointing on a kit change was the bug;
        // taking a first value once the kit has pads at all was not, since
        // `sources` is empty on the first composition. The fallback below
        // does the second without the first — the player's pick stands
        // while its pad exists, and the lowest slot is used only when there
        // is no pick to honour: none made yet, or one whose pad has since
        // been removed.
        var pickedSource by remember(entry.dir) { mutableStateOf<Int?>(null) }
        val sourceSlot = pickedSource?.takeIf { it in sources } ?: sources.firstOrNull()

        // One mode per panel, one knob value per panel-and-mode (J25, and
        // the same bug J24 had on PAD SHEET's move knobs). Each panel has
        // its own modes and each mode its own knob, so neither can simply
        // carry across — but keying them on the panel and the mode threw
        // the player's setting away by the act of looking at the other one,
        // which is what the chips are for. Untouched, each still opens on
        // its own default.
        val textureModes = remember(entry.dir) { mutableStateMapOf<String, String>() }
        val mode = panelKind?.takeIf { it != KEY_PANEL }
            ?.let { textureModes[it] ?: TextureKits.modesFor(it).first() } ?: ""
        val knob = panelKind?.takeIf { it != KEY_PANEL }?.let { TextureKits.knobFor(it, mode) }
        val textureKnobs = remember(entry.dir) { mutableStateMapOf<Pair<String, String>, Float>() }
        // Dialled value first, then this mode's own default; 0f only when
        // no panel is open and there is no knob to have a default.
        val fraction = panelKind?.let { textureKnobs[it to mode] }
            ?: knob?.defaultFraction
            ?: 0f

        // X2.3 KIT action rows: the artboard (`isKit` in `TapeOS Oilslick.dc.html`)
        // packs EVIL TWINS (W4.3) and the KEY cycler (F5.3) in here alongside
        // TAKES + BIN, and both are real now. Folded into THIS KIT (section
        // 3.3) with the rows the screen had before the box: the rows keep the
        // shelf's proven widths, because ActionButton is single-line and
        // SPLIT / DUST ALL run to 31 and 39 characters - a plan-literal
        // two-column grid truncated them, and a truncated visible label is
        // worse than a longer column. VERSIONS + BIN · REMIX BANK B · KEY
        // share one row (3:2:1), SPLIT, DUST ALL and BREED each take a full
        // row, then the two texture doors; the panel below them scrolls, so
        // a short screen never pushes the grid.
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GroupBox(
                legend = "THIS KIT",
                summary = Places.thisKit(kit.key?.let { KeyPicker.label(it) }, bankBEmpty, versions),
                open = thisKitOpen,
                onToggle = { thisKitOpen = !thisKitOpen },
                scheme = scheme,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // "VERSIONS + BIN", not "TAKES + BIN" (name-and-find
                    // followups) - matches TakesBinScreen.kt's own renamed
                    // header; the destination screen and its Kotlin symbol are
                    // unchanged, only this entry-point label.
                    // Weighted 3:2:1 against REMIX BANK B and KEY ▸ below
                    // (name-and-find followups, truncation pass): three equal
                    // weights on labels of 37/21/5 characters rendered as
                    // "VERSIONS + BIN …" on a device - the worst truncation in
                    // the app. "ROLL BACK OR RESTORE" also dropped to
                    // "RESTORE": RESTORE alone says the same thing the longer
                    // phrase did.
                    ActionButton("VERSIONS + BIN ▸ RESTORE", scheme, enabled = !busy, modifier = Modifier.weight(3f), onClick = onTakesBin)
                    // EVIL TWINS: bank B lit with seeded re-treatments of bank A; a
                    // second press rerolls. REROLL means twins are there — not
                    // merely something on B, which since bank B round 2 can be
                    // the user's own pads (and then the press is refused).
                    //
                    // No ▸: `onTwins` (`App.kt`'s `evilTwins`) fills bank B and
                    // toasts from right here — it never navigates and never
                    // opens a panel, so the "opens something" glyph doesn't
                    // apply (▸ rule, truncation pass). "·" replaces it as the
                    // same plain separator "KEY · <label>" already uses below.
                    val twinned = kit.pads.any { KitBuilderModel.isTwin(kit, it) }
                    ActionButton(
                        if (twinned) "REMIX BANK B · REROLL" else "REMIX BANK B",
                        scheme,
                        enabled = !busy && kit.pads.any { it.slot in 1..16 },
                        modifier = Modifier.weight(2f),
                        onClick = onTwins,
                    )
                    // KEY: the kit's key, IN KEY, and the tonal pads' tune readout.
                    // Still the fourth door's shape - a bevel that lights amber
                    // while its panel is open - not an ActionButton.
                    val keyOpen = panelKind == KEY_PANEL
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme, fill = if (keyOpen) scheme.amber.tape.copy(alpha = 0.85f) else null)
                            // Always clickable, `!busy` forwarded rather than
                            // dropped: a screen reader is told this control is
                            // temporarily unavailable instead of it silently
                            // vanishing from the tree (accessibility audit
                            // finding 12 — see ActionButton in PadSheetScreen.kt).
                            .tapeClick(label = if (keyOpen) "CLOSE KEY" else "OPEN KEY", enabled = !busy) { panelKind = if (keyOpen) null else KEY_PANEL }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText("KEY ▸", TapeType.pixel, if (busy) scheme.ink3.tape else if (keyOpen) scheme.titleInk.tape else scheme.ink2.tape)
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
                    // DUST ALL: the per-pad DUST chip on every pad at one amount,
                    // from the tape each pad came off (else the kit's), so the
                    // kit shares one room and one floor (docs/DUST.md). Only
                    // offered when the kit came off a tape at all.
                    ActionButton(
                        "DUST ALL · THE TAPE'S OWN HISS AND ROOM",
                        scheme,
                        enabled = !busy && DustPrints.kitTape(kit) != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDustAll,
                    )
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // BREED: this kit's own recipes crossed with a second kit's
                    // into a new child, kept beside this one — the shelf-level
                    // "pick a kit" hand-off (App.kt's pendingBreedWith) runs
                    // next, the same shape SNIPS → PAD already uses to pick a
                    // kit for a snip. The button always opens that picker, so
                    // its label is constant; the line under it counts the pads
                    // with a recipe to cross (`Breed.recipePads`), so "0 PADS
                    // CROSSED" is never the first word of the explanation.
                    // Still enabled at zero: the other kit's racks can cross
                    // over this one's audio.
                    ActionButton(
                        Copy.BREED_BUTTON,
                        scheme,
                        enabled = !busy && kit.pads.isNotEmpty() && canBreed,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onBreed,
                    )
                    TapeText(
                        Copy.breedSubtitle(Breed.recipePads(kit).size, kit.pads.size),
                        TapeType.pixelSmall,
                        scheme.ink3.tape,
                        Modifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (kind in TextureKits.KINDS) {
                        val open = panelKind == kind
                        val doorEnabled = !busy && kit.pads.isNotEmpty()
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                                .raisedBevel(scheme, fill = if (open) scheme.amber.tape.copy(alpha = 0.85f) else null)
                                // Always clickable, `doorEnabled` forwarded
                                // rather than dropped: a screen reader is told
                                // this door is temporarily unavailable instead
                                // of it silently vanishing from the tree
                                // (accessibility audit finding 12).
                                .tapeClick(label = if (open) "CLOSE $kind" else "OPEN $kind", enabled = doorEnabled) { panelKind = if (open) null else kind }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // TEXTURES dropped from SCULPT's own subtitle
                            // (truncation pass): equal 26/23-character weights
                            // on this even split were marginal at 411dp and
                            // truncated at 360dp, and "TEXTURES" was the padded
                            // half — GRANULAR already names the technique.
                            // STRETCH's two words are untouched: SLOW and
                            // FREEZE are its own two modes (`modesFor`), not
                            // padding.
                            val doorSubtitle = when (kind) {
                                "SCULPT" -> "GRANULAR"
                                "STRETCH" -> "SLOW & FREEZE"
                                else -> ""
                            }
                            TapeText(
                                "$kind ▸ $doorSubtitle",
                                TapeType.pixel,
                                if (!doorEnabled) scheme.ink3.tape else if (open) scheme.titleInk.tape else scheme.ink2.tape,
                            )
                        }
                    }
                }

                // SHARE is OUT's now (plan §3.5): one file out the share sheet
                // is an output, so it sits with the other outputs.

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
                        onMode = { picked -> textureModes[kind] = picked },
                        sourceLabel = "${MutateSheet.padTag(src)} · ${kit.pad(src)?.displayName ?: ""}",
                        onSourceStep = { step ->
                            val i = sources.indexOf(src)
                            if (i >= 0 && sources.size > 1) pickedSource = sources[(i + step).mod(sources.size)]
                        },
                        knobLabel = knob.label,
                        knobFraction = fraction,
                        knobText = TextureKits.knobLabel(knob, knob.value(fraction)),
                        onKnob = { f -> textureKnobs[kind to mode] = (f * 40f).let { Math.round(it) / 40f } },
                        busy = busy,
                        onGo = {
                            val seed = Random.nextLong(0L, 1_000_000L)
                            onTexture(src, TextureKits.spec(kind, mode, fraction, seed))
                        },
                    )
                }
            }
            // Under the box, in the same scroll: the legend, the provenance line
            // and PLAY's footer. They sat between the strip and the box as fixed
            // rows until a phone showed the box had no room left at all — the
            // grid and the strip take the height a 4×4 needs, and whatever is
            // fixed below them is taken from the one region that scrolls.
            // The second gesture with nothing to see (J37). A pad that answers
            // to where it is tapped is not discoverable by looking, and a hint
            // that can be dismissed is a feature that can be lost. The one
            // legend left: the strip carries the other.
            TapeText(Copy.PAD_VELOCITY_LEGEND, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            // Where the kit came from (persona review, P4.4). The same move as
            // the two lines above: always there, cannot be dismissed. The
            // finding was that provenance is good and reachable only by
            // holding a pad — this says the kit's half of it without a
            // gesture, and `Provenance.ofKit` is the same reader the liner
            // notes on the card use, so the screen and the paper agree.
            //
            // Absent, not blank, for a kit built by hand: there is no honest
            // line for "nowhere".
            val cameFrom = Provenance.ofKit(kit.pads)
            if (cameFrom != null) {
                TapeText(Copy.kitCameFrom(cameFrom), TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            }
    
            // PLAY's footer, folded in (section 3.3): PANIC always, and the MIDI
            // line only while a keyboard is plugged in - there is no idle
            // "NO MIDI KEYBOARD" line any more, since the strip and the legend
            // above already fill the space a hint used to. MPC NOTES is the
            // hardware's own map (36 is A02, the kick); A01 = C1 walks the
            // slots, for a kit that is a scale. The flip silences first, as
            // KEYS' OCT and LAYOUT do: a key held across it would let go on a
            // different slot and leave a gate ringing.
            Row(
                Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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
                if (midiDevices.isEmpty()) {
                    Box(Modifier.weight(1f))
                } else {
                    TapeText(midiLine(midiDevices), TapeType.pixelSmall, scheme.ink3.tape, Modifier.weight(1f), maxLines = 1)
                    Box(
                        Modifier
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme)
                            .tapeClick(label = "MIDI MAP ${midiMap.label}") { panic(); midiMap = midiMap.next() }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(midiMap.label, TapeType.pixel, scheme.ink2.tape)
                    }
                }
            }
        }
    }

    // The fullscreen grid, drawn from this screen's own engine, allocator
    // and glow (see the orientation note above). It plays through
    // `playHeld`/`release`, not the window's one-shot-forced `hit`: the
    // fullscreen pads have a release, so a gate pad holds while a finger
    // does. The status line is read here, inside the Popup, so only the
    // Popup recomposes as voices come and go.
    if (fullscreen) {
        Popup(
            onDismissRequest = exitFullscreen,
            properties = PopupProperties(focusable = true),
        ) {
            FullscreenPlayGrid(
                kit = kit,
                glow = glow,
                status = StreamFacts.playStatus(engineUp, voiceCount, PadEngine.MAX_VOICES, latency),
                onHit = { s, v, _ -> playHeld(s, v) },
                onRelease = { s, _ -> release(s) },
                onExit = exitFullscreen,
            )
        }
    }
}


/**
 * The pad strip: one line for the selected pad from [PadStrip.line], then
 * one door chip per entry in its doors - OPEN ▸ for a filled pad, CAPTURE ▸
 * and FROM SNIPS ▸ for an empty one. Dispatches on the door's own label,
 * the same strings `PadStrip` hands out. A row at least a hit target tall
 * in both states, so selecting a pad never moves the page under the thumb.
 */
@Composable
private fun PadStripLine(
    slot: Int?,
    pad: KitPad?,
    onOpen: (Int) -> Unit,
    onCapture: (Int) -> Unit,
    onFromSnips: (Int) -> Unit,
) {
    val scheme = LocalScheme.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (slot == null) {
            // `Copy.PADS_RESTING_LINE`: what the strip reads before any pad
            // has been tapped. ink2, not ink3 (3.85:1 on paper), and two
            // lines, because the hold hint is the half nobody can guess.
            TapeText(Copy.PADS_RESTING_LINE, TapeType.pixelSmall, scheme.ink2.tape, Modifier.weight(1f), maxLines = 2)
            return@Row
        }
        val strip = PadStrip.line(pad, slot)
        TapeText(PadStrip.oneLine(strip), TapeType.pixelSmall, scheme.ink2.tape, Modifier.weight(1f), maxLines = 1)
        for (door in strip.doors) {
            DoorChip(door, onClick = {
                when (door) {
                    PadStrip.DOOR_OPEN -> onOpen(slot)
                    PadStrip.DOOR_CAPTURE -> onCapture(slot)
                    PadStrip.DOOR_FROM_SNIPS -> onFromSnips(slot)
                }
            })
        }
    }
}

/** The Activity under a Compose context, for the fullscreen grid's orientation flip. */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
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
                            // Always clickable, `!busy` forwarded rather
                            // than dropped — see ActionButton's own note in
                            // PadSheetScreen.kt (accessibility audit finding
                            // 12).
                            .tapeClick(label = KeyPicker.ROOTS[r], enabled = !busy) { onSetKey(KeyPicker.key(r, scaleLabel)) }
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(KeyPicker.ROOTS[r], TapeType.pixel, if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape)
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
                        // Always clickable, `!busy` forwarded rather than
                        // dropped (accessibility audit finding 12).
                        .tapeClick(label = s, enabled = !busy) { onSetKey(KeyPicker.key(root, s)) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(
                        s,
                        TapeType.pixelSmall,
                        if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape,
                        maxLines = 2,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("OFF", scheme, enabled = !busy && key != null, modifier = Modifier.weight(1f), onClick = { onSetKey(null) })
            ActionButton("IN KEY ▸ RETUNE TONAL PADS", scheme, enabled = !busy && key != null, modifier = Modifier.weight(2f), onClick = onInKey)
        }

        if (readouts.isEmpty()) {
            TapeText(Copy.NO_TONAL_PADS, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 1)
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
            ActionButton("◄", scheme, enabled = !busy, accessibilityLabel = "PREVIOUS SOURCE", onClick = { onSourceStep(-1) })
            TapeText("SOURCE  $sourceLabel", TapeType.pixel, scheme.ink.tape, Modifier.weight(1f), maxLines = 1)
            ActionButton("►", scheme, enabled = !busy, accessibilityLabel = "NEXT SOURCE", onClick = { onSourceStep(1) })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in modes) {
                val selected = m == mode
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme, fill = if (selected) scheme.amber.tape.copy(alpha = 0.85f) else null)
                        // Always clickable, `!busy` forwarded rather than
                        // dropped (accessibility audit finding 12).
                        .tapeClick(label = m, enabled = !busy) { onMode(m) }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(m, TapeType.pixel, if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape)
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

/** A long press that opens PAD SHEET, timed from the design's own 480ms. */
private const val LONG_PRESS_MS = 480L

/**
 * How lit a pad sits while a finger is on it (J15).
 *
 * Below the 0.35 a fresh hit flashes to, so a hold reads as "held" rather
 * than as a hit that never ended, and well above zero, which is what the
 * pad showed for the last 300 ms of every 480 ms hold.
 */
private const val PAD_HELD_ALPHA = 0.20f

/** The treated-pad corner mark's side, in dp (J36). Small enough to read as a mark, not a control. */
private const val PAD_TREATED_MARK_DP = 7

@Composable
private fun PadCell(
    slot: Int,
    pad: KitPad?,
    peaks: List<PeaksPyramid.Column>?,
    /** The strip is reading this pad: a 1dp amber ring, and the semantics say so (never colour alone). */
    selected: Boolean,
    /** The glow of a hit that did not arrive as this cell's own touch - a MIDI note, say. Null for an empty slot. */
    echo: Animatable<Float, AnimationVector1D>?,
    /** The pad, and how hard: [PadHit.velocityAt] over where in the cell the finger landed. */
    onTap: (Int, Float) -> Unit,
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
                .height(Layout.KIT_PAD_H.dp)
                .then(if (selected) Modifier.border(2.dp, (if (scheme.look == Look.MODERN) scheme.accent else scheme.amber).tape, shape).padding(2.dp) else Modifier)
                .padSkin(scheme, null, shape, 0f, Modifier.raisedBevel(scheme, Layout.PAD_RADIUS.dp))
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
                    this.selected = selected
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
            // finding 5). ink2 itself now clears 4.5:1 against gray in
            // every scheme at full opacity — see Schemes.kt and ContrastTest.
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape, Modifier.padding(4.dp))
        }
        return
    }

    val cls = pad.colorHex?.removePrefix("#")?.toIntOrNull(16)
        ?: Schemes.classColor(pad.drumClass)
    val glow = remember(slot) { Animatable(0f) }
    /**
     * Whether a finger is down on this pad right now (J15).
     *
     * The hold that opens PAD SHEET runs for [LONG_PRESS_MS] = 480 ms and
     * the only feedback the pad had was [glow], which *decays* over
     * `Motion.PAD_GLOW_MS` = 180 ms. So the pad brightened, went dark
     * again, and then sat doing nothing for the remaining 300 ms — the
     * majority of the wait — before the largest surface in the app
     * appeared. A hit is a moment and should fade; a hold is a state and
     * should show for as long as it lasts.
     *
     * A steady lit floor rather than a growing bar, per `UI_DESIGN.md`:
     * "One signature animation: the snip's cassette flying onto the
     * shelf... Everything else is instant." This is a state flipping, not
     * an animation.
     */
    var held by remember(slot) { mutableStateOf(false) }
    // What this pad carries, read out of the same strips the pad sheet
    // draws (J36) rather than re-derived here - so the grid and the sheet
    // cannot disagree about what "treated" means.
    val touchedBenches = remember(pad) { PadSheetBoxes.touched(pad).map { it.legend } }
    // The hit's own flash still decays; the held floor is what stays.
    // maxOf, not a sum: a tap on an already-held pad must not stack into
    // a brighter fill than a fresh hit produces.
    val lit = maxOf(0.35f * glow.value, 0.35f * (echo?.value ?: 0f), if (held) PAD_HELD_ALPHA else 0f)

    val modern = scheme.look == Look.MODERN
    Box(
        modifier
            .height(Layout.KIT_PAD_H.dp)
            // The selection ring, OUTSIDE the class rim with the rim inset
            // under it: the rim's width is the held state and its hue the
            // drum class, so the ring takes the one thing neither already
            // says. It used to be a 1dp line drawn over the rim's outer
            // pixel, which the first run on a phone could not see at all.
            .then(if (selected) Modifier.border(2.dp, (if (modern) scheme.accent else scheme.amber).tape, shape).padding(2.dp) else Modifier)
            // The face is PadGrid's `padSkin`, shared with PlayPad. Classic
            // is this cell's own chain: the held rim is 3dp, and the hit's
            // flash is drawn over the border.
            .padSkin(
                scheme, cls, shape, lit,
                classic = Modifier
                    .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
                    .border(if (held) 3.dp else 2.dp, cls.tape, shape)
                    .background(cls.tape.copy(alpha = lit), shape),
            )
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
                // Names the benches the pad carries (J36). Without it,
                // sixteen treated pads read identically to sixteen raw
                // ones and the only way to tell them apart is to open
                // each pad sheet in turn - which is worse for a TalkBack
                // user than for a sighted one, since the corner marker
                // below is not available to them at all.
                contentDescription = "PAD $tag: ${pad.displayName}" +
                    if (touchedBenches.isEmpty()) "" else ", ${Copy.padTreated(touchedBenches)}"
                this.selected = selected
                // The centre, deliberately: a synthesized click has no
                // position to read, so it gets what a tap at the pad's
                // vertical middle would have produced — the same answer
                // PLAY and KEYS have always given. Full velocity, which
                // this used at first, was a third disagreement with the
                // rest of the app.
                onClick(label = "PLAY") { onTap(slot, PadHit.CENTER); true }
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
                    held = true
                    onTap(slot, PadHit.velocityAt(down.position.y, size.height.toFloat()))
                    scope.launch {
                        glow.snapTo(1f)
                        glow.animateTo(0f, tween(Motion.PAD_GLOW_MS))
                    }
                    val longPressJob = scope.launch {
                        delay(LONG_PRESS_MS)
                        onLongPress(slot)
                    }
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                            }
                        }
                    } finally {
                        // `finally`, because this pointerInput is cancelled
                        // outright when the sheet opens over the grid — and a
                        // pad left `held` would come back lit when the user
                        // returns to KIT, which is worse than the missing
                        // feedback this fixes.
                        held = false
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
            // On paper the colour is the name's own darkened ink and the
            // alpha stronger: lime, mint and cyan at 0.30 vanished on cream.
            PadWaveform(
                peaks,
                if (scheme.isPaper()) Schemes.classInkOn(cls, scheme.raised).tape.copy(alpha = 0.55f) else cls.tape.copy(alpha = 0.30f),
                Modifier.matchParentSize(),
            )
        }
        // Full-opacity ink2 on its own solid backing chip, not a dimmed
        // copy drawn straight over the cell (audit finding 5, 2.30-3.59:1
        // measured). The chip matters here specifically: PadWaveform above
        // draws class-colour peaks at 30% alpha across the *whole* cell,
        // so without an opaque backdrop the tag's actual background is
        // data-dependent (whatever the waveform happens to render at this
        // corner) and can still fall to ~2.4:1 for a bright class colour
        // like HAT_OPEN even with ink2 fixed — see ContrastTest.
        // On paper the chip is the card itself (`padTagBacking`).
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .background(padTagBacking(scheme), RoundedCornerShape(3.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp),
        ) {
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape)
        }
        // The name's ink is `padNameInk`: tinted on a dark pad, darkened on paper.
        TapeText(
            pad.displayName,
            TapeType.marker,
            padNameInk(scheme, cls),
            Modifier.align(Alignment.BottomStart),
            maxLines = 2,
        )
        // The treated marker (J36): a dog-eared corner in the pad's own
        // class colour. A corner rather than a rim, because the rim is
        // already spoken for - its width is the held state (J15) and its
        // hue is the drum class. A drawn shape rather than a glyph, per
        // `UI_DESIGN.md`: "Icons are drawn... never emoji."
        if (touchedBenches.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(PAD_TREATED_MARK_DP.dp)
                    .background(cls.tape),
            )
        }
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
