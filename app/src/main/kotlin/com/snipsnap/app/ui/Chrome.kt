package com.snipsnap.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalPersonality
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.oilslickSweep
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Delight
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.SchemeId
import kotlinx.coroutines.delay

/**
 * Every place the menu row can land. M0 builds KITS, KIT and PROPERTIES
 * for real; the rest render an honest stub naming their milestone.
 */
enum class AppScreen(val label: String) {
    KITS("KITS"),
    KIT("KIT"),
    TAPE("TAPE"),
    CHOP("CHOP"),
    PLAY("PLAY"),
    GROOVE("GROOVE"),
    SYNTH("SYNTH"),
    /** The tactile pad: macros under a finger, PRINT to resample the gesture onto TAPE. */
    SURFACE("SURFACE"),
    EXPORT("EXPORT"),
    PROPERTIES("SETUP"),
    HELP("HELP"),
    /** Not one of MenuRow's ten: reached from the shelf's INSTRUMENTS list, left by its own ◄ SHELF. */
    KEYS("KEYS"),

    /**
     * Nor is this one: reached from KIT's action row on the pad you want
     * taken apart, and left by its own ◄ KIT. A menu of twelve fits no
     * phone, and SPLIT is something you do *to a pad* — which is what KIT
     * is for.
     */
    SPLIT("SPLIT"),
}

/** Single-line themed text; TapeOS never relies on Material's Text. */
@Composable
fun TapeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Tap with no indication machinery — TapeOS draws its own feedback.
 *
 * [label] carries this control's accessible name to TalkBack and has no
 * default: every call site has to make an explicit choice, so a new
 * control can't silently ship without one the way all 97 of this app's
 * pre-existing interactive sites did (see the 2026-09-08 accessibility
 * audit). Pass `null` when a descendant `TapeText`/`TapeText`-bearing
 * child already says what the control does — `clickable` merges
 * descendant semantics into this node automatically, so a real
 * [label] here would *replace* that text in what TalkBack announces,
 * not add to it. Reserve an explicit [label] for controls with no
 * text child (scrim dismissers, glyph-only chips, colour swatches) or
 * whose visible glyph is itself an accessibility risk (single-letter
 * chips TalkBack may spell out instead of reading as a word).
 *
 * [enabled] mirrors `clickable`'s own flag: a disabled control keeps
 * its semantics node (and its merged/explicit name) but exposes
 * Compose's `disabled()` state instead of an actionable one, so a
 * screen-reader user is told "temporarily unavailable" instead of the
 * control silently vanishing from the tree (audit finding 12).
 */
@Composable
fun Modifier.tapeClick(label: String?, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick,
    ).let { base ->
        if (label != null) base.semantics { contentDescription = label } else base
    }

@Composable
fun TitleBar(modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    val brush =
        if (scheme.id == SchemeId.OILSLICK) {
            oilslickSweep()
        } else {
            Brush.horizontalGradient(listOf(scheme.title1.tape, scheme.title2.tape))
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Layout.TITLEBAR_H.dp)
            .background(brush, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TapeText("SNIPSNAP.EXE", TapeType.displayBig, scheme.titleInk.tape)
        TapeText("M0", TapeType.pixelSmall, scheme.titleInk.tape.copy(alpha = 0.7f))
    }
}

/** One menu entry: its label and the screen it lands on. */
data class MenuItem(val label: String, val screen: AppScreen)

val MENU_ITEMS = listOf(
    MenuItem("KITS", AppScreen.KITS),
    MenuItem("KIT", AppScreen.KIT),
    MenuItem("TAPE", AppScreen.TAPE),
    MenuItem("CHOP", AppScreen.CHOP),
    MenuItem("PLAY", AppScreen.PLAY),
    MenuItem("GROOVE", AppScreen.GROOVE),
    MenuItem("SYNTH", AppScreen.SYNTH),
    MenuItem("SURFACE", AppScreen.SURFACE),
    MenuItem("EXPORT", AppScreen.EXPORT),
    MenuItem("SETUP", AppScreen.PROPERTIES),
    MenuItem("HELP", AppScreen.HELP),
)

