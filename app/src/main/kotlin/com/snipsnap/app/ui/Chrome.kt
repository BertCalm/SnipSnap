package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.oilslickSweep
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Places
import com.snipsnap.shell.SchemeId

/**
 * The screens `App.kt` navigates by name. Under the four-place shape
 * (`docs/SCREEN_PLAN_2026_10.md` §2) none of these is a tab any more:
 * `App.kt`'s `locationOf` maps each one onto a place, a stage or a bench
 * of the `:shell` `Navigation` model, and that map is the plan's §2 table
 * as code — exhaustive, so a new entry here without a landing there
 * fails to compile instead of silently falling through. (There used to
 * be a `StubScreen` and an `else ->` branch for milestones that hadn't
 * shipped yet; every one of them had shipped long before the stub was
 * finally deleted, which is exactly the failure mode the missing
 * catch-all now prevents from recurring.)
 */
enum class AppScreen(val label: String) {
    /**
     * The shelf. Its label reads SHELF, not KITS (J14).
     *
     * KIT receives 11 of the 24 direct navigations in `App.kt` — nearly
     * half, and almost three times the next destination — while this
     * screen sat one letter away from it in the menu row. The app's own
     * voice had already picked a side: `Copy` says SHELF 41 times against
     * KITS 22, and `Copy.landed` reads "KITS LANDED ON **THE SHELF**".
     * The tab was the last place still calling it the other thing.
     *
     * The enum constant stays `KITS`, like `ChopReviewModel.GridSnap` and
     * `GrooveEdit.NAME_SUFFIX` before it: it is an identity the code
     * navigates by, not a word a player reads. Only [label] is the word.
     *
     * This settles the screen's NAME, not its form. `UI_DESIGN.md` still
     * lists "whether the desktop metaphor extends to a 'My Kits'
     * file-manager screen or kits stay a menu" as open, and it stays open.
     */
    KITS("SHELF"),
    KIT("KIT"),
    TAPE("TAPE"),
    CHOP("CHOP"),
    GROOVE("GROOVE"),
    /** The circular sequencer: the kit on rings of different lengths. Its own menu entry, since a sequencer is a peer of GROOVE, not a corner of it. */
    ORBIT("ORBIT"),
    SYNTH("SYNTH"),
    /** The tactile pad: macros under a finger, PRINT to resample the gesture onto TAPE. */
    SURFACE("SURFACE"),
    /** A photo becomes a pad: the camera's line read as a wavetable, its colours as the knobs. */
    SNAP("SNAP"),
    EXPORT("EXPORT"),
    PROPERTIES("SETUP"),
    HELP("HELP"),
    /** Never a tab: reached from the shelf's INSTRUMENTS list, left by its own ◄ SHELF. */
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
 * audit). Pass a real [label] always — including when a descendant
 * `TapeText` already shows the same words on screen. This modifier used
 * to say `clickable` merges descendant semantics into this node
 * automatically, so `null` was "fine" whenever visible text was nearby;
 * an accessibility-tree dump (2026-09 followup) proved that claim false
 * for the unlabelled case: `clickable`'s own semantics node did not
 * fold sibling text into itself, so every call site relying on it
 * shipped a clickable node with an empty name.
 *
 * What that same followup could NOT settle: whether an explicit
 * [label] here lands on the same accessibility node `clickable` makes
 * actionable, or on an adjacent one. `uiautomator dump` (the only tool
 * this pass was permitted — TalkBack itself destabilised the test
 * emulator in an earlier session) shows both `clickable().semantics {
 * contentDescription = label }` and the same call with
 * `mergeDescendants = true` added producing byte-identical trees, and
 * this file's own KNOWN-GOOD reference (`KitRow` in KitsScreen.kt,
 * explicit `mergeDescendants = true`, reviewed and shipped) shows that
 * same shape too — an outer node with an empty raw `content-desc` and
 * separate child text nodes beneath it. That means the dump cannot
 * distinguish "TalkBack speaks [label] once, correctly" from "TalkBack
 * speaks it as a second, adjacent, non-actionable node": both render
 * the same way to this tool. Left at the pre-existing, reviewed shape
 * (`clickable(...).semantics { contentDescription = label }`, no merge
 * flag) rather than shipping an unverified change across the ~113
 * call sites that route through this one function. Flagged for
 * adjudication with real TalkBack, not re-guessed here.
 *
 * `null` is for controls that genuinely have no accessible name to
 * give — not a shorthand for "the text nearby covers it." (A control
 * whose `onClick` is genuinely empty, e.g. a tap-absorbing scrim card,
 * isn't reachable through `tapeClick` at all for that purpose — see
 * call sites using a raw `Modifier.pointerInput { detectTapGestures {}
 * }` instead, which registers no semantics node.)
 *
 * [enabled] mirrors `clickable`'s own flag: a disabled control keeps
 * its semantics node (and its name) but exposes Compose's `disabled()`
 * state instead of an actionable one, so a screen-reader user is told
 * "temporarily unavailable" instead of the control silently vanishing
 * from the tree (audit finding 12).
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

