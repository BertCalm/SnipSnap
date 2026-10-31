package com.snipsnap.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.snipsnap.app.PREFS
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.EngineCatalog
import com.snipsnap.shell.EngineCategory
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.RecentEngines
import com.snipsnap.shell.engineVoicesLine
import com.snipsnap.shell.filterEngines

/*
 * The app's first bottom sheet, and the engine picker that fills it.
 *
 * [BottomSheet] is the reusable half: a window of its own with a scrim and a panel
 * hung from the bottom edge, with a handle and a title, and the three ways out (the
 * scrim, Back, a pull down on the handle). [EnginePickerContent] is the picker: a search box, a row of
 * sound-type chips and a list, drawn from the engine names and voices it is
 * handed, so a device test can drive it without SYNTH's private `Engine` enum.
 *
 * TapeOS screens draw with `foundation` alone, so nothing here is Material.
 */

/** The SharedPreferences key the picker's RECENT list is saved under, in the app's one prefs file. */
internal const val PREF_RECENT_ENGINES = "recent_engines"

/** The sheet's title: the caps of a heading. */
internal const val ENGINE_PICKER_TITLE = "CHOOSE AN ENGINE"

/** The search box's hint, a message and so in sentence case. */
internal const val ENGINE_SEARCH_HINT = "Search engines or sounds"

/** The share of the window's height the panel takes: the strip above it is the scrim, a tap on which dismisses. */
private const val PANEL_HEIGHT_SHARE = 0.88f

/** The dim behind the panel, the same as every other scrim in the app. */
private const val SCRIM_ALPHA = 0.55f

/** How far the handle must be pulled down, on release, for the sheet to go. */
private val DISMISS_PULL = 96.dp

/** The shortest an engine's row is: a name, its description and its voices, with room for a thumb. */
private val ROW_MIN_H = 64.dp

/**
 * A sheet hung from the bottom of the window, in a window of its own. The app's
 * other overlays are laid over one screen's box; this one is a Compose `Dialog` at
 * the full size of the display, so it covers the title bar, the places and the dock
 * and not only the screen that opened it, and the keyboard leaves it room on every
 * Android version.
 *
 * The window is edge to edge on purpose (`decorFitsSystemWindows = false`). A window
 * that fits the system windows is told by the platform that the keyboard is zero tall,
 * so `imePadding` has nothing to pad by before Android 15, and a resize of the whole
 * app window to get round that squeezes the screen under the sheet as well. In its own
 * edge to edge window the insets arrive as they do on Android 15, and the panel pads
 * itself by them: it stays where it is, 88 percent of the window tall, with what is in
 * it pushed up above the keys.
 *
 *  - A scrim over the whole window, drawn here (the window's own dim is turned off), a
 *    tap on it dismisses. It is a layer of its own behind the panel and not the
 *    panel's parent, because a clickable parent merges its descendants' semantics and
 *    swallows a text field's SetText.
 *  - The panel takes [PANEL_HEIGHT_SHARE] of the window's height, anchored at the
 *    bottom, on the theme's raised surface. [content] fills what is under the handle
 *    and the [title], in a column it can give weights to.
 *  - The handle can be pulled down: past [DISMISS_PULL] on release it dismisses,
 *    less and it springs back.
 *  - Back is the dialog's own dismiss, so it reaches [onDismiss] and never falls
 *    through to the screen under the sheet.
 *  - It slides up over [Motion.SHEET_SLIDE_MS], and under [LocalReduceMotion]
 *    it is simply there.
 *
 * [scrimLabel] is the scrim's accessible name: the one dismiss control a screen
 * reader can reach without a gesture. A dialog is modal to a screen reader as well:
 * what is under it is not reachable until it is gone.
 *
 * Reusable: nothing in it knows about engines. The menus the UI plan turns into
 * sheets are meant to be given to this.
 */
@Composable
fun BottomSheet(
    title: String,
    onDismiss: () -> Unit,
    scrimLabel: String = "CLOSE",
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // The scrim is the outside; a window this size has none to click.
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BottomSheetWindow(title, onDismiss, scrimLabel, content)
    }
}

