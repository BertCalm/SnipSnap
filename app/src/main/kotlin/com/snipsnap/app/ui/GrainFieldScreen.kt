package com.snipsnap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.snipsnap.app.GrainVoice
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.GrainField
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun padTag(slot: Int): String = "A%02d".format(slot)

/** Grains within this many dp of the live touch light up in [Scheme.accent] instead of [Scheme.ink2]. */
private val LIT_RADIUS = 56.dp
private val DOT_RADIUS = 2.5.dp
private val CURSOR_RING_RADIUS = 14.dp
private val CURSOR_RING_STROKE = 1.5.dp

/**
 * GRAIN FIELD: drag across a pad's own timbre landscape and hear it play.
 *
 * [GrainField.analyze] dices the pad's sample into a few hundred short
 * grains, fingerprints each with the classifier's own feature extractor, and
 * projects those fingerprints onto a 2D scatter — nearby dots sound alike,
 * distant ones don't. This screen is that scatter made touchable: a finger
 * anywhere on the field continuously re-triggers whichever grains sit
 * closest to it, for as long as it's held down ([GrainVoice] owns the actual
 * render thread; this composable only ever calls [GrainVoice.setTarget] and
 * [GrainVoice.gate]).
 *
 * **v1 is play-only.** Nothing dragged here is ever written back to a pad or
 * into TAPE — capturing the performance (recording what a drag actually
 * played) is the declared next phase, not built yet.
 *
 * On open: the pad's WAV is read and analyzed off the main thread (a
 * "LISTENING TO THE GRAIN…" LCD covers the wait); a `null` analysis — too
 * little audible material to build a field from — toasts and backs out
 * instead of showing an empty scatter. The [GrainVoice] itself is created
 * only once analysis succeeds, started once, and released whenever this
 * screen leaves composition for any reason (back, a MenuRow tab switch mid
 * drag, or the analyze-null path).
 */
@Composable
fun GrainFieldScreen(
    entry: KitShelf.Entry,
    slot: Int,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val scheme = LocalScheme.current

    var loaded by remember(entry.dir, slot) { mutableStateOf<Pair<Snip, GrainField.GrainMap>?>(null) }
    var failed by remember(entry.dir, slot) { mutableStateOf(false) }

    LaunchedEffect(entry.dir, slot) {
        val pad = entry.kit.pad(slot)
        val snip = pad?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching { Cleanup.toMono(WavReader.read(File(entry.dir, p.sampleFile))) }.getOrNull()
            }
        }
        if (snip == null) {
            failed = true
            return@LaunchedEffect
        }
        val map = withContext(Dispatchers.Default) { GrainField.analyze(snip) }
        if (map == null) failed = true else loaded = snip to map
    }

    // Analyze-null (or the WAV itself missing/unreadable) — same shape as
    // every other "can't do this" path in the app: toast, then leave. Keyed
    // on `failed` alone so this only ever fires once, the moment it flips.
    LaunchedEffect(failed) {
        if (failed) {
            // Named for the dominant real cause: GrainField.analyze needs at
            // least MIN_GRAINS (4) surviving windows of GRAIN_FRAMES (4096)
            // hopped by HOP_FRAMES (2048) — under ~10240 frames (~232ms @
            // 44.1kHz) it returns null regardless of level, so a loud, short
            // hat or snare bounces here far more often than a genuinely
            // quiet sample does.
            onToast("TOO SHORT TO MAP. THE FIELD NEEDS MORE TAPE.")
            onBack()
        }
    }

    // Created once analysis succeeds — remember is keyed on `loaded`'s
    // identity, which only changes the one time this screen's LaunchedEffect
    // above writes it, so this never rebuilds a second voice underneath an
    // already-playing one.
    val current = loaded
    val voice = remember(current) {
        current?.let { (snip, map) -> GrainVoice(snip.samples, snip.sampleRate, map) }
    }
    // Terminal teardown: fires on back, on a MenuRow tab switch that unmounts
    // this screen mid-drag, and on ordinary recomposition-driven disposal —
    // release() is documented safe to call more than once, so this racing
    // with the back chip's own explicit release() below is harmless.
    DisposableEffect(voice) {
        onDispose { voice?.release() }
    }
    // start() exactly once per voice instance — LaunchedEffect only
    // relaunches when `voice`'s identity changes, and GrainVoice.start()
    // itself is idempotent (a compareAndSet guard), so this can't double-arm
    // the render thread even under a fast recomposition.
    LaunchedEffect(voice) { voice?.start() }

    Column(Modifier.fillMaxSize().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChip("◄ KIT", scheme, Modifier.width(64.dp)) {
                voice?.release()
                onBack()
            }
            Spacer(Modifier.weight(1f))
            TapeText("PAD ${padTag(slot)}", TapeType.lcdHeader, scheme.lcdInk.tape)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        if (current == null) {
            Box(
                Modifier.fillMaxWidth().weight(1f).lcdPanel(scheme).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("LISTENING TO THE GRAIN…", TapeType.lcdSmall, scheme.lcdInk.tape)
            }
        } else {
            val (_, map) = current
            GrainFieldCanvas(map, voice!!, scheme, Modifier.fillMaxWidth().weight(1f))
            TapeText(
                "DRAG TO PLAY THE GRAIN FIELD",
                TapeType.pixelSmall,
                scheme.ink2.tape,
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            )
        }
    }
}

