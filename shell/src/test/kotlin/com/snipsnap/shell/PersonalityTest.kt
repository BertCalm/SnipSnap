package com.snipsnap.shell

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Despite the name, this no longer tests a PERSONALITY slider — that
 * enum and its `Delight` gate are gone (see `Personality.kt`'s own
 * KDoc). What survives, and what this class actually holds to the
 * app: the copy laws — every shipped `Copy` string shouts, lands on a
 * full stop, and never states an unfilled buffer as settled fact.
 * File not renamed per the ask that added this note; the laws below
 * are the reason it still lives in `Personality.kt`'s test file.
 */
class PersonalityTest {

    // ==================== Reflective Copy scan (shared by the laws below) ====================

    /**
     * Every `String`-typed constant [Copy] declares, keyed by field name —
     * via plain `java.lang.reflect`, not `kotlin.reflect` (no kotlin-reflect
     * jar is on this module's classpath, and adding one is a bigger call
     * than this guardrail). A hand-listed `listOf(Copy.A, Copy.B, ...)` used
     * to mean a brand-new `Copy` constant was silently uncovered by every
     * law below unless someone remembered to add it to the list — the
     * guard itself was forgettable. Walking the class's own fields means a
     * new constant is checked automatically the moment it's added, whether
     * anyone remembers it exists or not.
     *
     * Deliberately fields only, not functions: Copy's templated toasts
     * (`imported(...)`, `keySet(...)`, `tapeTruncated(...)`, etc.) take
     * arguments reflection can't synthesize meaningfully, and the existing
     * per-function tests below (`the interpolated lines name what they
     * acted on`, `tapeTruncated matches...`) already cover their real
     * values with real call sites. The ceiling law further down also scans
     * `Personality.kt`'s own source text, which is how a templated toast
     * gets covered too.
     */
    private fun copyStringConstants(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (field in Copy.javaClass.declaredFields) {
            if (field.type != String::class.java) continue
            if (field.isSynthetic) continue
            field.isAccessible = true
            val value = field.get(Copy) as? String ?: continue
            out[field.name] = value
        }
        return out
    }

    @Test
    fun `law 3 - funny copy still says exactly what happened`() {
        // The capture-blocked box names the problem and the way out.
        assertTrue("SCREEN RECORDER" in Copy.CAPTURE_BLOCKED)
        // A session the phone ended names the two ways that happens.
        assertTrue("LOCK SCREEN" in Copy.PHONE_STOPPED_TAPE)
        assertTrue("STOP CHIP" in Copy.PHONE_STOPPED_TAPE)
        // A refused consent must not claim anything is armed.
        assertTrue("NOTHING ARMED" in Copy.APP_AUDIO_REFUSED)
        // The Ear reports what it heard, in real numbers.
        assertEquals("HEARD 12 HITS OVER 2 BARS AT ~93 BPM. THEY PLAY ON YOUR PADS NOW.", Copy.grooveRead(12, 2, 93))
        assertEquals("HEARD 4 HITS OVER 1 BAR AT ~120 BPM. THEY PLAY ON YOUR PADS NOW.", Copy.grooveRead(4, 1, 120))
        // A single hit over a single bar is singular in both counts at once —
        // the shape that would have caught `grooveRead` counting BAR/BARS
        // right while leaving HITS always plural.
        assertEquals("HEARD 1 HIT OVER 1 BAR AT ~90 BPM. THEY PLAY ON YOUR PADS NOW.", Copy.grooveRead(1, 1, 90))
        assertEquals("TOOK 9 HITS OVER 2 BARS. PLAYING ON CAPTURED NOW.", Copy.takeLanded(9, 2), "4/4 carries no meter")
        // Was "TOOK 1 HITS OVER 1 BAR OF 3/4." — takeLanded sang BAR/BARS
        // right (it shares grooveRead's shape) and left HITS always plural,
        // the same asymmetry inside one sentence that the general helper
        // below exists to rule out everywhere at once.
        assertEquals("TOOK 1 HIT OVER 1 BAR OF 3/4. PLAYING ON CAPTURED NOW.", Copy.takeLanded(1, 1, "3/4"))
        assertEquals("TOOK 5 HITS OVER 2 BARS OF 5/4. PLAYING ON CAPTURED NOW.", Copy.takeLanded(5, 2, "5/4"))
        assertEquals("BANK A · 0 FREE", Copy.catchBank('A', 0))
        // A refusal names the control as it reads and the tab it is on
        // (September wiring review, findings 4 and 14): the reflective
        // laws below hold shape, not route, so a slide back to "ARM THE
        // MIC" or a bare "TRY GRID" would pass them.
        for (line in listOf(Copy.HUM_NOT_LISTENING, Copy.HUM_APP_AUDIO, Copy.HUM_NOTHING)) {
            assertTrue("LISTEN · MIC ON SHELF" in line || ("LISTEN · MIC" in line && "SHELF" in line), "HUM's route is the button on SHELF: $line")
            assertTrue("ARM" !in line, "nothing on any screen is called ARM: $line")
        }
        assertTrue("STOP ON SHELF" in Copy.HUM_APP_AUDIO, "APP AUDIO has to be stopped before the mic can start: ${Copy.HUM_APP_AUDIO}")
        // RING takes either ring, so its refusal names both doors on SHELF
        // (a slide back to the mic alone would send an APP AUDIO user to
        // stop the very session RING wanted).
        for (line in listOf(Copy.RING_NOT_LISTENING, Copy.RING_NOTHING)) {
            assertTrue("RING" in line, "the refusal names the button as it reads: $line")
            assertTrue("ARM" !in line, "nothing on any screen is called ARM: $line")
        }
        // EVERY BAR switching itself off names the button as it reads and
        // the same two doors, since either ring feeds it.
        assertTrue("EVERY BAR" in Copy.RING_BAR_STOPPED && "LISTEN · MIC" in Copy.RING_BAR_STOPPED && "APP AUDIO" in Copy.RING_BAR_STOPPED && "SHELF" in Copy.RING_BAR_STOPPED, Copy.RING_BAR_STOPPED)
        assertTrue("ARM" !in Copy.RING_BAR_STOPPED, "nothing on any screen is called ARM: ${Copy.RING_BAR_STOPPED}")
        assertTrue("LISTEN · MIC" in Copy.RING_NOT_LISTENING && "APP AUDIO" in Copy.RING_NOT_LISTENING && "SHELF" in Copy.RING_NOT_LISTENING, "RING's route is either button on SHELF: ${Copy.RING_NOT_LISTENING}")
        // FOLLOW and DUCK read the same ring RING does, so the same two doors.
        assertTrue("FOLLOW" in Copy.FOLLOW_NOT_LISTENING && "DUCK" in Copy.FOLLOW_NOT_LISTENING, "names the shapes as the MOD row reads them: ${Copy.FOLLOW_NOT_LISTENING}")
        assertTrue("LISTEN · MIC" in Copy.FOLLOW_NOT_LISTENING && "APP AUDIO" in Copy.FOLLOW_NOT_LISTENING && "SHELF" in Copy.FOLLOW_NOT_LISTENING, "FOLLOW's route is either button on SHELF: ${Copy.FOLLOW_NOT_LISTENING}")
        assertTrue("ARM" !in Copy.FOLLOW_NOT_LISTENING, "nothing on any screen is called ARM: ${Copy.FOLLOW_NOT_LISTENING}")
        // The length is the voice's own, not RING's four-second ask: a ring
        // that only just started gives less, and the ceiling law below holds
        // this line to what was kept.
        assertEquals("FROZE 4.0 S OF THE MIC ON THE SURFACE. PAD ◄ ► BRINGS THE PAD BACK.", Copy.ringFrozen(4f, appAudio = false))
        assertEquals("FROZE 1.5 S OF APP AUDIO ON THE SURFACE. PAD ◄ ► BRINGS THE PAD BACK.", Copy.ringFrozen(1.5f, appAudio = true))
        assertTrue("OPEN CUT" in Copy.CHOP_NO_HITS, "GRID is inside the closed CUT box: ${Copy.CHOP_NO_HITS}")
        // BOUNCE on a stopped GROOVE used to just dim with no reason
        // (wiring review finding 8) - tapping it now names the control
        // that actually resolves it, the same PLAY button GROOVE draws.
        assertTrue("PLAY" in Copy.GROOVE_BOUNCE_NEEDS_PLAY, "names the control that unblocks BOUNCE: ${Copy.GROOVE_BOUNCE_NEEDS_PLAY}")
        assertEquals("BANK B · 16 FREE", Copy.catchBank('B', 16))
        assertEquals("NO GROOVE: NO BEAT HEARD - THE EAR FINDS HITS, NOT TONES.", Copy.grooveRefused("no beat heard - the ear finds hits, not tones."))
        assertEquals("BREAK FOUND AT 1:12-1:20. IN AND OUT ARE SET. INSTANT KIT IS ONE TAP AWAY.", Copy.dug("1:12", "1:20"))
        // SEND TO PADS reports the real slice count.
        assertEquals("7 SLICES ON THE PADS. CHOKE GROUP SET.", Copy.sentToPads(7, chokeSet = true))
        assertEquals("3 SLICES ON THE PADS.", Copy.sentToPads(3, chokeSet = false))
        // A single slice sent to the pads is singular too — sentToPads used
        // to say "1 SLICES" (and so did instantKit, which routes through it).
        assertEquals("1 SLICE ON THE PADS.", Copy.sentToPads(1, chokeSet = false))
        assertEquals("ONE TAP, THE WHOLE TAPE. 1 SLICE ON THE PADS.", Copy.instantKit(1, chokeSet = false, wholeTape = true))
    }

