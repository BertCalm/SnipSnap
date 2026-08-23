package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass

/**
 * The TapeOS scheme system as data — the single Kotlin source for what
 * `design/Main.dc.html` keeps as CSS custom properties (`.t-chrome` …
 * `.t-clear`) and what the two working prototypes render live.
 *
 * The app's theme object reads these tables; the design files remain the
 * visual reference, but a colour that exists only in a design file cannot
 * be regression-tested. Colours are packed `0xRRGGBB` ints.
 *
 * **The two-surface rule** — the one TapeOS invariant every scheme obeys:
 * gray bevelled chrome is where you *work* (buttons, windows, pads), the
 * dark LCD is where sound *lives* (readouts, waveforms, play mode). Light
 * schemes lighten the chrome; the LCD stays dark in all six, and
 * `SchemesTest` enforces it.
 */
data class Scheme(
    val id: SchemeId,
    /** CSS class in the design files (`t-chrome`…), for cross-reference. */
    val cssClass: String,
    /** Bevel body — the working-surface gray. */
    val gray: Int,
    /** Bevel highlight (top-left of raised). */
    val grayHi: Int,
    /** Bevel edge, between highlight and body. */
    val grayEdge: Int,
    /** Bevel mid shadow. */
    val grayMid: Int,
    /** Bevel dark shadow (bottom-right of raised). */
    val grayDark: Int,
    /** Primary text on chrome. */
    val ink: Int,
    /** Secondary text on chrome. */
    val ink2: Int,
    /** Titlebar gradient start. */
    val title1: Int,
    /** Titlebar gradient end. */
    val title2: Int,
    /** Titlebar text. */
    val titleInk: Int,
    /** Desk checkerboard colours (behind the window). */
    val desk1: Int,
    val desk2: Int,
    /** The dark sound surface. Dark in every scheme — the two-surface rule. */
    val lcd: Int,
    /** Primary LCD glow ink. */
    val lcdInk: Int,
    /** The "amber" accent slot (warnings, needle, second LCD colour). */
    val amber: Int,
    /** Sunken input/list background. */
    val field: Int,
) {
    /** Rec.601 luma 0..255 — used by the two-surface test and ink fallbacks. */
    companion object {
        fun luma(rgb: Int): Int {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return (299 * r + 587 * g + 114 * b) / 1000
        }
    }
}

enum class SchemeId(val displayName: String) {
    CHROME("CHROME"),
    FERRIC("FERRIC"),
    METAL("METAL"),
    SNACK_BAR("SNACK BAR"),
    OILSLICK("OILSLICK"),
    CLEAR("CLEAR"),
}

object Schemes {

    val CHROME = Scheme(
        SchemeId.CHROME, "t-chrome",
        gray = 0xC3C7CB, grayHi = 0xFFFFFF, grayEdge = 0xE8EBEE, grayMid = 0x868A8E, grayDark = 0x3F4347,
        ink = 0x1C1E20, ink2 = 0x55595D,
        title1 = 0x000082, title2 = 0x1878C8, titleInk = 0xFFFFFF,
        desk1 = 0x0A7A78, desk2 = 0x0C817F,
        lcd = 0x0C130B, lcdInk = 0x49E83E, amber = 0xFFB000, field = 0xFFFFFF,
    )

    val FERRIC = Scheme(
        SchemeId.FERRIC, "t-ferric",
        gray = 0xD4C8A8, grayHi = 0xFAF4E4, grayEdge = 0xE6DCC0, grayMid = 0x9A8C6A, grayDark = 0x463A26,
        ink = 0x2C2214, ink2 = 0x6A5C42,
        title1 = 0x6A3210, title2 = 0xC87828, titleInk = 0xFAF4E4,
        desk1 = 0x8A5A24, desk2 = 0x936129,
        lcd = 0x140E06, lcdInk = 0xFFB000, amber = 0xFF7A1A, field = 0xFAF4E4,
    )

