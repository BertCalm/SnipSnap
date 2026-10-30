package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.kit.Kit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The back chip of GRAIN FIELD, SPLICE and STACK on a device: each reads the
 * label the app hands it (`Navigation.backChip`, so "◄ PAD" over the pad
 * editor), says it aloud as "BACK TO ...", and a tap calls `onBack`, which
 * App answers with `nav.closeBench()` and so lands on the editor.
 *
 * The kit has no `kit.json`, so SPLICE and STACK draw their refusal shell and
 * GRAIN FIELD finds no pad to analyze; the header, and so the chip, is there
 * either way. GRAIN FIELD also backs out on its own when it finds no pad (it
 * toasts and calls `onBack`), so its count is read before and after the tap
 * and the test asserts the tap's one call, not zero before it.
 *
 * Shown with [LocalReduceMotion] on, as `ComposeScreenTest`'s KDoc asks.
 */
@RunWith(AndroidJUnit4::class)
class PadToolBackChipTest : ComposeScreenTest() {

    private val backs = AtomicInteger(0)
    private val kitDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        kitDirs.forEach { it.deleteRecursively() }
    }

    private fun entry(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "pad-tool-test-kit-${System.nanoTime()}").also { kitDirs += it }
        return KitShelf.Entry(dir, Kit(name = "PAD TOOL UI TEST", pads = emptyList()))
    }

    private fun showGrain(backLabel: String) {
        val entry = entry()
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                GrainFieldScreen(
                    entry = entry,
                    slot = 1,
                    backLabel = backLabel,
                    onBack = { backs.incrementAndGet() },
                    onToast = {},
                    onRequestArm = {},
                    onFieldPrinted = {},
                )
            }
        }
    }

    private fun showSplice(backLabel: String) {
        val entry = entry()
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                TapeSpliceScreen(
                    entry = entry,
                    slot = 1,
                    backLabel = backLabel,
                    onBack = { backs.incrementAndGet() },
                    onToast = {},
                    onKitUpdated = {},
                )
            }
        }
        waitFor("the splice refusal shell") { compose.onAllNodesWithContentDescription("BACK TO PAD").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun showStack(backLabel: String) {
        val entry = entry()
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                StackTakesScreen(
                    entry = entry,
                    slot = 1,
                    backLabel = backLabel,
                    onBack = { backs.incrementAndGet() },
                    onToast = {},
                    onKitUpdated = {},
                )
            }
        }
        waitFor("the stack refusal shell") { compose.onAllNodesWithContentDescription("BACK TO PAD").fetchSemanticsNodes().isNotEmpty() }
    }

    /** The chip is there, whole, spoken as "BACK TO ...", and one tap calls `onBack` once. */
    private fun assertChip(label: String, spoken: String) {
        assertReadsInFull(label)
        button(spoken).assertIsEnabled()
        val before = backs.get()
        button(spoken).performClick()
        pump()
        assertEquals("one tap calls onBack once", before + 1, backs.get())
    }

    @Test
    fun grain_field_back_chip_reads_the_label_it_is_given_and_is_spoken_as_back_to_the_editor() {
        showGrain("◄ PAD")
        assertChip("◄ PAD", "BACK TO PAD")
    }

    @Test
    fun splice_back_chip_reads_the_label_it_is_given_and_is_spoken_as_back_to_the_editor() {
        showSplice("◄ PAD")
        assertChip("◄ PAD", "BACK TO PAD")
    }

    @Test
    fun stack_back_chip_reads_the_label_it_is_given_and_is_spoken_as_back_to_the_editor() {
        showStack("◄ PAD")
        assertChip("◄ PAD", "BACK TO PAD")
    }
}
