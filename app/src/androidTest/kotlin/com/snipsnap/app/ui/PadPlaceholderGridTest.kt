package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PlaceholderPulse
import com.snipsnap.shell.Schemes
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What stands where a starter kit will land while it renders, and the
 * button that started the job.
 *
 * Both are shown with [LocalReduceMotion] on, which is what
 * `ComposeScreenTest`'s KDoc asks of anything that would otherwise run an
 * infinite transition under the frozen clock. It is also the state worth
 * proving: with animation off the grid is still sixteen tiles a person can
 * see are not the stage, and the button still says it is working, with no
 * motion to say so.
 *
 * The grid says nothing itself - the card laid over it names the job, and is
 * tested with the loading-screen suite - so what is asked of it here is its
 * pixels, and that it is silent.
 */
@RunWith(AndroidJUnit4::class)
class PadPlaceholderGridTest : ComposeScreenTest() {

    /** The screen's pixel density, read from inside the composition the grid is drawn in. */
    private var density = 1f

    /** [content] with animation off, so nothing runs against the frozen clock. */
    private fun show(content: @Composable () -> Unit) {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { content() }
        }
    }

    /** The grid on magenta, which is no scheme's colour, so what is not a tile shows as magenta. */
    private fun showOnStage() {
        show {
            density = LocalDensity.current.density
            Box(Modifier.background(Color.Magenta).testTag(GRID)) { PadPlaceholderGrid() }
        }
    }

    private fun drawn(): PixelMap = compose.onNodeWithTag(GRID).captureToImage().toPixelMap()

    private fun progress(info: ProgressBarRangeInfo) =
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, info)

    /** The centre of the tile in [row], [col], in pixels, from the grid's own geometry. */
    private fun PixelMap.centreOf(row: Int, col: Int): Pair<Int, Int> {
        val gap = Layout.PAD_GAP * density
        val tileW = (width - 3 * gap) / 4f
        val tileH = Layout.KIT_PAD_H * density
        return Pair((col * (tileW + gap) + tileW / 2f).toInt(), (row * (tileH + gap) + tileH / 2f).toInt())
    }

    /** How far apart two colours are, as the largest difference in a channel, 0..1. */
    private fun apart(a: Color, b: Color): Float =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    @Test
    fun the_placeholder_grid_is_silent() {
        showOnStage()

        // Decoration: the card over it carries the words and the "in progress".
        // A grid that said anything too would be a second thing to swipe past,
        // and one that spoke before the card shows would break the 200 ms rule.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEquals(0)
    }

    @Test
    fun every_tile_is_visibly_off_the_stage_by_the_still_amount() {
        showOnStage()

        // lerp(raised, ink, 0.09): the fill, a tenth of the way to the ink. The
        // first version breathed the alpha of a fill that was nearly the stage's
        // own colour, and moved about 3 levels of 255.
        val scheme = Schemes.DEFAULT
        val expected = lerp(scheme.raised.tape, scheme.ink.tape, PlaceholderPulse.STILL)
        val grid = drawn()
        for (row in 0 until 4) for (col in 0 until 4) {
            val (x, y) = grid.centreOf(row, col)
            val tile = grid[x, y]
            assertTrue("tile $row,$col is the pulse's still fill: $tile against $expected", apart(tile, expected) < 0.02f)
        }
        assertTrue(
            "and that is a shade a person can see against the raised surface it started from",
            apart(expected, scheme.raised.tape) > 0.03f,
        )
    }

    @Test
    fun the_tiles_are_tiles_with_gaps_between_them() {
        showOnStage()

        val grid = drawn()
        val gap = Layout.PAD_GAP * density
        val (x0, y0) = grid.centreOf(0, 0)
        val tileW = (grid.width - 3 * gap) / 4f
        // Between the first two tiles, and between the first two rows: the stage.
        assertEquals("the gap between columns is the stage", Color.Magenta, grid[(tileW + gap / 2f).toInt(), y0].copy(alpha = 1f))
        assertEquals("the gap between rows is the stage", Color.Magenta, grid[x0, (Layout.KIT_PAD_H * density + gap / 2f).toInt()].copy(alpha = 1f))
    }

    @Test
    fun a_tile_keeps_a_hairline_edge() {
        showOnStage()

        val grid = drawn()
        val (_, y0) = grid.centreOf(0, 0)
        // The tile's left edge, one pixel in: the scheme's gray edge line.
        val edge = grid[1, y0]
        assertTrue("the edge is the scheme's line: $edge", apart(edge, Schemes.DEFAULT.grayEdge.tape) < 0.02f)
    }

    @Test
    fun a_working_button_keeps_its_label_and_shows_a_spinner() {
        show { PrimaryAction("NEW KIT ▸ STARTERS", enabled = false, onClick = {}, working = true) }

        // Still the same words it had before it was pressed, not DUBBING…
        compose.onNodeWithText("NEW KIT ▸ STARTERS").assertExists().assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, Copy.WORKING_BUSY))
        // With a ring beside them that is a node of its own: the spinner.
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun a_working_action_button_keeps_its_label_and_says_it_is_working() {
        show {
            ActionButton(
                "SCULPT ▸ NEW TAPE",
                LocalScheme.current,
                enabled = false,
                working = true,
                onClick = {},
            )
        }

        compose.onNodeWithText("SCULPT ▸ NEW TAPE").assertExists().assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, Copy.WORKING_BUSY))
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun a_button_that_is_not_working_has_no_spinner() {
        show { PrimaryAction("NEW KIT ▸ STARTERS", enabled = true, onClick = {}) }

        compose.onNodeWithText("NEW KIT ▸ STARTERS").assertExists()
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
    }

    private companion object {
        const val GRID = "grid"
    }
}
