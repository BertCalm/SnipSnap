package com.snipsnap.app.ui

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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** How long a reel has to be held before it counts as the pencil-rewind long-press. */
private const val PENCIL_LONG_PRESS_MS = 500L

/**
 * How long a synthesized TalkBack long-click holds the pencil rewind on
 * for. A real hold runs for as long as the finger stays down; a
 * semantics action has no such duration, so this is a fixed, brief
 * substitute — long enough to be audible as a nudge, short enough not
 * to run away with the tape unattended.
 */
private const val PENCIL_A11Y_NUDGE_MS = 250L

/**
 * How long a screen-reader WIND lasts, in milliseconds — [WindButton]'s
 * own [PENCIL_A11Y_NUDGE_MS], and for the same reason. `windStart` only
 * sets a target speed; the tape is moved by the step loop's per-frame
 * `step()`, so a start/stop pair with no elapsed time between them resets
 * the target before a single tick can ever observe it and the tape does
 * not move at all. The action would announce success and do nothing.
 * Shorter than the pencil's nudge because winding is much faster per
 * tick than a rewind-by-ear.
 */
private const val WIND_A11Y_NUDGE_MS = 180L

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

/**
 * TAPE's own ceiling on how much of a source file it will ever decode into
 * memory, in seconds. Every other ingest path is already capped before its
 * file reaches this screen — [MicSessionService.RING_SECONDS] (60s) for a
 * live capture, [SnipStore.IMPORT_MAX_SEC] (180s) for a shared-in import —
 * but two of TAPE's own load-source branches (see [loadLongestTape]'s KDoc)
 * hand it a file TAPE never chose the size of: the open kit's own pad
 * samples, reachable via MUTATE's arbitrary file picker, and the last
 * COMMIT's source file, which is whatever pad sample or shelf file COMMIT
 * was last run against. Without a cap here, either one can point at an
 * hours-long file and OOM the process outright — see [WavReader.readCapped].
 *
 * The arithmetic: TAPE decodes to mono float32, 4 bytes/frame, so a mono
 * source at the MPC-native 44.1 kHz rate costs `4 * 44_100 = 176_400`
 * bytes/second (176.4 KB/s). At this cap: `600 s * 176_400 B/s ≈ 105.8 MB`
 * for the sample buffer — [PeaksPyramid] adds only a small, bounded
 * fraction on top of that: level 0 alone is `N/128` floats (`baseBlock`
 * 256, min+max per block), and each level above halves, so the levels'
 * total is `N/128 * 2 = N/64` — ~1.6% of the source, ≈1.7 MB at this cap,
 * not a second copy of it. A 60-minute file uncapped was ~635 MB and a
 * near-certain OOM kill on a phone heap; 10 minutes is long enough that a
 * real jam or a full side of a cassette still loads whole, and short
 * enough that even a worst-case format (high sample rate, stereo, 32-bit)
 * stays well inside what an Android app heap can be expected to hold —
 * for a single file. But seconds alone do not bound memory: this cap
 * assumes mono 44.1 kHz, and nothing stops a picked file from being
 * high-rate stereo instead — [WavReader.readCapped]'s own
 * [WavReader.MAX_DECODE_BYTES] is the byte-precise ceiling that actually
 * bounds the worst case (a 192 kHz stereo file loads a correspondingly
 * shorter prefix, not 600 seconds of it). [loadLongestFromKit] measures
 * every pad's duration via [WavReader.peekSeconds] — a header-only read,
 * no decode — before decoding anything, then decodes only the longest
 * one, once. That removes the OLD worst case entirely: no more retained
 * running-longest candidate held live while the next pad decodes beside
 * it. The remaining peak — one file's raw slice, its interleaved decode,
 * and the mono copy made from it, in flight together only briefly — is
 * the ordinary cost of decoding a single capped file (see
 * [WavReader.MAX_DECODE_BYTES]'s own KDoc for that shape), now paid once
 * per kit-fallback load instead of once per pad in the kit.
 *
 * `internal`, not `private`: the same cap applies wherever `App.kt`
 * (`readGroove`, `stealFeel`, `instantKit`) or `ChopScreen.kt`
 * (`loadFromCommit`) re-decode a file this screen already showed —
 * `tapeData.sourceFile`, or a `TapeCommit` built from it — since TAPE's
 * selection ranges are only ever valid against the same capped frame
 * count. A second copy of `600f` there would be exactly the kind of
 * drift this comment is trying to prevent.
 */
internal const val TAPE_LOAD_MAX_SEC = 600f

/** What [readMono] decoded: the audio, and whether [TAPE_LOAD_MAX_SEC] cut it short. */
private class MonoRead(val snip: Snip, val truncated: Boolean)

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
    /** Whether [TAPE_LOAD_MAX_SEC] cut this tape's source short — [TapeScreen] toasts once when true. */
    val truncated: Boolean,
)

/**
 * The TAPE screen: drag-under-a-fixed-needle scrubbing over the newest snip,
 * else the last COMMIT's source, else (only with a kit open) the open kit's
 * longest sample — see [loadLongestTape]'s KDoc for the full priority order
 * — with the coast/snap/pencil-rewind physics driven entirely by `:shell`'s
 * tested [TapeDeckModel]. This file only renders it and forwards gestures,
 * plus a minimal unity-speed [TapeVoice] for playback.
 */