/**
 * The title bar. [onNote] is the WORKSHOP's NOTE chip (`docs/WORKSHOP.md`,
 * WS4): non-null only while the workshop is open, so a phone that never
 * knocked draws the bar exactly as before. It lives here, on the one row
 * every screen shares, because a bench note is about whatever screen is
 * under it - PLAY, CHOP, a pad sheet - and a chip per screen would be one
 * more thing per screen to keep in step. A raised bevel at the bar's right
 * end: the Win9x button-on-a-title-bar, which is what this is.
 *
 * [projectName] is the open project, at the bar's right (plan §1): the
 * one word of state the title carries, since the status bar that carried
 * the open kit's name is gone and the project is the unit now.
 *
 * [onGear] is the gear (`docs/SCREEN_PLAN_2026_10.md` §1): SETUP and HELP
 * behind one chip at the bar's right end, one tap from every screen, where
 * the two used to be the twelfth and thirteenth tabs of a row that fit
 * nine. Drawn as the same button-on-a-bar the NOTE chip is.
 */
@Composable
fun TitleBar(
    modifier: Modifier = Modifier,
    projectName: String? = null,
    onNote: (() -> Unit)? = null,
    onGear: () -> Unit,
) {
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
        // The build tag used to read "M0" here, and still did five milestones
        // later (September UAT, finding 2). Next to a HELP screen describing a
        // skeleton it made a finished app look like an abandoned prototype.
        // Gone rather than corrected: there is no version constant to hang a
        // true one on, and a hardcoded tag is exactly what went stale before.
        TapeText("SNIPSNAP.EXE", TapeType.displayBig, scheme.titleInk.tape)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (projectName != null) {
                TapeText(projectName.uppercase(), TapeType.pixel, scheme.titleInk.tape, Modifier.widthIn(max = 140.dp))
            }
            if (onNote != null) {
                TitleChip("NOTE", label = "NOTE", onClick = onNote)
            }
            // The gear's accessible name says what is behind it; the glyph
            // alone would be read as a symbol name or not at all.
            TitleChip("⚙", label = "${Places.GEAR_SETUP} · ${Places.GEAR_HELP}", onClick = onGear)
        }
    }
}

/**
 * A button on the title bar. The whole 34dp height is the tap target; the
 * bevel sits inside a vertical inset so it reads as a button on the bar
 * rather than the bar's own edge. `tapeClick` before the inset, so the
 * inset is inside the target and not taken out of it.
 */
@Composable
private fun TitleChip(text: String, label: String, onClick: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .fillMaxHeight()
            .tapeClick(label = label, onClick = onClick)
            .padding(vertical = 5.dp)
            .raisedBevel(scheme)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(text, TapeType.pixel, scheme.ink.tape)
    }
}

/** One menu entry: its label and the screen it lands on. */
data class MenuItem(val label: String, val screen: AppScreen)

