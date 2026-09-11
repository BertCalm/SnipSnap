package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.MixVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.shell.Arranger
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ARRANGE: [Arranger] surfaced — GROOVE's own four variations laid into a
 * song structure (intro → theme → variation → the turn → reprise → outro)
 * instead of cycled one at a time. Reached from GROOVE's own "SONG ▸"
 * button, not one of MenuRow's fixed ten — same GROOVE-scoped-overlay shape
 * as PAD SHEET's GRAIN FIELD (see `App.kt`'s `arrangeOpen`).
 *
 * The plan (`Arranger.arrange`) is cheap — pure note-list arithmetic over
 * an already-loaded groove — and loads on entry. The mixdown
 * (`Arranger.mixdown`, full synthesis through every pad) is not: it only
 * ever runs once PLAY is first pressed, cached per plan so REROLL (a new
 * seed) is what invalidates it, matching the CLI's own `--mixdown` being
 * an opt-in flag rather than something `arrange` does unasked.
 *
 * Every section states the rule that picked its clip
 * ([Arranger.Section.reason]) — tap a row to read it in the LCD readout
 * below the list; during playback the readout follows whichever section
 * is actually sounding, tracked by a wall-clock estimate against the
 * mixdown's own section-start frames (cosmetic only — [MixVoice] is the
 * transport of record, this is just what draws the needle).
 */
