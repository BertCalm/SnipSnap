package com.snipsnap.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.Finding
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Severity
import com.snipsnap.shell.Copy
import com.snipsnap.shell.ExportWizardModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Scheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EXPORT: the wizard's Compose surface over `ExportWizardModel`'s tested
 * stage machine (READY → WRITING → COMPLETE). Preflight, the format
 * cycler and the dub write all live in `:shell`/`:kit`; this file renders
 * the checklist, forwards taps, paces the dub-progress animation on its
 * own clock (the model exposes labels, not a live byte count — see the
 * model's own KDoc), and writes to `getExternalFilesDir("exports")/<kit
 * name>` — no permissions needed, reachable from the Files app. A
 * share-sheet / SAF picker is a known follow-up, out of scope this pass.
 *
 * [entry] is read fresh from disk on open (`KitStore.load`), the same
 * "a kit is its folder" rule `PadSheetScreen` follows, rather than
 * trusting `entry.kit`'s possibly-stale in-memory copy (edits made on
 * PAD SHEET land on disk, not necessarily in `App`'s hoisted `open`
 * before EXPORT is opened).
 */
@Composable
fun ExportScreen(
    entry: KitShelf.Entry?,
    onToast: (String) -> Unit,
) {
    val scheme = LocalScheme.current

    if (entry == null) {
        EmptyExport(scheme)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kit by remember(entry.dir) { mutableStateOf<Kit?>(null) }
    var model by remember(entry.dir) { mutableStateOf<ExportWizardModel?>(null) }
    var loadFailed by remember(entry.dir) { mutableStateOf(false) }
    LaunchedEffect(entry.dir) {
        loadFailed = false
        kit = null
        model = null
        // KitStore.load reads kit.json; ExportWizardModel's own init runs
        // Preflight.check, which opens and reads every pad WAV's header —
        // both belong off the composition thread, so both happen inside
        // this one IO block rather than splitting the load from the model
        // construction.
        val loaded = withContext(Dispatchers.IO) {
            runCatching { KitStore.load(entry.dir).let { it to ExportWizardModel(it, entry.dir) } }.getOrNull()
        }
        if (loaded == null) {
            loadFailed = true
        } else {
            kit = loaded.first
            model = loaded.second
        }
    }

    val loadedKit = kit
    val loadedModel = model
    if (loadedKit == null || loadedModel == null) {
        // Still opening the model, or the load failed outright — same
        // "still decoding vs. genuinely broken" split ChopScreen/
        // PadSheetScreen use; a blank LCD covers the former.
        if (loadFailed) EmptyExport(scheme) else Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }

    ExportContent(loadedKit, loadedModel, context, scope, scheme, onToast)
}

@Composable
private fun EmptyExport(scheme: Scheme) {
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

@Composable
private fun ExportContent(
    kit: Kit,
    model: ExportWizardModel,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    scheme: Scheme,
    onToast: (String) -> Unit,
) {
    // `ExportWizardModel` is a plain mutable class (its `stage`/`formatIx`/
    // `preflight` are ordinary vars, tested and owned by :shell), so — same
    // trick ChopScreen's and PadSheetScreen's own `revision` counters use —
    // this is what forces a recompose after a tap mutates it outside
    // Compose's snapshot system.
    var revision by remember(model) { mutableIntStateOf(0) }
    val revisionTick = revision

    var busy by remember(model) { mutableStateOf(false) }
    var lastOutcome by remember(model) { mutableStateOf<ExportOutcome?>(null) }
    // Elapsed-time clock, not an incrementing counter: `SystemClock.
    // elapsedRealtime()` is monotonic (unlike a wall clock, which can jump)
    // and, because `filesShown` below is a pure function of "how long has
    // WRITING been running", the progress readout recovers correctly on
    // any recomposition rather than depending on a step counter that could
    // desync from the model's actual stage.
    var writeStartedAtMs by remember(model) { mutableLongStateOf(0L) }
    var tickNow by remember(model) { mutableLongStateOf(0L) }

    LaunchedEffect(model, busy) {
        if (!busy) return@LaunchedEffect
        while (true) {
            tickNow = SystemClock.elapsedRealtime()
            delay(Motion.DUB_FILE_MS.toLong())
        }
    }

    // No ON_STOP lifecycle observer here, unlike ChopScreen/PadSheetScreen:
    // those release an audio voice because streaming while backgrounded is
    // wrong; a dub is a file write, not audio, and per the brief it may
    // keep running in the background. Nothing here needs to react to
    // ON_STOP — the elapsed-time derivation above is what makes the
    // progress readout correct whenever the screen next recomposes.
    fun startWrite() {
        if (busy || model.stage != ExportWizardModel.Stage.READY || model.blocked) return
        busy = true
        writeStartedAtMs = SystemClock.elapsedRealtime()
        // Symmetric with ChopScreen's SEND handler: real disk work in a
        // try, CancellationException rethrown first, busy cleared in a
        // finally so a failure can't leave the button silently stuck.
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // getExternalFilesDir does real filesystem work (creates
                    // the directory if absent, can return null if external
                    // storage isn't mounted) — resolved here, on IO, not in
                    // the composable body.
                    val root = context.getExternalFilesDir("exports")
                        ?: throw IOException("external storage unavailable")
                    model.write(File(root, kit.name), overwrite = true)
                }
                when (result) {
                    is ExportWizardModel.WriteResult.Done -> {
                        lastOutcome = result.outcome
                        onToast(Copy.DUB_DONE)
                    }
                    is ExportWizardModel.WriteResult.Blocked -> {
                        // Preflight flipped between render and tap (a file
                        // vanished, say) — write() already reset the model
                        // to READY and the refreshed checklist below is the
                        // message per the model's own KDoc; no extra toast.
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onToast("DUB FAILED: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                revision++
            }
        }
    }

    fun eject() {
        if (model.stage != ExportWizardModel.Stage.COMPLETE) return
        model.eject()
        revision++
        onToast(Copy.CARD_EJECTED)
    }

    val filesShown = when {
        busy -> (((tickNow - writeStartedAtMs).coerceAtLeast(0L)) / Motion.DUB_FILE_MS)
            .toInt().coerceIn(0, model.fileCount)
        model.stage == ExportWizardModel.Stage.COMPLETE -> model.fileCount
        else -> 0
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TapeText(kit.name, TapeType.lcdHeader, scheme.lcdInk.tape, Modifier.weight(1f), maxLines = 1)
                TapeText("${model.fileCount} FILES", TapeType.lcdSmall, scheme.lcdInk.tape)
            }
        }

        if (model.stage == ExportWizardModel.Stage.COMPLETE) {
            DoneContent(
                destinationPath = lastOutcome?.primary?.absolutePath ?: "",
                scheme = scheme,
                modifier = Modifier.weight(1f),
            )
            PrimaryAction(label = model.writeLabel, enabled = true, onClick = ::eject)
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PreflightCard(model.preflight, scheme)
                FormatCyclerRow(
                    label = model.formatLabel,
                    // `busy` (Compose-tracked) rather than `model.stage`
                    // directly — `stage` flips to WRITING on the IO thread
                    // inside `write()`, so it can lag a tick behind `busy`
                    // going true; `cycleFormat()` no-ops off READY either
                    // way, but this keeps the button's own enabled state
                    // from racing the plain var.
                    enabled = !busy,
                    scheme = scheme,
                    onTap = { model.cycleFormat(); revision++ },
                )
                DubProgressCard(model, filesShown, busy, scheme)
            }
            PrimaryAction(
                label = model.writeLabel,
                enabled = !busy && !model.blocked && model.stage == ExportWizardModel.Stage.READY,
                onClick = ::startWrite,
            )
        }
    }
}

