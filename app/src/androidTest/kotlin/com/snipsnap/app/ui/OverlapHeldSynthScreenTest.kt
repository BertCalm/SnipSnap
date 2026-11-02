package com.snipsnap.app.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.Scales
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.OverlapPadMaker
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Overlap
import com.snipsnap.synth.OverlapHeld
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.OverlapPlayback
import com.snipsnap.synth.OverlapTopology
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Explicit held playback is saved performance state; finite recipes keep their old contract. */
@RunWith(AndroidJUnit4::class)
class OverlapHeldSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "overlap-held-ui-${System.nanoTime()}").apply { mkdirs() }
    private val settings = mutableMapOf("engine" to "OVERLAP", "voice" to "TREFOIL")
    private var generation by mutableStateOf(0)
    private var entry by mutableStateOf<KitShelf.Entry?>(null)
    private val instrumentUpdates = AtomicInteger()
    private val toast = AtomicReference("")

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun source() = OverlapPatch("FINITE SOURCE", OverlapTopology.TREFOIL,
        Overlap.defaults(OverlapTopology.TREFOIL, model = 1) + mapOf("GLOW" to .42f, "HOLD" to .3f),
        midi = 55, velocity = .61f, model = 1)

    private fun heldSource() = OverlapPatch("HELD SOURCE", OverlapTopology.TREFOIL,
        OverlapHeld.defaults(OverlapTopology.TREFOIL), midi = 60, velocity = .7f,
        model = 2, playback = OverlapPlayback.HELD, releaseSeconds = .6f)

    private fun show(patch: OverlapPatch = source()) {
        SynthWork.replace(settings, listOf(SynthWork.Working(PadRecipe(patch = patch), null)))
        val dir = File(shelf, "kit").apply { mkdirs() }
        val kit = Kit(name = "OVERLAP HELD UI", pads = emptyList())
        KitStore.save(kit, dir)
        entry = KitShelf.Entry(dir, kit)
        setPhoneContent {
            key(generation) {
                SynthScreen(entry, shelf, onToast = { toast.set(it) },
                    onKitUpdated = { changed -> entry = entry?.copy(kit = changed) },
                    onShelfAssetWritten = { instrumentUpdates.incrementAndGet() },
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = appScope, settings = settings)
            }
        }
    }

    private fun activate(label: String) {
        val action = button(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("No click action on $label")
        assertTrue(action())
        pump(16)
    }

    private fun dial(label: String, value: Float) {
        val action = button(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("No slider action on $label")
        assertTrue(action(value))
        pump(16)
    }

    private fun progress(label: String) = button(label).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun current(topology: OverlapTopology = OverlapTopology.TREFOIL): OverlapPatch =
        SynthDrafts.read(settings).filterIsInstance<OverlapPatch>().single { it.topology == topology }

    private fun finishSave(name: String): OverlapPatch {
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        pump(16)
        activate("SAVE")
        waitFor("saved $name") { UserPresets.read(shelf).any { it.name == name } }
        return UserPresets.read(shelf).single { it.name == name }.patch as OverlapPatch
    }

    private fun waitForRender(what: String, timeoutMillis: Long = 180_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { "$what did not finish; latest toast: ${toast.get()}" }
            SystemClock.sleep(40)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    @Test
    fun explicitHeldChoiceRestoresFiniteModelAndShowsOnlyActiveControls() {
        val finite = source()
        show(finite)
        button("OVERLAP FINITE").assertIsSelected()
        button("HOLD").assertExists()
        button("MAKE INSTRUMENT").assertDoesNotExist()
        activate("OVERLAP HELD")
        button("OVERLAP HELD").assertIsSelected()
        for (hidden in listOf("PULSE", "LOCK", "GLOW", "HOLD")) button(hidden).assertDoesNotExist()
        button("KNOT").assertExists()
        button("RING").assertExists()
        button("RELEASE").assertExists()
        button("MAKE INSTRUMENT").assertExists()
        dial("RELEASE", 1f)
        compose.runOnIdle { generation++ }
        pump(16)
        activate("OVERLAP FINITE")
        val returned = current()
        assertEquals(OverlapPlayback.FINITE, returned.playback)
        assertEquals(1, returned.model)
        assertEquals(finite.macros, returned.macros)
        assertEquals(finite.midi, returned.midi)
        assertEquals(finite.velocity, returned.velocity)
        assertEquals(1.5f, returned.releaseSeconds, .00001f)
        assertEquals(.42f, progress("GLOW"), .00001f)
        assertEquals(.3f, progress("HOLD"), .00001f)
        activate("SELF")
        activate("OVERLAP HELD")
        button("KNOT").assertDoesNotExist()
        button("RING").assertExists()
        activate("SCRAMBLE")
        assertTrue(OverlapHeld.supports(current(OverlapTopology.SELF)))
        assertEquals(0f, current(OverlapTopology.SELF).macros.getValue("KNOT"))
    }

    @Test
    fun sameFrameEditsSaveCompleteHeldStateAndSendAndSpreadOnlyFiniteDeliveries() {
        show(heldSource())
        // Capture callbacks from one composition, then invoke all three without
        // allowing a frame between them. Each edit must read the live patch.
        val rootAction = button("ROOT").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        val velocityAction = button("VELOCITY").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        val saveAction = button("SAVE PRESET").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            assertTrue(rootAction((64 - Overlap.MIN_MIDI).toFloat() / (Overlap.MAX_MIDI - Overlap.MIN_MIDI)))
            assertTrue(velocityAction(.31f))
            assertTrue(saveAction())
        }
        pump(16)
        val saved = finishSave("HELD OVERLAP")
        assertEquals(64, saved.midi)
        assertEquals(.31f, saved.velocity)
        assertEquals(OverlapPlayback.HELD, saved.playback)
        assertEquals(OverlapHeld.VERSION, saved.heldVersion)
        dial("KNOT", .7f)
        dial("RING", .32f)
        dial("RELEASE", .8f)
        val release = OverlapPadMaker.RELEASE.value(.8f)
        activate("MIRROR")
        activate("TREFOIL")
        button("OVERLAP HELD").assertIsSelected()
        compose.runOnIdle { generation++ }
        pump(16)
        assertEquals(.7f, progress("KNOT"), .00001f)
        assertEquals(.32f, progress("RING"), .00001f)
        assertEquals(.8f, progress("RELEASE"), .00001f)
        activate("SAVE PRESET")
        val copy = finishSave("HELD COPY")
        assertEquals(saved.copy(name = copy.name, macros = saved.macros + mapOf("KNOT" to .7f, "RING" to .32f),
            releaseSeconds = release), copy)
        activate("SEND TO PAD")
        activate("A01, EMPTY")
        waitForRender("held one-shot delivery") { KitStore.load(entry!!.dir).pad(1) != null }
        val pad = KitStore.load(entry!!.dir).pad(1)!!
        val delivered = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        assertTrue(pad.oneShot)
        assertEquals(OverlapPadMaker.ONE_SHOT_FADE_MS, delivered.terminalFadeMs)
        assertEquals(copy.copy(name = delivered.patch!!.name), delivered.patch)

        // SPREAD is also a finite pad delivery. Exercise the actual overlay,
        // retaining the authored geometry reference and the full saved patch.
        waitForRender("SEND chooser to close") {
            compose.onAllNodes(hasContentDescription("A01, EMPTY")).fetchSemanticsNodes().isEmpty()
        }
        activate("SPREAD ACROSS PADS")
        waitForRender("held SPREAD panel") {
            compose.onAllNodes(hasContentDescription("FEWER PADS")).fetchSemanticsNodes().isNotEmpty()
        }
        // One in the SPREAD panel, one in the SYNTH screen's ROOT row beneath it: both name the copy's root.
        assertEquals(2, compose.onAllNodesWithText("ROOT ${Scales.nameOf(copy.midi)}").fetchSemanticsNodes().size)
        repeat(12) { activate("FEWER PADS") } // Four slots, with the existing A01 left intact.
        activate("SPREAD")
        waitForRender("held SPREAD finite pads") { KitStore.load(entry!!.dir).pad(4) != null }
        val spreadKit = KitStore.load(entry!!.dir)
        assertEquals(4, spreadKit.pads.size)
        val firstSpread = spreadKit.pad(2)!!
        assertEquals(2, firstSpread.tuneCoarse) // Major second above the saved reference.
        assertEquals(0, firstSpread.tuneFine)
        val firstRecipe = PadRecipe.fromJsonValue(requireNotNull(firstSpread.recipe))
        val expected = OverlapPadMaker.deliveryRecipe(copy.copy(name = firstRecipe.patch!!.name))
        val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
        for (spreadPad in spreadKit.pads.filter { it.slot in 2..4 }) {
            assertTrue(spreadPad.oneShot)
            assertEquals(expected.toJsonValue(), spreadPad.recipe)
            assertEquals(OverlapPadMaker.ONE_SHOT_FADE_MS,
                PadRecipe.fromJsonValue(requireNotNull(spreadPad.recipe)).terminalFadeMs)
            assertArrayEquals("SPREAD must keep the exact finite-delivery gain and ending",
                encoded, File(entry!!.dir, spreadPad.sampleFile).readBytes())
        }
        waitForRender("SPREAD panel to close") {
            compose.onAllNodes(hasContentDescription("FEWER PADS")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun preparedNativePreviewReleasesAndDoesNotRecaptureForNameOrReleaseEdits() {
        var patch by mutableStateOf(heldSource())
        var preview: FlockHeldPreviewState? = null
        var failed = false
        setPhoneContent {
            val held = rememberOverlapHeldPreview(patch) { failed = true }
            preview = held
            if (held != null) FlockHeldAudition(held, Color.Cyan, Schemes.DEFAULT)
        }
        waitForRender("prepared OVERLAP preview") { failed || preview?.ready == true }
        assertFalse("OVERLAP preview failed", failed)
        val controller = preview!!
        val capture = controller.snip
        compose.runOnIdle { patch = patch.copy(name = "RENAMED", releaseSeconds = .1f) }
        pump(16)
        assertSame(controller, preview)
        assertSame(capture, preview!!.snip)
        activate("AUDITION")
        assertTrue(preview!!.sounding)
        activate("AUDITION")
        assertFalse(preview!!.sounding)
        assertSame(capture, preview!!.snip)
        compose.runOnIdle { patch = patch.copy(playback = OverlapPlayback.FINITE,
            macros = Overlap.defaults(patch.topology, model = 2)) }
        pump(16)
        assertEquals(null, preview)
        assertFalse(controller.ready)
        assertFalse(controller.sounding)
    }

    @Test
    fun makeInstrumentUsesSavedReleaseAndWritesAllThreeNativeRoots() {
        val patch = heldSource().copy(macros = OverlapHeld.defaults(OverlapTopology.TREFOIL) + ("RING" to .3f),
            releaseSeconds = .37f)
        show(patch)
        activate("MAKE INSTRUMENT")
        button("INSTRUMENT AUDITION").assertExists()
        button("PREVIEW").assertDoesNotExist()
        // The last RELEASE belongs to the sheet, rather than the obscured bench.
        val releases = compose.onAllNodes(hasContentDescription("RELEASE"))
        val node = releases[releases.fetchSemanticsNodes().lastIndex].fetchSemanticsNode()
        assertTrue(node.config[SemanticsActions.SetProgress].action!!(.6f))
        pump(16)
        val expectedRelease = OverlapPadMaker.RELEASE.value(.6f)
        activate("MAKE")
        waitForRender("OVERLAP instrument", timeoutMillis = 240_000) { instrumentUpdates.get() > 0 }
        val dir = File(shelf, KitShelf.INSTRUMENTS_DIR)
        val sidecar = dir.listFiles()!!.single { it.name.endsWith(InstrumentStore.SUFFIX) }
        val instrument = InstrumentStore.read(sidecar)
        assertEquals(3, instrument.zones.size)
        assertEquals(expectedRelease, instrument.release, .00001f)
        assertEquals(OverlapPadMaker.zoneMidis(OverlapPadMaker.Spec(patch)), instrument.zones.map { it.rootNote })
        assertTrue(instrument.zones.all { it.loopStartFrame > 0 && it.loopStartFrame < it.frameCount })
        assertTrue(instrument.sampleFiles.all { File(dir, it).isFile })
        val recipe = OverlapPatch.fromJsonText(File(dir, "${instrument.name}.patch.json").readText())
        assertEquals(patch.copy(name = recipe.name, releaseSeconds = expectedRelease), recipe)
    }
}
