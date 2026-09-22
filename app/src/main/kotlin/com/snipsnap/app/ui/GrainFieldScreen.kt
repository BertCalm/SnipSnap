package com.snipsnap.app.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.snipsnap.app.GrainVoice
import com.snipsnap.app.KitShelf
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.TiltSource
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.AxesProjector
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.GrainField
import com.snipsnap.audio.Similar
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.TiltCursor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// One rule, one home ([PadBanks]): this said "A%02d".format(slot) until
// the September UAT's finding 11 made bank B reachable, at which point a
// pad on slot 17 would have been titled A17 on this screen.
private fun padTag(slot: Int): String = PadBanks.tag(slot)

/** Grains within this many dp of the live touch light up in [Scheme.accent] instead of [Scheme.ink2]. */
private val LIT_RADIUS = 56.dp
private val DOT_RADIUS = 2.5.dp
private val CURSOR_RING_RADIUS = 14.dp
private val CURSOR_RING_STROKE = 1.5.dp

/** DUET's auto-cursor ring — a touch larger than [CURSOR_RING_RADIUS] so the two never read as the same mark. */
private val AUTO_CURSOR_RING_RADIUS = 19.dp

/** DUET loop cadence — a control-rate tick, not audio-rate; see [GrainFieldScreen]'s DUET section. */
private const val DUET_TICK_MS = 100L

/** Below this input peak, DUET treats the room as silent and gates the voice off instead of analyzing hiss. */
private const val DUET_SILENCE_LEVEL = 0.02f

/** Auto-cursor smoothing: `new = lerp(prev, projected, DUET_SMOOTHING)` — stops the cursor teleporting between ticks. */
private const val DUET_SMOOTHING = 0.5f

/**
 * A field built elsewhere and handed to [GrainFieldScreen] whole, in place
 * of a pad's WAV and its analysis — SNAP's PHOTO FIELD (`PhotoField.build`
 * in `:synth`), where the map's positions are the cells of a picture and
 * [backdrop] is that picture, drawn under the dots so a finger sees what
 * it hears. Its map carries an `AxesProjector` rather than a
 * [GrainField.PcaProjector] — a photo field has no grains of its own to fit
 * a PCA basis from — so DUET's chip shows here too: a brighter cell reads
 * right, a louder voice moves the cursor up.
 */
class PrebuiltField(
    val snip: Snip,
    val map: GrainField.GrainMap,
    val backdrop: Bitmap?,
    /** The header's title in place of PAD A01. */
    val title: String,
    /** The back chip's label in place of ◄ KIT. */
    val backLabel: String,
)

/**
 * GRAIN FIELD: drag across a pad's own timbre landscape and hear it play.
 *
 * [GrainField.analyze] dices the pad's sample into a few hundred short
 * grains, fingerprints each with the classifier's own feature extractor, and
 * projects those fingerprints onto a 2D scatter — nearby dots sound alike,
 * distant ones don't. This screen is that scatter made touchable: a finger
 * anywhere on the field continuously re-triggers whichever grains sit
 * closest to it, for as long as it's held down ([GrainVoice] owns the actual
 * render thread; this composable only ever calls [GrainVoice.setTarget] and
 * [GrainVoice.gate]).
 *
 * **DUET** hands the cursor to the armed mic instead of a finger: the header
 * chip (visible only when the map carries a [GrainField.Projector] — a
 * [GrainField.PcaProjector] that survived analysis, see that class's own
 * KDoc for when it doesn't, or [AxesProjector] on a [PrebuiltField] that
 * always carries one) toggles a control-rate loop that snapshots the live
 * input, fingerprints it with the SAME extractor the map itself was built
 * from, and projects that fingerprint into the map's existing 0..1 space —
 * loudness stands in for y itself when the projector is an [AxesProjector],
 * since that's the one feature [Similar.vector] leaves out. A finger
 * touching the field always wins — see [GrainFieldCanvas]'s `touching`
 * flag — and DUET resumes the instant it lifts.
 *
 * **TILT** is the field's other automatic cursor: `TiltSource`'s roll and
 * pitch, dead-banded and smoothed by [TiltCursor.step], stand in for a
 * finger the same way DUET's projected mic input does — the identical
 * control-rate loop shape, `autoPos`, `setTarget`/`gate` and all, just fed
 * from the phone's own tilt instead of a projection. DUET and TILT are
 * exclusive: turning one on turns the other off, since both drive the same
 * one cursor and only one automatic source should own it at a time.
 *
 * **PRINT** captures the performance: from the tap to STOP PRINT,
 * everything [GrainVoice] mixed lands on TAPE as a snip ([GrainVoice.startPrint]/
 * [GrainVoice.stopPrint]) — the app's own capture door, so a drag across a
 * pad, or a photo, ends up on pads the same way a capture does (trim, chop,
 * classify, kit).
 *
 * On open: the pad's WAV is read and analyzed off the main thread (a
 * "LISTENING TO THE GRAIN…" LCD covers the wait); a `null` analysis — too
 * little audible material to build a field from — toasts and backs out
 * instead of showing an empty scatter. The [GrainVoice] itself is created
 * only once analysis succeeds, started once, and released whenever this
 * screen leaves composition for any reason (back, a MenuRow tab switch mid
 * drag, or the analyze-null path).
 */