// ---------- DONE stage ----------

@Composable
private fun DoneContent(destinationPath: String, scheme: Scheme, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TapeText(Copy.EXPORT_DONE, TapeType.lcd(25), scheme.ink.tape, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        TapeText(destinationPath, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    }
}

// ---------- PREFLIGHT ----------

// THE ONLY NON-SCHEME COLOURS IN THIS SCREEN — bin-red is deliberately
// constant across every scheme (same convention PadSheetScreen's own
// EJECT → BIN button uses, HANDOFF.md X2 / TAKES+BIN), so a FAIL reads as
// "red" even in a scheme with no red anywhere else in it.
private val BIN_RED_BORDER = Color(0xFF6A2020)
private val BIN_RED_GLOW = Color(0xFFC86050)

@Composable
private fun PreflightCard(findings: List<Finding>, scheme: Scheme, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("PREFLIGHT", TapeType.pixelSmall, scheme.ink3.tape)
        Column(
            Modifier.fillMaxWidth().sunkenField(scheme).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (finding in findings) {
                FindingRow(finding, scheme)
            }
        }
    }
}

@Composable
private fun FindingRow(finding: Finding, scheme: Scheme) {
    val color = when (finding.severity) {
        Severity.FAIL -> BIN_RED_GLOW
        Severity.WARN -> scheme.warn.tape
        Severity.OK -> scheme.ink2.tape
    }
    val tag = when (finding.severity) {
        Severity.FAIL -> "FAIL"
        Severity.WARN -> "WARN"
        Severity.OK -> "OK"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp / 2)
                .let { if (finding.severity == Severity.FAIL) it.border(1.dp, BIN_RED_BORDER) else it }
                .padding(horizontal = 3.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            TapeText(tag, TapeType.pixelSmall, color)
        }
        TapeText(finding.message, TapeType.pixelSmall, color, Modifier.weight(1f), maxLines = 2)
    }
}

