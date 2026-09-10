package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.Kit
import com.snipsnap.loop.Orbit
import com.snipsnap.loop.OrbitBank
import com.snipsnap.loop.OrbitClock
import com.snipsnap.loop.OrbitEngine
import com.snipsnap.loop.OrbitHit
import com.snipsnap.loop.OrbitMode
import com.snipsnap.loop.OrbitPresets
import com.snipsnap.loop.OrbitSet
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
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
 * off that thread, one atomic swap.
 *
 * Interaction, in full: tap a ring to pick it; tap a step on the picked
 * pattern ring to put the chosen pad there (again to take it off); the
 * panel below changes the ring's step count, its mode (SPEED: same needle
 * speed, a bigger ring takes longer — LAP: every ring once a bar), and
 * whether it is heard. A snip ring wraps a capture from the SNIPS shelf
 * round its own length.
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
    var padSlot by remember(kitDir) { mutableIntStateOf(kit.pads.minOfOrNull { it.slot } ?: 1) }
    var snipIndex by remember(kitDir) { mutableIntStateOf(0) }
    var snips by remember(kitDir) { mutableStateOf<List<File>>(emptyList()) }

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
            if (event == Lifecycle.Event.ON_STOP) stopPlayback()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** Persist and, if the transport is up, hand the edit to the engine at the next block. */
    fun commit(next: OrbitSet) {
        set = next
        scope.launch {
            preparing = true
            val prepared = withContext(Dispatchers.IO) {
                runCatching { OrbitStore.save(next, kitDir) }
                OrbitBank.prepare(next, source, previous = bank)
            }
            bank = prepared
            engine?.apply(OrbitEngine.Prepared(next, prepared))
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
            val e = OrbitEngine(s, prepared, audioSink)
            sink = audioSink
            engine = e
            audioThread = thread(name = "snipsnap-orbit", isDaemon = true) { e.run() }
            playing = true
            preparing = false
        }
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

    fun toggleHit(index: Int, step: Int) {
        updateRing(index) { ring ->
            val content = ring.content as? PatternOrbit ?: return@updateRing ring
            val existing = content.hits.firstOrNull { it.step == step && it.slot == padSlot }
            val hits = if (existing != null) content.hits - existing else content.hits + OrbitHit(step, padSlot, 0.9f)
            ring.copy(content = content.copy(hits = hits.sortedWith(compareBy({ it.step }, { it.slot }))))
        }
    }

    fun setSteps(index: Int, steps: Int) {
        val clamped = steps.coerceIn(1, Orbit.MAX_STEPS)
        updateRing(index) { ring ->
            // Shrinking a ring drops the hits past its new end rather than
            // refusing: the user asked for a shorter ring, not a lecture.
            val content = ring.content
            val kept = if (content is PatternOrbit) content.copy(hits = content.hits.filter { it.step < clamped }) else content
            ring.copy(steps = clamped, content = kept)
        }
    }

    fun addPatternRing() {
        val s = set ?: return
        if (s.orbits.size >= OrbitSet.MAX_ORBITS) { onToast("EIGHT RINGS IS THE SKY"); return }
        val name = "RING ${s.orbits.size + 1}"
        commit(s.copy(orbits = s.orbits + OrbitPresets.emptyPattern(kitDir.name, name)))
        selected = s.orbits.size
    }

    fun addSnipRing() {
        val s = set ?: return
        if (s.orbits.size >= OrbitSet.MAX_ORBITS) { onToast("EIGHT RINGS IS THE SKY"); return }
        val file = snips.getOrNull(snipIndex) ?: run { onToast("NO SNIPS ON THE SHELF YET"); return }
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
        }
    }

    fun deleteRing(index: Int) {
        val s = set ?: return
        if (index !in s.orbits.indices) return
        commit(s.copy(orbits = s.orbits.filterIndexed { i, _ -> i != index }))
        selected = (index - 1).coerceAtLeast(0)
    }

    fun setBpm(bpm: Float) {
        val s = set ?: return
        commit(s.copy(bpm = bpm.coerceIn(OrbitSet.MIN_BPM, OrbitSet.MAX_BPM)))
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
            HeaderChip("◄ GROOVE", scheme, Modifier.width(72.dp)) { stopPlayback(); onBack() }
            TapeText("ORBIT", TapeType.lcd(21), scheme.lcdInk.tape)
            TapeText(
                if (current != null) "${cycleLabel(current)} · ${current.bpm.roundToInt()} BPM" else "",
                TapeType.lcdSmall,
                scheme.amber.tape,
            )
        }

        if (current == null) {
            Box(Modifier.fillMaxWidth().weight(1f).lcdPanel(scheme), contentAlignment = Alignment.Center) {
                TapeText(refusal ?: "LOADING", TapeType.lcdSmall, scheme.lcdInk.tape)
            }
            return@Column
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RingsCanvas(
                set = current,
                kit = kit,
                frame = frame,
                playing = playing,
                selected = selected,
                scheme = scheme,
                onTapRing = { index -> selected = index },
                onTapStep = { index, step -> if (index == selected) toggleHit(index, step) else selected = index },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )

            // The picked ring's panel.
            Column(
                Modifier.fillMaxWidth().sunkenField(scheme).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (ring == null) {
                    TapeText("NO RINGS — ADD ONE BELOW", TapeType.pixel, scheme.ink2.tape)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TapeText(ring.name, TapeType.display, scheme.ink.tape)
                        TapeText(
                            when (ring.content) {
                                is PatternOrbit -> "PADS"
                                is SnipOrbit -> "SNIP"
                            },
                            TapeType.pixelSmall,
                            scheme.ink3.tape,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallChip("−", scheme) { setSteps(selected, ring.steps - 1) }
                        TapeText("${ring.steps} STEPS", TapeType.pixel, scheme.ink.tape, Modifier.width(72.dp))
                        SmallChip("+", scheme) { setSteps(selected, ring.steps + 1) }
                        SmallChip(
                            if (ring.mode == OrbitMode.SAME_SPEED) "SPEED" else "LAP",
                            scheme,
                            accent = ring.mode == OrbitMode.SAME_LAP,
                        ) {
                            updateRing(selected) {
                                it.copy(mode = if (it.mode == OrbitMode.SAME_SPEED) OrbitMode.SAME_LAP else OrbitMode.SAME_SPEED)
                            }
                        }
                        SmallChip(if (ring.engaged) "ON" else "OFF", scheme, accent = ring.engaged) {
                            updateRing(selected) { it.copy(engaged = !it.engaged) }
                        }
                        SmallChip("DEL", scheme) { deleteRing(selected) }
                    }
                    TapeText(
                        if (ring.mode == OrbitMode.SAME_SPEED) {
                            "SPEED: SAME NEEDLE SPEED. A BIGGER RING COMES ROUND LATER."
                        } else {
                            "LAP: ONCE A BAR WHATEVER THE STEPS. ${ring.steps} EVEN HITS AGAINST ${current.lapSteps}."
                        },
                        TapeType.pixelSmall,
                        scheme.ink3.tape,
                        Modifier.fillMaxWidth(),
                    )
                    when (val content = ring.content) {
                        is PatternOrbit -> {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                for (pad in kit.pads.sortedBy { it.slot }) {
                                    val color = Schemes.classColor(pad.drumClass).tape
                                    PadChip(
                                        label = padLabel(pad.slot),
                                        color = color,
                                        chosen = pad.slot == padSlot,
                                        used = content.hits.any { it.slot == pad.slot },
                                        scheme = scheme,
                                    ) { padSlot = pad.slot }
                                }
                            }
                        }
                        is SnipOrbit -> {
                            TapeText("WRAPS ${content.sampleFile.uppercase()} ROUND ${ring.steps} STEPS.", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
                        }
                    }
                }
            }

            // Transport and the ring shelf.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(
                    if (playing) "■ STOP" else "▶ PLAY",
                    scheme,
                    Modifier.weight(1f),
                    enabled = !preparing,
                    accent = !playing,
                ) { if (playing) stopPlayback() else startPlayback() }
                ActionButton("↺ TOP", scheme, Modifier.weight(1f), enabled = playing) { engine?.rewind() }
                ActionButton("BPM −", scheme, Modifier.weight(1f)) { setBpm(current.bpm - 2f) }
                ActionButton("BPM +", scheme, Modifier.weight(1f)) { setBpm(current.bpm + 2f) }
            }
            // The ring shelf: add a pad ring, or pick a snip and add it as one.
            // The chooser lives between the two buttons so the whole screen
            // fits a phone without scrolling, as the artboards lay it out.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton("+ PAD RING", scheme, Modifier.weight(1f)) { addPatternRing() }
                if (snips.isNotEmpty()) {
                    SmallChip("◄", scheme) { snipIndex = (snipIndex - 1 + snips.size) % snips.size }
                    Column(
                        Modifier
                            .weight(1.3f)
                            .height(Layout.MIN_HIT_TARGET.dp)
                            .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(6.dp)),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TapeText("SNIP", TapeType.pixelSmall, scheme.ink3.tape)
                        TapeText(SnipStore.displayName(snips[snipIndex]).uppercase(), TapeType.pixel, scheme.amber.tape)
                    }
                    SmallChip("►", scheme) { snipIndex = (snipIndex + 1) % snips.size }
                } else {
                    Box(
                        Modifier
                            .weight(1.3f)
                            .height(Layout.MIN_HIT_TARGET.dp)
                            .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
                            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText("NO SNIPS YET", TapeType.pixelSmall, scheme.ink3.tape)
                    }
                }
                ActionButton("+ SNIP RING", scheme, Modifier.weight(1f), enabled = snips.isNotEmpty()) { addSnipRing() }
            }
            TapeText(
                "TAP A RING TO PICK IT · TAP A STEP TO PLACE THE PAD · INNER RINGS COME ROUND FIRST · ALL BACK ON THE DOWNBEAT EVERY ${cycleLabel(current)}.",
                TapeType.pixelSmall,
                scheme.ink3.tape,
                Modifier.fillMaxWidth(),
                maxLines = 2,
            )
        }
    }
}

