package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass

/**
 * The TapeOS scheme system as data — the single Kotlin source for what
 * `design/Schemes.dc.html` keeps as CSS custom properties (`.t-metal` …
 * `.t-vapor`) and what the two working prototypes render live.
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
    METAL("METAL"),
    OILSLICK("OILSLICK"),
    PETROL("PETROL"),
    INFRARED("INFRARED"),
    ACID("ACID"),
    SODIUM("SODIUM"),
    ICE("ICE"),
    VAPOR("VAPOR"),
}

object Schemes {

    val METAL = Scheme(
        SchemeId.METAL, "t-metal",
        gray = 0x2E3136, grayHi = 0x4A4F56, grayEdge = 0x3A3E44, grayMid = 0x17191C, grayDark = 0x060708,
        ink = 0xD8DBE0, ink2 = 0x8A9099,
        title1 = 0x0A0A0C, title2 = 0x2C3038, titleInk = 0x7ADFE4,
        desk1 = 0x101215, desk2 = 0x14171B,
        lcd = 0x0A0C0E, lcdInk = 0x7ADFE4, amber = 0xFFB000, field = 0x1B1E22,
    )

    val OILSLICK = Scheme(
        SchemeId.OILSLICK, "t-oilslick",
        gray = 0x221A34, grayHi = 0x40306A, grayEdge = 0x2A2044, grayMid = 0x120E1A, grayDark = 0x060410,
        ink = 0xC8B2F8, ink2 = 0x7A6AA0,
        title1 = 0x5A2AE0, title2 = 0xE040C8, titleInk = 0xFFFFFF,
        desk1 = 0x0C0618, desk2 = 0x140B24,
        lcd = 0x0A0714, lcdInk = 0xC8B2F8, amber = 0x40E0E8, field = 0x161020,
        warn = 0xFFB000, ink3 = 0x584A80, win = 0x1A1424, deskGlow = 0x2A1050,
    )

    val PETROL = Scheme(
        SchemeId.PETROL, "t-petrol",
        gray = 0x141A28, grayHi = 0x30406A, grayEdge = 0x1A2438, grayMid = 0x0C101C, grayDark = 0x04060E,
        ink = 0xB2C8F8, ink2 = 0x6A7CA0,
        title1 = 0x2A6AE0, title2 = 0x40E890, titleInk = 0xFFFFFF,
        desk1 = 0x060A18, desk2 = 0x0B1224,
        lcd = 0x070A14, lcdInk = 0xB2C8F8, amber = 0x40E890, field = 0x101624,
    )

    val INFRARED = Scheme(
        SchemeId.INFRARED, "t-infrared",
        gray = 0x221218, grayHi = 0x5C3040, grayEdge = 0x2C141A, grayMid = 0x12080C, grayDark = 0x0A0304,
        ink = 0xF8B2C0, ink2 = 0xA06A78,
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

    /** Picker order — the order the design system tells its story in. */
    val ALL: List<Scheme> = listOf(METAL, OILSLICK, PETROL, INFRARED, ACID, SODIUM, ICE, VAPOR)

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
     * 11–15dp. Replaced Permanent Marker; the LOOP section of the handoff
     * still names the old face and is stale.
     */
    const val MARKER = "Rock Salt"
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
    /** The bubble swells while dragged, so the eject gesture reads as physical. */
    const val BUBBLE_DRAG_SCALE = 1.08f
}
