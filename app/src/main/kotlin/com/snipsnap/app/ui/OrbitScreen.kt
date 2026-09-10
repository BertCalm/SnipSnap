package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.AndroidAudioSink
import com.snipsnap.app.KitShelf
import com.snipsnap.app.OrbitSampleSource
import com.snipsnap.app.PadEngine
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Tempo
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.loop.Orbit
import com.snipsnap.loop.OrbitBank
import com.snipsnap.loop.OrbitClip
import com.snipsnap.loop.OrbitClock
import com.snipsnap.loop.OrbitEngine
import com.snipsnap.loop.OrbitHit
import com.snipsnap.loop.OrbitPatterns
import com.snipsnap.loop.OrbitPresets
import com.snipsnap.loop.OrbitSet
import com.snipsnap.loop.OrbitSpan
import com.snipsnap.loop.OrbitStore
import com.snipsnap.loop.PatternOrbit
import com.snipsnap.loop.SnipOrbit
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.SnipStore
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ORBIT: the circular sequencer — a bar taped end to end into a ring, a
 * longer snip taped into a bigger ring around it, one needle speed driving
 * them all so the inner ring comes round first. Reached from GROOVE's own
 * "ORBIT ▸" button, the same GROOVE-scoped-overlay shape as ARRANGE (see
 * `App.kt`'s `orbitOpen`).
 *
 * Nothing musical is decided here. [OrbitClock] says where every playhead
 * is, [OrbitEngine] plays it, [OrbitStore] keeps it beside the kit as
 * `orbits.json`; this screen draws rings, takes taps, and hands edits to
 * the engine at block boundaries. The transport is the loop grid's own
 * shape: an audio thread blocking on [AndroidAudioSink], a bank prepared
 * off that thread, one atomic swap. Auditions — hearing a pad the moment
 * you tap it — go through PLAY's own [PadEngine], the low-latency path.
 *
 * The picture tells the truth: rings are drawn shortest inside, so the
 * inner ring really does come round first, and each needle's comet tail is
 * one 16th of time, long on a fast ring and short on a slow one. A snip
 * ring wears its own waveform, and its panel says what the fit did to it
 * (as is, trimmed, padded, sliced) rather than leaving the ear to guess.
 * A snip that knows its tempo offers it to the set once, when it lands.
 *
 * Every edit is one step of undo away (UNDO in the panel, [UNDO_DEPTH]
 * deep), BPM runs while held, and the rings and every cell describe
 * themselves to a screen reader.
 *
 * Interaction, in full: tap a ring to pick it, long-press a ring to solo
 * it. The picked ring unrolls into the strip below — the tape untaped —
 * with one row per pad in its voice, so a bass ring is a small piano roll
 * and a kick ring is a single row; tap a cell to place or lift a hit, and
 * hear it. The panel changes the ring's step count, its span (free, or
 * ½, 1, 2 or 4 bars of the set's lap), which pads it plays, and whether
 * it is heard.
 */
@Composable
fun OrbitScreen(
    entry: KitShelf.Entry,
    kitsRoot: File,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kitDir = entry.dir
    val kit = entry.kit
    val filesDir = remember(context) { context.filesDir }
    val rate = remember(context) { deviceSampleRate(context) }
    val source = remember(kitDir) { OrbitSampleSource(kitsRoot, filesDir) }

    var set by remember(kitDir) { mutableStateOf<OrbitSet?>(null) }
    var refusal by remember(kitDir) { mutableStateOf<String?>(null) }
    var selected by remember(kitDir) { mutableIntStateOf(0) }
    var solo by remember(kitDir) { mutableStateOf<Int?>(null) }
    var snips by remember(kitDir) { mutableStateOf<List<File>>(emptyList()) }
    /** + SNIP RING opens the shelf as a list to pick from — no arrow-cycling through a long shelf. */
    var snipPickerOpen by remember(kitDir) { mutableStateOf(false) }
    /** Tapping the step count opens a picker of ring sizes: eight taps on + is not a way to reach 24. */
    var stepsPickerOpen by remember(kitDir) { mutableStateOf(false) }
    /** Long-pressing the SPAN chip opens the five spans as chips; a tap just cycles. */
    var spanPickerOpen by remember(kitDir) { mutableStateOf(false) }
    /** SPREAD asks how many hits before it fills the ring. */
    var spreadOpen by remember(kitDir) { mutableStateOf(false) }
    /** The dice: every roll a new seed, so a roll can always be rolled again. */
    var scrambleSeed by remember(kitDir) { mutableIntStateOf(1) }
    /** OUT ▸ swaps the panel for the two ways a set leaves the screen: onto TAPE, or into the kit as a clip. */
    var outOpen by remember(kitDir) { mutableStateOf(false) }
    var bouncing by remember(kitDir) { mutableStateOf(false) }
    /** A snip ring just added whose tempo the set does not match: SET it or KEEP the set's. Asked once. */
    var tempoOffer by remember(kitDir) { mutableStateOf<TempoOffer?>(null) }
    /** BPM taps settle before the snips refit: the job that fires the commit after a quiet [BPM_SETTLE_MS]. */
    var bpmJob by remember(kitDir) { mutableStateOf<Job?>(null) }
    var bpmPending by remember(kitDir) { mutableStateOf(false) }
    /** The sets before each edit, newest last: one UNDO steps back one edit. Not saved; a screen's worth. */
    var history by remember(kitDir) { mutableStateOf<List<OrbitSet>>(emptyList()) }

    // The transport: null until PLAY. Bank and engine live across edits;
    // each edit prepares a new bank (reusing the old one's buffers) and
    // hands both in as one swap.
    var engine by remember(kitDir) { mutableStateOf<OrbitEngine?>(null) }
    var sink by remember(kitDir) { mutableStateOf<AndroidAudioSink?>(null) }
    var audioThread by remember(kitDir) { mutableStateOf<Thread?>(null) }
    var bank by remember(kitDir) { mutableStateOf<OrbitBank?>(null) }
    var playing by remember(kitDir) { mutableStateOf(false) }
    var frame by remember(kitDir) { mutableLongStateOf(0L) }
    var preparing by remember(kitDir) { mutableStateOf(false) }

    // Audition: PLAY's engine, so a tapped pad sounds within the device's
    // round trip rather than after the transport's 150 ms buffer.
    val player = remember(kitDir) { PadEngine(rate) }
    var playerUp by remember(kitDir) { mutableStateOf(false) }
    var auditionId by remember(kitDir) { mutableIntStateOf(1) }
    DisposableEffect(kitDir) {
        playerUp = player.start()
        onDispose { player.close() }
    }
    LaunchedEffect(kit) { withContext(Dispatchers.IO) { player.load(entry) } }

    fun audition(slot: Int) {
        val pad = kit.pad(slot) ?: return
        if (player.needsRestart()) playerUp = player.start()
        if (!playerUp || !player.isUp()) return
        player.hit(pad, 0.9f, auditionId)
        auditionId = auditionId % 1_000_000 + 1
    }

    LaunchedEffect(kitDir) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val loaded = if (OrbitStore.exists(kitDir)) {
                    OrbitStore.load(kitDir)
                } else {
                    val bpm = (kit.tempoBpm ?: 92f).coerceIn(OrbitSet.MIN_BPM, OrbitSet.MAX_BPM)
                    OrbitPresets.fromKit(kitDir.name, kit, bpm, rate)
                }
                loaded.copy(sampleRate = rate) to SnipStore.list(filesDir)
            }
        }
        result.onSuccess { (loaded, files) ->
            set = loaded
            snips = files
            refusal = null
            // Prepare before PLAY: a snip ring's waveform and fit report
            // are worth seeing the moment the screen opens, not after.
            preparing = true
            bank = withContext(Dispatchers.IO) { runCatching { OrbitBank.prepare(loaded, source) }.getOrNull() }
            preparing = false
        }.onFailure { e ->
            set = null
            refusal = e.message ?: "NO RINGS"
        }
    }

    fun stopPlayback() {
        engine?.stop()
        audioThread?.join(300)
        sink?.close()
        engine = null
        sink = null
        audioThread = null
        playing = false
    }

    DisposableEffect(kitDir) { onDispose { stopPlayback() } }

    // Backgrounding silences, the same lesson every other transport here
    // applies: a ring set turning under a locked screen is a bug, not a feature.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, kitDir) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                stopPlayback()
                player.allOff()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** What the engine plays: the set, with every ring but the soloed one silenced while a solo holds. */
    fun heard(s: OrbitSet): OrbitSet {
        val only = solo ?: return s
        return s.copy(orbits = s.orbits.mapIndexed { i, o -> o.copy(engaged = i == only && o.engaged) })
    }

    /**
     * Persist and, if the transport is up, hand the edit to the engine at
     * the next block. Every edit is recorded for UNDO unless [record] says
     * not to (UNDO itself, and the settled end of a BPM run, which was
     * recorded once when the run began).
     */
    fun commit(next: OrbitSet, record: Boolean = true) {
        val before = set
        if (record && before != null && before != next) history = (history + before).takeLast(UNDO_DEPTH)
        set = next
        scope.launch {
            preparing = true
            val prepared = withContext(Dispatchers.IO) {
                runCatching { OrbitStore.save(next, kitDir) }
                OrbitBank.prepare(next, source, previous = bank)
            }
            bank = prepared
            engine?.apply(OrbitEngine.Prepared(heard(next), prepared))
            preparing = false
        }
    }

    fun startPlayback() {
        val s = set ?: return
        if (playing || preparing) return
        preparing = true
        scope.launch {
            val prepared = withContext(Dispatchers.IO) { OrbitBank.prepare(s, source, previous = bank) }
            bank = prepared
            val audioSink = AndroidAudioSink(s.sampleRate)
            val e = OrbitEngine(heard(s), prepared, audioSink)
            sink = audioSink
            engine = e
            audioThread = thread(name = "snipsnap-orbit", isDaemon = true) { e.run() }
            playing = true
            preparing = false
        }
    }

    /** Solo is a listening choice, not part of the set: applied to the engine, never saved. */
    fun toggleSolo(index: Int) {
        solo = if (solo == index) null else index
        val s = set ?: return
        val b = bank ?: return
        engine?.apply(OrbitEngine.Prepared(heard(s), b))
    }

    // The needle: read the engine's own frame count every display frame.
    // The transport is the engine; this only draws where it says it is.
    LaunchedEffect(playing, kitDir) {
        if (!playing) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            frame = engine?.position() ?: 0L
        }
    }

    fun updateRing(index: Int, edit: (Orbit) -> Orbit) {
        val s = set ?: return
        if (index !in s.orbits.indices) return
        val next = runCatching { s.copy(orbits = s.orbits.mapIndexed { i, o -> if (i == index) edit(o) else o }) }
        next.onSuccess { commit(it) }.onFailure { e -> onToast(e.message ?: "NO") }
    }

    /** Place or lift [slot] on [step] of ring [index]; a placed hit is heard. */
    fun toggleHit(index: Int, slot: Int, step: Int) {
        var placed = false
        updateRing(index) { ring ->
            val content = ring.content as? PatternOrbit ?: return@updateRing ring
            val existing = content.hits.firstOrNull { it.step == step && it.slot == slot }
            val hits = if (existing != null) content.hits - existing else content.hits + OrbitHit(step, slot, 0.9f)
            placed = existing == null
            ring.copy(content = content.copy(hits = hits.sortedWith(compareBy({ it.step }, { it.slot }))))
        }
        if (placed) audition(slot)
    }

    /** Long-press on a cell: an existing hit cycles soft → normal → accent; an empty cell takes an accent. */
    fun cycleHit(index: Int, slot: Int, step: Int) {
        updateRing(index) { ring ->
            val content = ring.content as? PatternOrbit ?: return@updateRing ring
            val existing = content.hits.firstOrNull { it.step == step && it.slot == slot }
            val hits = if (existing != null) {
                content.hits - existing + existing.copy(velocity = OrbitPatterns.nextVelocity(existing.velocity))
            } else {
                content.hits + OrbitHit(step, slot, OrbitPatterns.ACCENT_VELOCITY)
            }
            ring.copy(content = content.copy(hits = hits.sortedWith(compareBy({ it.step }, { it.slot }))))
        }
        audition(slot)
    }

    fun spreadRing(index: Int, k: Int) {
        spreadOpen = false
        updateRing(index) { ring -> OrbitPatterns.spread(ring, ring.pads.first(), k) }
    }

    fun scrambleRing(index: Int) {
        scrambleSeed += 1
        updateRing(index) { ring -> OrbitPatterns.scramble(ring, scrambleSeed) }
    }

    fun setSteps(index: Int, steps: Int) {
        stepsPickerOpen = false
        val clamped = steps.coerceIn(1, Orbit.MAX_STEPS)
        updateRing(index) { ring ->
            // Shrinking a ring drops the hits past its new end rather than
            // refusing: the user asked for a shorter ring, not a lecture.
            val content = ring.content
            val kept = if (content is PatternOrbit) content.copy(hits = content.hits.filter { it.step < clamped }) else content
            ring.copy(steps = clamped, content = kept)
        }
    }

    /** Add or drop a pad from the ring's voice; a dropped pad takes its hits with it, and the last pad stays. */
    fun toggleVoicePad(index: Int, slot: Int) {
        updateRing(index) { ring ->
            val content = ring.content as? PatternOrbit ?: return@updateRing ring
            val pads = ring.pads
            if (slot in pads) {
                if (pads.size == 1) return@updateRing ring
                ring.copy(voice = pads - slot, content = content.copy(hits = content.hits.filter { it.slot != slot }))
            } else {
                ring.copy(voice = (pads + slot).sorted())
            }
        }
    }

    fun addPatternRing() {
        val s = set ?: return
        if (s.orbits.size >= OrbitSet.MAX_ORBITS) { onToast("EIGHT RINGS IS THE SKY"); return }
        val firstPad = kit.pads.minOfOrNull { it.slot } ?: run { onToast("NO PADS ON THIS KIT"); return }
        val name = "RING ${s.orbits.size + 1}"
        commit(s.copy(orbits = s.orbits + OrbitPresets.emptyPattern(kitDir.name, name, listOf(firstPad))))
        selected = s.orbits.size
    }

    fun addSnipRing(file: File) {
        val s = set ?: return
        snipPickerOpen = false
        if (s.orbits.size >= OrbitSet.MAX_ORBITS) { onToast("EIGHT RINGS IS THE SKY"); return }
        scope.launch {
            // The header alone says how long the snip is — no decode, no
            // size ceiling to worry about. The bank decodes (capped) when
            // the ring is actually prepared for playback.
            val info = withContext(Dispatchers.IO) { runCatching { WavInfo.read(file) }.getOrNull() }
            if (info == null) { onToast("COULD NOT READ ${file.name}"); return@launch }
            val current = set ?: return@launch
            // Frames at the set's rate, since the bank resamples before it fits.
            val frames = (info.frameCount.toDouble() / info.sampleRate * current.sampleRate).roundToInt()
            val ring = OrbitPresets.snipRing(current, SnipStore.displayName(file).uppercase(), file.name, frames)
            commit(current.copy(orbits = current.orbits + ring))
            selected = current.orbits.size
            // What the snip thinks its tempo is, offered once if it differs
            // from the set's: a loop captured at 96 wrapped round a set at
            // 92 is sliced and squeezed for no reason anyone chose.
            val guess = withContext(Dispatchers.IO) {
                runCatching { Tempo.estimate(WavReader.readCapped(file, TEMPO_MAX_SEC).snip) }.getOrNull()
            }
            if (guess != null && guess.confidence >= TEMPO_TRUST && guess.bpm.roundToInt() != current.bpm.roundToInt()) {
                tempoOffer = TempoOffer(ring.name, file.name, guess.bpm.roundToInt().toFloat(), frames)
            }
        }
    }

    /** Step back one edit: the set before it, saved and handed to the engine like any other change. */
    fun undo() {
        val previous = history.lastOrNull() ?: run { onToast("NOTHING TO UNDO"); return }
        history = history.dropLast(1)
        bpmJob?.cancel()
        bpmPending = false
        tempoOffer = null
        val soloed = solo
        if (soloed != null && soloed >= previous.orbits.size) solo = null
        selected = selected.coerceIn(0, (previous.orbits.size - 1).coerceAtLeast(0))
        commit(previous, record = false)
    }

    /** Take the snip's tempo: the set moves to it and the snip's ring is re-sized to its natural length there. */
    fun acceptTempo(offer: TempoOffer) {
        tempoOffer = null
        val s = set ?: return
        bpmJob?.cancel()
        bpmPending = false
        val at = s.copy(bpm = offer.bpm.coerceIn(OrbitSet.MIN_BPM, OrbitSet.MAX_BPM))
        val steps = OrbitClock.naturalSteps(at, offer.frames)
        commit(at.copy(orbits = at.orbits.map { o ->
            val content = o.content
            if (content is SnipOrbit && content.sampleFile == offer.sampleFile) o.copy(steps = steps) else o
        }))
    }

    fun deleteRing(index: Int) {
        val s = set ?: return
        if (index !in s.orbits.indices) return
        if (solo == index) solo = null
        tempoOffer = null
        commit(s.copy(orbits = s.orbits.filterIndexed { i, _ -> i != index }))
        selected = (index - 1).coerceAtLeast(0)
    }

    /**
     * The tempo, debounced: the readout moves at once, the save and the
     * refit wait for [BPM_SETTLE_MS] of quiet, so ten taps on BPM + do not
     * slice a snip ten times. A set with no snip rings needs no refit —
     * pads do not change with tempo — so the engine takes the new tempo
     * on the next block and only the save waits.
     */
    fun setBpm(bpm: Float) {
        val s = set ?: return
        val next = s.copy(bpm = bpm.coerceIn(OrbitSet.MIN_BPM, OrbitSet.MAX_BPM))
        if (next == s) return
        // One UNDO steps back the whole run of taps, not one tap of it.
        if (!bpmPending) history = (history + s).takeLast(UNDO_DEPTH)
        set = next
        val b = bank
        if (b != null && next.orbits.none { it.content is SnipOrbit }) {
            engine?.apply(OrbitEngine.Prepared(heard(next), b))
        }
        bpmJob?.cancel()
        bpmPending = true
        bpmJob = scope.launch {
            delay(BPM_SETTLE_MS)
            bpmPending = false
            set?.let { commit(it, record = false) }
        }
    }

    /**
     * One full cycle of what is heard, rendered offline and dropped on the
     * TAPE shelf as a snip — so rings feed the app's own loop: tape, chop,
     * kit, MPC. What is heard: a solo bounces alone, a muted ring stays out.
     */
    fun bounceToTape() {
        val s = set ?: return
        OrbitClip.refusal(s)?.let { onToast(it); return }
        if (bouncing) return
        bouncing = true
        outOpen = false
        val what = heard(s)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val prepared = OrbitBank.prepare(what, source, previous = bank)
                    val frames = OrbitClock.cycleFrames(what).toInt()
                    val rendered = OrbitEngine.render(what, prepared, frames)
                    SnipStore.import(rendered, filesDir, System.currentTimeMillis())
                }
            }
            bouncing = false
            result.onSuccess { imported ->
                snips = SnipStore.list(filesDir)
                onToast("ON TAPE: ${cycleLabel(what)}. TRIM IT, CHOP IT, KIT IT.")
            }.onFailure { e -> onToast("BOUNCE FAILED: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /** One cycle as a clip in the kit's grooves, so it rides to the MPC with the kit. */
    fun clipIntoKit() {
        val s = set ?: return
        OrbitClip.refusal(s)?.let { onToast(it); return }
        outOpen = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { OrbitClip.save(kitDir, s) } }
            result.onSuccess { clip ->
                onToast("${clip.name} IS IN THE KIT'S GROOVES — ${clip.bars} BARS, ${clip.notes.size} NOTES. IT RIDES TO THE MPC.")
            }.onFailure { e -> onToast("CLIP FAILED: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    BackHandler { stopPlayback(); onBack() }

    val current = set
    val ring = current?.orbits?.getOrNull(selected)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(Layout.LCD_HEADER_H.dp).lcdPanel(scheme).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderChip("◄ GRV", scheme, Modifier.width(56.dp), description = "BACK TO GROOVE") { stopPlayback(); onBack() }
                // The transport lives in the header so it never scrolls away
                // under a tall strip: PLAY is the first thing this screen is for.
                HeaderChip(
                    if (playing) "■" else "▶",
                    scheme,
                    Modifier.width(40.dp),
                    accent = !playing,
                    enabled = current != null && !preparing,
                ) { if (playing) stopPlayback() else startPlayback() }
            }
            TapeText("ORBIT", TapeType.lcd(21), scheme.lcdInk.tape)
            if (current != null) {
                Column(horizontalAlignment = Alignment.End) {
                    TapeText(
                        "${OrbitClock.ratioLabel(current).replace(" : ", ":")} · ${cycleLabel(current)}",
                        TapeType.lcd(14),
                        scheme.amber.tape,
                    )
                    TapeText(barLabel(current, frame, playing) + " · ${current.bpm.roundToInt()} BPM", TapeType.lcd(14), scheme.lcdInk.tape)
                }
            }
        }

        if (current == null) {
            Box(Modifier.fillMaxWidth().weight(1f).lcdPanel(scheme), contentAlignment = Alignment.Center) {
                TapeText(refusal ?: "LOADING", TapeType.lcdSmall, scheme.lcdInk.tape)
            }
            return@Column
        }

        val stripRows = ring?.pads?.size ?: 0
        val hasSnips = current.orbits.any { it.content is SnipOrbit }
        // A snip ring is being cut to a new length: the picture says so
        // rather than going quietly stale while the tempo settles.
        val refitting = hasSnips && (bpmPending || preparing)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The rings give up a little height to the strip, and more to a
            // tall one (a bass ring's piano roll), so the transport stays on
            // screen rather than scrolling away under it.
            val ringsHeight = if (stripRows > 2) 250.dp else 300.dp
            RingsCanvas(
                set = current,
                kit = kit,
                frame = frame,
                playing = playing,
                selected = selected,
                solo = solo,
                bank = bank,
                refitting = refitting,
                scheme = scheme,
                onTapRing = { index -> selected = index },
                onLongPressRing = { index -> toggleSolo(index) },
                modifier = Modifier.fillMaxWidth().height(ringsHeight),
            )

            // The picked ring, unrolled: the tape untaped, one row per pad.
            if (ring != null && ring.content is PatternOrbit) {
                StripEditor(
                    set = current,
                    ring = ring,
                    kit = kit,
                    frame = frame,
                    playing = playing,
                    scheme = scheme,
                    onToggle = { slot, step -> toggleHit(selected, slot, step) },
                    onCycle = { slot, step -> cycleHit(selected, slot, step) },
                    onAudition = { slot -> audition(slot) },
                )
            }

            // The offer a snip makes on landing: its own tempo, once.
            tempoOffer?.let { offer ->
                Column(
                    Modifier.fillMaxWidth().sunkenField(scheme).border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TapeText(
                        "${offer.ringName} SOUNDS LIKE ${offer.bpm.roundToInt()} BPM · THE SET IS AT ${current.bpm.roundToInt()}.",
                        TapeType.pixel,
                        scheme.ink.tape,
                        maxLines = 2,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallChip("SET ${offer.bpm.roundToInt()}", scheme, accent = true) { acceptTempo(offer) }
                        SmallChip("KEEP ${current.bpm.roundToInt()}", scheme) { tempoOffer = null }
                        TapeText("SET MOVES THE WHOLE SET AND RE-SIZES THE RING TO FIT.", TapeType.pixelSmall, scheme.ink3.tape, Modifier.weight(1f), maxLines = 2)
                    }
                }
            }

            // The picked ring's panel — or, while + SNIP RING is choosing, the shelf.
            Column(
                Modifier.fillMaxWidth().sunkenField(scheme).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (snipPickerOpen) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TapeText("WHICH SNIP GOES ROUND A RING?", TapeType.pixel, scheme.ink.tape)
                        SmallChip("CLOSE", scheme) { snipPickerOpen = false }
                    }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (file in snips) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .background(scheme.lcd.tape, RoundedCornerShape(4.dp))
                                    .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(4.dp))
                                    .tapeClick(label = null) { addSnipRing(file) }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                TapeText(SnipStore.displayName(file).uppercase(), TapeType.pixel, scheme.amber.tape)
                            }
                        }
                    }
                } else if (outOpen) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TapeText("ONE CYCLE OUT — ${cycleLabel(current)}", TapeType.pixel, scheme.ink.tape)
                        SmallChip("CLOSE", scheme) { outOpen = false }
                    }
                    val refusal = OrbitClip.refusal(current)
                    if (refusal != null) {
                        TapeText(refusal, TapeType.pixelSmall, scheme.warn.tape, Modifier.fillMaxWidth(), maxLines = 2)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActionButton(if (bouncing) "BOUNCING…" else "BOUNCE ▸ TAPE", scheme, Modifier.weight(1f), enabled = !bouncing, accent = true) { bounceToTape() }
                            ActionButton("CLIP ▸ KIT", scheme, Modifier.weight(1f), accent = true) { clipIntoKit() }
                        }
                        TapeText(
                            "TAPE: WHAT YOU HEAR, AS A SNIP ON THE SHELF — TRIM IT, CHOP IT, MAKE A KIT OF IT. KIT: THE PATTERN AS A CLIP IN THE KIT'S GROOVES, SO IT RIDES TO THE MPC.",
                            TapeType.pixelSmall,
                            scheme.ink3.tape,
                            Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                    }
                } else if (stepsPickerOpen && ring != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TapeText("HOW MANY STEPS ROUND ${ring.name}?", TapeType.pixel, scheme.ink.tape)
                        SmallChip("CLOSE", scheme) { stepsPickerOpen = false }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (n in OrbitPatterns.STEP_CHOICES) {
                            SmallChip(n.toString(), scheme, accent = n == ring.steps) { setSteps(selected, n) }
                        }
                    }
                    TapeText("16 IS A BAR · 20 IS FIVE BEATS · 12 IS THREE · ODD NUMBERS DRIFT FURTHEST", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 2)
                } else if (spanPickerOpen && ring != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TapeText("HOW MANY BARS IS ONE TURN OF ${ring.name}?", TapeType.pixel, scheme.ink.tape)
                        SmallChip("CLOSE", scheme) { spanPickerOpen = false }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (span in OrbitSpan.entries) {
                            SmallChip(span.label, scheme, accent = span == ring.span) {
                                spanPickerOpen = false
                                updateRing(selected) { it.copy(span = span) }
                            }
                        }
                    }
                    TapeText("FREE IS AS LONG AS ITS STEPS. A SPAN IS A WHOLE NUMBER OF BARS WHATEVER THE STEPS: 3 STEPS ACROSS 2 BARS IS THREE HITS IN EIGHT BEATS.", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 3)
                } else if (spreadOpen && ring != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TapeText("SPREAD HOW MANY HITS ROUND ${ring.steps} STEPS?", TapeType.pixel, scheme.ink.tape)
                        SmallChip("CLOSE", scheme) { spreadOpen = false }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (k in 1..minOf(ring.steps, 12)) {
                            SmallChip(k.toString(), scheme) { spreadRing(selected, k) }
                        }
                    }
                    TapeText("AS EVEN AS THE STEPS ALLOW: 3 ROUND 8 IS THE TRESILLO, 5 ROUND 8 THE CINQUILLO. ON THE RING'S FIRST PAD.", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 2)
                } else if (ring == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TapeText("NO RINGS — ADD ONE BELOW", TapeType.pixel, scheme.ink2.tape)
                        SmallChip("UNDO", scheme, enabled = history.isNotEmpty(), description = "UNDO THE LAST EDIT") { undo() }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TapeText(ring.name, TapeType.display, scheme.ink.tape)
                        TapeText(
                            when {
                                solo == selected -> "SOLO"
                                ring.content is SnipOrbit -> "SNIP"
                                ring.pads.size > 1 -> "${ring.pads.size} PADS"
                                else -> "PAD"
                            },
                            TapeType.pixelSmall,
                            if (solo == selected) scheme.accent.tape else scheme.ink3.tape,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallChip("−", scheme, description = "ONE STEP FEWER") { setSteps(selected, ring.steps - 1) }
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp)
                                .tapeClick(label = "PICK A STEP COUNT") { stepsPickerOpen = true },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            TapeText(
                                "${ring.steps} STEPS · ${OrbitClock.lengthLabel(current, ring)} ▾",
                                TapeType.pixel,
                                scheme.ink.tape,
                            )
                        }
                        SmallChip("+", scheme, description = "ONE STEP MORE") { setSteps(selected, ring.steps + 1) }
                        // Tap for the next span, hold to pick one of the five.
                        SpanChip(ring.span, scheme, onNext = { updateRing(selected) { it.copy(span = it.span.next) } }) {
                            spanPickerOpen = true
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SmallChip("UNDO", scheme, enabled = history.isNotEmpty(), description = "UNDO THE LAST EDIT") { undo() }
                        SmallChip(if (ring.engaged) "ON" else "OFF", scheme, accent = ring.engaged, description = if (ring.engaged) "RING ON — TAP TO MUTE" else "RING OFF — TAP TO HEAR") {
                            updateRing(selected) { it.copy(engaged = !it.engaged) }
                        }
                        SmallChip("SOLO", scheme, accent = solo == selected) { toggleSolo(selected) }
                        SmallChip("DEL", scheme) { deleteRing(selected) }
                        if (ring.content is PatternOrbit) {
                            SmallChip("SPREAD", scheme) { spreadOpen = true }
                            SmallChip("CLEAR", scheme) { updateRing(selected) { OrbitPatterns.clear(it) } }
                            SmallChip("⚄ DICE", scheme, description = "ROLL THE DICE") { scrambleRing(selected) }
                        }
                    }
                    when (val content = ring.content) {
                        is PatternOrbit -> {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TapeText("PLAYS", TapeType.pixelSmall, scheme.ink3.tape, Modifier.width(36.dp))
                                for (pad in kit.pads.sortedBy { it.slot }) {
                                    PadChip(
                                        label = padLabel(pad.slot),
                                        color = Schemes.classColor(pad.drumClass).tape,
                                        chosen = pad.slot in ring.pads,
                                        used = content.hits.any { it.slot == pad.slot },
                                        scheme = scheme,
                                    ) { toggleVoicePad(selected, pad.slot) }
                                }
                            }
                        }
                        is SnipOrbit -> {
                            // What the fit did, in the bank's own words: the
                            // one line that tells a squeezed loop from a clean one.
                            val report = bank?.fit(current, ring)
                            val seconds = report?.let { "%.1f".format(java.util.Locale.ROOT, it.sourceFrames.toFloat() / current.sampleRate) }
                            val what = when {
                                report != null -> report.label
                                refitting || bank == null -> "FITTING…"
                                else -> "FILE MISSING — NOTHING TO WRAP"
                            }
                            TapeText(
                                "WRAPS ${content.sampleFile.uppercase()}${if (seconds != null) " ($seconds S)" else ""} ROUND ${ring.steps} STEPS · $what",
                                TapeType.pixelSmall,
                                if (report?.fit == com.snipsnap.loop.LoopFit.SLICED) scheme.amber.tape else scheme.ink2.tape,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }

            // One row: the tempo and the ring shelf. PLAY is in the header.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionButton("↺", scheme, Modifier.weight(0.7f), enabled = playing, description = "BACK TO THE TOP") { engine?.rewind() }
                // Hold to run: forty taps is not a way to get from 92 to 172.
                RepeatButton("BPM −", "SLOWER", scheme, Modifier.weight(1f)) { set?.let { setBpm(it.bpm - 2f) } }
                RepeatButton("BPM +", "FASTER", scheme, Modifier.weight(1f)) { set?.let { setBpm(it.bpm + 2f) } }
                ActionButton("+ PAD", scheme, Modifier.weight(1f)) { addPatternRing() }
                ActionButton("+ SNIP", scheme, Modifier.weight(1f), enabled = snips.isNotEmpty(), accent = snipPickerOpen) {
                    snipPickerOpen = !snipPickerOpen
                    outOpen = false
                }
                ActionButton("OUT ▸", scheme, Modifier.weight(1f), accent = outOpen) {
                    outOpen = !outOpen
                    snipPickerOpen = false
                }
            }
            TapeText(
                if (snips.isEmpty()) {
                    "TAP A RING TO PICK IT · HOLD TO SOLO · TAP A CELL FOR A HIT, HOLD IT FOR AN ACCENT · NO SNIPS ON THE SHELF YET FOR + SNIP."
                } else {
                    "TAP A RING TO PICK IT · HOLD TO SOLO · TAP A CELL FOR A HIT, HOLD IT FOR AN ACCENT · HOLD BPM TO RUN IT · SHORTEST RING INSIDE COMES ROUND FIRST."
                },
                TapeType.pixelSmall,
                scheme.ink3.tape,
                Modifier.fillMaxWidth(),
                maxLines = 3,
            )
        }
    }
}

