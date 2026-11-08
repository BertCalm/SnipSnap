package com.snipsnap.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.StarterKits
import com.snipsnap.shell.StarterShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Phone-height starter browsing, without rendering the selected kit. */
@RunWith(AndroidJUnit4::class)
class StarterMenuTest : ComposeScreenTest() {

    // StarterMenu is a BottomSheet: a Dialog, a window of its own at the size of the display,
    // so the old 480 dp host box no longer bounds it (SynthEngineHeaderTest's caveat). The
    // roster is far longer than a sheet's 88%, so a row below the fold still has to be scrolled to.
    private fun show(onPick: (StarterKits.Starter) -> Unit, onDismiss: () -> Unit) {
        setPhoneContent {
            StarterMenu(onPick = onPick, onDismiss = onDismiss)
        }
        // Past the slide up, so a touch lands on a seated panel.
        pump(Motion.SHEET_SLIDE_MS + 100L)
    }

    private fun dragChoicesUp() {
        // The shared clock is frozen. Avoid tap()/performScrollTo(), whose
        // animated semantics scroll can wait for frames that never arrive.
        compose.onNode(hasScrollAction()).performTouchInput {
            down(0, Offset(center.x, height * .80f))
            repeat(12) { moveBy(0, Offset(0f, -height * .05f), delayMillis = 40L) }
            // Pause before lifting so the drag does not turn into a fling.
            advanceEventTime(200L)
            up(0)
        }
        pump()
    }

    private fun rowFitsViewport(row: SemanticsNodeInteraction): Boolean {
        val bounds = row.getUnclippedBoundsInRoot()
        val viewport = compose.onNode(hasScrollAction()).getBoundsInRoot()
        return bounds.left >= viewport.left && bounds.right <= viewport.right &&
            bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
    }

    @Test
    fun a_starter_below_the_phone_viewport_can_be_scrolled_to_and_picked() = canPick("murmur", "MURMUR")

    @Test
    fun the_selected_murmur_starter_can_be_scrolled_to_and_picked() = canPick("murmur-selected", "MURMUR SELECTED")

    private fun canPick(id: String, displayName: String) {
        val picked = mutableListOf<StarterKits.Starter>()
        var dismissals = 0
        show(onPick = { picked += it }, onDismiss = { dismissals++ })

        val murmur = button("PICK $displayName")
        murmur.assertIsNotDisplayed()
        var drags = 0
        while (!rowFitsViewport(murmur) && drags < 24) {
            dragChoicesUp()
            drags++
        }
        murmur.assertIsDisplayed()
        assertTrue("The whole $displayName row must fit inside the scroll viewport", rowFitsViewport(murmur))
        // A real touch also checks that a child pick does not hit the scrim.
        murmur.performTouchInput { down(0, center); up(0) }
        pump()

        compose.runOnIdle {
            assertEquals(1, picked.size)
            assertSame(StarterKits.byId(id), picked.single())
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun tapping_the_scrim_above_the_starter_sheet_dismisses_without_picking() {
        val picked = mutableListOf<StarterKits.Starter>()
        var dismissals = 0
        show(onPick = { picked += it }, onDismiss = { dismissals++ })

        // The panel is anchored at the bottom and shorter than the window, so the scrim's top
        // left corner is bare scrim. The scrim keeps the name CANCEL the old menu gave it.
        button("CANCEL").performTouchInput {
            down(0, Offset(1f, 1f))
            up(0)
        }
        pump()

        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(0, picked.size)
        }
    }

    @Test
    fun a_starter_row_is_a_name_over_its_blurb_and_at_least_the_minimum_touch_target_tall() {
        show(onPick = {}, onDismiss = {})
        val first = StarterShelf.display().first().second.first()
        val row = button("PICK ${first.displayName.uppercase()}")
        row.assertHeightIsAtLeast(Layout.MIN_HIT_TARGET.dp)
        assertTrue("the blurb is on the row", compose.onAllNodesWithText(first.blurb, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
