package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout

/*
 * The rows of a menu that opens as a [BottomSheet]: pick one thing, or run one of a
 * short list of actions. One shape for all of them (clarity spec 7.3, "Sheet"): a
 * name, and under it one line of what it does.
 */

/**
 * A short menu in a sheet: [BottomSheet] sized to its rows (up to the share of the
 * window a sheet takes, then it scrolls), with [rows] under the handle and the
 * [title]. Swiping down, a tap on the scrim and Back all reach [onDismiss]; there is
 * no CANCEL row, because the three ways out are the sheet's own. The scrim keeps the
 * name CANCEL the menus it replaced gave theirs.
 */
@Composable
fun SheetMenu(title: String, onDismiss: () -> Unit, rows: @Composable ColumnScope.() -> Unit) {
    BottomSheet(title, onDismiss, scrimLabel = "CANCEL", wrap = true) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = rows,
        )
    }
}

/**
 * One row of a [SheetMenu]: [name], and [description] under it when there is one.
 * At least [Layout.MIN_HIT_TARGET] tall. The accessible name is the name and the
 * description read as one, unless [accessibilityLabel] is given: a row a test or a
 * screen reader already knows by another name keeps it. [tag] is a short word at the
 * name's right (a starter that rerolls). A disabled row stays in the list, dimmed,
 * and is announced as unavailable.
 */
@Composable
fun SheetRow(
    name: String,
    description: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    tag: String? = null,
) {
    val scheme = LocalScheme.current
    val ink = if (enabled) scheme.ink.tape else scheme.ink3.tape
    val ink2 = if (enabled) scheme.ink2.tape else scheme.ink3.tape
    val spoken = accessibilityLabel ?: sheetRowSpoken(name, description)
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .sunkenField(scheme)
            .tapeClick(label = spoken, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TapeText(name, TapeType.display, ink)
            if (tag != null) {
                Spacer(Modifier.width(6.dp))
                TapeText(tag, TapeType.pixelSmall, ink2)
            }
        }
        if (description != null) TapeText(description, TapeType.pixelSmall, ink2, maxLines = 2)
    }
}

/** A group heading between rows of a [SheetMenu]: announced as a heading, not a button. */
@Composable
fun SheetHeading(text: String) {
    TapeText(
        text,
        TapeType.pixel,
        LocalScheme.current.ink2.tape,
        Modifier.fillMaxWidth().padding(top = 6.dp).semantics { heading() },
        maxLines = 1,
    )
}

/** What a screen reader says for a row with no name of its own: the name, then the description. */
internal fun sheetRowSpoken(name: String, description: String?): String =
    if (description.isNullOrEmpty()) name else "$name. $description"
