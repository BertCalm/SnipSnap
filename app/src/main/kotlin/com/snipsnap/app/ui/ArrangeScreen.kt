package com.snipsnap.app.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.Exports
import com.snipsnap.app.KitShelf
import com.snipsnap.app.LocalHaptics
import com.snipsnap.app.ShareOut
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdInk2
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.Names
import com.snipsnap.kit.SongPlan
import com.snipsnap.shell.Arranger
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SongExport
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ARRANGE: [Arranger] surfaced — GROOVE's own variations laid into a song
 * structure (intro → theme → variation → the turn → reprise → outro)
 * instead of cycled one at a time. When the kit has a user program the
 * variation section is that, not a derived clip: `Arranger` reads it from
 * the sidecar itself, so nothing here has to pass it in. Reached from GROOVE's own "SONG ▸"
 * button: a bench (`Bench.SONG`) over BEAT, opened with GROOVE's swing, feel and
 * template and drawn only for the kit it was opened for (see `App.kt`'s
 * `Bench.SONG` branch).
 *
 * The plan (`Arranger.arrange`) is cheap — pure note-list arithmetic over
 * an already-loaded groove — and loads on entry. The mixdown
 * (`Arranger.mixdown`, full synthesis through every pad) is not: it only
 * ever runs once PLAY is first pressed, cached per plan so any edit (a new
 * plan) is what invalidates it, matching the CLI's own `--mixdown` being
 * an opt-in flag rather than something `arrange` does unasked. EXPORT hands
 * that cached mix to `SongExport` when there is one (and keeps the one it
 * had to render, for PLAY).
 *
 * Every section states the rule that picked its clip
 * ([Arranger.Section.reason]) — tap a row to read it in the LCD readout
 * below the list; during playback the readout follows whichever section
 * the speaker is playing.
 *
 * Playing is live ([LivePlay], song plan Phase 2): PLAY starts at the
 * selected section, LOOP repeats the section playing, a row tapped while
 * playing plays next (when the pattern playing comes round), and KEEP THIS
 * ORDER makes the sections heard, in the order heard, the song. All of it
 * moves through the one cached mix, so nothing renders twice.
 *
 * The song is the person's to keep and change ([SongKeeper]): reorder,
 * repeats, swap a section's clip for any other the kit can play (BEAT's
 * own programs included), duplicate, delete, add, lock, and REROLL, which
 * redraws only what is unlocked. A song is kept per kit in the project
 * ([projectDir]; null when no project is open, and then it is edited
 * on screen only, which the caption says). With nothing kept the first
 * plan is the arranger's own, seed 0, and it is kept from then on.
 */
