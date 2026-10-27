package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.TapeCommit
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.ChopReviewModel
import com.snipsnap.shell.Copy
import com.snipsnap.shell.PadBanks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CHOP on a device, over a tape a test can count: kick, closed hat, snare
 * and open hat on quarter steps with silence round them, the same break
 * `:shell`'s ChopReviewTest chops into four. What this suite adds is the
 * screen around that model: the decode and the chop off the main thread,
 * the header that counts them, RE-CHOP, the CUT bench's GRID, a MERGE under
 * a chip, and both ways out (SEND TO PADS onto a fresh kit, ONTO the open
 * kit's empty bank), each read back off the disk.
 *
 * Buttons are pressed through their click actions on the UI thread, as the
 * OVEN and SNAP suites do: the bench sits in a scrolling column, and a
 * scroll on the frozen clock is the hazard [ComposeScreenTest] names.
 */
@RunWith(AndroidJUnit4::class)
class ChopScreenTest : ComposeScreenTest() {

    private val folders = mutableListOf<File>()
    // Written by the screen on the main thread, read by the test's own.
    private val toasts = CopyOnWriteArrayList<String>()
    private val sent = CopyOnWriteArrayList<Pair<KitShelf.Entry, String>>()
    private val landed = CopyOnWriteArrayList<Triple<KitShelf.Entry, Int, String>>()

    @After
    fun cleanUp() {
        folders.forEach { it.deleteRecursively() }
    }

    private fun folder(name: String): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "$name-${System.nanoTime()}").apply { mkdirs() }.also { folders += it }
    }

    /** ChopReviewTest's break: four distinct hits, half a second apart. */
    private fun breakSnip(): Snip {
        val rate = 44_100
        val step = rate / 2
        val hits = listOf(1 to DrumSynth.kick(), 2 to DrumSynth.closedHat(), 3 to DrumSynth.snare(), 4 to DrumSynth.openHat())
        val total = FloatArray(step * 6)
        for ((s, hit) in hits) {
            val at = s * step
            for (i in hit.samples.indices) if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
        }
        return Snip(total, 1, rate)
    }

    /** The break, kept on TAPE: a WAV on disk and the commit that names all of it. */
    private fun tapeCommit(): TapeCommit {
        val snip = breakSnip()
        val file = File(folder("chop-test-tape"), "breaktape.wav")
        WavWriter.write(file, snip)
        return TapeCommit(file, 0 until snip.frameCount)
    }

    /** An empty kit on the shelf, where ONTO can land on its first bank. */
    private fun emptyKit(shelf: KitShelf): KitShelf.Entry {
        val dir = File(shelf.root, "CHOP UI TEST").apply { mkdirs() }
        val kit = Kit("CHOP UI TEST", emptyList())
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(entry: KitShelf.Entry?, commit: TapeCommit?, shelf: KitShelf = KitShelf(folder("chop-test-shelf"))) {
        setPhoneContent {
            ChopScreen(
                entry = entry,
                lastCommit = commit,
                shelf = shelf,
                teachEnabled = false,
                workshopOpen = false,
                onToast = { toasts += it },
                onSentToGrid = { e, line -> sent += e to line },
                onLandedOnto = { e, bank, line -> landed += Triple(e, bank, line) },
                onNavigateTape = {},
                onNavigateKits = {},
            )
        }
    }

    private fun invokeClick(label: String) {
        val node = compose.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode("no button \"$label\"")
        clickNode(label, node.config.getOrNull(SemanticsActions.OnClick)?.action)
    }

    private fun clickNode(label: String, action: (() -> Boolean)?) {
        requireNotNull(action) { "no click for $label" }
        var clicked = false
        compose.runOnUiThread { clicked = action.invoke() }
        assertTrue("$label refused its click", clicked)
        pump()
    }

    private fun showing(text: String): Boolean =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun header(slices: Int, mode: String) = "${Copy.countOf(slices, "SLICE", "SLICES")} — $mode"

    /** The break, chopped and on screen: the header counts its four hits. */
    private fun showChopped(entry: KitShelf.Entry? = null, shelf: KitShelf = KitShelf(folder("chop-test-shelf"))) {
        show(entry, tapeCommit(), shelf)
        waitFor("the break chopped", timeoutMillis = 60_000) { showing(header(4, "BY HITS")) }
    }

    @Test
    fun with_no_tape_and_no_kit_it_says_there_is_nothing_to_chop() {
        show(entry = null, commit = null)
        waitFor("the empty face") { showing(Copy.EMPTY_CHOP) }
    }

    @Test
    fun a_four_hit_break_chops_into_four_slices_and_names_its_source() {
        showChopped()
        assertTrue(showing(Copy.chopFromKit("BREAKTAPE")))
        assertTrue("nothing went wrong reading it: $toasts", toasts.isEmpty())
    }

    @Test
    fun re_chop_cuts_the_same_four_again_and_says_so() {
        showChopped()
        invokeClick("RE-CHOP")
        waitFor("RE-CHOP's answer") { Copy.RECHOPPED in toasts }
        assertTrue(showing(header(4, "BY HITS")))
    }

    @Test
    fun the_cut_bench_grid_cuts_the_tape_into_as_many_equal_parts() {
        showChopped()
        invokeClick("EXPAND CUT")
        invokeClick("GRID")
        waitFor("the grid chop") { showing(header(4, "GRID ×4")) }
    }

    @Test
    fun merge_under_the_first_chip_joins_slices_one_and_two() {
        showChopped()
        // The chip is named for its class: the same model the screen
        // builds, built here, says which class the first slice is. Only
        // the chips take a long press (RESTORE THE MACHINE'S CALL), which
        // tells a chip from anything else on screen reading that class.
        val first = ChopReviewModel.chipName(ChopReviewModel.chop(breakSnip()).rows.first().effectiveClass)
        val chip = compose.onAllNodes(hasText(first) and hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .fetchSemanticsNodes().first()
        clickNode("the $first chip", chip.config.getOrNull(SemanticsActions.OnClick)?.action)
        invokeClick("MERGE WITH 2")
        waitFor("the merge") { Copy.chopMerged(1) in toasts }
        waitFor("three slices") { showing(header(3, "BY HITS")) }
    }

    @Test
    fun send_to_pads_lands_the_four_slices_on_a_fresh_kit() {
        val shelf = KitShelf(folder("chop-test-shelf"))
        showChopped(shelf = shelf)
        invokeClick("SEND TO PADS")
        waitFor("the fresh kit", timeoutMillis = 60_000) { sent.isNotEmpty() }
        val (entry, _) = sent.single()
        assertEquals(shelf.root.canonicalFile, entry.dir.parentFile?.canonicalFile)
        val kit = KitStore.load(entry.dir)
        assertEquals(4, kit.pads.size)
        for (pad in kit.pads) assertTrue("${pad.slot}'s audio is on disk", File(entry.dir, pad.sampleFile).length() > 44)
    }

    @Test
    fun onto_lands_the_four_slices_on_the_open_kits_empty_bank() {
        val shelf = KitShelf(folder("chop-test-shelf"))
        val entry = emptyKit(shelf)
        showChopped(entry = entry, shelf = shelf)
        invokeClick("ONTO CHOP UI TEST · BANK ${PadBanks.letter(0)}")
        waitFor("the landing", timeoutMillis = 60_000) { landed.isNotEmpty() }
        val (onto, bank, _) = landed.single()
        assertEquals(0, bank)
        assertEquals(entry.dir.canonicalFile, onto.dir.canonicalFile)
        val kit = KitStore.load(entry.dir)
        assertEquals(4, kit.pads.size)
        assertTrue("every pad is on bank ${PadBanks.letter(0)}", kit.pads.all { it.slot in PadBanks.slots(0) })
    }
}
