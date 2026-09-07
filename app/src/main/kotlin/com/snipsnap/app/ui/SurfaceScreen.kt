package com.snipsnap.app.ui

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
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
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
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.SurfaceStore
import com.snipsnap.shell.TouchSurface
import com.snipsnap.shell.TouchSurface.Mode
import com.snipsnap.shell.TouchSurface.Reading
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SURFACE — the tactile pad. The open kit's first pad loops under a
 * finger; where the finger is drives the macros (`TouchSurface` in
 * `:shell` does the arithmetic, `SurfaceEngine` the sound), and PRINT
 * writes the performance to TAPE as a new sample the way an SP-404
 * resamples: what you played is now one pad, no DSP to run later.
 *
 * Three modes, one pad:
 *  - XY: one finger, X pitch, Y filter.
 *  - XYZ: a second finger's distance is Z (drive); the roll of the
 *    phone is resonance.
 *  - MORPH: the puck weights four corner states, A B C D.
 *
 * PAD ◄ ► picks which of the kit's pads the surface plays; SET A..D
 * captures the sound under the last touch as a morph corner. Both live
 * in `surface.json` beside the kit (`SurfaceStore`), so a morph you set
 * up is there when you come back.
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
    var padName by remember { mutableStateOf<String?>(null) }
    var padSlot by remember { mutableStateOf<Int?>(null) }
    var settings by remember { mutableStateOf(SurfaceStore.Settings.DEFAULT) }
    var lastHeld by remember { mutableStateOf<Reading?>(null) }
    var printing by remember { mutableStateOf(false) }
    var printToPad by remember { mutableStateOf(false) }
    /** A finished print waiting for a pad to be chosen; the chooser shows while it is set. */
    var pendingPrint by remember { mutableStateOf<Snip?>(null) }
    var landing by remember { mutableStateOf(false) }
    var engineUp by remember { mutableStateOf(false) }

    fun started(up: Boolean) {
        engineUp = up
        if (!up) onToast("NO LOW-LATENCY STREAM. THE SURFACE IS SILENT.")
        else if (engine.isShared()) onToast("SHARED STREAM. A LITTLE MORE LATENCY.")
    }

    DisposableEffect(engine) {
        started(engine.start())
        tilt.start()
        onDispose {
            tilt.stop()
            engine.close()
        }
    }

    // The voice: one of the kit's pads, read off the shelf, folded to mono in the engine.
    suspend fun loadPad(dir: File, pad: KitPad) {
        val snip = withContext(Dispatchers.IO) {
            runCatching { WavReader.read(File(dir, pad.sampleFile)) }.getOrNull()
        }
        if (snip == null) {
            padName = null
            onToast("${pad.displayName.uppercase()} WOULD NOT READ.")
        } else {
            engine.load(snip)
            padName = pad.displayName
            padSlot = pad.slot
        }
    }

    fun pushCorners(corners: List<SurfaceStore.Corner>) {
        corners.forEachIndexed { i, c -> engine.setCorner(i, c.pitch, c.cutoff, c.resonance, c.drive) }
    }

    fun persist(dir: File, next: SurfaceStore.Settings) {
        settings = next
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { SurfaceStore.save(dir, next) } }
                .onFailure { onToast("SURFACE SETTINGS NOT SAVED: ${(it.message ?: "UNREADABLE").uppercase()}.") }
        }
    }

    LaunchedEffect(entry) {
        if (entry == null) {
            padName = null
            padSlot = null
            return@LaunchedEffect
        }
        // The kit's surface settings first (a torn file is the defaults,
        // said aloud), then the pad they name, or the kit's lowest.
        val loaded = withContext(Dispatchers.IO) { runCatching { SurfaceStore.load(entry.dir) } }
        settings = loaded.getOrElse {
            onToast("SURFACE SETTINGS UNREADABLE. USING THE DEFAULTS.")
            SurfaceStore.Settings.DEFAULT
        }
        pushCorners(settings.corners)
        val pads = entry.kit.pads.sortedBy { it.slot }
        val pad = pads.firstOrNull { it.slot == settings.padSlot } ?: pads.firstOrNull()
        if (pad == null) {
            padName = null
            padSlot = null
        } else {
            loadPad(entry.dir, pad)
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

    // SET A..D: the sound under the last touch becomes a morph corner.
    fun setCorner(index: Int) {
        val dir = entry?.dir ?: return
        val held = lastHeld ?: run {
            onToast("TOUCH THE PAD FIRST. SET KEEPS WHAT WAS UNDER THE FINGER.")
            return
        }
        val corner = SurfaceStore.Corner.from(mode, held, tilt.tilt, settings.corners)
        val corners = settings.corners.toMutableList().also { it[index] = corner }
        pushCorners(corners)
        persist(dir, settings.copy(corners = corners))
        onToast("CORNER ${'A' + index} SET.")
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
                onToast("PRINTED ${"%.1f".format(it.seconds)} S TO TAPE.")
                onPrinted()
            }.onFailure {
                onToast("PRINT LOST: ${(it.message ?: "UNREADABLE").uppercase()}.")
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
                    onToast("NOTHING PRINTED. HOLD THE SURFACE WHILE IT PRINTS.")
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
                pendingPrint = null
                onKitUpdated(updated)
                onToast(
                    if (existed) "PAD ${padLabel(slot)} REPLACED WITH THE PRINT. ORIGINAL SLEEPS IN THE BIN."
                    else "PRINTED TO PAD ${padLabel(slot)}.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A layered or chained pad refuses in words; the print is not lost.
                onToast("${(e.message ?: "PAD REFUSED").uppercase()}. PICK ANOTHER PAD.")
            } finally {
                landing = false
            }
        }
    }

    // Screen-rate loop: smooth toward the target, paint, feed the engine.
    LaunchedEffect(engine) {
        val smoother = TouchSurface.SmoothedReading(TouchSurface.Smoother.coefficient(cutoffHz = 12f, rateHz = 60f))
        var lastMode = mode
        while (true) {
            withFrameNanos { }
            if (mode != lastMode) {
                // A mode change is a different instrument, not a glide
                // between two: the painted puck and the engine both jump.
                smoother.snap(target)
                lastMode = mode
            }
            val smooth = smoother.step(target)
            painted = smooth
            if (target.touching) lastHeld = smooth
            engine.control(mode, smooth, tilt.tilt, gate = target.touching && padName != null)
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
                        modifier = Modifier.weight(1f),
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
                    } else if (engine.armPrint(SurfaceEngine.MAX_PRINT_SECONDS)) {
                        printing = true
                        onToast("PRINTING. PLAY THE SURFACE.")
                    } else {
                        onToast("STILL LANDING THE LAST PRINT.")
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
            }

            Spacer(Modifier.height(6.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until 4) {
                    ActionButton(
                        "SET ${'A' + i}",
                        scheme,
                        enabled = padName != null,
                        dimmed = mode != Mode.MORPH,
                        modifier = Modifier.weight(1f),
                    ) { setCorner(i) }
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .lcdPanel(scheme)
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
                    // MORPH: a bar per corner, its length the weight.
                    if (mode == Mode.MORPH) {
                        val bar = 6.dp.toPx()
                        val len = minOf(w, h) * 0.3f
                        drawRect(ink.copy(alpha = 0.7f), Offset(0f, 0f), Size(len * painted.a, bar))
                        drawRect(ink.copy(alpha = 0.7f), Offset(w - len * painted.b, 0f), Size(len * painted.b, bar))
                        drawRect(ink.copy(alpha = 0.7f), Offset(0f, h - bar), Size(len * painted.c, bar))
                        drawRect(ink.copy(alpha = 0.7f), Offset(w - len * painted.d, h - bar), Size(len * painted.d, bar))
                    }
                }
                if (mode == Mode.MORPH) {
                    TapeText("A", TapeType.lcdSmall, ink, Modifier.align(Alignment.TopStart).padding(8.dp))
                    TapeText("B", TapeType.lcdSmall, ink, Modifier.align(Alignment.TopEnd).padding(8.dp))
                    TapeText("C", TapeType.lcdSmall, ink, Modifier.align(Alignment.BottomStart).padding(8.dp))
                    TapeText("D", TapeType.lcdSmall, ink, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                if (padName == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TapeText("OPEN A KIT. THE SURFACE PLAYS ITS FIRST PAD.", TapeType.lcdSmall, scheme.amber.tape)
                    }
                }
                if (printing) {
                    TapeText("● PRINTING", TapeType.lcdSmall, scheme.amber.tape, Modifier.align(Alignment.TopCenter).padding(8.dp))
                }
            }

            Spacer(Modifier.height(6.dp))

            val readout = buildString {
                append("X %.2f  Y %.2f".format(painted.x, painted.y))
                if (mode == Mode.XYZ) append("  Z %.2f".format(painted.z))
                if (mode == Mode.MORPH) append("  A %.2f B %.2f C %.2f D %.2f".format(painted.a, painted.b, painted.c, painted.d))
                if (tilt.available) append("  TILT %.2f".format(tilt.tilt))
                padName?.let { append("  ·  ").append(it.uppercase()) }
            }
            TapeText(readout, TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.fillMaxWidth())
        }

        if (pendingPrint != null) {
            SlotChooserOverlay(
                kit = entry?.kit,
                previewColor = scheme.amber.tape,
                scheme = scheme,
                busy = landing,
                onPick = ::landOnPad,
                // Cancelling is not losing the print: it goes to TAPE instead.
                onCancel = {
                    if (!landing) {
                        pendingPrint?.let { landOnTape(it) }
                        pendingPrint = null
                    }
                },
            )
        }
    }
}

/** A slot as the MPC names it: 1..16 is bank A, 17..32 bank B, and so on. */
private fun padLabel(slot: Int?): String {
    if (slot == null) return ""
    val bank = 'A' + (slot - 1) / 16
    val n = (slot - 1) % 16 + 1
    return "%c%02d".format(bank, n)
}