// The tab era's menu, kept as data with no reader. `MenuRow` is gone:
// the frame draws the four places and their stages from `Navigation`
// (`Frame.kt`), and nothing in the app lists these twelve any more. The
// declaration stays until the tab-era copy goes with it — the first-run
// note and HELP still name these tabs, and `ConventionTest`'s "first-run
// loop names real menu tabs" law reads `MenuItem("...")` here to hold
// them to real names. The gear step of `docs/SCREEN_PLAN_2026_10.md`
// retires that copy, that law's reading of this file, and this list in
// one change. Everything below it is the history of an order that no
// longer draws.
//
// EXPORT moved up beside TAPE/CHOP/KIT (name-and-find followups,
// truncation pass): the cold-open screen states the app's own primary
// flow as "TAPE ▸ CHOP ▸ KIT ▸ EXPORT — four tabs, in order," but at
// twelve items EXPORT sat at position 10 — past the ~8-9 tabs that fit
// on screen at every width tested (390dp/411dp/360dp), so step four of
// the app's own stated loop was never visible without a drag the row
// gives no other cue to try beyond its ◂/▸ edges. Reordering (not
// shortening, not wrapping) is the smallest change that puts it back in
// the visible run — total row width is unchanged, only which items land
// in the first ~9 slots. `ConventionTest`'s "first-run loop names real
// menu tabs" law only checks membership, not order, so this is safe
// against it.
//
// KIT then moved after CHOP (same followups): the shelf is where you
// arrive, not one of the four steps, so it stays first — but KIT had
// landed second, ahead of TAPE and CHOP, which put the strip out of step
// with the loop it was just fixed to make visible. The four flow tabs
// now read TAPE ▸ CHOP ▸ KIT ▸ EXPORT, matching the stated order exactly,
// still inside the same visible run.
//
// J12: the twelve tabs are four groups, and the row now says so with a
// [Layout.MENU_GROOVE_W] rule between them. The order above was already
// doing the grouping's work - the four flow tabs in their stated order,
// then the instruments, then the two utilities - but nothing drew the
// seams, so a strip of twelve equal words read as one undifferentiated
// run and the reordering's whole point was legible only to someone who
// already knew the loop.
//
// The groups are nested here rather than marked by a boundary index or a
// `groupStart` flag on [MenuItem], because either of those is the order
// written down a second time: move a tab and the index still points at
// the old seam, silently. Nesting makes the order and the grouping the
// same declaration, so they cannot disagree. `ConventionTest`'s
// "first-run loop names real menu tabs" law reads `MenuItem("...")` by
// regex, so it sees all twelve through the nesting unchanged.
//
// There is no flattened `MENU_ITEMS` beside this any more: `MenuRow` was
// its only reader, and a second spelling of the same twelve tabs kept
// only for tidiness is the shape this file has just finished removing.
val MENU_GROUPS = listOf(
    // Where you arrive, and the only tab that is a place rather than a
    // thing you do to a sound.
    listOf(
        MenuItem("SHELF", AppScreen.KITS),
    ),
    // The loop the empty shelf states in words, in its stated order.
    listOf(
        MenuItem("TAPE", AppScreen.TAPE),
        MenuItem("CHOP", AppScreen.CHOP),
        MenuItem("KIT", AppScreen.KIT),
        MenuItem("EXPORT", AppScreen.EXPORT),
    ),
    // Ways to play what the loop made.
    listOf(
        MenuItem("GROOVE", AppScreen.GROOVE),
        MenuItem("ORBIT", AppScreen.ORBIT),
        MenuItem("SYNTH", AppScreen.SYNTH),
        MenuItem("SURFACE", AppScreen.SURFACE),
        MenuItem("SNAP", AppScreen.SNAP),
    ),
    // Not about a sound at all.
    listOf(
        MenuItem("SETUP", AppScreen.PROPERTIES),
        MenuItem("HELP", AppScreen.HELP),
    ),
)

/**
 * One door out of an [EmptyStatePanel] — a [label] and the [onClick] it
 * fires. The label carries its own "▸" when it opens another screen, the
 * same convention `KitsScreen.kt`'s `ArmControl` KDoc already documents
 * (▸ for "opens a screen or panel," none for an in-place action).
 */
data class EmptyStateRoute(val label: String, val onClick: () -> Unit)

/**
 * The shape behind every screen's own "nothing here" face: a message plus
 * the real controls that get a user out of it, not just words naming
 * them. Pulled out once GROOVE's `loadedBase == null` branch (RECORD /
 * STEPS / ORBIT ▸, right under its own message) turned out to be the
 * template seven *other* empty states should have followed all along —
 * KIT, PLAY, ORBIT (menu-row door), GROOVE-with-no-kit, TAPE, EXPORT and
 * CHOP each used to draw [message] alone and leave the route it named for
 * the user to find by hand (September UAT follow-up).
 *
 * [routes] is usually one door, sometimes two (CHOP's own EMPTY_CHOP names
 * both TAPE and SHELF, since its own sentence promises both) — deliberately
 * never validated non-empty here: a screen with no honest route to offer
 * (EXPORT's own "kit won't parse" face, `KIT_WONT_OPEN`, same reasoning as
 * `TakesBinScreen`'s) simply doesn't call this at all, and stays a bare
 * message the way it always was.
 */
@Composable
fun EmptyStatePanel(
    message: String,
    routes: List<EmptyStateRoute>,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    Box(
        modifier
            .fillMaxSize()
            .lcdPanel(scheme)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText(message, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
            routes.forEach { route ->
                ActionButton(route.label, scheme, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = route.onClick)
            }
        }
    }
}
