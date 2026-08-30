package com.snipsnap.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Transients
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.TapeDeckModel
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `Copy.COMMIT_LINES[2]` is `"CLEAN CUT. NICE EARS."` — the exact toast the
 * brief and `design/HANDOFF.md` specify for a coast dying onto an onset.
 * There's no dedicated constant for it (`COMMIT_LINES` exists for the
 * COMMIT button's own rotation); reusing that index keeps this screen
 * honest to "copy constants, never string literals" without adding a
 * `:shell` constant for a single reuse elsewhere.
 */
private const val SNAP_TOAST_INDEX = 2

/** How long a reel has to be held before it counts as the pencil-rewind long-press. */
private const val PENCIL_LONG_PRESS_MS = 500L

/** Everything the deck needs once a WAV is on it. */
private class LoadedTape(
    val samples: FloatArray,
    val sampleRate: Int,
    val onsets: IntArray,
    val peaks: PeaksPyramid,
)

/**
 * The TAPE screen: drag-under-a-fixed-needle scrubbing over the open kit's
 * longest sample, with the coast/snap/pencil-rewind physics driven entirely
 * by `:shell`'s tested [TapeDeckModel]. This file only renders it and
 * forwards gestures, plus a minimal unity-speed [TapeVoice] for playback.
 */
@Composable
fun TapeScreen(
    entry: KitShelf.Entry?,
    onToast: (String) -> Unit,
    onCommit: (IntRange) -> Unit,
) {
    val scheme = LocalScheme.current

    if (entry == null || entry.kit.pads.isEmpty()) {
        EmptyDeck(scheme)
        return
    }

    var loaded by remember(entry.dir) { mutableStateOf<LoadedTape?>(null) }
    var failed by remember(entry.dir) { mutableStateOf(false) }

    LaunchedEffect(entry.dir) {
        loaded = null
        failed = false
        val result = withContext(Dispatchers.IO) { loadLongestTape(entry) }
        if (result == null) failed = true else loaded = result
    }

    val tapeData = loaded
    if (tapeData == null) {
        // Either still decoding, or nothing on the kit was a readable WAV —
        // the empty-shelf face covers both; a blank LCD for the moment it
        // takes to read a file is the honest state to show in between.
        if (failed) EmptyDeck(scheme) else Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }

    TapeDeckContent(entry, tapeData, onToast, onCommit)
}

