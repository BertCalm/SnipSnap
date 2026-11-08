package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout

/**
 * The `⋮` at the end of a row that also answers to a hold. A tap does exactly
 * what the hold does, so the gesture is never the only way in (the spec's
 * 7.4, and ConventionTest's hold-twin law). It is its own small composable so
 * the big shelf screens do not grow by its layout.
 *
 * [label] is what a screen reader says: the action and the row, for example
 * "RENAME OR DELETE ROW NAME". [enabled] is the row's own busy gate, passed
 * through rather than dropped.
 */
@Composable
internal fun RowMoreDoor(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .widthIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .tapeClick(label = label, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(Copy.ROW_MORE, TapeType.markerBig, if (enabled) scheme.amber.tape else scheme.ink3.tape)
    }
}
