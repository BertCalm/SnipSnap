package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.Busy
import com.snipsnap.shell.BusyLatch
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
        // Under reduced motion it is the whole track, dimmed, and it stays
        // exactly that: nothing sweeps, so two seconds on it has not changed.
        val before = drawn()
        assertTrue("the track's left end is dimmed, not dark or full", before.brightnessAt(0.02f) in 0.3f..0.5f)
        assertTrue("the track's right end is dimmed too", before.brightnessAt(0.98f) in 0.3f..0.5f)
        pump(2_000)
        assertArrayEquals(before.buffer, drawn().buffer)
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

    // rememberBusyVisible: the same latch the JVM suite proves, driven by a
    // clock this test owns (the frozen one, in ms), so "after 200 ms" is
    // exactly 200 ms and not whatever the emulator managed.

    /** Shows a marker while [rememberBusyVisible] says the bar should be up. */
    private fun showLatched(busy: () -> Busy?) {
        show {
            val visible = rememberBusyVisible(busy()) { compose.mainClock.currentTime }
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

    private companion object {
        const val DRAWN = "drawn"
        const val BAR = "latched-bar"
    }
}
