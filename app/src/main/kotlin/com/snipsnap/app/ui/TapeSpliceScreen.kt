package com.snipsnap.app.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import com.snipsnap.audio.TapeSplice
import com.snipsnap.audio.Transients
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SpliceNeedle
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * TAPE SPLICE: joins two prior takes of ONE pad at one chosen frame — a
 * cut, not a blend. Reached from PAD SHEET's SPLICE ▸ button, the same
 * shelf-level-overlay shape TAKES+BIN and PAD SHEET itself already use.
 *
 * Two steps, one screen: pick a HEAD take and a TAIL take from this pad's
 * own recoverable history (its live sample counts as a candidate too —
 * it's simply always available, never binned until something else
 * rewrites it), then drag a shared needle over both stacked waveforms to
 * choose the one frame where the head hands off to the tail.
 * [TapeSplice.join] decides on its own whether the raw cut needs a short
 * declick overlap; COMMIT is honest about which happened
 * ([Copy.spliced]). Whatever was live before COMMIT is itself binned,
 * same as every other pad rewrite — undoable from THE BIN.
 */
@Composable
fun TapeSpliceScreen(
    entry: KitShelf.Entry,
    slot: Int,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    var model by remember(entry.dir) { mutableStateOf<KitBuilderModel?>(null) }
    var loadFailed by remember(entry.dir) { mutableStateOf(false) }
    var candidates by remember(entry.dir) { mutableStateOf<List<Take>>(emptyList()) }
    // The pad this screen opened on, by its file - COMMIT's identity check.
    // A slot reassigned underneath an open overlay is exactly the
    // "Frankenstein entry" PadSheetScreen's onDesample already guards
    // against; splicing these takes into a stranger's pad would silently
    // overwrite its audio.
    var liveSampleFile by remember(entry.dir) { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.dir, slot) {
        loadFailed = false
        // Locked, not just opened - same reasoning as TakesBinScreen's own
        // mount open: this must never read `kitDir` mid-write.
        val opened = withContext(Dispatchers.IO) {
            KitWrites.mutex.withLock { runCatching { KitBuilderModel.open(entry.dir) }.getOrNull() }
        }
        val pad = opened?.kit?.pad(slot)
        if (opened == null || pad == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        model = opened
        liveSampleFile = pad.sampleFile
        val prior = opened.priorTakes(slot)
        candidates = listOf(Take.Live(File(entry.dir, pad.sampleFile))) + prior.map { Take.Prior(it) }
    }

    if (loadFailed) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpliceHeader(onBack, "SPLICE", scheme)
            Box(Modifier.fillMaxSize().weight(1f).lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
                TapeText(Copy.SPLICE_KIT_GONE, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
            }
        }
        return
    }
    if (model == null) {
        Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }
    // Only the LIVE candidate exists: nothing has ever rewritten this pad,
    // so there's no second take to splice against - an honest refusal,
    // stated in words, matching this app's own law over a dead button.
    if (candidates.size < 2) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpliceHeader(onBack, "SPLICE", scheme)
            Box(Modifier.fillMaxSize().weight(1f).lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
                TapeText(Copy.SPLICE_NEEDS_HISTORY, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 4)
            }
        }
        return
    }

    var headTake by remember(entry.dir) { mutableStateOf<Take?>(null) }
    var tailTake by remember(entry.dir) { mutableStateOf<Take?>(null) }
    var loadedHead by remember(entry.dir) { mutableStateOf<LoadedTake?>(null) }
    var loadedTail by remember(entry.dir) { mutableStateOf<LoadedTake?>(null) }
    // Why the pair can't be spliced, in words - null while it can. Said
    // here, before the needle ever shows, rather than as a thrown refusal
    // out of TapeSplice.join at PREVIEW or COMMIT.
    var pairRefusal by remember(entry.dir) { mutableStateOf<String?>(null) }
    var needleFrame by remember(entry.dir) { mutableStateOf(0) }
    var busy by remember(entry.dir) { mutableStateOf(false) }

    LaunchedEffect(headTake, tailTake) {
        val h = headTake
        val t = tailTake
        if (h == null || t == null) {
            loadedHead = null
            loadedTail = null
            return@LaunchedEffect
        }
        pairRefusal = null
        val (lh, lt) = withContext(Dispatchers.IO) { loadTake(h) to loadTake(t) }
        if (lh == null || lt == null) {
            pairRefusal = Copy.SPLICE_TAKE_UNREADABLE
            loadedHead = null
            loadedTail = null
        } else if (lh.original.sampleRate != lt.original.sampleRate || lh.original.channels != lt.original.channels) {
            // TapeSplice.join's own contract, checked up front: a mutated
            // take is stereo where its original was mono, and the two
            // can't be butted together without folding one - refused
            // here in words instead of thrown at COMMIT.
            pairRefusal = Copy.SPLICE_FORMATS_DIFFER
            loadedHead = null
            loadedTail = null
        } else {
            loadedHead = lh
            loadedTail = lt
            needleFrame = min(lh.original.frameCount, lt.original.frameCount) / 2
        }
    }

    fun pickHead(take: Take) {
        headTake = take
        if (tailTake == take) tailTake = null
    }
    fun pickTail(take: Take) {
        tailTake = take
        if (headTake == take) headTake = null
    }

    val lh = loadedHead
    val lt = loadedTail
    val refusal = pairRefusal
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SpliceHeader(onBack, "SPLICE", scheme)
        if (lh != null && lt != null) {
            NeedleStep(
                head = lh,
                tail = lt,
                needleFrame = needleFrame,
                onNeedleChange = { needleFrame = it },
                onNeedleSettle = {
                    needleFrame = SpliceNeedle.snap(
                        needleFrame,
                        min(lh.original.frameCount, lt.original.frameCount),
                        lh.mono,
                        lh.onsets,
                        lt.mono,
                        lt.onsets,
                        lh.original.sampleRate,
                    )
                },
                busy = busy,
                onRepick = {
                    headTake = null
                    tailTake = null
                },
                onCommit = {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                var result: TapeSplice.Spliced? = null
                                var refusal: String? = null
                                val openedOn = liveSampleFile
                                val fresh = withFreshKit(entry.dir) { f ->
                                    val freshPad = f.kit.pad(slot)
                                    when {
                                        freshPad == null -> refusal = Copy.SPLICE_KIT_GONE
                                        // Same slot, different pad: the takes on
                                        // screen aren't this pad's history, so
                                        // nothing is written (see liveSampleFile).
                                        freshPad.sampleFile != openedOn -> refusal = Copy.SPLICE_PAD_CHANGED
                                        else -> result = f.splicePad(slot, lh.original, needleFrame, lt.original, needleFrame)
                                    }
                                }
                                val spliced = result
                                if (spliced != null) {
                                    onKitUpdated(fresh.kit)
                                    onToast(Copy.spliced(spliced.crossfaded))
                                    onBack()
                                } else {
                                    onToast(refusal ?: Copy.SPLICE_KIT_GONE)
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.e("TapeSpliceScreen", "splice: failed", e)
                                onToast(Copy.SPLICE_FAILED)
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                onToast = onToast,
                scheme = scheme,
                modifier = Modifier.weight(1f, fill = true),
            )
        } else if (refusal != null) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f).lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
                    TapeText(refusal, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 4)
                }
                ActionButton(
                    "◄ CHOOSE DIFFERENT TAKES",
                    scheme,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        headTake = null
                        tailTake = null
                    },
                )
            }
        } else if (headTake != null && tailTake != null) {
            Box(Modifier.fillMaxSize().weight(1f).lcdPanel(scheme))
        } else {
            PickingStep(
                candidates = candidates,
                headTake = headTake,
                tailTake = tailTake,
                busy = busy,
                onPickHead = ::pickHead,
                onPickTail = ::pickTail,
                scheme = scheme,
                modifier = Modifier.weight(1f, fill = true),
            )
        }
    }
}

