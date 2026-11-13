package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.MurmurBody
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurPresets
import com.snipsnap.synth.MurmurSelectedPlucks
import com.snipsnap.synth.MurmurVoice
import com.snipsnap.synth.PadRecipe
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

/** Actual picker/working-recipe glue; no raw-reference gain or completed keygroup claim. */
@RunWith(AndroidJUnit4::class)
class MurmurKeptSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "murmur-kept-ui-${System.nanoTime()}").apply { mkdirs() }
    private var visit by mutableIntStateOf(0)

    @After fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(source: MurmurPatch? = null, factoryName: String? = null, yours: MurmurPatch? = null) {
        settings["engine"] = "MURMUR"
        settings["voice"] = "PLUCK"
        if (source != null) SynthWork.replace(settings, listOf(SynthWork.Working(PadRecipe(source), null)))
        if (factoryName != null) settings["preset.MURMUR.PLUCK"] = factoryName
        if (yours != null) {
            UserPresets.save(shelf, yours, 1L)
            settings["preset.MURMUR.PLUCK"] = yours.name
        }
        setPhoneContent {
            key(visit) {
                SynthScreen(null, shelf, onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
    }

    private fun draft() = SynthDrafts.read(settings).filterIsInstance<MurmurPatch>()
        .singleOrNull { it.voice == MurmurVoice.PLUCK }

    private fun clickAction(label: String): () -> Boolean = compose.onNode(
        hasContentDescription(label) and hasClickAction(), useUnmergedTree = true,
    ).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action ?: error("No click for $label")

    private fun activate(label: String) {
        val action = clickAction(label)
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun slider(label: String) = compose.onNode(hasContentDescription(label) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress), useUnmergedTree = true)

    private fun progressAction(label: String): (Float) -> Boolean = slider(label).fetchSemanticsNode()
        .config.getOrNull(SemanticsActions.SetProgress)?.action ?: error("No progress for $label")

    private fun progress(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current

    private fun kept(name: String) = MurmurPresets.forVoice(MurmurVoice.PLUCK).single { it.name == name }

    @Test fun unique_aliases_load_complete_recipes_and_name_only_hold_restore_keeps_its_macros() {
        val held = kept("HOLLOW WIRE HOLD")
        show(factoryName = held.name)
        waitFor("name-only held factory recipe") { draft() == held }
        assertRoot(held.midi)
        assertEquals(1f, progress("HOLD"), 0f)
        assertEquals(held.macros.getValue("MOUTH"), progress("MOUTH"), 0f)
        assertEquals(held.velocity, progress("VELOCITY"), 0f)
        assertEquals(45, MurmurPresets.all().size)
        assertEquals(45, MurmurPresets.all().map { it.name }.distinct().size)
        assertEquals(42, MurmurPresets.original().size + MurmurPresets.selected().size)
        for (name in listOf("HOLLOW WIRE NOTE", "HOLLOW WIRE HOLD", "STEADY ROOT C2")) {
            val expected = kept(name)
            activate("PRESET $name")
            waitFor("complete $name") { draft() == expected }
            assertEquals(4, requireNotNull(draft()).config.model)
            for ((macro, value) in expected.macros) assertEquals(value, progress(macro), 0f)
            compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertIsEnabled()
        }
        assertEquals(MurmurBody.STEADY_ROOT_V1, requireNotNull(draft()).config.body)
        rootTo(54)
        assertRoot(54)
        // The shared range exposes the real upper bound, rather than changing bodies above it.
        assertEquals(54, requireNotNull(draft()).midi)
        assertEquals(MurmurBody.STEADY_ROOT_V1, requireNotNull(draft()).config.body)
        compose.onNode(hasContentDescription("ROOT UP"), useUnmergedTree = true).assertIsNotEnabled()
        compose.onNode(hasContentDescription("ROOT OCTAVE UP"), useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test fun same_frame_preset_root_touch_macro_and_save_use_the_new_body_and_navigation_keeps_last_edit() {
        val initial = MurmurSelectedPlucks.shortAnswerRootSupported()
        show(source = initial)
        waitFor("source-bound C2 draft") { draft() == initial }
        val chooseWire = clickAction("PRESET HOLLOW WIRE NOTE")
        val moveRoot = clickAction("ROOT UP")
        val moveTouch = progressAction("VELOCITY")
        val moveMouth = progressAction("MOUTH")
        activate("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY KEPT WIRE")
        pump()
        val save = clickAction("SAVE")
        compose.runOnIdle {
            // These callbacks come from the C2 composition. None may copy that old body
            // over the newly selected Hollow Wire recipe or undo a preceding native edit.
            assertTrue(chooseWire.invoke())
            assertTrue(moveRoot.invoke())
            assertTrue(moveTouch.invoke(.37f))
            assertTrue(moveMouth.invoke(.51f))
            assertTrue(save.invoke())
        }
        pump()
        val source = kept("HOLLOW WIRE NOTE")
        val expected = source.copy(name = "MY KEPT WIRE", midi = 37, velocity = .37f,
            macros = source.macros + ("MOUTH" to .51f))
        waitFor("saved complete same-frame Wire") { UserPresets.read(shelf).any { it.name == expected.name } }
        assertEquals(expected, UserPresets.read(shelf).single { it.name == expected.name }.patch)
        assertEquals(MurmurBody.HOLLOW_WIRE_V1, requireNotNull(draft()).config.body)
        val finalMacro = progressAction("EDGE")
        compose.runOnIdle {
            assertTrue(finalMacro.invoke(.27f))
            visit++
        }
        pump()
        val returned = requireNotNull(draft())
        assertEquals(expected.copy(name = returned.name,
            macros = expected.macros + ("EDGE" to .27f)), returned)
        assertFalse(settings.containsKey("preset.MURMUR.PLUCK"))
        assertRoot(37)
        assertEquals(.37f, progress("VELOCITY"), 0f)
        assertEquals(.51f, progress("MOUTH"), 0f)
        assertEquals(.27f, progress("EDGE"), 0f)
    }

    @Test fun full_draft_and_delayed_yours_restore_keep_the_saved_controls_without_a_macro_bag() {
        val source = MurmurSelectedPlucks.openStringHollowWire().copy(name = "MY SAVED WIRE", midi = 72,
            velocity = .48f, macros = MurmurSelectedPlucks.openStringHollowWire().macros + ("MOUTH" to .24f))
        show(yours = source)
        waitFor("complete YOURS Wire without macro entries") { draft() == source }
        assertRoot(72)
        assertEquals(1f, progress("HOLD"), 0f)
        assertEquals(.24f, progress("MOUTH"), 0f)
        assertEquals(.48f, progress("VELOCITY"), 0f)
        compose.runOnIdle {
            // Simulate stale separate controls from before the complete draft was written.
            settings["macro.MURMUR.PLUCK.HOLD"] = "0.0"
            settings["macro.MURMUR.PLUCK.MOUTH"] = "0.91"
            visit++
        }
        pump()
        waitFor("full draft beats stale macro bag") { draft() == source }
        assertEquals(1f, progress("HOLD"), 0f)
        assertEquals(.24f, progress("MOUTH"), 0f)
        assertEquals(source.config, requireNotNull(draft()).config)
    }

    @Test fun recalled_legacy_pluck_retains_the_existing_generic_instrument_route_and_model() {
        val source = MurmurPresets.original().single { it.name == "OPEN STRING" }
        show(source = source)
        waitFor("legacy pluck source") { draft() == source }
        assertEquals(2, source.config.model)
        compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertIsEnabled()
        val mouth = progressAction("MOUTH")
        compose.runOnIdle {
            assertTrue(mouth.invoke(.43f))
            visit++
        }
        pump()
        val returned = requireNotNull(draft())
        assertEquals(source.copy(name = returned.name, macros = source.macros + ("MOUTH" to .43f)), returned)
        assertEquals(MurmurBody.LEGACY, returned.config.body)
        assertEquals(2, returned.config.model)
        compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertIsEnabled()
    }
}
