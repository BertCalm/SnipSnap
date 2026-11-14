package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.Copy
import com.snipsnap.shell.StarterKits
import com.snipsnap.shell.StarterShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** FRESH TAPE registration and real phone-height browsing. Picking records a callback; no kit or audio renders. */
@RunWith(AndroidJUnit4::class)
class MeridianSixStarterMenuTest : ComposeScreenTest() {
    private fun rowFitsViewport(row: SemanticsNodeInteraction): Boolean {
        val bounds = row.getUnclippedBoundsInRoot()
        val viewport = compose.onNode(hasScrollAction()).getBoundsInRoot()
        return bounds.left >= viewport.left && bounds.right <= viewport.right &&
            bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
    }

    private fun dragChoicesUp() {
        compose.onNode(hasScrollAction()).performTouchInput {
            down(0, Offset(center.x, height * .80f))
            repeat(12) { moveBy(0, Offset(0f, -height * .05f), delayMillis = 40L) }
            advanceEventTime(200L)
            up(0)
        }
        pump()
    }

    @Test fun sixStarterCanBeBrowsedAndPickedWithoutRenderingOrReplacingOriginalMeridian() {
        val original = requireNotNull(StarterKits.byId("meridian"))
        val materials = requireNotNull(StarterKits.byId("meridian-six"))
        assertEquals("MERIDIAN", original.displayName)
        assertEquals("MERIDIAN SIX", materials.displayName)
        assertFalse(materials.seeded)
        assertNull(materials.key)
        assertTrue(StarterKits.ALL.indexOf(materials) > StarterKits.ALL.indexOf(original))
        val metal = StarterShelf.GROUPS.single { it.heading == Copy.STARTER_HEADING_METAL }
        assertEquals(metal.ids.indexOf("meridian-materials") + 1, metal.ids.indexOf("meridian-six"))

        val picked = mutableListOf<StarterKits.Starter>()
        var dismissals = 0
        setPhoneContent {
            Box(Modifier.fillMaxWidth().height(480.dp)) {
                StarterMenu(onPick = { picked += it }, onDismiss = { dismissals++ })
            }
        }
        val row = button("PICK MERIDIAN SIX")
        var drags = 0
        while (!rowFitsViewport(row) && drags < 40) {
            dragChoicesUp()
            drags++
        }
        row.assertIsDisplayed()
        assertTrue("The complete materials row fits the phone viewport", rowFitsViewport(row))
        compose.onNodeWithText(materials.blurb).assertExists()
        row.performTouchInput { down(0, center); up(0) }
        pump()

        compose.runOnIdle {
            assertEquals(1, picked.size)
            assertSame(materials, picked.single())
            assertEquals(0, dismissals)
            assertSame(original, StarterKits.byId("meridian"))
        }
    }
}
