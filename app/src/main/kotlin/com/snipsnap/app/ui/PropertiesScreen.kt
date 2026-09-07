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
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Personality
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes

/**
 * TAPE PROPERTIES: the live scheme picker (each row previews itself in
 * its own colours — the design files' picker behaviour) and the
 * PERSONALITY slider, whose OFF is respected everywhere without
 * argument.
 */
@Composable
fun PropertiesScreen(
    currentScheme: SchemeId,
    onScheme: (SchemeId) -> Unit,
    personality: Personality,
    onPersonality: (Personality) -> Unit,
    teachEnabled: Boolean,
    onTeach: (Boolean) -> Unit,
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

        TapeText("PERSONALITY", TapeType.display, scheme.ink.tape)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (level in Personality.entries) {
                val selected = level == personality
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                        .tapeClick { onPersonality(level) },
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(level.name, TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
                }
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
                        .tapeClick { if (!selected) onTeach(on) },
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
            .tapeClick(onPick)
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
