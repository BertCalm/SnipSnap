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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import com.snipsnap.synth.WickPresets
import com.snipsnap.synth.WickVoice
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

/** The phone must render and save the selected body, including edits arriving in one frame. */
@RunWith(AndroidJUnit4::class)
class WickSynthScreenTest : ComposeScreenTest() {
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

    private fun show(source: WickPatch? = null, entry: KitShelf.Entry? = null): File {
        val shelf = folder("wick-ui")
        settings["engine"] = "WICK"
        settings["voice"] = source?.voiceName ?: "PLUCK"
        if (source != null) SynthWork.replace(settings,
            listOf(SynthWork.Working(PadRecipe(source), null)))
        setPhoneContent {
            key(visit) {
                SynthScreen(entry, shelf, onToast = { toasts += it }, onKitUpdated = { updatedKits += it }, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        return shelf
    }

    private fun stored(voice: WickVoice): WickPatch? = SynthDrafts.read(settings)
        .filterIsInstance<WickPatch>().firstOrNull { it.voice == voice }

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

    private fun dial(label: String, value: Float) {
        val action = progressAction(label)
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    private fun progress(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current

    private fun bodyLabel(body: Wick.RattleSource): String = "WICK BODY " + when (body) {
        Wick.RattleSource.CURRENT -> "PREVIOUS BODY"
        else -> body.name.replace('_', ' ')
    }

    private fun assertBody(body: Wick.RattleSource) {
        assertTrue("$body must be selected", compose.onNodeWithContentDescription(bodyLabel(body))
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)
    }

    @Test fun all_six_voices_expose_the_controls_and_recall_the_complete_factory_bank() {
        show()
        compose.onNodeWithContentDescription("ENGINE WICK. CHOOSE ENGINE").assertExists()
        assertEquals("PLUCK", settings["voice"])
        assertEquals((Wick.rootMidi(WickVoice.PLUCK) - Wick.MIN_MIDI) /
            (Wick.MAX_MIDI - Wick.MIN_MIDI).toFloat(), progress("ROOT"), 1e-6f)
        assertEquals(1f, progress("VELOCITY"), 1e-6f)
        for (voice in WickVoice.entries) {
            activate(voice.name)
            for (macro in Wick.macrosFor(voice)) slider(macro.name).assertExists()
            slider("ROOT").assertExists()
            slider("VELOCITY").assertExists()
            val presets = WickPresets.forVoice(voice)
            assertEquals(6, presets.size)
            for (preset in presets) {
                activate("PRESET ${preset.name}")
                waitFor("${preset.name} complete recipe") { stored(voice) == preset }
                if (voice == WickVoice.RATTLE) assertBody(preset.rattleSource)
            }
        }
        assertEquals(36, WickPresets.all().size)
    }

    @Test fun same_frame_body_note_velocity_and_macro_edits_survive_navigation_save_and_pad_audio() {
        val kitDir = folder("wick-pad")
        val kit = Kit("WICK UI", emptyList())
        KitStore.save(kit, kitDir)
        val source = WickPresets.forVoice(WickVoice.RATTLE).single { it.name == "Rubbed Glass" }
            .copy(name = "SAVED GLASS", midi = 57, velocity = .42f)
        val shelf = show(source, KitShelf.Entry(kitDir, kit))
        waitFor("saved Glass source") { stored(source.voice) == source }
        assertBody(Wick.RattleSource.RUBBED_GLASS)

        // All four callbacks originate in one composition. Copying the drawn
        // patch in any callback would undo one of the preceding edits.
        val chooseReed = clickAction(bodyLabel(Wick.RattleSource.HOLLOW_REED))
        val moveTimbre = progressAction("TIMBRE")
        val moveRoot = progressAction("ROOT")
        val moveVelocity = progressAction("VELOCITY")
        compose.runOnIdle {
            assertTrue(chooseReed.invoke())
            assertTrue(moveTimbre.invoke(.61f))
            assertTrue(moveRoot.invoke(.75f))
            assertTrue(moveVelocity.invoke(.37f))
        }
        pump()
        val expected = source.copy(macros = source.macros + ("TIMBRE" to .61f), midi = 69,
            velocity = .37f, rattleSource = Wick.RattleSource.HOLLOW_REED)
        waitFor("complete same-frame Reed edit") { stored(source.voice) == expected }
        assertBody(Wick.RattleSource.HOLLOW_REED)
        assertFalse(settings.containsKey("preset.WICK.RATTLE"))

        activate("PLUCK")
        activate("RATTLE")
        assertBody(Wick.RattleSource.HOLLOW_REED)
        activate("NEXT ENGINE")
        activate("PREVIOUS ENGINE")
        activate("RATTLE")
        assertBody(Wick.RattleSource.HOLLOW_REED)
        compose.runOnIdle { visit += 1 }
        pump()
        waitFor("Reed edit after return") { stored(source.voice) == expected }
        assertEquals(.75f, progress("ROOT"), 1e-6f)
        assertEquals(.37f, progress("VELOCITY"), 1e-6f)

        activate(bodyLabel(Wick.RattleSource.WARM_WIRE))
        val restoreReedForSave = clickAction(bodyLabel(Wick.RattleSource.HOLLOW_REED))
        activate("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY REED")
        pump()
        val save = clickAction("SAVE")
        compose.runOnIdle {
            assertTrue(restoreReedForSave.invoke())
            assertTrue(save.invoke())
        }
        pump()
        waitFor("saved typed WICK preset") { UserPresets.read(shelf).any { it.name == "MY REED" } }
        assertEquals(expected.copy(name = "MY REED"),
            UserPresets.read(shelf).single { it.name == "MY REED" }.patch)
        activate("PRESET Warm Wire")
        assertBody(Wick.RattleSource.WARM_WIRE)
        activateUserPreset("MY REED")
        waitFor("user preset recalls Reed") { stored(source.voice)?.rattleSource == Wick.RattleSource.HOLLOW_REED }
        assertBody(Wick.RattleSource.HOLLOW_REED)

        toasts.clear()
        updatedKits.clear()
        activate(bodyLabel(Wick.RattleSource.WARM_WIRE))
        val restoreReedForSend = clickAction(bodyLabel(Wick.RattleSource.HOLLOW_REED))
        activate("SEND TO PAD")
        val send = clickAction("A01, EMPTY")
        compose.runOnIdle {
            assertTrue(restoreReedForSend.invoke())
            assertTrue(send.invoke())
        }
        pump()
        // Real render/write on an emulator is bounded without claiming phone latency.
        compose.waitUntil(timeoutMillis = 300_000) {
            assertFalse("SEND TO PAD failed; messages: ${toasts.joinToString()}",
                toasts.any { it == Copy.SYNTH_PAD_REFUSED || it == Copy.SEND_FAILED })
            updatedKits.any { it.pad(1)?.recipe != null }
        }
        val pad = requireNotNull(KitStore.load(kitDir).pad(1))
        val landedRecipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val landed = landedRecipe.patch as WickPatch
        assertEquals(expected.copy(name = landed.name), landed)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertEquals("69", pad.source["rootMidi"])
        assertEquals("authored", pad.source["rootOrigin"])
        assertEquals(0, pad.tuneFine)
        val actual = WavReader.read(File(kitDir, pad.sampleFile))
        val expectedAudio = landedRecipe.render()
        assertEquals(expectedAudio.frameCount, actual.frameCount)
        assertTrue("SEND renders the saved Reed body within PCM24 rounding",
            actual.samples.indices.all { abs(actual.samples[it] - expectedAudio.samples[it]) < 2e-7f })
    }

    @Test fun imported_previous_body_remains_explicit_until_a_new_body_is_chosen() {
        val modern = WickPatch("OLDER RATTLE", WickVoice.RATTLE, Wick.defaults(WickVoice.RATTLE),
            midi = 55, velocity = .5f)
        val imported = WickPatch.fromJsonValue(JsonValue.Obj(modern.toJsonValue().entries - "rattleSource")) as WickPatch
        assertEquals(Wick.RattleSource.CURRENT, imported.rattleSource)
        show(imported)
        waitFor("imported previous body") { stored(imported.voice) == imported }
        assertBody(Wick.RattleSource.CURRENT)
        dial("CASCADE", .31f)
        dial("VELOCITY", .64f)
        compose.runOnIdle { visit += 1 }
        pump()
        val edited = imported.copy(macros = imported.macros + ("CASCADE" to .31f), velocity = .64f)
        waitFor("legacy body after edits and return") { stored(imported.voice) == edited }
        assertBody(Wick.RattleSource.CURRENT)
        assertEquals("CURRENT", requireNotNull(stored(imported.voice)).toJsonValue().entries
            .getValue("rattleSource").str())
        activate(bodyLabel(Wick.RattleSource.WARM_WIRE))
        waitFor("intentional modern body") {
            stored(imported.voice) == edited.copy(rattleSource = Wick.RattleSource.WARM_WIRE)
        }
        assertBody(Wick.RattleSource.WARM_WIRE)
        compose.onNodeWithContentDescription(bodyLabel(Wick.RattleSource.CURRENT)).assertDoesNotExist()
    }
}
