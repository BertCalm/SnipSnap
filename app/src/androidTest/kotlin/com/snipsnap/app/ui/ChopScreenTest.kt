package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.TapeCommit
import com.snipsnap.app.theme.LocalReduceMotion
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    // What the screen told the dock about its own job, label by label; null is "no job".
    private val told = CopyOnWriteArrayList<String?>()
    // A read the test keeps open: the screen's worker blocks on it, once it has chopped, until released.
    private val release = CountDownLatch(1)

    @After
    fun cleanUp() {
        release.countDown()
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

    /**
     * The screen under the compose test clock and with reduced motion on (the loading panel's bar
     * under a frozen clock is the hazard [ComposeScreenTest] names). [settleMillis] is how much of
     * the frozen clock the first composition gets (see `setPhoneContent`): the tests of the first
     * 200 ms take none. [hold] keeps the read open until [release] is counted down, because a real
     * decode of the break ends in a few milliseconds and the clock does not move on its own.
     */
    private fun show(
        entry: KitShelf.Entry?,
        commit: TapeCommit?,
        shelf: KitShelf = KitShelf(folder("chop-test-shelf")),
        settleMillis: Long = 300,
        hold: Boolean = false,
    ) {
        setPhoneContent(settleMillis) {
            CompositionLocalProvider(LocalBusySink provides { told += it?.label }, LocalReduceMotion provides true) {
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
                    loadingClock = { compose.mainClock.currentTime },
                    holdRead = {
                        if (hold) check(release.await(30, TimeUnit.SECONDS)) { "the held read was never released" }
                    },
                )
            }
        }
    }

    /**
     * Lets a held read finish and gives the screen the first frame after it: the read ends at that
     * frame, whatever the real time it took, because the clock is the test's. The sleep is the
     * worker's hop back to the main thread, which is real time and which nothing on screen shows;
     * the sink hearing "no job" is what says the read was seen to end, so a hop that was not done
     * fails here and not as a hold that was vacuously kept.
     */
    private fun endTheRead() {
        release.countDown()
        Thread.sleep(HOP_MS)
        pump(16)
        assertEquals("the read had not ended at ${compose.mainClock.currentTime} ms: $told", null, told.lastOrNull())
    }

    private fun assertWords(up: Boolean, why: String) {
        assertEquals("$why (at ${compose.mainClock.currentTime} ms)", up, described(Copy.READING_RECORDING))
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

    /** Some node on screen is named [label], the way [LoadingPanel] names itself. */
    private fun described(label: String): Boolean =
        compose.onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

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
    fun the_decode_is_told_to_the_dock_and_taken_back_when_the_chop_is_up() {
        showChopped()
        // The first thing the screen said about itself was the read, and the
        // last is that there is no job: a chop on screen is not a job.
        assertEquals("the read reached the dock first: $told", Copy.READING_RECORDING, told.firstOrNull())
        assertEquals("and the chop being up took it back: $told", null, told.lastOrNull())
    }

    @Test
    fun a_chop_with_nothing_to_read_takes_the_read_back_and_shows_the_empty_face() {
        show(entry = null, commit = null)
        waitFor("the empty face") { showing(Copy.EMPTY_CHOP) }
        assertEquals("the read reached the dock first: $told", Copy.READING_RECORDING, told.firstOrNull())
        assertEquals("and the empty face took it back: $told", null, told.lastOrNull())
    }

    @Test
    fun a_read_has_no_words_for_200_ms_and_then_says_it_is_reading() {
        show(entry = null, commit = tapeCommit(), settleMillis = 0, hold = true)
        // The first frame, then 150 ms in: a quiet lit panel and no words.
        assertWords(up = false, why = "the first frame of a read drew its words before the 200 ms show delay")
        pump(150)
        assertWords(up = false, why = "a read drew its words before the 200 ms show delay")
        // Past the delay, with the read still open: now the words are up. This is what makes
        // the absences above mean something.
        pump(100)
        assertWords(up = true, why = "a read still open after 200 ms did not say it was reading")
        assertEquals(
            "the dock was told the read, and has not been told it is over: $told",
            listOf<String?>(Copy.READING_RECORDING),
            told.toList(),
        )
    }

    @Test
    fun a_read_that_ends_inside_200_ms_never_draws_its_words() {
        show(entry = null, commit = tapeCommit(), settleMillis = 0, hold = true)
        pump(150)
        assertWords(up = false, why = "a read drew its words before the 200 ms show delay")
        endTheRead()
        // Every frame from the read's end to a full second on: no words, ever, though 200 ms
        // and 500 ms both pass in that time. And the chop is up.
        while (compose.mainClock.currentTime < 1_000) {
            assertWords(up = false, why = "a read that ended at 150 ms drew its words after all")
            pump(16)
        }
        waitFor("the break chopped", timeoutMillis = 60_000) { showing(header(4, "BY HITS")) }
        assertWords(up = false, why = "a read that ended inside the delay drew its words once the chop was up")
    }

    @Test
    fun a_read_that_ends_at_300_ms_keeps_its_words_for_500_ms_from_when_they_showed() {
        show(entry = null, commit = tapeCommit(), settleMillis = 0, hold = true)
        pump(250)
        assertWords(up = true, why = "a read still open after 200 ms did not say it was reading")
        // The read ends at about 300 ms (the first frame after the release). The words showed at
        // about 208 ms, so they are owed until about 708: the chop waits behind them.
        pump(34)
        endTheRead()
        assertTrue("the read ended too late for this test to mean anything", compose.mainClock.currentTime < 400)
        while (compose.mainClock.currentTime < 650) {
            assertWords(up = true, why = "the words went before their 500 ms minimum was up")
            assertFalse("the chop was drawn under the words", showing(header(4, "BY HITS")))
            pump(50)
        }
        // Past the minimum: the words give way, and what they held is on screen.
        pump(150)
        assertWords(up = false, why = "the words stayed after their 500 ms minimum")
        waitFor("the break chopped", timeoutMillis = 60_000) { showing(header(4, "BY HITS")) }
        assertEquals("the dock heard the read end and nothing after: $told", null, told.last())
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
        val model = ChopReviewModel.chop(breakSnip())
        val first = ChopReviewModel.chipName(model.rows.first().effectiveClass)
        val chip = compose.onAllNodes(hasText(first) and hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .fetchSemanticsNodes().first()
        clickNode("the $first chip", chip.config.getOrNull(SemanticsActions.OnClick)?.action)
        invokeClick("MERGE WITH 2")
        waitFor("the merge") { Copy.chopMerged(1) in toasts }
        // A hand edit says so in the header: the merged model's own label.
        val merged = requireNotNull(model.merged(0))
        assertEquals(3, merged.sliceCount)
        waitFor("three slices") { showing(header(3, merged.modeLabel())) }
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

    private companion object {
        /** Real time for a released read's worker to hand its result to the main thread: a hop of milliseconds, with room to spare. */
        const val HOP_MS = 500L
    }
}
