package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.shell.MurmurSelectedInstrumentMaker
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.synth.MurmurBody
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurPresets
import com.snipsnap.synth.MurmurSelectedInstrumentSource
import com.snipsnap.synth.MurmurVoice
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real screen qualification/snapshot/cancellation routing, with the expensive
 * streaming boundary observed. Native cells and admission rollback have separate tests.
 */
@RunWith(AndroidJUnit4::class)
class MurmurSelectedInstrumentScreenTest : ComposeScreenTest() {
    private class Recorder : MurmurSelectedInstrumentActions {
        val exports = CopyOnWriteArrayList<MurmurSelectedInstrumentMaker.Spec>()
        val previews = CopyOnWriteArrayList<MurmurSelectedInstrumentMaker.Spec>()
        val chords = CopyOnWriteArrayList<Pair<MurmurSelectedInstrumentMaker.Spec, List<Int>>>()
        val allowFinish = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)

        private fun silence() = Snip(FloatArray(2048), 2, WavWriter.MPC_SAMPLE_RATE)
        override fun preview(spec: MurmurSelectedInstrumentMaker.Spec, cancelled: () -> Boolean): Snip {
            if (cancelled()) throw CancellationException("preview cancelled")
            previews += spec
            return silence()
        }
        override fun previewChord(spec: MurmurSelectedInstrumentMaker.Spec, notes: List<Int>, cancelled: () -> Boolean): Snip {
            if (cancelled()) throw CancellationException("chord cancelled")
            chords += spec to notes.toList()
            return silence()
        }
        override fun export(name: String, spec: MurmurSelectedInstrumentMaker.Spec, destination: File,
                            cancelled: () -> Boolean, progress: (Int, Int) -> Unit) {
            exports += spec
            // Hold at the post-cell validation/admission boundary. CANCEL must still
            // reach the exporter after the last progress sample, not just midway.
            val total = MurmurSelectedInstrumentMaker.roots(spec).size * MurmurSelectedInstrumentMaker.VELOCITY_WINDOWS.size
            progress(total, total)
            try {
                while (!allowFinish.get()) {
                    if (cancelled()) {
                        this.cancelled.set(true)
                        throw CancellationException("streaming exporter cancelled")
                    }
                    try { Thread.sleep(5) } catch (_: InterruptedException) {
                        this.cancelled.set(true)
                        throw CancellationException("streaming exporter interrupted")
                    }
                }
                if (cancelled()) {
                    this.cancelled.set(true)
                    throw CancellationException("streaming exporter cancelled before completion")
                }
            } finally { stopped.set(true) }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val toasts = CopyOnWriteArrayList<String>()
    private val shelfNotifications = AtomicInteger(0)
    private val recorder = Recorder()
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "murmur-selected-instrument-ui-${System.nanoTime()}").apply { mkdirs() }

