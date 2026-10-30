package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadSheet
import com.snipsnap.shell.PadSheetBoxes
import com.snipsnap.shell.PedalSlate
import com.snipsnap.synth.PedalSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Row seven's slate on the real PadSheetScreen
 * (docs/superpowers/specs/2026-10-28-pedal-slate-design.md, section 6). The rules are
 * PedalSlateTest's; these tests check the wiring: a row chip puts a pedal on and takes it off,
 * AMT moves the marked pedal and hands the mark on at 0, MORE lights with a count and its list
 * puts on what the row hides and moves a pedal across the amp, PUT ON ROW swaps the app-wide row,
 * a tap on MORE treats nothing, and a pad whose original the bin let go keeps its pedals.
 *
 * WidePadSheetScreenTest's harness: TREATMENT open from the start, semantic clicks rather than
 * scrolled taps, every wait on something observable. Pads carry no recipe, so taking the last
 * pedal off gives the original back exactly. Toasts are compared with Copy's own lines.
 */
@RunWith(AndroidJUnit4::class)
class PedalSlateScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()
    private val toasts = CopyOnWriteArrayList<String>()
    private var currentSlot by mutableStateOf(1)
    private var openBoxState by mutableStateOf<String?>(PadSheetBoxes.Box.TREATMENT.name)

    // The app-wide row, held here the way App holds it.
    private var pedalRow by mutableStateOf(PedalSlate.DEFAULT)

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    private fun kit(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "pedal-slate-test-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        val samples = FloatArray(4410) { i -> (.4 * sin(2.0 * PI * 240 * i / 44100.0) * exp(-i / 1400.0)).toFloat() }
        WavWriter.write(File(dir, "hit1.wav"), Snip(samples, channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE))
        val kit = Kit(name = "PEDAL SLATE TEST", pads = listOf(KitPad(slot = 1, sampleFile = "hit1.wav", drumClass = DrumClass.SNARE)), tempoBpm = 92f)
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(entry: KitShelf.Entry) {
        setPhoneContent {
            PadSheetScreen(
                entry = entry,
                slot = currentSlot,
                backLabel = "◄ PADS",
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
                openBox = openBoxState,
                onOpenBox = { openBoxState = it },
                pedalRow = pedalRow,
                onPedalRowChange = { pedalRow = it },
            )
        }
        waitFor("the pad sheet", timeoutMillis = 60_000) { hasText("PAD ${PadBanks.tag(1)}") }
        waitFor("row seven's MORE", timeoutMillis = 60_000) { nodes(PadSheet.MORE).isNotEmpty() }
    }

    private fun padName(entry: KitShelf.Entry) = entry.kit.pad(1)!!.displayName

    private fun nodes(description: String) =
        compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()

    private fun hasText(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** A busy sheet greys every control; a click before it is live again could vanish, so wait for it. */
    private fun activate(description: String) {
        waitFor("\"$description\" to be live", timeoutMillis = 60_000) {
            val node = nodes(description).singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
        }
        compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue("\"$description\" refused its click", click()) }
        pump()
    }

    private fun activateTag(tag: String) {
        waitFor("$tag to be live", timeoutMillis = 60_000) {
            val node = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
        }
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue("$tag refused its click", click()) }
        pump()
    }

    private fun waitForToast(line: String) {
        waitFor("the toast \"$line\"", timeoutMillis = 60_000) { line in toasts }
        pump()
    }

    private fun amountText(label: String): String =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription) ?: error("\"$label\" has no state")

    private fun dialAmount(label: String, to: Float) {
        waitFor("\"$label\" to be live", timeoutMillis = 60_000) {
            val node = nodes(label).singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
        }
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { set -> assertTrue("\"$label\" refused $to", set(to)) }
        pump()
    }

    private fun savedKeys(entry: KitShelf.Entry): List<String> =
        PedalSlate.boardOf(KitStore.load(entry.dir).pad(1)?.recipe)?.picks?.map { it.key }.orEmpty()

    private fun files(dir: File): Map<String, List<Byte>> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).path to it.readBytes().toList() }

    private fun selected(description: String) =
        compose.onNodeWithContentDescription(description, useUnmergedTree = true)

    @Test
    fun row_chips_put_pedals_on_and_take_them_off_and_the_last_one_off_gives_the_original_back() {
        val entry = kit()
        val original = File(entry.dir, "hit1.wav").readBytes()
        show(entry)
        val pad = padName(entry)

        activate("DRIVE")
        waitForToast(Copy.treated("DRIVE", pad))
        activate("DELAY")
        waitForToast(Copy.treated("DELAY", pad))
        assertEquals(listOf("driven", "delayed"), savedKeys(entry))
        selected("DRIVE").assertIsSelected()
        selected("DELAY").assertIsSelected()
        selected("WAH").assertIsNotSelected()
        selected(PadSheet.MORE).assertIsNotSelected()
        selected(PadSheet.NONE).assertIsNotSelected()
        assertEquals("70%", amountText(Copy.pedalAmt("DELAY")))

        activate("DELAY")
        waitForToast(Copy.pedalOff("DELAY", pad, "DRIVE"))
        assertEquals(listOf("driven"), savedKeys(entry))
        selected("DELAY").assertIsNotSelected()

        activate("DRIVE")
        waitForToast(Copy.unTreated(pad))
        assertNull(KitStore.load(entry.dir).pad(1)?.recipe)
        assertTrue("the original, byte for byte", original.contentEquals(File(entry.dir, "hit1.wav").readBytes()))
        selected(PadSheet.NONE).assertIsSelected()
    }

    @Test
    fun amt_moves_the_marked_pedal_and_at_zero_takes_it_off_and_hands_the_mark_on() {
        val entry = kit()
        show(entry)
        val pad = padName(entry)
        activate("DRIVE")
        waitForToast(Copy.treated("DRIVE", pad))
        activate("DELAY")
        waitForToast(Copy.treated("DELAY", pad))

        dialAmount(Copy.pedalAmt("DELAY"), 0f)
        waitForToast(Copy.pedalOff("DELAY", pad, "DRIVE"))
        // Both were at 70: the stepper must reload for DRIVE rather than keep reading 0.
        waitFor("AMT to name DRIVE", timeoutMillis = 60_000) { nodes(Copy.pedalAmt("DRIVE")).isNotEmpty() }
        assertEquals("70%", amountText(Copy.pedalAmt("DRIVE")))

        dialAmount(Copy.pedalAmt("DRIVE"), 1f)
        waitFor("DRIVE at 100 on disk", timeoutMillis = 60_000) {
            PedalSlate.boardOf(KitStore.load(entry.dir).pad(1)?.recipe)?.picks?.singleOrNull()?.amount == 1f
        }
    }

    @Test
    fun more_lights_with_a_count_and_touch_from_more_takes_wahs_place() {
        val entry = kit()
        show(entry)
        val pad = padName(entry)
        activate("WAH")
        waitForToast(Copy.treated("WAH", pad))

        activate(PadSheet.MORE)
        waitFor("MORE's list", timeoutMillis = 60_000) { compose.onAllNodesWithTag("pedal-more-list", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        activateTag("pedal-more-touchwahed")
        waitForToast(Copy.pedalReplaced("TOUCH", listOf("WAH"), pad))
        assertEquals(listOf("touchwahed"), savedKeys(entry))
        selected("WAH").assertIsNotSelected()
        selected(PadSheet.MORE).assertIsSelected()
        assertTrue("MORE counts TOUCH", hasText(Copy.pedalMore(1)))
        assertTrue("AMT names the pedal behind MORE", nodes(Copy.pedalAmt("TOUCH")).isNotEmpty())
    }

    @Test
    fun put_on_row_swaps_the_chip_you_tap_and_touches_no_file() {
        val entry = kit()
        show(entry)
        val pad = padName(entry)
        activate(PadSheet.MORE)
        activateTag("pedal-more-touchwahed")
        waitForToast(Copy.treated("TOUCH", pad))

        activateTag("pedal-more-touchwahed-row")
        waitFor("the PUT ON ROW prompt", timeoutMillis = 60_000) { hasText(Copy.putOnRowPrompt("TOUCH")) }
        val before = files(entry.dir)
        activate("FORGE")
        waitForToast(Copy.rowSwapped("TOUCH", "FORGE"))
        assertEquals(listOf("driven", "compressed", "wahed", "touchwahed", "delayed"), pedalRow.keys)
        assertEquals("a row swap writes nothing", before, files(entry.dir))
        waitFor("TOUCH on the row", timeoutMillis = 60_000) { nodes("TOUCH").isNotEmpty() }
        selected("TOUCH").assertIsSelected()
        assertTrue("FORGE left the row", nodes("FORGE").isEmpty())
        assertTrue("MORE has nothing to count", hasText(PadSheet.MORE) && !hasText(Copy.pedalMore(1)))
    }

    @Test
    fun a_put_on_row_in_waiting_cancels_on_the_next_tap_and_more_treats_nothing() {
        val entry = kit()
        show(entry)
        val pad = padName(entry)
        val before = files(entry.dir)
        activate(PadSheet.MORE)
        waitFor("MORE's list", timeoutMillis = 60_000) { compose.onAllNodesWithTag("pedal-more-list", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("opening MORE says nothing", toasts.isEmpty())
        assertEquals("opening MORE writes nothing", before, files(entry.dir))

        activateTag("pedal-more-rockwahed-row")
        waitFor("the PUT ON ROW prompt", timeoutMillis = 60_000) { hasText(Copy.putOnRowPrompt("ROCK")) }
        // NONE is not live on a clean pad, so the other tap is MORE itself: it cancels and does nothing else.
        activate(PadSheet.MORE)
        waitFor("the prompt to go", timeoutMillis = 60_000) { !hasText(Copy.putOnRowPrompt("ROCK")) }
        assertTrue("the cancelling tap left MORE's list open", compose.onAllNodesWithTag("pedal-more-list", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals("the cancel left the row alone", PedalSlate.DEFAULT, pedalRow)

        activate("FORGE")
        waitForToast(Copy.treated("FORGE", pad))
        assertEquals(listOf("forged"), savedKeys(entry))
        assertFalse(toasts.any { it == Copy.rowSwapped("ROCK", "FORGE") })
    }

    @Test
    fun the_side_toggle_moves_a_pedal_to_the_other_side_of_the_amp() {
        val entry = kit()
        show(entry)
        val pad = padName(entry)
        activate("SUSTAIN")
        waitForToast(Copy.treated("SUSTAIN", pad))
        activate(PadSheet.MORE)
        activateTag("pedal-more-compressed-side")
        waitForToast(Copy.pedalSide("SUSTAIN", false, pad))
        val pick = PedalSlate.boardOf(KitStore.load(entry.dir).pad(1)?.recipe)?.picks?.single()
        assertEquals(PedalSide.POST, pick?.side)
        selected("SUSTAIN").assertIsSelected()
    }

    @Test
    fun a_pad_whose_original_the_bin_let_go_keeps_its_pedals_and_says_so() {
        val entry = kit()
        show(entry)
        val pad = padName(entry)
        activate("DRIVE")
        waitForToast(Copy.treated("DRIVE", pad))
        // What 30 days do to the bin: the original DRIVE rendered from is gone.
        KitBuilderModel.open(entry.dir).emptyBin()
        val before = files(entry.dir)

        activate("DELAY")
        waitForToast(Copy.PEDALS_NOT_BINNED)
        assertEquals(listOf("driven"), savedKeys(entry))
        toasts.clear()
        // A chip from another row refuses too, rather than wipe DRIVE off the card while the audio keeps it.
        activate("CRUSH")
        waitForToast(Copy.PEDALS_NOT_BINNED)
        assertEquals(listOf("driven"), savedKeys(entry))
        assertEquals("nothing was touched", before, files(entry.dir))
        selected("DRIVE").assertIsSelected()
    }

    @Test
    fun none_takes_every_pedal_off_at_once() {
        val entry = kit()
        val original = File(entry.dir, "hit1.wav").readBytes()
        show(entry)
        val pad = padName(entry)
        activate("SUSTAIN")
        waitForToast(Copy.treated("SUSTAIN", pad))
        activate("DELAY")
        waitForToast(Copy.treated("DELAY", pad))
        selected(PadSheet.NONE).assertIsNotSelected()

        activate(PadSheet.NONE)
        waitForToast(Copy.unTreated(pad))
        assertNull(KitStore.load(entry.dir).pad(1)?.recipe)
        assertTrue("the original, byte for byte", original.contentEquals(File(entry.dir, "hit1.wav").readBytes()))
        selected("SUSTAIN").assertIsNotSelected()
        selected("DELAY").assertIsNotSelected()
    }
}
