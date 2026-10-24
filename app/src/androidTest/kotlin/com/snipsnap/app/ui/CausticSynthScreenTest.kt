package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticPresets
import com.snipsnap.synth.CausticVoice
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Phone recipe plumbing: finite CAUSTIC keeps pitch, strike and model beside its macros. */
@RunWith(AndroidJUnit4::class)
class CausticSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directories = mutableListOf<File>()
    private var generation by mutableStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        directories.forEach { it.deleteRecursively() }
    }

    private fun shelf(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "caustic-synth-test-${System.nanoTime()}",
    ).apply { mkdirs(); directories += this }

    private fun show(root: File, settings: MutableMap<String, String>, entry: KitShelf.Entry? = null) {
        setPhoneContent {
            key(generation) {
                SynthScreen(
                    entry = entry, shelfRoot = root,
                    onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = appScope, settings = settings,
                )
            }
        }
    }

    private fun press(label: String) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("No click action for $label")
        compose.runOnIdle { assertTrue("Click refused for $label", action.invoke()) }
        pump()
    }

    private fun dial(label: String, value: Float) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("No slider action for $label")
        compose.runOnIdle { assertTrue("Slider refused for $label", action.invoke(value)) }
        pump()
    }

    private fun progress(label: String): Float = compose
        .onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun assertFiniteControls() {
        compose.onNodeWithText("FINITE NOTE · LENGTH SETS DECAY · HOLD RESERVED.").assertExists()
        compose.onNodeWithContentDescription("HOLD", useUnmergedTree = true).assertIsNotEnabled()
        val hold = compose.onNodeWithContentDescription("HOLD", useUnmergedTree = true).fetchSemanticsNode()
        assertNull(hold.config.getOrNull(SemanticsActions.SetProgress))
        assertEquals(0f, progress("HOLD"), .00001f)
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertDoesNotExist()
        compose.onNodeWithContentDescription("DRONE TO LOOP").assertDoesNotExist()
    }

    @Test
    fun picker_edits_save_recreation_and_pad_keep_the_finite_native_recipe() {
        val root = shelf()
        val kitDir = File(root, "kit").apply { mkdirs() }
        val kit = Kit(name = "CAUSTIC UI", pads = emptyList())
        KitStore.save(kit, kitDir)
        val settings = mutableMapOf("engine" to "OHM", "voice" to "STRING")
        show(root, settings, KitShelf.Entry(kitDir, kit))

        // The picker reads OHM, MERCURY, CAUSTIC: #643 put MERCURY after OHM.
        press("ENGINE OHM · TAP FOR NEXT")
        press("ENGINE MERCURY · TAP FOR NEXT")
        compose.onNodeWithContentDescription("ENGINE CAUSTIC · TAP FOR NEXT").assertExists()
        compose.onNodeWithContentDescription("ALLOY").assertExists()
        val factory = CausticPresets.forVoice(CausticVoice.ALLOY).first()
        press("PRESET ${factory.name}")
        assertFiniteControls()
        for (macro in listOf("SOURCE", "FOCUS", "BODY", "LENGTH")) {
            assertEquals(factory.macros.getValue(macro), progress(macro), .00001f)
        }

        dial("ROOT", (57 - CausticInstrument.MIDI_MIN).toFloat() /
            (CausticInstrument.MIDI_MAX - CausticInstrument.MIDI_MIN))
        dial("VELOCITY", .43f)
        dial("SOURCE", .68f)
        dial("BODY", .62f)
        dial("LENGTH", .3f)
        val changed = factory.copy(midi = 57, velocity = .43f,
            macros = factory.macros + mapOf("SOURCE" to .68f, "BODY" to .62f, "LENGTH" to .3f))

        // Macro edits remove the preset highlight. The independent draft still
        // carries ROOT, VELOCITY and the native model when MAKE is recreated.
        compose.runOnIdle { generation++ }
        pump()
        waitFor("the restored Caustic root") { progress("ROOT") == (57 - 36) / 48f }
        assertEquals(.43f, progress("VELOCITY"), .00001f)
        assertEquals(.68f, progress("SOURCE"), .00001f)
        assertFiniteControls()

        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY CAUSTIC")
        pump()
        press("SAVE")
        waitFor("saved Caustic preset", timeoutMillis = 30_000) {
            UserPresets.read(root).any { it.name == "MY CAUSTIC" }
        }
        waitFor("selected Caustic preset") {
            compose.onAllNodesWithText("MY CAUSTIC").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Selected) == true
            }
        }
        val saved = UserPresets.read(root).single { it.name == "MY CAUSTIC" }.patch as CausticPatch
        assertEquals(changed.copy(name = "MY CAUSTIC"), saved)

        press("SEND TO PAD")
        press("A01, EMPTY")
        waitFor("the finite Caustic pad", timeoutMillis = 180_000) {
            KitBuilderModel.open(kitDir).pad(1) != null
        }
        val pad = KitBuilderModel.open(kitDir).pad(1)!!
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val placed = recipe.patch as CausticPatch
        assertEquals(saved.copy(name = placed.name), placed)
        assertEquals(CausticInstrument.drumClassFor(saved.voice, saved.macros), pad.drumClass)
        assertTrue("a duration-based LOOP class does not make a held loop", pad.oneShot)
        assertEquals(null, recipe.fx)
        assertTrue(File(kitDir, pad.sampleFile).length() > 44)
    }

    @Test
    fun a_stale_reserved_hold_setting_cannot_trap_a_restored_note() {
        val root = shelf()
        val patch = CausticPatch("Saved root", CausticVoice.ALLOY,
            mapOf("BODY" to .7f), midi = 69, velocity = .37f, model = 1)
        val settings = mutableMapOf("engine" to "CAUSTIC", "voice" to "ALLOY",
            "macro.CAUSTIC.ALLOY.SOURCE" to ".33", "macro.CAUSTIC.ALLOY.HOLD" to "1.0")
        SynthDrafts.write(settings, listOf(patch))
        show(root, settings)
        waitFor("restored explicit Caustic root") { progress("ROOT") == (69 - 36) / 48f }
        assertEquals(.37f, progress("VELOCITY"), .00001f)
        assertEquals(.33f, progress("SOURCE"), .00001f)
        assertFiniteControls()
        waitFor("reserved HOLD reset") { settings["macro.CAUSTIC.ALLOY.HOLD"] == "0.0" }
    }
}