@Composable
private fun EmptyDeck(scheme: Scheme) {
    Box(
        Modifier
            .fillMaxSize()
            .lcdPanel(scheme)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(Copy.EMPTY_SHELF, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
    }
}

/** Reads every pad's WAV and keeps the longest, mixed to mono. */
private fun loadLongestTape(entry: KitShelf.Entry): LoadedTape? {
    var longest: Snip? = null
    for (pad in entry.kit.pads) {
        val file = File(entry.dir, pad.sampleFile)
        if (!file.isFile) continue
        val mono = try {
            Cleanup.toMono(WavReader.read(file))
        } catch (e: Exception) {
            continue
        }
        if (longest == null || mono.frameCount > longest.frameCount) longest = mono
    }
    val chosen = longest ?: return null
    val onsets = Transients.detect(chosen).map { it.frame }.sorted().toIntArray()
    val peaks = PeaksPyramid.fromSnip(chosen)
    return LoadedTape(chosen.samples, chosen.sampleRate, onsets, peaks)
}

@Composable
private fun TapeDeckContent(
    entry: KitShelf.Entry,
    tapeData: LoadedTape,
    onToast: (String) -> Unit,
    onCommit: (IntRange) -> Unit,
) {
    val scheme = LocalScheme.current

    val model = remember(tapeData) {
        TapeDeckModel(tapeData.samples, tapeData.sampleRate, tapeData.onsets)
    }
    val voice = remember(tapeData) { TapeVoice(tapeData.samples, tapeData.sampleRate) }
    DisposableEffect(voice) {
        onDispose { voice.release() }
    }

    var tick by remember(model) { mutableStateOf(0) }
    var commitIndex by remember(model) { mutableStateOf(0) }

    // The model advances outside Compose's snapshot system (it's plain
    // Kotlin, tested on its own), so this is what keeps the LCDs, reels and
    // waveform in step with it: a `withFrameNanos` clock converts elapsed
    // wall time to elapsed audio frames (carrying the fractional remainder
    // so the conversion doesn't drift) and calls `model.step()`, then bumps
    // `tick` so the rest of this composable — which reads it below —
    // recomposes every animation frame.
    LaunchedEffect(model, voice) {
        var lastNanos = withFrameNanos { it }
        var carryFrames = 0.0
        while (isActive) {
            withFrameNanos { now ->
                val dtNanos = (now - lastNanos).coerceAtLeast(0)
                lastNanos = now
                val exact = dtNanos.toDouble() * model.sampleRate / 1_000_000_000.0 + carryFrames
                val frames = exact.toInt()
                carryFrames = exact - frames
                if (frames > 0) {
                    for (event in model.step(frames)) {
                        when (event) {
                            is TapeDeckModel.Event.SnappingToOnset ->
                                onToast(Copy.COMMIT_LINES[SNAP_TOAST_INDEX])
                            TapeDeckModel.Event.HitEnd -> voice.stop()
                            TapeDeckModel.Event.PencilDone -> onToast(Copy.PENCIL_DONE)
                        }
                    }
                }
                tick++
            }
        }
    }
    // Establishes this composable's read of `tick` so it recomposes on
    // every frame bumped above.
    val frameTick = tick

    fun stopVoice() {
        voice.stop()
    }

    fun onPlayStop() {
        model.togglePlay()
        if (model.playing) voice.start(model.position.toInt()) else voice.stop()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CassetteRow(entry, model, frameTick, onToast, ::stopVoice)
        WaveformLcd(model, tapeData.onsets, tapeData.peaks, scheme, ::stopVoice) {
            onToast(Copy.COMMIT_LINES[SNAP_TOAST_INDEX])
        }
        ReadoutRow(model, onToast)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DeckButton("IN", Modifier.weight(1f)) { model.setIn() }
            DeckButton("OUT", Modifier.weight(1f)) { model.setOut() }
            DeckButton(model.zoomLabel, Modifier.weight(1f)) { model.cycleZoom() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WindButton("◄◄", Modifier.weight(1f), -1, model, ::stopVoice)
            DeckButton(if (model.playing) "■ STOP" else "▶ PLAY", Modifier.weight(1f)) { onPlayStop() }
            WindButton("▶▶", Modifier.weight(1f), 1, model, ::stopVoice)
        }
        DeckButton(
            "COMMIT",
            Modifier
                .fillMaxWidth()
                .height(Layout.PRIMARY_ACTION_H.dp),
            active = model.hasSelection,
        ) {
            val range = model.commitSelection()
            if (range != null) {
                onCommit(range)
                onToast(Copy.rotating(Copy.COMMIT_LINES, commitIndex))
                commitIndex++
            } else {
                onToast(Copy.COMMIT_NEEDS_SELECTION)
            }
        }
    }
}

@Composable
private fun ReadoutRow(model: TapeDeckModel, onToast: (String) -> Unit) {
    val scheme = LocalScheme.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .weight(1.3f)
                .height(Layout.LCD_HEADER_MAX_H.dp)
                .lcdPanel(scheme)
                .tapeClick {
                    model.toggleOdometer()
                    onToast(if (model.odometer) Copy.ODOMETER_ON else Copy.ODOMETER_OFF)
                }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText(model.positionReadout, TapeType.lcdReadout, scheme.lcdInk.tape)
        }
        Box(
            Modifier
                .weight(1f)
                .height(Layout.LCD_HEADER_MAX_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText(model.lengthReadout, TapeType.lcdReadout, scheme.amber.tape)
        }
    }
}

@Composable
private fun DeckButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = LocalScheme.current
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .tapeClick(onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (active) scheme.ink.tape else scheme.ink2.tape)
    }
}

/** WIND ◀◀ / ▶▶: fires while held, stops the instant the finger lifts. */
@Composable
private fun WindButton(
    label: String,
    modifier: Modifier,
    direction: Int,
    model: TapeDeckModel,
    onStop: () -> Unit,
) {
    val scheme = LocalScheme.current
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .pointerInput(model, direction) {
                detectTapGestures(
                    onPress = {
                        onStop()
                        model.windStart(direction)
                        tryAwaitRelease()
                        model.windStop(direction)
                    },
                )
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
    }
}