@Composable
private fun SpliceHeader(onBack: () -> Unit, title: String, scheme: Scheme) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .lcdPanel(scheme)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton("◄ PAD", scheme, enabled = true, modifier = Modifier.width(72.dp), onClick = onBack)
        TapeText(title, TapeType.lcdHeader, scheme.lcdInk.tape, Modifier.weight(1f).padding(start = 8.dp))
    }
}

@Composable
private fun PickingStep(
    candidates: List<Take>,
    headTake: Take?,
    tailTake: Take?,
    busy: Boolean,
    onPickHead: (Take) -> Unit,
    onPickTail: (Take) -> Unit,
    scheme: Scheme,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TapeText(
            Copy.SPLICE_PICK_HINT,
            TapeType.pixelSmall,
            scheme.ink3.tape,
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            maxLines = 2,
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(candidates, key = { it.identity() }) { take ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp).lcdPanel(scheme).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TapeText(take.label().uppercase(), TapeType.pixelSmall, scheme.ink.tape, Modifier.weight(1f), maxLines = 1)
                    ActionButton("HEAD", scheme, enabled = !busy, lit = headTake == take, modifier = Modifier.width(76.dp), onClick = { onPickHead(take) })
                    ActionButton("TAIL", scheme, enabled = !busy, lit = tailTake == take, modifier = Modifier.width(76.dp), onClick = { onPickTail(take) })
                }
            }
        }
    }
}