@Composable
fun GrainFieldScreen(
    /** The kit and slot whose pad is analyzed into a field — or null when [prebuilt] brings one. */
    entry: KitShelf.Entry?,
    slot: Int?,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onRequestArm: () -> Unit,
    /** PRINT landed on TAPE: the same reload request a share-sheet import raises. */
    onFieldPrinted: () -> Unit,
    /** A field built elsewhere (SNAP's photo), in place of loading and analyzing a pad. */
    prebuilt: PrebuiltField? = null,
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember(entry?.dir, slot, prebuilt) { mutableStateOf<Pair<Snip, GrainField.GrainMap>?>(null) }
    var failed by remember(entry?.dir, slot, prebuilt) { mutableStateOf(false) }

    LaunchedEffect(entry?.dir, slot, prebuilt) {
        if (prebuilt != null) {
            loaded = prebuilt.snip to prebuilt.map
            return@LaunchedEffect
        }
        if (entry == null || slot == null) {
            failed = true
            return@LaunchedEffect
        }
        val pad = entry.kit.pad(slot)
        val snip = pad?.let { p ->
            withContext(Dispatchers.IO) {
                // p.sampleFile is a kit pad sample, produced only by KitBuilderModel.assign
                // from an already-bounded Snip — readCapped's 600s ceiling is defense in
                // depth, not expected to ever bind.
                runCatching { Cleanup.toMono(WavReader.readCapped(File(entry.dir, p.sampleFile), TAPE_LOAD_MAX_SEC).snip) }.getOrNull()
            }
        }
        if (snip == null) {
            failed = true
            return@LaunchedEffect
        }
        val map = withContext(Dispatchers.Default) { GrainField.analyze(snip) }
        if (map == null) failed = true else loaded = snip to map
    }
    // The picture under the dots, converted once per field.
    val backdrop = remember(prebuilt) { prebuilt?.backdrop?.asImageBitmap() }

    // Analyze-null (or the WAV itself missing/unreadable) — same shape as
    // every other "can't do this" path in the app: toast, then leave. Keyed
    // on `failed` alone so this only ever fires once, the moment it flips.
    LaunchedEffect(failed) {
        if (failed) {
            // Named for the dominant real cause: GrainField.analyze needs at
            // least MIN_GRAINS (4) surviving windows of GRAIN_FRAMES (4096)
            // hopped by HOP_FRAMES (2048) — under ~10240 frames (~232ms @
            // 44.1kHz) it returns null regardless of level, so a loud, short
            // hat or snare bounces here far more often than a genuinely
            // quiet sample does.
            onToast(Copy.GRAIN_FIELD_TOO_SHORT)
            onBack()
        }
    }

    // Created once analysis succeeds — remember is keyed on `loaded`'s
    // identity, which only changes the one time this screen's LaunchedEffect
    // above writes it, so this never rebuilds a second voice underneath an
    // already-playing one.
    val current = loaded
    val voice = remember(current) {
        current?.let { (snip, map) -> GrainVoice(snip.samples, snip.sampleRate, map) }
    }
    // Terminal teardown: fires on back, on a MenuRow tab switch that unmounts
    // this screen mid-drag, and on ordinary recomposition-driven disposal —
    // release() is documented safe to call more than once, so this racing
    // with the back chip's own explicit release() below is harmless.
    DisposableEffect(voice) {
        onDispose { voice?.release() }
    }

    // The header's own ◄ KIT path: release the voice, then leave — shared
    // by the chip below and by system Back, so the two can't diverge on
    // whether the voice gets released before this screen unmounts.
    fun requestBack() {
        voice?.release()
        onBack()
    }
    BackHandler(onBack = ::requestBack)

    // start() exactly once per voice instance — LaunchedEffect only
    // relaunches when `voice`'s identity changes, and GrainVoice.start()
    // itself is idempotent (a compareAndSet guard), so this can't double-arm
    // the render thread even under a fast recomposition. `start()`'s own KDoc
    // documents that `buildTrack` can throw if the platform rejects the
    // format — a caught, retryable condition for a caller, not a silent dead
    // screen or an uncaught crash on the class of device that triggers it.
    LaunchedEffect(voice) {
        runCatching { voice?.start() }.onFailure {
            Log.e("GrainFieldScreen", "voice failed to start", it)
            onToast(Copy.GRAIN_FIELD_START_FAILED)
            onBack()
        }
    }

    // PRINT: everything the voice mixes from a tap to STOP PRINT lands on
    // TAPE as a snip — the field's own declared next phase (its class KDoc,
    // above), for the pad sheet's field as much as the photo's.
    var printing by remember { mutableStateOf(false) }
    // Guards the frame loop from calling finishPrint() again while the stop
    // is already in flight — SurfaceScreen's own `finishing` shape.
    var finishingPrint by remember { mutableStateOf(false) }

    fun printToTape(snip: Snip) {
        scope.launch {
            val landed = withContext(Dispatchers.IO) {
                runCatching { SnipStore.import(snip, context.filesDir, System.currentTimeMillis()) }
            }
            landed.onSuccess {
                onToast(Copy.surfacePrinted(it.seconds))
                onFieldPrinted()
            }.onFailure {
                Log.e("GrainFieldScreen", "printToTape: print lost", it)
                onToast(Copy.PRINT_LOST)
            }
        }
    }

    fun finishPrint() {
        if (finishingPrint) return
        val v = voice ?: return
        val rate = current?.first?.sampleRate ?: return
        finishingPrint = true
        printing = false
        scope.launch {
            try {
                val samples = withContext(Dispatchers.Default) { v.stopPrint() }
                if (samples == null || samples.size < rate / 10) {
                    onToast(Copy.GRAIN_FIELD_NOTHING_PRINTED)
                    return@launch
                }
                printToTape(Snip(samples, channels = 1, sampleRate = rate))
            } finally {
                finishingPrint = false
            }
        }
    }

    val projector = current?.second?.projector
    // DUET and TILT are the field's two automatic cursors, exclusive with
    // each other (one at a time) the same way a finger always wins over
    // either: turning one on turns the other off, at the header chips below.
    var duetOn by remember { mutableStateOf(false) }
    var tiltOn by remember { mutableStateOf(false) }
    val armed by MicSessionService.armed.collectAsState()
    val tilt = remember { TiltSource(context) }
    DisposableEffect(tilt) {
        tilt.start()
        onDispose { tilt.stop() }
    }

    // Written by the finger gesture inside GrainFieldCanvas (down/up only,
    // not per-move), read here so the DUET loop below can stand down the
    // instant a finger takes over — see GrainFieldCanvas's own KDoc for why
    // this is a plain state boolean and not something recomputed per frame.
    val touching = remember { mutableStateOf(false) }
    // Auto-cursor position in the map's normalized 0..1 space, written only
    // by the DUET loop below; read inside GrainFieldCanvas's own Canvas draw
    // lambda, exactly the discipline `touch` already follows in that file.
    val autoPos = remember { mutableStateOf<Offset?>(null) }

    // DUET: the armed mic drives the field's cursor instead of a finger.
    //
    // **Feedback hazard.** If the pad's own render is audible to the phone's
    // mic (a speaker, not headphones), the loop below will happily analyze
    // its own output and chase itself — level-gating (skipping ticks under
    // [DUET_SILENCE_LEVEL]) only keeps a truly silent room silent; it does
    // nothing once the render is loud enough to clear that floor, because at
    // that point the picked-up render IS a genuine, non-silent signal by the
    // same measure real input would be. Headphones are the actual fix; the
    // hint text below says so.
    LaunchedEffect(duetOn, armed) {
        val v = voice
        val p = projector
        if (!duetOn || !armed || v == null || p == null) {
            autoPos.value = null
            return@LaunchedEffect
        }
        try {
            var prevX = 0.5f
            var prevY = 0.5f
            while (true) {
                // The whole tick body, not just the extraction/project step
                // below — any uncaught exception here (level/snapshotTail
                // reads included) would otherwise silently kill this loop
                // while the DUET chip stays lit, looking engaged while doing
                // nothing. One stumble turns DUET back off, with a toast, so
                // the chip's state matches reality again.
                try {
                    if (!v.alive) {
                        // The render thread died on its own (route change,
                        // device hiccup) — GrainVoice.alive is exactly the
                        // signal that used to have no public accessor; keep
                        // polling a dead voice's setTarget/gate forever is
                        // the silent-failure shape this closes.
                        onToast(Copy.GRAIN_FIELD_DUET_STOPPED)
                        duetOn = false
                        break
                    }
                    if (!touching.value) {
                        val level = MicSessionService.level.value
                        if (level < DUET_SILENCE_LEVEL) {
                            v.gate(false)
                        } else {
                            // CaptureRing.snapshot returns fewer than the
                            // requested frames (not null) when the ring hasn't
                            // filled that far yet — the earliest ticks right
                            // after ARM, most likely. A short/empty window analyzes
                            // fine (FeatureExtractor/Fft both tolerate it) but
                            // its projection is meaningless, so it's skipped
                            // here rather than smoothed into the cursor's path.
                            val raw = MicSessionService.snapshotTail(GrainField.GRAIN_FRAMES)
                            if (raw != null && raw.size >= GrainField.GRAIN_FRAMES) {
                                val projected = runCatching {
                                    withContext(Dispatchers.Default) {
                                        val snip = Snip(raw, channels = 1, sampleRate = MicSessionService.SAMPLE_RATE)
                                        p.project(Similar.vector(FeatureExtractor.extract(snip)))
                                    }
                                }.getOrNull()
                                // Re-read `touching` LIVE here, after the suspend
                                // above returns — the gate at the top of this
                                // `if` was checked before that (real, non-trivial)
                                // dispatcher switch, so a finger landing mid-
                                // projection would otherwise still get clobbered
                                // by this stale, pre-touch target once execution
                                // resumes (the "re-read live state after
                                // suspension" rule).
                                if (projected != null && !touching.value) {
                                    // AxesProjector leaves y at the map's own
                                    // centre — loudness, the feature DUET
                                    // wants there, is deliberately not part
                                    // of Similar.vector (see AxesProjector's
                                    // own KDoc) — so the live mic level maps
                                    // onto y here instead, straight off the
                                    // same read the silence gate above used.
                                    // A PcaProjector's own y (its second
                                    // principal component) is untouched.
                                    val py = if (p is AxesProjector) level.coerceIn(0f, 1f) else projected.second
                                    prevX += (projected.first - prevX) * DUET_SMOOTHING
                                    prevY += (py - prevY) * DUET_SMOOTHING
                                    autoPos.value = Offset(prevX, prevY)
                                    v.setTarget(prevX, prevY)
                                    v.gate(true)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("GrainFieldScreen", "duet loop stumbled", e)
                    onToast(Copy.GRAIN_FIELD_DUET_STOPPED)
                    duetOn = false
                    break
                }
                delay(DUET_TICK_MS)
            }
        } finally {
            // Covers both "DUET switched off" and "this loop is being torn
            // down" (armed dropped out from under it, or the screen itself
            // left composition) — a finger already holding the field owns
            // gate() exclusively, so this must not clobber that.
            if (!touching.value) v.gate(false)
        }
    }

    // TILT: the phone's own roll/pitch drives the field's cursor instead of
    // a finger — DUET's own loop shape, [TiltCursor.step] in place of the
    // mic's projection, ticked no faster than the frame it draws on.
    LaunchedEffect(tiltOn, tilt.available) {
        val v = voice
        if (!tiltOn || !tilt.available || v == null) {
            autoPos.value = null
            return@LaunchedEffect
        }
        try {
            var prevX = 0.5f
            var prevY = 0.5f
            while (true) {
                try {
                    if (!v.alive) {
                        onToast(Copy.GRAIN_FIELD_TILT_STOPPED)
                        tiltOn = false
                        break
                    }
                    if (!touching.value) {
                        val (nx, ny) = TiltCursor.step(tilt.tilt, tilt.pitch, prevX, prevY)
                        prevX = nx
                        prevY = ny
                        autoPos.value = Offset(prevX, prevY)
                        v.setTarget(prevX, prevY)
                        v.gate(true)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("GrainFieldScreen", "tilt loop stumbled", e)
                    onToast(Copy.GRAIN_FIELD_TILT_STOPPED)
                    tiltOn = false
                    break
                }
                delay(DUET_TICK_MS)
            }
        } finally {
            // Same discipline as DUET's own loop above: a finger already
            // holding the field owns gate() exclusively.
            if (!touching.value) v.gate(false)
        }
    }

    Column(Modifier.fillMaxSize().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChip(prebuilt?.backLabel ?: "◄ KIT", scheme, Modifier.width(64.dp), onClick = ::requestBack)
            Spacer(Modifier.weight(1f))
            TapeText(prebuilt?.title ?: "PAD ${padTag(slot ?: 0)}", TapeType.lcdHeader, scheme.lcdInk.tape)
            Spacer(Modifier.weight(1f))
            if (voice != null) {
                HeaderChip(
                    if (printing) "STOP PRINT" else "PRINT",
                    scheme,
                    Modifier.width(84.dp),
                    engaged = printing,
                    accessibilityLabel = if (printing) "STOP PRINT" else "PRINT TO TAPE",
                ) {
                    if (printing) finishPrint() else if (voice.startPrint()) printing = true
                }
            } else {
                Spacer(Modifier.width(84.dp))
            }
            Spacer(Modifier.width(4.dp))
            if (projector != null) {
                HeaderChip(
                    "DUET",
                    scheme,
                    Modifier.width(64.dp),
                    engaged = duetOn,
                    accessibilityLabel = "DUET ${if (duetOn) "ON" else "OFF"}",
                ) {
                    // Exclusive with TILT: at most one automatic cursor at a time.
                    duetOn = !duetOn
                    if (duetOn) tiltOn = false
                }
            } else {
                Spacer(Modifier.width(64.dp))
            }
            Spacer(Modifier.width(4.dp))
            if (tilt.available) {
                HeaderChip(
                    "TILT",
                    scheme,
                    Modifier.width(64.dp),
                    engaged = tiltOn,
                    accessibilityLabel = "TILT ${if (tiltOn) "ON" else "OFF"}",
                ) {
                    // Exclusive with DUET: at most one automatic cursor at a time.
                    tiltOn = !tiltOn
                    if (tiltOn) duetOn = false
                }
            } else {
                Spacer(Modifier.width(64.dp))
            }
        }

        if (current == null) {
            Box(
                Modifier.fillMaxWidth().weight(1f).lcdPanel(scheme).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(Copy.GRAIN_FIELD_LISTENING, TapeType.lcdSmall, scheme.lcdInk.tape)
            }
        } else {
            val (_, map) = current
            Box(Modifier.fillMaxWidth().weight(1f)) {
                GrainFieldCanvas(map, voice!!, scheme, touching, autoPos, backdrop, Modifier.fillMaxSize())
                if (printing) {
                    TapeText(
                        "● PRINTING",
                        TapeType.lcdSmall,
                        scheme.amber.tape,
                        Modifier.align(Alignment.TopCenter).padding(8.dp),
                    )
                }
            }
            if (duetOn && !armed) {
                PrimaryAction(label = "START MIC", enabled = true, onClick = onRequestArm)
            }
            TapeText(
                when {
                    duetOn -> Copy.GRAIN_FIELD_DUET_HINT
                    tiltOn -> Copy.GRAIN_FIELD_TILT_HINT
                    else -> Copy.GRAIN_FIELD_DRAG_HINT
                },
                TapeType.pixelSmall,
                scheme.ink2.tape,
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            )
        }
    }
}

/**
 * The scatter itself. [map]'s grain positions are precomputed once (they
 * never change for the lifetime of a given [map]) into [dots]; only the
 * live touch position needs to repaint fast, and repainting it must not
 * recompose this composable — a drag can report at 60Hz, and recomposing a
 * whole `Canvas` + gesture-modifier tree at that rate would be far more work
 * than redrawing it.
 *
 * The fix (same discipline `WaveformLcd`/`TapeScreen`'s `position: () ->
 * Double` already uses): [touch] is read exactly once, inside the [Canvas]
 * draw lambda itself — never at this composable's own body scope. A draw
 * lambda's state reads are tracked against the draw phase, not composition,
 * so writing [touch] from the pointer-input coroutine below invalidates
 * only this Canvas's next draw pass. [autoPos] (DUET's or TILT's cursor —
 * exclusive, so only ever one at a time — written by whichever caller's
 * control-rate loop is on) follows the exact same discipline.
 *
 * [touching] is the one signal this composable exports upward: true for as
 * long as a finger is down, set/cleared only on down/up (not per-move, so
 * it doesn't fight the same 60Hz budget [touch] is protected from) — the
 * caller's DUET/TILT loop polls it each tick to know whether the manual
 * path currently owns [voice]'s target and gate.
 */
@Composable
private fun GrainFieldCanvas(
    map: GrainField.GrainMap,
    voice: GrainVoice,
    scheme: Scheme,
    touching: MutableState<Boolean>,
    autoPos: MutableState<Offset?>,
    /** The picture the grains were cut from, drawn under them; null for an analyzed pad. */
    backdrop: ImageBitmap? = null,
    modifier: Modifier = Modifier,
) {
    val dots = remember(map) { map.grains.map { Offset(it.x, it.y) } }
    // Written only from the pointer-input coroutine below; read only inside
    // the Canvas draw lambda. Do NOT read this anywhere else in this
    // composable's body (e.g. to show a coordinate readout) — doing so would
    // subscribe recomposition to a 60Hz drag, defeating the whole point.
    var touch by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier
            .lcdPanel(scheme)
            // Canvas-drawn, invisible to the a11y tree by default (audit
            // finding 4). This composable's own KDoc above explains why
            // `touch` is deliberately never read at composition scope
            // (a 60Hz drag would otherwise recompose the whole tree) —
            // a live stateDescription would reintroduce exactly that, so
            // this meets finding 4's required floor (a descriptive
            // label) rather than its "ideally adjustable" ceiling. 2D
            // scatter position has no single progressBarRangeInfo to
            // expose either way.
            .semantics { contentDescription = "GRAIN FIELD" }
            .pointerInput(voice) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id

                    fun report(pos: Offset) {
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val clampedX = pos.x.coerceIn(0f, w)
                        val clampedY = pos.y.coerceIn(0f, h)
                        touch = Offset(clampedX, clampedY)
                        val nx = if (w > 0f) (clampedX / w).coerceIn(0f, 1f) else 0f
                        val ny = if (h > 0f) (clampedY / h).coerceIn(0f, 1f) else 0f
                        voice.setTarget(nx, ny)
                    }

                    // Finger priority: claim `touching` (and drop any stale
                    // DUET ring) before the manual path's own gate/target
                    // writes below — a DUET tick racing in right now will
                    // see `touching.value == true` and stand down instead.
                    touching.value = true
                    autoPos.value = null
                    report(down.position)
                    voice.gate(true)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            voice.gate(false)
                            touch = null
                            touching.value = false
                            change?.consume()
                            break
                        }
                        report(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val dotRadiusPx = DOT_RADIUS.toPx()
        val litRadiusPx = LIT_RADIUS.toPx()
        val litRadiusSqPx = litRadiusPx * litRadiusPx

        // The picture first, dimmed so the dots and the cursor still read
        // over it, stretched to the panel the way the cells were laid out.
        if (backdrop != null && w > 0f && h > 0f) {
            drawImage(backdrop, dstSize = IntSize(w.toInt(), h.toInt()), alpha = 0.6f)
        }

        // The one deferred state read for this whole draw pass — see this
        // function's own KDoc for why it must happen here and nowhere else.
        val t = touch
        val a = autoPos.value

        for (dot in dots) {
            val center = Offset(dot.x * w, dot.y * h)
            val lit = if (t != null) {
                val dx = center.x - t.x
                val dy = center.y - t.y
                dx * dx + dy * dy <= litRadiusSqPx
            } else {
                false
            }
            drawCircle(
                color = if (lit) scheme.accent.tape else scheme.ink2.tape,
                radius = dotRadiusPx,
                center = center,
            )
        }

        if (t != null) {
            drawCircle(
                color = scheme.accent.tape,
                radius = CURSOR_RING_RADIUS.toPx(),
                center = t,
                style = Stroke(width = CURSOR_RING_STROKE.toPx()),
            )
        }

        // DUET's or TILT's cursor (exclusive, never both): a hollow ring at
        // a distinct radius from the touch ring above, so the two never
        // read as the same mark even in the brief window either could be
        // drawn (they're not otherwise expected to coexist — see
        // GrainFieldCanvas's own KDoc on `touching`).
        if (a != null) {
            drawCircle(
                color = scheme.accent.tape,
                radius = AUTO_CURSOR_RING_RADIUS.toPx(),
                center = Offset(a.x * w, a.y * h),
                style = Stroke(width = CURSOR_RING_STROKE.toPx()),
            )
        }
    }
}

@Composable
private fun HeaderChip(
    label: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    // Orthogonal latched/lit look — accent fill + inverted ink — mirroring
    // TapeScreen.kt's DeckButton `engaged` param (IN/OUT), implemented
    // locally here since this file's chips use a bordered-box look, not
    // DeckButton's raised bevel. Every other HeaderChip call leaves this at
    // the default and renders exactly as before.
    engaged: Boolean = false,
    /**
     * Accessible name override, for the one call site (DUET) where
     * [engaged] is a real toggle a screen reader needs to hear the state
     * of. `null` (every other call site, where [engaged] never changes)
     * falls back to [label] alone.
     */
    accessibilityLabel: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .then(
                if (engaged) {
                    Modifier.background(scheme.accent.tape, RoundedCornerShape(3.dp))
                } else {
                    Modifier
                },
            )
            .border(1.dp, if (engaged) scheme.accent.tape else scheme.ink2.tape, RoundedCornerShape(3.dp))
            .tapeClick(label = accessibilityLabel ?: label, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (engaged) scheme.lcd.tape else scheme.ink.tape)
    }
}
