package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
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
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.SnipStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SNIPS: the shelf-level list of every catch on the phone — every WAV
 * `SnipStore.commit`/`SnipStore.import` ever wrote into `snips/`, not just
 * the newest one TAPE surfaces. Reached from the shelf (`KitsScreen`'s own
 * `SNIPS ▸` button), never KIT-scoped.
 *
 * **→ PAD's kit-gate fix.** SNIPS lives on `AppScreen.KITS`, where `open`
 * (the app's currently-open kit) is null by definition — gating → PAD on an
 * already-open kit would make the button permanently disabled on this
 * screen's own natural entry path, the exact TAPE/CHOP bug this task's brief
 * calls out by name. [onPickPadFor] is the fix: it hands the tapped file up
 * to `App`, which stashes it, routes to the shelf's kit list (this screen
 * closes), and arms the *next* empty-pad long-press inside whichever kit
 * gets opened to assign straight onto that pad — see `App.kt`'s
 * `pendingSnipAssign`/`assignPendingSnip` for the landing side of that
 * hand-off. This screen's own button is simply always enabled and never
 * reads `open` at all.
 *
 * **→ TAPE.** [onOpenInTape] hands the file to `App`, which offers it to
 * `TapeScreen`'s own `lastCommitSource` fallback slot — see `App.kt`'s own
 * comment on `tapeOpenOverride` for the honest limit of what that actually
 * guarantees (TAPE's newest-snip-first priority usually wins regardless).
 *
 * **Playback** reuses [TapeVoice] — the same unity-speed one-shot voice
 * `TapeScreen` already streams a decoded WAV through — rather than building
 * a second audio path for what's still just "play one file, stop it on a
 * second tap or on leaving." Only one row plays at a time; starting a new
 * one stops whichever was playing.
 */
@Composable
fun SnipsScreen(
    shelf: KitShelf,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onOpenInTape: (File) -> Unit,
    onPickPadFor: (File) -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    var snips by remember { mutableStateOf<List<SnipStore.Info>>(emptyList()) }
    LaunchedEffect(Unit) {
        snips = withContext(Dispatchers.IO) { SnipStore.listWithInfo(shelf.root) }
    }

    // DELETED SNIPS (name-and-find task): the SNIPS-level equivalent of
    // KitsScreen's own "DELETED KITS ▸" row — reached from SNIPS, not the
    // shelf, since snips (not kits) are what it holds. `binnedCount` gates
    // the button below (shown only when the bin actually holds something,
    // the same locked behavior `binnedKitsCount` already keeps for kits).
    // Keyed on `deletedSnipsOpen` itself, not `Unit` — `App.kt`'s own
    // `binnedKitsCount` effect gives the exact reasoning: EMPTY THE BIN NOW
    // (or a RESTORE) inside DeletedSnipsScreen changes nothing else this
    // effect watches, so without that key the row would keep reading a
    // stale count after the door behind it is empty. The local `+= 1`/`- 1`
    // nudges in doDelete/onRestored below stay too, for instant feedback
    // between refreshes.
    var deletedSnipsOpen by remember { mutableStateOf(false) }
    var binnedCount by remember { mutableStateOf(0) }
    LaunchedEffect(deletedSnipsOpen) {
        binnedCount = withContext(Dispatchers.IO) { SnipStore.binned(shelf.root).size }
    }

    // USED badge: null until the cheap gate below resolves, and the list
    // renders with no badges at all in the meantime — never a guess, per
    // the honesty rule (see [anyPadTaggedWithProvenance]'s own KDoc for why
    // this is cheap even on a shelf with many kits). Keyed on [snips]
    // itself, not `Unit`, and matched against those SAME `Info`/`File`
    // instances — not a second, independent `listWithInfo` call — so a
    // RENAME (`doRename` below reassigns `snips` to a freshly-listed set
    // whose `File`s point at the new path) re-triggers this and the badge
    // set is never compared against stale `File`s from before the rename.
    // Missing this was almost the same regression this whole task exists
    // to fix, just moved one layer up: capturedAtMillis makes a pad's OWN
    // provenance survive a rename, but the badge only reads correctly if
    // the row it's matched against is refreshed too.
    var usedSnipFiles by remember { mutableStateOf<Set<File>?>(null) }
    LaunchedEffect(snips) {
        usedSnipFiles = withContext(Dispatchers.IO) {
            if (anyPadTaggedWithProvenance(shelf.root)) usedSnipsAcrossShelf(shelf.root, snips) else emptySet()
        }
    }

    // One voice at a time: starting a row stops whatever was already
    // playing (including, trivially, itself — a second tap on the same
    // row's own PLAY is how STOP is spelled here).
    var voice by remember { mutableStateOf<TapeVoice?>(null) }
    var playingFile by remember { mutableStateOf<File?>(null) }
    // Bumped by every togglePlay call, whether it starts or stops, and
    // captured by that call's own coroutine as `myToken` below — the
    // rapid-double-tap guard. Without it: tap row A (kicks off A's IO
    // decode), tap row B before A's decode resolves — at tap-B time
    // `playingFile` is still null (A hasn't set it yet, it's still
    // decoding), so B's own `stopPlayback()` is a no-op too, and BOTH
    // coroutines run to completion, each eventually doing
    // `voice = v; v.start(0)` — whichever resolves second silently
    // clobbers the `voice` var out from under the first with no `.stop()`
    // ever reaching it, leaving two snips audibly overlapping and one
    // `TapeVoice`'s daemon thread invisible to `DisposableEffect`'s
    // cleanup (which only ever sees whatever `voice` holds last). Checked
    // right after the decode, before a `TapeVoice` is even built: if a
    // later call has already claimed the token, this one's result is
    // simply dropped — nothing was started, so there's nothing to
    // stop/release.
    var playToken by remember { mutableStateOf(0) }
    // A manual stop/row-switch only ever needs to signal, not block the UI
    // thread on a join — TapeVoice.stop() is documented as safe for exactly
    // that caller. release() (which does join, briefly) is reserved for the
    // screen's own teardown below, the one true "call once, for good" exit.
    fun stopPlayback() {
        voice?.stop()
        voice = null
        playingFile = null
    }
    // Leaving the screen (◄ SHELF, or SNIPS closing under a → PAD/→ TAPE
    // navigation) must not leave a voice streaming into a screen that's
    // gone — the same contract TapeScreen's own DisposableEffect(voice)
    // keeps for its one long-lived voice.
    DisposableEffect(Unit) { onDispose { voice?.release() } }

    fun togglePlay(info: SnipStore.Info) {
        val myToken = ++playToken
        if (playingFile == info.file) {
            stopPlayback()
            return
        }
        stopPlayback()
        scope.launch {
            val mono = withContext(Dispatchers.IO) {
                // info.file is a SNIP, bounded by SnipStore.IMPORT_MAX_SEC (180s) or
                // real-time mic capture — readCapped's 600s ceiling is defense in
                // depth, not expected to ever bind.
                runCatching { Cleanup.toMono(WavReader.readCapped(info.file, TAPE_LOAD_MAX_SEC).snip) }.getOrNull()
            }
            // Superseded by a later togglePlay call (this row again, or a
            // different one) while the decode was in flight — see
            // `playToken`'s own KDoc above.
            if (playToken != myToken) return@launch
            if (mono == null || mono.frameCount <= 0) {
                onToast("CAN'T PLAY THIS SNIP")
                return@launch
            }
            val v = TapeVoice(mono.samples, mono.sampleRate)
            voice = v
            playingFile = info.file
            v.start(0)
            // No end-of-playback signal from TapeVoice itself (it just runs
            // its own thread to completion) — this is the auto-stop so the
            // row's toggle doesn't read PLAYING forever after a short snip
            // finishes on its own. Compared by THIS voice instance, not by
            // file: a stop-then-restart of the SAME file inside one delay
            // window is a different `TapeVoice` (`v`) even though
            // `playingFile` reads identical either way, so comparing files
            // here would let this stale wake-up stop a brand-new playback
            // of the same snip out from under it.
            delay((mono.durationSeconds * 1000).toLong().coerceAtLeast(0))
            if (voice === v) stopPlayback()
        }
    }

    if (deletedSnipsOpen) {
        // Placed here, past `voice`/`playToken`/`stopPlayback`/
        // `DisposableEffect` above (NOT at the top of the function): an
        // early return before those hooks would mean a PLAY in flight when
        // DELETED SNIPS opens is orphaned — the coroutine's captured
        // `playToken` still matches, so it still assigns a fresh `voice`
        // into a state slot this branch never composes again, and the
        // `DisposableEffect` that would have released it was never
        // registered either. Stopping first closes that window; keeping
        // the hooks composed either way is the belt this relies on.
        // Everything BELOW this point (confirmDelete/renameTarget/the main
        // BackHandler/the dialogs) is still skipped while this shows, so
        // DELETED SNIPS owns Back entirely on its own with no shared
        // `enabled =` gate to keep in sync.
        stopPlayback()
        DeletedSnipsScreen(
            shelf = shelf,
            onBack = { deletedSnipsOpen = false },
            onToast = onToast,
            onRestored = {
                binnedCount = (binnedCount - 1).coerceAtLeast(0)
                scope.launch {
                    snips = withContext(Dispatchers.IO) { SnipStore.listWithInfo(shelf.root) }
                }
            },
        )
        return
    }

    var confirmDelete by remember { mutableStateOf<SnipStore.Info?>(null) }
    var renameTarget by remember { mutableStateOf<SnipStore.Info?>(null) }
    // Mirrors the header's own ◄ SHELF chip — disabled while either dialog
    // is up so that dialog's own BackHandler below (composed only while
    // it's showing) is the one Back reaches first. Paired the same way
    // ConventionTest.kt's own law documents: while confirmDelete OR
    // renameTarget is non-null (this one dark), the relevant dialog
    // composes its own unconditional BackHandler in that exact window, so
    // Back is never left with zero enabled callbacks.
    BackHandler(enabled = confirmDelete == null && renameTarget == null) { onBack() }
    fun doDelete(info: SnipStore.Info) {
        confirmDelete = null
        if (playingFile == info.file) stopPlayback()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { SnipStore.delete(info.file) }
            if (ok) {
                snips = snips.filter { it.file != info.file }
                binnedCount += 1
                onToast(Copy.snipDeleted(info.displayName))
            } else {
                // Law 3: say exactly what happened — a second delete racing
                // this one, most plausibly, is the only way this fails.
                onToast(Copy.SNIP_DELETE_FAILED)
            }
        }
    }
    fun doRename(info: SnipStore.Info, newName: String) {
        renameTarget = null
        scope.launch {
            val renamed = withContext(Dispatchers.IO) { SnipStore.rename(info.file, newName) }
            if (renamed != null) {
                snips = withContext(Dispatchers.IO) { SnipStore.listWithInfo(shelf.root) }
                // The name it actually landed under, read straight off the
                // returned file rather than echoing `newName` — the same
                // "never trust the typed string, trust the result" posture
                // `Copy.kitRenamed`'s own call sites keep.
                onToast(Copy.snipRenamed(SnipStore.displayName(renamed)))
                if (playingFile == info.file) playingFile = renamed
            } else {
                onToast(Copy.SNIP_RENAME_FAILED)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .lcdPanel(scheme)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderChip("◄ SHELF", scheme, Modifier.width(72.dp), onClick = onBack)
                Spacer(Modifier.weight(1f))
                TapeText("SNIPS", TapeType.lcdHeader, scheme.lcdInk.tape)
                Spacer(Modifier.weight(1f))
                TapeText(
                    "${snips.size} · ${humanSize(snips.sumOf { it.sizeBytes })}",
                    TapeType.lcdSmall,
                    scheme.amber.tape,
                )
            }

            // DELETED SNIPS: gated on the bin actually holding something —
            // KitsScreen's own "DELETED KITS ▸" row, one level down: a user
            // who has never deleted a snip sees no door to an empty room
            // at all (locked behavior).
            if (binnedCount > 0) {
                ActionButton(
                    "DELETED SNIPS ▸ $binnedCount WAITING",
                    scheme,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { deletedSnipsOpen = true },
                )
            }

            if (snips.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .lcdPanel(scheme)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Plain, not the tape-metaphor voice (`Copy`'s own quips) —
                    // this screen's own locked tone, per the task brief.
                    TapeText("NO SNIPS YET", TapeType.lcdSmall, scheme.lcdInk.tape)
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .sunkenField(scheme)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(snips, key = { it.file.name }) { info ->
                        SnipRow(
                            info = info,
                            playing = playingFile == info.file,
                            used = usedSnipFiles?.contains(info.file) == true,
                            onTogglePlay = { togglePlay(info) },
                            onPickPad = { onPickPadFor(info.file) },
                            onOpenTape = { onOpenInTape(info.file) },
                            onRename = { renameTarget = info },
                            onDelete = { confirmDelete = info },
                        )
                    }
                }
            }
        }

        confirmDelete?.let { target ->
            val cancelDelete = { confirmDelete = null }
            DeleteConfirmDialog(
                onCancel = cancelDelete,
                onConfirm = { doDelete(target) },
            )
            // Innermost: Back cancels exactly like CANCEL, never DELETE.
            BackHandler(onBack = cancelDelete)
        }

        renameTarget?.let { target ->
            val cancelRename = { renameTarget = null }
            SnipRenameDialog(
                initialName = target.displayName,
                onCancel = cancelRename,
                onConfirm = { newName -> doRename(target, newName) },
            )
            // Innermost: Back cancels exactly like CANCEL, never RENAME.
            BackHandler(onBack = cancelRename)
        }
    }
}

