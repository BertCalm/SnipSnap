package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.BoardSheet
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadSheetBoxes
import com.snipsnap.shell.PedalSlate
import com.snipsnap.synth.ObliterateBoard
import com.snipsnap.synth.BoardRecipe
import com.snipsnap.synth.BoardType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The Android half of editable WIDE, RUSH and OBLITERATE: staged controls must not commit,
 * KEEP must use its displayed amount, and REMOVE/UNDO must refresh the
 * screen after saving. BoardSheetTest covers DSP and snapshot integrity;
 * these tests drive the real PadSheetScreen callbacks and reopen kit.json.
 *
 * Start with TREATMENT open and use semantic actions, as PadSheetScreenTest
 * does. Nothing scrolls with ComposeScreenTest's hand-driven clock: the
 * local activate helper invokes OnClick instead of the inherited tap's
 * performScrollTo. WIDE's
 * own semantics scope distinguishes its AMT from the legacy treatment's
 * identically named slider. Its undo state lets a test wait for the real
 * asynchronous availability check instead of racing that check.
 */
@RunWith(AndroidJUnit4::class)
class WidePadSheetScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()
    private var currentSlot by mutableStateOf(1)
    private var openBoxState by mutableStateOf<String?>(PadSheetBoxes.Box.TREATMENT.name)

    @Volatile
    private var lastUpdatedKit: Kit? = null

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    private fun sourceRecipe(): JsonValue.Obj = Json.parse(
        """{"future":{"opaque":[1,"keep this",null]}}""",
    ) as JsonValue.Obj

    private fun kit(slots: List<Int>): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "wide-padsheet-test-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        val pads = slots.map { slot ->
            val name = "hit$slot.wav"
            val samples = FloatArray(4410) { i ->
                (.4 * sin(2.0 * PI * (220 + slot * 20) * i / 44100.0) * exp(-i / 1400.0)).toFloat()
            }
            WavWriter.write(File(dir, name), Snip(samples, channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE))
            KitPad(slot = slot, sampleFile = name, drumClass = DrumClass.KICK, recipe = sourceRecipe())
        }
        val kit = Kit(name = "WIDE UI TEST", pads = pads, tempoBpm = 92f)
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(entry: KitShelf.Entry) {
        currentSlot = entry.kit.pads.first().slot
        setPhoneContent {
            PadSheetScreen(
                entry = entry,
                slot = currentSlot,
                backLabel = "◄ PADS",
                onSlotChange = { currentSlot = it },
                onBack = {},
                onToast = {},
                onNavigateTape = {},
                onGrainField = {},
                onSplice = {},
                onStack = {},
                onKitUpdated = { lastUpdatedKit = it },
                onShelfAssetWritten = {},
                appScope = appScope,
                workshopOpen = false,
                openBox = openBoxState,
                onOpenBox = { openBoxState = it },
                pedalRow = PedalSlate.DEFAULT,
                onPedalRowChange = {},
            )
        }
        waitForText("PAD ${PadBanks.tag(currentSlot)}")
        waitForText("WIDE · STEREO CHARACTER")
    }

    private fun waitForText(text: String) {
        waitFor("the text \"$text\"", timeoutMillis = 60_000) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun boardAmount(type: BoardType = BoardType.WIDE) = compose.onNode(
        hasContentDescription("AMT") and hasAnyAncestor(hasContentDescription(type.name)),
        useUnmergedTree = true,
    )

    private fun amountText(type: BoardType = BoardType.WIDE): String = boardAmount(type).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.StateDescription) ?: error("WIDE AMT has no state description")

    private fun dialAmount(amount: Float, type: BoardType = BoardType.WIDE) {
        boardAmount(type).performSemanticsAction(SemanticsActions.SetProgress) { change ->
            assertTrue("WIDE AMT refused $amount", change(amount))
        }
        pump()
    }

    /** Activate even a below-fold control without starting a scroll animation. */
    private fun activate(label: String) {
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click ->
                assertTrue("the button \"$label\" refused its click", click())
            }
        pump()
    }

    private fun savedPad(entry: KitShelf.Entry, slot: Int = 1): KitPad =
        KitStore.load(entry.dir).pad(slot) ?: error("saved pad $slot disappeared")

    private fun waitForKept(amount: Float, type: BoardType = BoardType.WIDE) {
        waitFor("KEEP to report its saved amount", timeoutMillis = 60_000) {
            BoardSheet.read(lastUpdatedKit?.pad(1)?.recipe) == BoardSheet.Applied(amount, type)
        }
        waitForText("${type.name} kept at ${(amount * 100).toInt()}%")
    }

    private fun waitForUndo(type: BoardType = BoardType.WIDE) {
        // A board change can replace this scope on the next frame; absence means keep waiting.
        waitFor("${type.name}'s undo availability check", timeoutMillis = 60_000) {
            val card = compose.onAllNodesWithContentDescription(type.name, useUnmergedTree = true)
                .fetchSemanticsNodes().singleOrNull()
            card?.config?.getOrNull(SemanticsProperties.StateDescription) == "Undo available"
        }
    }

    private fun files(dir: File): Map<String, List<Byte>> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).path to it.readBytes().toList() }

    @Test
    fun staging_does_not_write_and_keep_survives_pad_navigation() {
        val entry = kit(listOf(1, 2))
        val original = entry.kit.pad(1)!!
        val otherPad = entry.kit.pad(2)!!
        show(entry)
        val before = files(entry.dir)

        dialAmount(.35f)

        assertEquals("35%", amountText())
        assertEquals("moving AMT must not change any kit file", before, files(entry.dir))
        assertEquals(original, savedPad(entry))
        assertNull(lastUpdatedKit)

        activate("KEEP")
        waitForKept(.35f)
        val kept = savedPad(entry)
        assertEquals(.35f, BoardSheet.read(kept.recipe)!!.amount, 0f)
        assertNotEquals(original.sampleFile, kept.sampleFile)
        assertEquals(2, WavReader.read(File(entry.dir, kept.sampleFile)).channels)
        assertEquals("the neighbouring pad must stay untouched", otherPad, savedPad(entry, 2))

        activate("${PadBanks.tag(2)} ►")
        waitForText("PAD ${PadBanks.tag(2)}")
        assertEquals("70%", amountText())
        activate("◄ ${PadBanks.tag(1)}")
        waitForText("PAD ${PadBanks.tag(1)}")

        assertEquals("35%", amountText())
        compose.onNodeWithText("WIDE kept at 35%", useUnmergedTree = true).assertExists()
        assertEquals("navigation must retain the saved board", kept, savedPad(entry))
    }

    @Test
    fun remove_restores_the_saved_source_recipe_and_undo_restores_the_kept_board() {
        val entry = kit(listOf(1))
        val original = entry.kit.pad(1)!!
        val originalAudio = File(entry.dir, original.sampleFile).readBytes()
        show(entry)
        dialAmount(.35f)
        activate("KEEP")
        waitForKept(.35f)
        val kept = savedPad(entry)
        val keptAudio = File(entry.dir, kept.sampleFile).readBytes()

        activate("REMOVE")
        waitFor("REMOVE to report the restored recipe", timeoutMillis = 60_000) {
            lastUpdatedKit?.pad(1) == original
        }
        waitForText("Hear a wider sound before keeping it")
        assertEquals(original, savedPad(entry))
        assertArrayEquals(originalAudio, File(entry.dir, savedPad(entry).sampleFile).readBytes())
        assertEquals("70%", amountText())

        waitForUndo()
        activate("UNDO")
        waitForKept(.35f)

        assertEquals("UNDO must restore the exact saved board recipe", kept, savedPad(entry))
        assertArrayEquals(keptAudio, File(entry.dir, savedPad(entry).sampleFile).readBytes())
        assertEquals("35%", amountText())
    }

    @Test
    fun rush_selection_is_staged_and_switch_undo_remove_keep_the_shared_original() {
        val entry = kit(listOf(1, 2))
        val original = entry.kit.pad(1)!!
        val originalAudio = File(entry.dir, original.sampleFile).readBytes()
        show(entry)
        dialAmount(.35f)
        activate("KEEP")
        waitForKept(.35f)
        val wide = savedPad(entry)
        val wideAudio = File(entry.dir, wide.sampleFile).readBytes()
        val beforeSelection = files(entry.dir)

        activate("Choose RUSH")
        waitForText("RUSH · SWELL & MOTION")
        dialAmount(.55f, BoardType.RUSH)

        assertEquals("55%", amountText(BoardType.RUSH))
        assertEquals("choosing another board must remain staged", beforeSelection, files(entry.dir))
        assertEquals(wide, savedPad(entry))
        waitForText("WIDE is active. KEEP replaces it from the original.")
        compose.onNodeWithContentDescription("REMOVE", useUnmergedTree = true).assertIsNotEnabled()

        activate("KEEP")
        waitForKept(.55f, BoardType.RUSH)
        val rush = savedPad(entry)
        assertEquals(BoardType.RUSH, BoardSheet.read(rush.recipe)!!.type)
        assertNotEquals(wide.sampleFile, rush.sampleFile)
        activate("${PadBanks.tag(2)} ►")
        waitForText("PAD ${PadBanks.tag(2)}")
        assertEquals("70%", amountText())
        activate("◄ ${PadBanks.tag(1)}")
        waitForText("PAD ${PadBanks.tag(1)}")
        waitForText("RUSH · SWELL & MOTION")
        assertEquals("55%", amountText(BoardType.RUSH))
        assertEquals(rush, savedPad(entry))
        waitForUndo(BoardType.RUSH)
        activate("UNDO")
        waitForKept(.35f)
        assertEquals(wide, savedPad(entry))
        assertArrayEquals(wideAudio, File(entry.dir, savedPad(entry).sampleFile).readBytes())
        assertEquals("35%", amountText())

        activate("Choose RUSH")
        dialAmount(.55f, BoardType.RUSH)
        activate("KEEP")
        waitForKept(.55f, BoardType.RUSH)
        activate("REMOVE")
        waitFor("RUSH REMOVE to restore the opaque original recipe", timeoutMillis = 60_000) {
            lastUpdatedKit?.pad(1) == original
        }
        assertEquals(original, savedPad(entry))
        assertArrayEquals(originalAudio, File(entry.dir, savedPad(entry).sampleFile).readBytes())
        waitForText("Hear a wider sound before keeping it")
        waitForUndo()
        activate("UNDO")
        waitForKept(.55f, BoardType.RUSH)
        assertEquals(rush, savedPad(entry))
        assertEquals("55%", amountText(BoardType.RUSH))
    }

    @Test
    fun obliterate_stages_and_saves_every_control_then_remove_and_undo_restore_exact_state() {
        val entry = kit(listOf(1, 2)); val original = entry.kit.pad(1)!!
        val originalAudio = File(entry.dir, original.sampleFile).readBytes()
        show(entry)
        val before = files(entry.dir)
        activate("Choose OBLITERATE")
        waitForText("OBLITERATE · HALO & DESTRUCTION")
        assertEquals("35%", amountText(BoardType.OBLITERATE))
        activate("CLOCK & PATTERN")
        fun dial(label: String) = requireNotNull(compose.onNode(
            hasContentDescription(label) and hasAnyAncestor(hasContentDescription("OBLITERATE")), useUnmergedTree = true,
        ).fetchSemanticsNode().config[SemanticsActions.SetProgress].action)
        val shimmer = dial("SHIMMER")
        val bpm = dial("BPM")
        // Invoke both mounted callbacks before recomposition. Neither may erase the other's edit.
        compose.runOnIdle { assertTrue(shimmer(.6f)); assertTrue(bpm(.29f)) }
        pump()
        activate("OBLITERATE division 1/8")
        activate("OBLITERATE ROOM")
        val newPattern = requireNotNull(compose.onNodeWithContentDescription("NEW PATTERN", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle { repeat(2) { assertTrue(newPattern()) } }
        pump()
        val expected = BoardRecipe(BoardType.OBLITERATE, .35f, ObliterateBoard.Settings(.3, false, 98.0, 8, 20261025L))
        assertEquals("all OBLITERATE controls remain staged", before, files(entry.dir))
        assertEquals(original, savedPad(entry))
        activate("KEEP")
        waitFor("complete OBLITERATE settings to be saved", timeoutMillis = 60_000) {
            BoardSheet.read(lastUpdatedKit?.pad(1)?.recipe)?.recipe == expected
        }
        waitForText("OBLITERATE kept at 35%")
        val kept = savedPad(entry)
        assertEquals(expected, BoardSheet.read(kept.recipe)!!.recipe)
        activate("${PadBanks.tag(2)} ►")
        waitForText("PAD ${PadBanks.tag(2)}")
        activate("◄ ${PadBanks.tag(1)}")
        waitForText("OBLITERATE kept at 35%")
        waitForText("98 BPM · 1/8 · ROOM")
        activate("REMOVE")
        waitFor("OBLITERATE removal", timeoutMillis = 60_000) { lastUpdatedKit?.pad(1) == original }
        assertArrayEquals(originalAudio, File(entry.dir, savedPad(entry).sampleFile).readBytes())
        waitForText("Hear a wider sound before keeping it")
        waitForUndo()
        activate("UNDO")
        waitFor("complete OBLITERATE undo", timeoutMillis = 60_000) { lastUpdatedKit?.pad(1) == kept }
        assertEquals(expected, BoardSheet.read(savedPad(entry).recipe)!!.recipe)
    }

}