@Composable
fun TapeScreen(
    entry: KitShelf.Entry?,
    lastCommitSource: File?,
    onToast: (String) -> Unit,
    onCommit: (File, IntRange) -> Unit,
    /**
     * INSTANT KIT: the range to chop, and whether the user actually drew it.
     * The flag can't be re-derived downstream — a selection dragged across
     * the whole tape is a different event from no selection at all, and only
     * this screen knows which happened (September UAT, finding 19).
     */
    onInstantKit: (File, IntRange, Boolean) -> Unit,
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

    // entry is nullable on purpose: ARM/SNIP live on the shelf (KitsScreen),
    // where no kit is open, so a snip taken there must still be able to
    // reach TAPE. loadLongestTape's snip and lastCommitSource branches are
    // already kit-independent — only its kit-fallback branch needs `entry`,
    // and it's skipped when `entry == null`. An early return here (the old
    // bug) would have blocked those kit-independent branches from ever
    // running when the shelf has no kit open.
    val kitDir = entry?.dir
    var loaded by remember(kitDir) { mutableStateOf<LoadedTape?>(null) }
    var failed by remember(kitDir) { mutableStateOf(false) }
    // Bumped by TapeDeckContent's idle-reload watcher, or by the
    // empty-state watcher just below, to force a fresh call to
    // loadLongestTape without changing `kitDir` (the other triggers below
    // being the app's own reloadRequest) — see each watcher's own comment
    // for what bumps it and why.
    var reloadToken by remember(kitDir) { mutableStateOf(0) }

    LaunchedEffect(kitDir, reloadToken, reloadRequest) {
        loaded = null
        failed = false
        val result = withContext(Dispatchers.IO) {
            loadLongestTape(entry, context.filesDir, lastCommitSource)
        }
        // Distinct from an ordinary unreadable file (silent, always was):
        // this is [TAPE_LOAD_MAX_SEC]'s own safety net catching an
        // OutOfMemoryError the cap didn't manage to prevent — see
        // [readMono]'s KDoc. Said even when a later candidate went on to
        // load successfully, since the file that OOM'd is still real and
        // still unreadable from TAPE.
        if (result.oomEncountered) onToast(Copy.TAPE_TOO_BIG)
        val tape = result.tape
        if (tape == null) {
            failed = true
        } else {
            loaded = tape
            // The actual kept duration, not the nominal TAPE_LOAD_MAX_SEC:
            // WavReader.MAX_DECODE_BYTES can bind before the duration cap
            // does (a high-rate/stereo source), so the true cutoff here can
            // be well short of 600s — see TAPE_LOAD_MAX_SEC's own KDoc.
            if (tape.truncated) onToast(Copy.tapeTruncated(tape.samples.size / tape.sampleRate.toFloat()))
        }
    }

    // Empty-state reload: TapeDeckContent's own lastSnipFile watcher only
    // exists once a tape has loaded, so it can't catch the first snip taken
    // while TAPE is showing the empty deck (the exact shelf-with-no-kit
    // case this screen exists to fix). This one covers exactly that gap and
    // nothing else — it reads `loaded`/`failed` live via the property
    // delegates above, so it always sees the current load state, not a
    // value frozen at launch. Keyed on `kitDir`, matching the state it
    // reads/writes, so it's torn down and relaunched in lockstep with those
    // `remember(kitDir)` slots rather than outliving them.
    LaunchedEffect(kitDir) {
        MicSessionService.lastSnipFile.collect { file ->
            // Gated on `failed` (settled empty), not `loaded == null`
            // (which is also true while a load is still in flight).
            // `lastSnipFile` is a StateFlow — a fresh collector replays its
            // current value immediately — so on the ordinary arm → snip →
            // open TAPE flow, the in-flight load effect is still running
            // (failed=false) when this replay lands; gating on `loaded ==
            // null` would bump reloadToken right then and cancel/restart
            // that in-flight load for no reason (a double decode + a
            // second blank-LCD flash). Waiting for `failed` means this only
            // nudges a reload once the deck has genuinely settled on
            // nothing — at which point the in-flight load has already
            // picked up the replayed snip on its own, or a later, truly
            // new snip arrives. Once a tape is loaded, TapeDeckContent's
            // idle-gated watcher owns reloads (it must not interrupt an
            // active scrub), and `failed` goes false the moment a load
            // resolves — so this cannot loop against a loaded tape either.
            if (file != null && failed) reloadToken++
        }
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

/** [loadLongestTape]'s answer: the tape it settled on (if any), and whether [readMono] hit [TAPE_LOAD_MAX_SEC]'s OOM safety net along the way. */
private class TapeLoadResult(val tape: LoadedTape?, val oomEncountered: Boolean)

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
private fun loadLongestTape(entry: KitShelf.Entry?, filesDir: File, lastCommitSource: File?): TapeLoadResult {
    var oomEncountered = false
    for (file in listOfNotNull(SnipStore.newest(filesDir), lastCommitSource)) {
        when (val outcome = readMono(file)) {
            is MonoOutcome.Ok -> return TapeLoadResult(buildLoadedTape(file, outcome.read), oomEncountered)
            MonoOutcome.OutOfMemory -> oomEncountered = true
            MonoOutcome.Unreadable -> {}
        }
    }
    // The kit fallback is the only branch that needs a kit — skip it
    // outright when none is open (the shelf, with a session armed there)
    // rather than let it run on a null entry.
    if (entry == null) return TapeLoadResult(null, oomEncountered)
    val kit = loadLongestFromKit(entry)
    if (kit.oomEncountered) oomEncountered = true
    val found = kit.found ?: return TapeLoadResult(null, oomEncountered)
    return TapeLoadResult(buildLoadedTape(found.first, found.second), oomEncountered)
}

/**
 * What one file's decode produced: the audio (capped to [TAPE_LOAD_MAX_SEC]
 * and mixed to mono) on success, an ordinary unreadable-file miss (silent,
 * same as always — a file that isn't a WAV, or isn't one anymore), or an
 * [OutOfMemoryError] the cap didn't manage to prevent.
 */
private sealed class MonoOutcome {
    class Ok(val read: MonoRead) : MonoOutcome()
    object Unreadable : MonoOutcome()
    object OutOfMemory : MonoOutcome()
}

/**
 * Decodes [file] through [WavReader.readCapped] — never [WavReader.read]'s
 * unbounded whole-file load — so neither of TAPE's two uncapped ingest
 * paths (a kit pad reachable via MUTATE's arbitrary file picker, or the
 * last COMMIT's source file) can hand this an hours-long file and OOM the
 * process on the raw decode. [OutOfMemoryError] is caught separately from
 * [Exception] on purpose: it is a [Error], not an [Exception], so a plain
 * `catch (e: Exception)` here — the shape every other call site in this
 * file already used — would never have caught it, and the process would
 * die instead of this function returning [MonoOutcome.OutOfMemory]. Either
 * catch leaves no partial state behind: nothing is written anywhere until
 * a full [MonoRead] comes back successfully.
 */
private fun readMono(file: File): MonoOutcome {
    if (!file.isFile) return MonoOutcome.Unreadable
    return try {
        val capped = WavReader.readCapped(file, TAPE_LOAD_MAX_SEC)
        MonoOutcome.Ok(MonoRead(Cleanup.toMono(capped.snip), capped.truncated))
    } catch (e: OutOfMemoryError) {
        MonoOutcome.OutOfMemory
    } catch (e: Exception) {
        MonoOutcome.Unreadable
    }
}

/** [loadLongestFromKit]'s answer: the longest readable pad found (if any), and whether an OOM was swallowed skipping past a pad along the way. */
private class KitLongestResult(val found: Pair<File, MonoRead>?, val oomEncountered: Boolean)

/**
 * Every pad's WAV, mixed to mono, keeping the longest — TAPE's fallback
 * when neither a snip nor a last-commit source resolves.
 *
 * Finds the longest pad by [WavReader.peekSeconds] alone — a header-only
 * read that never decodes a sample — then decodes that one winner, once,
 * through [readMono]. The old shape decoded every pad just to measure it,
 * so while comparing an unusually long kit it could hold a retained
 * running-longest candidate's decode live AT THE SAME TIME as the next
 * pad's raw slice and its own decode — up to three buffers at once, and
 * that multiple grew with how many long pads the kit had. This holds at
 * most one pad's worth (its raw slice, its interleaved decode, and the
 * mono copy made from it — the ordinary cost of decoding a single capped
 * file) no matter how many pads the kit has, because only the header is
 * touched until the winner is already chosen. If the header-longest pad
 * then fails to actually decode (OOM, or a header that resolved but a
 * body that doesn't), this reports that failure rather than falling back
 * to the next-longest candidate — a second decode attempt would reintroduce
 * the multi-buffer cost this fix removes, and a pad whose header parses
 * but whose body doesn't is already the rare, malformed case.
 */
private fun loadLongestFromKit(entry: KitShelf.Entry): KitLongestResult {
    var longestFile: File? = null
    var longestSeconds = -1f
    for (pad in entry.kit.pads) {
        val file = File(entry.dir, pad.sampleFile)
        if (!file.isFile) continue
        val seconds = WavReader.peekSeconds(file) ?: continue
        if (seconds > longestSeconds) {
            longestSeconds = seconds
            longestFile = file
        }
    }
    val file = longestFile ?: return KitLongestResult(null, oomEncountered = false)
    return when (val outcome = readMono(file)) {
        is MonoOutcome.Ok -> KitLongestResult(file to outcome.read, oomEncountered = false)
        MonoOutcome.OutOfMemory -> KitLongestResult(null, oomEncountered = true)
        MonoOutcome.Unreadable -> KitLongestResult(null, oomEncountered = false)
    }
}

private fun buildLoadedTape(file: File, mono: MonoRead): LoadedTape {
    val chosen = mono.snip
    val onsets = Transients.detect(chosen).map { it.frame }.sorted().toIntArray()
    val peaks = PeaksPyramid.fromSnip(chosen)
    return LoadedTape(chosen.samples, chosen.sampleRate, onsets, peaks, file, mono.truncated)
}

@Composable
private fun TapeDeckContent(
    entry: KitShelf.Entry?,
    tapeData: LoadedTape,
    onToast: (String) -> Unit,
    onCommit: (File, IntRange) -> Unit,
    onInstantKit: (File, IntRange, Boolean) -> Unit,
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

    // Everything the model exposes OTHER than position (button labels,
    // the selection, zoom, odometer mode) is a plain, unobserved var — it
    // only reaches the screen when something recomposes this composable
    // and its (structurally unstable, hence unskippable) children re-read
    // it. `uiGeneration` is that trigger, but — unlike the old `tick` —
    // it's bumped only from discrete UI events (`touch()`), never from the
    // per-frame loop, so it doesn't reintroduce 60×/sec recomposition.
    // Declared before the lifecycle effect below (and everything else that
    // closes over `touch`) so those closures see a fully-initialized `touch`.
    var uiGeneration by remember(model) { mutableStateOf(0) }
    fun touch() {
        uiGeneration++
    }
    // The composition-scope read that actually subscribes this composable
    // (and its unstable-param, hence unskippable, children) to
    // `uiGeneration` bumps — mirrors the old `val frameTick = tick`, but
    // the thing driving it is now event-driven `touch()` calls, not the
    // 60 Hz frame loop.
    @Suppress("UNUSED_VARIABLE") val uiGen = uiGeneration

    // Home-during-play would otherwise leave the voice's thread writing to
    // an AudioTrack nobody can hear forever (no media session, no way for
    // the system to stop it) — ON_STOP is the app losing the foreground,
    // which is exactly when a tape deck should stop rolling. Deliberately
    // does NOT resume on ON_START/ON_RESUME: the user presses PLAY again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (model.playing) {
                    model.togglePlay()
                    // `playing` just flipped outside any button tap — the
                    // PLAY/STOP label needs the same nudge HitEnd gets
                    // below, or it reads "■ STOP" after the app resumes.
                    touch()
                }
                voice.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var commitIndex by remember(model) { mutableStateOf(0) }

    // The model advances outside Compose's snapshot system (it's plain
    // Kotlin, tested on its own) — `positionState` is the bridge into it.
    // A `withFrameNanos` clock converts elapsed wall time to elapsed audio
    // frames (carrying the fractional remainder so the conversion doesn't
    // drift) and calls `model.step()`, then republishes `model.position`
    // into this snapshot State every frame. Leaves (the waveform Canvas,
    // the reels' graphicsLayer) read it inside their draw/layer lambdas,
    // which subscribes only *that* phase — a position change repaints or
    // re-layers without recomposing this composable or its children.
    val positionState = remember(model) { mutableDoubleStateOf(model.position) }

    // A ~10 Hz snap of the live position, for the LCD text: recomposing a
    // Text 60×/sec is both unreadable and (via `String.format` in the
    // readouts) wasted work. Consumed inside ReadoutRow's own body, not
    // read here — reading it at this top scope would recompose this
    // entire composable, and everything under it, 10×/sec instead of 60.
    val readoutPos = remember(model) {
        derivedStateOf { (positionState.doubleValue / (model.sampleRate / 10.0)).toLong() }
    }

    LaunchedEffect(model, voice) {
        var lastNanos = withFrameNanos { it }
        var carryFrames = 0.0
        // Tracks the IDLE/at-rest transition so a GLIDE lock-on, a COAST
        // friction stop, or a PLAY→STOP spin-down each get exactly one
        // extra `touch()` the instant motion actually ends — POS is shown
        // to hundredths (`%05.2f`, finer than the ~10 Hz `readoutPos`
        // throttle), so without this the LCD can settle on a value that's
        // up to a decisecond stale. `positionState` (written unconditionally
        // below) already holds the exact resting position by then, so this
        // costs nothing beyond the one bump.
        var wasSettled = model.mode == TapeDeckModel.Mode.IDLE && model.speed == 0.0
        while (isActive) {
            withFrameNanos { now ->
                val dtNanos = (now - lastNanos).coerceIn(0, MAX_STEP_NANOS)
                lastNanos = now
                val exact = dtNanos.toDouble() * model.sampleRate / 1_000_000_000.0 + carryFrames
                val frames = exact.toInt()
                carryFrames = exact - frames
                if (frames > 0) {
                    val events = model.step(frames)
                    for (event in events) {
                        when (event) {
                            is TapeDeckModel.Event.SnappingToOnset ->
                                onToast(Copy.SNAPPED)
                            TapeDeckModel.Event.HitEnd -> voice.stop()
                            TapeDeckModel.Event.PencilDone -> onToast(Copy.PENCIL_DONE)
                        }
                    }
                    // HitEnd (at least) flips `model.playing` off outside
                    // any button tap — without this, "■ STOP" would keep
                    // showing after playback runs off the end of the tape.
                    if (events.isNotEmpty()) touch()
                    val settledNow = model.mode == TapeDeckModel.Mode.IDLE && model.speed == 0.0
                    if (settledNow && !wasSettled) touch()
                    wasSettled = settledNow
                }
                positionState.doubleValue = model.position
            }
        }
    }
    // The deferred read every leaf visual invokes from its own draw/layer
    // phase — see `positionState`'s comment above.
    val position: () -> Double = { positionState.doubleValue }

    fun stopVoice() {
        voice.stop()
    }

    fun onPlayStop() {
        model.togglePlay()
        touch()
        if (model.playing) voice.start(model.position.toInt()) else voice.stop()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CassetteRow(entry, model, position, onToast, ::stopVoice, ::touch)
        WaveformLcd(
            model,
            tapeData.onsets,
            tapeData.peaks,
            scheme,
            position,
            ::stopVoice,
            onSnapToast = { onToast(Copy.SNAPPED) },
            onTouch = ::touch,
            // Strong skipping (on by default, Kotlin 2.0.21 + Compose
            // compiler plugin, no stability override) skips a child whose
            // parameter INSTANCES are unchanged even when the parent
            // recomposed — `model`/`peaks`/`scheme`/`position` are all the
            // same references across a `uiGeneration`-only recompose of
            // TapeDeckContent. Passing the live Int value here (not a
            // State, a plain value that differs from the prior call) is
            // what defeats that skip and forces this composable's body —
            // and therefore its `Canvas` draw lambda — to re-run the
            // instant a non-position model field (the selection) changes.
            uiGen = uiGeneration,
            // Absorbs whatever room the fixed-height rows above and below
            // it don't need, rather than a hardcoded height that clips
            // COMMIT off-screen on a short viewport (landscape, split
            // screen) — WAVEFORM_MIN_H keeps it from collapsing to nothing.
            modifier = Modifier.weight(1f, fill = true).heightIn(min = WAVEFORM_MIN_H.dp),
        )
        // Same strong-skipping reasoning as WaveformLcd's `uiGen` above:
        // `model`/`readoutPos`/`onToast` are all instance-equal across a
        // `uiGeneration`-only recompose, so ReadoutRow would otherwise be
        // skipped and the selection-length ("LEN") text would go stale
        // after IN/OUT/COMMIT while stopped.
        ReadoutRow(model, readoutPos, onToast, uiGen = uiGeneration)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DeckButton("IN", Modifier.weight(1f), engaged = model.inFrame >= 0) { model.setIn(); touch() }
            DeckButton("OUT", Modifier.weight(1f), engaged = model.outFrame >= 0) { model.setOut(); touch() }
            DeckButton(model.zoomLabel, Modifier.weight(1f)) { model.cycleZoom(); touch() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WindButton("◄◄", Modifier.weight(1f), -1, model, ::stopVoice, ::touch)
            DeckButton(if (model.playing) "■ STOP" else "▶ PLAY", Modifier.weight(1f)) { onPlayStop() }
            WindButton("▶▶", Modifier.weight(1f), 1, model, ::stopVoice, ::touch)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DeckButton(
                "KEEP",
                Modifier
                    .weight(1f)
                    .height(Layout.PRIMARY_ACTION_H.dp),
                active = model.hasSelection,
            ) {
                val range = model.commitSelection()
                touch()
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
                // Read before commitSelection(), which clears it — and passed
                // on rather than left to be inferred from `range`, since an
                // IN/OUT the user dragged across the whole tape arrives in
                // App.kt looking exactly like no selection at all.
                val hadSelection = model.hasSelection
                val range = if (hadSelection) model.commitSelection() else null
                // togglePlay and commitSelection both change what the
                // PLAY/STOP label and the IN/OUT engaged state should read —
                // same reasoning as onPlayStop/the COMMIT button above.
                touch()
                onInstantKit(tapeData.sourceFile, range ?: (0 until tapeData.samples.size), hadSelection)
            }
        }
        // Wave ZZ, the phone reads: three more readings of the same tape.
        // DIG finds the break and sets IN and OUT to it, so INSTANT KIT is
        // the next tap; READ AS GROOVE hears the tape as a rhythm for the
        // open kit's pads; STEAL THE FEEL keeps only its timing and accent.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DeckButton("FIND BREAK ▸", Modifier.weight(1f), active = !digging) {
                if (digging) return@DeckButton
                if (model.playing) model.togglePlay()
                stopVoice()
                touch()
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
                            // IN/OUT just moved off the model, outside any
                            // DeckButton tap — same reasoning as every other
                            // direct `model` mutation in this file: the
                            // IN/OUT engaged state and LEN readout only see
                            // it once this composable recomposes.
                            touch()
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
                touch()
                onReadGroove(tapeData.sourceFile, range ?: (0 until tapeData.samples.size))
            }
            DeckButton("COPY GROOVE ▸", Modifier.weight(1.4f)) {
                if (model.playing) model.togglePlay()
                stopVoice()
                val range = if (model.hasSelection) model.commitSelection() else null
                touch()
                onStealFeel(tapeData.sourceFile, range ?: (0 until tapeData.samples.size))
            }
        }
    }
}