/** What is inside [BottomSheet]'s window: the scrim and the panel. */
@Composable
private fun BottomSheetWindow(
    title: String,
    onDismiss: () -> Unit,
    scrimLabel: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = LocalScheme.current
    val still = LocalReduceMotion.current
    // The scrim is drawn below, so the window's own dim would darken it twice.
    // Back reaches this window only while it holds focus. The window manager
    // has been leaving the new dialog unfocused: the header test waited ten
    // seconds for that focus and it never came, so the key would have fallen
    // through to the screen under the sheet. Adding NOT_FOCUSABLE and then
    // clearing it is a layout change, which is what makes the window manager
    // hand focus over; clearing a flag that was never set does nothing by
    // itself. Once, and only while it is still unfocused, so a sheet that
    // already holds focus is left alone.
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    var claimedFocus by remember { mutableStateOf(false) }
    SideEffect {
        val dialogWindow = window ?: return@SideEffect
        dialogWindow.setDimAmount(0f)
        if (claimedFocus) return@SideEffect
        val decor = dialogWindow.decorView
        if (decor.hasWindowFocus()) {
            claimedFocus = true
            return@SideEffect
        }
        claimedFocus = true
        decor.post {
            if (!decor.isAttachedToWindow || decor.hasWindowFocus()) return@post
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }
    val latestDismiss by rememberUpdatedState(onDismiss)
    // 0 is below the bottom edge, 1 is seated. Seated from the first frame when motion is off.
    val seated = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(still) {
        if (still) seated.snapTo(1f) else seated.animateTo(1f, tween(Motion.SHEET_SLIDE_MS, easing = LinearOutSlowInEasing))
    }
    var pulled by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .tapeClick(label = scrimLabel, onClick = onDismiss),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(PANEL_HEIGHT_SHARE)
                .graphicsLayer { translationY = (1f - seated.value) * size.height + pulled }
                .raisedBevel(scheme)
                // Swallows a tap on the panel so it does not fall through to the scrim: a bare
                // gesture detector, which registers no semantics node.
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { dismiss { latestDismiss(); true } }
                // Inside the panel's own fill: the navigation bar's height, then the keyboard's
                // beyond it, so the content stops above the keys and the fill runs on behind them.
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                val far = pulled > DISMISS_PULL.toPx()
                                pulled = 0f
                                if (far) latestDismiss()
                            },
                            onDragCancel = { pulled = 0f },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                pulled = (pulled + dy).coerceAtLeast(0f)
                            },
                        )
                    }
                    .padding(top = 8.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(width = 40.dp, height = 4.dp).background(scheme.ink3.tape, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(10.dp))
                TapeText(title, TapeType.displayBig, scheme.ink.tape, Modifier.semantics { heading() })
            }
            content()
        }
    }
}

/** The engine picker in its sheet: [EnginePickerContent] under the handle and [ENGINE_PICKER_TITLE]. */
@Composable
internal fun EnginePickerSheet(
    names: List<String>,
    voicesOf: (String) -> List<String>,
    current: String,
    recents: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(ENGINE_PICKER_TITLE, onDismiss) {
        EnginePickerContent(names, voicesOf, current, recents, onPick, Modifier.weight(1f))
    }
}

/**
 * What the picker holds for SYNTH: whether the sheet is open, and the engines
 * picked lately, kept in the app's prefs under [PREF_RECENT_ENGINES]. One holder
 * and not loose locals in `SynthScreen`, which is at the limit of what the
 * toolchain verifies on a device (`rememberEmbouchureRestore` is the same shape).
 *
 * [valid] is the engines that exist now: a saved name that is not among them
 * (a build that retired an engine) is dropped on the way in and so is not saved
 * back.
 */
internal class EnginePickerState(private val prefs: SharedPreferences, valid: Set<String>) {
    var open by mutableStateOf(false)

    private val history = RecentEngines.decode(prefs.getString(PREF_RECENT_ENGINES, null), valid)

    var recents by mutableStateOf(history.list)
        private set

    /** Records a pick from the sheet: first in RECENT, and saved. */
    fun picked(name: String) {
        history.used(name)
        recents = history.list
        prefs.edit().putString(PREF_RECENT_ENGINES, history.encode()).apply()
    }
}

/** The picker's state for one SYNTH screen, read from the app's prefs once. */
@Composable
internal fun rememberEnginePicker(valid: Set<String>): EnginePickerState {
    val context = LocalContext.current
    return remember { EnginePickerState(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), valid) }
}

