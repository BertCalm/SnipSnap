package com.snipsnap.app.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine picker's content and its sheet, driven on a device with a fixed list of
 * engines and voices. The private `Engine` enum is SYNTH's, so these hand the content
 * the same names and voices SYNTH would; what is chosen to show for a query, a chip
 * set and a history is also decided on the JVM (`EngineCatalogTest`), and what is
 * tested here is that the screen draws that answer and answers the finger.
 *
 * Reduce motion is on: the slide is the one thing in the sheet that moves, and the
 * frozen frame clock this suite runs on is for tests that look at it deliberately.
 *
 * The fixture is small on purpose. A lazy list composes only what is in view and this
 * suite must never scroll (see [ComposeScreenTest]), so every row a test asserts has
 * to be in the first screenful. Rows are found by their names and not by taps: chips
 * and rows are in lazy lists, where the base class's `tap` would scroll.
 */
@RunWith(AndroidJUnit4::class)
class EnginePickerSheetTest : ComposeScreenTest() {

    private val voices = mapOf(
        "THUMP" to listOf("KICK", "SNARE", "CLAP"),
        "ONSET" to listOf("KICK", "SNARE", "HAT_CLOSED", "HAT_OPEN"),
        "TINES" to listOf("BELL", "CHIME", "KALIMBA"),
        "FATHOM" to listOf("DEEP", "GRIND", "GLASS"),
        "VELVET" to listOf("BASS", "BRASS", "SQUELCH", "CHIP", "ACID", "SUB"),
        "SCANNED_CHAIN" to listOf("PLUCK", "METAL", "BLOOM"),
    )

    /** Deliberately out of order: the list's order must come from the search, not from what it was given. */
    private val names = listOf("THUMP", "VELVET", "ONSET", "TINES", "SCANNED_CHAIN", "FATHOM")
    private val aToZ = listOf("FATHOM", "ONSET", "SCANNED_CHAIN", "THUMP", "TINES", "VELVET")

    private var picked: String? = null
    private var dismissed = 0
    private var dispatcher: OnBackPressedDispatcher? = null

    private fun voicesOf(name: String) = voices[name].orEmpty()