    val METAL = Scheme(
        SchemeId.METAL, "t-metal",
        gray = 0x2E3136, grayHi = 0x4A4F56, grayEdge = 0x3A3E44, grayMid = 0x17191C, grayDark = 0x060708,
        ink = 0xD8DBE0, ink2 = 0x8A9099,
        title1 = 0x0A0A0C, title2 = 0x2C3038, titleInk = 0x7ADFE4,
        desk1 = 0x101215, desk2 = 0x14171B,
        lcd = 0x0A0C0E, lcdInk = 0x7ADFE4, amber = 0xFFB000, field = 0x1B1E22,
    )

    val SNACK_BAR = Scheme(
        SchemeId.SNACK_BAR, "t-snackbar",
        gray = 0xFFD400, grayHi = 0xFFF0A0, grayEdge = 0xFFE45C, grayMid = 0xB89200, grayDark = 0x5C4A00,
        ink = 0x241C00, ink2 = 0x6A5600,
        title1 = 0xE81C1C, title2 = 0xE81C1C, titleInk = 0xFFFFFF,
        desk1 = 0xE81C1C, desk2 = 0xEE2424,
        lcd = 0x1A0404, lcdInk = 0xFFD400, amber = 0xFFFFFF, field = 0xFFF8D8,
    )

    val OILSLICK = Scheme(
        SchemeId.OILSLICK, "t-oilslick",
        gray = 0x221A34, grayHi = 0x40306A, grayEdge = 0x2A2044, grayMid = 0x120E1A, grayDark = 0x060410,
        ink = 0xC8B2F8, ink2 = 0x7A6AA0,
        title1 = 0x5A2AE0, title2 = 0xE040C8, titleInk = 0xFFFFFF,
        desk1 = 0x0C0618, desk2 = 0x140B24,
        lcd = 0x0A0714, lcdInk = 0xC8B2F8, amber = 0x40E0E8, field = 0x161020,
    )

    val CLEAR = Scheme(
        SchemeId.CLEAR, "t-clear",
        gray = 0xE2E6EC, grayHi = 0xFFFFFF, grayEdge = 0xF2F5F9, grayMid = 0x9AA4B0, grayDark = 0x4A525C,
        ink = 0x242A32, ink2 = 0x6E7884,
        title1 = 0x3888B8, title2 = 0x78C8E8, titleInk = 0xFFFFFF,
        desk1 = 0xB8C8D4, desk2 = 0xBFCEDA,
        lcd = 0x101418, lcdInk = 0x9AE8FF, amber = 0xFF9A1A, field = 0xFFFFFF,
    )

    /** Picker order — the order the design system tells its story in. */
    val ALL: List<Scheme> = listOf(CHROME, FERRIC, METAL, SNACK_BAR, OILSLICK, CLEAR)

    val DEFAULT: Scheme = CHROME

    operator fun get(id: SchemeId): Scheme = ALL.first { it.id == id }

    /**
     * OILSLICK's signature sweep gradient (titlebar, window frame, primary
     * buttons): `conic-gradient(from 210deg, …)`. Compose maps this to
     * `Brush.sweepGradient`; on API < 33 the handoff's fallback is a linear
     * gradient over the first three stops.
     */
    val OILSLICK_SWEEP: List<Int> = listOf(0x5A2AE0, 0xE040C8, 0x40E0E8, 0x8A5AF0, 0x5A2AE0)

    /**
     * Class colour for a drum class — one language across shell pads, app
     * pads and MPC pads. Sourced from [AutoPlace.colorFor] so the value
     * exists exactly once in the codebase.
     */
    fun classColor(drumClass: DrumClass): Int =
        AutoPlace.colorFor(drumClass).removePrefix("#").toInt(16)

