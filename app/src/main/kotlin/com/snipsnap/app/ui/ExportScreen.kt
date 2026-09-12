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
import com.snipsnap.shell.DubStamp
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
    /**
     * Whether the remembered export format has already been restored onto
     * [model] (see `PREF_EXPORT_FORMAT`). A plain latch, not Compose state:
     * nothing renders from it, it only stops the restore running twice.
     *
     * It lives here rather than in a `remember` because the session is what
     * survives a remount — leaving EXPORT and coming back re-runs the
     * screen's effects against this same wizard, and restoring a second
     * time would quietly undo a pick the user made in between.
     */
    var formatRestored: Boolean = false
    var busy by mutableStateOf(false)
    var lastOutcome by mutableStateOf<ExportOutcome?>(null)
    var writeStartedAtMs by mutableLongStateOf(0L)

    /**
     * What a first DUB found already at the destination, or null when
     * nothing is armed (September UAT, finding 18). The next DUB writes over
     * it; anything that changes *where* the write would land disarms it, so
     * a confirmation can never be spent on a destination the user never saw.
     *
     * Hoisted alongside [busy] for the same reason: this screen remounts on
     * every tab switch, and an armed confirm that forgot itself on remount
     * would make the second tap a fresh first tap — an infinite loop the
     * user cannot escape.
     */
    var overwriting by mutableStateOf<File?>(null)
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

    /** The format list, open or shut. Shut on arrival: the row says what is picked. */
    var formatPickerOpen by remember(model) { mutableStateOf(false) }

    /**
     * A dub locks the row (`enabled = !busy`), and a locked row cannot be
     * tapped shut — so an open list would vanish while the header kept
     * showing ▴, claiming a state the screen was not in, until the write
     * finished. Shut it when the write starts instead.
     */
    LaunchedEffect(session.busy) {
        if (session.busy) formatPickerOpen = false
    }

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

    /**
     * Start on the format this user actually uses (September UAT, finding
     * 7): every visit used to begin at index 0 however many times you had
     * exported `.xtd`.
     *
     * Guarded by the session's own latch rather than by the wizard's index:
     * a remount re-runs this effect against the same wizard, and restoring
     * twice would undo a pick made in between. The earlier version tested
     * `formatIx == 0`, which tied the screen to the enum's order and would
     * have started misbehaving the day the first entry changed.
     *
     * Stage-checked too, so `revision++` cannot fire for a `setFormat` that
     * a non-READY wizard quietly refused. An id that no longer exists (a
     * format dropped between releases) simply leaves the default alone.
     */
    LaunchedEffect(model) {
        if (session.formatRestored) return@LaunchedEffect
        session.formatRestored = true
        if (model.stage != ExportWizardModel.Stage.READY) return@LaunchedEffect
        val remembered = prefs.getString(PREF_EXPORT_FORMAT, null)?.let { ExportFormat.byId(it) }
        if (remembered != null && remembered != model.format) {
            model.setFormat(remembered)
            revision++
        }
    }
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

    /**
     * Record the dub for the shelf's chip — and never let that recording turn
     * a successful dub into a failure.
     *
     * The files are already written when this runs. A sidecar is ancillary:
     * losing it costs a chip that reads DRAFT until the next dub, which is
     * the same under-claim [DubStamp.read] already makes for an unreadable
     * stamp. Letting an IOException here escape into `startWrite`'s catch
     * would show DUB FAILED over a card that actually has the kit on it —
     * a worse lie than the one finding 15 set out to fix.
     */
    suspend fun stampDub(kitDir: File, card: String?) {
        withContext(Dispatchers.IO) {
            runCatching { DubStamp.write(kitDir, DubStamp.Stamp(System.currentTimeMillis(), cardTree = card)) }
                .onFailure { Log.w(TAG, "stampDub: the dub landed but its stamp did not", it) }
        }
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
                    // overwrite only on the second tap, and only when the
                    // armed path is the one this write is actually about
                    // (finding 18). A stale arm - format changed, kit
                    // changed - is not consent for this destination.
                    model.write(destRoot, overwrite = session.overwriting != null)
                }
                when (result) {
                    is ExportWizardModel.WriteResult.WouldOverwrite -> {
                        // Nothing was written. Arm, and say what the next
                        // tap would replace - by name, so the decision is
                        // about that thing rather than an abstract "sure?".
                        session.overwriting = result.path
                        onToast(Copy.dubWouldOverwrite(result.path.name))
                    }
                    is ExportWizardModel.WriteResult.Done -> {
                        session.overwriting = null
                        session.lastOutcome = result.outcome
                        val card = cardTree
                        // The shelf's chip, recorded here because this is the
                        // only place that knows a dub happened (September UAT,
                        // finding 15). Written before the card copy is
                        // attempted and again after it lands, so a copy that
                        // fails leaves DUBBED rather than a false ON CARD.
                        stampDub(session.dir, card = null)
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
                            // Only now is it really on the card, so only now
                            // does the stamp name one.
                            stampDub(session.dir, card = card.toString())
                            onToast(Copy.DUB_DONE_CARD)
                        }
                    }
                    is ExportWizardModel.WriteResult.Blocked -> {
                        session.overwriting = null
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
                readBack = outcome?.readBack.orEmpty(),
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
                FindingsCard(model.preflight, scheme)
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
                FormatPickerRow(
                    current = model.format,
                    open = formatPickerOpen,
                    // `session.busy` (Compose-tracked) rather than
                    // `model.stage` directly — `stage` flips to WRITING on
                    // the IO thread inside `write()`, so it can lag a tick
                    // behind `busy` going true; `setFormat()` no-ops off
                    // READY either way, but this keeps the row's own
                    // enabled state from racing the plain var.
                    enabled = !session.busy,
                    scheme = scheme,
                    onToggle = { formatPickerOpen = !formatPickerOpen },
                    onPick = { picked ->
                        model.setFormat(picked)
                        // A different format writes to a different place, so
                        // a confirmation taken against the old destination is
                        // not consent for this one (finding 18).
                        session.overwriting = null
                        // Remembered for next time: finding 7's other half.
                        // Written on the pick rather than on the dub, so a
                        // change of mind is kept even if nothing is written.
                        prefs.edit().putString(PREF_EXPORT_FORMAT, picked.id).apply()
                        formatPickerOpen = false
                        revision++
                    },
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
private fun DoneContent(
    destinationPath: String,
    /** READ BACK: the written file re-read and diffed against the kit. Empty only before it has run; a format X-Ray can't read (MIDI, SFZ, DecentSampler) is one SKIP row, not an empty list. */
    readBack: List<Finding>,
    scheme: Scheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapeText(Copy.EXPORT_DONE, TapeType.lcd(25), scheme.ink.tape, maxLines = 2)
        // Where the write actually landed, in plain words — regardless of
        // whether SHARE is offered below, or ever tapped. No claim about
        // which file browser can reach it, just the fact of it.
        TapeText(Copy.EXPORT_SAVED_TO, TapeType.pixelSmall, scheme.ink3.tape)
        TapeText(destinationPath, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
        if (readBack.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            // The file just written, read back through X-Ray and diffed pad
            // by pad against what the kit asked for — a check on the writers,
            // said in the same rows PREFLIGHT uses. The caveat under it is
            // the honest limit: our reader agreeing is not the Live III
            // agreeing.
            FindingsCard(readBack, scheme, title = "READ BACK")
            TapeText(Copy.READ_BACK_CAVEAT, TapeType.pixelSmall, scheme.ink3.tape, Modifier.fillMaxWidth(), maxLines = 3)
        }
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
 *
 * `internal`, not file-private: the kit shelf reads the same key to decide
 * whether a kit's dub stamp may honestly say ON CARD (September UAT, finding
 * 15). One key with two readers - if it ever gains a second definition, the
 * chip and the wizard will disagree about which card is in the phone.
 */
internal const val PREF_CARD_TREE = "export_card_tree"

/**
 * The format picked last time (an [ExportFormat.id]).
 *
 * The September UAT's finding 7: every visit to EXPORT started at index 0,
 * so someone who exports `.xtd` every day walked the same three taps every
 * day. A format is a property of how you work, not of this one visit.
 *
 * `internal` for the same reason [PREF_CARD_TREE] is: SETUP reads it to show
 * what EXPORT will open on (September UAT, finding 23). One key, two readers,
 * one owner — SETUP only ever reads it.
 */
internal const val PREF_EXPORT_FORMAT = "export_format"

// The glow half moved to Schemes.BIN_RED_GLOW / theme.BinRedGlow
// (accessibility audit finding 5) — a single tuned token, not a
// duplicated literal; the border half is untouched.
private val BIN_RED_BORDER = Color(0xFF6A2020)

/** One titled list of [Finding] rows — PREFLIGHT before the write, READ BACK after it, same rows either way. */
@Composable
private fun FindingsCard(findings: List<Finding>, scheme: Scheme, modifier: Modifier = Modifier, title: String = "PREFLIGHT") {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText(title, TapeType.pixelSmall, scheme.ink3.tape)
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
        // "Not checked" reads quieter than OK on purpose: it's an absence
        // of a claim, not a pass.
        Severity.SKIP -> scheme.ink3.tape
    }
    val tag = when (finding.severity) {
        Severity.FAIL -> "FAIL"
        Severity.WARN -> "WARN"
        Severity.OK -> "OK"
        Severity.SKIP -> "SKIP"
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
        "${Copy.CARD_PICKED} ${Copy.cardName(tree.lastPathSegment)}"
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
                // `enabled` is forwarded into combinedClickable itself
                // rather than gating the call — the same principle as
                // tapeClick's own `enabled` param: dropping the call
                // entirely would remove this row from the accessibility
                // tree instead of announcing it as unavailable (finding 12).
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onLongClickLabel = if (tree != null) "FORGET THIS CARD" else null,
                    onLongClick = if (tree != null) onForget else null,
                    onClick = onPick,
                )
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
        // September UAT, finding 21: holding this row is the only way to
        // forget a card anywhere in the app.
        //
        // Drawn only when the gesture it names actually exists, which takes
        // BOTH of the conditions the row above puts on it: a card to forget
        // (onLongClick is null without one) and `enabled` (the whole
        // combinedClickable is dropped while a dub is in flight). Miss
        // either and the legend advertises an action that cannot run - the
        // same "furniture describing nothing" this gate exists to prevent.
        if (tree != null && enabled) {
            TapeText(Copy.EXPORT_CARD_LEGEND, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 1)
        }
    }
}

/**
 * FORMAT: what is picked, and — when opened — every format with the reason
 * you would pick it.
 *
 * This was a one-way cycler (September UAT, findings 7 and 8). Eight states,
 * no back step: DECENTSAMPLER cost seven taps, one tap past your target cost
 * seven more, and [ExportFormat.cyclerLabel] was the only copy a format ever
 * got, so nothing said when EXPANSION beats XPN.
 *
 * A list, not a dialog: the same inline-panel move KIT's key picker makes,
 * so it costs no new machinery and cannot strand the user behind a scrim.
 * The closed row still reads exactly as the cycler did, so nothing is lost
 * for someone who liked it.
 */
@Composable
private fun FormatPickerRow(
    current: ExportFormat,
    open: Boolean,
    enabled: Boolean,
    scheme: Scheme,
    onToggle: () -> Unit,
    onPick: (ExportFormat) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("FORMAT", TapeType.pixelSmall, scheme.ink3.tape)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .raisedBevel(scheme)
                // Always clickable, `enabled` forwarded rather than
                // dropped: a screen reader is told this control is
                // temporarily unavailable instead of it silently vanishing
                // from the tree (accessibility audit finding 12).
                .tapeClick(label = "FORMAT: ${current.cyclerLabel}, ${if (open) "CLOSE" else "OPEN"}", enabled = enabled, onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The longest label ("MPC SESSION (.XPJ) — KITS + GROOVES")
            // wraps to a second line here rather than shrinking below
            // readable size — maxLines=2, fixed 9sp, per the brief.
            TapeText(
                if (open) "${current.cyclerLabel} ▴" else "${current.cyclerLabel} ▾",
                TapeType.pixel.copy(textAlign = TextAlign.Center),
                if (enabled) scheme.ink.tape else scheme.ink3.tape,
                Modifier.fillMaxWidth(),
                maxLines = 2,
            )
        }
        if (open && enabled) {
            Column(
                Modifier.fillMaxWidth().lcdPanel(scheme).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (f in ExportFormat.entries) {
                    val picked = f == current
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            // Names both the format and the reason finding
                            // 8 added below (f.why) — the whole point of
                            // that finding was that TalkBack (and everyone
                            // else) should hear why, not just which.
                            .tapeClick(label = "${f.cyclerLabel}: ${f.why}", onClick = { onPick(f) })
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        TapeText(
                            if (picked) "▸ ${f.cyclerLabel}" else f.cyclerLabel,
                            TapeType.pixel,
                            if (picked) scheme.amber.tape else scheme.lcdInk.tape,
                            maxLines = 2,
                        )
                        // Finding 8: the reason, not just the name.
                        TapeText(f.why, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 2)
                    }
                }
            }
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