    /**
     * [Copy.countOf] itself: the general form every counted-noun toast in
     * this file now routes through. `n == 1` is the only case that picks
     * the singular; `n == 0` reads as plural, same as English ("0 BARS",
     * never "0 BAR") — asserted here so a future "simplification" to
     * `n <= 1` doesn't sneak back in.
     */
    @Test
    fun `countOf picks the singular only at exactly one`() {
        assertEquals("1 BAR", Copy.countOf(1, "BAR", "BARS"))
        assertEquals("2 BARS", Copy.countOf(2, "BAR", "BARS"))
        assertEquals("0 BARS", Copy.countOf(0, "BAR", "BARS"), "zero is plural, same as English")
        assertEquals("-1 BARS", Copy.countOf(-1, "BAR", "BARS"), "a count can't go negative, but this is not the guard for that")
    }

    @Test
    fun `boot sequence ends ready`() {
        assertEquals("READY.", Copy.BOOT_LINES.last())
    }

    /**
     * HELP's one job is to be true, and the September UAT caught it being
     * false: twenty hardcoded lines in `StubScreen.kt` calling the app "the
     * M0 skeleton" and promising capture "arrives with M1", long after it
     * shipped. It had no test because it lived in a Composable, so nothing
     * failed as the app grew past it.
     *
     * These are the two ways it went wrong, now nailed down: a milestone
     * tag anywhere in the text, and the loop not naming the four screens a
     * user actually walks through. Moving or renaming one of those screens
     * now breaks a test rather than quietly making HELP lie.
     */
    @Test
    fun `help describes the app rather than a milestone`() {
        val all = (Copy.HELP_LOOP + Copy.HELP_MORE + Copy.HELP_LOOP_HEADER + Copy.HELP_MORE_HEADER)
            .joinToString(" ")
        for (tag in listOf("M0", "M1", "M2", "M3", "M4", "M5", "SKELETON", "ARRIVES WITH", "NOT RECORDED")) {
            assertFalse(all.contains(tag), "HELP still talks about the build, not the app: found \"$tag\"")
        }
        // The loop, in the order a user walks it.
        val steps = Copy.HELP_LOOP.map { it.removePrefix("· ").substringBefore(" ") }
        assertEquals(listOf("TAPE", "CHOP", "KIT", "EXPORT"), steps)
        // Every line is a line, not a paragraph: HELP renders on a phone.
        for (line in Copy.HELP_LOOP + Copy.HELP_MORE) {
            assertTrue(line.startsWith("· "), "HELP lines are bulleted: $line")
            assertTrue(line.length <= 72, "HELP line is too long for the panel (${line.length}): $line")
        }
    }

    /**
     * Finding 14's fix: the TREATMENT card has to say it is working, and
     * say which treatment, or a 1.8-second wait reads as a dead screen. The
     * ellipsis is the part that carries "still going" - a header that
     * merely renamed itself would look finished.
     *
     * Fed through [PadSheet.displayLabel] the way the card feeds it, which
     * is not the same string as the segment id: the segment is "ETERNAL",
     * what a user reads on the chip is "DRONE". Testing the id would have
     * pinned a header no one ever sees.
     */
    @Test
    fun `the treatment header names what it is working on`() {
        val segment = "ETERNAL"
        val label = PadSheet.displayLabel(segment)
        assertTrue(label != segment, "this test is only meaningful while the two differ: $label")

        val busy = Copy.treatmentBusy(label)
        assertTrue(busy.contains(label), busy)
        assertFalse(busy.contains(segment), "the header carries the chip's word, not the segment id: $busy")
        assertTrue(busy.endsWith("…"), "a progress line has to look unfinished: $busy")
        assertTrue(busy.startsWith("TREATMENT"), "it replaces the card's own header: $busy")
        // Distinguishable from the idle header, which is the whole point.
        assertTrue(busy != "TREATMENT", busy)
        // Every segment's label has to fit, not just this one.
        for (seg in PadSheet.ROWS.flatten()) {
            val line = Copy.treatmentBusy(PadSheet.displayLabel(seg))
            assertTrue(line.length <= 40, "one line on the card: $line (${line.length})")
        }
    }

    /**
     * Finding 12. The empty shelf tells the user to make a kit for the snip
     * they just captured; both ways into that kit have to say what happens
     * next, and a full kit has to say something different from one with room.
     */
    @Test
    fun `a landing snip is told where to go, or that there is no room`() {
        val room = Copy.snipLanding(hasEmptyPad = true)
        val full = Copy.snipLanding(hasEmptyPad = false)
        assertTrue(room != full, "a full kit and an empty one cannot read the same")
        assertTrue(room.contains("PAD"), room)
        assertTrue(room.contains("LONG-PRESS"), "it names the gesture, which nothing else on that screen does: $room")
        assertTrue(full.contains("FULL"), full)
        for (line in listOf(room, full)) {
            assertEquals(line.uppercase(Locale.ROOT), line, "TapeOS shouts: $line")
            assertTrue(line.endsWith("."), "a toast lands on a full stop: $line")
        }
    }

    /**
     * Finding 18. Re-exporting to the same destination used to overwrite
     * silently — on someone's SD card, the one write worth pausing on. The
     * pause is only useful if it names the thing at risk and says what the
     * next tap does; "are you sure?" teaches neither.
     */
    @Test
    fun `the overwrite warning names what is at risk and what the next tap does`() {
        val line = Copy.dubWouldOverwrite("MY KIT.XPN")
        assertTrue(line.contains("MY KIT.XPN"), "it names the thing, not \"a file\": $line")
        assertTrue(line.contains("DUB AGAIN"), "it says what the next tap does: $line")
        assertEquals(line.uppercase(Locale.ROOT), line, "TapeOS shouts: $line")
        assertTrue(line.endsWith("."), "a toast lands on a full stop: $line")
        // It must not read like something already happened - nothing has been
        // written when this appears.
        assertFalse(line.contains("DONE"), "nothing was written yet: $line")
        assertTrue(line != Copy.DUB_DONE && line != Copy.DUB_FAILED, "distinct from both outcomes: $line")
    }