/** The cassette: kit name, two reels, and the pencil-rewind gag on the left one. */
@Composable
private fun CassetteRow(
    entry: KitShelf.Entry,
    model: TapeDeckModel,
    @Suppress("UNUSED_PARAMETER") frameTick: Int,
    onToast: (String) -> Unit,
    onScrubStart: () -> Unit,
) {
    val scheme = LocalScheme.current
    val infiniteTransition = rememberInfiniteTransition(label = "reels")
    val cycleAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(Motion.REEL_SPIN_MS, easing = LinearEasing)),
        label = "reelAngle",
    )
    // Mirrors the running transition while playing; freezes — doesn't
    // reset — the instant playback stops, so the reels look static rather
    // than snapping back to zero.
    var frozenAngle by remember { mutableStateOf(0f) }
    if (model.playing) frozenAngle = cycleAngle

    val fraction = (model.position / model.lengthFrames.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)

    Row(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .lcdPanel(scheme)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Reel(
            fraction = 1f - fraction,
            angle = frozenAngle,
            scheme = scheme,
            modifier = Modifier
                .size(Layout.MIN_HIT_TARGET.dp)
                .pointerInput(model) {
                    detectTapGestures(
                        onPress = {
                            val releasedEarly = withTimeoutOrNull(PENCIL_LONG_PRESS_MS) { tryAwaitRelease() }
                            if (releasedEarly == null) {
                                onScrubStart()
                                val started = model.pencilRewind()
                                onToast(if (started) Copy.PENCIL_STARTED else Copy.PENCIL_AT_TOP)
                                tryAwaitRelease()
                                model.pencilOff()
                            }
                        },
                    )
                },
        )
        TapeText(
            entry.kit.name,
            TapeType.marker,
            scheme.lcdInk.tape,
            Modifier.weight(1f),
            maxLines = 1,
        )
        Reel(
            fraction = fraction,
            angle = frozenAngle,
            scheme = scheme,
            modifier = Modifier.size(Layout.MIN_HIT_TARGET.dp),
        )
    }
}

/** One reel: a wound-tape disc sized to [fraction], spinning at [angle]. */
@Composable
private fun Reel(fraction: Float, angle: Float, scheme: Scheme, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        val hubRadius = radius * 0.45f
        val woundRadius = radius * (0.5f + 0.5f * fraction.coerceIn(0f, 1f))
        drawCircle(color = scheme.grayDark.tape.copy(alpha = 0.6f), radius = woundRadius, center = c)
        drawCircle(color = scheme.ink2.tape, radius = hubRadius, center = c, style = Stroke(width = 2f))
        rotate(degrees = angle, pivot = c) {
            val spoke = hubRadius * 0.9f
            drawLine(scheme.ink2.tape, Offset(c.x - spoke, c.y), Offset(c.x + spoke, c.y), strokeWidth = 2f)
            drawLine(scheme.ink2.tape, Offset(c.x, c.y - spoke), Offset(c.x, c.y + spoke), strokeWidth = 2f)
        }
    }
}

/**
 * The main draggable waveform. The needle is a fixed vertical line at the
 * canvas's horizontal centre — matching both `design/TapeDeck.dc.html` and
 * `prototype/tapedeck.html` (`cx0 = width/2`) — and the tape scrolls under
 * it as `model.position` moves; `Layout.NEEDLE_Y` is GROOVE's fixed *y*
 * for a needle-roll that scrolls vertically; TAPE's needle scrolls
 * horizontally, so it doesn't apply here — the design sources win.
 */
