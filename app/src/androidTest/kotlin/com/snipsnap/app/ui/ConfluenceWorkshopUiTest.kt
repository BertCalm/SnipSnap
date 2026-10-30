package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.ConfluenceWorkshop
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.PadSheetBoxes
import com.snipsnap.shell.PedalSlate
import com.snipsnap.shell.StarterKits
import com.snipsnap.synth.ConfluenceAssemblyRecipe
import com.snipsnap.synth.ConfluenceRole
import com.snipsnap.synth.ConfluenceVoiceBank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Exercises PadSheet's real gathering workflow, including bank rendering,
 * preview completion, file-backed assignment and reopening the saved recipe.
 * Controls use their accessibility actions: the shared test clock is frozen,
 * so animated performScrollTo would hang (see ComposeScreenTest).
 * This checks the workflow and persisted audio, not subjective sound quality.
 */
@RunWith(AndroidJUnit4::class)
class ConfluenceWorkshopUiTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()
    private var currentSlot by mutableStateOf(1)
    private var openBox by mutableStateOf<String?>(PadSheetBoxes.Box.MAKE.name)

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun hear_then_make_preserves_original_and_reopens_the_new_gathering_controls() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = File(cache, "gathering-ui-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += directory
        val bank = ConfluenceVoiceBank.bundled()
        val recipe = bank.preset(bank.mainPresetId).recipe.copy(space = .20f)
        KitAssembler.assembleArranged("GATHERING UI", listOf(bank.arrangedPad(recipe)), directory)
        bank.writePortableBank(File(directory, StarterKits.CONFLUENCE_BANK_DIR))
        // Independent pad edits belong to the original, not the new source render.
        val initialModel = KitBuilderModel.open(directory)
        initialModel.update(1) { it.copy(level = .31f, cutoff = .40f) }
        initialModel.save()
        val original = initialModel.pad(1)!!
        val originalAudio = File(directory, original.sampleFile).readBytes()
        val entry = KitShelf.Entry(directory, initialModel.kit)
        val toasts = CopyOnWriteArrayList<String>()

        setPhoneContent {
            PadSheetScreen(
                entry = entry,
                slot = currentSlot,
                onSlotChange = { currentSlot = it },
                onBack = {},
                onToast = { toasts += it },
                onNavigateTape = {},
                onGrainField = {},
                onSplice = {},
                onStack = {},
                onKitUpdated = {},
                onShelfAssetWritten = {},
                appScope = appScope,
                workshopOpen = false,
                openBox = openBox,
                onOpenBox = { openBox = it },
                pedalRow = PedalSlate.DEFAULT,
                onPedalRowChange = {},
            )
        }
        waitFor("the original gathering's saved controls", timeoutMillis = 30_000) {
            hasText("PAD A01") && sliderState("ROOM") == "20%"
        }

        clickAction("Low voice: ROUNDED OO")
        compose.onNodeWithContentDescription("ROOM", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(.70f))
            }
        pump()
        assertEquals("70%", sliderState("ROOM"))

        clickAction("HEAR")
        waitFor("the actual rendered preview", timeoutMillis = 120_000) {
            hasText("PREVIEW READY · ORIGINAL KEPT")
        }
        val afterHear = KitStore.load(directory)
        assertEquals("HEAR must not create a pad", 1, afterHear.pads.size)
        assertEquals(original, afterHear.pad(1))
        assertArrayEquals(originalAudio, File(directory, original.sampleFile).readBytes())

        clickAction("MAKE VARIATION → A02")
        waitFor("the saved variation and destination pad", timeoutMillis = 120_000) {
            toasts.contains("GATHERING MADE ON A02 · ORIGINAL KEPT") &&
                hasText("PAD A02") && sliderState("ROOM") == "70%"
        }
        val saved = KitStore.load(directory)
        val made = checkNotNull(saved.pad(2))
        assertEquals(2, saved.pads.size)
        assertEquals(original, saved.pad(1))
        assertArrayEquals(originalAudio, File(directory, original.sampleFile).readBytes())
        assertFalse("the changed voices and room must produce a different render",
            originalAudio.contentEquals(File(directory, made.sampleFile).readBytes()))
        assertNull("the original filter must stay on the original", made.cutoff)
        assertNull("gathering controls belong to source.assemblyRecipe", made.recipe)
        assertEquals("1", made.source["editingParentSlot"])
        val savedRecipe = ConfluenceAssemblyRecipe.fromJsonText(made.source.getValue("assemblyRecipe"))
        assertEquals(.70f, savedRecipe.space, .0001f)
        assertEquals(bank.voice("round-oo").region,
            savedRecipe.roles.single { it.role == ConfluenceRole.FOUNDATION }.region)
        val reopened = ConfluenceWorkshop.open(directory, made).initialControls
        assertEquals("round-oo", reopened.foundationVoiceId)
        assertEquals(.70f, reopened.space, .0001f)

        // Navigate away and back: the visible state must come from each pad's
        // saved source recipe, rather than the previous screen's local draft.
        clickAction("◄ A01")
        waitFor("the unchanged original controls") {
            hasText("PAD A01") && sliderState("ROOM") == "20%"
        }
        clickAction("A02 ►")
        waitFor("the variation controls after reopening") {
            hasText("PAD A02") && sliderState("ROOM") == "70%"
        }
    }

    private fun hasText(text: String): Boolean =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun sliderState(label: String): String? =
        compose.onAllNodesWithContentDescription(label, useUnmergedTree = true)
            .fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun clickAction(label: String) {
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue(click()) }
        pump()
    }
}