/**
 * The picker: a search box, the sound-type chips, and the engines as a list.
 *
 * Driven by [names] (the picker's `Engine.name` values) and [voicesOf], so it knows
 * nothing of `SynthScreen`'s private enum; the catalogue supplies each engine's
 * description and categories. What it lists is [filterEngines]'s answer for the
 * query and the chips, which the JVM tests decide. The query and the chips are
 * the sheet's own state: a sheet opened again starts clean.
 *
 *  - **RECENT** is [recents] still in [names], above the list, and only while the
 *    query is empty and no chip is on. Then ALL ENGINES (n), or RESULTS (n) once
 *    anything filters. An engine can be in both; they are different rows with
 *    different keys.
 *  - **No match** says so, with a CLEAR FILTERS button that empties the query and
 *    the chips.
 *  - **Picking** a row calls [onPick] with the engine's name. The caller switches
 *    to it and closes the sheet.
 */
@Composable
internal fun EnginePickerContent(
    names: List<String>,
    voicesOf: (String) -> List<String>,
    current: String,
    recents: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    var query by remember { mutableStateOf("") }
    var chips by remember { mutableStateOf(emptySet<EngineCategory>()) }
    val results = remember(names, query, chips) { filterEngines(names, voicesOf, query, chips) }
    val filtered = query.isNotBlank() || chips.isNotEmpty()
    val recent = if (filtered) emptyList() else recents.filter { it in names }
    val listState = rememberLazyListState()
    // A new search starts at the top: the first match is the one that matters.
    LaunchedEffect(query, chips) { listState.scrollToItem(0) }

    Column(modifier.fillMaxWidth()) {
        SearchField(query) { query = it }
        val chipRow = rememberLazyListState()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Pinned at the row's start and outside the scrolling list, so it never scrolls away.
            // Always in the row, dim and inert while no chip is on: a chip that came and went
            // would narrow the list beside it by its own width, and every chip would jump that
            // far sideways under a thumb that had just pressed one.
            AllChip(enabled = chips.isNotEmpty()) { chips = emptySet() }
            LazyRow(
                Modifier.weight(1f).fadeEdges(chipRow),
                state = chipRow,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(EngineCategory.entries, key = { it.name }) { category ->
                    val on = category in chips
                    CategoryChip(category.label, on) { chips = if (on) chips - category else chips + category }
                }
            }
        }
        if (results.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                TapeText("No engines match.", TapeType.pixelSmall, scheme.ink.tape)
                ActionButton("CLEAR FILTERS", scheme, enabled = true) {
                    query = ""
                    chips = emptySet()
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                if (recent.isNotEmpty()) {
                    item(key = "header:recent") { SectionHeader("RECENT") }
                    items(recent, key = { "recent:$it" }) { name ->
                        EngineRow(name, voicesOf(name), query, name == current, onPick)
                    }
                }
                item(key = "header:all") {
                    SectionHeader(if (filtered) "RESULTS (${results.size})" else "ALL ENGINES (${results.size})")
                }
                items(results, key = { "all:$it" }) { name ->
                    EngineRow(name, voicesOf(name), query, name == current, onPick)
                }
            }
        }
    }
}

/** The search box: a sunken field with a hint, and a clear button once there is something to clear. Not focused until touched. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val scheme = LocalScheme.current
    val focus = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .sunkenField(scheme)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Search,
                ),
                // The search key puts the keyboard away so the whole list shows.
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                textStyle = TapeType.pixelSmall.copy(color = scheme.ink.tape, fontSize = 14.sp),
                cursorBrush = SolidColor(scheme.ink.tape),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "SEARCH ENGINES" },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            TapeText(ENGINE_SEARCH_HINT, TapeType.pixelSmall.copy(fontSize = 14.sp), scheme.ink2.tape)
                        }
                        field()
                    }
                },
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(Layout.MIN_HIT_TARGET.dp)
                    .tapeClick(label = "CLEAR SEARCH") { onQuery("") },
                contentAlignment = Alignment.Center,
            ) {
                TapeText("✕", TapeType.pixel, scheme.ink.tape)
            }
        }
    }
}

/**
 * A sound-type filter: a toggle. On it is a filled chip, in the ink on the panel's
 * colour; off it is an outline. The fill is the cue, not the hue, so it reads in
 * every scheme and to anyone who cannot tell two colours apart.
 *
 * The touch target is [Layout.MIN_HIT_TARGET] tall and the chip is drawn inside
 * it. `tapeClick` makes every control a button; this one is a check box (`toggleable`,
 * so a screen reader is told it is one, and whether it is checked).
 */
