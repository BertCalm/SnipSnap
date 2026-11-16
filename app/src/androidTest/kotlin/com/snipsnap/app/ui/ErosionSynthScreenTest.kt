package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Erosion
import com.snipsnap.synth.ErosionPatch
import com.snipsnap.synth.ErosionPresets
import com.snipsnap.synth.ErosionVoice
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

/**
 * EROSION's three voices on the SYNTH picker: the eight accepted recipes, every control, and the
 * YOURS shelf. A saved recipe keeps its native touch (velocity sits outside the macro map) through
 * recall, edit, a rebuilt screen and another SAVE. Presets are chosen by name, never by position.
 * Slider and button semantics are invoked directly (ComposeScreenTest). Listening stays a phone check.
 */
@RunWith(AndroidJUnit4::class)
class ErosionSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "erosion-ui-${System.nanoTime()}").apply { mkdirs() }
    private var visit by mutableIntStateOf(0)

    @After fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(voice: ErosionVoice = ErosionVoice.ARC) {
        settings["engine"] = "EROSION"
        settings["voice"] = voice.name
        setPhoneContent {
            key(visit) {
                SynthScreen(null, shelf, onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        compose.onNode(hasContentDescription("ENGINE EROSION. CHOOSE ENGINE")).assertExists()
    }

    private fun stored(voice: ErosionVoice) = SynthDrafts.read(settings)
        .filterIsInstance<ErosionPatch>().firstOrNull { it.voice == voice }

    private fun factory(name: String) = ErosionPresets.all().single { it.name == name }

    private fun activate(label: String) {
        val action = compose.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    /** A voice chip is the only selectable node with the voice's bare name; SCAR is also a preset, spoken "PRESET SCAR". */
    private fun chooseVoice(voice: ErosionVoice) {
        val action = compose.onNode(hasContentDescription(voice.name) and hasClickAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no voice chip ${voice.name}")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun slider(label: String) = compose.onNode(hasContentDescription(label) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    private fun dial(label: String, value: Float) {
        val action = slider(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("no slider for $label")
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    private fun sliderValue(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current

    /** User-preset chips carry their name on the OnClick label, not a content description. */
    private fun yourPreset(name: String) = SemanticsMatcher("YOUR PRESET $name") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "YOUR PRESET $name"
    }

    private fun waitForYourPreset(name: String) = waitFor("$name on the YOURS strip") {
        compose.onAllNodes(yourPreset(name), useUnmergedTree = true).fetchSemanticsNodes().size == 1
    }

    private fun recallYourPreset(name: String) {
        waitForYourPreset(name)
        val chip = compose.onNode(yourPreset(name), useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)!!.action!!
        compose.runOnIdle { assertTrue(chip.invoke()) }
        pump()
    }

    private fun saveAs(name: String) {
        activate("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        pump()
        activate("SAVE")
        waitFor("saved $name") { UserPresets.read(shelf).any { it.name == name } }
        // The file is on disk once UserPresets.read sees it; the chip only after the save's re-read lands.
        waitForYourPreset(name)
    }

    @Test fun erosion_sits_between_wick_and_rivet_and_names_itself_in_full() {
        show()
        assertReadsInFull("EROSION ▾")
        activate("NEXT ENGINE")
        compose.onNode(hasContentDescription("ENGINE RIVET. CHOOSE ENGINE")).assertExists()
        activate("PREVIOUS ENGINE")
        compose.onNode(hasContentDescription("ENGINE EROSION. CHOOSE ENGINE")).assertExists()
    }

    @Test fun three_voices_load_the_eight_accepted_recipes_with_every_control() {
        show()
        assertEquals(listOf("THUD", "CHIP", "RASP", "WIRE", "DUST", "DROPS", "SPLINTER", "SCAR"),
            ErosionPresets.all().map { it.name })
        for (voice in ErosionVoice.entries) {
            chooseVoice(voice)
            for (macro in Erosion.macrosFor(voice)) slider(macro.name).assertExists()
            slider("HOLD").assertDoesNotExist()
            for (preset in ErosionPresets.forVoice(voice)) {
                activate("PRESET ${preset.name}")
                waitFor("${preset.name} full recipe") { stored(voice) == preset }
                assertEquals(1f, requireNotNull(stored(voice)).velocity, 0f)
                for ((name, value) in preset.macros) assertEquals(value, sliderValue(name), 1e-6f)
            }
        }
    }

    @Test fun an_edited_accepted_recipe_survives_a_rebuild_saves_and_comes_back_from_the_shelf() {
        show(ErosionVoice.ARC)
        val wire = factory("WIRE")
        activate("PRESET WIRE")
        waitFor("factory WIRE") { stored(ErosionVoice.ARC) == wire }
        dial("GRAIN", .4f)
        val expected = wire.copy(macros = wire.macros + ("GRAIN" to .4f))
        waitFor("edited WIRE") { stored(ErosionVoice.ARC) == expected }
        compose.runOnIdle { visit++ }
        pump()
        waitFor("return keeps the edited recipe") { stored(ErosionVoice.ARC) == expected }
        assertEquals(.4f, sliderValue("GRAIN"), 1e-6f)
        saveAs("MY WIRE")
        val saved = UserPresets.read(shelf).single { it.name == "MY WIRE" }.patch
        assertEquals(expected.copy(name = "MY WIRE"), saved)
        activate("PRESET DUST")
        waitFor("a different factory recipe") { stored(ErosionVoice.ARC) == factory("DUST") }
        recallYourPreset("MY WIRE")
        waitFor("the saved recipe comes back") { stored(ErosionVoice.ARC) == saved }
        assertEquals(.4f, sliderValue("GRAIN"), 1e-6f)
    }

    @Test fun a_shelved_soft_touch_keeps_its_velocity_through_recall_edit_and_resave() {
        val drops = factory("DROPS")
        val soft = drops.copy(name = "MY SOFT DROPS", velocity = .35f)
        UserPresets.save(shelf, soft, System.currentTimeMillis())
        show(ErosionVoice.CRATER)
        recallYourPreset("MY SOFT DROPS")
        waitFor("soft DROPS recalled whole") { stored(ErosionVoice.CRATER) == soft }
        dial("SLAG", .5f)
        val edited = soft.copy(macros = soft.macros + ("SLAG" to .5f))
        waitFor("edit keeps the soft touch") { stored(ErosionVoice.CRATER) == edited }
        saveAs("SOFTER DROPS")
        val resaved = UserPresets.read(shelf).single { it.name == "SOFTER DROPS" }.patch as ErosionPatch
        assertEquals(edited.copy(name = "SOFTER DROPS"), resaved)
        assertEquals(.35f.toRawBits(), resaved.velocity.toRawBits())
    }
}
