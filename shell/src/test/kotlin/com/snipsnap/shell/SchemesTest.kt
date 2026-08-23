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
}
