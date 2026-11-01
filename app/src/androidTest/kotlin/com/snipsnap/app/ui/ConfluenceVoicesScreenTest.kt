package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Bench
import com.snipsnap.shell.ConfluenceSelectedWorkshop
import com.snipsnap.shell.KitBuilderModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real bundled passages, retained preview PCM, guarded kit writes and reopened controls. */
@RunWith(AndroidJUnit4::class)
class ConfluenceVoicesScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directories = mutableListOf<File>()
    private var shown by mutableStateOf(true)
    private var entry by mutableStateOf<KitShelf.Entry?>(null)

    @After
    fun cleanUp() {
        compose.runOnIdle { shown = false }
        pump()
        appScope.cancel()
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun source_reference_and_processed_comparisons_save_the_exact_heard_group_and_reopen() {
        val bank = ConfluenceSelectedWorkshop.bank
        val voice = bank.voice("kulning-03")
        val model = kit()
        model.assign(1, smallHit(), DrumClass.UNKNOWN, "KEEP THIS PAD")
        model.save()
        val original = model.pad(1)!!
        val originalBytes = File(model.kitDir, original.sampleFile).readBytes()
        val toasts = CopyOnWriteArrayList<String>()
        show(model, toasts)
        waitFor("all recorded passage controls", timeoutMillis = 60_000) { hasText(bank.voices.first().title) }
        click("Choose voice passage")
        for (available in bank.voices) {
            compose.onNodeWithContentDescription("Choose passage ${available.title}").fetchSemanticsNode()
        }
        click("Choose passage ${voice.title}")
        assertTrue(hasText(voice.performerCredit))
        click("SOURCE CREDITS & LICENCE")
        assertTrue(hasText(ConfluenceSelectedWorkshop.credits(voice.id)))
        click("CLOSE SOURCE CREDITS")
        for (mode in listOf(ConfluenceSelectedWorkshop.Mode.SOURCE, ConfluenceSelectedWorkshop.Mode.REFERENCE, ConfluenceSelectedWorkshop.Mode.SOLO, ConfluenceSelectedWorkshop.Mode.PAIR)) {
            click("Voice mode: ${mode.name}")
            compose.onNodeWithContentDescription("SAVE → A02").assertIsNotEnabled()
            click("HEAR")
            waitFor("the actual ${mode.name} comparison", timeoutMillis = 180_000) { hasText("${mode.name} PREVIEW READY") }
        }
        click("Voice mode: GROUP")
        setSlider("VARIATION", .4f)
        setSlider("ROOM", .25f)
        compose.onNodeWithContentDescription("SAVE → A02").assertIsNotEnabled()
        click("HEAR")
        waitFor("the changed group preview", timeoutMillis = 180_000) { hasText("GROUP PREVIEW READY") }
        assertEquals("comparison must not create a pad", 1, KitStore.load(model.kitDir).pads.size)
        val expectedSettings = ConfluenceSelectedWorkshop.Settings(voice.id, ConfluenceSelectedWorkshop.Mode.GROUP, .4f, .25f)
        val exact = ConfluenceSelectedWorkshop.prepare(expectedSettings)
        val expectedBytes = ByteArrayOutputStream().also { WavWriter.write(it, exact.snip) }.toByteArray()
        val beforeSave = toasts.size
        click("SAVE → A02")
        waitForSavedPad("the source-backed new pad", toasts, after = beforeSave, slot = "A02")
        val saved = KitStore.load(model.kitDir)
        assertEquals(original, saved.pad(1))
        assertArrayEquals(originalBytes, File(model.kitDir, original.sampleFile).readBytes())
        val made = checkNotNull(saved.pad(2))
        assertArrayEquals(expectedBytes, File(model.kitDir, made.sampleFile).readBytes())
        assertEquals(exact.previewLevel, made.level, 0f)
        assertEquals(expectedSettings, ConfluenceSelectedWorkshop.openPad(model.kitDir, made))
        click("Next voice passage")
        compose.onNodeWithContentDescription("SAVE → A03").assertIsNotEnabled()
        click("Open voice pad A02")
        waitFor("the saved passage settings", timeoutMillis = 60_000) { hasText("SAVED SETTINGS READY · HEAR BEFORE SAVING") }
        assertEquals("40%", sliderText("VARIATION"))
        assertEquals("25%", sliderText("ROOM"))
        compose.onNodeWithContentDescription("SAVE → A03").assertIsNotEnabled()
        click("HEAR")
        waitFor("the reopened group preview", timeoutMillis = 180_000) { hasText("GROUP PREVIEW READY") }
    }

    @Test
    fun an_occupied_destination_refuses_then_offers_the_next_empty_pad_without_overwriting() {
        val model = kit()
        val toasts = CopyOnWriteArrayList<String>()
        show(model, toasts)
        waitFor("the voice picker", timeoutMillis = 60_000) { hasText(ConfluenceSelectedWorkshop.bank.voices.first().title) }
        click("Voice mode: SOURCE")
        click("HEAR")
        waitFor("the source preview", timeoutMillis = 60_000) { hasText("SOURCE PREVIEW READY") }
        val occupied = runBlocking { KitWrites.mutex.withLock {
            val fresh = KitBuilderModel.open(model.kitDir)
            fresh.assign(1, smallHit(), DrumClass.UNKNOWN, "ARRIVED FIRST")
            fresh.save(); fresh.kit.pad(1)!!
        } }
        val occupiedBytes = File(model.kitDir, occupied.sampleFile).readBytes()
        click("SAVE → A01")
        waitFor("the occupied-pad refusal", timeoutMillis = 60_000) { toasts.any { it.contains("occupied") } }
        assertEquals(occupied, KitStore.load(model.kitDir).pad(1))
        assertArrayEquals(occupiedBytes, File(model.kitDir, occupied.sampleFile).readBytes())
        waitFor("the next destination label") { hasText("SAVE → A02") }
        val beforeRetry = toasts.size
        click("SAVE → A02")
        waitForSavedPad("the explicit retry to a new empty pad", toasts, after = beforeRetry, slot = "A02")
        assertEquals(2, KitStore.load(model.kitDir).pads.size)
    }

    @Test
    fun make_opens_the_voice_bench_without_a_kit() {
        var opened: Bench? = null
        setPhoneContent { if (shown) MakeList(onOpen = { opened = it }, hasKit = false) }
        click("CONFLUENCE VOICES ▸")
        assertEquals(Bench.CONFLUENCE_VOICES, opened)
    }

    private fun kit(): KitBuilderModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "cultural-voices-ui-${System.nanoTime()}")
        directories += directory
        return KitBuilderModel.create("VOICE PASSAGES UI", directory)
    }

    private fun show(model: KitBuilderModel, toasts: MutableList<String>) {
        entry = KitShelf.Entry(model.kitDir, model.kit)
        setPhoneContent {
            if (shown) ConfluenceVoicesScreen(entry, appScope, onToast = { toasts += it }, onKitUpdated = { kit -> entry = entry?.copy(kit = kit) })
        }
    }

    private fun smallHit(): Snip = Snip(floatArrayOf(.25f, -.25f, 0f), 1, WavWriter.MPC_SAMPLE_RATE)
    private fun waitForSavedPad(what: String, toasts: List<String>, after: Int, slot: String) {
        val expected = "VOICE PASSAGE SAVED ON $slot"
        waitFor(what, timeoutMillis = 180_000) {
            val received = toasts.drop(after)
            received.firstOrNull { it != expected }?.let { refusal ->
                throw AssertionError("$what was refused: $refusal")
            }
            expected in received
        }
    }
    private fun hasText(text: String): Boolean = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun click(label: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick) { action -> assertTrue(action()) }
        pump()
    }
    private fun setSlider(label: String, fraction: Float) {
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { action -> assertTrue(action(fraction)) }
        pump()
    }
    private fun sliderText(label: String): String? = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
}
