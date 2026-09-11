package com.snipsnap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.platform.LocalContext
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
import com.snipsnap.audio.DrumClass
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
import com.snipsnap.shell.SnipStore
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
    // The app's actual SNIPS shelf, for the tape reference below.
    val snipsDir = File(LocalContext.current.filesDir, SnipStore.DIR)

    var sourceFile by remember(kitDir, lastCommit) { mutableStateOf<File?>(null) }
    var model by remember(kitDir, lastCommit) { mutableStateOf<ChopReviewModel?>(null) }
    // Null while still decoding (or once loaded); set on failure to the
    // honest reason — distinct copy for "nothing was ever taped" (no
    // commit at all, [Copy.EMPTY_SHELF]) versus "a commit exists but its
    // file won't read anymore" ([Copy.CHOP_SOURCE_GONE]), so a user who
    // made something can tell that apart from a user who hasn't yet.
    var emptyReason by remember(kitDir, lastCommit) { mutableStateOf<String?>(null) }

    LaunchedEffect(kitDir, lastCommit) {
        sourceFile = null
        model = null
        emptyReason = null
        // The decode (and the classifier's first pass over every slice) are
        // both real work on real audio — off the main thread, matching
        // TapeScreen's own `loadLongestTape`.
        val (oomEncountered, loaded) = withContext(Dispatchers.IO) {
            val result = loadChopSource(entry, lastCommit)
            val built = result.found?.let { (file, snip) ->
                // The tape reference (RE-TRIM): only when what loaded is
                // the commit's own snip — the kit-sample fallback is not a
                // tape the pads could go back to.
                val tape = lastCommit?.takeIf { it.sourceFile == file }
                    ?.let { ChopReviewModel.TapeRef.ofSnip(it.sourceFile, it.range.first, snipsDir) }
                file to ChopReviewModel.chop(snip, tape = tape)
            }
            result.oomEncountered to built
        }
        // Same courtesy as TapeScreen's own toast: said even when the kit
        // fallback above went on to load something else, since the commit
        // file that hit TAPE_LOAD_MAX_SEC's OOM safety net is still real
        // and still unreadable from CHOP.
        if (oomEncountered) onToast(Copy.TAPE_TOO_BIG)
        if (loaded == null) {
            emptyReason = if (lastCommit != null) Copy.CHOP_SOURCE_GONE else Copy.EMPTY_SHELF
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
        val reason = emptyReason
        if (reason != null) EmptyChop(scheme, reason) else Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }

    ChopContent(
        shelf = shelf,
        sourceFile = file,
        initialModel = loadedModel,
        teachEnabled = teachEnabled,
        onToast = onToast,
        onSentToGrid = onSentToGrid,
    )
}

@Composable
private fun EmptyChop(scheme: Scheme, message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .lcdPanel(scheme)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(message, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
    }
}

/**
 * A commit's file mono-mixed and cropped to its range — the exact cut TAPE
 * made. `commit.sourceFile` is `tapeData.sourceFile` from TAPE, so
 * `commit.range` is only ever valid against the capped view TAPE actually
 * decoded — reading through [WavReader.readCapped] with the same
 * [TAPE_LOAD_MAX_SEC] reproduces that exact frame count, instead of
 * [WavReader.read]'s unbounded whole-file decode re-inviting the same OOM
 * TAPE_LOAD_MAX_SEC exists to prevent. [OutOfMemoryError] is caught
 * separately from [Exception] — same reasoning as [WavReader]'s own
 * `readMono` in `TapeScreen.kt`: it's an [Error], not an [Exception], so a
 * plain `catch (e: Exception)` would never have caught it.
 */
private fun loadFromCommit(commit: TapeCommit): CommitLoad {
    if (!commit.sourceFile.isFile) return CommitLoad.Unreadable
    val mono = try {
        Cleanup.toMono(WavReader.readCapped(commit.sourceFile, TAPE_LOAD_MAX_SEC).snip)
    } catch (e: OutOfMemoryError) {
        return CommitLoad.OutOfMemory
    } catch (e: Exception) {
        return CommitLoad.Unreadable
    }
    val start = commit.range.first.coerceIn(0, mono.frameCount)
    val end = (commit.range.last + 1).coerceIn(start, mono.frameCount)
    if (end <= start) return CommitLoad.Unreadable
    return CommitLoad.Ok(Snip(mono.samples.copyOfRange(start, end), channels = 1, sampleRate = mono.sampleRate))
}

