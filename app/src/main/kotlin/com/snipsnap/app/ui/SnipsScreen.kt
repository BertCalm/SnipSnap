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
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
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

    // USED badge: null until the cheap gate below resolves, and the list
    // renders with no badges at all in the meantime — never a guess, per
    // the honesty rule (see [anyPadTaggedWithSourceFile]'s own KDoc for why
    // this is cheap even on a shelf with many kits).
    var usedFileNames by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) {
        usedFileNames = withContext(Dispatchers.IO) {
            if (anyPadTaggedWithSourceFile(shelf.root)) usedFileNamesAcrossShelf(shelf.root) else emptySet()
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
                runCatching { Cleanup.toMono(WavReader.read(info.file)) }.getOrNull()
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

    var confirmDelete by remember { mutableStateOf<SnipStore.Info?>(null) }
    // Mirrors the header's own ◄ SHELF chip — disabled while the delete
    // dialog is up so that dialog's own BackHandler below (composed only
    // while it's showing) is the one Back reaches first.
    BackHandler(enabled = confirmDelete == null) { onBack() }
    fun doDelete(info: SnipStore.Info) {
        confirmDelete = null
        if (playingFile == info.file) stopPlayback()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { SnipStore.delete(info.file) }
            if (ok) {
                snips = snips.filter { it.file != info.file }
                onToast("SNIP DELETED.")
            } else {
                // Law 3: say exactly what happened — a second delete racing
                // this one, most plausibly, is the only way this fails.
                onToast("DELETE FAILED. THE FILE MAY ALREADY BE GONE.")
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
                            used = usedFileNames?.contains(info.file.name) == true,
                            onTogglePlay = { togglePlay(info) },
                            onPickPad = { onPickPadFor(info.file) },
                            onOpenTape = { onOpenInTape(info.file) },
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
    }
}

@Composable
private fun SnipRow(
    info: SnipStore.Info,
    playing: Boolean,
    used: Boolean,
    onTogglePlay: () -> Unit,
    onPickPad: () -> Unit,
    onOpenTape: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = LocalScheme.current
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
                TapeText(relativeTime(info.capturedAtMillis), TapeType.marker, scheme.ink.tape)
                TapeText(humanSize(info.sizeBytes), TapeType.pixelSmall, scheme.ink2.tape)
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
        DeleteButton(scheme, Modifier.fillMaxWidth(), onClick = onDelete)
    }
}

/**
 * "DELETE THIS SNIP? CAN'T UNDO." — the exact confirm text the brief locks
 * in. Same scrim + raisedBevel shape as `App.kt`'s own `CaptureBlockedDialog`
 * / `KitsScreen.kt`'s `StarterMenu`: a `Box` scrim that dismisses on tap,
 * a `Column` that swallows its own tap so that dismiss can't fire through it.
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
            TapeText("DELETE THIS SNIP? CAN'T UNDO.", TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                DeleteButton(scheme, Modifier.weight(1f), onClick = onConfirm)
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
        TapeText("DELETE", TapeType.pixel, BIN_RED_GLOW)
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
 * never going on to build the full per-pad name set [usedFileNamesAcrossShelf]
 * would, which is the actual work worth skipping for a guaranteed-empty
 * result.
 */
private fun anyPadTaggedWithSourceFile(root: File): Boolean =
    KitStore.list(root).any { dir ->
        runCatching { KitStore.load(dir) }.getOrNull()?.pads?.any { pad ->
            pad.source["file"]?.isNotBlank() == true
        } == true
    }

/** Only called once [anyPadTaggedWithSourceFile] says it's worth it: every tagged pad's source file name, across every kit on the shelf. */
private fun usedFileNamesAcrossShelf(root: File): Set<String> =
    KitStore.list(root)
        .mapNotNull { dir -> runCatching { KitStore.load(dir) }.getOrNull() }
        .flatMap { it.pads }
        .mapNotNull { pad -> pad.source["file"]?.takeIf { it.isNotBlank() } }
        .toSet()

// Duplicated, not hoisted (see this file's own `DeleteButton`/`HeaderChip`
// comments) — BIN red is deliberately constant across every scheme so a
// delete action reads as "red" even in a scheme with no red anywhere else.
private val BIN_RED_BORDER = Color(0xFF6A2020)
private val BIN_RED_GLOW = Color(0xFFC86050)
