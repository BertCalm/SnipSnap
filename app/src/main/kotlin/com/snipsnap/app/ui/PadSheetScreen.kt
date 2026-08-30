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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.shell.ChopReviewModel
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadSheet
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
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
    onKitUpdated: (com.snipsnap.kit.Kit) -> Unit,
    appScope: CoroutineScope,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    var model by remember(entry.dir) { mutableStateOf<KitBuilderModel?>(null) }
    var loadFailed by remember(entry.dir) { mutableStateOf(false) }
    LaunchedEffect(entry.dir) {
        loadFailed = false
        val opened = withContext(Dispatchers.IO) { runCatching { KitBuilderModel.open(entry.dir) }.getOrNull() }
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
    // Backgrounding mid-audition must stop the voice, not wait for this
    // composable to next leave composition (see ChopScreen's own fix).
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

    /** Re-decode this pad's current audio and its bin status — the one door every write refreshes through. */
    suspend fun refreshPadAudio(m: KitBuilderModel) {
        val p = m.kit.pad(slot)
        if (p == null) {
            snip = null
            binDaysLeft = null
            return
        }
        val (loadedSnip, daysLeft) = withContext(Dispatchers.IO) {
            val s = runCatching { Cleanup.toMono(WavReader.read(File(entry.dir, p.sampleFile))) }.getOrNull()
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
    }

    LaunchedEffect(model, slot) { refreshPadAudio(builtModel) }

    fun audition(target: Snip, level: Float) {
        // Release synchronously at the swap site — a composition-scoped
        // coroutine can't guarantee the previous voice actually stopped
        // before this one starts (ChopScreen's `audition()` comment).
        voice?.release()
        val gained = if (level == 1f) target.samples else FloatArray(target.samples.size) { target.samples[it] * level }
        val v = TapeVoice(gained, target.sampleRate)
        voice = v
        v.start(0)
    }

    fun failure(action: String, e: Exception) {
        onToast("$action FAILED: ${e.message ?: e.javaClass.simpleName}")
    }

    // `save()` is deliberately NOT called per stepper nudge: see
    // `editPadMetadata`'s KDoc just below for why, and this counter for
    // how the actual disk write gets debounced instead.
    var pendingMetadataSave by remember(model) { mutableIntStateOf(0) }

    /**
     * Metadata edits — LEVEL/PAN/TUNE/ONE-SHOT/CHOKE — never touch a WAV,
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
        if (!m.dirty) return@LaunchedEffect
        busy = true
        try {
            withContext(Dispatchers.IO) { m.save() }
            onKitUpdated(m.kit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failure("SAVE", e)
        } finally {
            busy = false
        }
    }

    /** Audio-rewriting ops — TREATMENT/GHOSTS/EJECT — always earn a real save+take: the WAV already changed on disk. */
    fun commitPadEditNow(action: String, onSuccess: (() -> Unit)? = null, mutate: (KitBuilderModel) -> Unit) {
        if (busy) return
        val m = model ?: return
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { mutate(m); m.save() }
                revision++
                onKitUpdated(m.kit)
                refreshPadAudio(m)
                onSuccess?.invoke()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure(action, e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * TREATMENT: picking a segment or moving AMT. `eraPad` always reads the
     * *current on-disk* audio, ages it, and bins whatever was there — so
     * re-applying onto an already-treated pad (moving AMT, or switching
     * segments) would stack onto the aged audio rather than replacing it.
     * When the pad already carries an era recipe, undo back to it first —
     * but only when the bin can restore *every* file the pad currently
     * references (main sample and any GHOSTS velocity layers). A layer
     * built by `addGhostLayers` *after* a treatment was derived from the
     * already-aged main sample and never earned its own bin entry, so a
     * partial undo there would leave the main sample and its layers aged
     * by a different number of passes — the exact "untreated underside"
     * `PadSheet.kt`'s KDoc says routing through `eraPad` (not `treatPad`)
     * exists to avoid. Refusing honestly in that case beats a silent skew.
     */
    fun applyTreatment(segment: String, amount: Float) {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        val era = PadSheet.eraFor(segment) ?: return
        val padName = p.displayName
        val hadPriorTreatment = readEraRecipe(p.recipe) != null
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    if (hadPriorTreatment) {
                        val files = (listOf(p.sampleFile) + p.velocityLayers.map { it.sampleFile }).distinct()
                        val binned = m.binContents().map { it.originalName }.toSet()
                        check(files.all { it in binned }) {
                            "pad $slot can't cleanly re-treat - its ghost layers postdate the last " +
                                "treatment (or the bin's since emptied) - clear GHOSTS, or accept the " +
                                "current sound, before treating again"
                        }
                        m.unEraPad(slot)
                    }
                    m.eraPad(slot, era, amount)
                    m.save()
                }
                revision++
                onKitUpdated(m.kit)
                refreshPadAudio(m)
                snip?.let { audition(it, m.kit.pad(slot)?.level ?: 1f) }
                onToast(Copy.treated(segment, padName))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The ghosts-postdate-treatment refusal above is expected,
                // in-voice user copy, not a diagnostic — the check()'s own
                // message stays in logs (via `e`/a debugger) but the toast
                // says what Copy says, not raw exception prose.
                if (e is IllegalStateException) onToast(Copy.RETREAT_REFUSED) else failure("TREATMENT", e)
            } finally {
                busy = false
            }
        }
    }

    fun onGhostsToggle() {
        if (busy) return
        val m = model ?: return
        val p = m.kit.pad(slot) ?: return
        val enabling = p.velocityLayers.isEmpty()
        commitPadEditNow("GHOSTS", onSuccess = { if (enabling) onToast(Copy.GHOSTS_ON) }) { mm ->
            if (enabling) mm.addGhostLayers(slot) else mm.clearGhostLayers(slot)
        }
    }

    fun onEject() {
        commitPadEditNow("EJECT", onSuccess = { onToast(Copy.DELETE_SNIP); onBack() }) { mm -> mm.clear(slot) }
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
                val destRoot = File(entry.dir.parentFile ?: entry.dir, "Instruments")
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
        if (m == null || !m.dirty || busy) {
            if (!busy) onBack()
            return
        }
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { m.save() }
                onKitUpdated(m.kit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure("SAVE", e)
            } finally {
                busy = false
            }
            onBack()
        }
    }

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
     */
    DisposableEffect(model) {
        onDispose {
            val m = model
            if (m != null && m.dirty) {
                appScope.launch {
                    try {
                        withContext(Dispatchers.IO) { m.save() }
                        onKitUpdated(m.kit)
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

    val recipeEra = readEraRecipe(pad.recipe)
    val activeSegment = recipeEra?.let { PadSheet.segmentFor(it.first) }
    val isNoneState = recipeEra == null
    val unmappedEraName = if (recipeEra != null && activeSegment == null) recipeEra.first else null
    val amount = recipeEra?.second ?: PadSheet.DEFAULT_AMOUNT

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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PadSheetHeader(
            slot = slot,
            pad = pad,
            classColor = classColor,
            scheme = scheme,
            busy = busy,
            onBack = ::requestBack,
            onHit = { snip?.let { audition(it, pad.level) } },
        )

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WaveformLcd(snip, pad.drumClass, classColor, scheme, Modifier.padding(top = 4.dp))

            TapeText(
                provenanceLine(pad, snip, binDaysLeft) + (unmappedEraName?.let { " · AGED: ${it.uppercase()}" } ?: ""),
                TapeType.pixelSmall,
                scheme.ink2.tape,
                maxLines = 2,
            )

            StepperSlider(
                label = "LEVEL",
                fraction = ((pendingDb - LEVEL_DB_MIN) / (LEVEL_DB_MAX - LEVEL_DB_MIN)).coerceIn(0f, 1f),
                valueText = "%.0f dB".format(pendingDb),
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
                    "GHOSTS",
                    pad.velocityLayers.isNotEmpty(),
                    classColor,
                    scheme,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onToggle = ::onGhostsToggle,
                )
            }

            TreatmentCard(
                segments = PadSheet.SEGMENTS,
                noneSegment = PadSheet.NONE,
                activeSegment = activeSegment,
                isNoneState = isNoneState,
                amountFraction = pendingAmt,
                amountText = "${(pendingAmt * 100).roundToInt()}%",
                padColor = classColor,
                scheme = scheme,
                busy = busy,
                onSegmentTap = { seg -> applyTreatment(seg, pendingAmt) },
                onAmountChange = { f -> pendingAmt = (f * 20f).roundToInt() / 20f },
                onAmountCommit = { activeSegment?.let { seg -> applyTreatment(seg, pendingAmt) } },
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("RE-TRIM ▸", scheme, enabled = !busy, modifier = Modifier.weight(1f), onClick = onNavigateTape)
            ActionButton(
                "MAKE INSTRUMENT",
                scheme,
                enabled = !busy,
                dimmed = pad.drumClass != DrumClass.TONAL,
                modifier = Modifier.weight(1f),
                onClick = ::onMakeInstrument,
            )
        }
        EjectButton(scheme, enabled = !busy, onClick = ::onEject)

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
    source["resampledFrom"] != null -> "resampled from ${source.getValue("resampledFrom")}"
    source["importedFrom"] != null -> "imported from ${source.getValue("importedFrom")}"
    source["sculptedFrom"] != null -> "sculpted from ${source.getValue("sculptedFrom")}"
    source["dissectedFrom"] != null -> "dissected from ${source.getValue("dissectedFrom")}"
    source["mergedFrom"] != null -> "merged from ${source.getValue("mergedFrom")}"
    source["origin"] == "chop" -> "chopped from tape"
    else -> null
}

private fun provenanceLine(pad: KitPad, snip: Snip?, binDaysLeft: Int?): String {
    val parts = mutableListOf(provenanceOrigin(pad.source) ?: pad.sampleFile)
    snip?.let { parts += "%.0f ms".format(it.durationSeconds * 1000f) }
    binDaysLeft?.let { parts += "original in bin, ${it}d left" }
    return parts.joinToString(" · ")
}

/**
 * A pad aged via `eraPad` carries `{"era": name, "amount": x}` — read
 * defensively, since [KitPad.recipe] may just as well be a synth patch or
 * an fx-chain recipe from a different door (`treatPad`, `replaceAudio`),
 * neither of which uses these keys. Anything else here reads as untreated.
 */
private fun readEraRecipe(recipe: JsonValue.Obj?): Pair<String, Float>? {
    if (recipe == null) return null
    val era = (recipe.entries["era"] as? JsonValue.Str)?.value ?: return null
    val amount = (recipe.entries["amount"] as? JsonValue.Num)?.value?.toFloat() ?: return null
    return era to amount
}

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

private fun padTag(slot: Int): String = "A%02d".format(slot)

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
            .let { if (enabled) it.tapeClick(onClick) else it }
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
private fun StepperSlider(
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
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .clip(RoundedCornerShape(4.dp))
                .sunkenField(scheme)
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
            .let { if (enabled) it.tapeClick(onToggle) else it }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (engaged) scheme.titleInk.tape else scheme.ink2.tape, maxLines = 1)
    }
}

// ---------- treatment card ----------

@Composable
private fun TreatmentCard(
    segments: List<String>,
    noneSegment: String,
    activeSegment: String?,
    isNoneState: Boolean,
    amountFraction: Float,
    amountText: String,
    padColor: Color,
    scheme: Scheme,
    busy: Boolean,
    onSegmentTap: (String) -> Unit,
    onAmountChange: (Float) -> Unit,
    onAmountCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("TREATMENT", TapeType.pixelSmall, scheme.ink3.tape)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (seg in segments) {
                val selected = if (seg == noneSegment) isNoneState else seg == activeSegment
                // NONE is display-only here — this card has no "un-treat"
                // action, so it never accepts a tap (see the file's report
                // for why that's a deliberate scope line, not an oversight).
                val tappable = !busy && seg != noneSegment
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme, fill = if (selected) padColor.copy(alpha = 0.85f) else null)
                        .let { if (tappable) it.tapeClick { onSegmentTap(seg) } else it }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(seg, TapeType.pixel, if (selected) scheme.titleInk.tape else scheme.ink2.tape)
                }
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

// ---------- action row ----------

@Composable
private fun ActionButton(
    label: String,
    scheme: Scheme,
    enabled: Boolean,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .let { if (enabled) it.tapeClick(onClick) else it }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            label,
            TapeType.pixel,
            if (!enabled) scheme.ink3.tape else if (dimmed) scheme.ink2.tape else scheme.ink.tape,
            maxLines = 1,
        )
    }
}

// THE ONLY NON-SCHEME COLOURS IN THE DESIGN — BIN red is deliberately
// constant across every scheme (HANDOFF.md X2 / TAKES+BIN), so a delete
// action reads as "red" even in a scheme with no red anywhere else in it.
private val BIN_RED_BORDER = Color(0xFF6A2020)
private val BIN_RED_GLOW = Color(0xFFC86050)

@Composable
private fun EjectButton(scheme: Scheme, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            // The dark LCD surface, same as every other dark button fill in
            // this scheme (see PrimaryAction) — only the rim is the fixed
            // bin-red; the fill still answers to the scheme.
            .background(scheme.lcd.tape, RoundedCornerShape(4.dp))
            .border(2.dp, BIN_RED_BORDER, RoundedCornerShape(4.dp))
            .let { if (enabled) it.tapeClick(onClick) else it }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText("EJECT → BIN", TapeType.pixel, BIN_RED_GLOW)
    }
}
