package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import com.snipsnap.shell.BusyLatch
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PlaceholderPulse
import com.snipsnap.shell.Schemes
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * The button's ring keeps the rule every loading drawing keeps: nothing for
 * the first [BusyLatch.SHOW_AFTER_MS], and once it has shown, at least
 * [BusyLatch.MIN_VISIBLE_MS]. Its latch sleeps on a coroutine `delay`, which
 * runs on the compose test clock's virtual time, so the buttons are handed
 * that clock ([testClock]) and "after 200 ms" is 200 ms of it.
 */
@RunWith(AndroidJUnit4::class)
class PadPlaceholderGridTest : ComposeScreenTest() {

    /** The screen's pixel density, read from inside the composition the grid is drawn in. */
    private var density = 1f

    /**
     * [content] with animation off, so nothing runs against the frozen clock.
     * [settleMillis] is how much of that clock the first composition is given
     * (see [setPhoneContent]): a test of a button's ring either gives it enough
     * to be past the show delay or none, and asks from there.
     */
    private fun show(settleMillis: Long = 300, content: @Composable () -> Unit) {
        setPhoneContent(settleMillis) {
            CompositionLocalProvider(LocalReduceMotion provides true) { content() }
        }
    }

    /** The compose test clock, in ms: what a button's ring latch reads in these tests. */
    private val testClock: () -> Long = { compose.mainClock.currentTime }

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
        // The ring is owed after the show delay, not on the first frame: the
        // first composition is given the delay, and a frame or two to draw in.
        show(settleMillis = RING_DUE_MS) {
            PrimaryAction("NEW KIT ▸ STARTERS", enabled = false, onClick = {}, working = true, ringClock = testClock)
        }

        // Still the same words it had before it was pressed, not DUBBING…
        compose.onNodeWithText("NEW KIT ▸ STARTERS").assertExists().assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, Copy.WORKING_BUSY))
        // With a ring beside them that is a node of its own: the spinner.
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun a_working_action_button_keeps_its_label_and_says_it_is_working() {
        show(settleMillis = RING_DUE_MS) {
            ActionButton(
                "SCULPT ▸ NEW TAPE",
                LocalScheme.current,
                enabled = false,
                working = true,
                ringClock = testClock,
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

    // The ring on the control that started a job obeys the 200 ms / 500 ms
    // rule. Both buttons carry it, so each scenario runs on both.

    private fun now() = compose.mainClock.currentTime

    /** Pumps whole frames until the test clock reads at least [time]. */
    private fun pumpTo(time: Long) {
        if (time > now()) pump(time - now())
    }

    private val actionButton: @Composable (Boolean) -> Unit = { working ->
        ActionButton(RING_LABEL, LocalScheme.current, enabled = !working, working = working, ringClock = testClock, onClick = {})
    }

    private val primaryAction: @Composable (Boolean) -> Unit = { working ->
        PrimaryAction(RING_LABEL, enabled = !working, working = working, ringClock = testClock, onClick = {})
    }

    /**
     * Whether the button's ring is up. Both of its signals are read and must
     * agree: the spinner is a progress node, and the node says
     * [Copy.WORKING_BUSY] as its state; one without the other is a button
     * half-way to working. The label is there either way.
     */
    private fun ringIsUp(): Boolean {
        compose.onNodeWithText(RING_LABEL).assertExists("the label stays, ring or not, at ${now()} ms")
        val spinners = compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().size
        val said = compose.onNodeWithText(RING_LABEL).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.StateDescription) == Copy.WORKING_BUSY
        assertEquals("the spinner and the working state come up together at ${now()} ms ($spinners spinners)", spinners == 1, said)
        return said
    }

    private fun aJobThatEndsInsideTheDelayDrawsNoRing(button: @Composable (Boolean) -> Unit) {
        val working = mutableStateOf(false)
        show(settleMillis = 0) { button(working.value) }
        assertFalse("no ring before there is a job", ringIsUp())

        working.value = true
        pump(FRAME_MS)
        val started = now()
        compose.onNodeWithText(RING_LABEL).assertIsNotEnabled()
        assertFalse("the first frame of a job draws no ring", ringIsUp())
        pumpTo(started + 150)
        assertFalse("and 150 ms in there is still none", ringIsUp())

        working.value = false
        // Past where the delay would have fired, and well past: a ring that
        // flashed late would be caught at one of these.
        for (after in listOf(250L, 400L, 800L)) {
            pumpTo(started + after)
            assertFalse("a job that ended at 150 ms never draws one, $after ms after it began", ringIsUp())
        }
    }

    private fun aJobHeldPastTheDelayKeepsItsRingForTheMinimum(button: @Composable (Boolean) -> Unit) {
        val working = mutableStateOf(false)
        show(settleMillis = 0) { button(working.value) }

        working.value = true
        pump(FRAME_MS)
        val started = now()
        // Frame by frame, to see the moment it comes up: the minimum is
        // counted from the first frame that draws it, not from the job.
        var up = -1L
        while (now() < started + 300) {
            pump(FRAME_MS)
            if (up < 0 && ringIsUp()) up = now()
        }
        assertTrue("a job of 300 ms has its ring up by then (started $started)", up >= 0)
        assertTrue(
            "and not before the show delay: up at $up, started $started",
            up - started >= BusyLatch.SHOW_AFTER_MS - FRAME_MS,
        )

        working.value = false
        pump(FRAME_MS)
        assertTrue("a frame after the job ended, the ring is still up", ringIsUp())
        // `up` is read a frame at most after the frame that drew the ring, so
        // 450 is inside the 500 by a frame and more.
        pumpTo(up + 450)
        assertTrue("450 ms after it came up the ring is still up: ${BusyLatch.MIN_VISIBLE_MS} ms is owed", ringIsUp())
        pumpTo(up + BusyLatch.MIN_VISIBLE_MS + 4 * FRAME_MS)
        assertFalse("and once the minimum is served it goes", ringIsUp())
    }

    @Test
    fun an_action_button_whose_job_ends_inside_200_ms_never_draws_its_ring() =
        aJobThatEndsInsideTheDelayDrawsNoRing(actionButton)

    @Test
    fun a_primary_action_whose_job_ends_inside_200_ms_never_draws_its_ring() =
        aJobThatEndsInsideTheDelayDrawsNoRing(primaryAction)

    @Test
    fun an_action_button_held_300_ms_keeps_its_ring_for_the_500_ms_minimum() =
        aJobHeldPastTheDelayKeepsItsRingForTheMinimum(actionButton)

    @Test
    fun a_primary_action_held_300_ms_keeps_its_ring_for_the_500_ms_minimum() =
        aJobHeldPastTheDelayKeepsItsRingForTheMinimum(primaryAction)

    private companion object {
        const val GRID = "grid"
        const val RING_LABEL = "SCULPT ▸ NEW TAPE"

        /** One frame of the compose test clock, which `advanceTimeBy` rounds up to a whole number of. */
        const val FRAME_MS = 16L

        /** A first composition's worth of the test clock that is past the ring's show delay and a frame or two to draw it in. */
        const val RING_DUE_MS = BusyLatch.SHOW_AFTER_MS + 2 * FRAME_MS
    }
}
