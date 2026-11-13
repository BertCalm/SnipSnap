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
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Rivet
import com.snipsnap.synth.RivetPatch
import com.snipsnap.synth.RivetPresets
import com.snipsnap.synth.RivetVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Complete strike recipes must survive phone navigation and last-frame edits. */
@RunWith(AndroidJUnit4::class)
class RivetSynthScreenTest : ComposeScreenTest() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val toasts = CopyOnWriteArrayList<String>()
    private val updates = CopyOnWriteArrayList<Kit>()
    private val folders = mutableListOf<File>()
    private var visit by mutableIntStateOf(0)

    @After fun cleanUp() { scope.cancel(); folders.forEach { it.deleteRecursively() } }

    private fun folder(name: String) = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "$name-${System.nanoTime()}",
    ).apply { mkdirs(); folders += this }

    private fun show(source: RivetPatch? = null, entry: KitShelf.Entry? = null): File {
        val shelf = folder("rivet-ui")
        settings["engine"] = "RIVET"
        settings["voice"] = source?.voiceName ?: "KICK"
        if (source != null) SynthWork.replace(settings, listOf(SynthWork.Working(PadRecipe(source), null)))
        setPhoneContent {
            key(visit) {
                SynthScreen(entry, shelf, onToast = { toasts += it }, onKitUpdated = { updates += it },
                    onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = scope, settings = settings)
            }
        }
        return shelf
    }

    private fun stored(voice: RivetVoice) = SynthDrafts.read(settings)
        .filterIsInstance<RivetPatch>().firstOrNull { it.voice == voice }

    private fun click(label: String): () -> Boolean = compose.onNode(
        hasContentDescription(label) and hasClickAction(),
    ).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)!!.action!!

    private fun activate(label: String) { val action = click(label); compose.runOnIdle { assertTrue(action()) }; pump() }

    private fun slider(label: String) = compose.onNode(hasContentDescription(label) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    private fun progressAction(label: String): (Float) -> Boolean = slider(label).fetchSemanticsNode()
        .config.getOrNull(SemanticsActions.SetProgress)!!.action!!

    private fun voiceLabel(voice: RivetVoice) = when (voice) {
        RivetVoice.HAT_CLOSED -> "HAT CL"
        RivetVoice.HAT_OPEN -> "HAT OP"
        else -> voice.name
    }

    @Test fun all_six_voices_recall_the_accepted_bank_and_expose_only_finite_hit_controls() {
        show()
        compose.onNodeWithContentDescription("ENGINE RIVET. CHOOSE ENGINE").assertExists()
        for (voice in RivetVoice.entries) {
            activate(voiceLabel(voice))
            for (macro in Rivet.macrosFor(voice).filterNot { it.name == "HOLD" }) slider(macro.name).assertExists()
            slider("HOLD").assertDoesNotExist()
            slider("ROOT").assertDoesNotExist()
            slider("VELOCITY").assertExists()
            for (preset in RivetPresets.forVoice(voice)) {
                activate("PRESET ${preset.name}")
                waitFor("complete ${preset.name}") { stored(voice) == preset }
            }
        }
    }

    @Test fun same_frame_strike_and_room_edits_survive_navigation_save_recall_and_pad_delivery() {
        val dir = folder("rivet-pad")
        val kit = Kit("RIVET UI", emptyList())
        KitStore.save(kit, dir)
        // An older source must keep its renderer, rather than adopt today's default.
        val source = RivetPatch("SAVED SNARE", RivetVoice.SNARE, modelVersion = 1, velocity = .6f)
        val shelf = show(source, KitShelf.Entry(dir, kit))
        waitFor("restored source") { stored(source.voice) == source }
        val room = progressAction("SIZE")
        val strike = progressAction("VELOCITY")
        compose.runOnIdle { assertTrue(room(.87f)); assertTrue(strike(.37f)) }
        pump()
        val expected = source.copy(macros = source.macros + ("SIZE" to .87f), velocity = .37f)
        waitFor("complete edit") { stored(source.voice) == expected }
        activate("KICK"); activate("SNARE")
        activate("NEXT ENGINE"); activate("PREVIOUS ENGINE"); activate("SNARE")
        compose.runOnIdle { visit += 1 }; pump()
        waitFor("restored edit after navigation") { stored(source.voice) == expected }
        assertEquals(.37f, slider("VELOCITY").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current, 1e-6f)

        val lastStrike = progressAction("VELOCITY")
        activate("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY RIVET")
        pump()
        val save = click("SAVE")
        compose.runOnIdle { assertTrue(lastStrike(.42f)); assertTrue(save()) }; pump()
        val saved = expected.copy(name = "MY RIVET", velocity = .42f)
        waitFor("saved complete recipe") { UserPresets.read(shelf).any { it.name == saved.name } }
        assertEquals(saved, UserPresets.read(shelf).single { it.name == saved.name }.patch)
        activate("PRESET Wire Snare")
        val yours = SemanticsMatcher("YOUR PRESET MY RIVET") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "YOUR PRESET MY RIVET"
        }
        // The save writes the preset and re-reads the list off the main thread, and only then shows the
        // YOURS strip, so the file on disk can land a frame or more before its button does.
        waitFor("the saved preset's button") { compose.onAllNodes(yours, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val recall = compose.onNode(yours, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)!!.action!!
        compose.runOnIdle { assertTrue(recall()) }; pump()
        waitFor("user preset recall") { stored(source.voice) == saved }

        val lastRoom = progressAction("SIZE")
        activate("SEND TO PAD")
        val send = click("A01, EMPTY")
        compose.runOnIdle { assertTrue(lastRoom(.91f)); assertTrue(send()) }; pump()
        compose.waitUntil(timeoutMillis = 300_000) {
            assertFalse(toasts.any { it == Copy.SYNTH_PAD_REFUSED || it == Copy.SEND_FAILED })
            updates.any { it.pad(1)?.recipe != null }
        }
        val pad = requireNotNull(KitStore.load(dir).pad(1))
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val landed = recipe.patch as RivetPatch
        assertEquals(saved.copy(name = landed.name, macros = saved.macros + ("SIZE" to .91f)), landed)
        assertEquals(DrumClass.SNARE, pad.drumClass)
        assertFalse(pad.source.containsKey("rootMidi"))
        val actual = WavReader.read(File(dir, pad.sampleFile))
        val expectedAudio = recipe.render()
        assertEquals(expectedAudio.frameCount, actual.frameCount)
        assertTrue(actual.samples.indices.all { abs(actual.samples[it] - expectedAudio.samples[it]) < 2e-7f })
    }
}
