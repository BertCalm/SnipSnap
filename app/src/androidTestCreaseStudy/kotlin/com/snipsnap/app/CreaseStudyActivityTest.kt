package com.snipsnap.app

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

/** Device-only: actual bundled decoder, native stream and Activity lifecycle. */
class CreaseStudyActivityTest {
    @get:Rule val compose = createAndroidComposeRule<CreaseStudyActivity>()
    private fun ready() { compose.waitUntil(60000) {
        compose.onAllNodesWithText("Ready · C4 · 44.1 kHz source").fetchSemanticsNodes().isNotEmpty()
    } }
    @Test fun touchReleaseAndStopResetTheGesture() {
        ready();val key=compose.onNodeWithTag("crease-study-key")
        key.performTouchInput { down(center) };compose.onNodeWithText("C4 · HOLDING").assertExists()
        key.performTouchInput { up() };compose.onNodeWithText("PRESS & HOLD C4").assertExists()
        key.performTouchInput { down(center) };compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("PRESS & HOLD C4").assertExists();key.performTouchInput { up() }
    }
    @Test fun aLateKeyboardLiftDoesNotReleaseTheNewKey() {
        ready();compose.runOnUiThread {
            compose.activity.onKeyDown(KeyEvent.KEYCODE_SPACE,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE))
            compose.activity.onKeyDown(KeyEvent.KEYCODE_ENTER,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER))
            compose.activity.onKeyUp(KeyEvent.KEYCODE_SPACE,KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SPACE))
        }
        compose.onNodeWithText("C4 · HOLDING").assertExists()
        compose.runOnUiThread { compose.activity.onKeyUp(KeyEvent.KEYCODE_ENTER,KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER)) }
        compose.onNodeWithText("PRESS & HOLD C4").assertExists()
    }
    @Test fun backgroundAndResumeNeverRestoreAHeldGesture() {
        ready();compose.onNodeWithTag("crease-study-key").performTouchInput { down(center) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ready();compose.onNodeWithText("PRESS & HOLD C4").assertExists()
    }
    @Test fun accessibilityClickHasAFinitePreview() {
        ready();compose.onNodeWithTag("crease-study-key").performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithText("PRESS & HOLD C4").fetchSemanticsNodes().isNotEmpty() }
    }
}
