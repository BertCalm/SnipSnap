package com.snipsnap.app.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes

/**
 * TAPE PROPERTIES: the live scheme picker (each row previews itself in
 * its own colours — the design files' picker behaviour). The PERSONALITY
 * slider that used to live here is gone; see `Personality.kt`'s own KDoc
 * for why — the app's personality is now carried entirely by the visual
 * design, not by a tone knob over its words.
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
 */
@Composable
fun PropertiesScreen(
    currentScheme: SchemeId,
    onScheme: (SchemeId) -> Unit,
    teachEnabled: Boolean,
    onTeach: (Boolean) -> Unit,
    exportFormatLabel: String?,
    filesWhere: String?,
    cardName: String?,
    onHelp: () -> Unit,
) {
    val scheme = LocalScheme.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapeText("TAPE PROPERTIES", TapeType.display, scheme.ink.tape)

        Column(
            Modifier
                .fillMaxWidth()
                .sunkenField(scheme)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (s in Schemes.ALL) {
                SchemeRow(s, selected = s.id == currentScheme, onPick = { onScheme(s.id) })
            }
        }

        // X4.4 TEACH THE MACHINE: the consent row. Off by default; what ON
        // sends is spelled out under it, in the copy's own words.
        TapeText("TEACH THE MACHINE", TapeType.display, scheme.ink.tape)
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
                        .tapeClick(label = null) { if (!selected) onTeach(on) },
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(if (on) "ON" else "OFF", TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
                }
            }
        }
        TapeText(Copy.TEACH_CONSENT, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        TapeText(
            "WITH IT ON, EVERY CHIP YOU CORRECT ON CHOP IS LOGGED AS A FEATURE VECTOR AND A LABEL IN THAT KIT'S FOLDER.",
            TapeType.pixelSmall,
            scheme.ink2.tape,
            maxLines = 3,
        )

        // ---- What the app already knew and never said (finding 23) ----

        TapeText(Copy.SETUP_FORMAT_HEADING, TapeType.display, scheme.ink.tape)
        TapeText(
            exportFormatLabel ?: Copy.SETUP_FORMAT_NONE,
            TapeType.pixel,
            scheme.ink2.tape,
            maxLines = 2,
        )
        TapeText(Copy.SETUP_FORMAT_NOTE, TapeType.pixelSmall, scheme.ink3.tape, maxLines = 2)

        // The real path, not a description of it: a user hunting for a file
        // over a cable needs the actual thing to look for. maxLines is
        // generous because a private-storage path is long and truncating it
        // would defeat the only reason to print it.
        //
        // Heading and path appear together or not at all. The path arrives
        // from IO a frame or two after the screen opens, and a heading with
        // nothing under it reads as a fault rather than as a wait.
        if (filesWhere != null) {
            TapeText(Copy.SETUP_WHERE_HEADING, TapeType.display, scheme.ink.tape)
            TapeText(Copy.setupWhere(filesWhere), TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4)
        }

        TapeText(Copy.SETUP_CARD_HEADING, TapeType.display, scheme.ink.tape)
        TapeText(
            cardName?.let { Copy.setupCardHeld(it) } ?: Copy.SETUP_CARD_NONE,
            TapeType.pixelSmall,
            scheme.ink2.tape,
            maxLines = 2,
        )

        // HELP has a menu tab, but at 390 dp it sits off the right edge of a
        // row that scrolls with no cue (September UAT, finding 10, still
        // open). Until that is fixed this is the only door to HELP a user is
        // certain to find.
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .raisedBevel(scheme)
                .tapeClick(label = null, onClick = onHelp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("HELP ▸", TapeType.pixel, scheme.amber.tape)
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
            .tapeClick(label = null, onClick = onPick)
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