@Composable
private fun NeedleStep(
    head: LoadedTake,
    tail: LoadedTake,
    needleFrame: Int,
    onNeedleChange: (Int) -> Unit,
    onNeedleSettle: () -> Unit,
    busy: Boolean,
    onRepick: () -> Unit,
    onCommit: () -> Unit,
    onToast: (String) -> Unit,
    scheme: Scheme,
    modifier: Modifier = Modifier,
) {
    val spliceMaxFrame = min(head.original.frameCount, tail.original.frameCount)
    val sharedMaxFrames = max(head.original.frameCount, tail.original.frameCount).coerceAtLeast(1)
    var voice by remember(head, tail) { mutableStateOf<TapeVoice?>(null) }
    DisposableEffect(head, tail) {
        onDispose { voice?.release() }
    }
    // Losing the foreground stops the preview, same as TapeScreen's own
    // deck: a PREVIEW that outlives Home has no way to be stopped and no
    // media session to show for it. Deliberately does not resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voice?.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ActionButton(
            "◄ CHOOSE DIFFERENT TAKES",
            scheme,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRepick,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(head, tail) {
                    // `size` read fresh inside `frameAt` on every call, not
                    // once at the top of this block - the same "layout may
                    // not have landed yet" trap TapeScreen.kt's own waveform
                    // gesture handler avoids for the same reason.
                    // Null while the width is still 0: dividing by it would
                    // hand `toInt()` an Infinity/NaN and throw the needle to
                    // an edge on the first touch. Doing nothing is the
                    // honest answer to a touch before layout.
                    fun frameAt(x: Float): Int? {
                        val w = size.width
                        if (w <= 0) return null
                        val framesPerPixel = sharedMaxFrames.toFloat() / w.toFloat()
                        return (x * framesPerPixel).toInt().coerceIn(0, spliceMaxFrame)
                    }
                    detectDragGestures(
                        onDragStart = { offset -> frameAt(offset.x)?.let(onNeedleChange) },
                        onDragEnd = { onNeedleSettle() },
                        onDragCancel = { onNeedleSettle() },
                        onDrag = { change, _ ->
                            change.consume()
                            frameAt(change.position.x)?.let(onNeedleChange)
                        },
                    )
                },
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SpliceLane(head, sharedMaxFrames, needleFrame, "HEAD", scheme, Modifier.weight(1f))
                SpliceLane(tail, sharedMaxFrames, needleFrame, "TAIL", scheme, Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(
                "▶ PREVIEW",
                scheme,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    voice?.release()
                    // The pair was format-checked at load, so join can't
                    // refuse here in practice - but a refusal is a toast,
                    // never a crash, same as COMMIT's own guard.
                    val samples = runCatching { previewSamples(head, tail, needleFrame) }
                        .getOrElse { e ->
                            Log.e("TapeSpliceScreen", "preview: failed", e)
                            onToast(Copy.PREVIEW_FAILED)
                            return@ActionButton
                        }
                    val v = TapeVoice(samples, head.original.sampleRate)
                    voice = v
                    v.start(0)
                },
            )
            ActionButton(
                // Batch 3, Task 4: no ▸ — COMMIT writes the splice in
                // place; it doesn't navigate or open a panel.
                if (busy) "SPLICING…" else "SPLICE · COMMIT",
                scheme,
                enabled = !busy,
                modifier = Modifier.weight(1.4f).heightIn(min = Layout.PRIMARY_ACTION_H.dp),
                onClick = onCommit,
            )
        }
    }
}

@Composable
private fun SpliceLane(loaded: LoadedTake, sharedMaxFrames: Int, needleFrame: Int, label: String, scheme: Scheme, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().lcdPanel(scheme)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val halfHeight = h / 2f
            val framesPerPixel = sharedMaxFrames.toDouble() / w
            val ownWidthPx = (loaded.original.frameCount / framesPerPixel).toFloat().coerceIn(0f, w)
            if (ownWidthPx > 0f) {
                val columnCount = max(1, (ownWidthPx / BAR_STEP_PX).toInt())
                val columns = loaded.peaks.columns(0, loaded.original.frameCount, columnCount)
                for (i in columns.indices) {
                    val x = i * BAR_STEP_PX
                    val col = columns[i]
                    val top = (halfHeight - col.max * halfHeight).coerceIn(0f, h)
                    val bottom = (halfHeight - col.min * halfHeight).coerceIn(0f, h)
                    drawRect(
                        color = scheme.lcdInk.tape,
                        topLeft = Offset(x, top),
                        size = Size(BAR_WIDTH_PX, (bottom - top).coerceAtLeast(1f)),
                    )
                }
            }
            val needleX = (needleFrame / framesPerPixel).toFloat().coerceIn(0f, w)
            drawLine(scheme.warn.tape, Offset(needleX, 0f), Offset(needleX, h), strokeWidth = NEEDLE_STROKE_PX)
        }
        TapeText(label, TapeType.pixelSmall, scheme.ink3.tape, Modifier.align(Alignment.TopStart).padding(4.dp), maxLines = 1)
    }
}

