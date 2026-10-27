package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemesTest {

    @Test
    fun `two schemes in picker order, VENT by default`() {
        assertEquals(listOf(SchemeId.VENT, SchemeId.OPENSKY), Schemes.ALL.map { it.id })
        assertEquals(SchemeId.VENT, Schemes.DEFAULT.id)
    }

    /**
     * `Schemes.get` is `ALL.first { it.id == id }`, so a [SchemeId] with no
     * row in [Schemes.ALL] is a crash at launch for whoever has it saved
     * (and `SchemeId.entries` is what the app's restore reads), while a row
     * with no [SchemeId] cannot exist. This holds the enum and the list to
     * the same members in the same order: shrinking one without the other
     * fails here, not on a phone.
     */
    @Test
    fun `every SchemeId has exactly one scheme, in the enum's order`() {
        assertEquals(SchemeId.entries.toList(), Schemes.ALL.map { it.id })
        for (id in SchemeId.entries) {
            assertEquals(id, Schemes[id].id)
        }
    }

    /**
     * The August cut (`DESIGN_GAP.md` §2f, 2026-08-28: "CLEAR is cut. Every
     * light shell goes with it") held every scheme's chrome dark: a pale
     * bevelled shell is what was cut, and the bevels are what made it pale.
     * The decision of 2026-10-15 reopened it for a paper scheme, which is a
     * printed page that happens to be light, drawn flat, and it is light all
     * the way — chrome at paper luma, not a grey that is neither. What every
     * scheme owes, light or dark, is the two-surface rule (the LCD stays
     * dark, the next law) and chrome text that reads on its chrome.
     */
    @Test
    fun `a scheme's chrome is dark or paper, never between`() {
        for (scheme in Schemes.ALL) {
            val luma = Scheme.luma(scheme.gray)
            assertTrue(luma < 90 || luma > 200, "${scheme.id}: chrome ${"%06x".format(scheme.gray)} is neither dark nor paper (luma $luma)")
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
        for (scheme in Schemes.ALL) {
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
        assertEquals(0x3A1005, Schemes.padLabelInk(Schemes.DEFAULT, DrumClass.KICK))
    }

    @Test
    fun `scheme lookup and css cross-reference`() {
        assertEquals(Schemes.OPENSKY, Schemes[SchemeId.OPENSKY])
        assertEquals("t-vent", Schemes.DEFAULT.cssClass)
        assertEquals("t-opensky", Schemes[SchemeId.OPENSKY].cssClass)
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

    /**
     * VENT is transcribed from the VENT audition page's `:root`
     * (`docs/DIRECTION_2026_10.md` §4). The slot each page token fills is
     * the thing worth pinning: a retune that moves `--panel` must move
     * `gray`, not `field`.
     */
    @Test
    fun `VENT matches the audition page's root exactly`() {
        val s = Schemes.VENT
        assertEquals("t-vent", s.cssClass)
        assertEquals(0x1D1E1B, s.gray, "--panel — the chrome")
        assertEquals(0x232420, s.field, "--raised — fields, pads, the raised fill")
        assertEquals(s.field, s.raised, "the page has one raised fill")
        assertEquals(0x393B33, s.grayHi, "--line — the hairline stands in for the bevel highlight")
        assertEquals(0xEEEADD, s.ink, "--text")
        assertEquals(0xB3B4A6, s.ink2, "--muted")
        assertEquals(0x929587, s.ink3, "--quiet")
        assertEquals(0xE08A68, s.accent, "--accent — selection")
        assertEquals(s.accent, s.amber, "one ember serves chrome and LCD alike")
        assertEquals(s.accent, s.warn, "and the needle")
        assertEquals(0xEEEADD, s.lcdInk, "the dock's text is the page's text")
        assertEquals(0x151614, s.desk1, "--bg, flat: desk1 == desk2")
        assertEquals(s.desk1, s.desk2)
    }

    /**
     * VENT's third text tier clears AA on its own chrome. OPENSKY's does
     * not, and `ContrastTest` pins that gap as a measured one; this pins
     * that the dark scheme has no known text-contrast gap.
     */
    @Test
    fun `VENT's third ink tier clears AA text contrast`() {
        val s = Schemes.VENT
        val ratio = Scheme.contrastRatio(s.ink3, s.gray)
        assertTrue(ratio >= 4.5, "VENT ink3 vs gray = %.2f:1, below AA text".format(ratio))
    }

    /**
     * The modern look's light pad keeps the pad's name in its class colour,
     * darkened until it reads: the eight class colours were tuned for dark
     * pads (lime on paper is 1.6 : 1), and a paper scheme's pad is the card.
     * Every class, on every paper scheme's card, clears AA; on a dark
     * scheme the function is never called, and the handoff's 55 %-to-white
     * tint stands.
     */
    @Test
    fun `every class colour reads on a paper scheme's pad once darkened`() {
        for (scheme in Schemes.ALL.filter { Scheme.luma(it.gray) >= 90 }) {
            for (dc in DrumClass.entries) {
                val cls = Schemes.classColor(dc)
                val ink = Schemes.classInkOn(cls, scheme.raised)
                val ratio = Scheme.contrastRatio(ink, scheme.raised)
                assertTrue(ratio >= 4.5, "${scheme.id}/$dc: name ink ${"%06x".format(ink)} is %.2f:1 on the card".format(ratio))
            }
        }
        // And a colour that already reads is left alone: the function is a
        // floor, not a filter.
        assertEquals(0x292820, Schemes.classInkOn(0x292820, Schemes.OPENSKY.raised))
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
     * Nothing here can assert the frame's Compose tree - this is the
     * arithmetic that says the frame has the room.
     *
     * The frame is the four-place shape's now (`docs/SCREEN_PLAN_2026_10.md`
     * §1): title bar, place row, stage row (on TAPE and PROJECT, the deepest
     * case) and the dock at the foot. The stage row is a segment row, which
     * is a hit target tall like the place row.
     */
    @Test
    fun `the chrome still leaves the body most of the frame`() {
        val chrome = Layout.TITLEBAR_H + Layout.MENU_ROW_H + Layout.MIN_HIT_TARGET + Layout.DOCK_H
        assertTrue(
            chrome < Layout.FRAME_H / 4,
            "titlebar + place row + stage row + dock = ${chrome}dp of a ${Layout.FRAME_H}dp frame",
        )
    }

    @Test
    fun `the dock is at least a hit target tall`() {
        assertTrue(
            Layout.DOCK_H >= Layout.MIN_HIT_TARGET,
            "the dock holds the SNIP and door chips: DOCK_H=${Layout.DOCK_H} against MIN_HIT_TARGET=${Layout.MIN_HIT_TARGET}",
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
