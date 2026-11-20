package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Places
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The empty LIBRARY while the first LISTEN runs (UAT 2026-11 user states, D-7).
 *
 * It used to read "Nothing recorded yet." beside CATCH YOUR FIRST SOUND, the button just
 * pressed, while only the dock said it was listening. Listening, it names the next step
 * (make the sound, then SNIP) and drops the button that started it; START FROM A KIT stays.
 */
@RunWith(AndroidJUnit4::class)
class KitsScreenListeningTest : ComposeScreenTest() {

    private fun show(listening: Boolean) {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                KitsScreen(
                    kits = emptyList(),
                    projects = emptyList(),
                    listening = listening,
                    onOpenProject = {},
                    onNewProject = {},
                    onCatchFirst = {},
                    busy = false,
                    loading = false,
                    onOpen = {},
                    onFresh = {},
                    onBackup = {},
                    loadingClock = { compose.mainClock.currentTime },
                )
            }
        }
    }

    @Test
    fun the_empty_library_names_the_next_step_while_listening() {
        show(listening = true)

        compose.onNodeWithText(Copy.EMPTY_SHELF_LISTENING).assertExists()
        compose.onNodeWithText(Copy.EMPTY_SHELF).assertDoesNotExist()
        button(Places.CATCH_FIRST).assertDoesNotExist()
        button(Copy.START_FROM_KIT).assertExists()
    }

    /** The positive control: not listening, the first-run panel is as it was. */
    @Test
    fun the_empty_library_is_unchanged_when_not_listening() {
        show(listening = false)

        compose.onNodeWithText(Copy.EMPTY_SHELF).assertExists()
        button(Places.CATCH_FIRST).assertExists()
    }
}