/**
 * Every [DrumClass] keyed by its own [AutoPlace.nameFor] output — the
 * reverse of the map that named a confidently-classified snip in the first
 * place, so a row can colour its name the same way the pad grid colours a
 * pad ([Schemes.classColor]) without this screen needing to store the
 * class separately. A name that doesn't match any of these (a legacy or
 * unclassified snip's "SNIP" fallback, or a user's own free-typed rename)
 * simply carries no class colour — never a guessed one.
 */
private val CLASS_BY_NAME: Map<String, DrumClass> = DrumClass.entries.associateBy { AutoPlace.nameFor(it) }

@Composable
private fun SnipRow(
    info: SnipStore.Info,
    playing: Boolean,
    used: Boolean,
    onTogglePlay: () -> Unit,
    onPickPad: () -> Unit,
    onOpenTape: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = LocalScheme.current
    val nameColor = CLASS_BY_NAME[info.name]?.let { Schemes.classColor(it).tape } ?: scheme.ink.tape
    Column(
        Modifier
            .fillMaxWidth()
            .raisedBevel(scheme)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Primary field: the name, class-coloured when the
                // classifier earned it — the pad grid's own "name + class
                // colour, legible at a glance" standard. Time and size are
                // secondary, same as today, just demoted a line.
                TapeText(info.displayName, TapeType.marker, nameColor, maxLines = 1)
                TapeText(
                    "${relativeTime(info.capturedAtMillis)} · ${humanSize(info.sizeBytes)}",
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                )
            }
            if (used) {
                Box(
                    Modifier
                        .lcdPanel(scheme)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    TapeText("USED", TapeType.pixelSmall, scheme.amber.tape)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(
                if (playing) "■ STOP" else "▶ PLAY",
                scheme,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = onTogglePlay,
            )
            // Always enabled — never gated on an already-open kit. See this
            // file's own KDoc and App.kt's `pendingSnipAssign` for why.
            ActionButton("→ PAD", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onPickPad)
            ActionButton("→ TAPE", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onOpenTape)
        }
        // RENAME paired with DELETE — KitsScreen's own KitRow shape for the
        // same two actions.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("RENAME", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onRename)
            DeleteButton(scheme, Modifier.weight(1f), onClick = onDelete)
        }
    }
}

