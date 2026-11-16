package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.BristlePadMaker
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.BristlePatch
import com.snipsnap.synth.BristlePresets
import com.snipsnap.synth.Bristle
import java.io.File
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

/** Real sheet selection and source restoration. Native rendering and shelf admission are checked on the JVM. */
@RunWith(AndroidJUnit4::class)
class BristleSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val roots = mutableListOf<File>()

    @After fun cleanUp() {
        appScope.cancel()
        roots.forEach { it.deleteRecursively() }
    }

    private fun source() = BristlePresets.forVoice(Bristle.Route.B).single().copy(
        name = "EDITED BASS", midi = 32, velocity = .65f,
        macros = Bristle.defaults + ("TUNE" to .75f))

    private fun show(patch: BristlePatch): MutableMap<String, String> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "bristle-sheet-${System.nanoTime()}").apply { mkdirs(); roots += this }
        val settings = mutableMapOf("engine" to "BRISTLE", "voice" to patch.voice.name)
        SynthDrafts.write(settings, listOf(patch))
        setPhoneContent {
            SynthScreen(entry = null, shelfRoot = root, onToast = {}, onKitUpdated = {},
                onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                appScope = appScope, settings = settings)
        }
        pump(16)
        return settings
    }

    private fun press(label: String) {
        val action = requireNotNull(button(label).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle { assertTrue(action()) }
        pump(16)
    }

    /** The sheet has two CANCEL targets (its dimmed backdrop and its button); press the button, drawn last. */
    private fun cancelSheet() {
        val action = requireNotNull(compose.onAllNodes(hasContentDescription("CANCEL") and hasClickAction())
            .fetchSemanticsNodes().last().config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle { assertTrue(action()) }
        pump(16)
    }

    @Test fun finiteEditedRecipeOpensFiniteKeysAndCancelPreservesEverySavedField() {
        val patch = source()
        val settings = show(patch)
        press("MAKE INSTRUMENT")
        val spec = HeldSpec.Bristle(BristlePadMaker.Spec(patch))
        compose.onNodeWithText(spec.instrumentNote!!).assertExists()
        compose.onNodeWithText("FINITE BASS ENVELOPE · THREE TOUCH LAYERS").assertExists()
        button("ATTACK").assertDoesNotExist()
        button("RELEASE").assertDoesNotExist()
        button("PREVIEW").assertExists()
        assertFalse(spec.hasAttack)
        assertFalse(spec.hasRelease)
        assertEquals("Lands in the LIBRARY as Bristle Keys, playable on keyboard.", spec.shelfNote("Bristle Keys"))
        cancelSheet()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<BristlePatch>().single())
    }



    @Test fun bothRoutesKeepTheirControlsAcrossEngineSwitches() {
        val patch = source()
        val settings = show(patch)
        button("ENGINE BRISTLE. CHOOSE ENGINE").assertExists()
        button("B").assertIsSelected()
        compose.onNodeWithContentDescription("GRIT").performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue(it(.81f))
        }
        pump(16)
        press("A")
        button("A").assertIsSelected()
        press("B")
        press("NEXT ENGINE")
        press("PREVIOUS ENGINE")
        // Arriving on an engine always starts on its first voice (SynthScreen.arrive); the saved B
        // draft is reached by choosing B again, which must show B's edit, not A's.
        button("A").assertIsSelected()
        press("B")
        button("B").assertIsSelected()
        val restored = SynthDrafts.read(settings).filterIsInstance<BristlePatch>().single { it.voice == Bristle.Route.B }
        assertEquals(patch.midi, restored.midi)
        assertEquals(patch.velocity, restored.velocity)
        assertEquals(patch.model, restored.model)
        assertEquals(.81f, restored.macros.getValue("GRIT"))
        for (control in Bristle.defaults.keys) button(control).assertExists()
        press("MAKE INSTRUMENT")
        compose.onNodeWithText("FINITE BASS ENVELOPE · THREE TOUCH LAYERS").assertExists()
        cancelSheet()
    }

    @Test fun compressionAndFuzzKeepTheCompleteDraftAcrossVoiceAndEngineChanges() {
        val patch = source()
        val settings = show(patch)
        for ((label, value) in listOf("COMP" to .63f, "FUZZ" to .71f)) {
            compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.SetProgress) {
                assertTrue(it(value))
            }
            pump(16)
        }
        press("A")
        press("B")
        press("NEXT ENGINE")
        press("PREVIOUS ENGINE")
        val saved = SynthDrafts.read(settings).filterIsInstance<BristlePatch>().single { it.voice == patch.voice }
        assertEquals(patch.name, saved.name)
        assertEquals(patch.midi, saved.midi)
        assertEquals(patch.velocity, saved.velocity)
        assertEquals(patch.macros, saved.macros)
        assertEquals(.63f, saved.effects.compress)
        assertEquals(.71f, saved.effects.fuzz)
        press("MAKE INSTRUMENT")
        button("PREVIEW").assertExists()
        cancelSheet()
        assertEquals(saved, SynthDrafts.read(settings).filterIsInstance<BristlePatch>().single { it.voice == patch.voice })
    }

    @Test fun zeroVelocityKeepsTheRecipeAndExplainsWhyAnInstrumentCannotBeMade() {
        val patch = source().copy(velocity = 0f)
        val settings = show(patch)
        button("MAKE INSTRUMENT").assertIsNotEnabled()
        compose.onNodeWithText(BristlePadMaker.UNSUPPORTED_NOTE).assertExists()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<BristlePatch>().single())
    }

    @Test fun sustainIsAnInstrumentChoiceWithReleaseAndDoesNotRewriteFiniteDrafts() {
        val patch = source()
        val settings = show(patch)
        press("BRISTLE SUSTAIN KEYS")
        assertEquals("true", settings["bristle.sustain"])
        press("MAKE INSTRUMENT")
        button("RELEASE").assertExists()
        val spec = HeldSpec.Bristle(BristlePadMaker.Spec(patch, held = true))
        assertTrue(spec.hasRelease)
        compose.onNodeWithText(spec.instrumentNote!!).assertExists()
        cancelSheet()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<BristlePatch>().single())
        press("BRISTLE FINITE KEYS")
        assertEquals("false", settings["bristle.sustain"])
        press("MAKE INSTRUMENT")
        button("RELEASE").assertDoesNotExist()
        compose.onNodeWithText("FINITE BASS ENVELOPE · THREE TOUCH LAYERS").assertExists()
        cancelSheet()
    }
    @Test fun roundRobinIsOptInAndDoesNotChangeTheSavedSource() {
        val patch = source()
        val settings = show(patch)
        press("BRISTLE ROUND ROBIN")
        assertEquals("true", settings["bristle.roundrobin"])
        press("MAKE INSTRUMENT")
        val spec = HeldSpec.Bristle(BristlePadMaker.Spec(patch, takes = 2))
        assertEquals(6, spec.progressPerZone)
        compose.onNodeWithText(spec.instrumentNote!!).assertExists()
        cancelSheet()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<BristlePatch>().single())
        press("BRISTLE SINGLE TAKE")
        assertEquals("false", settings["bristle.roundrobin"])
    }
    @Test fun sixSourceVoicesOpenAlongsideTheApprovedRoutes() {
        show(source())
        for (voice in Bristle.musicalVoices) {
            press(voice.name)
            button(voice.name).assertIsSelected()
            for (macro in Bristle.defaults.keys) button(macro).assertExists()
            press("MAKE INSTRUMENT")
            button("PREVIEW").assertExists()
            cancelSheet()
        }
        press("A"); button("A").assertIsSelected()
        press("B"); button("B").assertIsSelected()
    }
}