/** "15 BARS", or "1 BAR" — the realignment length the loop grid's bounce also reports. */
private fun cycleLabel(set: OrbitSet): String {
    val bars = OrbitClock.cycleBars(set)
    val whole = bars.roundToInt()
    return if (abs(bars - whole) < 1e-9) (if (whole == 1) "1 BAR" else "$whole BARS") else "${"%.1f".format(bars)} BARS"
}

private fun padLabel(slot: Int): String {
    val bank = 'A' + (slot - 1) / 16
    val n = (slot - 1) % 16 + 1
    return "$bank${n.toString().padStart(2, '0')}"
}

/**
 * The rings themselves: index 0 innermost, every ring a circle of its own
 * steps, hits as class-coloured dots, the playhead a bright dot riding
 * round at [OrbitClock.phase]. Twelve o'clock is step 0 and the needle
 * runs clockwise, the way a clock and a record both do.
 */
@Composable
private fun RingsCanvas(
    set: OrbitSet,
    kit: Kit,
    frame: Long,
    playing: Boolean,
    selected: Int,
    scheme: Scheme,
    onTapRing: (Int) -> Unit,
    onTapStep: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenDensity = LocalDensity.current.density
    val classBySlot = remember(kit) { kit.pads.associate { it.slot to it.drumClass } }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TapeType.pixelSmall.copy(fontSize = 7.sp)
    val inkColor = scheme.lcdInk.tape
    val amber = scheme.amber.tape
    val dim = scheme.ink3.tape.copy(alpha = 0.6f)
    val ringColor = scheme.ink2.tape.copy(alpha = 0.7f)

    Box(modifier.lcdPanel(scheme)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(10.dp)
                .pointerInput(set, selected) {
                    detectTapGestures { pos ->
                        val geometry = RingGeometry(size.width.toFloat(), size.height.toFloat(), set.orbits.size, screenDensity)
                        val index = geometry.ringAt(pos) ?: return@detectTapGestures
                        val ring = set.orbits[index]
                        if (ring.content is PatternOrbit) {
                            onTapStep(index, geometry.stepAt(pos, ring.steps))
                        } else {
                            onTapRing(index)
                        }
                    }
                },
        ) {
            val geometry = RingGeometry(size.width, size.height, set.orbits.size, screenDensity)
            val centre = geometry.centre
            for ((i, ring) in set.orbits.withIndex()) {
                val r = geometry.radius(i)
                val picked = i == selected
                val isSnip = ring.content is SnipOrbit
                val strokeW = (if (picked) 2.5f else 1.25f) * screenDensity
                drawCircle(
                    color = when {
                        !ring.engaged -> dim
                        picked -> amber
                        isSnip -> amber.copy(alpha = 0.6f)
                        else -> ringColor
                    },
                    radius = r,
                    center = centre,
                    style = Stroke(width = strokeW),
                )
                // Step ticks: a faint dot per step, so the ring's size is readable.
                val tick = 1.2f * screenDensity
                for (s in 0 until ring.steps) {
                    drawCircle(dim, tick, geometry.point(r, s.toDouble() / ring.steps))
                }
                // Hits, flaring for a moment after the needle strikes them:
                // the eye goes to where the sound just happened.
                val content = ring.content
                if (content is PatternOrbit) {
                    for (hit in content.hits) {
                        val drumClass = classBySlot[hit.slot]
                        val color = if (drumClass != null) Schemes.classColor(drumClass).tape else inkColor
                        val dotR = (2.5f + 2.5f * hit.velocity) * screenDensity
                        val at = geometry.point(r, hit.step.toDouble() / ring.steps)
                        val flare = if (ring.engaged && playing) {
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
                // The needle, with a comet tail a twelfth of a lap long so it
                // reads as a moving thing and not as one more dot.
                val phase = OrbitClock.phase(set, ring, frame)
                val needle = if (ring.engaged) inkColor else dim
                val tailRect = Rect(centre - Offset(r, r), Size(r * 2, r * 2))
                val segments = 8
                val segDeg = (TAIL_LAP * 360.0 / segments).toFloat()
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
        }
    }
}

/** The comet tail's length as a fraction of a lap. */
private const val TAIL_LAP = 1.0 / 12.0

/** How long a struck hit glows. About a 16th at 150 BPM; shorter than a 16th at anything slower. */
private const val FLARE_SECONDS = 0.1f

/** Where ring [i] of [count] sits inside a [w]×[h] canvas, and the inverse for taps. */
private class RingGeometry(w: Float, h: Float, private val count: Int, density: Float) {
    val centre = Offset(w / 2f, h / 2f)
    private val outer = minOf(w, h) / 2f - 8f * density
    private val minSpacing = 26f * density
    private val spacing = if (count <= 1) 0f else minOf(minSpacing, (outer * 0.72f) / (count - 1))

    fun radius(i: Int): Float = outer - (count - 1 - i) * spacing

    fun point(r: Float, phase: Double): Offset {
        val angle = -PI / 2 + phase * 2 * PI
        return Offset(centre.x + (r * cos(angle)).toFloat(), centre.y + (r * sin(angle)).toFloat())
    }

    /** The ring nearest [pos], or null when the tap is nowhere near one. */
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

    fun stepAt(pos: Offset, steps: Int): Int {
        var angle = atan2((pos.y - centre.y).toDouble(), (pos.x - centre.x).toDouble()) + PI / 2
        if (angle < 0) angle += 2 * PI
        val phase = angle / (2 * PI)
        return floor(phase * steps + 0.5).toInt() % steps
    }
}

@Composable
private fun HeaderChip(label: String, scheme: Scheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(3.dp))
            .tapeClick(label = null, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
    }
}

@Composable
private fun SmallChip(label: String, scheme: Scheme, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .background(scheme.field.tape, RoundedCornerShape(4.dp))
            .border(1.dp, if (accent) scheme.accent.tape else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            .tapeClick(label = label, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (accent) scheme.accent.tape else scheme.ink2.tape)
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
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(Layout.MIN_HIT_TARGET.dp)
            .background(scheme.field.tape, RoundedCornerShape(6.dp))
            .border(1.dp, if (accent) scheme.accent.tape else scheme.grayEdge.tape, RoundedCornerShape(6.dp))
            .tapeClick(label = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (!enabled) scheme.ink3.tape else if (accent) scheme.accent.tape else scheme.ink2.tape)
    }
}