/**
 * The scatter itself. [map]'s grain positions are precomputed once (they
 * never change for the lifetime of a given [map]) into [dots]; only the
 * live touch position needs to repaint fast, and repainting it must not
 * recompose this composable — a drag can report at 60Hz, and recomposing a
 * whole `Canvas` + gesture-modifier tree at that rate would be far more work
 * than redrawing it.
 *
 * The fix (same discipline `WaveformLcd`/`TapeScreen`'s `position: () ->
 * Double` already uses): [touch] is read exactly once, inside the [Canvas]
 * draw lambda itself — never at this composable's own body scope. A draw
 * lambda's state reads are tracked against the draw phase, not composition,
 * so writing [touch] from the pointer-input coroutine below invalidates
 * only this Canvas's next draw pass.
 */
@Composable
private fun GrainFieldCanvas(
    map: GrainField.GrainMap,
    voice: GrainVoice,
    scheme: Scheme,
    modifier: Modifier = Modifier,
) {
    val dots = remember(map) { map.grains.map { Offset(it.x, it.y) } }
    // Written only from the pointer-input coroutine below; read only inside
    // the Canvas draw lambda. Do NOT read this anywhere else in this
    // composable's body (e.g. to show a coordinate readout) — doing so would
    // subscribe recomposition to a 60Hz drag, defeating the whole point.
    var touch by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier
            .lcdPanel(scheme)
            .pointerInput(voice) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id

                    fun report(pos: Offset) {
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val clampedX = pos.x.coerceIn(0f, w)
                        val clampedY = pos.y.coerceIn(0f, h)
                        touch = Offset(clampedX, clampedY)
                        val nx = if (w > 0f) (clampedX / w).coerceIn(0f, 1f) else 0f
                        val ny = if (h > 0f) (clampedY / h).coerceIn(0f, 1f) else 0f
                        voice.setTarget(nx, ny)
                    }

                    report(down.position)
                    voice.gate(true)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            voice.gate(false)
                            touch = null
                            change?.consume()
                            break
                        }
                        report(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val dotRadiusPx = DOT_RADIUS.toPx()
        val litRadiusPx = LIT_RADIUS.toPx()
        val litRadiusSqPx = litRadiusPx * litRadiusPx

        // The one deferred state read for this whole draw pass — see this
        // function's own KDoc for why it must happen here and nowhere else.
        val t = touch

        for (dot in dots) {
            val center = Offset(dot.x * w, dot.y * h)
            val lit = if (t != null) {
                val dx = center.x - t.x
                val dy = center.y - t.y
                dx * dx + dy * dy <= litRadiusSqPx
            } else {
                false
            }
            drawCircle(
                color = if (lit) scheme.accent.tape else scheme.ink2.tape,
                radius = dotRadiusPx,
                center = center,
            )
        }

        if (t != null) {
            drawCircle(
                color = scheme.accent.tape,
                radius = CURSOR_RING_RADIUS.toPx(),
                center = t,
                style = Stroke(width = CURSOR_RING_STROKE.toPx()),
            )
        }
    }
}

@Composable
private fun HeaderChip(label: String, scheme: Scheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(3.dp))
            .tapeClick(onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
    }
}
