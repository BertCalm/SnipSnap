package com.snipsnap.app.ui

import android.util.Log
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.TapeCommit
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.pressedBevel
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
import com.snipsnap.shell.PadBanks
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
import kotlinx.coroutines.sync.withLock
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
    /** ONTO <kit> · BANK X landed: the kit as it is now, and the bank (0-based) to open KIT on. */
    onLandedOnto: (KitShelf.Entry, Int) -> Unit = { e, _ -> onSentToGrid(e) },
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
        entry = entry,
        shelf = shelf,
        sourceFile = file,
        initialModel = loadedModel,
        teachEnabled = teachEnabled,
        onToast = onToast,
        onSentToGrid = onSentToGrid,
        onLandedOnto = onLandedOnto,
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

/** "A2", "C#4" — what a detected pitch reads as on a MELODIC row. */
private fun pitchLabel(estimate: PitchEstimate): String =
    Scales.nameOf(Scales.hzToMidi(estimate.hz).roundToInt())

/** Pad-numbered like the real 4×4 (A13–A16 top row, A01 bottom-left) — see KitScreen. */
private val CHOP_GRID_ROWS = listOf(13..16, 9..12, 5..8, 1..4)

@Composable
private fun ChopContent(
    /** The open kit, if any: ONTO <kit> · BANK X lands the chop on its first empty bank. */
    entry: KitShelf.Entry?,
    shelf: KitShelf,
    sourceFile: File,
    initialModel: ChopReviewModel,
    teachEnabled: Boolean,
    onToast: (String) -> Unit,
    onSentToGrid: (KitShelf.Entry) -> Unit,
    onLandedOnto: (KitShelf.Entry, Int) -> Unit,
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

    // CLASSIC, MELODIC or FOLD (docs/CHOP_CONTROLS.md §8): which layout
    // the grid preview and SEND use. `melodic` and `fold` are the two
    // reads the rest of this function makes of it.
    var layout by remember(model) { mutableStateOf(ChopLayout.CLASSIC) }
    val melodic = layout == ChopLayout.MELODIC
    val fold = layout == ChopLayout.FOLD
    // MELODIC's placement runs pitch detection over every row on first
    // touch — real DSP, so it's computed off the main thread rather than
    // inline during composition. Row pitch labels ride along with it
    // (same lazily-cached `pitchByRow` the engine already pays for).
    var melodicPlaced by remember(model) { mutableStateOf<List<ChopReviewModel.Row?>?>(null) }
    var pitchLabels by remember(model) { mutableStateOf<List<String?>?>(null) }
    var melodicBusy by remember(model) { mutableStateOf(false) }

    var rechopBusy by remember { mutableStateOf(false) }
    var sendBusy by remember { mutableStateOf(false) }

    // The source's tempo, for ON THE GRID's row: measured once on IO off
    // the first model (every later model cut from this source shares the
    // measurement), null while measuring or when the tape has no pulse.
    val tempoMeasured by produceState<Pair<Boolean, com.snipsnap.audio.TempoEstimate?>>(initialValue = false to null, initialModel) {
        val t = withContext(Dispatchers.IO) { initialModel.tempo }
        value = true to t
    }

    // The CUT bench (docs/CHOP_CONTROLS.md): closed by default so the
    // screen is the screen it was; open, the count, the ear, the cut and
    // the grid. Every change is a fresh chop off the main thread with the
    // corrected chips carried across, and the markers under the header
    // move with it — the feedback that makes the controls usable.
    var cutOpen by remember { mutableStateOf(false) }

    /**
     * Re-chop at [newMode] with overrides carried, then [after] on the
     * result on the main thread (a toast, a check the count moved).
     * Shares RE-CHOP's busy flag: two chops in flight would race for
     * `model`.
     */
    fun rechopTo(newMode: ChopReviewModel.ChopMode, after: (ChopReviewModel) -> Unit = {}) {
        if (rechopBusy || sendBusy) return
        rechopBusy = true
        val current = model
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { current.rechopKeeping(newMode) }
                model = fresh
                after(fresh)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ChopScreen", "rechop: failed", e)
                onToast(Copy.RECHOP_FAILED)
            } finally {
                rechopBusy = false
            }
        }
    }

    /** HITS ◀ ▶: one fewer or one more than the chop has now, so the step is always visible when the tape allows it. */
    fun stepHits(delta: Int) {
        val hits = model.mode as? ChopReviewModel.ChopMode.ByHits ?: return
        val want = (model.sliceCount + delta).coerceIn(1, ChopReviewModel.MAX_HITS)
        if (want == model.sliceCount) return
        val before = model.sliceCount
        rechopTo(hits.copy(maxSlices = want)) { fresh ->
            if (delta > 0 && fresh.sliceCount <= before) onToast(Copy.chopOnlyHits(fresh.sliceCount))
        }
    }

    /** GRID ◀ ▶: one part fewer or more. */
    fun stepGrid(delta: Int) {
        val grid = model.mode as? ChopReviewModel.ChopMode.Grid ?: return
        val want = (grid.parts + delta).coerceIn(1, ChopReviewModel.MAX_HITS)
        if (want != grid.parts) rechopTo(grid.copy(parts = want))
    }

    /**
     * MERGE / SPLIT under a chip: a local move on the model, off the main
     * thread all the same — SPLIT runs the detector over the slice, and a
     * slice can be most of a long tape. A refusal says why in words; a
     * landing says what the slices are now.
     */
    fun editSlices(edit: (ChopReviewModel) -> ChopReviewModel?, landed: String, refused: String) {
        if (rechopBusy || sendBusy) return
        rechopBusy = true
        val current = model
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { edit(current) }
                if (fresh == null) {
                    onToast(refused)
                } else {
                    model = fresh
                    onToast(landed)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ChopScreen", "edit: failed", e)
                onToast(Copy.RECHOP_FAILED)
            } finally {
                rechopBusy = false
            }
        }
    }

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
        if (fold) {
            // FOLD: one pad per fold; the preview draws the leads. The
            // distance pass is pairwise over at most 64 rows of features
            // already measured — a lookup, not DSP.
            val folded = model.foldedPreview()
            (folded.map { it?.lead } to Copy.folded(model.sliceCount, folded.count { it != null })).also { classicError = null }
        } else {
            (model.placementPreview() to model.placementSummary()).also { classicError = null }
        }
    } catch (e: Exception) {
        classicError = e.message ?: e.javaClass.simpleName
        null
    }
    val foldTags = if (fold) model.foldTags() else null
    val classicFailed = !melodic && classicPlacement == null

    LaunchedEffect(classicFailed) {
        if (classicFailed) {
            classicError?.let { Log.e("ChopScreen", "classic layout failed: $it") }
            onToast(Copy.CHOP_LAYOUT_FAILED)
        }
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
                "${model.sliceCount} SLICES — ${model.modeLabel()}",
                TapeType.lcdHeader,
                scheme.lcdInk.tape,
            )
        }

        // The whole tape with every cut drawn on it, live: as the bench
        // moves the count or the ear, the markers move, before anything is
        // committed. Until now the only way to see what changed was to
        // scroll the rows.
        SourceStrip(model.source, model.cutFrames(), scheme)

        CutBench(
            model = model,
            open = cutOpen,
            busy = rechopBusy || sendBusy,
            scheme = scheme,
            onToggle = { cutOpen = !cutOpen },
            onByHits = {
                if (model.mode !is ChopReviewModel.ChopMode.ByHits) rechopTo(ChopReviewModel.ChopMode.ByHits(model.sliceCount.coerceIn(1, ChopReviewModel.MAX_HITS)))
            },
            onGrid = {
                if (model.mode !is ChopReviewModel.ChopMode.Grid) rechopTo(ChopReviewModel.ChopMode.Grid(model.sliceCount.coerceIn(1, ChopReviewModel.MAX_HITS)))
            },
            onStep = { delta -> if (model.mode is ChopReviewModel.ChopMode.Grid) stepGrid(delta) else stepHits(delta) },
            onAuto = {
                val hits = model.mode as? ChopReviewModel.ChopMode.ByHits
                if (hits != null && !rechopBusy && !sendBusy) {
                    rechopBusy = true
                    val current = model
                    scope.launch {
                        try {
                            val (count, fresh) = withContext(Dispatchers.IO) {
                                val n = current.autoCount()
                                n to n?.let { current.rechopKeeping(hits.copy(maxSlices = it)) }
                            }
                            // No hit at all is a refusal, not a count of one.
                            if (count == null || fresh == null) {
                                onToast(Copy.CHOP_AUTO_NONE)
                            } else {
                                model = fresh
                                onToast(Copy.chopAuto(count))
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e("ChopScreen", "auto: failed", e)
                            onToast(Copy.RECHOP_FAILED)
                        } finally {
                            rechopBusy = false
                        }
                    }
                }
            },
            onEar = { ear ->
                val hits = model.mode as? ChopReviewModel.ChopMode.ByHits
                if (hits != null && hits.ear != ear) rechopTo(hits.copy(ear = ear))
            },
            onCut = { cut ->
                val hits = model.mode as? ChopReviewModel.ChopMode.ByHits
                if (hits != null && hits.cut != cut) rechopTo(hits.copy(cut = cut))
            },
            tempo = tempoMeasured,
            onSnap = { grid ->
                val hits = model.mode as? ChopReviewModel.ChopMode.ByHits
                if (hits != null && hits.grid != grid) {
                    // The row stays tappable without a pulse (dimmed, not
                    // disabled; the toast explains) — OFF is always allowed.
                    if (grid != ChopReviewModel.GridSnap.OFF && tempoMeasured.first && tempoMeasured.second == null) onToast(Copy.CHOP_NO_TEMPO) else rechopTo(hits.copy(grid = grid))
                }
            },
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SegmentButton("CLASSIC", active = layout == ChopLayout.CLASSIC, modifier = Modifier.weight(1f)) {
                layout = ChopLayout.CLASSIC
            }
            // FOLD DOUBLES: sixteen slices of a break are five sounds played
            // over and over; one pad per sound, the repeats cycling under it.
            SegmentButton("FOLD", active = fold, modifier = Modifier.weight(1f)) {
                layout = ChopLayout.FOLD
                onToast(Copy.FOLD_ON)
            }
            SegmentButton("MELODIC", active = melodic, modifier = Modifier.weight(1f)) {
                layout = ChopLayout.MELODIC
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
                        pitchLabel = if (melodic) pitchLabels?.getOrNull(row.n - 1) else foldTags?.getOrNull(row.n - 1),
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
                            busy = rechopBusy || sendBusy,
                            mergeWith = if (row.n < model.sliceCount) row.n + 1 else null,
                            enabled = !(rechopBusy || sendBusy),
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
                            onMerge = {
                                pickerFor = null
                                editSlices({ it.merged(row.n - 1) }, Copy.chopMerged(row.n), Copy.CHOP_MERGE_LAST)
                            },
                            onSplit = {
                                pickerFor = null
                                editSlices({ it.split(row.n - 1) }, Copy.chopSplit(row.n), Copy.chopNoSplit(row.n))
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
                        Log.e("ChopScreen", "rechop: failed", e)
                        onToast(Copy.RECHOP_FAILED)
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
                    val isFold = fold
                    val base = "${sourceFile.nameWithoutExtension} CHOP"
                    scope.launch {
                        try {
                            val send = withContext(Dispatchers.IO) {
                                when {
                                    isMelodic -> current.sendToGridMelodic()
                                    isFold -> current.sendToGridFolded()
                                    else -> current.sendToGrid()
                                }
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
                            onToast(if (isFold) Copy.foldedToGrid(current.sliceCount, send.sliceCount, send.chokeSet) else Copy.sentToGrid(send.sliceCount, send.chokeSet))
                            onSentToGrid(newEntry)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e("ChopScreen", "sendToGrid: failed", e)
                            onToast(Copy.SEND_FAILED)
                        } finally {
                            sendBusy = false
                        }
                    }
                }
            }
        }

        // ONTO <kit> · BANK X (bank B round 2): the same arrangement, landed
        // on the open kit's first empty bank instead of into a new kit —
        // the second page a user builds by hand. Only offered when there
        // is such a bank; `KitBuilderModel.landArranged` refuses a bank
        // with anything on it, so nothing here ever decides which of two
        // sounds a slot keeps. A bank holds sixteen: a wider chop lands its
        // first sixteen and the toast counts the rest.
        val landBank = entry?.let { e -> (0..1).firstOrNull { b -> e.kit.pads.none { it.slot in PadBanks.slots(b) } } }
        if (entry != null && landBank != null) {
            SecondaryButton(
                if (sendBusy) "…" else "ONTO ${entry.kit.name} · BANK ${PadBanks.letter(landBank)}",
                modifier = Modifier.fillMaxWidth().height(Layout.PRIMARY_ACTION_H.dp),
                enabled = !sendBusy && !rechopBusy,
            ) {
                if (sendBusy || rechopBusy) return@SecondaryButton
                sendBusy = true
                voice?.release()
                voice = null
                val current = model
                val isMelodic = melodic
                val isFold = fold
                val target = entry
                scope.launch {
                    try {
                        val send = withContext(Dispatchers.IO) {
                            when {
                                isMelodic -> current.sendToGridMelodic()
                                isFold -> current.sendToGridFolded()
                                else -> current.sendToGrid()
                            }
                        }
                        val (updated, landed) = withContext(Dispatchers.IO) {
                            // The same lock every kit writer takes: this is an
                            // open → assign → save on the open kit's kit.json.
                            KitWrites.mutex.withLock {
                                val m = KitBuilderModel.open(target.dir)
                                val slots = m.landArranged(send.arranged, landBank)
                                m.save()
                                if (teachEnabled) {
                                    val examples = current.labeledOverrides()
                                    if (examples.isNotEmpty()) {
                                        TeachLog.append(File(target.dir, TeachLog.FILE_NAME), examples)
                                    }
                                }
                                KitShelf.Entry(target.dir, m.kit) to slots
                            }
                        }
                        val left = send.arranged.drop(PadBanks.SIZE).count { it != null }
                        onToast(Copy.landedOnto(target.kit.name, PadBanks.letter(landBank), landed.size, left))
                        // KIT opens on the bank it landed on, not A: the
                        // pads that just arrived are the point of the tap.
                        onLandedOnto(updated, landBank)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        onToast("LAND FAILED: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        sendBusy = false
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
    busy: Boolean,
    /** The slice MERGE would join this one with (its 1-based number), or null on the last slice. */
    mergeWith: Int?,
    /**
     * False while a chop or a send is reading the model off the main
     * thread: a chip changed under it would hand the chop a half-carried
     * set of labels, or SEND labels from two moments. Every tap in the
     * panel stays announced and refuses, rather than vanishing.
     */
    enabled: Boolean = true,
    onPick: (DrumClass) -> Unit,
    onMachine: () -> Unit,
    onMerge: () -> Unit,
    onSplit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().lcdPanel(scheme).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The slice's own two cuts (docs/CHOP_CONTROLS.md): join it with
        // the next, or cut it at its own next hit. Under the chip because
        // this panel is already the slice's bench, and a row has no room.
        // MERGE on the last slice stays tappable and refuses in words
        // (this screen's convention: dimmed, not disabled; the toast
        // explains), so the refusal is something a user can actually hear.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SecondaryButton(
                mergeWith?.let { "MERGE WITH $it" } ?: "MERGE",
                modifier = Modifier.weight(1f).heightIn(min = Layout.MIN_HIT_TARGET.dp),
                enabled = !busy,
                onClick = onMerge,
            )
            SecondaryButton(
                "SPLIT AT ITS NEXT HIT",
                modifier = Modifier.weight(1f).heightIn(min = Layout.MIN_HIT_TARGET.dp),
                enabled = !busy,
                onClick = onSplit,
            )
        }
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
                            .tapeClick(label = ChopReviewModel.chipName(dc), enabled = enabled, onClick = { onPick(dc) }),
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
                // Tapping accepts the machine's own guess back (clears the
                // manual override) — named as that action, not just the
                // readout it shows.
                .tapeClick(label = "ACCEPT THE MACHINE'S GUESS: ${ChopReviewModel.chipName(machine)}", enabled = enabled, onClick = onMachine),
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

/**
 * The whole source with a marker at every cut — `PeaksPyramid` over the
 * tape once (it is the one thing on this screen that does not change),
 * the cuts redrawn whenever the model does. The first cut is where the
 * first slice starts, which is not always frame zero.
 */
@Composable
private fun SourceStrip(source: Snip, cuts: List<Int>, scheme: Scheme) {
    // The pyramid is real work on a long tape (CHOP takes the same
    // 600-second cap TAPE does), so it is built on IO and the strip
    // draws its markers alone until it lands, rather than freezing the
    // screen on first composition.
    val peaks by produceState<PeaksPyramid?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) { PeaksPyramid.fromSnip(source) }
    }
    Canvas(Modifier.fillMaxWidth().height(40.dp).lcdPanel(scheme).padding(horizontal = 2.dp)) {
        if (source.frameCount <= 0) return@Canvas
        val barStep = 2.dp.toPx()
        val h = size.height
        val halfH = h / 2f
        val count = max(1, (size.width / barStep).toInt())
        val columns = peaks?.columns(0, source.frameCount, count) ?: emptyList()
        for ((i, col) in columns.withIndex()) {
            val top = (halfH - col.max * halfH).coerceIn(0f, h)
            val bottom = (halfH - col.min * halfH).coerceIn(0f, h)
            drawRect(
                color = scheme.lcdInk.tape.copy(alpha = 0.55f),
                topLeft = Offset(i * barStep, top),
                size = Size(barStep, (bottom - top).coerceAtLeast(1f)),
            )
        }
        for (cut in cuts) {
            val x = (cut.toFloat() / source.frameCount * size.width).coerceIn(0f, size.width - 1f)
            drawRect(color = scheme.accent.tape, topLeft = Offset(x, 0f), size = Size(2.dp.toPx(), h))
        }
    }
}

/**
 * The CUT bench: BY HITS or GRID; ◀ the count ▶ with AUTO; and, by
 * hits, the EAR (how hard the detector listens) and the CUT (where each
 * cut lands against the attack). A GroupBox like the pad sheet's, closed
 * to one summary line by default so the review is what it was.
 */
@Composable
private fun CutBench(
    model: ChopReviewModel,
    open: Boolean,
    busy: Boolean,
    scheme: Scheme,
    onToggle: () -> Unit,
    onByHits: () -> Unit,
    onGrid: () -> Unit,
    onStep: (Int) -> Unit,
    onAuto: () -> Unit,
    onEar: (ChopReviewModel.Ear) -> Unit,
    onCut: (ChopReviewModel.Cut) -> Unit,
    /** (measured yet, the tempo): ON THE GRID's row reads the pulse it would snap to, or that none was heard. */
    tempo: Pair<Boolean, com.snipsnap.audio.TempoEstimate?>,
    /** ON THE GRID's row: the snap picked. (`onGrid` above is the BY HITS / GRID segment; the two are different things.) */
    onSnap: (ChopReviewModel.GridSnap) -> Unit,
) {
    val hits = model.mode as? ChopReviewModel.ChopMode.ByHits
    val readout = when (val mode = model.mode) {
        is ChopReviewModel.ChopMode.ByHits -> "${model.sliceCount} ${if (model.sliceCount == 1) "HIT" else "HITS"}"
        is ChopReviewModel.ChopMode.Grid -> "GRID ×${mode.parts}"
    }
    // By hits the count and the mode are two things (12 HITS · BY HITS ·
    // FINE); on a grid the mode label already is the count.
    val summary = if (hits != null) "$readout · ${model.modeLabel()}" else model.modeLabel()
    val unit = if (hits != null) "HIT" else "PART"
    GroupBox(
        legend = "CUT",
        summary = summary,
        open = open,
        onToggle = onToggle,
        scheme = scheme,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SegmentButton("BY HITS", active = hits != null, modifier = Modifier.weight(1f), onClick = onByHits)
            SegmentButton("GRID", active = hits == null, modifier = Modifier.weight(1f), onClick = onGrid)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SecondaryButton("◀", modifier = Modifier.weight(1f).heightIn(min = Layout.MIN_HIT_TARGET.dp), enabled = !busy, spoken = "ONE $unit FEWER") { onStep(-1) }
            Box(
                Modifier.weight(1.6f).heightIn(min = Layout.MIN_HIT_TARGET.dp).lcdPanel(scheme),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(if (busy) Copy.CHOP_BENCH_BUSY else readout, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 1)
            }
            SecondaryButton("▶", modifier = Modifier.weight(1f).heightIn(min = Layout.MIN_HIT_TARGET.dp), enabled = !busy, spoken = "ONE $unit MORE") { onStep(1) }
            if (hits != null) {
                SecondaryButton("AUTO", modifier = Modifier.weight(1f).heightIn(min = Layout.MIN_HIT_TARGET.dp), enabled = !busy, onClick = onAuto)
            }
        }
        if (hits != null) {
            TapeText("EAR · HOW HARD IT LISTENS", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (ear in ChopReviewModel.Ear.entries) {
                    SegmentButton(ear.name, active = hits.ear == ear, modifier = Modifier.weight(1f)) { if (!busy) onEar(ear) }
                }
            }
            TapeText("CUT · AGAINST THE ATTACK", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (cut in ChopReviewModel.Cut.entries) {
                    SegmentButton(cut.name, active = hits.cut == cut, modifier = Modifier.weight(1f)) { if (!busy) onCut(cut) }
                }
            }
            val (measured, t) = tempo
            val pulse = when {
                !measured -> "MEASURING…"
                t == null -> "NO TEMPO HEARD"
                else -> "${t.bpm.roundToInt()} BPM"
            }
            TapeText("ON THE GRID · CUTS ON THE PULSE · $pulse", TapeType.pixelSmall, if (t == null) scheme.ink3.tape else scheme.ink2.tape, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (grid in ChopReviewModel.GridSnap.entries) {
                    SegmentButton(grid.label, active = hits.grid == grid, modifier = Modifier.weight(1f)) { if (!busy) onSnap(grid) }
                }
            }
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
            .tapeClick(label = "HEAR SLICE ${row.n}", onClick = onTapRow)
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
                    // NOT "RECLASSIFY": the long press does the opposite
                    // of reclassifying - it drops any override and puts the
                    // classifier's own call back (onLongPressChip below is
                    // clearOverride). Tapping is what reclassifies, by
                    // opening the picker. TalkBack was announcing this
                    // action as the one gesture it is not.
                    onLongClickLabel = "RESTORE THE MACHINE'S CALL",
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

/**
 * Batch 3, Task 5: bright border + sub-label, not a solid fill — SETUP's
 * own selected-state treatment (`PropertiesScreen.kt`'s `SchemeRow`,
 * [pressedBevel] vs [raisedBevel]), applied here so CLASSIC/MELODIC agrees
 * with every other picker in the app rather than inventing its own third
 * convention. [pressedBevel]'s own KDoc is where the colourblindness case
 * for a border over a fill is made (a thicker, distinctly-hued ring reads
 * even to someone who can't use the hue at all, where two fills measured
 * 1.02–1.07:1 contrast against each other); this call site is citing that
 * precedent, not re-deriving it. The "SELECTED" line is reserved — not
 * conditionally inserted — on BOTH segments, so the row doesn't grow a
 * second line only on the active half and read as jagged against its
 * neighbour.
 */
@Composable
private fun SegmentButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = LocalScheme.current
    Column(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .let { if (active) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
            .semantics { this.selected = active }
            .tapeClick(label = label, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TapeText(label, TapeType.pixel, if (active) scheme.ink.tape else scheme.ink2.tape)
        TapeText(if (active) "SELECTED" else "", TapeType.pixelSmall, scheme.ink2.tape)
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** What a screen reader says for a glyph-only label (◀, ▶): the action, not the arrow. Null = the label itself. */
    spoken: String? = null,
    onClick: () -> Unit,
) {
    val scheme = LocalScheme.current
    Box(
        modifier
            .raisedBevel(scheme)
            // Always clickable, `enabled` forwarded rather than dropped: a
            // screen reader is told this control is temporarily unavailable
            // instead of it silently vanishing from the tree (accessibility
            // audit finding 12 — see ActionButton in PadSheetScreen.kt).
            .tapeClick(label = spoken ?: label, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (enabled) scheme.ink.tape else scheme.ink2.tape)
    }
}

/** Which layout the grid preview and SEND use: the classifier's placement, the low-to-high scale, or one pad per sound. */
private enum class ChopLayout { CLASSIC, FOLD, MELODIC }

/** The "NOT SURE" dashed-chip treatment — a guess, and honest about it. */
private fun Modifier.dashedBorder(color: Color, radius: Dp = 4.dp): Modifier = this.drawWithContent {
    drawContent()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)),
    )
}