// ---------- FORMAT cycler ----------

@Composable
private fun FormatCyclerRow(label: String, enabled: Boolean, scheme: Scheme, onTap: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("FORMAT", TapeType.pixelSmall, scheme.ink3.tape)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .raisedBevel(scheme)
                .let { if (enabled) it.tapeClick(onTap) else it }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The cycler's longest label ("MPC SESSION (.XPJ) — KITS +
            // GROOVES") wraps to a second line here rather than shrinking
            // below readable size — maxLines=2, fixed 9sp, per the brief.
            TapeText(
                label,
                TapeType.pixel.copy(textAlign = TextAlign.Center),
                if (enabled) scheme.ink.tape else scheme.ink3.tape,
                Modifier.fillMaxWidth(),
                maxLines = 2,
            )
        }
    }
}

// ---------- DUB progress ----------

@Composable
private fun DubProgressCard(model: ExportWizardModel, filesShown: Int, writing: Boolean, scheme: Scheme) {
    val fraction = if (model.fileCount > 0) filesShown.toFloat() / model.fileCount else 0f
    Column(
        Modifier.fillMaxWidth().lcdPanel(scheme).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DubReel(spinning = writing, scheme = scheme, modifier = Modifier.size(20.dp))
            TapeText(model.dubLabel, TapeType.pixelSmall, scheme.lcdInk.tape)
        }
        Box(Modifier.fillMaxWidth().height(14.dp).sunkenField(scheme)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(scheme.lcdInk.tape.copy(alpha = 0.7f)),
            )
        }
        TapeText(model.dubFilesLine(filesShown), TapeType.pixelSmall, scheme.lcdInk.tape.copy(alpha = 0.85f), maxLines = 2)
    }
}

/**
 * A lighter spin than `TapeScreen`'s own `Reel` — that composable is
 * private to `TapeScreen.kt` and extracting it would mean surgery on a
 * file outside this task's scope, so this is a standalone single spoke
 * rather than the wound-tape-disc treatment.
 */
@Composable
private fun DubReel(spinning: Boolean, scheme: Scheme, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "dubReel")
    val cycleAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(Motion.REEL_SPIN_MS, easing = LinearEasing)),
        label = "dubReelAngle",
    )
    // Mirrors while spinning, freezes (doesn't reset) the instant it stops —
    // same trick TapeScreen's own reels use.
    var frozenAngle by remember { mutableStateOf(0f) }
    if (spinning) frozenAngle = cycleAngle

    Canvas(modifier) {
        val strokeWidth = 1.5.dp.toPx()
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        drawCircle(color = scheme.ink2.tape, radius = radius, center = c, style = Stroke(width = strokeWidth))
        rotate(degrees = frozenAngle, pivot = c) {
            val spoke = radius * 0.75f
            drawLine(scheme.ink2.tape, Offset(c.x - spoke, c.y), Offset(c.x + spoke, c.y), strokeWidth = strokeWidth)
            drawLine(scheme.ink2.tape, Offset(c.x, c.y - spoke), Offset(c.x, c.y + spoke), strokeWidth = strokeWidth)
        }
    }
}
