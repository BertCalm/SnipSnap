package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Bench
import com.snipsnap.shell.Copy
import com.snipsnap.shell.ExpansionStore
import com.snipsnap.shell.KeysLayout
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.MurmurRoundRobinShelf
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * MAKE (`docs/SCREEN_PLAN_2026_10.md` §3.6): sound from nothing. A list
 * page of four rows that open the labs as benches, and the INSTRUMENTS
 * bench the shelf's instrument rows moved to.
 */

/**
 * The MAKE list: under MAKE A SOUND, SYNTH ▸, SNAP ▸, SURFACE ▸, CONFLUENCE
 * VOICES ▸ and MY VOICE ▸; under WHAT YOU MADE, INSTRUMENTS ▸. Each is a row
 * 72 dp tall that opens its bench, under a header that says what MAKE is
 * and where it lands, and a line under each name that says what the word
 * means (SNAP and SURFACE meant nothing before they were tapped; the words
 * were defined only in HELP, behind the gear). The sub-line is what the
 * lab *is*, a fact that does not change; the plan's "last: RESIN · KICK,
 * to A09" is still not drawn: the labs say what they made in a toast and
 * nowhere structured, so that subtitle would be a parse of a sentence — the
 * labs want a "last made" record first.
 *
 * Each row's accessible name stays exactly `SYNTH ▸` (and so on): the
 * sub-line is drawn but not part of the name, which tests and the
 * uiautomator walks read.
 *
 * [kitName] is the active kit's name, named in the header when [hasKit].
 * [hasKit] is whether a kit is active: every lab lands its sound on the
 * active kit, so without one the header is followed by the one line that
 * says what to do first.
 */
@Composable
fun MakeList(onOpen: (Bench) -> Unit, hasKit: Boolean, onMyVoice: (() -> Unit)? = null, kitName: String? = null) {
    val scheme = LocalScheme.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.LCD_HEADER_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            TapeText(Copy.makeHeader(kitName.takeIf { hasKit }), TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
        }
        if (!hasKit) {
            TapeText(if (onMyVoice == null) Copy.ADD_KIT_FIRST else "Open a kit for new pads. MY VOICE can keep a personal source first.",
                TapeType.pixelSmall, scheme.ink2.tape, Modifier.fillMaxWidth(), maxLines = 3)
        }
        MakeGroupHeading(Copy.MAKE_A_SOUND)
        for (bench in listOf(Bench.SYNTH, Bench.SNAP, Bench.SURFACE, Bench.CONFLUENCE_VOICES)) {
            MakeRow("${bench.label} ▸", sub = Copy.makeSub(bench), scheme = scheme, onOpen = { onOpen(bench) })
        }
        if (onMyVoice != null) MakeRow("MY VOICE ▸", "A short vowel into a personal voice. Kept on this phone.", scheme, onMyVoice)
        MakeGroupHeading(Copy.WHAT_YOU_MADE)
        for (bench in listOf(Bench.INSTRUMENTS)) {
            MakeRow("${bench.label} ▸", sub = Copy.makeSub(bench), scheme = scheme, onOpen = { onOpen(bench) })
        }
    }
}

/** A group heading on the MAKE list: a quiet label over the rows it names. */
@Composable
private fun MakeGroupHeading(text: String) {
    TapeText(text, TapeType.pixelSmall, LocalScheme.current.ink2.tape, Modifier.padding(top = 4.dp), maxLines = 2)
}

/** One MAKE row: the lab's name and arrow, and under it what the word means. */
@Composable
private fun MakeRow(name: String, sub: String, scheme: Scheme, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MAKE_ROW_H.dp)
            .raisedBevel(scheme)
            // The accessible name is the name alone, as it always was; the
            // sub-line is a description, and reading it as part of the
            // button's name would change what every test and walk looks for.
            .tapeClick(label = name, onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        TapeText(name, TapeType.pixel, scheme.ink.tape)
        TapeText(sub, TapeType.pixelSmall, scheme.ink2.tape, Modifier.fillMaxWidth(), maxLines = 2)
    }
}

/** A MAKE row's height: two lines and their gaps, with room to spare at a larger font. */
private const val MAKE_ROW_H = 72

/**
 * MAKE ▸ INSTRUMENTS: what MAKE INSTRUMENT and MAKE PAD left in the
 * shelf's `Instruments/`, each row opening KEYS. The rows were the
 * shelf's; only where the list is shown moved (plan §3.6) — the files
 * are still written beside the kits. With [expansions], each row also
 * carries EXPANSION ▸, which packs the instrument into an expansion beside
 * its kits ([InstrumentExpansionFlow]).
 */
