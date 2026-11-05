package com.snipsnap.app

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertTrue
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
        key.assertTextContains("PRESS & HOLD C4")
        key.performTouchInput { down(center) };compose.onNodeWithText("C4 · HOLDING").assertExists()
        key.performTouchInput { up() };compose.onNodeWithText("PRESS & HOLD C4").assertExists()
        key.performTouchInput { down(center) }
        val stop=compose.onNodeWithText("Stop").fetchSemanticsNode().boundsInRoot.center
        // Stop is a second finger while the C4 finger remains down.
        compose.onRoot().performTouchInput { down(1,stop);up(1) }
        compose.onNodeWithText("PRESS & HOLD C4").assertExists()
        compose.onRoot().performTouchInput { up(0) }
    }
    @Test fun aLateKeyboardLiftDoesNotReleaseTheNewKey() {
        ready();compose.waitForIdle()
        val input=InstrumentationRegistry.getInstrumentation()
        input.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE))
        input.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER))
        input.sendKeySync(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SPACE))
        compose.onNodeWithText("C4 · HOLDING").assertExists()
        input.sendKeySync(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))
        compose.onNodeWithText("PRESS & HOLD C4").assertExists()
    }
    @Test fun backgroundAndResumeNeverRestoreAHeldGesture() {
        ready();compose.onNodeWithTag("crease-study-key").performTouchInput { down(center) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ready();compose.onNodeWithText("PRESS & HOLD C4").assertExists()
        compose.onRoot().performTouchInput { up(0) }
        compose.onNodeWithText("PRESS & HOLD C4").assertExists()
    }
    @Test fun accessibilityClickHasAFinitePreview() {
        ready();var accepted=false
        compose.onNodeWithTag("crease-study-key").performSemanticsAction(SemanticsActions.OnClick) { accepted=it() }
        assertTrue("accessibility action must start a native gesture",accepted)
        compose.waitForIdle()
        compose.waitUntil(3000) { compose.onAllNodesWithText("PRESS & HOLD C4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ready · C4 · 44.1 kHz source").assertExists()
    }
}
