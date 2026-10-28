package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Busy
import com.snipsnap.shell.BusyLatch
import com.snipsnap.shell.Schemes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The job-in-flight drawings: the bar, the spinner, the loading panel,
 * and the glue that holds a bar back for its show delay.
 *
 * Every drawing is shown with [LocalReduceMotion] on, which is what
 * `ComposeScreenTest`'s KDoc asks of anything that would otherwise run an
 * infinite transition under the frozen clock. It is also the state this
 * suite is there to prove: that "animations off" still draws, and still
 * says what it is, rather than drawing nothing.
 */
@RunWith(AndroidJUnit4::class)
class BusyBarTest : ComposeScreenTest() {

    /** [content] with animation off, so nothing runs against the frozen clock. */
    private fun show(content: @Composable () -> Unit) {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { content() }
        }
    }

    /** [content] on black, tagged [DRAWN], so what it paints is any pixel that is not black. */
    private fun showOnBlack(content: @Composable () -> Unit) {
        show { Box(Modifier.background(Color.Black).testTag(DRAWN)) { content() } }
    }

    private fun drawn(): PixelMap = compose.onNodeWithTag(DRAWN).captureToImage().toPixelMap()

    /** The red channel (white drawn on black, so the brightness) a share of the way along, mid-height. */
    private fun PixelMap.brightnessAt(xShare: Float): Float =
        this[(width * xShare).toInt().coerceIn(0, width - 1), height / 2].red

    private fun progress(info: ProgressBarRangeInfo) =
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, info)

    private fun anyProgress() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    /** Any node that says LOADING, by its words or its description. */
    private fun loadingWords() = hasText("LOADING", substring = true) or hasContentDescription("LOADING", substring = true)

    @Test
    fun a_determinate_bar_reports_its_fraction() {
        showOnBlack { BusyBar(Busy("X", 5, 16), Color.White) }

        // 5 of 16, drawn as one node a reader of the screen can ask "how far?".
        compose.onAllNodes(progress(ProgressBarRangeInfo(0.3125f, 0f..1f))).assertCountEquals(1)
        // And drawn as that much of the track: filled at 15 %, and past the
        // fill, at 60 %, the faint track the fill is part of - dim, neither
        // full nor dark.
        val bar = drawn()
        assertTrue("the first third of the bar is filled", bar.brightnessAt(0.15f) > 0.9f)
        assertTrue("the rest of the bar is the track, not the fill", bar.brightnessAt(0.60f) in 0.1f..0.3f)
    }

    @Test
    fun a_counted_bar_at_zero_still_shows_its_track() {
        // 0 of 16 fills nothing, and a bar that drew nothing would be
        // invisible for as long as the first item took: the track is what
        // says "a counted job is under way".
        showOnBlack { BusyBar(Busy("X", 0, 16), Color.White) }

        compose.onAllNodes(progress(ProgressBarRangeInfo(0f, 0f..1f))).assertCountEquals(1)
        val bar = drawn()
        assertTrue("the track's left end is dim", bar.brightnessAt(0.02f) in 0.1f..0.3f)
        assertTrue("the track's right end is dim", bar.brightnessAt(0.98f) in 0.1f..0.3f)
    }

    @Test
    fun a_full_counted_bar_has_no_track_showing() {
        showOnBlack { BusyBar(Busy("X", 16, 16), Color.White) }

        val bar = drawn()
        assertTrue("the whole bar is filled", bar.brightnessAt(0.02f) > 0.9f)
        assertTrue("the whole bar is filled to its far end", bar.brightnessAt(0.98f) > 0.9f)
    }

    @Test
    fun an_indeterminate_bar_says_so() {
        showOnBlack { BusyBar(Busy("X"), Color.White) }

        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        // Under reduced motion it is a faint track the whole width with a
        // solid segment in the middle of it, and it stays exactly that:
        // nothing sweeps, so two seconds on it has not changed.
        val before = drawn()
        assertTrue("the track's left end is dim, not dark or full", before.brightnessAt(0.02f) in 0.1f..0.3f)
        assertTrue("the track's right end is dim too", before.brightnessAt(0.98f) in 0.1f..0.3f)
        pump(2_000)
        assertArrayEquals(before.buffer, drawn().buffer)
    }

    @Test
    fun the_still_bar_has_a_solid_segment_in_the_middle() {
        // The first still form was the whole track dimmed, which on the dock's
        // edge read as a divider and not as work. It is a progress bar when
        // there is something solid on it: 40 % of the width, centred, so from
        // 30 % to 70 % - and a bar that lost the segment would pass every
        // other still assertion.
        showOnBlack { BusyBar(Busy("X"), Color.White) }

        val bar = drawn()
        assertTrue("the middle of the bar is solid", bar.brightnessAt(0.50f) > 0.9f)
        assertTrue("the segment reaches in to 35 %", bar.brightnessAt(0.35f) > 0.9f)
        assertTrue("and out to 65 %", bar.brightnessAt(0.65f) > 0.9f)
        assertTrue("outside it, at 25 %, is track", bar.brightnessAt(0.25f) in 0.1f..0.3f)
        assertTrue("and at 75 %", bar.brightnessAt(0.75f) in 0.1f..0.3f)
    }

    // The sweep's own form, at a phase the test pins. Animation is not on: a
    // transition under the frozen clock would never idle. The phases are the
    // ones that matter: mid-sweep, the very start, and the end, where Compose
    // pins an infinite animation when the system's animator scale is 0.

    private fun sweepAt(phase: Float) {
        showOnBlack { SweepBar(Color.White, Modifier.fillMaxWidth().height(4.dp)) { phase } }
    }

    @Test
    fun a_sweep_mid_loop_is_a_moving_segment_over_nothing() {
        sweepAt(0.25f)

        // At a quarter of the loop the 30 % segment is 2.5 % to 32.5 % of the
        // way along, and there is no track under or beyond it: black.
        val bar = drawn()
        assertTrue("the segment is there at 15 %", bar.brightnessAt(0.15f) > 0.9f)
        assertTrue("and nothing is drawn beyond it, at 60 %", bar.brightnessAt(0.60f) < 0.05f)
    }

    @Test
    fun a_sweep_pinned_at_the_end_draws_the_still_bar_and_not_nothing() {
        // The system skips an infinite animation to its end when the animator
        // scale is 0, which pins the phase at 1: the segment computed from it is
        // off the right edge, zero wide, and the bar used to vanish.
        sweepAt(1f)

        val bar = drawn()
        assertTrue("the middle is solid, as the still bar's is", bar.brightnessAt(0.50f) > 0.9f)
        assertTrue("and the track under it is dim, not dark", bar.brightnessAt(0.10f) in 0.1f..0.3f)
        assertTrue("to its far end", bar.brightnessAt(0.95f) in 0.1f..0.3f)
    }

    @Test
    fun a_sweep_on_its_first_frame_draws_nothing_and_not_a_flash_of_the_still_bar() {
        // Phase 0 is the segment about to enter, as empty as phase 1 is, but it
        // is the start of every loop and not a pinned animation: showing the
        // still bar for that one frame would flash a centred segment each time
        // the bar appears.
        sweepAt(0f)

        val bar = drawn()
        assertTrue("nothing is drawn yet", bar.brightnessAt(0.50f) < 0.05f)
    }

    @Test
    fun a_job_with_no_total_is_indeterminate_even_with_a_count() {
        // A count with nothing to count towards is not a fraction.
        show { BusyBar(Busy("X", done = 3), Color.White) }

        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun the_loading_panel_names_its_job() {
        show { LoadingPanel("LOADING THE LIBRARY…") }

        compose.onNodeWithContentDescription("LOADING THE LIBRARY…").assertExists()
        // And what it draws under the words is a bar with no end in it.
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun the_spinner_draws_without_animation_under_reduced_motion() {
        showOnBlack { Spinner(Color.White) }
        pump()

        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        val before = drawn()
        assertTrue("the ring is drawn", before.buffer.toSet().size > 1)
        // Nothing is running against the clock, so it can be handed two
        // seconds (not a whole number of turns) and come back idle, with the
        // ring where it was.
        pump(2_000)
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        assertArrayEquals(before.buffer, drawn().buffer)
    }

    @Test
    fun the_loading_card_is_one_node_named_for_its_job() {
        showOnBlack { LoadingCard(Busy("LOADING TIDAL…")) }

        // One thing to a reader of the screen: named for the job, in progress
        // with no end in sight. Its spinner and its bar are drawing, not
        // three more things to swipe past.
        compose.onNodeWithContentDescription("LOADING TIDAL…").assertExists()
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        compose.onAllNodes(anyProgress()).assertCountEquals(1)
        // The words are on the card, drawn and not only said: the same
        // matcher the loading-screen tests use for "no text", so those cannot
        // pass for a card that never says anything.
        compose.onNode(hasText("LOADING TIDAL…")).assertExists()
        compose.onNode(loadingWords()).assertExists()
    }

    @Test
    fun the_loading_card_counts_when_the_job_can_be_counted() {
        showOnBlack { LoadingCard(Busy("LOADING TIDAL…", 5, 16)) }

        compose.onAllNodes(progress(ProgressBarRangeInfo(0.3125f, 0f..1f))).assertCountEquals(1)
        compose.onAllNodes(anyProgress()).assertCountEquals(1)
        compose.onNode(hasText("5 OF 16")).assertExists()
    }

    @Test
    fun the_loading_card_says_no_count_for_a_job_with_none() {
        showOnBlack { LoadingCard(Busy("LOADING TIDAL…", done = 3)) }

        compose.onNode(hasText("LOADING TIDAL…")).assertExists()
        compose.onNode(hasText("3 OF", substring = true)).assertDoesNotExist()
    }

    @Test
    fun the_loading_card_draws_its_ring_and_bar_in_the_amber_on_a_lit_panel() {
        showOnBlack { LoadingCard(Busy("LOADING TIDAL…")) }

        // The card is the dark LCD (not the black it is laid on), and the ring
        // and the 6 dp bar are drawn on it in the scheme's amber: a good many
        // pixels of exactly that colour, which a card that drew only its words
        // would not have.
        val amber = Schemes.DEFAULT.amber.tape.toArgb()
        val card = drawn()
        assertTrue("the amber ring and bar are drawn: ${card.buffer.count { it == amber }} pixels", card.buffer.count { it == amber } > 2_000)
        assertTrue("the panel is the LCD and not the black under it", card.buffer.count { it == Color.Black.toArgb() } < card.buffer.size / 2)
    }

    @Test
    fun a_taller_bar_is_taller() {
        // The card's bar is 6 dp where the dock's is 4: a height the caller picks.
        showOnBlack { BusyBar(Busy("X", 16, 16), Color.White, height = 20.dp) }

        compose.onNodeWithTag(DRAWN).assertHeightIsEqualTo(20.dp)
    }

    // rememberBusyVisible: the same latch the JVM suite proves, driven by a
    // clock this test owns (the frozen one, in ms), so "after 200 ms" is
    // exactly 200 ms and not whatever the emulator managed.

    /** Added to the latched clock: time the latch believes passed that the compose clock has not run. */
    private var skew = 0L

    /** Shows a marker while [rememberBusyVisible] says the bar should be up. */
    private fun showLatched(busy: () -> Busy?) {
        show {
            val visible = rememberBusyVisible(busy()) { compose.mainClock.currentTime + skew }
            if (visible) Box(Modifier.size(4.dp).testTag(BAR))
        }
    }

    @Test
    fun a_job_that_ends_inside_the_show_delay_never_draws() {
        var busy by mutableStateOf<Busy?>(null)
        showLatched { busy }
        compose.onNodeWithTag(BAR).assertDoesNotExist()

        busy = Busy("X")
        pump(BusyLatch.SHOW_AFTER_MS - 50)
        compose.onNodeWithTag(BAR).assertDoesNotExist()

        busy = null
        pump(BusyLatch.MIN_VISIBLE_MS * 2)
        compose.onNodeWithTag(BAR).assertDoesNotExist()
    }

    @Test
    fun a_job_that_ends_after_the_delay_but_before_the_bar_was_drawn_never_draws_it() {
        var busy by mutableStateOf<Busy?>(null)
        showLatched { busy }

        busy = Busy("X")
        pump(20)
        compose.onNodeWithTag(BAR).assertDoesNotExist()

        // By the latch's clock the job ran 230 ms, so by its rules a bar is
        // owed; by the compose clock the delay that would draw it has not run.
        // A bar that was never up has no 500 ms minimum to serve, and putting
        // it up now would show a job that is over.
        skew = 230
        busy = null
        pump(50)
        compose.onNodeWithTag(BAR).assertDoesNotExist()
        pump(BusyLatch.MIN_VISIBLE_MS * 2)
        compose.onNodeWithTag(BAR).assertDoesNotExist()
    }

    @Test
    fun a_long_job_shows_after_the_delay_and_a_finished_one_lingers() {
        var busy by mutableStateOf<Busy?>(null)
        showLatched { busy }

        busy = Busy("X")
        pump(BusyLatch.SHOW_AFTER_MS - 50)
        compose.onNodeWithTag(BAR).assertDoesNotExist()
        pump(100)
        compose.onNodeWithTag(BAR).assertExists()

        // Progress updates are new Busy values, not new jobs: it stays up.
        busy = Busy("X", 1, 4)
        pump(50)
        compose.onNodeWithTag(BAR).assertExists()

        // The job ends 100 ms after the bar appeared; the bar outlasts it
        // until MIN_VISIBLE_MS has passed.
        busy = null
        pump(BusyLatch.MIN_VISIBLE_MS / 2)
        compose.onNodeWithTag(BAR).assertExists()
        pump(BusyLatch.MIN_VISIBLE_MS)
        compose.onNodeWithTag(BAR).assertDoesNotExist()
    }

    /** Pumps until the latch's own clock (the frozen one plus [skew]) reads at least [latchTime]. */
    private fun pumpToLatchTime(latchTime: Long) {
        val now = compose.mainClock.currentTime + skew
        if (latchTime > now) pump(latchTime - now)
    }

    @Test
    fun the_minimum_is_counted_from_the_frame_that_first_draws_the_bar() {
        // Cold-start jank: the show delay is up on schedule, and the first frame
        // that can draw the bar comes late. The 500 ms is owed from that frame
        // (spec 7.6: once shown, it stays 500 ms), not from the schedule.
        //
        // The numbers. The job starts on the frame the effect launches on, F.
        // The latch is started on the clock at F, so the bar is scheduled for
        // S = F + 200 on the latch's clock. [skew] jumps the latch's clock 150 ms
        // ahead of the frames right after that, so the delay still wakes on the
        // frozen clock at F + 200 but reads S + 150 there, and the first frame
        // after it - the test clock's next 16 ms boundary, F + 208 - reads S + 158.
        // That frame is when the bar is first drawn, so the bar is due to go at
        // S + 658: the delay for it wakes at F + 708 (S + 658 on the latch's
        // clock, which runs 150 ahead), and the frame that recomposes it away is
        // F + 720, which is S + 670 on the latch's clock. Counted from the
        // schedule the bar would go at S + 500.
        //
        // So on the latch's clock: still there at S + 600 (the old count had
        // dropped it a hundred milliseconds before), and gone by S + 650 plus two
        // frames - two and not one, because the delay's wake and the frame that
        // recomposes it away are separate boundaries.
        var busy by mutableStateOf<Busy?>(null)
        showLatched { busy }

        busy = Busy("X")
        pump(1)
        val scheduled = compose.mainClock.currentTime + BusyLatch.SHOW_AFTER_MS
        compose.onNodeWithTag(BAR).assertDoesNotExist()
        skew = JANK_MS

        // Past the wake and its frame; the job is over a moment after the bar is up.
        pump(BusyLatch.SHOW_AFTER_MS + 40)
        compose.onNodeWithTag(BAR).assertExists("the bar is up once its first frame has come, at ${compose.mainClock.currentTime}")
        busy = null

        pumpToLatchTime(scheduled + 600)
        compose.onNodeWithTag(BAR).assertExists(
            "600 ms after the scheduled show the bar is still in its 500 ms from the first drawn frame " +
                "(frame clock ${compose.mainClock.currentTime}, latch clock ${compose.mainClock.currentTime + skew}, scheduled $scheduled)",
        )
        pumpToLatchTime(scheduled + 650 + 2 * FRAME_MS)
        compose.onNodeWithTag(BAR).assertDoesNotExist()
    }

    @Test
    fun a_job_that_ends_before_the_first_frame_still_gets_its_minimum_from_that_frame() {
        // The job's end lands between the show delay's wake-up and the frame
        // that draws the bar, and the minimum still counts from that frame, not
        // from the schedule it missed.
        //
        // Same jumped clock as above, but the delay is woken without its frame:
        // advancing exactly SHOW_AFTER_MS with the frame rounding off runs the
        // wake-up and stops short of the next frame boundary, where the job is
        // ended. The frames that follow are the late first ones, and the numbers
        // are the first test's, within a frame or two: the anchor is one of
        // those frames, S + 150 plus up to two of them, so the bar goes by
        // S + 650 plus four frames.
        //
        // What this does not separate: ending the job replaces the effect that
        // is waiting for the frame, and the next effect asks for a frame of its
        // own, so that a wait cancelled before it resumed does not lose the
        // mark. Here the test clock resumes the waiting effect before the
        // replacement cancels it, so the first effect's mark is the one used;
        // the second effect's wait is not exercised.
        var busy by mutableStateOf<Busy?>(null)
        showLatched { busy }

        busy = Busy("X")
        pump(1)
        val scheduled = compose.mainClock.currentTime + BusyLatch.SHOW_AFTER_MS
        skew = JANK_MS

        compose.mainClock.advanceTimeBy(BusyLatch.SHOW_AFTER_MS, ignoreFrameDuration = true)
        busy = null
        pump(2 * FRAME_MS)
        compose.onNodeWithTag(BAR).assertExists("the bar is up from its first frame, at ${compose.mainClock.currentTime}")

        pumpToLatchTime(scheduled + 600)
        compose.onNodeWithTag(BAR).assertExists(
            "600 ms after the scheduled show the bar is still in its 500 ms from the first drawn frame " +
                "(frame clock ${compose.mainClock.currentTime}, latch clock ${compose.mainClock.currentTime + skew}, scheduled $scheduled)",
        )
        pumpToLatchTime(scheduled + 650 + 4 * FRAME_MS)
        compose.onNodeWithTag(BAR).assertDoesNotExist()
    }

    private companion object {
        const val DRAWN = "drawn"
        const val BAR = "latched-bar"

        /** One frame of the compose test clock, which `advanceTimeBy` rounds up to a whole number of. */
        const val FRAME_MS = 16L

        /** How far the latch's clock is jumped ahead of the frames to model a first frame that comes late. */
        const val JANK_MS = 150L
    }
}
