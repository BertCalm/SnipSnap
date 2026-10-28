package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.BusyLatch
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Places
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shelf on a cold start: until the kit list and the project list have
 * both come back, an empty pair of lists means "not read yet", not "nothing
 * here". The shelf used to draw the empty state in that gap, so a populated
 * library opened on NOTHING TAPED YET and a button that tells a new user to
 * catch their first sound, then flipped to the real list a moment later.
 *
 * It says it is loading by the 200 ms / 500 ms rule every loading screen
 * keeps: an empty LCD panel and no words for the first 200 ms, so a library
 * that reads at once never flashes "LOADING THE LIBRARY…"; the panel with its
 * words once the read has run that long; and, once the words have shown, at
 * least 500 ms of them even if the read ends a moment later. The footer's way
 * in stays away for the whole of it, lingering included, because which button
 * is the primary one depends on what the lists hold.
 *
 * Shown with [LocalReduceMotion] on, as `ComposeScreenTest`'s KDoc asks of
 * anything that holds a bar under the frozen clock: the loading panel does.
 * The shelf is given the compose test clock, because its latch sleeps on
 * `delay`, which runs on that clock's virtual time, and `SystemClock` would
 * not move with it.
 */
@RunWith(AndroidJUnit4::class)
class KitsScreenLoadingTest : ComposeScreenTest() {

    private var loading by mutableStateOf(true)

    /**
     * The shelf with no kits and no projects, [loading] or not; everything
     * else is a default or a no-op. [settleMillis] is how much of the frozen
     * clock the first composition gets (see `setPhoneContent`): the tests of
     * the first 200 ms take almost none of it.
     */
    private fun show(isLoading: Boolean, settleMillis: Long = 300) {
        loading = isLoading
        setPhoneContent(settleMillis) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                KitsScreen(
                    kits = emptyList(),
                    projects = emptyList(),
                    onOpenProject = {},
                    onNewProject = {},
                    onCatchFirst = {},
                    busy = false,
                    loading = loading,
                    onOpen = {},
                    onFresh = {},
                    onBackup = {},
                    loadingClock = { compose.mainClock.currentTime },
                )
            }
        }
    }

    @Test
    fun a_loading_library_says_so_and_offers_no_first_run_button() {
        show(isLoading = true)
        // Past the show delay, with room to spare: the settle is 300 ms and
        // the words are due at 200.
        pump(BusyLatch.SHOW_AFTER_MS)

        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertIsDisplayed()
        // Neither the empty state nor the first-run way in: a library that is
        // still being read is not an empty one.
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertDoesNotExist()
        button(Places.CATCH_FIRST).assertDoesNotExist()
        button("NEW KIT ▸ STARTERS").assertDoesNotExist()
    }

    @Test
    fun an_empty_library_after_loading_shows_the_empty_state() {
        show(isLoading = false)

        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertDoesNotExist()
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertIsDisplayed()
        button(Places.CATCH_FIRST).assertIsDisplayed()
        // The cold shelf draws this row too: the positive control for the
        // literal the loading test asserts absent, so a rename of the button
        // cannot leave that assertion passing for nothing.
        button("NEW KIT ▸ STARTERS").assertIsDisplayed()
    }

    @Test
    fun the_first_200_ms_are_an_empty_panel_with_no_words() {
        show(isLoading = true, settleMillis = 0)

        pump(150)
        // Nothing says it is loading, and nothing says it is empty either, and
        // no way in is offered: a quiet LCD panel is all there is.
        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertDoesNotExist()
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertDoesNotExist()
        button(Places.CATCH_FIRST).assertDoesNotExist()
        button("NEW KIT ▸ STARTERS").assertDoesNotExist()

        // It was only the delay: 100 ms later the words are up, so the
        // absence above was a real one.
        pump(100)
        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertIsDisplayed()
    }

    @Test
    fun a_library_that_loads_inside_the_delay_never_says_it_is_loading() {
        show(isLoading = true, settleMillis = 0)
        pump(100)
        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertDoesNotExist()

        // Done at 150 ms: straight to the content, which is the empty state
        // for this library, and the words never drew.
        loading = false
        pump(50)
        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertDoesNotExist()
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertIsDisplayed()
        button(Places.CATCH_FIRST).assertIsDisplayed()
        // And it stays that way past the whole of the minimum linger.
        pump(BusyLatch.MIN_VISIBLE_MS * 2)
        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertDoesNotExist()
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertIsDisplayed()
    }

    @Test
    fun a_library_that_loads_after_the_words_showed_keeps_them_for_500_ms() {
        show(isLoading = true, settleMillis = 0)
        pump(300)
        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertIsDisplayed()

        // The read ends 300 ms in, 100 ms after the words appeared: they stay
        // until 500 ms after they appeared, with the footer's way in still
        // away, and the content is not drawn under them.
        loading = false
        pump(350)
        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertIsDisplayed()
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertDoesNotExist()
        button(Places.CATCH_FIRST).assertDoesNotExist()
        button("NEW KIT ▸ STARTERS").assertDoesNotExist()

        pump(110)
        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertDoesNotExist()
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertIsDisplayed()
        button(Places.CATCH_FIRST).assertIsDisplayed()
        button("NEW KIT ▸ STARTERS").assertIsDisplayed()
    }
}
