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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.KitShelf
import com.snipsnap.app.MicSessionService
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
import com.snipsnap.shell.Dig
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.TapeDeckModel
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long a reel has to be held before it counts as the pencil-rewind long-press. */
private const val PENCIL_LONG_PRESS_MS = 500L

/**
 * Ceiling on one step-loop tick's elapsed time, in nanoseconds (~100ms).
 * `withFrameNanos`'s delta goes stale across any gap in frame delivery —
 * backgrounding the app is the big one (the clock's `lastNanos` doesn't
 * move while the composition is stopped), but a debugger pause or a janky
 * frame have the same shape. Capping the converted frame count here means
 * a stale clock can never fast-forward the tape, regardless of why the gap
 * happened — belt to the lifecycle observer's suspenders below, which stops
 * playback outright on backgrounding.
 */
private const val MAX_STEP_NANOS = 100_000_000L

/** The waveform LCD's floor when it's sharing a short viewport with everything else below it. */
private const val WAVEFORM_MIN_H = 96

/** Everything the deck needs once a WAV is on it. */
private class LoadedTape(
    val samples: FloatArray,
    val sampleRate: Int,
    val onsets: IntArray,
    val peaks: PeaksPyramid,
    /**
     * The file this tape actually came from. Compared against
     * [MicSessionService.lastSnipFile] by [TapeDeckContent]'s idle-reload
     * watcher to tell a genuinely new snip from the one already loaded —
     * without it, every emission of an already-loaded snip (including the
     * StateFlow replay a fresh collector gets on launch) would look like
     * a reason to reload.
     */
    val sourceFile: File,
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
    lastCommitSource: File?,
    onToast: (String) -> Unit,
    onCommit: (File, IntRange) -> Unit,
    onInstantKit: (File, IntRange) -> Unit,
    /** READ AS GROOVE (wave ZZ): the selection, or the whole deck, read as a rhythm onto the open kit. */
    onReadGroove: (File, IntRange) -> Unit,
    /** STEAL THE FEEL (wave ZZ): the same reading kept as timing and accent, poured over the kit's pattern. */
    onStealFeel: (File, IntRange) -> Unit,
    /**
     * Bumped by App when something outside this screen put a new snip on
     * the shelf while TAPE may already be showing — a share-sheet import
     * (F3.1) — so the deck re-resolves its source instead of keeping the
     * tape it had. A fresh composition ignores it; `entry.dir` and the
     * idle-reload watcher stay the other two triggers.
     */
    reloadRequest: Int = 0,
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current

    // No open kit is not an empty deck: a snip — a capture, or a file
    // shared in — plays here without one. Only the kit's-longest-sample
    // fallback needs an entry, so a share into an empty shelf still lands.
    val kitDir = entry?.dir
    var loaded by remember(kitDir) { mutableStateOf<LoadedTape?>(null) }
    var failed by remember(kitDir) { mutableStateOf(false) }
    // Bumped by TapeDeckContent's idle-reload watcher to force a fresh
    // call to loadLongestTape without changing `kitDir` (the other
    // triggers below being the app's own reloadRequest) — see that
    // watcher's own KDoc for what bumps it and why it's gated on the deck
    // being idle.
    var reloadToken by remember(kitDir) { mutableStateOf(0) }

    LaunchedEffect(kitDir, reloadToken, reloadRequest) {
        loaded = null
        failed = false
        val result = withContext(Dispatchers.IO) {
            loadLongestTape(entry, context.filesDir, lastCommitSource)
        }
        if (result == null) failed = true else loaded = result
    }

    val tapeData = loaded
    if (tapeData == null) {
        // Either still decoding, or nothing resolved — no snip, no
        // last-commit source, and nothing on the (possibly empty) kit was
        // a readable WAV — the empty-shelf face covers both; a blank LCD
        // for the moment it takes to read a file is the honest state to
        // show in between.
        if (failed) EmptyDeck(scheme) else Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }

    TapeDeckContent(
        entry,
        tapeData,
        onToast,
        onCommit,
        onInstantKit,
        onReadGroove,
        onStealFeel,
        onIdleReload = { reloadToken++ },
    )
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

/**
 * TAPE's load-source priority, retroactive-snip Task 4: the newest snip
 * anywhere on the phone if one exists — a fresh capture always wins, since
 * surfacing it without a manual re-open is the entire point of the mic
 * spine — else the file the last COMMIT actually scrubbed (so returning to
 * TAPE after a trim resumes where the user left off), else the open kit's
 * longest sample (TAPE's original, pre-capture fallback). A snip loads
 * exactly like any other WAV: [WavReader] + [Cleanup.toMono] is the same
 * path for all three sources.
 */
private fun loadLongestTape(entry: KitShelf.Entry?, filesDir: File, lastCommitSource: File?): LoadedTape? {
    for (file in listOfNotNull(SnipStore.newest(filesDir), lastCommitSource)) {
        val mono = readMono(file) ?: continue
        return buildLoadedTape(file, mono)
    }
    if (entry == null) return null
    val (file, mono) = loadLongestFromKit(entry) ?: return null
    return buildLoadedTape(file, mono)
}

private fun readMono(file: File): Snip? {
    if (!file.isFile) return null
    return try {
        Cleanup.toMono(WavReader.read(file))
    } catch (e: Exception) {
        null
    }
}

/** Every pad's WAV, mixed to mono, keeping the longest — TAPE's fallback when neither a snip nor a last-commit source resolves. */
private fun loadLongestFromKit(entry: KitShelf.Entry): Pair<File, Snip>? {
    var longestFile: File? = null
    var longest: Snip? = null
    for (pad in entry.kit.pads) {
        val file = File(entry.dir, pad.sampleFile)
        val mono = readMono(file) ?: continue
        if (longest == null || mono.frameCount > longest.frameCount) {
            longest = mono
            longestFile = file
        }
    }
    val file = longestFile ?: return null
    return file to (longest ?: return null)
}

private fun buildLoadedTape(file: File, chosen: Snip): LoadedTape {
    val onsets = Transients.detect(chosen).map { it.frame }.sorted().toIntArray()
    val peaks = PeaksPyramid.fromSnip(chosen)
    return LoadedTape(chosen.samples, chosen.sampleRate, onsets, peaks, file)
}

@Composable
private fun TapeDeckContent(
    entry: KitShelf.Entry?,
    tapeData: LoadedTape,
    onToast: (String) -> Unit,
    onCommit: (File, IntRange) -> Unit,
    onInstantKit: (File, IntRange) -> Unit,
    onReadGroove: (File, IntRange) -> Unit,
    onStealFeel: (File, IntRange) -> Unit,
    onIdleReload: () -> Unit,
) {
    val scheme = LocalScheme.current
    val digScope = rememberCoroutineScope()
    // DIG runs on this screen: it only moves the deck's own IN and OUT.
    var digging by remember(tapeData) { mutableStateOf(false) }

    val model = remember(tapeData) {
        TapeDeckModel(tapeData.samples, tapeData.sampleRate, tapeData.onsets)
    }
    val voice = remember(tapeData) { TapeVoice(tapeData.samples, tapeData.sampleRate) }
    DisposableEffect(voice) {
        onDispose { voice.release() }
    }

    // Retroactive-snip Task 4: a fresh SNIP anywhere in the app should
    // surface here without forcing the user to back out of TAPE and back
    // in — but NEVER mid-edit. If a new snip lands while the deck is
    // playing or holding an IN/OUT selection, this is a no-op: nothing
    // re-triggers the watcher on its own (`lastSnipFile` only changes on
    // the NEXT snip), so leaving TAPE and returning — or stopping playback
    // / clearing the selection while still here — is what actually picks
    // it up. `file != tapeData.sourceFile` is what stops this from firing
    // on the file already loaded, including the StateFlow replay a fresh
    // collector gets immediately on launch. Keyed on `model` (not `Unit`)
    // so a reload — which swaps `tapeData`, and therefore `model`, via the
    // `remember(tapeData)` above — restarts this collector against the
    // live model instead of leaving it closed over a stale, already-
    // replaced one.
    LaunchedEffect(model) {
        MicSessionService.lastSnipFile.collect { file ->
            if (file != null && file != tapeData.sourceFile && !model.playing && !model.hasSelection) {
                onIdleReload()
            }
        }
    }

    // Home-during-play would otherwise leave the voice's thread writing to
    // an AudioTrack nobody can hear forever (no media session, no way for
    // the system to stop it) — ON_STOP is the app losing the foreground,
    // which is exactly when a tape deck should stop rolling. Deliberately
    // does NOT resume on ON_START/ON_RESUME: the user presses PLAY again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (model.playing) model.togglePlay()
                voice.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                val dtNanos = (now - lastNanos).coerceIn(0, MAX_STEP_NANOS)
                lastNanos = now
                val exact = dtNanos.toDouble() * model.sampleRate / 1_000_000_000.0 + carryFrames
                val frames = exact.toInt()
                carryFrames = exact - frames
                if (frames > 0) {
                    for (event in model.step(frames)) {
                        when (event) {
                            is TapeDeckModel.Event.SnappingToOnset ->
                                onToast(Copy.SNAPPED)
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
        WaveformLcd(
            model,
            tapeData.onsets,
            tapeData.peaks,
            scheme,
            ::stopVoice,
            onSnapToast = { onToast(Copy.SNAPPED) },
            // Absorbs whatever room the fixed-height rows above and below
            // it don't need, rather than a hardcoded height that clips
            // COMMIT off-screen on a short viewport (landscape, split
            // screen) — WAVEFORM_MIN_H keeps it from collapsing to nothing.
            modifier = Modifier.weight(1f, fill = true).heightIn(min = WAVEFORM_MIN_H.dp),
        )
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DeckButton(
                "COMMIT",
                Modifier
                    .weight(1f)
                    .height(Layout.PRIMARY_ACTION_H.dp),
                active = model.hasSelection,
            ) {
                val range = model.commitSelection()
                if (range != null) {
                    // tapeData.sourceFile, not a re-derived "open kit's longest
                    // sample" — TapeCommit's own contract is that `range`'s
                    // frames only mean something against the exact file TAPE
                    // was scrubbing when COMMIT fired, and under the new
                    // source priority that's frequently a snip, not a pad WAV.
                    onCommit(tapeData.sourceFile, range)
                    onToast(Copy.rotating(Copy.COMMIT_LINES, commitIndex))
                    commitIndex++
                } else {
                    onToast(Copy.COMMIT_NEEDS_SELECTION)
                }
            }
            // INSTANT KIT (F2.2): the one tap. The selection when there is
            // one, else the whole deck, chopped with the defaults and on the
            // grid without the review - CHOP's own result, nothing touched.
            DeckButton(
                "INSTANT KIT ▸",
                Modifier
                    .weight(1f)
                    .height(Layout.PRIMARY_ACTION_H.dp),
                active = true,
            ) {
                // Stop the transport as well as the voice: the deck would
                // otherwise keep rolling silently under the busy overlay.
                if (model.playing) model.togglePlay()
                stopVoice()
                val range = if (model.hasSelection) model.commitSelection() else null
                onInstantKit(tapeData.sourceFile, range ?: (0 until tapeData.samples.size))
            }
        }
        // Wave ZZ, the phone reads: three more readings of the same tape.
        // DIG finds the break and sets IN and OUT to it, so INSTANT KIT is
        // the next tap; READ AS GROOVE hears the tape as a rhythm for the
        // open kit's pads; STEAL THE FEEL keeps only its timing and accent.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DeckButton("DIG ▸", Modifier.weight(1f), active = !digging) {
                if (digging) return@DeckButton
                if (model.playing) model.togglePlay()
                stopVoice()
                digging = true
                onToast(Copy.DIG_BUSY)
                digScope.launch {
                    try {
                        val found = withContext(Dispatchers.IO) {
                            Dig.best(Snip(tapeData.samples, 1, tapeData.sampleRate))
                        }
                        if (found == null) {
                            onToast(Copy.NO_BREAK)
                        } else {
                            model.select(found.startFrame, found.endFrame)
                            onToast(Copy.dug(Dig.stamp(found.startSec), Dig.stamp(found.endSec)))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Law 3: when it breaks, say exactly what happened.
                        onToast("DIG FAILED: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        digging = false
                    }
                }
            }
            DeckButton("READ AS GROOVE ▸", Modifier.weight(1.4f)) {
                if (model.playing) model.togglePlay()
                stopVoice()
                val range = if (model.hasSelection) model.commitSelection() else null
                onReadGroove(tapeData.sourceFile, range ?: (0 until tapeData.samples.size))
            }
            DeckButton("STEAL THE FEEL ▸", Modifier.weight(1.4f)) {
                if (model.playing) model.togglePlay()
                stopVoice()
                val range = if (model.hasSelection) model.commitSelection() else null
                onStealFeel(tapeData.sourceFile, range ?: (0 until tapeData.samples.size))
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
    entry: KitShelf.Entry?,
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
            entry?.kit?.name ?: "TAPE",
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
        val strokeWidth = 2.dp.toPx()
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        val hubRadius = radius * 0.45f
        val woundRadius = radius * (0.5f + 0.5f * fraction.coerceIn(0f, 1f))
        drawCircle(color = scheme.grayDark.tape.copy(alpha = 0.6f), radius = woundRadius, center = c)
        drawCircle(color = scheme.ink2.tape, radius = hubRadius, center = c, style = Stroke(width = strokeWidth))
        rotate(degrees = angle, pivot = c) {
            val spoke = hubRadius * 0.9f
            drawLine(scheme.ink2.tape, Offset(c.x - spoke, c.y), Offset(c.x + spoke, c.y), strokeWidth = strokeWidth)
            drawLine(scheme.ink2.tape, Offset(c.x, c.y - spoke), Offset(c.x, c.y + spoke), strokeWidth = strokeWidth)
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
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
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
            // dp-first, converted at draw time — see Bevel.kt's `3.dp.toPx()`
            // — so these read at their intended weight on real (non-1x)
            // phones instead of at a third of it. Dp-sizing barStep also
            // means fewer, wider columns at high density: 3x density used
            // to mean 3x the `peaks.columns` work for the same visual bar.
            val strokeWidth = 2.dp.toPx()
            val barStep = 3.dp.toPx()
            val barWidth = 2.dp.toPx()
            val tickLength = 8.dp.toPx()
            val tickInset = 2.dp.toPx()
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
                drawLine(scheme.accent.tape, Offset(x0.toFloat(), 0f), Offset(x0.toFloat(), h), strokeWidth = strokeWidth)
                drawLine(scheme.accent.tape, Offset(x1.toFloat(), 0f), Offset(x1.toFloat(), h), strokeWidth = strokeWidth)
            }

            if (clampedEnd > clampedStart) {
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
                        size = Size(barWidth, (bottom - top).coerceAtLeast(1f)),
                    )
                }
            }

            for (onset in onsets) {
                val x = (centerX + (onset - model.position) / framesPerPixel).toFloat()
                if (x < -4f || x > w + 4f) continue
                val tickBottom = h - tickInset
                drawLine(scheme.warn.tape, Offset(x, tickBottom - tickLength), Offset(x, tickBottom), strokeWidth = strokeWidth)
            }

            drawLine(scheme.warn.tape, Offset(centerX, 0f), Offset(centerX, h), strokeWidth = strokeWidth)
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
