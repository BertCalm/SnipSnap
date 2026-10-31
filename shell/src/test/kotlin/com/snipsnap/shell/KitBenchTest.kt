package com.snipsnap.shell

import com.snipsnap.shell.KitBench.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The verdict a kit-bound bench reaches on arrival, case by case. The app
 * closes VERSIONS, PAD CAPTURE, SONG and the pad editor with its tools on a
 * [Verdict.CLOSE], and `beatMustStop` stops the beat only for a SONG that gets
 * [Verdict.DRAW], so an inverted term here would show one kit's screen over
 * another or stop a beat for a screen that never draws. Nothing else in the
 * suite can reach these.
 */
class KitBenchTest {

    private val padBenches = listOf(Bench.PAD_SHEET, Bench.GRAIN_FIELD, Bench.SPLICE, Bench.STACK)
    private val kitBound = listOf(Bench.VERSIONS, Bench.PAD_CAPTURE, Bench.SONG) + padBenches

    /** Any kit-bound bench, asked with the slot it needs to draw: empty for PAD CAPTURE, filled for the pad benches. */
    private fun verdict(bench: Bench, openedFor: String?, openNow: String?) =
        KitBench.verdict(
            bench,
            openedFor,
            openNow,
            slotFilled = when (bench) {
                Bench.PAD_CAPTURE -> false
                in padBenches -> true
                else -> null
            },
        )

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
    fun `an empty slot gives CLOSE for every pad bench`() {
        for (bench in padBenches) {
            assertEquals(
                Verdict.CLOSE,
                KitBench.verdict(bench, "/shelf/A", "/shelf/A", slotFilled = false),
                "$bench works on a pad that is there, and this one was deleted or ejected since it was opened",
            )
        }
    }

    @Test
    fun `a null slot gives CLOSE for every pad bench`() {
        for (bench in padBenches) {
            assertEquals(
                Verdict.CLOSE,
                KitBench.verdict(bench, "/shelf/A", "/shelf/A", slotFilled = null),
                "$bench with no pad recorded has no pad to show",
            )
            assertEquals(Verdict.CLOSE, KitBench.verdict(bench, "/shelf/A", "/shelf/A"), "the slot defaults to unrecorded")
        }
    }

    @Test
    fun `a filled slot on the same kit gives DRAW for every pad bench`() {
        for (bench in padBenches) {
            assertEquals(Verdict.DRAW, KitBench.verdict(bench, "/shelf/A", "/shelf/A", slotFilled = true), "$bench opened for A, A is open, the pad is there")
        }
    }

    @Test
    fun `a filled slot on another kit still gives CLOSE for every pad bench`() {
        for (bench in padBenches) {
            assertEquals(
                Verdict.CLOSE,
                KitBench.verdict(bench, "/shelf/A", "/shelf/B", slotFilled = true),
                "$bench must never show kit A's pad over kit B",
            )
        }
    }

    @Test
    fun `no open kit gives CLOSE for every pad bench`() {
        for (bench in padBenches) {
            assertEquals(Verdict.CLOSE, KitBench.verdict(bench, "/shelf/A", null, slotFilled = true), "$bench has no kit to show a pad of")
        }
    }

    @Test
    fun `the pad benches answer opposite to PAD CAPTURE on the slot`() {
        // The two rules must never drift to the same answer: PAD CAPTURE draws on an empty pad and closes on a filled one,
        // the pad benches the other way round.
        for (filled in listOf(true, false)) {
            val capture = KitBench.verdict(Bench.PAD_CAPTURE, "/shelf/A", "/shelf/A", slotFilled = filled)
            for (bench in padBenches) {
                val padBench = KitBench.verdict(bench, "/shelf/A", "/shelf/A", slotFilled = filled)
                assertEquals(
                    if (capture == Verdict.DRAW) Verdict.CLOSE else Verdict.DRAW,
                    padBench,
                    "slotFilled=$filled: $bench must answer opposite to PAD_CAPTURE",
                )
            }
        }
    }

    @Test
    fun `SPREAD is not opened for a kit`() {
        assertFailsWith<IllegalArgumentException>("SPREAD is still an in-sheet panel, so it has no verdict") {
            KitBench.verdict(Bench.SPREAD, "/shelf/A", "/shelf/A", slotFilled = true)
        }
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
