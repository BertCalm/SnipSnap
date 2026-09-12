package com.snipsnap.app.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.AudioFocus
import com.snipsnap.app.AudioVoice
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.PadEngine
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Separate
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layers
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.SnipStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SPLIT — one sound on three faders.
 *
 * `Separate.stn` (`:audio`, and what `snipsnap dissect` already uses)
 * pulls a pad apart into its **sines** (the body), its **transient**
 * (the attack) and its **air** (the noise between) — three fuzzy masks
 * that sum to one, so the three parts sum back to the sound. This is
 * that split with a desk in front of it: a fader, a REVERSE, a mute and
 * a solo each, playing as one loop, and PRINT to keep what you found.
 *
 * The parts are the point. Sines backwards under a forward transient is
 * a sound you cannot get from a plugin chain, because both halves have
 * to come out of the same take.
 *
 * How the pieces divide:
 *  - **The maths** is `Separate.stn`, untouched, on a background
 *    coroutine — it is a spectrogram and two median filters, seconds of
 *    work, never the main thread.
 *  - **The desk** is `Layers` (`:shell`, tested): gains, solo, mute, and
 *    the exact offline render. At rest it is transparent — three faders
 *    up and the render *is* the source, sample for sample, which is the
 *    test that lets a print through it be trusted.
 *  - **The sound** is one `PadEngine` bank of three, started as one group
 *    (`hitLayers`, so the layers cannot start a buffer apart) and looping.
 *    A fader glides through `setGain` on the sounding voice rather than
 *    retriggering it.
 *  - **PRINT** needs no capture at all. The mix is completely determined
 *    by the three buffers and the desk, so it is `Layers.render` —
 *    the same every time, and provably the sound itself when nothing is
 *    touched.
 */
