package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemesTest {

    @Test
    fun `six schemes in picker order`() {
        assertEquals(
            listOf(
                SchemeId.CHROME, SchemeId.FERRIC, SchemeId.METAL,
                SchemeId.SNACK_BAR, SchemeId.OILSLICK, SchemeId.CLEAR,
            ),
            Schemes.ALL.map { it.id },
        )
        assertEquals(SchemeId.CHROME, Schemes.DEFAULT.id)
    }

    @Test
    fun `the two-surface rule - the LCD is dark in every scheme`() {
        for (scheme in Schemes.ALL) {
            assertTrue(
                Scheme.luma(scheme.lcd) < 40,
                "${scheme.id}: lcd ${"%06x".format(scheme.lcd)} is not dark — " +
                    "light schemes lighten the chrome, never the sound surface",
            )
            // And the LCD ink must actually read on it.
            assertTrue(
                Scheme.luma(scheme.lcdInk) - Scheme.luma(scheme.lcd) > 80,
                "${scheme.id}: lcdInk barely contrasts its lcd",
            )
        }
    }

    @Test
    fun `chrome text contrasts its own gray in every scheme`() {
        for (scheme in Schemes.ALL) {
            val contrast = Math.abs(Scheme.luma(scheme.ink) - Scheme.luma(scheme.gray))
            assertTrue(contrast > 90, "${scheme.id}: ink/gray contrast only $contrast")
        }
    }

    @Test
    fun `class colours come from the one source`() {
        for (dc in DrumClass.entries) {
            assertEquals(
                AutoPlace.colorFor(dc).removePrefix("#").toInt(16),
                Schemes.classColor(dc),
            )
        }
    }

    @Test
    fun `pad label inks are darker than their pads in both ink tables`() {
        for (scheme in listOf(Schemes.OILSLICK, Schemes.CLEAR)) {
            for (dc in DrumClass.entries) {
                val pad = Schemes.classColor(dc)
                val ink = Schemes.padLabelInk(scheme, dc)
                assertTrue(
                    Scheme.luma(ink) < Scheme.luma(pad),
                    "${scheme.id}/$dc: label ink not darker than pad",
                )
            }
        }
        // Spot-check the tables verbatim from the prototypes.
        assertEquals(0x3A1005, Schemes.padLabelInk(Schemes.OILSLICK, DrumClass.KICK))
        assertEquals(0xC73A12, Schemes.padLabelInk(Schemes.CLEAR, DrumClass.KICK))
        assertEquals(0x0E6870, Schemes.padLabelInk(Schemes.CLEAR, DrumClass.HAT_CLOSED))
    }

    @Test
    fun `scheme lookup and css cross-reference`() {
        assertEquals(Schemes.CLEAR, Schemes[SchemeId.CLEAR])
        assertEquals("t-oilslick", Schemes.OILSLICK.cssClass)
        assertEquals(5, Schemes.OILSLICK_SWEEP.size)
        assertEquals(Schemes.OILSLICK_SWEEP.first(), Schemes.OILSLICK_SWEEP.last())
    }

    @Test
    fun `warn is warm in every scheme - the needle is never the LCD colour`() {
        for (scheme in Schemes.ALL) {
            val r = (scheme.warn shr 16) and 0xFF
            val g = (scheme.warn shr 8) and 0xFF
            val b = scheme.warn and 0xFF
            assertTrue(
                r > g && g > b,
                "${scheme.id}: warn ${"%06x".format(scheme.warn)} is not warm — " +
                    "the needle and onset bars must read as a warning, not as readout text",
            )
        }
    }

    @Test
    fun `OILSLICK separates its warn from its amber slot`() {
        assertEquals(0x40E0E8, Schemes.OILSLICK.amber, "the amber slot is cyan in OILSLICK")
        assertEquals(0xFFB000, Schemes.OILSLICK.warn, "but the needle stays amber")
    }

    @Test
    fun `OILSLICK matches the handoff token table exactly`() {
        val s = Schemes.OILSLICK
        assertEquals(0xE040C8, s.accent, "accent — selection and row inset bar")
        assertEquals(0x221A34, s.raised, "raised — button and empty-pad gradient start")
        assertEquals(0x1A1424, s.win, "win — window body gradient start")
        assertEquals(0x2A1050, s.deskGlow, "desk-glow — the radial behind everything")
        assertEquals(0x584A80, s.ink3, "ink3 — third text tier")
    }

    @Test
    fun `the three ink tiers descend in every scheme`() {
        for (scheme in Schemes.ALL) {
            val tiers = listOf(scheme.ink, scheme.ink2, scheme.ink3).map { Scheme.luma(it) }
            val descending = tiers.zipWithNext().all { (a, b) -> a >= b }
            val ascending = tiers.zipWithNext().all { (a, b) -> a <= b }
            assertTrue(
                descending || ascending,
                "${scheme.id}: ink tiers $tiers don't form a hierarchy — " +
                    "ink2 and ink3 must step away from ink, not straddle it",
            )
        }
    }
}
