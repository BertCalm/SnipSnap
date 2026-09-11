package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemesTest {

    @Test
    fun `eight dark schemes in picker order, OILSLICK by default`() {
        assertEquals(
            listOf(
                SchemeId.METAL, SchemeId.OILSLICK, SchemeId.PETROL, SchemeId.INFRARED,
                SchemeId.ACID, SchemeId.SODIUM, SchemeId.ICE, SchemeId.VAPOR,
            ),
            Schemes.ALL.map { it.id },
        )
        assertEquals(SchemeId.OILSLICK, Schemes.DEFAULT.id)
    }

    @Test
    fun `every scheme is dark now - the light shells are gone`() {
        for (scheme in Schemes.ALL) {
            assertTrue(
                Scheme.luma(scheme.gray) < 90,
                "${scheme.id}: chrome ${"%06x".format(scheme.gray)} is a light shell, and those were cut",
            )
        }
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
        for (scheme in listOf(Schemes.OILSLICK)) {
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
    }

    @Test
    fun `scheme lookup and css cross-reference`() {
        assertEquals(Schemes.PETROL, Schemes[SchemeId.PETROL])
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
    fun `the three ink tiers never invert in any scheme`() {
        for (scheme in Schemes.ALL) {
            val ink = Scheme.luma(scheme.ink)
            val ink2 = Scheme.luma(scheme.ink2)
            val ink3 = Scheme.luma(scheme.ink3)
            // Dark schemes run bright -> dim; light schemes run dim -> bright.
            // Either direction is legal, but the tiers must not turn around
            // mid-way, and ink3 must never step back past ink2.
            if (Scheme.luma(scheme.gray) < 90) {
                assertTrue(ink > ink2, "${scheme.id}: ink $ink must be brighter than ink2 $ink2")
                assertTrue(ink3 <= ink2, "${scheme.id}: ink3 $ink3 must not be brighter than ink2 $ink2")
            } else {
                assertTrue(ink < ink2, "${scheme.id}: on a light scheme ink $ink must be darker than ink2 $ink2")
                assertTrue(ink3 >= ink2, "${scheme.id}: ink3 $ink3 must not be darker than ink2 $ink2")
            }
        }
    }

    @Test
    fun `selection is visible against the chrome it sits on`() {
        for (scheme in Schemes.ALL) {
            val accentSep = Math.abs(Scheme.luma(scheme.accent) - Scheme.luma(scheme.gray))
            assertTrue(
                accentSep > 40,
                "${scheme.id}: accent and gray are $accentSep luma apart - a selected row would " +
                    "disappear into the chrome behind it",
            )
            val winFrameSep = Math.abs(Scheme.luma(scheme.winFrame) - Scheme.luma(scheme.gray))
            assertTrue(
                winFrameSep > 40,
                "${scheme.id}: winFrame and gray are $winFrameSep luma apart - the window frame " +
                    "would disappear into the chrome behind it",
            )
        }
    }

    @Test
    fun `the handwriting is Rock Salt`() {
        assertEquals("Rock Salt", Type.MARKER)
    }

    @Test
    fun `LOOP block cells have room to grow but not to sprawl`() {
        assertTrue(Layout.BLOCK_MIN_H < Layout.BLOCK_MAX_H, "a block cell grows between two bounds")
        assertTrue(Layout.BLOCK_MIN_H >= 30, "below 30dp a two-line block cell clips its name")
        assertTrue(Layout.TRACK_HEADER_H >= 24, "the header is a tap target for mute")
    }

    /**
     * September UAT, finding 9. The nav strip was 26dp - the handoff's
     * figure - which made the eleven tabs a lost user reaches for first
     * the smallest targets in an app that holds everything else to
     * MIN_HIT_TARGET.
     *
     * This is the guard the constant's own KDoc promises. It fails if the
     * strip is set back below the floor, and it fails again if the floor
     * is ever raised without the strip following it up.
     */
    @Test
    fun `the menu row is at least a hit target tall`() {
        assertTrue(
            Layout.MENU_ROW_H >= Layout.MIN_HIT_TARGET,
            "the app's primary navigation cannot hold its smallest targets: " +
                "MENU_ROW_H=${Layout.MENU_ROW_H} against MIN_HIT_TARGET=${Layout.MIN_HIT_TARGET}",
        )
    }

    /**
     * The other half of finding 9, stated where it can be read: the strip
     * grows into the body, so the body has to be the thing that gives.
     * Nothing here can assert `MenuRow`'s Compose tree - this is the
     * arithmetic that says the frame has the room.
     */
    @Test
    fun `the chrome still leaves the body most of the frame`() {
        val chrome = Layout.TITLEBAR_H + Layout.MENU_ROW_H + Layout.STATUS_BAR_H
        assertTrue(
            chrome < Layout.FRAME_H / 4,
            "titlebar + menu + status = ${chrome}dp of a ${Layout.FRAME_H}dp frame",
        )
    }

    @Test
    fun `the landscape frame is the portrait frame turned over`() {
        assertTrue(
            Layout.LANDSCAPE_W > Layout.LANDSCAPE_H,
            "landscape is wider than tall",
        )
        assertTrue(
            Layout.LANDSCAPE_H < Layout.FRAME_W,
            "landscape loses height to the system bars — 362 against a 390 width",
        )
    }
}
