package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.WickPadMaker
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import com.snipsnap.synth.WickPresets
import com.snipsnap.synth.WickVoice
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

/** Real SYNTH sheet routing and finite wording; source rendering and shelf admission have JVM tests. */
@RunWith(AndroidJUnit4::class)
class WickFiniteInstrumentScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val roots = mutableListOf<File>()

    @After fun cleanUp() {
        appScope.cancel()
        roots.forEach { it.deleteRecursively() }
    }

    private fun source() = WickPresets.forVoice(WickVoice.RATTLE).first().copy(
        midi = 55, velocity = .37f, rattleSource = Wick.RattleSource.HOLLOW_REED)

    private fun show(patch: WickPatch): MutableMap<String, String> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "wick-finite-sheet-${System.nanoTime()}").apply { mkdirs(); roots += this }
        val settings = mutableMapOf("engine" to "WICK", "voice" to patch.voice.name)
        SynthDrafts.write(settings, listOf(patch))
        setPhoneContent {
            SynthScreen(entry = null, shelfRoot = root, onToast = {}, onKitUpdated = {},
                onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                appScope = appScope, settings = settings)
        }
        return settings
    }

    @Test fun finiteSourceOffersKeysWithoutReplacingItsEnvelopeOrPromisingSustain() {
        val patch = source()
        show(patch)
        val action = requireNotNull(button("MAKE INSTRUMENT").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle { assertTrue(action()) }
        pump(16)
        val spec = HeldSpec.Wick(WickPadMaker.Spec(patch))
        compose.onNodeWithText(spec.instrumentNote!!).assertExists()
        compose.onNodeWithText("FINITE SOURCE GESTURE · THREE TOUCH LAYERS").assertExists()
        button("RELEASE").assertDoesNotExist()
        button("PREVIEW").assertExists()
        assertFalse(spec.hasAttack)
        assertFalse(spec.hasRelease)
        val shelfNote = spec.shelfNote("Wick Keys")
        assertEquals("Lands in the LIBRARY as Wick Keys, playable on keyboard.", shelfNote)
        assertFalse(shelfNote.contains("Every key holds while it is down"))
    }

    @Test fun heldLoopSourceExplainsWhyFiniteKeysAreUnavailableAndKeepsTheSavedLoop() {
        val held = source().copy(macros = source().macros + ("HOLD" to 1f))
        val settings = show(held)
        button("MAKE INSTRUMENT").assertIsNotEnabled()
        compose.onNodeWithText(WickPadMaker.UNSUPPORTED_NOTE).assertExists()
        val restored = SynthDrafts.read(settings).filterIsInstance<WickPatch>().single()
        assertEquals(held, restored)
        assertEquals(1f, restored.macros.getValue("HOLD"))
    }
}