@Composable
private fun ReadoutRow(model: TapeDeckModel, readoutPos: State<Long>, onToast: (String) -> Unit, uiGen: Int) {
    val scheme = LocalScheme.current
    // Reading `readoutPos` here — not at TapeDeckContent's top scope —
    // scopes its ~10 Hz recomposition to just this Row rather than the
    // whole subtree above it.
    @Suppress("UNUSED_VARIABLE") val snappedTenth = readoutPos.value
    // `uiGen` is TapeDeckContent's `uiGeneration`, threaded in explicitly.
    // Strong skipping (on by default here — Kotlin 2.0.21 + the Compose
    // compiler plugin, no stability config override) compares unstable
    // parameters like `model` by instance, not by forcing a recompose —
    // `model`/`readoutPos`/`onToast` are the same references on a
    // `uiGeneration`-only recompose of TapeDeckContent, so without this,
    // this whole Row would be SKIPPED and "LEN --.--" would never refresh
    // after IN/OUT/COMMIT while the deck is stopped. A plain `Int` that
    // differs from its prior value can't be skipped past.
    @Suppress("UNUSED_VARIABLE") val genRead = uiGen
    // The odometer toggle changes `positionReadout`'s format immediately
    // on tap — it can't wait for the next throttled tick, so it gets its
    // own tiny local trigger rather than borrowing `uiGen` (which would
    // recompose more than this Row for a change that's local to it).
    var localGen by remember { mutableStateOf(0) }
    // A `val` initializer is always evaluated (unlike a bare expression
    // statement, which some compiler paths could fold away as dead), so
    // this is the unambiguous way to establish the read.
    @Suppress("UNUSED_VARIABLE") val localTick = localGen
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .weight(1.3f)
                .height(Layout.LCD_HEADER_MAX_H.dp)
                .lcdPanel(scheme)
                .tapeClick(label = null) {
                    model.toggleOdometer()
                    localGen++
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
    // Orthogonal to `active` — `active` only dims/brightens the label
    // (an enabled-ish axis); `engaged` is "this control is currently set,"
    // a separate latched/lit look (accent fill + inverted ink), wired only
    // for IN/OUT (`model.inFrame`/`outFrame >= 0`). Every other DeckButton
    // call leaves this at the default and renders exactly as before.
    engaged: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = LocalScheme.current
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            // `raisedBevel`'s own `fill` param swaps just the background
            // color, keeping the bevel border/shape identical either way —
            // this is what makes `engaged` a strict overlay on the normal
            // look rather than a different component.
            .raisedBevel(scheme, fill = if (engaged) scheme.accent.tape else null)
            .tapeClick(label = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            label,
            TapeType.pixel,
            when {
                // `scheme.lcd` — "dark in every scheme" per Schemes.kt — is
                // the same dark-on-accent ink the selection markers' flag
                // labels use below, so "lit" reads the same everywhere.
                engaged -> scheme.lcd.tape
                active -> scheme.ink.tape
                else -> scheme.ink2.tape
            },
        )
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
    onTouch: () -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            // A screen-reader double-tap has no hold duration, so this
            // gives the wind a real one: WIND_A11Y_NUDGE_MS of elapsed
            // time on a coroutine, the same shape the pencil-rewind
            // action above uses, rather than a synchronous start/stop
            // pair. That pair looked like "a brief nudge" but was not
            // one — `windStart` only sets a target speed and the tape is
            // moved by the step loop's per-frame `step()`, so stopping
            // in the same frame reset the target before any tick could
            // read it and the tape never moved. The action announced
            // success and did nothing.
            //
            // label (this button's own text, e.g. "◄◄") is the merged
            // accessible name — mergeDescendants is what supplies it;
            // without it this action node carries no name.
            .semantics(mergeDescendants = true) {
                onClick {
                    scope.launch {
                        onStop()
                        model.windStart(direction)
                        onTouch()
                        delay(WIND_A11Y_NUDGE_MS)
                        model.windStop(direction)
                        onTouch()
                    }
                    true
                }
            }
            .pointerInput(model, direction) {
                detectTapGestures(
                    onPress = {
                        onStop()
                        model.windStart(direction)
                        onTouch()
                        tryAwaitRelease()
                        model.windStop(direction)
                        onTouch()
                    },
                )
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
    }
}

/**
 * The cassette: kit name, two reels, and the pencil-rewind gag on the left
 * one. `entry` is null when the tape loaded from a snip or last-commit
 * source with no kit open (a shelf-armed capture) — the label falls back
 * to a bare "TAPE" rather than a kit name that doesn't exist yet.
 *
 * Deliberately takes no `uiGen`: everything this composable and [Reel]
 * draw is either `entry` (stable, unrelated to `model`) or driven through
 * the deferred `position` lambda inside a draw/layer lambda (`fraction`,
 * `rotation`) — no composition- or draw-scope code here reads a mutable
 * `model` field directly, so there's nothing for a strong-skipped call to
 * leave stale.
 */
@Composable
private fun CassetteRow(
    entry: KitShelf.Entry?,
    model: TapeDeckModel,
    position: () -> Double,
    onToast: (String) -> Unit,
    onScrubStart: () -> Unit,
    onTouch: () -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

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
            fraction = { 1f - windFraction(position(), model.lengthFrames) },
            rotation = { rotationForPosition(position(), model.sampleRate) },
            scheme = scheme,
            modifier = Modifier
                .size(Layout.MIN_HIT_TARGET.dp)
                // This reel had zero accessibility affordance at all
                // (audit finding 1's "no visible affordance either"
                // twin, finding 8) — no text child, no click action, a
                // 500ms hold as the *only* way to reach pencil rewind.
                // A semantics onLongClick can't hold for real, so it
                // runs the same start/toast/stop sequence for a fixed
                // PENCIL_A11Y_NUDGE_MS instead of however long a finger
                // stays down.
                .semantics {
                    contentDescription = "REWIND PENCIL"
                    onLongClick(label = "SPIN BACK BY EAR") {
                        scope.launch {
                            onScrubStart()
                            onTouch()
                            val started = model.pencilRewind()
                            onToast(if (started) Copy.PENCIL_STARTED else Copy.PENCIL_AT_TOP)
                            delay(PENCIL_A11Y_NUDGE_MS)
                            model.pencilOff()
                            onTouch()
                        }
                        true
                    }
                }
                .pointerInput(model) {
                    detectTapGestures(
                        onPress = {
                            val releasedEarly = withTimeoutOrNull(PENCIL_LONG_PRESS_MS) { tryAwaitRelease() }
                            if (releasedEarly == null) {
                                onScrubStart()
                                onTouch()
                                val started = model.pencilRewind()
                                onToast(if (started) Copy.PENCIL_STARTED else Copy.PENCIL_AT_TOP)
                                tryAwaitRelease()
                                model.pencilOff()
                                onTouch()
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
            fraction = { windFraction(position(), model.lengthFrames) },
            rotation = { rotationForPosition(position(), model.sampleRate) },
            scheme = scheme,
            modifier = Modifier.size(Layout.MIN_HIT_TARGET.dp),
        )
    }
}

/** How much of the tape (0..1) is wound onto a reel at [position]. */
private fun windFraction(position: Double, lengthFrames: Int): Float =
    (position / lengthFrames.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)

/**
 * Reel angle for [position]: one full turn every [Motion.REEL_SPIN_MS] of
 * tape motion. Under the unity-speed playback [TapeVoice] actually runs,
 * this is exactly the same visual rate the old wall-clock
 * `infiniteRepeatable` animation produced during ordinary PLAY — but
 * because it's driven by position instead of elapsed real time, the reels
 * now also turn (and freeze) correctly under drag, wind and coast, and
 * during pause, instead of only spinning while `model.playing` was true.
 */
private fun rotationForPosition(position: Double, sampleRate: Int): Float {
    val ms = position / sampleRate * 1000.0
    val cycleMs = Motion.REEL_SPIN_MS.toDouble()
    val phase = ms % cycleMs
    val nonNegativePhase = if (phase < 0) phase + cycleMs else phase
    return (nonNegativePhase / cycleMs * 360.0).toFloat()
}

/**
 * One reel: a wound-tape disc sized by [fraction], spinning by [rotation]
 * — both deferred reads. [fraction] is invoked inside the `Canvas` draw
 * lambda (draw phase: a change repaints only this reel), and [rotation] is
 * applied via `graphicsLayer` (layer phase: a change re-layers this reel's
 * draw output without re-running the draw lambda, let alone recomposing).
 * Neither ever triggers recomposition of this composable or its caller.
 */
@Composable
private fun Reel(fraction: () -> Float, rotation: () -> Float, scheme: Scheme, modifier: Modifier = Modifier) {
    Canvas(
        modifier.graphicsLayer {
            rotationZ = rotation()
        },
    ) {
        val strokeWidth = 2.dp.toPx()
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        val hubRadius = radius * 0.45f
        val woundRadius = radius * (0.5f + 0.5f * fraction().coerceIn(0f, 1f))
        drawCircle(color = scheme.grayDark.tape.copy(alpha = 0.6f), radius = woundRadius, center = c)
        drawCircle(color = scheme.ink2.tape, radius = hubRadius, center = c, style = Stroke(width = strokeWidth))
        val spoke = hubRadius * 0.9f
        drawLine(scheme.ink2.tape, Offset(c.x - spoke, c.y), Offset(c.x + spoke, c.y), strokeWidth = strokeWidth)
        drawLine(scheme.ink2.tape, Offset(c.x, c.y - spoke), Offset(c.x, c.y + spoke), strokeWidth = strokeWidth)
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
    position: () -> Double,
    onScrubStart: () -> Unit,
    onSnapToast: () -> Unit,
    onTouch: () -> Unit,
    // TapeDeckContent's `uiGeneration`, threaded in explicitly. The
    // selection rectangle below is read straight off `model` — a plain,
    // unobserved var — inside this composable's `Canvas` draw lambda, so
    // it only gets fresh values when this composable's body re-runs
    // (which re-invokes `Canvas` with a new draw lambda). Under strong
    // skipping (default here), `model`/`peaks`/`scheme`/`position` are all
    // instance-equal across a `uiGeneration`-only recompose of
    // TapeDeckContent, so without an explicit, differently-valued `Int`
    // parameter, this whole composable — and therefore the selection
    // rectangle — would be skipped and never redraw while the deck sits
    // stopped after IN/OUT/COMMIT.
    uiGen: Int,
    modifier: Modifier = Modifier,
) {
    // A scratch buffer for the draw phase's per-frame `peaks` query, so the
    // steady-state playback path (position moving every frame) doesn't
    // allocate a fresh boxed `List<Column>` — see `PeaksPyramid.columnsInto`.
    // Grown, never shrunk; `remember` alone can't do that (the holder's
    // array reference has to be reassignable), hence the tiny class.
    val columnBuffer = remember { ColumnBuffer() }
    // One shared, reused Paint for the IN/OUT flag labels — created once,
    // not per draw; only `.textSize`/`.color` (plain field assignments, no
    // allocation) are refreshed per draw pass to track the scheme.
    val markerLabelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .lcdPanel(scheme)
            // Canvas-drawn, invisible to the a11y tree by default (audit
            // finding 4). A live stateDescription is deliberately not
            // attempted here: this composable's own KDoc above explains
            // that `position` is read only inside draw/layer-phase
            // lambdas specifically so steady-state playback doesn't
            // recompose it every frame — reading `position()` here, at
            // composition scope, would either fight that discipline (if
            // wired to recompose) or hand TalkBack a value that's stale
            // the instant playback resumes (if not). A static label
            // meets finding 4's required floor without that tradeoff;
            // the existing tap-to-seek gesture below has no accessible
            // equivalent because a synthesized click has no X position
            // to seek to, the same gap velocityFromY has for PLAY's pads.
            .semantics { contentDescription = "TAPE POSITION" }
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
                            if (dragging) {
                                model.dragEnd()
                                onTouch()
                            }
                            break
                        }
                        if (!change.pressed) {
                            if (dragging) {
                                val snapped = model.dragEnd()
                                onTouch()
                                if (snapped != null) onSnapToast()
                            } else {
                                onScrubStart()
                                val frame = frameAtX(change.position.x, widthPx, model)
                                model.seekTo(model.snapPoint(frame))
                                onTouch()
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
                            onTouch()
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
            // The single deferred position read for this whole draw pass —
            // invoked here, inside the draw lambda, so it subscribes only
            // this Canvas's draw phase, not composition. Everything below
            // uses this local snapshot rather than re-reading `model.position`
            // (a plain, unobserved var that draw-phase invalidation can't
            // subscribe to at all).
            val pos = position()
            // Not a State read — `uiGen` is just the Int this draw lambda
            // was captured with, which is only ever a fresh one when this
            // composable's body actually re-ran (see the `uiGen` param's
            // KDoc: that's what a `uiGeneration` bump now forces, past
            // strong skipping). Reading it here documents that this draw
            // pass — including the selection rectangle below, which reads
            // `model.hasSelection`/`inFrame`/`outFrame` directly — is only
            // ever current as of the last `touch()`, not live-subscribed.
            @Suppress("UNUSED_VARIABLE") val gen = uiGen
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
            // IN/OUT markers: thicker than the onset ticks/center needle
            // (which stay `strokeWidth` in `scheme.warn` — accent already
            // distinguishes selection from those, so they're untouched)
            // and topped with a small flag so which edge is which reads at
            // a glance.
            val selectionStrokeWidth = 2.5.dp.toPx()
            val flagWidth = 14.dp.toPx()
            val flagHeight = 10.dp.toPx()
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
            val visibleStart = pos - centerX * framesPerPixel
            val visibleEnd = pos + (w - centerX) * framesPerPixel
            val clampedStart = visibleStart.coerceAtLeast(0.0)
            val clampedEnd = visibleEnd.coerceAtMost(model.lengthFrames.toDouble())

            // IN and OUT each draw the moment they're individually set —
            // `model.hasSelection` requires BOTH (`inFrame >= 0 &&
            // outFrame > inFrame`, TapeDeck.kt), so gating the markers on
            // it too would leave tapping IN alone invisible until OUT
            // followed (half of every selection gesture). Only the filled
            // band below stays gated on `hasSelection`; the boundary lines
            // + flags react to each frame independently. `xIn`/`xOut` are
            // computed unconditionally (cheap arithmetic, no allocation)
            // and only used where their `hasIn`/`hasOut`/`hasSelection`
            // guard passes.
            val hasIn = model.inFrame >= 0
            val hasOut = model.outFrame >= 0
            val xIn = (centerX + (model.inFrame - pos) / framesPerPixel).toFloat()
            val xOut = (centerX + (model.outFrame - pos) / framesPerPixel).toFloat()
            if (model.hasSelection) {
                drawRect(
                    color = scheme.accent.tape.copy(alpha = 0.40f),
                    topLeft = Offset(xIn, 0f),
                    size = Size((xOut - xIn).coerceAtLeast(0f), h),
                )
            }
            if (hasIn || hasOut) {
                // Field assignments only (no allocation) — refreshed per
                // draw so the flags track the live scheme.
                markerLabelPaint.textSize = flagHeight * 0.62f
                markerLabelPaint.color = (0xFF shl 24) or scheme.lcd
            }
            if (hasIn) {
                // Flag to the RIGHT of the IN line: it reads into the
                // selection rather than overhanging off the start of it.
                drawSelectionMarker(
                    x = xIn,
                    canvasWidth = w,
                    h = h,
                    flagOnRight = true,
                    label = "IN",
                    scheme = scheme,
                    labelPaint = markerLabelPaint,
                    strokeWidth = selectionStrokeWidth,
                    flagWidth = flagWidth,
                    flagHeight = flagHeight,
                )
            }
            if (hasOut) {
                // Flag to the LEFT of the OUT line — same reasoning,
                // mirrored.
                drawSelectionMarker(
                    x = xOut,
                    canvasWidth = w,
                    h = h,
                    flagOnRight = false,
                    label = "OUT",
                    scheme = scheme,
                    labelPaint = markerLabelPaint,
                    strokeWidth = selectionStrokeWidth,
                    flagWidth = flagWidth,
                    flagHeight = flagHeight,
                )
            }

            if (clampedEnd > clampedStart) {
                val xStart = (centerX + (clampedStart - pos) / framesPerPixel).toFloat()
                val columnCount = max(1, (((clampedEnd - clampedStart) / framesPerPixel) / barStep).toInt())
                val buf = columnBuffer.ensure(columnCount * 2)
                peaks.columnsInto(clampedStart.toInt(), clampedEnd.toInt(), columnCount, buf)
                val halfHeight = h / 2f
                for (i in 0 until columnCount) {
                    val x = xStart + i * barStep
                    val top = (centerY - buf[i * 2 + 1] * halfHeight).coerceIn(0f, h)
                    val bottom = (centerY - buf[i * 2] * halfHeight).coerceIn(0f, h)
                    drawRect(
                        color = scheme.lcdInk.tape,
                        topLeft = Offset(x, top),
                        size = Size(barWidth, (bottom - top).coerceAtLeast(1f)),
                    )
                }
            }

            for (onset in onsets) {
                val x = (centerX + (onset - pos) / framesPerPixel).toFloat()
                if (x < -4f || x > w + 4f) continue
                val tickBottom = h - tickInset
                drawLine(scheme.warn.tape, Offset(x, tickBottom - tickLength), Offset(x, tickBottom), strokeWidth = strokeWidth)
            }

            drawLine(scheme.warn.tape, Offset(centerX, 0f), Offset(centerX, h), strokeWidth = strokeWidth)
        }
    }
}

/**
 * One IN/OUT boundary: a pronounced accent line spanning the full canvas
 * height, plus a small flag at the top labelled [label] so which edge is
 * which reads at a glance. [flagOnRight] puts the flag to the right of
 * the line (IN) or the left (OUT) so it always points into the selection
 * rather than overhanging off it. [labelPaint]'s size/color are set by
 * the caller (once per draw pass, not per marker) — this only positions
 * and draws it. Off-screen guarded like the onset-tick loop, widened by
 * the flag's own width so a flag just past either edge doesn't smear.
 */
private fun DrawScope.drawSelectionMarker(
    x: Float,
    canvasWidth: Float,
    h: Float,
    flagOnRight: Boolean,
    label: String,
    scheme: Scheme,
    labelPaint: android.graphics.Paint,
    strokeWidth: Float,
    flagWidth: Float,
    flagHeight: Float,
) {
    if (x < -flagWidth || x > canvasWidth + flagWidth) return
    drawLine(scheme.accent.tape, Offset(x, 0f), Offset(x, h), strokeWidth = strokeWidth)
    val flagLeft = if (flagOnRight) x else x - flagWidth
    drawRect(color = scheme.accent.tape, topLeft = Offset(flagLeft, 0f), size = Size(flagWidth, flagHeight))
    // Standard Paint vertical-centering formula: the midpoint between
    // ascent (negative) and descent, offset from the flag's own center.
    val baseline = flagHeight / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
    drawContext.canvas.nativeCanvas.drawText(label, flagLeft + flagWidth / 2f, baseline, labelPaint)
}

/**
 * A growable scratch [FloatArray] for [WaveformLcd]'s per-frame
 * [PeaksPyramid.columnsInto] call — reused across draws instead of letting
 * `remember` hand back an immutable reference, since the required size
 * (`columnCount * 2`) changes with the canvas width and zoom level.
 */
private class ColumnBuffer {
    private var array = FloatArray(256)
    fun ensure(size: Int): FloatArray {
        if (array.size < size) array = FloatArray(size)
        return array
    }
}

/** Screen-x to tape frame, inverse of the waveform's own draw mapping. */
private fun frameAtX(x: Float, widthPx: Float, model: TapeDeckModel): Int {
    val center = widthPx / 2f
    val framesPerPixel = model.sampleRate.toDouble() / model.pxPerSec.toDouble()
    val frame = model.position + (x - center) * framesPerPixel
    return frame.toInt().coerceIn(0, (model.lengthFrames - 1).coerceAtLeast(0))
}
