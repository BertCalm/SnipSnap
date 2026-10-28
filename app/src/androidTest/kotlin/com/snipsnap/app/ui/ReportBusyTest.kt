package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.Busy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A screen's own job reaching the dock: [ReportBusy] hands a job to
 * whatever sink the app provides, and takes it back when the screen goes.
 *
 * The sink here is a list, so the test sees every call it gets, in order,
 * and not just the last one - which is what tells a screen that took its
 * job back from one that simply never reported.
 */
@RunWith(AndroidJUnit4::class)
class ReportBusyTest : ComposeScreenTest() {

    /** What the sink was told, by label; null is "no job". */
    private val told = mutableListOf<String?>()

    /** Whether the screen that reports is on screen. */
    private var shown by mutableStateOf(true)

    /** The job the screen reports while it is shown. */
    private var job by mutableStateOf<Busy?>(null)

    private fun showScreen() {
        setPhoneContent {
            CompositionLocalProvider(LocalBusySink provides { told += it?.label }) {
                if (shown) ReportBusy(job)
            }
        }
    }

    @Test
    fun a_screens_job_reaches_the_sink_and_leaves_with_the_screen() {
        job = Busy("X")
        showScreen()
        assertEquals("the job reached the sink", listOf<String?>("X"), told)

        shown = false
        pump()

        assertEquals("and the screen's leaving took it back", listOf<String?>("X", null), told)
    }

    @Test
    fun a_job_that_moves_on_is_the_sinks_last_word() {
        job = Busy("A")
        showScreen()

        job = Busy("B")
        pump()

        assertEquals("the sink was told the first job", "A", told.first())
        assertEquals("and ends holding the job as it now stands", "B", told.last())
    }
}
