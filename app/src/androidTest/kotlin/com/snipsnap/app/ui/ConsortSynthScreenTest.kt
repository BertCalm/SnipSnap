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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.Consort
import com.snipsnap.synth.ConsortChair
import com.snipsnap.synth.ConsortInterval
import com.snipsnap.synth.ConsortPatch
import com.snipsnap.synth.ConsortPresets
import com.snipsnap.synth.ConsortVoice
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
import java.util.concurrent.CopyOnWriteArrayList

/** The SYNTH picker's CONSORT: six voices, their presets with sections, the section editor, breath, take and MAKE INSTRUMENT. */
@RunWith(AndroidJUnit4::class)
class ConsortSynthScreenTest : ComposeScreenTest() {
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

    private fun show() {
        val shelf = folder("consort-ui")
        settings["engine"] = "CONSORT"
        settings["voice"] = "FLUTE"
        setPhoneContent {
            key(visit) {
                SynthScreen(null, shelf, onToast = { toasts += it }, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
    }

    private fun stored(voice: ConsortVoice): ConsortPatch? = SynthDrafts.read(settings)
        .filterIsInstance<ConsortPatch>().firstOrNull { it.voice == voice }

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

    @Test fun every_voice_shows_its_knobs_and_presets_and_a_preset_keeps_its_section() {
        show()
        compose.onNodeWithContentDescription("ENGINE CONSORT. CHOOSE ENGINE").assertExists()
        // CONSORT closes the engine ring: NEXT wraps to the first engine, PREVIOUS comes back.
        activate("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE LATTICE. CHOOSE ENGINE").assertExists()
        activate("PREVIOUS ENGINE")
        compose.onNodeWithContentDescription("ENGINE CONSORT. CHOOSE ENGINE").assertExists()
        assertEquals(Consort.DEFAULT_VELOCITY, progress("TOUCH"), 1e-6f)
        for (voice in ConsortVoice.entries) {
            activate(voice.name)
            for (macro in Consort.macrosFor(voice)) slider(macro.name).assertExists()
            slider("TOUCH").assertExists()
            for (preset in ConsortPresets.forVoice(voice)) {
                activate("PRESET ${preset.name}")
                waitFor("${preset.name} complete recipe") { stored(voice)?.chairs == preset.chairs }
            }
        }
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertExists()
    }

    @Test fun the_section_editor_adds_steps_and_removes_players_and_take_and_touch_stay_on_the_recipe() {
        show()
        activate("OBOE")
        activate("CONSORT ADD PLAYER")
        waitFor("one player") { stored(ConsortVoice.OBOE)?.chairs == listOf(ConsortChair(ConsortVoice.OBOE, ConsortInterval.OCT_DOWN)) }
        activate("CONSORT PLAYER 2 OBOE")
        waitFor("the player's instrument steps") { stored(ConsortVoice.OBOE)?.chairs?.single()?.voice == ConsortVoice.BASSOON }
        activate("CONSORT PLAYER 2 OCTAVE DOWN")
        waitFor("the player's interval steps") { stored(ConsortVoice.OBOE)?.chairs?.single()?.interval == ConsortInterval.UNISON }
        activate("CONSORT ADD PLAYER")
        activate("CONSORT ADD PLAYER")
        waitFor("three players") { stored(ConsortVoice.OBOE)?.chairs?.size == Consort.MAX_CHAIRS }
        compose.onNodeWithContentDescription("CONSORT ADD PLAYER").assertDoesNotExist()
        activate("CONSORT REMOVE PLAYER 2")
        waitFor("two players") { stored(ConsortVoice.OBOE)?.chairs?.size == 2 }
        activate("CONSORT TAKE 3")
        waitFor("take 3") { stored(ConsortVoice.OBOE)?.take == 2 }
        assertEquals(2, stored(ConsortVoice.OBOE)?.chairs?.size)
    }
}
