package com.snipsnap.shell

/**
 * The delight system as data and rules — `docs/PERSONALITY.md` in
 * executable form, so law compliance is a unit test instead of a hope.
 *
 * The four laws:
 * 1. Plausible in 1996.
 * 2. Delight lives in the chrome, never in the signal path.
 * 3. Jokes never gate function.
 * 4. One visible gag per screen; the rest are hidden.
 *
 * The PERSONALITY slider (OFF / MILD / FULL, default FULL) gates
 * everything here; OFF is respected everywhere without argument.
 */
enum class Personality { OFF, MILD, FULL }

object Delight {

    /** Toasts show at MILD and FULL; quips and gags need FULL; OFF is silent. */
    fun toastsEnabled(level: Personality): Boolean = level != Personality.OFF

    fun quipsEnabled(level: Personality): Boolean = level == Personality.FULL

    /**
     * Law 2: UI sounds are hard-muted while a capture session is armed —
     * a deck thunk must never end up inside somebody's snip.
     */
    fun deckSoundsEnabled(level: Personality, captureArmed: Boolean): Boolean =
        level != Personality.OFF && !captureArmed
}

/**
 * Shipped copy. Everything the UI says lives here, in voice: a 90s
 * shareware program crossed with a mixtape-obsessed friend — confident,
 * terse, a little smug, never cutesy-apologetic. Funny copy still says
 * exactly what happened (law 3).
 */
object Copy {

    const val BOOT_READY = "SNIPSNAP.EXE — READY."

    /** Boot screen progress lines, in order. */
    val BOOT_LINES = listOf(
        "CHECKING HEADS…",
        "DEMAGNETIZING…",
        "CHROME BIAS: ON",
        "READY.",
    )

    // Empty states.
    const val EMPTY_SHELF = "NOTHING TAPED YET. GO STEAL A SOUND (LEGALLY)."
    const val EMPTY_KIT = "16 EMPTY PADS. TERRIFYING."

    // Capture.
    const val SESSION_ARMED = "TAPE ROLLING. GO STEAL A SOUND (LEGALLY)."
    const val SNIPPED = "SNIP! LAST 60s KEPT."
    const val BUBBLE_EJECTED = "EJECTED. TAPE IS KEPT."
    const val CAPTURE_BLOCKED =
        "TAPE JAM — SPOTIFY BLOCKS THE TAPE. USE THE SCREEN RECORDER, I'LL PULL THE AUDIO OUT."
    const val CAPTURE_BLOCKED_BUTTON = "FINE"

    // Tape deck.
    /** COMMIT toasts, rotated in order per commit. */
    val COMMIT_LINES = listOf(
        "TAPED. NO TAKEBACKS.",
        "IT'S OURS NOW.",
        "CLEAN CUT. NICE EARS.",
        "SHELF +1. LABEL IT LATER.",
    )
    const val COMMIT_NEEDS_SELECTION = "SET IN + OUT FIRST"
    const val PENCIL_STARTED = "PENCIL REWIND. OLD SCHOOL."
    const val PENCIL_DONE = "REWOUND. YOU'RE WELCOME."
    const val PENCIL_AT_TOP = "ALREADY AT THE TOP."
    const val ODOMETER_ON = "TAPE COUNTER. LIKE THE OLD DAYS."
    const val ODOMETER_OFF = "BACK TO REAL TIME."
    const val DELETE_SNIP = "EJECTED. THE BIN KEEPS IT 30 DAYS."

    // Chop shop.
    const val RECHOPPED = "RE-CHOPPED. THE MACHINE APOLOGIZES FOR SLICE 3."

    /** "N SLICES ON THE GRID. CHOKE GROUP SET." — the send-to-grid toast. */
    fun sentToGrid(sliceCount: Int, chokeSet: Boolean): String =
        "$sliceCount SLICES ON THE GRID." + if (chokeSet) " CHOKE GROUP SET." else ""

    // Export.
    const val EXPORT_DONE = "DUBBED. GO MAKE SOMETHING."
    const val DUB_DONE = "DUB DONE. SOUNDS 3% WARMER NOW."
    const val CARD_EJECTED = "CARD EJECTED. HAND IT TO THE MPC."

    // Kits.
    const val FRESH_TAPE = "FRESH TAPE. SMELLS LIKE FERRIC OXIDE."

    /** Shown when a kit could not be created, at every personality level. */
    const val CREATE_FAILED = "COULDN'T MAKE THAT TAPE."

    /** Status-bar deck mutterings, rotated slowly (FULL only). */
    val STATUS_QUIPS = listOf(
        "NO DOLBY. WE LIKE HISS.",
        "REWIND IS FREE.",
        "CHROME BIAS: ON",
        "HAND-WOUND SINCE 1996",
        "AZIMUTH: VIBES",
    )

    /** Rotation helper: line [n] of a rotating list (n counts from 0). */
    fun rotating(lines: List<String>, n: Int): String = lines[n % lines.size]

    // ---------- hidden eggs ----------

    /**
     * The Konami code on the pads, as 1-based pad slots:
     * up up down down left right left right — on a 4×4 grid where A13 is
     * the top row and A01 the bottom-left.
     */
    val KONAMI_PADS = listOf(13, 13, 5, 5, 2, 4, 2, 4)

    /** What the Konami code unlocks: the hidden SLIME scheme. */
    const val KONAMI_UNLOCK = "SLIME"

    const val ELITE_BPM = 133.7f
    const val ELITE = "ELITE."

    /** Naming a kit TEST earns exactly this. */
    fun kitNameResponse(name: String): String? =
        if (name.trim().uppercase() == "TEST") "VERY CREATIVE." else null

    /** BPM readout egg: `ELITE.` flashes at 133.7. */
    fun bpmResponse(bpm: Float): String? =
        if (bpm == ELITE_BPM) ELITE else null
}
