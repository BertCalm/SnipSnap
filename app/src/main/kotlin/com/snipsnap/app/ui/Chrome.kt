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
    EXPORT("EXPORT"),
    PROPERTIES("SETUP"),
    HELP("HELP"),
    /** Not one of MenuRow's ten: reached from the shelf's INSTRUMENTS list, left by its own ◄ SHELF. */
    KEYS("KEYS"),
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

/** Tap with no indication machinery — TapeOS draws its own feedback. */
@Composable
fun Modifier.tapeClick(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

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
            val selected = item.screen == current
            val itemModifier = Modifier
                .let { if (selected) it.pressedBevel(scheme, 3.dp) else it }
                .tapeClick { onSelect(item.screen) }
                .padding(horizontal = 4.dp, vertical = 3.dp)
            TapeText(
                item.label,
                TapeType.pixel,
                if (selected) scheme.ink.tape else scheme.ink2.tape,
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
 * the state holder (see `App`). Whether it shows at all is the
 * personality slider's call.
 */
@Composable
fun ToastOverlay(message: String?, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    val personality = LocalPersonality.current
    if (message == null || !Delight.toastsEnabled(personality)) return

    val t = remember(message) { Animatable(0f) }
    LaunchedEffect(message) { t.animateTo(1f, tween(Motion.TOAST_IN_MS)) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
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
