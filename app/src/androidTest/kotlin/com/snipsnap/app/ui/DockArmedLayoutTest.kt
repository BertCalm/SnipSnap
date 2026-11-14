package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.DockModel
import com.snipsnap.shell.Location
import com.snipsnap.shell.Place
import com.snipsnap.shell.Places
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dock while the mic is armed, at 360 dp, with the transport chip beside it
 * (a kit is open). SNIP and STOP are the way out of a live session, so neither
 * may be squeezed under a hit target's 48 dp by the counter beside them.
 *
 * What the old layout did here: the light, the 173 dp "LISTENING · 00:41
 * BUFFERED" count and SNIP + STOP were one unweighted group. After the
 * transport chip (48) and a gap (8) the group got 288 dp; light 8 + gap 5 +
 * count 173 + gap 5 + SNIP 48 + gap 5 left 44 dp for STOP, whose 48 dp floor
 * is clamped to its constraint: 44 dp, not 48. (If the count is nearer 8.3 dp a
 * character, 216 dp, STOP gets nothing at all.) So the 48 dp
 * assertion below is red on that layout, and green now that SNIP and STOP are
 * measured before the count and the count is what gives way.
 */
@RunWith(AndroidJUnit4::class)
class DockArmedLayoutTest : ComposeScreenTest() {

    @Test
    fun armed_with_the_transport_chip_stop_and_snip_keep_their_48_dp() {
        val dock = DockState(locationNow = { Location(Place.SHELF) })
        dock.capture = DockModel.Capture.Armed(41)
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Dock(
                    dock = dock,
                    busy = null,
                    onArm = {},
                    onPlayToggle = {},
                    onSnip = {},
                    onEject = {},
                    onTakeDoor = {},
                    modifier = Modifier,
                    clock = { compose.mainClock.currentTime },
                )
            }
        }
        // The count is on screen, so the dock really is the armed one with the long label.
        compose.onNodeWithContentDescription("MIC LEVEL METER", useUnmergedTree = true).assertIsDisplayed()
        // Vacuity: the transport chip is present (a stopped beat's chip is named PLAY THE BEAT).
        compose.onNodeWithContentDescription(Places.PLAY_BEAT, useUnmergedTree = true).assertIsDisplayed()
        for (name in listOf(Places.STOP, Places.SNIP)) {
            val chip = compose.onNodeWithContentDescription(name, useUnmergedTree = true)
            chip.assertIsDisplayed()
            val w = chip.getBoundsInRoot().width
            assertTrue("$name is $w wide at 360 dp, under its 48 dp hit target", w >= 48.dp)
        }
    }
}
