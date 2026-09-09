package com.snipsnap.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.snipsnap.app.KitWrites
import com.snipsnap.app.ShareOut
import com.snipsnap.app.theme.BinRedGlow
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.Finding
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.Severity
import com.snipsnap.app.CardWriter
import com.snipsnap.app.PREFS
import com.snipsnap.shell.Copy
import com.snipsnap.shell.ExportWizardModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Scheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "ExportScreen"

/**
 * A dub's live state, hoisted to `App()` (see `App.kt`'s `exportSession`)
 * rather than kept in `ExportScreen`'s own composition. The write itself
 * runs on `appScope` — `App()`'s own `rememberCoroutineScope()`, the same
 * one `PadSheetScreen`'s teardown save uses (`PadSheetScreen.kt:103-110`)
 * — precisely so it survives a MenuRow tab switch instead of being
 * cancelled with it. That means `ExportScreen` itself can unmount and
 * remount around an in-flight write (any tab switch does this to its
 * composable), and whichever instance is current needs to find the *same*
 * write in progress rather than spinning up a second one against the same
 * kit's files. [busy]/[lastOutcome]/[writeStartedAtMs] are `mutableStateOf`
 * properties on this plain class — not `remember`-scoped — so they keep
 * notifying whichever `ExportScreen` composition is current, however many
 * times it mounts and unmounts, because the object itself (not the
 * composable) is what `App.kt` keeps alive.
 */
class ExportSession(val dir: File, val kit: Kit, val model: ExportWizardModel) {
    var busy by mutableStateOf(false)
    var lastOutcome by mutableStateOf<ExportOutcome?>(null)
    var writeStartedAtMs by mutableLongStateOf(0L)
}

/**
 * EXPORT: the wizard's Compose surface over `ExportWizardModel`'s tested
 * stage machine (READY → WRITING → COMPLETE). Preflight, the format
 * cycler and the dub write all live in `:shell`/`:kit`; this file renders
 * the checklist, forwards taps, paces the dub-progress animation on its
 * own clock (the model exposes labels, not a live byte count — see the
 * model's own KDoc), and always writes to `getExternalFilesDir("exports")`
 * first — no permission needed. When the user has picked a card (the
 * `CardRow` below, SAF `ACTION_OPEN_DOCUMENT_TREE`, a persistable grant),
 * `startWrite` also copies the outcome onto it through [CardWriter] —
 * `DocumentsContract` calls, not a second write of the export itself. The
 * completion stage always says where the phone-side copy landed
 * (`Copy.EXPORT_SAVED_TO` plus the raw path — DoneContent below), and,
 * when the write produced one self-contained file (XPN or MIDI — see
 * [exportShareMime]'s KDoc), offers SHARE through `ShareOut`, the same
 * FileProvider-backed chooser SHARE and BACKUP already use (`ShareOut.kt`,
 * `res/xml/share_paths.xml`'s `exports/` entry). A format that writes a
 * folder, or a file with a companion (the MPC3/SFZ/DecentSampler
 * multi-file outputs), has no SHARE button — a single content URI can't
 * carry more than one file, and this screen doesn't zip one up to force
 * it.
 *
 * [entry] is read fresh from disk on open (`KitStore.load`), the same
 * "a kit is its folder" rule `PadSheetScreen` follows, rather than
 * trusting `entry.kit`'s possibly-stale in-memory copy — but only when
 * [session] doesn't already hold a live one for this kit's folder; once
 * built, the session (and whatever write it's mid-flight on) persists in
 * `App()` across this screen's own mount/unmount cycles.
 */
