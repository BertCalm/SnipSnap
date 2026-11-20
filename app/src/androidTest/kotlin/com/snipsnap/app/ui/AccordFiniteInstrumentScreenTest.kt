package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.AccordPadMaker
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.AccordPatch
import com.snipsnap.synth.AccordPresets
import com.snipsnap.synth.AccordVoice
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
class AccordFiniteInstrumentScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val roots = mutableListOf<File>()

    @After fun cleanUp() {
        appScope.cancel()
        roots.forEach { it.deleteRecursively() }
    }

    private fun source() = AccordPresets.forVoice(AccordVoice.REED).first().copy(midi = 55, velocity = .37f)

    private fun show(patch: AccordPatch): MutableMap<String, String> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "accord-finite-sheet-${System.nanoTime()}").apply { mkdirs(); roots += this }
        val settings = mutableMapOf("engine" to "ACCORD", "voice" to patch.voice.name)
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
        val spec = HeldSpec.Accord(AccordPadMaker.Spec(patch))
        compose.onNodeWithText(spec.instrumentNote!!).assertExists()
        compose.onNodeWithText("FINITE SOURCE GESTURE · THREE TOUCH LAYERS · TWO TAKES").assertExists()
        button("RELEASE").assertDoesNotExist()
        button("PREVIEW").assertExists()
        assertFalse(spec.hasAttack)
        assertFalse(spec.hasRelease)
        assertEquals(6, spec.progressPerZone)
        val shelfNote = spec.shelfNote("Accord Keys")
        assertEquals("Lands in the LIBRARY as Accord Keys, playable on keyboard.", shelfNote)
        assertFalse(shelfNote.contains("Every key holds while it is down"))
    }
}
