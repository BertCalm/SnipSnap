package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.Ballast
import com.snipsnap.synth.BackwashPatch
import com.snipsnap.synth.BallastPatch
import com.snipsnap.synth.BallastVoice
import com.snipsnap.synth.Soot
import com.snipsnap.synth.SootPatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The real SYNTH editor, including restoring the previously roster-only engine selections. */
@RunWith(AndroidJUnit4::class)
class NewEngineSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "new-engine-editor-${System.nanoTime()}").apply { mkdirs() }
    private val settings = mutableMapOf<String, String>()
    private var generation by mutableStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(engine: String, voice: String) {
        settings["engine"] = engine
        settings["voice"] = voice
        setPhoneContent {
            key(generation) {
                SynthScreen(entry = null, shelfRoot = shelf, onToast = {}, onKitUpdated = {},
                    onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = appScope, settings = settings)
            }
        }
        button("ENGINE $engine. CHOOSE ENGINE").assertExists()
    }

    /** A macro slider by its label. A voice chip can share the name (SOOT's COIL voice and its snare's COIL). */
    private fun control(label: String) = compose.onNode(
        hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    private fun adjust(label: String, value: Float) {
        control(label).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        pump()
    }

    private fun progress(label: String) =
        control(label).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun showsLoop() = compose.onAllNodesWithText("LOOP", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun backwashRestoresItsDrumVoiceAndSixControls() {
        show("BACKWASH", "SNARE")
        button("SNARE").assertIsSelected()
        button("PRESET Tight Snare").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.9f, progress("DAMP"), .0001f)
        for (label in listOf("TUNE", "DAMP", "SWAY", "TRAIL", "PRESS", "DRIVE")) button(label).assertExists()
        adjust("TRAIL", .8f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("BACKWASH", settings["engine"])
        assertEquals(.8f, progress("TRAIL"), .0001f)
        assertFalse(showsLoop())
        val original = SynthDrafts.read(settings).single { it.engine == "BACKWASH" && it.voiceName == "SNARE" } as BackwashPatch
        assertEquals(3, original.model)
        assertEquals(.8f, original.macros.getValue("TRAIL"), .0001f)
        button("PRESET Pushed Snare").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        for (label in listOf("SWAY", "TRAIL", "PRESS", "DRIVE")) assertEquals(1f, progress(label), .0001f)
        adjust("PRESS", .72f)
        compose.runOnIdle { generation++ }
        pump()
        for (label in listOf("SWAY", "TRAIL", "DRIVE")) assertEquals(1f, progress(label), .0001f)
        assertEquals(.72f, progress("PRESS"), .0001f)
        val pushed = SynthDrafts.read(settings).single { it.engine == "BACKWASH" && it.voiceName == "SNARE" } as BackwashPatch
        assertEquals(4, pushed.model)
        assertEquals(.72f, pushed.macros.getValue("PRESS"), .0001f)
    }

    /**
     * SOOT on the picker (2026-11-13): a prototype outside the factory presets, so its voices and controls
     * with no preset chips. The snare opens at the owner's pick (EDGE 1, the hot cassette), an edit survives
     * a rebuild, and the saved draft keeps its renderer model.
     */
    @Test
    fun sootOpensItsSnareAtThePickAndKeepsAnEditAndItsModel() {
        show("SOOT", "SNARE")
        button("SNARE").assertIsSelected()
        for (label in listOf("TUNE", "COIL", "EDGE", "BENCH", "GRIME")) control(label).assertExists()
        // COIL is both a voice chip and the snare's spring send: two nodes, one of them the slider.
        compose.onAllNodesWithContentDescription("COIL").assertCountEquals(2)
        assertEquals(1f, progress("EDGE"), .0001f)
        adjust("COIL", .8f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("SOOT", settings["engine"])
        assertEquals(.8f, progress("COIL"), .0001f)
        assertFalse(showsLoop())
        val draft = SynthDrafts.read(settings).single { it.engine == "SOOT" && it.voiceName == "SNARE" } as SootPatch
        assertEquals(Soot.MODEL_VERSION, draft.model)
        assertEquals(.8f, draft.macros.getValue("COIL"), .0001f)
    }

    @Test
    fun chitinLoadsCandidateBassAndKeepsMaximumHoldFinite() {
        show("CHITIN", "SHELL")
        button("SHELL").assertIsSelected()
        button("PRESET FOUNDATION").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.18f, progress("BITE"), .0001f)
        adjust("HOLD", 1f)
        assertTrue(compose.onAllNodesWithText("LONG NOTE", useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("CHITIN", settings["engine"])
        assertEquals(1f, progress("HOLD"), .0001f)
    }

    @Test
    fun aerostatOpensItsApprovedPresetAndItsActualControls() {
        show("AEROSTAT", "FLOAT")
        button("FLOAT").assertIsSelected()
        button("PRESET SOFT CATCH").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        // This is the listener-approved dry PVC recipe, not the original full-air recipe.
        assertEquals(.10f, progress("STRIKE"), .0001f)
        assertEquals(.18f, progress("PRESSURE"), .0001f)
        assertEquals(0f, progress("LIFT"), .0001f)
        adjust("HOLD", 1f)
        assertTrue(showsLoop())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("AEROSTAT", settings["engine"])
        assertEquals(1f, progress("HOLD"), .0001f)
        assertEquals(.10f, progress("STRIKE"), .0001f)
    }

    @Test
    fun tremorKeepsTheEditedVoiceAndShowsItsHalfwayLoopThreshold() {
        show("TREMOR", "WIRE")
        button("WIRE").assertIsSelected()
        adjust("CAGE", .73f)
        adjust("HOLD", .49f)
        assertFalse(showsLoop())
        adjust("HOLD", .5f)
        assertTrue(showsLoop())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("TREMOR", settings["engine"])
        assertEquals("WIRE", settings["voice"])
        button("WIRE").assertIsSelected()
        assertEquals(.73f, progress("CAGE"), .0001f)
        assertEquals(.5f, progress("HOLD"), .0001f)
    }

    @Test
    fun ballastDisplaysTilesButKeepsTheStoredGlintVoice() {
        val patch = BallastPatch("MY TILES", BallastVoice.GLINT,
            Ballast.defaults(BallastVoice.GLINT) + ("SPAN" to .33f))
        SynthDrafts.write(settings, listOf(patch))
        for ((name, value) in patch.macros) settings["macro.BALLAST.GLINT.$name"] = value.toString()
        show("BALLAST", "GLINT")
        button("TILES").assertIsSelected()
        button("GLINT").assertDoesNotExist()
        adjust("SPAN", .77f)
        adjust("HOLD", 1f)
        assertTrue(showsLoop())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("BALLAST", settings["engine"])
        assertEquals("GLINT", settings["voice"])
        compose.runOnIdle {
            assertEquals("GLINT", SynthDrafts.read(settings).single { it.engine == "BALLAST" }.voiceName)
        }
        button("TILES").assertIsSelected()
        assertEquals(.77f, progress("SPAN"), .0001f)
        assertEquals(1f, progress("HOLD"), .0001f)
    }
}
