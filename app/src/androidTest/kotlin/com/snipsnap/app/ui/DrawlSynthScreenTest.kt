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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.DrawlPadMaker
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.Drawl
import com.snipsnap.synth.DrawlPatch
import com.snipsnap.synth.DrawlPresets
import com.snipsnap.synth.DrawlStrum
import com.snipsnap.synth.DrawlVoice
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The SYNTH picker's DRAWL (round 4): six voices, their knobs and factory presets, TOUCH, TAKE and
 * STRUM on the recipe, and MAKE INSTRUMENT's finite keys. Rendering and the key package have JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class DrawlSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val toasts = CopyOnWriteArrayList<String>()
    private val folders = mutableListOf<File>()
    private var visit by mutableIntStateOf(0)

    @After fun cleanUp() {
        appScope.cancel()
        folders.forEach { it.deleteRecursively() }
    }

    private fun folder(name: String): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "$name-${System.nanoTime()}").apply { mkdirs(); folders += this }
    }

    private fun show(voice: DrawlVoice = DrawlVoice.CONE) {
        val shelf = folder("drawl-ui")
        settings["engine"] = "DRAWL"
        settings["voice"] = voice.name
        setPhoneContent {
            key(visit) {
                SynthScreen(null, shelf, onToast = { toasts += it }, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
    }

    private fun stored(voice: DrawlVoice): DrawlPatch? = SynthDrafts.read(settings)
        .filterIsInstance<DrawlPatch>().firstOrNull { it.voice == voice }

    private fun activate(label: String) {
        val action = compose.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun slider(label: String) = compose.onNode(hasContentDescription(label) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    private fun progress(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current

    @Test fun every_voice_shows_its_knobs_and_presets_and_a_strum_preset_keeps_its_chord() {
        show()
        compose.onNodeWithContentDescription("ENGINE DRAWL. CHOOSE ENGINE").assertExists()
        // DRAWL closes the engine ring: NEXT wraps to the first engine, PREVIOUS comes back.
        activate("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE LATTICE. CHOOSE ENGINE").assertExists()
        activate("PREVIOUS ENGINE")
        compose.onNodeWithContentDescription("ENGINE DRAWL. CHOOSE ENGINE").assertExists()
        assertEquals(Drawl.DEFAULT_VELOCITY, progress("TOUCH"), 1e-6f)
        for (voice in DrawlVoice.entries) {
            activate(voice.name)
            for (macro in Drawl.macrosFor(voice)) slider(macro.name).assertExists()
            slider("TOUCH").assertExists()
            for (preset in DrawlPresets.forVoice(voice)) {
                activate("PRESET ${preset.name}")
                waitFor("${preset.name} complete recipe") { stored(voice)?.strum == preset.strum }
            }
        }
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertDoesNotExist()
        activate("DRAWL STRUM OFF")
        waitFor("a single note again") { stored(DrawlVoice.GUT)?.strum == null }
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertExists()
    }

    @Test fun take_touch_and_strum_stay_on_the_recipe() {
        show(DrawlVoice.LAP)
        activate("DRAWL TAKE 3")
        waitFor("take 3") { stored(DrawlVoice.LAP)?.take == 2 }
        activate("DRAWL STRUM UP")
        waitFor("strummed up") { stored(DrawlVoice.LAP)?.strum == DrawlPresets.STRUMS.getValue(DrawlVoice.LAP).copy(direction = DrawlStrum.Direction.UP) }
        slider("SWEEP").assertExists()
        activate("DRAWL STRUM DOWN")
        waitFor("strummed down") { stored(DrawlVoice.LAP)?.strum?.direction == DrawlStrum.Direction.DOWN }
        assertEquals(2, stored(DrawlVoice.LAP)?.take)
        activate("DRAWL STRUM OFF")
        waitFor("one string") { stored(DrawlVoice.LAP)?.strum == null }
        slider("SWEEP").assertDoesNotExist()
    }

    @Test fun make_instrument_offers_finite_keys_without_a_release_knob() {
        val patch = DrawlPresets.named(DrawlVoice.STEEL, "PEDAL STEEL").copy(velocity = .4f)
        val root = folder("drawl-finite-sheet")
        settings["engine"] = "DRAWL"
        settings["voice"] = patch.voice.name
        SynthDrafts.write(settings, listOf(patch))
        setPhoneContent {
            SynthScreen(entry = null, shelfRoot = root, onToast = {}, onKitUpdated = {},
                onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                appScope = appScope, settings = settings)
        }
        activate("MAKE INSTRUMENT")
        pump(16)
        val spec = HeldSpec.Drawl(DrawlPadMaker.Spec(patch))
        compose.onNodeWithText(spec.instrumentNote!!).assertExists()
        compose.onNodeWithText("FINITE NOTES · THREE TOUCH LAYERS · TWO TAKES").assertExists()
        button("RELEASE").assertDoesNotExist()
        button("PREVIEW").assertExists()
        assertFalse(spec.hasAttack)
        assertFalse(spec.hasRelease)
        assertEquals(6, spec.progressPerZone)
        assertEquals("Lands in the LIBRARY as Drawl Keys, playable on keyboard.", spec.shelfNote("Drawl Keys"))
    }
}