@Composable
fun ArrangeScreen(
    entry: KitShelf.Entry,
    /** The back chip's words: `Navigation.backChip`, so they name the screen Back lands on. */
    backLabel: String,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    swingPercent: Int? = null,
    feel: Float = 0f,
    feelTemplate: GrooveFeel.Template? = null,
    /** The project's folder, where `song.json` lives; null when no project is open. */
    projectDir: File? = null,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val kitDir = entry.dir
    val kit = entry.kit

    val keeper = remember(kitDir) {
        SongKeeper(kit, kitDir, projectDir, scope, swingPercent, feel, feelTemplate, onToast)
    }
    LaunchedEffect(keeper) { keeper.open() }
    val plan = keeper.plan

    // The mixdown, built lazily on first PLAY and cached per plan — a
    // edit (a new `plan` value) is what throws this cache out.
    var mix by remember(plan) { mutableStateOf<Arranger.Mix?>(null) }
    var mixing by remember(plan) { mutableStateOf(false) }
    // EXPORT is writing; PLAY and REROLL wait, so no second render starts beside it.
    var exporting by remember(kitDir) { mutableStateOf(false) }

    // Live play: from the selected section, LOOP, a tapped row next, KEEP THIS ORDER.
    val live = remember(kitDir) { LivePlay() }
    val playing = live.playing
    fun stopPlayback() = live.stop()
    DisposableEffect(kitDir) { onDispose { live.release() } }
    // An edit makes a different song: what was playing, and what was played, are the old one's.
    LaunchedEffect(plan) { live.forget() }

    fun playMix(m: Arranger.Mix) {
        live.start(m, keeper.selected.coerceIn(0, (m.sectionStarts.size - 1).coerceAtLeast(0)))
    }

    fun togglePlay() {
        val currentPlan = plan ?: return
        if (playing) {
            stopPlayback()
            return
        }
        val cached = mix
        if (cached != null) {
            playMix(cached)
            return
        }
        if (mixing || exporting) return
        mixing = true
        scope.launch {
            val built = withContext(Dispatchers.IO) { runCatching { Arranger.mixdown(kit, kitDir, currentPlan) } }
            mixing = false
            built.onSuccess { m ->
                // A REROLL landed while this was mixing — the plan this
                // mix was built for isn't the one on screen any more.
                if (keeper.plan !== currentPlan) return@onSuccess
                mix = m
                playMix(m)
            }.onFailure { e ->
                Log.e("ArrangeScreen", "mixdown: failed", e)
                onToast(Copy.MIX_FAILED)
            }
        }
    }

    // EXPORT: the song as an MPC project and a WAV under
    // exports/<kit>/song/, then the WAV to the share sheet - GROOVE's
    // SHARE shape (`shareBeat`). The folder is the app's own, so a second
    // export of the same song writes over the first. The mix PLAY already
    // rendered is handed through so the song is not rendered twice; when
    // there is none yet, the write renders it and it is kept for PLAY.
    fun exportSong() {
        val currentPlan = plan ?: return
        if (exporting || mixing) return
        exporting = true
        onToast(Copy.ARRANGE_EXPORT_BUSY)
        val held = mix
        scope.launch {
            try {
                val stem = Names.sanitizeStem(kit.name)
                val written = offMain {
                    val root = Exports.dir(context) ?: throw IOException("external storage unavailable")
                    SongExport.write(kit, kitDir, currentPlan, File(File(root, stem), "song"), overwrite = true, mixdown = true, mix = held)
                }
                val wav = written.mixdown!!
                // Kept for PLAY unless an edit has since put another plan on screen.
                if (keeper.plan === currentPlan && mix == null) mix = wav.mix
                haptics.land()
                val where = "exports/$stem/song"
                if (ShareOut.send(context, wav.file, ShareOut.WAV_MIME, kit.name)) {
                    onToast(Copy.songShared(wav.file.name, where))
                } else {
                    onToast(Copy.songKept(where))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("ArrangeScreen", "exportSong: failed", e)
                onToast(Copy.ARRANGE_EXPORT_FAILED)
            } finally {
                exporting = false
            }
        }
    }

    fun reroll() {
        if (keeper.loading || mixing || exporting) return
        keeper.reroll()
    }

    // KEEP THIS ORDER: what was heard becomes the song. While playing it stops first, so the take is what was heard up to the tap.
    fun keepOrder() {
        if (keeper.loading || mixing || exporting) return
        live.stop()
        keeper.keepOrder(live.take)
    }

    // The needle follows the speaker (LivePlay.tick), once a frame while playing.
    LaunchedEffect(playing, kitDir) {
        if (!playing) return@LaunchedEffect
        while (isActive && live.playing) {
            withFrameNanos { }
            live.tick()
        }
    }
    val activeIndex = live.active
    val selectedIndex = keeper.selected.coerceIn(0, ((plan?.sections?.size ?: 1) - 1).coerceAtLeast(0))
    val readoutIndex = if (playing) (activeIndex ?: selectedIndex) else selectedIndex

    // System Back stops the mix first, as the header chip does.
    BackHandler { stopPlayback(); onBack() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(Layout.LCD_HEADER_H.dp).lcdPanel(scheme).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HeaderChip(backLabel, scheme, Modifier.widthIn(min = 72.dp), onClick = { stopPlayback(); onBack() })
            TapeText("SONG", TapeType.lcd(21), scheme.lcdInk.tape)
            val p = plan
            TapeText(
                if (p != null) "${Copy.countOf(p.totalBars, "BAR", "BARS")} · ${Copy.countOf(p.sections.size, "SECTION", "SECTIONS")}" else "",
                TapeType.lcdSmall,
                scheme.amber.tape,
            )
        }

        val currentPlan = plan
        when {
            keeper.loading -> Box(Modifier.fillMaxSize().lcdPanel(scheme), contentAlignment = Alignment.Center) {
                TapeText("PLANNING…", TapeType.lcdSmall, scheme.lcdInk.tape)
            }
            keeper.refusal != null || currentPlan == null -> Box(
                Modifier.fillMaxSize().lcdPanel(scheme).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(keeper.refusal ?: Copy.ARRANGE_NEEDS_GROOVE, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
            }
            else -> {
                SectionList(
                    sections = currentPlan.sections,
                    readoutIndex = readoutIndex,
                    activeIndex = if (playing) activeIndex else null,
                    names = keeper.names,
                    queued = if (playing) live.queued else null,
                    onTap = {
                        keeper.selected = it
                        if (live.playing) live.queue(it)
                    },
                    modifier = Modifier.weight(1f),
                )
                ReadoutPanel(currentPlan.sections.getOrNull(readoutIndex))
                SongEditor(keeper, currentPlan.sections.getOrNull(selectedIndex), selectedIndex, currentPlan.sections.size, busy = mixing || exporting)
                val from = currentPlan.sections.getOrNull(selectedIndex)
                LiveRow(
                    live = live,
                    from = selectedIndex + 1,
                    fromName = from?.name ?: "",
                    mixing = mixing,
                    exporting = exporting,
                    onPlay = ::togglePlay,
                    onKeep = ::keepOrder,
                )
                TransportRow(
                    mixing = mixing,
                    exporting = exporting,
                    onReroll = ::reroll,
                    onExport = ::exportSong,
                )
                TapeText(
                    if (playing) Copy.SONG_CAPTION_PLAYING else if (keeper.keeps) Copy.SONG_CAPTION_KEPT else Copy.SONG_CAPTION_NOT_KEPT,
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                    Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
            }
        }
    }
}

/** The song's rows. Each is its own composable so this screen's main method stays under the dex register ceiling. */
@Composable
private fun SectionList(
    sections: List<Arranger.Section>,
    readoutIndex: Int,
    activeIndex: Int?,
    names: Map<Int, String>,
    /** The section that plays next, while playing. */
    queued: Int?,
    onTap: (Int) -> Unit,
    modifier: Modifier,
) {
    val scheme = LocalScheme.current
    LazyColumn(
        modifier.fillMaxWidth().sunkenField(scheme).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Keyed by place: a section's name is not unique once a song can
        // hold the same section twice.
        itemsIndexed(sections, key = { i, _ -> i }) { i, section ->
            SectionRow(
                index = i,
                section = section,
                selected = i == readoutIndex,
                active = i == activeIndex,
                next = i == queued,
                names = names,
                onTap = { onTap(i) },
            )
        }
    }
}

@Composable
private fun ReadoutPanel(shown: Arranger.Section?) {
    val scheme = LocalScheme.current
    Column(
        Modifier.fillMaxWidth().lcdPanel(scheme).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TapeText(shown?.name?.uppercase() ?: "", TapeType.pixel, scheme.amber.tape)
        TapeText(shown?.reason?.uppercase() ?: "", TapeType.pixelSmall, scheme.lcdInk2, maxLines = 2)
    }
}

/**
 * PLAY from the selected section (or STOP), LOOP with its state in words,
 * and KEEP THIS ORDER, which has something to keep once a play has been
 * heard (or while one plays).
 */
@Composable
private fun LiveRow(
    live: LivePlay,
    from: Int,
    fromName: String,
    mixing: Boolean,
    exporting: Boolean,
    onPlay: () -> Unit,
    onKeep: () -> Unit,
) {
    val scheme = LocalScheme.current
    val playing = live.playing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ActionButton(
            if (playing) Copy.SONG_STOP else if (mixing) Copy.ARRANGE_MIXING else Copy.songPlayFrom(from),
            scheme,
            enabled = !mixing && (playing || !exporting),
            accessibilityLabel = if (playing || mixing) null else Copy.songA11yPlayFrom(from, fromName),
            modifier = Modifier.weight(1f),
            onClick = onPlay,
        )
        ActionButton(
            if (live.loop) Copy.SONG_LOOP_ON else Copy.SONG_LOOP_OFF,
            scheme,
            enabled = true,
            lit = live.loop,
            modifier = Modifier.weight(1f),
            onClick = live::toggleLoop,
        )
        ActionButton(
            Copy.SONG_KEEP_ORDER,
            scheme,
            enabled = !mixing && !exporting && (playing || live.take.isNotEmpty()),
            modifier = Modifier.weight(1.4f),
            onClick = onKeep,
        )
    }
}

@Composable
private fun TransportRow(
    mixing: Boolean,
    exporting: Boolean,
    onReroll: () -> Unit,
    onExport: () -> Unit,
) {
    val scheme = LocalScheme.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ActionButton(
            Copy.SONG_REROLL,
            scheme,
            enabled = !mixing && !exporting,
            accessibilityLabel = Copy.SONG_A11Y_REROLL,
            modifier = Modifier.weight(1f),
            onClick = onReroll,
        )
        ActionButton(
            "EXPORT",
            scheme,
            enabled = !mixing && !exporting,
            working = exporting,
            accessibilityLabel = "EXPORT SONG",
            modifier = Modifier.weight(1f),
            onClick = onExport,
        )
    }
}