    private fun show(current: String = "THUMP", recents: List<String> = emptyList()) {
        picked = null
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                EnginePickerContent(names, ::voicesOf, current, recents, onPick = { picked = it }, modifier = Modifier.fillMaxSize())
            }
        }
    }

    private fun showSheet(still: Boolean = true, settleMillis: Long = 300) {
        dismissed = 0
        setPhoneContent(settleMillis) {
            CompositionLocalProvider(LocalReduceMotion provides still) {
                BottomSheet("CHOOSE AN ENGINE", onDismiss = { dismissed++ }) {
                    // The sheet is a dialog, with a back dispatcher of its own, which the activity's
                    // is not: take it from the view this content is in.
                    val view = LocalView.current
                    SideEffect { dispatcher = view.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher }
                    TapeText("the body of the sheet", TapeType.pixelSmall, LocalScheme.current.ink.tape)
                }
            }
        }
    }

    /** The sheet's own window: a dialog, so the activity's root is not the one to measure against. */
    private fun dialogBounds() = compose.onNode(isDialog()).getBoundsInRoot()

    /** The engines listed now, top to bottom, read off the rows' names ("SCANNED CHAIN, Evolving ..."). */
    private fun rows(): List<String> =
        compose.onAllNodes(hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .fetchSemanticsNodes()
            .mapNotNull { it.config[SemanticsProperties.ContentDescription].firstOrNull() }
            .filter { ", " in it }
            .map { it.substringBefore(", ").replace(' ', '_') }

    private fun type(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        pump()
    }

    private fun toggle(chip: String) {
        button(chip).performClick()
        pump()
    }

    private fun rowNode(name: String) = compose.onNode(
        hasContentDescription(name.replace('_', ' ') + ", ", substring = true) and hasClickAction(),
    )

    private fun exists(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun top(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true)[0].getBoundsInRoot().top

    // ---- search ----

    @Test
    fun withNothingTypedEveryEngineIsListedAToZ() {
        show()
        assertEquals(aToZ, rows())
        assertTrue(exists("ALL ENGINES (6)"))
        assertTrue("no RECENT header without recents", !exists("RECENT"))
    }

    @Test
    fun typingKickShowsThumpAndOnsetAndNotTines() {
        show()
        type("kick")
        assertEquals(listOf("ONSET", "THUMP"), rows())
        assertTrue("TINES must not be listed", "TINES" !in rows())
        assertTrue(exists("RESULTS (2)"))
        assertTrue("ALL ENGINES is the unfiltered header", !exists("ALL ENGINES (6)"))
    }

    @Test
    fun theSearchIsCaseAndUnderscoreInsensitive() {
        show()
        type("HaT ClOsEd")
        assertEquals(listOf("ONSET"), rows())
        type("scanned chain")
        assertEquals(listOf("SCANNED_CHAIN"), rows())
    }

    @Test
    fun theSearchFieldIsNotFocusedAndHintsUntilTyped() {
        show()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        assertTrue(exists("Search engines or sounds"))
        assertTrue("no clear button on an empty box", !exists("✕"))
        type("kick")
        assertTrue("the hint goes once there is text", !exists("Search engines or sounds"))
        button("CLEAR SEARCH").assertExists()
        button("CLEAR SEARCH").performClick()
        pump()
        assertEquals(aToZ, rows())
        assertTrue(exists("Search engines or sounds"))
    }

    // ---- chips ----

    @Test
    fun theBassChipShowsOnlyBassTaggedEnginesAndASecondChipWidensIt() {
        show()
        toggle("BASS")
        assertEquals(listOf("FATHOM", "VELVET"), rows())
        button("BASS").assertIsOn()
        toggle("DRUMS & PERCUSSION")
        // OR across the chips: the drums join the bass engines. TINES is tagged drums second.
        assertEquals(listOf("FATHOM", "ONSET", "THUMP", "TINES", "VELVET"), rows())
        button("DRUMS & PERCUSSION").assertIsOn()
        toggle("BASS")
        assertEquals(listOf("ONSET", "THUMP", "TINES"), rows())
        button("BASS").assertIsOff()
    }

    @Test
    fun theQueryAndTheChipsIntersect() {
        show()
        type("kick")
        toggle("BASS")
        assertTrue("kick is on no bass engine", exists("No engines match."))
        toggle("DRUMS & PERCUSSION")
        assertEquals(listOf("ONSET", "THUMP"), rows())
    }

    @Test
    fun theAllChipIsThereOnlyWhileAChipIsOnAndClearsThem() {
        show()
        assertTrue("no ALL chip with every chip off", compose.onAllNodes(hasContentDescription("ALL")).fetchSemanticsNodes().isEmpty())
        toggle("BASS")
        // A thumb target both ways (its three letters alone are about a third of 48 dp wide), and
        // pinned ahead of the chips, which scroll in the space it leaves.
        button("ALL").assertWidthIsAtLeast(48.dp)
        button("ALL").assertHeightIsAtLeast(48.dp)
        assertTrue(
            "ALL comes before the first chip",
            button("ALL").getBoundsInRoot().right <= button("DRUMS & PERCUSSION").getBoundsInRoot().left,
        )
        toggle("DRUMS & PERCUSSION")
        button("ALL").performClick()
        pump()
        assertEquals(aToZ, rows())
        button("BASS").assertIsOff()
        assertTrue(compose.onAllNodes(hasContentDescription("ALL")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun chipsAreCheckBoxesAtLeastFortyEightDpTall() {
        show()
        val chip = button("BASS")
        chip.assertHeightIsAtLeast(48.dp)
        assertEquals(Role.Checkbox, chip.fetchSemanticsNode().config[SemanticsProperties.Role])
        chip.assertIsOff()
        toggle("BASS")
        chip.assertIsOn()
    }

    // ---- no match ----

    @Test
    fun aNonsenseQuerySaysSoAndClearFiltersBringsTheListBack() {
        show()
        toggle("BASS")
        type("zzqx")
        assertTrue(exists("No engines match."))
        assertEquals(emptyList<String>(), rows())
        button("CLEAR FILTERS").performClick()
        pump()
        assertEquals(aToZ, rows())
        assertTrue(!exists("No engines match."))
        button("BASS").assertIsOff()
        assertTrue("the search box is empty again", exists("Search engines or sounds"))
    }

    // ---- recents ----

    @Test
    fun recentsShowAboveAllEnginesOnlyWhileUnfiltered() {
        show(recents = listOf("TINES"))
        assertTrue(exists("RECENT"))
        assertEquals("TINES comes first, above the whole list", listOf("TINES") + aToZ, rows())
        assertTrue("RECENT is above the first recent row", top("RECENT") < top("ALL ENGINES (6)"))

        type("t")
        assertTrue("a query hides RECENT", !exists("RECENT"))
        type("")
        assertTrue(exists("RECENT"))

        toggle("BASS")
        assertTrue("a chip hides RECENT", !exists("RECENT"))
        toggle("BASS")
        assertTrue(exists("RECENT"))
    }

    @Test
    fun aRecentEngineThatIsGoneFromThePickerIsNotListed() {
        show(recents = listOf("GONE", "TINES"))
        assertEquals(listOf("TINES") + aToZ, rows())
    }

    // ---- rows ----

    @Test
    fun aRowNamesTheEngineAndWhatItIsAndIsAButtonAtLeastSixtyFourDpTall() {
        show()
        val row = rowNode("FATHOM")
        row.assertHeightIsAtLeast(64.dp)
        val config = row.fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals("FATHOM, Deep, grinding and glassy bass with glide", config[SemanticsProperties.ContentDescription].single())
        assertEquals("an underscore reads as a space", "SCANNED CHAIN, Evolving pitched notes: springy plucks, metal and swells",
            rowNode("SCANNED_CHAIN").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
    }

    @Test
    fun aRowShowsUpToFourVoicesAndCountsTheRest() {
        show()
        assertTrue(exists("BASS · BRASS · SQUELCH · CHIP +2"))
        assertTrue(exists("KICK · SNARE · CLAP"))
        assertTrue("an underscore in a voice reads as a space", exists("KICK · SNARE · HAT CLOSED · HAT OPEN"))
    }

    @Test
    fun theSearchFindsADescriptionAtTheStartOfAWordAndACategoryLabel() {
        show()
        // THUMP: "Analog-style kick, ..." in its description, in no voice or name.
        type("analog")
        assertEquals(listOf("THUMP"), rows())
        type("nalog")
        assertTrue("the middle of a word is no match", exists("No engines match."))
        // The label of the DRUMS & PERCUSSION chip, which is on three engines' tags.
        type("percussion")
        assertEquals(listOf("ONSET", "THUMP", "TINES"), rows())
    }

    @Test
    fun theVoicesAQueryHitComeFirstInTheVoicesLine() {
        show()
        // VELVET has six voices; ACID is the fifth, behind the "+2", until the query puts it first.
        type("acid")
        assertEquals(listOf("VELVET"), rows())
        assertTrue(exists("ACID · BASS · BRASS · SQUELCH +2"))
        assertTrue("the unfiltered line is unchanged", !exists("BASS · BRASS · SQUELCH · CHIP +2"))
    }

    @Test
    fun theCurrentEngineIsMarkedOnce() {
        show(current = "FATHOM")
        assertEquals(1, compose.onAllNodesWithText("● CURRENT", useUnmergedTree = true).fetchSemanticsNodes().size)
        rowNode("FATHOM").assertIsSelected()
        rowNode("ONSET").assertIsNotSelected()
    }

    @Test
    fun tappingARowPicksThatEngine() {
        show()
        rowNode("FATHOM").performClick()
        pump()
        assertEquals("FATHOM", picked)
        // The name handed back is the engine's constant, underscore and all.
        rowNode("SCANNED_CHAIN").performClick()
        pump()
        assertEquals("SCANNED_CHAIN", picked)
    }

    // ---- the sheet ----

    @Test
    fun theSheetIsTitledAndTakesAboutEightyEightPercentOfTheHeight() {
        showSheet()
        assertTrue(exists("CHOOSE AN ENGINE"))
        val panel = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).getBoundsInRoot()
        val root = dialogBounds()
        val share = panel.height.value / root.height.value
        assertTrue("the panel is $share of the height", share in 0.85f..0.91f)
        assertEquals("the panel is anchored at the bottom", root.bottom.value, panel.bottom.value, 1f)
    }

    @Test
    fun backDismissesTheSheet() {
        showSheet()
        assertNotNull("the host has a back dispatcher", dispatcher)
        compose.runOnUiThread { dispatcher!!.onBackPressed() }
        pump()
        assertEquals(1, dismissed)
    }

    @Test
    fun aTapOnTheScrimDismissesTheSheet() {
        showSheet()
        // The panel covers the lower part of the scrim, so the tap goes on the strip above it.
        button("CLOSE").performTouchInput { click(Offset(centerX, 24f)) }
        pump()
        assertEquals(1, dismissed)
    }

    @Test
    fun aTapOnThePanelDoesNotFallThroughToTheScrim() {
        showSheet()
        compose.onNodeWithText("the body of the sheet").performClick()
        pump()
        assertEquals(0, dismissed)
    }

    @Test
    fun pullingTheHandleDownDismissesAndAShortPullSpringsBack() {
        showSheet()
        val title = compose.onNodeWithText("CHOOSE AN ENGINE")
        title.performTouchInput {
            down(center)
            moveBy(Offset(0f, 40f))
            up()
        }
        pump()
        assertEquals("a short pull is not a dismiss", 0, dismissed)
        title.performTouchInput {
            down(center)
            moveBy(Offset(0f, 150f))
            moveBy(Offset(0f, 150f))
            up()
        }
        pump()
        assertEquals(1, dismissed)
    }

    @Test
    fun withMotionAllowedTheSheetSlidesUpOver200Ms() {
        showSheet(still = false, settleMillis = 0)
        val root = dialogBounds()
        val start = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).getBoundsInRoot()
        assertTrue("the panel starts below the bottom edge: ${start.top} of ${root.bottom}", start.top >= root.bottom - 1.dp)
        pump(100)
        val middle = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).getBoundsInRoot()
        assertTrue("halfway through it has moved up: ${middle.top} from ${start.top}", middle.top < start.top)
        pump(300)
        val seated = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).getBoundsInRoot()
        assertEquals(root.bottom.value - seated.height.value, seated.top.value, 1f)
    }

    @Test
    fun withReducedMotionTheSheetIsSeatedFromTheFirstFrame() {
        showSheet(still = true, settleMillis = 0)
        val root = dialogBounds()
        val panel = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).getBoundsInRoot()
        assertEquals(root.bottom.value, panel.bottom.value, 1f)
    }
}
