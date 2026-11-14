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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Etch
import com.snipsnap.synth.EtchPatch
import com.snipsnap.synth.EtchPresets
import com.snipsnap.synth.EtchVoice
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** ETCH in the picker (round K2): the factory bank recalls whole recipes, and a saved note and velocity survive. */
@RunWith(AndroidJUnit4::class)
class EtchSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val toasts = CopyOnWriteArrayList<String>()
    private val updatedKits = CopyOnWriteArrayList<Kit>()
    private val folders = mutableListOf<File>()
    private var visit by mutableIntStateOf(0)

    @After fun cleanUp() {
        appScope.cancel()
        folders.forEach { it.deleteRecursively() }
    }

    private fun folder(name: String): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "$name-${System.nanoTime()}",
    ).apply { mkdirs(); folders += this }

    private fun show(source: EtchPatch? = null, entry: KitShelf.Entry? = null): File {
        val shelf = folder("etch-ui")
        settings["engine"] = "ETCH"
        settings["voice"] = source?.voiceName ?: "SAW"
        if (source != null) SynthWork.replace(settings, listOf(SynthWork.Working(PadRecipe(source), null)))
        setPhoneContent {
            key(visit) {
                SynthScreen(entry, shelf, onToast = { toasts += it }, onKitUpdated = { updatedKits += it }, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        return shelf
    }

    private fun stored(voice: EtchVoice): EtchPatch? = SynthDrafts.read(settings)
        .filterIsInstance<EtchPatch>().firstOrNull { it.voice == voice }

    private fun clickAction(label: String): () -> Boolean =
        compose.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")

    private fun activate(label: String) {
        val action = clickAction(label)
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun activateUserPreset(name: String) {
        val action = compose.onNode(SemanticsMatcher("YOUR PRESET $name") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "YOUR PRESET $name"
        }, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)!!.action!!
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun slider(label: String) = compose.onNode(hasContentDescription(label) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    private fun progressAction(label: String): (Float) -> Boolean = slider(label).fetchSemanticsNode()
        .config.getOrNull(SemanticsActions.SetProgress)?.action ?: error("no slider for $label")

    private fun progress(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current

    @Test fun all_six_voices_expose_the_controls_and_recall_the_complete_factory_bank() {
        show()
        compose.onNodeWithContentDescription("ENGINE ETCH. CHOOSE ENGINE").assertExists()
        assertEquals("SAW", settings["voice"])
        assertEquals((Etch.DEFAULT_MIDI - Etch.MIDI_MIN) / (Etch.MIDI_MAX - Etch.MIDI_MIN).toFloat(), progress("ROOT"), 1e-6f)
        assertEquals(1f, progress("VELOCITY"), 1e-6f)
        for (voice in EtchVoice.entries) {
            activate(voice.name)
            for (macro in Etch.macrosFor(voice)) slider(macro.name).assertExists()
            slider("ROOT").assertExists()
            slider("VELOCITY").assertExists()
            for (preset in EtchPresets.forVoice(voice)) {
                activate("PRESET ${preset.name}")
                waitFor("${preset.name} complete recipe") { stored(voice) == preset }
            }
        }
        assertEquals(14, EtchPresets.all().size)
    }

    @Test fun same_frame_note_velocity_and_macro_edits_survive_navigation_save_and_pad_audio() {
        val kitDir = folder("etch-pad")
        val kit = Kit("ETCH UI", emptyList())
        KitStore.save(kit, kitDir)
        val source = EtchPresets.forVoice(EtchVoice.SAW).first().copy(name = "SAVED NOTE", midi = 41, velocity = .42f)
        val shelf = show(source, KitShelf.Entry(kitDir, kit))
        waitFor("saved SAW source") { stored(source.voice) == source }

        // Three callbacks from one composition: copying the drawn patch in any of them would undo the others.
        val moveCutoff = progressAction("CUTOFF")
        val moveRoot = progressAction("ROOT")
        val moveVelocity = progressAction("VELOCITY")
        compose.runOnIdle {
            assertTrue(moveCutoff.invoke(.61f))
            assertTrue(moveRoot.invoke(.75f)) // MIDI 24 + 0.75 × 36 = 51
            assertTrue(moveVelocity.invoke(.37f))
        }
        pump()
        val expected = source.copy(macros = source.macros + ("CUTOFF" to .61f), midi = 51, velocity = .37f)
        waitFor("complete same-frame edit") { stored(source.voice) == expected }
        assertFalse(settings.containsKey("preset.ETCH.SAW"))

        activate("SQUARE")
        activate("SAW")
        activate("NEXT ENGINE")
        activate("PREVIOUS ENGINE")
        activate("SAW")
        compose.runOnIdle { visit += 1 }
        pump()
        waitFor("edit after return") { stored(source.voice) == expected }
        assertEquals(.75f, progress("ROOT"), 1e-6f)
        assertEquals(.37f, progress("VELOCITY"), 1e-6f)

        activate("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY NOTE")
        pump() // The clock is manual: let the typed name recompose into SAVE before pressing it.
        compose.onNodeWithContentDescription("SAVE").assertIsEnabled()
        activate("SAVE")
        waitFor("saved typed ETCH preset") { UserPresets.read(shelf).any { it.name == "MY NOTE" } }
        assertEquals(expected.copy(name = "MY NOTE"), UserPresets.read(shelf).single { it.name == "MY NOTE" }.patch)
        activate("PRESET Note")
        activateUserPreset("MY NOTE")
        waitFor("user preset recalls the note") { stored(source.voice)?.midi == 51 && stored(source.voice)?.velocity == .37f }

        toasts.clear()
        updatedKits.clear()
        activate("SEND TO PAD")
        activate("A01, EMPTY")
        // Real render/write on an emulator is bounded without claiming phone latency.
        compose.waitUntil(timeoutMillis = 300_000) {
            assertFalse("SEND TO PAD failed; messages: ${toasts.joinToString()}",
                toasts.any { it == Copy.SYNTH_PAD_REFUSED || it == Copy.SEND_FAILED })
            updatedKits.any { it.pad(1)?.recipe != null }
        }
        val pad = requireNotNull(KitStore.load(kitDir).pad(1))
        val landedRecipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val landed = landedRecipe.patch as EtchPatch
        assertEquals(expected.copy(name = landed.name), landed)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertEquals("51", pad.source["rootMidi"])
        assertEquals("authored", pad.source["rootOrigin"])
        assertEquals(0, pad.tuneFine)
        val actual = WavReader.read(File(kitDir, pad.sampleFile))
        val expectedAudio = landedRecipe.render()
        assertEquals(expectedAudio.frameCount, actual.frameCount)
        assertTrue("SEND renders the saved note within PCM24 rounding",
            actual.samples.indices.all { abs(actual.samples[it] - expectedAudio.samples[it]) < 2e-7f })
    }
}
