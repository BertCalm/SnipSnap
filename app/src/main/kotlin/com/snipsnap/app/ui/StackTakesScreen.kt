package com.snipsnap.app.ui

import android.util.Log
import androidx.activity.compose.BackHandler
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
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.StackTakes
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * STACK THE TAKES: this pad's real prior takes as its soft velocity
 * zones — recordings the mic actually heard, in place of the softened
 * copies SOFT HITS renders. Reached from PAD SHEET's STACK ▸ button, the
 * same KIT-scoped-overlay shape TAPE SPLICE uses, over the same
 * candidate list (`priorTakes`).
 *
 * One screen: LIVE pinned at the top (it is always the loudest zone —
 * `KitPad.init` demands it), then every recoverable prior take with its
 * age, its peak in dBFS and its length. Tap a row's zone chip to add it
 * to the stack; picks are ordered softest first and ▲ moves one earlier.
 * Three soft zones is the cap ([StackTakes.MAX_SOFT]).
 *
 * Shown, not fixed: a picked take that peaks over LIVE is flagged in
 * words beside the stack ([Copy.stackOverLive]) and left exactly that
 * loud — the user reorders, or keeps it. No auto-gain. And the cost is
 * said before COMMIT, not after: a stacked pad is layered, which locks
 * every single-sample door until SOFT HITS is cleared, and clearing
 * deletes the copies ([Copy.STACK_LOCKS]).
 */
