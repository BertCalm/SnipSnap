package com.snipsnap.app.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.MidiOut
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Look
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes

/**
 * TAPE PROPERTIES: the live scheme picker (each row previews itself in
 * its own colours — the design files' picker behaviour). The PERSONALITY
 * slider that used to live here is gone; see `Personality.kt`'s own KDoc
 * for why — the app's personality is now carried entirely by the visual
 * design, not by a tone knob over its words.
 *
 * Each group is a [GroupBox], closed to one line, except LOOK: first on
 * the page, and open, because it is the biggest visible change in the app.
 * The foot reads the build's version.
 *
 * September UAT, finding 23 added three facts the app already knew and
 * never showed anyone, plus the door to HELP. They are **read-only on
 * purpose**: [exportFormatLabel] is a memory of the last format picked on
 * EXPORT, not a preference owned here — a second picker would give one
 * setting two owners, and the row says where to change it instead.
 * [filesWhere] and [cardName] are facts, not settings at all.
 *
 * @param exportFormatLabel the format EXPORT will open on, or null if none
 *   has been picked yet.
 * @param filesWhere the export folder's real path, or null while it is
 *   still being resolved off the main thread. Resolving it touches the
 *   filesystem (see `App`'s own note), so the heading and the path are
 *   drawn together once it is known rather than a heading over a guess.
 * @param cardName the card currently held, or null when none is.
 *
 * The WORKSHOP (`docs/WORKSHOP.md`) sits at the very foot, below HELP, and
 * only while [workshopOpen]: HELP is the door a player must find; this is
 * the one a player never needs. [onKnock] is every tap on the title —
 * `App` counts them and decides when the seventh opens it, so this screen
 * holds no knock state of its own.
 *
 * @param workshopOpen whether the WORKSHOP section shows.
 * @param onKnock a tap on the TAPE PROPERTIES title.
 * @param onCloseWorkshop CLOSE THE WORKSHOP.
 * @param onSendToBench SEND TO BENCH: every teach log on the phone, packed
 *   for the chooser.
 * @param onSendHitsToBench SEND HITS TO BENCH: the hits labelled on pad
 *   sheets, as audio - the one button that sends sound, under its own
 *   note, so SEND TO BENCH's never-audio promise stays whole.
 */
