package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.ScannedChainEditor
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.ScannedChain
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.ScannedChainConfig
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.ScannedChainShape
import com.snipsnap.synth.ScannedChainVoice
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
import java.util.concurrent.ConcurrentHashMap

/** The real bench must save the newest envelope and physical macro, even within one frame. */
@RunWith(AndroidJUnit4::class)
class ScannedChainEditorScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "scanned-editor-${System.nanoTime()}").apply { mkdirs() }
    private val settings = ConcurrentHashMap<String, String>()

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    @Test
    fun envelope_macro_and_save_in_one_frame_keep_the_complete_saved_source() {
        val source = ScannedChainPatch("KEPT SCAN", ScannedChainVoice.PLUCK,
            macros = mapOf("MOTION" to .61f, "DAMP" to .37f, "BEND" to .29f, "BLOOM" to .12f),
            shape = ScannedChainShape.drawn(FloatArray(128) { i -> if (i % 9 < 4) .18f else -.11f }, "KEPT SOURCE"),
            config = ScannedChainConfig.forVoice(ScannedChainVoice.PLUCK).copy(
                seconds = .4f, restFraction = .13f, attackSeconds = .031f, releaseSeconds = .17f, seed = -952,
            ),
            midi = 57, velocity = .43f, model = 1,
        )
        settings["engine"] = "SCANNED_CHAIN"
        settings["voice"] = source.voiceName
        SynthWork.replace(settings, listOf(SynthWork.Working(PadRecipe(source), null)))
        setPhoneContent {
            SynthScreen(
                entry = null, shelfRoot = shelf, onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings,
            )
        }
        val root = button("ROOT").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        // The shared ROOT row reports the root as a fraction of the engine's range.
        assertEquals((source.midi - ScannedChain.MIDI_MIN).toFloat() / (ScannedChain.MIDI_MAX - ScannedChain.MIDI_MIN), root.current, 1e-6f)
        fun progress(label: String) = button(label).fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        val attack = progress("ATTACK")
        val release = progress("RELEASE")
        val damp = progress("DAMP")
        val openSave = button("SAVE PRESET").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assertTrue(openSave()) }
        pump()
        val fields = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        fields[fields.fetchSemanticsNodes().lastIndex].performTextReplacement("SCAN ENVELOPE")
        pump()
        val save = compose.onNodeWithContentDescription("SAVE", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            assertTrue(damp(.5f))
            assertTrue(attack(.4f))
            assertTrue(release(.3f))
            assertTrue(save())
        }
        waitFor("the newest complete SCANNED preset", timeoutMillis = 30_000) {
            UserPresets.read(shelf).any { it.name == "SCAN ENVELOPE" }
        }
        val saved = UserPresets.read(shelf).single { it.name == "SCAN ENVELOPE" }.patch
        assertEquals(source.copy(
            name = "SCAN ENVELOPE",
            macros = source.macros + ("DAMP" to .25f),
            config = source.config.copy(
                attackSeconds = ScannedChainEditor.attackSeconds(.4f),
                releaseSeconds = ScannedChainEditor.releaseSeconds(.3f),
            ),
        ), saved)
    }
}