/** "15 BARS", or "1 BAR" — the realignment length the loop grid's bounce also reports. */
private fun cycleLabel(set: OrbitSet): String {
    val bars = OrbitClock.cycleBars(set)
    val whole = bars.roundToInt()
    return if (abs(bars - whole) < 1e-9) (if (whole == 1) "1 BAR" else "$whole BARS") else "${"%.1f".format(java.util.Locale.ROOT, bars)} BARS"
}

/** "BAR 7 / 15": where in the cycle the transport is. Stopped, it is at the top. */
private fun barLabel(set: OrbitSet, frame: Long, playing: Boolean): String {
    val total = ceil(OrbitClock.cycleBars(set)).toInt().coerceAtLeast(1)
    val lap = OrbitClock.lapFrames(set)
    val bar = if (playing && lap > 0) ((frame / lap) % total + 1).toInt() else 1
    return "BAR $bar/$total"
}

private fun padLabel(slot: Int): String {
    val bank = 'A' + (slot - 1) / 16
    val n = (slot - 1) % 16 + 1
    return "$bank${n.toString().padStart(2, '0')}"
}

/** The pad's class colour, or the LCD ink for a pad the kit no longer has. */
private fun padColor(kit: Kit, slot: Int, fallback: Color): Color =
    kit.pads.firstOrNull { it.slot == slot }?.let { Schemes.classColor(it.drumClass).tape } ?: fallback