/** [loadFromCommit]'s answer: the cropped [Snip] on success, an ordinary unreadable-file miss, or [TAPE_LOAD_MAX_SEC]'s own OOM safety net catching what the cap didn't prevent. */
private sealed class CommitLoad {
    class Ok(val snip: Snip) : CommitLoad()
    object Unreadable : CommitLoad()
    object OutOfMemory : CommitLoad()
}

/** [loadLongestSample]'s answer: the file+snip it settled on (if any), and whether [TAPE_LOAD_MAX_SEC]'s OOM safety net was hit skipping past a candidate pad along the way — same shape as `TapeScreen.loadLongestFromKit`'s own `KitLongestResult`. */
private class LongestSampleResult(val found: Pair<File, Snip>?, val oomEncountered: Boolean)

/**
 * The open kit's longest sample, mono-mixed — TapeScreen's own fallback
 * (`loadLongestFromKit`) for when neither a snip nor a last-commit source
 * resolves, replicated here for CHOP's own no-commit case. Every pad is a
 * file MUTATE's arbitrary file picker could have made arbitrarily long.
 *
 * Measures every pad via [WavReader.peekSeconds] first — a header-only
 * read, no decode — and only then decodes the longest one, once, through
 * [WavReader.readCapped] with the same [TAPE_LOAD_MAX_SEC] TapeScreen
 * uses (same reasoning as [loadFromCommit] above). An earlier version of
 * this function decoded each candidate once, on the reasoning that
 * decoding each pad exactly once (rather than once to measure it and
 * again to return it) was cheaper — true for CPU, but wrong for memory:
 * it still held a decode live per pad while scanning, so comparing an
 * unusually long kit could hold a retained running-longest candidate's
 * decode live at the same time as the next pad's raw slice and its own
 * decode. Measuring headers first holds at most one pad's worth (its raw
 * slice, its interleaved decode, and the mono copy made from it) no
 * matter how many pads the kit has, since only the header is touched
 * until the winner is chosen. [WavReader.peekSeconds] reports each pad's
 * true, uncapped duration — not a capped one — so two pads that would
 * both have been truncated to the same capped length by the old
 * decode-to-measure shape no longer tie at all; the one with the longer
 * header wins outright, matching `loadLongestFromKit`'s own header-first
 * behavior. If the header-longest pad then fails to actually decode (OOM,
 * or a header that resolved but a body that doesn't), this reports that
 * failure rather than falling back to the next-longest candidate — a
 * second decode attempt would reintroduce the multi-buffer cost this
 * avoids.
 */
private fun loadLongestSample(entry: KitShelf.Entry): LongestSampleResult {
    var longestFile: File? = null
    var longestSeconds = -1f
    for (pad in entry.kit.pads) {
        val f = File(entry.dir, pad.sampleFile)
        if (!f.isFile) continue
        val seconds = WavReader.peekSeconds(f) ?: continue
        if (seconds > longestSeconds) {
            longestSeconds = seconds
            longestFile = f
        }
    }
    val file = longestFile ?: return LongestSampleResult(null, oomEncountered = false)
    val mono = try {
        Cleanup.toMono(WavReader.readCapped(file, TAPE_LOAD_MAX_SEC).snip)
    } catch (e: OutOfMemoryError) {
        return LongestSampleResult(null, oomEncountered = true)
    } catch (e: Exception) {
        return LongestSampleResult(null, oomEncountered = false)
    }
    return LongestSampleResult(file to mono, oomEncountered = false)
}

/** [loadChopSource]'s answer: the source it settled on (if any), and whether [TAPE_LOAD_MAX_SEC]'s OOM safety net was hit reading the commit along the way. */
private class ChopSourceResult(val found: Pair<File, Snip>?, val oomEncountered: Boolean)

