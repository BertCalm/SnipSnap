package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.Busy
import com.snipsnap.shell.BusyLatch
import com.snipsnap.shell.Door
import com.snipsnap.shell.Location
import com.snipsnap.shell.Place
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dock as it carries a job in flight: the line, the bar over it, the
 * count beside it, and Cancel where the job can stop.
 *
 * Every test starts the dock with no job and hands it one afterwards:
 * `setPhoneContent` pumps 300 ms of the frozen clock before it returns, and
 * a job that existed from the first frame would be past its 200 ms show
 * delay before the test could look. The dock is given the compose test
 * clock for its show delay, for the reason `BusyBarTest` gives for
 * `rememberBusyVisible`: the effect's `delay` runs on that clock's virtual
 * time, and `SystemClock` would not move with it.
 *
 * Animation is off ([LocalReduceMotion]), as the frozen clock requires of
 * anything that would loop, so the bar a job without a total gets is the
 * still one, and still says "in progress".
 */
@RunWith(AndroidJUnit4::class)
class DockBusyTest : ComposeScreenTest() {

    private var busy by mutableStateOf<Busy?>(null)

    /** Where the dock "is", and so what a door must lead away from to be offered at all. */
    private val dock = DockState(locationNow = { Location(Place.SHELF) })

    private fun showDock() {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Dock(
                    dock = dock,
                    busy = busy,
                    onArm = {},
                    onPlayToggle = null,
                    onSnip = {},
                    onEject = {},
                    onTakeDoor = {},
                    modifier = Modifier,
                    clock = { compose.mainClock.currentTime },
                )
            }
        }
    }

    private fun anyProgress() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun progress(info: ProgressBarRangeInfo) =
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, info)

    @Test
    fun nothing_shows_in_the_first_200_ms() {
        showDock()

        busy = Busy("LOADING FACTORY…")
        pump(150)
        compose.onNodeWithText("LOADING FACTORY…").assertDoesNotExist()
        compose.onAllNodes(anyProgress()).assertCountEquals(0)

        // It was only the delay: the same job, 100 ms later, is drawn.
        pump(100)
        compose.onNodeWithText("LOADING FACTORY…").assertIsDisplayed()
        compose.onAllNodes(anyProgress()).assertCountEquals(1)
    }

    @Test
    fun the_bar_label_and_count_show_after_200_ms() {
        showDock()

        busy = Busy("LOADING FACTORY…", 5, 16)
        pump(250)

        compose.onNodeWithText("LOADING FACTORY…").assertIsDisplayed()
        compose.onNodeWithText("5 OF 16").assertIsDisplayed()
        compose.onAllNodes(progress(ProgressBarRangeInfo(0.3125f, 0f..1f))).assertCountEquals(1)
    }

    @Test
    fun the_count_follows_the_job() {
        showDock()
        busy = Busy("LOADING FACTORY…", 5, 16)
        pump(250)

        // A progress update is a new value for the same job.
        busy = Busy("LOADING FACTORY…", 6, 16)
        pump(50)

        compose.onNodeWithText("6 OF 16").assertIsDisplayed()
        compose.onNodeWithText("5 OF 16").assertDoesNotExist()
        compose.onAllNodes(progress(ProgressBarRangeInfo(0.375f, 0f..1f))).assertCountEquals(1)
    }

    @Test
    fun a_job_that_cannot_be_counted_shows_no_count() {
        showDock()

        busy = Busy("LOADING FACTORY…", done = 3)
        pump(250)

        compose.onNodeWithText("LOADING FACTORY…").assertIsDisplayed()
        compose.onNodeWithText("3 OF", substring = true).assertDoesNotExist()
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun a_long_label_keeps_its_count() {
        showDock()

        busy = Busy("X".repeat(60), done = 3, total = 8)
        pump(250)

        // The label gives way (it is the line that has the room to spare);
        // the count is whole, on one line.
        compose.onNodeWithText("X".repeat(60)).assertIsDisplayed()
        compose.onNodeWithText("3 OF 8").assertIsDisplayed()
        assertReadsInFull("3 OF 8")
    }

    @Test
    fun cancel_appears_when_the_job_can_stop() {
        var cancelled = false
        showDock()

        busy = Busy("PACKING…", cancel = { cancelled = true })
        pump(250)

        compose.onNodeWithText("PACKING…").assertIsDisplayed()
        assertFalse("it is offered, not taken", cancelled)
        tap("CANCEL")
        assertTrue("the chip runs the job's cancel", cancelled)
    }

    @Test
    fun cancel_is_absent_when_the_job_cannot_stop() {
        showDock()

        busy = Busy("PACKING…")
        pump(250)

        compose.onNodeWithText("PACKING…").assertIsDisplayed()
        compose.onNodeWithText("CANCEL").assertDoesNotExist()
        compose.onNodeWithContentDescription("CANCEL").assertDoesNotExist()
    }

    @Test
    fun the_door_hides_while_busy() {
        dock.offer("PACKED", Door("OPEN THE KIT ▸", Location(Place.MAKE)))
        showDock()
        compose.onNodeWithText("OPEN THE KIT ▸").assertIsDisplayed()

        busy = Busy("PACKING…")
        pump(250)

        compose.onNodeWithText("PACKING…").assertIsDisplayed()
        compose.onNodeWithText("OPEN THE KIT ▸").assertDoesNotExist()

        // And it is the job that hid it: with the job over and its bar gone,
        // the standing door is back.
        busy = null
        pump(BusyLatch.MIN_VISIBLE_MS + 100)
        compose.onNodeWithText("OPEN THE KIT ▸").assertIsDisplayed()
    }

    @Test
    fun the_door_stays_hidden_in_the_delay_before_the_bar() {
        dock.offer("PACKED", Door("OPEN THE KIT ▸", Location(Place.MAKE)))
        showDock()

        // Today's rule, unchanged: a job in flight owns the dock, drawn yet or not.
        busy = Busy("PACKING…")
        pump(100)

        compose.onNodeWithText("PACKING…").assertDoesNotExist()
        compose.onNodeWithText("OPEN THE KIT ▸").assertDoesNotExist()
    }

    @Test
    fun the_last_label_stays_up_while_the_bar_lingers() {
        var cancelled = false
        showDock()
        busy = Busy("PACKING…", 1, 2, cancel = { cancelled = true })
        pump(250)
        compose.onNodeWithText("PACKING…").assertIsDisplayed()

        // The job ends, and the bar stays its minimum: the words with it,
        // not a blank - and not a Cancel for a job that is over.
        busy = null
        pump(100)
        compose.onNodeWithText("PACKING…").assertIsDisplayed()
        compose.onAllNodes(anyProgress()).assertCountEquals(1)
        compose.onNodeWithContentDescription("CANCEL").assertDoesNotExist()
        assertFalse(cancelled)

        pump(BusyLatch.MIN_VISIBLE_MS)
        compose.onNodeWithText("PACKING…").assertDoesNotExist()
        compose.onAllNodes(anyProgress()).assertCountEquals(0)
    }
}
