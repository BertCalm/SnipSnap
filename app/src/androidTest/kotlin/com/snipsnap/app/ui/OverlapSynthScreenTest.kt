package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.OverlapMusicalBank
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.OverlapTopology
import com.snipsnap.synth.PadRecipe
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

/** Exercises the phone's real preset/edit/save/place glue; DSP and export are covered in :shell. */
@RunWith(AndroidJUnit4::class)
class OverlapSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directories = mutableListOf<File>()
    private var screenGeneration by mutableStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        directories.forEach { it.deleteRecursively() }
    }

    private fun shelf(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "overlap-synth-test-${System.nanoTime()}")
            .apply { mkdirs(); directories += this }
    }

    private fun show(root: File, settings: MutableMap<String, String>, entry: KitShelf.Entry? = null) {
        setPhoneContent {
            key(screenGeneration) {
                SynthScreen(
                    entry = entry,
                    shelfRoot = root,
                    onToast = {},
                    onKitUpdated = {},
                    onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> },
                    onOpenSurface = {},
                    appScope = appScope,
                    settings = settings,
                )
            }
        }
    }

    // Direct semantics actions reach controls below the fold without animating
    // a scroll on ComposeScreenTest's deliberately frozen frame clock.
    private fun press(label: String) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("No click action for $label")
        assertTrue("Click refused for $label", action.invoke())
        pump()
    }

    private fun dial(label: String, value: Float) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("No slider action for $label")
        assertTrue("Slider refused for $label", action.invoke(value))
        pump()
    }

    private fun loadPersonalPreset(name: String) {
        // combinedClickable labels this action while its child supplies the text.
        val node = compose.onNodeWithText(name).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("No preset click action for $name")
        assertTrue(action.invoke())
        pump()
    }

    private fun progress(label: String): Float {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        return node.config[SemanticsProperties.ProgressBarRangeInfo].current
    }

    private fun saveAs(name: String, root: File): OverlapPatch {
        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        pump()
        press("SAVE")
        waitFor("the saved preset", timeoutMillis = 30_000) {
            UserPresets.read(root).any { it.name == name }
        }
        waitFor("the saved preset selection") {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Selected) == true
            }
        }
        return UserPresets.read(root).single { it.name == name }.patch as OverlapPatch
    }

    private fun recreate() {
        compose.runOnIdle { screenGeneration++ }
        pump()
    }

    @Test
    fun factory_recipe_edits_survive_save_screen_recreation_and_send_to_pad() {
        val root = shelf()
        val kitDir = File(root, "kit").apply { mkdirs() }
        val kit = Kit(name = "OVERLAP UI", pads = emptyList())
        KitStore.save(kit, kitDir)
        val settings = mutableMapOf("engine" to "OPENSKY", "voice" to "SOAR")
        show(root, settings, KitShelf.Entry(kitDir, kit))

        // Enter through the real engine cycler, then choose the topology and preset.
        press("ENGINE OPENSKY · TAP FOR NEXT")
        compose.onNodeWithContentDescription("ENGINE OVERLAP · TAP FOR NEXT").assertExists()
        for (topology in OverlapTopology.entries) {
            compose.onNodeWithContentDescription(topology.name).assertExists()
        }
        press("TREFOIL")
        press("PRESET GLASS THREAD")
        val factory = OverlapMusicalBank.all().single { it.id == "glass-thread" }.patch
        for (macro in listOf("KNOT", "PULSE", "LOCK", "GLOW", "RING", "HOLD")) {
            assertEquals(factory.macros.getValue(macro), progress(macro), .00001f)
        }
        assertEquals((factory.midi - 24) / 60f, progress("ROOT"), .00001f)
        assertEquals(factory.velocity, progress("VELOCITY"), .00001f)

        dial("ROOT", (57 - 24) / 60f)
        dial("VELOCITY", .43f)
        dial("GLOW", .62f)
        val saved = saveAs("MY OVERLAP", root)
        assertEquals(factory.copy(name = "MY OVERLAP", midi = 57, velocity = .43f,
            macros = factory.macros + ("GLOW" to .62f)), saved)

        recreate()
        waitFor("the restored preset root") { progress("ROOT") == (57 - 24) / 60f }
        assertEquals(.43f, progress("VELOCITY"), .00001f)
        assertEquals(.62f, progress("GLOW"), .00001f)
        press("SEND TO PAD")
        press("A01, EMPTY")
        waitFor("the rendered pad", timeoutMillis = 60_000) {
            KitBuilderModel.open(kitDir).pad(1) != null
        }
        val pad = KitBuilderModel.open(kitDir).pad(1)!!
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertTrue(pad.oneShot)
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val placed = recipe.patch as OverlapPatch
        assertEquals(saved.copy(name = placed.name), placed)
        assertEquals(null, recipe.fx)
        assertTrue(File(kitDir, pad.sampleFile).length() > 44)
    }

    @Test
    fun partial_legacy_preset_uses_its_saved_model_defaults_after_load_and_edit() {
        val root = shelf()
        val legacy = OverlapPatch("OLD NETWORK", OverlapTopology.TREFOIL,
            mapOf("GLOW" to .1f), midi = 55, velocity = .61f, model = 1)
        UserPresets.save(root, legacy, 1L)
        // Restoring a named YOURS preset also exercises the asynchronous shelf read.
        val settings = mutableMapOf("engine" to "OVERLAP", "voice" to "TREFOIL",
            "preset.OVERLAP.TREFOIL" to legacy.name)
        show(root, settings)
        waitFor("the legacy preset root") { progress("ROOT") == (55 - 24) / 60f }
        assertEquals(.65f, progress("RING"), .00001f)
        loadPersonalPreset("OLD NETWORK")
        assertEquals(.65f, progress("RING"), .00001f)
        dial("GLOW", .42f)
        val saved = saveAs("MY LEGACY", root)
        assertEquals(1, saved.model)
        assertEquals(legacy.midi, saved.midi)
        assertEquals(legacy.velocity, saved.velocity, .00001f)
        assertEquals(.65f, saved.macros.getValue("RING"), .00001f)
        assertEquals(.42f, saved.macros.getValue("GLOW"), .00001f)

        recreate()
        waitFor("the saved legacy root") { progress("ROOT") == (55 - 24) / 60f }
        assertEquals(.65f, progress("RING"), .00001f)
        assertEquals(.42f, progress("GLOW"), .00001f)
    }
}