@Composable
fun MenuRow(
    current: AppScreen,
    onSelect: (AppScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Layout.MENU_ROW_H.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in MENU_ITEMS) {
            val isSelected = item.screen == current
            val itemModifier = Modifier
                .let { if (isSelected) it.pressedBevel(scheme, 3.dp) else it }
                // The tab's own name (item.label) is the accessible name
                // via the merged descendant TapeText below; selection is
                // the one thing that text can't say on its own (audit
                // finding 7 — selection state had no programmatic
                // exposure anywhere in the app).
                .semantics { selected = isSelected }
                .tapeClick(label = null) { onSelect(item.screen) }
                .padding(horizontal = 4.dp, vertical = 3.dp)
            TapeText(
                item.label,
                TapeType.pixel,
                if (isSelected) scheme.ink.tape else scheme.ink2.tape,
                itemModifier,
            )
        }
    }
}

/**
 * Three cells: where you are, what's on the shelf, and the deck
 * muttering to itself (FULL personality only; a busy line preempts the
 * quip because status is function, not joke).
 */
@Composable
fun StatusBar(
    screenLabel: String,
    shelfLabel: String,
    busy: String?,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    val personality = LocalPersonality.current

    var quipIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(personality) {
        while (Delight.quipsEnabled(personality)) {
            delay(Motion.QUIP_ROTATE_MS.toLong())
            quipIndex++
        }
    }
    val tail = busy
        ?: if (Delight.quipsEnabled(personality)) Copy.rotating(Copy.STATUS_QUIPS, quipIndex) else ""

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Layout.STATUS_BAR_H.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusCell(screenLabel, Modifier.width(88.dp))
        StatusCell(shelfLabel, Modifier.width(88.dp))
        StatusCell(tail, Modifier.weight(1f))
    }
}

@Composable
private fun StatusCell(text: String, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Layout.STATUS_BAR_H.dp)
            .sunkenField(scheme, 3.dp)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        TapeText(text, TapeType.pixelSmall, scheme.ink2.tape)
    }
}

/**
 * The toast: rises 8dp and fades in over 250ms, dwells, and is cleared by
 * the state holder (see `App`). Whether the *visible* bubble shows at all
 * is the personality slider's call — but PERSONALITY is a tone preference
 * (Law 2/3 territory: no quips, no flourish at OFF), not a permission to
 * withhold function. A toast is a screen-reader user's only channel for
 * "did DELETE/SHARE/RENAME work" (audit finding 3); OFF silencing that
 * entirely, with no fallback, would cost that user information a sighted
 * user still gets from watching the operation resolve. So the semantics
 * node — [liveRegion] plus [contentDescription] carrying the message — is
 * always present while a message is live, regardless of PERSONALITY;
 * only the drawn bubble beneath it is gated, exactly as before this fix.
 * `Polite` (not `Assertive`) throughout: several of this file's own error
 * strings (`Copy.grooveRefused`, `Copy.KIT_RENAME_FAILED`, ...) don't share
 * a common marker that would let this function tell a failure from a
 * status line without guessing, and a wrong guess (an `Assertive` status
 * toast interrupting whatever TalkBack was already reading) is worse than
 * a uniformly polite announcement.
 */
@Composable
fun ToastOverlay(message: String?, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    val personality = LocalPersonality.current
    if (message == null) return
    val visible = Delight.toastsEnabled(personality)

    val t = remember(message) { Animatable(0f) }
    LaunchedEffect(message) { if (visible) t.animateTo(1f, tween(Motion.TOAST_IN_MS)) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // mergeDescendants: the bubble's own TapeText would otherwise
            // register as a second, separately-focusable text node with
            // the same words — one node per toast, not two, is what
            // "announced once" (this KDoc's whole point) requires.
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = message
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (visible) {
            Box(
                modifier = Modifier
                    .padding(bottom = 44.dp)
                    .alpha(t.value)
                    .raisedBevel(scheme, 4.dp)
                    .let {
                        if (scheme.id == SchemeId.OILSLICK) {
                            it.border(2.dp, oilslickSweep(), RoundedCornerShape(4.dp))
                        } else {
                            it
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TapeText(message, TapeType.pixel, scheme.ink.tape, maxLines = 2)
            }
        }
    }
}
