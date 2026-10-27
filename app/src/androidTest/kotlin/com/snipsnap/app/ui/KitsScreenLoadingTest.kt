package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
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
 * Shown with [LocalReduceMotion] on, as `ComposeScreenTest`'s KDoc asks of
 * anything that holds a bar under the frozen clock: the loading panel does.
 */
@RunWith(AndroidJUnit4::class)
class KitsScreenLoadingTest : ComposeScreenTest() {

    /** The shelf with no kits and no projects, [loading] or not; everything else is a default or a no-op. */
    private fun show(loading: Boolean) {
        setPhoneContent {
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
                )
            }
        }
    }

    @Test
    fun a_loading_library_says_so_and_offers_no_first_run_button() {
        show(loading = true)

        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertIsDisplayed()
        // Neither the empty state nor the first-run way in: a library that is
        // still being read is not an empty one.
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertDoesNotExist()
        button(Places.CATCH_FIRST).assertDoesNotExist()
        button("NEW KIT ▸ STARTERS").assertDoesNotExist()
    }

    @Test
    fun an_empty_library_after_loading_shows_the_empty_state() {
        show(loading = false)

        compose.onNodeWithContentDescription(Copy.LIBRARY_LOADING).assertDoesNotExist()
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertIsDisplayed()
        button(Places.CATCH_FIRST).assertIsDisplayed()
    }
}