/** Priority order from the brief: a TAPE commit first, else the open kit's longest sample. */
private fun loadChopSource(entry: KitShelf.Entry?, lastCommit: TapeCommit?): ChopSourceResult {
    var oomEncountered = false
    if (lastCommit != null) {
        when (val outcome = loadFromCommit(lastCommit)) {
            is CommitLoad.Ok -> return ChopSourceResult(lastCommit.sourceFile to outcome.snip, oomEncountered)
            CommitLoad.OutOfMemory -> oomEncountered = true
            CommitLoad.Unreadable -> {}
        }
    }
    // The kit fallback is the only branch that needs a kit — skip it
    // outright when none is open rather than let it run on a null entry.
    if (entry == null) return ChopSourceResult(null, oomEncountered)
    val kit = loadLongestSample(entry)
    return ChopSourceResult(kit.found, oomEncountered || kit.oomEncountered)
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

    /**
     * The slice whose class picker is open (its 1-based `n`), or null.
     *
     * Only one at a time: the list is long, and two open panels would push
     * the row you were looking at off screen.
     */
    var pickerFor by remember(model) { mutableStateOf<Int?>(null) }
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
        EmptyChop(scheme, Copy.CHOP_LAYOUT_FAILED)
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
                Column(Modifier.fillMaxWidth()) {
                    SliceRow(
                        row = row,
                        pitchLabel = if (melodic) pitchLabels?.getOrNull(row.n - 1) else null,
                        // The chip opens the list rather than advancing one
                        // step (September UAT, finding 6): the cycle ran one
                        // way through ten classes with no back step, so a
                        // 16-slice kit could cost 144 taps and overshooting
                        // by one meant going round again.
                        onTapChip = {
                            pickerFor = if (pickerFor == row.n) null else row.n
                        },
                        // Long-press still restores the classifier's call
                        // outright, for anyone who already has it in their
                        // fingers. The picker offers the same thing in
                        // words, which is finding 21's complaint about
                        // hidden long-presses answered in passing.
                        onLongPressChip = {
                            model.clearOverride(row.n - 1)
                            pickerFor = null
                            revision++
                        },
                        onTapRow = { audition(row) },
                    )
                    if (pickerFor == row.n) {
                        ClassPicker(
                            current = row.effectiveClass,
                            machine = row.classification.drumClass,
                            scheme = scheme,
                            onPick = { dc ->
                                model.setLabel(row.n - 1, dc)
                                pickerFor = null
                                revision++
                            },
                            onMachine = {
                                model.clearOverride(row.n - 1)
                                pickerFor = null
                                revision++
                            },
                        )
                    }
                }
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

/**
 * Every class a slice can be, under the slice that asked.
 *
 * The September UAT's finding 6: the chip was a ten-state one-way cycle,
 * so the class furthest from the machine's guess cost nine taps, one tap
 * past it cost nine more, and relabelling a 16-slice kit ran to 144. This
 * is two taps for any slice and any class — open, choose.
 *
 * The same inline-panel move EXPORT's format picker makes, deliberately:
 * no dialog, no scrim, nothing new to learn, and the list sits directly
 * under the chip it belongs to so it is obvious which slice is being
 * relabelled.
 */
@Composable
private fun ClassPicker(
    current: DrumClass,
    machine: DrumClass,
    scheme: Scheme,
    onPick: (DrumClass) -> Unit,
    onMachine: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().lcdPanel(scheme).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (chunk in ChopReviewModel.CHIP_CYCLE.chunked(3)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (dc in chunk) {
                    val picked = dc == current
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .background(
                                if (picked) Schemes.classColor(dc).tape else scheme.ink3.tape.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp),
                            )
                            // label = null: the TapeText below already says
                            // the class name, and clickable merges descendant
                            // semantics into this node - an explicit label
                            // would replace that text rather than add to it
                            // (tapeClick's own contract, Chrome.kt).
                            .tapeClick(label = null, onClick = { onPick(dc) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(
                            ChopReviewModel.chipName(dc),
                            TapeType.marker,
                            if (picked) Schemes.padLabelInk(scheme, dc).tape else scheme.lcdInk.tape,
                            maxLines = 1,
                        )
                    }
                }
                // The last row is short of three; keep the columns aligned.
                repeat(3 - chunk.size) { Box(Modifier.weight(1f)) }
            }
        }
        // The machine's own call, said in words rather than hidden behind a
        // long-press nothing on screen mentions (finding 21).
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .tapeClick(label = null, onClick = onMachine),
            contentAlignment = Alignment.Center,
        ) {
            TapeText(
                "THE MACHINE SAID ${ChopReviewModel.chipName(machine)}",
                TapeType.pixelSmall,
                scheme.ink3.tape,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
            .tapeClick(label = null, onClick = onTapRow)
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
                // A plain tap/long-press pair with no drag — the same
                // shape RoomRow/KitRow (KitsScreen.kt) had before this
                // audit pass, and the same fix: combinedClickable
                // registers real onClick/onLongClick accessibility
                // actions where the raw pointerInput this replaces
                // registered none (finding 1). The chip's own class
                // name (below) is already this node's merged name.
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onLongClickLabel = "RECLASSIFY",
                    onLongClick = { onLongPressChip() },
                    onClick = { onTapChip() },
                )
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
            .tapeClick(label = null, onClick = onClick),
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
            .let { if (enabled) it.tapeClick(label = null, onClick = onClick) else it }
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
