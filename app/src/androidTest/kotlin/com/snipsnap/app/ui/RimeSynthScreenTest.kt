package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.synth.Rime
import com.snipsnap.synth.RimePresets
import com.snipsnap.synth.RimeVoice
import com.snipsnap.synth.RimePatch
import com.snipsnap.shell.SynthDrafts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RimeSynthScreenTest : ComposeScreenTest() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "rime-six-voices-${System.nanoTime()}").apply { mkdirs() }
    @After fun close() { scope.cancel(); root.deleteRecursively() }

    private fun press(label: String) {
        val node=compose.onNodeWithContentDescription(label,useUnmergedTree=true).fetchSemanticsNode()
        val action=requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle { assertTrue("Click refused for $label",action.invoke()) }; pump()
    }
    private fun progress(label: String) = compose.onNode(
        hasContentDescription(label,substring=true) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),useUnmergedTree=true)
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test fun opening_a_saved_model_five_hold_does_not_enable_moving_detail() {
        val patch=RimePatch("Saved vessel",RimeVoice.VESSEL,Rime.defaults(RimeVoice.VESSEL,5)+("HOLD" to 1f),
            53,.37f,.4f,2718,5)
        val settings=mutableMapOf("engine" to "RIME","voice" to "VESSEL")
        SynthDrafts.write(settings,listOf(patch))
        setPhoneContent {
            SynthScreen(entry=null,shelfRoot=root,onToast={},onKitUpdated={},onShelfAssetWritten={},
                onDroneToLoop={_,_,_->},onOpenSurface={},appScope=scope,settings=settings)
        }
        compose.onNodeWithText("UNFOLD SHAPES THE OPENING. HELD DETAIL STAYS STEADY.").assertExists()
        assertEquals(patch.velocity,progress("VELOCITY"),.00001f)
        compose.runOnIdle {
            val saved=SynthDrafts.read(settings).filterIsInstance<RimePatch>().single()
            assertEquals(5,saved.model); assertEquals(patch.seed,saved.seed)
            assertEquals(patch.gateSeconds,saved.gateSeconds)
        }
    }

    @Test fun all_six_voices_expose_six_complete_presets_and_their_native_performance() {
        val settings=mutableMapOf("engine" to "RIME","voice" to "LOOP")
        setPhoneContent {
            SynthScreen(entry=null,shelfRoot=root,onToast={},onKitUpdated={},onShelfAssetWritten={},
                onDroneToLoop={_,_,_->},onOpenSurface={},appScope=scope,
                settings=settings)
        }
        compose.onNodeWithContentDescription("ENGINE RIME. CHOOSE ENGINE").assertExists()
        for (voice in RimeVoice.entries) {
            press(voice.name)
            val presets=RimePresets.forVoice(voice)
            assertEquals(6,presets.size)
            val patch=presets.last()
            press("PRESET ${patch.name}")
            for ((macro,value) in patch.macros) assertEquals(value,progress(macro),.00001f)
            assertEquals((patch.midi-Rime.MIN_MIDI).toFloat()/(Rime.MAX_MIDI-Rime.MIN_MIDI),progress("ROOT"),.00001f)
            assertEquals(patch.velocity,progress("VELOCITY"),.00001f)
            assertEquals((patch.gateSeconds-.15f)/3.85f,progress("GATE"),.00001f)
            compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertExists()
            compose.onNodeWithText("UNFOLD 60–100 REPEATS DETAIL DURING HOLD. LOWER SETTINGS UNFOLD ONCE.").assertExists()
        }
    }
}
