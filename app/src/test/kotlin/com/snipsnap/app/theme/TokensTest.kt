package com.snipsnap.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokensTest {

    @Test
    fun `packed rgb becomes an opaque colour`() {
        assertEquals(Color(0xFFC3C7CB), 0xC3C7CB.toColor())
        assertEquals(Color(0xFF000000), 0x000000.toColor())
        assertEquals(Color(0xFFFFFFFF), 0xFFFFFF.toColor())
    }

    @Test
    fun `every scheme token survives the trip to Compose`() {
        // Compare packed ints via toArgb(), not reconstructed float
        // channels: Color stores each channel as a Float, so `red * 255`
        // can land at 194.99999 for 0xC3 and truncate to 194.
        for (scheme in Schemes.ALL) {
            for ((name, rgb) in scheme.tokens()) {
                assertEquals(
                    rgb or 0xFF000000.toInt(),
                    rgb.toColor().toArgb(),
                    "$name in ${scheme.id} round-tripped wrong",
                )
            }
        }
    }

    /**
     * The two-surface rule as a *relative* invariant: in every scheme the
     * LCD is darker than the chrome it sits on.
     *
     * This is stricter than `:shell`'s own `SchemesTest`, which asserts the
     * absolute `luma(lcd) < 40`. OILSLICK's chrome is `0x221A34` — luma ≈ 31,
     * already under that threshold — so an LCD anywhere in the 31–40 band
     * would pass `:shell` and still be lighter than the surface around it.
     * That band is what this test closes.
     *
     * What it does *not* cover: it reads the raw `Int` fields, so it says
     * nothing about `toColor()` or about `LcdSurface`/`ChromeSurface`
     * picking the right field. Those two lines of wiring are trusted by
     * inspection until there is a Compose test harness to check them.
     */
    @Test
    fun `the lcd is darker than the chrome in every scheme`() {
        for (scheme in Schemes.ALL) {
            assertTrue(
                Scheme.luma(scheme.lcd) < Scheme.luma(scheme.gray),
                "${scheme.id}: LCD is not darker than chrome",
            )
        }
    }
}
