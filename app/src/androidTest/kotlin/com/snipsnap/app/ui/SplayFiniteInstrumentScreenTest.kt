package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SplayFinitePadMaker
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.SplayFactoryPresets
import com.snipsnap.synth.SplayPatch
import com.snipsnap.synth.SplayVoice
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

/** Real finite sheet selection and recipe preservation; source audio and admission are checked on the JVM. */
@RunWith(AndroidJUnit4::class)
class SplayFiniteInstrumentScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val roots = mutableListOf<File>()

    @After fun cleanUp() {
        appScope.cancel()
        roots.forEach { it.deleteRecursively() }
    }

    private fun source() = SplayFactoryPresets.all().first().copy(
        name = "EDITED OLD TONGUE", model = 1, midi = 55, velocity = .37f, seed = Int.MIN_VALUE,
        macros = SplayFactoryPresets.all().first().macros + ("CONTACT" to .29f))

    private fun show(patch: SplayPatch): MutableMap<String, String> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "splay-finite-sheet-${System.nanoTime()}").apply { mkdirs(); roots += this }
        val settings = ConcurrentHashMap<String, String>().apply {
            put("engine", "SPLAY")
            put("voice", patch.voice.name)
        }
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

    @Test fun editedLegacyPerformanceOpensFiniteKeysAndCancelPreservesEverySavedField() {
        val patch = source()
        val settings = show(patch)
        button("MAKE INSTRUMENT").assertIsEnabled()
        press("MAKE INSTRUMENT")
        val spec = SplayFiniteHeldSpec(SplayFinitePadMaker.Spec(patch))
        compose.onNodeWithText(spec.instrumentNote).assertExists()
        compose.onNodeWithText("FINITE SOURCE · GAIN-ONLY VELOCITY").assertExists()
        button("ATTACK").assertDoesNotExist()
        button("RELEASE").assertDoesNotExist()
        button("PREVIEW").assertExists()
        assertFalse(spec.hasAttack)
        assertFalse(spec.hasRelease)
        assertEquals(3, spec.progressPerZone)
        assertEquals("LANDS IN THE LIBRARY AS SPLAY KEYS, PLAYABLE ON KEYS.", spec.shelfNote("Splay Keys"))
        cancelSheet()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<SplayPatch>().single())
    }

    @Test fun eachAcceptedFactoryChoiceOpensFiniteInstrumentWithoutChangingItsOneVoiceRecipe() {
        val factories = SplayFactoryPresets.all()
        val settings = show(factories.first())
        assertEquals(5, factories.size)
        assertEquals(setOf(SplayVoice.TONGUE), factories.map { it.voice }.toSet())
        for (patch in factories) {
            press("PRESET ${patch.name}")
            waitFor("factory SPLAY recipe ${patch.name}") {
                SynthDrafts.read(settings).filterIsInstance<SplayPatch>().singleOrNull() == patch
            }
            button("MAKE INSTRUMENT").assertIsEnabled()
            press("MAKE INSTRUMENT")
            compose.onNodeWithText(SplayFinitePadMaker.instrumentNote(SplayFinitePadMaker.Spec(patch))).assertExists()
            button("RELEASE").assertDoesNotExist()
            cancelSheet()
            assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<SplayPatch>().single())
        }
    }

    @Test fun zeroExcitationExplainsUnavailableInstrumentAndKeepsTheSavedPerformance() {
        val patch = source().copy(velocity = 0f)
        val settings = show(patch)
        button("MAKE INSTRUMENT").assertIsNotEnabled()
        compose.onNodeWithText(SplayFinitePadMaker.UNSUPPORTED_NOTE).assertExists()
        assertEquals(patch, SynthDrafts.read(settings).filterIsInstance<SplayPatch>().single())
    }
}
