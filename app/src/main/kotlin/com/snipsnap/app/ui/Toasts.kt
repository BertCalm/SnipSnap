package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Delight
import com.snipsnap.shell.Personality

/**
 * What to say after a FRESH TAPE attempt, or null to stay silent.
 *
 * Personality law 3 — jokes never gate function — is the whole content
 * of this function. "FRESH TAPE. SMELLS LIKE FERRIC OXIDE." is a joke,
 * so OFF silences it. "That didn't work" is function, so it speaks at
 * every level: without that asymmetry a failed create at OFF looks
 * exactly like a success, and the tape you just named is quietly
 * missing from the shelf.
 *
 * It lives here rather than in the dialog's click handler because a rule
 * belongs where a test can hold it still.
 */
fun newTapeToast(
    created: Boolean,
    name: String,
    personality: Personality,
): String? = when {
    !created -> Copy.CREATE_FAILED
    Delight.toastsEnabled(personality) -> Copy.kitNameResponse(name) ?: Copy.FRESH_TAPE
    else -> null
}

/**
 * A toast on screen, with its own identity.
 *
 * [id] exists so a *second* toast carrying the same [text] (two
 * consecutive `CREATE_FAILED`s, say) is still a distinct value — the
 * dismiss timer in `MainActivity` keys on [id], not [text], so the second
 * toast restarts its own dwell instead of the reassignment being a no-op
 * that inherits whatever time is left on the first one's timer.
 */
data class ToastMessage(val id: Int, val text: String)

/**
 * A one-line status toast, sat just above the status bar — an "oil-rim
 * card" per the handoff (`design/HANDOFF.md`), dismissed on its own by a
 * [com.snipsnap.shell.Motion.TOAST_DWELL_MS] timer the caller owns.
 *
 * Anchored inside `SnipSnapWindow`'s content slot (not a full-screen
 * sibling of the window), so "above the status bar" is measured from the
 * window's own status bar row rather than from the physical screen edge —
 * the window already consumes system insets, this slot does not need to.
 */
@Composable
fun ToastBanner(text: String) {
    val s = LocalScheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(s.gray.toColor())
                .border(2.dp, s.lcdInk.toColor(), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BasicText(
                text = text,
                style = TextStyle(
                    color = s.ink.toColor(),
                    fontFamily = TapeFonts.pixel,
                    fontSize = 9.sp,
                ),
            )
        }
    }
}
