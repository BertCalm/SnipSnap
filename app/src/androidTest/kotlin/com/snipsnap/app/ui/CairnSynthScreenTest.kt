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
import com.snipsnap.synth.Cairn
import com.snipsnap.synth.CairnPatch
import com.snipsnap.synth.CairnPresets
import com.snipsnap.synth.CairnVoice
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
 * CAIRN's six stone voices on the SYNTH picker: the factory roster, every control, the shared
 * ROOT row, RANDOMISE, and a kept SING recipe's saved model surviving edit, return and SAVE.
 * Slider and button semantics are invoked directly (ComposeScreenTest). Listening stays a phone check.
 */
@RunWith(AndroidJUnit4::class)
class CairnSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "cairn-ui-${System.nanoTime()}").apply { mkdirs() }
    private var visit by mutableIntStateOf(0)

    @After fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(voice: CairnVoice = CairnVoice.KNOCK) {
        settings["engine"] = "CAIRN"
        settings["voice"] = voice.name
        setPhoneContent {
            key(visit) {
                SynthScreen(null, shelf, onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        compose.onNode(hasContentDescription("ENGINE CAIRN. CHOOSE ENGINE")).assertExists()
    }

    private fun stored(voice: CairnVoice) = SynthDrafts.read(settings)
        .filterIsInstance<CairnPatch>().firstOrNull { it.voice == voice }

    private fun activate(label: String) {
        val action = compose.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
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

    @Test fun cairn_closes_the_engine_ring_and_names_itself_in_full() {
        show()
        assertReadsInFull("CAIRN ▾")
        activate("NEXT ENGINE")
        compose.onNode(hasContentDescription("ENGINE LATTICE. CHOOSE ENGINE")).assertExists()
        activate("PREVIOUS ENGINE")
        compose.onNode(hasContentDescription("ENGINE CAIRN. CHOOSE ENGINE")).assertExists()
    }

    @Test fun six_voices_load_their_twenty_four_presets_with_every_control_and_no_instrument_maker() {
        show()
        assertEquals(24, CairnPresets.all().size)
        for (voice in CairnVoice.entries) {
            activate(voice.name)
            for (macro in Cairn.macrosFor(voice)) slider(macro.name).assertExists()
            slider("ROOT").assertExists()
            for (preset in CairnPresets.forVoice(voice)) {
                activate("PRESET ${preset.name}")
                waitFor("${preset.name} full recipe") { stored(voice) == preset }
                assertEquals(preset.model, requireNotNull(stored(voice)).model)
                for ((name, value) in preset.macros) assertEquals(value, sliderValue(name), 1e-6f)
            }
        }
        compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertDoesNotExist()
    }

    @Test fun a_kept_sing_recipe_keeps_its_saved_model_through_edit_return_save_and_recall() {
        show(CairnVoice.SING)
        val kept = CairnPresets.forVoice(CairnVoice.SING).first { it.name == "SING EARLIER" }
        assertEquals(4, kept.model)
        activate("PRESET SING EARLIER")
        waitFor("kept SING recipe") { stored(CairnVoice.SING) == kept }
        dial("FRICTION", .4f)
        val expected = kept.copy(macros = kept.macros + ("FRICTION" to .4f))
        waitFor("edited recipe keeps model four") { stored(CairnVoice.SING) == expected }
        compose.runOnIdle { visit++ }
        pump()
        waitFor("return keeps the edited model-four recipe") { stored(CairnVoice.SING) == expected }
        activate("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY EARLIER")
        pump()
        activate("SAVE")
        waitFor("saved model-four recipe") { UserPresets.read(shelf).any { it.name == "MY EARLIER" } }
        val saved = UserPresets.read(shelf).single { it.name == "MY EARLIER" }.patch
        assertEquals(expected.copy(name = "MY EARLIER"), saved)
        activate("PRESET SING MOVING")
        waitFor("a different kept recipe") { stored(CairnVoice.SING)?.model == 5 }
        val chip = compose.onNode(yourPreset("MY EARLIER"), useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)!!.action!!
        compose.runOnIdle { assertTrue(chip.invoke()) }
        pump()
        waitFor("the saved recipe comes back with its model") { stored(CairnVoice.SING) == saved }
    }

    @Test fun root_and_randomise_keep_the_saved_model_and_stop_hold_short_of_the_loop() {
        show(CairnVoice.KNOCK)
        val knock = CairnPresets.forVoice(CairnVoice.KNOCK).first()
        activate("PRESET ${knock.name}")
        waitFor("factory KNOCK") { stored(CairnVoice.KNOCK) == knock }
        dial("ROOT", 1f)
        waitFor("root moved to C5") { stored(CairnVoice.KNOCK)?.midi == Cairn.MAX_MIDI }
        activate("RANDOMISE")
        waitFor("scrambled KNOCK keeps root and model") {
            stored(CairnVoice.KNOCK)?.let { it.midi == Cairn.MAX_MIDI && it.model == knock.model && it.macros != knock.macros } == true
        }
        val scrambled = requireNotNull(stored(CairnVoice.KNOCK))
        assertTrue(!Cairn.isLoop(scrambled.macros))
        compose.runOnIdle { visit++ }
        pump()
        waitFor("return keeps the scrambled recipe") { stored(CairnVoice.KNOCK) == scrambled }
    }
}
