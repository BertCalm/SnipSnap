package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.AquariumPadRenderer
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.AquariumPadSheet
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PedalSlate
import com.snipsnap.shell.PadSheetBoxes
import com.snipsnap.shell.Schemes
import com.snipsnap.synth.AquariumControls
import com.snipsnap.synth.AquariumEditor
import com.snipsnap.synth.AquariumOfflineRenderer
import com.snipsnap.synth.AquariumRecipe
import com.snipsnap.synth.AquariumRenderRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * Aquarium's screen wiring: staged controls, truthful preview, source-backed KEEP,
 * and late-preview/error handling. The real-JNI AquariumPadRendererTest owns the
 * native transfer/DSP edge matrix; this suite crosses the actual PAD callbacks.
 *
 * Like WidePadSheetScreenTest, use direct semantic actions below the fold. The
 * ComposeScreenTest clock is deliberately frozen; performScrollTo can hang it.
 * No assertion claims that a waveform was heard or tests audio-device output.
 */
@RunWith(AndroidJUnit4::class)
class AquariumPanelTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directories = mutableListOf<File>()
    private val gates = mutableListOf<GatedRenderer>()
    private var editor by mutableStateOf(AquariumEditor())
    private var panelBusy by mutableStateOf(false)
    private var panelPreviewing by mutableStateOf(false)
    private var currentSlot by mutableStateOf(1)
    private var openBox by mutableStateOf<String?>(PadSheetBoxes.Box.TREATMENT.name)
    private var screenVisible by mutableStateOf(true)
    @Volatile private var updated: Kit? = null

    @After
    fun cleanup() {
        gates.forEach { it.release.countDown() }
        appScope.cancel()
        gates.forEach { if (it.entered.count == 0L) it.finished.await(10, TimeUnit.SECONDS) }
        directories.forEach { it.deleteRecursively() }
    }

    private fun tagged(id: String) = compose.onNodeWithTag(id)

    private fun activate(id: String) {
        tagged(id).performSemanticsAction(SemanticsActions.OnClick) { click ->
            assertTrue("$id refused its semantic click", click())
        }
        pump()
    }

    private fun activateDescription(description: String) {
        compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue(click()) }
        pump()
    }

    private fun waitForTag(id: String) = waitFor("$id", timeoutMillis = 60_000) {
        compose.onAllNodesWithTag(id, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = waitFor("the text $text", timeoutMillis = 60_000) {
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun showPanel(initial: AquariumEditor) {
        editor = initial
        setPhoneContent {
            // Match the real PAD host: content receives unbounded vertical
            // measurement, while the phone viewport remains scrollable.
            Box(Modifier.fillMaxSize().testTag("aquarium-test-scroll").verticalScroll(rememberScrollState())) {
                AquariumPanel(
                editor = editor,
                applied = false,
                canPreview = true,
                canKeep = !editor.render.isBypass,
                canRemove = false,
                canUndo = false,
                busy = panelBusy,
                previewing = panelPreviewing,
                sourceStatus = "Preview hears the main sample. KEEP renders every velocity layer.",
                status = null,
                error = null,
                disabledReason = null,
                scheme = Schemes.DEFAULT,
                color = Schemes.DEFAULT.accent.tape,
                onEditorChange = { editor = it },
                onBefore = {}, onHear = {}, onKeep = {}, onRemove = {}, onUndo = {}, onCancel = {},
                )
            }
        }
        waitForTag("aquarium-panel")
    }

    /**
     * ScrollBy starts a frame-clock coroutine. Invoke it on the UI thread, then
     * advance the frozen clock in a separate call; performScrollTo waits for
     * that animation before this test gets a chance to supply its frames.
     * Both geometry APIs are used by the existing StarterMenuTest as well.
     */
    private fun revealPanelAction(id: String) {
        repeat(8) {
            val viewportNode = tagged("aquarium-test-scroll")
            val viewport = viewportNode.getBoundsInRoot()
            val target = tagged(id).getUnclippedBoundsInRoot()
            val delta = when {
                target.top < viewport.top -> target.top - viewport.top
                target.bottom > viewport.bottom -> target.bottom - viewport.bottom
                else -> {
                    assertTrue("$id must fit horizontally at the phone width",
                        target.left >= viewport.left && target.right <= viewport.right)
                    tagged(id).assertIsDisplayed()
                    return
                }
            }
            val scrollBy = viewportNode.fetchSemanticsNode().config.getOrNull(SemanticsActions.ScrollBy)?.action
                ?: error("the stateless Aquarium fixture must expose ScrollBy")
            val pixels = with(compose.density) { delta.toPx() }
            compose.runOnIdle { assertTrue("the fixture refused to scroll to $id", scrollBy(0f, pixels)) }
            pump(2_000)
        }
        throw AssertionError("$id did not fit in the scroll viewport after eight frame-stepped scrolls")
    }

    private fun context(): AquariumRenderRecipe = AquariumRenderRecipe(
        settings = AquariumRecipe(seed = 0xffff_ffffL),
        bpm = 123.0, tailSeconds = .003, fadeSeconds = .001, outputGain = .25f, peakCeiling = .9f,
    )

    @Test
    fun accepted_presets_preserve_context_and_each_bypass_restores_only_its_own_mix() {
        val context = context()
        showPanel(AquariumEditor(context))
        for (preset in AquariumControls.presets) {
            activate("aquarium-preset-${preset.id}")
            tagged("aquarium-preset-${preset.id}").assertIsSelected()
            assertEquals(preset.settings.parameters, editor.render.settings.parameters)
            assertEquals(context.copy(settings = editor.render.settings), editor.render)
            assertEquals(context.settings.seed, editor.render.settings.seed)
        }
        activate("aquarium-preset-full")
        val full = editor.render.settings.parameters
        for (group in AquariumControls.groups) {
            activate("aquarium-group-${group.id}")
            for (effect in group.effects) {
                tagged("aquarium-bypass-${effect.id}").assertIsSelected()
                activate("aquarium-bypass-${effect.id}")
                tagged("aquarium-bypass-${effect.id}").assertIsNotSelected()
                assertEquals(0f, editor.render.settings.parameters.getValue(effect.mixId), 0f)
                assertEquals(full - effect.mixId, editor.render.settings.parameters - effect.mixId)
                activate("aquarium-bypass-${effect.id}")
                tagged("aquarium-bypass-${effect.id}").assertIsSelected()
                assertEquals(full, editor.render.settings.parameters)
            }
        }
    }

    @Test
    fun selectors_emit_exact_native_values_and_log_slider_reports_physical_units() {
        showPanel(AquariumEditor(context()).selectPreset("full"))
        var selectorCount = 0
        for (group in AquariumControls.groups) {
            activate("aquarium-group-${group.id}")
            for (effect in group.effects) {
                activateDescription("EXPAND ${effect.name.uppercase(Locale.ROOT)} DETAILS")
                for (control in (effect.primary + effect.details).filter { it.spec.isDiscrete }) {
                    val count = ((control.spec.maximum - control.spec.minimum) / control.spec.step).toInt() + 1
                    for (index in 0 until count) {
                        val tag = "aquarium-option-${control.id}-$index"
                        activate(tag)
                        tagged(tag).assertIsSelected()
                        assertEquals(control.spec.minimum + index * control.spec.step,
                            editor.render.settings.parameters.getValue(control.id), 0f)
                    }
                    selectorCount++
                }
            }
        }
        assertEquals("every native selector needs an exact-value UI", 8, selectorCount)
        activate("aquarium-group-motion")
        val control = AquariumControls.control("aqua_tide_rate")
        tagged("aquarium-control-${control.id}").performSemanticsAction(SemanticsActions.SetProgress) { change ->
            assertTrue(change(.37f))
        }
        pump()
        val actual = editor.render.settings.parameters.getValue(control.id)
        assertEquals(control.fromNormalized(.37f), actual, 0f)
        val node = tagged("aquarium-control-${control.id}").fetchSemanticsNode()
        assertNotNull("a continuous control needs progress semantics", node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        assertTrue("TalkBack must read physical Hz rather than a normalized fraction",
            node.config.getOrNull(SemanticsProperties.StateDescription)?.contains(control.format(actual)) == true)

        activateDescription("EXPAND PRINT SETTINGS")
        compose.runOnIdle { editor = editor.withRender(editor.render.copy(bpm = 123.5)) }
        pump()
        val beforeTempo = editor.render
        activate("aquarium-tempo-up")
        assertEquals(beforeTempo.copy(bpm = beforeTempo.bpm + 1.0), editor.render)
        activate("aquarium-tempo-down")
        assertEquals("one BPM up then down must preserve the complete render", beforeTempo, editor.render)
        compose.runOnIdle { editor = editor.withRender(editor.render.copy(bpm = 0.0)) }
        pump()
        tagged("aquarium-tempo-down").assertIsNotEnabled()
        tagged("aquarium-tempo-up").assertIsEnabled()
        compose.runOnIdle { editor = editor.withRender(editor.render.copy(bpm = 1000.0)) }
        pump()
        tagged("aquarium-tempo-up").assertIsNotEnabled()
        tagged("aquarium-tempo-down").assertIsEnabled()
    }

    @Test
    fun phone_width_actions_have_complete_labels_touch_targets_and_busy_semantics() {
        showPanel(AquariumEditor(context()).selectPreset("full"))
        revealPanelAction("aquarium-cancel")
        assertReadsInFull("AQUARIUM · PAD")
        assertReadsInFull("◄ PAD")
        tagged("aquarium-cancel").assertHeightIsAtLeast(44.dp)
        for ((id, label) in listOf("before" to "BEFORE", "hear" to "PREVIEW", "keep" to "KEEP", "remove" to "REMOVE", "undo" to "UNDO")) {
            revealPanelAction("aquarium-$id")
            tagged("aquarium-$id").assertHeightIsAtLeast(44.dp)
            assertReadsInFull(label)
        }
        tagged("aquarium-remove").assertIsNotEnabled()
        tagged("aquarium-undo").assertIsNotEnabled()
        assertNotNull(tagged("aquarium-cancel").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        compose.runOnIdle { panelPreviewing = true }
        pump()
        tagged("aquarium-cancel").assertIsEnabled()
        tagged("aquarium-before").assertIsEnabled()
        tagged("aquarium-control-aqua_fathom_depth").assertIsEnabled()
        compose.runOnIdle { panelBusy = true }
        pump()
        for (id in listOf("before", "hear", "keep", "remove", "undo", "cancel"))
            tagged("aquarium-$id").assertIsNotEnabled()
        tagged("aquarium-preset-full").assertIsNotEnabled()
        tagged("aquarium-bypass-fathom").assertIsNotEnabled()
        tagged("aquarium-control-aqua_fathom_depth").assertIsNotEnabled()
    }

    private data class Fixture(val entry: KitShelf.Entry, val original: KitPad, val neighbour: KitPad)

    /** Distinct stereo hard/mono soft source layers, root note, and opaque prior recipe. */
    private fun fixture(applied: AquariumRenderRecipe? = null): Fixture {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = File(cache, "aquarium-panel-${System.nanoTime()}").apply { check(mkdirs()) }
        directories += directory
        val hard = Snip(FloatArray(2048) { sample ->
            val channel = sample % 2
            (.18 * sin(2 * PI * (if (channel == 0) 233 else 519) * (sample / 2) / 48000)).toFloat()
        }, 2, 48000)
        val soft = Snip(FloatArray(768) { frame -> (.1 * sin(2 * PI * 233 * frame / 44100)).toFloat() }, 1, 44100)
        WavWriter.write(File(directory, "hard.wav"), hard, WavWriter.BitDepth.PCM_24,
            allowNonMpcRate = true, smpl = SmplChunk(60))
        WavWriter.write(File(directory, "soft.wav"), soft, WavWriter.BitDepth.PCM_24)
        WavWriter.write(File(directory, "neighbour.wav"), soft, WavWriter.BitDepth.PCM_24)
        val prior = Json.parse("""{"capture":1,"future":{"opaque":[1,"keep",null]}}""") as JsonValue.Obj
        val original = KitPad(1, "hard.wav", drumClass = DrumClass.TONAL, recipe = prior,
            velocityLayers = listOf(KitLayer("soft.wav", 0, 63), KitLayer("hard.wav", 64, 127)))
        val neighbour = KitPad(2, "neighbour.wav", drumClass = DrumClass.TONAL)
        KitStore.save(Kit("Aquarium panel", listOf(original, neighbour), tempoBpm = 92f), directory)
        if (applied != null) {
            val model = KitBuilderModel.open(directory)
            AquariumPadSheet.apply(model, 1, applied, AquariumPadRenderer)
            model.save()
        }
        return Fixture(KitShelf.Entry(directory, KitStore.load(directory)), original, neighbour)
    }

    private fun showSheet(fixture: Fixture, renderer: AquariumOfflineRenderer = AquariumPadRenderer) {
        currentSlot = 1
        openBox = PadSheetBoxes.Box.TREATMENT.name
        screenVisible = true
        setPhoneContent {
            if (screenVisible) PadSheetScreen(
                entry = fixture.entry,
                slot = currentSlot,
                backLabel = "◄ PADS",
                onSlotChange = { currentSlot = it },
                onBack = {}, onToast = {}, onNavigateTape = {}, onGrainField = {}, onSplice = {}, onStack = {},
                onKitUpdated = { updated = it }, onShelfAssetWritten = {}, appScope = appScope,
                workshopOpen = false, openBox = openBox, onOpenBox = { openBox = it },
                pedalRow = PedalSlate.DEFAULT, onPedalRowChange = {},
                aquariumRenderer = renderer,
            )
        }
        waitForText("PAD ${PadBanks.tag(1)}")
        waitForTag("aquarium-open")
        activate("aquarium-open")
        waitForTag("aquarium-panel")
    }

    private fun files(directory: File): Map<String, List<Byte>> = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }

    private fun saved(fixture: Fixture): KitPad = KitStore.load(fixture.entry.dir).pad(1)!!

    private fun waitUntilEnabled(id: String) = waitFor("$id enabled", timeoutMillis = 60_000) {
        val node = compose.onAllNodesWithTag(id).fetchSemanticsNodes().singleOrNull()
        node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
    }

    private fun waitForPreviewEnd() = waitFor("the Aquarium preview to finish", timeoutMillis = 60_000) {
        compose.onAllNodesWithText("RENDERING PREVIEW…", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }

    private class RecordingRenderer : AquariumOfflineRenderer {
        data class Call(val channels: Int, val rate: Int, val frames: Int, val recipe: AquariumRenderRecipe)
        val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()
        val completed = AtomicInteger()
        override fun render(source: Snip, recipe: AquariumRenderRecipe): Snip {
            calls += Call(source.channels, source.sampleRate, source.frameCount, recipe)
            try { return AquariumPadRenderer.render(source, recipe) }
            finally { completed.incrementAndGet() }
        }
    }

    @Test
    fun real_pad_preview_then_keep_prints_all_layers_and_reload_remove_undo_preserve_sources() {
        val originalContext = context().copy(settings = AquariumRecipe(mapOf("aqua_surface_mix" to 1f), seed = 0xffff_ffffL))
        val fixture = fixture(originalContext)
        val renderer = RecordingRenderer()
        val originalHard = File(fixture.entry.dir, fixture.original.sampleFile).readBytes()
        showSheet(fixture, renderer)
        val before = files(fixture.entry.dir)
        activate("aquarium-before")
        waitForText("BEFORE · original main sample")
        waitForPreviewEnd()
        assertEquals("BEFORE must bypass DSP and play the saved source", 0, renderer.calls.size)
        assertEquals(before, files(fixture.entry.dir))
        activate("aquarium-preset-surface")
        val selected = originalContext.copy(settings = AquariumRecipe(
            AquariumControls.preset("surface").settings.parameters,
            originalContext.settings.seed, originalContext.settings.model,
        ))
        assertEquals("preset selection stays staged", before, files(fixture.entry.dir))
        activate("aquarium-hear")
        waitFor("the native main-sample preview", timeoutMillis = 60_000) { renderer.completed.get() == 1 }
        waitForPreviewEnd()
        waitForText("PREVIEW READY · main sample")
        tagged("aquarium-error").assertDoesNotExist()
        assertEquals(1, renderer.calls.size)
        val preview = renderer.calls.single()
        assertEquals(2, preview.channels)
        assertEquals(48000, preview.rate)
        assertEquals(1024, preview.frames)
        assertEquals(selected, preview.recipe)
        assertEquals("PREVIEW must not change live or hidden kit files", before, files(fixture.entry.dir))
        assertNull(updated)
        compose.onNodeWithText("PREVIEW PLAYS THE MAIN SAMPLE. KEEP UPDATES EVERY VELOCITY LAYER.", useUnmergedTree = true).assertExists()

        waitUntilEnabled("aquarium-keep")
        activate("aquarium-keep")
        waitFor("KEEP to save exactly the displayed recipe", timeoutMillis = 60_000) {
            AquariumPadSheet.read(updated?.pad(1)?.recipe) == selected
        }
        val kept = saved(fixture)
        assertEquals(selected, AquariumPadSheet.read(kept.recipe))
        val keptCalls = renderer.calls.drop(1)
        assertTrue("KEEP must render both unique source layers", keptCalls.map { Triple(it.channels, it.rate, it.frames) }.toSet()
            .containsAll(setOf(Triple(1, 44100, 768), Triple(2, 48000, 1024))))
        assertTrue(keptCalls.all { it.recipe == selected })
        assertEquals(fixture.neighbour, KitStore.load(fixture.entry.dir).pad(2))
        assertNotEquals(fixture.original.sampleFile, kept.sampleFile)
        assertEquals(2, kept.velocityLayers.size)
        for (layer in kept.velocityLayers) {
            val sourceName = if (layer.velStart == 0) "soft.wav" else "hard.wav"
            assertNotEquals(sourceName, layer.sampleFile)
            val source = WavReader.read(File(fixture.entry.dir, sourceName))
            val wet = WavReader.read(File(fixture.entry.dir, layer.sampleFile))
            assertEquals(2, wet.channels)
            assertEquals(source.sampleRate, wet.sampleRate)
            assertEquals(source.frameCount + selected.tailFrames(source.sampleRate), wet.frameCount)
        }
        assertEquals(60, WavReader.readSmplStrict(File(fixture.entry.dir, kept.sampleFile))!!.rootNote)
        assertArrayEquals(originalHard, File(fixture.entry.dir, fixture.original.sampleFile).readBytes())
        val keptAudio = File(fixture.entry.dir, kept.sampleFile).readBytes()

        // Dispose and remount the real screen: its model must reopen kit.json.
        compose.runOnIdle { screenVisible = false }
        pump()
        compose.runOnIdle { screenVisible = true }
        pump()
        waitForTag("aquarium-open")
        activate("aquarium-open")
        waitForTag("aquarium-panel")
        tagged("aquarium-preset-surface").assertIsSelected()
        assertEquals(selected, AquariumPadSheet.read(saved(fixture).recipe))
        waitUntilEnabled("aquarium-remove")
        activate("aquarium-remove")
        waitFor("REMOVE to restore the source descriptor", timeoutMillis = 60_000) { updated?.pad(1) == fixture.original }
        assertEquals(fixture.original, saved(fixture))
        assertArrayEquals(originalHard, File(fixture.entry.dir, saved(fixture).sampleFile).readBytes())
        waitUntilEnabled("aquarium-undo")
        activate("aquarium-undo")
        waitFor("UNDO to restore the kept Aquarium descriptor", timeoutMillis = 60_000) { updated?.pad(1) == kept }
        assertEquals(kept, saved(fixture))
        assertArrayEquals(keptAudio, File(fixture.entry.dir, saved(fixture).sampleFile).readBytes())
    }

    @Test
    fun closing_a_delayed_preview_discards_its_result_and_unsaved_draft() {
        val fixture = fixture()
        val renderer = GatedRenderer().also { gates += it }
        showSheet(fixture, renderer)
        val before = files(fixture.entry.dir)
        activate("aquarium-preset-full")
        activate("aquarium-hear")
        waitFor("the held preview to enter native rendering") { renderer.entered.count == 0L }
        activate("aquarium-cancel")
        tagged("aquarium-panel").assertDoesNotExist()
        renderer.release.countDown()
        waitFor("the cancelled native call to return") { renderer.finished.count == 0L }
        pump()
        assertNull(updated)
        assertEquals(before, files(fixture.entry.dir))
        assertEquals(fixture.original, saved(fixture))
        activate("aquarium-open")
        waitForTag("aquarium-panel")
        tagged("aquarium-preset-dry").assertIsSelected()
        compose.onNodeWithText("PREVIEW READY · main sample", useUnmergedTree = true).assertDoesNotExist()
        tagged("aquarium-error").assertDoesNotExist()
        assertEquals(1, renderer.calls.get())
        assertEquals(before, files(fixture.entry.dir))
    }

    @Test
    fun changing_controls_during_a_delayed_preview_does_not_adopt_the_old_result() {
        val fixture = fixture()
        val renderer = GatedRenderer().also { gates += it }
        showSheet(fixture, renderer)
        val before = files(fixture.entry.dir)
        activate("aquarium-preset-full")
        activate("aquarium-hear")
        waitFor("the held preview") { renderer.entered.count == 0L }
        activate("aquarium-preset-reef")
        tagged("aquarium-preset-reef").assertIsSelected()
        renderer.release.countDown()
        waitFor("the obsolete native call to return") { renderer.finished.count == 0L }
        waitForPreviewEnd()
        tagged("aquarium-preset-reef").assertIsSelected()
        compose.onNodeWithText("PREVIEW READY · main sample", useUnmergedTree = true).assertDoesNotExist()
        tagged("aquarium-error").assertDoesNotExist()
        assertNull(updated)
        assertEquals(before, files(fixture.entry.dir))
        assertEquals(fixture.original, saved(fixture))
    }

    @Test
    fun header_before_replaces_a_held_preview_without_delivering_the_old_wet_result() {
        val fixture = fixture()
        val renderer = GatedRenderer().also { gates += it }
        showSheet(fixture, renderer)
        val before = files(fixture.entry.dir)
        activate("aquarium-preset-full")
        activate("aquarium-hear")
        waitFor("the wet preview to be held") { renderer.entered.count == 0L }
        // This is the pinned PAD header, not the panel's lower BEFORE action.
        // It must remain available even on an untreated pad with no bin take.
        activateDescription("◀ BEFORE")
        renderer.release.countDown()
        waitFor("the superseded wet render to return") { renderer.finished.count == 0L }
        waitForText("BEFORE · original main sample")
        waitForPreviewEnd()
        assertEquals("BEFORE must read the source without another native render", 1, renderer.calls.get())
        compose.onNodeWithText("PREVIEW READY · main sample", useUnmergedTree = true).assertDoesNotExist()
        tagged("aquarium-error").assertDoesNotExist()
        assertNull(updated)
        assertEquals(before, files(fixture.entry.dir))
        assertEquals(fixture.original, saved(fixture))
    }

    @Test
    fun an_external_bench_change_cancels_a_hidden_preview_and_discards_its_draft() {
        val fixture = fixture()
        val renderer = GatedRenderer().also { gates += it }
        showSheet(fixture, renderer)
        val before = files(fixture.entry.dir)
        activate("aquarium-preset-full")
        activate("aquarium-hear")
        waitFor("the preview before changing benches") { renderer.entered.count == 0L }
        // openBox is caller-owned. Changing it here exercises the same input
        // update as parent navigation, including paths that do not tap Close.
        compose.runOnIdle { openBox = "BENCH" }
        pump()
        tagged("aquarium-panel").assertDoesNotExist()
        renderer.release.countDown()
        waitFor("the hidden panel's native call to return") { renderer.finished.count == 0L }
        pump()
        assertNull(updated)
        assertEquals(before, files(fixture.entry.dir))
        compose.runOnIdle { openBox = PadSheetBoxes.Box.TREATMENT.name }
        pump()
        waitForTag("aquarium-open")
        activate("aquarium-open")
        waitForTag("aquarium-panel")
        tagged("aquarium-preset-dry").assertIsSelected()
        compose.onNodeWithText("PREVIEW READY · main sample", useUnmergedTree = true).assertDoesNotExist()
        tagged("aquarium-error").assertDoesNotExist()
        assertEquals(fixture.original, saved(fixture))
    }

    @Test
    fun render_errors_leave_the_pad_unchanged_and_offer_a_way_back() {
        val fixture = fixture()
        val refusal = "Aquarium test render refusal"
        val renderer = AquariumOfflineRenderer { _, _ -> throw IllegalArgumentException(refusal) }
        showSheet(fixture, renderer)
        val before = files(fixture.entry.dir)
        activate("aquarium-preset-surface")
        activate("aquarium-hear")
        waitForTag("aquarium-error")
        waitForText(refusal)
        waitForPreviewEnd()
        assertNull(updated)
        assertEquals(before, files(fixture.entry.dir))
        assertEquals(fixture.original, saved(fixture))
        tagged("aquarium-cancel").assertIsEnabled()
        activate("aquarium-cancel")
        tagged("aquarium-panel").assertDoesNotExist()
    }

    @Test
    fun malformed_saved_aquarium_is_visible_but_cannot_be_previewed_or_overwritten() {
        val fixture = fixture()
        val broken = fixture.original.copy(recipe = Json.parse("""{"aquariumPad":{}}""") as JsonValue.Obj)
        val kit = fixture.entry.kit.copy(pads = listOf(broken, fixture.neighbour))
        KitStore.save(kit, fixture.entry.dir)
        val damagedFixture = fixture.copy(entry = KitShelf.Entry(fixture.entry.dir, kit))
        showSheet(damagedFixture)
        val before = files(fixture.entry.dir)
        waitForText("This Aquarium recipe needs repair before editing")
        tagged("aquarium-hear").assertIsNotEnabled()
        tagged("aquarium-keep").assertIsNotEnabled()
        tagged("aquarium-remove").assertIsNotEnabled()
        tagged("aquarium-undo").assertIsNotEnabled()
        assertEquals(before, files(fixture.entry.dir))
        assertEquals(broken, saved(fixture))
        tagged("aquarium-cancel").assertIsEnabled()
    }

    private class GatedRenderer : AquariumOfflineRenderer {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        override fun render(source: Snip, recipe: AquariumRenderRecipe): Snip {
            calls.incrementAndGet()
            entered.countDown()
            try {
                check(release.await(30, TimeUnit.SECONDS)) { "test renderer release timed out" }
                return AquariumPadRenderer.render(source, recipe)
            } finally { finished.countDown() }
        }
    }
}
