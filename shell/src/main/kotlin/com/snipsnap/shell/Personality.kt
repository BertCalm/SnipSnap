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
    /** A kit folder that won't parse (torn `kit.json`, missing file, etc.) — distinct from EMPTY_SHELF, which claims no kit exists at all. */
    const val KIT_WONT_OPEN = "THIS KIT WON'T OPEN. THE TAPE MAY BE CHEWED."

    // Capture.
    const val SESSION_ARMED = "TAPE ROLLING. GO STEAL A SOUND (LEGALLY)."
    const val SNIPPED = "SNIP! LAST 60s KEPT."
    const val BUBBLE_EJECTED = "EJECTED. TAPE IS KEPT."
    const val CAPTURE_BLOCKED =
        "TAPE JAM — SPOTIFY BLOCKS THE TAPE. USE THE SCREEN RECORDER, I'LL PULL THE AUDIO OUT."
    const val CAPTURE_BLOCKED_BUTTON = "FINE"
    // INSIDE: another app's audio, from inside it (M1's second source).
    const val INSIDE_ARMED = "TAPE ROLLING ON THE INSIDE. GO PLAY THE THING."
    /** The projection consent dialog was dismissed: nothing armed, nothing lost; ARM TAPE is still there. */
    const val INSIDE_REFUSED = "NO NOD, NO TAPE. NOTHING ARMED. ARM TAPE STILL WORKS."
    /** The platform ended the session — the lock screen or the status-bar stop chip, never us. */
    const val PHONE_STOPPED_TAPE = "THE PHONE STOPPED THE TAPE. LOCK SCREEN OR THE STOP CHIP. ARM AGAIN."
    // IMPORT: a file shared in from another app (F3).
    /** A shared file landed as a snip; [seconds] how much, [truncated] whether the cap cut its tail. */
    fun imported(seconds: Float, truncated: Boolean): String {
        val length = if (seconds >= 60f) "${Math.round(seconds / 60f)} MIN" else "${Math.round(seconds)}s"
        return if (truncated) "TAPED FROM OUTSIDE. FIRST $length KEPT - THE TAPE IS ONLY SO LONG." else "TAPED FROM OUTSIDE. $length ON THE DECK."
    }
    const val IMPORT_BUSY = "IMPORTING…"
    const val IMPORT_NO_TAPE = "TAPED FROM OUTSIDE. MAKE A FRESH TAPE AND IT'S ON THE DECK."
    const val IMPORT_NOT_AUDIO = "NOTHING TO HEAR IN THAT. SHARE AUDIO OR A VIDEO WITH SOUND."
    // The quick-settings tile.
    const val TILE_LABEL = "SNIPSNAP"
    const val TILE_IDLE = "TAP TO ARM"
    const val TILE_ARMED = "TAP TO SNIP"

    // Tape deck.
    /** COMMIT toasts, rotated in order per commit. */
    val COMMIT_LINES = listOf(
        "TAPED. NO TAKEBACKS.",
        "IT'S OURS NOW.",
        "CLEAN CUT. NICE EARS.",
        "SHELF +1. LABEL IT LATER.",
    )
    const val COMMIT_NEEDS_SELECTION = "SET IN + OUT FIRST"
    /** The deck glided onto an onset after a coast. */
    const val SNAPPED = "SNAPPED TO THE HIT. THE MACHINE HAS EARS."
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
    /** IN KEY: [n] tonal pads moved into [key] by their tune fields. */
    fun inKey(n: Int, key: String): String = "$n ${if (n == 1) "PAD" else "PADS"} RETUNED INTO $key. THE KICK IS UNTOUCHED."
    const val IN_KEY_NONE = "NOTHING MOVED. NO TONAL PAD HOLDS A NOTE THE TUNER IS SURE OF."
    const val IN_KEY_NEEDS_KEY = "SET A KEY FIRST."

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
    /** No archived takes yet — the TAKES card holds only the current (NOW) state. */
    const val TAKES_EMPTY = "NOTHING TO ROLL BACK TO YET."
    /** The bin's own empty state (the artboard's literal copy — `binEmpty` in `TapeOS Oilslick.dc.html`). */
    const val BIN_EMPTY_STATE = "THE BIN IS EMPTY. NOTHING TO REGRET."
    /** `restoreFromBin` returned null: the row was stale by the time BACK was tapped (already pulled, or purged). */
    const val BIN_ITEM_GONE = "ALREADY GONE. SOMEONE BEAT YOU TO IT."

    // ---- TAKES + BIN: the rule the screen states plainly (X2.3) ----
    const val TAKES_BIN_RULE =
        "EVERY SAVE ARCHIVES A TAKE. EVERY DELETE GOES TO THE BIN FIRST."

    // ---- GROOVE ----
    const val HUMANIZED = "HUMANIZED. NOBODY PLAYS LIKE A ROBOT."
    const val FORKED_TO_E = "FORKED TO PROG E. A–D STAY UNTOUCHED."
    const val BAR_WIPED = "BAR WIPED. THE MACHINE FORGIVES."

    /** The needle-roll only draws five lanes; a note on any other pad still plays and still exports — this says so. */
    fun offLane(n: Int): String = "+$n OFF-LANE — HEARD AND EXPORTED, NOT DRAWN"

    // ---- PAD SHEET ----
    const val GHOSTS_ON = "GHOST LAYERS ON. QUIET HITS GO SOFT, NOT JUST QUIETER."
    fun treated(segment: String, pad: String): String = "$segment ON $pad. ORIGINAL SLEEPS IN THE BIN."
    const val INSTRUMENT_MADE = "ONE NOTE IN, WHOLE KEYBOARD OUT. INSTRUMENT ON THE SHELF."
    const val NO_PITCH = "NO CONFIDENT PITCH. THE MACHINE REFUSES POLITELY."
    const val RETREAT_REFUSED = "GHOSTS CAME AFTER THE TREATMENT. CLEAR THEM FIRST."
    /** Row five and TUNE: [key] is the kit's key label, or what the treatment did without one ("THE NEAREST SEMITONES", "A, THE HIT'S OWN NOTE"). */
    fun keyed(segment: String, pad: String, key: String): String = "$segment ON $pad, IN $key. ORIGINAL SLEEPS IN THE BIN."
    /** The keyed family's honest refusal, [reason] in the treatment's own words ("a kick is a drum, not a note"). */
    fun notANote(reason: String): String = "NOT A NOTE: ${reason.uppercase().trimEnd('.')}."
    fun mutated(move: String, pad: String, parent: String): String = "$move: $pad × $parent. ONE HIT, TWO PARENTS."
    /** DE-SAMPLE: the pad is a patch now; [voice] the engine's own word, [distance] the honest number. */
    fun desampled(pad: String, voice: String, distance: Float): String =
        "$pad IS A ${voice.uppercase().replace('_', ' ')} PATCH NOW, %.2f AWAY. ORIGINAL SLEEPS IN THE BIN.".format(java.util.Locale.ROOT, distance)
    /** DE-SAMPLE's refusal: no patch near enough. */
    fun desampleFar(voice: String, distance: Float): String =
        "NO PATCH IS NEAR. THE CLOSEST IS A ${voice.uppercase().replace('_', ' ')}, %.2f AWAY.".format(java.util.Locale.ROOT, distance)
    /** DRIFT: the pad drifted toward what the crate dealt. */
    fun drifted(pad: String, toward: String): String = "$pad DRIFTED TOWARD $toward. ORIGINAL SLEEPS IN THE BIN."
    const val UNMUTATED = "PARENTS SEPARATED. THE ORIGINAL IS BACK FROM THE BIN."
    const val MUTATE_NEEDS_ONE = "GHOSTS ON. MUTATE WANTS ONE SAMPLE - CLEAR THEM FIRST."
    const val CRATE_EMPTY = "THE CRATE HAS NOTHING TO DEAL. ONLY YOU ON THE SHELF."

    // ---- KIT: textures ----
    const val SCULPTED = "SCULPTED. THE HIT IS WEATHER NOW. NEW TAPE ON THE SHELF."
    const val STRETCHED = "STRETCHED. A BLINK BECAME A LANDSCAPE. NEW TAPE ON THE SHELF."
    const val FROZEN = "FROZEN. ONE INSTANT, HELD. NEW TAPE ON THE SHELF."

    // ---- PAD SHEET: outside ----
    /** OUTSIDE: the pad went out the jack and came back; [lagMs] the trip, [confidence] how surely the return was found. */
    fun outside(move: String, pad: String, lagMs: Float, confidence: Float): String =
        "$move: $pad WENT OUT AND CAME BACK ${Math.round(lagMs)} MS LATER, ${Math.round(confidence * 100)}% SURE. ORIGINAL SLEEPS IN THE BIN."
    /** OUTSIDE's honest refusal, [reason] in the verb's own words ("the room said nothing back"). */
    fun outsideRefused(reason: String): String = "OUTSIDE REFUSED: ${reason.uppercase(java.util.Locale.ROOT).trimEnd('.')}."
    const val OUTSIDE_UNDONE = "BACK INSIDE. THE ORIGINAL IS BACK FROM THE BIN."
    const val OUTSIDE_NEEDS_MIC = "OUTSIDE NEEDS THE MIC. ARM THE TAPE ONCE ON KITS TO GRANT IT."
    const val OUTSIDE_TAPE_ROLLING = "THE TAPE IS ROLLING. EJECT IT FIRST - OUTSIDE WANTS THE MIC TO ITSELF."
    const val OUTSIDE_NEEDS_ONE = "GHOSTS ON. OUTSIDE WANTS ONE SAMPLE - CLEAR THEM FIRST."
    const val OUTSIDE_SENDING = "SENDING… TURN IT UP."
    const val OUTSIDE_LISTENING = "LISTENING FOR THE ROOM…"

    // ---- PAD SHEET: pad from anything ----
    const val PAD_MADE = "ONE HIT IN, A PAD FOREVER. INSTRUMENT ON THE SHELF."
    const val PAD_TOO_SHORT = "TOO SHORT TO STRETCH INTO A PAD. FEED IT MORE THAN A BLINK."
    const val PAD_TOO_LONG = "TOO LONG TO SLOW INSIDE A MINUTE. TRIM IT UNDER THIRTY SECONDS."

    // Chop shop.
    const val RECHOPPED = "RE-CHOPPED. THE MACHINE APOLOGIZES FOR SLICE 3."

    /** "N SLICES ON THE GRID. CHOKE GROUP SET." — the send-to-grid toast. */
    fun sentToGrid(sliceCount: Int, chokeSet: Boolean): String =
        "$sliceCount SLICES ON THE GRID." + if (chokeSet) " CHOKE GROUP SET." else ""
    /** INSTANT KIT: the one tap, then the same words SEND TO GRID says. */
    fun instantKit(sliceCount: Int, chokeSet: Boolean): String = "ONE TAP. " + sentToGrid(sliceCount, chokeSet)

    // ---- CHOP: the chip itself (HANDOFF.md — "chip tap = cycle class label, 'YOU ✓'") ----
    /** A chip under the confidence threshold, in its own words. */
    const val CHIP_NOT_SURE = "NOT SURE"

    /** The override marker on a corrected chip. */
    const val CHIP_OVERRIDDEN = "YOU ✓"

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