@Composable
fun StackTakesScreen(
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
    var alreadyLayered by remember(entry.dir) { mutableStateOf(false) }
    var live by remember(entry.dir) { mutableStateOf<Candidate?>(null) }
    var candidates by remember(entry.dir) { mutableStateOf<List<Candidate>>(emptyList()) }
    // The pad this screen opened on, by its file - COMMIT's identity check,
    // same reasoning as TapeSpliceScreen's own `liveSampleFile`.
    var liveSampleFile by remember(entry.dir) { mutableStateOf<String?>(null) }
    // Softest first. BinEntries, not row indices: the list never reorders
    // under the picks, but a pick is a specific archive, not a position.
    var picked by remember(entry.dir) { mutableStateOf<List<KitBuilderModel.BinEntry>>(emptyList()) }
    var busy by remember(entry.dir) { mutableStateOf(false) }
    var voice by remember(entry.dir) { mutableStateOf<TapeVoice?>(null) }
    DisposableEffect(entry.dir) { onDispose { voice?.release() } }
    // Losing the foreground stops the preview, same as TAPE SPLICE's own
    // needle step: a ▶ that outlives Home has no way to be stopped.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voice?.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        alreadyLayered = pad.velocityLayers.isNotEmpty()
        val prior = opened.priorTakes(slot)
        // Facts are read once here, on IO - peak and length need the whole
        // file - and the samples are dropped; ▶ re-reads on demand rather
        // than holding every take's audio in memory at once.
        val (liveFacts, rows) = withContext(Dispatchers.IO) {
            Candidate(null, File(entry.dir, pad.sampleFile), loadFacts(File(entry.dir, pad.sampleFile))) to
                prior.map { Candidate(it, it.file, loadFacts(it.file)) }
        }
        live = liveFacts
        candidates = rows
    }

    @Composable
    fun refusalShell(text: String) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StackHeader(onBack, scheme)
            Box(Modifier.fillMaxSize().weight(1f).lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
                TapeText(text, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 4)
            }
        }
    }

    if (loadFailed) {
        refusalShell(Copy.SPLICE_KIT_GONE)
        return
    }
    val m = model
    val liveRow = live
    if (m == null || liveRow == null) {
        Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }
    // Refused in words, not a dead button: PAD SHEET dims STACK ▸ in these
    // two cases but keeps it tappable so the destination can explain.
    if (alreadyLayered) {
        refusalShell(Copy.STACK_ALREADY_LAYERED)
        return
    }
    if (candidates.isEmpty()) {
        refusalShell(Copy.STACK_NEEDS_HISTORY)
        return
    }

    fun togglePick(c: Candidate) {
        val e = c.entry ?: return
        if (e in picked) {
            picked = picked - e
        } else if (picked.size >= StackTakes.MAX_SOFT) {
            onToast(Copy.STACK_MAX_THREE)
        } else if (c.facts == null) {
            onToast(Copy.STACK_TAKE_UNREADABLE)
        } else {
            picked = picked + e
        }
    }

    fun moveEarlier(c: Candidate) {
        val e = c.entry ?: return
        val i = picked.indexOf(e)
        if (i <= 0) return
        picked = picked.toMutableList().also { it[i] = it[i - 1]; it[i - 1] = e }
    }

    fun preview(c: Candidate) {
        if (c.facts == null) {
            onToast(Copy.STACK_TAKE_UNREADABLE)
            return
        }
        scope.launch {
            voice?.release()
            voice = null
            val loaded = withContext(Dispatchers.IO) { loadMono(c.file) }
            if (loaded == null) {
                onToast(Copy.STACK_TAKE_UNREADABLE)
                return@launch
            }
            val v = TapeVoice(loaded.first, loaded.second)
            voice = v
            v.start(0)
        }
    }

    fun commit() {
        if (busy || picked.isEmpty()) return
        busy = true
        val takes = picked
        scope.launch {
            try {
                var refusal: String? = null
                var stackedSoft = 0
                val openedOn = liveSampleFile
                val fresh = withFreshKit(entry.dir) { f ->
                    val freshPad = f.kit.pad(slot)
                    when {
                        freshPad == null -> refusal = Copy.SPLICE_KIT_GONE
                        // Same slot, different pad: these takes aren't its
                        // history, so nothing is written.
                        freshPad.sampleFile != openedOn -> refusal = Copy.SPLICE_PAD_CHANGED
                        // GHOSTS (or another STACK) landed while this screen
                        // was open: the model would refuse too, but in words
                        // a user reads rather than an exception a toast quotes.
                        freshPad.velocityLayers.isNotEmpty() -> refusal = Copy.STACK_ALREADY_LAYERED
                        else -> stackedSoft = f.stackTakes(slot, takes).velocityLayers.size - 1
                    }
                }
                val said = refusal
                if (said == null) {
                    onKitUpdated(fresh.kit)
                    onToast(Copy.stacked(stackedSoft))
                    onBack()
                } else {
                    onToast(said)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("StackTakesScreen", "stack: failed", e)
                onToast(Copy.STACK_FAILED)
            } finally {
                busy = false
            }
        }
    }

    val zoneNames = if (picked.isEmpty()) emptyList() else StackTakes.zoneNames(picked.size)
    val livePeak = liveRow.facts?.peak
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StackHeader(onBack, scheme)
        TapeText(
            "PICK UP TO THREE PRIOR TAKES AS SOFT ZONES UNDER LIVE. FIRST PICKED IS SOFTEST; ▲ MOVES ONE SOFTER.",
            TapeType.pixelSmall,
            scheme.ink3.tape,
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            maxLines = 3,
        )
        // LIVE, pinned: the loudest zone by construction, so it is the
        // reference every other row's level is read against.
        Row(
            Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp).lcdPanel(scheme).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TapeText("LIVE", TapeType.pixelSmall, scheme.amber.tape, Modifier.width(52.dp), maxLines = 1)
            TapeText(liveRow.factsText(), TapeType.pixelSmall, scheme.ink.tape, Modifier.weight(1f), maxLines = 1)
            ActionButton("▶", scheme, enabled = !busy && liveRow.facts != null, accessibilityLabel = "PREVIEW LIVE", modifier = Modifier.width(48.dp), onClick = { preview(liveRow) })
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(candidates, key = { it.file.absolutePath }) { c ->
                val e = c.entry
                val index = if (e == null) -1 else picked.indexOf(e)
                val chip = if (index >= 0) zoneNames[index] else "ADD"
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp).lcdPanel(scheme).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        TapeText(c.ageLabel(), TapeType.pixelSmall, scheme.ink.tape, maxLines = 1)
                        TapeText(c.factsText(), TapeType.pixelSmall, if (c.facts == null) scheme.warn.tape else scheme.ink3.tape, maxLines = 1)
                    }
                    ActionButton("▶", scheme, enabled = !busy && c.facts != null, accessibilityLabel = "PREVIEW THIS TAKE", modifier = Modifier.width(48.dp), onClick = { preview(c) })
                    ActionButton("▲", scheme, enabled = !busy && index > 0, dimmed = index <= 0, accessibilityLabel = "MOVE SOFTER", modifier = Modifier.width(48.dp), onClick = { moveEarlier(c) })
                    ActionButton(chip, scheme, enabled = !busy, lit = index >= 0, dimmed = c.facts == null, modifier = Modifier.width(72.dp), onClick = { togglePick(c) })
                }
            }
        }
        // The stack as it would land, loudest first, with every level
        // warning beside the zone it belongs to - said here, acted on by
        // nobody.
        if (picked.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().lcdPanel(scheme).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val byEntry = candidates.associateBy { it.entry }
                for (i in picked.indices.reversed()) {
                    val c = byEntry[picked[i]]
                    val over = if (livePeak != null && c?.facts != null) StackTakes.overLiveDb(c.facts.peak, livePeak) else null
                    val line = "${zoneNames[i]} · ${c?.factsText() ?: "?"}"
                    TapeText(line, TapeType.pixelSmall, if (over != null) scheme.warn.tape else scheme.ink.tape, maxLines = 1)
                    if (over != null) {
                        TapeText(Copy.stackOverLive(zoneNames[i], over), TapeType.pixelSmall, scheme.warn.tape, maxLines = 2)
                    }
                }
            }
        }
        TapeText(Copy.STACK_NO_GAIN, TapeType.pixelSmall, scheme.ink3.tape, Modifier.fillMaxWidth().padding(horizontal = 4.dp), maxLines = 2)
        TapeText(Copy.STACK_LOCKS, TapeType.pixelSmall, scheme.ink3.tape, Modifier.fillMaxWidth().padding(horizontal = 4.dp), maxLines = 3)
        ActionButton(
            // Batch 3, Task 4: no ▸ — this button commits the picked takes
            // in place; it doesn't navigate anywhere or open a panel, so it
            // doesn't get the glyph. "·" is the house separator for a
            // label-plus-detail pair that isn't one of those two things
            // (`PROG A · THE BREAK`, GrooveScreen.kt).
            when {
                busy -> "STACKING…"
                picked.isEmpty() -> "STACK · PICK A TAKE FIRST"
                else -> "STACK · COMMIT ${picked.size} UNDER LIVE"
            },
            scheme,
            enabled = !busy && picked.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = Layout.PRIMARY_ACTION_H.dp),
            onClick = ::commit,
        )
    }
}

