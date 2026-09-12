package com.snipsnap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.MediaDecode
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.OutsideSession
import com.snipsnap.app.ShareInbox
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.BinRedGlow
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Outside
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.PadShape
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.kit.PadFromAnything
import com.snipsnap.shell.ChopReviewModel
import com.snipsnap.shell.Copy
import com.snipsnap.shell.DustPrints
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Mutate
import com.snipsnap.shell.MutateSheet
import com.snipsnap.shell.OutsideSheet
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadMaker
import com.snipsnap.shell.PadSheet
import com.snipsnap.shell.PadSheetBoxes
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.RecipeReplay
import com.snipsnap.shell.Retrim
import com.snipsnap.shell.Rooms
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.ShapeAudition
import com.snipsnap.shell.SnipStore
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How long a LEVEL/PAN/TUNE/ONE-SHOT/CHOKE edit waits, quiet, before it's
 * actually written to `kit.json` — see `editPadMetadata`'s KDoc for why
 * this exists at all (every `KitBuilderModel.save()` archives a take).
 */
private const val METADATA_SAVE_DEBOUNCE_MS = 1000L

/**
 * PAD SHEET: the long-press pad inspector, wireframe 1d ("full-screen
 * inspector — the pad as its own screen"). Everything it shows already
 * lives in `:shell`/`:kit`'s tested engines; this file is the Compose
 * surface plus the write-and-refresh glue.
 *
 * [entry] is the kit as `App` currently knows it; this screen opens its
 * *own* [KitBuilderModel] on [entry]'s folder (a kit is its folder, so
 * that's always current) and reports every successful edit back up via
 * [onKitUpdated] so `App`'s copy — and the shelf list — stay in step.
 * `entry.dir` never changes while the sheet is open (only its `kit`
 * does), so the model opens once and edits mutate that one instance.
 *
 * [appScope] is `App()`'s own `rememberCoroutineScope()` — the one
 * `fresh()` launches into — which outlives this composable's own scope
 * across every kind of exit, not just [onBack]. The debounced metadata
 * save (see `editPadMetadata`) is scoped to *this* composable and would
 * be cancelled mid-flight by any exit that isn't [requestBack]'s own
 * synchronous flush (a MenuRow tab switch, RE-TRIM); the teardown
 * `DisposableEffect` below hands a still-pending save to [appScope]
 * instead of letting it die with the screen.
 */