    /**
     * Finding 19. COMMIT and INSTANT KIT sit side by side under the deck and
     * used to disagree about an empty selection: COMMIT refused in words
     * while INSTANT KIT quietly chopped the whole tape, and the user was
     * never told which they got.
     *
     * INSTANT KIT still takes the whole tape — that is the right default for
     * a one-tap button, and the silence about it was the bug — so what this
     * pins is that the two scopes now read differently, and that the
     * selection reading borrows COMMIT's own words for the markers.
     */
    @Test
    fun `INSTANT KIT names what it chopped`() {
        val whole = Copy.instantKit(8, chokeSet = false, wholeTape = true)
        val selected = Copy.instantKit(8, chokeSet = false, wholeTape = false)
        assertTrue(whole != selected, "the whole tape and a selection cannot read the same: $whole")
        assertTrue(whole.contains("WHOLE TAPE"), "it says it took everything: $whole")
        // COMMIT's refusal is "SET IN + OUT FIRST"; the same two markers are
        // named here, so the pair of buttons speaks one vocabulary.
        assertTrue(selected.contains("IN + OUT"), "it names the markers COMMIT asks for: $selected")
        assertTrue(Copy.COMMIT_NEEDS_SELECTION.contains("IN + OUT"), Copy.COMMIT_NEEDS_SELECTION)

        for (line in listOf(whole, selected)) {
            // The count survives either reading - it is the part users act on.
            assertTrue(line.contains("8 SLICES"), "the slice count is still there: $line")
            assertEquals(line.uppercase(Locale.ROOT), line, "TapeOS shouts: $line")
            assertTrue(line.endsWith("."), "a toast lands on a full stop: $line")
        }
        assertTrue(
            Copy.instantKit(8, chokeSet = true, wholeTape = true).endsWith("CHOKE GROUP SET."),
            "the choke note still rides on the end, whichever scope it was",
        )
    }

    /**
     * Finding 13. NONE takes a treatment back off now, so it has three
     * things to say — it landed, the ghosts are in the way, or the bin
     * never held the take — and the three have to be told apart. Two of
     * them are `const`s the reflective law already shouts at; [Copy.unTreated]
     * is a function, which that law cannot see, so it is checked here.
     */
    @Test
    fun `the un-treat says which of its three answers it is giving`() {
        val landed = Copy.unTreated("KICK")
        assertTrue(landed.contains("KICK"), "it names the pad it gave back: $landed")
        assertTrue(landed.contains("BIN"), "the bin is where the take came from, and the user has to learn that: $landed")
        assertEquals(landed.uppercase(Locale.ROOT), landed, "TapeOS shouts: $landed")
        assertTrue(landed.endsWith("."), "a toast lands on a full stop: $landed")

        // The three answers are three sentences, not one sentence three times.
        val answers = listOf(landed, Copy.RETREAT_REFUSED, Copy.UNTREAT_NOT_BINNED)
        assertEquals(answers.size, answers.toSet().size, "a refusal that reads like a success teaches nothing: $answers")
        // The two refusals say what is in the way, and neither claims a restore.
        assertTrue(Copy.RETREAT_REFUSED.contains("GHOSTS"), Copy.RETREAT_REFUSED)
        assertTrue(Copy.UNTREAT_NOT_BINNED.contains("BIN"), Copy.UNTREAT_NOT_BINNED)
        assertFalse(Copy.UNTREAT_NOT_BINNED.contains("BACK"), "nothing came back: ${Copy.UNTREAT_NOT_BINNED}")
    }

    /**
     * Finding 23. SETUP held three settings and answered none of the
     * questions a user actually arrives with. These are the three the app
     * can answer from what it already knows — the format EXPORT will open
     * on, where the files are, whether a card is held — rather than from
     * settings invented to fill a screen.
     */
    @Test
    fun `SETUP answers its three questions without pretending to own them`() {
        // The format readout says where it is changed, because SETUP is not
        // a second picker and must not read like one.
        assertTrue(Copy.SETUP_FORMAT_NOTE.contains("EXPORT"), Copy.SETUP_FORMAT_NOTE)
        assertTrue(Copy.SETUP_FORMAT_NOTE.endsWith("."), "a sentence the screen says: ${Copy.SETUP_FORMAT_NOTE}")
        assertTrue(Copy.SETUP_CARD_NONE.endsWith("."), Copy.SETUP_CARD_NONE)

        // The headings are labels above a readout, so they do not end in a
        // full stop - the same register as the legends.
        val headings = listOf(
            Copy.SETUP_FORMAT_HEADING, Copy.SETUP_WHERE_HEADING, Copy.SETUP_CARD_HEADING, Copy.SETUP_RATE_HEADING,
        )
        for (h in headings) {
            assertFalse(h.endsWith("."), "furniture, not a sentence: $h")
            assertEquals(h.uppercase(Locale.ROOT), h, "TapeOS shouts: $h")
            assertTrue(h.length <= 24, "a heading on a narrow screen: $h")
        }
        // Four different questions must read as four different questions.
        assertEquals(headings.size, headings.toSet().size, "$headings")

        // THE RATE is the one row here the app cannot be wrong about and
        // had never said: the readout is derived from the writer's own
        // constant (see SnipStoreTest), and the note points at the place a
        // rate IS adjustable, since "sample rate" is the phrase somebody
        // arrives with - the persona review's P4.2.
        assertEquals("44.1 KHZ", Copy.SETUP_RATE)
        assertTrue(Copy.SETUP_RATE_NOTE.endsWith("."), Copy.SETUP_RATE_NOTE)
        assertTrue(
            Copy.SETUP_RATE_NOTE.contains("TIME MACHINE"),
            "the note sends a reader to the rate they can actually turn: ${Copy.SETUP_RATE_NOTE}",
        )

        // The path is handed through unchanged: a user hunting on a cable
        // needs the real thing to look for, not a description of it.
        assertEquals("/storage/emulated/0/Android/data/x/files/exports", Copy.setupWhere("/storage/emulated/0/Android/data/x/files/exports"))

        // One card, one name. EXPORT's DESTINATION row and SETUP both go
        // through Copy.cardName, so the two screens cannot name it two ways -
        // and it is string work on the uri rather than a DocumentFile lookup,
        // which would mean a dependency and a disk touch for a label.
        assertEquals("Kits", Copy.cardName("primary:Music/Kits"))
        assertEquals("Kits", Copy.cardName("Music/Kits"))
        assertEquals("SDCARD", Copy.cardName("SDCARD"))
        assertEquals("CARD", Copy.cardName("1A2B-3C4D:"), "a volume root has no folder to name")
        assertEquals("CARD", Copy.cardName(null), "and neither has a uri with no segment")
        assertEquals("CARD", Copy.cardName(""))

        // A held card is named, and shouts like everything else.
        val held = Copy.setupCardHeld("Untitled SD card")
        assertTrue(held.contains("UNTITLED SD CARD"), held)
        assertEquals(held.uppercase(Locale.ROOT), held, held)
        assertTrue(held != Copy.SETUP_CARD_NONE)
    }

    /**
     * Finding 17. Every creation door auto-names a kit, so RENAME is the only
     * place a name is ever typed — and it sits behind a hold on the row that
     * nothing on screen mentioned. The legend has to name the gesture and the
     * two things it reveals, in the same words the row's own TalkBack
     * long-press label uses, so the sighted and the spoken app agree.
     */
    @Test
    fun `the shelf legend names the hold and what it reveals`() {
        assertTrue(Copy.SHELF_LEGEND.contains("HOLD"), "it names the gesture: ${Copy.SHELF_LEGEND}")
        assertTrue(Copy.SHELF_LEGEND.contains("KIT"), "and what to hold: ${Copy.SHELF_LEGEND}")
        assertTrue(Copy.SHELF_LEGEND.contains("RENAME"), "naming is the finding's own subject: ${Copy.SHELF_LEGEND}")
        assertTrue(Copy.SHELF_LEGEND.contains("DELETE"), "the hold reveals both, so both are named: ${Copy.SHELF_LEGEND}")
        assertEquals(Copy.SHELF_LEGEND.uppercase(Locale.ROOT), Copy.SHELF_LEGEND, "TapeOS shouts")
        assertTrue(Copy.SHELF_LEGEND.length <= 52, "one line under the shelf: ${Copy.SHELF_LEGEND.length}")
        // Furniture, not a toast - it never lands on a full stop, and the
        // reflective law exempts it for exactly that reason.
        assertFalse(Copy.SHELF_LEGEND.endsWith("."), "a permanent label is not a sentence: ${Copy.SHELF_LEGEND}")
        // Two legends on two screens must not read as the same instruction.
        assertTrue(Copy.SHELF_LEGEND != Copy.PAD_SHEET_LEGEND)
    }