/**
 * What can be done to the selected section, in two rows of four, then the
 * clip picker when SWAP CLIP is open, then + SECTION and UNDO. A disabled
 * button is how the screen says "not here": MOVE UP on the first row, DELETE
 * on the only section.
 */
@Composable
private fun SongEditor(keeper: SongKeeper, section: Arranger.Section?, index: Int, count: Int, busy: Boolean) {
    val scheme = LocalScheme.current
    var picking by remember(keeper) { mutableStateOf(false) }
    val repeatMax = section?.let { Arranger.maxRepeats(it.clip) } ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(Copy.SONG_UP, scheme, enabled = !busy && index > 0, accessibilityLabel = Copy.SONG_A11Y_UP, modifier = Modifier.weight(1f)) { keeper.move(-1) }
            ActionButton(Copy.SONG_DOWN, scheme, enabled = !busy && index < count - 1, accessibilityLabel = Copy.SONG_A11Y_DOWN, modifier = Modifier.weight(1f)) { keeper.move(1) }
            ActionButton(Copy.SONG_REPEAT_LESS, scheme, enabled = !busy && (section?.repeats ?: 1) > 1, accessibilityLabel = Copy.SONG_A11Y_REPEAT_LESS, modifier = Modifier.weight(1f)) { keeper.repeat(-1) }
            ActionButton(Copy.SONG_REPEAT_MORE, scheme, enabled = !busy && (section?.repeats ?: 1) < repeatMax, accessibilityLabel = Copy.SONG_A11Y_REPEAT_MORE, modifier = Modifier.weight(1f)) { keeper.repeat(1) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(Copy.SONG_SWAP, scheme, enabled = !busy && section != null, lit = picking, modifier = Modifier.weight(1f)) { picking = !picking }
            ActionButton(Copy.SONG_DUPLICATE, scheme, enabled = !busy && count < SongPlan.MAX_SECTIONS, modifier = Modifier.weight(1f)) { keeper.duplicate() }
            ActionButton(Copy.SONG_DELETE, scheme, enabled = !busy && count > 1, modifier = Modifier.weight(1f)) { keeper.delete() }
            ActionButton(
                if (section?.locked == true) Copy.SONG_UNLOCK else Copy.SONG_LOCK,
                scheme,
                enabled = !busy && section != null,
                lit = section?.locked == true,
                modifier = Modifier.weight(1f),
            ) { keeper.toggleLock() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val bass = section?.bass == true
            val pullUp = section?.pullUp == true
            ActionButton(if (bass) Copy.SONG_BASS_ON else Copy.SONG_BASS_OFF, scheme, enabled = !busy && section != null, lit = bass, modifier = Modifier.weight(1f)) { keeper.toggleBass() }
            ActionButton(if (pullUp) Copy.SONG_PULL_UP_ON else Copy.SONG_PULL_UP_OFF, scheme, enabled = !busy && section != null && index > 0, lit = pullUp, modifier = Modifier.weight(1f)) { keeper.togglePullUp() }
        }
        if (picking && section != null && !busy) {
            ClipPicker(keeper.picks, current = section.ref, names = keeper.names) { ref ->
                picking = false
                keeper.swap(ref)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(Copy.SONG_ADD, scheme, enabled = !busy && count < SongPlan.MAX_SECTIONS, modifier = Modifier.weight(1f)) { keeper.add() }
            val last = keeper.undoStack.lastOrNull()
            ActionButton(
                if (last != null) Copy.songUndo(last.verb) else Copy.SONG_UNDO,
                scheme,
                enabled = !busy && last != null,
                modifier = Modifier.weight(1.5f),
            ) { keeper.undo() }
        }
    }
}

/** Every clip the kit can play, as chips in a row that scrolls. The one in use is lit and says NOW. */
@Composable
private fun ClipPicker(picks: List<Arranger.Pick>, current: String, names: Map<Int, String>, onPick: (String) -> Unit) {
    val scheme = LocalScheme.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (pick in picks) {
            val now = pick.ref == current
            val name = Copy.songClipLabel(pick.ref, names)
            ActionButton(
                if (now) "$name · ${Copy.SONG_PICK_NOW}" else name,
                scheme,
                enabled = true,
                lit = now,
                accessibilityLabel = if (now) Copy.songPickNow(name) else Copy.songPickSwap(name),
                modifier = Modifier.widthIn(min = 96.dp),
            ) { onPick(pick.ref) }
        }
    }
}