/**
 * "DELETE THIS SNIP? IT WAITS IN DELETED SNIPS FOR 30 DAYS." — replaces the
 * old "CAN'T UNDO.", which this task's own bin makes false the moment it
 * ships (a string that lies is exactly what this project's copy laws exist
 * to catch). `KitsScreen.kt`'s own `KitDeleteConfirmDialog` names its
 * screen the same way, now that one exists for snips too. Same scrim +
 * raisedBevel shape as `App.kt`'s own `BlockedDialog` / `KitsScreen.kt`'s
 * `StarterMenu`: a `Box` scrim that dismisses on tap, a `Column` that
 * swallows its own tap so that dismiss can't fire through it.
 */
@Composable
private fun DeleteConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // The scrim has no descendant text of its own (the dialog
            // card below is a separate merge boundary, per its own
            // no-op tapeClick), so it needs an explicit label — reusing
            // the visible CANCEL button's own word rather than inventing
            // a second term for the same action.
            .tapeClick(label = "CANCEL", onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                .tapeClick(label = null) { }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("DELETE THIS SNIP? IT WAITS IN DELETED SNIPS FOR 30 DAYS.", TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                DeleteButton(scheme, Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}

/**
 * "RENAME SNIP" — `KitsScreen.kt`'s own `KitRenameDialog`, copied verbatim
 * (per that file's own house convention: screen-local composables are
 * duplicated, not hoisted) and pointed at a snip's own filename-encoded
 * name instead of `kit.json`'s. RENAME is disabled while the typed name
 * fails [Names.isMpcSafe] — the same refusal `SnipStore.rename` would give,
 * surfaced before the tap instead of after.
 */
@Composable
private fun SnipRenameDialog(initialName: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    val scheme = LocalScheme.current
    var name by remember { mutableStateOf(initialName) }
    val safe = Names.isMpcSafe(name)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // No descendant text of its own — labelled with the same
            // word the visible CANCEL button below uses.
            .tapeClick(label = "CANCEL", onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                .tapeClick(label = null) { }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("RENAME SNIP", TapeType.lcdSmall, scheme.ink.tape)
            Box(
                Modifier
                    .fillMaxWidth()
                    .sunkenField(scheme)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TapeType.marker.copy(color = scheme.ink.tape),
                    cursorBrush = SolidColor(scheme.ink.tape),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!safe) {
                TapeText(
                    "NAMES CAN'T HOLD / \\ : * ? \" < > | OR END IN A DOT/SPACE.",
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                    maxLines = 2,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton("RENAME", scheme, enabled = safe, modifier = Modifier.weight(1f), onClick = { onConfirm(name) })
            }
        }
    }
}

@Composable
private fun DeleteButton(scheme: Scheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(4.dp))
            .border(2.dp, BIN_RED_BORDER, RoundedCornerShape(4.dp))
            .tapeClick(label = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText("DELETE", TapeType.pixel, BinRedGlow)
    }
}

/**
 * Duplicated from `PadCaptureScreen.kt`'s own private `HeaderChip` rather
 * than exported — every screen-local button in this codebase stays private
 * to its own file (see `TakesBinScreen.kt`'s own copy and its comment on the
 * house convention).
 */
@Composable
private fun HeaderChip(label: String, scheme: Scheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(3.dp))
            .tapeClick(label = null, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
    }
}