/**
 * The rings themselves, shortest inside: every ring a circle of its own
 * steps, hits as class-coloured dots (a melodic ring's dots step in and
 * out from the line by pitch), the playhead a bright dot with a comet
 * tail one 16th long. Twelve o'clock is step 0 and the needle runs
 * clockwise, the way a clock and a record both do. The panel's frame
 * pulses when every ring is back on its downbeat together.
 */
@Composable
private fun RingsCanvas(
    set: OrbitSet,
    kit: Kit,
    frame: Long,
    playing: Boolean,
    selected: Int,
    solo: Int?,
    bank: OrbitBank?,
    refitting: Boolean,
    scheme: Scheme,
    onTapRing: (Int) -> Unit,
    onLongPressRing: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenDensity = LocalDensity.current.density
    // Each snip ring's waveform as peaks round the ring, scaled so its
    // loudest bucket reaches full height: a quiet capture still reads.
    val waves = remember(bank, set) {
        set.orbits.map { ring ->
            bank?.peaks(set, ring, WAVE_BUCKETS)?.let { raw ->
                val top = raw.maxOrNull() ?: 0f
                if (top > 0f) FloatArray(raw.size) { raw[it] / top } else null
            }
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TapeType.pixelSmall.copy(fontSize = 7.sp)
    val inkColor = scheme.lcdInk.tape
    val amber = scheme.amber.tape
    val dim = scheme.ink3.tape.copy(alpha = 0.6f)
    // Display order: shortest period innermost, then fewer steps, then as added.
    val order = remember(set) {
        set.orbits.indices.sortedWith(
            compareBy({ OrbitClock.periodFrames(set, set.orbits[it]) }, { set.orbits[it].steps }, { it }),
        )
    }

    // The picture in words, for a screen reader: every ring in display
    // order, its length, and whether it is picked, muted or soloed.
    val description = remember(set, selected, solo) {
        val rings = order.joinToString(", ") { i ->
            val ring = set.orbits[i]
            val state = listOfNotNull(
                if (i == selected) "PICKED" else null,
                if (!ring.engaged) "MUTED" else null,
                if (solo == i) "SOLOED" else null,
            ).joinToString(" ")
            "${ring.name}, ${OrbitClock.lengthLabel(set, ring)}${if (state.isEmpty()) "" else ", $state"}"
        }
        if (set.orbits.isEmpty()) "RINGS: NONE" else "RINGS, SHORTEST INSIDE: $rings. THEY MEET EVERY ${cycleLabel(set)}."
    }

    Box(modifier.lcdPanel(scheme).semantics { contentDescription = description }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(10.dp)
                .pointerInput(set, selected) {
                    detectTapGestures(
                        onLongPress = { pos ->
                            val geometry = RingGeometry(size.width.toFloat(), size.height.toFloat(), set.orbits.size, screenDensity)
                            geometry.ringAt(pos)?.let { onLongPressRing(order[it]) }
                        },
                        onTap = { pos ->
                            val geometry = RingGeometry(size.width.toFloat(), size.height.toFloat(), set.orbits.size, screenDensity)
                            geometry.ringAt(pos)?.let { onTapRing(order[it]) }
                        },
                    )
                },
        ) {
            val geometry = RingGeometry(size.width, size.height, set.orbits.size, screenDensity)
            val centre = geometry.centre
            for ((slot, i) in order.withIndex()) {
                val ring = set.orbits[i]
                val r = geometry.radius(slot)
                val picked = i == selected
                val heard = ring.engaged && (solo == null || solo == i)
                val ringInk = when (val content = ring.content) {
                    is PatternOrbit -> padColor(kit, ring.pads.first(), inkColor)
                    is SnipOrbit -> Schemes.classColor(com.snipsnap.audio.DrumClass.LOOP).tape
                }
                val strokeW = (if (picked) 2.5f else 1.25f) * screenDensity
                drawCircle(
                    color = when {
                        !heard -> dim
                        picked -> amber
                        else -> ringInk.copy(alpha = 0.7f)
                    },
                    radius = r,
                    center = centre,
                    style = Stroke(width = strokeW),
                )
                // A snip ring wears its waveform: one bar across the stroke
                // per bucket, as long as the loudest sample in that arc.
                waves[i]?.let { wave ->
                    val waveInk = ringInk.copy(alpha = if (heard) 0.55f else 0.2f)
                    val amp = WAVE_AMP_DP * screenDensity
                    for (b in wave.indices) {
                        val a = wave[b] * amp
                        if (a < 0.5f * screenDensity) continue
                        val ph = (b + 0.5) / wave.size
                        drawLine(waveInk, geometry.point(r - a, ph), geometry.point(r + a, ph), strokeWidth = 1.5f * screenDensity)
                    }
                }
                // Step ticks: a faint dot per step, so the ring's size is readable.
                val tick = 1.2f * screenDensity
                for (s in 0 until ring.steps) {
                    drawCircle(dim, tick, geometry.point(r, s.toDouble() / ring.steps))
                }
                // Lap ticks: on a ring spanning two or more bars, a heavier
                // mark where each bar boundary falls, so "the bar is here"
                // reads on a ring longer than a bar. A free ring's bar
                // boundary drifts, which is the point of it, so it gets none.
                val laps = OrbitClock.spannedLaps(ring)
                if (laps >= 2) {
                    val reach = LAP_TICK_DP * screenDensity
                    for (k in 0 until laps) {
                        val ph = k.toDouble() / laps
                        drawLine(ringInk.copy(alpha = 0.8f), geometry.point(r - reach, ph), geometry.point(r + reach, ph), strokeWidth = 1.5f * screenDensity)
                    }
                }
                // Hits, flaring for a moment after the needle strikes them:
                // the eye goes to where the sound just happened. On a ring
                // with several pads, pitch steps the dot in or out from the line.
                val content = ring.content
                if (content is PatternOrbit) {
                    val pads = ring.pads
                    for (hit in content.hits) {
                        val color = padColor(kit, hit.slot, inkColor)
                        val dotR = (2.5f + 2.5f * hit.velocity) * screenDensity
                        val pitch = if (pads.size > 1) (pads.indexOf(hit.slot).coerceAtLeast(0).toFloat() / (pads.size - 1) - 0.5f) else 0f
                        val at = geometry.point(r + pitch * 8f * screenDensity, hit.step.toDouble() / ring.steps)
                        val flare = if (heard && playing) {
                            val since = OrbitClock.framesSinceFiring(set, ring, hit, frame)
                            (1f - since.toFloat() / (set.sampleRate * FLARE_SECONDS)).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        if (flare > 0f) {
                            drawCircle(color.copy(alpha = 0.35f * flare), dotR + 9f * screenDensity * flare, at)
                        }
                        drawCircle(color, dotR + 3f * screenDensity * flare, at)
                    }
                }
                // The needle, with a comet tail one 16th of time long: long
                // on a fast inner ring, short on a slow outer one.
                val phase = OrbitClock.phase(set, ring, frame)
                val needle = if (heard) inkColor else dim
                val tailRect = Rect(centre - Offset(r, r), Size(r * 2, r * 2))
                val segments = 8
                val segDeg = (OrbitClock.tailSweep(set, ring) * 360.0 / segments).toFloat()
                val headDeg = (phase * 360.0 - 90.0).toFloat()
                for (j in 0 until segments) {
                    val fade = 1f - j.toFloat() / segments
                    drawArc(
                        color = needle.copy(alpha = 0.75f * fade),
                        startAngle = headDeg - (j + 1) * segDeg,
                        sweepAngle = segDeg + 0.5f,
                        useCenter = false,
                        topLeft = tailRect.topLeft,
                        size = tailRect.size,
                        style = Stroke(width = (3f * fade + 0.6f) * screenDensity, cap = StrokeCap.Round),
                    )
                }
                drawCircle(needle, 3.5f * screenDensity, geometry.point(r, phase))
                // The ring's name and size, just outside it at 12 o'clock.
                val labelAt = geometry.point(r + 9f * screenDensity, 0.012)
                drawText(
                    textMeasurer = textMeasurer,
                    text = "${ring.name} · ${ring.steps}",
                    topLeft = Offset(labelAt.x + 6f * screenDensity, labelAt.y - 4f * screenDensity),
                    style = labelStyle.copy(color = if (picked) amber else scheme.ink2.tape),
                )
            }
            // The hub: a dot marking the shared centre every ring turns about.
            drawCircle(dim, 2f * screenDensity, centre)
            // A refit under way: the snips are being cut to a new length.
            if (refitting) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = "REFITTING…",
                    topLeft = Offset(0f, 0f),
                    style = labelStyle.copy(color = amber),
                )
            }
            // The meeting: every ring on its downbeat together, once a cycle.
            if (playing && set.orbits.isNotEmpty()) {
                val cycle = OrbitClock.cycleFrames(set)
                val since = Math.floorMod(frame, cycle)
                val pulse = (1f - since.toFloat() / (set.sampleRate * MEET_SECONDS)).coerceIn(0f, 1f)
                if (pulse > 0f) {
                    drawRect(
                        color = amber.copy(alpha = 0.8f * pulse),
                        topLeft = Offset(-8f * screenDensity, -8f * screenDensity),
                        size = Size(size.width + 16f * screenDensity, size.height + 16f * screenDensity),
                        style = Stroke(width = (2f + 4f * pulse) * screenDensity),
                    )
                }
            }
        }
    }
}