    /**
     * The legend that replaced the expiring toast (UAT findings 4 and 5).
     * It has to name the gesture, because it is the only thing on screen
     * that does — the toast it backstops can be dismissed forever.
     */
    /**
     * The three labels J18's rename missed, found by extending
     * `ConventionTest`'s own GROOVE law from `PROG_NAMES` to the whole
     * file while writing `GrooveScreenTest`.
     *
     * J18 took A–E off the program row because the letter is an argument
     * to `GrooveProgram.compute` and nothing a player can find on the
     * hardware. Three strings kept it: the step editor's header (`STEP
     * EDIT — PROG E`), RECORD's status line (`OVERDUBBING ONTO PROG A`)
     * and the editor's footer (`B–D STAY DERIVED FROM A`, on a screen
     * where nothing is called A, B, C or D). The law stops a fourth; this
     * holds down what the three say now.
     */
    @Test
    fun `GROOVE names its programs the same way wherever it says them`() {
        // The row's own words, which these three now use.
        assertTrue(Copy.STEP_EDIT_TITLE.contains("YOURS"), Copy.STEP_EDIT_TITLE)
        assertTrue(Copy.GROOVE_OVERDUBBING.contains("CAPTURED"), Copy.GROOVE_OVERDUBBING)

        // Shorter than the string it replaces: the header shares its row
        // with DONE and has about 260dp of a 360dp phone to live in.
        assertTrue(
            Copy.STEP_EDIT_TITLE.length <= "STEP EDIT — PROG E".length,
            "the header got longer, and it did not have the room: ${Copy.STEP_EDIT_TITLE}",
        )

        // The count is its own line under the header, so it says the
        // number and nothing else.
        assertEquals("2 OF 3", Copy.stepEditOf(2, 3))

        // The footer says what editing yours does NOT do, in the words
        // FORKED_TO_E already uses for the same fact.
        val footer = Copy.stepEditFooter(16, "CAPTURED")
        assertTrue(footer.contains("16 STEPS"), footer)
        assertTrue(footer.contains("FORKED FROM CAPTURED"), footer)
        assertTrue(footer.contains("THE OTHER FOUR"), "the house phrase, as FORKED_TO_E says it: $footer")
        assertTrue(Copy.FORKED_TO_E.contains("THE OTHER FOUR"), Copy.FORKED_TO_E)
        assertEquals("1 STEP", Copy.stepEditFooter(1, "X").substringBefore(" ·"))

        // And none of the three says a letter again.
        for (line in listOf(Copy.STEP_EDIT_TITLE, Copy.GROOVE_OVERDUBBING, footer)) {
            assertFalse(
                Regex("""PROG [A-E]\b|\b[A-E][-–—][A-E]\b""").containsMatchIn(line),
                "a PROG letter is back on GROOVE: $line",
            )
        }
    }

    @Test
    fun `the pad sheet legend names the gesture`() {
        assertTrue(Copy.PAD_SHEET_LEGEND.contains("HOLD"), Copy.PAD_SHEET_LEGEND)
        assertTrue(Copy.PAD_SHEET_LEGEND.contains("PAD"), Copy.PAD_SHEET_LEGEND)
        assertTrue(Copy.PAD_SHEET_LEGEND.length <= 52, "one line under the grid: ${Copy.PAD_SHEET_LEGEND.length}")
    }

    /**
     * `Copy` constants that are legitimately not full-stop toasts — button
     * labels, tile subtitles, chip text, "…BUSY" progress indicators, and
     * one hidden-egg unlock name — so the "every line lands on a full
     * stop" law doesn't apply to them. Explicit and commented per the same
     * reasoning as the ceiling law below: a rule loose enough to guess
     * these automatically (e.g. "ends in an ellipsis") is a rule someone
     * disables the first time it's wrong, so it's a reviewed list instead.
     * NOT ONE of these may also appear in [legacyHandListedToasts] below —
     * that's the mechanical proof this reflective law didn't quietly
     * exempt anything the old hand list used to check.
     */
    private val notASentence = setOf(
        "TILE_LABEL_IDLE", "TILE_LABEL_ARMED", "TILE_SUBTITLE_IDLE", "TILE_SUBTITLE_ARMED",
        "COMMIT_NEEDS_SELECTION", "CAPTURE_BLOCKED_BUTTON",
        "IMPORT_BUSY", "PACKING_BUSY", "LANDING_BUSY", "READ_GROOVE_BUSY", "DIG_BUSY", "FEEL_BUSY", "CHART_BUSY", "BREEDING_BUSY",
        // SNIPS' SHARE, while the snip is copied into the share cache under
        // a readable name — like every other *_BUSY here, an overlay line
        // rather than a landing.
        "SHARE_BUSY",
        "ARRANGE_MIXING", "XRAY_BUSY", "DOUBLES_BUSY",
        "CHOP_ALL_BUSY",
        // DUST ALL's busy overlay line, like every other *_BUSY above.
        "DUSTING_BUSY", "CHOP_BENCH_BUSY",
        // LOOP's bounce, while the render runs — same shape as every other
        // *_BUSY above: a button's label, not a landing.
        "LOOP_BOUNCE_BUSY",
        // SNAP's FIELD and CLOUD, while the photo is cut into grains and
        // while the field is granulated — the same shape as every other
        // *_BUSY above: an overlay line, not a landing.
        "SNAP_FIELD_BUSY", "SNAP_CLOUD_BUSY",
        // BACK ONTO's busy overlay line, like every other *_BUSY above.
        "RETRIM_BUSY",
        // CATCH A HIT's busy line while the hits are found, and its grid's
        // one-word button — a label, not a landing.
        "CATCH_BUSY", "CATCH_DONE_BUTTON",
        // HUM THE CHOP's readout while the hum runs.
        "HUM_BUSY",
        // The HITS stepper's own busy readout.
        "HITS_BUSY",
        "OUTSIDE_LISTENING", "ROOM_FORGET_BUSY", "ROOM_RESTORE_BUSY", "ROOM_BIN_EMPTY_BUSY", "KIT_DELETE_BUSY", "KIT_RENAME_BUSY",
        "CHIP_NOT_SURE", "CHIP_OVERRIDDEN",
        "EXPORT_SAVED_TO", "EXPORT_SHARE_LABEL", "CARD_NONE", "CARD_PICKED",
        "SHELF_SORT_RECENT", "SHELF_SORT_ALPHA",
        // BREED's button label (glyph sweep): now a constant destination
        // name, same shape as CAPTURE_BLOCKED_BUTTON above — furniture on
        // a button, not a line the app says once. The pad-count readout
        // that used to live here moved to Copy.breedSubtitle, a function,
        // which this reflective law doesn't reach either.
        "BREED_BUTTON",
        // The shelf filter's chip (finding 16) is the sort chip's twin and
        // sits beside it, so it is furniture under the same rule. The
        // filtered labels come from Copy.shelfFilter(), a function, which
        // this reflective law does not reach - ShelfFilterTest holds those.
        "SHELF_FILTER_ALL",
        // Permanent on-screen furniture, not toasts: the two legends that sit
        // under KIT's grid and the kit shelf's own list for as long as those
        // screens are open, and HELP's two section headings. ROOMS' third
        // legend, Copy.ROOMS_LEGEND ("HOLD A ROOM TO FORGET IT · THE BIN
        // KEEPS IT 30 DAYS"), reads without a full stop for the same reason -
        // a copy-consolidation pass brought it in from `:app`, where it used
        // to live inline. A label on the furniture is not a line the app
        // says to you once and takes away, so it does not end in a full stop
        // - and every legend must be added here when it is written, or the
        // shouting law will ask it to become a sentence.
        // PAD_VELOCITY_LEGEND rides with PAD_SHEET_LEGEND: it is the second
        // line of the same furniture under KIT's grid, on screen
        // permanently, not a sentence the app says once.
        // The two J10 door labels are buttons, not sentences - "CHOP ▸"
        // carries its own arrow the way every other door label does.
        "CAPTURE_OFFER_DOOR", "KIT_OFFER_DOOR",
        // GROOVE's and ORBIT's BOUNCE buttons, moved out of inline
        // literals so one law can hold all three bounces to one
        // destination word (prior #25). Labels on buttons, not sentences.
        "GROOVE_BOUNCE_BUTTON", "ORBIT_BOUNCE_BUTTON",
        "PAD_SHEET_LEGEND", "PAD_VELOCITY_LEGEND", "SHELF_LEGEND", "HELP_LOOP_HEADER", "HELP_MORE_HEADER", "ROOMS_LEGEND",
        // The empty shelf's loop line (finding 3) is a row of tab names,
        // not a line the app says - it reads TAPE > CHOP > KIT > EXPORT.
        // FIRST_RUN_LOOP_NOTE, the sentence under it that says what the
        // four words mean, is NOT here: it keeps its full stop.
        "FIRST_RUN_LOOP",
        // EXPORT's DESTINATION legend joins them (finding 21): same rule,
        // same register - a line that stays under the row it explains.
        "EXPORT_CARD_LEGEND",
        // SETUP's three section headings and the "nothing picked yet" it
        // shows in place of a format - labels above a readout, in the same
        // register as the legends above. SETUP_FORMAT_NOTE and
        // SETUP_CARD_NONE are NOT here: both are sentences the screen says
        // to you, and both keep their full stops.
        "SETUP_FORMAT_HEADING", "SETUP_WHERE_HEADING", "SETUP_CARD_HEADING", "SETUP_FORMAT_NONE",
        // THE RATE, the fourth of the same kind, and the readout under it -
        // "44.1 KHZ" is a value, not a sentence, exactly as the format
        // readout above it is. SETUP_RATE_NOTE is NOT here: it is a
        // sentence the screen says, and it keeps its full stop.
        "SETUP_RATE_HEADING", "SETUP_RATE",
        // The step editor's header and RECORD's own status line - a title
        // over a grid and a label saying what is happening right now,
        // neither of them a toast said once. Both arrived with the sweep
        // that found the two PROG letters J18's rename had missed.
        "STEP_EDIT_TITLE", "GROOVE_OVERDUBBING",
        // GRAIN FIELD's own busy line while the tape is read into its grid -
        // furniture, like every other `…`-suffixed busy line above.
        "GRAIN_FIELD_LISTENING",
        // EVIL TWINS' and INSTANT KIT's own busy overlays - the same
        // `…`-suffixed shape as every other *_BUSY constant above.
        "EVIL_TWINS_BUSY", "INSTANT_KIT_BUSY",
        // The status bar's third cell resting label with no kit open - a
        // standing chip value like CARD_NONE above, not a toast said once.
        "NO_KIT_STATUS",
    )

