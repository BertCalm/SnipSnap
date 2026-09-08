package com.snipsnap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
import com.snipsnap.app.TapeCommit
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.PitchEstimate
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.ChopReviewModel
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.TeachLog
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The CHOP SHOP screen: `ChopReviewModel` review + `SEND TO GRID`, over
 * whatever `TAPE` last committed (or, with no commit yet, the open kit's
 * longest sample — the same fallback `TapeScreen` uses). All the slicing
 * and classification lives in `:shell`'s tested `ChopReviewModel`; this
 * file renders it and forwards taps, the same split `TapeScreen` keeps
 * with `TapeDeckModel`.
 */
@Composable
fun ChopScreen(
    entry: KitShelf.Entry?,
    lastCommit: TapeCommit?,
    shelf: KitShelf,
    teachEnabled: Boolean,
    onToast: (String) -> Unit,
    onSentToGrid: (KitShelf.Entry) -> Unit,
) {
    val scheme = LocalScheme.current

    // entry is nullable on purpose, mirroring TapeScreen's own fix: a TAPE
    // commit is kit-independent (loadChopSource's commit branch needs only
    // commit.sourceFile), so a blanket entry-null early return here would
    // block that branch from ever running when the shelf has no kit open —
    // the exact bug shape TapeScreen already had and fixed. Only
    // loadChopSource's kit-fallback branch (loadLongestSample) needs
    // `entry`, and it's skipped when `entry == null`.
    val kitDir = entry?.dir

    var sourceFile by remember(kitDir, lastCommit) { mutableStateOf<File?>(null) }
    var model by remember(kitDir, lastCommit) { mutableStateOf<ChopReviewModel?>(null) }
    var failed by remember(kitDir, lastCommit) { mutableStateOf(false) }

    LaunchedEffect(kitDir, lastCommit) {
        sourceFile = null
        model = null
        failed = false
        // The decode (and the classifier's first pass over every slice) are
        // both real work on real audio — off the main thread, matching
        // TapeScreen's own `loadLongestTape`.
        val loaded = withContext(Dispatchers.IO) {
            loadChopSource(entry, lastCommit)?.let { (file, snip) -> file to ChopReviewModel.chop(snip) }
        }
        if (loaded == null) {
            failed = true
        } else {
            sourceFile = loaded.first
            model = loaded.second
        }
    }

    val file = sourceFile
    val loadedModel = model
    if (file == null || loadedModel == null) {
        // Either still decoding, or nothing readable was found — the empty
        // face covers both; a blank LCD for the moment it takes to read a
        // file is the honest state to show in between (TapeScreen's call).
        if (failed) EmptyChop(scheme) else Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }

    ChopContent(
        entry = entry,
        shelf = shelf,
        sourceFile = file,
        initialModel = loadedModel,
        teachEnabled = teachEnabled,
        onToast = onToast,
        onSentToGrid = onSentToGrid,
    )
}