    @After fun cleanUp() {
        recorder.allowFinish.set(true)
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(source: MurmurPatch = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()) {
        settings["engine"] = "MURMUR"
        settings["voice"] = source.voiceName
        SynthWork.replace(settings, listOf(SynthWork.Working(PadRecipe(source), null)))
        setPhoneContent {
            CompositionLocalProvider(LocalMurmurSelectedInstrumentActions provides recorder) {
                SynthScreen(null, shelf, onToast = { toasts += it }, onKitUpdated = {},
                    onShelfAssetWritten = { shelfNotifications.incrementAndGet() },
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        waitFor("complete MURMUR source") { draft() == source }
    }

    private fun draft() = SynthDrafts.read(settings).filterIsInstance<MurmurPatch>()
        .singleOrNull { it.voice == MurmurVoice.PLUCK }

    private fun clickAction(label: String): () -> Boolean = compose.onAllNodes(
        hasContentDescription(label) and hasClickAction(), useUnmergedTree = true,
    ).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsActions.OnClick)?.action
        ?: error("No click for $label")

    private fun activate(label: String) {
        val action = clickAction(label)
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun progressAction(label: String): (Float) -> Boolean = compose.onNode(
        hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
        useUnmergedTree = true,
    ).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action ?: error("No slider for $label")

    private fun dial(label: String, value: Float) {
        val action = progressAction(label)
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    @Test fun exact_selected_shapes_offer_dedicated_make_and_edits_refuse_without_generic_fallback() {
        show()
        for (source in MurmurKeptSources.all()) {
            activate("PRESET ${source.presetName}")
            compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertIsEnabled()
            activate("MAKE INSTRUMENT")
            compose.onNodeWithText("MURMUR · ${if (source.id == MurmurKeptSources.HELD_WIRE) "HELD" else "FINITE"} PLUCK")
                .assertExists()
            compose.onNode(hasContentDescription("MINOR CHORD PREVIEW")).assertIsEnabled()
            activate("CANCEL")
        }
        val staleMake = clickAction("MAKE INSTRUMENT")
        val mouth = progressAction("MOUTH")
        compose.runOnIdle {
            assertTrue(mouth.invoke(.11f))
            assertTrue(staleMake.invoke())
        }
        pump()
        compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertIsNotEnabled()
        compose.onNodeWithText(murmurInstrumentUnavailable(requireNotNull(draft()))).assertExists()
        assertTrue(recorder.exports.isEmpty())
        assertTrue(recorder.previews.isEmpty())
        dial("MOUTH", .35f)
        compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertIsEnabled()
        dial("HOLD", 1f)
        compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertIsNotEnabled()
        activate("PRESET STEADY ROOT C2")
        dial("VELOCITY", 0f)
        compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertIsNotEnabled()
        compose.onNodeWithText("RAISE VELOCITY ABOVE ZERO TO MAKE AN INSTRUMENT.").assertExists()

        activate("PRESET OPEN STRING")
        compose.onNode(hasContentDescription("MAKE INSTRUMENT")).assertIsEnabled()
        activate("MAKE INSTRUMENT")
        compose.onNode(hasContentDescription("MINOR CHORD PREVIEW")).assertDoesNotExist()
        assertEquals(2, requireNotNull(draft()).config.model)
        assertEquals(MurmurBody.LEGACY, requireNotNull(draft()).config.body)
        activate("CANCEL")
    }

    @Test fun final_make_freezes_same_frame_preset_root_touch_controls_and_release_before_streaming() {
        show()
        val chooseWire = clickAction("PRESET HOLLOW WIRE HOLD")
        val moveRoot = clickAction("ROOT UP")
        val moveTouch = progressAction("VELOCITY")
        val moveMouth = progressAction("MOUTH")
        activate("MAKE INSTRUMENT")
        val moveRelease = progressAction("RELEASE")
        val make = clickAction("MAKE")
        compose.runOnIdle {
            assertTrue(chooseWire.invoke())
            assertTrue(moveMouth.invoke(.11f))
            assertTrue(moveRoot.invoke())
            assertTrue(moveTouch.invoke(.41f))
            // Restoring the actual kept shape is allowed; arbitrary edits are not.
            assertTrue(moveMouth.invoke(.84f))
            assertTrue(moveRelease.invoke(.6f))
            assertTrue(make.invoke())
        }
        pump()
        waitFor("the dedicated serial exporter") { recorder.exports.size == 1 }
        val spec = recorder.exports.single()
        val actual = spec.sourcePatch()
        val selected = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch()
        assertEquals(selected.copy(name = actual.name, midi = 37, velocity = .41f), actual)
        assertTrue(spec.held)
        assertEquals(MurmurSelectedInstrumentSource.MIN_RELEASE_SECONDS + .6f *
            (MurmurSelectedInstrumentSource.MAX_RELEASE_SECONDS - MurmurSelectedInstrumentSource.MIN_RELEASE_SECONDS),
            spec.releaseSeconds, 0f)
        val frozen = spec.sourcePatchJson
        // This edit invalidates the generation immediately, without changing its frozen request.
        val edge = progressAction("EDGE")
        compose.runOnIdle { assertTrue(edge.invoke(.27f)) }
        pump()
        waitFor("obsolete streaming exporter stops") { recorder.stopped.get() }
        assertTrue(recorder.cancelled.get())
        assertEquals(frozen, spec.sourcePatchJson)
        assertEquals(0, shelfNotifications.get())
    }

    @Test fun cancel_remains_live_after_last_cell_and_cannot_emit_a_stale_shelf_callback() {
        show(MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch())
        activate("MAKE INSTRUMENT")
        activate("MAKE")
        waitFor("all cells reach the streaming admission boundary") { recorder.exports.size == 1 }
        compose.onAllNodes(hasContentDescription("CANCEL") and hasClickAction(), useUnmergedTree = true)[0]
            .assertIsEnabled()
        activate("CANCEL")
        waitFor("streaming cancellation drains") { recorder.stopped.get() }
        assertTrue(recorder.cancelled.get())
        assertEquals(0, shelfNotifications.get())
        assertTrue(File(shelf, KitShelf.INSTRUMENTS_DIR).listFiles().orEmpty().isEmpty())
        assertFalse(toasts.any { it.contains("MADE", ignoreCase = true) })
    }

    @Test fun minor_chord_preview_checks_every_actual_stab_tone_before_rendering_any_note() {
        show(MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch().copy(midi = 48))
        activate("MAKE INSTRUMENT")
        activate("MINOR CHORD PREVIEW")
        // Actual minor Stab notes are 48,51,55: the fifth lies outside Steady's 36–54.
        assertTrue(toasts.contains(MURMUR_CHORD_OUT_OF_RANGE))
        assertTrue(recorder.chords.isEmpty())
        assertTrue(recorder.previews.isEmpty())
        activate("CANCEL")
        rootTo(44)
        activate("MAKE INSTRUMENT")
        activate("MINOR CHORD PREVIEW")
        waitFor("the wholly in-range generated chord") { recorder.chords.size == 1 }
        val (spec, notes) = recorder.chords.single()
        assertEquals(listOf(44, 47, 51), notes)
        assertEquals(44, spec.sourcePatch().midi)
        assertEquals(MurmurKeptSources.STEADY_C2, spec.selectionId)
        assertTrue(recorder.exports.isEmpty())
    }
}