@Composable
fun InstrumentsBench(
    instruments: List<KitShelf.InstrumentEntry>,
    backLabel: String,
    onOpen: (KitShelf.InstrumentEntry) -> Unit,
    onBack: () -> Unit,
    expansions: ExpansionStore? = null,
    onToast: (String) -> Unit = {},
    onMakeMurmurInstrument: ((String) -> Unit)? = null,
    murmurInstrumentEnabled: Boolean = true,
    roundRobinShelf: File? = null,
    onOpenRoundRobin: ((MurmurRoundRobinShelf.SavedBank) -> Unit)? = null,
) {
    val scheme = LocalScheme.current
    var expanding by remember { mutableStateOf<KitShelf.InstrumentEntry?>(null) }
    var rrRevision by remember(roundRobinShelf) { mutableStateOf(0) }
    var rrListing by remember(roundRobinShelf) { mutableStateOf<MurmurRoundRobinShelf.Listing?>(null) }
    var rrLoading by remember(roundRobinShelf) { mutableStateOf(false) }
    var rrError by remember(roundRobinShelf) { mutableStateOf(false) }
    LaunchedEffect(roundRobinShelf, rrRevision) {
        if (roundRobinShelf != null && onOpenRoundRobin != null) {
            rrLoading = true
            try {
                rrListing = withContext(Dispatchers.IO) { MurmurRoundRobinShelf.list(roundRobinShelf) }
                rrError = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { rrListing = null; rrError = true }
            finally { rrLoading = false }
        }
    }
    val rrBanks = rrListing?.banks.orEmpty()
    BackHandler(onBack = onBack)
    val target = expanding
    if (target != null && expansions != null) {
        InstrumentExpansionFlow(target, expansions, onToast) { expanding = null }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .widthIn(min = 72.dp)
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .tapeClick(label = backLabel, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(backLabel, TapeType.pixel, scheme.lcdInk.tape)
            }
            Spacer(Modifier.weight(1f))
            TapeText(Bench.INSTRUMENTS.label, TapeType.lcdHeader, scheme.lcdInk.tape)
            Spacer(Modifier.weight(1f))
            TapeText("${instruments.size + rrBanks.size}", TapeType.lcdSmall, scheme.amber.tape)
        }
        if (onMakeMurmurInstrument != null) {
            MurmurKeptInstrumentPicker(enabled = murmurInstrumentEnabled, onMake = onMakeMurmurInstrument)
        }
        if (roundRobinShelf != null && onOpenRoundRobin != null) {
            MurmurRoundRobinImport(roundRobinShelf, murmurInstrumentEnabled) { rrRevision++ }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TapeText("ROUND-ROBIN INSTRUMENTS", TapeType.pixelSmall, scheme.ink2.tape, Modifier.weight(1f))
                ActionButton("REFRESH", scheme, enabled = !rrLoading, working = rrLoading) { rrRevision++ }
            }
            val rrMessage = when {
                rrLoading -> "CHECKING SAVED INSTRUMENTS…"
                rrError -> "ROUND-ROBIN INSTRUMENTS COULD NOT BE READ. TRY REFRESH."
                rrListing?.refused?.isNotEmpty() == true -> "${rrListing!!.refused.size} SAVED ROUND-ROBIN INSTRUMENTS COULD NOT BE OPENED."
                rrBanks.isEmpty() -> "NO ROUND-ROBIN INSTRUMENTS SAVED."
                else -> null
            }
            if (rrMessage != null) TapeText(rrMessage, TapeType.lcdSmall, scheme.ink2.tape, maxLines = 2)
        }
        if (instruments.isEmpty() && rrBanks.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .lcdPanel(scheme)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(NO_INSTRUMENTS, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
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
                items(rrBanks, key = { "murmur-rr:" + it.name + ":" + it.identitySha256 }) { bank ->
                    Row(Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp).raisedBevel(scheme)
                        .tapeClick(label = "OPEN ROUND ROBIN ${bank.name}") { onOpenRoundRobin?.invoke(bank) }
                        .padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TapeText(bank.name, TapeType.pixel, scheme.ink.tape, Modifier.weight(1f))
                        TapeText("RR KEYS ▸", TapeType.pixelSmall, scheme.ink2.tape)
                    }
                }
                items(instruments, key = { "instrument:" + it.sidecar.name }) { entry ->
                    InstrumentRow(entry, onOpen, onExpansion = expansions?.let { { expanding = entry } })
                }
            }
        }
    }
}

/** The empty list's one line: where an instrument comes from. */
private const val NO_INSTRUMENTS = "No instruments yet. Synth's MAKE INSTRUMENT, or a pad editor's, puts one here."

/** One instrument: its name, zones and root, and KEYS ▸; EXPANSION ▸ beside it when [onExpansion] is given. */
@Composable
private fun InstrumentRow(entry: KitShelf.InstrumentEntry, onOpen: (KitShelf.InstrumentEntry) -> Unit, onExpansion: (() -> Unit)? = null) {
    val scheme = LocalScheme.current
    val i = entry.instrument
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .tapeClick(label = "OPEN ${i.name.uppercase()}") { onOpen(entry) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TapeText(i.name, TapeType.markerBig, scheme.ink.tape)
                val looped = if (i.zones.any { it.loopStartFrame > 0 }) "  ·  HOLDS" else ""
                TapeText("${i.zones.size} ${if (i.zones.size == 1) "ZONE" else "ZONES"}  ·  ROOT ${KeysLayout.label(i.rootNote)}$looped", TapeType.pixelSmall, scheme.ink2.tape)
            }
            TapeText("KEYBOARD ▸", TapeType.pixelSmall, scheme.ink2.tape)
        }
        if (onExpansion != null) {
            Box(
                Modifier
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .tapeClick(label = "ADD ${i.name.uppercase()} TO AN EXPANSION", onClick = onExpansion)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText(Copy.EXPANSION_DOOR, TapeType.pixelSmall, scheme.amber.tape)
            }
        }
    }
}
