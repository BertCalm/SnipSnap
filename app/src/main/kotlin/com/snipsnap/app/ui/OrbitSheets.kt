package com.snipsnap.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.loop.Orbit
import com.snipsnap.loop.OrbitPatterns
import com.snipsnap.loop.OrbitSpan
import com.snipsnap.shell.OrbitPanels
import com.snipsnap.shell.Places
import com.snipsnap.shell.SnipStore
import java.io.File
import java.util.Locale

/*
 * ORBIT's pickers and folds (clarity spec 7.3, "Removed patterns"). They used to
 * swap in for the ring's panel behind a button; now a picker is a sheet, and a
 * panel of several controls is a fold under the ring's controls. Kept out of
 * OrbitScreen.kt on purpose: that composable is at the dex 256-register cliff, so
 * what is open is one holder it reads, and the sheets are one composable it calls.
 */

/** A "choose one" picker, opened as a sheet. */
internal enum class OrbitSheet { SNIP, STEPS, SPAN, SPREAD }

/** A panel of several controls, folded open under the ring's. One at a time, as on every screen of the app. */
internal enum class OrbitFold { SET, SECTIONS, OUT, ARP }

/**
 * What is open on ORBIT: at most one [sheet] and at most one [fold]. One value
 * for each, so two panels can no longer be open over each other by a missed reset.
 */
@Stable
internal class OrbitOpen {
    var sheet by mutableStateOf<OrbitSheet?>(null)
    var fold by mutableStateOf<OrbitFold?>(null)

    fun open(which: OrbitSheet) { sheet = which }

    /** Opens [which], or folds it shut if it is the one open. Opening one folds the others shut. */
    fun toggle(which: OrbitFold) { fold = if (fold == which) null else which }

    fun close(which: OrbitSheet) { if (sheet == which) sheet = null }

    fun close(which: OrbitFold) { if (fold == which) fold = null }
}

/** The open picker, if any: one call from the screen. The sheet's own handle, scrim and Back all close it. */
@Composable
internal fun OrbitSheets(
    panels: OrbitOpen,
    ring: Orbit?,
    snips: List<File>,
    onAddSnipRing: (File) -> Unit,
    onSetSteps: (Int) -> Unit,
    onPickSpan: (OrbitSpan) -> Unit,
    onSpread: (Int) -> Unit,
) {
    val sheet = panels.sheet ?: return
    val close = { panels.close(sheet) }
    when (sheet) {
        OrbitSheet.SNIP -> SnipSheet(snips, onAddSnipRing, close)
        OrbitSheet.STEPS -> if (ring != null) StepsSheet(ring, onSetSteps, close)
        OrbitSheet.SPAN -> if (ring != null) SpanSheet(ring, onPickSpan, close)
        OrbitSheet.SPREAD -> if (ring != null) SpreadSheet(ring, onSpread, close)
    }
}

@Composable
private fun SnipSheet(snips: List<File>, onAddSnipRing: (File) -> Unit, onDismiss: () -> Unit) {
    SheetMenu("WHICH SAMPLE GOES ROUND A RING?", onDismiss) {
        for (file in snips) {
            val name = SnipStore.displayName(file).uppercase(Locale.ROOT)
            SheetRow(name, onClick = { onAddSnipRing(file) }, accessibilityLabel = "ADD RING $name")
        }
    }
}

@Composable
private fun StepsSheet(ring: Orbit, onSetSteps: (Int) -> Unit, onDismiss: () -> Unit) {
    SheetMenu("HOW MANY STEPS ROUND ${ring.name}?", onDismiss) {
        for (n in OrbitPatterns.STEP_CHOICES) {
            SheetRow(
                Places.pickedRow(OrbitPanels.stepsName(n), n == ring.steps),
                OrbitPanels.stepsNote(n),
                onClick = { onSetSteps(n) },
            )
        }
        SheetHelp(OrbitPanels.STEPS_HELP)
    }
}

@Composable
private fun SpanSheet(ring: Orbit, onPickSpan: (OrbitSpan) -> Unit, onDismiss: () -> Unit) {
    SheetMenu("HOW MANY BARS IS ONE TURN OF ${ring.name}?", onDismiss) {
        for (span in OrbitSpan.entries) {
            SheetRow(
                Places.pickedRow(span.label, span == ring.span),
                OrbitPanels.spanNote(span),
                onClick = { onPickSpan(span) },
                accessibilityLabel = "SPAN: ${span.label}",
            )
        }
        SheetHelp(OrbitPanels.SPAN_HELP)
    }
}

@Composable
private fun SpreadSheet(ring: Orbit, onSpread: (Int) -> Unit, onDismiss: () -> Unit) {
    SheetMenu(OrbitPanels.spreadTitle(ring.steps), onDismiss) {
        for (k in OrbitPanels.spreadCounts(ring.steps)) {
            SheetRow(OrbitPanels.spreadName(k), OrbitPanels.spreadNote(k, ring.steps), onClick = { onSpread(k) })
        }
        SheetHelp(OrbitPanels.SPREAD_HELP)
    }
}

/** The line of help that ends a picker's rows. */
@Composable
private fun SheetHelp(text: String) {
    TapeText(text, TapeType.pixelSmall, LocalScheme.current.ink2.tape, Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 3)
}
