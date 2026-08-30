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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

    if (entry == null) {
        EmptyChop(scheme)
        return
    }

    var sourceFile by remember(entry.dir, lastCommit) { mutableStateOf<File?>(null) }
    var model by remember(entry.dir, lastCommit) { mutableStateOf<ChopReviewModel?>(null) }
    var failed by remember(entry.dir, lastCommit) { mutableStateOf(false) }

    LaunchedEffect(entry.dir, lastCommit) {
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
 * The file among [entry]'s pads with the most decoded frames — the exact
 * rule `TapeScreen.loadLongestTape` uses to pick the tape. Exposed
 * (not private) so `App`'s COMMIT handler can record which file a TAPE
 * trim actually came from, without `ChopScreen` needing to touch
 * `TapeScreen.kt` to learn it.
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
private fun loadChopSource(entry: KitShelf.Entry, lastCommit: TapeCommit?): Pair<File, Snip>? {
    lastCommit?.let { commit -> loadFromCommit(commit)?.let { return commit.sourceFile to it } }
    return loadLongestSample(entry)
}

private fun modeLabel(mode: ChopReviewModel.ChopMode): String = when (mode) {
    is ChopReviewModel.ChopMode.ByHits -> "BY HITS"
    is ChopReviewModel.ChopMode.Grid -> "GRID ×${mode.parts}"
}

/** Pad-numbered like the real 4×4 (A13–A16 top row, A01 bottom-left) — see KitScreen. */
private val CHOP_GRID_ROWS = listOf(13..16, 9..12, 5..8, 1..4)

@Composable
private fun ChopContent(
    entry: KitShelf.Entry,
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
    // inline during composition.
    var melodicPlaced by remember(model) { mutableStateOf<List<ChopReviewModel.Row?>?>(null) }

    var rechopBusy by remember { mutableStateOf(false) }
    var sendBusy by remember { mutableStateOf(false) }

    var voice by remember(model) { mutableStateOf<TapeVoice?>(null) }
    DisposableEffect(model) {
        onDispose { voice?.release() }
    }

    fun audition(row: ChopReviewModel.Row) {
        // `TapeVoice.release()` joins its streaming thread (up to 200ms) —
        // fine on a screen exit, not fine inside a tap handler on a list a
        // user might tap down quickly. Swap first, release the old voice
        // off the main thread.
        val old = voice
        val v = TapeVoice(row.slice.snip.samples, row.slice.snip.sampleRate)
        voice = v
        v.start(0)
        if (old != null) scope.launch { withContext(Dispatchers.IO) { old.release() } }
    }

    val placed = if (melodic) (melodicPlaced ?: emptyList()) else model.placementPreview()
    val stripText = if (melodic) Copy.MELODIC_ON else model.placementSummary()

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
                    scope.launch {
                        melodicPlaced = withContext(Dispatchers.IO) { model.melodicPreview() }
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
            ) {
                if (rechopBusy) return@SecondaryButton
                rechopBusy = true
                val current = model
                scope.launch {
                    val fresh = withContext(Dispatchers.IO) { current.rechop() }
                    model = fresh
                    rechopBusy = false
                    onToast(Copy.RECHOPPED)
                }
            }
            Box(Modifier.weight(2f)) {
                PrimaryAction(
                    label = if (sendBusy) "…" else "SEND TO GRID",
                    enabled = !sendBusy && !rechopBusy,
                ) {
                    if (sendBusy || rechopBusy) return@PrimaryAction
                    sendBusy = true
                    val oldVoice = voice
                    voice = null
                    if (oldVoice != null) scope.launch { withContext(Dispatchers.IO) { oldVoice.release() } }
                    val current = model
                    val isMelodic = melodic
                    val base = "${sourceFile.nameWithoutExtension} CHOP"
                    scope.launch {
                        val send = withContext(Dispatchers.IO) {
                            if (isMelodic) current.sendToGridMelodic() else current.sendToGrid()
                        }
                        val newEntry = withContext(Dispatchers.IO) {
                            val kitName = shelf.freshName(base)
                            val kitDir = File(entry.dir.parentFile ?: entry.dir, kitName)
                            val builder = KitBuilderModel.fromChop(kitName, send.arranged, kitDir)
                            if (teachEnabled) {
                                val examples = current.labeledOverrides()
                                if (examples.isNotEmpty()) {
                                    TeachLog.append(File(kitDir, TeachLog.FILE_NAME), examples)
                                }
                            }
                            KitShelf.Entry(kitDir, builder.kit)
                        }
                        sendBusy = false
                        onToast(Copy.sentToGrid(send.sliceCount, send.chokeSet))
                        onSentToGrid(newEntry)
                    }
                }
            }
        }
    }
}

@Composable
private fun SliceRow(
    row: ChopReviewModel.Row,
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
        val h = size.height
        val halfH = h / 2f
        val barStep = 3f
        val count = max(1, (size.width / barStep).toInt())
        val columns = peaks.columns(0, snip.frameCount, count)
        for ((i, col) in columns.withIndex()) {
            val x = i * barStep
            val top = (halfH - col.max * halfH).coerceIn(0f, h)
            val bottom = (halfH - col.min * halfH).coerceIn(0f, h)
            drawRect(
                color = scheme.ink2.tape,
                topLeft = Offset(x, top),
                size = Size(2f, (bottom - top).coerceAtLeast(1f)),
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
    onClick: () -> Unit,
) {
    val scheme = LocalScheme.current
    Box(
        modifier
            .raisedBevel(scheme)
            .tapeClick(onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
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
