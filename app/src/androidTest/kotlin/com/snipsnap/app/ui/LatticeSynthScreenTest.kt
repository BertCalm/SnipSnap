package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Lattice
import com.snipsnap.synth.LatticePatch
import com.snipsnap.synth.LatticeVoice
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
import java.util.concurrent.atomic.AtomicInteger

/** The accepted moving pluck must retain the last speed edit and its authored note. */
@RunWith(AndroidJUnit4::class)
class LatticeSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "lattice-synth-${System.nanoTime()}").apply { mkdirs() }
    private val settings = ConcurrentHashMap<String, String>()
    private var entry by mutableStateOf<KitShelf.Entry?>(null)
    private val kitUpdates = AtomicInteger()

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun source() = LatticePatch("SAVED TRACE", LatticeVoice.TRACE,
        mapOf("TUNE" to .625f, "SPEED" to .23f, "HOLD" to .2f), midi = 55, velocity = .43f)

    private fun show(source: LatticePatch) {
        settings["engine"] = "LATTICE"
        settings["voice"] = source.voiceName
        SynthWork.replace(settings, listOf(SynthWork.Working(PadRecipe(source), null)))
        val dir = File(shelf, "kit").apply { mkdirs() }
        val kit = Kit("LATTICE TEST", emptyList())
        KitStore.save(kit, dir)
        entry = KitShelf.Entry(dir, kit)
        setPhoneContent {
            SynthScreen(
                entry = entry, shelfRoot = shelf, onToast = {},
                onKitUpdated = { updated -> entry = entry?.copy(kit = updated); kitUpdates.incrementAndGet() },
                onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                appScope = appScope, settings = settings,
            )
        }
        waitFor("the restored TRACE controls") { progress("SPEED") == source.macros.getValue("SPEED") }
    }

    private fun clickAction(label: String): () -> Boolean =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!

    private fun progressAction(label: String): (Float) -> Boolean =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.SetProgress].action!!

    private fun progress(label: String): Float =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun activate(label: String) {
        val action = clickAction(label)
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump(16)
    }

    @Test
    fun speedEditAndPresetSaveInOneFrameKeepTheCompleteNativePatch() {
        val source = source()
        show(source)
        // Retain the real slider action before the naming overlay covers it.
        val speed = progressAction("SPEED")
        activate("SAVE PRESET")
        val fields = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        fields[fields.fetchSemanticsNodes().lastIndex].performTextReplacement("MY TRACE")
        pump(16)
        val save = clickAction("SAVE")
        compose.runOnIdle {
            assertTrue(speed.invoke(.81f))
            assertTrue(save.invoke())
        }
        waitFor("the last SPEED edit in the saved TRACE preset", timeoutMillis = 30_000) {
            UserPresets.read(shelf).any { it.name == "MY TRACE" }
        }
        val saved = UserPresets.read(shelf).single { it.name == "MY TRACE" }.patch
        assertEquals(source.copy(name = "MY TRACE", macros = source.macros + ("SPEED" to .81f)), saved)
    }

    @Test
    fun speedEditAndSendInOneFrameKeepMotionAndAuthoredRootWithoutRetuning() {
        val source = source()
        show(source)
        val speed = progressAction("SPEED")
        activate("SEND TO PAD")
        val send = clickAction("A01, EMPTY")
        compose.runOnIdle {
            assertTrue(speed.invoke(.84f))
            assertTrue(send.invoke())
        }
        // Rendering on an unaccelerated emulator can exceed phone-sized timing budgets.
        waitFor("the finite TRACE pad delivery", timeoutMillis = 180_000) { kitUpdates.get() > 0 }
        val pad = KitStore.load(requireNotNull(entry).dir).pad(1)!!
        val delivered = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as LatticePatch
        assertEquals(source.copy(name = delivered.name, macros = source.macros + ("SPEED" to .84f)), delivered)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertTrue(pad.oneShot)
        assertFalse(pad.loop)
        assertEquals(0, pad.tuneCoarse)
        assertEquals(0, pad.tuneFine)
        val root = Lattice.midiFor(source.voice, source.macros.getValue("TUNE"), source.midi)
        assertEquals(mapOf("from" to "SYNTH", "rootMidi" to root.toString(),
            "rootNote" to Scales.nameOf(root), "rootOrigin" to "authored"), pad.source)
    }
}
