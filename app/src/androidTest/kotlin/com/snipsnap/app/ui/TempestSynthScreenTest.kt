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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.json.JsonValue
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Tempest
import com.snipsnap.synth.TempestPatch
import com.snipsnap.synth.TempestPresets
import com.snipsnap.synth.TempestVoice
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

/** The accepted finite drums enter the picker without silently upgrading recalled render models. */
@RunWith(AndroidJUnit4::class)
class TempestSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "tempest-ui-${System.nanoTime()}").apply { mkdirs() }
    private var visit by mutableIntStateOf(0)

    @After fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(source: TempestPatch? = null) {
        settings["engine"] = "TEMPEST"
        settings["voice"] = source?.voiceName ?: "KICK"
        if (source != null) SynthWork.replace(settings,
            listOf(SynthWork.Working(PadRecipe(source), null)))
        setPhoneContent {
            key(visit) {
                SynthScreen(null, shelf, onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        compose.onNode(hasContentDescription("ENGINE TEMPEST. CHOOSE ENGINE")).assertExists()
    }

    private fun stored(voice: TempestVoice) = SynthDrafts.read(settings)
        .filterIsInstance<TempestPatch>().firstOrNull { it.voice == voice }

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

    @Test fun six_voices_load_their_exact_twenty_two_presets_and_only_real_controls() {
        show()
        assertEquals(22, TempestPresets.all().size)
        for (voice in TempestVoice.entries) {
            activate(voice.name)
            for (macro in Tempest.macrosFor(voice).filterNot { it.name == "HOLD" }) slider(macro.name).assertExists()
            slider("HOLD").assertDoesNotExist()
            if (voice == TempestVoice.KICK || voice == TempestVoice.METAL) slider("DECAY").assertDoesNotExist()
            else slider("DECAY").assertExists()
            for (preset in TempestPresets.forVoice(voice)) {
                activate("PRESET ${preset.name}")
                waitFor("${preset.name} full recipe") { stored(voice) == preset }
                assertEquals(Tempest.defaultModelFor(voice), requireNotNull(stored(voice)).model)
                for ((name, value) in preset.macros.filterKeys { it != "HOLD" }) {
                    assertEquals(value, slider(name).fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current, 1e-6f)
                }
            }
        }
        assertTrue(compose.onAllNodesWithText("FINITE DRUM · 1.20 SECONDS · HOLD IS RESERVED",
            useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertDoesNotExist()
    }

    @Test fun missing_model_legacy_recall_keeps_model_one_through_edit_return_and_save() {
        val original = TempestPatch("ORIGINAL KICK", TempestVoice.KICK,
            Tempest.defaults(TempestVoice.KICK), model = 1)
        val withoutModel = JsonValue.Obj(LinkedHashMap(original.toJsonValue().entries).apply { remove("model") })
        val source = TempestPatch.fromJsonValue(withoutModel) as TempestPatch
        assertEquals(1, source.model)
        show(source)
        waitFor("legacy recipe") { stored(source.voice) == source }
        dial("FILTER", .37f)
        val expected = source.copy(macros = source.macros + ("FILTER" to .37f))
        waitFor("edited original remains model one") { stored(source.voice) == expected }
        compose.runOnIdle { visit++ }
        pump()
        waitFor("return retains original renderer") { stored(source.voice) == expected }
        activate("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY ORIGINAL KICK")
        pump()
        activate("SAVE")
        waitFor("saved legacy recipe") { UserPresets.read(shelf).any { it.name == "MY ORIGINAL KICK" } }
        assertEquals(expected.copy(name = "MY ORIGINAL KICK"),
            UserPresets.read(shelf).single { it.name == "MY ORIGINAL KICK" }.patch)
    }

    @Test fun an_explicit_model_three_metal_recipe_survives_scramble_and_navigation() {
        val source = TempestPatch("EXPLICIT THIRD MODEL", TempestVoice.METAL,
            Tempest.defaults(TempestVoice.METAL), model = 3)
        show(source)
        waitFor("explicit model three") { stored(source.voice) == source }
        activate("RANDOMISE")
        waitFor("scrambled source remains model three") {
            stored(source.voice)?.let { it.model == 3 && it.macros != source.macros } == true
        }
        val scrambled = requireNotNull(stored(source.voice))
        assertEquals(0f, scrambled.macros.getValue("HOLD"), 0f)
        compose.runOnIdle { visit++ }
        pump()
        waitFor("return retains explicit third model") { stored(source.voice) == scrambled }
    }
}
