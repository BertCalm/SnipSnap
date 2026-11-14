package com.snipsnap.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SheetMenu] and [SheetRow]: the one sheet the short menus open as. The sheet is a Dialog,
 * a window of its own, which the compose rule finds the nodes of like any other root.
 */
@RunWith(AndroidJUnit4::class)
class SheetMenuTest : ComposeScreenTest() {

    private fun show(onDismiss: () -> Unit = {}, canRun: Boolean = true, onRun: () -> Unit = {}, onOther: () -> Unit = {}) {
        setPhoneContent {
            SheetMenu("TOOLS", onDismiss) {
                SheetRow("BACKUP", "Every kit, one file", onRun, enabled = canRun)
                SheetRow("INSPECT FILE", "INSPECT A FILE", onOther, accessibilityLabel = "OPEN INSPECT FILE")
            }
        }
        pump(Motion.SHEET_SLIDE_MS + 100L)
    }

    @Test
    fun a_row_is_named_by_its_name_and_its_description_and_is_a_full_touch_target() {
        show()
        button("BACKUP. Every kit, one file").assertHeightIsAtLeast(Layout.MIN_HIT_TARGET.dp)
        button("OPEN INSPECT FILE").assertHeightIsAtLeast(Layout.MIN_HIT_TARGET.dp)
    }

    @Test
    fun a_tap_on_a_row_runs_it_and_does_not_dismiss() {
        var ran = 0
        var dismissed = 0
        show(onDismiss = { dismissed++ }, onRun = { ran++ })
        button("BACKUP. Every kit, one file").performClick()
        pump()
        compose.runOnIdle {
            assertEquals(1, ran)
            assertEquals(0, dismissed)
        }
    }

    @Test
    fun a_disabled_row_stays_in_the_list_and_does_not_run() {
        var ran = 0
        show(canRun = false, onRun = { ran++ })
        button("BACKUP. Every kit, one file").assertIsNotEnabled()
        button("OPEN INSPECT FILE").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, ran) }
    }

    @Test
    fun the_scrim_dismisses() {
        var dismissed = 0
        show(onDismiss = { dismissed++ })
        // The panel is as tall as its rows, anchored at the bottom: the top left corner is bare scrim.
        button("CANCEL").performTouchInput {
            down(0, Offset(1f, 1f))
            up(0)
        }
        pump()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}