@Composable
private fun SectionRow(
    index: Int,
    section: Arranger.Section,
    selected: Boolean,
    active: Boolean,
    next: Boolean,
    names: Map<Int, String>,
    onTap: () -> Unit,
) {
    val scheme = LocalScheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(if (selected) scheme.field.tape else scheme.lcd.tape, RoundedCornerShape(4.dp))
            // Selected is the app's border-plus-word (pressedBevel's 2 dp
            // accent line and the SELECTED line below), not the fill alone:
            // the field fill is a hair from the lcd fill, and a state shown
            // by a fill that close is colour-only. The playing row keeps its
            // amber, at the same 2 dp, with the play glyph.
            .border(
                if (selected) 2.dp else 1.dp,
                if (active) scheme.amber.tape else if (selected) scheme.accent.tape else scheme.grayEdge.tape,
                RoundedCornerShape(4.dp),
            )
            .semantics { this.selected = selected }
            .tapeClick(
                label = "SECTION ${index + 1}: ${section.name.uppercase()}, ${Copy.songClipLabel(section.ref, names)}, " +
                    "${Copy.countOf(section.repeats, "TIME", "TIMES")}, ${Copy.countOf(section.bars, "BAR", "BARS")}" +
                    (if (section.locked) ", ${Copy.SONG_LOCKED}" else "") +
                    (if (section.bass) ", ${Copy.SONG_ROW_BASS}" else "") +
                    (if (section.pullUp && index > 0) ", ${Copy.SONG_ROW_PULL_UP}" else "") +
                    (if (next) ", ${Copy.SONG_ROW_NEXT}" else ""),
            ) { onTap() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TapeText(if (active) "▶" else "%02d".format(java.util.Locale.ROOT, index + 1), TapeType.pixel, scheme.amber.tape)
            Column {
                TapeText(section.name.uppercase(), TapeType.pixel, scheme.ink.tape)
                // Reserved on every row, blank on the others, so no row
                // grows only when it is the chosen one. A lock is a word
                // too, never a colour alone.
                TapeText(
                    listOfNotNull(
                        Copy.SONG_ROW_NEXT.takeIf { next },
                        "SELECTED".takeIf { selected },
                        Copy.SONG_LOCKED.takeIf { section.locked },
                        Copy.SONG_ROW_BASS.takeIf { section.bass },
                        Copy.SONG_ROW_PULL_UP.takeIf { section.pullUp && index > 0 },
                    ).joinToString(" · "),
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            TapeText("${Copy.songClipLabel(section.ref, names)} ×${section.repeats}", TapeType.pixelSmall, scheme.ink.tape)
            TapeText(Copy.countOf(section.bars, "BAR", "BARS"), TapeType.pixelSmall, scheme.ink2.tape)
        }
    }
}

/** Duplicated, not hoisted — see `SnipsScreen.kt`'s own copy and its comment on the house convention for screen-local buttons. */
@Composable
private fun HeaderChip(label: String, scheme: Scheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.lcdInk2, RoundedCornerShape(3.dp))
            .tapeClick(label = label, spoken = spokenBack(label), onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.lcdInk.tape)
    }
}
