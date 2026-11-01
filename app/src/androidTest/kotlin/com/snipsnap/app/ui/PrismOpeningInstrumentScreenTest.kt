package com.snipsnap.app.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.PcmPlayback
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.PrismPadMaker
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismMotionSources
import com.snipsnap.synth.PrismOpeningPresets
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The optional keys delivery keeps the opening without changing the saved sound or ordinary pad. */
@RunWith(AndroidJUnit4::class)
class PrismOpeningInstrumentScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val instrumentUpdates = AtomicInteger()
    private val directories = mutableListOf<File>()
    private var screenGeneration by mutableIntStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        directories.forEach { it.deleteRecursively() }
    }

    private fun source(): PrismPatch {
        val base = PrismOpeningPresets.all().single { it.name == "Bowed Bloom" }
        val selected = PrismMotionSources.applySource(base,
            requireNotNull(PrismMotionSources.byId("fork-mellow-reed")))
        return selected.copy(name = "MY SAVED OPENING", velocity = .37f,
            macros = selected.macros + mapOf("TUNE" to 7f / Prism.TUNE_SEMITONES, "HOLD" to 1f),
            motion = requireNotNull(selected.motion).copy(morph = .17f, evolve = .63f,
                seconds = .2f, groupLag = .31f, repeat = true))
    }

    private fun show(patch: PrismPatch): File {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "prism-opening-keys-${System.nanoTime()}").apply { mkdirs() }
        directories += root
        settings["engine"] = "PRISM"
        settings["voice"] = patch.voice.name
        SynthDrafts.write(settings, listOf(patch))
        setPhoneContent {
            key(screenGeneration) {
                SynthScreen(entry = null, shelfRoot = root, onToast = {}, onKitUpdated = {},
                    onShelfAssetWritten = { instrumentUpdates.incrementAndGet() },
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = appScope, settings = settings)
            }
        }
        waitFor("complete PRISM source draft") { draft() == patch }
        return root
    }

    private fun draft(): PrismPatch? = SynthDrafts.read(settings)
        .filterIsInstance<PrismPatch>().singleOrNull { it.voice == PrismVoice.KEY }

    private fun clickAction(label: String): () -> Boolean =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("No click action for $label")

    private fun press(label: String) {
        val action = clickAction(label)
        compose.runOnIdle { assertTrue("Click refused for $label", action()) }
        pump()
    }

    private fun dial(label: String, value: Float) {
        val action = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.SetProgress)?.action ?: error("No slider action for $label")
        compose.runOnIdle { assertTrue(action(value)) }
        pump()
    }

    private fun cancelAction(): () -> Boolean = compose.onAllNodes(hasContentDescription("CANCEL"),
        useUnmergedTree = true).fetchSemanticsNodes().last()
        .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("No sheet CANCEL action")

    private fun noInstrumentFiles(root: File) {
        val dir = File(root, KitShelf.INSTRUMENTS_DIR)
        assertTrue("preview or cancellation must not write a package", !dir.exists() || dir.listFiles().isNullOrEmpty())
        assertEquals(0, instrumentUpdates.get())
    }

    private fun waitForPreviewReady() = waitForRender("the cancelled preview worker to drain", timeoutMillis = 30_000) {
        compose.onAllNodes(hasContentDescription("PREVIEW"), useUnmergedTree = true)
            .fetchSemanticsNodes().singleOrNull()?.config?.let {
                it.getOrNull(SemanticsProperties.Disabled) == null &&
                    it.getOrNull(SemanticsActions.OnClick)?.action != null
            } == true
    }

    /** Give the worker real time while the Compose clock remains frozen. */
    private fun waitForRender(what: String, timeoutMillis: Long = 180_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError("$what did not finish in $timeoutMillis ms")
            SystemClock.sleep(40)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    @Test
    fun the_instrument_door_requires_repeat_and_loop_hold_and_the_sheet_does_not_edit_the_source() {
        val opening = PrismOpeningPresets.all().single { it.name == "Bowed Bloom" }
        val root = show(opening)
        button("MAKE INSTRUMENT").assertDoesNotExist()
        press("EXPAND MOTION")
        press("PRISM MOTION REPEAT")
        button("MAKE INSTRUMENT").assertDoesNotExist()
        dial("HOLD", 1f)
        button("MAKE INSTRUMENT").assertExists()
        compose.onNodeWithText("PAD AND MAIN AUDITION KEEP THE SUSTAIN ONLY.").assertExists()
        val before = requireNotNull(draft())
        press("MAKE INSTRUMENT")
        compose.onNodeWithText("OPENING ONCE, THEN REPEATING SUSTAIN. HOLD A KEY TO CONTINUE; LET GO TO FADE.")
            .assertExists()
        dial("RELEASE", .81f)
        val cancel = cancelAction()
        compose.runOnIdle { assertTrue(cancel()) }
        pump()
        assertEquals(before, draft())
        noInstrumentFiles(root)
        val oldMake = clickAction("MAKE INSTRUMENT")
        val open = clickAction("PRISM MOTION OPEN")
        compose.runOnIdle { assertTrue(open()); assertTrue(oldMake()) }
        pump()
        button("MAKE INSTRUMENT").assertDoesNotExist()
        button("PREVIEW").assertDoesNotExist()
        assertFalse(Prism.isLoop(requireNotNull(draft()).macros.getValue("HOLD")))
    }

    @Test
    fun instrument_preview_keeps_the_saved_note_and_plays_the_opening_once_before_the_actual_sustain() {
        val patch = source()
        val spec = PrismPadMaker.Spec(patch)
        val adapter = PrismHeldSpec(spec)
        val preview = adapter.previewPlayback { false }
        val loop = requireNotNull(preview.loop)
        assertTrue("the opening precedes the sustain marker", loop.first > 0)
        assertEquals(preview.snip.frameCount - 1, loop.last)
        val midi = Prism.midiFor(patch.voice, patch.macros.getValue("TUNE"))
        assertFalse("preview must exercise a saved note outside the export grid", midi in adapter.zoneMidis)
        val expected = PrismPadMaker.renderZone(spec, midi)
        assertEquals(expected.loopStartFrame.toInt(), loop.first)
        assertArrayEquals(expected.snip.samples, preview.snip.samples, 0f)
        val reader = PcmPlayback(preview.snip.samples, startFrame = 0, loop = loop)
        val firstPass = FloatArray(preview.snip.frameCount)
        assertEquals(preview.snip.frameCount, reader.readFrames(firstPass))
        assertArrayEquals(preview.snip.samples, firstPass, 0f)
        val repeated = FloatArray(minOf(512, loop.count()))
        assertEquals(repeated.size, reader.readFrames(repeated))
        assertArrayEquals(preview.snip.samples.copyOfRange(loop.first, loop.first + repeated.size), repeated, 0f)
        assertEquals(patch, spec.patch)
    }

    @Test
    fun make_writes_opening_keys_with_the_complete_authored_recipe_and_positive_sustain_markers() {
        val patch = source()
        val root = show(patch)
        press("MAKE INSTRUMENT")
        dial("RELEASE", .72f)
        press("MAKE")
        waitForRender("PRISM opening keys") { instrumentUpdates.get() == 1 }
        val dir = File(root, KitShelf.INSTRUMENTS_DIR)
        val instrument = InstrumentStore.read(requireNotNull(dir.listFiles()).single { it.name.endsWith(InstrumentStore.SUFFIX) })
        assertEquals(9, instrument.zones.size)
        assertEquals(PrismPadMaker.RELEASE.value(.72f), instrument.release, .00001f)
        for (zone in instrument.zones) {
            assertTrue("every note keeps an opening", zone.loopStartFrame > 0)
            assertTrue("every note has a real sustain region", zone.loopStartFrame < zone.frameCount - 1)
        }
        val recipe = PrismPadMaker.Spec.fromJsonText(PrismPadMaker.recipeFile(dir, instrument.name).readText())
        assertEquals("name, carrier, both spectra, source, motion and touch survive MAKE", patch, recipe.patch)
        assertEquals("MAKE does not change the live source recipe", patch, draft())
    }

    @Test
    fun reset_source_change_and_disposal_cancel_the_sheet_preview_without_writing_an_instrument() {
        val patch = source()
        val root = show(patch)
        press("EXPAND MOTION")
        press("MAKE INSTRUMENT")
        val preview = clickAction("PREVIEW")
        val reset = clickAction("RESET PRISM MOTION")
        compose.runOnIdle { assertTrue(preview()); assertTrue(reset()) }
        pump()
        button("PREVIEW").assertDoesNotExist()
        assertEquals(patch, draft())
        noInstrumentFiles(root)

        press("MAKE INSTRUMENT")
        waitForPreviewReady()
        press("PREVIEW")
        val oldMake = clickAction("MAKE INSTRUMENT")
        press("CHOOSE MOTION SOURCE")
        val selectSource = clickAction("MOTION SOURCE Wooden Arrival")
        // A stale MAKE action must not cancel the newly selected source job.
        compose.runOnIdle { assertTrue(selectSource()); assertTrue(oldMake()) }
        pump()
        waitForRender("new source preparation", timeoutMillis = 30_000) {
            draft()?.motion?.sourceId == "tessera-wooden-arrival"
        }
        button("PREVIEW").assertDoesNotExist()
        assertEquals(patch.velocity, requireNotNull(draft()).velocity, 0f)
        noInstrumentFiles(root)

        press("MAKE INSTRUMENT")
        waitForPreviewReady()
        val nextPreview = clickAction("PREVIEW")
        compose.runOnIdle { assertTrue(nextPreview()); screenGeneration++ }
        pump()
        waitForRender("PRISM screen recreation", timeoutMillis = 30_000) {
            compose.onAllNodesWithText("MAKE INSTRUMENT", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        assertNotNull(draft())
        noInstrumentFiles(root)
    }
}
