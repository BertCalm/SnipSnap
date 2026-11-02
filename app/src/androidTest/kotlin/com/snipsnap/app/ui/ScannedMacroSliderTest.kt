package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.tape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the actual editor slider: its thumb is a position, its stored value stays physical. */
@RunWith(AndroidJUnit4::class)
class ScannedMacroSliderTest : ComposeScreenTest() {
    private var value by mutableStateOf(.36f)
    private var changes = 0

    private fun show(name: String = "DAMP") {
        setPhoneContent {
            val scheme = LocalScheme.current
            ScannedMacroSlider(name, value, scheme.amber.tape, scheme) {
                value = it
                changes++
            }
        }
    }

    @Test
    fun opening_a_saved_damp_value_moves_only_its_thumb() {
        show()
        val range = button("DAMP").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.6f, range.current, .000001f)
        assertEquals(.36f, value, 0f)
        assertEquals(0, changes)
        compose.onNodeWithText("36", useUnmergedTree = true).assertExists()
    }

    @Test
    fun accessible_and_pointer_midpoints_store_the_same_damp_value() {
        show()
        val setProgress = button("DAMP").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        compose.runOnUiThread { assertTrue(setProgress(.5f)) }
        pump()
        assertEquals(.25f, value, .000001f)
        compose.onNodeWithText("25", useUnmergedTree = true).assertExists()

        compose.runOnUiThread { value = .8f }
        pump()
        val width = button("DAMP").fetchSemanticsNode().boundsInRoot.width
        button("DAMP").performTouchInput { down(Offset(width / 2f, centerY)); up() }
        pump()
        assertEquals(.25f, value, .00001f)
        val range = button("DAMP").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.5f, range.current, .00001f)
        compose.onNodeWithText("25", useUnmergedTree = true).assertExists()
    }

    @Test
    fun bloom_keeps_its_existing_linear_travel_and_saved_value() {
        show("BLOOM")
        val setProgress = button("BLOOM").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        compose.runOnUiThread { assertTrue(setProgress(.5f)) }
        pump()
        assertEquals(.5f, value, 0f)
        compose.onNodeWithText("50", useUnmergedTree = true).assertExists()
    }
}