    /**
     * `Copy` constants whose lowercase "s" is a deliberate seconds-unit
     * suffix (matching this codebase's own "10 MIN" / "8s" convention,
     * e.g. `tapeTruncated`/`imported`'s templated output) rather than a
     * violation of "TapeOS shouts". Still checked for the full-stop law.
     */
    private val allowedLowercaseUnit = setOf("SNIPPED", "TILE_SUBTITLE_ARMED")

    /**
     * The exact ~30 names the old hand-listed `listOf(Copy.A, Copy.B, ...)`
     * checked, kept only so the test below can assert none of them ended
     * up in an exclusion set above — proof this change swapped the
     * mechanism (hand list -> reflection) without relaxing which strings
     * are actually held to the law.
     */
    private val legacyHandListedToasts = setOf(
        "MELODIC_ON", "KEY_OFF", "TEACHING_ON", "TEACHING_OFF",
        "BANK_B_LIT", "TWINS_REROLLED", "BACK_FROM_BIN", "BIN_EMPTIED",
        // "HUMANIZED" dropped (feel axis, task 6): HUMANIZE ⚄ is gone, so
        // Copy.HUMANIZED no longer exists for reflection to find — this was
        // the control it described being removed, not a relaxation of the
        // law itself.
        "FORKED_TO_E", "BAR_WIPED", "GHOSTS_ON",
        // "INSTRUMENT_MADE" and "PAD_MADE" dropped (MAKE PAD fresh names):
        // both said "ON THE SHELF." with no name, which was fine while a
        // second press overwrote the first and there was only ever one thing
        // to point at. Presses now land beside each other, so the line names
        // what landed and is built per call - `Copy.madeNamed(what, made)`.
        //
        // NOT a relaxation, and worth being precise about why, because it
        // looks like one: this law reflects over `declaredFields`, so a `fun`
        // is invisible to it and deleting these two really would have taken
        // their text out of coverage. The shout and full-stop checks moved
        // with the text, into the explicit assertions on `madeNamed` in
        // `the interpolated lines name what they acted on` - which is this
        // file's established mechanism for templated copy, the same one
        // `treated`, `keyed` and `loopBounced` are held by. Delete those
        // assertions and the text is unchecked again.
        "NO_PITCH", "RETREAT_REFUSED",
        "TAKES_BIN_RULE", "TEACH_CONSENT", "SNAPPED",
        "TAKES_EMPTY", "BIN_EMPTY_STATE", "BIN_ITEM_GONE", "KIT_WONT_OPEN",
        "UNMUTATED", "MUTATE_NEEDS_ONE", "CRATE_EMPTY",
        "SCULPTED", "STRETCHED", "FROZEN", "PAD_TOO_SHORT", "PAD_TOO_LONG",
        "IN_KEY_NONE", "IN_KEY_NEEDS_KEY", "TAPE_TOO_BIG",
    )

    /**
     * Every legend teaches a gesture, or it is not a legend.
     *
     * The legends exist because the actions behind them are long presses
     * nothing on screen mentions - PAD SHEET, kit RENAME, and now EXPORT's
     * forget-this-card, which is the only door to forgetting a card in the
     * whole app (September UAT, findings 4, 5, 17 and 21). A legend that
     * describes the feature but never names the hold would sit there
     * looking like a fix while teaching nobody the one thing they cannot
     * guess.
     *
     * Reflective rather than a list, so a legend written next year is held
     * to it without anyone remembering to come back here.
     */
    @Test
    fun `every legend names the gesture it teaches`() {
        val legends = copyStringConstants().filterKeys { it.endsWith("_LEGEND") }
        assertTrue(
            legends.size >= 3,
            "expected at least the three legends this law was written for, found ${legends.keys}",
        )
        // The gestures a legend can be teaching.
        //
        // This law read `"HOLD" in value` until a legend arrived for a
        // gesture that is not a hold. That was not the law being wrong — its
        // own message says the point is naming *the gesture*, and HOLD was
        // simply the only invisible one the app had. TAP joined it when the
        // pad grid started reading *where* it was tapped (J37): equally
        // unguessable by looking, and taught the same way.
        //
        // A list rather than dropping the check: a legend that names no
        // gesture at all is still the failure this law exists to catch, and
        // widening the list stays a deliberate act.
        val gestures = listOf("HOLD", "TAP")
        for ((name, value) in legends) {
            assertTrue(
                gestures.any { it in value },
                "Copy.$name is a legend but names none of $gestures: '$value' — " +
                    "a legend that does not name the gesture teaches nobody the thing they cannot guess. " +
                    "If it teaches a genuinely new gesture, add that verb to the list above rather than " +
                    "removing the check.",
            )
        }
    }

