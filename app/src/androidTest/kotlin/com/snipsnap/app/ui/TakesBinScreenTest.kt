package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.BoardSheet
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PedalSlate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * VERSIONS + BIN's back chip on a device, in both of its states: the main
 * screen and the shell it draws when the kit's folder will not open. Each reads
 * the label the app hands it (`Navigation.backChip`, so it names the screen
 * Back lands on), says that label aloud as "BACK TO ...", and a tap calls
 * `onBack`.
 *
 * And THE BIN's BACK on a kept row (docs/superpowers/specs/2026-10-31-bin-keeps-what-a-pad-needs-design.md,
 * sections 4 and 6). A row a pad keeps names that pad. When the pad can give the entry back itself
 * (NONE over a card treatment, `PadSheet.hasOwnWayBack`), the row has no BACK: BACK would copy the
 * original over the pad's audio, leave its treatment on the card, and use up the original that undo
 * needs. A kept row whose pad has no such door (a SPLICE) keeps BACK, its only way back in the app,
 * and so does a row on the clock. And no row whose file a print's saved source names has BACK
 * (`KitBuilderModel.filesUnderPrints`): BACK would overwrite a file REMOVE restores and checks by
 * hash, and the print could never come off. The rows sit in a plain `verticalScroll` column, so
 * every row is in the semantics tree without scrolling.
 *
 * Shown with [LocalReduceMotion] on, as `ComposeScreenTest`'s KDoc asks.
 */
@RunWith(AndroidJUnit4::class)
class TakesBinScreenTest : ComposeScreenTest() {

    private val folders = mutableListOf<File>()
    private val backs = AtomicInteger(0)

    @After
    fun cleanUp() {
        folders.forEach { it.deleteRecursively() }
    }

