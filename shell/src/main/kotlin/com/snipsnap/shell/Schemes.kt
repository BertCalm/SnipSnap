package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass

/**
 * The TapeOS scheme system as data — the single Kotlin source for what
 * `design/Schemes.dc.html` keeps as CSS custom properties (`.t-metal` …
 * `.t-vent`) and what the two working prototypes render live.
 *
 * The app's theme object reads these tables; the design files remain the
 * visual reference, but a colour that exists only in a design file cannot
 * be regression-tested. Colours are packed `0xRRGGBB` ints.
 *
 * **The two-surface rule** — the one TapeOS invariant every scheme obeys:
 * gray bevelled chrome is where you *work* (buttons, windows, pads), the
 * dark LCD is where sound *lives* (readouts, waveforms, play mode). Every
 * scheme is dark now, and `SchemesTest` enforces it.
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
    /** The "amber" accent slot (second LCD colour). Cyan in OILSLICK. */
    val amber: Int,
    /** Sunken input/list background. */
    val field: Int,
    /**
     * Warnings, the GROOVE needle, onset bars. Always warm, and deliberately
     * *not* [amber] — OILSLICK spends its amber slot on cyan, so a needle
     * drawn with [amber] vanishes into the readouts it is supposed to cross.
     */
    val warn: Int = 0xFFB000,
    /**
     * Third text tier, below [ink2]. OILSLICK uses it for the dimmest
     * chrome labels; schemes that never needed a third tier reuse [ink2].
     */
    val ink3: Int = ink2,
    /** Selection: the chosen menu item, the 3px inset bar on a selected row. */
    val accent: Int = title2,
    /** Raised-surface gradient start (buttons, empty pads). Ends at [field]. */
    val raised: Int = gray,
    /** Window-body gradient start. Ends at [grayMid]. */
    val win: Int = grayMid,
    /**
     * Window frame, 3px. Replaces the bevel highlight/shadow pair for
     * schemes that don't bevel — OILSLICK draws [Schemes.OILSLICK_SWEEP]
     * here instead of a flat colour.
     */
    val winFrame: Int = title2,
    /** Centre of the radial glow behind the desk; fades to [desk1]. */
    val deskGlow: Int = desk2,
    /**
     * Which drawing these colours are for (`docs/DIRECTION_2026_10.md`
     * §4): CLASSIC is the bevelled TapeOS surface language in `Bevel.kt`;
     * MODERN is the audition pages' — flat fills, hairlines, system
     * faces — which the SDK session draws. Data only until then: the
     * picker groups by it, and a MODERN scheme under the CLASSIC drawing
     * still reads (VENT's KDoc says how).
     */
    val look: Look = Look.CLASSIC,
    /**
     * Destructive-action text — STOP, DELETE, EMPTY THE BIN, FORGET → BIN
     * — which must read as red in every scheme (`docs/MODERN_LOOK.md` §4).
     * [Schemes.BIN_RED_GLOW] by default, the one value tuned for dark
     * chrome; a paper scheme gives its own, because a glow tuned to read on
     * dark reads at 2.5 : 1 on paper. `ContrastTest` holds every scheme's
     * to AA on its chrome and its LCD.
     */
    val bin: Int = Schemes.BIN_RED_GLOW,
    /**
     * The same word on the LCD — the dock's STOP. One red cannot clear AA
     * on both a paper chrome and a dark LCD (the luminance a paper needs
     * is below what a dark surface needs), so a paper scheme gives the
     * dock its own: the page's active player light, which is what its
     * dock turns the word. [bin] by default, which reads on both where the
     * chrome is dark.
     */
    val binLcd: Int = bin,
) {
    /** Rec.601 luma 0..255 — used by the two-surface test and ink fallbacks. */
    companion object {
        fun luma(rgb: Int): Int {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return (299 * r + 587 * g + 114 * b) / 1000
        }

        /**
         * sRGB channel -> linear-light, per the WCAG 2.x relative-luminance
         * definition (not the Rec.601 [luma] above, which is a fast
         * perceptual approximation good enough for the two-surface/ink
         * checks but not a real contrast ratio).
         */
        private fun linearChannel(c: Int): Double {
            val cs = c / 255.0
            return if (cs <= 0.04045) cs / 12.92 else Math.pow((cs + 0.055) / 1.055, 2.4)
        }

        /** WCAG relative luminance, 0..1, of a packed 0xRRGGBB colour. */
        fun relativeLuminance(rgb: Int): Double {
            val r = linearChannel((rgb shr 16) and 0xFF)
            val g = linearChannel((rgb shr 8) and 0xFF)
            val b = linearChannel(rgb and 0xFF)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        /**
         * WCAG contrast ratio between two packed 0xRRGGBB colours:
         * `(L1+0.05)/(L2+0.05)`, lighter over darker — 1:1 (none) to 21:1
         * (max). This is the real ratio the WCAG AA thresholds (4.5:1 text,
         * 3:1 UI components/large text) are defined against; see
         * `ContrastTest` for the guardrails built on it.
         */
        fun contrastRatio(a: Int, b: Int): Double {
            val la = relativeLuminance(a)
            val lb = relativeLuminance(b)
            val lighter = maxOf(la, lb)
            val darker = minOf(la, lb)
            return (lighter + 0.05) / (darker + 0.05)
        }
    }
}

/** The two surface languages a scheme can be drawn in. The second axis beside [SchemeId]. */
enum class Look(val displayName: String) {
    CLASSIC("CLASSIC"),
    MODERN("MODERN"),
}

enum class SchemeId(val displayName: String) {
    METAL("METAL"),
    OILSLICK("OILSLICK"),
    PETROL("PETROL"),
    INFRARED("INFRARED"),
    ACID("ACID"),
    SODIUM("SODIUM"),
    ICE("ICE"),
    VAPOR("VAPOR"),
    VENT("VENT"),
    /** Paper, ink and a deep orange — the first light modern scheme, from the OPENSKY audition page (`docs/MODERN_LOOK.md` §2). */
    OPENSKY("OPENSKY"),
}

object Schemes {

    val METAL = Scheme(
        SchemeId.METAL, "t-metal",
        gray = 0x2E3136, grayHi = 0x4A4F56, grayEdge = 0x3A3E44, grayMid = 0x17191C, grayDark = 0x060708,
        // ink2 lightened from 0x8A9099 (4.06:1 against gray, fails AA) to
        // clear 4.5:1 — same hue/saturation, luminance raised. See
        // ContrastTest.
        ink = 0xD8DBE0, ink2 = 0x959AA3,
        title1 = 0x0A0A0C, title2 = 0x2C3038, titleInk = 0x7ADFE4,
        desk1 = 0x101215, desk2 = 0x14171B,
        lcd = 0x0A0C0E, lcdInk = 0x7ADFE4, amber = 0xFFB000, field = 0x1B1E22,
        // title2 (0x2C3038, luma 47) is one luma step from gray (0x2E3136,
        // luma 48) — the default accent/winFrame would put the selected
        // menu item's fill and the 3px selected-row bar right on top of the
        // chrome behind them. titleInk is METAL's only saturated colour
        // (luma 193, 145 clear of gray), so pin selection to it instead.
        accent = 0x7ADFE4, winFrame = 0x7ADFE4,
    )

    val OILSLICK = Scheme(
        SchemeId.OILSLICK, "t-oilslick",
        gray = 0x221A34, grayHi = 0x40306A, grayEdge = 0x2A2044, grayMid = 0x120E1A, grayDark = 0x060410,
        // ink2 lightened from 0x7A6AA0 (3.47:1 against gray, the worst of
        // every scheme then, and OILSLICK is Schemes.DEFAULT) to clear 4.5:1. Same
        // hue/saturation, luminance raised. See ContrastTest.
        ink = 0xC8B2F8, ink2 = 0x8D80AE,
        title1 = 0x5A2AE0, title2 = 0xE040C8, titleInk = 0xFFFFFF,
        desk1 = 0x0C0618, desk2 = 0x140B24,
        lcd = 0x0A0714, lcdInk = 0xC8B2F8, amber = 0x40E0E8, field = 0x161020,
        // ink3 (2.14:1 against gray) deliberately left below 4.5:1 AA:
        // it's the third tier below the now-brightened ink2 (4.61:1), and
        // the minimum lightening that clears 4.5:1 here (measured in
        // ContrastTest) lands within a few percent of ink2 itself,
        // erasing the two-tier hierarchy ink3 exists to draw. Left as an
        // explicit, measured gap rather than a silent one — see
        // ContrastTest's own note on this scheme/token.
        warn = 0xFFB000, ink3 = 0x584A80, win = 0x1A1424, deskGlow = 0x2A1050,
    )

    val PETROL = Scheme(
        SchemeId.PETROL, "t-petrol",
        gray = 0x141A28, grayHi = 0x30406A, grayEdge = 0x1A2438, grayMid = 0x0C101C, grayDark = 0x04060E,
        // ink2 lightened from 0x6A7CA0 (4.14:1 against gray, fails AA) to
        // clear 4.5:1 — same hue/saturation, luminance raised. See
        // ContrastTest.
        ink = 0xB2C8F8, ink2 = 0x7384A6,
        title1 = 0x2A6AE0, title2 = 0x40E890, titleInk = 0xFFFFFF,
        desk1 = 0x060A18, desk2 = 0x0B1224,
        lcd = 0x070A14, lcdInk = 0xB2C8F8, amber = 0x40E890, field = 0x101624,
    )

    val INFRARED = Scheme(
        SchemeId.INFRARED, "t-infrared",
        gray = 0x221218, grayHi = 0x5C3040, grayEdge = 0x2C141A, grayMid = 0x12080C, grayDark = 0x0A0304,
        // ink2 lightened from 0xA06A78 (4.13:1 against gray, fails AA) to
        // clear 4.5:1 — same hue/saturation, luminance raised. See
        // ContrastTest.
        ink = 0xF8B2C0, ink2 = 0xA67380,
        title1 = 0x9A2AE0, title2 = 0xE02A5A, titleInk = 0xFFFFFF,
        desk1 = 0x160408, desk2 = 0x200A10,
        lcd = 0x120608, lcdInk = 0xF8B2C0, amber = 0xFF8A1A, field = 0x1A0C12,
        warn = 0xFF8A1A,
    )

    val ACID = Scheme(
        SchemeId.ACID, "t-acid",
        gray = 0x161E0E, grayHi = 0x4A6030, grayEdge = 0x243218, grayMid = 0x0E160A, grayDark = 0x060A02,
        ink = 0xD4F0A0, ink2 = 0x8AA060,
        title1 = 0x6AB010, title2 = 0x20D0E8, titleInk = 0xFFFFFF,
        desk1 = 0x0A1204, desk2 = 0x101A08,
        lcd = 0x0A1004, lcdInk = 0xD4F0A0, amber = 0x20D0E8, field = 0x121A0A,
    )

    val SODIUM = Scheme(
        SchemeId.SODIUM, "t-sodium",
        gray = 0x221A0C, grayHi = 0x5C4C28, grayEdge = 0x2C2410, grayMid = 0x120E06, grayDark = 0x080502,
        ink = 0xF0D8A8, ink2 = 0xA08A58,
        title1 = 0xC85A10, title2 = 0xFFB000, titleInk = 0xFFFFFF,
        desk1 = 0x160E02, desk2 = 0x201606,
        lcd = 0x120C04, lcdInk = 0xFFD25E, amber = 0xFF6A2A, field = 0x1A140A,
        warn = 0xFF6A2A,
    )

    val ICE = Scheme(
        SchemeId.ICE, "t-ice",
        gray = 0x10202E, grayHi = 0x305468, grayEdge = 0x16283A, grayMid = 0x081420, grayDark = 0x030A12,
        ink = 0xB8E2F8, ink2 = 0x6A92A8,
        title1 = 0x1A5AC8, title2 = 0x58C8FF, titleInk = 0xFFFFFF,
        desk1 = 0x04101C, desk2 = 0x081826,
        lcd = 0x060E16, lcdInk = 0xB8E2F8, amber = 0x58C8FF, field = 0x0C1A26,
    )

    val VAPOR = Scheme(
        SchemeId.VAPOR, "t-vapor",
        gray = 0x10201C, grayHi = 0x2E5C4A, grayEdge = 0x1A2C26, grayMid = 0x081410, grayDark = 0x040C08,
        ink = 0xA8F0D8, ink2 = 0x62A08A,
        title1 = 0x10B088, title2 = 0x40E8C0, titleInk = 0xFFFFFF,
        desk1 = 0x061410, desk2 = 0x0A1C16,
        lcd = 0x06120E, lcdInk = 0xA8F0D8, amber = 0xB27AF8, field = 0x0C1A16,
    )

    /**
     * VENT — the first scheme of the modern look (`docs/DIRECTION_2026_10.md`
     * §4), transcribed from the VENT audition page's `:root` and the slots
     * its components fill: slate chrome, warm off-white ink, one ember
     * accent that serves chrome and LCD alike. The page draws no bevels and
     * no gradients, so under today's drawing its bevel highlight is the
     * page's hairline and its title bar is a dark band (the page's masthead
     * is the ground with a hairline, which the OG title bar cannot draw);
     * both read correctly once the modern drawing lands and are honest
     * until then.
     *
     * Every value clears every law in `SchemesTest` and `ContrastTest` as
     * written — including a third ink tier at 5.48:1, which no earlier
     * scheme's does — so it ships as data ahead of the look that was
     * designed for it. Pinned by `SchemesTest`.
     */
    val VENT = Scheme(
        SchemeId.VENT, "t-vent",
        gray = 0x1D1E1B, grayHi = 0x393B33, grayEdge = 0x2D2F28, grayMid = 0x181916, grayDark = 0x0E0F0D,
        ink = 0xEEEADD, ink2 = 0xB3B4A6,
        title1 = 0x0F100E, title2 = 0x232420, titleInk = 0xEEEADD,
        desk1 = 0x151614, desk2 = 0x151614,
        lcd = 0x0F100E, lcdInk = 0xEEEADD, amber = 0xE08A68, field = 0x232420,
        warn = 0xE08A68, ink3 = 0x929587, accent = 0xE08A68,
        // winFrame is the page's axis stroke (#55564B), not its console
        // border (#45473C): the border sits exactly 40 luma from gray, on
        // the floor of SchemesTest's "selection is visible" law, and a
        // frame drawn 3dp in the OG look needs the margin.
        raised = 0x232420, win = 0x1D1E1B, winFrame = 0x55564B, deskGlow = 0x2A1E18,
        look = Look.MODERN,
    )

    /**
     * OPENSKY: the first paper scheme, every value from the audition page's
     * `:root` mapped onto the slot that does the same job
     * (`docs/MODERN_LOOK.md` §2). The August cut of light shells
     * (`DESIGN_GAP.md` §2f) is reopened for it on purpose, by the decision
     * of 2026-10-15: this is not CLEAR coming back — a pale bevelled shell
     * — but a printed page that happens to be light, drawn by the modern
     * look's flat forms. The two-surface rule holds: the LCD is the page's
     * dark dock, one step darker so it sits inside the dark-surface law
     * with margin, and `amber` reads there (6.9 : 1), never on paper
     * (2.1 : 1) — on chrome the modern look reaches for `accent`.
     *
     * `bin` is the page's own error colour, not the dark-chrome glow:
     * `BIN_RED_GLOW` is 2.5 : 1 on paper.
     */
    val OPENSKY = Scheme(
        SchemeId.OPENSKY, "t-opensky",
        gray = 0xF8F3E9, grayHi = 0xFFFFFF, grayEdge = 0xDBD5C7, grayMid = 0xEEE6D8, grayDark = 0xB4AD9E,
        ink = 0x292820, ink2 = 0x69675A,
        title1 = 0xF8F3E9, title2 = 0xF8F3E9, titleInk = 0x292820,
        desk1 = 0xEFE8DA, desk2 = 0xF3EDDF,
        lcd = 0x22211A, lcdInk = 0xF8F3E9, amber = 0xFF8C00, field = 0xFFFAF1,
        warn = 0xFF8C00, ink3 = 0x817A6B, accent = 0xA74C00,
        raised = 0xFFFAF1, win = 0xF8F3E9, winFrame = 0xB4AD9E, deskGlow = 0xFFD350,
        look = Look.MODERN,
        bin = 0x813600, binLcd = 0xFF8C00,
    )

    /** Picker order — the order the design system tells its story in. */
    val ALL: List<Scheme> = listOf(METAL, OILSLICK, PETROL, INFRARED, ACID, SODIUM, ICE, VAPOR, VENT, OPENSKY)

    /** The picker's groups: each look's schemes, in [ALL]'s order, looks in [Look]'s order. */
    fun byLook(): Map<Look, List<Scheme>> = Look.entries.associateWith { look -> ALL.filter { it.look == look } }

    val DEFAULT: Scheme = OILSLICK

    operator fun get(id: SchemeId): Scheme = ALL.first { it.id == id }

    /**
     * OILSLICK's signature sweep gradient (titlebar, window frame, primary
     * buttons): `conic-gradient(from 210deg, …)`. Compose maps this to
     * `Brush.sweepGradient`; on API < 33 the handoff's fallback is a linear
     * gradient over the first three stops.
     */
    val OILSLICK_SWEEP: List<Int> = listOf(0x5A2AE0, 0xE040C8, 0x40E0E8, 0x8A5AF0, 0x5A2AE0)

    /**
     * Destructive-action text - deliberately scheme-independent (a delete
     * reads as "red" even in a scheme with no red anywhere else), so it's a
     * single packed colour here rather than a per-Scheme field. Was
     * 0xC86050, which fails WCAG AA (4.5:1) against every scheme's `gray`
     * chrome (3.26-4.49:1 - the actual background this text is read on at
     * every raisedBevel-backed site; the lcd/field-backed sites were
     * already passing with more margin). 0xC86050 is the *lighter* of the
     * two colours in every pairing (luminance 0.212 vs. gray's
     * 0.008-0.030), so raising the ratio means brightening it, not
     * darkening it - this value keeps the original hue/saturation and only
     * raises luminance until the worst-case scheme (METAL, the lightest
     * gray) clears 4.5:1. See ContrastTest.
     */
    const val BIN_RED_GLOW: Int = 0xD6877B

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
     * Near-black class-tinted inks, verbatim from the working prototypes. A
     * class colour not in the table (TONAL, UNKNOWN) falls back to darkening
     * the class colour itself.
     */
    fun padLabelInk(scheme: Scheme, drumClass: DrumClass): Int {
        val c = classColor(drumClass)
        return DARK_PAD_INK[c] ?: darken(c, 0.75f)
    }

    /**
     * Ink for a pad's name on a light pad (`docs/MODERN_LOOK.md` §4's
     * "ink3 is 3.9 : 1 on paper", met for the class colours): the class
     * colour itself, darkened in 5 % steps just until it clears AA text
     * contrast against [surface], so lime stays lime and cyan stays cyan
     * while reading. On a dark surface the app tints the other way (55 %
     * to white, the handoff's rule) and never reaches for this. A colour
     * that cannot get there by 90 % darkening (none of the eight can;
     * `SchemesTest` says so) comes back as dark as it got.
     */
    fun classInkOn(classColor: Int, surface: Int): Int {
        var amount = 0f
        var ink = classColor
        while (Scheme.contrastRatio(ink, surface) < 4.5 && amount < 0.9f) {
            amount += 0.05f
            ink = darken(classColor, amount)
        }
        return ink
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

    /**
     * Rock Salt — handwriting on pads, cassette labels and loop blocks.
     * 11–15dp. Replaced Permanent Marker.
     *
     * The prototype drives this through a `--marker` CSS variable whose
     * "Look" control offers four faces and defaults to Rock Salt; the
     * `'Permanent Marker'` seen in the artboards is that variable's CSS
     * fallback, not the choice. `:app` vendors the matching TTF and pins
     * `TapeFonts.Marker` to it — Compose needs an `R.font` resource and
     * cannot read this string, so the two are kept in step by hand.
     */
    const val MARKER = "Rock Salt"
}

/** Layout constants from the handoff, dp at the 390dp design width. */
object Layout {
    const val FRAME_W = 390
    const val FRAME_H = 844
    const val OUTER_MARGIN = 12
    const val TITLEBAR_H = 34

    /**
     * The nav strip's height, and so the height of every tab in it.
     *
     * Was 26 — the handoff's figure, and September UAT finding 9: the row
     * a lost user reaches for first held eleven targets a little over half
     * the size [MIN_HIT_TARGET] sets for every other control in the app.
     * It is that floor now, held there by a test rather than by this
     * comment.
     *
     * The 22dp it costs comes out of the body below it, which is a
     * `weight(1f)` box in `App` — no fixed-pixel frame in this app is
     * measured off this constant, so the strip grows and the screen under
     * it shrinks by the same amount.
     *
     * A taller strip is only half of it: the row has to make each place
     * *fill* that height too. A 48dp strip around a text-sized tap area
     * is a taller strip with the same finding still in it. The place row
     * (`Frame.kt`, four chips, no scroll) is cut to this; the old menu
     * row's edge gutters and group seams went with its thirteen tabs.
     */
    const val MENU_ROW_H = 48

    /**
     * The dock at the foot of every screen (`docs/SCREEN_PLAN_2026_10.md`
     * §1): one LCD row holding the beat's transport, the capture light and
     * the standing line with its door. Taller than [MIN_HIT_TARGET] so a
     * two-line sentence fits beside a door chip without either shrinking.
     * It replaces the status bar at the frame's foot, whose three cells it
     * absorbs; [STATUS_BAR_H] stays for the one row still cut to it, the
     * fullscreen grid's own header.
     */
    const val DOCK_H = 56
    const val STATUS_BAR_H = 26
    const val LCD_HEADER_H = 40
    const val PAD_GAP = 8
    const val PAD_H = 76
    const val PAD_RADIUS = 6
    const val PRIMARY_ACTION_H = 52

    /**
     * `heightIn(min = MIN_HIT_TARGET.dp)` is a floor, not a size — inside an
     * unbounded-height parent (an unbounded scrolling column, say), a child
     * further down the tree that calls `fillMaxHeight()` with no content of
     * its own measures against that same unboundedness and collapses to
     * zero, not to the floor. Fixed twice on exactly that pattern — SYNTH's
     * `MacroSlider` and PADS SHEET's `StepperSlider` — by giving the track a
     * bounded `.height(MIN_HIT_TARGET.dp)` instead.
     *
     * Was 44 — 4dp under Android's 48dp accessibility minimum (WCAG 2.5.5 /
     * Android's own touch-target guidance) — applied consistently via
     * `heightIn(min = ...)` at ~70 call sites, so every one of them is a
     * floor, not a fixed size: raising it only ever grows a row, never
     * shrinks one. Every call site sits in a `fillMaxSize()`/scrollable
     * column with a flexible sibling absorbing the difference (verified:
     * KIT's action rows and PAD SHEET's body both scroll under a fixed
     * header/nav; GROOVE's transport rows sit above a `weight(1f)`
     * NeedleRoll) — no fixed-pixel frame in this app clips content when a
     * row grows by 4dp.
     */
    const val MIN_HIT_TARGET = 48

    /** LCD headers run 40–44 depending on whether they carry a counter. */
    const val LCD_HEADER_MAX_H = 44

    // LOOP — the six-column phasing grid.
    /** Track column header; tap toggles mute. */
    const val TRACK_HEADER_H = 24
    /** A block cell grows between these bounds to fill its column. */
    const val BLOCK_MIN_H = 30
    const val BLOCK_MAX_H = 46
    /** Landscape LOOP frame — transport moves to a top bar, cycle strip to the bottom. */
    const val LANDSCAPE_W = 816
    const val LANDSCAPE_H = 362

    // GROOVE — the needle-roll.
    /** The needle is fixed; the notes scroll under it. */
    const val NEEDLE_Y = 96
    /** Horizontal distance one step travels. */
    const val STEP_W = 20
    /** Note block height in a lane. */
    const val NOTE_H = 17
}

/**
 * Motion constants — "TapeOS is snappy, not springy": these five are the
 * complete animation budget.
 */
object Motion {
    /** Reel rotation period while playing, ms per revolution. */
    const val REEL_SPIN_MS = 1200
    /**
     * The dock line's fade-in when its words change, ms, ease-out. The
     * toast used to rise and fade over this and then dwell — 2.6 s for a
     * line you only read, 7 s for one carrying a door you had to reach.
     * Both dwells are gone with the toast: the line stays until the next
     * one replaces it and the door stays until it is taken, so nothing in
     * the dock is timed (J10's standing next step).
     */
    const val TOAST_IN_MS = 250
    /** Pad hit glow decay, ms. */
    const val PAD_GLOW_MS = 180
    /** Export dub progress, ms per file. */
    const val DUB_FILE_MS = 180
    /** Status-bar quip rotation, ms. */
    const val QUIP_ROTATE_MS = 6000
    /** The bubble swells while dragged, so the eject gesture reads as physical. */
    const val BUBBLE_DRAG_SCALE = 1.08f
}
