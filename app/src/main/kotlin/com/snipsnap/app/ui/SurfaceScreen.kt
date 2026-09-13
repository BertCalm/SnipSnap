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
import com.snipsnap.app.SurfaceEngine
import com.snipsnap.app.TiltSource
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PrintLength
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.StreamFacts
import com.snipsnap.shell.SurfaceStore
import com.snipsnap.shell.TouchSurface
import com.snipsnap.shell.TouchSurface.Mode
import com.snipsnap.shell.TouchSurface.Reading
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SURFACE — the tactile pad. The open kit's first pad loops under a
 * finger; where the finger is drives the macros (`TouchSurface` in
 * `:shell` does the arithmetic, `SurfaceEngine` the sound), and PRINT
 * writes the performance to TAPE as a new sample the way an SP-404
 * resamples: what you played is now one pad, no DSP to run later.
 *
 * Four modes, one pad. The phone's tilt always feeds resonance
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
 *
 * PAD ◄ ► picks which of the kit's pads the surface plays; SET A..D
 * captures the sound under the last touch as a morph corner (MORPH and
 * VECTOR alike - it is the same corner blend). Both live in
 * `surface.json` beside the kit (`SurfaceStore`), so a morph you set up
 * is there when you come back.
 *
 * ARM A..D picks a corner without touching the pad at all; PRESET ◄ ►
 * then steps `SurfaceStore.Corner.LIBRARY`'s named quartet - LBP +/ECHO
 * +/ECHO -/LBP -, `design/surface-vector`'s own idea - onto whichever
 * corner is armed. It writes the very same `corners` SET A..D does, just
 * from a curated library instead of a live capture, so the two are
 * interchangeable afterwards: a stepped corner can be re-captured by
 * touch, and a captured one overwritten by stepping.
 *
 * PAD2/PAD3/PAD4 ◄ ► load three more voices onto the pad's sample area -
 * PAD at the apex, PAD2 at the base-left, PAD3 at the base-right, PAD4 at
 * the base-mid, directly under the apex (`TouchSurface.sampleWeights`,
 * `design/surface-vector`'s boards) - and the finger blends all four at
 * once, continuously, with no dead zone: the same position that drives
 * the mode's own macros drives this too, independently. A slot nobody
 * has loaded is just silence at its vertex, not a hole in the pad.
 *
 * LATCH keeps the loop sounding where the finger left it, so one hand can
 * set corners while the other is free; BARS locks a print to a whole
 * number of bars at the kit's tempo, so it drops onto the groove grid.
 *
 * A print goes → TAPE (the deck's shelf) or → PAD: the SP-404 move
 * proper, the performance landing on a pad of the kit you are holding
 * through the same door SYNTH's SEND TO PAD uses (`assign` on an empty
 * slot, `replaceAudio` on a taken one, the original in the bin).
 *
 * Polling: every pointer event updates the *target* reading; a frame
 * loop steps a screen-rate smoother toward it, paints the puck from the
 * smoothed value and sends that same value to the engine, which glides
 * again per sample. Two smoothers, two rates, one equation.
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
    var lastHeld by remember { mutableStateOf<Reading?>(null) }
    var printing by remember { mutableStateOf(false) }
    var printToPad by remember { mutableStateOf(false) }
    var latched by remember { mutableStateOf(false) }
    /** Index into PrintLength.BARS; 0 = FREE. */
    var barsIndex by remember { mutableStateOf(0) }
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
        lastHeld = null
        latched = false
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

    // The voice: one of the kit's pads, read off the shelf, folded to mono in the engine.
    suspend fun loadPad(dir: File, pad: KitPad) {
        val snip = withContext(Dispatchers.IO) {
            // pad.sampleFile is a kit pad sample, produced only by KitBuilderModel.assign
            // from an already-bounded Snip — readCapped's 600s ceiling is defense in
            // depth, not expected to ever bind.
            runCatching { WavReader.readCapped(File(dir, pad.sampleFile), TAPE_LOAD_MAX_SEC).snip }.getOrNull()
        }
        if (snip == null) {
            padName = null
            onToast(Copy.sourceUnreadable(pad.displayName))
        } else {
            engine.load(snip)
            padName = pad.displayName
            padSlot = pad.slot
        }
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
        corners.forEachIndexed { i, c -> engine.setCorner(i, c.pitch, c.cutoff, c.resonance, c.drive) }
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
        if (entry == null) {
            padName = null
            padSlot = null
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
            pad2Generation++
            pad3Generation++
            pad4Generation++
            armedCorner = null
            presetIndexA = null
            presetIndexB = null
            presetIndexC = null
            presetIndexD = null
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
        // A fresh kit's corners are whatever surface.json says, not last
        // kit's preset steps - stale presetIndex/armedCorner state would
        // otherwise claim one of this kit's corners is some named preset
        // it was never actually stepped onto here.
        armedCorner = null
        presetIndexA = null
        presetIndexB = null
        presetIndexC = null
        presetIndexD = null
        val pads = entry.kit.pads.sortedBy { it.slot }
        val pad = pads.firstOrNull { it.slot == settings.padSlot } ?: pads.firstOrNull()
        if (pad == null) {
            padName = null
            padSlot = null
        } else {
            loadPad(entry.dir, pad)
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
        val pads = entry.kit.pads.sortedBy { it.slot }
        if (pads.isEmpty()) return
        val chosen = settings.padSlot ?: padSlot
        val at = pads.indexOfFirst { it.slot == chosen }.let { if (it < 0) 0 else it }
        val pad = pads[((at + delta) % pads.size + pads.size) % pads.size]
        persist(dir, settings.copy(padSlot = pad.slot))
        scope.launch { loadPad(dir, pad) }
    }

    // PAD2 ◄ ►: same stepping, over the second source slot.
    fun stepPad2(delta: Int) {
        val dir = entry?.dir ?: return
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
        val pads = entry.kit.pads.sortedBy { it.slot }
        if (pads.isEmpty()) return
        val chosen = settings.fourthPadSlot ?: padSlot4
        val at = pads.indexOfFirst { it.slot == chosen }.let { if (it < 0) 0 else it }
        val pad = pads[((at + delta) % pads.size + pads.size) % pads.size]
        persist(dir, settings.copy(fourthPadSlot = pad.slot))
        val generation = ++pad4Generation
        scope.launch { loadPad4(dir, pad, generation) }
    }

    // SET A..D: the sound under the last touch becomes a morph corner.
    fun setCorner(index: Int) {
        val dir = entry?.dir ?: return
        val held = lastHeld ?: run {
            onToast(Copy.SURFACE_SET_NEEDS_TOUCH)
            return
        }
        val corner = SurfaceStore.Corner.from(mode, held, tilt.tilt, settings.corners)
        val corners = settings.corners.toMutableList().also { it[index] = corner }
        pushCorners(corners)
        persist(dir, settings.copy(corners = corners))
        onToast(Copy.surfaceCornerSet('A' + index))
    }

    fun presetIndexFor(corner: Int): Int? = when (corner) {
        0 -> presetIndexA
        1 -> presetIndexB
        2 -> presetIndexC
        else -> presetIndexD
    }

    fun setPresetIndexFor(corner: Int, index: Int) {
        when (corner) {
            0 -> presetIndexA = index
            1 -> presetIndexB = index
            2 -> presetIndexC = index
            else -> presetIndexD = index
        }
    }

    // PRESET ◄ ►: steps SurfaceStore.Corner.LIBRARY's named quartet onto
    // whichever corner ARM A..D last armed - see armedCorner's own
    // declaration for why arming is a separate step from SET A..D.
    fun stepPreset(delta: Int) {
        val corner = armedCorner ?: return
        val dir = entry?.dir ?: return
        val library = SurfaceStore.Corner.LIBRARY
        val at = presetIndexFor(corner) ?: -1
        val next = ((at + delta) % library.size + library.size) % library.size
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

    // Screen-rate loop: smooth toward the target, paint, feed the engine.
    LaunchedEffect(engine) {
        val smoother = TouchSurface.SmoothedReading(TouchSurface.Smoother.coefficient(cutoffHz = 12f, rateHz = 60f))
        var lastMode = mode
        var lastLatencyAt = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (now - lastLatencyAt >= StreamFacts.POLL_NANOS) {
                lastLatencyAt = now
                latency = if (engineUp) StreamFacts.latency(engine.latencyMillis(), engine.isShared()) else StreamFacts.NO_STREAM
            }
            // Every frame, touch or none: tilt moves on its own, and this
            // is the only thing that keeps `tiltReading` fresh once the
            // puck itself stops moving. Compose's own equality check on
            // the `mutableStateOf` write skips the no-op case for free.
            tiltReading = tilt.tilt
            if (mode != lastMode) {
                // A mode change is a different instrument, not a glide
                // between two: the painted puck and the engine both jump.
                smoother.snap(target)
                lastMode = mode
                // lastHeld's a/b/c/d were smoothed under the *old* mode -
                // XY/XYZ never compute morph weights at all (TouchSurface.
                // read leaves them at a flat 0.25 each), so carrying it
                // into MORPH/VECTOR's SET A..D would capture that flat
                // blend, not a corner, no matter where the finger was.
                // Clearing it means SET asks for a fresh touch instead of
                // capturing a reading that doesn't belong to this mode.
                lastHeld = null
            }
            val smooth = smoother.step(target)
            if (target.touching) lastHeld = smooth
            // Latched with no finger down: the sound stays where the finger
            // left it, and so does the puck.
            val held = lastHeld
            val play = if (latched && !target.touching && held != null) held else smooth
            painted = play
            // The sample blend reads the same position as the mode's own
            // macros, but independently - see the class doc on PAD2/PAD3/PAD4.
            val (wA, wB, wC, wD) = TouchSurface.sampleWeights(play.x, play.y)
            engine.control(mode, play, tilt.tilt, sampleA = wA, sampleB = wB, sampleC = wC, sampleD = wD, gate = (target.touching || latched) && padName != null)
            if (engine.needsRestart()) started(engine.start())
            if (printing && !finishing && engine.printState() == SurfaceEngine.PrintState.DONE) finishPrint()
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
                ActionButton(
                    label = if (printToPad) "→ PAD" else "→ TAPE",
                    scheme = scheme,
                    enabled = !printing && !finishing,
                    dimmed = true,
                    modifier = Modifier.weight(1.1f),
                ) { printToPad = !printToPad }
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

            // PAD ◄ name ► and the four corner captures.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton("◄ PAD", scheme, enabled = padName != null) { stepPad(-1) }
                TapeText(
                    padName?.let { "${padLabel(padSlot)} ${it.uppercase()}" } ?: "NO PAD",
                    TapeType.pixel,
                    scheme.ink.tape,
                    Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                ActionButton("PAD ►", scheme, enabled = padName != null) { stepPad(+1) }
                ActionButton("LATCH", scheme, enabled = padName != null, dimmed = !latched) { latched = !latched }
                // BARS needs a tempo; a kit without one prints free.
                val bpm = entry?.kit?.tempoBpm
                ActionButton(
                    if (bpm == null) "NO TEMPO" else PrintLength.label(PrintLength.BARS[barsIndex]),
                    scheme,
                    enabled = bpm != null && !printing,
                    dimmed = barsIndex == 0,
                ) { barsIndex = (barsIndex + 1) % PrintLength.BARS.size }
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
                        modifier = Modifier.weight(1f),
                    ) { armedCorner = if (armedCorner == i) null else i }
                }
            }

            Spacer(Modifier.height(6.dp))

            // PRESET ◄ ►: design/surface-vector's LBP+/ECHO+/ECHO-/LBP-
            // idea, a named library stepped onto the armed corner - see
            // SurfaceStore.Corner.LIBRARY and the class doc.
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
