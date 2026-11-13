package com.snipsnap.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.snipsnap.shell.Scheme

/**
 * PADS' VELOCITY STRIP section (UI clarity 7d): the four strip buttons, which used to sit
 * bare above the grid as an unlabelled row, behind a labelled group box that starts closed.
 *
 * Display only. The buttons are [StrikeControls] untouched, so each keeps its own caption
 * (STRIP, SIDE, RELEASE, LEVEL), its words, its behaviour and its persisted [StrikeState];
 * only where they sit changes. The closed strip says whether the strip is on, because the
 * fader appears beside the grid whether or not the section is open.
 *
 * Its own composable so [KitScreen], which sits near the dex register limit, gains one call.
 */
@Composable
internal fun StrikeStripSection(strike: StrikeState, scheme: Scheme, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    GroupBox(
        legend = STRIKE_STRIP_LEGEND,
        summary = if (strike.enabled) "STRIP ON" else "STRIP OFF",
        open = open,
        onToggle = { open = !open },
        scheme = scheme,
        modifier = modifier,
    ) {
        StrikeControls(strike, Modifier.fillMaxWidth())
    }
}

/** The section's heading: the ledger's name for the strip (`docs/VOCABULARY.md`, ruling 12). */
internal const val STRIKE_STRIP_LEGEND = "VELOCITY STRIP"