@Composable
fun PropertiesScreen(
    currentScheme: SchemeId,
    onScheme: (SchemeId) -> Unit,
    /**
     * LOOK (plan §3.7, `Scheme.look`): which drawing's schemes the picker
     * shows, CLASSIC or MODERN. A preference beside the scheme's, written
     * by [onLook]; the drawing itself follows in the modern-look step, so
     * for now the row groups the schemes and nothing more.
     */
    currentLook: Look,
    onLook: (Look) -> Unit,
    teachEnabled: Boolean,
    onTeach: (Boolean) -> Unit,
    /** HAPTICS: whether the motor ticks under pads, steppers and landings. ON by default; always silent while the mic is armed (`Haptics`). */
    hapticsOn: Boolean,
    onHaptics: (Boolean) -> Unit,
    /** MIDI OUT: whether pads and keys also go to plugged-in hardware. ON by default (`MIDI_OUT_DEFAULT`); the app's own SNIPSNAP OUT port is published either way. */
    midiOutOn: Boolean,
    onMidiOut: (Boolean) -> Unit,
    /** False on a phone with no MIDI at all: the group says so and offers no switch. */
    midiSupported: Boolean,
    /** Where notes go right now, one row each; `open` false is a device another app holds. */
    midiPorts: List<MidiOut.Port>,
    exportFormatLabel: String?,
    filesWhere: String?,
    cardName: String?,
    onHelp: () -> Unit,
    workshopOpen: Boolean,
    onKnock: () -> Unit,
    onCloseWorkshop: () -> Unit,
    onSendToBench: () -> Unit,
    onSendHitsToBench: () -> Unit,
) {
    val scheme = LocalScheme.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The knock (docs/WORKSHOP.md): seven taps here, inside two seconds
        // of each other, open the WORKSHOP at the foot of this screen. The
        // node is labelled with its own text, never null — LoopGrid's note
        // on `tapeClick(label = null)` says why a nameless clickable is a
        // defect, and a hidden door is not an excuse for one.
        Box(Modifier.tapeClick(label = "TAPE PROPERTIES", onClick = onKnock)) {
            TapeText("TAPE PROPERTIES", TapeType.display, scheme.ink.tape)
        }

        // Every group below is a GroupBox, THIS KIT's pattern: one closed
        // line each, and LOOK - the biggest visible change on the page, and
        // the thing most people came for - open and first.

        // LOOK: the two drawings, the schemes under the chosen one. The same
        // two-up toggle TEACH THE MACHINE uses below.
        var lookOpen by rememberSaveable { mutableStateOf(true) }
        GroupBox(
            legend = "LOOK",
            summary = "${currentLook.displayName} · ${currentScheme.displayName}",
            open = lookOpen,
            onToggle = { lookOpen = !lookOpen },
            scheme = scheme,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (look in Look.entries) {
                    val selected = look == currentLook
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                            .semantics { this.selected = selected }
                            .tapeClick(label = "LOOK ${look.displayName}") { if (!selected) onLook(look) },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(look.displayName, TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .sunkenField(scheme)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (s in Schemes.byLook().getValue(currentLook)) {
                    SchemeRow(s, selected = s.id == currentScheme, onPick = { onScheme(s.id) })
                }
            }
        }

        // X4.4 TEACH THE MACHINE: the consent row. Off by default; what ON
        // sends is spelled out under it, in the copy's own words.
        var teachOpen by rememberSaveable { mutableStateOf(false) }
        GroupBox(
            legend = "TEACH THE MACHINE",
            summary = if (teachEnabled) "ON" else "OFF",
            open = teachOpen,
            onToggle = { teachOpen = !teachOpen },
            scheme = scheme,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (on in listOf(false, true)) {
                    val selected = on == teachEnabled
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                            .semantics { this.selected = selected }
                            .tapeClick(label = "TEACH THE MACHINE ${if (on) "ON" else "OFF"}") { if (!selected) onTeach(on) },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(if (on) "ON" else "OFF", TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
                    }
                }
            }
            TapeText(Copy.TEACH_CONSENT, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
            TapeText(
                "WITH IT ON, EVERY CHIP YOU CORRECT OR CONFIRM ON CHOP IS LOGGED AS A FEATURE VECTOR AND A LABEL IN THAT KIT'S FOLDER, AND A STAR YOU GIVE THE CUTS AS A RATING WITH THE BENCH'S SETTINGS.",
                TapeType.pixelSmall,
                scheme.ink2.tape,
                maxLines = 4,
            )
        }

        // HAPTICS: the same two-up toggle, its own closed group. The note says
        // the one exception, because "why did it stop ticking" is a question
        // a player would otherwise put to the switch.
        var hapticsOpen by rememberSaveable { mutableStateOf(false) }
        GroupBox(
            legend = "HAPTICS",
            summary = if (hapticsOn) "ON" else "OFF",
            open = hapticsOpen,
            onToggle = { hapticsOpen = !hapticsOpen },
            scheme = scheme,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (on in listOf(false, true)) {
                    val selected = on == hapticsOn
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                            .semantics { this.selected = selected }
                            .tapeClick(label = "HAPTICS ${if (on) "ON" else "OFF"}") { if (!selected) onHaptics(on) },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(if (on) "ON" else "OFF", TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
                    }
                }
            }
            TapeText(Copy.SETUP_HAPTICS_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
        }

        // MIDI OUT: HAPTICS' two-up toggle again, then where the notes go. A
        // phone with no MIDI gets the one sentence and no switch - a bevel
        // that could not do anything would be a lie.
        var midiOpen by rememberSaveable { mutableStateOf(false) }
        GroupBox(
            legend = Copy.SETUP_MIDI_OUT_HEADING,
            summary = if (midiSupported && midiOutOn) "ON" else "OFF",
            open = midiOpen,
            onToggle = { midiOpen = !midiOpen },
            scheme = scheme,
        ) {
            if (!midiSupported) {
                TapeText(Copy.SETUP_MIDI_OUT_UNSUPPORTED, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (on in listOf(false, true)) {
                        val selected = on == midiOutOn
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                                .semantics { this.selected = selected }
                                .tapeClick(label = "MIDI OUT ${if (on) "ON" else "OFF"}") { if (!selected) onMidiOut(on) },
                            contentAlignment = Alignment.Center,
                        ) {
                            TapeText(if (on) "ON" else "OFF", TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
                        }
                    }
                }
                TapeText(Copy.SETUP_MIDI_OUT_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
                if (midiPorts.isEmpty()) {
                    TapeText(Copy.SETUP_MIDI_OUT_NONE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
                } else {
                    for (port in midiPorts) {
                        TapeText(
                            "${port.name} \u00B7 ${if (port.open) Copy.SETUP_MIDI_OUT_PORT_OK else Copy.SETUP_MIDI_OUT_PORT_BUSY}",
                            TapeType.pixelSmall,
                            scheme.ink2.tape,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // ---- What the app already knew and never said (finding 23) ----

        var formatOpen by rememberSaveable { mutableStateOf(false) }
        GroupBox(
            legend = Copy.SETUP_FORMAT_HEADING,
            summary = exportFormatLabel ?: Copy.SETUP_FORMAT_NONE,
            open = formatOpen,
            onToggle = { formatOpen = !formatOpen },
            scheme = scheme,
        ) {
            TapeText(
                exportFormatLabel ?: Copy.SETUP_FORMAT_NONE,
                TapeType.pixel,
                scheme.ink2.tape,
                maxLines = 2,
            )
            TapeText(Copy.SETUP_FORMAT_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        }

        // The fourth of the same kind, and the persona review's P4.2: the
        // app has always been a 44.1 kHz machine and has never once said
        // so on a screen. Static — there is nothing to read from disk and
        // nothing to pick — because the rate genuinely cannot vary; the
        // copy's own note says where a rate you can *hear* lives instead.
        var rateOpen by rememberSaveable { mutableStateOf(false) }
        GroupBox(
            legend = Copy.SETUP_RATE_HEADING,
            summary = Copy.SETUP_RATE,
            open = rateOpen,
            onToggle = { rateOpen = !rateOpen },
            scheme = scheme,
        ) {
            TapeText(Copy.SETUP_RATE, TapeType.pixel, scheme.ink2.tape, maxLines = 1)
            TapeText(Copy.SETUP_RATE_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
        }

        // The real path, not a description of it: a user hunting for a file
        // over a cable needs the actual thing to look for. maxLines is
        // generous because a private-storage path is long and truncating it
        // would defeat the only reason to print it.
        //
        // Group and path appear together or not at all. The path arrives
        // from IO a frame or two after the screen opens, and a group with
        // nothing in it reads as a fault rather than as a wait. The closed
        // line is not the path: a private-storage path is the one string on
        // this page that cannot fit on one.
        if (filesWhere != null) {
            var whereOpen by rememberSaveable { mutableStateOf(false) }
            GroupBox(
                legend = Copy.SETUP_WHERE_HEADING,
                summary = SETUP_WHERE_SUMMARY,
                open = whereOpen,
                onToggle = { whereOpen = !whereOpen },
                scheme = scheme,
            ) {
                TapeText(Copy.setupWhere(filesWhere), TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4)
            }
        }

        var cardOpen by rememberSaveable { mutableStateOf(false) }
        GroupBox(
            legend = Copy.SETUP_CARD_HEADING,
            summary = cardName?.uppercase(java.util.Locale.ROOT) ?: SETUP_CARD_SUMMARY_NONE,
            open = cardOpen,
            onToggle = { cardOpen = !cardOpen },
            scheme = scheme,
        ) {
            TapeText(
                cardName?.let { Copy.setupCardHeld(it) } ?: Copy.SETUP_CARD_NONE,
                TapeType.pixelSmall,
                scheme.ink2.tape,
                maxLines = 2,
            )
        }

        // HELP has a menu tab, but at 390 dp it sits off the right edge of a
        // row that scrolls with no cue (September UAT, finding 10, still
        // open). Until that is fixed this is the only door to HELP a user is
        // certain to find.
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .raisedBevel(scheme)
                .tapeClick(label = "HELP", onClick = onHelp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("HELP ▸", TapeType.pixel, scheme.amber.tape)
        }

        // The WORKSHOP (docs/WORKSHOP.md): the developer's tools, shown only
        // once the knock on the title has opened it, and below HELP on
        // purpose. Everything here adds a control; nothing here hides one.
        if (workshopOpen) {
            TapeText("WORKSHOP", TapeType.display, scheme.ink.tape)
            TapeText(Copy.WORKSHOP_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .raisedBevel(scheme)
                    .tapeClick(label = "SEND TO BENCH", onClick = onSendToBench),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("SEND TO BENCH ▸", TapeType.pixel, scheme.ink.tape)
            }
            TapeText(Copy.SEND_TO_BENCH_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 7)
            // The one button that sends audio (docs/WORKSHOP.md, WS3), its
            // own note under it every time, never folded into the button
            // above: two zips, two promises.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .raisedBevel(scheme)
                    .tapeClick(label = "SEND HITS TO BENCH", onClick = onSendHitsToBench),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("SEND HITS TO BENCH ▸", TapeType.pixel, scheme.ink.tape)
            }
            TapeText(Copy.SEND_HITS_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .raisedBevel(scheme)
                    .tapeClick(label = "CLOSE THE WORKSHOP", onClick = onCloseWorkshop),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("CLOSE THE WORKSHOP", TapeType.pixel, scheme.ink2.tape)
            }
        }

        // The build, last and quiet: the one fact a bug report needs and
        // the page never said. Read from the package, since the app builds
        // without BuildConfig; a package that will not answer draws no row.
        val versionName = rememberVersionName()
        if (versionName != null) {
            TapeText("VERSION $versionName", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 1)
        }
    }
}

/** The closed line of WHERE YOUR FILES LIVE: the path itself is too long for one. */
private const val SETUP_WHERE_SUMMARY = "THE EXPORT FOLDER"

/** The closed line of THE CARD while none is held. */
private const val SETUP_CARD_SUMMARY_NONE = "NONE PICKED"

/** `versionName` from the app's own package, or null when the package manager will not say. */
@Composable
internal fun rememberVersionName(): String? {
    val context = LocalContext.current
    return remember(context) {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName?.uppercase(java.util.Locale.ROOT)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}

/**
 * A picker row painted in the scheme it offers — a working miniature of
 * the window (titlebar strip, chrome body, LCD sliver), not a swatch.
 */
@Composable
private fun SchemeRow(s: Scheme, selected: Boolean, onPick: () -> Unit) {
    val host = LocalScheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (selected) it.pressedBevel(host) else it.raisedBevel(host) }
            .semantics { this.selected = selected }
            .tapeClick(label = s.id.displayName, onClick = onPick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier
                .width(52.dp)
                .background(s.gray.tape, RoundedCornerShape(3.dp))
                .padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .background(
                        Brush.horizontalGradient(listOf(s.title1.tape, s.title2.tape)),
                        RoundedCornerShape(2.dp),
                    ),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(s.lcd.tape, RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.size(width = 18.dp, height = 3.dp).background(s.lcdInk.tape))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(s.id.displayName, TapeType.display, host.ink.tape)
            if (selected) {
                TapeText("LOADED", TapeType.pixelSmall, host.ink2.tape)
            }
        }
    }
}
