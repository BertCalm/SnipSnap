package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shelf while a job is in flight. A starter render takes a minute on a
 * slow phone, and the project row has always been closed to taps for it; the
 * kit row was not, so opening kit X while TIDAL rendered landed on TIDAL's
 * placeholders. Both rows keep the same rule: not openable while `busy`.
 *
 * Shown with [LocalReduceMotion] on, as `ComposeScreenTest`'s KDoc asks.
 */
@RunWith(AndroidJUnit4::class)
class KitsScreenBusyTest : ComposeScreenTest() {

    private val opened = mutableListOf<String>()

    private fun show(busy: Boolean) {
        val kit = KitShelf.Entry(File("no-such-dir"), Kit("TIDAL", listOf(KitPad(1, "a.wav"))))
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                KitsScreen(
                    kits = listOf(kit),
                    projects = emptyList(),
                    onOpenProject = {},
                    onNewProject = {},
                    onCatchFirst = {},
                    busy = busy,
                    loading = false,
                    onOpen = { opened += it.kit.name },
                    onFresh = {},
                    onBackup = {},
                )
            }
        }
    }

    @Test
    fun a_kit_row_does_not_open_while_a_job_runs() {
        show(busy = true)

        compose.onNodeWithText("TIDAL").assertIsNotEnabled()
        compose.onNodeWithText("TIDAL").performClick()
        pump()
        assertEquals("a tap on a kit while a job runs opens nothing: $opened", emptyList<String>(), opened)
    }

    @Test
    fun a_kit_row_opens_when_nothing_is_running() {
        show(busy = false)

        compose.onNodeWithText("TIDAL").assertIsEnabled()
        compose.onNodeWithText("TIDAL").performClick()
        pump()
        assertEquals("the positive control: the same tap opens it", listOf("TIDAL"), opened)
    }
}