@Composable
fun PadSheetScreen(
    entry: KitShelf.Entry,
    slot: Int,
    onSlotChange: (Int) -> Unit,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onNavigateTape: () -> Unit,
    onGrainField: (Int) -> Unit,
    /** SPLICE ▸: opens TAPE SPLICE scoped to this pad - unlike [onNavigateTape], which is RE-TRIM ▸: App resolves the pad's own tape (`Retrim.of`) and opens TAPE on its cut, or toasts why it can't. */
    onSplice: (Int) -> Unit,
    /** STACK ▸: opens STACK THE TAKES scoped to this pad - its real prior takes as soft velocity zones, over the same history SPLICE reads. */
    onStack: (Int) -> Unit,
    /** DO IT AGAIN: what COPY LAST TREATMENT last lifted, held by the caller so it survives a kit switch - PASTE reads it. */
    clipboard: RecipeReplay.Clip? = null,
    /** DO IT AGAIN: COPY LAST TREATMENT hands the clip up here; the caller keeps it. */
    onRecipeCopied: (RecipeReplay.Clip) -> Unit = {},
    onKitUpdated: (com.snipsnap.kit.Kit) -> Unit,
    appScope: CoroutineScope,
    /** Pad Sheet v2: which workshop box is open (a `PadSheetBoxes.Box` name), remembered per kit by the caller. */
    openBox: String? = null,
    onOpenBox: (String?) -> Unit = {},
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The SNIPS shelf: where a pad's tape, and the dust print cached beside it, live.
    val snipsDir = File(context.filesDir, SnipStore.DIR)

    var model by remember(entry.dir) { mutableStateOf<KitBuilderModel?>(null) }
    var loadFailed by remember(entry.dir) { mutableStateOf(false) }
    LaunchedEffect(entry.dir) {
        loadFailed = false
        // Locked, not just opened: KitScreen's pad-grid long-press has no
        // busy gate, so this mount can race a SET KEY/EVIL TWINS/IN KEY
        // write already in flight on `entry.dir` from KIT. Opening under
        // the same KitWrites.mutex.withLock every writer already uses means
        // this snapshot is always taken either fully before or fully after
        // that write, never mid-write — see Law 5 in ConventionTest.kt.
        val opened = withContext(Dispatchers.IO) {
            KitWrites.mutex.withLock { runCatching { KitBuilderModel.open(entry.dir) }.getOrNull() }
        }
        if (opened == null) loadFailed = true else model = opened
    }

    // KitBuilderModel is a plain mutable class, not Compose state (see
    // ChopScreen's own `revision` counter for the same reason) — every
    // write below bumps this so the pad read below picks up the change.
    var revision by remember(model) { mutableIntStateOf(0) }
    val revisionTick = revision

    val kit = model?.kit
    val pad = kit?.pad(slot)

    if (pad == null || model == null) {
        // Still opening the model, the open failed, or (mid-EJECT) this
        // slot just emptied — the header's own back arrow is the one piece
        // of chrome that must work regardless, so it renders alone. A load
        // failure reuses EMPTY_SHELF rather than a one-off sentence, the
        // same "nothing to show here" line ChopScreen's own EmptyChop
        // reuses for its unreadable-source case — zero copy literals.
        // Still opening/failed/mid-EJECT — nothing dirty to flush yet, so
        // Back matches the header's own bare `onBack()` here, not the full
        // `requestBack()` below (which needs a live `model` to check).
        BackHandler { onBack() }
        Box(Modifier.fillMaxSize().lcdPanel(scheme).padding(14.dp)) {
            HeaderChip("◄ KIT", scheme, Modifier.align(Alignment.TopStart).width(64.dp)) { onBack() }
            if (loadFailed) {
                TapeText(Copy.EMPTY_SHELF, TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.align(Alignment.Center), maxLines = 3)
            }
        }
        return
    }
    val builtModel = model!!

    var busy by remember(model) { mutableStateOf(false) }
    var snip by remember(model) { mutableStateOf<Snip?>(null) }
    var binDaysLeft by remember(model) { mutableStateOf<Int?>(null) }

    var voice by remember(model) { mutableStateOf<TapeVoice?>(null) }
    DisposableEffect(model) { onDispose { voice?.release() } }
    // Set by `applyTreatment`/`applySmear` when a treatment lands; read
    // (and cleared) by the effect under `audition` below, once the fresh
    // model's audio is decoded. Keyed on the slot, not the model, because
    // its whole job is to outlive the model swap those writes make.
    var auditionOnRefresh by remember(slot) { mutableStateOf(false) }
    // Backgrounding mid-audition must stop the voice, not wait for this
    // composable to next leave composition (see ChopScreen's own fix).
    // Keyed on `model` too: `voice` is `remember(model)`-keyed, so an
    // observer from before a treatment's model swap would close over the
    // old state and leave the fresh voice playing in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voice?.release()
                voice = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** Re-decode this pad's current audio and its bin status — the one door every write refreshes through. */
    suspend fun refreshPadAudio(m: KitBuilderModel) {
        val p = m.kit.pad(slot)
        if (p == null) {
            snip = null
            binDaysLeft = null
            auditionOnRefresh = false
            return
        }
        val (loadedSnip, daysLeft) = withContext(Dispatchers.IO) {
            // p.sampleFile is a kit pad sample, produced only by KitBuilderModel.assign
            // from an already-bounded Snip — readCapped's 600s ceiling is defense in
            // depth, not expected to ever bind.
            val s = runCatching { Cleanup.toMono(WavReader.readCapped(File(entry.dir, p.sampleFile), TAPE_LOAD_MAX_SEC).snip) }.getOrNull()
            val binned = m.binContents()
                .filter { it.originalName == p.sampleFile }
                .maxByOrNull { it.binnedAtMillis }
            val days = binned?.let {
                val daysSince = (System.currentTimeMillis() - it.binnedAtMillis) / 86_400_000.0
                (KitBuilderModel.BIN_KEEP_DAYS - daysSince).let { left -> if (left <= 0) 0 else left.roundToInt() }
            }
            s to days
        }
        snip = loadedSnip
        binDaysLeft = daysLeft
        // A refresh that couldn't decode the file consumes the play
        // request too: otherwise it stays armed and the next unrelated
        // refresh that does decode (an undo, a pad swap) plays as if a
        // treatment had just landed.
        if (loadedSnip == null) auditionOnRefresh = false
    }

    LaunchedEffect(model, slot) { refreshPadAudio(builtModel) }

    /**
     * HIT. [shape] is the pad whose SHAPE the audition honours — the WAV
     * on disk is pristine (the hardware renders attack/decay/cutoff/res
     * from metadata), so an unshaped pad plays its bytes and a shaped one
     * plays `ShapeAudition`'s approximation, the same one `KitPreview`
     * renders; a preview must not claim the card will sound like the file.
     */
    fun audition(target: Snip, level: Float, shape: KitPad? = null) {
        // Release synchronously at the swap site — a composition-scoped
        // coroutine can't guarantee the previous voice actually stopped
        // before this one starts (ChopScreen's `audition()` comment).
        voice?.release()
        val rendered = shape?.let { ShapeAudition.render(target, it) } ?: target
        val gained = if (level == 1f) rendered.samples else FloatArray(rendered.samples.size) { rendered.samples[it] * level }
        val v = TapeVoice(gained, rendered.sampleRate)
        voice = v
        v.start(0)
    }

    // A treatment that just landed plays itself. `applyTreatment` and
    // `applySmear` swap [model] for a fresh instance, and `voice` is
    // `remember(model)`-keyed, so a play from inside their coroutine
    // would start a voice in a slot the new model never sees (and the old
    // model's `DisposableEffect` would then release the wrong one —
    // `applySmear`'s KDoc). This effect runs after the swap, once
    // `refreshPadAudio` has decoded the new file into `snip`, so the
    // voice it starts is the one HIT would: the treated sound, through
    // the pad's SHAPE, the moment the toast says it landed.
    LaunchedEffect(snip) {
        val s = snip
        if (auditionOnRefresh && s != null) {
            auditionOnRefresh = false
            // The decode can finish after ON_STOP with the sheet still
            // mounted; the observer above only releases a voice that
            // already exists, so don't start one from the background.
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                builtModel.kit.pad(slot)?.let { audition(s, it.level, it) }
            }
        }
    }

    /**
     * ◀ BEFORE: the pad as it sounded before its treatment — the newest
     * bin entry for its file, the same take `unEraPad` would restore —
     * through the same SHAPE and level as HIT, so the only difference
     * between the two chips is the treatment. The chip only shows while
     * a treatment is on and that take is still in the bin; a bin emptied
     * between the frame and the tap says so rather than playing nothing.
     */
    fun playBefore(p: KitPad) {
        if (busy) return
        // Claimed like every other sheet action, so a second tap or a
        // treatment can't run alongside the read. Picking the take is
        // under the writers' lock (`moveToBin` copies and deletes bin
        // files under it, so a half-copied take can't be chosen); the
        // decode is outside it, as `KitWrites` keeps every read-only DSP
        // pass — a purge racing the decode reads as gone, which is true.
        busy = true
        scope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    val take = KitWrites.mutex.withLock {
                        builtModel.binContents()
                            .filter { it.originalName == p.sampleFile }
                            .maxByOrNull { it.binnedAtMillis }
                            ?.file
                    }
                    take?.let { runCatching { Cleanup.toMono(WavReader.readCapped(it, TAPE_LOAD_MAX_SEC).snip) }.getOrNull() }
                }
                // The model swapped while the file was read: `voice` now
                // belongs to the new one, so don't start a voice in the old slot.
                if (model !== builtModel) return@launch
                when {
                    before == null -> onToast(Copy.BIN_ITEM_GONE)
                    // Backgrounded during the read: the same rule as the landing play.
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> audition(before, p.level, p)
                }
            } finally {
                busy = false
            }
        }
    }

    fun failure(action: String, e: Exception) {
        Log.e("PadSheetScreen", "$action: failed", e)
        onToast(Copy.actionFailed(action))
    }

    // `save()` is deliberately NOT called per stepper nudge: see
    // `editPadMetadata`'s KDoc just below for why, and this counter for
    // how the actual disk write gets debounced instead.
    var pendingMetadataSave by remember(model) { mutableIntStateOf(0) }

    // Every pad slot with an edit riding [pendingMetadataSave], not just
    // the most recent one — a pad-to-pad visit (LEVEL on pad 3, then PAN
    // on pad 5, all inside one debounce window) leaves more than one slot
    // dirty at once. Read via `State` (not the [slot] parameter directly)
    // by the teardown flush below: `DisposableEffect(model)` only
    // re-installs its `onDispose` when [model] itself changes, not on
    // pad-to-pad navigation (`onSlotChange` recomposes this same
    // composable with a new [slot] but the same [model]) — so a plain
    // `slot` read inside that `onDispose` would close over whatever pad
    // was open the *first* time this effect was installed, not the pads
    // the pending edits actually belong to. This `MutableState`, by
    // contrast, is read live at invocation time no matter which
    // composition's closure ends up running it.
    var pendingMetadataSlots by remember(model) { mutableStateOf<Set<Int>>(emptySet()) }

    /**
     * Metadata edits — LEVEL/PAN/TUNE/ONE-SHOT/CHOKE/SHAPE — never touch a WAV,
     * so they mutate [KitBuilderModel.kit] in memory only, right here,
     * synchronously. `save()` is deliberately NOT called per nudge:
     * `KitBuilderModel.save()` unconditionally archives a take whenever
     * the model is dirty, capped at 32 — one save per stepper tick would
     * rotate the kit's real rollback points out of history inside a
     * single pad's worth of dragging. [pendingMetadataSave] debounces
     * the actual disk write instead.
     */
    fun editPadMetadata(mutate: (KitBuilderModel) -> Unit) {
        if (busy) return
        val m = model ?: return
        mutate(m)
        revision++
        pendingMetadataSlots = pendingMetadataSlots + slot
        pendingMetadataSave++
    }

    LaunchedEffect(model, pendingMetadataSave) {
        if (pendingMetadataSave == 0) return@LaunchedEffect
        delay(METADATA_SAVE_DEBOUNCE_MS)
        // An audio-rewriting op is already mid-flight (or about to save) —
        // its own save() will flush this pending edit too, since `dirty`
        // covers everything outstanding, not just what triggered it.
        // Skipping here, rather than racing it for the busy flag, is what
        // keeps two coroutines from mutating `KitBuilderModel.kit` (a plain
        // var) and archiving two takes at once.
        if (busy) return@LaunchedEffect
        val m = model ?: return@LaunchedEffect
        // `pendingMetadataSlots`, not `m.dirty` — see withFreshKit's KDoc:
        // this flush no longer saves `m` itself, so `m.dirty` would only
        // ever get reset by an unrelated unconverted write elsewhere in
        // this file, making it an unreliable signal here. The slot set is
        // the true "is there anything this flush owns" answer.
        if (pendingMetadataSlots.isEmpty()) return@LaunchedEffect
        val kitDir = m.kitDir
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        busy = true
        try {
            val (fresh, saved) = withFreshKit(kitDir) { f -> reapplyPendingMetadataFields(f, stalePads) }
            if (saved) onKitUpdated(fresh.kit)
            pendingMetadataSlots = emptySet()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failure("SAVE", e)
        } finally {
            busy = false
        }
    }

    /**
     * Audio-rewriting ops — GHOSTS/EJECT/UNDO — always earn a real
     * save+take: the WAV already changed on disk. Runs [mutate] against a
     * FRESH model opened under the lock (see [withFreshKit]'s KDoc), never
     * the long-lived [model] — so a concurrent SET KEY/EVIL TWINS/IN KEY
     * from KIT is never clobbered by this screen's stale mount snapshot.
     * [model] is then swapped to that fresh instance so this screen's own
     * next read/write starts from what's actually on disk, not from
     * whatever [entry.dir] looked like at mount.
     *
     * Because [model] is swapped, [mutate] is refused — not applied to the
     * long-lived model as a fallback, which would double-apply a
     * structural change like `clear`/`addGhostLayers` (real file deletes
     * and writes, not a value copy) — when [slot]'s sample has changed
     * identity since this action was requested: another screen (GRAB, SEND
     * TO PAD, EVIL TWINS' reroll) may have ejected or reassigned it while
     * this screen was open. Comparing `sampleFile`, the same identity the
     * teardown flush already checks, not just null-ness, is the point:
     * a reassigned slot is non-null and would otherwise let this action
     * land on somebody else's sound.
     */
    fun commitPadEditNow(action: String, onSuccess: (() -> Unit)? = null, mutate: (KitBuilderModel) -> Unit) {
        if (busy) return
        val m = model ?: return
        val kitDir = m.kitDir
        val staleSampleFile = m.kit.pad(slot)?.sampleFile
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        scope.launch {
            busy = true
            try {
                var applied = false
                val (fresh, _) = withFreshKit(kitDir) { f ->
                    reapplyPendingMetadataFields(f, stalePads)
                    if (f.kit.pad(slot)?.sampleFile == staleSampleFile) {
                        mutate(f)
                        applied = true
                    }
                }
                model = fresh
                pendingMetadataSlots = emptySet()
                onKitUpdated(fresh.kit)
                if (applied) onSuccess?.invoke() else onToast(Copy.BIN_ITEM_GONE)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure(action, e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * SMEAR: not an era, so it doesn't route through `eraPad` — it's
     * `com.snipsnap.audio.Smear.process` through the generic
     * [KitBuilderModel.replaceAudio] door, exactly like `Mutate.morph`'s
     * recipe idiom. `replaceAudio` always reads the *current on-disk*
     * audio (same as `eraPad`), so re-applying onto an already-smeared pad
     * (moving AMT again) would stack the stretch onto the already-stretched
     * body rather than replacing it — restore first when this pad's own
     * SMEAR recipe is riding it, same shape as the era branch below.
     * Unlike eras, `replaceAudio` refuses velocity-layered pads outright
     * (a transform tuned on the loud zone would lie on the soft ones), so
     * that's checked up front, *before* any restore runs — un-doing a
     * prior SMEAR and then hitting the refusal on the way back in would
     * leave the main sample restored, the (untouched, still-smeared)
     * ghost layers stale, and the recipe cleared: the exact "untreated
     * underside" skew the era branch's own `check()` exists to prevent,
     * just via a different file shape (one file vs. many).
     *
     * `Smear.process` folds to mono internally and always returns a mono
     * [Snip] (Pghi/HPSS's contract, like `Retime`) — unlike `Eras.apply`,
     * which preserves `snip.channels` throughout. A stereo pad handed to
     * `Smear.process` untouched would silently lose its width, so a
     * stereo source gets its mono result duplicated back across both
     * channels, the same convention `Mutate.morph` uses for its own
     * mono-PGHI result.
     *
     * amount 0 is a no-op — `Smear.process` returns the input unchanged,
     * so the rewrite is skipped entirely rather than binning a take that's
     * byte-for-byte the same, matching `eraPad`'s own `amount <= 0f`
     * early-return. `save()` still runs unconditionally afterward, same
     * as the era branch: a segment tap or AMT commit is always a real
     * user action worth a rollback point, treated or not.
     *
     * Runs against a FRESH model, same as [commitPadEditNow] and for the
     * same reason (see its KDoc) — [p] is a snapshot from whenever [m] was
     * opened, so `p.velocityLayers`/`p.recipe`/[hadPriorSmear] are all
     * re-read off the *fresh* pad instead, and the whole action is refused
     * (not silently applied to the wrong sound) if [slot]'s `sampleFile`
     * has changed since [p] was captured. [model] is swapped to the fresh
     * instance on completion, which is why the treated result is not
     * auditioned from *this* coroutine: `voice` is `remember(model)`-keyed,
     * so writing to it after the swap would target an already-orphaned
     * state slot, and the *next* [DisposableEffect] for the old model
     * would then release a voice the new one never knew about. The play
     * rides [auditionOnRefresh] instead — `LaunchedEffect(model, slot)`
     * decodes the new file, and the effect under `audition` plays it.
     */
    fun applySmear(m: KitBuilderModel, p: KitPad, amount: Float, padName: String) {
        val kitDir = m.kitDir
        val staleSampleFile = p.sampleFile
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        scope.launch {
            busy = true
            try {
                var applied = false
                var stacked = false
                var noop = false
                val (fresh, _) = withFreshKit(kitDir) { f ->
                    reapplyPendingMetadataFields(f, stalePads)
                    val freshPad = f.kit.pad(slot)
                    if (freshPad != null && freshPad.sampleFile == staleSampleFile) {
                        check(freshPad.velocityLayers.isEmpty()) {
                            "pad $slot is velocity-layered - clear GHOSTS before smearing"
                        }
                        // AMT 0 on a pad that isn't smeared: `smearPad`
                        // touches nothing (its own KDoc — a slider must not
                        // take an era off on the way past zero), so this is
                        // not a landing: no toast claiming one, no audition.
                        if (amount <= 0f && PadSheet.readSmear(freshPad.recipe) == null) {
                            noop = true
                            return@withFreshKit
                        }
                        // `smearPad` restores first when it can; when the
                        // original is not in the bin it stacks (amount 0 is
                        // a no-op, never a stack), and the toast says so.
                        stacked = amount > 0f &&
                            PadSheet.unTreatState(freshPad, f.binContents().map { it.originalName }.toSet()) == PadSheet.UnTreat.NOT_BINNED
                        // The rewrite itself lives in the model now
                        // (`smearPad`: restore-first, then replaceAudio,
                        // stereo kept stereo) so DO IT AGAIN can replay it.
                        f.smearPad(slot, amount)
                        applied = true
                    }
                }
                if (applied) auditionOnRefresh = true
                model = fresh
                pendingMetadataSlots = emptySet()
                onKitUpdated(fresh.kit)
                if (applied) {
                    val label = PadSheet.displayLabel(PadSheet.SMEAR)
                    onToast(if (stacked) Copy.treatedStacked(label, padName) else Copy.treated(label, padName))
                } else if (noop) {
                    onToast(Copy.SMEAR_ZERO)
                } else {
                    onToast(Copy.BIN_ITEM_GONE)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Same in-voice-copy split as the era branch: the
                // ghosts-before-smearing refusal above is expected user
                // copy, not a diagnostic.
                if (e is IllegalStateException) onToast(Copy.RETREAT_REFUSED) else failure("TREATMENT", e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * DUST: the tape's own hiss and room under this pad (`docs/DUST.md`) —
     * `KitBuilderModel.dustPad`, the same door shape as [applySmear] with
     * one step in front: which tape, and its print. The tape is the pad's
     * own, else the kit's (`DustPrints.tapeFor`); none at all is refused
     * in the app's words, a tape gone from the shelf says so, and a tape
     * with nothing between its hits says that. The print is made, or
     * read off the shelf's cache, on IO before the kit is opened, so the
     * write under the lock is only the convolution.
     */
    fun applyDust(m: KitBuilderModel, p: KitPad, amount: Float, padName: String, from: String? = null) {
        // Which tape: the one asked for (DUST FROM ▸); else the one the pad
        // already dusts from, so moving AMT on a borrowed dust keeps the
        // borrowed tape — and when that tape has left the shelf, says so
        // by name below rather than quietly dusting from another; else
        // the pad's own or the kit's.
        val riding = PadSheet.readDust(p.recipe)?.tape?.takeIf { DustPrints.isBare(it) }
        val tape = from ?: riding ?: DustPrints.tapeFor(m.kit, p)
        if (tape == null) {
            onToast(Copy.DUST_NO_TAPE)
            return
        }
        val kitDir = m.kitDir
        val staleSampleFile = p.sampleFile
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        scope.launch {
            busy = true
            try {
                val tapeFile = File(snipsDir, tape)
                // AMT 0 takes dust off and needs no print; anything above needs the tape's.
                val print = if (amount > 0f) withContext(Dispatchers.IO) { DustPrints.forTape(tapeFile) } else null
                if (amount > 0f && print == null) {
                    onToast(if (tapeFile.isFile) Copy.DUST_NO_GHOSTS else Copy.dustTapeGone(tape))
                    return@launch
                }
                var applied = false
                var stacked = false
                var noop = false
                val (fresh, _) = withFreshKit(kitDir) { f ->
                    reapplyPendingMetadataFields(f, stalePads)
                    val freshPad = f.kit.pad(slot)
                    // The tape was read off the pad before the lock; a DUST
                    // rewrite keeps the sample file's name and changes only
                    // the recipe, so the sample-file guard alone would let a
                    // print made for one tape land under a recipe that now
                    // names another. The pad has to still ride the same tape.
                    val ridingNow = freshPad?.let { PadSheet.readDust(it.recipe)?.tape?.takeIf { t -> DustPrints.isBare(t) } }
                    if (freshPad != null && freshPad.sampleFile == staleSampleFile && (from != null || ridingNow == riding)) {
                        check(freshPad.velocityLayers.isEmpty()) {
                            "pad $slot is velocity-layered - clear GHOSTS before dusting"
                        }
                        // AMT 0 on a pad that isn't dusted: `dustPad` touches
                        // nothing, so this is not a landing.
                        if (amount <= 0f && PadSheet.readDust(freshPad.recipe) == null) {
                            noop = true
                            return@withFreshKit
                        }
                        stacked = amount > 0f &&
                            PadSheet.unTreatState(freshPad, f.binContents().map { it.originalName }.toSet()) == PadSheet.UnTreat.NOT_BINNED
                        f.dustPad(slot, amount, tape, print)
                        applied = true
                    }
                }
                if (applied) auditionOnRefresh = true
                model = fresh
                pendingMetadataSlots = emptySet()
                onKitUpdated(fresh.kit)
                if (applied) {
                    val label = if (from != null) Copy.dustFromLabel(SnipStore.displayName(tapeFile)) else PadSheet.displayLabel(PadSheet.DUST)
                    onToast(if (stacked) Copy.treatedStacked(label, padName) else Copy.treated(label, padName))
                } else if (noop) {
                    onToast(Copy.DUST_ZERO)
                } else {
                    onToast(Copy.BIN_ITEM_GONE)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is IllegalStateException) onToast(Copy.RETREAT_REFUSED) else failure("TREATMENT", e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * TREATMENT: picking a segment on either row, or moving AMT (SMEAR is
     * handled separately — see [applySmear] above). Both whole-pad doors
     * (`eraPad`, `characterPad`, `keyedPad`) always read the *current
     * on-disk* audio, rewrite it, and bin whatever was there — so
     * re-applying onto an already-treated pad (moving AMT, or switching
     * segments) would stack onto the treated audio rather than replacing
     * it. When the pad already carries a card recipe *and the bin holds
     * its main sample*, undo back to it first — but only when the bin can
     * restore *every* file the pad currently references (main sample and
     * any GHOSTS velocity layers). A layer built by `addGhostLayers`
     * *after* a treatment was derived from the already-treated main sample
     * and never earned its own bin entry, so a partial undo there would
     * leave the main sample and its layers treated by a different number
     * of passes — the exact "untreated underside" `PadSheet.kt`'s KDoc
     * says routing through the whole-pad doors exists to avoid. Refusing
     * honestly in that case beats a silent skew.
     *
     * A recipe with nothing in the bin behind it (a bank-B twin, a CLI
     * `treat`, a bin since emptied) is a sound the sheet can name but not
     * undo: the new treatment stacks on it, the way `treat` always has.
     *
     * Runs against a FRESH model via [withFreshKit] — same shape and same
     * reason as [commitPadEditNow]/[applySmear] (Law 6: a `.save()` must
     * share its lock span with its own `open(`, not with the screen-mount
     * `m`). [hadPriorTreatment]'s recipe-and-bin check moves inside the
     * lock too, re-read off the fresh pad rather than the stale one — it's
     * a cheap read (a JSON field and a bin listing), not the kind of
     * expensive/interactive work that has to stay outside. The whole
     * era/character/keyed rewrite is refused (not applied to the wrong
     * sound) if [slot]'s `sampleFile` has changed since [p] was captured.
     * [model] is swapped to the fresh instance on completion, which is why
     * this — like [applySmear] — does NOT audition the result from this
     * coroutine: `voice` is `remember(model)`-keyed, and writing to it
     * after the swap would target a state slot the next
     * `DisposableEffect(model)` teardown is about to release out from under
     * it. The play rides [auditionOnRefresh] instead — set here when the
     * treatment applied, consumed by the effect under `audition` once
     * `LaunchedEffect(model, slot)` has decoded the fresh file — so the
     * treated pad is heard the moment the toast says it landed.
     */
    // Set on a treatment tap, shown only while `busy`, cleared whenever any
    // operation on this sheet finishes. One effect rather than a clear in
    // each coroutine's `finally`: `busy` is shared by fourteen call sites in
    // this file, and gating the display on it makes a stale value invisible
    // rather than wrong.
    var applyingSegment by remember(slot) { mutableStateOf<String?>(null) }
    LaunchedEffect(busy) {
        if (!busy) applyingSegment = null
    }

    fun applyTreatment(segment: String, amount: Float) {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        val padName = p.displayName
        if (segment == PadSheet.SMEAR) {
            applySmear(m, p, amount, padName)
            return
        }
        if (segment == PadSheet.DUST) {
            applyDust(m, p, amount, padName)
            return
        }
        val treatment = PadSheet.treatmentFor(segment) ?: return
        val staleSampleFile = p.sampleFile
        val kitDir = m.kitDir
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        val keyedSeed = kotlin.random.Random.nextLong(0L, 1_000_000L)
        scope.launch {
            busy = true
            try {
                var applied = false
                var stacked = false
                var keyLabel = ""
                val (fresh, _) = withFreshKit(kitDir) { f ->
                    reapplyPendingMetadataFields(f, stalePads)
                    val freshPad = f.kit.pad(slot)
                    if (freshPad != null && freshPad.sampleFile == staleSampleFile) {
                        // One question, both recipe shapes (September UAT,
                        // finding 20). This used to ask `PadSheet.read` alone,
                        // which cannot see SMEAR by design - so tapping CRUSH
                        // on a smeared pad skipped the restore and baked the
                        // era onto the stretched audio, then lit CRUSH alone.
                        // The card claimed one treatment while the sound
                        // carried two. `unTreatState` reads both shapes and
                        // answers the bin question at the same time.
                        when (PadSheet.unTreatState(freshPad, f.binContents().map { it.originalName }.toSet())) {
                            PadSheet.UnTreat.READY -> f.unEraPad(slot)
                            PadSheet.UnTreat.GHOSTS_POSTDATE -> error(
                                "pad $slot can't cleanly re-treat - its ghost layers postdate the last " +
                                    "treatment - clear GHOSTS, or accept the current sound, before treating again",
                            )
                            // A recipe with nothing in the bin behind it (a
                            // bank-B twin, a CLI treat, a bin since emptied)
                            // is a sound the sheet can name but not undo: the
                            // new treatment stacks, the way `treat` always has
                            // — and the toast says so, since the card will
                            // light one segment while the sound carries two.
                            PadSheet.UnTreat.NOT_BINNED -> stacked = amount > 0f
                            PadSheet.UnTreat.NOTHING -> Unit
                        }
                        when (treatment) {
                            is PadSheet.Treatment.Era -> f.eraPad(slot, treatment.name, amount)
                            is PadSheet.Treatment.Character -> f.characterPad(slot, treatment.name, amount)
                            // Row five (and TUNE) reads the kit's key, or does without
                            // its own way; the retune's phases come from a fresh seed
                            // per press.
                            is PadSheet.Treatment.Keyed -> f.keyedPad(slot, treatment.name, amount, keyedSeed)
                        }
                        if (treatment is PadSheet.Treatment.Keyed) keyLabel = f.lastKeyLabel
                        applied = true
                    }
                }
                if (applied) auditionOnRefresh = true
                model = fresh
                pendingMetadataSlots = emptySet()
                onKitUpdated(fresh.kit)
                if (applied) {
                    onToast(
                        when {
                            stacked -> Copy.treatedStacked(PadSheet.displayLabel(segment), padName)
                            treatment is PadSheet.Treatment.Keyed -> Copy.keyed(PadSheet.displayLabel(segment), padName, keyLabel)
                            else -> Copy.treated(PadSheet.displayLabel(segment), padName)
                        },
                    )
                } else {
                    onToast(Copy.BIN_ITEM_GONE)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The ghosts-postdate-treatment refusal above is expected,
                // in-voice user copy, not a diagnostic — the check()'s own
                // message stays in logs (via `e`/a debugger) but the toast
                // says what Copy says, not raw exception prose. The retune's
                // refusal is the same kind of line: the pad is a drum, said so.
                when {
                    e is IllegalStateException -> onToast(Copy.RETREAT_REFUSED)
                    e is KitBuilderModel.Unpitched -> onToast(Copy.notANote(e.message ?: "not a note"))
                    else -> failure("TREATMENT", e)
                }
            } finally {
                busy = false
            }
        }
    }

    /**
     * NONE: take the treatment back off (September UAT, finding 13 — "there
     * is no un-treat"). Until now the chip was display-only, so undoing a
     * treatment meant leaving PAD SHEET for TAKES + BIN and restoring a
     * whole-kit take — which rolled back everything else done since.
     *
     * The move itself is [KitBuilderModel.unEraPad], the same door
     * [applyTreatment] already uses to get back to clean audio before
     * stacking a new treatment. Nothing new happens to the files; what is
     * new is that the user can ask for it.
     *
     * Which of [PadSheet.UnTreat]'s answers applies is decided *inside* the
     * lock, off the fresh pad and a fresh bin listing — not off the chip's
     * enablement. The chip lights whenever the card shows a treatment,
     * because deciding otherwise would mean listing the bin directory on
     * every recomposition to draw one chip; refusing in words is this
     * file's habit anyway, and a refusal that names its reason teaches more
     * than a chip that is quietly grey.
     *
     * Same fresh-model shape, sample-identity guard and [model] swap as
     * [applyTreatment] — see its KDoc. A round-robin pad refuses from
     * [KitBuilderModel.unEraPad]'s own `require`, exactly as treating one
     * does today; that is the card's existing gap, not this action's.
     */
    fun unTreat() {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        val padName = p.displayName
        val staleSampleFile = p.sampleFile
        val kitDir = m.kitDir
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        scope.launch {
            busy = true
            try {
                // null = the slot changed underneath us; BIN_ITEM_GONE says so.
                var state: PadSheet.UnTreat? = null
                val (fresh, _) = withFreshKit(kitDir) { f ->
                    reapplyPendingMetadataFields(f, stalePads)
                    val freshPad = f.kit.pad(slot)
                    if (freshPad != null && freshPad.sampleFile == staleSampleFile) {
                        val binned = f.binContents().map { it.originalName }.toSet()
                        val answer = PadSheet.unTreatState(freshPad, binned)
                        if (answer == PadSheet.UnTreat.READY) f.unEraPad(slot)
                        state = answer
                    }
                }
                model = fresh
                pendingMetadataSlots = emptySet()
                onKitUpdated(fresh.kit)
                onToast(
                    when (state) {
                        PadSheet.UnTreat.READY -> Copy.unTreated(padName)
                        PadSheet.UnTreat.GHOSTS_POSTDATE -> Copy.RETREAT_REFUSED
                        PadSheet.UnTreat.NOT_BINNED -> Copy.UNTREAT_NOT_BINNED
                        // The card only offers NONE over a treatment, so this
                        // is a race (a twin, another screen) rather than a tap
                        // on an untreated pad - it reads the same either way.
                        PadSheet.UnTreat.NOTHING, null -> Copy.BIN_ITEM_GONE
                    },
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("UNTREAT", e)
            } finally {
                busy = false
            }
        }
    }

    /** DO IT AGAIN, half one: lift this pad's last treatment onto the caller's clipboard, named. */
    fun onCopyRecipe() {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        val clip = RecipeReplay.clip(p.recipe, m.name, slot)
        if (clip == null) {
            onToast(Copy.REPLAY_NOTHING)
            return
        }
        onRecipeCopied(clip)
        onToast(Copy.copied(clip.word, clip.from))
    }

    /**
     * DO IT AGAIN, half two: replay the clipboard's recipe on this pad
     * through [commitPadEditNow] — same fresh model, same lock, same
     * sample-identity guard as every other rewrite here. The plan is
     * checked first so a recipe with no door here refuses in its own
     * words before the lock is ever taken; the keyed family's own
     * refusal (a drum, not a note) is caught inside and said the way the
     * TREATMENT card says it, rather than as a "PASTE FAILED" diagnostic.
     */
    fun onPasteRecipe() {
        if (busy) return
        val clip = clipboard
        if (clip == null) {
            onToast(Copy.REPLAY_CLIPBOARD_EMPTY)
            return
        }
        val plan = RecipeReplay.plan(clip.recipe)
        if (plan is RecipeReplay.Plan.Refused) {
            onToast(plan.reason)
            return
        }
        val padName = model?.kit?.pad(slot)?.displayName?.uppercase() ?: return
        var said: String? = null
        if (plan is RecipeReplay.Plan.Dust) {
            // A dust recipe needs its tape's print. Resolved here, on IO and
            // before the lock, so the refusals are the app's own words
            // (the tape gone, or nothing between its hits) rather than a
            // PASTE FAILED, and the extraction never runs under the mutex.
            // `busy` is held from here: the button is enabled on `!busy`,
            // and a second tap during the extraction must not start a
            // second commit. It is let go right before `commitPadEditNow`
            // takes it back, on the same main-thread turn.
            busy = true
            scope.launch {
                val tapeFile = File(snipsDir, plan.tape)
                val print = try {
                    withContext(Dispatchers.IO) { DustPrints.forTape(tapeFile) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    busy = false
                    failure("PASTE", e)
                    return@launch
                }
                busy = false
                if (print == null) {
                    onToast(if (tapeFile.isFile) Copy.DUST_NO_GHOSTS else Copy.dustTapeGone(plan.tape))
                    return@launch
                }
                commitPadEditNow("PASTE", onSuccess = { said?.let(onToast) }) { mm ->
                    said = RecipeReplay.apply(mm, slot, clip.recipe, padName) { print }.toast
                }
            }
            return
        }
        commitPadEditNow("PASTE", onSuccess = { said?.let(onToast) }) { mm ->
            said = try {
                RecipeReplay.apply(mm, slot, clip.recipe, padName).toast
            } catch (e: KitBuilderModel.Unpitched) {
                Copy.notANote(e.message ?: "not a note")
            }
        }
    }

    fun onGhostsToggle() {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        val enabling = p.velocityLayers.isEmpty()
        commitPadEditNow("SOFT HITS", onSuccess = { if (enabling) onToast(Copy.GHOSTS_ON) }) { mm ->
            if (enabling) mm.addGhostLayers(slot) else mm.clearGhostLayers(slot)
        }
    }

    fun onEject() {
        // "DELETE", not "EJECT" — EJECT means "stop listening" on the
        // shelf's own ArmControl; this clears the pad into the bin, a
        // different action entirely, and a failure here must read
        // "DELETE FAILED", not "EJECT FAILED".
        commitPadEditNow("DELETE", onSuccess = { onToast(Copy.DELETE_SNIP); onBack() }) { mm -> mm.clear(slot) }
    }

    // ---- MUTATE: one hit from two parents (MutateSheet over Mutate) ----
    var mutateMode by remember(slot) { mutableStateOf(MutateSheet.MODES.first()) }
    var partner by remember(slot) { mutableStateOf<MutateSheet.Partner?>(null) }
    // Each ROULETTE tap is a new seed, so every spin is a new deal and each one reproducible.
    var spins by remember(slot) { mutableIntStateOf(0) }
    // The rooms on the shelf (OUTSIDE measured, KEEP ROOM kept): the card's
    // third kind of parent. Read off the shelf once per sheet and again
    // after a keep; the shelf is the kit folder's parent, as ROULETTE has it.
    var rooms by remember { mutableStateOf<List<Rooms.Room>>(emptyList()) }
    // The other kits on the shelf, for the picker (the crate with intent);
    // the picked kit's pads load when one is picked.
    var otherKits by remember { mutableStateOf<List<MutateSheet.OtherKit>>(emptyList()) }
    var pickedKit by remember(slot) { mutableStateOf<MutateSheet.OtherKit?>(null) }
    var otherPads by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(pickedKit) {
        val k = pickedKit
        otherPads = if (k == null) emptyList() else withContext(Dispatchers.IO) {
            try {
                MutateSheet.padsOf(k).map { it.slot }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    var roomsRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(entry.dir, roomsRevision) {
        val root = entry.dir.parentFile ?: entry.dir
        rooms = withContext(Dispatchers.IO) {
            try {
                Rooms.list(root)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        otherKits = withContext(Dispatchers.IO) {
            try {
                MutateSheet.otherKits(root, entry.dir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    val mutateKnob = MutateSheet.knobFor(MutateSheet.modeFor(mutateMode))
    var pendingMutateKnob by remember(slot, mutateMode) {
        mutableFloatStateOf(mutateKnob?.let { MutateSheet.fraction(it, it.default) } ?: 0f)
    }

    /**
     * MUTATE. The verb refuses layered and chained pads itself; the GHOSTS
     * case gets its own line first because it's the one a thumb causes.
     *
     * Runs against a FRESH model via [withFreshKit] — the pre-lock GHOSTS
     * refusal above is a cheap read (no DSP), so it's simply re-checked
     * against the fresh pad inside the lock too, alongside the sampleFile
     * identity check every converted sibling uses. [MutateSheet.apply]
     * itself is the DSP; there's nothing to hoist ahead of the lock the way
     * [onOutside]'s mic capture is — the transform needs the model it's
     * writing into. [model] is swapped to the fresh instance on success, so
     * (like [applySmear]/[applyTreatment]) this does not audition the
     * result immediately — see [applySmear]'s KDoc for why that write would
     * target an already-orphaned `remember(model)` state slot.
     */
    fun onMutate() {
        if (busy) return
        val m = model ?: return
        val who = partner ?: return
        val p = m.kit.pad(slot) ?: return
        if (p.velocityLayers.isNotEmpty()) {
            onToast(Copy.MUTATE_NEEDS_ONE)
            return
        }
        val padName = p.displayName
        val staleSampleFile = p.sampleFile
        val move = MutateSheet.modeFor(mutateMode)
        val fraction = pendingMutateKnob
        val kitDir = m.kitDir
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        scope.launch {
            busy = true
            try {
                var applied = false
                val (fresh, _) = withFreshKit(kitDir) { f ->
                    reapplyPendingMetadataFields(f, stalePads)
                    val freshPad = f.kit.pad(slot)
                    if (freshPad != null && freshPad.sampleFile == staleSampleFile && freshPad.velocityLayers.isEmpty()) {
                        MutateSheet.apply(f, slot, who, move, fraction)
                        applied = true
                    }
                }
                model = fresh
                pendingMetadataSlots = emptySet()
                onKitUpdated(fresh.kit)
                if (applied) {
                    onToast(Copy.mutated(mutateMode, padName, MutateSheet.name(who)))
                } else {
                    onToast(Copy.BIN_ITEM_GONE)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("MUTATE", e)
            } finally {
                busy = false
            }
        }
    }

    fun onUnmutate() {
        commitPadEditNow("UNDO", onSuccess = { onToast(Copy.UNMUTATED) }) { mm -> MutateSheet.undo(mm, slot) }
    }

    /** ROULETTE: the shelf (the kit folder's parent) is the crate; the deal becomes the partner. */
    fun onRoulette() {
        if (busy) return
        val m = model ?: return
        val root = entry.dir.parentFile ?: entry.dir
        val seed = spins
        scope.launch {
            busy = true
            try {
                val deal = withContext(Dispatchers.IO) { MutateSheet.deal(m, slot, root, seed) }
                spins = seed + 1
                partner = deal
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is IllegalArgumentException) onToast(Copy.CRATE_EMPTY) else failure("ROULETTE", e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * DRIFT: one tap — the shelf deals the neighbour and MORPH blends toward
     * it, MIX how far. The card flips to MORPH so the knob it read is the
     * knob on screen; each tap is a new seed, like ROULETTE.
     *
     * Runs against a FRESH model via [withFreshKit] — same shape as
     * [onMutate] above, GHOSTS refusal re-checked on the fresh pad, sample
     * identity re-checked before [MutateSheet.drift] (which deals AND
     * mutates — both need the model this lock actually opened) ever runs.
     * A crate-empty [IllegalArgumentException] from the roulette still
     * escapes [withFreshKit] undirtied and untouched by the identity check
     * — nothing was applied, nothing to save, same refusal as before. On
     * success [model] swaps to the fresh instance; no immediate audition,
     * same trade as [applySmear]/[onMutate] for the same reason.
     */
    fun onDrift() {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        if (p.velocityLayers.isNotEmpty()) {
            onToast(Copy.MUTATE_NEEDS_ONE)
            return
        }
        val root = entry.dir.parentFile ?: entry.dir
        val seed = spins
        val padName = p.displayName
        val staleSampleFile = p.sampleFile
        if (mutateMode != Mutate.Mode.MORPH.name) mutateMode = Mutate.Mode.MORPH.name
        val fraction = pendingMutateKnob
        val kitDir = m.kitDir
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        scope.launch {
            busy = true
            try {
                var drifted: Mutate.Drifted? = null
                val (fresh, _) = withFreshKit(kitDir) { f ->
                    reapplyPendingMetadataFields(f, stalePads)
                    val freshPad = f.kit.pad(slot)
                    if (freshPad != null && freshPad.sampleFile == staleSampleFile && freshPad.velocityLayers.isEmpty()) {
                        drifted = MutateSheet.drift(f, slot, root, seed, fraction)
                    }
                }
                model = fresh
                pendingMetadataSlots = emptySet()
                onKitUpdated(fresh.kit)
                val d = drifted
                if (d != null) {
                    spins = seed + 1
                    partner = MutateSheet.Partner.Deal(d.pick.label, d.pick.file, seed)
                    onToast(Copy.drifted(padName, d.pick.label))
                } else {
                    onToast(Copy.BIN_ITEM_GONE)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is IllegalArgumentException) onToast(Copy.CRATE_EMPTY) else failure("DRIFT", e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * A FILE: what the picker handed back becomes the partner - decoded
     * the way a share is (a WAV straight through, anything else by the
     * platform's codec), held under the cache as a WAV, labelled with the
     * file's own name. A refusal (silent, too big, not audio) is read out
     * in the decoder's words; a cancelled picker hands back nothing and
     * nothing happens.
     */
    fun onFilePicked(uri: Uri) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val held = withContext(Dispatchers.IO) {
                    val name = ShareInbox.displayName(context, uri)
                    val decoded = MediaDecode.decode(context, uri)
                    MutateSheet.hold(File(context.cacheDir, MutateSheet.PARENTS_DIR), name, decoded)
                }
                partner = held
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is IllegalArgumentException) onToast(Copy.fileRefused(e.message ?: "that file said no")) else failure("A FILE", e)
            } finally {
                busy = false
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    fun onMakeInstrument() {
        if (busy) return
        val m = model
        val currentSnip = snip
        if (m == null || currentSnip == null) return
        val p = m.kit.pad(slot) ?: return
        val instrumentName = Names.sanitizeStem("${m.kit.name}_${p.displayName}")
        scope.launch {
            busy = true
            try {
                val destRoot = File(entry.dir.parentFile ?: entry.dir, KitShelf.INSTRUMENTS_DIR)
                withContext(Dispatchers.IO) {
                    OneNote.export(instrumentName, currentSnip, destRoot, overwrite = true)
                }
                onToast(Copy.INSTRUMENT_MADE)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is IllegalArgumentException) onToast(Copy.NO_PITCH) else failure("INSTRUMENT", e)
            } finally {
                busy = false
            }
        }
    }

    // ---- OUTSIDE: the world as an effect (OutsideSheet over Outside) ----
    var outsideMove by remember(slot) { mutableStateOf(OutsideSheet.MOVES.first()) }
    val outsideKnob = OutsideSheet.knobFor(OutsideSheet.moveFor(outsideMove))
    var pendingOutsideKnob by remember(slot, outsideMove) { mutableFloatStateOf(outsideKnob.defaultFraction) }
    // What the trip is doing right now — LISTENING…, SENDING… — for the
    // SEND button's own label; null when nothing is out.
    var outsideStage by remember(slot) { mutableStateOf<String?>(null) }
    // The last ROOM trip's outcome, the room as measured riding it, until
    // KEEP ROOM puts it on the shelf; a REAMP measures none.
    var measuredRoom by remember(slot) { mutableStateOf<OutsideSheet.Outcome?>(null) }
    // The armed mic session holds the mic; OUTSIDE wants it to itself.
    val tapeArmed by MicSessionService.armed.collectAsState()

    /**
     * SEND: the phone listens, plays the send (the pad for REAMP, the
     * sweep for ROOM) out of its current output route, keeps listening,
     * and the return becomes the pad through `OutsideSheet.apply` — bin,
     * recipe, provenance exactly as any treatment. Refusals come first
     * and in words: GHOSTS on, the mic not granted (ARM on KITS grants
     * it), the tape rolling. The audition is silenced before the trip so
     * the mic never hears the pad twice.
     *
     * The send and the multi-second [OutsideSession.run] capture run
     * first, fully OUTSIDE `KitWrites.mutex` — a live mic recording is
     * exactly the expensive/interactive work that must never sit behind an
     * app-wide write lock; every other kit-write screen in the app would
     * freeze for the length of the trip if it did. Only what happens
     * AFTER the return is in — `OutsideSheet.apply`'s rewrite, plus the
     * open and the save — runs inside [withFreshKit]'s lock span, same as
     * the original hand-rolled version already did (this isn't new
     * lock-held work, just the right model underneath it). [staleSampleFile]
     * is re-verified against the FRESH pad right before `apply` runs: if
     * the slot's sound changed while the trip was out recording, the write
     * is abandoned rather than landing the return on somebody else's pad.
     * [model] swaps to the fresh instance on success; no immediate
     * audition, same trade as the other converted siblings.
     */
    fun onOutside() {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        if (p.velocityLayers.isNotEmpty()) {
            onToast(Copy.OUTSIDE_NEEDS_ONE)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onToast(Copy.OUTSIDE_NEEDS_MIC)
            return
        }
        if (tapeArmed) {
            onToast(Copy.OUTSIDE_TAPE_ROLLING)
            return
        }
        val move = OutsideSheet.moveFor(outsideMove)
        val fraction = pendingOutsideKnob
        val padName = p.displayName
        val staleSampleFile = p.sampleFile
        val kitDir = m.kitDir
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        voice?.release()
        voice = null
        scope.launch {
            busy = true
            outsideStage = Copy.OUTSIDE_LISTENING
            try {
                // Unlocked: reading the send off `m` (still the screen-mount
                // model — fine, this only READS the pad's current audio) and
                // the mic capture itself, which can run for seconds.
                val returned = withContext(Dispatchers.IO) {
                    val send = OutsideSheet.send(m, slot, move)
                    val preRoll = OutsideSheet.preRollFrames(send.sampleRate)
                    OutsideSession.run(context, send, preRoll, OutsideSheet.listenFrames(send)) {
                        // onSending fires on the IO thread; the stage is
                        // Compose state, so the write hops back to the
                        // composition's own (main) scope.
                        scope.launch { outsideStage = Copy.OUTSIDE_SENDING }
                    } to preRoll
                }
                val (returnedSnip, preRoll) = returned
                var applied = false
                var outcome: OutsideSheet.Outcome? = null
                val (fresh, _) = withFreshKit(kitDir) { f ->
                    reapplyPendingMetadataFields(f, stalePads)
                    val freshPad = f.kit.pad(slot)
                    if (freshPad != null && freshPad.sampleFile == staleSampleFile && freshPad.velocityLayers.isEmpty()) {
                        outcome = OutsideSheet.apply(f, slot, move, returnedSnip, fraction, preRoll)
                        applied = true
                    }
                }
                model = fresh
                pendingMetadataSlots = emptySet()
                onKitUpdated(fresh.kit)
                val o = outcome
                if (applied && o != null) {
                    // The last trip's room, or none: a REAMP measures no
                    // room, so KEEP ROOM dims until the next ROOM trip.
                    measuredRoom = o.takeIf { it.impulse != null }
                    onToast(Copy.outside(outsideMove, padName, o.lagMs, o.confidence))
                } else {
                    // Not `measuredRoom = null` here: this trip abandoned
                    // without applying anything, so an earlier ROOM trip's
                    // still-unkept measurement (measuredRoom is
                    // remember(slot)-keyed, so it survives this model swap)
                    // is exactly as keepable as it was before this tap.
                    onToast(Copy.BIN_ITEM_GONE)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is Outside.Refused) onToast(Copy.outsideRefused(e.message ?: "the room said no")) else failure("OUTSIDE", e)
            } finally {
                outsideStage = null
                busy = false
            }
        }
    }

    fun onOutsideUndo() {
        commitPadEditNow("UNDO", onSuccess = { onToast(Copy.OUTSIDE_UNDONE) }) { mm -> OutsideSheet.undo(mm, slot) }
    }

    /**
     * KEEP ROOM: the room the last ROOM trip measured goes onto the shelf
     * as a reusable parent (`Rooms`, beside the kits), named after the kit,
     * and becomes this card's MUTATE partner at once - so the next pad can
     * take it without a trip. Kept once: the button dims until the next
     * ROOM trip measures another.
     */
    fun onKeepRoom() {
        if (busy) return
        val m = model ?: return
        val o = measuredRoom
        if (o == null) {
            onToast(Copy.ROOM_NONE_TO_KEEP)
            return
        }
        val root = entry.dir.parentFile ?: entry.dir
        // Busy before the launch, not inside it: a second tap in the gap
        // before the coroutine starts must not keep the same room twice.
        busy = true
        scope.launch {
            try {
                val kept = withContext(Dispatchers.IO) { OutsideSheet.keep(root, o, m.kit.name) }
                measuredRoom = null
                roomsRevision++
                partner = Rooms.partner(kept)
                onToast(Copy.roomKept(kept.name))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("KEEP ROOM", e)
            } finally {
                busy = false
            }
        }
    }

    // ---- PAD FROM ANYTHING: one hit, a pad forever (PadMaker over PadFromAnything) ----
    var pendingDepth by remember(slot) { mutableFloatStateOf(PadMaker.DEPTH.defaultFraction) }
    var pendingBloom by remember(slot) { mutableFloatStateOf(PadMaker.BLOOM.defaultFraction) }

    /**
     * MAKE PAD: the same door as MAKE INSTRUMENT (an instrument beside the
     * kits), but any pad qualifies — a drum lands as a drone. Seconds of
     * stretching, so it runs on IO under the busy flag; a fresh seed every
     * press, like every other door that renders.
     */
    fun onMakePad() {
        if (busy) return
        val m = model
        val currentSnip = snip
        if (m == null || currentSnip == null) return
        val p = m.kit.pad(slot) ?: return
        // The two refusals a thumb can cause get their own lines, before any work starts;
        // anything else the builder refuses says exactly why, law 3.
        if (currentSnip.durationSeconds < PadFromAnything.MIN_SOURCE_SEC) {
            onToast(Copy.PAD_TOO_SHORT)
            return
        }
        if (currentSnip.durationSeconds > PadFromAnything.MAX_SOURCE_SEC) {
            onToast(Copy.PAD_TOO_LONG)
            return
        }
        val padName = Names.sanitizeStem("${m.kit.name}_${p.displayName}_Pad")
        val spec = PadMaker.spec(pendingDepth, pendingBloom, kotlin.random.Random.nextLong(0L, 1_000_000L))
        scope.launch {
            busy = true
            try {
                val destRoot = File(entry.dir.parentFile ?: entry.dir, KitShelf.INSTRUMENTS_DIR)
                withContext(Dispatchers.IO) {
                    PadFromAnything.export(padName, currentSnip, destRoot, spec, overwrite = true)
                }
                onToast(Copy.PAD_MADE)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("PAD", e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * DE-SAMPLE: the pad becomes the nearest THUMP patch's own render, the
     * patch riding it as a recipe. A far match is named, not taken.
     *
     * Runs against a FRESH model via [withFreshKit] — same shape as
     * [onMutate]/[onDrift]. A far-match [KitBuilderModel.Far] refusal still
     * escapes [withFreshKit] undirtied — the search ran (against the fresh
     * pad's audio) but nothing was written, so there's nothing to save
     * either way. On success [model] swaps to the fresh instance; no
     * immediate audition, same trade as the other converted siblings.
     */
    fun onDesample() {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        if (p.velocityLayers.isNotEmpty()) {
            onToast(Copy.MUTATE_NEEDS_ONE)
            return
        }
        val padName = p.displayName
        val staleSampleFile = p.sampleFile
        val kitDir = m.kitDir
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        scope.launch {
            busy = true
            try {
                var match: com.snipsnap.synth.Desample.Match? = null
                val (fresh, _) = withFreshKit(kitDir) { f ->
                    reapplyPendingMetadataFields(f, stalePads)
                    val freshPad = f.kit.pad(slot)
                    if (freshPad != null && freshPad.sampleFile == staleSampleFile && freshPad.velocityLayers.isEmpty()) {
                        match = f.desamplePad(slot)
                    }
                }
                model = fresh
                pendingMetadataSlots = emptySet()
                onKitUpdated(fresh.kit)
                val found = match
                if (found != null) {
                    onToast(Copy.desampled(padName, found.patch.voice.name, found.distance))
                } else {
                    onToast(Copy.BIN_ITEM_GONE)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is KitBuilderModel.Far) onToast(Copy.desampleFar(e.match.patch.voice.name, e.match.distance)) else failure("MAKE SYNTH", e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * The header's own ◄ KIT path: flush a pending metadata save (if any)
     * *before* calling the real [onBack], on this composable's own scope —
     * so the common exit gets immediate consistency (the save is done, not
     * just handed off, by the time KIT re-renders) rather than waiting on
     * the teardown safety net below. Every *other* way to leave this
     * screen (a MenuRow tab switch, RE-TRIM) calls [onBack] — or, for
     * RE-TRIM, [onNavigateTape] — directly, bypassing this; the teardown
     * `DisposableEffect` is what covers those.
     */
    fun requestBack() {
        val m = model
        // `pendingMetadataSlots`, not `m.dirty` — same reasoning as the
        // debounce effect above: this flush saves a fresh model, not `m`,
        // so `m.dirty` is no longer a reliable "is there anything this
        // flush owns" signal.
        if (m == null || pendingMetadataSlots.isEmpty() || busy) {
            if (!busy) onBack()
            return
        }
        val kitDir = m.kitDir
        val stalePads = pendingMetadataSlots.associateWith { m.kit.pad(it) }
        scope.launch {
            busy = true
            try {
                val (fresh, saved) = withFreshKit(kitDir) { f -> reapplyPendingMetadataFields(f, stalePads) }
                if (saved) onKitUpdated(fresh.kit)
                pendingMetadataSlots = emptySet()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("SAVE", e)
            } finally {
                busy = false
            }
            onBack()
        }
    }

    // System Back mirrors the header's own ◄ KIT chip exactly — same
    // function, so a debounced metadata edit is flushed here too, not just
    // on the chip's own tap. `requestBack()` already self-guards on `busy`
    // (a second press while the save is in flight no-ops), so no extra
    // `enabled` condition is needed here, same as the chip's own `enabled`.
    BackHandler(onBack = ::requestBack)

    /**
     * The safety net every exit shares, present and future: whatever's
     * still dirty when this composable leaves composition — a debounced
     * metadata edit that hadn't fired yet, most likely — gets one save,
     * launched into [appScope] rather than this composable's own
     * (about-to-be-cancelled) one. `onDispose` can't suspend, so launching
     * into a scope that outlives the dispose call is the whole point;
     * [onKitUpdated] and [onToast] are plain callbacks into `App`, which
     * is still alive and still owns them regardless of which child
     * composable is being torn down.
     *
     * This flush must NOT save the held [m] directly: by the time it runs,
     * [appScope] may have outlived a navigation away from this pad, and
     * [m] is a snapshot from whenever this screen's `KitBuilderModel`
     * was opened. `KitBuilderModel.save()` is an unconditional whole-file
     * overwrite with no version check, so saving a stale [m] would silently
     * revert any edit another screen made to this same kit in the meantime
     * (SET KEY, EVIL TWINS, IN KEY off the KIT screen — none of them touch
     * this kit's directory, so `App`'s identity guard doesn't catch it) —
     * or, if the kit's folder was itself renamed/deleted out from under
     * this screen, resurrect a ghost directory containing nothing but a
     * stale `kit.json` (`KitStore.save`'s `dir.mkdirs()`).
     *
     * Instead, re-open a FRESH model on the same folder under the lock —
     * [PadCaptureScreen]'s `commitToPad` and [SynthScreen]'s `sendToSlot`
     * both already do this for the same reason — and apply only the
     * fields [editPadMetadata] itself ever touches (LEVEL/PAN/TUNE/
     * ONE-SHOT/CHOKE/SHAPE) onto the *current* on-disk pad. A fresh
     * `open()` on a directory that no longer exists throws in
     * `KitStore.load`, which the `catch` below turns into a toast exactly
     * like every other failed write here — closing the ghost-resurrection
     * path for free, not just the revert. This exact sequence — lock,
     * reopen fresh, reapply, save once — is [withFreshKit] below; the
     * per-slot reapply-with-identity-check is [reapplyPendingMetadataFields].
     * [requestBack] and the debounced save above share both, so this
     * pattern is written once, not four times.
     *
     * The guard is `targetSlots.isNotEmpty()` alone, not `m.dirty` too:
     * `m.dirty` no longer means "this flush has unsaved work" now that
     * saves route through a fresh model instead of `m` — the metadata
     * paths above never call `m.save()` at all any more, and the
     * structural paths ([commitPadEditNow], [applySmear]) swap [model] to
     * their own fresh instance on success rather than mutating `m` in
     * place. Only [applyTreatment]'s still-unconverted era/character/keyed
     * branch can still leave `m` dirty directly; since that state is never
     * read here, a mid-throw half-baked `m` from that branch can no longer
     * leak into this flush's decision either way.
     *
     * One narrower window is knowingly accepted rather than closed here,
     * so it doesn't read later as an oversight: the copy is slot-granular,
     * not field-granular — an edited slot has all nine of
     * [editPadMetadata]'s fields restamped, even the ones this burst
     * didn't touch. If IN KEY ([KitBuilderModel.retuneTonalPads], which
     * moves `tuneCoarse` AND `tuneFine` together) lands inside the flush
     * window, the stale `tuneCoarse` is restored over its new `tuneFine` —
     * a tuning neither writer meant. Adding `tuneFine` to the copy list
     * would NOT fix that; it would deliberately clobber IN KEY instead.
     * The real fix is a per-slot baseline snapshot taken when the slot
     * first goes pending, copying only fields that actually differ. Not
     * worth it yet: this window is far narrower than the whole-file
     * clobber it replaced.
     */
    DisposableEffect(model) {
        onDispose {
            val m = model
            val targetSlots = pendingMetadataSlots
            if (m != null && targetSlots.isNotEmpty()) {
                // Snapshot now, synchronously — `m` can't change further
                // once this composable has left composition, so this is
                // the exact in-memory state the debounce would have saved.
                // More than one slot here means the user visited several
                // pads inside one debounce window; every one of them gets
                // applied to the same freshly-opened model below, under
                // one lock, before the one save. (`targetSlots.isNotEmpty()`
                // alone is the right gate now, not `m.dirty` too —
                // `reapplyPendingMetadataFields` below already no-ops when
                // there's nothing to apply, and `m.dirty` can no longer be
                // read as "this flush has unsaved work": requestBack and
                // the debounce above never call `m.save()` any more either.)
                val stalePads = targetSlots.associateWith { m.kit.pad(it) }
                val kitDir = m.kitDir
                appScope.launch {
                    try {
                        val (fresh, saved) = withFreshKit(kitDir) { f -> reapplyPendingMetadataFields(f, stalePads) }
                        if (saved) onKitUpdated(fresh.kit)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failure("SAVE", e)
                    }
                }
            }
        }
    }

    val cls = pad.colorHex?.removePrefix("#")?.toIntOrNull(16) ?: Schemes.classColor(pad.drumClass)
    val classColor = cls.tape

    // SMEAR never rides PadSheet.read's recipe shapes (era/treatment/keyed)
    // - its own ad-hoc `{"verb":"smear","amount":x}` is read separately,
    // and checked first: it always draws its own row-one segment, so it
    // never needs the phone-ruling fallback below.
    val smearAmount = readSmearRecipe(pad.recipe)
    // DUST rides its own shape too (`{"verb":"dust","amount","tape"}`), read the same way.
    val dustAmount = PadSheet.readDust(pad.recipe)?.amount
    val applied = if (smearAmount != null || dustAmount != null) null else PadSheet.read(pad.recipe)
    val activeSegment = when {
        smearAmount != null -> PadSheet.SMEAR
        dustAmount != null -> PadSheet.DUST
        else -> applied?.segment
    }
    val isNoneState = smearAmount == null && dustAmount == null && applied == null
    // The phone ruling, both rows: a treatment no segment draws is named
    // on the provenance line, never shown as NONE.
    val unmappedLabel = applied?.takeIf { it.segment == null }?.let { a ->
        when (a.treatment) {
            is PadSheet.Treatment.Era -> "AGED: ${a.treatment.name.uppercase()}"
            is PadSheet.Treatment.Character -> "TREATED: ${a.treatment.name.uppercase()}"
            is PadSheet.Treatment.Keyed -> "IN KEY: ${a.treatment.name.uppercase()}"
        }
    }
    val amount = smearAmount ?: dustAmount ?: applied?.amount ?: PadSheet.DEFAULT_AMOUNT

    val assignedSlots = kit.pads.map { it.slot }.sorted()
    val idx = assignedSlots.indexOf(slot)
    val prevSlot = assignedSlots.getOrNull((idx - 1 + assignedSlots.size).mod(assignedSlots.size.coerceAtLeast(1)))
        .takeIf { assignedSlots.size > 1 }
    val nextSlot = assignedSlots.getOrNull((idx + 1).mod(assignedSlots.size.coerceAtLeast(1)))
        .takeIf { assignedSlots.size > 1 }

    // ---- pending stepper state: local until the drag/tap releases ----
    var pendingDb by remember(slot, pad.level) { mutableFloatStateOf(levelToDb(pad.level)) }
    var pendingPan by remember(slot, pad.pan) { mutableFloatStateOf(pad.pan) }
    var pendingTuneSemis by remember(slot, pad.tuneCoarse) {
        mutableIntStateOf(pad.tuneCoarse.coerceIn(-12, 12))
    }
    var pendingAmt by remember(slot, amount) { mutableFloatStateOf(amount) }
    // SHAPE's four: null on the pad means "the format's own default", drawn
    // at the position that default sounds like (no ramp, full length, open,
    // no ring) — and a knob committed *at* that position writes null back,
    // so resting a stepper never turns a pad "shaped".
    var pendingAttack by remember(slot, pad.attack) { mutableFloatStateOf(pad.attack ?: 0f) }
    var pendingDecay by remember(slot, pad.decay) { mutableFloatStateOf(pad.decay ?: 1f) }
    var pendingCutoff by remember(slot, pad.cutoff) { mutableFloatStateOf(pad.cutoff ?: 1f) }
    var pendingRes by remember(slot, pad.resonance) { mutableFloatStateOf(pad.resonance ?: 0f) }
    val isShaped = pad.attack != null || pad.decay != null || pad.cutoff != null || pad.resonance != null

    // Pad Sheet v2 (Direction A): the everyday controls stay put; every
    // workshop card is a group box, closed by default, its strip reading
    // what the pad carries (PadSheetBoxes). One box open at a time, the
    // open one remembered per kit by the caller so the next pad opens on
    // the same bench. The pad nav is pinned under the scroll.
    val boxes = PadSheetBoxes.summaries(pad, outsideStage)
    val openBoxKind = openBox?.let { PadSheetBoxes.boxFor(it) }
    fun tapBox(box: PadSheetBoxes.Box) = onOpenBox(PadSheetBoxes.toggle(openBoxKind, box)?.name)

    // DUST FROM ▸ (docs/DUST.md §5): borrow another tape's dust. The shelf's
    // tapes open inline on the TREATMENT bench, newest first; a pick runs
    // the DUST door at the card's AMT with that tape, and since the recipe
    // carries the tape, AMT moves after that keep the borrowed dust.
    var dustFromOpen by remember(slot) { mutableStateOf(false) }
    var dustFromTapes by remember { mutableStateOf<List<File>>(emptyList()) }
    fun openDustFrom() {
        if (busy) return
        scope.launch {
            val tapes = withContext(Dispatchers.IO) { SnipStore.list(context.filesDir) }
            if (tapes.isEmpty()) {
                onToast(Copy.DUST_FROM_EMPTY)
                return@launch
            }
            dustFromTapes = tapes
            dustFromOpen = true
        }
    }
    fun applyDustFrom(tape: String) {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        applyingSegment = PadSheet.DUST
        applyDust(m, p, pendingAmt, p.displayName, from = tape)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PadSheetHeader(
            slot = slot,
            pad = pad,
            classColor = classColor,
            scheme = scheme,
            busy = busy,
            onBack = ::requestBack,
            onHit = { snip?.let { audition(it, pad.level, pad) } },
            // A/B: only while a treatment is on and the take before it is
            // still in the bin (`binDaysLeft` is that entry's countdown).
            onBefore = if (!isNoneState && binDaysLeft != null) ({ playBefore(pad) }) else null,
        )

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WaveformLcd(snip, pad.drumClass, classColor, scheme, Modifier.padding(top = 4.dp))

            TapeText(
                provenanceLine(pad, snip, binDaysLeft) + (unmappedLabel?.let { " · $it" } ?: ""),
                TapeType.pixelSmall,
                scheme.ink2.tape,
                maxLines = 2,
            )

            StepperSlider(
                label = "LEVEL",
                fraction = ((pendingDb - LEVEL_DB_MIN) / (LEVEL_DB_MAX - LEVEL_DB_MIN)).coerceIn(0f, 1f),
                valueText = "%.0f dB".format(java.util.Locale.ROOT, pendingDb),
                fillColor = classColor,
                scheme = scheme,
                enabled = !busy,
                onFractionChange = { f -> pendingDb = LEVEL_DB_MIN + f * (LEVEL_DB_MAX - LEVEL_DB_MIN) },
                onFractionCommit = {
                    editPadMetadata { m -> m.update(slot) { p -> p.copy(level = dbToLevel(pendingDb)) } }
                },
            )
            StepperSlider(
                label = "PAN",
                fraction = pendingPan,
                valueText = panLabel(pendingPan),
                fillColor = classColor,
                scheme = scheme,
                enabled = !busy,
                onFractionChange = { f -> pendingPan = f },
                onFractionCommit = {
                    editPadMetadata { m -> m.update(slot) { p -> p.copy(pan = pendingPan) } }
                },
            )
            StepperSlider(
                label = "TUNE",
                fraction = (pendingTuneSemis + 12f) / 24f,
                valueText = tuneLabel(pendingTuneSemis),
                fillColor = classColor,
                scheme = scheme,
                enabled = !busy,
                onFractionChange = { f -> pendingTuneSemis = ((f * 24f) - 12f).roundToInt().coerceIn(-12, 12) },
                onFractionCommit = {
                    editPadMetadata { m -> m.update(slot) { p -> p.copy(tuneCoarse = pendingTuneSemis) } }
                },
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip("ONE-SHOT", pad.oneShot, classColor, scheme, enabled = !busy, modifier = Modifier.weight(1f)) {
                    editPadMetadata { m -> m.update(slot) { p -> p.copy(oneShot = !p.oneShot) } }
                }
                ToggleChip(
                    "CHOKE GRP",
                    pad.muteGroup != 0,
                    classColor,
                    scheme,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    editPadMetadata { m ->
                        m.update(slot) { p ->
                            val newGroup = if (p.muteGroup != 0) {
                                0
                            } else {
                                AutoPlace.muteGroupFor(p.drumClass).takeIf { it != 0 } ?: 1
                            }
                            p.copy(muteGroup = newGroup)
                        }
                    }
                }
                ToggleChip(
                    "SOFT HITS",
                    pad.velocityLayers.isNotEmpty(),
                    classColor,
                    scheme,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onToggle = ::onGhostsToggle,
                )
            }

            GroupBox(
                legend = PadSheetBoxes.Box.TREATMENT.legend,
                summary = boxes.getValue(PadSheetBoxes.Box.TREATMENT),
                open = openBoxKind == PadSheetBoxes.Box.TREATMENT,
                onToggle = { tapBox(PadSheetBoxes.Box.TREATMENT) },
                scheme = scheme,
            ) {
            // Which segment the user just tapped, so the card can say what it
            // is working on (September UAT, finding 14). Read only while
            // `busy` is true - see `applying` below - so an applyTreatment
            // that returns early without ever starting cannot leave a chip
            // claiming to be in flight.
            TreatmentCard(
                rows = PadSheet.ROWS,
                noneSegment = PadSheet.NONE,
                activeSegment = activeSegment,
                isNoneState = isNoneState,
                amountFraction = pendingAmt,
                amountText = "${(pendingAmt * 100).roundToInt()}%",
                padColor = classColor,
                scheme = scheme,
                busy = busy,
                applying = applyingSegment.takeIf { busy },
                onSegmentTap = { seg ->
                    if (seg == PadSheet.NONE) {
                        // No `applyingSegment` for NONE: the busy header reads
                        // "TREATMENT · <segment>…", and a restore out of the bin
                        // is a file copy, not the second-and-a-bit of DSP that
                        // header exists to explain. `busy` still greys the card.
                        unTreat()
                    } else {
                        applyingSegment = seg
                        applyTreatment(seg, pendingAmt)
                    }
                },
                onAmountChange = { f -> pendingAmt = (f * 20f).roundToInt() / 20f },
                // AMT re-runs the treatment at the new amount, which is the
                // same second-and-a-bit of work a chip tap starts - so it
                // records the segment too. Finding 14 is about the treatment
                // path, not only the taps that begin at a chip.
                onAmountCommit = {
                    activeSegment?.let { seg ->
                        applyingSegment = seg
                        applyTreatment(seg, pendingAmt)
                    }
                },
            )
            // The tape this pad's dust comes from — or would come from — and
            // the door to borrow another's.
            DustFromRow(
                tape = PadSheet.readDust(pad.recipe)?.tape ?: model?.kit?.let { DustPrints.tapeFor(it, pad) },
                open = dustFromOpen,
                tapes = dustFromTapes,
                scheme = scheme,
                busy = busy,
                onOpen = ::openDustFrom,
                onClose = { dustFromOpen = false },
                onPick = { file ->
                    dustFromOpen = false
                    applyDustFrom(file.name)
                },
            )
            // DO IT AGAIN: the recipe as a thing you can carry to another
            // pad. Dimmed, not disabled, when there's nothing to copy or
            // nothing copied yet - the toast explains, same convention as
            // SPLICE ▸ / STACK ▸ below.
            //
            // "COPY LAST TREATMENT" keeps its exact wording — it's quoted
            // verbatim inside `Copy.REPLAY_CLIPBOARD_EMPTY`
            // ("NOTHING COPIED YET. COPY LAST TREATMENT OFF A PAD FIRST."),
            // so shortening it here would desync the toast from the button
            // it's pointing at. PASTE's ▸ dropped instead (truncation
            // pass): `onPasteRecipe` replays the recipe on THIS pad right
            // here via `commitPadEditNow` — an in-place mutation, not a
            // navigation or a panel — so the "opens something" glyph never
            // applied; "·" is the same neutral separator ROULETTE/DRIFT use
            // above for the same reason.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(
                    "COPY LAST TREATMENT",
                    scheme,
                    enabled = !busy,
                    dimmed = pad.recipe == null,
                    modifier = Modifier.weight(1f),
                    onClick = ::onCopyRecipe,
                )
                ActionButton(
                    clipboard?.let { "PASTE · ${it.word}" } ?: "PASTE",
                    scheme,
                    enabled = !busy,
                    dimmed = clipboard == null,
                    modifier = Modifier.weight(1f),
                    onClick = ::onPasteRecipe,
                )
            }
            TapeText(
                clipboard?.let { "ON THE CLIPBOARD: ${it.word} FROM ${it.from}" } ?: Copy.REPLAY_LAST_ONLY,
                TapeType.pixelSmall,
                scheme.ink3.tape,
                Modifier.fillMaxWidth(),
                maxLines = 2,
            )
            }

            GroupBox(
                legend = PadSheetBoxes.Box.SHAPE.legend,
                summary = boxes.getValue(PadSheetBoxes.Box.SHAPE),
                open = openBoxKind == PadSheetBoxes.Box.SHAPE,
                onToggle = { tapBox(PadSheetBoxes.Box.SHAPE) },
                scheme = scheme,
            ) {
            ShapeCard(
                knobs = listOf(
                    ShapeKnob(
                        label = "ATTACK",
                        fraction = pendingAttack,
                        valueText = if (pad.attack == null) "OFF" else "%.0f ms".format(java.util.Locale.ROOT, PadShape.attackSeconds(pendingAttack) * 1000f),
                        onChange = { f -> pendingAttack = (f * 20f).roundToInt() / 20f },
                        onCommit = { editPadMetadata { m -> m.update(slot) { p -> p.copy(attack = pendingAttack.takeIf { it > 0f }) } } },
                    ),
                    ShapeKnob(
                        label = "DECAY",
                        fraction = pendingDecay,
                        valueText = if (pad.decay == null) "FULL" else "${(pendingDecay * 100).roundToInt()}%",
                        onChange = { f -> pendingDecay = ((f * 20f).roundToInt() / 20f).coerceAtLeast(0.05f) },
                        onCommit = { editPadMetadata { m -> m.update(slot) { p -> p.copy(decay = pendingDecay.takeIf { it < 1f }) } } },
                    ),
                    ShapeKnob(
                        label = "CUTOFF",
                        fraction = pendingCutoff,
                        valueText = if (pad.cutoff == null) "OPEN" else cutoffLabel(pendingCutoff),
                        onChange = { f -> pendingCutoff = (f * 20f).roundToInt() / 20f },
                        onCommit = { editPadMetadata { m -> m.update(slot) { p -> p.copy(cutoff = pendingCutoff.takeIf { it < 1f }) } } },
                    ),
                    ShapeKnob(
                        label = "RES",
                        fraction = pendingRes,
                        valueText = if (pad.resonance == null) "OFF" else "%.0f dB".format(java.util.Locale.ROOT, PadShape.resonanceDb(pendingRes)),
                        onChange = { f -> pendingRes = (f * 20f).roundToInt() / 20f },
                        onCommit = { editPadMetadata { m -> m.update(slot) { p -> p.copy(resonance = pendingRes.takeIf { it > 0f }) } } },
                    ),
                ),
                shaped = isShaped,
                padColor = classColor,
                scheme = scheme,
                busy = busy,
                onReset = {
                    editPadMetadata { m ->
                        m.update(slot) { p -> p.copy(attack = null, decay = null, cutoff = null, resonance = null) }
                    }
                },
            )
            }

            GroupBox(
                legend = PadSheetBoxes.Box.MUTATE.legend,
                summary = boxes.getValue(PadSheetBoxes.Box.MUTATE),
                open = openBoxKind == PadSheetBoxes.Box.MUTATE,
                onToggle = { tapBox(PadSheetBoxes.Box.MUTATE) },
                scheme = scheme,
            ) {
            MutateCard(
                modes = MutateSheet.MODES,
                mode = mutateMode,
                onMode = { mutateMode = it },
                partners = MutateSheet.partners(kit, slot).map { it.slot },
                partner = partner,
                onPartner = { partner = MutateSheet.Partner.Pad(it) },
                rooms = rooms.map { it.name },
                onRoom = { name -> rooms.firstOrNull { it.name == name }?.let { partner = Rooms.partner(it) } },
                otherKits = otherKits.map { it.name },
                pickedKit = pickedKit?.name,
                onPickKit = { name -> pickedKit = if (pickedKit?.name == name) null else otherKits.firstOrNull { it.name == name } },
                otherPads = otherPads,
                onOtherPad = { s -> pickedKit?.let { k -> partner = MutateSheet.Partner.Other(k.name, k.dir, s) } },
                onPickFile = { filePicker.launch(arrayOf("audio/*")) },
                onRoulette = ::onRoulette,
                onDrift = ::onDrift,
                knobLabel = mutateKnob?.label,
                knobFraction = pendingMutateKnob,
                knobText = mutateKnob?.let { MutateSheet.label(it, MutateSheet.value(it, pendingMutateKnob)) } ?: "",
                onKnobChange = { f -> pendingMutateKnob = (f * 40f).roundToInt() / 40f },
                mutated = MutateSheet.read(pad.recipe),
                canUndo = binDaysLeft != null,
                onMutate = ::onMutate,
                onUndo = ::onUnmutate,
                padColor = classColor,
                scheme = scheme,
                busy = busy,
            )
            }

            GroupBox(
                legend = PadSheetBoxes.Box.OUTSIDE.legend,
                summary = boxes.getValue(PadSheetBoxes.Box.OUTSIDE),
                open = openBoxKind == PadSheetBoxes.Box.OUTSIDE,
                onToggle = { tapBox(PadSheetBoxes.Box.OUTSIDE) },
                scheme = scheme,
                // The trip out: the strip goes lcd-alt while the reels turn.
                summaryColor = if (outsideStage != null) scheme.amber.tape else scheme.ink2.tape,
            ) {
        OutsideCard(
            moves = OutsideSheet.MOVES,
            move = outsideMove,
            onMove = { outsideMove = it },
            knobLabel = outsideKnob.label,
            knobFraction = pendingOutsideKnob,
            knobText = OutsideSheet.label(outsideKnob, OutsideSheet.value(outsideKnob, pendingOutsideKnob)),
            onKnobChange = { f -> pendingOutsideKnob = (f * 40f).roundToInt() / 40f },
            applied = OutsideSheet.read(pad.recipe),
            stage = outsideStage,
            canUndo = binDaysLeft != null,
            canKeep = measuredRoom != null,
            onSend = ::onOutside,
            onKeep = ::onKeepRoom,
            onUndo = ::onOutsideUndo,
            padColor = classColor,
            scheme = scheme,
            busy = busy,
        )
            }

            GroupBox(
                legend = PadSheetBoxes.Box.MAKE.legend,
                summary = boxes.getValue(PadSheetBoxes.Box.MAKE),
                open = openBoxKind == PadSheetBoxes.Box.MAKE,
                onToggle = { tapBox(PadSheetBoxes.Box.MAKE) },
                scheme = scheme,
            ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TapeText("PAD FROM ANYTHING · HOLD IT FOREVER", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            StepperSlider(
                label = "DEPTH",
                fraction = pendingDepth,
                valueText = PadMaker.depthLabel(PadMaker.DEPTH.value(pendingDepth)),
                fillColor = classColor,
                scheme = scheme,
                enabled = !busy,
                onFractionChange = { f -> pendingDepth = (f * 40f).roundToInt() / 40f },
                onFractionCommit = {},
            )
            StepperSlider(
                label = "BLOOM",
                fraction = pendingBloom,
                valueText = PadMaker.bloomLabel(PadMaker.BLOOM.value(pendingBloom)),
                fillColor = classColor,
                scheme = scheme,
                enabled = !busy,
                onFractionChange = { f -> pendingBloom = (f * 20f).roundToInt() / 20f },
                onFractionCommit = {},
            )
            ActionButton(
                if (busy) "DUBBING…" else "MAKE PAD ▸ INSTRUMENT",
                scheme,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = ::onMakePad,
            )
        }

        // DE-SAMPLE: the capture as a recipe - the nearest patch, distance told.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TapeText("DE-SAMPLE · THE NEAREST PATCH", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            val away = pad.source["desampled"]
            TapeText(
                if (away != null) "A PATCH NOW, $away AWAY" else "THE HIT MEASURED AGAINST EVERY THUMP",
                TapeType.pixelSmall,
                scheme.ink2.tape,
                maxLines = 1,
            )
            ActionButton(
                if (busy) "MEASURING…" else "MAKE SYNTH ▸ REPLACES THE WAV",
                scheme,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = ::onDesample,
            )
        }

            ActionButton(
                "GRAIN ▸",
                scheme,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = { onGrainField(slot) },
            )
            ActionButton(
                "MAKE INSTRUMENT",
                scheme,
                enabled = !busy,
                dimmed = pad.drumClass != DrumClass.TONAL,
                modifier = Modifier.fillMaxWidth(),
                onClick = ::onMakeInstrument,
            )
            }
            ActionButton("RE-TRIM ▸", scheme, enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = onNavigateTape)
            // Dimmed, not disabled, when there's no prior take yet - same
            // "still tappable, the destination explains why" convention as
            // MAKE INSTRUMENT above. `binDaysLeft` is already exactly "this
            // pad's file has at least one recoverable prior take," the
            // same signal `refreshPadAudio` computes for the undo buttons.
            ActionButton(
                "SPLICE ▸",
                scheme,
                enabled = !busy,
                dimmed = binDaysLeft == null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSplice(slot) },
            )
            // Same history, same dimming rule as SPLICE ▸ - plus dimmed
            // when the pad already has layers, since STACK wants a
            // single-sample pad to build on. Still tappable: the screen
            // says which of the two it is.
            ActionButton(
                "STACK ▸",
                scheme,
                enabled = !busy,
                dimmed = binDaysLeft == null || pad.velocityLayers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onStack(slot) },
            )
            DeleteButton(scheme, enabled = !busy, onClick = ::onEject)
        }

        // Pinned under the scroll: a 2dp rule, then the pad nav, so the
        // next pad is always one tap away whatever box is open.
        Box(Modifier.fillMaxWidth().height(2.dp).background(scheme.grayEdge.tape))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(
                prevSlot?.let { "◄ ${padTag(it)}" } ?: "◄",
                scheme,
                enabled = prevSlot != null,
                onClick = { prevSlot?.let(onSlotChange) },
            )
            // No swipe gesture is wired here — the buttons on either side are
            // the only way to move — so the middle cell says where you are
            // rather than promising a gesture that doesn't exist (law 3).
            val position = (idx + 1).takeIf { idx >= 0 }?.let { "$it OF ${assignedSlots.size}" } ?: ""
            TapeText(position, TapeType.pixelSmall, scheme.ink3.tape, Modifier.weight(1f), maxLines = 1)
            ActionButton(
                nextSlot?.let { "${padTag(it)} ►" } ?: "►",
                scheme,
                enabled = nextSlot != null,
                onClick = { nextSlot?.let(onSlotChange) },
            )
        }
    }
}

// ---------- fresh-kit writes (Law 5) ----------

/**
 * The one correct shape for a kit mutation issued by a screen whose own
 * [KitBuilderModel] was opened earlier, outside any lock, and is therefore
 * a stale snapshot relative to whatever else may have saved since (see
 * `ConventionTest`'s Law 5 KDoc — this is the fix for the
 * `KNOWN_STALE_SNAPSHOT` category, not a new pattern). Locks, opens a
 * FRESH model on [kitDir] — never a long-lived one held by the caller —
 * runs [block] against it, and saves once, but ONLY if [block] actually
 * left the fresh model [KitBuilderModel.dirty]. That last part is what
 * lets a caller's [block] implement "my target vanished/was reassigned
 * underneath me" by simply mutating nothing: this function reads that as
 * "nothing to save" on its own, rather than every call site having to
 * report it a second way.
 *
 * Returns the fresh model (so a caller that needs to keep using it — e.g.
 * to adopt it as the screen's new long-lived model — always gets one back)
 * paired with whether a save actually happened.
 */
private suspend fun withFreshKit(kitDir: File, block: (KitBuilderModel) -> Unit): Pair<KitBuilderModel, Boolean> {
    return withContext(Dispatchers.IO) {
        KitWrites.mutex.withLock {
            val fresh = KitBuilderModel.open(kitDir)
            block(fresh)
            val saved = fresh.dirty
            if (saved) fresh.save()
            fresh to saved
        }
    }
}

/**
 * The debounced metadata edit's own reapply, run inside [withFreshKit]'s
 * [block] by every metadata-flushing site in this file (the teardown
 * `DisposableEffect`, [requestBack], the debounce `LaunchedEffect`, and —
 * before their own structural mutation — [commitPadEditNow] and
 * [applySmear], which would otherwise lose it when they swap `model`).
 * One copy instead of five hand-written ones is the whole point: see this
 * file's own comment, and `ConventionTest`'s KDoc, on how a pattern
 * applied correctly at some sites and not at a sibling is exactly how this
 * codebase's bugs have shipped before.
 *
 * For every ([targetSlot], [stalePad]) pair, restamps [stalePad]'s nine
 * `editPadMetadata` fields onto [fresh]'s CURRENT pad at that slot — but
 * only when that pad's `sampleFile` still matches [stalePad]'s. A pad
 * that's vanished from disk, or whose slot now holds a DIFFERENT sound —
 * ejected, reassigned (GRAB, SEND TO PAD), moved, or rerolled (EVIL TWINS
 * clears bank B and re-adds fresh twins under new stems) by another screen
 * while this edit was pending — is skipped rather than stamped: comparing
 * `sampleFile`, not just null-ness, is the point, since a reassigned slot
 * is non-null and stamping this pad's LEVEL/PAN/TUNE/SHAPE onto somebody
 * else's sample would be the same class of silent corruption one level
 * down. Conjuring the old pad back would be its own phantom-pad bug, so
 * that one edit is dropped — the rest still apply.
 */
private fun reapplyPendingMetadataFields(fresh: KitBuilderModel, stalePads: Map<Int, KitPad?>) {
    for ((targetSlot, stalePad) in stalePads) {
        val freshPad = fresh.kit.pad(targetSlot)
        if (stalePad == null || freshPad == null || freshPad.sampleFile != stalePad.sampleFile) {
            continue
        }
        fresh.update(targetSlot) { p ->
            p.copy(
                level = stalePad.level,
                pan = stalePad.pan,
                tuneCoarse = stalePad.tuneCoarse,
                oneShot = stalePad.oneShot,
                muteGroup = stalePad.muteGroup,
                attack = stalePad.attack,
                decay = stalePad.decay,
                cutoff = stalePad.cutoff,
                resonance = stalePad.resonance,
            )
        }
    }
}

// ---------- provenance ----------

/**
 * A pad's origin as one phrase, read defensively from [KitPad.source] — the
 * map's keys vary by pipeline (`song`/`at` for a capture dig, `file` for a
 * CLI chop, `origin` for the app's own CHOP, `resampledFrom`/`importedFrom`/
 * `sculptedFrom`/`dissectedFrom`/`mergedFrom` for the rest), so this reads
 * in the same priority order `LinerNotes.kt` uses and falls back to the
 * bare sample file rather than showing nothing.
 */
private fun provenanceOrigin(source: Map<String, String>): String? = when {
    source["song"] != null -> "\"${source["song"]}\" @ ${source["at"] ?: "?"}"
    source["file"] != null -> source.getValue("file")
    // RE-TRIM's own key (Retrim.FILE_KEY): CHOP and INSTANT KIT name the
    // tape now, so a chopped pad reads its file rather than "from tape".
    source["tapeFile"] != null -> source.getValue("tapeFile")
    source["resampledFrom"] != null -> "resampled from ${source.getValue("resampledFrom")}"
    source["importedFrom"] != null -> "imported from ${source.getValue("importedFrom")}"
    source["sculptedFrom"] != null -> "sculpted from ${source.getValue("sculptedFrom")}"
    source["stretchedFrom"] != null ->
        "${if (source["mode"] == "freeze") "frozen" else "stretched"} from ${source.getValue("stretchedFrom")}"
    source["dissectedFrom"] != null -> "dissected from ${source.getValue("dissectedFrom")}"
    source["mergedFrom"] != null -> "merged from ${source.getValue("mergedFrom")}"
    source["origin"] == "chop" -> "chopped from tape"
    else -> null
}

/**
 * How the pad came to be, when a door made it from other pads: a twin
 * (`KitBuilderModel.TWIN_OF`, the bank-A pad it was dealt from) and/or a
 * bred child (`Breed`'s `bredFrom`, "Mother x Father"). Both doors copy
 * the parent's source keys, so [provenanceOrigin] alone would read a
 * twin as its parent's tape — this goes first on the line, so BREED and
 * REMIX BANK B are answered for on the one screen that inspects a pad.
 * Both stamps show when both are there (a bred kit whose parent had
 * twins carries a twin's `twinOf` under BREED's `bredFrom`): neither
 * derivation is the whole story alone.
 */
private fun lineage(source: Map<String, String>): List<String> = listOfNotNull(
    source[KitBuilderModel.TWIN_OF]?.let { "twin of $it" },
    source["bredFrom"]?.let { "bred from ${it.replace(" x ", " × ")}" },
)

private fun provenanceLine(pad: KitPad, snip: Snip?, binDaysLeft: Int?): String {
    val origin = provenanceOrigin(pad.source) ?: pad.sampleFile
    // The cut in the tape (`BASS 5.WAV @ 1.20–1.62s`, docs/RETRIM.md §5):
    // the one place the numbers show, and what says where RE-TRIM ▸ will
    // land before it is tapped. Frames sit at the tape's rate, which is
    // the pad's own — neither CHOP nor BACK ONTO resamples.
    val cut = Retrim.cutOf(pad)
    val parts = mutableListOf<String>()
    parts += lineage(pad.source)
    // DUST names the tape it borrowed from, which may not be the pad's own.
    PadSheet.readDust(pad.recipe)?.let { parts += "dusted from ${it.tape}" }
    parts += if (cut != null && snip != null) "$origin @ ${cut.label(snip.sampleRate)}" else origin
    snip?.let { parts += "%.0f ms".format(java.util.Locale.ROOT, it.durationSeconds * 1000f) }
    binDaysLeft?.let { parts += "original in bin, ${it}d left" }
    return parts.joinToString(" · ")
}

/**
 * SMEAR's own recipe shape, `{"verb": "smear", "amount": x}` — the
 * `Mutate.morph` idiom, not [PadSheet.read]'s `{"era"/"treatment"/"keyed",
 * "amount"}` shapes. Same defensive contract: anything else (an era, a
 * Treatments FX chain, a Mutate recipe) reads as no SMEAR active. Read
 * ahead of [PadSheet.read] wherever both are consulted, since SMEAR is
 * the one row-one segment [PadSheet.read] cannot see.
 */
private fun readSmearRecipe(recipe: JsonValue.Obj?): Float? = PadSheet.readSmear(recipe)

// ---------- level <-> dB ----------

/** KitPad's own unity reference — see `SfzWriter.db()`/`DecentSamplerWriter.db()`, which this mirrors. */
private const val LEVEL_UNITY = 0.707946f
private const val LEVEL_DB_MIN = -24f
private const val LEVEL_DB_MAX = 6f

private fun levelToDb(level: Float): Float =
    if (level <= 0f) LEVEL_DB_MIN else (20f * kotlin.math.log10(level / LEVEL_UNITY)).coerceIn(LEVEL_DB_MIN, LEVEL_DB_MAX)

/**
 * dB back to a pad level. `level` is hard-capped at 1f ([KitPad] requires
 * 0f..1f), which caps real gain at ~+3 dB re unity — the slider still
 * spans the handoff's full −24..+6 range, it just tops out a few dB short
 * of the nominal +6, the same ceiling every other pad-level UI in this
 * codebase lives under.
 */
private fun dbToLevel(db: Float): Float = (LEVEL_UNITY * 10f.pow(db / 20f)).coerceIn(0f, 1f)

private fun panLabel(pan: Float): String {
    val st = ((pan - 0.5f) * 100f).roundToInt()
    return when {
        st == 0 -> "C"
        st < 0 -> "L${-st}"
        else -> "R$st"
    }
}

private fun tuneLabel(semis: Int): String = when {
    semis > 0 -> "+$semis st"
    else -> "$semis st"
}

// One rule, one home ([PadBanks]): this said "A%02d".format(slot) until
// the September UAT's finding 11 made bank B reachable, at which point a
// pad on slot 17 would have been titled A17 on this screen.
private fun padTag(slot: Int): String = PadBanks.tag(slot)

/** 632 Hz / 12.6k — six characters at most, the value column's width. */
private fun cutoffLabel(cutoff: Float): String {
    val hz = PadShape.cutoffHz(cutoff)
    return if (hz >= 1000f) "%.1fk".format(java.util.Locale.ROOT, hz / 1000f) else "%.0f Hz".format(java.util.Locale.ROOT, hz)
}

// ---------- header ----------

@Composable
private fun PadSheetHeader(
    slot: Int,
    pad: KitPad,
    classColor: Color,
    scheme: Scheme,
    busy: Boolean,
    onBack: () -> Unit,
    onHit: () -> Unit,
    onBefore: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .lcdPanel(scheme)
            .background(classColor.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .border(2.dp, classColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `onBack` (`requestBack`) silently no-ops while busy rather than
        // interrupting an in-flight save — dimmed + untappable here says
        // so instead of the tap doing nothing with no feedback at all.
        HeaderChip("◄ KIT", scheme, Modifier.width(64.dp), enabled = !busy, onClick = onBack)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TapeText("PAD ${padTag(slot)}", TapeType.lcdHeader, scheme.lcdInk.tape)
            Box(
                Modifier.background(classColor.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                TapeText(ChopReviewModel.chipName(pad.drumClass), TapeType.pixelSmall, Schemes.padLabelInk(scheme, pad.drumClass).tape)
            }
        }
        Spacer(Modifier.weight(1f))
        // ◀ BEFORE sits against ▶ HIT so an A/B is two taps in one place.
        // Dimmed while busy: a treatment mid-write is about to swap the
        // model, and the take it would play is about to change.
        onBefore?.let {
            HeaderChip("◀ BEFORE", scheme, Modifier.width(92.dp), enabled = !busy, onClick = it)
            Spacer(Modifier.width(4.dp))
        }
        HeaderChip("▶ HIT", scheme, Modifier.width(64.dp), onClick = onHit)
    }
}

@Composable
private fun HeaderChip(
    label: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(3.dp))
            // `enabled` goes through `tapeClick`, not around it: a dimmed
            // chip stays in the semantics tree, so TalkBack finds ◀ BEFORE
            // (and ◄ KIT) during the same busy spell sighted users see it.
            .tapeClick(label = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (enabled) scheme.ink.tape else scheme.ink3.tape)
    }
}

// ---------- waveform ----------

private const val WAVEFORM_BARS = 44

/** 44 bars from [PeaksPyramid], real magnitude shaped by an exp-decay envelope in pad colour — loops decay slower (W12). */
@Composable
private fun WaveformLcd(snip: Snip?, drumClass: DrumClass, color: Color, scheme: Scheme, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(64.dp).lcdPanel(scheme)) {
        if (snip == null || snip.frameCount <= 0) return@Box
        val peaks = remember(snip) { PeaksPyramid.fromSnip(snip) }
        // LOOP material shouldn't visually vanish by bar 10 the way a
        // one-shot's tail does — a slower per-bar decay constant.
        val decayRate = if (drumClass == DrumClass.LOOP) 0.5f else 2.2f
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val barGap = 1.dp.toPx()
            val barWidth = ((size.width - barGap * (WAVEFORM_BARS - 1)) / WAVEFORM_BARS).coerceAtLeast(1f)
            val halfH = size.height / 2f
            val columns = peaks.columns(0, snip.frameCount, WAVEFORM_BARS)
            for ((i, col) in columns.withIndex()) {
                val mag = max(abs(col.max), abs(col.min)).coerceIn(0f, 1f)
                val envelope = exp(-i * decayRate / WAVEFORM_BARS)
                val h = (mag * envelope * halfH).coerceAtLeast(1f)
                drawRect(
                    color = color,
                    topLeft = Offset(i * (barWidth + barGap), halfH - h),
                    size = Size(barWidth, h * 2f),
                )
            }
        }
    }
}

// ---------- stepper-slider ----------

@Composable
internal fun StepperSlider(
    label: String,
    fraction: Float,
    valueText: String,
    fillColor: Color,
    scheme: Scheme,
    enabled: Boolean,
    onFractionChange: (Float) -> Unit,
    onFractionCommit: () -> Unit,
) {
    // The gesture loop below runs once, keyed on Unit — rememberUpdatedState
    // is what keeps it calling this recomposition's callbacks instead of
    // whichever ones were captured the first time it started.
    val currentOnChange by rememberUpdatedState(onFractionChange)
    val currentOnCommit by rememberUpdatedState(onFractionCommit)

    Row(
        Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapeText(label, TapeType.pixelSmall, scheme.ink2.tape, Modifier.width(44.dp))
        Box(
            Modifier
                .weight(1f)
                .height(Layout.MIN_HIT_TARGET.dp)
                .clip(RoundedCornerShape(4.dp))
                .sunkenField(scheme)
                // Canvas-drawn fill, invisible to the a11y tree by
                // default (audit finding 4) — this one composable backs
                // most of PAD SHEET's numeric controls (pitch, gain,
                // decay, ...), so fixing it here is the single-component
                // win the audit calls out as the pattern to follow.
                // progressBarRangeInfo + setProgress give TalkBack's
                // adjust gesture a real target, the "ideally adjustable"
                // half of finding 4, not just its label+state floor.
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                    if (enabled) {
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                        setProgress { target ->
                            val clamped = target.coerceIn(0f, 1f)
                            currentOnChange(clamped)
                            currentOnCommit()
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .let { base ->
                    if (!enabled) return@let base
                    base.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun fractionAt(x: Float) = (x / size.width.toFloat()).coerceIn(0f, 1f)
                            currentOnChange(fractionAt(down.position.x))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    currentOnChange(fractionAt(change.position.x))
                                    currentOnCommit()
                                    change.consume()
                                    break
                                }
                                currentOnChange(fractionAt(change.position.x))
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(fillColor.copy(alpha = if (enabled) 0.85f else 0.35f)),
            )
        }
        TapeText(valueText, TapeType.lcdSmall, scheme.ink.tape, Modifier.width(56.dp))
    }
}

// ---------- toggles ----------

@Composable
private fun ToggleChip(
    label: String,
    engaged: Boolean,
    color: Color,
    scheme: Scheme,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme, fill = if (engaged) color.copy(alpha = 0.85f) else null)
            // Always clickable, `enabled` forwarded rather than dropped: a
            // screen reader is told this control is temporarily unavailable
            // instead of it silently vanishing from the tree (accessibility
            // audit finding 12 — see ActionButton, above in this file).
            .tapeClick(label = null, enabled = enabled, onClick = onToggle)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            label,
            TapeType.pixel,
            if (!enabled) scheme.ink3.tape else if (engaged) scheme.titleInk.tape else scheme.ink2.tape,
            maxLines = 1,
        )
    }
}

// ---------- treatment card ----------

@Composable
private fun TreatmentCard(
    rows: List<List<String>>,
    noneSegment: String,
    activeSegment: String?,
    isNoneState: Boolean,
    amountFraction: Float,
    amountText: String,
    padColor: Color,
    scheme: Scheme,
    busy: Boolean,
    /** The segment being applied right now, or null when nothing is in flight. */
    applying: String?,
    onSegmentTap: (String) -> Unit,
    onAmountChange: (Float) -> Unit,
    onAmountCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The card says when it is working, and on what (September UAT,
        // finding 14): ETERNAL takes 1.77 s on a desktop JVM and longer on a
        // phone, and chips going quietly untappable read as a dead screen
        // rather than a busy one.
        TapeText(
            if (applying != null) Copy.treatmentBusy(PadSheet.displayLabel(applying)) else "TREATMENT",
            TapeType.pixelSmall,
            if (applying != null) scheme.amber.tape else scheme.ink3.tape,
            maxLines = 1,
        )
        // Row one is the eras, the rest the rack's characters (PadSheet.ROWS);
        // one segment lights across every row, since a pad carries one recipe.
        val widest = rows.maxOf { it.size }
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (seg in row) {
                    val selected = if (seg == noneSegment) isNoneState else seg == activeSegment
                    // NONE takes the treatment back off (September UAT,
                    // finding 13). Which chips are live is PadSheet's rule,
                    // not this card's, so it can be asserted without a phone.
                    val tappable = !busy && PadSheet.tappable(seg, isNoneState)
                    // The one being applied wears the pad's colour at half
                    // strength - lit enough to find, not so lit it reads as
                    // already done.
                    val working = seg == applying
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(
                                scheme,
                                fill = when {
                                    working -> padColor.copy(alpha = 0.5f)
                                    selected -> padColor.copy(alpha = 0.85f)
                                    else -> null
                                },
                            )
                            // Always clickable, `tappable` forwarded rather
                            // than dropped so a non-tappable segment still
                            // announces itself instead of vanishing from the
                            // accessibility tree (finding 12).
                            .tapeClick(label = null, enabled = tappable) { onSegmentTap(seg) }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(
                            PadSheet.displayLabel(seg),
                            TapeType.pixel,
                            when {
                                working || selected -> scheme.titleInk.tape
                                // Everything else steps back while the work
                                // runs, so "you cannot tap this yet" is
                                // visible rather than merely true.
                                busy -> scheme.ink3.tape
                                else -> scheme.ink2.tape
                            },
                        )
                    }
                }
                // A short row keeps the same chip width as a full one.
                repeat(widest - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        StepperSlider(
            label = "AMT",
            fraction = amountFraction,
            valueText = amountText,
            fillColor = padColor,
            scheme = scheme,
            enabled = !busy && activeSegment != null,
            onFractionChange = onAmountChange,
            onFractionCommit = onAmountCommit,
        )
    }
}

/** DUST FROM ▸ shows this many of the shelf's tapes, newest first; the bench is a column, not a browser. */
private const val DUST_FROM_SHOWN = 24

/**
 * DUST FROM ▸: the tape this pad's dust comes from, or would come from
 * ([tape], lit in the list so the label and the list agree), and the
 * shelf's tapes inline when [open] — the bench's own convention over a
 * dialog, since a pick is one tap and the column already scrolls.
 */
@Composable
private fun DustFromRow(
    tape: String?,
    open: Boolean,
    tapes: List<File>,
    scheme: Scheme,
    busy: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onPick: (File) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(
                if (open) "DUST FROM ▾" else "DUST FROM ▸",
                scheme,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = { if (open) onClose() else onOpen() },
            )
            TapeText(
                tape?.let { "FROM ${SnipStore.displayName(File(it))}" } ?: "NO TAPE YET",
                TapeType.pixelSmall,
                scheme.ink3.tape,
                Modifier.weight(1f),
                maxLines = 1,
            )
        }
        if (open) {
            TapeText(Copy.DUST_FROM_PICK, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
            for (file in tapes.take(DUST_FROM_SHOWN)) {
                ActionButton(
                    SnipStore.displayName(file),
                    scheme,
                    enabled = !busy,
                    lit = file.name == tape,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPick(file) },
                )
            }
            if (tapes.size > DUST_FROM_SHOWN) {
                TapeText("NEWEST $DUST_FROM_SHOWN OF ${tapes.size}.", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            }
        }
    }
}

// ---------- shape card ----------

/** One SHAPE stepper: its label, where it sits, what it reads, and the two callbacks StepperSlider wants. */
private class ShapeKnob(
    val label: String,
    val fraction: Float,
    val valueText: String,
    val onChange: (Float) -> Unit,
    val onCommit: () -> Unit,
)

/**
 * SHAPE: attack, decay, cutoff and resonance as *metadata* — the audio on
 * disk never changes; the exported programs' own fields carry the shape
 * and the hardware renders it (GG1). The value column says OFF / FULL /
 * OPEN while a field is still the format's own default, and RESET puts
 * all four back there. HIT auditions the approximation, so a tighten is
 * heard before the card.
 */
@Composable
private fun ShapeCard(
    knobs: List<ShapeKnob>,
    shaped: Boolean,
    padColor: Color,
    scheme: Scheme,
    busy: Boolean,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TapeText("SHAPE · CARD RENDERS IT", TapeType.pixelSmall, scheme.ink3.tape, Modifier.weight(1f), maxLines = 1)
            ActionButton("RESET", scheme, enabled = !busy && shaped, onClick = onReset)
        }
        for (knob in knobs) {
            StepperSlider(
                label = knob.label,
                fraction = knob.fraction,
                valueText = knob.valueText,
                fillColor = padColor,
                scheme = scheme,
                enabled = !busy,
                onFractionChange = knob.onChange,
                onFractionCommit = knob.onCommit,
            )
        }
    }
}

// ---------- mutate card ----------

/**
 * MUTATE: one hit from two parents. A move (STACK · SPLICE · SPLIT ·
 * MORPH), a partner — a pad on this kit from the mini grid, or the deal
 * ROULETTE spins off the shelf — the move's one knob when it has one,
 * then MUTATE. The line under the title says what the pad already is
 * ("SPLICE: Kit:A02") so a mutated pad never reads as an original; UNDO
 * pulls the pre-mutation sound back out of the bin. Everything behind it
 * is `MutateSheet` over the CLI's own `Mutate` — same recipe, same
 * provenance, same bin.
 */
@Composable
private fun MutateCard(
    modes: List<String>,
    mode: String,
    onMode: (String) -> Unit,
    partners: List<Int>,
    partner: MutateSheet.Partner?,
    onPartner: (Int) -> Unit,
    rooms: List<String>,
    onRoom: (String) -> Unit,
    otherKits: List<String>,
    pickedKit: String?,
    onPickKit: (String) -> Unit,
    otherPads: List<Int>,
    onOtherPad: (Int) -> Unit,
    onPickFile: () -> Unit,
    onRoulette: () -> Unit,
    onDrift: () -> Unit,
    knobLabel: String?,
    knobFraction: Float,
    knobText: String,
    onKnobChange: (Float) -> Unit,
    mutated: MutateSheet.Applied?,
    canUndo: Boolean,
    onMutate: () -> Unit,
    onUndo: () -> Unit,
    padColor: Color,
    scheme: Scheme,
    busy: Boolean,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TapeText("MUTATE · ONE HIT FROM TWO", TapeType.pixelSmall, scheme.ink3.tape, Modifier.weight(1f), maxLines = 1)
            ActionButton("UNDO", scheme, enabled = !busy && mutated != null && canUndo, onClick = onUndo)
        }
        TapeText(
            mutated?.let { "${it.word}: ${it.parents.joinToString(", ")}" } ?: "PICK A MOVE AND A PARENT",
            TapeType.pixelSmall,
            scheme.ink2.tape,
            maxLines = 1,
        )

        // The move: three to a row, so TRANSPLANT gets the width its word needs.
        for (row in modes.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in row) {
                    val selected = m == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme, fill = if (selected) padColor.copy(alpha = 0.85f) else null)
                            // Always clickable, `!busy` forwarded rather
                            // than dropped (accessibility audit finding 12).
                            .tapeClick(label = null, enabled = !busy) { onMode(m) }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(m, TapeType.pixel, if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        // The partner: this kit's other pads, four to a row, then the crate.
        val chosenSlot = (partner as? MutateSheet.Partner.Pad)?.slot
        for (row in partners.chunked(4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in row) {
                    val selected = p == chosenSlot
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme, fill = if (selected) padColor.copy(alpha = 0.85f) else null)
                            // Always clickable, `!busy` forwarded rather
                            // than dropped (accessibility audit finding 12).
                            .tapeClick(label = null, enabled = !busy) { onPartner(p) }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(
                            MutateSheet.padTag(p),
                            TapeType.pixel,
                            if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape,
                        )
                    }
                }
                // A short last row keeps the same chip width as a full one.
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        // The rooms on the shelf, two to a row (their names are words, not
        // tags); the row is absent until OUTSIDE has kept one.
        if (rooms.isNotEmpty()) {
            TapeText("ROOMS ON THE SHELF · ROOM PLAYS THE PAD INSIDE ONE", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            val chosenRoom = (partner as? MutateSheet.Partner.Room)?.name
            for (row in rooms.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in row) {
                        val selected = r == chosenRoom
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                                .raisedBevel(scheme, fill = if (selected) padColor.copy(alpha = 0.85f) else null)
                                // Always clickable, `!busy` forwarded
                                // rather than dropped (accessibility audit
                                // finding 12).
                                .tapeClick(label = null, enabled = !busy) { onRoom(r) }
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TapeText(
                                r,
                                TapeType.pixel,
                                if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape,
                                maxLines = 1,
                            )
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // Another kit, picked: its name among the shelf's kits (two to a row),
        // then its pads (four to a row) - the crate with intent, where
        // ROULETTE below is the crate by chance. Absent on a one-kit shelf.
        if (otherKits.isNotEmpty()) {
            TapeText("ANOTHER KIT · PICK ITS PAD", TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
            for (row in otherKits.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in row) {
                        val selected = k == pickedKit
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                                .raisedBevel(scheme, fill = if (selected) padColor.copy(alpha = 0.85f) else null)
                                // Always clickable, `!busy` forwarded
                                // rather than dropped (accessibility audit
                                // finding 12).
                                .tapeClick(label = null, enabled = !busy) { onPickKit(k) }
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TapeText(
                                k,
                                TapeType.pixel,
                                if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape,
                                maxLines = 1,
                            )
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (pickedKit != null) {
                val other = partner as? MutateSheet.Partner.Other
                for (row in otherPads.chunked(4)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (p in row) {
                            val selected = other != null && other.kitName == pickedKit && other.slot == p
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                                    .raisedBevel(scheme, fill = if (selected) padColor.copy(alpha = 0.85f) else null)
                                    // Always clickable, `!busy` forwarded
                                    // rather than dropped (accessibility
                                    // audit finding 12).
                                    .tapeClick(label = null, enabled = !busy) { onOtherPad(p) }
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                TapeText(
                                    MutateSheet.padTag(p),
                                    TapeType.pixel,
                                    if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape,
                                )
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        // A file off the phone, as the CLI takes `--with hit.wav`: the picker
        // opens on any audio; once one is held the button reads its name.
        val held = partner as? MutateSheet.Partner.Wav
        ActionButton(
            held?.let { "A FILE ▸ ${it.label.uppercase(java.util.Locale.ROOT)}" } ?: "A FILE ▸ PICK ONE OFF THE PHONE",
            scheme,
            enabled = !busy,
            dimmed = held == null,
            modifier = Modifier.fillMaxWidth(),
            onClick = onPickFile,
        )
        val deal = partner as? MutateSheet.Partner.Deal
        // Both labels were 29 characters at rest ("ROULETTE ▸ LET THE CRATE
        // DEAL" / "DRIFT ▸ DEALS & SAVES A BLEND"), so ROULETTE's old
        // weight(2f) against DRIFT's weight(1f) gave the wider share to no
        // more text — backwards, not proportional (truncation pass).
        // Equalizing the weight wasn't enough on its own — confirmed by
        // screenshot inside this MUTATE box's own 10dp side padding
        // (`GroupBox`'s content `Column`), both halves still ellipsized —
        // so both are shortened too: "LET THE" and the article "A" were
        // pure filler around the words that carry meaning (CRATE DEAL,
        // DEALS & SAVES). Neither keeps its ▸: `onRoulette` deals a
        // partner and `onDrift` deals AND mutates, both right here on this
        // card with a toast, never navigating or opening a panel — the
        // same "no ▸" rule RESET/UNDO on this same screen already follow.
        // "·" replaces it, same neutral separator "REMIX BANK B · REROLL"
        // (`KitScreen.kt`) uses for the same reason.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionButton(
                deal?.let { "ROULETTE · ${it.label}" } ?: "ROULETTE · CRATE DEAL",
                scheme,
                enabled = !busy,
                dimmed = deal == null,
                modifier = Modifier.weight(1f),
                onClick = onRoulette,
            )
            // DRIFT: the deal and the morph in one tap, MIX how far.
            // "SAVES" stays — it's the one word that says DRIFT commits
            // the blend, unlike ROULETTE's preview-only deal — but the
            // article "A" and "BLEND" (already this GroupBox's own legend,
            // "MUTATE · ONE HIT FROM TWO", right above) don't need to be
            // said again in a label that must also fit half this row.
            ActionButton("DRIFT · DEALS & SAVES", scheme, enabled = !busy, modifier = Modifier.weight(1f), onClick = onDrift)
        }

        // The move's knob, when it has one; STACK's row stays so the card never jumps.
        StepperSlider(
            label = knobLabel ?: "—",
            fraction = if (knobLabel == null) 0f else knobFraction,
            valueText = knobText,
            fillColor = padColor,
            scheme = scheme,
            enabled = !busy && knobLabel != null,
            onFractionChange = onKnobChange,
            onFractionCommit = {},
        )

        ActionButton(
            "MUTATE ▸",
            scheme,
            enabled = !busy && partner != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = onMutate,
        )
    }
}

// ---------- outside card ----------

/**
 * OUTSIDE: the world as an effect. Two moves (REAMP sends the pad, ROOM
 * sends the sweep), the move's one knob, a status line reading what the
 * pad carries (the move, how late it came back, how surely it was found,
 * a polarity flip if there was one), and SEND — whose label is the trip's
 * own stage while one is out, so the button says LISTENING… and SENDING…
 * rather than going dead. KEEP ROOM puts the last ROOM trip's measured
 * room on the shelf for any pad; it dims until a ROOM trip has measured one.
 */
@Composable
private fun OutsideCard(
    moves: List<String>,
    move: String,
    onMove: (String) -> Unit,
    knobLabel: String,
    knobFraction: Float,
    knobText: String,
    onKnobChange: (Float) -> Unit,
    applied: OutsideSheet.Applied?,
    stage: String?,
    canUndo: Boolean,
    canKeep: Boolean,
    onSend: () -> Unit,
    onKeep: () -> Unit,
    onUndo: () -> Unit,
    padColor: Color,
    scheme: Scheme,
    busy: Boolean,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TapeText("OUTSIDE · THE WORLD AS AN EFFECT", TapeType.pixelSmall, scheme.ink3.tape, Modifier.weight(1f), maxLines = 1)
            ActionButton("UNDO", scheme, enabled = !busy && applied != null && canUndo, onClick = onUndo)
        }
        if (stage != null) {
            // The trip is out: the reels turn on an LCD strip beside the
            // stage, so the wait is alive rather than a dead button.
            ReelsStrip(stage, scheme)
        } else if (applied != null) {
            // Measured: a number the ear is meant to trust, so it reads on
            // an LCD like every other readout — the same strip the reels
            // use, at rest.
            Box(
                Modifier.fillMaxWidth().height(28.dp).lcdPanel(scheme).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TapeText(OutsideSheet.statusLine(applied), TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 1)
            }
        } else {
            TapeText(
                if (move == OutsideSheet.Move.ROOM.name) "A SWEEP GOES OUT, THE ROOM COMES BACK AS THE PAD'S ROOM" else "THE PAD GOES OUT THE JACK, WHAT COMES BACK IS THE PAD",
                TapeType.pixelSmall,
                scheme.ink2.tape,
                maxLines = 1,
            )
        }

        // The move: two chips, half the width each.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in moves) {
                val selected = m == move
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme, fill = if (selected) padColor.copy(alpha = 0.85f) else null)
                        // Always clickable, `!busy` forwarded rather than
                        // dropped (accessibility audit finding 12).
                        .tapeClick(label = null, enabled = !busy) { onMove(m) }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(m, TapeType.pixel, if (busy) scheme.ink3.tape else if (selected) scheme.titleInk.tape else scheme.ink2.tape)
                }
            }
        }

        StepperSlider(
            label = knobLabel,
            fraction = knobFraction,
            valueText = knobText,
            fillColor = padColor,
            scheme = scheme,
            enabled = !busy,
            onFractionChange = onKnobChange,
            onFractionCommit = {},
        )

        ActionButton(
            stage ?: "SEND ▸ SPEAKER OUT, MIC BACK IN",
            scheme,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onSend,
        )
        // The room the last ROOM trip measured, onto the shelf for any pad.
        // The row stays, dimmed, so the card never jumps when a room lands.
        ActionButton(
            "KEEP ROOM ▸ ON THE SHELF, FOR ANY PAD",
            scheme,
            enabled = !busy && canKeep,
            dimmed = !canKeep,
            lit = canKeep,
            modifier = Modifier.fillMaxWidth(),
            onClick = onKeep,
        )
    }
}

// ---------- action row ----------

@Composable
internal fun ActionButton(
    label: String,
    scheme: Scheme,
    enabled: Boolean,
    dimmed: Boolean = false,
    /**
     * Lit in the LCD's second colour when [enabled] too — a state that
     * has to announce itself (KEEP ROOM the instant a trip measures a
     * room), not merely stop being dimmed: an amber rim over the ordinary
     * bevel, [PrimaryAction]'s own dress on a working-surface button.
     * Busy, or not yet earned, and the plain bevel and ink3 return.
     */
    lit: Boolean = false,
    /**
     * Override for [label] as the accessible name — for the rare button
     * whose visible glyph is too short/acronym-shaped to trust TalkBack
     * to read as a word (e.g. SplitScreen's "M"/"S" mute/solo chips).
     * `null` (the default, and every call site but those two) lets
     * [label] serve as its own name via `tapeClick`'s merge.
     */
    accessibilityLabel: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val litNow = lit && enabled
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .let { if (litNow) it.border(2.dp, scheme.amber.tape, RoundedCornerShape(4.dp)) else it }
            // Always clickable, `enabled` forwarded rather than dropped:
            // a screen reader is told this control is temporarily
            // unavailable instead of it silently vanishing from the tree
            // (accessibility audit finding 12).
            .tapeClick(label = accessibilityLabel, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            label,
            TapeType.pixel,
            if (!enabled) scheme.ink3.tape else if (litNow) scheme.amber.tape else if (dimmed) scheme.ink2.tape else scheme.ink.tape,
            maxLines = 1,
        )
    }
}

// THE ONLY NON-SCHEME COLOURS IN THE DESIGN — BIN red is deliberately
// constant across every scheme (HANDOFF.md X2 / TAKES+BIN), so a delete
// action reads as "red" even in a scheme with no red anywhere else in it.
// The glow half moved to Schemes.BIN_RED_GLOW / theme.BinRedGlow — a
// single tuned token instead of a duplicated literal (accessibility audit
// finding 5); the border half is untouched (a 3:1, non-text UI element,
// not the failure that was found).
private val BIN_RED_BORDER = Color(0xFF6A2020)

/**
 * Pad delete → bin. Named DeleteButton, not EjectButton — EJECT already
 * means "stop listening" on the shelf's ArmControl; this button destroys
 * (into a 30-day bin) instead, and the app is worse off with one word
 * covering both.
 */
@Composable
private fun DeleteButton(scheme: Scheme, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            // The dark LCD surface, same as every other dark button fill in
            // this scheme (see PrimaryAction) — only the rim is the fixed
            // bin-red; the fill still answers to the scheme.
            .background(scheme.lcd.tape, RoundedCornerShape(4.dp))
            .border(2.dp, BIN_RED_BORDER, RoundedCornerShape(4.dp))
            // enabled forwarded, not dropped — see ActionButton's own note.
            .tapeClick(label = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The glow was fixed regardless of `enabled` — a dead DELETE button
        // that still glows red reads as live. Falling back to `ink3` (not a
        // dimmed BinRedGlow) matches the convention already used by every
        // sibling BIN-red button (EmptyBinButton × 3, EmptyRoomsBinButton):
        // BinRedGlow at reduced alpha risks failing contrast against the
        // dark LCD fill, where ink3 is a scheme token already tuned for it.
        TapeText("DELETE → BIN", TapeType.pixel, if (enabled) BinRedGlow else scheme.ink3.tape)
    }
}
