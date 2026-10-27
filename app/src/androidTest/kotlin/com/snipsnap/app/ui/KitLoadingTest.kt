package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.Copy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The page a starter kit lands on, as it obeys the 200 ms / 500 ms rule: a
 * job's loading screen does not exist for its first 200 ms and, once it has
 * shown, stays for at least 500 ms, so what draws is readable and what does
 * not draw never flashed.
 *
 * The device evidence that made this a rule for the destination too and not
 * only for the dock: a frame of `pr2a-emptygrid-recording.mp4` showed
 * "LOADING EMPTY GRID…" over sixteen tiles for about 89 ms before the kit
 * landed.
 *
 * Every test starts with no name pending and hands it one afterwards, for the
 * reason `DockBusyTest` gives: `setPhoneContent` pumps 300 ms of the frozen
 * clock before it returns, and a name pending from the first frame would be
 * past the delay before the test could look. The page is given the compose
 * test clock, because its latch sleeps on `delay`, which runs on that clock's
 * virtual time, and `SystemClock` would not move with it. Animation is off
 * ([LocalReduceMotion]), as the frozen clock requires of anything that would
 * loop.
 */
@RunWith(AndroidJUnit4::class)
class KitLoadingTest : ComposeScreenTest() {

    private var pending by mutableStateOf<String?>(null)

    private fun showPage() {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.fillMaxWidth().testTag(PAGE)) {
                    KitLoading(pending, { compose.mainClock.currentTime }) {
                        Box(Modifier.size(40.dp).testTag(LANDED))
                    }
                }
            }
        }
    }

    /** Any node that says LOADING, by its words or its description. */
    private fun loadingWords() = hasText("LOADING", substring = true) or hasContentDescription("LOADING", substring = true)

    private fun noneInProgress() =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)

    /** The page, drawn: something is on screen where the kit will be, and it is not nothing. */
    private fun assertSkeletonDrawn() {
        val page = compose.onNodeWithTag(PAGE).captureToImage()
        assertTrue("sixteen tiles are drawn on the page: ${page.height} px tall", page.height > 300)
    }

    @Test
    fun nothing_with_words_shows_in_the_first_200_ms() {
        showPage()

        pending = "TIDAL"
        pump(150)

        // The skeleton is there, drawn and silent, and nothing says LOADING.
        compose.onNodeWithTag(LANDED).assertDoesNotExist()
        assertSkeletonDrawn()
        compose.onNode(loadingWords()).assertDoesNotExist()
        noneInProgress()

        // It was only the delay: 100 ms later the card is up, and the same
        // matcher finds it, so the absence above was a real one.
        pump(100)
        compose.onNode(loadingWords()).assertExists()
        compose.onNodeWithContentDescription(Copy.loadingBusy("TIDAL")).assertExists()
        compose.onNodeWithTag(LANDED).assertDoesNotExist()
    }

    @Test
    fun the_card_names_the_starter_and_is_in_progress() {
        showPage()

        pending = "TIDAL"
        pump(250)

        compose.onNode(hasText(Copy.loadingBusy("TIDAL"))).assertExists()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(1)
    }

    @Test
    fun a_kit_that_lands_while_the_card_shows_waits_out_the_500_ms() {
        showPage()

        pending = "TIDAL"
        pump(300)
        compose.onNodeWithContentDescription(Copy.loadingBusy("TIDAL")).assertExists()

        // The kit lands 300 ms in, 100 ms after the card appeared: the card
        // (and the name it had) stays until 500 ms after it appeared, and the
        // kit does not replace it before.
        pending = null
        pump(350)
        compose.onNodeWithContentDescription(Copy.loadingBusy("TIDAL")).assertExists()
        compose.onNodeWithTag(LANDED).assertDoesNotExist()

        pump(110)
        compose.onNode(loadingWords()).assertDoesNotExist()
        compose.onNodeWithTag(LANDED).assertExists()
        noneInProgress()
    }

    @Test
    fun a_kit_that_lands_inside_the_delay_goes_straight_to_the_page() {
        showPage()

        pending = "TIDAL"
        pump(150)
        compose.onNodeWithTag(LANDED).assertDoesNotExist()

        // Cleared at 150 ms: the landed content now, and the card never.
        pending = null
        pump(50)
        compose.onNodeWithTag(LANDED).assertExists()
        compose.onNode(loadingWords()).assertDoesNotExist()
        pump(300)
        compose.onNodeWithTag(LANDED).assertExists()
        compose.onNode(loadingWords()).assertDoesNotExist()
        pump(1_000)
        compose.onNodeWithTag(LANDED).assertExists()
        compose.onNode(loadingWords()).assertDoesNotExist()
    }

    @Test
    fun a_second_starter_gets_a_delay_of_its_own() {
        showPage()
        pending = "TIDAL"
        pump(300)
        pending = null
        pump(1_000)
        compose.onNodeWithTag(LANDED).assertExists()
        compose.onNode(loadingWords()).assertDoesNotExist()

        pending = "FACTORY"
        pump(150)
        compose.onNodeWithTag(LANDED).assertDoesNotExist()
        compose.onNode(loadingWords()).assertDoesNotExist()
        pump(100)
        compose.onNodeWithContentDescription(Copy.loadingBusy("FACTORY")).assertExists()
    }

    @Test
    fun with_nothing_pending_it_is_the_landed_content() {
        showPage()

        compose.onNodeWithTag(LANDED).assertExists()
        compose.onNode(loadingWords()).assertDoesNotExist()
    }

    private companion object {
        const val PAGE = "page"
        const val LANDED = "landed"
    }
}
