package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.oilslickSweep
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.StarterKits

/**
 * The tape shelf: every kit folder on the device, plus the FRESH TAPE
 * menu (the cold-start answer — the shelf is never uselessly empty when
 * six starters are one tap away).
 */
@Composable
fun KitsScreen(
    kits: List<KitShelf.Entry>,
    busy: Boolean,
    onOpen: (KitShelf.Entry) -> Unit,
    onFresh: (StarterKits.Starter) -> Unit,
) {
    val scheme = LocalScheme.current
    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Layout.LCD_HEADER_H.dp)
                    .lcdPanel(scheme)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TapeText("THE SHELF", TapeType.lcdHeader, scheme.lcdInk.tape)
            }

            if (kits.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .lcdPanel(scheme)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(Copy.EMPTY_SHELF, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
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
                    items(kits, key = { it.dir.name }) { entry ->
                        KitRow(entry, onOpen)
                    }
                }
            }

            PrimaryAction(
                label = if (busy) "DUBBING…" else "FRESH TAPE",
                enabled = !busy,
                onClick = { menuOpen = true },
            )
        }

        if (menuOpen) {
            StarterMenu(
                onPick = { menuOpen = false; onFresh(it) },
                onDismiss = { menuOpen = false },
            )
        }
    }
}

@Composable
private fun KitRow(entry: KitShelf.Entry, onOpen: (KitShelf.Entry) -> Unit) {
    val scheme = LocalScheme.current
    val kit = entry.kit
    Row(
        Modifier
            .fillMaxWidth()
            .raisedBevel(scheme)
            .tapeClick { onOpen(entry) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(kit.name, TapeType.markerBig, scheme.ink.tape)
            val tempo = kit.tempoBpm?.let { "  ·  %.0f BPM".format(it) } ?: ""
            TapeText("${kit.pads.size} PADS$tempo", TapeType.pixelSmall, scheme.ink2.tape)
        }
        // Every kit is a DRAFT until the export wizard (M5) records a dub
        // to the card; ON CARD status arrives with it.
        TapeText("DRAFT", TapeType.pixelSmall, scheme.ink2.tape)
    }
}

/** The primary action per the handoff: 52dp, rimmed, dark fill. */
@Composable
fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = LocalScheme.current
    val rim =
        if (scheme.id == SchemeId.OILSLICK) {
            Modifier.border(2.dp, oilslickSweep(), RoundedCornerShape(6.dp))
        } else {
            Modifier.border(2.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
        }
    Box(
        Modifier
            .fillMaxWidth()
            .height(Layout.PRIMARY_ACTION_H.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
            .then(rim)
            .then(if (enabled) Modifier.tapeClick(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            label,
            TapeType.displayBig,
            if (enabled) scheme.lcdInk.tape else scheme.lcdInk.tape.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun StarterMenu(onPick: (StarterKits.Starter) -> Unit, onDismiss: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .tapeClick(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .raisedBevel(scheme)
                // Swallow taps so the scrim's dismiss doesn't fire through.
                .tapeClick { }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TapeText("FRESH TAPE", TapeType.display, scheme.ink.tape)
            for (starter in StarterKits.ALL) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .sunkenField(scheme)
                        .tapeClick { onPick(starter) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TapeText(starter.displayName, TapeType.display, scheme.ink.tape)
                        if (starter.seeded) {
                            Spacer(Modifier.width(6.dp))
                            TapeText("REROLLS", TapeType.pixelSmall, scheme.ink2.tape)
                        }
                    }
                    TapeText(starter.blurb, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
                }
            }
        }
    }
}
