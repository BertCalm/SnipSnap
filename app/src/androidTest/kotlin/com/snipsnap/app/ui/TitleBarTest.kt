package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.TapeTheme
import com.snipsnap.shell.Schemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The title bar's lockup: named SNIPSNAP for TalkBack, two words and the symbol, never squeezed by the project name. */
@RunWith(AndroidJUnit4::class)
class TitleBarTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theLockupIsNamedSnipSnap() {
        rule.setContent { TapeTheme(Schemes.DEFAULT) { TitleBar(onGear = {}) } }
        rule.onNodeWithContentDescription("SNIPSNAP").assertIsDisplayed()
        rule.onNodeWithText("SNIP", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("SNAP", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aLongProjectNameDoesNotSqueezeTheLockup() {
        // One composition, two bars: the rule allows setContent once per test.
        rule.setContent {
            TapeTheme(Schemes.DEFAULT) {
                Column(Modifier.width(320.dp)) {
                    TitleBar(projectName = "A".repeat(80), onNote = {}, onGear = {})
                    TitleBar(onGear = {})
                }
            }
        }
        val lockups = rule.onAllNodesWithContentDescription("SNIPSNAP").fetchSemanticsNodes()
        assertEquals(2, lockups.size)
        val (crowded, alone) = lockups.sortedBy { it.positionInRoot.y }
        assertEquals("the lockup's width is its own, project name or not", alone.size.width, crowded.size.width)
        assertTrue("the lockup has width", alone.size.width > 0)
        rule.onAllNodesWithText("SNIP", useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodesWithText("SNAP", useUnmergedTree = true).assertCountEquals(2)
    }
}
