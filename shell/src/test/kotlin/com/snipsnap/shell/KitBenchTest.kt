package com.snipsnap.shell

import com.snipsnap.shell.KitBench.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The verdict a kit-bound bench reaches on arrival, case by case. The app
 * closes VERSIONS, PAD CAPTURE and SONG on a [Verdict.CLOSE], and `beatMustStop`
 * stops the beat only for a SONG that gets [Verdict.DRAW], so an inverted term
 * here would show one kit's screen over another or stop a beat for a screen
 * that never draws. Nothing else in the suite can reach these.
 */
class KitBenchTest {

    private val kitBound = listOf(Bench.VERSIONS, Bench.PAD_CAPTURE, Bench.SONG)

    /** A PAD CAPTURE or any other bench, asked with the slot it needs to draw. */
    private fun verdict(bench: Bench, openedFor: String?, openNow: String?) =
        KitBench.verdict(bench, openedFor, openNow, slotFilled = if (bench == Bench.PAD_CAPTURE) false else null)

    @Test
    fun `a different kit gives CLOSE on every bench`() {
        for (bench in kitBound) {
            assertEquals(Verdict.CLOSE, verdict(bench, "/shelf/A", "/shelf/B"), "$bench opened for A, B is open")
        }
    }

    @Test
    fun `the same kit gives DRAW on every bench`() {
        for (bench in kitBound) {
            assertEquals(Verdict.DRAW, verdict(bench, "/shelf/A", "/shelf/A"), "$bench opened for A, A is open")
        }
    }

    @Test
    fun `no recorded kit gives CLOSE on every bench`() {
        for (bench in kitBound) {
            assertEquals(Verdict.CLOSE, verdict(bench, null, "/shelf/A"), "$bench was never opened for a kit")
        }
    }

    @Test
    fun `no open kit gives NO_KIT for SONG and CLOSE for the others`() {
        assertEquals(Verdict.NO_KIT, verdict(Bench.SONG, "/shelf/A", null), "SONG says why instead of closing")
        assertEquals(Verdict.CLOSE, verdict(Bench.VERSIONS, "/shelf/A", null))
        assertEquals(Verdict.CLOSE, verdict(Bench.PAD_CAPTURE, "/shelf/A", null))
    }

    @Test
    fun `no open kit gives NO_KIT for SONG whatever it was opened for`() {
        assertEquals(Verdict.NO_KIT, verdict(Bench.SONG, null, null), "never opened for a kit, and none is open")
    }

    @Test
    fun `a filled slot gives CLOSE for PAD CAPTURE`() {
        assertEquals(
            Verdict.CLOSE,
            KitBench.verdict(Bench.PAD_CAPTURE, "/shelf/A", "/shelf/A", slotFilled = true),
            "the capture would replace a sample without asking",
        )
    }

    @Test
    fun `an empty slot on the same kit gives DRAW for PAD CAPTURE`() {
        assertEquals(Verdict.DRAW, KitBench.verdict(Bench.PAD_CAPTURE, "/shelf/A", "/shelf/A", slotFilled = false))
    }

    @Test
    fun `a null slot gives CLOSE for PAD CAPTURE`() {
        assertEquals(
            Verdict.CLOSE,
            KitBench.verdict(Bench.PAD_CAPTURE, "/shelf/A", "/shelf/A", slotFilled = null),
            "no pad was recorded, so there is nothing to fill",
        )
        assertEquals(Verdict.CLOSE, KitBench.verdict(Bench.PAD_CAPTURE, "/shelf/A", "/shelf/A"), "the slot defaults to unrecorded")
    }

    @Test
    fun `the slot decides nothing for VERSIONS and SONG`() {
        for (bench in listOf(Bench.VERSIONS, Bench.SONG)) {
            for (slotFilled in listOf(null, false, true)) {
                assertEquals(
                    Verdict.DRAW,
                    KitBench.verdict(bench, "/shelf/A", "/shelf/A", slotFilled),
                    "$bench has no pad to fill, so slotFilled=$slotFilled must not close it",
                )
            }
        }
    }

    @Test
    fun `a filled slot on another kit still gives CLOSE`() {
        assertEquals(Verdict.CLOSE, KitBench.verdict(Bench.PAD_CAPTURE, "/shelf/A", "/shelf/B", slotFilled = false))
    }

    @Test
    fun `a bench that is not opened for a kit is refused`() {
        for (bench in Bench.values().filter { it !in kitBound }) {
            assertFailsWith<IllegalArgumentException>("$bench has no kit to fit") { KitBench.verdict(bench, "/shelf/A", "/shelf/A") }
        }
    }
}