    /** A one-pad kit on a shelf of its own. */
    private fun kit(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val shelf = File(cache, "versions-test-shelf-${System.nanoTime()}").apply { mkdirs() }
        folders += shelf
        val dir = File(shelf, "VERSIONS UI TEST").apply { mkdirs() }
        WavWriter.write(File(dir, "kick.wav"), DrumSynth.kick())
        val kit = Kit(
            name = "VERSIONS UI TEST",
            pads = listOf(KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK)),
            tempoBpm = 92f,
        )
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    /** A kit whose folder holds no kit.json, so the screen draws its shell. */
    private fun missingKit(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "versions-test-missing-${System.nanoTime()}")
        return KitShelf.Entry(dir, Kit(name = "NO SUCH KIT", pads = emptyList()))
    }

    private fun show(entry: KitShelf.Entry, backLabel: String) {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                TakesBinScreen(
                    entry = entry,
                    backLabel = backLabel,
                    onBack = { backs.incrementAndGet() },
                    onToast = {},
                    onKitUpdated = {},
                )
            }
        }
    }

    /**
     * The main screen is up: its header is drawn only once the kit has loaded
     * on the IO dispatcher, which `waitForIdle` does not wait for. Until then
     * the loading shell draws the same chip, so a test that asserted at once
     * could be reading the shell's and never see the main screen's.
     */
    private fun waitForMainScreen() {
        waitFor("the main screen's header") {
            compose.onAllNodesWithText("VERSIONS + BIN").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun the_back_chip_reads_the_label_it_is_given_in_full() {
        show(kit(), "◄ PADS")
        waitForMainScreen()
        assertReadsInFull("◄ PADS")
    }

    @Test
    fun the_back_chip_is_spoken_as_back_to_that_screen_and_a_tap_calls_onBack() {
        show(kit(), "◄ PADS")
        waitForMainScreen()

        button("BACK TO PADS").assertIsEnabled()
        assertEquals("nothing has closed it yet", 0, backs.get())
        button("BACK TO PADS").performClick()
        pump()
        assertEquals("one tap closes it once", 1, backs.get())
    }

    @Test
    fun the_shell_for_a_kit_that_will_not_open_has_the_same_chip() {
        show(missingKit(), "◄ PADS")
        waitFor("the shell's line about a kit that will not open") {
            compose.onAllNodesWithText(Copy.KIT_WONT_OPEN).fetchSemanticsNodes().isNotEmpty()
        }

        assertReadsInFull("◄ PADS")
        button("BACK TO PADS").performClick()
        pump()
        assertEquals("the shell's chip closes it too", 1, backs.get())
    }

    private fun hit(dir: File, name: String, hz: Double) {
        val samples = FloatArray(4410) { i -> (.4 * sin(2.0 * PI * hz * i / 44100.0) * exp(-i / 1400.0)).toFloat() }
        WavWriter.write(File(dir, name), Snip(samples, channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE))
    }

    private fun hasText(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun nodes(description: String) =
        compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun back_leaves_only_the_kept_rows_whose_pad_can_undo_itself() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "takes-bin-test-${System.nanoTime()}").apply { mkdirs() }
        folders += dir
        hit(dir, "hit1.wav", 240.0)
        hit(dir, "hit2.wav", 180.0)
        hit(dir, "hit3.wav", 320.0)
        val pads = listOf(
            KitPad(slot = 1, sampleFile = "hit1.wav", drumClass = DrumClass.SNARE),
            KitPad(slot = 2, sampleFile = "hit2.wav", drumClass = DrumClass.KICK),
            KitPad(slot = 3, sampleFile = "hit3.wav", drumClass = DrumClass.SNARE),
        )
        KitStore.save(Kit(name = "TAKES BIN TEST", pads = pads, tempoBpm = 92f), dir)
        val m = KitBuilderModel.open(dir)
        assertTrue("DRIVE landed", PedalSlate.land(m, 1, PedalSlate.Gesture.On("driven")) is PedalSlate.Landed.Applied)
        m.clear(2) // an untreated pad ejected: nothing keeps its entry, so it sits on the clock
        // SPLICE's recipe, as splicePad writes it: the pad keeps its pre-splice take, and its sheet
        // has no NONE or UNDO that reads it.
        m.replaceAudio(3, Json.parse("""{"splice":{"crossfaded":true}}""") as JsonValue.Obj) { it }
        m.save()
        val holds = KitBuilderModel.open(dir).binHolds().associateBy { it.entry.originalName }
        assertEquals("test setup: the treated pad's original is kept for A01", 1, holds.getValue("hit1.wav").keptFor)
        assertNull("test setup: the ejected pad's entry is on the clock", holds.getValue("hit2.wav").keptFor)
        assertEquals("test setup: the spliced pad's take is kept for A03", 3, holds.getValue("hit3.wav").keptFor)

        show(KitShelf.Entry(dir, KitStore.load(dir)), "◄ PADS")
        val kept = Copy.binKeptFor(PadBanks.tag(1))
        val keptSplice = Copy.binKeptFor(PadBanks.tag(3))
        waitFor("the kept rows' \"$kept\" and \"$keptSplice\"", timeoutMillis = 60_000) { hasText(kept) && hasText(keptSplice) }
        waitFor("the BACK on the row on the clock", timeoutMillis = 60_000) { nodes("BRING BACK HIT2.WAV").isNotEmpty() }
        assertTrue("the spliced pad's kept row keeps BACK, its only way back", nodes("BRING BACK HIT3.WAV").isNotEmpty())
        assertTrue("the treated pad's kept row has no BACK: its NONE gives the original back", nodes("BRING BACK HIT1.WAV").isEmpty())
    }

    @Test
    fun no_row_under_a_print_has_back() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "takes-bin-print-test-${System.nanoTime()}").apply { mkdirs() }
        folders += dir
        hit(dir, "hit1.wav", 240.0)
        hit(dir, "hit2.wav", 180.0)
        val pads = listOf(
            KitPad(slot = 1, sampleFile = "hit1.wav", drumClass = DrumClass.SNARE),
            KitPad(slot = 2, sampleFile = "hit2.wav", drumClass = DrumClass.KICK),
        )
        KitStore.save(Kit(name = "TAKES BIN PRINT TEST", pads = pads, tempoBpm = 92f), dir)
        val m = KitBuilderModel.open(dir)
        m.treatPad(1, "crushed") // the first original: on the clock once a newer one sits on it
        m.treatPad(1, "washed", amount = .6f) // the newest original, kept for A01
        BoardSheet.apply(m, 1, .7f) // a board over the treated pad: both rows sit under its saved source
        m.clear(2) // an untreated pad ejected: a row on the clock with nothing over it
        m.save()
        val fresh = KitBuilderModel.open(dir)
        assertEquals("test setup: two rows under the board's source", 2, fresh.binHolds().count { it.entry.originalName == "hit1.wav" })
        assertTrue("test setup: the board's source names hit1.wav", "hit1.wav" in fresh.filesUnderPrints())

        show(KitShelf.Entry(dir, KitStore.load(dir)), "◄ PADS")
        val kept = Copy.binKeptFor(PadBanks.tag(1))
        waitFor("the kept row's \"$kept\"", timeoutMillis = 60_000) { hasText(kept) }
        waitFor("the BACK on the row with nothing over it", timeoutMillis = 60_000) { nodes("BRING BACK HIT2.WAV").isNotEmpty() }
        assertTrue("no row under the board has BACK, the older one on the clock included", nodes("BRING BACK HIT1.WAV").isEmpty())
    }
}