@Composable
private fun CategoryChip(label: String, on: Boolean, onToggle: () -> Unit) {
    val scheme = LocalScheme.current
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .toggleable(
                value = on,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .semantics { contentDescription = label }
            .padding(vertical = 6.dp)
            .let { if (on) it.background(scheme.ink.tape, shape) else it.border(1.dp, scheme.ink3.tape, shape) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (on) scheme.gray.tape else scheme.ink.tape)
    }
}

/**
 * ALL: clears the chips. A button and not a toggle, drawn as a chip (an outline, in the
 * accent) so it reads as one of the row, and at least [Layout.MIN_HIT_TARGET] wide as well
 * as tall: its three letters alone are about a third of that.
 *
 * It is there whether or not [enabled] (that is, whether or not any chip is on) and so takes
 * the same room either way. While no chip is on it is in the quiet ink, with nothing to
 * clear, and reads to a screen reader as unavailable, which it is.
 */
@Composable
private fun AllChip(enabled: Boolean, onClear: () -> Unit) {
    val scheme = LocalScheme.current
    val ink = if (enabled) scheme.accent.tape else scheme.ink3.tape
    Box(
        Modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .widthIn(min = Layout.MIN_HIT_TARGET.dp)
            .tapeClick(label = "ALL", enabled = enabled, onClick = onClear)
            .padding(vertical = 6.dp)
            .border(1.dp, ink, RoundedCornerShape(2.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText("ALL", TapeType.pixel, ink)
    }
}

/** How wide the fade is where a scrolling row runs on past its edge. */
private val EDGE_FADE = 28.dp

/**
 * Fades the end of a scrolling row that has more beyond it, at the trailing edge and, once
 * it has scrolled, the leading one, so that a chip cut off by the edge reads as "more this
 * way" and not as a chip with its end missing. The fades are drawn only while there is
 * something to scroll to, read in the draw phase so a scroll redraws and recomposes nothing.
 */
private fun Modifier.fadeEdges(state: LazyListState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val width = EDGE_FADE.toPx()
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - width, endX = size.width),
                topLeft = Offset(size.width - width, 0f),
                size = Size(width, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = width),
                topLeft = Offset.Zero,
                size = Size(width, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** A heading over a run of rows. */
@Composable
private fun SectionHeader(text: String) {
    TapeText(
        text,
        TapeType.pixel,
        LocalScheme.current.ink2.tape,
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).semantics { heading() },
    )
}

/**
 * One engine: its name (with ● CURRENT on the one SYNTH is on), what it is, and
 * the voices it has, the last in the quieter ink and smaller, with the ones the
 * [query] matched first. At least [ROW_MIN_H] tall, and a button named "name,
 * description" so a screen reader gets both in one breath. An engine the catalogue
 * does not know shows its name alone.
 */
@Composable
private fun EngineRow(name: String, voices: List<String>, query: String, isCurrent: Boolean, onPick: (String) -> Unit) {
    val scheme = LocalScheme.current
    val display = name.replace('_', ' ')
    val description = EngineCatalog.entry(name)?.description.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_H)
                .let { if (isCurrent) it.background(scheme.field.tape) else it }
                .semantics { selected = isCurrent }
                .tapeClick(label = if (description.isEmpty()) display else "$display, $description") { onPick(name) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The name takes what the marker leaves, so the marker is at the row's right edge.
                TapeText(display, TapeType.display, scheme.ink.tape, Modifier.weight(1f))
                if (isCurrent) TapeText("● CURRENT", TapeType.pixel, scheme.accent.tape)
            }
            if (description.isNotEmpty()) {
                TapeText(description, TapeType.pixelSmall, scheme.ink.tape, maxLines = 2)
            }
            val line = engineVoicesLine(voices, query = query)
            if (line.isNotEmpty()) {
                TapeText(line, TapeType.pixel, scheme.ink2.tape, maxLines = 2)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.grayEdge.tape))
    }
}
