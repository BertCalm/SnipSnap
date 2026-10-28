package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Schemes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two loading reels - Export's single spoke while a kit is written, and
 * the strip beside a trip's words - hold still when animations are off.
 *
 * Both are shown with [LocalReduceMotion] on, and the check is a pixel
 * compare across frozen-clock time: the same drawing a moment later is the
 * same drawing. Each reel is a cross, so it looks the same every quarter
 * turn; the waits here are an eighth of a loop and a span that is not a
 * whole number of quarters, so a reel that still turned would have turned
 * to something else.
 */
@RunWith(AndroidJUnit4::class)
class ReelsReduceMotionTest : ComposeScreenTest() {

    private val scheme = Schemes.DEFAULT

    /** [content] on black with animation off, tagged [DRAWN]: what it paints is any pixel that is not black. */
    private fun showOnBlack(content: @Composable () -> Unit) {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.background(Color.Black).testTag(DRAWN)) { content() }
            }
        }
    }

    private fun drawn(): PixelMap = compose.onNodeWithTag(DRAWN).captureToImage().toPixelMap()

    private fun PixelMap.isInk(x: Int, y: Int): Boolean = this[x, y].toArgb() != Color.Black.toArgb()

    @Test
    fun the_dub_reel_stands_still_while_a_kit_is_written() {
        showOnBlack { DubReel(spinning = true, scheme = scheme, modifier = Modifier.size(20.dp)) }

        val before = drawn()
        assertTrue("the reel is drawn", before.buffer.toSet().size > 1)
        pump(Motion.REEL_SPIN_MS / 8L)
        assertArrayEquals("an eighth of a loop on, the reel has not turned", before.buffer, drawn().buffer)
        pump(2_000)
        assertArrayEquals("and two seconds on, still not", before.buffer, drawn().buffer)
    }

    @Test
    fun the_still_dub_reel_is_drawn_upright() {
        // Pins the pose, not the stillness: a cross reads upright every quarter turn, so the test above holds that.
        showOnBlack { DubReel(spinning = true, scheme = scheme, modifier = Modifier.size(20.dp)) }

        // An upright cross: ink up the middle and along it, none out on the
        // diagonal where a reel turned by an eighth would put an arm.
        val reel = drawn()
        val cx = reel.width / 2
        val cy = reel.height / 2
        val quarter = reel.width / 4
        assertTrue("an arm points up", reel.isInk(cx, cy - quarter))
        assertTrue("an arm points right", reel.isInk(cx + quarter, cy))
        assertTrue("nothing on the diagonal", !reel.isInk(cx + quarter / 2, cy - quarter / 2))
    }

    @Test
    fun the_reels_strip_stands_still_beside_its_words() {
        showOnBlack { ReelsStrip("SENDING TO THE LIBRARY", scheme) }

        compose.onNodeWithText("SENDING TO THE LIBRARY").assertExists()
        val before = drawn()
        assertTrue("the strip is drawn", before.buffer.toSet().size > 1)
        pump(Motion.REEL_SPIN_MS / 8L)
        assertArrayEquals("an eighth of a loop on, the reels have not turned", before.buffer, drawn().buffer)
        pump(2_000)
        assertArrayEquals("and two seconds on, still not", before.buffer, drawn().buffer)
    }

    private companion object {
        const val DRAWN = "drawn"
    }
}