/**
 * The picked ring unrolled: one row per pad in its voice (highest at the
 * top, as a piano roll reads), one column per step, the playhead column
 * lit while the transport runs. Cells are at least [CELL_MIN_DP] wide and
 * the strip scrolls sideways past what fits, so a 64-step ring is as
 * editable as a 4-step one — which the ring itself, at phone size, is not.
 */
@Composable
private fun StripEditor(
    set: OrbitSet,
    ring: Orbit,
    kit: Kit,
    frame: Long,
    playing: Boolean,
    scheme: Scheme,
    onToggle: (slot: Int, step: Int) -> Unit,
    onCycle: (slot: Int, step: Int) -> Unit,
    onAudition: (slot: Int) -> Unit,
) {
    val content = ring.content as? PatternOrbit ?: return
    val rows = ring.pads.reversed()
    // One index per ring, not one scan per cell: a 64-step bass ring is
    // rows × steps lookups per recomposition, and that must stay cheap.
    val hitAt = remember(content) { content.hits.associateBy { it.step to it.slot } }
    val playheadStep = if (playing) OrbitClock.stepAt(set, ring, frame) else -1
    val inkColor = scheme.lcdInk.tape

    BoxWithConstraints(Modifier.fillMaxWidth().lcdPanel(scheme).padding(6.dp)) {
        val railW = 40.dp
        val gap = 2.dp
        val available = maxWidth - railW - gap
        val cellW = maxOf(CELL_MIN_DP.dp, (available - gap * (ring.steps - 1)) / ring.steps)
        val scroll = rememberScrollState()
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (slot in rows) {
                val color = padColor(kit, slot, inkColor)
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(railW)
                            .height(CELL_H_DP.dp)
                            .background(scheme.field.tape, RoundedCornerShape(3.dp))
                            .tapeClick(label = "HEAR PAD ${padLabel(slot)}") { onAudition(slot) },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(padLabel(slot), TapeType.pixelSmall, color)
                    }
                    Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (step in 0 until ring.steps) {
                            val hit = hitAt[step to slot]
                            val onBeat = step % 4 == 0
                            val fill = when {
                                hit != null -> color.copy(alpha = 0.45f + 0.55f * hit.velocity)
                                onBeat -> scheme.raised.tape
                                else -> scheme.field.tape
                            }
                            val accent = hit != null && hit.velocity >= OrbitPatterns.ACCENT_VELOCITY
                            val edge = when {
                                step == playheadStep -> inkColor
                                accent -> color
                                else -> scheme.grayEdge.tape
                            }
                            StripCell(
                                width = cellW,
                                fill = fill,
                                edge = edge,
                                edgeWidth = if (step == playheadStep || accent) 2.dp else 1.dp,
                                label = "STEP ${step + 1} PAD ${padLabel(slot)}",
                                state = when {
                                    hit == null -> "EMPTY"
                                    accent -> "ACCENT"
                                    hit.velocity < OrbitPatterns.HIT_VELOCITY -> "SOFT HIT"
                                    else -> "HIT"
                                },
                                onTap = { onToggle(slot, step) },
                                onLongPress = { onCycle(slot, step) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One strip cell. A tap places or lifts the hit; a long-press cycles its
 * weight (soft, normal, accent) or places an accent on an empty cell.
 * `combinedClickable` rather than a raw pointerInput so both gestures are
 * real accessibility actions, as the CHOP chips do it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripCell(
    width: androidx.compose.ui.unit.Dp,
    fill: Color,
    edge: Color,
    edgeWidth: androidx.compose.ui.unit.Dp,
    label: String,
    state: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .width(width)
            .height(CELL_H_DP.dp)
            .background(fill, RoundedCornerShape(3.dp))
            .border(edgeWidth, edge, RoundedCornerShape(3.dp))
            .semantics { stateDescription = state }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onLongClickLabel = "ACCENT",
                onLongClick = onLongPress,
                onClick = onTap,
            ),
    )
}

/** A strip cell's least width, in dp: GROOVE's step editor at 16 across, and still a thumb-sized target with a gap. */
private const val CELL_MIN_DP = 22

/** A strip row's height, in dp. */
private const val CELL_H_DP = 26

/** How long a struck hit glows. About a 16th at 150 BPM; shorter than a 16th at anything slower. */
private const val FLARE_SECONDS = 0.1f

/** How long the panel's frame glows when every ring meets on its downbeat. */
private const val MEET_SECONDS = 0.35f

/** Room outside the outermost ring for its name at 12 o'clock, in dp. */
private const val LABEL_MARGIN_DP = 14f

/** How far a lap tick reaches either side of the ring's stroke. */
private const val LAP_TICK_DP = 4f

/** How many bars a snip ring's waveform is drawn in, round the ring. */
private const val WAVE_BUCKETS = 120

/** Half-height of the loudest waveform bar, across the ring's stroke. */
private const val WAVE_AMP_DP = 9f

/** How many edits UNDO can step back through. */
private const val UNDO_DEPTH = 40

/** A held BPM button waits this long before it runs, then steps every [REPEAT_EVERY_MS]. */
private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 90L

/** Quiet after the last BPM tap before the set saves and its snips refit. */
private const val BPM_SETTLE_MS = 400L

/** Below this, a tempo estimate is numerology (see [Tempo]) and no offer is made. */
private const val TEMPO_TRUST = 0.3f

/** As much of a snip as the tempo estimate reads — the bank's own cap. */
private const val TEMPO_MAX_SEC = 30f

/** A snip ring's own tempo, offered to the set once when the ring lands. */
private data class TempoOffer(val ringName: String, val sampleFile: String, val bpm: Float, val frames: Int)

/** Where display slot [i] of [count] sits inside a [w]×[h] canvas, and the inverse for taps. */
private class RingGeometry(w: Float, h: Float, private val count: Int, density: Float) {
    val centre = Offset(w / 2f, h / 2f)
    // 14dp in from the edge, not 8: the ring's label sits 9dp outside it
    // at 12 o'clock and is about 9dp tall, so anything less puts the
    // outermost label above the canvas.
    private val outer = minOf(w, h) / 2f - LABEL_MARGIN_DP * density
    private val minSpacing = 26f * density
    private val spacing = if (count <= 1) 0f else minOf(minSpacing, (outer * 0.72f) / (count - 1))

    fun radius(i: Int): Float = outer - (count - 1 - i) * spacing

    fun point(r: Float, phase: Double): Offset {
        val angle = -PI / 2 + phase * 2 * PI
        return Offset(centre.x + (r * cos(angle)).toFloat(), centre.y + (r * sin(angle)).toFloat())
    }

    /** The display slot nearest [pos], or null when the tap is nowhere near a ring. */
    fun ringAt(pos: Offset): Int? {
        if (count == 0) return null
        val d = hypot(pos.x - centre.x, pos.y - centre.y)
        var best: Int? = null
        var bestGap = Float.MAX_VALUE
        for (i in 0 until count) {
            val gap = abs(d - radius(i))
            if (gap < bestGap) { bestGap = gap; best = i }
        }
        val slop = if (count <= 1) minSpacing else maxOf(spacing / 2f, 12f)
        return if (bestGap <= slop) best else null
    }
}

@Composable
private fun HeaderChip(
    label: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    description: String? = null,
    onClick: () -> Unit,
) {
    val ink = if (!enabled) scheme.ink3.tape else if (accent) scheme.accent.tape else scheme.ink.tape
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, if (accent && enabled) scheme.accent.tape else scheme.ink2.tape, RoundedCornerShape(3.dp))
            .tapeClick(label = description ?: if (label == "▶") "PLAY" else if (label == "■") "STOP" else null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, ink)
    }
}

@Composable
private fun SmallChip(
    label: String,
    scheme: Scheme,
    accent: Boolean = false,
    enabled: Boolean = true,
    /** What a screen reader says for it, when the label alone would not do ("−", "⚄ DICE"). */
    description: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .background(scheme.field.tape, RoundedCornerShape(4.dp))
            .border(1.dp, if (accent && enabled) scheme.accent.tape else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            .tapeClick(label = description ?: label, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (!enabled) scheme.ink3.tape else if (accent) scheme.accent.tape else scheme.ink2.tape)
    }
}

/**
 * The SPAN chip: what a ring's turn is measured in. A tap moves to the
 * next span round the five; a long-press opens them as a picker. Both
 * gestures are accessibility actions, as the strip's cells do it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpanChip(span: OrbitSpan, scheme: Scheme, onNext: () -> Unit, onPick: () -> Unit) {
    val spanned = span != OrbitSpan.FREE
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .background(scheme.field.tape, RoundedCornerShape(4.dp))
            .border(1.dp, if (spanned) scheme.accent.tape else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            .semantics { contentDescription = "SPAN: ${span.label}" }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "NEXT SPAN",
                onLongClickLabel = "PICK A SPAN",
                onLongClick = onPick,
                onClick = onNext,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(if (spanned) "SPAN ${span.label}" else "SPAN FREE", TapeType.pixel, if (spanned) scheme.accent.tape else scheme.ink2.tape)
    }
}

@Composable
private fun PadChip(label: String, color: Color, chosen: Boolean, used: Boolean, scheme: Scheme, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .background(if (chosen) color else scheme.field.tape, RoundedCornerShape(4.dp))
            .border(if (used) 2.dp else 1.dp, if (chosen || used) color else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            .tapeClick(label = "PAD $label", onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixelSmall, if (chosen) scheme.lcd.tape else scheme.ink2.tape)
    }
}

@Composable
private fun ActionButton(
    label: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    description: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(Layout.MIN_HIT_TARGET.dp)
            .background(scheme.field.tape, RoundedCornerShape(6.dp))
            .border(1.dp, if (accent) scheme.accent.tape else scheme.grayEdge.tape, RoundedCornerShape(6.dp))
            .tapeClick(label = description, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (!enabled) scheme.ink3.tape else if (accent) scheme.accent.tape else scheme.ink2.tape)
    }
}

/**
 * An [ActionButton] that steps once on touch and keeps stepping while
 * held — [REPEAT_AFTER_MS] before the run starts, then every
 * [REPEAT_EVERY_MS] — the way a hardware tempo button runs. The pointer
 * loop is KIT's own press-and-hold shape; for a screen reader the button
 * is a plain click that steps once, under [description].
 */
@Composable
private fun RepeatButton(
    label: String,
    description: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    onStep: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The pointer loop is keyed once; the latest step reaches it through this.
    val step by rememberUpdatedState(onStep)
    Box(
        modifier
            .height(Layout.MIN_HIT_TARGET.dp)
            .background(scheme.field.tape, RoundedCornerShape(6.dp))
            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(6.dp))
            .semantics {
                contentDescription = description
                onClick { step(); true }
            }
            .pointerInput(Unit) {
                while (true) {
                    val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                    step()
                    val run = scope.launch {
                        delay(REPEAT_AFTER_MS)
                        while (isActive) {
                            step()
                            delay(REPEAT_EVERY_MS)
                        }
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                        }
                    }
                    run.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink2.tape)
    }
}
