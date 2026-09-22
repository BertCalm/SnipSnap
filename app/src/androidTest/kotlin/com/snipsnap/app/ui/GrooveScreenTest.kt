package com.snipsnap.app.ui

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.shell.Copy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * GROOVE's program row on a device: the five segments, the line under
 * them, and the one segment that cycles.
 *
 * **Why this screen, and why this part of it.** The cycler shipped in
 * #289 — tap YOURS while YOURS is already live and it steps to your next
 * program — with no behavioural test of any kind. It is invisible to a
 * compiler and to `ConventionTest` alike: both would be perfectly happy
 * with a row that selects the wrong program, counts wrong, or draws a
 * label cut in half. Four bugs in the session that built it lived in
 * `:app`, where nothing but re-reading could reach them, and this is the
 * species `SurfaceScreenTest` was written to catch, on a screen it does
 * not cover.
 *
 * The clock, the finder helpers and the width check are
 * [ComposeScreenTest]'s own, for the reason its KDoc gives: GROOVE runs a
 * `withFrameNanos` loop for its whole life (the voice drain, and the
 * transport when it plays), and with the clock advancing itself the
 * harness counts that loop as work still pending and every test sits in
 * `waitForIdle` until it times out.
 *
 * The program names and sub-lines below are **retyped, not imported**:
 * they are `private` to `GrooveScreen.kt`, and a test that read them from
 * the screen would agree with any rename including a wrong one. Typed
 * here, a rename fails this file loudly, which is what a test of what the
 * screen says is for.
 *
 * **Nothing here scrolls** — see [ComposeScreenTest]'s own KDoc for why,
 * and for the exact way this file found out the hard way: four tests
 * passed, the fifth hung on a `performScrollTo()`, and the job was
 * cancelled at its 45-minute cap with nothing failed, before that one
 * test was removed. Every assertion here lives above GROOVE's scrolling
 * control region: the program row and its sub-line are in the fixed
 * header, which is where the cycler is.
 *
 * The one assertion that needed a scroll — FORK TO YOURS opens the
 * program the cycler is on, the #289 bug — is therefore **not here yet**.
 * The header it would read (`Copy.STEP_EDIT_TITLE` and
 * `Copy.stepEditOf`) exists and is JVM-tested; what is missing is a way
 * to reach that button on a hand-driven clock. A slow drag on the
 * scrolling container (`down`/`moveBy`/`up`, no fling) is the likely
 * route and is untried.
 *
 * What is deliberately not here at all: anything only an ear can judge —
 * the feel poured over a program, swing at 60%, whether a take landed on
 * the beat. Those stay on the human checklist.
 */
@RunWith(AndroidJUnit4::class)
class GrooveScreenTest : ComposeScreenTest() {

