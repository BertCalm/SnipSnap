package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeTheme
import com.snipsnap.shell.Schemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule

/**
 * Shared plumbing for `:app`'s on-device Compose suites — extracted once
 * `SurfaceScreenTest` and `GrooveScreenTest` had grown the same clock,
 * the same finder, and the same width check word for word.
 *
 * **Why the clock is hand-driven, in every subclass, unconditionally.**
 * `SurfaceScreen` and `GrooveScreen` each run a permanent `withFrameNanos`
 * loop for their whole life (a voice drain, and the transport while it
 * plays), so with auto-advance on, the test harness sees that loop as
 * work still pending and every assertion sits in `waitForIdle` until it
 * times out. [setPhoneContent] turns auto-advance off before it sets any
 * content, so a screen that runs such a loop is safe to test from the
 * moment it exists; a screen that does not just never notices.
 *
 * **The one thing never to do with that frozen clock: call
 * `performScrollTo()`.** It drives `Modifier.verticalScroll`'s `ScrollBy`
 * semantics action, which *animates* — it launches a coroutine on the
 * frame clock and returns, and `performScrollTo`'s own `waitForIdle` then
 * waits for work only that clock can finish. With the clock frozen it
 * never arrives. This is exactly what happened to `GrooveScreenTest`'s
 * first CI run: four tests passed, the fifth hung, and `emulator-tests`
 * was cancelled at its 45-minute cap with nothing failed — before that
 * one test was removed. A subclass that needs to reach a control below
 * the fold should assert only on what the fixture puts within the
 * unscrolled frame, or scroll with a manual `down`/`moveBy`/`up` touch
 * gesture instead; neither this class nor any subclass has tried the
 * latter yet.
 *
 * A plain async wait — a coroutine doing real file IO, a `delay()` loop,
 * anything not tied to the frame/animation clock [setPhoneContent]
 * freezes — is a different case and is not the hazard above: [waitFor]
 * polls it safely in real wall-clock steps, which is how every
 * disk-backed fixture in these suites already waits for its kit to load.
 */
abstract class ComposeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * [content], wrapped at [PHONE_WIDTH_DP] with the clock frozen. Every
     * subclass's own `show()` calls this first, then does whatever
     * screen-specific waiting its own fixture needs (a kit read off disk,
     * an engine spinning up) — this only sets the stage.
     */
    protected fun setPhoneContent(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TapeTheme(Schemes.DEFAULT) {
                Box(Modifier.width(PHONE_WIDTH_DP.dp).fillMaxHeight()) { content() }
            }
        }
        pump()
    }

    /** Frames for the screen's loops, then layout: what a moment of real time gives it. */
    protected fun pump(millis: Long = 300) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    /**
     * A frame at a time until [condition], or a failure naming what never
     * came. Safe for a real async wait (disk IO, a coroutine `delay()`) —
     * see this class's own KDoc for the one thing that is not safe.
     */
    protected fun waitFor(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("waited $timeoutMillis ms and $what never came")
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    protected fun button(label: String) = compose.onNodeWithContentDescription(label)

    /** A tap on the button named [label], and the frames for the screen to answer. */
    protected fun tap(label: String) {
        button(label).performClick()
        pump()
    }

    /**
     * The text is drawn whole, on one line, with no ellipsis.
     * `didOverflowWidth` is not asked: the layout the semantics hand back
     * is measured at the button's width, not the text's, and would answer
     * yes for any label narrower than its button - "XY" first of all
     * (`SurfaceScreenTest`'s own discovery: seven of nine tests passed on
     * the emulator and the two that failed did so on labels that plainly
     * fit, both on that check).
     */
    protected fun assertReadsInFull(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode("nothing on screen reads \"$text\"")
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertNotNull("\"$text\" has no text layout to inspect", action)
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(action!!.invoke(results))
        val layout = results.single()
        assertEquals("\"$text\" is more than one line at $PHONE_WIDTH_DP dp", 1, layout.lineCount)
        assertFalse("\"$text\" is cut short with an ellipsis at $PHONE_WIDTH_DP dp", layout.isLineEllipsized(0))
    }

    companion object {
        /** The narrow phone: 360 dp, the width `SurfaceScreenTest`'s first pass was done at. */
        const val PHONE_WIDTH_DP = 360
    }
}
