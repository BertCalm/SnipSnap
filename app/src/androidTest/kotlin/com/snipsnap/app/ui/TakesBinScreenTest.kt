package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Copy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * VERSIONS + BIN's back chip on a device, in both of its states: the main
 * screen and the shell it draws when the kit's folder will not open. Each reads
 * the label the app hands it (`Navigation.backChip`, so it names the screen
 * Back lands on), says that label aloud as "BACK TO ...", and a tap calls
 * `onBack`. What the lists under the header do is not this test's business.
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
}