    @Test
    fun `every Copy string constant shouts and stops (reflective)`() {
        val allStrings = copyStringConstants()
        assertTrue(
            allStrings.size > 50,
            "reflection over Copy.javaClass.declaredFields found only ${allStrings.size} String constants — " +
                "expected well over 50. A build/classpath change may be hiding fields from plain reflection, " +
                "which would make this law pass by checking nothing; fix the reflection, don't lower this bound.",
        )

        // Anti-relaxation: every exclusion above must be an addition to
        // coverage the old hand list never had, never a carve-out from it.
        for (legacy in legacyHandListedToasts) {
            assertTrue(legacy in allStrings, "Copy.$legacy (checked by the old hand-listed test) no longer exists via reflection — did it get renamed without updating this legacy set?")
            assertFalse(legacy in notASentence, "Copy.$legacy was checked by the original hand-listed law and must not be exempted from the full-stop law now — that would be a relaxation, not a mechanism change.")
            assertFalse(legacy in allowedLowercaseUnit, "Copy.$legacy was checked by the original hand-listed law and must not be exempted from the shout law now — that would be a relaxation, not a mechanism change.")
        }
        // Every entry in the exclusion sets must correspond to a real
        // field — a stale exclusion (the constant was renamed or removed)
        // would otherwise sit there doing nothing, forever.
        for (name in notASentence) {
            assertTrue(name in allStrings, "Copy.$name is listed in notASentence but reflection found no such String constant — remove the stale exclusion (or fix the rename it's tracking).")
        }
        for (name in allowedLowercaseUnit) {
            assertTrue(name in allStrings, "Copy.$name is listed in allowedLowercaseUnit but reflection found no such String constant — remove the stale exclusion (or fix the rename it's tracking).")
        }

        for ((name, value) in allStrings) {
            if (name !in allowedLowercaseUnit) {
                assertEquals(value.uppercase(), value, "TapeOS shouts: Copy.$name = '$value' has a lowercase letter — if it's the deliberate seconds-unit 's' (like SNIPPED's '60s'), add Copy.$name to allowedLowercaseUnit with a comment; otherwise SHOUT IT.")
            }
            if (name !in notASentence) {
                assertTrue(value.endsWith("."), "every toast lands on a full stop: Copy.$name = '$value' — if this is genuinely a label/button/busy-indicator rather than a toast, add Copy.$name to notASentence with a comment explaining why; otherwise it needs a period.")
            }
        }
    }

    @Test
    fun `the interpolated lines name what they acted on`() {
        assertTrue(Copy.keySet("Am").startsWith("Am SET."), "the key leads its own toast")
        assertTrue(Copy.keySet("Am").endsWith("."), "and still lands on a full stop")
        assertEquals("1 PAD RETUNED INTO A MINOR. THE KICK IS UNTOUCHED.", Copy.inKey(1, "A MINOR"))
        assertEquals(
            "ONE TAP, YOUR IN + OUT. 8 SLICES ON THE PADS. CHOKE GROUP SET.",
            Copy.instantKit(8, chokeSet = true, wholeTape = false),
        )
        assertEquals("ONE TAP, THE WHOLE TAPE. 5 SLICES ON THE PADS.", Copy.instantKit(5, chokeSet = false, wholeTape = true))
        assertEquals("3 PADS RETUNED INTO A MINOR. THE KICK IS UNTOUCHED.", Copy.inKey(3, "A MINOR"))
        assertTrue(Copy.takeRestored("T3").startsWith("T3 RESTORED."), "the take leads its own toast")
        assertTrue(Copy.feelRolled(4).startsWith("FEEL #4 ROLLED."), "the seed leads its own toast")
        assertTrue(Copy.feelRolled(4).endsWith("."), "and still lands on a full stop")
        assertTrue(
            Copy.treated("CRUSH", "A02").startsWith("CRUSH ON A02."),
            "the treatment and the pad both lead their own toast",
        )
        assertTrue(Copy.treated("CRUSH", "A02").endsWith("."), "and still lands on a full stop")
        // The stacked landing: same opening, then the honest difference and the way back.
        val stacked = Copy.treatedStacked("CRUSH", "A02")
        assertTrue(stacked.startsWith("CRUSH ON A02,"), stacked)
        assertTrue("NO ORIGINAL IN THE BIN" in stacked && "VERSIONS" in stacked, stacked)
        assertEquals(stacked.uppercase(), stacked, "shouts")
        assertTrue(stacked.endsWith("."))
        assertEquals("TUNE ON A02, IN C MAJOR. ORIGINAL SLEEPS IN THE BIN.", Copy.keyed("TUNE", "A02", "C MAJOR"))
        assertEquals("A02 DRIFTED TOWARD Other:B03. ORIGINAL SLEEPS IN THE BIN.", Copy.drifted("A02", "Other:B03"))
        // Both engines own a HAT_CLOSED and a SNARE, so the engine is
        // part of the answer rather than a prefix (DE-SAMPLE searched
        // THUMP alone until SKIN joined it).
        assertEquals(
            "A03 IS A THUMP HAT CLOSED PATCH NOW, 0.12 AWAY. ORIGINAL SLEEPS IN THE BIN.",
            Copy.desampled("A03", "THUMP", "HAT_CLOSED", 0.123f),
        )
        assertEquals("NO PATCH IS NEAR. THE CLOSEST IS A SKIN SNARE, 0.61 AWAY.", Copy.desampleFar("SKIN", "SNARE", 0.61f))
        assertEquals("NOT A NOTE: A KICK IS A DRUM, NOT A NOTE.", Copy.notANote("a kick is a drum, not a note"))
        assertTrue(
            Copy.mutated("SPLICE", "A01", "A03").startsWith("SPLICE: A01 × A03."),
            "the move and both parents lead their own toast",
        )
        assertTrue(Copy.mutated("SPLICE", "A01", "A03").endsWith("."), "and still lands on a full stop")
        // "NOT DRAWN" was accurate before GrooveScreen.kt's NeedleRoll Fix 3
        // (live-record follow-ups): an off-lane note now draws too, in its
        // own sixth OTHER column, so the old wording would be an outright
        // false claim on screen — this assertion tracks the corrected text,
        // not a relaxation of the law itself.
        assertEquals("+3 OFF-LANE — HEARD, EXPORTED, DRAWN UNDER OTHER", Copy.offLane(3), "the count leads its own line")
        assertTrue(Copy.offLane(1).uppercase() == Copy.offLane(1), "TapeOS shouts here too")
        // LOOP: the snip, the track it landed on, and the block count that
        // decides how it drifts — all three named, and the counts passed in
        // rather than typed into the line (`:shell` cannot import `:loop`).
        assertEquals("BREAK IS ON TRACK 2, 3 BLOCKS LONG.", Copy.loopTrackFilled("BREAK", 2, 3))
        assertEquals("HIT IS ON TRACK 1, 1 BLOCK LONG.", Copy.loopTrackFilled("HIT", 1, 1))
        assertEquals("LONG IS ON TRACK 4. ONLY ITS FIRST 8 BLOCKS FIT.", Copy.loopTrackTruncated("LONG", 4, 8))
        // loopTrackFilled beside it already sang "1 BLOCK LONG" — truncated
        // said "1 BLOCKS FIT" regardless, the same one-sided fix as the rest.
        assertEquals("LONG IS ON TRACK 4. ONLY ITS FIRST 1 BLOCK FIT.", Copy.loopTrackTruncated("LONG", 4, 1))
        assertEquals("ALL 6 TRACKS ARE FULL. CLEAR ONE IN LOOP FIRST.", Copy.loopFull(6))
        // The bounce names what it rendered, and names the cycle too when the
        // two differ — a player told only "12 BARS" would read that as the
        // whole loop.
        assertEquals("12 BARS BOUNCED. IT IS IN SNIPS NOW.", Copy.loopBounced(12))
        assertEquals("64 BARS BOUNCED, OUT OF A 840 BAR CYCLE. IT IS IN SNIPS NOW.", Copy.loopBouncedPart(64, 840))
        // One bar is a legal bounce — the grid's default interval is one bar,
        // so a six-track grid of one-block chains bounces exactly this. Both
        // lines count the same thing and both have to say BAR for it.
        assertEquals("1 BAR BOUNCED. IT IS IN SNIPS NOW.", Copy.loopBounced(1))
        assertEquals("1 BAR BOUNCED, OUT OF A 6 BAR CYCLE. IT IS IN SNIPS NOW.", Copy.loopBouncedPart(1, 6))
        // The block readout: 1-based like everything else on the grid, and
        // its own line for a track nothing has been sent to — an app that
        // counts tracks and blocks from zero anywhere on screen is the one
        // thing this line exists to rule out.
        assertEquals("TRACK 1 · BLOCK 1 · KICK", Copy.loopBlock(1, 1, "KICK"))
        assertEquals("TRACK 6 · BLOCK 8 · KICK · PIECE 2/3", Copy.loopBlock(6, 8, "KICK · PIECE 2/3"))
        assertEquals("TRACK 2 · BLOCK 1 · NOTHING SENT HERE YET", Copy.loopBlock(2, 1, null))
        // MAKE PAD / MAKE INSTRUMENT: what landed, by name. These carry the
        // shout and full-stop checks that `Copy.PAD_MADE`/`INSTRUMENT_MADE`
        // used to get from the reflective law - see the note in
        // `legacyHandListedToasts`. The name matters: press three times and
        // three pads pile up, so a line that reads the same each time cannot
        // say which one is new.
        // SNIPS' SHARE. Ends on the same "PICK WHERE IT GOES." as kitPacked,
        // roomPacked and backedUp because it is the same moment, and says
        // READY rather than SENT because `ShareOut.send` only reports that
        // the chooser opened.
        assertEquals("BREAK IS READY TO SEND. PICK WHERE IT GOES.", Copy.snipReady("BREAK"))
        assertTrue(Copy.snipReady("BREAK").endsWith("."), "and still lands on a full stop")
        assertFalse("SENT" in Copy.snipReady("BREAK"), "the chooser opening is not the file leaving")
        assertEquals("PAD MADE — DRONE 2. ON THE SHELF.", Copy.madeNamed("PAD", "DRONE 2"))
        assertEquals("INSTRUMENT MADE — BELL. ON THE SHELF.", Copy.madeNamed("INSTRUMENT", "BELL"))
        assertTrue(Copy.madeNamed("PAD", "DRONE").endsWith("."), "and still lands on a full stop")
        val made = Copy.madeNamed("PAD", "DRONE 2")
        assertEquals(made.uppercase(), made, "TapeOS shouts here too")
    }

