package com.snipsnap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.shell.Copy
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What stands where a starter kit will land while it renders, and the
 * button that started the job.
 *
 * Both are shown with [LocalReduceMotion] on, which is what
 * `ComposeScreenTest`'s KDoc asks of anything that would otherwise run an
 * infinite transition under the frozen clock. It is also the state worth
 * proving: with animation off the grid still says what it is waiting for,
 * and the button still says it is working, with no motion to say so.
 */
@RunWith(AndroidJUnit4::class)
class PadPlaceholderGridTest : ComposeScreenTest() {

    /** [content] with animation off, so nothing runs against the frozen clock. */
    private fun show(content: @Composable () -> Unit) {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { content() }
        }
    }

    private fun progress(info: ProgressBarRangeInfo) =
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, info)

    @Test
    fun the_placeholder_grid_names_the_kit_it_is_loading() {
        show { PadPlaceholderGrid("FACTORY") }

        // One node, named for the kit, that a reader of the screen can ask
        // "is it still going?" and be told it is.
        compose.onNodeWithContentDescription("LOADING FACTORY…")
            .assertExists()
            .assert(progress(ProgressBarRangeInfo.Indeterminate))
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun the_placeholder_grid_heads_itself_with_the_line_the_dock_says() {
        show { PadPlaceholderGrid("FACTORY") }

        // The header is the dock's own line for this job, not a second wording of it.
        compose.onNodeWithText(Copy.loadingBusy("FACTORY")).assertExists()
    }

    @Test
    fun a_working_button_keeps_its_label_and_shows_a_spinner() {
        show { PrimaryAction("NEW KIT ▸ STARTERS", enabled = false, onClick = {}, working = true) }

        // Still the same words it had before it was pressed, not DUBBING…
        compose.onNodeWithText("NEW KIT ▸ STARTERS").assertExists().assertIsNotEnabled()
        // With a ring beside them that is a node of its own: the spinner.
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun a_working_action_button_keeps_its_label_and_says_it_is_working() {
        show {
            ActionButton(
                "SCULPT ▸ NEW TAPE",
                LocalScheme.current,
                enabled = false,
                working = true,
                onClick = {},
            )
        }

        compose.onNodeWithText("SCULPT ▸ NEW TAPE").assertExists().assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, Copy.WORKING_BUSY))
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun a_button_that_is_not_working_has_no_spinner() {
        show { PrimaryAction("NEW KIT ▸ STARTERS", enabled = true, onClick = {}) }

        compose.onNodeWithText("NEW KIT ▸ STARTERS").assertExists()
        compose.onAllNodes(progress(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
    }
}
