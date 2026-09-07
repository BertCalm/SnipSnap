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
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.TouchSurface
import com.snipsnap.shell.TouchSurface.Mode
import com.snipsnap.shell.TouchSurface.Reading
import java.io.File
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
    var printing by remember { mutableStateOf(false) }
    var engineUp by remember { mutableStateOf(false) }

    DisposableEffect(engine) {
        engineUp = engine.start()
        if (!engineUp) onToast("NO LOW-LATENCY STREAM. THE SURFACE IS SILENT.")
        tilt.start()
        onDispose {
            tilt.stop()
            engine.close()
        }
    }

    // The voice: the open kit's lowest pad, read off the shelf, folded to mono in the engine.
    LaunchedEffect(entry) {
        val pad = entry?.kit?.pads?.minByOrNull { it.slot }
        if (entry == null || pad == null) {
            padName = null
            return@LaunchedEffect
        }
        val snip = withContext(Dispatchers.IO) {
            runCatching { WavReader.read(File(entry.dir, pad.sampleFile)) }.getOrNull()
        }
        if (snip == null) {
            padName = null
            onToast("${pad.displayName.uppercase()} WOULD NOT READ.")
        } else {
            engine.load(snip)
            padName = pad.displayName
        }
    }

    fun finishPrint() {
        val snip = engine.stopPrint()
        printing = false
        if (snip == null || snip.frameCount < engine.sampleRate / 10) {
            onToast("NOTHING PRINTED. HOLD THE SURFACE WHILE IT PRINTS.")
            return
        }
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

    // Screen-rate loop: smooth toward the target, paint, feed the engine.
    LaunchedEffect(engine) {
        val smoother = TouchSurface.SmoothedReading(TouchSurface.Smoother.coefficient(cutoffHz = 12f, rateHz = 60f))
        while (true) {
            withFrameNanos { }
            val smooth = smoother.step(target)
            painted = smooth
            engine.control(mode, smooth, tilt.tilt, gate = target.touching && padName != null)
            if (engine.needsRestart()) engineUp = engine.start()
            if (printing && engine.printState() == SurfaceEngine.PrintState.DONE) finishPrint()
        }
    }

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
                    // A mode change is a different instrument, not a glide between two.
                    target = Reading.REST
                }
            }
            ActionButton(
                label = if (printing) "STOP PRINT" else "PRINT",
                scheme = scheme,
                enabled = engineUp && padName != null,
                modifier = Modifier.weight(1.4f),
            ) {
                if (printing) {
                    finishPrint()
                } else {
                    engine.armPrint(SurfaceEngine.MAX_PRINT_SECONDS)
                    printing = true
                    onToast("PRINTING. PLAY THE SURFACE.")
                }
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
}