@Composable
private fun StackHeader(onBack: () -> Unit, scheme: Scheme) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .lcdPanel(scheme)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton("◄ PAD", scheme, enabled = true, modifier = Modifier.width(72.dp), onClick = onBack)
        TapeText("STACK THE TAKES", TapeType.lcdHeader, scheme.lcdInk.tape, Modifier.weight(1f).padding(start = 8.dp))
    }
}

/** What a take measures, read once off its file: peak (linear, 0..1+) and length. */
private class Facts(val peak: Float, val seconds: Float)

/** One row: a recoverable prior take ([entry] set) or the live sample ([entry] null), with its [facts] or null when the file wouldn't read. */
private class Candidate(val entry: KitBuilderModel.BinEntry?, val file: File, val facts: Facts?)

/** "LIVE", or this take's age off its real bin timestamp - no invented "what changed" text. */
private fun Candidate.ageLabel(): String = entry?.let { agoLabel(it.binnedAtMillis) } ?: "LIVE"

/** "-6.2 DBFS · 0.41 S", or the honest miss. Locale.ROOT: digits, not display text. */
private fun Candidate.factsText(): String {
    val f = facts ?: return "WON'T READ"
    val db = StackTakes.dbfs(f.peak)
    val dbText = if (db.isFinite()) String.format(Locale.ROOT, "%.1f", db) else "-INF"
    return "$dbText DBFS · ${String.format(Locale.ROOT, "%.2f", f.seconds)} S"
}

/** Coarse relative age off a take's real bin timestamp; duplicated per this codebase's own file-local convention (see TakesBinScreen.kt's and TapeSpliceScreen.kt's copies). */
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

/**
 * Peak and length through [WavReader.readCapped] — the same
 * [TAPE_LOAD_MAX_SEC] ceiling TAPE itself uses — then the samples are
 * dropped. `null` on anything unreadable (a bin entry another process
 * purged, a torn WAV) or an [OutOfMemoryError] the cap didn't prevent.
 */
private fun loadFacts(file: File): Facts? {
    if (!file.isFile) return null
    return try {
        val snip = WavReader.readCapped(file, TAPE_LOAD_MAX_SEC).snip
        Facts(snip.peak(), snip.durationSeconds)
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }
}

/** The ▶ preview's audio: mono-folded samples and their rate, read on demand. Same cap, same honest null. */
private fun loadMono(file: File): Pair<FloatArray, Int>? {
    if (!file.isFile) return null
    return try {
        val snip = WavReader.readCapped(file, TAPE_LOAD_MAX_SEC).snip
        Cleanup.toMono(snip).samples to snip.sampleRate
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * The one correct shape for a kit mutation issued by a screen whose own
 * [KitBuilderModel] was opened earlier, outside any lock — the same
 * file-local copy TapeSpliceScreen.kt and TakesBinScreen.kt each carry.
 * Locks, opens a FRESH model on [kitDir], runs [block], saves once if
 * [block] left it [KitBuilderModel.dirty], and hands the fresh model back.
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