@Composable
fun ExportScreen(
    entry: KitShelf.Entry?,
    session: ExportSession?,
    onSessionChange: (ExportSession?) -> Unit,
    appScope: CoroutineScope,
    onToast: (String) -> Unit,
) {
    val scheme = LocalScheme.current

    if (entry == null) {
        EmptyExport(scheme)
        return
    }

    val context = LocalContext.current

    var loadFailed by remember(entry.dir) { mutableStateOf(false) }
    LaunchedEffect(entry.dir) {
        // Already holding a live session for this exact kit folder (built
        // by an earlier mount, possibly still mid-write) — reuse it rather
        // than reloading, which would both discard the in-flight write's
        // visible state and race a second `ExportWizardModel` against the
        // same files.
        if (session != null && session.dir == entry.dir) return@LaunchedEffect
        loadFailed = false
        // KitStore.load reads kit.json; ExportWizardModel's own init runs
        // Preflight.check, which opens and reads every pad WAV's header —
        // both belong off the composition thread, so both happen inside
        // this one IO block rather than splitting the load from the model
        // construction. This snapshot read goes through KitWrites too: a
        // read landing mid-write of this exact kit.json/pad WAV (SET KEY,
        // EVIL TWINS, IN KEY off the KIT screen; any pad's own commit) could
        // otherwise build the session from a torn file or a WAV header
        // that's mid-replace. `:shell` (ExportWizardModel/Preflight) can't
        // see `:app`'s KitWrites, so the lock is taken here, by the caller —
        // same layering as every KitShelf helper (setKey/evilTwins/inKey)
        // below. Only the snapshot read is locked, not the dub itself
        // (`model.write` below): that's seconds-long file-copy IO, exactly
        // what every other KitWrites site keeps outside the lock.
        //
        // `withLock` suspends, so it must sit OUTSIDE `runCatching`: a
        // tab-away/kit-switch cancelling this `LaunchedEffect` while it's
        // waiting on (or holding) the mutex throws `CancellationException`
        // through this block, and `runCatching` catches `Throwable` — it
        // would otherwise swallow the cancellation as an ordinary load
        // failure (`loadFailed = true`, EMPTY_SHELF on a perfectly good
        // kit) instead of letting it propagate.
        val loaded = withContext(Dispatchers.IO) {
            KitWrites.mutex.withLock {
                runCatching {
                    val kit = KitStore.load(entry.dir)
                    ExportSession(entry.dir, kit, ExportWizardModel(kit, entry.dir))
                }.getOrNull()
            }
        }
        if (loaded == null) loadFailed = true else onSessionChange(loaded)
    }

    val activeSession = session
    if (activeSession == null || activeSession.dir != entry.dir) {
        // Still opening the model, the load failed outright, or the
        // session on hand belongs to a different kit and a fresh one is
        // being built — same "still decoding vs. genuinely broken" split
        // ChopScreen/PadSheetScreen use; a blank LCD covers the former.
        if (loadFailed) EmptyExport(scheme) else Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }

    ExportContent(activeSession, context, appScope, scheme, onToast)
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
    session: ExportSession,
    context: Context,
    appScope: CoroutineScope,
    scheme: Scheme,
    onToast: (String) -> Unit,
) {
    val model = session.model
    val kit = session.kit

    // `ExportWizardModel` is a plain mutable class (its `stage`/`formatIx`/
    // `preflight` are ordinary vars, tested and owned by :shell), so — same
    // trick ChopScreen's and PadSheetScreen's own `revision` counters use —
    // this is what forces a recompose after a tap mutates it outside
    // Compose's snapshot system. Local to this mount (not on `session`):
    // it only needs to trigger a recompose of whichever instance is
    // currently composed, not to survive a remount — the first composition
    // after any remount already reads `model`'s current fields directly.
    var revision by remember(model) { mutableIntStateOf(0) }
    val revisionTick = revision

    // Elapsed-time clock, not an incrementing counter: `SystemClock.
    // elapsedRealtime()` is monotonic (unlike a wall clock, which can jump)
    // and, because `filesShown` below is a pure function of "how long has
    // WRITING been running" against `session.writeStartedAtMs` (which
    // *does* survive a remount, being on the hoisted session), the
    // progress readout recovers correctly whether this mount started the
    // write itself or is reconnecting to one already in flight.
    var tickNow by remember(model) { mutableLongStateOf(0L) }

    LaunchedEffect(model, session.busy) {
        if (!session.busy) return@LaunchedEffect
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
    // progress readout correct whenever the screen next recomposes, and
    // `session` (not screen-local state) is what makes it correct even
    // after a full unmount/remount, not just a stop/resume.
    // The card the user picked, or null for the app's own folder. Held in
    // the same prefs the scheme uses: a card is picked once and expected
    // to still be the card next time the app opens, which is why the
    // permission below is taken *persistably* rather than for this
    // process only.
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var cardTree by remember {
        mutableStateOf(prefs.getString(PREF_CARD_TREE, null)?.let { Uri.parse(it) })
    }
    /**
     * Hands a card's grant back. A persistable permission outlives the
     * process — that is the point of it — so nothing but this ends one:
     * not forgetting the card, not picking a different one, not even
     * closing the app. Left alone they pile up, and the platform caps how
     * many an app may hold at once, so the card picked tenth would be the
     * one that fails. It also keeps the row honest: a card the app says
     * it is not using is a card the app can no longer read.
     *
     * Best effort — releasing a grant that was never held throws, and a
     * grant that is already gone is the state we wanted anyway.
     */
    fun releaseCard(uri: Uri?) {
        if (uri == null) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    val cardPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        // Without this the grant dies with the process and the next dub
        // fails with a permission error on a card the user did pick.
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                picked,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!granted) {
            onToast(Copy.CARD_REFUSED)
            return@rememberLauncherForActivityResult
        }
        // The old one goes back only once the new one is held: released
        // first, a picker that then refused would leave the user with
        // neither, having started with a working card.
        val previous = cardTree
        if (previous != null && previous != picked) releaseCard(previous)
        cardTree = picked
        prefs.edit().putString(PREF_CARD_TREE, picked.toString()).apply()
    }

    fun forgetCard() {
        releaseCard(cardTree)
        cardTree = null
        prefs.edit().remove(PREF_CARD_TREE).apply()
    }

    fun startWrite() {
        if (session.busy || model.stage != ExportWizardModel.Stage.READY || model.blocked) return
        session.busy = true
        session.writeStartedAtMs = SystemClock.elapsedRealtime()
        // Launched on `appScope` — App()'s own scope, handed down — not
        // this composable's local `rememberCoroutineScope()`: a dub must
        // survive a MenuRow tab switch instead of being cancelled by the
        // very navigation that would otherwise abandon it mid-write.
        // `busy`/the `CancellationException`-rethrow-first/`finally` shape
        // is otherwise exactly ChopScreen's SEND handler.
        appScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // getExternalFilesDir does real filesystem work (creates
                    // the directory if absent, can return null if external
                    // storage isn't mounted) — resolved here, on IO, not in
                    // the composable body.
                    val root = context.getExternalFilesDir("exports")
                        ?: throw IOException("external storage unavailable")
                    // A self-nesting format (see ExportFormat.selfNesting's
                    // KDoc) already nests the kit's own name inside
                    // `destRoot` itself; handing it our own kit-name
                    // subfolder on top would double- or triple-nest it.
                    val destRoot = if (model.format.selfNesting) {
                        root
                    } else {
                        // Sanitized, not the raw kit name — GrooveScreen's
                        // own MIDI export dir already does this (a kit name
                        // free-typed by a user can carry a path separator or
                        // other filesystem-hostile character); this and
                        // that one agree now.
                        File(root, Names.sanitizeStem(kit.name))
                    }
                    model.write(destRoot, overwrite = true)
                }
                when (result) {
                    is ExportWizardModel.WriteResult.Done -> {
                        session.lastOutcome = result.outcome
                        val card = cardTree
                        if (card == null) {
                            onToast(Copy.DUB_DONE)
                        } else {
                            // The drivers wrote a real File tree, byte for
                            // byte as the tests and the golden fixtures
                            // cover it; this puts that tree on the card.
                            // Only the outcome's own items — handing over
                            // the folder they sit in would carry every
                            // earlier export along with them.
                            withContext(Dispatchers.IO) {
                                CardWriter.copy(
                                    context,
                                    card,
                                    listOfNotNull(result.outcome.primary, result.outcome.companion),
                                )
                            }
                            onToast(Copy.DUB_DONE_CARD)
                        }
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
                // Law 3 in practice: the user gets what failed and what to
                // do about it, not a Java exception's `message` — that
                // detail stays in logcat, where it's actually useful.
                Log.e(TAG, "startWrite: dub failed", e)
                onToast(Copy.DUB_FAILED)
            } finally {
                session.busy = false
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

    // SHARE on the completion stage: the file DUB already wrote, handed to
    // the system chooser through `ShareOut` — the same FileProvider path
    // SHARE and BACKUP use (App.kt's `shareKit`/`backupAll`), not a second
    // sharing implementation. Reads `session.lastOutcome` live rather than
    // closing over a captured value, same as `eject()` above.
    fun shareExport() {
        val outcome = session.lastOutcome ?: return
        val mime = exportShareMime(outcome.format) ?: return
        if (outcome.companion != null || !outcome.primary.isFile) return
        val sent = ShareOut.send(context, outcome.primary, mime, kit.name)
        onToast(if (sent) Copy.EXPORT_SHARE_SENT else Copy.SHARE_NOWHERE)
    }

    val filesShown = when {
        session.busy -> (((tickNow - session.writeStartedAtMs).coerceAtLeast(0L)) / Motion.DUB_FILE_MS)
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
            val outcome = session.lastOutcome
            DoneContent(
                destinationPath = outcome?.primary?.absolutePath ?: "",
                scheme = scheme,
                modifier = Modifier.weight(1f),
            )
            // Only when the write landed one self-contained file (XPN,
            // MIDI — see exportShareMime's KDoc): a folder (PROGRAM_FOLDER,
            // EXPANSION) or a file with a companion (MPC3/SFZ/DecentSampler)
            // can't travel as a single content URI, so there's nothing here
            // for SHARE to offer rather than a disabled button that lies
            // about what a tap would do.
            if (outcome != null && exportShareMime(outcome.format) != null &&
                outcome.companion == null && outcome.primary.isFile
            ) {
                ActionButton(
                    Copy.EXPORT_SHARE_LABEL,
                    scheme,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::shareExport,
                )
            }
            PrimaryAction(label = model.writeLabel, enabled = true, onClick = ::eject)
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PreflightCard(model.preflight, scheme)
                CardRow(
                    tree = cardTree,
                    enabled = !session.busy,
                    scheme = scheme,
                    onPick = { cardPicker.launch(null) },
                    onForget = {
                        forgetCard()
                        onToast(Copy.CARD_FORGOTTEN)
                    },
                )
                FormatCyclerRow(
                    label = model.formatLabel,
                    // `session.busy` (Compose-tracked) rather than
                    // `model.stage` directly — `stage` flips to WRITING on
                    // the IO thread inside `write()`, so it can lag a tick
                    // behind `busy` going true; `cycleFormat()` no-ops off
                    // READY either way, but this keeps the button's own
                    // enabled state from racing the plain var.
                    enabled = !session.busy,
                    scheme = scheme,
                    onTap = { model.cycleFormat(); revision++ },
                )
                DubProgressCard(model, filesShown, session.busy, scheme)
            }
            PrimaryAction(
                label = model.writeLabel,
                enabled = !session.busy && !model.blocked && model.stage == ExportWizardModel.Stage.READY,
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
        // Where the write actually landed, in plain words — regardless of
        // whether SHARE is offered below, or ever tapped. No claim about
        // which file browser can reach it, just the fact of it.
        TapeText(Copy.EXPORT_SAVED_TO, TapeType.pixelSmall, scheme.ink3.tape)
        TapeText(destinationPath, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
    }
}

/**
 * Which MIME a completed export would travel under via SHARE, or null when
 * the format isn't a single self-contained file. Deliberately derived from
 * [ExportFormat] alone rather than also checked against the outcome here —
 * the call sites already gate on `outcome.companion == null &&
 * outcome.primary.isFile`, which is what actually rules out PROGRAM_FOLDER/
 * EXPANSION (a directory) and MPC3_TRACK/MPC3_PROJECT/SFZ/DECENT_SAMPLER
 * (a companion file or folder the chooser's single content URI can't also
 * carry). XPN and MIDI are the two formats where that check can ever pass.
 */
private fun exportShareMime(format: ExportFormat): String? = when (format) {
    ExportFormat.XPN -> ShareOut.ZIP_MIME
    ExportFormat.MIDI -> ShareOut.MIDI_MIME
    else -> null
}

// ---------- PREFLIGHT ----------

// THE ONLY NON-SCHEME COLOURS IN THIS SCREEN — bin-red is deliberately
// constant across every scheme (same convention PadSheetScreen's own
// DELETE → BIN button uses, HANDOFF.md X2 / TAKES+BIN), so a FAIL reads as
// "red" even in a scheme with no red anywhere else in it.
/**
 * The picked card's tree URI. Stored rather than asked for each dub: a
 * card is picked once and is still the card next time the app opens,
 * which is the whole reason the grant is taken persistably.
 */
private const val PREF_CARD_TREE = "export_card_tree"

// The glow half moved to Schemes.BIN_RED_GLOW / theme.BinRedGlow
// (accessibility audit finding 5) — a single tuned token, not a
// duplicated literal; the border half is untouched.
private val BIN_RED_BORDER = Color(0xFF6A2020)

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
        Severity.FAIL -> BinRedGlow
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

/**
 * Where a dub lands. Tapping opens the system's folder picker; holding
 * gives the card back and sends dubs to the phone again.
 *
 * It shows the destination's own last path segment rather than a name
 * from the provider: a tree URI carries no display name we can read
 * without another query, and the segment is what the picker showed the
 * user when they chose it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRow(
    tree: Uri?,
    enabled: Boolean,
    scheme: Scheme,
    onPick: () -> Unit,
    onForget: () -> Unit,
) {
    val label = if (tree == null) {
        Copy.CARD_NONE
    } else {
        "${Copy.CARD_PICKED} ${tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/').orEmpty().ifBlank { "CARD" }}"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("DESTINATION", TapeType.pixelSmall, scheme.ink3.tape)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .raisedBevel(scheme)
                // combinedClickable, not tapeClick, because this row has a
                // long press and tapeClick has only the one gesture — the
                // same pair ChopScreen's class chip uses, and the same
                // reason: it registers real accessibility actions for both.
                .let {
                    if (enabled) {
                        it.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onLongClickLabel = if (tree != null) "FORGET THIS CARD" else null,
                            onLongClick = if (tree != null) onForget else null,
                            onClick = onPick,
                        )
                    } else {
                        it
                    }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
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

@Composable
private fun FormatCyclerRow(label: String, enabled: Boolean, scheme: Scheme, onTap: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("FORMAT", TapeType.pixelSmall, scheme.ink3.tape)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .raisedBevel(scheme)
                .let { if (enabled) it.tapeClick(label = null, onClick = onTap) else it }
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