private const val BAR_STEP_PX = 3f
private const val BAR_WIDTH_PX = 2f
private const val NEEDLE_STROKE_PX = 2f

/** One pickable source for a lane: this pad's live sample, or a recoverable prior take from the bin. */
private sealed class Take {
    data class Live(val liveFile: File) : Take()
    data class Prior(val entry: KitBuilderModel.BinEntry) : Take()
}

private fun Take.file(): File = when (this) {
    is Take.Live -> liveFile
    is Take.Prior -> entry.file
}

/** "LIVE", or this take's age off its real archive timestamp - no invented "what changed" text. */
private fun Take.label(): String = when (this) {
    is Take.Live -> "LIVE"
    is Take.Prior -> agoLabel(entry.binnedAtMillis)
}

/** A stable per-row key for [LazyColumn] - the file path is unique per candidate; two [Take.Prior] entries never share a bin file. */
private fun Take.identity(): String = file().absolutePath

/** Coarse relative age off a take's real archive timestamp (its file mtime) — no invented "what changed" text; duplicated per this codebase's own file-local convention (see TakesBinScreen.kt's own copy). */
private fun agoLabel(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val diffMs = (nowMillis - millis).coerceAtLeast(0)
    val minutes = diffMs / 60_000
    val hours = diffMs / 3_600_000
    val days = diffMs / 86_400_000
    return when {
        minutes < 1 -> "JUST NOW"
        minutes < 60 -> "$minutes MIN AGO"
        hours < 24 -> "$hours HR AGO"
        days == 1L -> "YESTERDAY"
        else -> "${days}D AGO"
    }
}

private class LoadedTake(
    val original: Snip,
    val mono: FloatArray,
    val onsets: IntArray,
    val peaks: PeaksPyramid,
)

/**
 * Decodes [take]'s file through [WavReader.readCapped] — same
 * [TAPE_LOAD_MAX_SEC] ceiling TAPE itself uses — for both the full-format
 * [LoadedTake.original] SPLICE actually commits and the mono analysis
 * buffer its waveform/onset/preview machinery reads. `null` on anything
 * unreadable (a bin entry another process purged out from under this
 * screen, a torn WAV) or an [OutOfMemoryError] the cap didn't prevent —
 * either way an honest miss, never a crash.
 */
private fun loadTake(take: Take): LoadedTake? {
    val file = take.file()
    if (!file.isFile) return null
    return try {
        val capped = WavReader.readCapped(file, TAPE_LOAD_MAX_SEC)
        val mono = Cleanup.toMono(capped.snip)
        val onsets = Transients.detect(mono).map { it.frame }.sorted().toIntArray()
        LoadedTake(capped.snip, mono.samples, onsets, PeaksPyramid.fromSnip(mono))
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }
}

/** The live preview: head-up-to-the-needle then tail-from-it, mono-folded like the waveform it's previewing. */
private fun previewSamples(head: LoadedTake, tail: LoadedTake, needleFrame: Int): FloatArray {
    val h = Snip(head.mono, 1, head.original.sampleRate)
    val t = Snip(tail.mono, 1, tail.original.sampleRate)
    return TapeSplice.join(h, needleFrame, t, needleFrame).snip.samples
}

/**
 * The one correct shape for a kit mutation issued by a screen whose own
 * [KitBuilderModel] was opened earlier, outside any lock — mirrors
 * PadSheetScreen's own `withFreshKit` (same file-local-duplication
 * convention TakesBinScreen.kt's own copy already follows, rather than
 * exporting a cross-screen shared function). Locks, opens a FRESH model
 * on [kitDir], runs [block] against it, and saves once — but only if
 * [block] actually left the fresh model [KitBuilderModel.dirty].
 */
private suspend fun withFreshKit(kitDir: File, block: (KitBuilderModel) -> Unit): KitBuilderModel {
    return withContext(Dispatchers.IO) {
        KitWrites.mutex.withLock {
            val fresh = KitBuilderModel.open(kitDir)
            block(fresh)
            if (fresh.dirty) fresh.save()
            fresh
        }
    }
}
