package com.snipsnap.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Scheme

/**
 * The pad editor's AQUARIUM section: a [GroupBox] whose closed line is [summary]
 * (what the pad keeps from it, or what it holds) and whose open body is the staged
 * panel. A section of its own file so the editor's composable stays under the dex
 * register ceiling; the panel's state and its writes stay with the editor, which
 * is why this is a section and not a bench (the draft and its preview live and die
 * with the pad's own model).
 */
@Composable
internal fun AquariumSection(
    summary: String,
    open: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    scheme: Scheme,
    content: @Composable ColumnScope.() -> Unit,
) {
    GroupBoxCore(
        legend = "AQUARIUM",
        summary = summary,
        open = open,
        onToggle = onToggle,
        scheme = scheme,
        enabled = enabled,
        summaryColor = scheme.ink2.tape,
        modifier = Modifier,
        also = null,
        toggleModifier = Modifier.testTag("aquarium-open"),
        content = content,
    )
}
