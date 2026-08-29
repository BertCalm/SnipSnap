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

    // ---- CHOP: the melodic rule (X1.3) ----
    const val MELODIC_ON = "MELODIC. THE PADS BECOME A SCALE, LOW LEFT."

    // ---- KIT: the key cycler (F5.3) ----
    fun keySet(key: String): String =
        "$key SET. TONAL PADS RETUNE ON ASSIGN — THE KICK IS UNTOUCHED."
    const val KEY_OFF = "KEY OFF. EVERYTHING LANDS AS CAPTURED."

    // ---- Settings: teach the machine (X4.4) ----
    const val TEACHING_ON = "TEACHING ON. THE MACHINE LEARNS FROM YOUR CORRECTIONS."
    const val TEACHING_OFF = "TEACHING OFF. THE MACHINE STOPS TAKING NOTES."

    // ---- Settings: what TEACH THE MACHINE actually sends (X4.4) ----
    const val TEACH_CONSENT = "FEATURES ONLY, NEVER AUDIO. NOTHING LEAVES THE PHONE."

    // ---- BANK B: evil twins (W4.3) ----
    const val BANK_B_LIT = "BANK B LIT. YOUR KIT, BUT EVIL. RECIPES KEPT."
    const val TWINS_REROLLED = "TWINS REROLLED. SAME SEED, DIFFERENT SINS."

    // ---- TAKES + BIN (X2.3) ----
    fun takeRestored(take: String): String = "$take RESTORED. THE PAST, REPLAYED."
    const val BACK_FROM_BIN = "BACK FROM THE BIN. NO QUESTIONS ASKED."
    const val BIN_EMPTIED = "BIN EMPTIED. THE MACHINE FORGETS, AS ASKED."

    // ---- TAKES + BIN: the rule the screen states plainly (X2.3) ----
    const val TAKES_BIN_RULE =
        "EVERY SAVE ARCHIVES A TAKE. EVERY DELETE GOES TO THE BIN FIRST."

    // ---- GROOVE ----
    const val HUMANIZED = "HUMANIZED. NOBODY PLAYS LIKE A ROBOT."
    const val FORKED_TO_E = "FORKED TO PROG E. A–D STAY UNTOUCHED."
    const val BAR_WIPED = "BAR WIPED. THE MACHINE FORGIVES."

    // ---- PAD SHEET ----
    const val GHOSTS_ON = "GHOST LAYERS ON. QUIET HITS GO SOFT, NOT JUST QUIETER."
    fun treated(segment: String, pad: String): String = "$segment ON $pad. ORIGINAL SLEEPS IN THE BIN."
    const val INSTRUMENT_MADE = "ONE NOTE IN, WHOLE KEYBOARD OUT. INSTRUMENT ON THE SHELF."
    const val NO_PITCH = "NO CONFIDENT PITCH. THE MACHINE REFUSES POLITELY."

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
