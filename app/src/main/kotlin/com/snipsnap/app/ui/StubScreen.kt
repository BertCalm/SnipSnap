package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/**
 * HELP: what the app is, for whoever went looking for it.
 *
 * The September UAT's finding 1. This was twenty hardcoded lines calling
 * the app "the M0 skeleton" and promising that capture "arrives with M1",
 * months after capture shipped — and it was the only in-app explanation of
 * anything, so every user who went looking for help was told the product
 * they were holding did not exist yet.
 *
 * It rotted because it lived here, where nothing could test it. The words
 * are in [Copy] now, held to the app as built by `PersonalityTest`; this
 * composable only draws them. It scrolls because the true version is
 * longer than the lie was and a phone in one hand is short.
 */
@Composable
fun HelpScreen() {
    val scheme = LocalScheme.current
    Column(
        Modifier
            .fillMaxSize()
            .lcdPanel(scheme)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TapeText(Copy.BOOT_READY, TapeType.lcdHeader, scheme.lcdInk.tape)
        Spacer(Modifier.height(4.dp))
        for (line in Copy.BOOT_LINES) {
            TapeText(line, TapeType.lcdSmall, scheme.lcdInk.tape.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(10.dp))
        TapeText(Copy.HELP_LOOP_HEADER, TapeType.lcdSmall, scheme.amber.tape)
        for (line in Copy.HELP_LOOP) {
            TapeText(line, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
        TapeText(Copy.HELP_MORE_HEADER, TapeType.lcdSmall, scheme.amber.tape)
        for (line in Copy.HELP_MORE) {
            TapeText(line, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 2)
        }
    }
}
