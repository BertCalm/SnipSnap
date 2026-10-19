package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmGesture
import com.snipsnap.synth.OhmPatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the real editor bindings and recreate its remember-state from the MAKE bag. */
@RunWith(AndroidJUnit4::class)
class SynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelfRoot = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "ohm-editor-${System.nanoTime()}").apply { mkdirs() }
    private var generation by mutableStateOf(0)
    private val settings = mutableMapOf("engine" to "OHM", "voice" to "STRING")

    @After
    fun cleanUp() {
        appScope.cancel()
        shelfRoot.deleteRecursively()
    }

    private fun show() = setPhoneContent {
        key(generation) {
            SynthScreen(entry = null, shelfRoot = shelfRoot, onToast = {}, onKitUpdated = {},
                onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                appScope = appScope, settings = settings)
        }
    }

    // Invoke the same accessibility actions as TalkBack. No animated scrolling is
    // needed on ComposeScreenTest's deliberately hand-driven frame clock.
    private fun adjust(label: String, value: Float) {
        button(label).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        pump()
    }

    private fun progress(label: String) =
        button(label).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test
    fun legacyEncounterRetainsItsIdentityThroughEditsAndNavigation() {
        val source = OhmPatch("MY FAMILY", Ohm.defaults() + ("LENGTH" to .1f), midi = 57,
            velocity = .65f, seed = -905, gesture = OhmGesture.GATHER, model = 1)
        settings["patch.OHM.STRING"] = source.toJsonText()
        settings["preset.OHM.STRING"] = "Gather"
        show()
        button("OHM GATHER").assertIsSelected()
        assertEquals((57 - 48) / 24f, progress("NOTE"), .0001f)

        adjust("NOTE", (64 - 48) / 24f)
        adjust("VELOCITY", .35f)
        adjust("COMMUNE", .73f)
        button("OHM STEADY").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        val expected = source.copy(midi = 64, velocity = .35f, gesture = OhmGesture.STEADY,
            macros = source.macros + ("COMMUNE" to .73f))
        compose.runOnIdle {
            assertEquals(expected, OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING")))
            assertFalse(settings.containsKey("preset.OHM.STRING"))
            generation++
        }
        pump()
        button("OHM STEADY").assertIsSelected()
        assertEquals((64 - 48) / 24f, progress("NOTE"), .0001f)
        assertEquals(.35f, progress("VELOCITY"), .0001f)
        assertEquals(.73f, progress("COMMUNE"), .0001f)
        compose.runOnIdle {
            assertEquals(expected, OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING")))
        }
    }

    @Test
    fun foreignRecipeInTheMakeBagFallsBackToANewModelTwoEncounter() {
        settings["patch.OHM.STRING"] = """{"engine":"THUMP","version":1,"name":"OTHER","voice":"KICK","macros":{}}"""
        show()
        button("OHM STEADY").assertIsSelected()
        adjust("VELOCITY", .4f)
        compose.runOnIdle {
            val patch = OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING"))
            assertEquals(2, patch.model)
            assertEquals(60, patch.midi)
            assertEquals(.4f, patch.velocity)
            assertEquals(OhmGesture.STEADY, patch.gesture)
        }
    }
}
