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
import com.snipsnap.shell.PadBanks
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
 * Every test hands the screen [LABEL], which none of them ever drew as a
 * literal: GRAIN FIELD's old chip read "◄ KIT" and SPLICE's and STACK's read
 * "◄ PAD", so a screen that went back to its own words (or built them some
 * way these tests do not read) fails here instead of passing on a coincidence.
 *
 * SPLICE and STACK also name the pad in their header, as GRAIN FIELD does,
 * because Back can restore them after the editor moved to another pad.
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

    private companion object {
        /** A back label no screen ever hard-coded, and what it is spoken as. */
        const val LABEL = "◄ TEST"
        const val SPOKEN = "BACK TO TEST"

        /** The pad every fixture opens on (`slot = 1`). */
        val PAD = PadBanks.tag(1)
    }

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
        waitFor("the splice refusal shell") { compose.onAllNodesWithContentDescription(SPOKEN).fetchSemanticsNodes().isNotEmpty() }
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
        waitFor("the stack refusal shell") { compose.onAllNodesWithContentDescription(SPOKEN).fetchSemanticsNodes().isNotEmpty() }
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
    fun grain_field_back_chip_reads_the_label_it_is_given_and_is_spoken_as_back_to_it() {
        showGrain(LABEL)
        assertChip(LABEL, SPOKEN)
    }

    @Test
    fun splice_back_chip_reads_the_label_it_is_given_and_is_spoken_as_back_to_it() {
        showSplice(LABEL)
        assertChip(LABEL, SPOKEN)
    }

    @Test
    fun stack_back_chip_reads_the_label_it_is_given_and_is_spoken_as_back_to_it() {
        showStack(LABEL)
        assertChip(LABEL, SPOKEN)
    }

    /** The header says which pad SPLICE is on, whole on one line at phone width. */
    @Test
    fun splice_header_names_the_pad_it_works_on() {
        showSplice(LABEL)
        assertReadsInFull("SPLICE · $PAD")
    }

    /** The header says which pad STACK is on, whole on one line at phone width. */
    @Test
    fun stack_header_names_the_pad_it_works_on() {
        showStack(LABEL)
        assertReadsInFull("STACK THE TAKES · $PAD")
    }
}