@Composable
fun SplitScreen(
    entry: KitShelf.Entry?,
    onToast: (String) -> Unit,
    /** The print landed on TAPE; the deck should re-read its shelf. */
    onPrinted: () -> Unit,
    /** The print landed on a pad: the kit changed on disk. */
    onKitUpdated: (Kit) -> Unit,
    /** ◄ KIT — SPLIT is reached from the kit's action row and leaves the same way. */
    onExit: () -> Unit,
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engine = remember { PadEngine(deviceSampleRate(context)) }
    var engineUp by remember { mutableStateOf(false) }

    /** Which pad is being taken apart. */
    var sourceSlot by remember(entry?.dir) { mutableStateOf<Int?>(null) }
    var sourceName by remember(entry?.dir) { mutableStateOf<String?>(null) }

    /** The split itself, and where its three parts sit in the bank. */
    var split by remember(entry?.dir) { mutableStateOf<Separate.Stn?>(null) }
    var banked by remember(entry?.dir) { mutableStateOf<List<Int>>(emptyList()) }
    var splitFrames by remember(entry?.dir) { mutableIntStateOf(0) }
    var splitSeconds by remember(entry?.dir) { mutableStateOf(0f) }

    var desk by remember(entry?.dir) { mutableStateOf(Layers.Desk()) }
    var working by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var printToPad by remember { mutableStateOf(false) }
    var pendingPrint by remember { mutableStateOf<Snip?>(null) }
    var landing by remember { mutableStateOf(false) }

    // Mirrors the ◄ KIT chip below (`enabled = !working`) exactly: always
    // registered so Back can't fall through to a broader "go to shelf"
    // policy — or, worse, to Activity.finish() — mid-operation, but a no-op
    // while `working`, the same as the disabled chip.
    //
    // That closes BACK specifically, not the screen: MenuRow renders above
    // this content unconditionally, so tapping any tab still unmounts SPLIT
    // mid-render. That's deliberate — swallowing Back must never become a
    // trap — but it does mean "no way to leave while working" would be false
    // if said of the screen as a whole.
    BackHandler { if (!working) onExit() }

    DisposableEffect(engine) {
        engineUp = engine.start()
        if (!engineUp) onToast(Copy.noLowLatencyStream("SPLIT"))
        onDispose { engine.close() }
    }

    // The three voices are ours alone — SPLIT owns this engine, so no
    // allocator is needed and the ids can simply be 1, 2, 3 in part order.
    fun voiceId(part: Layers.Part): Int = part.ordinal + 1

    fun layersNow(): List<PadEngine.Layer> = Layers.Part.entries.mapIndexedNotNull { i, part ->
        val sample = banked.getOrNull(i) ?: return@mapIndexedNotNull null
        val gain = desk.gain(part)
        PadEngine.Layer(
            voiceId = voiceId(part),
            sample = sample,
            gainLeft = gain,
            gainRight = gain,
            reverse = desk.strip(part).reverse,
        )
    }

    fun stopAll() {
        playing = false
        engine.allOff()
    }

    // Audit correction: SPLIT holds a looping three-voice group (the desk's
    // PLAY ▸) exactly like PLAY/KIT/GROOVE do, but had no ON_STOP handler —
    // backgrounding the app left it playing. Same lesson, same shape: ON_STOP
    // means stopAll(), and a focus loss (a call, another app's audio) asks
    // for the same silence, so the same AudioFocus registration rides this
    // effect — see PlayScreen's own copy of this pattern for the full
    // reasoning.
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioVoice = remember(engine) { object : AudioVoice { override fun silence() = stopAll() } }
    DisposableEffect(lifecycleOwner, engine) {
        AudioFocus.acquire(audioVoice)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stopAll()
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

    /**
     * Start the three together, looping. `loopStart = 0` over the whole
     * part means each voice sustains until it is stopped — forwards from
     * the first frame, or backwards from the last, whichever its strip
     * says.
     */
    fun startAll() {
        if (!engineUp || banked.size < Layers.Part.entries.size || splitFrames <= 1) return
        if (desk.silent) {
            onToast(Copy.SPLIT_ALL_FADERS_DOWN)
            return
        }
        val started = engine.hitLayers(
            layers = layersNow(),
            startFrame = 0L,
            endFrame = splitFrames.toLong(),
            loopStart = 0L,
            pitch = 1.0,
        )
        playing = started
        if (!started) onToast(Copy.SPLIT_START_FAILED)
    }

    /** A fader moved: glide the sounding voice rather than retrigger it. */
    fun fade(part: Layers.Part) {
        if (playing) {
            val gain = desk.gain(part)
            engine.setGain(voiceId(part), gain, gain)
        }
    }

    /** Every strip's gain at once — what a mute or a solo changes. */
    fun fadeAll() {
        if (!playing) return
        for (part in Layers.Part.entries) fade(part)
    }

    /**
     * REVERSE is the one control a sounding voice cannot take: direction
     * is fixed when a voice starts. Flipping it restarts the group, which
     * is honest — a reverse is a deliberate act, not a drag.
     */
    fun flipReverse(part: Layers.Part) {
        desk = desk.with(part) { it.copy(reverse = !it.reverse) }
        if (playing) {
            engine.allOff()
            startAll()
        }
    }

    suspend fun loadSource(dir: File, slot: Int, name: String) {
        working = true
        try {
            val pad = entry?.kit?.pad(slot)
            val file = pad?.sampleFile
            if (file == null) {
                onToast(Copy.SPLIT_PAD_EMPTY)
                return
            }
            // `runCatching` would take a CancellationException along with the
            // real failures, and this screen can be left mid-split: the work
            // would be cancelled and the code would carry on toasting and
            // banking anyway. Cancellation travels; only failures become words.
            val source = try {
                // `file` is a kit pad sample, produced only by KitBuilderModel.assign
                // from an already-bounded Snip — readCapped's 600s ceiling is defense
                // in depth, not expected to ever bind.
                withContext(Dispatchers.IO) { WavReader.readCapped(File(dir, file), TAPE_LOAD_MAX_SEC).snip }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (source == null) {
                onToast(Copy.sourceUnreadable(name))
                return
            }
            // The expensive part: a spectrogram and two median filters.
            val parts = try {
                withContext(Dispatchers.Default) { Separate.stn(source) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SplitScreen", "loadSource: split refused", e)
                onToast(Copy.SPLIT_REFUSED)
                return
            }
            val indices = withContext(Dispatchers.Default) {
                engine.loadSnips(listOf(parts.sines, parts.transients, parts.noise))
            }
            split = parts
            banked = indices
            splitFrames = parts.sines.frameCount
            splitSeconds = parts.sines.durationSeconds
            desk = Layers.Desk()
            sourceSlot = slot
            sourceName = name
        } finally {
            working = false
        }
    }

    // The source pad, and PAD ◄ ►: the next pad by slot, wrapping.
    fun stepPad(delta: Int) {
        val dir = entry?.dir ?: return
        val pads = entry.kit.pads.sortedBy { it.slot }
        if (pads.isEmpty()) return
        val at = pads.indexOfFirst { it.slot == sourceSlot }.let { if (it < 0) 0 else it }
        val pad = pads[((at + delta) % pads.size + pads.size) % pads.size]
        stopAll()
        split = null
        banked = emptyList()
        sourceSlot = pad.slot
        sourceName = pad.displayName
    }

    LaunchedEffect(entry?.dir) {
        val pad = entry?.kit?.pads?.minByOrNull { it.slot }
        sourceSlot = pad?.slot
        sourceName = pad?.displayName
    }

    /**
     * [hot] rides along rather than being said in its own toast: the
     * landing's toast arrives a beat later and would wipe a separate
     * warning off the screen before it had been read.
     */
    fun landOnTape(snip: Snip, hot: Float = 0f) {
        scope.launch {
            val landed = try {
                withContext(Dispatchers.IO) { SnipStore.import(snip, context.filesDir, System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e  // leaving the screen is not a lost print to report
            } catch (e: Exception) {
                Log.e("SplitScreen", "landOnTape: print lost", e)
                onToast(Copy.PRINT_LOST)
                return@launch
            }
            onToast(Copy.splitPrinted(landed.seconds, hot.takeIf { it > 1f }))
            onPrinted()
        }
    }

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
                        if (taken) model.replaceAudio(slot, null) { _ -> snip }
                        else model.assign(slot, snip, DrumClass.UNKNOWN, "Split Print")
                        model.save()
                        taken to model.kit
                    }
                }
                pendingPrint = null
                onKitUpdated(updated)
                onToast(
                    if (existed) "PAD ${splitPadLabel(slot)} REPLACED WITH THE SPLIT. ORIGINAL SLEEPS IN THE BIN."
                    else "SPLIT PRINTED TO PAD ${splitPadLabel(slot)}.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                onToast(Copy.PRINT_PAD_REFUSED)
            } catch (e: IllegalStateException) {
                onToast(Copy.PRINT_PAD_REFUSED)
            } catch (e: Exception) {
                Log.e("SplitScreen", "landOnPad: failed", e)
                onToast(Copy.PRINT_LANDING_FAILED)
            } finally {
                landing = false
            }
        }
    }

    /**
     * PRINT: the desk rendered exactly, with no capture and no waiting.
     * A mix that came out over full scale is said aloud rather than
     * quietly turned down — the faders are yours, and the number is a
     * fact about what you asked for.
     */
    fun printMix() {
        val parts = split ?: return
        if (desk.silent) {
            onToast(Copy.SPLIT_NOTHING_TO_PRINT)
            return
        }
        working = true
        scope.launch {
            try {
                val mixed = withContext(Dispatchers.Default) { Layers.render(parts, desk) }
                val peak = mixed.peak()
                if (printToPad && entry != null) {
                    // The chooser is about to take over and says nothing of
                    // its own, so a warning here survives to be read.
                    if (peak > 1f) onToast(Copy.splitMixHot(peak))
                    pendingPrint = mixed
                } else {
                    landOnTape(mixed, peak)
                }
            } finally {
                working = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- the LCD: whose pad, and what state the split is in ----
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.LCD_HEADER_MAX_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TapeText(
                (sourceName ?: "NO PAD").uppercase(),
                TapeType.lcdHeader,
                scheme.lcdInk.tape,
                Modifier.padding(end = 8.dp),
            )
            TapeText(
                when {
                    working -> "WORKING"
                    split == null -> "NOT SPLIT"
                    playing -> "PLAYING"
                    else -> "SPLIT · ${"%.1f".format(java.util.Locale.ROOT, splitSeconds)} S"
                },
                TapeType.lcdSmall,
                scheme.amber.tape,
            )
        }

        // ---- the source, and the split itself ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("◄ KIT", scheme, enabled = !working, modifier = Modifier.weight(1f)) { onExit() }
            ActionButton("PAD ◄", scheme, enabled = !working && entry != null, modifier = Modifier.weight(1f)) { stepPad(-1) }
            ActionButton("PAD ►", scheme, enabled = !working && entry != null, modifier = Modifier.weight(1f)) { stepPad(1) }
        }
        ActionButton(
            if (working) "WORKING…" else if (split == null) "SPLIT ▸ TAKE THIS PAD APART" else "SPLIT ▸ AGAIN",
            scheme,
            enabled = !working && entry != null && sourceSlot != null,
            lit = split == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (working) return@ActionButton  // the flag is set inside the coroutine; a fast second tap beats it
            val dir = entry?.dir ?: return@ActionButton
            val slot = sourceSlot ?: return@ActionButton
            stopAll()
            scope.launch { loadSource(dir, slot, sourceName ?: "PAD") }
        }

        // ---- the desk ----
        Row(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (part in Layers.Part.entries) {
                ChannelStrip(
                    part = part,
                    strip = desk.strip(part),
                    enabled = split != null && !working,
                    modifier = Modifier.weight(1f),
                    onLevel = { level ->
                        desk = desk.with(part) { it.copy(level = level) }
                        fade(part)
                    },
                    onReverse = { flipReverse(part) },
                    onMute = {
                        desk = desk.with(part) { it.copy(muted = !it.muted) }
                        fadeAll()
                    },
                    onSolo = {
                        desk = desk.with(part) { it.copy(soloed = !it.soloed) }
                        fadeAll()
                    },
                )
            }
        }

        // ---- transport and print ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(
                if (playing) "STOP" else "PLAY ▸",
                scheme,
                enabled = split != null && !working && engineUp,
                lit = playing,
                modifier = Modifier.weight(1f),
            ) { if (playing) stopAll() else startAll() }
            ActionButton(
                if (printToPad) "→ PAD" else "→ TAPE",
                scheme,
                enabled = !working,
                modifier = Modifier.weight(1f),
            ) { printToPad = !printToPad }
            ActionButton(
                "PRINT",
                scheme,
                enabled = split != null && !working,
                modifier = Modifier.weight(1f),
            ) { printMix() }
        }
    }

    if (pendingPrint != null) {
        val cancelChooser = {
            val snip = pendingPrint
            pendingPrint = null
            if (snip != null) landOnTape(snip)  // the level was said when it was rendered
        }
        SlotChooserOverlay(
            kit = entry?.kit,
            previewColor = scheme.amber.tape,
            scheme = scheme,
            busy = landing,
            onPick = ::landOnPad,
            onCancel = cancelChooser,
        )
        // Unlike SYNTH/SURFACE's own onCancel, this one has no internal
        // `!landing` guard — the overlay's own CANCEL is only reachable
        // while idle because SlotChooserOverlay's `busy = landing` disables
        // its tap target, not because this lambda checks. Back must not
        // reopen a path the tap itself can't reach: always registered (so a
        // landing print in flight can't fall through to a broader policy),
        // but a no-op while `landing`, same as the disabled CANCEL.
        BackHandler { if (!landing) cancelChooser() }
    }
}