@Composable
fun ArrangeScreen(
    entry: KitShelf.Entry,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    swingPercent: Int? = null,
    feel: Float = 0f,
    feelTemplate: GrooveFeel.Template? = null,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    val kitDir = entry.dir
    val kit = entry.kit

    var seed by remember(kitDir) { mutableIntStateOf(0) }
    var plan by remember(kitDir) { mutableStateOf<Arranger.Arrangement?>(null) }
    var refusal by remember(kitDir) { mutableStateOf<String?>(null) }
    var loading by remember(kitDir) { mutableStateOf(true) }
    var firstLoad by remember(kitDir) { mutableStateOf(true) }
    LaunchedEffect(kitDir, seed) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { Arranger.arrange(kit, kitDir, seed, swingPercent, feel, feelTemplate) }
        }
        result.onSuccess {
            plan = it
            refusal = null
            if (!firstLoad) onToast(Copy.ARRANGE_REROLLED)
        }.onFailure { e ->
            plan = null
            refusal = e.message ?: Copy.ARRANGE_NEEDS_GROOVE
        }
        loading = false
        firstLoad = false
    }

    // The mixdown, built lazily on first PLAY and cached per plan — a
    // reroll (a new `plan` value) is what throws this cache out.
    var mix by remember(plan) { mutableStateOf<Arranger.Mix?>(null) }
    var mixing by remember(plan) { mutableStateOf(false) }

    var voice by remember(kitDir) { mutableStateOf<MixVoice?>(null) }
    var playing by remember(kitDir) { mutableStateOf(false) }
    var posSeconds by remember(kitDir) { mutableFloatStateOf(0f) }
    var selectedIndex by remember(plan) { mutableIntStateOf(0) }

    fun stopPlayback() {
        voice?.stop()
        voice = null
        playing = false
        posSeconds = 0f
    }
    DisposableEffect(kitDir) { onDispose { voice?.release() } }

    fun playMix(m: Arranger.Mix) {
        val v = MixVoice(m.snip.samples, m.snip.sampleRate)
        voice = v
        posSeconds = 0f
        playing = true
        v.start()
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
        if (mixing) return
        mixing = true
        scope.launch {
            val built = withContext(Dispatchers.IO) { runCatching { Arranger.mixdown(kit, kitDir, currentPlan) } }
            mixing = false
            built.onSuccess { m ->
                // A REROLL landed while this was mixing — the plan this
                // mix was built for isn't the one on screen any more.
                if (plan !== currentPlan) return@onSuccess
                mix = m
                playMix(m)
            }.onFailure { e ->
                onToast("MIX FAILED: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun reroll() {
        if (loading || mixing) return
        stopPlayback()
        seed++
    }

    // The needle: a wall-clock estimate of playback position, matched
    // against the mixdown's own section-start frames. MixVoice is the
    // actual transport; this only ever draws where it should be.
    LaunchedEffect(playing, kitDir) {
        if (!playing) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                val dtNanos = (now - lastNanos).coerceAtLeast(0)
                lastNanos = now
                posSeconds += dtNanos / 1_000_000_000f
            }
            val total = mix?.snip?.durationSeconds
            if (total != null && posSeconds >= total) stopPlayback()
        }
    }
    val activeIndex = mix?.let { m ->
        val frame = (posSeconds * m.snip.sampleRate).toInt()
        m.sectionStarts.indexOfLast { it <= frame }.takeIf { it >= 0 }
    }
    val readoutIndex = if (playing) (activeIndex ?: selectedIndex) else selectedIndex

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(Layout.LCD_HEADER_H.dp).lcdPanel(scheme).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HeaderChip("◄ GROOVE", scheme, Modifier.width(72.dp), onClick = { stopPlayback(); onBack() })
            TapeText("ARRANGE", TapeType.lcd(21), scheme.lcdInk.tape)
            val p = plan
            TapeText(
                if (p != null) "${p.totalBars} BARS · ${p.sections.size} SECTIONS" else "",
                TapeType.lcdSmall,
                scheme.amber.tape,
            )
        }

        val currentPlan = plan
        when {
            loading -> Box(Modifier.fillMaxSize().lcdPanel(scheme), contentAlignment = Alignment.Center) {
                TapeText("PLANNING…", TapeType.lcdSmall, scheme.lcdInk.tape)
            }
            refusal != null || currentPlan == null -> Box(
                Modifier.fillMaxSize().lcdPanel(scheme).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(refusal ?: Copy.ARRANGE_NEEDS_GROOVE, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
            }
            else -> {
                val sections = currentPlan.sections
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).sunkenField(scheme).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sections, key = { it.name }) { section ->
                        val i = sections.indexOf(section)
                        SectionRow(
                            index = i,
                            section = section,
                            selected = i == readoutIndex,
                            active = playing && i == activeIndex,
                            onTap = { selectedIndex = i },
                        )
                    }
                }

                Column(
                    Modifier.fillMaxWidth().lcdPanel(scheme).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val shown = sections.getOrNull(readoutIndex)
                    TapeText(
                        shown?.name?.uppercase() ?: "",
                        TapeType.pixel,
                        scheme.amber.tape,
                    )
                    TapeText(
                        shown?.reason?.uppercase() ?: "",
                        TapeType.pixelSmall,
                        scheme.ink3.tape,
                        maxLines = 2,
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton(
                        if (playing) "■ STOP" else if (mixing) Copy.ARRANGE_MIXING else "► PLAY",
                        scheme,
                        enabled = !mixing,
                        modifier = Modifier.weight(1f),
                        onClick = ::togglePlay,
                    )
                    ActionButton(
                        "REROLL ⚄",
                        scheme,
                        enabled = !loading && !mixing,
                        modifier = Modifier.weight(1f),
                        onClick = ::reroll,
                    )
                }
                TapeText(
                    "SAME KIT, LAID OUT AS A SONG — TAP A SECTION TO READ WHY IT'S THERE.",
                    TapeType.pixelSmall,
                    scheme.ink3.tape,
                    Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SectionRow(
    index: Int,
    section: Arranger.Section,
    selected: Boolean,
    active: Boolean,
    onTap: () -> Unit,
) {
    val scheme = LocalScheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(if (selected) scheme.field.tape else scheme.lcd.tape, RoundedCornerShape(4.dp))
            .border(1.dp, if (active) scheme.amber.tape else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            // A text child already covers the accessible name (index, name,
            // bar count) — see `tapeClick`'s own KDoc on when null is right.
            .tapeClick(label = null) { onTap() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TapeText(if (active) "▶" else "%02d".format(java.util.Locale.ROOT, index + 1), TapeType.pixel, scheme.amber.tape)
            TapeText(section.name.uppercase(), TapeType.pixel, scheme.ink.tape)
        }
        TapeText("${section.bars} BARS", TapeType.pixelSmall, scheme.ink2.tape)
    }
}

/** Duplicated, not hoisted — see `SnipsScreen.kt`'s own copy and its comment on the house convention for screen-local buttons. */
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