    /**
     * Ink for a handwritten pad label sitting **on** a class-coloured pad.
     *
     * Dark schemes use near-black class-tinted inks; CLEAR (a light scheme)
     * needs stronger, saturated darks to stay legible on the same colours —
     * both tables verbatim from the working prototypes. A class colour not
     * in the table (TONAL, UNKNOWN) falls back to darkening the class
     * colour itself, biased by how dark the tables run.
     */
    fun padLabelInk(scheme: Scheme, drumClass: DrumClass): Int {
        val c = classColor(drumClass)
        val table = if (scheme.id == SchemeId.CLEAR) CLEAR_PAD_INK else DARK_PAD_INK
        return table[c] ?: darken(c, if (scheme.id == SchemeId.CLEAR) 0.35f else 0.75f)
    }

    private val DARK_PAD_INK: Map<Int, Int> = mapOf(
        0xE8542E to 0x3A1005, // kick
        0xFFC41F to 0x3A2C02, // snare
        0x1FC6CF to 0x062E30, // hat closed
        0x7ADFE4 to 0x0A3234, // hat open
        0xE8409F to 0x3A0A24, // clap
        0x9A6CF0 to 0x241040, // tom
        0x8FD424 to 0x1E3006, // perc
        0x3F8CF0 to 0x0C1C3A, // loop
    )

    private val CLEAR_PAD_INK: Map<Int, Int> = mapOf(
        0xE8542E to 0xC73A12,
        0xFFC41F to 0xA87800,
        0x1FC6CF to 0x0E6870,
        0x7ADFE4 to 0x1A8A94,
        0xE8409F to 0xC0207A,
        0x9A6CF0 to 0x6A3EC0,
        0x8FD424 to 0x4A7A10,
        0x3F8CF0 to 0x2C5EA8,
    )

    /** Each channel scaled toward black by [amount] (0 = unchanged, 1 = black). */
    fun darken(rgb: Int, amount: Float): Int {
        val k = (1f - amount).coerceIn(0f, 1f)
        val r = (((rgb shr 16) and 0xFF) * k).toInt()
        val g = (((rgb shr 8) and 0xFF) * k).toInt()
        val b = ((rgb and 0xFF) * k).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}

/**
 * The four TapeOS typefaces and where they're allowed. Sizes are dp at the
 * design width — see [Layout.FRAME_W].
 */
object Type {
    /** VT323 — everything on an LCD. Headers 25/21, readouts 22, small 17–18. */
    const val LCD = "VT323"

    /** Silkscreen — pixel UI chrome. 9dp (8 for status quips), +0.5 tracking. */
    const val PIXEL = "Silkscreen"

    /** Michroma — display headers and key actions. 11–12dp, +2 tracking. */
    const val DISPLAY = "Michroma"

    /** Permanent Marker — handwriting on pads and cassette labels. 13–15dp. */
    const val MARKER = "Permanent Marker"
}

/** Layout constants from the handoff, dp at the 390dp design width. */
object Layout {
    const val FRAME_W = 390
    const val FRAME_H = 844
    const val OUTER_MARGIN = 12
    const val TITLEBAR_H = 34
    const val MENU_ROW_H = 26
    const val STATUS_BAR_H = 26
    const val LCD_HEADER_H = 40
    const val PAD_GAP = 8
    const val PAD_H = 76
    const val PAD_RADIUS = 6
    const val PRIMARY_ACTION_H = 52
    const val MIN_HIT_TARGET = 44
}

/**
 * Motion constants — "TapeOS is snappy, not springy": these five are the
 * complete animation budget.
 */
object Motion {
    /** Reel rotation period while playing, ms per revolution. */
    const val REEL_SPIN_MS = 1200
    /** Toast rise+fade, ms, ease-out. */
    const val TOAST_IN_MS = 250
    /** Toast dwell before dismissing itself, ms. */
    const val TOAST_DWELL_MS = 2600
    /** Pad hit glow decay, ms. */
    const val PAD_GLOW_MS = 180
    /** Export dub progress, ms per file. */
    const val DUB_FILE_MS = 180
    /** Status-bar quip rotation, ms. */
    const val QUIP_ROTATE_MS = 6000
}