/** One vertical channel: the part's name, its fader, and REV · M · S. */
@Composable
private fun ChannelStrip(
    part: Layers.Part,
    strip: Layers.Strip,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onLevel: (Float) -> Unit,
    onReverse: () -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
) {
    val scheme = LocalScheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(20.dp).lcdPanel(scheme),
            contentAlignment = Alignment.Center,
        ) {
            TapeText(part.label, TapeType.pixelSmall, scheme.lcdInk.tape)
        }
        Fader(
            label = part.label,
            level = strip.level,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(FADER_H.dp),
            onLevel = onLevel,
        )
        Box(
            Modifier.fillMaxWidth().height(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("%.2f".format(java.util.Locale.ROOT, strip.level), TapeType.pixelSmall, scheme.ink3.tape)
        }
        ActionButton("REV", scheme, enabled = enabled, lit = strip.reverse, modifier = Modifier.fillMaxWidth()) { onReverse() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Single-letter glyphs are exactly the shortest, most
            // acronym-shaped strings TalkBack is documented to sometimes
            // spell out letter-by-letter instead of reading as a word
            // (audit finding 13) — an explicit accessibilityLabel sidesteps
            // that without changing the visible "M"/"S".
            ActionButton("M", scheme, enabled = enabled, lit = strip.muted, accessibilityLabel = "MUTE", modifier = Modifier.weight(1f)) { onMute() }
            ActionButton("S", scheme, enabled = enabled, lit = strip.soloed, accessibilityLabel = "SOLO", modifier = Modifier.weight(1f)) { onSolo() }
        }
    }
}

