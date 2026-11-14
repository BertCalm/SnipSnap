package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.MeridianFinitePadMaker
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.MeridianVoice
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
class MeridianFiniteInstrumentScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val roots = mutableListOf<File>()

    @After fun cleanUp() {
        appScope.cancel()
        roots.forEach { it.deleteRecursively() }
    }

    private fun source() = MeridianPresets.forVoice(MeridianVoice.CHIME).first().copy(
        name = "EDITED FINITE CHIME", midi = 55, velocity = .37f, seedContext = 813,
        macros = MeridianPresets.forVoice(MeridianVoice.CHIME).first().macros + ("TUNE" to .75f))

    private fun show(patch: MeridianPatch): MutableMap<String, String> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "meridian-finite-sheet-${System.nanoTime()}").apply { mkdirs(); roots += this }
        val settings = mutableMapOf("engine" to "MERIDIAN", "voice" to patch.voice.name)
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
        val spec = HeldSpec.MeridianFinite(MeridianFinitePadMaker.Spec(patch))
        compose.onNodeWithText(spec.instrumentNote!!).assertExists()
        compose.onNodeWithText("FINITE SOURCE GESTURE · THREE TOUCH LAYERS").assertExists()
        button("ATTACK").assertDoesNotExist()
        button("RELEASE").assertDoesNotExist()
        button("PREVIEW").assertExists()
        assertFalse(spec.hasAttack)
        assertFalse(spec.hasRelease)
        assertEquals("Lands in the LIBRARY as Meridian Keys, playable on keyboard.", spec.shelfNote("Meridian Keys"))
        cancelSheet()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<MeridianPatch>().single())
    }

    @Test fun originalModelOneHoldStillUsesFiniteKeysWithoutUpgradingItsRenderer() {
        val source = MeridianPresets.forVoice(MeridianVoice.SWEEP).first()
        val patch = source.copy(name = "SAVED ORIGINAL SWING", model = 1, midi = 51,
            velocity = .61f, seedContext = 93, macros = source.macros + ("HOLD" to 1f))
        val settings = show(patch)
        press("MAKE INSTRUMENT")
        compose.onNodeWithText("FINITE SOURCE GESTURE · THREE TOUCH LAYERS").assertExists()
        button("RELEASE").assertDoesNotExist()
        cancelSheet()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<MeridianPatch>().single())
    }

    @Test fun currentHoldKeepsItsExistingHeldMakerAndReleaseControl() {
        val patch = source().copy(macros = source().macros + ("HOLD" to 1f))
        val settings = show(patch)
        press("MAKE INSTRUMENT")
        compose.onNodeWithText("Fixed-alignment swing · C2–C5 · soft, medium and hard touch.").assertExists()
        button("RELEASE").assertExists()
        compose.onNodeWithText("FINITE SOURCE GESTURE · THREE TOUCH LAYERS").assertDoesNotExist()
        cancelSheet()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<MeridianPatch>().single())
    }

    @Test fun zeroExcitationExplainsWhyFiniteKeysAreUnavailableWithoutChangingTheRecipe() {
        val patch = source().copy(velocity = 0f)
        val settings = show(patch)
        button("MAKE INSTRUMENT").assertIsNotEnabled()
        compose.onNodeWithText(MeridianFinitePadMaker.UNSUPPORTED_NOTE).assertExists()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<MeridianPatch>().single())
    }
}