@Composable
private fun WaveformLcd(
    model: TapeDeckModel,
    onsets: IntArray,
    peaks: PeaksPyramid,
    scheme: Scheme,
    onScrubStart: () -> Unit,
    onSnapToast: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .lcdPanel(scheme)
            .pointerInput(model) {
                awaitEachGesture {
                    // Read fresh from AwaitPointerEventScope's own `size`
                    // rather than PointerInputScope's — reading it once at
                    // the top of `pointerInput` risks a 0px width if layout
                    // hasn't landed yet, and that width would be baked in
                    // for the modifier's whole lifetime since it's keyed
                    // only on `model`.
                    val widthPx = size.width.toFloat()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastPosition = down.position
                    var lastTimeMs = down.uptimeMillis
                    var totalDelta = 0f
                    var dragging = false
                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null) {
                            if (dragging) model.dragEnd()
                            break
                        }
                        if (!change.pressed) {
                            if (dragging) {
                                if (model.dragEnd() != null) onSnapToast()
                            } else {
                                onScrubStart()
                                val frame = frameAtX(change.position.x, widthPx, model)
                                model.seekTo(model.snapPoint(frame))
                            }
                            change.consume()
                            break
                        }
                        val dx = change.position.x - lastPosition.x
                        val dtMs = (change.uptimeMillis - lastTimeMs).coerceAtLeast(1L).toDouble()
                        totalDelta += abs(dx)
                        if (!dragging && totalDelta > viewConfiguration.touchSlop) {
                            dragging = true
                            onScrubStart()
                            model.dragStart()
                        }
                        if (dragging) model.dragBy(dx.toDouble(), dtMs)
                        change.consume()
                        lastPosition = change.position
                        lastTimeMs = change.uptimeMillis
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerX = w / 2f
            val centerY = h / 2f
            val framesPerPixel = model.sampleRate.toDouble() / model.pxPerSec.toDouble()
            // The unclamped visible-frame range is the mapping's authority —
            // it's what the needle, onset ticks and selection brackets below
            // all use. Clamping *this* range to the tape (rather than
            // stretching a clamped range to the full canvas width) is what
            // keeps the waveform bars aligned with them near either end,
            // including the deck's own opening position (0).
            val visibleStart = model.position - centerX * framesPerPixel
            val visibleEnd = model.position + (w - centerX) * framesPerPixel
            val clampedStart = visibleStart.coerceAtLeast(0.0)
            val clampedEnd = visibleEnd.coerceAtMost(model.lengthFrames.toDouble())

            if (model.hasSelection) {
                val x0 = centerX + (model.inFrame - model.position) / framesPerPixel
                val x1 = centerX + (model.outFrame - model.position) / framesPerPixel
                drawRect(
                    color = scheme.accent.tape.copy(alpha = 0.27f),
                    topLeft = Offset(x0.toFloat(), 0f),
                    size = Size((x1 - x0).toFloat().coerceAtLeast(0f), h),
                )
                drawLine(scheme.accent.tape, Offset(x0.toFloat(), 0f), Offset(x0.toFloat(), h), strokeWidth = 2f)
                drawLine(scheme.accent.tape, Offset(x1.toFloat(), 0f), Offset(x1.toFloat(), h), strokeWidth = 2f)
            }

            if (clampedEnd > clampedStart) {
                val barStep = 3f
                val xStart = (centerX + (clampedStart - model.position) / framesPerPixel).toFloat()
                val columnCount = max(1, (((clampedEnd - clampedStart) / framesPerPixel) / barStep).toInt())
                val columns = peaks.columns(clampedStart.toInt(), clampedEnd.toInt(), columnCount)
                val halfHeight = h / 2f
                for ((i, col) in columns.withIndex()) {
                    val x = xStart + i * barStep
                    val top = (centerY - col.max * halfHeight).coerceIn(0f, h)
                    val bottom = (centerY - col.min * halfHeight).coerceIn(0f, h)
                    drawRect(
                        color = scheme.lcdInk.tape,
                        topLeft = Offset(x, top),
                        size = Size(2f, (bottom - top).coerceAtLeast(1f)),
                    )
                }
            }

            for (onset in onsets) {
                val x = (centerX + (onset - model.position) / framesPerPixel).toFloat()
                if (x < -4f || x > w + 4f) continue
                drawLine(scheme.warn.tape, Offset(x, h - 10f), Offset(x, h - 2f), strokeWidth = 2f)
            }

            drawLine(scheme.warn.tape, Offset(centerX, 0f), Offset(centerX, h), strokeWidth = 2f)
        }
    }
}

/** Screen-x to tape frame, inverse of the waveform's own draw mapping. */
private fun frameAtX(x: Float, widthPx: Float, model: TapeDeckModel): Int {
    val center = widthPx / 2f
    val framesPerPixel = model.sampleRate.toDouble() / model.pxPerSec.toDouble()
    val frame = model.position + (x - center) * framesPerPixel
    return frame.toInt().coerceIn(0, (model.lengthFrames - 1).coerceAtLeast(0))
}
