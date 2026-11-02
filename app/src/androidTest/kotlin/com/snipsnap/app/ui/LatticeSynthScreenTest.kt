package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.shell.LatticePadMaker
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
    private val instrumentUpdates = AtomicInteger()

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
                onShelfAssetWritten = { instrumentUpdates.incrementAndGet() },
                onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
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
    fun onlyTraceOffersKeysAndItsAuthoredAttackHasNoExtraAttackKnob() {
        show(source())
        button("MAKE INSTRUMENT").assertExists()
        activate("MAKE INSTRUMENT")
        button("RELEASE").assertExists()
        button("ATTACK").assertDoesNotExist()
        button("TOUCH LAYERS").assertDoesNotExist()
        // The scrim and the visible button share CANCEL's description.
        val cancel = compose.onAllNodes(hasContentDescription("CANCEL"), useUnmergedTree = true)
            .fetchSemanticsNodes().last().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assertTrue(cancel.invoke()) }
        pump(16)
        for (voice in listOf("WARM", "PLUCK", "HOLLOW")) {
            activate(voice)
            button("MAKE INSTRUMENT").assertDoesNotExist()
        }
        activate("TRACE")
        button("MAKE INSTRUMENT").assertExists()
    }

    @Test
    fun lastSpeedAndReleaseEditsReachTheBoundedKeygroupPackage() {
        val source = source()
        show(source)
        // The bench callback remains live underneath the sheet. Both edits and
        // MAKE can arrive before Compose has drawn their next values.
        val speed = progressAction("SPEED")
        activate("MAKE INSTRUMENT")
        val release = progressAction("RELEASE")
        val make = clickAction("MAKE")
        compose.runOnIdle {
            assertTrue(speed.invoke(.84f))
            assertTrue(release.invoke(.8f))
            assertTrue(make.invoke())
        }
        waitFor("the TRACE keyboard instrument", timeoutMillis = 300_000) {
            instrumentUpdates.get() > 0
        }
        val dir = File(shelf, KitShelf.INSTRUMENTS_DIR)
        val instrument = InstrumentStore.read(dir.listFiles()!!.single { it.name.endsWith(InstrumentStore.SUFFIX) })
        val recipe = LatticePadMaker.Spec.fromJsonText(LatticePadMaker.recipeFile(dir, instrument.name).readText())
        assertEquals(source.copy(name = recipe.patch.name, macros = source.macros + ("SPEED" to .84f)), recipe.patch)
        assertEquals(LatticePadMaker.RELEASE.value(.8f), recipe.releaseSeconds, .00001f)
        assertEquals(recipe.releaseSeconds, instrument.release, .00001f)
        assertEquals((48..72 step 3).toList(), instrument.zones.map { it.rootNote })
        assertEquals(48, instrument.zones.minOf { it.lowNote })
        assertEquals(72, instrument.zones.maxOf { it.highNote })
        assertTrue(instrument.zones.all { it.loopStartFrame > 0 && it.loopStartFrame < it.frameCount })
        assertTrue(instrument.sampleFiles.all { File(dir, it).isFile })
        val dataDir = File(dir, Mpc3TrackWriter.trackDataDirName(instrument.name))
        assertTrue(File(dataDir, "${instrument.name}.xpm").isFile)
        assertTrue(File(dir, "${instrument.name}.xty").isFile)
        assertEquals(0, kitUpdates.get())
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
