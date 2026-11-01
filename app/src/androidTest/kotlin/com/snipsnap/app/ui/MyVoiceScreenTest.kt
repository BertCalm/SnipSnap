package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.ConfluencePersonalLibrary
import com.snipsnap.shell.ConfluencePersonalWorkshop
import com.snipsnap.shell.KitBuilderModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Real source files, quality assessment, rendering, reopening and kit writes through the UI. */
@RunWith(AndroidJUnit4::class)
class MyVoiceScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val created = mutableListOf<File>()
    private var shown by mutableStateOf(true)

    @After
    fun cleanUp() {
        compose.runOnIdle { shown = false }
        pump()
        appScope.cancel()
        created.forEach { it.deleteRecursively() }
    }

    @Test
    fun trimming_a_reopened_voice_still_previews_and_preserves_the_crop_in_a_new_pad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = File(context.filesDir, ConfluencePersonalWorkshop.LIBRARY_DIR)
        val title = "UI VOICE ${System.nanoTime()}"
        val prepared = ConfluencePersonalWorkshop.prepare(heldVowel())
        val voice = ConfluencePersonalWorkshop.saveVoice(library, prepared, title, ConfluencePersonalLibrary.Origin.IMPORT)
        created += voice.directory
        val directory = File(context.cacheDir, "my-voice-ui-${System.nanoTime()}")
        created += directory
        val model = KitBuilderModel.create("MY VOICE UI", directory)
        model.assign(1, Snip(floatArrayOf(.25f, -.25f, 0f), 1, 44_100), DrumClass.UNKNOWN, "KEEP ORIGINAL")
        model.save()
        val original = model.pad(1)!!
        val originalBytes = File(directory, original.sampleFile).readBytes()
        val toasts = CopyOnWriteArrayList<String>()
        setPhoneContent {
            if (shown) MyVoiceScreen(KitShelf.Entry(directory, model.kit), appScope, onToast = { toasts += it }, onKitUpdated = {})
        }
        waitFor("the saved personal source", timeoutMillis = 30_000) { hasText(title) }
        click("Open personal voice $title")
        waitFor("the source to reopen", timeoutMillis = 60_000) { hasText("SAVED VOICE READY") }
        click("ORIGINAL")
        waitFor("the original preview", timeoutMillis = 60_000) { hasText("ORIGINAL PREVIEW READY") }
        click("ADJUST TRIM")
        setSlider("START", .15f)
        setSlider("END", .80f)
        assertTrue(hasText("TRIM CHANGED · CHECK THIS PASSAGE"))
        assertTrue(hasText("1.95S SELECTED"))
        // ORIGINAL is usable immediately after editing, before processed-source requalification.
        click("ORIGINAL")
        waitFor("original playback after both trim boundaries changed", timeoutMillis = 60_000) {
            hasText("ORIGINAL PREVIEW READY")
        }
        click("USE THIS TRIM")
        waitFor("the trimmed source to requalify", timeoutMillis = 60_000) {
            hasText("TRIM READY. COMPARE YOUR VOICE BELOW.")
        }
        click("SOLO")
        waitFor("the real solo render", timeoutMillis = 120_000) { hasText("SOLO PREVIEW READY") }
        click("ENSEMBLE")
        waitFor("the real ensemble render", timeoutMillis = 120_000) { hasText("ENSEMBLE PREVIEW READY") }
        assertEquals("preview must not create a pad", 1, KitStore.load(directory).pads.size)
        val named = "$title COPY"
        compose.onNodeWithContentDescription("Voice name").performTextReplacement(named)
        pump()
        click("SAVE VOICE")
        waitFor("the named personal voice", timeoutMillis = 60_000) { hasText("VOICE SAVED · READY FOR A NEW PAD") }
        val kept = ConfluencePersonalWorkshop.listVoices(library).single { it.name == named }
        val savedVoice = ConfluencePersonalWorkshop.loadVoice(library, kept.id)
        created += savedVoice.directory
        val fullFrames = voice.canonicalRawSnip.frameCount
        val expectedStart = (.15f * fullFrames).roundToInt()
        val expectedEnd = (.80f * fullFrames).roundToInt()
        assertEquals(expectedStart, savedVoice.selectionStartFrame)
        assertEquals(expectedEnd, savedVoice.selectionEndFrame)
        click("SAVE ENSEMBLE → A02")
        waitFor("the new ordinary pad", timeoutMillis = 120_000) { toasts.contains("MY VOICE SAVED ON A02") }
        val saved = KitStore.load(directory)
        assertEquals(original, saved.pad(1))
        assertArrayEquals(originalBytes, File(directory, original.sampleFile).readBytes())
        val made = checkNotNull(saved.pad(2))
        assertEquals(ConfluencePersonalWorkshop.ENGINE, made.source["engine"])
        assertEquals(kept.id, made.source["personalVoiceId"])
        val reopened = ConfluencePersonalWorkshop.openPad(directory, made)
        assertEquals(kept.id, reopened.id)
        assertEquals(expectedStart, reopened.selectionStartFrame)
        assertEquals(expectedEnd, reopened.selectionEndFrame)
        assertEquals(ConfluencePersonalWorkshop.Mode.ENSEMBLE, ConfluencePersonalWorkshop.savedMode(made))
        val expected = ConfluencePersonalWorkshop.preparePad(reopened, ConfluencePersonalWorkshop.Mode.ENSEMBLE)
        assertEquals(expected.previewLevel, made.level, 0f)
        val decoded = WavReader.readCapped(File(directory, made.sampleFile), 30f).snip
        assertEquals(expected.snip.channels, decoded.channels)
        assertArrayEquals(expected.snip.samples, decoded.samples, 2e-7f)
        compose.runOnIdle { shown = false }; pump()
        compose.runOnIdle { shown = true }; pump()
        waitFor("personal sources after reopening the screen", timeoutMillis = 30_000) { hasText(named) }
        click("Open personal voice $named")
        waitFor("the saved source after screen restart", timeoutMillis = 60_000) { hasText("SAVED VOICE READY") }
        assertTrue(hasText("1.95S SELECTED"))
        click("ADJUST TRIM")
        assertEquals("0.45S", sliderText("START"))
        assertEquals("2.40S", sliderText("END"))
        click("ENSEMBLE")
        waitFor("the cropped ensemble after reopening", timeoutMillis = 120_000) { hasText("ENSEMBLE PREVIEW READY") }
    }

    @Test
    fun a_kit_only_personal_source_reopens_without_the_original_phone_library() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = File(context.cacheDir, "voice-origin-${System.nanoTime()}")
        created += external
        val title = "KIT VOICE ${System.nanoTime()}"
        val voice = ConfluencePersonalWorkshop.saveVoice(external, ConfluencePersonalWorkshop.prepare(heldVowel()), title)
        val directory = File(context.cacheDir, "kit-only-voice-${System.nanoTime()}")
        created += directory
        val model = KitBuilderModel.create("RESTORED VOICE", directory)
        val rendered = ConfluencePersonalWorkshop.preparePad(voice)
        ConfluencePersonalWorkshop.makePad(model, voice, rendered, 1)
        model.save()
        external.deleteRecursively()
        val phone = File(context.filesDir, ConfluencePersonalWorkshop.LIBRARY_DIR)
        assertTrue(ConfluencePersonalWorkshop.listVoices(phone).none { it.id == voice.id })
        val toasts = CopyOnWriteArrayList<String>()
        setPhoneContent {
            if (shown) MyVoiceScreen(KitShelf.Entry(directory, model.kit), appScope, onToast = { toasts += it }, onKitUpdated = {})
        }
        waitFor("the kit's portable voice row", timeoutMillis = 30_000) { hasText("VOICES IN THIS KIT") }
        click("Open kit voice $title")
        waitFor("the kit-only source to reopen", timeoutMillis = 60_000) { hasText("SAVED VOICE READY") }
        click("SAVE ENSEMBLE → A02")
        waitFor("a new pad from the kit-only source", timeoutMillis = 120_000) { toasts.contains("MY VOICE SAVED ON A02") }
        val made = checkNotNull(KitStore.load(directory).pad(2))
        assertEquals(voice.id, ConfluencePersonalWorkshop.openPad(directory, made).id)
    }

    @Test
    fun make_has_an_accessible_my_voice_door_without_an_active_kit() {
        var opened = false
        setPhoneContent { if (shown) MakeList(onOpen = {}, hasKit = false, onMyVoice = { opened = true }) }
        click("MY VOICE ▸")
        assertTrue(opened)
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

    /** A periodic qualification fixture; no claim that this is a human recording. */
    private fun heldVowel(): Snip {
        val rate = 44_100
        return Snip(FloatArray(rate * 3) { frame ->
            val time = frame.toDouble() / rate
            val envelope = min(1.0, min(time / .08, (3.0 - time) / .08)).coerceAtLeast(0.0)
            (envelope * (.20 * sin(2 * PI * 140 * time) + .06 * sin(2 * PI * 280 * time) + .03 * sin(2 * PI * 420 * time))).toFloat()
        }, 1, rate)
    }
}