    @Test
    fun `tapeTruncated matches the import cap's own truncation phrasing`() {
        assertEquals("FIRST 10 MIN KEPT - THE TAPE IS ONLY SO LONG.", Copy.tapeTruncated(600f))
        assertEquals("FIRST 8s KEPT - THE TAPE IS ONLY SO LONG.", Copy.tapeTruncated(8.2f))
        // Same tail as SnipStore.import's own truncation line — one voice
        // for "a cap cut this tape's tail", not two competing ones.
        assertTrue(Copy.imported(180f, truncated = true).endsWith(Copy.tapeTruncated(180f)))
    }

    // ==================== Law: a kept-content ceiling is never stated as fact ====================

    /**
     * Matches "LAST <n>s" / "LAST <n> MIN" — a claim that the app is
     * holding (or will hold) a fixed trailing window, stated as settled
     * fact. That's only ever true once a ring buffer has actually filled;
     * before then it's a promise the ring hasn't kept yet. SNIP's own
     * toast (`Copy.SNIPPED`) was corrected from "LAST 60s" to "the qualified
     * UP TO 60s" for exactly this reason — GRAB shipped "LAST 2s" in the
     * *same commit* and nobody caught it because the two toasts were never
     * checked against each other, only individually.
     *
     * Deliberately keys on the literal word "LAST", not "FIRST": a fact
     * about what a truncation already did to a file's tail ("FIRST 3 MIN
     * KEPT", `Copy.tapeTruncated`/`Copy.imported`'s own phrasing) is true
     * the moment it's said and never becomes false later — there is
     * nothing to qualify. Only a claim that reads as an ongoing, standing
     * fact about a live buffer ("LAST Ns") is the false-until-full shape
     * this law refuses. Case-sensitive on purpose: the shout law above
     * already forces every Copy string constant fully uppercase, so a
     * real violation is always uppercase too — a lowercase match here
     * would only ever fire on a KDoc comment, which the source-text half
     * of this test skips.
     *
     * The number slot accepts either a literal digit run OR a Kotlin
     * string-template reference (`$n`, `${n}`, `${length}`) — a templated
     * toast built inside a `fun ...(): String`, exactly the shape
     * `Copy.tapeTruncated`/`Copy.imported` already use for their real
     * numbers, is precisely where a future GRAB-style bug is most likely
     * to hide, and a regex that only caught a hardcoded literal would miss
     * it entirely (hardcoded literals in `const val`s are already caught
     * by the reflective field scan below; this is what makes the
     * source-text scan worth doing at all).
     */
    private val ceilingClaim = Regex("""LAST\s+(?:\d+|\$\{?[A-Za-z_]\w*\}?)\s*(s\b|MIN\b)""")

    /**
     * Reviewed Copy field names allowed to match [ceilingClaim] anyway,
     * with a comment on each explaining why the ceiling really is settled
     * fact there (e.g. it describes a ring already known to be full).
     * Empty today — no current Copy string legitimately needs "LAST Ns"
     * phrasing — kept as an explicit list rather than loosening the regex,
     * per the same reasoning as [notASentence] above: a rule loose enough
     * to auto-approve the legitimate cases is a rule someone disables the
     * first time it's wrong.
     */
    private val reviewedCeilingClaimFields = emptySet<String>()

    /** Same idea as [reviewedCeilingClaimFields], but for a match inside `Personality.kt`'s own source text (a templated `fun`'s string literal) rather than a reflected field value. */
    private val reviewedCeilingClaimSourceSnippets = emptyList<String>()

    private fun isCommentLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private fun ceilingLawMessage(where: String, text: String): String =
        "$where = '$text' states a buffered/retained-content ceiling as settled fact (matches \"LAST <n>s\"/\"LAST " +
            "<n> MIN\"). That's true only once the buffer has actually filled — before then it's a promise the " +
            "ring hasn't kept yet, exactly the shape of the GRAB/\"LAST 2s\" bug SNIP's own toast was fixed away " +
            "from in the same commit it should have been fixed in both places. Say what was actually captured, " +
            "qualified (\"UP TO\"), the way Copy.SNIPPED does — or if this really is a settled fact (e.g. a ring " +
            "already known to be full), add it to reviewedCeilingClaimFields/reviewedCeilingClaimSourceSnippets " +
            "with a comment explaining why."