    private val toasts = mutableListOf<String>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    /**
     * A kit with a captured program and [userPrograms] of the player's
     * own, on disk where the screen reads them.
     *
     * The base clip is named without a trailing " E": `GrooveEdit.isProgE`
     * is the predicate that tells the captured program from the player's,
     * and a fixture that tripped it would test a kit with no base at all.
     */
    private fun grooveKit(userPrograms: Int): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "groove-test-kit-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        WavWriter.write(File(dir, "kick.wav"), Snip(FloatArray(4410), channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE))
        val base = Mpc3Clip(BASE_NAME, bars = 1, notes = listOf(Mpc3Note(note = 36, timePulses = 0L, velocity = 1f)))
        // One note each, at a different step, so the programs are not
        // merely differently named copies of each other.
        val yours = (0 until userPrograms).map { i ->
            Mpc3Clip(
                GrooveEdit.progEName(BASE_NAME, i),
                bars = 1,
                notes = listOf(Mpc3Note(note = 36, timePulses = GrooveEdit.STEP_PULSES * (i + 1), velocity = 1f)),
            )
        }
        GrooveStore.save(dir, listOf(base) + yours)
        return KitShelf.Entry(
            dir,
            Kit(
                name = "GROOVE TEST",
                pads = listOf(KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK)),
                tempoBpm = 92f,
            ),
        )
    }

    private fun show(entry: KitShelf.Entry) {
        setPhoneContent {
            GrooveScreen(
                entry = entry,
                appScope = appScope,
                onToast = { toasts += it },
                onNavigateKits = {},
            )
        }
        // The kit's grooves are read off disk in a LaunchedEffect, so the
        // row does not exist on the first frame — `loading` draws a bare
        // panel until it lands.
        waitFor("the program row") {
            compose.onAllNodesWithText("CAPTURED", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The line under the row, which names the selected program and — for YOURS — which of yours. */
    private fun assertSubLine(sub: String) =
        compose.onNodeWithText("PROGRAM · $sub", useUnmergedTree = true).assertExists()

    /**
     * #289's measurement, turned into a check.
     *
     * The row holds five segments because six was measured not to fit:
     * CAPTURED needs 48dp of the ~56 a segment gets at the 390dp design
     * frame, and a sixth would leave 47. That measurement is why the
     * cycler exists at all — one segment reaching all eight of your
     * programs instead of a segment each — and until now it lived in a
     * comment. A wrapped label makes its own segment taller than its four
     * neighbours, so this fails on the line count as well as the ellipsis.
     */
    @Test
    fun the_program_row_reads_in_full_at_phone_width() {
        show(grooveKit(userPrograms = 1))
        for (name in PROG_NAMES) assertReadsInFull(name)
    }

    /**
     * The sub-line carries what the segments cannot, so it is the half of
     * this row most likely to run out of width — and the longest thing it
     * ever says is the cycler's own count, which no one had measured.
     */
    @Test
    fun the_line_under_the_row_reads_in_full_for_every_program() {
        show(grooveKit(userPrograms = GrooveEdit.MAX_USER_PROGRAMS))
        for ((i, sub) in PROG_SUBS.withIndex()) {
            if (i > 0) tap(PROG_NAMES[i])
            if (i == YOURS_INDEX) {
                // With more than one of yours the count replaces the sub.
                assertReadsInFull("PROGRAM · " + Copy.yoursOf(1, GrooveEdit.MAX_USER_PROGRAMS))
            } else {
                assertReadsInFull("PROGRAM · $sub")
            }
        }
    }

    /** Selecting a program selects it, and the line under the row says which. */
    @Test
    fun each_program_selects_and_the_line_under_the_row_says_which() {
        show(grooveKit(userPrograms = 1))
        button("CAPTURED").assertIsSelected()
        assertSubLine(PROG_SUBS[0])
        for (i in 1 until PROG_NAMES.size) {
            tap(PROG_NAMES[i])
            button(PROG_NAMES[i]).assertIsSelected()
            button(PROG_NAMES[i - 1]).assertIsNotSelected()
            assertSubLine(PROG_SUBS[i])
        }
    }

    /**
     * YOURS is the one segment that is not always there: the first four
     * are computed from the take and always exist, and the fifth appears
     * only once the player has made a program of their own.
     */
    @Test
    fun yours_is_absent_until_the_kit_holds_one_of_your_own() {
        show(grooveKit(userPrograms = 0))
        button("CAPTURED").assertExists()
        button("SPARSE").assertExists()
        button("YOURS").assertDoesNotExist()
    }

    /**
     * The cycler itself (#289): tapping YOURS when YOURS is already live
     * steps to the next one and wraps, and the count under the row says
     * where you are.
     */
    @Test
    fun tapping_yours_while_it_is_live_steps_to_the_next_and_wraps() {
        show(grooveKit(userPrograms = 3))
        tap("YOURS")
        button("YOURS").assertIsSelected()
        assertSubLine(Copy.yoursOf(1, 3))
        tap("YOURS")
        assertSubLine(Copy.yoursOf(2, 3))
        tap("YOURS")
        assertSubLine(Copy.yoursOf(3, 3))
        // Round the loop.
        tap("YOURS")
        assertSubLine(Copy.yoursOf(1, 3))
        button("YOURS").assertIsSelected()
    }

    /**
     * A player who never makes a second program never reads a count, and
     * tapping YOURS again does nothing — there is nowhere to step to.
     */
    @Test
    fun one_program_of_your_own_gets_no_count_and_nowhere_to_step() {
        show(grooveKit(userPrograms = 1))
        tap("YOURS")
        assertSubLine(PROG_SUBS[YOURS_INDEX])
        tap("YOURS")
        assertSubLine(PROG_SUBS[YOURS_INDEX])
        button("YOURS").assertIsSelected()
    }

    companion object {
        /** The fixture's captured clip, and what every program of yours is named after. */
        const val BASE_NAME = "FIXTURE"

        /** Retyped from `GrooveScreen.kt`'s own `private val` — see this class's KDoc. */
        val PROG_NAMES = listOf("CAPTURED", "SWING", "HALF", "SPARSE", "YOURS")

        val PROG_SUBS = listOf(
            "THE TAKE, AS PLAYED",
            "ON THE GRID, PUSHED LATE",
            "ROOM TO BREATHE · TWICE AS LONG",
            "THE SKELETON",
            "YOUR STEPS · THE ONLY ONE YOU CAN EDIT",
        )

        const val YOURS_INDEX = 4
    }
}
