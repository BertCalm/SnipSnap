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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Places

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
 * are in [Places.HELP] now — one short section per place and one for the
 * dock (`docs/SCREEN_PLAN_2026_10.md` §3.7), held to the app as built by
 * `PlacesTest` and the HELP line-length law; the first section's first
 * line names the MPC, so the front door says who the app is for. This
 * composable only draws them. It scrolls because a phone in one hand is
 * short, and it is one tap from every screen now, behind the gear.
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
        for ((section, lines) in Places.HELP) {
            Spacer(Modifier.height(10.dp))
            TapeText(section, TapeType.lcdSmall, scheme.amber.tape)
            for (line in lines) {
                TapeText(line, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 2)
            }
        }
    }
}
