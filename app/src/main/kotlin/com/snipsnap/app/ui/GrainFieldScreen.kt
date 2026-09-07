package com.snipsnap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
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
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.GrainField
import com.snipsnap.audio.Similar
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun padTag(slot: Int): String = "A%02d".format(slot)

/** Grains within this many dp of the live touch light up in [Scheme.accent] instead of [Scheme.ink2]. */
private val LIT_RADIUS = 56.dp
private val DOT_RADIUS = 2.5.dp
private val CURSOR_RING_RADIUS = 14.dp
private val CURSOR_RING_STROKE = 1.5.dp

/** DUET's auto-cursor ring — a touch larger than [CURSOR_RING_RADIUS] so the two never read as the same mark. */
private val AUTO_CURSOR_RING_RADIUS = 19.dp

/** DUET loop cadence — a control-rate tick, not audio-rate; see [GrainFieldScreen]'s DUET section. */
private const val DUET_TICK_MS = 100L

/** Below this input peak, DUET treats the room as silent and gates the voice off instead of analyzing hiss. */
private const val DUET_SILENCE_LEVEL = 0.02f

/** Auto-cursor smoothing: `new = lerp(prev, projected, DUET_SMOOTHING)` — stops the cursor teleporting between ticks. */
private const val DUET_SMOOTHING = 0.5f

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
 * **DUET** hands the cursor to the armed mic instead of a finger: the header
 * chip (visible only when the map's [GrainField.Projector] survived analysis
 * — see that class's own KDoc for when it doesn't) toggles a control-rate
 * loop that snapshots the live input, fingerprints it with the SAME
 * extractor the map itself was built from, and projects that fingerprint
 * into the map's existing 0..1 space. A finger touching the field always
 * wins — see [GrainFieldCanvas]'s `touching` flag — and DUET resumes the
 * instant it lifts.
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
    onRequestArm: () -> Unit,
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
    // the render thread even under a fast recomposition. `start()`'s own KDoc
    // documents that `buildTrack` can throw if the platform rejects the
    // format — a caught, retryable condition for a caller, not a silent dead
    // screen or an uncaught crash on the class of device that triggers it.
    LaunchedEffect(voice) {
        runCatching { voice?.start() }.onFailure {
            onToast("THE FIELD LOST ITS VOICE")
            onBack()
        }
    }

    val projector = current?.second?.projector
    var duetOn by remember { mutableStateOf(false) }
    val armed by MicSessionService.armed.collectAsState()

    // Written by the finger gesture inside GrainFieldCanvas (down/up only,
    // not per-move), read here so the DUET loop below can stand down the
    // instant a finger takes over — see GrainFieldCanvas's own KDoc for why
    // this is a plain state boolean and not something recomputed per frame.
    val touching = remember { mutableStateOf(false) }
    // Auto-cursor position in the map's normalized 0..1 space, written only
    // by the DUET loop below; read inside GrainFieldCanvas's own Canvas draw
    // lambda, exactly the discipline `touch` already follows in that file.
    val autoPos = remember { mutableStateOf<Offset?>(null) }

    // DUET: the armed mic drives the field's cursor instead of a finger.
    //
    // **Feedback hazard.** If the pad's own render is audible to the phone's
    // mic (a speaker, not headphones), the loop below will happily analyze
    // its own output and chase itself — level-gating (skipping ticks under
    // [DUET_SILENCE_LEVEL]) only keeps a truly silent room silent; it does
    // nothing once the render is loud enough to clear that floor, because at
    // that point the picked-up render IS a genuine, non-silent signal by the
    // same measure real input would be. Headphones are the actual fix; the
    // hint text below says so.
    LaunchedEffect(duetOn, armed) {
        val v = voice
        val p = projector
        if (!duetOn || !armed || v == null || p == null) {
            autoPos.value = null
            return@LaunchedEffect
        }
        try {
            var prevX = 0.5f
            var prevY = 0.5f
            while (true) {
                // The whole tick body, not just the extraction/project step
                // below — any uncaught exception here (level/snapshotTail
                // reads included) would otherwise silently kill this loop
                // while the DUET chip stays lit, looking engaged while doing
                // nothing. One stumble turns DUET back off, with a toast, so
                // the chip's state matches reality again.
                try {
                    if (!v.alive) {
                        // The render thread died on its own (route change,
                        // device hiccup) — GrainVoice.alive is exactly the
                        // signal that used to have no public accessor; keep
                        // polling a dead voice's setTarget/gate forever is
                        // the silent-failure shape this closes.
                        onToast("DUET STUMBLED — OFF")
                        duetOn = false
                        break
                    }
                    if (!touching.value) {
                        val level = MicSessionService.level.value
                        if (level < DUET_SILENCE_LEVEL) {
                            v.gate(false)
                        } else {
                            // CaptureRing.snapshot returns fewer than the
                            // requested frames (not null) when the ring hasn't
                            // filled that far yet — the earliest ticks right
                            // after ARM, most likely. A short/empty window analyzes
                            // fine (FeatureExtractor/Fft both tolerate it) but
                            // its projection is meaningless, so it's skipped
                            // here rather than smoothed into the cursor's path.
                            val raw = MicSessionService.snapshotTail(GrainField.GRAIN_FRAMES)
                            if (raw != null && raw.size >= GrainField.GRAIN_FRAMES) {
                                val projected = runCatching {
                                    withContext(Dispatchers.Default) {
                                        val snip = Snip(raw, channels = 1, sampleRate = MicSessionService.SAMPLE_RATE)
                                        p.project(Similar.vector(FeatureExtractor.extract(snip)))
                                    }
                                }.getOrNull()
                                // Re-read `touching` LIVE here, after the suspend
                                // above returns — the gate at the top of this
                                // `if` was checked before that (real, non-trivial)
                                // dispatcher switch, so a finger landing mid-
                                // projection would otherwise still get clobbered
                                // by this stale, pre-touch target once execution
                                // resumes (the "re-read live state after
                                // suspension" rule).
                                if (projected != null && !touching.value) {
                                    prevX += (projected.first - prevX) * DUET_SMOOTHING
                                    prevY += (projected.second - prevY) * DUET_SMOOTHING
                                    autoPos.value = Offset(prevX, prevY)
                                    v.setTarget(prevX, prevY)
                                    v.gate(true)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onToast("DUET STUMBLED — OFF")
                    duetOn = false
                    break
                }
                delay(DUET_TICK_MS)
            }
        } finally {
            // Covers both "DUET switched off" and "this loop is being torn
            // down" (armed dropped out from under it, or the screen itself
            // left composition) — a finger already holding the field owns
            // gate() exclusively, so this must not clobber that.
            if (!touching.value) v.gate(false)
        }
    }

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
            if (projector != null) {
                HeaderChip("DUET", scheme, Modifier.width(64.dp), engaged = duetOn) { duetOn = !duetOn }
            } else {
                Spacer(Modifier.width(64.dp))
            }
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
            GrainFieldCanvas(map, voice!!, scheme, touching, autoPos, Modifier.fillMaxWidth().weight(1f))
            if (duetOn && !armed) {
                PrimaryAction(label = "START MIC", enabled = true, onClick = onRequestArm)
            }
            TapeText(
                if (duetOn) {
                    "THE MIC PLAYS THE FIELD. HEADPHONES RECOMMENDED."
                } else {
                    "DRAG TO PLAY THE GRAIN FIELD"
                },
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
 * only this Canvas's next draw pass. [autoPos] (DUET's cursor, written by
 * the caller's control-rate loop) follows the exact same discipline.
 *
 * [touching] is the one signal this composable exports upward: true for as
 * long as a finger is down, set/cleared only on down/up (not per-move, so
 * it doesn't fight the same 60Hz budget [touch] is protected from) — the
 * caller's DUET loop polls it each tick to know whether the manual path
 * currently owns [voice]'s target and gate.
 */
@Composable
private fun GrainFieldCanvas(
    map: GrainField.GrainMap,
    voice: GrainVoice,
    scheme: Scheme,
    touching: MutableState<Boolean>,
    autoPos: MutableState<Offset?>,
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

                    // Finger priority: claim `touching` (and drop any stale
                    // DUET ring) before the manual path's own gate/target
                    // writes below — a DUET tick racing in right now will
                    // see `touching.value == true` and stand down instead.
                    touching.value = true
                    autoPos.value = null
                    report(down.position)
                    voice.gate(true)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            voice.gate(false)
                            touch = null
                            touching.value = false
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
        val a = autoPos.value

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

        // DUET's cursor: a hollow ring at a distinct radius from the touch
        // ring above, so the two never read as the same mark even in the
        // brief window either could be drawn (they're not otherwise
        // expected to coexist — see GrainFieldCanvas's own KDoc on `touching`).
        if (a != null) {
            drawCircle(
                color = scheme.accent.tape,
                radius = AUTO_CURSOR_RING_RADIUS.toPx(),
                center = Offset(a.x * w, a.y * h),
                style = Stroke(width = CURSOR_RING_STROKE.toPx()),
            )
        }
    }
}

@Composable
private fun HeaderChip(
    label: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    // Orthogonal latched/lit look — accent fill + inverted ink — mirroring
    // TapeScreen.kt's DeckButton `engaged` param (IN/OUT), implemented
    // locally here since this file's chips use a bordered-box look, not
    // DeckButton's raised bevel. Every other HeaderChip call leaves this at
    // the default and renders exactly as before.
    engaged: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .then(
                if (engaged) {
                    Modifier.background(scheme.accent.tape, RoundedCornerShape(3.dp))
                } else {
                    Modifier
                },
            )
            .border(1.dp, if (engaged) scheme.accent.tape else scheme.ink2.tape, RoundedCornerShape(3.dp))
            .tapeClick(onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (engaged) scheme.lcd.tape else scheme.ink.tape)
    }
}
