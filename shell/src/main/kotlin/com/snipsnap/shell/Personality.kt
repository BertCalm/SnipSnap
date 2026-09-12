package com.snipsnap.shell

/**
 * Shipped copy. Everything the UI says lives here, stated as fact: what
 * happened, what to do next, what is true right now. The app's
 * personality lives in its visual design — the neon/tape aesthetic — not
 * in these words; voice in text only hides inaccuracy and competes with
 * what the screen already says visually. (This file used to also hold a
 * PERSONALITY slider and an `object Delight` gating toasts and quips by
 * it — removed once the copy itself no longer needed a tone knob, and
 * because the gate had a real bug: `Delight.toastsEnabled` folded OFF
 * into "no toasts at all," which meant a failure toast like DUB FAILED
 * silently never appeared for a user who'd turned personality off.)
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
    const val EMPTY_SHELF = "NOTHING TAPED YET."

    /**
     * The product loop, named once, on the only screen a new user is
     * guaranteed to see (September UAT, finding 3: capture -> trim -> grid
     * -> export is never stated anywhere in the app, and `BOOT_LINES` had
     * no caller outside HELP).
     *
     * **These are the menu tabs, not prose.** Learning the line teaches the
     * navigation, because the words are the tabs - a `ConventionTest` law
     * holds every stage here to a real `MenuItem` label, so a renamed tab
     * fails the build rather than leaving the app's one explanation of
     * itself pointing at a screen that no longer exists.
     *
     * A list rather than a written-out string for the same reason: the
     * separator is applied once, in [FIRST_RUN_LOOP], and the law reads the
     * stages without having to parse it back out.
     */
    val FIRST_RUN_LOOP_STAGES = listOf("TAPE", "CHOP", "KIT", "EXPORT")

    /** [FIRST_RUN_LOOP_STAGES] as the shelf draws it. Furniture: a row of tab names, not a sentence. */
    val FIRST_RUN_LOOP: String = FIRST_RUN_LOOP_STAGES.joinToString(" ▸ ")

    /**
     * What the four words mean, in verbs.
     *
     * The finding's complaint is that the *loop* is never stated - and four
     * tab names alone are a map, not an explanation. This is the sentence
     * that makes them one: it says what you do at each stop, in the order
     * [FIRST_RUN_LOOP] lists them.
     *
     * No door is named here on purpose. NEW KIT sits directly under this
     * panel as the screen's primary action, and TAPE is a tab on the row
     * above it - a line pointing at either would be a third copy of a
     * control already twice on screen.
     */
    const val FIRST_RUN_LOOP_NOTE = "RECORD IT, CUT IT, PLAY IT, DUB IT. FOUR TABS, IN ORDER."
    /**
     * The shelf's empty face during a SNIPS → PAD hand-off (`assigningSnip`
     * in `KitsScreen`/`App.kt`) when the shelf also has zero kits — distinct
     * from [EMPTY_SHELF], which would otherwise flatly claim nothing exists
     * right after the user just captured the very snip they're trying to
     * place. Names the real blocker (no kit to land on) and the real fix
     * (NEW KIT, right below this panel) rather than denying the snip exists.
     */
    const val EMPTY_SHELF_FOR_ASSIGN = "THIS SNIP NEEDS A KIT TO LAND ON. TAP NEW KIT BELOW TO MAKE ONE."
    const val EMPTY_KIT = "16 EMPTY PADS."
    /** GROOVE with a kit open and nothing in it yet: the three ways in, named. TAPE's [EMPTY_SHELF] sent people to the wrong screen. */
    const val EMPTY_GROOVE = "NOTHING HERE YET. PLAY A TAKE IN, TAP STEPS IN, OR PUT THE KIT ON RINGS."
    /** ORBIT from the menu row with no kit open. */
    const val NO_KIT_FOR_ORBIT = "ORBIT PUTS A KIT ON RINGS. OPEN ONE FROM KITS FIRST."
    /** A kit folder that won't parse (torn `kit.json`, missing file, etc.) — distinct from EMPTY_SHELF, which claims no kit exists at all. */
    const val KIT_WONT_OPEN = "THIS KIT WON'T OPEN. THE TAPE MAY BE CHEWED."
    /**
     * CHOP SHOP's own empty face when TAPE's last commit exists but nothing
     * readable came back — its source file is gone or won't decode, and the
     * open kit's fallback sample (if there even is a kit) didn't read
     * either. Distinct from EMPTY_SHELF, which claims there's nothing here
     * to chop at all: a user who taped something deserves to know the tape
     * is unreadable rather than be told they never made one.
     */
    const val CHOP_SOURCE_GONE = "THAT TAPE WON'T READ. IT MAY BE CHEWED."
    /** CHOP SHOP's empty face when the classic (non-melodic) layout itself fails — agrees with the toast `ChopScreen` fires alongside it, whose own fallback is this same "couldn't lay out the slices". */
    const val CHOP_LAYOUT_FAILED = "CHOP FAILED. COULDN'T LAY OUT THE SLICES."

    // Capture.
    const val SESSION_ARMED = "TAPE ROLLING."
    /**
     * SNIP's own toast — the arm-then-capture promise, stated honestly:
     * LISTEN is what starts the ring; this only ever holds what's been
     * heard since then, capped at 60s. Not "the last 60 seconds" as a
     * standing fact (that's false the instant LISTEN is pressed), but
     * exactly what was actually heard, up to that cap.
     */
    const val SNIPPED = "SNIP! KEPT WHAT IT'S HEARD SINCE LISTEN, UP TO 60s."
    /** The Bubble's drag-to-hot-zone stop — harmless, distinct from a delete or a reset. */
    const val BUBBLE_EJECTED = "STOPPED. TAPE IS KEPT."
    const val CAPTURE_BLOCKED =
        "TAPE JAM — SPOTIFY BLOCKS THE TAPE. USE THE SCREEN RECORDER; THE AUDIO PULLS OUT OF THAT."
    const val CAPTURE_BLOCKED_BUTTON = "FINE"
    /**
     * RECORD_AUDIO denied by the user (or the system, permanently) —
     * distinct from [CAPTURE_BLOCKED], which is the real DRM/dead-air
     * refusal. A denied permission isn't Spotify's fault and isn't fixed by
     * the screen recorder; it's fixed in the phone's own permission
     * settings, so this says that instead.
     */
    const val MIC_PERMISSION_DENIED =
        "SNIPSNAP NEEDS THE MIC TO LISTEN. TURN IT ON IN YOUR PHONE'S SETTINGS, THEN HIT LISTEN AGAIN."
    // INSIDE: another app's audio, from inside it (M1's second source).
    const val INSIDE_ARMED = "TAPE ROLLING ON THE INSIDE."
    /** The projection consent dialog was dismissed: nothing armed, nothing lost; LISTEN is still there. */
    const val INSIDE_REFUSED = "PROJECTION DECLINED. NOTHING ARMED. LISTEN STILL WORKS."
    /** The platform ended the session — the lock screen or the status-bar stop chip, never us. */
    const val PHONE_STOPPED_TAPE = "THE PHONE STOPPED THE TAPE. LOCK SCREEN OR THE STOP CHIP. PRESS LISTEN AGAIN."

    /**
     * The mic session died on its own — a dead `AudioRecord`, the OS
     * reclaiming the service, a revoked permission. Distinct from
     * [PHONE_STOPPED_TAPE], which names the lock screen and the stop chip
     * because a MediaProjection session genuinely only ends those two ways;
     * a plain mic session has no such culprit to name, and pointing at the
     * wrong one sends the user to check something that isn't the problem.
     *
     * What matters here is the part the app got wrong for a long time:
     * the tape had stopped and the UI still said it was rolling, so
     * whatever the user thought they were capturing was never being
     * captured. Say that it stopped, and say what gets it back.
     */
    const val TAPE_STOPPED_ITSELF = "THE TAPE STOPPED ON ITS OWN. NOTHING SINCE WAS KEPT. PRESS LISTEN AGAIN."

    /**
     * A MIC session has read nothing but exact digital silence for a long
     * while — the platform likely muted or revoked the mic mid-session.
     * Deliberately NOT [TAPE_STOPPED_ITSELF]'s language: that toast says
     * the tape stopped and nothing since was kept, which is only true if
     * the session actually ended — this one fires while the session is
     * still armed and the ring is still rolling (possibly on a genuinely
     * quiet room), so it asks the user to go check rather than claiming
     * the capture is already lost. A warning, not an obituary.
     */
    const val MIC_HEARING_NOTHING = "TAPE ROLLING, HEARING NOTHING. CHECK THE MIC ISN'T MUTED OR COVERED."
    // IMPORT: a file shared in from another app (F3).
    /** A shared file landed as a snip; [seconds] how much, [truncated] whether the cap cut its tail. */
    fun imported(seconds: Float, truncated: Boolean): String {
        val length = if (seconds >= 60f) "${Math.round(seconds / 60f)} MIN" else "${Math.round(seconds)}s"
        return if (truncated) "TAPED FROM OUTSIDE. FIRST $length KEPT - THE TAPE IS ONLY SO LONG." else "TAPED FROM OUTSIDE. $length ON THE DECK."
    }
    const val IMPORT_BUSY = "IMPORTING…"
    const val IMPORT_NOT_AUDIO = "NOTHING TO HEAR IN THAT. SHARE AUDIO OR A VIDEO WITH SOUND."
    // SHARE, BACKUP, and kits landing on the shelf (F6.3, X3.3, W3.3).
    const val PACKING_BUSY = "PACKING…"
    const val LANDING_BUSY = "UNPACKING…"
    /** READ BACK's own caveat under the completion-stage card: agreement with our reader is not a hardware guarantee. */
    const val READ_BACK_CAVEAT = "THE FILE AGREES WITH OUR OWN READER. HARDWARE IS THE ONLY PROOF IT OPENS."
    /** SHARE: the kit is one file now and the chooser is up. */
    fun kitPacked(kit: String): String = "$kit PACKED AS ONE FILE. PICK WHERE IT GOES."
    /** BACKUP: every kit on one file; [skipped] the ones preflight refused, named in the file's own report. */
    fun backedUp(packed: Int, skipped: Int): String =
        "$packed ${if (packed == 1) "KIT" else "KITS"} ON ONE FILE." + (if (skipped > 0) " $skipped SKIPPED." else "") + " PICK WHERE IT GOES."
    const val BACKUP_EMPTY = "NOTHING TO BACK UP. THE SHELF IS BARE."
    const val SHARE_NOWHERE = "NOWHERE TO SEND IT. NO APP ON THIS PHONE TAKES A FILE."
    /** A kit file landed: [landed] kits on the shelf, [skipped] refused - the box ([LandingNote]) names them when there are any. */
    fun landed(landed: Int, skipped: Int): String =
        "$landed ${if (landed == 1) "KIT" else "KITS"} LANDED ON THE SHELF." + if (skipped > 0) " $skipped SKIPPED." else ""
    /** The message box's title when a share landed nothing: the file's name and the refuser's words follow. */
    const val NOTHING_LANDED = "NOTHING LANDED."
    // The quick-settings tile: idle reads LISTEN (opens the app, arms
    // nothing yet), armed reads SNIP (writes what the ring has heard since
    // LISTEN, up to 60s, to disk).
    const val TILE_LABEL_IDLE = "LISTEN"
    const val TILE_LABEL_ARMED = "SNIP"
    const val TILE_SUBTITLE_IDLE = "OPENS SNIPSNAP"
    const val TILE_SUBTITLE_ARMED = "KEEPS UP TO 60s"

    // Tape deck.
    /** COMMIT toasts, rotated in order per commit. */
    val COMMIT_LINES = listOf(
        "TAPED. NO TAKEBACKS.",
        "COMMITTED TO TAPE.",
        "CLEAN CUT.",
        "ON THE SHELF. RENAME IT LATER.",
    )
    const val COMMIT_NEEDS_SELECTION = "SET IN + OUT FIRST"
    /** The deck glided onto an onset after a coast. */
    const val SNAPPED = "SNAPPED TO THE HIT."
    const val PENCIL_STARTED = "PENCIL REWIND. OLD SCHOOL."
    const val PENCIL_DONE = "REWOUND."
    const val PENCIL_AT_TOP = "ALREADY AT THE TOP."
    const val ODOMETER_ON = "TAPE COUNTER. LIKE THE OLD DAYS."
    const val ODOMETER_OFF = "BACK TO REAL TIME."
    /** PAD SHEET's own DELETE → BIN, on a pad — a real delete, distinct from EJECT (stop listening) or the export wizard's reset. */
    const val DELETE_SNIP = "DELETED. ${Reversal.BIN}"
    /**
     * A source file TAPE loaded (a kit pad or the last COMMIT's source —
     * the two ingest paths with no length cap of their own) ran past
     * TAPE's own load ceiling and got cut, same shape as [imported]'s own
     * truncation line and the same reason: the app already tells the
     * truth about a cut tape rather than staying quiet about it.
     */
    fun tapeTruncated(seconds: Float): String {
        val length = if (seconds >= 60f) "${Math.round(seconds / 60f)} MIN" else "${Math.round(seconds)}s"
        return "FIRST $length KEPT - THE TAPE IS ONLY SO LONG."
    }
    /**
     * TAPE's own load cap has a safety net (catching an [OutOfMemoryError]
     * the cap didn't manage to prevent) as well as the cap itself — this is
     * what that net says instead of the process dying silently.
     */
    const val TAPE_TOO_BIG = "THAT TAPE'S TOO BIG TO LOAD. TRY A SHORTER FILE."

    // ---- KIT: teaching the one gesture that opens PAD SHEET ----
    /**
     * Shown on opening a kit until the user has actually held a pad, and
     * never again after that. PAD SHEET is reachable ONLY by a long press on
     * a filled pad — no button, no menu entry — and it holds every
     * treatment, shape, tune and mutate control in the app.
     *
     * This used to stop after three showings, which meant three dismissals
     * while busy with something else cost the user that half of the app for
     * good (September UAT, finding 5). The cap is gone: only opening the
     * sheet retires the hint, because that is the only event that proves
     * they found it.
     *
     * It is no longer the only teacher either — [PAD_SHEET_LEGEND] sits
     * under KIT's grid permanently (finding 4), so this is a nudge with a
     * backstop rather than the single thread the feature hangs from.
     *
     * Says what to do and what it gets, in that order, and names the thing
     * it opens so the toast and the screen agree.
     */
    const val PAD_SHEET_HINT = "HOLD A PAD TO OPEN ITS PAD SHEET — SHAPE, TUNE, TREAT."

    /**
     * The permanent legend under KIT's grid.
     *
     * The September UAT's findings 4 and 5: the 480 ms hold was PAD SHEET's
     * only door, and [PAD_SHEET_HINT] — the toast that taught it — stopped
     * after three showings. Dismiss it three times while learning something
     * else and half the app's depth was gone for good.
     *
     * ROOMS already had the answer on its own list ("HOLD A ROOM TO FORGET
     * IT · THE BIN KEEPS 30 DAYS"): one line, always on screen, naming the
     * gesture and what it opens. A legend cannot be dismissed, so the
     * feature behind it cannot be lost.
     */
    const val PAD_SHEET_LEGEND = "HOLD A PAD · SHAPE, TUNE, TREAT, MUTATE, GRAIN"

    /**
     * The kit shelf's own legend (September UAT, finding 17). Every creation
     * door auto-names, so RENAME is the only place a user ever types a kit
     * name — and it sits behind a hold on the row that nothing on screen
     * mentions. Most samplers ask for a name once, up front; this does not
     * change that, it just stops the one door that exists from being a
     * secret.
     *
     * Wording follows the row's own `onLongClickLabel` ("RENAME OR DELETE")
     * so the sighted legend and the TalkBack announcement say the same
     * thing. No full stop: like ROOMS' legend and [PAD_SHEET_LEGEND], this is
     * furniture that stays on screen, not a line the app says once.
     */
    const val SHELF_LEGEND = "HOLD A KIT TO RENAME OR DELETE IT"

    // ---- SETUP's answers (September UAT, finding 23) ----

    /**
     * SETUP held three settings and answered none of the questions a user
     * actually arrives with. These are the three the app can answer from
     * what it already knows, rather than from settings invented to fill a
     * screen.
     *
     * Headings, not toasts: no full stops.
     */
    const val SETUP_FORMAT_HEADING = "EXPORT OPENS ON"
    const val SETUP_WHERE_HEADING = "WHERE YOUR FILES LIVE"
    const val SETUP_CARD_HEADING = "THE CARD"

    /**
     * Under the format readout: this is a memory of the last format picked,
     * not a preference set here, so it says where it is changed rather than
     * pretending to be a second picker.
     */
    const val SETUP_FORMAT_NOTE = "THE LAST ONE YOU PICKED. CHANGE IT ON EXPORT."

    /** No format has ever been picked, so EXPORT will open on its own first entry. */
    const val SETUP_FORMAT_NONE = "NOTHING PICKED YET"

    /** No card has been granted, or the grant was forgotten. */
    const val SETUP_CARD_NONE = "NO CARD PICKED. EXPORT ASKS FOR ONE."

    /**
     * The exports folder, said plainly. [where] is the real path, because a
     * user hunting for a file on a cable needs the actual thing to look for
     * and "your app's private storage" is not it.
     */
    fun setupWhere(where: String): String = where

    /** A card is held: [name] is what [cardName] made of its tree uri. */
    fun setupCardHeld(name: String): String = "HOLDING: ${name.uppercase(java.util.Locale.ROOT)}"

    /**
     * A readable name for a picked card, from its SAF tree uri's last path
     * segment — `primary:Music/Kits` becomes `Kits`, `1A2B-3C4D:` becomes the
     * fallback.
     *
     * String work rather than `DocumentFile.fromTreeUri`, which would mean a
     * dependency and a disk touch for a label. EXPORT's DESTINATION row was
     * already doing exactly this inline; SETUP needs the same answer, and two
     * screens naming one card two ways would be its own small lie.
     */
    fun cardName(lastPathSegment: String?): String =
        lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/').orEmpty().ifBlank { "CARD" }

    /**
     * The kit row's status chip (September UAT, finding 15). Three words for
     * the three states [DubStamp.Status] can honestly tell apart:
     *
     * - DRAFT — nothing was ever written out.
     * - DUBBED — written out, but not to the card in this phone right now.
     * - ON CARD — written to the card this phone is holding.
     *
     * DUBBED exists so the middle case is not forced to lie in either
     * direction. Calling it DRAFT would deny work the user did; calling it ON
     * CARD would promise a card that is not there.
     *
     * Chips, not toasts: no full stop, like every other label on the
     * furniture.
     */
    fun dubChip(status: DubStamp.Status): String = when (status) {
        DubStamp.Status.DRAFT -> "DRAFT"
        DubStamp.Status.DUBBED -> "DUBBED"
        DubStamp.Status.ON_CARD -> "ON CARD"
    }

    // ---- HELP: what the app is, said inside the app ----

    /**
     * HELP's body, kept here rather than in the Composable that draws it.
     *
     * The September UAT's finding 1: HELP was twenty hardcoded lines in
     * `StubScreen.kt` describing "the M0 skeleton" and promising that
     * capture "arrives with M1" — months after capture shipped. It was the
     * only in-app explanation of anything, and every line of it was false.
     *
     * It rotted because it lived where nothing could test it. Here it is
     * ordinary data in a module with tests, and `PersonalityTest` holds it
     * to the app as built: no milestone tags, and the four steps of the
     * loop named in order. The next person to move a feature has to walk
     * past a failing test to leave this stale.
     */
    const val HELP_LOOP_HEADER = "THE LOOP:"

    val HELP_LOOP = listOf(
        "· TAPE — CATCH A SOUND. THE MIC, ANOTHER APP, OR A FILE SHARED IN.",
        "· CHOP — CUT IT ON THE HITS. THE MACHINE GUESSES; ARGUE WITH IT.",
        "· KIT — 16 PADS. TAP TO HEAR, HOLD FOR THE PAD SHEET.",
        "· EXPORT — ONTO THE CARD, EIGHT WAYS. THE MPC IS ONE OF THEM.",
    )

    const val HELP_MORE_HEADER = "WORTH KNOWING:"

    val HELP_MORE = listOf(
        "· HOLD A PAD: SHAPE, TUNE, TREAT, MUTATE, LAYERS, TAKES, GRAIN.",
        "· HOLD A ROW ON THE SHELF TO RENAME IT, OR TO BIN IT.",
        "· THE BIN KEEPS WHAT YOU THREW OUT FOR ${Reversal.DAYS} DAYS.",
        "· PLAY IS THE ONE THAT FEELS LIKE DRUMS. SURFACE IS THE ONE THAT PRINTS.",
        "· ORBIT PUTS THE KIT ON RINGS: 5 AGAINST 4 IN ONE TAP.",
        "· EMPTY GROOVE? RECORD A TAKE, TAP STEPS IN, OR GO TO ORBIT.",
        "· HOLD A PAD, RE-TRIM ▸: TAPE OPENS ON ITS CUT. BACK ONTO LANDS IT.",
        "· TAP BANK B: A SECOND PAGE. HOLD A PAD, OR SEND A CHOP ONTO IT.",
        "· REMIX BANK B ▸ DEALS EVIL TWINS ONTO AN EMPTY BANK B.",
        "· BREED ▸ MIXES TWO KITS' RECIPES INTO A NEW KIT. PARENTS STAY.",
        "· KEYS PLAYS WHATEVER YOU MAKE AN INSTRUMENT FROM.",
        "· THE MENU ROW SCROLLS — SETUP AND HELP SIT OFF ITS RIGHT EDGE.",
    )

    /**
     * The TREATMENT card's header while a treatment is being applied.
     *
     * The September UAT's finding 14: ETERNAL measured 1.77 s on a desktop
     * JVM (SKIM 480 ms, DUB 247 ms, GHOST 162 ms) and a phone is several
     * times slower - but while `busy` was true the chips silently stopped
     * accepting taps with no label change, no dimming and no spinner. The
     * user taps ETERNAL, nothing happens, taps again, still nothing. An app
     * that is working should say so; this is the card saying it, and naming
     * which treatment, so the wait is attributable.
     *
     * A function rather than a constant, so it carries the segment's own
     * name - and so the reflective "every Copy constant shouts and stops"
     * law, which scans constants, does not need an exemption for a
     * progress indicator.
     */
    fun treatmentBusy(label: String): String = "TREATMENT · $label…"

    // ---- SYNTH: SEND TO PAD (a rendered patch lands on a pad) ----
    /**
     * SEND TO PAD's own landing toast: [name] the patch's own display name.
     * REPLACE bins the pad's old sample ([replaced] true, the same fact
     * [treated]'s "ORIGINAL SLEEPS IN THE BIN" states for a treatment); ADD
     * has no original to bin.
     */
    fun synthSent(pad: String, name: String, replaced: Boolean): String =
        if (replaced) {
            "PAD $pad REPLACED WITH ${name.uppercase(java.util.Locale.ROOT)}. ORIGINAL SLEEPS IN THE BIN."
        } else {
            "PAD $pad ADDED: ${name.uppercase(java.util.Locale.ROOT)}."
        }
    /** The chooser's own `IllegalArgumentException`/`IllegalStateException` when the kit changed under it - same "no pad on slot N" internal text `KitBuilder.assign`/`replaceAudio`/`update` throw that [PRINT_PAD_REFUSED] already keeps out of a toast, so this keeps it out here too rather than quoting it. */
    const val SYNTH_PAD_REFUSED = "THAT PAD WON'T TAKE THE PATCH. PICK ANOTHER."

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
    /** Named after the button that did it (REMIX BANK B ▸), so the toast, the button and HELP say one thing. */
    const val BANK_B_LIT = "BANK B REMIXED: EVERY PAD'S EVIL TWIN. RECIPES KEPT."
    const val TWINS_REROLLED = "BANK B REROLLED."
    /**
     * Flipping to an empty bank on the KIT screen. The row is always
     * drawn (a second page nobody can see is a page nobody finds) and an
     * empty bank flips like a full one, since its pads fill the same
     * three ways bank A's do — hold a pad to capture, the same hold to
     * place a snip SNIPS → PAD armed, a chop sent ONTO it; the toast
     * names them, plus the twins for bank B (REMIX BANK B fills B only,
     * so a sparse kit's empty C is not told to press it). A function:
     * the bank letter rides in, so the laws leave it be.
     */
    fun bankEmpty(bank: Char): String {
        val twins = if (bank == 'B') " REMIX BANK B ▸ DEALS TWINS HERE TOO." else ""
        return "BANK $bank: EMPTY. HOLD A PAD TO CAPTURE ONTO IT, OR TO PLACE A SNIP FROM SNIPS. A CHOP CAN LAND HERE.$twins"
    }
    /**
     * REMIX BANK B pressed while bank B holds pads the user put there: a
     * refusal, and it says so in the reversal law's terms — the pads stay
     * untouched; nothing here destroyed anything (`ReversalTest`).
     */
    const val TWINS_KEEP_OWN = "BANK B HOLDS YOUR OWN PADS. THEY STAY UNTOUCHED — REMIX ONLY DEALS ONTO AN EMPTY B. CLEAR THEM FIRST, OR KEEP THE PAGE."
    /**
     * CHOP's ONTO <kit> · BANK X: the slices landed on an existing kit's
     * empty bank, and how many did not fit when the chop was wider than
     * sixteen (a bank is a bank; the rest is one more chop away).
     */
    fun landedOnto(kitName: String, bank: Char, landed: Int, left: Int): String {
        val slices = "$landed ${if (landed == 1) "SLICE" else "SLICES"}"
        val tail = if (left > 0) " $left DIDN'T FIT — A BANK HOLDS 16." else ""
        return "'$kitName' BANK $bank: $slices LANDED.$tail"
    }

    // ---- BREED: two kits crossed into a child (XX2 wired in) ----
    /** BREED's busy line while `KitShelf.breed` renders every crossed pad offline — same DUBBING…/TWINNING… shape as EVIL TWINS. */
    const val BREEDING_BUSY = "BREEDING…"
    /**
     * The shelf's header while BREED is mid-pick (`App.kt`'s
     * `pendingBreedWith`) — names the kit the pick is FOR, the same way
     * `KitsScreen`'s SNIPS → PAD header names the snip it's placing. A
     * function, not a reflected field, so the shout/full-stop laws in
     * `PersonalityTest` don't apply to it (see that test's own KDoc on why
     * only fields are scanned) — which is exactly right here, since
     * [sourceName] is a user's own kit name and can't be forced to shout.
     */
    fun breedPickHeader(sourceName: String): String = "PICK A KIT TO CROSS WITH $sourceName"
    /** The hint line under [breedPickHeader] once the shelf actually has a second kit to tap. */
    const val BREED_PICK_HINT = "TAP A KIT TO CROSS WITH THIS ONE."
    /** Refused: tapping the very kit BREED was pressed from during the pick — breeding needs two different kits. */
    const val BREED_SAME_KIT = "THAT'S THE KIT YOU'RE BREEDING FROM — PICK A DIFFERENT ONE."
    /**
     * Refused at the pick: `Breed.crossable` found no slot the coin could
     * work on, so the child would be a copy of A with "0 PADS CROSSED".
     * Said before the copy is made, in the precondition's own terms: a
     * pad here needs its own patch or treatment (rack), or a partner
     * there (same slot, else same class) whose treatment it can borrow —
     * a partner's patch alone is not enough, since a captured pad has no
     * engine to render it through. The fix is the same in every case.
     */
    const val BREED_NOTHING_TO_CROSS = "NOTHING WOULD CROSS: NO PAD HERE HAS A PATCH OR TREATMENT, OR A PARTNER THERE WITH ONE TO BORROW. TREAT A PAD FIRST."
    /** The line under the BREED button: what comes out, before the tap. */
    const val BREED_SUBTITLE = "MIXES THE TWO KITS' RECIPES INTO A NEW KIT. BOTH PARENTS STAY."
    /**
     * The BREED button's own readout: how many of this kit's pads carry
     * a recipe the cross can use (`Breed.recipePads`), so "0 PADS
     * CROSSED" is never the first time the user hears the word. A
     * function, so the singular reads right and the laws leave it be.
     */
    fun breedButton(recipePads: Int, pads: Int): String = when {
        recipePads == 0 -> "BREED ▸ NO RECIPES HERE YET"
        pads == 1 -> "BREED ▸ ITS ONE PAD HAS A RECIPE"
        recipePads == 1 -> "BREED ▸ 1 OF $pads PADS HAS A RECIPE"
        else -> "BREED ▸ $recipePads OF $pads PADS HAVE RECIPES"
    }
    /**
     * BREED's own toast: the child kit's name, how many pads actually
     * crossed, and — only when the audit sent one back (`Breed.Report.audited`
     * is non-empty) — how many, so "0 PADS CROSSED" reads as the audit
     * doing its job rather than as breeding silently failing. `Breed.breed`'s
     * own contract — both parents come out untouched — is worth stating
     * since a first-time breeder has no reason to assume it.
     */
    fun bred(childName: String, crossed: Int, audited: Int = 0): String {
        val auditNote = if (audited > 0) {
            " ($audited SENT BACK BY THE AUDIT — THE CROSS CHANGED CLASS)"
        } else {
            ""
        }
        return "'$childName' BRED. $crossed ${if (crossed == 1) "PAD" else "PADS"} CROSSED$auditNote, BOTH PARENTS UNTOUCHED."
    }

    // ---- TAKES + BIN (X2.3) ----
    fun takeRestored(take: String): String = "$take RESTORED."
    const val BACK_FROM_BIN = "BACK FROM THE BIN."
    const val BIN_EMPTIED = "BIN EMPTIED."
    /** No archived takes yet — the TAKES card holds only the current (NOW) state. */
    const val TAKES_EMPTY = "NOTHING TO ROLL BACK TO YET."
    /** The bin's own empty state (the artboard's literal copy — `binEmpty` in `TapeOS Oilslick.dc.html`). */
    const val BIN_EMPTY_STATE = "THE BIN IS EMPTY."
    /** `restoreFromBin` returned null: the row was stale by the time BACK was tapped (already pulled, or purged). */
    const val BIN_ITEM_GONE = "ALREADY GONE."

    // ---- TAKES + BIN: the rule the screen states plainly (X2.3) ----
    const val TAKES_BIN_RULE =
        "EVERY SAVE ARCHIVES A TAKE. EVERY DELETE GOES TO THE BIN FIRST."

    // ---- SINCE T3: the TAKES card's expander (Spec Sheet II #03) ----
    /** The expander's one honest limit: `KitDiff` reads `kit.json`, and a rewrite that touched no recipe leaves no mark there. */
    const val TAKES_DIFF_CAVEAT = "SETTINGS AND RECIPES ONLY. AUDIO REDRAWN UNDER THE SAME NAME LEAVES NO MARK HERE."
    /** The take's path changed hands between `takes()` listing it and the diff reading it (a rotation racing the refresh) — the row stays, RESTORE's own identity check decides. */
    const val TAKES_DIFF_UNREADABLE = "THIS TAKE WON'T READ. NOTHING TO COMPARE."

    // ---- GROOVE ----
    // HUMANIZE ⚄ (and Copy.HUMANIZED, its toast) is gone — the feel axis
    // (wave, task 6) replaced it with a control that actually changes the
    // clip continuously instead of jittering PROG A once and forcing a jump
    // to it. See feelRolled/FEEL_RECENTRED below for its replacements.
    // The phone reads (wave ZZ): READ AS GROOVE, DIG, STEAL THE FEEL on TAPE.
    const val READ_GROOVE_BUSY = "LISTENING…"
    const val READ_GROOVE_NEEDS_KIT = "OPEN A KIT FIRST. THE EAR NEEDS PADS TO PLAY ON."
    /** The reading landed: how much was heard, and where it plays. */
    fun grooveRead(hits: Int, bars: Int, bpm: Int): String {
        val barWord = if (bars == 1) "BAR" else "BARS"
        return "HEARD $hits HITS OVER $bars $barWord AT ~$bpm BPM. THEY PLAY ON YOUR PADS NOW."
    }
    /** The Ear's refusal, [reason] in its own words ("no confident tempo - the ear needs a grid"). */
    fun grooveRefused(reason: String): String = "NO GROOVE: ${reason.uppercase(java.util.Locale.ROOT).trimEnd('.')}."
    const val DIG_BUSY = "DIGGING…"
    /** The break found, IN and OUT set to it. */
    fun dug(from: String, to: String): String = "BREAK FOUND AT $from-$to. IN AND OUT ARE SET. INSTANT KIT IS ONE TAP AWAY."
    const val NO_BREAK = "NO BREAK HEARD IN THAT. DIG BY HAND WITH IN AND OUT."
    const val FEEL_BUSY = "STEALING THE FEEL…"
    /** The feel poured over the kit's pattern as PROG E; [covered] of 16 positions the record actually played. */
    fun feelStolen(covered: Int): String = "FEEL STOLEN: $covered OF 16 POSITIONS. IT'S ON PROG E. A–D STAY UNTOUCHED."
    /** STEAL THE FEEL's refusal, [reason] in its own words. */
    fun feelRefused(reason: String): String = "NO FEEL: ${reason.uppercase(java.util.Locale.ROOT).trimEnd('.')}."
    const val FORKED_TO_E = "FORKED TO PROG E. A–D STAY UNTOUCHED."
    /** The post-take FORK TO E row's confirmed-replace branch (live-record plan Task 6 bug fix): an E already existed and the user tapped "REPLACE E?" a second time — says the old steps are gone, never claims a plain "forked" like [FORKED_TO_E] does for a from-nothing fork. */
    const val FORKED_TO_E_REPLACED = "PROG E REPLACED WITH THIS TAKE. THE OLD STEPS ARE GONE."
    /**
     * The step editor's WIPE BAR, said honestly.
     *
     * It read "THE MACHINE FORGIVES" until the September undo-labelling
     * pass, which is an offer of forgiveness the machine does not make:
     * `clearEditorBar` bumps `editorSaveTick`, the autosave writes the
     * wiped bar to the sidecar, and no control on that screen steps it
     * back — UNDO TAKE is about recorded takes, not editor edits. A user
     * who trusted the old line lost steps they believed were recoverable.
     * So this names the recourse that actually exists: tap them in again.
     */
    val BAR_WIPED: String = Reversal.goneBut("TAP THE STEPS BACK IN")
    /** RECORD tapped before `PadEngine.load` has committed the bank (`clickSampleIndex == -1` until then, so the count-in clicks would be silent and give no feedback at all) — told instead of armed. */
    const val KIT_STILL_LOADING = "KIT'S STILL LOADING. GIVE IT A SECOND."

    /** ⚄ RESEED: a new rolled feel. Names the seed so a roll worth keeping can be found again by hand, until `.pocket` files make it saveable. */
    fun feelRolled(seed: Int): String = "FEEL #$seed ROLLED."

    /** Tapping the FEEL readout returns the axis to the performance itself. */
    const val FEEL_RECENTRED = "BACK TO AS PLAYED."

    // ---- GROOVE: RECORD landing and UNDO TAKE (live-record plan, Task 5) ----
    /** RECORD landed a take as the new PROG A; echoes [grooveRead]'s shape, but names what was PLAYED, not what was heard. */
    fun takeLanded(notes: Int, bars: Int): String {
        val barWord = if (bars == 1) "BAR" else "BARS"
        return "TOOK $notes HITS OVER $bars $barWord. PLAYING ON PROG A NOW."
    }
    /** UNDO TAKE's existing-base branch: whatever was captured before this take plays again. */
    const val TAKE_UNDONE = "TAKE UNDONE. BACK TO WHAT WAS THERE BEFORE."
    /** UNDO TAKE's from-scratch-with-nothing branch: said honestly as a delete, never as a "restore" to a base that never existed. */
    const val TAKE_UNDONE_EMPTY = "TAKE UNDONE. NO GROOVE LEFT - SAME AS BEFORE RECORD."
    /** UNDO TAKE's from-scratch-with-E branch: the take is gone, PROG E rides through untouched. */
    const val TAKE_UNDONE_TO_E = "TAKE UNDONE. PROG E RIDES THROUGH, UNTOUCHED."
    /** STOP RECORDING with nothing captured — armed, counted in, played nothing, tapped STOP. Previously a bare no-op: no toast, no message at all (live-record follow-ups, Fix 4). See `GrooveScreen.kt`'s `stopRecording` for the guard this backs. */
    const val TAKE_SILENT = "NOTHING PLAYED — NO TAKE LANDED."

    /** A note on any pad outside the five named drum lanes still plays and still exports — and, since GrooveScreen.kt's `NeedleRoll` Fix 3, still draws too, in its own sixth OTHER column rather than on one of the five named lanes. This says so; it must NOT claim "not drawn" again — see that fix's own KDoc for why that used to be true and now isn't. */
    fun offLane(n: Int): String = "+$n OFF-LANE — HEARD, EXPORTED, DRAWN UNDER OTHER"

    // ---- GROOVE: CHART ▸ (wave XXX) — the program on screen as a text drum chart ----
    const val CHART_BUSY = "CHARTING…"
    const val CHART_NEEDS_GROOVE = "NO GROOVE TO CHART. CHOP WITH A GROOVE, RECORD ONE, OR STEAL ONE FIRST."
    /** The chart is written and the chooser is up; [offGrid] hits were drawn in their nearest cell and footnoted, never moved. */
    fun chartWritten(notes: Int, offGrid: Int): String {
        val noteWord = if (notes == 1) "NOTE" else "NOTES"
        val grid = when (offGrid) {
            0 -> "ALL ON THE GRID"
            1 -> "1 OFF THE GRID - DRAWN, NOT MOVED"
            else -> "$offGrid OFF THE GRID - DRAWN, NOT MOVED"
        }
        return "CHART WRITTEN: $notes $noteWord, $grid. PICK WHERE IT GOES."
    }
    /** The chart is written but no app took it; [where] is the path under the app's own files, so it isn't lost. */
    fun chartKept(where: String): String = "CHART KEPT AT $where. $SHARE_NOWHERE"

    // ---- ARRANGE ----
    const val ARRANGE_NEEDS_GROOVE = "NO GROOVE TO ARRANGE. CHOP WITH A GROOVE, OR STEAL ONE, FIRST."
    const val ARRANGE_MIXING = "MIXING…"
    const val ARRANGE_REROLLED = "REROLLED. SAME STRUCTURE, A FRESH TAKE ON THE VARIATION."

    // ---- RE-TRIM: a pad goes back to its own tape (docs/RETRIM.md) ----
    /** RE-TRIM ▸ on a pad with no tape name at all: GRAB/HOLD off the mic ring, an import, a synth. */
    const val RETRIM_NO_TAPE = "THIS PAD CAME OFF THE MIC (OR AN IMPORT). NO TAPE TO GO BACK TO."
    /** RE-TRIM ▸ on a pad CHOP landed before the tape keys existed: honest about which fix works. */
    const val RETRIM_OLD_CHOP = "THIS PAD WAS CHOPPED BEFORE TAPES WERE REMEMBERED. RE-CHOP TO FIX THAT."
    /** RE-TRIM ▸ when the named tape isn't on this phone's SNIPS shelf any more. */
    const val RETRIM_TAPE_GONE = "THAT TAPE'S GONE. THE PAD KEEPS WHAT IT HAS."
    /** RE-TRIM ▸ on a pad with GHOSTS or STACK THE TAKES layers: they were rendered from, or are, the old file. */
    const val RETRIM_LAYERED = "CLEAR GHOSTS (OR THE STACK) FIRST. LAYERS RIDE ON THE OLD FILE."
    /** RE-TRIM ▸ on a round-robin chain: its boundaries index the old file. */
    const val RETRIM_CHAINED = "THIS PAD IS A ROUND-ROBIN CHAIN. UNDO THE ROBIN FIRST."
    /** The cut lies beyond TAPE's load cap; the deck opens at the top instead. */
    const val RETRIM_PAST_CAP = "THAT CUT SITS PAST WHAT TAPE CAN HOLD."
    /** TAPE's header while a RE-TRIM is live: which pad, which tape. */
    fun retrimHeader(pad: String, tape: String): String = "RE-TRIM $pad · $tape"
    /** The deck's primary button while a RE-TRIM is live — it replaces KEEP. */
    fun backOnto(pad: String): String = "BACK ONTO $pad"
    /** The busy line while BACK ONTO reads the cut and writes the pad. */
    const val RETRIM_BUSY = "RE-CUTTING…"
    /** The HITS stepper's readout while the hits are still being found. */
    const val HITS_BUSY = "HITS…"
    /** Stepping the HITS stepper on a tape with no hit in it. */
    const val HITS_NONE = "NO HITS ON THIS TAPE. DRAG IN AND OUT INSTEAD."
    /** The HITS stepper's readout: which of the tape's hits the selection sits on, none, or a tape with no hits at all. */
    fun hitReadout(index: Int, count: Int): String = when {
        count == 0 -> "NO HITS"
        index < 0 -> "HIT -/$count"
        else -> "HIT ${index + 1}/$count"
    }
    /**
     * BACK ONTO landed. [treatment] is the old pad's treatment name when it
     * had one — a treatment is baked into the file, so it stays with the
     * old file in the bin rather than being silently re-applied to the cut.
     */
    fun retrimLanded(pad: String, treatment: String?): String =
        "$pad RE-CUT." + (treatment?.let { " THE ${it.uppercase()} STAYED WITH THE OLD ONE - IT'S IN THE BIN." } ?: "")

    // ---- PAD SHEET ----
    const val GHOSTS_ON ="GHOST LAYERS ON. QUIET HITS GO SOFT, NOT JUST QUIETER."
    fun treated(segment: String, pad: String): String = "$segment ON $pad. ORIGINAL SLEEPS IN THE BIN."
    /**
     * The same landing when `PadSheet.unTreatState` was NOT_BINNED: the
     * pad carried a treatment with no original in the bin behind it (a
     * bank-B twin, a CLI treat, a bin since emptied), so the new one went
     * on top of the old rather than in its place. Said, since the card
     * lights one segment while the sound carries two; the fix is named.
     */
    fun treatedStacked(segment: String, pad: String): String =
        "$segment ON $pad, OVER THE LAST ONE — NO ORIGINAL IN THE BIN TO SWAP FROM. VERSIONS ▸ ROLLS BACK."
    /** SMEAR at AMT 0 on a pad that isn't smeared: `smearPad` writes nothing, so nothing landed. */
    const val SMEAR_ZERO = "AMT 0: NOTHING TO SMEAR. THE PAD STAYS AS IT IS."
    const val INSTRUMENT_MADE = "INSTRUMENT MADE. ON THE SHELF."
    const val NO_PITCH = "NO CONFIDENT PITCH."
    const val RETREAT_REFUSED = "GHOSTS CAME AFTER THE TREATMENT. CLEAR THEM FIRST."
    /** NONE, when it lands: the pad's earlier take is back out of the bin and the recipe is off. */
    fun unTreated(pad: String): String = "$pad IS ITSELF AGAIN. THE BIN GAVE THE ORIGINAL BACK."
    /** NONE, when the bin cannot help: a twin's copied recipe, a treatment performed elsewhere, or a bin since emptied. */
    const val UNTREAT_NOT_BINNED = "THE BIN HOLDS NO EARLIER TAKE OF THIS PAD. THE TREATMENT STAYS."
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
    /** A FILE: the picked file cannot be a parent; [reason] the decoder's or the holder's own words. */
    fun fileRefused(reason: String): String = "NOT A PARENT: ${reason.uppercase(java.util.Locale.ROOT).trimEnd('.')}."

    // ---- TAPE SPLICE: two takes of one pad, joined at one chosen frame ----
    const val SPLICE_NEEDS_HISTORY =
        "SPLICE WANTS AT LEAST ONE PRIOR TAKE. RE-TRIM OR TREAT THIS PAD FIRST - ITS OLD AUDIO WAITS IN THE BIN."
    const val SPLICE_KIT_GONE = "THIS PAD ISN'T THERE ANY MORE."
    /** The slot was reassigned while SPLICE was open: the takes belong to a pad that no longer lives here, so nothing is written. */
    const val SPLICE_PAD_CHANGED = "THIS SLOT HOLDS A DIFFERENT PAD NOW. NOTHING WAS TOUCHED."
    const val SPLICE_TAKE_UNREADABLE = "ONE OF THOSE TAKES WON'T READ ANY MORE. PICK ANOTHER PAIR."
    /** `TapeSplice.join`'s own refusal, said before the needle ever shows: a mutated (stereo) take against its mono original, or two rates. */
    const val SPLICE_FORMATS_DIFFER =
        "THOSE TWO TAKES DON'T MATCH - SAMPLE RATE OR CHANNELS. SPLICE WON'T RESAMPLE OR FOLD ONE TO FIT. PICK ANOTHER PAIR."
    // ---- DO IT AGAIN: COPY LAST TREATMENT off one pad, PASTE it on another ----
    const val REPLAY_NOTHING = "THIS PAD CARRIES NO RECIPE. NOTHING TO COPY."
    const val REPLAY_CLIPBOARD_EMPTY = "NOTHING COPIED YET. COPY LAST TREATMENT OFF A PAD FIRST."
    /** The honest limit, under the buttons: the recipe is the last step, never the stack. */
    const val REPLAY_LAST_ONLY = "COPIES THE LAST TREATMENT ONLY. A CRUSHED-THEN-WASHED PAD COPIES AS WASHED."
    /** MUTATE's recipe names its parents by label; the bytes never rode along. */
    fun replayNeedsParent(move: String, parents: List<String>): String =
        "MUTATE ($move WITH ${parents.joinToString(" + ").ifEmpty { "?" }}) NEEDS ITS PARENT - NOT CARRIED."
    const val REPLAY_SPLICE = "SPLICE NAMES NO TAKES - NOT REPLAYABLE."
    const val REPLAY_OUTSIDE = "OUTSIDE WAS A ROOM, NOT A SETTING - NOT REPLAYABLE."
    /** CLEAN, THE DOCTOR and SCULPT are readings of that exact sound, not settings for another. */
    fun replayMeasured(what: String): String = "$what WAS A MEASUREMENT OF THAT SOUND, NOT A SETTING - NOT REPLAYABLE."
    const val REPLAY_NO_DOOR = "THIS RECIPE HAS NO DOOR HERE."
    fun copied(word: String, from: String): String = "$word COPIED FROM $from. PASTE IT ON ANY PAD."
    fun replayed(word: String, pad: String): String = "$word DONE AGAIN ON $pad. ORIGINAL SLEEPS IN THE BIN."
    /** The keyed family reads the DESTINATION kit's key; [sourceKey] is named when it differs from what played. */
    fun replayedInKey(word: String, pad: String, key: String, sourceKey: String?): String =
        "$word DONE AGAIN ON $pad, IN $key" +
            (if (sourceKey != null) " - THE SOURCE WAS $sourceKey" else "") +
            ". ORIGINAL SLEEPS IN THE BIN."
    /** A patch recipe replaces the sound outright, and the toast says so. */
    fun replayedPatch(pad: String): String = "$pad IS THAT PATCH NOW. ITS OWN SOUND SLEEPS IN THE BIN."
    fun replayedRobin(takes: Int, pad: String): String = "ROUND ROBIN ×$takes DEALT AGAIN ON $pad - SAME RECIPE, NEW DEAL."

    // ---- STACK THE TAKES: a pad's real prior takes as its velocity zones ----
    const val STACK_NEEDS_HISTORY =
        "STACK WANTS AT LEAST ONE PRIOR TAKE. RE-TRIM OR TREAT THIS PAD FIRST - ITS OLD AUDIO WAITS IN THE BIN."
    /** The pad grew layers (GHOSTS, or another STACK) while this screen was open, or was already layered when it opened. */
    const val STACK_ALREADY_LAYERED = "THIS PAD IS LAYERED ALREADY. CLEAR SOFT HITS FIRST, THEN STACK."
    const val STACK_MAX_THREE = "THREE SOFT ZONES IS THE STACK. THE MPC 2 HAS FOUR LAYER SLOTS AND LIVE TAKES ONE."
    const val STACK_TAKE_UNREADABLE = "THAT TAKE WON'T READ ANY MORE. IT STAYS OUT OF THE STACK."
    /** The screen's standing caveat: layers lock every single-sample door until cleared, and clearing deletes the copies. */
    const val STACK_LOCKS =
        "A STACKED PAD IS LAYERED. TREAT, MUTATE, SPLICE AND OUTSIDE WAIT UNTIL SOFT HITS IS CLEARED - AND CLEARING DELETES THE COPIES."
    /** Shown, not fixed: no auto-gain, ever. */
    const val STACK_NO_GAIN = "LEVELS ARE SHOWN, NOT FIXED. NO AUTO-GAIN - THE MPC'S VELOCITY CURVE OWNS LOUDNESS."
    /** COMMIT: how many real takes now sit under LIVE, and what that costs. */
    fun stacked(soft: Int): String =
        "$soft REAL ${if (soft == 1) "TAKE" else "TAKES"} STACKED UNDER LIVE. THIS PAD IS LAYERED NOW - CLEAR SOFT HITS TO TREAT IT AGAIN."
    /** A soft-zone take that peaks over the live one, said beside its row and left exactly that loud. */
    fun stackOverLive(zone: String, db: Float): String =
        "$zone IS +${String.format(java.util.Locale.ROOT, "%.1f", db)} DB OVER LIVE. THE MPC'S VELOCITY CURVE WILL NOT HIDE THIS."

    /** COMMIT: [crossfaded] is honest about whether the raw cut needed a declick overlap. */
    fun spliced(crossfaded: Boolean): String =
        if (crossfaded) {
            "SPLICED. THE RAW SEAM WOULD HAVE CLICKED, SO A FEW MS OF TAPE OVERLAP WENT IN."
        } else {
            "SPLICED CLEAN. NO OVERLAP NEEDED."
        }

    // ---- KIT: textures ----
    const val SCULPTED = "SCULPTED. NEW TAPE ON THE SHELF."
    const val STRETCHED = "STRETCHED. NEW TAPE ON THE SHELF."
    const val FROZEN = "FROZEN. NEW TAPE ON THE SHELF."

    // ---- PAD SHEET: outside ----
    /** OUTSIDE: the pad went out the jack and came back; [lagMs] the trip, [confidence] how surely the return was found. */
    fun outside(move: String, pad: String, lagMs: Float, confidence: Float): String =
        "$move: $pad WENT OUT AND CAME BACK ${Math.round(lagMs)} MS LATER, ${Math.round(confidence * 100)}% SURE. ORIGINAL SLEEPS IN THE BIN."
    /** OUTSIDE's honest refusal, [reason] in the verb's own words ("the room said nothing back"). */
    fun outsideRefused(reason: String): String = "OUTSIDE REFUSED: ${reason.uppercase(java.util.Locale.ROOT).trimEnd('.')}."
    const val OUTSIDE_UNDONE = "BACK INSIDE. THE ORIGINAL IS BACK FROM THE BIN."
    const val OUTSIDE_NEEDS_MIC = "OUTSIDE NEEDS THE MIC. PRESS LISTEN ONCE ON KITS TO GRANT IT."
    const val OUTSIDE_TAPE_ROLLING = "THE TAPE IS ROLLING. STOP LISTENING FIRST - OUTSIDE WANTS THE MIC TO ITSELF."
    const val OUTSIDE_NEEDS_ONE = "GHOSTS ON. OUTSIDE WANTS ONE SAMPLE - CLEAR THEM FIRST."
    const val OUTSIDE_SENDING = "SENDING… TURN IT UP."
    const val OUTSIDE_LISTENING = "LISTENING FOR THE ROOM…"
    /** KEEP ROOM: the measured room is on the shelf under [name], for any pad through MUTATE ▸ ROOM. */
    fun roomKept(name: String): String = "$name IS ON THE SHELF. ANY PAD CAN PLAY IN IT - MUTATE ▸ ROOM."
    const val ROOM_NONE_TO_KEEP = "NO ROOM MEASURED YET. SEND A SWEEP OUT FIRST - ROOM ▸ SEND."
    /** FORGET → BIN on the shelf: the room sleeps in the bin, like every delete. */
    fun roomForgotten(name: String): String = "$name IS IN THE BIN. ${Reversal.MIND}"
    /** RESTORE on a binned room: back on the shelf under [name]. */
    fun roomRestored(name: String): String = "$name IS BACK ON THE SHELF."
    const val ROOM_FORGET_BUSY = "FORGETTING…"
    const val ROOM_RESTORE_BUSY = "RESTORING…"
    const val ROOM_BIN_EMPTY_BUSY = "EMPTYING…"
    /** EMPTY THE BIN NOW on the rooms bin, confirmed — `kitBinEmptied`/`snipBinEmptied`'s own no-count reasoning applies here too: the bin may hold a stray file the list never showed. */
    val roomBinEmptied: String = "THE BIN IS EMPTY. GONE FOR GOOD."
    /** SHARE on a room row: the room is one `.snip-room` file now and the chooser is up. */
    fun roomPacked(name: String): String = "$name PACKED AS ONE FILE. PICK WHERE IT GOES."
    /** A `.snip-room` landed through the share door: [name] on the shelf, ready for MUTATE ▸ ROOM. */
    fun roomLanded(name: String): String = "$name LANDED ON THE SHELF. ANY PAD CAN PLAY IN IT - MUTATE ▸ ROOM."

    // ---- KIT: delete (restorable from DELETED KITS for 30 days) + rename from the shelf (Task 4) ----
    /**
     * DELETE on a shelf kit. Now matches ROOMS's own FORGET → BIN (which
     * `unforget`/`BinnedRoomRow`'s RESTORE surfaces on the shelf) and TAKES +
     * BIN (its own visible RESTORE / "EMPTY THE BIN NOW"): a deleted kit is
     * DELETED KITS's own listing now too, with the same RESTORE and early
     * "EMPTY THE BIN NOW" — so this keeps the softer promise instead of the
     * old "gone for good" wording, which was only ever true because that
     * screen didn't exist yet.
     */
    fun kitDeleted(name: String): String = "$name IS OFF THE SHELF. ${Reversal.MIND}"
    const val KIT_DELETE_BUSY = "DELETING…"
    const val KIT_DELETE_FAILED = "DELETE FAILED. THE KIT MAY ALREADY BE GONE."
    /** RENAME on a shelf kit; [name] is what it actually landed under — a collision may have freshened it. */
    fun kitRenamed(name: String): String = "RENAMED TO $name."
    const val KIT_RENAME_BUSY = "RENAMING…"
    const val KIT_RENAME_FAILED = "COULDN'T RENAME - CHECK THE NAME AND TRY AGAIN."

    // ---- DELETED KITS (Task 2 of the bin-restore plan): restore or empty early ----
    /** RESTORE on a binned kit; [name] is what it actually landed under — `KitShelf.restoreKit`'s own collision fallback may have freshened it, never the name that was tapped. */
    fun kitRestored(name: String): String = "$name IS BACK ON THE SHELF."
    /**
     * EMPTY THE BIN NOW on DELETED KITS, confirmed. Deliberately carries no
     * count: `KitShelf.emptyKitBin` reports every child it removed, but the
     * list only ever showed the ones whose `kit.json` could be read, so a
     * bin holding a stray file would have toasted more KITS than the user
     * ever saw — reading as unexplained data loss. They just looked at the
     * list; what they need told is that it's empty now, and that it's final.
     */
    val kitBinEmptied: String = "THE BIN IS EMPTY. GONE FOR GOOD."

    // ---- SNIPS: rename, delete → bin (name-and-find task) ----
    /** RENAME on a SNIPS row; [name] is what it actually landed under — a collision refuses instead of guessing, so this is always the typed name verbatim. */
    fun snipRenamed(name: String): String = "RENAMED TO $name."
    const val SNIP_RENAME_FAILED = "COULDN'T RENAME - CHECK THE NAME AND TRY AGAIN."
    /**
     * DELETE on a SNIPS row, now into the 30-day bin — `SnipStore.delete`'s
     * own promise, `kitDeleted`'s shape applied to one file. [name] is
     * `SnipStore.Info.displayName`, so a never-confidently-classified snip
     * reads "SNIP", never a guess.
     */
    fun snipDeleted(name: String): String = "$name IS OFF THE LIST. ${Reversal.MIND}"
    const val SNIP_DELETE_FAILED = "DELETE FAILED. THE FILE MAY ALREADY BE GONE."

    // ---- DELETED SNIPS: restore or empty early ----
    /** RESTORE on a binned snip; [name] is what it actually landed under — `SnipStore.restore`'s own collision fallback may have freshened it, never the name the row showed. */
    fun snipRestored(name: String): String = "$name IS BACK IN SNIPS."
    /** EMPTY THE BIN NOW on DELETED SNIPS, confirmed — `kitBinEmptied`'s own no-count reasoning applies here too. */
    val snipBinEmptied: String = "THE BIN IS EMPTY. GONE FOR GOOD."

    // ---- SHELF SORT (name-and-find followups): the KitsScreen header toggle ----
    /** The shelf's own header chip while sorted `KitShelf.ShelfSort.RECENT` — tapping switches to [SHELF_SORT_ALPHA]. */
    const val SHELF_SORT_RECENT = "SORT ▸ RECENT"
    /** The shelf's own header chip while sorted `KitShelf.ShelfSort.ALPHA` — tapping switches back to [SHELF_SORT_RECENT]. */
    const val SHELF_SORT_ALPHA = "SORT ▸ A–Z"

    // ---- SHELF FILTER (September UAT, finding 16): the tap-only find ----

    /**
     * The shelf's filter chip while nothing is filtered out.
     *
     * Reads as a state, not a command, exactly as the SORT chip beside it
     * does: both say what the shelf is currently doing, and tapping moves
     * it on. "ALL" rather than "NO FILTER" because the shelf is showing
     * all of them, which is the fact; the absence of a filter is an
     * implementation detail.
     */
    const val SHELF_FILTER_ALL = "SHOW ▸ ALL"

    /**
     * The same chip narrowed to one dub state, named with [dubChip] so the
     * chip on the row and the chip in the header cannot drift apart.
     */
    fun shelfFilter(status: DubStamp.Status?): String =
        if (status == null) SHELF_FILTER_ALL else "SHOW ▸ ${dubChip(status)}"

    /**
     * What the shelf says when a filter has hidden every kit on it.
     *
     * A list that empties itself with no explanation is the single most
     * alarming thing a shelf can do - the user's reading is that the kits
     * are gone, not that they are filtered. So this names the filter that
     * did it and the tap that undoes it, and the count of what is really
     * there. A sentence the screen says, so it keeps its full stop.
     */
    fun shelfFilterEmpty(status: DubStamp.Status, total: Int): String =
        "NO KITS ARE ${dubChip(status)}. ALL $total ARE STILL THERE — TAP SHOW."

    // ---- PAD SHEET: pad from anything ----
    const val PAD_MADE = "PAD MADE. ON THE SHELF."
    const val PAD_TOO_SHORT = "TOO SHORT TO STRETCH INTO A PAD. FEED IT MORE THAN A BLINK."
    const val PAD_TOO_LONG = "TOO LONG TO SLOW INSIDE A MINUTE. TRIM IT UNDER THIRTY SECONDS."

    // Chop shop.
    /**
     * RE-CHOP's own toast. Used to always name "SLICE 3" regardless of
     * which slice, or even how many slices, were actually involved —
     * `ChopScreen.kt`'s RE-CHOP button calls `ChopReviewModel.rechop`,
     * which redoes the *whole* chop from the tape, not one numbered
     * slice, so there is no real "which slice" for this toast to name.
     * States what actually happened instead of inventing a slice number.
     */
    const val RECHOPPED = "RE-CHOPPED."

    /** "N SLICES ON THE GRID. CHOKE GROUP SET." — the send-to-grid toast. */
    fun sentToGrid(sliceCount: Int, chokeSet: Boolean): String =
        "$sliceCount SLICES ON THE GRID." + if (chokeSet) " CHOKE GROUP SET." else ""
    /**
     * INSTANT KIT: the one tap, what it chopped, then the same words SEND TO
     * GRID says.
     *
     * [wholeTape] is the September UAT's finding 19. INSTANT KIT sits beside
     * COMMIT under the deck, and with nothing selected the two disagree:
     * COMMIT refuses in words ([COMMIT_NEEDS_SELECTION]), INSTANT KIT quietly
     * takes the whole tape. Taking the whole tape is the right default for a
     * one-tap button — the silence about it was the bug — so the line names
     * the scope instead, and names it as IN + OUT, the same two words COMMIT's
     * refusal uses for the markers the user would have set.
     *
     * No default value on purpose: a caller that forgets this argument would
     * otherwise report the wrong scope silently, which is the exact failure
     * being fixed.
     */
    fun instantKit(sliceCount: Int, chokeSet: Boolean, wholeTape: Boolean): String =
        (if (wholeTape) "ONE TAP, THE WHOLE TAPE. " else "ONE TAP, YOUR IN + OUT. ") + sentToGrid(sliceCount, chokeSet)

    // ---- CHOP ALL: the crate-digging verb, wired in ----
    /** CHOP ALL's busy line while every picked .wav chops in turn — same DUBBING…/BREEDING… shape. */
    const val CHOP_ALL_BUSY = "CHOPPING…"
    /** The picker returned files, but none of them were .wav — `ChopAllCommand`'s own refusal, said in words instead of a CLI exit code. */
    const val CHOP_ALL_NO_WAVS = "NONE OF THAT WAS A .WAV. PICK SOME AND TRY AGAIN."
    /**
     * CHOP ALL's own toast: [made] kits actually landed out of [wavCount]
     * .wav files it tried — the same "chopped N of M files" shape
     * `ChopAllCommand` itself prints — with [failed] (threw mid-chop; a
     * bad transient read, silence, whatever) and [skipped] (not a .wav at
     * all) named only when either is above zero. A file failing never
     * fails the batch — `ChopAllCommand`'s whole point, carried over here.
     */
    fun choppedAll(made: Int, wavCount: Int, skipped: Int, failed: Int): String {
        val extra = buildList {
            if (failed > 0) add("$failed FAILED")
            if (skipped > 0) add("$skipped SKIPPED (NOT .WAV)")
        }
        val tail = if (extra.isEmpty()) "" else " — ${extra.joinToString(", ")}"
        return "CHOPPED $made OF $wavCount FILES INTO $made ${if (made == 1) "KIT" else "KITS"}$tail."
    }

    // ---- X-RAY: read any MPC file, never import it ----
    const val XRAY_BUSY = "READING…"
    /** The picker handed back a file X-Ray's own bytes-in-hand path never opens — a dead content URI, a provider that vanished mid-read. */
    fun xrayFailed(reason: String): String = "COULDN'T READ THAT: ${reason.uppercase(java.util.Locale.ROOT).trimEnd('.')}."

    // ---- DOUBLES: pads across kits inside a "same sound" distance — the number, never a verdict ----
    const val DOUBLES_BUSY = "MEASURING…"
    /** The empty state names the ring, not a verdict — a clean bill is not something a distance can give. */
    fun noDoubles(within: Float): String =
        "NO DOUBLES WITHIN ${String.format(java.util.Locale.ROOT, "%.2f", within)}. NOT A CLEAN BILL - JUST NONE THIS CLOSE."
    /** The screen's standing rule, on screen: what the number is, and what this screen never does. */
    const val DOUBLES_RULE = "THE NUMBER IS A FEATURE DISTANCE, NOT A VERDICT. NOTHING HERE DELETES, MOVES OR MERGES."
    /** The one known blind spot, said out loud: the head of the hit is what's measured, so two trims of one take can read as strangers. */
    const val DOUBLES_TRIM_CAVEAT = "TWO TRIMS OF ONE HIT MAY NOT LAND THIS CLOSE. THE FIRST 4096 SAMPLES ARE WHAT'S MEASURED."
    /** What the measuring pass actually did: the honest split between fresh work and the crate's cache. */
    fun doublesMeasured(extracted: Int, fromCache: Int): String =
        "$extracted ${if (extracted == 1) "PAD" else "PADS"} MEASURED, $fromCache FROM THE CRATE INDEX."
    /** A GO ▸ on a kit the shelf's own listing doesn't hold (a kit in a subfolder, say) — the number stays, the door doesn't. */
    const val DOUBLES_KIT_NOT_ON_SHELF = "THAT KIT ISN'T ON THE SHELF'S OWN LIST. THE NUMBER STANDS - THE DOOR DOESN'T."
    /** The measuring pass itself threw — a folder that vanished mid-scan, an unreadable `kit.json`. [reason] in the exception's own words when it has one; the exception's detail goes to `Log.e`, never a Java class name here. */
    fun doublesFailed(reason: String): String = "COULDN'T MEASURE THE SHELF: ${reason.uppercase(java.util.Locale.ROOT).trimEnd('.')}."

    // ---- CHOP: the chip itself (HANDOFF.md — "chip tap = cycle class label, 'YOU ✓'") ----
    /** A chip under the confidence threshold, in its own words. */
    const val CHIP_NOT_SURE = "NOT SURE"

    /** The override marker on a corrected chip. */
    const val CHIP_OVERRIDDEN = "YOU ✓"

    // Export.
    const val EXPORT_DONE = "DUBBED."
    /**
     * WRITE KIT landed with no card picked, so the file is on the phone
     * only. Used to claim "SOUNDS 3% WARMER NOW" — a fabricated
     * measurement attached to every successful export, nothing actually
     * measured. States what really happened: the file is on the phone,
     * the same fact [DUB_DONE_CARD] states for the card.
     */
    const val DUB_DONE = "DUB DONE. IT'S ON THE PHONE."
    /**
     * WRITE ANOTHER ✓ (`ExportWizardModel.writeLabel`'s COMPLETE-stage
     * label — no longer "EJECT CARD ✓", which claimed an eject that never
     * happens): resets the wizard to READY for another dub or another
     * kit. That's the whole effect — the file DUB already wrote stays
     * exactly where it landed; this button doesn't touch it, so the toast
     * doesn't claim it moved anywhere.
     */
    const val CARD_EJECTED = "RESET. WRITE ANOTHER, OR SWITCH KITS."
    /** The completion stage's own location line, sitting above the raw path. No claim about which file browser can see it — just that it's on the phone. */
    const val EXPORT_SAVED_TO = "SAVED ON THIS PHONE:"
    /** EXPORT's SHARE action label, next to WRITE ANOTHER ✓ on the completion stage — offered only when the write produced one self-contained file. */
    const val EXPORT_SHARE_LABEL = "SHARE ▸ SEND THIS FILE"
    /** EXPORT's own share toast once the chooser is up — the same tail SHARE and BACKUP use. */
    const val EXPORT_SHARE_SENT = "PICK WHERE IT GOES."
    /**
     * WRITE KIT threw mid-dub — something Preflight's checklist couldn't
     * have caught (storage pulled, disk full, permission yanked). The
     * exception's own message goes to logcat, not here: it isn't in voice
     * and isn't actionable, and law 3 means saying what happened, not
     * quoting Java at somebody.
     */
    const val DUB_FAILED = "DUB FAILED. CHECK YOUR STORAGE AND TRY AGAIN."
    /**
     * The first tap on DUB when something is already at the destination
     * (September UAT, finding 18). [what] is the exact thing in the way, as
     * the writer named it — a name, not "a file", because the user is about
     * to decide whether that particular thing is expendable.
     *
     * It says what the next tap will do, so the confirmation is a decision
     * rather than a dare: nothing has been written when this appears.
     */
    fun dubWouldOverwrite(what: String): String = "$what IS ALREADY THERE. DUB AGAIN TO WRITE OVER IT."
    /**
     * The dub landed *and* went onto the card the user picked. Said apart
     * from [DUB_DONE] because it is a different promise: that one means
     * the file is on the phone, this one means it is on the thing you are
     * about to carry to the MPC.
     */
    const val DUB_DONE_CARD = "DUB DONE. IT'S ON THE CARD."
    /** The card picker came back with a folder the system would not grant lasting access to. */
    const val CARD_REFUSED = "THAT FOLDER WON'T HOLD STILL. PICK ANOTHER."
    /** The CARD row's label when no card is picked — the dub goes to the phone. */
    const val CARD_NONE = "CARD ▸ NONE — SAVING TO THIS PHONE"
    /** The CARD row when one is picked; the destination's own name follows. */
    const val CARD_PICKED = "CARD ▸"
    /** Long-press the CARD row to go back to the phone. */
    const val CARD_FORGOTTEN = "CARD FORGOTTEN. DUBS GO TO THE PHONE."

    /**
     * The legend under EXPORT's DESTINATION row (September UAT, finding 21).
     *
     * Holding that row is the ONLY way to forget a card - there is no
     * button and no menu entry anywhere else in the app - so without this
     * the gesture was unfindable, and a user who granted the wrong folder
     * had no visible way back.
     *
     * Shown only while a card is actually held, matching the row's own
     * `onLongClick`, which is null without one. A legend offering to forget
     * a card that was never picked would be furniture describing nothing.
     *
     * Wording follows the row's `onLongClickLabel` ("FORGET THIS CARD") so
     * the sighted legend and the TalkBack announcement say the same thing -
     * the rule [SHELF_LEGEND] and [PAD_SHEET_LEGEND] already follow. No full
     * stop: it stays on screen, so it is furniture, not a line said once.
     */
    const val EXPORT_CARD_LEGEND = "HOLD TO FORGET THIS CARD"

    // Kits.
    const val FRESH_TAPE = "NEW TAPE."

    /**
     * What to say when a kit opens with a SNIPS → PAD hand-off still armed.
     *
     * The September UAT's finding 12 called the empty-shelf case a dead end
     * — "nothing offers to make the kit". That is not what the code does:
     * NEW KIT ▸ STARTERS sits right below the panel, exactly where
     * [EMPTY_SHELF_FOR_ASSIGN] says it does, and `fresh()` never clears the
     * pending snip, so the hand-off survives being sent to make a kit.
     *
     * The real gap was narrower and only on that one route. Every other way
     * into a kit with a snip armed says what to do next; `fresh()` said
     * [FRESH_TAPE] and nothing else, so the user arrived on a new kit with
     * the snip still waiting and no idea it was. One rule, both call sites.
     */
    fun snipLanding(hasEmptyPad: Boolean): String =
        if (hasEmptyPad) "LONG-PRESS AN EMPTY PAD TO PLACE THIS SNIP." else "THIS KIT IS FULL — PICK ANOTHER."

    /**
     * `App.kt`'s `fresh()` when `KitShelf.render` throws mid-render (a
     * starter kit fails to write). Its KDoc used to claim this fired "at
     * every personality level," a leftover from the deleted PERSONALITY
     * system — but `fresh()`'s catch block never actually called it; it
     * built its own literal, `"DUB FAILED: ${e.message ?: e.javaClass
     * .simpleName}"`, quoting the raw exception at the user. Wired to
     * this constant now: the exception's own message goes to logcat
     * instead, same reasoning as [DUB_FAILED]'s own KDoc on why law 3
     * means saying what happened, not quoting Java at somebody.
     */
    const val CREATE_FAILED = "COULDN'T MAKE THAT TAPE."

    // ==================== Escaped strings, brought in (name-and-find follow-ups, Part C) ====================
    //
    // 78 strings across `:app`'s newer screens lived as inline literals
    // passed straight to `onToast(...)`/`toast = "..."`, invisible to every
    // law in `PersonalityTest` because they were never `Copy` fields — this
    // KDoc's own claim that "everything the UI says lives here" was false
    // for all of them. Moved in here, screen by screen, rewritten to the
    // same plain register as the rest of this file.
    //
    // The single biggest repeated shape, across `App.kt` and a dozen other
    // screens, was `"$VERB FAILED: ${e.message ?: e.javaClass.simpleName}"` —
    // an unexpected exception quoted straight at the user, sometimes as
    // nothing more than a bare Java class name. [CREATE_FAILED]'s and
    // [DUB_FAILED]'s own KDocs already refuse to do this. The exception's own
    // detail now goes to `Log.e` at every one of those call sites instead of
    // the toast, and the toast itself is one of the `*_FAILED` constants
    // below — a `const val`, like every other field in this file, so the
    // reflective shout/full-stop laws actually check it (the whole reason
    // this section exists: a string that only lives in a function argument
    // is invisible to them, same as it was invisible entirely before this
    // pass). [actionFailed] is the one exception: it exists only for the
    // handful of call sites where the verb itself is a runtime value
    // (`TextureKits.Spec.verb`, or a `failure(action, e)` helper shared by
    // several buttons on one screen) and a fixed constant genuinely cannot
    // name it in advance.
    const val KEY_FAILED = "KEY FAILED. TRY AGAIN."
    const val TWINS_FAILED = "EVIL TWINS FAILED. TRY AGAIN."
    const val BREED_FAILED = "BREED FAILED. TRY AGAIN."
    const val PLACE_FAILED = "PLACE FAILED. TRY AGAIN."
    const val RETRIM_FAILED = "RE-TRIM FAILED. TRY AGAIN."
    const val READ_GROOVE_FAILED = "READ FAILED. TRY AGAIN."
    const val FEEL_FAILED = "FEEL FAILED. TRY AGAIN."
    const val INSTANT_KIT_FAILED = "INSTANT KIT FAILED. TRY AGAIN."
    /** SHARE's own generic failure — both a kit's SHARE and a room's SHARE in `App.kt` use this one line. */
    const val SHARE_FAILED = "SHARE FAILED. TRY AGAIN."
    const val ROOM_FORGET_FAILED = "FORGET FAILED. TRY AGAIN."
    const val ROOM_RESTORE_FAILED = "RESTORE FAILED. TRY AGAIN."
    const val ROOM_BIN_EMPTY_FAILED = "EMPTY BIN FAILED. TRY AGAIN."
    const val BACKUP_FAILED = "BACKUP FAILED. TRY AGAIN."
    const val IN_KEY_FAILED = "IN KEY FAILED. TRY AGAIN."
    const val RECHOP_FAILED = "RE-CHOP FAILED. TRY AGAIN."
    /** SEND's own generic failure — CHOP's send-to-grid and SYNTH's send-to-pad both use this one line. */
    const val SEND_FAILED = "SEND FAILED. TRY AGAIN."
    const val SPLICE_FAILED = "SPLICE FAILED. TRY AGAIN."
    const val PREVIEW_FAILED = "PREVIEW FAILED. TRY AGAIN."
    const val RENDER_FAILED = "RENDER FAILED. TRY AGAIN."
    const val DIG_FAILED = "DIG FAILED. TRY AGAIN."
    const val STACK_FAILED = "STACK FAILED. TRY AGAIN."
    const val MIX_FAILED = "MIX FAILED. TRY AGAIN."
    const val ORBIT_BOUNCE_FAILED = "BOUNCE FAILED. TRY AGAIN."
    const val ORBIT_CLIP_FAILED = "CLIP FAILED. TRY AGAIN."

    /**
     * The generic "X failed, try again" toast for the handful of call sites
     * where [action] is a runtime value, not a fixed verb — `App.kt`'s
     * `texture` (`TextureKits.Spec.verb`: SCULPT, STRETCH or FREEZE) and the
     * `failure(action, e)` helpers several screens share across more than
     * one of their own buttons. A function, not a field, so the reflective
     * laws don't reach it - written in register by hand for exactly that
     * reason. Every call site whose verb is fixed at compile time gets its
     * own `*_FAILED` constant above instead, so the law actually checks it.
     */
    fun actionFailed(action: String): String = "$action FAILED. TRY AGAIN."

    /** PLACE (SNIPS → PAD): the snip landed on [slot], `App.kt`'s `assignPendingSnip`'s success line, moved out of an inline literal. */
    fun snipPlaced(slot: Int): String = "SNIP PLACED ON PAD ${PadBanks.tag(slot)}."

    // ---- SURFACE + SPLIT: the two printing screens share one shape ----
    //
    // Both SURFACE (the tactile pad) and SPLIT (stems) print a live
    // performance to TAPE or onto a pad, through the same `SnipStore.import`/
    // `KitBuilderModel.assign`-or-`replaceAudio` pair - so the failures they
    // can hit are the same failures, and now say the same thing.
    /** Neither screen's engine could open a low-latency audio stream. */
    fun noLowLatencyStream(screen: String): String = "NO LOW-LATENCY STREAM. $screen IS SILENT."
    const val SURFACE_SHARED_STREAM = "SHARED STREAM. A LITTLE MORE LATENCY."
    /** A kit pad's own sample wouldn't decode - SURFACE loading a voice, SPLIT loading a source. */
    fun sourceUnreadable(name: String): String = "${name.uppercase(java.util.Locale.ROOT)} WOULD NOT READ."
    /** `SnipStore.import` threw on the way to TAPE - an unexpected write failure, not a refusal with words of its own. */
    const val PRINT_LOST = "PRINT LOST. TRY AGAIN."
    /** The chooser's own `IllegalArgumentException`/`IllegalStateException` when the kit changed under it (a layered or chained pad) - the exception's own message is `KitBuilder`'s internal "no pad on slot N", not user copy, so it stays out of the toast. */
    const val PRINT_PAD_REFUSED = "THAT PAD WON'T TAKE THE PRINT. PICK ANOTHER."
    /** Disk or decode trouble landing a print on a pad - the print itself is not lost, so this says so, unlike [PRINT_LOST]. */
    const val PRINT_LANDING_FAILED = "LANDING FAILED. THE PRINT IS STILL HERE."
    /** SPLIT's own → PAD landing: [replaced] is true when a taken slot's old sample went to the bin; false when the split landed fresh on an empty one. */
    fun splitPrintedToPad(pad: String, replaced: Boolean): String =
        if (replaced) "PAD $pad REPLACED WITH THE SPLIT. ORIGINAL SLEEPS IN THE BIN." else "SPLIT PRINTED TO PAD $pad."
    /** SURFACE's own → PAD landing - same shape as [splitPrintedToPad], SURFACE's own words. */
    fun surfacePrintedToPad(pad: String, replaced: Boolean): String =
        if (replaced) "PAD $pad REPLACED WITH THE PRINT. ORIGINAL SLEEPS IN THE BIN." else "PRINTED TO PAD $pad."

    // ---- SURFACE ----
    const val SURFACE_SETTINGS_NOT_SAVED = "SURFACE SETTINGS NOT SAVED. TRY AGAIN."
    const val SURFACE_SETTINGS_UNREADABLE = "SURFACE SETTINGS UNREADABLE. USING THE DEFAULTS."
    const val SURFACE_SET_NEEDS_TOUCH = "TOUCH THE PAD FIRST. SET KEEPS WHAT WAS UNDER THE FINGER."
    fun surfaceCornerSet(letter: Char): String = "CORNER $letter SET."
    fun surfacePrinted(seconds: Float): String = "PRINTED ${"%.1f".format(java.util.Locale.ROOT, seconds)} S TO TAPE."
    const val SURFACE_NOTHING_PRINTED = "NOTHING PRINTED. HOLD THE SURFACE WHILE IT PRINTS."
    const val SURFACE_STILL_LANDING = "STILL LANDING THE LAST PRINT."
    /** PRINT armed with no tempo to count bars against - a `const val` (not folded into [surfacePrintingStarted]'s own fixed text) so the reflective shout/full-stop laws actually check it. */
    const val SURFACE_PRINTING_NO_TEMPO = "PRINTING. PLAY THE SURFACE."
    /** PRINT armed: names the bar count and tempo when the kit has one to count against, or falls back to [SURFACE_PRINTING_NO_TEMPO] when it doesn't. */
    fun surfacePrintingStarted(bars: Int, bpm: Int?): String =
        if (bars > 0 && bpm != null) "PRINTING ${PrintLength.label(bars)} AT $bpm BPM." else SURFACE_PRINTING_NO_TEMPO

    // ---- SPLIT ----
    const val SPLIT_ALL_FADERS_DOWN = "EVERY FADER IS DOWN. NOTHING TO HEAR."
    /** SPLIT's engine refused to arm playback - not an exception, just `false` back from `armPrint`/its own start call. */
    const val SPLIT_START_FAILED = "SPLIT WOULD NOT START. TRY AGAIN."
    const val SPLIT_PAD_EMPTY = "THAT PAD HAS NOTHING ON IT."
    /** `Separate.stn`'s own unexpected failure - no domain refusal words of its own, so nothing worth quoting. */
    const val SPLIT_REFUSED = "SPLIT REFUSED. TRY AGAIN."
    /** [hotPeak] over 1.0 when the split's own mix clipped; null when it did not, matching `SPLIT PRINTED`'s own optional "HOT" note. */
    fun splitPrinted(seconds: Float, hotPeak: Float?): String {
        val note = if (hotPeak != null) " HOT: PEAK ${"%.2f".format(java.util.Locale.ROOT, hotPeak)}." else ""
        return "SPLIT PRINTED ${"%.1f".format(java.util.Locale.ROOT, seconds)} S TO TAPE.$note"
    }
    const val SPLIT_NOTHING_TO_PRINT = "EVERY FADER IS DOWN. THERE IS NOTHING TO PRINT."
    fun splitMixHot(peak: Float): String = "THE MIX IS HOT: PEAK ${"%.2f".format(java.util.Locale.ROOT, peak)}. PICK A PAD, OR PULL A FADER DOWN."

    // ---- ORBIT ----
    /** ADD RING x3 (a pad ring, a snip ring, DUPLICATE) - `OrbitSet.MAX_ORBITS` is 8; a settled fact worth stating outright, not "THE SKY". */
    const val ORBIT_RINGS_FULL = "8 RINGS ALREADY. ORBIT HOLDS NO MORE."
    const val ORBIT_NO_PADS = "NO PADS ON THIS KIT."
    fun orbitFileUnreadable(name: String): String = "COULD NOT READ $name."
    const val ORBIT_NOTHING_TO_UNDO = "NOTHING TO UNDO."
    /** BOUNCE landed: [label] is `cycleLabel`'s own name for what was heard. */
    fun orbitBounced(label: String): String = "ON TAPE: $label. TRIM IT, CHOP IT, KIT IT."
    /**
     * CLIP ▸ KIT landed: [name] the clip's own name, [bars]/[notes] what it
     * holds, [snipRingsLeftOut] how many snip rings (audio, which a
     * note-only clip can never carry) stayed out of it. Leads with the
     * confirmation, not the omission - a player who just tapped CLIP ▸ KIT
     * wants to hear that it worked first.
     */
    fun clippedIntoKit(name: String, bars: Int, notes: Int, snipRingsLeftOut: Int): String =
        if (snipRingsLeftOut == 0) {
            "$name IS IN THE KIT'S GROOVES — $bars BARS, $notes NOTES. IT RIDES TO THE MPC."
        } else {
            "$name IN THE GROOVES: $bars BARS, $notes NOTES. $snipRingsLeftOut SNIP RING${if (snipRingsLeftOut == 1) "" else "S"} STAYED OUT."
        }

    /**
     * CLIP ▸ KIT landed for a set with an arrangement: [sections] clips
     * rather than one, each becoming a sequence the hardware's switcher
     * flips between.
     *
     * [sections] is the clips actually WRITTEN, not the sections asked
     * for — a section that plays no rings is a break and writes none, so
     * the line says what is in the kit rather than what was intended.
     */
    fun clippedSectionsIntoKit(sections: Int, bars: Int, notes: Int, snipRingsLeftOut: Int): String {
        // One section is a sentence, not a count with an S on it.
        //
        // This function IS the multi-section path from CLIP ▸ KIT; what
        // has no caller today is the [sections] == 1 case, because a save
        // that wrote one clip goes to [clippedIntoKit] and names it. But a
        // line that reads "1 SECTIONS ARE" the first time anything calls
        // it that way is a trap left lying about, and the plural is two
        // words.
        val many = sections != 1
        val subject = if (many) "$sections SECTIONS ARE" else "1 SECTION IS"
        val them = if (many) "THEY RIDE" else "IT RIDES"
        return if (snipRingsLeftOut == 0) {
            "$subject IN THE KIT'S GROOVES — $bars BARS, $notes NOTES, ONE SEQUENCE EACH. $them TO THE MPC."
        } else {
            "$sections SECTION${if (many) "S" else ""} IN THE GROOVES: $bars BARS, $notes NOTES. " +
                "$snipRingsLeftOut SNIP RING${if (snipRingsLeftOut == 1) "" else "S"} STAYED OUT."
        }
    }

    // ---- GRAIN FIELD ----
    const val GRAIN_FIELD_TOO_SHORT = "TOO SHORT TO MAP. THE FIELD NEEDS MORE TAPE."
    /** `GrainVoice.start()` threw - the platform rejected the format; a caught, retryable condition. */
    const val GRAIN_FIELD_START_FAILED = "GRAIN VOICE WON'T START."
    /** The render thread died on its own, or a live gesture threw against it - DUET turns itself off either way. */
    const val GRAIN_FIELD_DUET_STOPPED = "DUET STOPPED — OFF."
    /** The field's own busy line while the tape is read into its grid - furniture, like every other `…`-suffixed busy line, so it carries no full stop. */
    const val GRAIN_FIELD_LISTENING = "LISTENING TO THE GRAIN…"
    /** DUET on: the mic plays the field live, said once so headphones are a choice, not a surprise. */
    const val GRAIN_FIELD_DUET_HINT = "THE MIC PLAYS THE FIELD. HEADPHONES RECOMMENDED."
    /** DUET off: the field's ordinary touch-to-play hint. */
    const val GRAIN_FIELD_DRAG_HINT = "DRAG TO PLAY THE GRAIN FIELD."

    // ---- GROOVE: MIDI EXPORT's own success line ----
    fun midiFilesWritten(count: Int): String =
        "$count MIDI FILES WRITTEN — ANY DAW OPENS THE RHYTHM. THE MPC PLAYS IT TOO."

    // ---- PAD CAPTURE: GRAB/HOLD's own too-short guard ----
    const val PAD_CAPTURE_HOLD_TOO_SHORT = "HOLD TO RECORD."
    /** GRAB/HOLD attempted before LISTEN was ever pressed - `MicSessionService.armed` is false, so there is no ring to snapshot at all. */
    const val PAD_CAPTURE_NOT_LISTENING = "NOT LISTENING YET."
    /** GRAB's own empty snapshot - the ring had nothing in it to cut. */
    const val GRAB_NOTHING_YET = "NOTHING TO GRAB YET."
    /** HOLD's own empty snapshot - released before a single frame landed. */
    const val HOLD_NOTHING_RECORDED = "NOTHING RECORDED."
    /** GRAB/HOLD's own landing toast: [verb] the gesture's own past-tense word ("GRABBED"/"RECORDED"), [pad] the pad it landed on. */
    fun padGestureLanded(verb: String, pad: String): String = "$verb → PAD $pad."
    /**
     * GRAB/HOLD's own idle panel once armed: what each gesture actually
     * captures - [grabSeconds] interpolated so this can't drift from the
     * real ring window. [SNIPPED]'s own shape ("KEPT WHAT IT'S HEARD SINCE
     * LISTEN, UP TO 60s") rather than "THE LAST ${grabSeconds}s HEARD" -
     * naming the start point (SINCE LISTEN), not just the cap, is what
     * makes "UP TO" true: `CaptureRing.snapshot` returns a SHORT array when
     * the ring holds fewer frames than asked for, so in the first couple
     * seconds after LISTEN a GRAB genuinely gets less than this. (The
     * reflective ceiling law now catches a bare "LAST Ns" here the way it
     * always could for a `const val` - moving this out of
     * `PadCaptureScreen.kt` and into a function here is what let it reach
     * this line at all, and it caught exactly that shape on the first
     * draft of this fn.)
     */
    fun padCaptureReady(grabSeconds: Int): String =
        "GRAB KEEPS WHAT'S BEEN HEARD SINCE LISTEN, UP TO ${grabSeconds}s. HOLD RECORDS WHILE YOU HOLD."
    /** GRAB/HOLD's own idle panel before LISTEN has ever been pressed. */
    const val PAD_CAPTURE_NEEDS_MIC = "NOT LISTENING YET. START THE MIC, THEN HIT SOMETHING."

    // ---- SNIPS: a row that will not decode ----
    const val SNIP_CANT_PLAY = "CAN'T PLAY THIS SNIP."

    // ==================== Escaped strings, brought in (copy-consolidation follow-ups) ====================
    //
    // The count-driven pass above stopped once it hit its own tally of 78.
    // These were the residue: inline literals a `grep 'onToast("'` never
    // saw (a multi-line call, a `TapeText` argument, a parameter carrying a
    // literal from its call site) but a user reads all the same, so the
    // property this file's own KDoc claims - "everything the UI says lives
    // here" - was still false for every one of them.
    /** DELETED KITS'/DELETED SNIPS' own empty state - plain, not the tape-metaphor voice, matching SNIPS' own locked tone ([SNIPS_EMPTY]). */
    const val NOTHING_DELETED = "NOTHING DELETED."
    /** SNIPS' own empty state. */
    const val SNIPS_EMPTY = "NO SNIPS YET."
    /** KIT/PLAY with no tape ever committed to this kit - both screens' own empty deck. */
    const val NO_TAPE_IN_DECK = "NO TAPE IN THE DECK. OPEN ONE ON THE SHELF."
    /** IN KEY's row when the kit holds no tonal pad at all. */
    const val NO_TONAL_PADS = "NO TONAL PADS. DRUMS LAND AS CAPTURED."
    /** SNIPS → PAD's own header hint once the shelf has a kit to tap - [EMPTY_SHELF_FOR_ASSIGN] carries the instruction when it doesn't. */
    const val ASSIGN_PICK_HINT = "TAP A KIT, THEN LONG-PRESS AN EMPTY PAD."
    /**
     * The kit shelf's ROOMS legend (September UAT, finding 4/5's own shape
     * applied here): HOLD is the only door to FORGET on a room row, so this
     * stays on screen rather than riding on a toast that can be dismissed
     * forever. A `val`, not `const val`, only because [Rooms.BIN_DAYS]
     * rides in the template - reflection over `Copy`'s declared fields
     * finds it either way.
     */
    val ROOMS_LEGEND: String = "HOLD A ROOM TO FORGET IT · THE BIN KEEPS IT ${Rooms.BIN_DAYS} DAYS"
    /** ORBIT's SET ▸ TEMPO confirm row: what accepting the offered tempo actually does to the ring, since SET is otherwise silent about it. */
    const val ORBIT_SET_TEMPO_HINT = "SET MOVES THE WHOLE SET AND RE-SIZES THE RING TO FIT."
    /**
     * ORBIT's own on-screen legend: the ring/cell gestures, permanent
     * furniture under the deck, plus one of two closing clauses depending
     * on whether the shelf actually holds a snip for + SNIP to reach for. A
     * function, not a `_LEGEND` field, because of that branch - the
     * reflective legend law only walks fields, same reasoning as
     * `breedPickHeader`'s own KDoc.
     */
    fun orbitLegend(hasSnips: Boolean): String =
        "TAP A RING TO PICK IT · HOLD TO SOLO · TAP A CELL FOR A HIT, HOLD IT FOR AN ACCENT · " +
            if (hasSnips) {
                "HOLD BPM TO RUN IT · TAP THE READOUT FOR THE BAR · SHORTEST RING INSIDE COMES ROUND FIRST."
            } else {
                "NO SNIPS ON THE SHELF YET FOR + SNIP."
            }
    /** SURFACE with no kit open - the pad it plays has nothing to come from yet. */
    const val SURFACE_NEEDS_KIT = "OPEN A KIT. THE SURFACE PLAYS ITS FIRST PAD."
    /** SPLICE's own picker hint before a head and a tail are chosen. */
    const val SPLICE_PICK_HINT = "PICK A HEAD AND A TAIL - TWO DIFFERENT TAKES OF THIS PAD."
    /** X-RAY on a JSON file that isn't an MPC program at all - [topKeys] the top-level field count when the tree parsed as an object, null when it didn't even parse that far. */
    fun xrayNotAProgram(topKeys: Int?): String =
        if (topKeys != null) "JSON, NOT AN MPC PROGRAM. $topKeys TOP-LEVEL FIELDS." else XRAY_NOTHING_TO_SHOW
    /** [xrayNotAProgram]'s own fallback, pulled out as a `const val` so the reflective laws reach it. */
    const val XRAY_NOTHING_TO_SHOW = "NOTHING TO SHOW."
    /** X-RAY's own footnote: fields the reading found but doesn't yet have a label for. */
    fun xrayUnlabeledFields(count: Int): String =
        "$count FIELD${if (count == 1) "" else "S"} PRESENT IN THIS FILE, NOT YET LABELED HERE."
    /** `App.kt`'s `fresh()`: the busy overlay while a starter kit renders offline, named so the wait is attributable - same shape as [treatmentBusy]. */
    fun dubbingBusy(name: String): String = "DUBBING $name…"
    /** EVIL TWINS' own busy overlay while every twin renders offline. */
    const val EVIL_TWINS_BUSY = "TWINNING…"
    /** INSTANT KIT's own busy overlay - the same word [CHOP_ALL_BUSY] uses for its own (unrelated) crate-digging pass, kept as its own constant since the two features are otherwise unconnected. */
    const val INSTANT_KIT_BUSY = "CHOPPING…"

    /** Rotation helper: line [n] of a rotating list (n counts from 0) — [COMMIT_LINES]'s own rotation. */
    fun rotating(lines: List<String>, n: Int): String = lines[n % lines.size]
}
