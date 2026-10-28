package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass

/**
 * The TapeOS scheme system as data — VENT (dark) and OPENSKY (paper), the
 * two schemes of the one look the app has (`docs/DIRECTION_2026_10.md`).
 * `design/Schemes.dc.html` still shows them beside the schemes retired on
 * 2026-10-27, and stays as the visual reference for the history.
 *
 * The app's theme object reads these tables; a colour that exists only in
 * a design file cannot be regression-tested. Colours are packed `0xRRGGBB`
 * ints.
 *
 * **The two-surface rule** — the one TapeOS invariant every scheme obeys:
 * the chrome is where you *work* (buttons, windows, pads), the dark LCD is
 * where sound *lives* (readouts, waveforms, play mode). The chrome may be
 * dark or paper; the LCD is dark in both, and `SchemesTest` enforces it.
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
    /**
     * The "amber" accent slot (second LCD colour). It is the LCD's colour and
     * never sits on chrome — on paper it is 2.1 : 1 — so a chrome site reads
     * [accent] (`docs/MODERN_LOOK.md` §4).
     */
    val amber: Int,
    /** Sunken input/list background. */
    val field: Int,
    /**
     * Warnings, the GROOVE needle, onset bars. Always warm, and a slot of its
     * own rather than a borrowed [amber]: a scheme may spend its amber slot on
     * a cool colour, and a needle drawn with it would vanish into the
     * readouts it is supposed to cross.
     */
    val warn: Int = 0xFFB000,
    /**
     * Third text tier, below [ink2], for the dimmest chrome labels; a scheme
     * that does not need one reuses [ink2].
     */
    val ink3: Int = ink2,
    /** Selection: the chosen menu item, the 3px inset bar on a selected row. */
    val accent: Int = title2,
    /** Raised-surface gradient start (buttons, empty pads). Ends at [field]. */
    val raised: Int = gray,
    /** Window-body gradient start. Ends at [grayMid]. */
    val win: Int = grayMid,
    /** The one line `Modifier.windowFrame` draws around the window. */
    val winFrame: Int = title2,
    /** Centre of the radial glow behind the desk; fades to [desk1]. */
    val deskGlow: Int = desk2,
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

enum class SchemeId(val displayName: String) {
    /** Slate, warm off-white ink and one ember accent — the dark scheme, and the default. */
    VENT("VENT"),
    /** Paper, ink and a deep orange — the first light modern scheme, from the OPENSKY audition page (`docs/MODERN_LOOK.md` §2). */
    OPENSKY("OPENSKY"),
}

object Schemes {

    /**
     * VENT — the dark scheme (`docs/DIRECTION_2026_10.md` §4), transcribed
     * from the VENT audition page's `:root` and the slots its components
     * fill: slate chrome, warm off-white ink, one ember accent that serves
     * chrome and LCD alike. The page draws no bevels and no gradients, so
     * the bevel highlight slot is the page's hairline and the title bar is
     * a dark band, the page's masthead being the ground with a hairline.
     *
     * Every value clears every law in `SchemesTest` and `ContrastTest` as
     * written, including a third ink tier at 5.48:1. Pinned by
     * `SchemesTest`.
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
        // the floor of SchemesTest's "selection is visible" law, and the
        // frame needs the margin.
        raised = 0x232420, win = 0x1D1E1B, winFrame = 0x55564B, deskGlow = 0x2A1E18,
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
        bin = 0x813600, binLcd = 0xFF8C00,
    )

    /**
     * Picker order: every [SchemeId], in the enum's order. [get] is `first`
     * over this list, so a [SchemeId] missing from it would throw at launch;
     * `SchemesTest` holds the two together.
     */
    val ALL: List<Scheme> = listOf(VENT, OPENSKY)

    /** What a fresh install, a saved name that is no longer a [SchemeId], and every export without a choice get. */
    val DEFAULT: Scheme = VENT

    operator fun get(id: SchemeId): Scheme = ALL.first { it.id == id }

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
     * raises luminance until the worst-case scheme of the day (METAL, the
     * lightest gray, since retired) cleared 4.5:1. See ContrastTest.
     */
    const val BIN_RED_GLOW: Int = 0xD6877B

    /**
     * How much of [Scheme.lcdInk] a secondary line on the LCD keeps - the
     * LCD's own second tier. `ink2` and `ink3` are chrome inks, tuned
     * against `gray`; on a paper scheme the LCD is the dark surface and they
     * land at 2.8 : 1 and 3.8 : 1 on it. `ContrastTest` holds this alpha, over
     * the LCD, at AA in every scheme.
     */
    const val LCD_INK2_ALPHA: Float = 0.75f

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
     * absorbs; [STATUS_BAR_H] is no longer cut to by any row (the fullscreen
     * grid's header is a hit target tall).
     */
    const val DOCK_H = 56
    const val STATUS_BAR_H = 26
    const val LCD_HEADER_H = 40
    const val PAD_GAP = 8
    const val PAD_H = 76

    /**
     * PADS' own pad height. PAD_H is KEYS', SYNTH's slot chooser's and the
     * spread preview's too, and KEYS cannot scroll; the PADS grid is the
     * one that got 56dp back when the switcher row went into the header
     * (UX review S6/P1), and the first look is a row of pads that should
     * read as pads.
     */
    const val KIT_PAD_H = 88
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
 * Motion constants — "TapeOS is snappy, not springy": these nine are the
 * complete animation budget. The last three are the loading drawings (the
 * busy bar's sweep, the spinner's turn, a placeholder's pulse); each stands
 * still when the system has animations off (`LocalReduceMotion`).
 */
object Motion {
    /**
     * Reel rotation period, ms per revolution: the deck's reels while
     * playing, and the two loading reels (Export's dub reel and the strip
     * beside a trip's words), which stand still when animations are off.
     */
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
    /** One pass of the busy bar's sweep across its track, ms, linear, repeating. */
    const val BUSY_SWEEP_MS = 1100
    /** One turn of the spinner, ms per revolution, linear, repeating. */
    const val SPINNER_MS = 900
    /** One breath of a placeholder where a result will land, ms, repeating. */
    const val PLACEHOLDER_PULSE_MS = 900
}