/**
 * A vertical fader, `0..MAX_LEVEL` with unity marked. The drag is
 * *relative* — the knob moves by how far the finger moved, not to where
 * it landed — so touching a fader never jumps it, which on a loop you
 * are listening to is the difference between an adjustment and a lurch.
 */
@Composable
private fun Fader(
    label: String,
    level: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onLevel: (Float) -> Unit,
) {
    val scheme = LocalScheme.current
    var heightPx by remember { mutableIntStateOf(0) }
    // The gesture block is keyed on `enabled`, so it is started once and
    // keeps running across recompositions — a plain `level` captured in it
    // would stay at whatever it was when the drag detector began, and every
    // delta would be applied to that same stale base. The fader would twitch
    // and stick instead of following the finger. `rememberUpdatedState` is
    // what makes the block read the level it is being dragged from now.
    val liveLevel by rememberUpdatedState(level)
    val emit by rememberUpdatedState(onLevel)
    Box(
        modifier
            .lcdPanel(scheme)
            .onSizeChanged { heightPx = it.height }
            // Canvas-drawn, so invisible to the a11y tree by default
            // (audit finding 4). A vertical fader maps directly onto
            // Compose's progress-bar semantics: progressBarRangeInfo +
            // setProgress give TalkBack's two-finger adjust gesture a
            // real target, not just a label — the "ideally... adjust"
            // half of finding 4, not merely its label+state floor.
            .semantics {
                contentDescription = "$label FADER"
                if (enabled) {
                    progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..Layers.MAX_LEVEL)
                    setProgress { target -> emit(target.coerceIn(0f, Layers.MAX_LEVEL)); true }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val span = if (heightPx > 0) heightPx.toFloat() else 1f
                    // Up is more: the screen counts down, a fader counts up.
                    val next = liveLevel - dragAmount / span * Layers.MAX_LEVEL
                    emit(next.coerceIn(0f, Layers.MAX_LEVEL))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackX = size.width / 2f
            val top = 6f
            val bottom = size.height - 6f
            val span = (bottom - top).coerceAtLeast(1f)
            // The track, unity's mark, and the knob.
            drawLine(
                color = scheme.ink3.tape,
                start = Offset(trackX, top),
                end = Offset(trackX, bottom),
                strokeWidth = 2f,
            )
            val unityY = bottom - span * (Layers.UNITY / Layers.MAX_LEVEL)
            drawLine(
                color = scheme.ink3.tape,
                start = Offset(size.width * 0.2f, unityY),
                end = Offset(size.width * 0.8f, unityY),
                strokeWidth = 1f,
            )
            val knobY = bottom - span * (level / Layers.MAX_LEVEL)
            drawRect(
                color = if (enabled) scheme.amber.tape else scheme.ink3.tape,
                topLeft = Offset(size.width * 0.15f, knobY - 5f),
                size = Size(size.width * 0.7f, 10f),
            )
        }
    }
}

/** The fader's height: tall enough to be played, short enough for three plus buttons. */
private const val FADER_H = 140

/** A slot as the MPC names it: 1..16 is bank A, 17..32 bank B, and so on. */
// One rule, one home ([PadBanks]) - this copy was correct, and is
// now the same correct thing everywhere.
private fun splitPadLabel(slot: Int): String = PadBanks.tag(slot)
