package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Scheme

/**
 * How loudly a button asks to be pressed (UI clarity spec, 7.5 "Button tiers").
 *
 * One [Primary] per screen: the action the screen is for, filled with the
 * scheme's `accent` and lettered in `onAccent`. [Secondary] is the ordinary
 * outlined button (the raised form, outlined with `edge`) and is the default,
 * so a call site that says nothing is unchanged. [Text] is a low-weight link:
 * no fill, no outline, `accent` letters, for the way out of a dialog (CANCEL).
 *
 * The tier is dress only. It changes no label, hit target, enabled state or
 * semantics: every tier keeps the 48 dp floor and the same accessible name.
 */
enum class ButtonTier { Primary, Secondary, Text }

private val TIER_SHAPE = RoundedCornerShape(2.dp)

/** The body of a button of this tier: the fill and the outline, nothing else. */
internal fun Modifier.tierSurface(tier: ButtonTier, scheme: Scheme): Modifier = when (tier) {
    // The `edge` outline is what holds the fill's boundary at 3:1 where the accent alone
    // would not (OPENSKY's accent on an LCD panel is 2.83:1); `edge` clears it on chrome and LCD alike.
    ButtonTier.Primary -> background(scheme.accent.tape, TIER_SHAPE).border(1.dp, scheme.edge.tape, TIER_SHAPE)
    ButtonTier.Secondary -> raisedBevel(scheme)
    ButtonTier.Text -> this
}

/** The letters of a button of this tier. A button that cannot be pressed is `ink3` whatever its tier. */
internal fun tierInk(tier: ButtonTier, scheme: Scheme, enabled: Boolean, lit: Boolean, dimmed: Boolean): Color = when {
    !enabled -> scheme.ink3.tape
    tier == ButtonTier.Primary -> scheme.onAccent.tape
    tier == ButtonTier.Text -> if (dimmed) scheme.ink2.tape else scheme.accent.tape
    lit -> scheme.accent.tape
    dimmed -> scheme.ink2.tape
    else -> scheme.ink.tape
}
