package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
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
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismOpeningPresets
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismPresets
import com.snipsnap.synth.PrismVoice
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
import java.util.concurrent.ConcurrentHashMap

/** The real factory/edit/save controls must keep an opening out of PRISM's loop-only path. */
@RunWith(AndroidJUnit4::class)
class PrismSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val directories = mutableListOf<File>()
    private var screenGeneration by mutableIntStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        directories.forEach { it.deleteRecursively() }
    }

    private fun show(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "prism-synth-test-${System.nanoTime()}").apply { mkdirs() }
        directories += root
        settings["engine"] = "PRISM"
        settings["voice"] = "KEY"
        setPhoneContent {
            key(screenGeneration) {
                SynthScreen(
                    entry = null,
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
        return root
    }

    // Direct actions reach below the fold without an animated scroll on the frozen clock.
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
        assertTrue("Slider refused for $label", action.invoke(value))
        pump()
    }

    private fun progress(label: String): ProgressBarRangeInfo =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

    private fun draft(voice: PrismVoice): PrismPatch? = SynthDrafts.read(settings)
        .filterIsInstance<PrismPatch>().singleOrNull { it.voice == voice }

    private fun loadFactory(patch: PrismPatch) {
        press(patch.voice.name)
        // The spoken name has to be on the tree after the voice change. A chip
        // left over from the previous voice is not this preset.
        waitFor("PRESET ${patch.name}") {
            runCatching {
                compose.onNodeWithContentDescription("PRESET ${patch.name}", useUnmergedTree = true)
                    .fetchSemanticsNode()
            }.isSuccess
        }
        press("PRESET ${patch.name}")
        waitFor("${patch.name} complete recipe") { draft(patch.voice) == patch }
    }

    private fun assertFiniteHold() {
        val hold = progress("HOLD")
        assertEquals(0f, hold.range.start, 0f)
        assertEquals(Math.nextDown(Prism.LOOP_THRESHOLD), hold.range.endInclusive, 0f)
        assertTrue("An opening must never offer the loop threshold", hold.range.endInclusive < Prism.LOOP_THRESHOLD)
        assertFalse(Prism.isLoop(hold.current))
        compose.onNodeWithText("NOTE", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("FINITE NOTE · HOLD SETS LENGTH", useUnmergedTree = true).assertExists()
    }

    private fun saveAs(name: String, root: File): PrismPatch {
        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        pump()
        press("SAVE")
        waitFor("the saved PRISM preset", timeoutMillis = 30_000) {
            UserPresets.read(root).any { it.name == name }
        }
        waitFor("the saved preset selection") {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Selected) == true
            }
        }
        return UserPresets.read(root).single { it.name == name }.patch as PrismPatch
    }

    @Test
    fun every_factory_opening_clamps_accessibility_hold_and_preserves_its_spectral_recipe() {
        show()
        compose.onNodeWithContentDescription("ENGINE PRISM · TAP FOR NEXT").assertExists()
        val openings = PrismOpeningPresets.all()
        assertEquals(setOf("Soft Arrival", "Wire Bloom", "Bowed Bloom", "Hollow Rise"), openings.map { it.name }.toSet())
        for (factory in openings) {
            loadFactory(factory)
            for ((macro, value) in factory.macros) assertEquals(value, progress(macro).current, 0f)
            assertFiniteHold()

            // Request the old loop endpoint through TalkBack's action, not a bounded helper.
            dial("HOLD", 1f)
            val maximum = Math.nextDown(Prism.LOOP_THRESHOLD)
            waitFor("${factory.name} finite HOLD edit") {
                settings["macro.PRISM.${factory.voice.name}.HOLD"]?.toFloatOrNull() == maximum
            }
            assertEquals(maximum, progress("HOLD").current, 0f)
            assertFiniteHold()
            // Drafts keep the complete source model; slider edits have their own bag entries.
            assertEquals(factory, draft(factory.voice))
        }
    }

    @Test
    fun switching_between_opening_and_legacy_preset_restores_each_hold_contract() {
        show()
        val opening = PrismOpeningPresets.all().single { it.name == "Soft Arrival" }
        val legacy = PrismPresets.forVoice(PrismVoice.KEY).single { it.name == "Soft Facets" }
        loadFactory(opening)
        assertFiniteHold()

        loadFactory(legacy)
        assertEquals(0f..1f, progress("HOLD").range)
        compose.onNodeWithText("FINITE NOTE · HOLD SETS LENGTH", useUnmergedTree = true).assertDoesNotExist()
        dial("HOLD", 1f)
        assertEquals(1f, progress("HOLD").current, 0f)
        assertTrue(Prism.isLoop(progress("HOLD").current))
        compose.onNodeWithText("LOOP", useUnmergedTree = true).assertExists()
        assertEquals(null, draft(legacy.voice)?.motion)

        loadFactory(opening)
        assertFiniteHold()
        assertEquals(opening.macros.getValue("HOLD"), progress("HOLD").current, 0f)
        loadFactory(legacy)
        assertEquals(0f..1f, progress("HOLD").range)
        dial("HOLD", 1f)
        compose.onNodeWithText("LOOP", useUnmergedTree = true).assertExists()
    }

    @Test
    fun edited_opening_saves_reloads_and_returns_with_the_finite_hold_guard() {
        val root = show()
        val factory = PrismOpeningPresets.all().single { it.name == "Bowed Bloom" }
        loadFactory(factory)
        dial("HOLD", 1f)
        dial("BODY", .23f)
        val saved = saveAs("MY OPENING", root)
        assertEquals(factory.copy(name = "MY OPENING", macros = factory.macros + mapOf(
            "HOLD" to Math.nextDown(Prism.LOOP_THRESHOLD), "BODY" to .23f,
        )), saved)

        loadFactory(PrismPresets.forVoice(PrismVoice.KEY).single { it.name == "Soft Facets" })
        // YOURS uses combinedClickable; its child text resolves the merged click action.
        val node = compose.onNodeWithText(saved.name).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("No personal preset click action for ${saved.name}")
        assertTrue(action.invoke())
        pump()
        waitFor("the reloaded complete opening") { draft(saved.voice) == saved }
        assertFiniteHold()

        compose.runOnIdle { screenGeneration++ }
        pump()
        waitFor("the restored opening HOLD") { progress("HOLD").current == saved.macros.getValue("HOLD") }
        assertFiniteHold()
        assertEquals(.23f, progress("BODY").current, 0f)
        assertEquals(saved, draft(saved.voice))
    }
}
