package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Schemes
import com.snipsnap.synth.AquariumControls
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AquariumPerformancePanelTest : ComposeScreenTest() {
    private var current by mutableStateOf(AquariumPerformanceSettings(settings = AquariumRecipe(seed = 0xffff_ffffL)))
    private var saved by mutableStateOf(current)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)
    private var resetCount = 0

    private fun show() = setPhoneContent {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AquariumPerformancePanel(
                settings = current, dirty = current != saved, busy = busy,
                streamUp = true, pending = false, faulted = false, limitedFrames = 0,
                bpm = 123.5, warning = null, status = status,
                scheme = Schemes.DEFAULT, color = Schemes.DEFAULT.accent.tape,
                onChange = { current = it }, onReset = { resetCount++ },
                onSave = { saved = current; status = "SAVED TO KIT" }, onRevert = { current = saved },
            )
        }
    }

    private fun activate(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        pump()
    }

    @Test fun live_controls_preserve_gain_and_seed_and_revert_to_the_saved_snapshot() {
        show()
        compose.onNodeWithTag("performance-save").assertIsNotEnabled()
        compose.onNodeWithTag("aquarium-print-settings").assertDoesNotExist()
        activate("aquarium-preset-full")
        assertEquals(AquariumControls.preset("full").settings.parameters, current.settings.parameters)
        assertFalse("choosing a sound must preserve explicit global bypass", current.enabled)
        assertEquals(0xffff_ffffL, current.settings.seed)
        assertEquals(.5f, current.outputGain, 0f)
        activate("performance-enable")
        assertTrue(current.enabled)
        compose.onNodeWithTag("performance-output").performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue(it(0f))
        }
        pump()
        assertEquals(0f, current.outputGain, 0f)
        activate("aquarium-preset-full")
        assertEquals("presets must preserve intentional output mute", 0f, current.outputGain, 0f)
        activate("performance-save")
        assertEquals(current, saved)
        activate("aquarium-preset-dry")
        assertTrue(current.settings.isBypass)
        activate("performance-revert")
        assertEquals(saved, current)
        activate("performance-reset")
        assertEquals(1, resetCount)
        assertEquals(saved, current)
    }

    @Test fun saving_disables_every_edit_and_failure_leaves_the_draft_available_for_retry() {
        show()
        activate("aquarium-preset-reef")
        val draft = current
        compose.runOnIdle { busy = true; status = "SAVING PERFORMANCE…" }
        pump()
        compose.onNodeWithTag("performance-save").assertIsNotEnabled()
        compose.onNodeWithTag("performance-revert").assertIsNotEnabled()
        compose.onNodeWithTag("performance-enable").assertIsNotEnabled()
        compose.onNodeWithTag("aquarium-preset-full").assertIsNotEnabled()
        compose.runOnIdle { busy = false; status = "COULD NOT SAVE PERFORMANCE · TRY AGAIN" }
        pump()
        assertEquals(draft, current)
        activate("performance-save")
        assertEquals(draft, saved)
    }
}