@Composable
private fun EmptyChop(scheme: Scheme) {
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

/** A commit's file mono-mixed and cropped to its range — the exact cut TAPE made. */
private fun loadFromCommit(commit: TapeCommit): Snip? {
    if (!commit.sourceFile.isFile) return null
    val mono = try {
        Cleanup.toMono(WavReader.read(commit.sourceFile))
    } catch (e: Exception) {
        return null
    }
    val start = commit.range.first.coerceIn(0, mono.frameCount)
    val end = (commit.range.last + 1).coerceIn(start, mono.frameCount)
    if (end <= start) return null
    return Snip(mono.samples.copyOfRange(start, end), channels = 1, sampleRate = mono.sampleRate)
}

/** The open kit's longest sample, mono-mixed — TapeScreen's own fallback. */
private fun loadLongestSample(entry: KitShelf.Entry): Pair<File, Snip>? {
    val file = longestSampleFile(entry) ?: return null
    val mono = try {
        Cleanup.toMono(WavReader.read(file))
    } catch (e: Exception) {
        return null
    }
    return file to mono
}

/**
 * The file among [entry]'s pads with the most decoded frames —
 * [loadLongestSample]'s own fallback source, and this file's only caller.
 * Retroactive-snip Task 4 gave `TapeScreen.loadLongestTape` a
 * higher-priority source list ahead of this exact rule (the newest snip,
 * then the last commit's own file, both checked before TAPE's own private
 * per-pad fallback) — so this is no longer "the exact rule TapeScreen
 * uses to pick the tape" the way an earlier version of this KDoc claimed;
 * TapeScreen keeps its own private copy of the per-pad fallback now
 * (`loadLongestFromKit`) rather than calling this one. Also no longer
 * used by `App`'s COMMIT handler — TAPE hands COMMIT the exact file it
 * was scrubbing directly, since re-deriving it via this function would
 * pick the wrong file whenever the commit came from a snip.
 */
internal fun longestSampleFile(entry: KitShelf.Entry): File? {
    var longest: File? = null
    var longestFrames = -1
    for (pad in entry.kit.pads) {
        val f = File(entry.dir, pad.sampleFile)
        if (!f.isFile) continue
        val frames = try {
            Cleanup.toMono(WavReader.read(f)).frameCount
        } catch (e: Exception) {
            continue
        }
        if (frames > longestFrames) {
            longest = f
            longestFrames = frames
        }
    }
    return longest
}

/** Priority order from the brief: a TAPE commit first, else the open kit's longest sample. */
private fun loadChopSource(entry: KitShelf.Entry?, lastCommit: TapeCommit?): Pair<File, Snip>? {
    lastCommit?.let { commit -> loadFromCommit(commit)?.let { return commit.sourceFile to it } }
    // The kit fallback is the only branch that needs a kit — skip it
    // outright when none is open rather than let it run on a null entry.
    if (entry == null) return null
    return loadLongestSample(entry)
}

private fun modeLabel(mode: ChopReviewModel.ChopMode): String = when (mode) {
    is ChopReviewModel.ChopMode.ByHits -> "BY HITS"
    is ChopReviewModel.ChopMode.Grid -> "GRID ×${mode.parts}"
}

/** "A2", "C#4" — what a detected pitch reads as on a MELODIC row. */
private fun pitchLabel(estimate: PitchEstimate): String =
    Scales.nameOf(Scales.hzToMidi(estimate.hz).roundToInt())

/** Pad-numbered like the real 4×4 (A13–A16 top row, A01 bottom-left) — see KitScreen. */
private val CHOP_GRID_ROWS = listOf(13..16, 9..12, 5..8, 1..4)

@Composable
private fun ChopContent(
    entry: KitShelf.Entry?,
    shelf: KitShelf,
    sourceFile: File,
    initialModel: ChopReviewModel,
    teachEnabled: Boolean,
    onToast: (String) -> Unit,
    onSentToGrid: (KitShelf.Entry) -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    var model by remember(initialModel) { mutableStateOf(initialModel) }
    // Row overrides mutate `ChopReviewModel.Row` in place (plain Kotlin vars,
    // tested and owned by :shell) — Compose can't see that, so this counter
    // is what forces a recompose after a chip tap, the same trick
    // TapeScreen's frame clock uses for its own outside-Compose model.
    var revision by remember(model) { mutableIntStateOf(0) }
    // Row overrides mutate outside Compose's snapshot system (see above);
    // this read is what makes the whole function's composition scope —
    // slice list included — depend on `revision`, so a chip mutation
    // below actually recomposes the rows that show it.
    val revisionTick = revision

    var melodic by remember(model) { mutableStateOf(false) }
    // MELODIC's placement runs pitch detection over every row on first
    // touch — real DSP, so it's computed off the main thread rather than
    // inline during composition. Row pitch labels ride along with it
    // (same lazily-cached `pitchByRow` the engine already pays for).
    var melodicPlaced by remember(model) { mutableStateOf<List<ChopReviewModel.Row?>?>(null) }
    var pitchLabels by remember(model) { mutableStateOf<List<String?>?>(null) }
    var melodicBusy by remember(model) { mutableStateOf(false) }

    var rechopBusy by remember { mutableStateOf(false) }
    var sendBusy by remember { mutableStateOf(false) }

    var voice by remember(model) { mutableStateOf<TapeVoice?>(null) }
    DisposableEffect(model) {
        onDispose { voice?.release() }
    }

    // Backgrounding mid-audition must not keep streaming: a track playing
    // when the app leaves the foreground has to stop there, not whenever
    // the composable next happens to leave composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voice?.release()
                voice = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun audition(row: ChopReviewModel.Row) {
        // `TapeVoice.release()` is a bounded join on its own streaming
        // thread (~200ms worst case, usually instant) — doing it here,
        // synchronously at the swap site, is what guarantees the previous
        // voice actually stops even if the user taps once and immediately
        // navigates away. A composition-scoped coroutine can't make that
        // guarantee: it's cancelled the instant this composable leaves.
        voice?.release()
        val v = TapeVoice(row.slice.snip.samples, row.slice.snip.sampleRate)
        voice = v
        v.start(0)
    }

    // `placementPreview()`/`placementSummary()` are real DSP-adjacent
    // derivations over `model` — normally total, but a single guarded call
    // here is the seam that stops any exception from either one killing
    // the app mid-recomposition. On failure this reuses `ChopScreen`'s own
    // empty-state face and toast path rather than inventing new UI; the
    // toast fires from a `LaunchedEffect` (composition itself must stay a
    // pure read of state, not a place to trigger side effects directly).
    var classicError by remember(model) { mutableStateOf<String?>(null) }
    val classicPlacement = if (melodic) null else try {
        (model.placementPreview() to model.placementSummary()).also { classicError = null }
    } catch (e: Exception) {
        classicError = e.message ?: e.javaClass.simpleName
        null
    }
    val classicFailed = !melodic && classicPlacement == null

    LaunchedEffect(classicFailed) {
        if (classicFailed) onToast("CHOP FAILED: ${classicError ?: "couldn't lay out the slices"}")
    }

    if (!melodic && classicFailed) {
        EmptyChop(scheme)
        return
    }

    val placed = if (melodic) (melodicPlaced ?: emptyList()) else classicPlacement!!.first
    val stripText = when {
        melodic && melodicBusy -> "…"
        melodic -> Copy.MELODIC_ON
        else -> classicPlacement!!.second
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Layout.LCD_HEADER_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            TapeText(
                "${model.sliceCount} SLICES — ${modeLabel(model.mode)}",
                TapeType.lcdHeader,
                scheme.lcdInk.tape,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SegmentButton("CLASSIC", active = !melodic, modifier = Modifier.weight(1f)) {
                melodic = false
            }
            SegmentButton("MELODIC", active = melodic, modifier = Modifier.weight(1f)) {
                melodic = true
                onToast(Copy.MELODIC_ON)
                if (melodicPlaced == null) {
                    melodicBusy = true
                    val current = model
                    scope.launch {
                        try {
                            val (placedList, labels) = withContext(Dispatchers.IO) {
                                val p = current.melodicPreview()
                                val l = current.rows.indices.map { i -> current.pitchOf(i)?.let(::pitchLabel) }
                                p to l
                            }
                            melodicPlaced = placedList
                            pitchLabels = labels
                        } finally {
                            melodicBusy = false
                        }
                    }
                }
            }
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .sunkenField(scheme)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(model.rows, key = { it.n }) { row ->
                SliceRow(
                    row = row,
                    pitchLabel = if (melodic) pitchLabels?.getOrNull(row.n - 1) else null,
                    onTapChip = {
                        model.cycleLabel(row.n - 1)
                        revision++
                    },
                    onLongPressChip = {
                        model.clearOverride(row.n - 1)
                        revision++
                    },
                    onTapRow = { audition(row) },
                )
            }
        }

        GridPreview(placed, scheme)

        Box(
            Modifier
                .fillMaxWidth()
                .height(Layout.LCD_HEADER_MAX_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            TapeText(stripText, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 2)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton(
                if (rechopBusy) "…" else "RE-CHOP",
                modifier = Modifier.weight(1f).height(Layout.PRIMARY_ACTION_H.dp),
                // Symmetric with SEND's own guard below — RE-CHOP swapping
                // `model` out from under an in-flight SEND would re-key the
                // arrangement the write is reading.
                enabled = !rechopBusy && !sendBusy,
            ) {
                if (rechopBusy || sendBusy) return@SecondaryButton
                rechopBusy = true
                val current = model
                scope.launch {
                    // Match `App.fresh()`'s own pattern: real disk/CPU work
                    // in a try, the busy flag cleared in a finally so a
                    // failure can't leave the button permanently disabled
                    // and silent — it says exactly what happened instead.
                    try {
                        val fresh = withContext(Dispatchers.IO) { current.rechop() }
                        model = fresh
                        onToast(Copy.RECHOPPED)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        onToast("RE-CHOP FAILED: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        rechopBusy = false
                    }
                }
            }
            Box(Modifier.weight(2f)) {
                PrimaryAction(
                    label = if (sendBusy) "…" else "SEND TO GRID",
                    enabled = !sendBusy && !rechopBusy,
                ) {
                    if (sendBusy || rechopBusy) return@PrimaryAction
                    sendBusy = true
                    // Synchronous, not a composition-scoped launch — see
                    // `audition()`'s comment on why that matters.
                    voice?.release()
                    voice = null
                    val current = model
                    val isMelodic = melodic
                    val base = "${sourceFile.nameWithoutExtension} CHOP"
                    scope.launch {
                        try {
                            val send = withContext(Dispatchers.IO) {
                                if (isMelodic) current.sendToGridMelodic() else current.sendToGrid()
                            }
                            val newEntry = withContext(Dispatchers.IO) {
                                val kitName = shelf.freshName(base)
                                val kitDir = File(shelf.root, kitName)
                                val builder = KitBuilderModel.fromChop(kitName, send.arranged, kitDir)
                                if (teachEnabled) {
                                    val examples = current.labeledOverrides()
                                    if (examples.isNotEmpty()) {
                                        TeachLog.append(File(kitDir, TeachLog.FILE_NAME), examples)
                                    }
                                }
                                KitShelf.Entry(kitDir, builder.kit)
                            }
                            onToast(Copy.sentToGrid(send.sliceCount, send.chokeSet))
                            onSentToGrid(newEntry)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            onToast("SEND FAILED: ${e.message ?: e.javaClass.simpleName}")
                        } finally {
                            sendBusy = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliceRow(
    row: ChopReviewModel.Row,
    pitchLabel: String?,
    onTapChip: () -> Unit,
    onLongPressChip: () -> Unit,
    onTapRow: () -> Unit,
) {
    val scheme = LocalScheme.current
    val classColor = Schemes.classColor(row.effectiveClass).tape

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .tapeClick(onTapRow)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapeText("${row.n}", TapeType.lcdSmall, scheme.ink2.tape, Modifier.width(20.dp))

        MiniWaveform(row.slice.snip, scheme, Modifier.size(width = 64.dp, height = 24.dp))

        Box(
            Modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .let {
                    if (row.unsure) {
                        it.dashedBorder(scheme.ink2.tape)
                    } else {
                        it.background(classColor.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    }
                }
                .pointerInput(row.n) {
                    detectTapGestures(
                        onTap = { onTapChip() },
                        onLongPress = { onLongPressChip() },
                    )
                }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText(
                ChopReviewModel.chipName(row.effectiveClass),
                TapeType.marker,
                if (row.unsure) scheme.ink2.tape else Schemes.padLabelInk(scheme, row.effectiveClass).tape,
            )
        }

        // MELODIC's whole point is the note review — the pitch that landed
        // each row where it did.
        if (pitchLabel != null) {
            TapeText(pitchLabel, TapeType.pixelSmall, scheme.ink2.tape)
        }

        Spacer(Modifier.weight(1f))

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (row.overridden) {
                TapeText(Copy.CHIP_OVERRIDDEN, TapeType.pixelSmall, scheme.accent.tape)
            }
            val confText = if (row.unsure) {
                Copy.CHIP_NOT_SURE
            } else {
                "${(row.classification.confidence * 100).toInt()}%"
            }
            TapeText(confText, TapeType.pixelSmall, if (row.unsure) scheme.warn.tape else scheme.ink2.tape)
        }
    }
}

/** A slice's own tiny waveform — `PeaksPyramid.fromSnip` per row, cheap at slice length. */
@Composable
private fun MiniWaveform(snip: Snip, scheme: Scheme, modifier: Modifier = Modifier) {
    val peaks = remember(snip) { PeaksPyramid.fromSnip(snip) }
    Canvas(modifier) {
        if (snip.frameCount <= 0) return@Canvas
        // dp-first, converted at draw time — see TapeScreen.kt's WaveformLcd.
        val barStep = 3.dp.toPx()
        val barWidth = 2.dp.toPx()
        val h = size.height
        val halfH = h / 2f
        val count = max(1, (size.width / barStep).toInt())
        val columns = peaks.columns(0, snip.frameCount, count)
        for ((i, col) in columns.withIndex()) {
            val x = i * barStep
            val top = (halfH - col.max * halfH).coerceIn(0f, h)
            val bottom = (halfH - col.min * halfH).coerceIn(0f, h)
            drawRect(
                color = scheme.ink2.tape,
                topLeft = Offset(x, top),
                size = Size(barWidth, (bottom - top).coerceAtLeast(1f)),
            )
        }
    }
}

/** The 4×4 placement preview: one cell per pad slot, tinted its landing class. */
@Composable
private fun GridPreview(placed: List<ChopReviewModel.Row?>, scheme: Scheme) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (slots in CHOP_GRID_ROWS) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (slot in slots) {
                    val row = placed.getOrNull(slot - 1)
                    val color = if (row != null) Schemes.classColor(row.effectiveClass).tape else scheme.grayDark.tape
                    Box(
                        Modifier
                            .weight(1f)
                            .height(18.dp)
                            .background(color.copy(alpha = if (row != null) 0.8f else 0.3f), RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = LocalScheme.current
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme, fill = if (active) scheme.accent.tape else null)
            .tapeClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (active) scheme.titleInk.tape else scheme.ink2.tape)
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = LocalScheme.current
    Box(
        modifier
            .raisedBevel(scheme)
            .let { if (enabled) it.tapeClick(onClick) else it }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (enabled) scheme.ink.tape else scheme.ink2.tape)
    }
}

/** The "NOT SURE" dashed-chip treatment — a guess, and honest about it. */
private fun Modifier.dashedBorder(color: Color, radius: Dp = 4.dp): Modifier = this.drawWithContent {
    drawContent()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)),
    )
}
