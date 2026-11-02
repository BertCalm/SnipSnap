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
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
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
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpPatch
import com.snipsnap.synth.ThumpVoice
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
 * UNDO SPLICE and DE-SAMPLE's UNDO on the real PadSheetScreen (2026-11-01): each gives back the
 * take its write binned, takes its recipe off, and lands on its own toast; a second tap, with
 * nothing left to undo, says so in the refusal the dimmed button stands for. The rules are
 * PadSheetTest's and KitBuilderTest's; these tests check the wiring.
 *
 * UNDO SPLICE sits in the sheet's always-composed foot, so it carries its own label: with every box
 * closed there is still no bare "UNDO" on the sheet for WidePadSheetScreenTest's lookup to trip on.
 * DE-SAMPLE's UNDO sits in the MAKE box, open here, and is spoken as "UNDO DE-SAMPLE".
 *
 * The de-sampled pad is written the way `KitBuilderModel.desamplePad` writes one once its search
 * has picked a patch: the patch's own render through `replaceAudio` with the bare patch as its
 * recipe, then the `desampled` stamp. The search itself, which renders a grid of every kindred
 * voice, is KitBuilderTest's and CliTest's to exercise, not a device test's.
 *
 * WidePadSheetScreenTest's and PedalSlateScreenTest's harness: semantic clicks rather than
 * scrolled taps, every wait on something observable. Both doors refuse until the sheet has read
 * the bin (`binDaysLeft`), so each test waits for the provenance line to say the bin keeps the take
 * before it taps.
 */
@RunWith(AndroidJUnit4::class)
class PadSheetUndoScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()
    private val toasts = CopyOnWriteArrayList<String>()
    private var currentSlot by mutableStateOf(1)
    private var openBoxState by mutableStateOf<String?>(null)

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    private fun sine(hz: Double): Snip {
        val samples = FloatArray(4410) { i -> (.4 * sin(2.0 * PI * hz * i / 44100.0) * exp(-i / 1400.0)).toFloat() }
        return Snip(samples, channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE)
    }

    private fun kit(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "pad-sheet-undo-test-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        WavWriter.write(File(dir, "hit1.wav"), sine(240.0))
        val kit = Kit(name = "PAD SHEET UNDO TEST", pads = listOf(KitPad(slot = 1, sampleFile = "hit1.wav", drumClass = DrumClass.KICK)), tempoBpm = 92f)
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(entry: KitShelf.Entry, openBox: String?) {
        openBoxState = openBox
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
                pedalRow = PedalSlate.DEFAULT,
                onPedalRowChange = {},
            )
        }
        waitFor("the pad sheet", timeoutMillis = 60_000) { hasText("PAD ${PadBanks.tag(1)}") }
        waitFor("the provenance line to say the bin keeps the take", timeoutMillis = 60_000) {
            compose.onAllNodesWithText("original in bin, kept", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

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

    private fun waitForToast(line: String) {
        waitFor("the toast \"$line\"", timeoutMillis = 60_000) { line in toasts }
        pump()
    }

    private fun savedPad(entry: KitShelf.Entry): KitPad = KitStore.load(entry.dir).pad(1) ?: error("pad A01 disappeared")

    @Test
    fun undo_splice_gives_the_take_before_the_splice_back_and_takes_the_splice_off() {
        val entry = kit()
        val original = File(entry.dir, "hit1.wav").readBytes()
        val m = KitBuilderModel.open(entry.dir)
        val live = WavReader.read(File(entry.dir, "hit1.wav"))
        val other = sine(520.0)
        m.splicePad(1, live, live.frameCount / 3, other, other.frameCount / 2)
        m.save()
        val spliced = savedPad(entry)
        assertTrue("test setup: TAPE SPLICE's own recipe", PadSheet.isSpliced(spliced.recipe))
        assertFalse("test setup: the splice replaced the audio", original.contentEquals(File(entry.dir, "hit1.wav").readBytes()))
        assertEquals(
            "test setup: the take before the splice is kept for A01",
            1,
            KitBuilderModel.open(entry.dir).binHolds().single { it.entry.originalName == "hit1.wav" }.keptFor,
        )

        show(KitShelf.Entry(entry.dir, KitStore.load(entry.dir)), openBox = null)
        assertTrue(
            "with every box closed the sheet draws no bare UNDO; UNDO SPLICE must keep its own name",
            nodes("UNDO").isEmpty(),
        )
        activate("UNDO SPLICE")
        waitForToast(Copy.UNSPLICED)
        assertNull("the splice's recipe came off", savedPad(entry).recipe)
        assertTrue("the take before the splice, byte for byte", original.contentEquals(File(entry.dir, "hit1.wav").readBytes()))

        activate("UNDO SPLICE")
        waitForToast(Copy.SPLICE_UNDO_NOTHING)
        assertTrue("a refused UNDO SPLICE touches nothing", original.contentEquals(File(entry.dir, "hit1.wav").readBytes()))
    }

    @Test
    fun de_sample_undo_gives_the_take_before_the_de_sample_back_and_clears_its_mark() {
        val entry = kit()
        val original = File(entry.dir, "hit1.wav").readBytes()
        val m = KitBuilderModel.open(entry.dir)
        val patch = ThumpPatch("Kick", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK))
        m.replaceAudio(1, PadRecipe(patch = patch).toJsonValue()) { patch.render() }
        m.update(1) { it.copy(source = it.source + ("desampled" to "0.12")) }
        m.save()
        val desampled = savedPad(entry)
        assertTrue("test setup: DE-SAMPLE's bare patch and stamp are on top", PadSheet.isDesampled(desampled))
        assertEquals(
            "test setup: the take before the de-sample is kept for A01",
            1,
            KitBuilderModel.open(entry.dir).binHolds().single { it.entry.originalName == "hit1.wav" }.keptFor,
        )

        show(KitShelf.Entry(entry.dir, KitStore.load(entry.dir)), openBox = PadSheetBoxes.Box.MAKE.name)
        waitFor("the MAKE box's DE-SAMPLE line to name the patch", timeoutMillis = 60_000) { hasText("A PATCH NOW, 0.12 AWAY") }
        activate("UNDO DE-SAMPLE")
        waitForToast(Copy.UNDESAMPLED)
        val undone = savedPad(entry)
        assertNull("the patch came off", undone.recipe)
        assertFalse("the de-sample's stamp came off", "desampled" in undone.source)
        assertTrue("the take before the de-sample, byte for byte", original.contentEquals(File(entry.dir, "hit1.wav").readBytes()))
        waitFor("the MAKE box's DE-SAMPLE line to stop naming a patch", timeoutMillis = 60_000) {
            hasText("THE HIT MEASURED AGAINST EVERY THUMP")
        }

        activate("UNDO DE-SAMPLE")
        waitForToast(Copy.DESAMPLE_UNDO_NOTHING)
        assertTrue("a refused UNDO touches nothing", original.contentEquals(File(entry.dir, "hit1.wav").readBytes()))
    }
}