    @Test
    fun `law - a kept-content ceiling is never stated as fact`() {
        // Sanity: prove the regex actually distinguishes the real shapes
        // from history before trusting it against production copy.
        assertTrue(ceilingClaim.containsMatchIn("KEEPS LAST 2s"), "sanity check on the regex itself: it must catch the historical GRAB bug's exact shape, or this law is checking nothing")
        assertTrue(ceilingClaim.containsMatchIn("\"GRAB! KEEPS LAST \${n}s.\""), "sanity check: it must also catch the same shape written inside a templated toast's string interpolation (\$n / \${n}), not just a hardcoded literal — that's the whole reason the source-text scan exists")
        assertFalse(ceilingClaim.containsMatchIn("FIRST 3 MIN KEPT - THE TAPE IS ONLY SO LONG."), "sanity check: a fact about what a truncation already did (FIRST, not LAST) must never be flagged")
        assertFalse(ceilingClaim.containsMatchIn("SNIP! KEPT WHAT IT'S HEARD SINCE LISTEN, UP TO 60s."), "sanity check: the qualified UP TO form must never be flagged")

        // 1) Every reflected String constant.
        for ((name, value) in copyStringConstants()) {
            if (name in reviewedCeilingClaimFields) continue
            assertFalse(ceilingClaim.containsMatchIn(value), ceilingLawMessage("Copy.$name", value))
        }

        // 2) Personality.kt's own source text, so a *templated* toast built
        // inside a `fun ...(): String` with string interpolation (which
        // reflection over fields can't see at all) is covered too — the
        // historical bug could just as easily have shipped inside one of
        // Copy's functions as inside a `const val`.
        val personalitySource = File("src/main/kotlin/com/snipsnap/shell/Personality.kt")
        require(personalitySource.isFile) {
            "expected Personality.kt at ${personalitySource.absolutePath} (relative to :shell's own project dir, " +
                "Gradle's default test working directory) but it doesn't exist — fix the path, don't delete this " +
                "check: a scan that finds nothing would pass by accident, which is worse than no test at all."
        }
        val lines = personalitySource.readLines()
        require(lines.isNotEmpty()) { "found Personality.kt but it's empty — the scan or the file is broken, fail loudly instead of silently checking nothing." }
        for ((index, rawLine) in lines.withIndex()) {
            if (isCommentLine(rawLine)) continue
            if (reviewedCeilingClaimSourceSnippets.any { rawLine.contains(it) }) continue
            assertFalse(ceilingClaim.containsMatchIn(rawLine), ceilingLawMessage("Personality.kt:${index + 1}", rawLine.trim()))
        }
    }

    @Test
    fun `the HITS readout names the hit, or none, or a tape without any`() {
        assertEquals("HIT 3/7", Copy.hitReadout(2, 7))
        assertEquals("HIT -/7", Copy.hitReadout(-1, 7))
        assertEquals("NO HITS", Copy.hitReadout(-1, 0), "known the moment the search comes back empty, not on a tap")
    }

    @Test
    fun `one section clipped is a sentence, not a count with an S on it`() {
        val one = Copy.clippedSectionsIntoKit(1, 2, 8, 0)
        assertTrue(one.startsWith("1 SECTION IS"), "said: $one")
        assertTrue(one.contains("IT RIDES TO THE MPC"), "said: $one")
        val many = Copy.clippedSectionsIntoKit(3, 6, 24, 0)
        assertTrue(many.startsWith("3 SECTIONS ARE") && many.contains("THEY RIDE"), "said: $many")
        // And the same on the branch that counts what stayed behind.
        assertTrue(Copy.clippedSectionsIntoKit(1, 2, 8, 1).startsWith("1 SECTION IN"))
        assertTrue(Copy.clippedSectionsIntoKit(2, 2, 8, 2).startsWith("2 SECTIONS IN"))
        // BARS and NOTES used to stay plural in this same sentence even
        // though SECTIONS was already singular right beside them — this
        // function's own KDoc bragged about fixing the count-with-an-S-on-it
        // bug for SECTIONS while leaving exactly that bug alive for its two
        // neighbors. One bar, one note, one section, one snip ring, all at
        // once: the sentence that would have caught it.
        assertEquals(
            "1 SECTION IS IN THE KIT'S GROOVES — 1 BAR, 1 NOTE, ONE SEQUENCE EACH. IT RIDES TO THE MPC.",
            Copy.clippedSectionsIntoKit(1, 1, 1, 0),
        )
        assertEquals(
            "1 SECTION IN THE GROOVES: 1 BAR, 1 NOTE. 1 SNIP RING STAYED OUT.",
            Copy.clippedSectionsIntoKit(1, 1, 1, 1),
        )
    }

    /**
     * `clippedIntoKit` is `clippedSectionsIntoKit`'s single-section sibling
     * (CLIP ▸ KIT's own path for a set with no arrangement) and had the same
     * BARS/NOTES-always-plural bug, with no test pinning it at all before
     * this one.
     */
    @Test
    fun `clippedIntoKit sings BARS and NOTES singular at one, same as its multi-section sibling`() {
        assertEquals(
            "GROOVE IS IN THE KIT'S GROOVES — 1 BAR, 1 NOTE. IT RIDES TO THE MPC.",
            Copy.clippedIntoKit("GROOVE", 1, 1, 0),
        )
        assertEquals(
            "GROOVE IN THE GROOVES: 2 BARS, 8 NOTES. 1 SNIP RING STAYED OUT.",
            Copy.clippedIntoKit("GROOVE", 2, 8, 1),
        )
    }

    /** CHOP ALL's own singular: one .wav file chopped, not "1 FILES". */
    @Test
    fun `choppedAll counts its files singular at one`() {
        assertEquals("CHOPPED 1 OF 1 FILE INTO 1 KIT.", Copy.choppedAll(made = 1, wavCount = 1, skipped = 0, failed = 0))
        assertEquals("CHOPPED 2 OF 3 FILES INTO 2 KITS.", Copy.choppedAll(made = 2, wavCount = 3, skipped = 0, failed = 0))
    }

    /**
     * LOOP, GROOVE and ORBIT all bounce through `SnipStore.import` into the
     * one folder SNIPS lists — and between them they had three names for it
     * (prior #25). ORBIT's button said BOUNCE TO TAPE and its landing line
     * said "ON TAPE", which is a different screen entirely; LOOP's and
     * GROOVE's buttons said nothing at all about where the audio goes, so
     * the destination was first mentioned in the toast *after* the tap.
     *
     * Both halves are held here: the button says it before, the line says
     * it after, and all six say the same word.
     */
    @Test
    fun `every bounce names the one shelf its audio lands on`() {
        val before = mapOf(
            "LOOP's BOUNCE button" to Copy.loopBounceButton(2),
            "GROOVE's BOUNCE button" to Copy.GROOVE_BOUNCE_BUTTON,
            "ORBIT's BOUNCE button" to Copy.ORBIT_BOUNCE_BUTTON,
        )
        val after = mapOf(
            "LOOP's landing line" to Copy.loopBounced(2),
            "LOOP's part-cycle landing line" to Copy.loopBouncedPart(2, 4),
            "GROOVE's landing line" to Copy.groovePrinted(1.5f),
            "ORBIT's landing line" to Copy.orbitBounced("5 AGAINST 4"),
        )
        for ((who, line) in before + after) {
            assertTrue(
                "SNIPS" in line,
                "$who reads \"$line\", which does not name SNIPS. Every bounce in the app writes through " +
                    "SnipStore.import into the folder SNIPS lists — three names for one destination is what " +
                    "prior #25 was, and TAPE (ORBIT's old word) is a different screen.",
            )
        }
        for ((who, line) in before + after) {
            assertFalse(
                Regex("""\bTAPE\b""").containsMatchIn(line),
                "$who reads \"$line\" and says TAPE. A bounce does not land on TAPE; it lands in SNIPS.",
            )
        }
    }


    /**
     * J33: CHOP's layout preview is sixteen coloured rectangles with no
     * text and no semantics node, and it is the only feedback CLASSIC /
     * FOLD / MELODIC has. A sighted player at least gets the class
     * colours; TalkBack got silence.
     */
    @Test
    fun `the layout preview counts the pads and tallies the classes`() {
        assertEquals("NOTHING ON THE PADS YET.", Copy.layoutPreview(List(16) { null }))
        assertEquals(
            "1 PAD OF 16 · 1 KICK",
            Copy.layoutPreview(listOf("KICK") + List(15) { null }),
            "singular at one, like every other count in this file",
        )
        // Sorted by count, then alphabetically, so the same layout always
        // reads the same way rather than following slot order.
        assertEquals(
            "5 PADS OF 16 · 3 HAT CLOSED · 1 KICK · 1 SNARE",
            Copy.layoutPreview(
                listOf("HAT_CLOSED", "KICK", "HAT_CLOSED", "SNARE", "HAT_CLOSED") + List(11) { null },
            ),
            "underscores read as spaces - HAT_CLOSED is not a word anyone says",
        )
    }


    /** J30: CHOP's fallback source, named only when the app picked it rather than the player. */
    @Test
    fun `chop names the kit sample it fell back to`() {
        assertEquals("FROM A03_SNARE", Copy.chopFromKit("A03_SNARE"))
    }

}