/**
 * Same shape as `TakesBinScreen.kt`'s own private `agoLabel` — duplicated,
 * not hoisted, per that file's own stated convention ("do it if a clean
 * one-liner, else duplicate with a comment"). [capturedAtMillis] is the
 * `snip_<millis>.wav` timestamp `SnipStore.listWithInfo` already parsed, not
 * a file mtime.
 */
private fun relativeTime(capturedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val diffMs = (nowMillis - capturedAtMillis).coerceAtLeast(0)
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

/**
 * No existing byte-formatting helper anywhere in the repo (grepped first,
 * per the brief) — this is the first. Plain B under 1 KB, one decimal past
 * that; a SNIPS row's file is never large enough to need GB.
 */
private fun humanSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/**
 * The USED badge's cheap gate (task brief): short-circuits on the very
 * first tagged pad it finds, reading one kit's `kit.json` at a time via
 * `KitStore.list` (a directory listing) + `KitStore.load` (a lazy per-kit
 * read inside `any`'s own predicate) — NOT `KitShelf.list()`, which eagerly
 * parses every kit up front regardless of whether the first one already
 * answered the question. On this app's very first launch after Task 2
 * ships, every pad on every kit is untagged (Task 2 only tags going
 * forward), so this still reads every kit.json once — there's no way to
 * know the answer is "no" without checking each — but it stops there,
 * never going on to build the full per-snip match set [usedSnipsAcrossShelf]
 * would, which is the actual work worth skipping for a guaranteed-empty
 * result. Checks both provenance keys [SnipStore.isUsedBy] can match on
 * (`"file"`, the legacy-only key, and `"capturedAtMillis"`, the durable one
 * `SnipStore.provenanceTag` adds alongside it) — this gate is a superset of
 * that match, so it can never say "nothing to build" while the full scan
 * would go on to find something.
 */
private fun anyPadTaggedWithProvenance(root: File): Boolean =
    KitStore.list(root).any { dir ->
        runCatching { KitStore.load(dir) }.getOrNull()?.pads?.any { pad ->
            pad.source["file"]?.isNotBlank() == true || pad.source["capturedAtMillis"]?.isNotBlank() == true
        } == true
    }

/**
 * Only called once [anyPadTaggedWithProvenance] says it's worth it: every
 * snip in [snips] that some pad on some kit under [root] genuinely
 * references, per [SnipStore.isUsedBy] — the one honesty check, reused
 * here rather than a second copy of its capturedAtMillis-first/file-
 * fallback logic that could quietly drift from it.
 */
private fun usedSnipsAcrossShelf(root: File, snips: List<SnipStore.Info>): Set<File> {
    val pads = KitStore.list(root)
        .mapNotNull { dir -> runCatching { KitStore.load(dir) }.getOrNull() }
        .flatMap { it.pads }
    return snips.filter { info -> pads.any { pad -> SnipStore.isUsedBy(pad, info.file) } }.map { it.file }.toSet()
}

// Duplicated, not hoisted (see this file's own `DeleteButton`/`HeaderChip`
// comments) — BIN red is deliberately constant across every scheme so a
// delete action reads as "red" even in a scheme with no red anywhere else.
// The glow half moved to Schemes.BIN_RED_GLOW / theme.BinRedGlow
// (accessibility audit finding 5) — a single tuned token, not a duplicated
// literal.
private val BIN_RED_BORDER = Color(0xFF6A2020)
