package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.Busy
import com.snipsnap.shell.BusyLatch
import com.snipsnap.shell.Location
import com.snipsnap.shell.Place
import kotlin.concurrent.thread
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app-owned job board as the dock draws it: a job begun on [AppJobs] and
 * passed to the dock as its `busy` shows by the same 200 ms rule as any other
 * job, stays its minimum once shown, and is gone once it has ended.
 *
 * Built like `DockBusyTest`, for the same reasons: the dock starts with no
 * job, because `setPhoneContent` pumps 300 ms of the frozen clock before it
 * returns and a job that existed from the first frame would be past its show
 * delay before the test could look; the dock is given the compose test clock;
 * and animation is off, as the frozen clock requires.
 */
@RunWith(AndroidJUnit4::class)
class AppJobsTest : ComposeScreenTest() {

    private val jobs = AppJobs()

    private val dock = DockState(locationNow = { Location(Place.SHELF) })

    private fun showDock() {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Dock(
                    dock = dock,
                    busy = jobs.current,
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

    @Test
    fun a_job_on_the_board_shows_its_label_after_200_ms() {
        showDock()

        jobs.begin(Busy("TREATING PAD…"))
        pump(150)
        compose.onNodeWithText("TREATING PAD…").assertDoesNotExist()

        pump(100)
        compose.onNodeWithText("TREATING PAD…").assertIsDisplayed()
    }

    @Test
    fun once_ended_the_label_is_gone_after_the_linger() {
        showDock()
        val job = jobs.begin(Busy("TREATING PAD…"))
        pump(250)
        compose.onNodeWithText("TREATING PAD…").assertIsDisplayed()

        job.end()
        pump(100)
        compose.onNodeWithText("TREATING PAD…").assertIsDisplayed()

        pump(BusyLatch.MIN_VISIBLE_MS)
        compose.onNodeWithText("TREATING PAD…").assertDoesNotExist()
    }

    @Test
    fun ending_a_job_twice_is_harmless() {
        showDock()
        val first = jobs.begin(Busy("FIRST JOB"))
        val second = jobs.begin(Busy("SECOND JOB"))
        pump(250)
        compose.onNodeWithText("SECOND JOB").assertIsDisplayed()

        second.end()
        second.end()
        pump(50)
        // The second end took nothing else off the board: the first job is still the dock's.
        compose.onNodeWithText("FIRST JOB").assertIsDisplayed()
        compose.onNodeWithText("SECOND JOB").assertDoesNotExist()

        first.end()
        first.end()
        pump(BusyLatch.MIN_VISIBLE_MS + 100)
        compose.onNodeWithText("FIRST JOB").assertDoesNotExist()
    }

    @Test
    fun an_update_changes_what_the_dock_says() {
        showDock()
        val job = jobs.begin(Busy("TREATING PAD…", done = 1, total = 4))
        pump(250)
        compose.onNodeWithText("1 OF 4").assertIsDisplayed()

        job.update(Busy("TREATING PAD…", done = 3, total = 4))
        pump(50)
        compose.onNodeWithText("3 OF 4").assertIsDisplayed()
        compose.onNodeWithText("1 OF 4").assertDoesNotExist()
    }

    @Test
    fun a_job_begun_and_ended_off_the_main_thread_reaches_the_dock() {
        showDock()

        var job: JobHandle? = null
        thread { job = jobs.begin(Busy("OFF THE MAIN THREAD")) }.join()
        pump(250)
        compose.onNodeWithText("OFF THE MAIN THREAD").assertIsDisplayed()

        thread { job!!.end() }.join()
        pump(BusyLatch.MIN_VISIBLE_MS + 100)
        compose.onNodeWithText("OFF THE MAIN THREAD").assertDoesNotExist()
    }
}
