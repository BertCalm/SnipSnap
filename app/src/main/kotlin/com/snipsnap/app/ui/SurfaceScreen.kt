package com.snipsnap.app.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.AudioFocus
import com.snipsnap.app.AudioVoice
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.SurfaceEngine
import com.snipsnap.app.TiltSource
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.Copy
import com.snipsnap.shell.EchoTime
import com.snipsnap.shell.Gesture
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Modulator
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PrintLength
import com.snipsnap.shell.RingSlot
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.StreamFacts
import com.snipsnap.shell.SurfaceKey
import com.snipsnap.shell.SurfaceLoop
import com.snipsnap.shell.SurfaceStore
import com.snipsnap.shell.TouchSurface
import com.snipsnap.shell.TouchSurface.Mode
import com.snipsnap.shell.TouchSurface.Reading
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The three panels of controls above the pad, in strip order - one shows
 * at a time (see the strip's own comment in [SurfaceScreen]).
 */
private enum class Panel { VOICE, SHAPE, MOD }

/** GRAIN's three knobs, in the order its row's own button cycles them - each an index into `SurfaceStore.Grain`'s fields below. */
private val GRAIN_KNOBS = listOf("SIZE", "DENSITY", "SPRAY")

/** One ◄ ► press on a GRAIN knob: a twentieth of its travel, so an end-to-end sweep is twenty presses - the FEEL stepper's own grain of control. */
private const val GRAIN_STEP = 0.05f

/** SWARM's two knobs, in the order its row's own button cycles them. */
private val SWARM_KNOBS = listOf("VOICES", "DETUNE")

/** One ◄ ► press on DETUNE - GRAIN's own twentieth; VOICES steps by one. */
private const val SWARM_DETUNE_STEP = 0.05f

/** The MOD row's four fields, in the order its own button cycles them. */
private val MOD_FIELDS = listOf("TARGET", "SHAPE", "RATE", "DEPTH")

/** One ◄ ► press on a modulator's DEPTH - the same twentieth GRAIN's knobs step by. */
private const val MOD_DEPTH_STEP = 0.05f

/** A knob as the rows print it: whole percent, no units - the units live in the engine (see GRAIN's row). */
private fun pct(v: Float): String = "${(v * 100f).roundToInt()}%"

/**
 * SURFACE — the tactile pad. The open kit's first pad loops under a
 * finger; where the finger is drives the macros (`TouchSurface` in
 * `:shell` does the arithmetic, `SurfaceEngine` the sound), and PRINT
 * writes the performance to TAPE as a new sample the way an SP-404
 * resamples: what you played is now one pad, no DSP to run later.
 *
 * Five modes, one pad. The phone's tilt always feeds resonance
 * (`SurfaceEngine::applyControl`), not just in XYZ, but the mapping
 * differs per mode:
 *  - XY: one finger, X pitch, Y filter; tilt sets resonance directly,
 *    half-scaled (`f.tilt * 0.5`).
 *  - XYZ: a second finger's distance is Z (drive); the roll of the
 *    phone sets resonance outright (`f.tilt`).
 *  - MORPH: the puck weights four saved corner states, A B C D; tilt
 *    only nudges the blended resonance on top of them, so a flat phone
 *    (tilt 0.5) is a no-op here specifically - every corner still
 *    sounds exactly as captured.
 *  - VECTOR: MORPH's exact corner blend, with the sample area below
 *    also reading the same finger at once - the design/surface-vector
 *    concept's own mode, kept separate from XY/XYZ/MORPH so none of
 *    those change: one touch position, two blends, neither aware of the
 *    other. `applyControl`'s switch falls VECTOR through to MORPH's own
 *    case, so tilt behaves exactly as it does there.
 *  - GRAIN: a cloud of short grains instead of the loop. X is POSITION
 *    (where in the sample the grains read from), Y is pitch, snapped by
 *    the engine to the kit's key (`Grain.h`; `SurfaceKey` decides what
 *    the engine is told - the key, and the pad's own note when the
 *    detector is confident of one, so the snap is to real notes and a
 *    slightly flat pad is pulled into tune). SIZE, DENSITY and SPRAY are
 *    the mode's own row, stepped rather than swept, and kept in
 *    `surface.json` with the pad and the corners. Everything after the
 *    source - crush, drive, filter, echo, spring - is the same chain at
 *    XY's rest, so tilt still sets resonance here (`f.tilt * 0.5`).
 *
 * KEY snaps the loop's pitch to the kit's key in every mode but GRAIN,
 * where it always is - the same snap, in the engine (`Grain.h`), so a
 * note the loop lands on is a note the cloud would land on; with no key
 * it is a semitone ladder around the pad's own note. Off by default and
 * kept in `surface.json`, so a kit from before plays as it did.
 *
 * SWARM, on its own row in every mode but GRAIN (the GRAIN row's twin),
 * thickens the loop into a detuned unison - VOICES copies of every slot's
 * loop, spread across ±DETUNE of a quarter tone and summed at
 * 1/sqrt(VOICES) (`SurfaceEngine::setSwarm`) - the S-4's detuned swarm.
 * One voice is the plain loop, sample for sample; kept in `surface.json`.
 *
 * PAD ◄ ► picks which of the kit's pads the surface plays; SET A..D
 * captures the sound under the last touch as a morph corner (MORPH and
 * VECTOR alike - it is the same corner blend). Both live in
 * `surface.json` beside the kit (`SurfaceStore`), so a morph you set up
 * is there when you come back.
 *
 * ARM A..D picks a corner without touching the pad at all; PRESET ◄ ►
 * then steps `SurfaceStore.Corner.LIBRARY`'s named presets - LBP +/ECHO
 * +/ECHO -/LBP -, `design/surface-vector`'s own idea, plus CRUSH +/-/GLITCH
 * +/-/SPRING +/- once the engine grew a bitcrusher, a delay and a reverb -
 * onto whichever corner is armed. It writes the very same `corners` SET
 * A..D does, just from a curated library instead of a live capture, so the
 * two are interchangeable afterwards: a stepped corner can be re-captured
 * by touch, and a captured one overwritten by stepping.
 *
 * PAD2/PAD3/PAD4 ◄ ► load three more voices onto the pad's sample area -
 * PAD at the apex, PAD2 at the base-left, PAD3 at the base-right, PAD4 at
 * the base-mid, directly under the apex (`TouchSurface.sampleWeights`,
 * `design/surface-vector`'s boards) - and the finger blends all four at
 * once, continuously, with no dead zone: the same position that drives
 * the mode's own macros drives this too, independently. A slot nobody
 * has loaded is just silence at its vertex, not a hole in the pad.
 *
 * RING freezes the last few seconds of whatever the phone is hearing -
 * the mic, or another app through APP AUDIO - into PAD's own slot
 * (`RingSlot` in `:shell`; `MicSessionService.snapshotTail` is the ring):
 * a snapshot on the tap, the ring rolling on underneath, the next tap a
 * fresh one. The frozen voice plays under every mode exactly as a pad
 * would, GRAIN's key snap included (its note is found the way a pad's
 * is). It is a moment, not a setting: nothing lands in `surface.json`,
 * and PAD ◄ ► - or reopening the kit - brings the pad back. EVERY BAR,
 * on the row that appears while the ring is the voice, takes the freeze
 * again on every bar line - the modulators' own bar, from the same origin
 * (`RingSlot.barIndex`) - so the voice tracks the track in the next app a
 * bar behind; it switches itself off, in words, if the ring stops
 * listening, and PAD ◄ ► ends it the way it ends a freeze.
 *
 * LATCH keeps the loop sounding where the finger left it, so one hand can
 * set corners while the other is free; BARS locks a print to a whole
 * number of bars at the kit's tempo, so it drops onto the groove grid.
 * ECHO on the SET row locks the echo's time to a division of that bar
 * (`EchoTime` in `:shell` - FREE, a sixteenth, an eighth, the dotted
 * eighth, a quarter, a half), so the repeats land on the grid too; the
 * engine only ever gets the seconds, and the wet MIX stays the macro.
 *
 * MOD A/B are two modulators (`Modulator` in `:shell`): each a SHAPE at a
 * tempo-snapped RATE with a DEPTH, aimed at a TARGET - one of the seven
 * macros, one of GRAIN's knobs, or the finger itself (X, Y) - and added to
 * whatever the mode and the finger already say for it, in every mode. They
 * are what lets a sound keep moving while the finger is elsewhere, and
 * what makes a print a performance; the frame loop below evaluates them at
 * screen rate from one origin and the engine glides the steps like any
 * other target. X and Y are applied here rather than in the engine
 * (`TouchSurface.nudged`): the nudged position is what the engine, the
 * sample blend and the painted puck all see, so a RANDOM on X in XY lands
 * the loop on a new pitch every bar and on both axes in MORPH jumps
 * between corners on the bar, visibly. FOLLOW and DUCK are shapes that
 * run on the room instead of the bar - the live input level from
 * whichever ring is listening (`MicSessionService.level`), through a
 * `Modulator.Follower` stepped once a frame - so CUTOFF opens when
 * you clap and DRIVE ducks under the kick of the track in the next app: a
 * sidechain from the room. GESTURE is a shape that plays the kit's
 * recorded finger (`Gesture` in `:shell`): REC on the GESTURE row arms
 * it, the next touch starts a recording of BARS' own length against the
 * bar clock, and the kit keeps it in `surface.json`; a MOD slot on SHAPE
 * GESTURE then plays it back looping on the bar, as X and Y nudges - so
 * at rest it repeats the hand exactly, and under a finger it adds, like
 * every other modulator. A performance that repeats on its own. SET A..D
 * captures the finger, not the modulators' nudge - a corner is a place,
 * and the modulator moves around it.
 *
 * A print goes → TAPE (the deck's shelf) or → PAD: the SP-404 move
 * proper, the performance landing on a pad of the kit you are holding
 * through the same door SYNTH's SEND TO PAD uses (`assign` on an empty
 * slot, `replaceAudio` on a taken one, the original in the bin).
 *
 * The controls above the pad are three panels behind a strip - VOICE
 * (the pad, the ring, the sample triangle's other three), SHAPE (the
 * mode's knobs, the corners with what they are held to, the presets),
 * MOD (the modulators, the gesture) - one showing at a time, so the pad
 * keeps its height however many rows the panels hold. The mode row and
 * the print row (→ TAPE or → PAD, BARS, PRINT) stay above the strip in
 * every panel.
 *
 * Polling: every pointer event updates the *target* reading; a frame
 * loop steps a screen-rate smoother toward it, paints the puck from the
 * smoothed value and sends that same value to the engine, which glides
 * again per sample. Two smoothers, two rates, one equation. Everything
 * the loop works out between frames - the smoother, the modulators'
 * origin, EVERY BAR's bar, the gesture recorder, what LATCH holds - is
 * `SurfaceLoop` in `:shell`, tested there; the loop here reads the clock
 * and the engine, hands it a frame, and paints and sends what comes back.
 */
@Composable
fun SurfaceScreen(
    entry: KitShelf.Entry?,
    onToast: (String) -> Unit,
    /** The print landed on TAPE; the deck should re-read its shelf. */
    onPrinted: () -> Unit,
    /** The print landed on a pad: the kit changed on disk, the shelf should know. */
    onKitUpdated: (Kit) -> Unit,
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engine = remember { SurfaceEngine(deviceSampleRate(context)) }
    val tilt = remember { TiltSource(context) }
    var mode by remember { mutableStateOf(Mode.XY) }
    var target by remember { mutableStateOf(Reading.REST) }
    var painted by remember { mutableStateOf(Reading.REST) }
    // `tilt.tilt` is a plain `@Volatile var` on `TiltSource`, not Compose
    // state - reading it directly from the readout/semantics below would
    // only ever refresh piggybacked on `painted` changing, and `painted`
    // stops changing the instant the puck is still (Compose skips
    // recomposition on a structurally-equal `mutableStateOf` write). Tilt
    // moves independently of the finger, so it needs its own mirror,
    // stepped every frame in the loop below (Copilot review, PR #187).
    var tiltReading by remember { mutableStateOf(0.5f) }
    var padName by remember { mutableStateOf<String?>(null) }
    var padSlot by remember { mutableStateOf<Int?>(null) }
    // True while slot 0 holds a RING freeze rather than the pad padSlot
    // names: padName then wears RingSlot.label() (so everything gated on
    // "a voice is loaded" keeps working), and the readout skips the pad
    // tag. padSlot is left alone on purpose - PAD ◄ ► steps on from the
    // pad the ring replaced, not from nowhere.
    var ringVoice by remember { mutableStateOf(false) }
    // EVERY BAR: the frame loop re-freezes on each bar line while this is
    // on. Not persisted, like the freeze itself - it needs a live session.
    var ringOnBar by remember { mutableStateOf(false) }
    // Bumped by every press that loads slot 0 (PAD ◄ ►, RING) before its
    // IO starts, and checked after - the same discipline pad2Generation
    // keeps for slot 1, so a RING tap and a PAD press close together
    // land whichever was pressed last, not whichever read finished last.
    var voiceGeneration by remember { mutableStateOf(0) }
    // Slots 1, 2 and 3 of the engine's source array - the sample area's
    // base-left, base-right and base-mid vertices (TouchSurface.sampleWeights);
    // the apex is padName/padSlot above. Independent of mode or corners.
    var padName2 by remember { mutableStateOf<String?>(null) }
    var padSlot2 by remember { mutableStateOf<Int?>(null) }
    var padName3 by remember { mutableStateOf<String?>(null) }
    var padSlot3 by remember { mutableStateOf<Int?>(null) }
    var padName4 by remember { mutableStateOf<String?>(null) }
    var padSlot4 by remember { mutableStateOf<Int?>(null) }
    // Bumped by every stepPad2/stepPad3/stepPad4 press before its IO read
    // starts, and checked after: a press whose read finishes after a
    // later one's is stale and must not overwrite the newer result.
    var pad2Generation by remember { mutableStateOf(0) }
    var pad3Generation by remember { mutableStateOf(0) }
    var pad4Generation by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(SurfaceStore.Settings.DEFAULT) }
    // Which corner PRESET ◄ ► targets - armed by its own ARM A..D row, a
    // separate gesture from SET A..D (which always captures the live
    // touch): arming only picks a target, it never itself changes a
    // corner's sound.
    var armedCorner by remember { mutableStateOf<Int?>(null) }
    // Where each corner's stepper last landed in SurfaceStore.Corner.
    // LIBRARY, null until PRESET ◄ ► has actually been pressed for that
    // corner - so the readout below never claims a corner already reads
    // (say) "LBP +" before a preset has actually been stepped onto it.
    var presetIndexA by remember { mutableStateOf<Int?>(null) }
    var presetIndexB by remember { mutableStateOf<Int?>(null) }
    var presetIndexC by remember { mutableStateOf<Int?>(null) }
    var presetIndexD by remember { mutableStateOf<Int?>(null) }
    // The kit `settings` (and so its `corners`) actually belong to - null
    // while LaunchedEffect(entry) below is still loading a kit's
    // surface.json. setCorner/stepPreset both refuse to persist while this
    // disagrees with the current entry, so a fast SET/PRESET press landing
    // in that window can't write the outgoing kit's other corners into the
    // incoming kit's surface.json (Copilot review, PR #195) - `settings`
    // itself, not just armedCorner/presetIndex*, is stale until this is set.
    var settingsLoadedFor by remember { mutableStateOf<File?>(null) }
    // The frame loop's arithmetic lives in `SurfaceLoop` (`:shell`, tested):
    // the smoother, the modulators' origin, EVERY BAR's bar, the gesture
    // recorder and the finger LATCH holds and SET A..D captures (`held`).
    // The loop below reads the clock and the engine and hands it a frame;
    // it hands back what to play and what happened.
    val loop = remember { SurfaceLoop() }
    var printing by remember { mutableStateOf(false) }
    var printToPad by remember { mutableStateOf(false) }
    var latched by remember { mutableStateOf(false) }
    /** Index into PrintLength.BARS; 0 = FREE. */
    var barsIndex by remember { mutableStateOf(0) }
    /** Which [Panel] of controls is showing above the pad; a pick, not a sound, so not persisted - same as armedCorner. */
    var panel by remember { mutableStateOf(Panel.VOICE) }
    /** Which of [GRAIN_KNOBS] the GRAIN row's ◄ ► currently step; a pick, not a sound, so not persisted - same as armedCorner. */
    var grainKnob by remember { mutableStateOf(0) }
    /** Which of [SWARM_KNOBS] the SWARM row's ◄ ► step; a pick, like grainKnob. */
    var swarmKnob by remember { mutableStateOf(0) }
    // GESTURE's recording: REC arms, the next touch-down starts a recording
    // of gestureLength bars (BARS' own length when armed; FREE is one) in
    // the loop, which feeds it every frame and hands the gesture back when
    // it has run. `recording` and `recordingBars` mirror the loop's
    // recorder as Compose state, for the GESTURE row.
    var gestureArmed by remember { mutableStateOf(false) }
    var gestureLength by remember { mutableStateOf(1) }
    var recording by remember { mutableStateOf(false) }
    var recordingBars by remember { mutableStateOf(0f) }
    /** Which modulator (0 = MOD A) and which of its [MOD_FIELDS] the MOD row's ◄ ► step; picks, not sounds, so not persisted either. */
    var modSlot by remember { mutableStateOf(0) }
    var modField by remember { mutableStateOf(0) }
    /** A finished print waiting for a pad to be chosen; the chooser shows while it is set. */
    var pendingPrint by remember { mutableStateOf<Snip?>(null) }
    var landing by remember { mutableStateOf(false) }
    var engineUp by remember { mutableStateOf(false) }
    // The bench's one number, polled about once a second (see PlayScreen).
    var latency by remember { mutableStateOf(StreamFacts.latency(null)) }

    fun started(up: Boolean) {
        engineUp = up
        if (!up) onToast(Copy.noLowLatencyStream("THE SURFACE"))
        else if (engine.isShared()) onToast(Copy.SURFACE_SHARED_STREAM)
    }

    DisposableEffect(engine) {
        started(engine.start())
        tilt.start()
        onDispose {
            tilt.stop()
            engine.close()
        }
    }

    // Audit finding: SURFACE was one of two audio-bearing screens with no
    // ON_STOP handler — PLAY/KIT/GROOVE all silence on backgrounding, but a
    // finger left on the pad (or LATCH holding the last position) kept
    // sounding here. `engine.control(gate = false)` is the same silence
    // [gate] itself already sends the native side, plus resetting the touch
    // state so foregrounding again doesn't replay a stale finger position;
    // a focus loss (a call, another app's audio) asks for the same thing,
    // so the same AudioFocus registration rides this effect — see
    // PlayScreen's own copy of this pattern for the full reasoning.
    fun silenceSurface() {
        target = Reading.REST
        loop.letGo()
        latched = false
        // A recording cannot span a stop: frames pause while the screen
        // is away and the recorder would fill the rest with the last
        // place when they resume, keeping a fragment and a long hold as
        // the kit's gesture over whatever it had. Dropped instead, and
        // disarmed - REC is a moment's intent, not a standing order.
        loop.dropRecording()
        recording = false
        gestureArmed = false
        engine.control(mode, Reading.REST, tilt.tilt, gate = false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioVoice = remember(engine) { object : AudioVoice { override fun silence() = silenceSurface() } }
    DisposableEffect(lifecycleOwner, engine) {
        AudioFocus.acquire(audioVoice)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    silenceSurface()
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

    // The voice: one of the kit's pads, read off the shelf, folded to mono
    // in the engine. Its own note is found on the same IO pass (GRAIN snaps
    // to the kit's [key] from wherever this pad actually sits - see
    // SurfaceKey) and handed over with the sample, so the key the engine
    // holds is never for a pad other than the one it is playing.
    suspend fun loadPad(dir: File, pad: KitPad, key: KeySpec?, generation: Int) {
        val loaded = withContext(Dispatchers.IO) {
            // pad.sampleFile is a kit pad sample, produced only by KitBuilderModel.assign
            // from an already-bounded Snip — readCapped's 600s ceiling is defense in
            // depth, not expected to ever bind.
            runCatching { WavReader.readCapped(File(dir, pad.sampleFile), TAPE_LOAD_MAX_SEC).snip }.getOrNull()
                ?.let { it to SurfaceKey.sourceMidi(it) }
        }
        if (generation != voiceGeneration) return  // a later PAD or RING press already superseded this one
        if (loaded == null) {
            padName = null
            ringVoice = false
            onToast(Copy.sourceUnreadable(pad.displayName))
        } else {
            val (snip, sourceMidi) = loaded
            engine.load(snip)
            engine.setKey(SurfaceKey.of(key, sourceMidi))
            padName = pad.displayName
            padSlot = pad.slot
            ringVoice = false
        }
    }

    // RING: the ring's last few seconds become the voice, once, now. The
    // snapshot and the cleaning run off the main thread (the ring's own
    // contract for readers), and the key is told this voice's note the
    // way loadPad tells it a pad's. Refusals are said, not dimmed: the
    // button is live whenever a kit is open, and a tap with nothing
    // listening names the route (HUM's own discipline).
    // [onBar] is EVERY BAR's own call from the frame loop: quiet on
    // success and on a silent bar (the last freeze stays), and a ring that
    // has stopped listening switches the mode off once, in words, rather
    // than saying so every bar.
    fun freezeRing(onBar: Boolean = false) {
        val key = (entry ?: return).kit.key
        if (!MicSessionService.armed.value) {
            if (onBar) {
                ringOnBar = false
                onToast(Copy.RING_BAR_STOPPED)
            } else {
                onToast(Copy.RING_NOT_LISTENING)
            }
            return
        }
        val appAudio = MicSessionService.source.value == MicSessionService.Source.INSIDE
        val generation = ++voiceGeneration
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                MicSessionService.snapshotTail(RingSlot.frames(MicSessionService.SAMPLE_RATE))
                    ?.let { RingSlot.freeze(it, MicSessionService.SAMPLE_RATE) }
                    ?.let { it to SurfaceKey.sourceMidi(it) }
            }
            if (generation != voiceGeneration) return@launch
            if (loaded == null) {
                if (!onBar) onToast(Copy.RING_NOTHING)
                return@launch
            }
            val (snip, sourceMidi) = loaded
            engine.load(snip)
            engine.setKey(SurfaceKey.of(key, sourceMidi))
            padName = RingSlot.label(snip.durationSeconds)
            ringVoice = true
            if (!onBar) onToast(Copy.ringFrozen(snip.durationSeconds, appAudio))
        }
    }

    // EVERY BAR on: a freeze now, and the frame loop takes the next on the
    // bar line. Off: the last freeze stays as the voice. With nothing
    // listening it stays off and the tap says the route, as RING does.
    fun toggleRingOnBar() {
        if (ringOnBar) {
            ringOnBar = false
            return
        }
        if (!MicSessionService.armed.value) {
            onToast(Copy.RING_NOT_LISTENING)
            return
        }
        ringOnBar = true
        freezeRing()
    }

    // The engine's second source slot - the sample area's base-left
    // vertex. Failure is quieter than the first pad's: a second voice is
    // optional, so a toast for every unreadable file would be noise the
    // first pad already covers when it matters. clearSlot on failure
    // matters here specifically because the touch position, not this
    // screen, decides how much of slot 1 to play - a failed load that
    // left the *previous* sample sitting in the engine would still sound
    // wherever the puck favours that vertex, while padName2 said NO PAD2.
    suspend fun loadPad2(dir: File, pad: KitPad, generation: Int) {
        val snip = withContext(Dispatchers.IO) {
            runCatching { WavReader.readCapped(File(dir, pad.sampleFile), TAPE_LOAD_MAX_SEC).snip }.getOrNull()
        }
        if (generation != pad2Generation) return  // a later press already superseded this one
        if (snip == null) {
            padName2 = null
            engine.clearSlot(1)
        } else {
            engine.load(snip, slot = 1)
            padName2 = pad.displayName
            padSlot2 = pad.slot
        }
    }

    // The engine's third source slot - the sample area's base-right vertex.
    suspend fun loadPad3(dir: File, pad: KitPad, generation: Int) {
        val snip = withContext(Dispatchers.IO) {
            runCatching { WavReader.readCapped(File(dir, pad.sampleFile), TAPE_LOAD_MAX_SEC).snip }.getOrNull()
        }
        if (generation != pad3Generation) return  // a later press already superseded this one
        if (snip == null) {
            padName3 = null
            engine.clearSlot(2)
        } else {
            engine.load(snip, slot = 2)
            padName3 = pad.displayName
            padSlot3 = pad.slot
        }
    }

    // The engine's fourth source slot - the sample area's base-mid vertex.
    suspend fun loadPad4(dir: File, pad: KitPad, generation: Int) {
        val snip = withContext(Dispatchers.IO) {
            runCatching { WavReader.readCapped(File(dir, pad.sampleFile), TAPE_LOAD_MAX_SEC).snip }.getOrNull()
        }
        if (generation != pad4Generation) return  // a later press already superseded this one
        if (snip == null) {
            padName4 = null
            engine.clearSlot(3)
        } else {
            engine.load(snip, slot = 3)
            padName4 = pad.displayName
            padSlot4 = pad.slot
        }
    }

    fun pushCorners(corners: List<SurfaceStore.Corner>) {
        corners.forEachIndexed { i, c -> engine.setCorner(i, c.pitch, c.cutoff, c.resonance, c.drive, c.crush, c.echo, c.spring) }
    }

    fun persist(dir: File, next: SurfaceStore.Settings) {
        settings = next
        scope.launch {
            // Write `settings` fresh here, not the `next` this call
            // captured: two persist() calls close together (a fast PAD3
            // double-tap, say) each launch their own IO write, and those
            // can finish in either order - a write of its own captured
            // value could let an older call's write land on disk after a
            // newer one's and leave a stale choice there. By the time any
            // of these coroutines actually runs, every synchronous
            // `settings = next` above it has already happened, so reading
            // `settings` here means every one of them writes the same,
            // latest content - the write order stops mattering.
            withContext(Dispatchers.IO) { runCatching { SurfaceStore.save(dir, settings) } }
                .onFailure {
                    Log.e("SurfaceScreen", "persist: settings not saved", it)
                    onToast(Copy.SURFACE_SETTINGS_NOT_SAVED)
                }
        }
    }

    LaunchedEffect(entry) {
        // Synchronous, before any suspension below: the moment `entry`
        // changes, `settings` (and so armedCorner's targets) are for a kit
        // that is no longer showing, so nothing may act on them until this
        // effect says otherwise. Resetting armedCorner/presetIndex* here
        // rather than only after the load below closes most of the window
        // a fast ARM+PRESET press could land in; settingsLoadedFor closes
        // the rest (see its own declaration).
        settingsLoadedFor = null
        armedCorner = null
        // The old kit's live intents end now, before the load below
        // suspends, not after it returns: EVERY BAR, an armed REC and a
        // recording in flight were all for the kit that just closed, and
        // a bar line or a touch-down in the load's window must not act
        // on them for this one.
        ringOnBar = false
        gestureArmed = false
        loop.dropRecording()
        recording = false
        recordingBars = 0f
        presetIndexA = null
        presetIndexB = null
        presetIndexC = null
        presetIndexD = null
        if (entry == null) {
            padName = null
            padSlot = null
            ringVoice = false
            padName2 = null
            padSlot2 = null
            padName3 = null
            padSlot3 = null
            padName4 = null
            padSlot4 = null
            engine.clearSlot(1)
            engine.clearSlot(2)
            engine.clearSlot(3)
            // Orphan any in-flight load from before the kit closed.
            voiceGeneration++
            pad2Generation++
            pad3Generation++
            pad4Generation++
            return@LaunchedEffect
        }
        // The kit's surface settings first (a torn file is the defaults,
        // said aloud), then the pad they name, or the kit's lowest.
        val loaded = withContext(Dispatchers.IO) { runCatching { SurfaceStore.load(entry.dir) } }
        settings = loaded.getOrElse {
            onToast(Copy.SURFACE_SETTINGS_UNREADABLE)
            SurfaceStore.Settings.DEFAULT
        }
        pushCorners(settings.corners)
        engine.setGrain(settings.grain)
        engine.setKeySnap(settings.keySnap)
        engine.setSwarm(settings.swarm)
        engine.setEchoTime(EchoTime.seconds(settings.echoTime, entry.kit.tempoBpm))
        settingsLoadedFor = entry.dir
        val pads = entry.kit.pads.sortedBy { it.slot }
        val pad = pads.firstOrNull { it.slot == settings.padSlot } ?: pads.firstOrNull()
        if (pad == null) {
            padName = null
            padSlot = null
            ringVoice = false
            // Orphan a RING or PAD load from the previous entry, as
            // pad2Generation is below - and clear the slot itself: with no
            // pad to load over it, a freeze from the last kit would
            // otherwise keep sounding under a readout that says NO PAD.
            voiceGeneration++
            engine.clearSlot(0)
        } else {
            loadPad(entry.dir, pad, entry.kit.key, ++voiceGeneration)
        }
        // The second and third slots are optional - null unless a kit was
        // saved with one chosen, and never fall back to the kit's lowest
        // the way the first slot does.
        val pad2 = pads.firstOrNull { it.slot == settings.secondPadSlot }
        if (pad2 == null) {
            padName2 = null
            padSlot2 = null
            engine.clearSlot(1)
            // Orphan any load stepPad2 kicked off against the previous
            // entry - without this, its generation check still passes
            // against this kit's unchanged pad2Generation, and it can
            // repopulate padName2/slot 1 with the old kit's sample after
            // this effect has already decided there is none here.
            pad2Generation++
        } else {
            loadPad2(entry.dir, pad2, ++pad2Generation)
        }
        val pad3 = pads.firstOrNull { it.slot == settings.thirdPadSlot }
        if (pad3 == null) {
            padName3 = null
            padSlot3 = null
            engine.clearSlot(2)
            // Same reasoning as pad2Generation above, for stepPad3.
            pad3Generation++
        } else {
            loadPad3(entry.dir, pad3, ++pad3Generation)
        }
        val pad4 = pads.firstOrNull { it.slot == settings.fourthPadSlot }
        if (pad4 == null) {
            padName4 = null
            padSlot4 = null
            engine.clearSlot(3)
            // Same reasoning as pad2Generation above, for stepPad4.
            pad4Generation++
        } else {
            loadPad4(entry.dir, pad4, ++pad4Generation)
        }
    }

    // PAD ◄ ►: the next pad by slot, wrapping; remembered in surface.json.
    // The position steps from the *chosen* slot (settings, updated the
    // moment a tap lands), not the loaded one (padSlot, updated when the
    // WAV has been read), so a run of quick taps advances once per tap.
    fun stepPad(delta: Int) {
        val dir = entry?.dir ?: return
        // Refused while `settings` is still the outgoing kit's, like every
        // other press that persists (see settingsLoadedFor): a tap in the
        // reload window would write the chosen pad to disk and then be
        // reverted in memory by the load landing after it.
        if (settingsLoadedFor != dir) return
        val pads = entry.kit.pads.sortedBy { it.slot }
        if (pads.isEmpty()) return
        val chosen = settings.padSlot ?: padSlot
        val at = pads.indexOfFirst { it.slot == chosen }.let { if (it < 0) 0 else it }
        val pad = pads[((at + delta) % pads.size + pads.size) % pads.size]
        persist(dir, settings.copy(padSlot = pad.slot))
        // Choosing a pad ends EVERY BAR: the next bar would only take the
        // voice straight back.
        ringOnBar = false
        val generation = ++voiceGeneration
        scope.launch { loadPad(dir, pad, entry.kit.key, generation) }
    }

    // GRAIN ◄ ►: steps whichever of SIZE/DENSITY/SPRAY the row's own knob
    // button has picked, a twentieth at a time. Remembered in surface.json
    // like the pad and the corners, and shaping the very next grain - the
    // engine reads its knobs at trigger time, so there is nothing to wait
    // for. Refused while `settings` is still the outgoing kit's, for the
    // same reason SET and PRESET are (see settingsLoadedFor).
    fun stepGrain(delta: Int) {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return
        val g = settings.grain
        fun nudged(v: Float) = (v + delta * GRAIN_STEP).coerceIn(0f, 1f)
        val next = when (grainKnob) {
            0 -> g.copy(size = nudged(g.size))
            1 -> g.copy(density = nudged(g.density))
            else -> g.copy(spray = nudged(g.spray))
        }
        engine.setGrain(next)
        persist(dir, settings.copy(grain = next))
    }

    // SWARM ◄ ►: VOICES by one, DETUNE by a twentieth - GRAIN's row's own
    // discipline, remembered in surface.json and heard within a control
    // interval (a voice that joins under a held note starts where the
    // loop is, so the swarm is a unison at once; a tap restarts them all).
    fun stepSwarm(delta: Int) {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return
        val w = settings.swarm
        val next = when (swarmKnob) {
            0 -> w.copy(voices = (w.voices + delta).coerceIn(1, SurfaceStore.Swarm.MAX_VOICES))
            else -> w.copy(detune = (w.detune + delta * SWARM_DETUNE_STEP).coerceIn(0f, 1f))
        }
        engine.setSwarm(next)
        persist(dir, settings.copy(swarm = next))
    }

    // KEY: the loop's pitch snapped to the kit's key, the way GRAIN's
    // always is - the same snap, in the engine, so a note the loop lands
    // on is a note the cloud would. Remembered in surface.json. Refused
    // while `settings` is still the outgoing kit's, like SET and PRESET.
    fun toggleKeySnap() {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return
        val next = !settings.keySnap
        engine.setKeySnap(next)
        persist(dir, settings.copy(keySnap = next))
    }

    // ECHO: the echo's time stepped round EchoTime's divisions - FREE,
    // then the note values - at the kit's tempo (no tempo runs at the
    // stand-in the modulators use). Remembered in surface.json, heard
    // within a control interval as a crossfade between the two times.
    // Refused while `settings` is still the outgoing kit's, like KEY.
    fun stepEchoTime() {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return
        val next = EchoTime.next(settings.echoTime)
        engine.setEchoTime(EchoTime.seconds(next, entry?.kit?.tempoBpm))
        persist(dir, settings.copy(echoTime = next))
    }

    // MOD ◄ ►: steps the picked field of the picked modulator - TARGET
    // and SHAPE cycle their lists, RATE walks Modulator.RATES and stops
    // at the ends, DEPTH moves a twentieth. Nothing is sent to the engine
    // here: the frame loop reads `settings.mods` every frame and sends the
    // offsets it works out, so a change is heard on the next frame.
    fun stepMod(delta: Int) {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return
        val slot = settings.mods[modSlot]
        val next = when (modField) {
            0 -> slot.copy(target = Modulator.Target.entries[(slot.target.ordinal + delta).mod(Modulator.Target.entries.size)])
            1 -> slot.copy(shape = Modulator.Shape.entries[(slot.shape.ordinal + delta).mod(Modulator.Shape.entries.size)])
            2 -> slot.copy(rateIndex = (slot.rateIndex + delta).coerceIn(Modulator.RATES.indices))
            else -> slot.copy(depth = (slot.depth + delta * MOD_DEPTH_STEP).coerceIn(0f, 1f))
        }
        val mods = settings.mods.toMutableList().also { it[modSlot] = next }
        persist(dir, settings.copy(mods = mods))
        // Stepping onto a shape that listens, with nothing to listen to:
        // the shape is kept and the route is said, RING's own discipline.
        if (next.shape.followsRoom && !slot.shape.followsRoom && !MicSessionService.armed.value) onToast(Copy.FOLLOW_NOT_LISTENING)
        // Same discipline for a shape that plays a gesture the kit has not got.
        if (next.shape.playsGesture && !slot.shape.playsGesture && settings.gesture == null) onToast(Copy.GESTURE_NONE)
    }

    // REC on the GESTURE row: arm (or disarm) a recording of BARS' own
    // length - FREE is one bar - that the next touch-down starts; the
    // frame loop feeds it and keeps it. A recording in flight runs its
    // length; REC does nothing to it. Refused while `settings` is still
    // the outgoing kit's, like SET and PRESET.
    fun armGesture() {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return
        if (recording) return
        if (gestureArmed) {
            gestureArmed = false
            return
        }
        gestureLength = PrintLength.BARS[barsIndex].let { if (it == 0) 1 else it }
        gestureArmed = true
        onToast(Copy.gestureArmed(gestureLength))
    }

    fun clearGesture() {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return
        persist(dir, settings.copy(gesture = null))
        onToast(Copy.GESTURE_CLEARED)
    }

    // The recorder ran its length: the kit keeps the gesture, and the
    // toast says how to hear it, since a gesture only plays through a
    // MOD slot.
    fun keepGesture(gesture: Gesture) {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return
        persist(dir, settings.copy(gesture = gesture))
        onToast(Copy.gestureKept(gesture.bars))
    }

    // PAD2 ◄ ►: same stepping, over the second source slot.
    fun stepPad2(delta: Int) {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return  // the outgoing kit's settings - see stepPad
        val pads = entry.kit.pads.sortedBy { it.slot }
        if (pads.isEmpty()) return
        val chosen = settings.secondPadSlot ?: padSlot2
        val at = pads.indexOfFirst { it.slot == chosen }.let { if (it < 0) 0 else it }
        val pad = pads[((at + delta) % pads.size + pads.size) % pads.size]
        persist(dir, settings.copy(secondPadSlot = pad.slot))
        val generation = ++pad2Generation
        scope.launch { loadPad2(dir, pad, generation) }
    }

    // PAD3 ◄ ►: same stepping, over the third source slot.
    fun stepPad3(delta: Int) {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return  // the outgoing kit's settings - see stepPad
        val pads = entry.kit.pads.sortedBy { it.slot }
        if (pads.isEmpty()) return
        val chosen = settings.thirdPadSlot ?: padSlot3
        val at = pads.indexOfFirst { it.slot == chosen }.let { if (it < 0) 0 else it }
        val pad = pads[((at + delta) % pads.size + pads.size) % pads.size]
        persist(dir, settings.copy(thirdPadSlot = pad.slot))
        val generation = ++pad3Generation
        scope.launch { loadPad3(dir, pad, generation) }
    }

    // PAD4 ◄ ►: same stepping, over the fourth source slot.
    fun stepPad4(delta: Int) {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return  // the outgoing kit's settings - see stepPad
        val pads = entry.kit.pads.sortedBy { it.slot }
        if (pads.isEmpty()) return
        val chosen = settings.fourthPadSlot ?: padSlot4
        val at = pads.indexOfFirst { it.slot == chosen }.let { if (it < 0) 0 else it }
        val pad = pads[((at + delta) % pads.size + pads.size) % pads.size]
        persist(dir, settings.copy(fourthPadSlot = pad.slot))
        val generation = ++pad4Generation
        scope.launch { loadPad4(dir, pad, generation) }
    }

    fun presetIndexFor(corner: Int): Int? = when (corner) {
        0 -> presetIndexA
        1 -> presetIndexB
        2 -> presetIndexC
        else -> presetIndexD
    }

    fun setPresetIndexFor(corner: Int, index: Int?) {
        when (corner) {
            0 -> presetIndexA = index
            1 -> presetIndexB = index
            2 -> presetIndexC = index
            else -> presetIndexD = index
        }
    }

    // SET A..D: the sound under the last touch becomes a morph corner.
    fun setCorner(index: Int) {
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return  // settings.corners is still the outgoing kit's - see settingsLoadedFor
        val held = loop.held ?: run {
            onToast(Copy.SURFACE_SET_NEEDS_TOUCH)
            return
        }
        val corner = SurfaceStore.Corner.from(mode, held, tilt.tilt, settings.corners)
        val corners = settings.corners.toMutableList().also { it[index] = corner }
        pushCorners(corners)
        persist(dir, settings.copy(corners = corners))
        // A captured corner is no longer whatever preset it may have been
        // stepped to before - clearing this corner's tracked position stops
        // the PRESET readout from going on claiming a name that no longer
        // matches what SET just wrote (Copilot review, PR #195).
        setPresetIndexFor(index, null)
        onToast(Copy.surfaceCornerSet('A' + index))
    }

    // PRESET ◄ ►: steps SurfaceStore.Corner.LIBRARY's named quartet onto
    // whichever corner ARM A..D last armed - see armedCorner's own
    // declaration for why arming is a separate step from SET A..D.
    fun stepPreset(delta: Int) {
        val corner = armedCorner ?: return
        val dir = entry?.dir ?: return
        if (settingsLoadedFor != dir) return  // settings.corners is still the outgoing kit's - see settingsLoadedFor
        val library = SurfaceStore.Corner.LIBRARY
        val at = presetIndexFor(corner)
        // A corner with no tracked position yet starts the stepper at
        // library's own ends - 0 stepping forward, the last entry stepping
        // back - rather than folding a sentinel through the same modulo
        // arithmetic an already-set index uses, which put the first ◄
        // press one short of the end (Copilot review, PR #195).
        val next = if (at == null) {
            if (delta >= 0) 0 else library.lastIndex
        } else {
            ((at + delta) % library.size + library.size) % library.size
        }
        setPresetIndexFor(corner, next)
        val corners = settings.corners.toMutableList().also { it[corner] = library[next].corner }
        pushCorners(corners)
        persist(dir, settings.copy(corners = corners))
    }

    // The print's landing, once: `finishing` guards the frame loop from
    // calling this again while the stop is in flight, and the stop itself
    // (a bounded wait for the callback's last write) runs off the main
    // thread so the pad never freezes on STOP PRINT.
    var finishing by remember { mutableStateOf(false) }
    fun landOnTape(snip: Snip) {
        scope.launch {
            val landed = withContext(Dispatchers.IO) {
                runCatching { SnipStore.import(snip, context.filesDir, System.currentTimeMillis()) }
            }
            landed.onSuccess {
                onToast(Copy.surfacePrinted(it.seconds))
                onPrinted()
            }.onFailure {
                Log.e("SurfaceScreen", "landOnTape: print lost", it)
                onToast(Copy.PRINT_LOST)
            }
        }
    }

    fun finishPrint() {
        if (finishing) return
        finishing = true
        printing = false
        scope.launch {
            try {
                val snip = withContext(Dispatchers.Default) { engine.stopPrint() }
                if (snip == null || snip.frameCount < engine.sampleRate / 10) {
                    onToast(Copy.SURFACE_NOTHING_PRINTED)
                    return@launch
                }
                if (printToPad && entry != null) {
                    // The chooser takes it from here (landOnPad, or the cancel path).
                    pendingPrint = snip
                } else {
                    landOnTape(snip)
                }
            } finally {
                finishing = false
            }
        }
    }

    // → PAD: through SYNTH's own door, so a print is a pad like any other -
    // bin-backed on a taken slot, named and coloured on an empty one.
    fun landOnPad(slot: Int) {
        val snip = pendingPrint ?: return
        val dir = entry?.dir ?: return
        if (landing) return
        landing = true
        scope.launch {
            try {
                val (existed, updated) = withContext(Dispatchers.IO) {
                    // KitWrites: this kit.json write must not race SET KEY/
                    // EVIL TWINS/IN KEY off the KIT screen or another pad's
                    // own commit — same open→mutate→save-under-one-lock
                    // shape as PadCaptureScreen's commitToPad/SynthScreen's
                    // sendToSlot.
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(dir)
                        val taken = model.pad(slot) != null
                        if (taken) {
                            model.replaceAudio(slot, null) { _ -> snip }
                        } else {
                            model.assign(slot, snip, DrumClass.UNKNOWN, "Surface Print")
                        }
                        model.save()
                        taken to model.kit
                    }
                }
                pendingPrint = null
                onKitUpdated(updated)
                onToast(Copy.surfacePrintedToPad(padLabel(slot), replaced = existed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // The chooser already dims a layered or chained pad (SYNTH's
                // rule), so this is the rarer refusal: the kit changed under
                // the chooser. In words, the print kept.
                onToast(Copy.PRINT_PAD_REFUSED)
            } catch (e: IllegalStateException) {
                onToast(Copy.PRINT_PAD_REFUSED)
            } catch (e: Exception) {
                // Disk or decode trouble - not a pad problem, so no "pick
                // another"; the print is still pending and TAPE is one
                // CANCEL away.
                Log.e("SurfaceScreen", "landOnPad: failed", e)
                onToast(Copy.PRINT_LANDING_FAILED)
            } finally {
                landing = false
            }
        }
    }

    // The kit's tempo as the frame loop below must see it: that loop is
    // keyed on `engine` alone and runs for the screen's whole life, so a
    // plain `entry` read inside it would be the kit open when the loop
    // started, for ever - a kit change would leave the modulators counting
    // bars at the old tempo. Read through state instead, live.
    val kitBpm by rememberUpdatedState(entry?.kit?.tempoBpm)
    // The frame loop below is one LaunchedEffect, so a local function it
    // calls is the one captured at first composition, with that
    // composition's `entry` - stale the moment the kit changes (or null,
    // if the screen opened without one), the same trap kitBpm dodges
    // above. Anything the loop calls that reads `entry` goes through
    // rememberUpdatedState, so the call lands on the current kit.
    val freezeOnBar by rememberUpdatedState({ freezeRing(onBar = true) })
    val keepGestureNow by rememberUpdatedState({ gesture: Gesture -> keepGesture(gesture) })
    val finishPrintNow by rememberUpdatedState({ finishPrint() })

    // Screen-rate loop: read the clock and the room, hand the frame to
    // `SurfaceLoop`, then do what only this side can - poll the engine,
    // mirror state for the rows, paint, send. The arithmetic between
    // (the smoother, the modulators' origin, EVERY BAR, the recorder,
    // LATCH) is the loop object's, tested in `:shell`.
    LaunchedEffect(engine) {
        var lastLatencyAt = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (now - lastLatencyAt >= StreamFacts.POLL_NANOS) {
                lastLatencyAt = now
                latency = if (engineUp) StreamFacts.latency(engine.latencyMillis(), engine.isShared()) else StreamFacts.NO_STREAM
            }
            val frame = loop.step(
                SurfaceLoop.Inputs(
                    nowNanos = now,
                    mode = mode,
                    target = target,
                    latched = latched,
                    mods = settings.mods,
                    gesture = settings.gesture,
                    bpm = kitBpm,
                    room = MicSessionService.level.value,
                    ringOnBar = ringOnBar,
                    recordBars = if (gestureArmed) gestureLength else null,
                ),
            )
            if (frame.freezeRing) freezeOnBar()
            frame.engineOffsets?.let { engine.setModulation(it) }
            if (frame.recordingStarted) {
                gestureArmed = false
                recording = true
                recordingBars = 0f
            }
            frame.recordingBars?.let { recordingBars = it }
            frame.keptGesture?.let { kept ->
                recording = false
                keepGestureNow(kept)
            }
            if (recording && !loop.recording) recording = false
            // Every frame, touch or none: tilt moves on its own, and this
            // is the only thing that keeps `tiltReading` fresh once the
            // puck itself stops moving. Compose's own equality check on
            // the `mutableStateOf` write skips the no-op case for free.
            tiltReading = tilt.tilt
            val play = frame.play
            painted = play
            // The sample blend reads the same position as the mode's own
            // macros, but independently - see the class doc on PAD2/PAD3/PAD4.
            val (wA, wB, wC, wD) = TouchSurface.sampleWeights(play.x, play.y)
            engine.control(mode, play, tilt.tilt, sampleA = wA, sampleB = wB, sampleC = wC, sampleD = wD, gate = (target.touching || latched) && padName != null)
            if (engine.needsRestart()) started(engine.start())
            if (printing && !finishing && engine.printState() == SurfaceEngine.PrintState.DONE) finishPrintNow()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (m in Mode.entries) {
                    ActionButton(
                        label = m.name,
                        scheme = scheme,
                        enabled = true,
                        dimmed = m != mode,
                        // Equal-sounding buttons to TalkBack otherwise
                        // (finding #21) - the same `selected` semantics the
                        // CUT bench's SegmentButtons already carry for their
                        // own mutually-exclusive row.
                        modifier = Modifier.weight(1f).semantics { selected = m == mode },
                    ) {
                        mode = m
                        target = Reading.REST // the frame loop snaps to it on the mode change
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // The print row, under the modes and there whatever panel is
            // showing: where the print lands, how long it runs, and PRINT
            // itself. Its own row of three, not the tail of the mode row:
            // seven buttons across a phone's width left every label past
            // XYZ cut to two letters and an ellipsis, so a tester read the
            // destination as "TA..." and never found → PAD. BARS sits here
            // with the print it sizes, not on VOICE beside LATCH.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(
                    label = if (printToPad) "→ PAD" else "→ TAPE",
                    scheme = scheme,
                    enabled = !printing && !finishing,
                    dimmed = true,
                    modifier = Modifier.weight(1f),
                ) { printToPad = !printToPad }
                // BARS needs a tempo; a kit without one prints free.
                val printBpm = entry?.kit?.tempoBpm
                ActionButton(
                    if (printBpm == null) "NO TEMPO" else PrintLength.label(PrintLength.BARS[barsIndex]),
                    scheme,
                    enabled = printBpm != null && !printing,
                    dimmed = barsIndex == 0,
                    modifier = Modifier.weight(1f),
                ) { barsIndex = (barsIndex + 1) % PrintLength.BARS.size }
                ActionButton(
                    label = if (printing) "STOP PRINT" else "PRINT",
                    scheme = scheme,
                    enabled = engineUp && padName != null && !finishing,
                    modifier = Modifier.weight(1.4f),
                ) {
                    if (printing) {
                        finishPrint()
                    } else {
                        val bars = PrintLength.BARS[barsIndex]
                        val bpm = entry?.kit?.tempoBpm
                        val seconds = if (bars > 0 && bpm != null) {
                            PrintLength.seconds(bars, bpm).coerceAtMost(SurfaceEngine.MAX_PRINT_SECONDS)
                        } else {
                            SurfaceEngine.MAX_PRINT_SECONDS
                        }
                        if (engine.armPrint(seconds)) {
                            printing = true
                            onToast(Copy.surfacePrintingStarted(bars, bpm?.toInt()))
                        } else {
                            onToast(Copy.SURFACE_STILL_LANDING)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // The panel strip. SURFACE's controls grew a row at a time until
            // a dozen sat above the pad and the pad was what was left; three
            // panels show one group at a time and give the pad its height
            // back. VOICE is what plays (the pad, the ring, the sample
            // triangle's other three); SHAPE is what the finger does to it
            // (the mode's knobs, the corners with what they are held to,
            // the presets); MOD is what moves on its own (the modulators,
            // the gesture). A pick, not a sound, so not persisted - like
            // grainKnob - and a mutually-exclusive row for TalkBack, like
            // the mode row above.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (p in Panel.entries) {
                    ActionButton(
                        p.name,
                        scheme,
                        enabled = true,
                        dimmed = p != panel,
                        modifier = Modifier.weight(1f).semantics { selected = p == panel },
                    ) { panel = p }
                }
            }

            if (panel == Panel.VOICE) {
                Spacer(Modifier.height(6.dp))

                // PAD ◄ name ►, RING and LATCH.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton("◄ PAD", scheme, enabled = padName != null) { stepPad(-1) }
                    TapeText(
                        padName?.let { if (ringVoice) it else "${padLabel(padSlot)} ${it.uppercase()}" } ?: "NO PAD",
                        TapeType.pixel,
                        scheme.ink.tape,
                        Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    ActionButton("PAD ►", scheme, enabled = padName != null) { stepPad(+1) }
                    // Live whenever a kit is open (a kit with no pads can still
                    // have a voice this way) - a tap with nothing listening says
                    // where to go rather than sitting disabled. Lit while the
                    // voice is the ring, the way LATCH is lit while it holds.
                    ActionButton(
                        "RING",
                        scheme,
                        enabled = entry != null,
                        dimmed = !ringVoice,
                        modifier = Modifier.semantics { selected = ringVoice },
                    ) { freezeRing() }
                    ActionButton("LATCH", scheme, enabled = padName != null, dimmed = !latched) { latched = !latched }
                }

                if (ringVoice || ringOnBar) {
                    Spacer(Modifier.height(6.dp))

                    // The ring's own row, only while the ring is the voice (the
                    // GRAIN row's pattern): EVERY BAR, and a readout that says
                    // which clock it is on. ONCE is the plain tap; EVERY BAR is
                    // lit while it runs, the way LATCH is.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton(
                            "EVERY BAR",
                            scheme,
                            enabled = entry != null,
                            dimmed = !ringOnBar,
                            modifier = Modifier.weight(1.2f).semantics { selected = ringOnBar },
                        ) { toggleRingOnBar() }
                        val ringBpm = entry?.kit?.tempoBpm
                        TapeText(
                            when {
                                !ringOnBar -> "RING ONCE, ON THE TAP"
                                ringBpm == null -> "RING AGAIN EVERY BAR AT THE DEFAULT TEMPO"
                                else -> "RING AGAIN EVERY BAR AT ${ringBpm.toInt()} BPM"
                            },
                            TapeType.pixel,
                            scheme.ink.tape,
                            Modifier.weight(2.4f).padding(horizontal = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // PAD2/PAD3/PAD4 ◄ name ►: the sample area's other three
                // vertices, blended in by the finger's own position - see the
                // class doc.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Unlike PAD's enabled = padName != null: a pad always
                    // auto-loads on entry, but slots 2/3/4 start empty and only
                    // these buttons ever fill them, so gating on entry alone
                    // (not padName2/3/4) is what lets the first press work at all.
                    ActionButton("◄ PAD2", scheme, enabled = entry != null, modifier = Modifier.weight(1f)) { stepPad2(-1) }
                    TapeText(
                        padName2?.let { "${padLabel(padSlot2)} ${it.uppercase()}" } ?: "NO PAD2",
                        TapeType.pixel,
                        scheme.ink.tape,
                        Modifier.weight(1.2f).padding(horizontal = 4.dp),
                    )
                    ActionButton("PAD2 ►", scheme, enabled = entry != null, modifier = Modifier.weight(1f)) { stepPad2(+1) }
                }

                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton("◄ PAD3", scheme, enabled = entry != null, modifier = Modifier.weight(1f)) { stepPad3(-1) }
                    TapeText(
                        padName3?.let { "${padLabel(padSlot3)} ${it.uppercase()}" } ?: "NO PAD3",
                        TapeType.pixel,
                        scheme.ink.tape,
                        Modifier.weight(1.2f).padding(horizontal = 4.dp),
                    )
                    ActionButton("PAD3 ►", scheme, enabled = entry != null, modifier = Modifier.weight(1f)) { stepPad3(+1) }
                }

                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton("◄ PAD4", scheme, enabled = entry != null, modifier = Modifier.weight(1f)) { stepPad4(-1) }
                    TapeText(
                        padName4?.let { "${padLabel(padSlot4)} ${it.uppercase()}" } ?: "NO PAD4",
                        TapeType.pixel,
                        scheme.ink.tape,
                        Modifier.weight(1.2f).padding(horizontal = 4.dp),
                    )
                    ActionButton("PAD4 ►", scheme, enabled = entry != null, modifier = Modifier.weight(1f)) { stepPad4(+1) }
                }
            }

            if (panel == Panel.SHAPE) {
                if (mode == Mode.GRAIN) {
                    Spacer(Modifier.height(6.dp))

                    // GRAIN's own row, only while the mode is on: the first
                    // button picks which knob ◄ ► step (it cycles SIZE →
                    // DENSITY → SPRAY, its label saying which), and the readout
                    // shows all three. As percentages, deliberately: what a
                    // percentage means in milliseconds or grains a second is
                    // Grain.h's arithmetic, and printing those units here would
                    // be a second copy of it that nothing keeps in step.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton(
                            GRAIN_KNOBS[grainKnob],
                            scheme,
                            enabled = padName != null,
                            modifier = Modifier.weight(1.1f),
                        ) { grainKnob = (grainKnob + 1) % GRAIN_KNOBS.size }
                        ActionButton("◄", scheme, enabled = padName != null) { stepGrain(-1) }
                        val g = settings.grain
                        TapeText(
                            "SIZE ${pct(g.size)}  DENS ${pct(g.density)}  SPRAY ${pct(g.spray)}",
                            TapeType.pixel,
                            scheme.ink.tape,
                            Modifier.weight(1.8f).padding(horizontal = 4.dp),
                        )
                        ActionButton("►", scheme, enabled = padName != null) { stepGrain(+1) }
                    }
                }

                if (mode != Mode.GRAIN) {
                    Spacer(Modifier.height(6.dp))

                    // SWARM's row, the GRAIN row's twin for the loop modes: the
                    // first button picks which knob ◄ ► step (VOICES or DETUNE),
                    // the readout shows both. DETUNE as a percentage: what it
                    // means in cents is SurfaceEngine.h's number, not a second
                    // copy here.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton(
                            SWARM_KNOBS[swarmKnob],
                            scheme,
                            enabled = padName != null,
                            modifier = Modifier.weight(1.1f),
                        ) { swarmKnob = (swarmKnob + 1) % SWARM_KNOBS.size }
                        ActionButton("◄", scheme, enabled = padName != null) { stepSwarm(-1) }
                        val w = settings.swarm
                        TapeText(
                            "SWARM ${w.voices} ${if (w.voices == 1) "VOICE" else "VOICES"}  DETUNE ${pct(w.detune)}",
                            TapeType.pixel,
                            scheme.ink.tape,
                            Modifier.weight(1.8f).padding(horizontal = 4.dp),
                        )
                        ActionButton("►", scheme, enabled = padName != null) { stepSwarm(+1) }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until 4) {
                        ActionButton(
                            "SET ${'A' + i}",
                            scheme,
                            enabled = padName != null,
                            dimmed = mode != Mode.MORPH && mode != Mode.VECTOR,
                            modifier = Modifier.weight(1f),
                        ) { setCorner(i) }
                    }
                    // KEY, on the row about what the sound under the finger
                    // is held to: lit while the loop's pitch snaps to the key
                    // (see toggleKeySnap), dimmed while it slides as recorded.
                    ActionButton(
                        "KEY",
                        scheme,
                        enabled = padName != null,
                        dimmed = !settings.keySnap,
                        modifier = Modifier.weight(1f).semantics { selected = settings.keySnap },
                    ) { toggleKeySnap() }
                    // ECHO, on the same row for the same reason: the echo's
                    // time held to the kit's bar (see stepEchoTime), lit and
                    // naming the note value while it is, dimmed while free.
                    val echoSynced = settings.echoTime != EchoTime.FREE_INDEX
                    ActionButton(
                        "ECHO ${EchoTime.label(settings.echoTime)}",
                        scheme,
                        enabled = padName != null,
                        dimmed = !echoSynced,
                        modifier = Modifier.weight(1.4f).semantics { selected = echoSynced },
                    ) { stepEchoTime() }
                }

                Spacer(Modifier.height(6.dp))

                // ARM A..D: picks which corner PRESET ◄ ► targets - see armedCorner.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until 4) {
                        ActionButton(
                            "ARM ${'A' + i}",
                            scheme,
                            enabled = padName != null,
                            dimmed = armedCorner != i,
                            // A mutually-exclusive row, same as the mode row
                            // above - TalkBack otherwise has no way to tell
                            // which corner is armed or when it changes
                            // (Copilot review, PR #195).
                            modifier = Modifier.weight(1f).semantics { selected = armedCorner == i },
                        ) { armedCorner = if (armedCorner == i) null else i }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // PRESET ◄ ►: design/surface-vector's LBP+/ECHO+/ECHO-/LBP-
                // idea (plus CRUSH+/-/GLITCH+/-/SPRING+/-), a named library
                // stepped onto the armed corner - see SurfaceStore.Corner.
                // LIBRARY and the class doc.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val armed = armedCorner
                    ActionButton("◄ PRESET", scheme, enabled = armed != null, modifier = Modifier.weight(1f)) { stepPreset(-1) }
                    TapeText(
                        armed?.let { c -> presetIndexFor(c)?.let { SurfaceStore.Corner.LIBRARY[it].name } ?: "◄ ► TO STEP" } ?: "ARM A CORNER",
                        TapeType.pixel,
                        scheme.ink.tape,
                        Modifier.weight(1.2f).padding(horizontal = 4.dp),
                    )
                    ActionButton("PRESET ►", scheme, enabled = armed != null, modifier = Modifier.weight(1f)) { stepPreset(+1) }
                }
            }

            if (panel == Panel.MOD) {
                Spacer(Modifier.height(6.dp))

                // MOD A/B: the first button picks the modulator, the second which
                // of its fields ◄ ► step, and the readout shows the picked slot
                // whole - TARGET SHAPE RATE DEPTH - so what is about to be
                // stepped is never a guess. A slot at 0% is listed, not hidden:
                // the row is how you find out it is there to turn up.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val slot = settings.mods[modSlot]
                    ActionButton(
                        "MOD ${'A' + modSlot}",
                        scheme,
                        enabled = padName != null,
                        modifier = Modifier.weight(1f).semantics { selected = slot.depth > 0f },
                    ) { modSlot = (modSlot + 1) % Modulator.SLOTS }
                    ActionButton(
                        MOD_FIELDS[modField],
                        scheme,
                        enabled = padName != null,
                        modifier = Modifier.weight(1.1f),
                    ) { modField = (modField + 1) % MOD_FIELDS.size }
                    ActionButton("◄", scheme, enabled = padName != null) { stepMod(-1) }
                    TapeText(
                        "${slot.target} ${slot.shape} ${
                            when {
                                slot.shape.followsRoom -> "ROOM"
                                slot.shape.playsGesture -> settings.gesture?.let { PrintLength.label(it.bars) } ?: "NONE"
                                else -> Modulator.rateLabel(slot.rateIndex)
                            }
                        } ${pct(slot.depth)}",
                        TapeType.pixel,
                        scheme.ink.tape,
                        Modifier.weight(1.6f).padding(horizontal = 4.dp),
                    )
                    ActionButton("►", scheme, enabled = padName != null) { stepMod(+1) }
                }

                // GESTURE's row, while a slot is on the shape, REC is armed or a
                // recording runs (the ring row's pattern): REC, what the kit has
                // or what is happening, CLEAR.
                if (recording || gestureArmed || settings.mods.any { it.shape.playsGesture }) {
                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton(
                            "REC",
                            scheme,
                            enabled = padName != null && !recording,
                            dimmed = !(gestureArmed || recording),
                            modifier = Modifier.weight(1f).semantics { selected = gestureArmed || recording },
                        ) { armGesture() }
                        val kept = settings.gesture
                        TapeText(
                            when {
                                recording -> "RECORDING %.1f OF %d BARS".format(java.util.Locale.ROOT, recordingBars.coerceIn(0f, gestureLength.toFloat()), gestureLength)
                                gestureArmed -> "ARMED. TOUCH THE PAD FOR ${PrintLength.label(gestureLength)}"
                                kept != null -> "GESTURE ${PrintLength.label(kept.bars)} · ${kept.points} POINTS"
                                else -> "NO GESTURE. REC, THEN TOUCH THE PAD"
                            },
                            TapeType.pixel,
                            scheme.ink.tape,
                            Modifier.weight(2.2f).padding(horizontal = 4.dp),
                        )
                        ActionButton("CLEAR", scheme, enabled = settings.gesture != null && !recording, modifier = Modifier.weight(1f)) { clearGesture() }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .lcdPanel(scheme)
                    // Canvas-drawn (the puck/crosshair/rings below), so
                    // invisible to the a11y tree by default (audit
                    // finding 4). Two/four-dimensional input has no
                    // single progressBarRangeInfo to map onto the way a
                    // 1D fader does, so this meets the finding's
                    // required floor — a descriptive label plus a live
                    // stateDescription — rather than the "ideally
                    // adjustable" ceiling; this composable already
                    // recomposes every frame while dragging (the readout
                    // TapeText below reads `painted` at composition
                    // scope), so reading it again here costs nothing new.
                    .semantics {
                        contentDescription = "TOUCH SURFACE, $mode MODE"
                        // Mirrors the visible `readout` TapeText below,
                        // corner-for-corner: MORPH used to report only X/Y
                        // here, the least of any mode's state, while its
                        // own on-screen readout already prints A/B/C/D and
                        // TILT (finding #21). VECTOR shares MORPH's exact
                        // corner blend (`TouchSurface.morphWeights`), so it
                        // gets the same A/B/C/D line.
                        stateDescription = buildString {
                            append("X %.2f  Y %.2f".format(java.util.Locale.ROOT, painted.x, painted.y))
                            if (mode == Mode.XYZ) append("  Z %.2f".format(java.util.Locale.ROOT, painted.z))
                            if (mode == Mode.MORPH || mode == Mode.VECTOR) append("  A %.2f B %.2f C %.2f D %.2f".format(java.util.Locale.ROOT, painted.a, painted.b, painted.c, painted.d))
                            // `tiltReading`, not `tilt.tilt` directly - see
                            // its own declaration for why (Copilot review).
                            if (tilt.available) append("  TILT %.2f".format(java.util.Locale.ROOT, tiltReading))
                        }
                    }
                    .pointerInput(mode) {
                        // Fingers in press order: the first down owns the puck,
                        // the second is the pinch. LinkedHashMap keeps that order
                        // across moves, so a second finger never steals the puck.
                        val down = LinkedHashMap<Long, TouchSurface.Touch>()
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    val id = change.id.value
                                    if (change.pressed) {
                                        down[id] = TouchSurface.Touch(id, change.position.x, change.position.y)
                                    } else {
                                        down.remove(id)
                                    }
                                    change.consume()
                                }
                                target = TouchSurface.read(
                                    mode,
                                    down.values.toList(),
                                    size.width.toFloat().coerceAtLeast(1f),
                                    size.height.toFloat().coerceAtLeast(1f),
                                    target,
                                )
                            }
                        }
                    },
            ) {
                val ink = scheme.lcdInk.tape
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // The grid: quarters, faint.
                    for (i in 1..3) {
                        val fx = w * i / 4f
                        val fy = h * i / 4f
                        drawLine(ink.copy(alpha = 0.18f), Offset(fx, 0f), Offset(fx, h), strokeWidth = 1f)
                        drawLine(ink.copy(alpha = 0.18f), Offset(0f, fy), Offset(w, fy), strokeWidth = 1f)
                    }
                    // The sample area: apex top-centre (PAD), base-left
                    // (PAD2), base-right (PAD3), base-mid (PAD4) directly
                    // under the apex - drawn in every mode, since the blend
                    // it represents runs off the same touch position
                    // independently of whatever the mode's own macros are
                    // doing with it. The apex-to-base-mid line marks the
                    // seam TouchSurface.sampleWeights splits the two
                    // half-triangles on. The puck below doubles as its own
                    // live indicator: it sits at the exact position
                    // TouchSurface.sampleWeights reads, so there is no
                    // second dot to keep in sync.
                    val apex = Offset(w * 0.5f, 0f)
                    val baseLeft = Offset(0f, h)
                    val baseRight = Offset(w, h)
                    val baseMid = Offset(w * 0.5f, h)
                    val triangleInk = ink.copy(alpha = 0.3f)
                    drawLine(triangleInk, apex, baseLeft, strokeWidth = 1f)
                    drawLine(triangleInk, baseLeft, baseRight, strokeWidth = 1f)
                    drawLine(triangleInk, baseRight, apex, strokeWidth = 1f)
                    drawLine(triangleInk, apex, baseMid, strokeWidth = 1f)

                    val px = painted.x * w
                    val py = (1f - painted.y) * h
                    // The crosshair, brighter when a finger holds it.
                    val cross = ink.copy(alpha = if (painted.touching) 0.8f else 0.35f)
                    drawLine(cross, Offset(px, 0f), Offset(px, h), strokeWidth = 1f)
                    drawLine(cross, Offset(0f, py), Offset(w, py), strokeWidth = 1f)
                    // The puck.
                    drawCircle(ink, radius = 9.dp.toPx(), center = Offset(px, py))
                    // XYZ: the pinch ring, its radius the depth.
                    if (mode == Mode.XYZ) {
                        val r = 9.dp.toPx() + painted.z * (minOf(w, h) / 2f - 9.dp.toPx())
                        drawCircle(ink.copy(alpha = 0.6f), radius = r, center = Offset(px, py), style = Stroke(2.dp.toPx()))
                    }
                    // MORPH/VECTOR: a bar per corner, its length the weight.
                    if (mode == Mode.MORPH || mode == Mode.VECTOR) {
                        val bar = 6.dp.toPx()
                        val len = minOf(w, h) * 0.3f
                        drawRect(ink.copy(alpha = 0.7f), Offset(0f, 0f), Size(len * painted.a, bar))
                        drawRect(ink.copy(alpha = 0.7f), Offset(w - len * painted.b, 0f), Size(len * painted.b, bar))
                        drawRect(ink.copy(alpha = 0.7f), Offset(0f, h - bar), Size(len * painted.c, bar))
                        drawRect(ink.copy(alpha = 0.7f), Offset(w - len * painted.d, h - bar), Size(len * painted.d, bar))
                    }
                }
                if (mode == Mode.MORPH || mode == Mode.VECTOR) {
                    TapeText("A", TapeType.lcdSmall, ink, Modifier.align(Alignment.TopStart).padding(8.dp))
                    TapeText("B", TapeType.lcdSmall, ink, Modifier.align(Alignment.TopEnd).padding(8.dp))
                    TapeText("C", TapeType.lcdSmall, ink, Modifier.align(Alignment.BottomStart).padding(8.dp))
                    TapeText("D", TapeType.lcdSmall, ink, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                if (padName == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TapeText(Copy.SURFACE_NEEDS_KIT, TapeType.lcdSmall, scheme.amber.tape)
                    }
                }
                if (printing) {
                    TapeText("● PRINTING", TapeType.lcdSmall, scheme.amber.tape, Modifier.align(Alignment.TopCenter).padding(8.dp))
                }
            }

            Spacer(Modifier.height(6.dp))

            val readout = buildString {
                // Latency leads: the line can outrun a narrow screen, and
                // during a bench it is the part worth keeping.
                append(latency).append("  ·  ")
                append("X %.2f  Y %.2f".format(java.util.Locale.ROOT, painted.x, painted.y))
                if (mode == Mode.XYZ) append("  Z %.2f".format(java.util.Locale.ROOT, painted.z))
                if (mode == Mode.MORPH || mode == Mode.VECTOR) append("  A %.2f B %.2f C %.2f D %.2f".format(java.util.Locale.ROOT, painted.a, painted.b, painted.c, painted.d))
                // Only worth a line once there is a second, third or fourth
                // source actually in the blend - with just PAD loaded the
                // weights are trivially (1, 0, 0, 0) and say nothing new.
                if (padName2 != null || padName3 != null || padName4 != null) {
                    val (wA, wB, wC, wD) = TouchSurface.sampleWeights(painted.x, painted.y)
                    append("  SMPL %.2f/%.2f/%.2f/%.2f".format(java.util.Locale.ROOT, wA, wB, wC, wD))
                }
                // GRAIN: the key the pitch axis snaps to - the kit's, or
                // none, in which case the axis is a semitone ladder. The
                // note actually landed on is the engine's to know
                // (Grain.h); this line names the rule, not a second copy
                // of its answer.
                if (mode == Mode.GRAIN || settings.keySnap) append("  KEY ").append(entry?.kit?.key?.label?.uppercase() ?: "NONE")
                // `tiltReading`, not `tilt.tilt` directly - see its own
                // declaration for why (Copilot review): this line is only
                // ever redrawn when something the enclosing recomposition
                // reads changes, and a still puck stops giving it a reason
                // to without tilt mirrored into state of its own.
                if (tilt.available) append("  TILT %.2f".format(java.util.Locale.ROOT, tiltReading))
                padName?.let { append("  ·  ").append(it.uppercase()) }
            }
            // MORPH's six numbers plus TILT and the pad name run well past
            // 390dp on one line - a design-canvas board caught it clipping
            // mid-digit. Latency still leads (see above), so a second line
            // is spare capacity, not a redesign: it covers every case but
            // the rare worst one (VECTOR, now the longest possible line -
            // A/B/C/D and SMPL both at once - plus tilt, a shared stream
            // and a long pad name), which would need a restructure, not a
            // parameter.
            TapeText(readout, TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.fillMaxWidth(), maxLines = 2)
        }

        if (pendingPrint != null) {
            // Cancelling is not losing the print: it goes to TAPE instead.
            val cancelChooser = {
                if (!landing) {
                    pendingPrint?.let { landOnTape(it) }
                    pendingPrint = null
                }
            }
            SlotChooserOverlay(
                kit = entry?.kit,
                previewColor = scheme.amber.tape,
                scheme = scheme,
                busy = landing,
                onPick = ::landOnPad,
                onCancel = cancelChooser,
            )
            // Innermost: same self-guarded cancel as the overlay's own
            // CANCEL — a landing print in flight makes both no-ops.
            BackHandler(onBack = cancelChooser)
        }
    }
}

/**
 * A slot as the MPC names it, over [PadBanks] - nullable here because the
 * surface has no pad chosen until one is.
 */
private fun padLabel(slot: Int?): String = slot?.let { PadBanks.tag(it) } ?: ""
