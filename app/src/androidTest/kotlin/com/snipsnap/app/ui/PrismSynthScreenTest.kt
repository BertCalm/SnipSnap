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
import com.snipsnap.synth.PrismMotionSources
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
        compose.runOnIdle { assertTrue("Slider refused for $label", action.invoke(value)) }
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
        waitFor("the preset name field") {
            compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).performTextReplacement(name)
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
        compose.onNodeWithContentDescription("ENGINE PRISM. CHOOSE ENGINE").assertExists()
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
            assertEquals(factory.copy(macros = factory.macros + ("HOLD" to maximum)), draft(factory.voice))
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
        compose.onNodeWithContentDescription("STOP AUDITION", useUnmergedTree = true).assertExists()
        val beforeStop = draft(legacy.voice)
        press("STOP AUDITION")
        assertEquals("stopping playback must not change the held recipe", beforeStop, draft(legacy.voice))
        assertEquals(null, draft(legacy.voice)?.motion)

        loadFactory(opening)
        assertFiniteHold()
        compose.onNodeWithContentDescription("STOP AUDITION", useUnmergedTree = true).assertDoesNotExist()
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
        compose.runOnIdle { assertTrue(action.invoke()) }
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

    private fun expandMotion() {
        val collapsed = runCatching {
            compose.onNodeWithContentDescription("EXPAND MOTION", useUnmergedTree = true).fetchSemanticsNode()
        }.getOrNull()
        if (collapsed != null) press("EXPAND MOTION")
    }

    private fun clickAction(label: String): () -> Boolean =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("No click action for $label")

    private fun progressAction(label: String): (Float) -> Boolean =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.SetProgress)?.action ?: error("No slider action for $label")

    @Test
    fun ordinary_presets_can_add_edit_repeat_and_reset_motion_without_changing_factory_defaults() {
        show()
        val legacy = PrismPresets.forVoice(PrismVoice.KEY).single { it.name == "Soft Facets" }
        loadFactory(legacy)
        expandMotion()
        assertEquals(null, draft(legacy.voice)?.motion)
        press("ADD PRISM MOTION")
        waitFor("the new motion") { draft(legacy.voice)?.motion != null }
        dial("AMOUNT", .43f)
        dial("TIME", 1.37f)

        // Two accessibility actions in one main-thread turn must see the latest mode,
        // even before Compose can replace the old HOLD node and callback.
        val repeat = clickAction("PRISM MOTION REPEAT")
        val hold = progressAction("HOLD")
        compose.runOnIdle { assertTrue(repeat()); assertTrue(hold(1f)) }
        pump()
        waitFor("repeat motion with loop HOLD") {
            val live = draft(legacy.voice)
            live?.motion?.repeat == true && live.macros["HOLD"] == 1f
        }
        assertEquals(0f..1f, progress("HOLD").range)
        compose.onNodeWithText("LOOP", useUnmergedTree = true).assertExists()
        assertEquals(.43f, draft(legacy.voice)!!.motion!!.evolve, 0f)
        assertEquals(1.37f, draft(legacy.voice)!!.motion!!.seconds, 0f)

        press("PRISM MOTION OPEN")
        waitFor("finite OPEN HOLD") { draft(legacy.voice)?.motion?.repeat == false }
        assertFiniteHold()
        press("RESET PRISM MOTION")
        waitFor("the original motion-free recipe") { draft(legacy.voice)?.motion == null }
        assertEquals(0f..1f, progress("HOLD").range)
        assertEquals(legacy.motion, PrismPresets.forVoice(PrismVoice.KEY).single { it.name == legacy.name }.motion)
    }

    @Test
    fun motion_amount_time_and_repeat_survive_save_reload_navigation_and_reset() {
        val root = show()
        val factory = PrismOpeningPresets.all().single { it.name == "Bowed Bloom" }
        loadFactory(factory)
        expandMotion()
        dial("AMOUNT", .36f)
        dial("TIME", 2.3f)
        press("PRISM MOTION REPEAT")
        dial("HOLD", 1f)
        val saved = saveAs("MY MOTION", root)
        assertEquals(factory.motion!!.copy(evolve = .36f, seconds = 2.3f, repeat = true), saved.motion)

        loadFactory(PrismPresets.forVoice(PrismVoice.KEY).single { it.name == "Soft Facets" })
        val personal = compose.onNodeWithText(saved.name).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("No saved motion click action")
        compose.runOnIdle { assertTrue(personal()) }
        pump()
        waitFor("the selected personal motion") { draft(saved.voice) == saved }

        compose.runOnIdle { screenGeneration++ }
        pump()
        waitFor("saved repeat motion after navigation") { draft(saved.voice) == saved }
        expandMotion()
        assertEquals(.36f, progress("AMOUNT").current, 0f)
        assertEquals(2.3f, progress("TIME").current, 0f)
        assertEquals(0f..1f, progress("HOLD").range)
        dial("AMOUNT", .12f)
        dial("TIME", .4f)
        press("RESET PRISM MOTION")
        waitFor("initial motion reset") { draft(saved.voice)?.motion == saved.motion }
        assertEquals(saved, draft(saved.voice))
    }

    @Test
    fun source_preparation_preserves_intervening_edits_and_previews_do_not_replace_the_recipe() {
        show()
        val factory = PrismOpeningPresets.all().single { it.name == "Soft Arrival" }
        loadFactory(factory)
        expandMotion()
        press("CHOOSE MOTION SOURCE")
        val choose = clickAction("MOTION SOURCE Wooden Arrival")
        val body = progressAction("BODY")
        val amount = progressAction("AMOUNT")
        // Source preparation yields off main; these edits occur before its result can
        // return to the main dispatcher, so a stale full-patch assignment would lose them.
        compose.runOnIdle { assertTrue(choose()); assertTrue(body(.23f)); assertTrue(amount(.41f)) }
        pump()
        waitFor("selected source without stale macro overwrite", timeoutMillis = 30_000) {
            val live = draft(factory.voice)
            live?.motion?.sourceId == "tessera-wooden-arrival" && live.macros["BODY"] == .23f && live.motion?.evolve == .41f
        }
        press("CHOOSE MOTION SOURCE")
        press("MOTION SOURCE HOLLOW GROWL")
        waitFor("the latest selected source", timeoutMillis = 30_000) {
            draft(factory.voice)?.motion?.sourceId == "fathom-hollow-growl"
        }
        val current = draft(factory.voice)!!
        assertEquals(.23f, current.macros.getValue("BODY"), 0f)
        assertEquals(.41f, current.motion!!.evolve, 0f)
        assertEquals("HOLLOW GROWL", PrismMotionSources.byId(current.motion!!.sourceId)?.title)
        for (kind in listOf("SOURCE", "STATIC", "MOVING")) {
            press("PREVIEW PRISM $kind")
            waitFor("$kind preview completion", timeoutMillis = 30_000) {
                compose.onAllNodesWithText("PREPARING PREVIEW…", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            assertEquals("$kind preview must not replace the playable recipe", current, draft(factory.voice))
        }
        press("STOP PRISM PREVIEW")
    }
}
