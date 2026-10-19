package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.StarterKits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Phone-height starter browsing, without rendering the selected kit. */
@RunWith(AndroidJUnit4::class)
class StarterMenuTest : ComposeScreenTest() {

    private fun show(onPick: (StarterKits.Starter) -> Unit, onDismiss: () -> Unit) {
        setPhoneContent {
            Box(Modifier.fillMaxWidth().height(480.dp).testTag(HOST)) {
                StarterMenu(onPick = onPick, onDismiss = onDismiss)
            }
        }
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
    fun a_starter_below_the_phone_viewport_can_be_scrolled_to_and_picked() {
        val picked = mutableListOf<StarterKits.Starter>()
        var dismissals = 0
        show(onPick = { picked += it }, onDismiss = { dismissals++ })

        val murmur = button("PICK MURMUR")
        murmur.assertIsNotDisplayed()
        var drags = 0
        while (!rowFitsViewport(murmur) && drags < 24) {
            dragChoicesUp()
            drags++
        }
        murmur.assertIsDisplayed()
        assertTrue("The whole MURMUR row must fit inside the scroll viewport", rowFitsViewport(murmur))
        // A real touch also checks that a child pick does not hit the scrim.
        murmur.performTouchInput { down(0, center); up(0) }
        pump()

        compose.runOnIdle {
            assertEquals(1, picked.size)
            assertSame(StarterKits.byId("murmur"), picked.single())
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun tapping_outside_the_starter_card_dismisses_without_picking() {
        val picked = mutableListOf<StarterKits.Starter>()
        var dismissals = 0
        show(onPick = { picked += it }, onDismiss = { dismissals++ })

        // The card retains its 12dp outer padding; this is the scrim corner.
        compose.onNodeWithTag(HOST).performTouchInput {
            down(0, Offset(1f, 1f))
            up(0)
        }
        pump()

        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(0, picked.size)
        }
    }

    private companion object {
        const val HOST = "starter-menu-host"
    }
}
