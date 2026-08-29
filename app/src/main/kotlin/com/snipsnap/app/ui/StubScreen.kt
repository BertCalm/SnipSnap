package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy

/**
 * The honest placeholder for a screen whose milestone hasn't shipped:
 * names the screen, names the milestone, gates nothing (law 3 — the menu
 * still navigates, the app still says exactly what's true).
 */
@Composable
fun StubScreen(screen: AppScreen) {
    val scheme = LocalScheme.current
    val milestone = when (screen) {
        AppScreen.TAPE -> "M2 — THE TAPE DECK"
        AppScreen.CHOP -> "M3 — THE CHOP SHOP"
        AppScreen.PLAY -> "M4 — PLAY MODE"
        AppScreen.SYNTH -> "M5 — THE SYNTH"
        AppScreen.EXPORT -> "M5 — THE EXPORT WIZARD"
        else -> "LATER"
    }
    Column(
        Modifier
            .fillMaxSize()
            .lcdPanel(scheme)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TapeText(screen.label, TapeType.lcd(25), scheme.lcdInk.tape)
        Spacer(Modifier.height(8.dp))
        TapeText("SIDE B — NOT RECORDED YET.", TapeType.lcdSmall, scheme.amber.tape)
        Spacer(Modifier.height(4.dp))
        TapeText(milestone, TapeType.lcdSmall, scheme.lcdInk.tape.copy(alpha = 0.7f))
    }
}

/** HELP, M0 edition: the boot litany plus where capture will arrive. */
@Composable
fun HelpScreen() {
    val scheme = LocalScheme.current
    Column(
        Modifier
            .fillMaxSize()
            .lcdPanel(scheme)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TapeText(Copy.BOOT_READY, TapeType.lcdHeader, scheme.lcdInk.tape)
        Spacer(Modifier.height(4.dp))
        for (line in Copy.BOOT_LINES) {
            TapeText(line, TapeType.lcdSmall, scheme.lcdInk.tape.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(10.dp))
        TapeText("THIS IS THE M0 SKELETON:", TapeType.lcdSmall, scheme.amber.tape)
        TapeText("· BROWSE THE SHELF, TAP PADS, HEAR WAVS", TapeType.lcdSmall, scheme.lcdInk.tape)
        TapeText("· FLIP SCHEMES IN SETUP", TapeType.lcdSmall, scheme.lcdInk.tape)
        TapeText("· CAPTURE ARRIVES WITH M1", TapeType.lcdSmall, scheme.lcdInk.tape)
    }
}
