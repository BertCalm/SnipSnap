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
    /**
     * The shelf's empty face during a SNIPS → PAD hand-off (`assigningSnip`
     * in `KitsScreen`/`App.kt`) when the shelf also has zero kits — distinct
     * from [EMPTY_SHELF], which would otherwise flatly claim nothing exists
     * right after the user just captured the very snip they're trying to
     * place. Names the real blocker (no kit to land on) and the real fix
     * (NEW KIT, right below this panel) rather than denying the snip exists.
     */
    const val EMPTY_SHELF_FOR_ASSIGN = "THIS SNIP NEEDS A KIT TO LAND ON. TAP NEW KIT BELOW TO MAKE ONE."
    const val EMPTY_KIT = "16 EMPTY PADS. TERRIFYING."
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
    const val SESSION_ARMED = "TAPE ROLLING. GO STEAL A SOUND (LEGALLY)."
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
        "TAPE JAM — SPOTIFY BLOCKS THE TAPE. USE THE SCREEN RECORDER, I'LL PULL THE AUDIO OUT."
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
    const val INSIDE_ARMED = "TAPE ROLLING ON THE INSIDE. GO PLAY THE THING."
    /** The projection consent dialog was dismissed: nothing armed, nothing lost; LISTEN is still there. */
    const val INSIDE_REFUSED = "NO NOD, NO TAPE. NOTHING ARMED. LISTEN STILL WORKS."
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
    /** PAD SHEET's own DELETE → BIN, on a pad — a real delete, distinct from EJECT (stop listening) or the export wizard's reset. */
    const val DELETE_SNIP = "DELETED. THE BIN KEEPS IT 30 DAYS."
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
     * Shown on opening a kit, at most a few times, and never again once the
     * user has actually held a pad. PAD SHEET is reachable ONLY by a long
     * press on a filled pad — no button, no menu entry — and it holds every
     * treatment, shape, tune and mutate control in the app. Without this the
     * gesture is undiscoverable, and a feature nobody can find is a feature
     * they don't have.
     *
     * Says what to do and what it gets, in that order, and names the thing
     * it opens so the toast and the screen agree.
     */
    const val PAD_SHEET_HINT = "HOLD A PAD TO OPEN ITS PAD SHEET — SHAPE, TUNE, TREAT."

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
    const val BANK_B_LIT = "EVIL TWINS DEALT ONTO BANK B. RECIPES KEPT."
    const val TWINS_REROLLED = "EVIL TWINS REROLLED. SAME SEED, DIFFERENT SINS."

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
    /** A FILE: the picked file cannot be a parent; [reason] the decoder's or the holder's own words. */
    fun fileRefused(reason: String): String = "NOT A PARENT: ${reason.uppercase(java.util.Locale.ROOT).trimEnd('.')}."

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
    const val OUTSIDE_NEEDS_MIC = "OUTSIDE NEEDS THE MIC. PRESS LISTEN ONCE ON KITS TO GRANT IT."
    const val OUTSIDE_TAPE_ROLLING = "THE TAPE IS ROLLING. STOP LISTENING FIRST - OUTSIDE WANTS THE MIC TO ITSELF."
    const val OUTSIDE_NEEDS_ONE = "GHOSTS ON. OUTSIDE WANTS ONE SAMPLE - CLEAR THEM FIRST."
    const val OUTSIDE_SENDING = "SENDING… TURN IT UP."
    const val OUTSIDE_LISTENING = "LISTENING FOR THE ROOM…"
    /** KEEP ROOM: the measured room is on the shelf under [name], for any pad through MUTATE ▸ ROOM. */
    fun roomKept(name: String): String = "$name IS ON THE SHELF. ANY PAD CAN PLAY IN IT - MUTATE ▸ ROOM."
    const val ROOM_NONE_TO_KEEP = "NO ROOM MEASURED YET. SEND A SWEEP OUT FIRST - ROOM ▸ SEND."
    /** FORGET → BIN on the shelf: the room sleeps in the bin, like every delete. */
    fun roomForgotten(name: String): String = "$name IS IN THE BIN. ${Rooms.BIN_DAYS} DAYS TO CHANGE YOUR MIND."
    /** RESTORE on a binned room: back on the shelf under [name]. */
    fun roomRestored(name: String): String = "$name IS BACK ON THE SHELF. AS IF NOTHING HAPPENED."
    const val ROOM_FORGET_BUSY = "FORGETTING…"
    const val ROOM_RESTORE_BUSY = "RESTORING…"

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
    fun kitDeleted(name: String): String = "$name IS OFF THE SHELF. 30 DAYS TO CHANGE YOUR MIND."
    const val KIT_DELETE_BUSY = "DELETING…"
    const val KIT_DELETE_FAILED = "DELETE FAILED. THE KIT MAY ALREADY BE GONE."
    /** RENAME on a shelf kit; [name] is what it actually landed under — a collision may have freshened it. */
    fun kitRenamed(name: String): String = "RENAMED TO $name."
    const val KIT_RENAME_BUSY = "RENAMING…"
    const val KIT_RENAME_FAILED = "COULDN'T RENAME - CHECK THE NAME AND TRY AGAIN."

    // ---- DELETED KITS (Task 2 of the bin-restore plan): restore or empty early ----
    /** RESTORE on a binned kit; [name] is what it actually landed under — `KitShelf.restoreKit`'s own collision fallback may have freshened it, never the name that was tapped. */
    fun kitRestored(name: String): String = "$name IS BACK ON THE SHELF. AS IF NOTHING HAPPENED."
    /**
     * EMPTY THE BIN NOW on DELETED KITS, confirmed. Deliberately carries no
     * count: `KitShelf.emptyKitBin` reports every child it removed, but the
     * list only ever showed the ones whose `kit.json` could be read, so a
     * bin holding a stray file would have toasted more KITS than the user
     * ever saw — reading as unexplained data loss. They just looked at the
     * list; what they need told is that it's empty now, and that it's final.
     */
    val kitBinEmptied: String = "THE BIN IS EMPTY. GONE FOR GOOD."

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
