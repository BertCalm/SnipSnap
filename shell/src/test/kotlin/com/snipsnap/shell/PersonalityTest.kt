package com.snipsnap.shell

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun `kit round robin copy counts pads in the singular and the plural`() {
        assertEquals("ROUND ROBIN on 1 pad.", Copy.robinKitOn(1))
        assertEquals("ROUND ROBIN on 5 pads.", Copy.robinKitOn(5))
        assertEquals("ROUND ROBIN on 0 pads.", Copy.robinKitOn(0))
    }

    @Test
    fun `law 3 - funny copy still says exactly what happened`() {
        // The capture-blocked box names the problem and the way out.
        assertTrue(Copy.CAPTURE_BLOCKED.contains("SCREEN RECORDER", ignoreCase = true))
        // A session the phone ended names the two ways that happens.
        assertTrue(Copy.PHONE_STOPPED_TAPE.contains("LOCK SCREEN", ignoreCase = true))
        assertTrue(Copy.PHONE_STOPPED_TAPE.contains("STOP CHIP", ignoreCase = true))
        // A refused consent must not claim anything is armed.
        assertTrue(Copy.APP_AUDIO_REFUSED.contains("NOTHING ARMED", ignoreCase = true))
        // The Ear reports what it heard, in real numbers.
        assertEquals("Heard 12 hits over 2 bars at ~93 BPM. They play on your pads now.", Copy.grooveRead(12, 2, 93))
        assertEquals("Heard 4 hits over 1 bar at ~120 BPM. They play on your pads now.", Copy.grooveRead(4, 1, 120))
        // A single hit over a single bar is singular in both counts at once —
        // the shape that would have caught `grooveRead` counting BAR/BARS
        // right while leaving HITS always plural.
        assertEquals("Heard 1 hit over 1 bar at ~90 BPM. They play on your pads now.", Copy.grooveRead(1, 1, 90))
        assertEquals("Took 9 hits over 2 bars. Playing on CAPTURED now.", Copy.takeLanded(9, 2), "4/4 carries no meter")
        // Was "TOOK 1 HITS OVER 1 BAR OF 3/4." — takeLanded sang BAR/BARS
        // right (it shares grooveRead's shape) and left HITS always plural,
        // the same asymmetry inside one sentence that the general helper
        // below exists to rule out everywhere at once.
        assertEquals("Took 1 hit over 1 bar of 3/4. Playing on CAPTURED now.", Copy.takeLanded(1, 1, "3/4"))
        assertEquals("Took 5 hits over 2 bars of 5/4. Playing on CAPTURED now.", Copy.takeLanded(5, 2, "5/4"))
        assertEquals("BANK A · 0 FREE", Copy.catchBank('A', 0))
        // A refusal names the control as it reads and the tab it is on
        // (September wiring review, findings 4 and 14): the reflective
        // laws below hold shape, not route, so a slide back to "ARM THE
        // MIC" or a bare "TRY GRID" would pass them.
        for (line in listOf(Copy.HUM_NOT_LISTENING, Copy.HUM_APP_AUDIO, Copy.HUM_NOTHING)) {
            assertTrue(line.contains("LISTEN · MIC IN LIBRARY", ignoreCase = true) || (line.contains("LISTEN · MIC", ignoreCase = true) && line.contains("LIBRARY", ignoreCase = true)), "HUM's route is the button on LIBRARY: $line")
            assertTrue(!line.contains("ARM", ignoreCase = true), "nothing on any screen is called ARM: $line")
        }
        assertTrue(Copy.HUM_APP_AUDIO.contains("STOP IN LIBRARY", ignoreCase = true), "APP AUDIO has to be stopped before the mic can start: ${Copy.HUM_APP_AUDIO}")
        // RING takes either ring, so its refusal names both doors on SHELF
        // (a slide back to the mic alone would send an APP AUDIO user to
        // stop the very session RING wanted).
        for (line in listOf(Copy.RING_NOT_LISTENING, Copy.RING_NOTHING)) {
            assertTrue(line.contains("RING", ignoreCase = true), "the refusal names the button as it reads: $line")
            assertTrue(!line.contains("ARM", ignoreCase = true), "nothing on any screen is called ARM: $line")
        }
        // EVERY BAR switching itself off names the button as it reads and
        // the same two doors, since either ring feeds it.
        assertTrue(Copy.RING_BAR_STOPPED.contains("EVERY BAR", ignoreCase = true) && Copy.RING_BAR_STOPPED.contains("LISTEN · MIC", ignoreCase = true) && Copy.RING_BAR_STOPPED.contains("APP AUDIO", ignoreCase = true) && Copy.RING_BAR_STOPPED.contains("LIBRARY", ignoreCase = true), Copy.RING_BAR_STOPPED)
        assertTrue(!Copy.RING_BAR_STOPPED.contains("ARM", ignoreCase = true), "nothing on any screen is called ARM: ${Copy.RING_BAR_STOPPED}")
        assertTrue(Copy.RING_NOT_LISTENING.contains("LISTEN · MIC", ignoreCase = true) && Copy.RING_NOT_LISTENING.contains("APP AUDIO", ignoreCase = true) && Copy.RING_NOT_LISTENING.contains("LIBRARY", ignoreCase = true), "RING's route is either button on LIBRARY: ${Copy.RING_NOT_LISTENING}")
        // FOLLOW and DUCK read the same ring RING does, so the same two doors.
        assertTrue(Copy.FOLLOW_NOT_LISTENING.contains("FOLLOW", ignoreCase = true) && Copy.FOLLOW_NOT_LISTENING.contains("DUCK", ignoreCase = true), "names the shapes as the MOD row reads them: ${Copy.FOLLOW_NOT_LISTENING}")
        assertTrue(Copy.FOLLOW_NOT_LISTENING.contains("LISTEN · MIC", ignoreCase = true) && Copy.FOLLOW_NOT_LISTENING.contains("APP AUDIO", ignoreCase = true) && Copy.FOLLOW_NOT_LISTENING.contains("LIBRARY", ignoreCase = true), "FOLLOW's route is either button on LIBRARY: ${Copy.FOLLOW_NOT_LISTENING}")
        assertTrue(!Copy.FOLLOW_NOT_LISTENING.contains("ARM", ignoreCase = true), "nothing on any screen is called ARM: ${Copy.FOLLOW_NOT_LISTENING}")
        // The length is the voice's own, not RING's four-second ask: a ring
        // that only just started gives less, and the ceiling law below holds
        // this line to what was kept.
        assertEquals("Froze 4.0 s of the mic on the SURFACE. Pad ◄ ► brings the pad back.", Copy.ringFrozen(4f, appAudio = false))
        assertEquals("Froze 1.5 s of APP AUDIO on the SURFACE. Pad ◄ ► brings the pad back.", Copy.ringFrozen(1.5f, appAudio = true))
        assertTrue(Copy.CHOP_NO_HITS.contains("OPEN CHOP", ignoreCase = true), "GRID is inside the closed CUT box: ${Copy.CHOP_NO_HITS}")
        // BOUNCE on a stopped GROOVE used to just dim with no reason
        // (wiring review finding 8) - tapping it now names the control
        // that actually resolves it, the same PLAY button GROOVE draws.
        assertTrue(Copy.GROOVE_BOUNCE_NEEDS_PLAY.contains("PLAY", ignoreCase = true), "names the control that unblocks BOUNCE: ${Copy.GROOVE_BOUNCE_NEEDS_PLAY}")
        assertEquals("BANK B · 16 FREE", Copy.catchBank('B', 16))
        assertEquals("No GROOVE: no beat heard - the ear finds hits, not tones.", Copy.grooveRefused("no beat heard - the ear finds hits, not tones."))
        assertEquals("Break found at 1:12-1:20. IN and OUT are set. INSTANT KIT is one tap away.", Copy.dug("1:12", "1:20"))
        // SEND TO PADS reports the real slice count.
        assertEquals("7 slices on the pads. CHOKE GROUP set.", Copy.sentToPads(7, chokeSet = true))
        assertEquals("3 slices on the pads.", Copy.sentToPads(3, chokeSet = false))
        // The kit switcher names the kit it put on the grid, in capitals.
        assertEquals("On the grid: Funk Room.", Copy.onTheGrid("Funk Room"))
        // A single slice sent to the pads is singular too — sentToPads used
        // to say "1 SLICES" (and so did instantKit, which routes through it).
        assertEquals("1 slice on the pads.", Copy.sentToPads(1, chokeSet = false))
        assertEquals("One tap, the whole sample. 1 slice on the pads.", Copy.instantKit(1, chokeSet = false, wholeTape = true))
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

    /**
     * HELP's one job is to be true, and the September UAT caught it being
     * false: twenty hardcoded lines in `StubScreen.kt` calling the app "the
     * M0 skeleton" and promising capture "arrives with M1", long after it
     * shipped. It had no test because it lived in a Composable, so nothing
     * failed as the app grew past it.
     *
     * These are the two ways it went wrong, now nailed down: a milestone
     * tag anywhere in the text, and the sections not naming the places a
     * user actually walks through. HELP draws `Places.HELP` since the
     * four-place shape (one section per place and one for the dock, which
     * `PlacesTest` holds to the rows); the long WORTH KNOWING list stays in
     * `Copy` as the inventory those sections were distilled from. Moving or
     * renaming a place now breaks a test rather than quietly making HELP lie.
     */
    @Test
    fun `help describes the app rather than a milestone`() {
        val all = (Places.HELP.values.flatten() + Places.HELP.keys + Copy.HELP_MORE)
            .joinToString(" ")
        for (tag in listOf("M0", "M1", "M2", "M3", "M4", "M5", "SKELETON", "ARRIVES WITH", "NOT RECORDED")) {
            assertFalse(all.contains(tag), "HELP still talks about the build, not the app: found \"$tag\"")
        }
        // The places, in the order a user walks them, then the dock.
        assertEquals(Places.PLACES + "THE DOCK", Places.HELP.keys.toList())
        // Every line is a line, not a paragraph: HELP renders on a phone.
        // Two limits, deliberately not one: the drawn sections are held to
        // `Places.HELP_LINE_CHARS` (80), which `PlacesTest` pins and the
        // sections were written to; the inventory keeps the 72 it was held
        // to, since loosening a law to fit a list that is no longer drawn
        // would be a relaxation. Which of the two the panel truly fits at
        // `lcdSmall` on a 360 dp phone is a device question, open.
        for (line in Places.HELP.values.flatten()) {
            assertTrue(line.startsWith("· "), "HELP lines are bulleted: $line")
            assertTrue(line.length <= Places.HELP_LINE_CHARS, "HELP line is too long for the panel (${line.length}): $line")
        }
        for (line in Copy.HELP_MORE) {
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
        assertTrue(busy.startsWith("EFFECTS", ignoreCase = true), "it replaces the card's own header: $busy")
        // Distinguishable from the idle header, which is the whole point.
        assertTrue(busy != "EFFECTS", busy)
        // Every segment's label has to fit, not just this one: a slate pedal behind MORE as well.
        for (seg in PadSheet.ALL_SEGMENTS) {
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
        assertTrue(room.contains("PAD", ignoreCase = true), room)
        assertTrue(room.contains("LONG-PRESS", ignoreCase = true), "it names the gesture, which nothing else on that screen does: $room")
        assertTrue(full.contains("FULL", ignoreCase = true), full)
        for (line in listOf(room, full)) {
            assertSentenceCase(line)
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
        assertTrue(line.contains("MY KIT.XPN", ignoreCase = true), "it names the thing, not \"a file\": $line")
        assertTrue(line.contains("EXPORT AGAIN", ignoreCase = true), "it says what the next tap does: $line")
        assertSentenceCase(line)
        assertTrue(line.endsWith("."), "a toast lands on a full stop: $line")
        // It must not read like something already happened - nothing has been
        // written when this appears.
        assertFalse(line.contains("DONE", ignoreCase = true), "nothing was written yet: $line")
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
        assertTrue(whole.contains("WHOLE SAMPLE", ignoreCase = true), "it says it took everything: $whole")
        // COMMIT's refusal is "SET IN + OUT FIRST"; the same two markers are
        // named here, so the pair of buttons speaks one vocabulary.
        assertTrue(selected.contains("IN + OUT", ignoreCase = true), "it names the markers COMMIT asks for: $selected")
        assertTrue(Copy.COMMIT_NEEDS_SELECTION.contains("IN + OUT", ignoreCase = true), Copy.COMMIT_NEEDS_SELECTION)

        for (line in listOf(whole, selected)) {
            // The count survives either reading - it is the part users act on.
            assertTrue(line.contains("8 SLICES", ignoreCase = true), "the slice count is still there: $line")
            assertSentenceCase(line)
            assertTrue(line.endsWith("."), "a toast lands on a full stop: $line")
        }
        assertTrue(
            Copy.instantKit(8, chokeSet = true, wholeTape = true).endsWith("CHOKE GROUP SET.", ignoreCase = true),
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
        assertTrue(landed.contains("KICK", ignoreCase = true), "it names the pad it gave back: $landed")
        assertTrue(landed.contains("TRASH", ignoreCase = true), "the bin is where the take came from, and the user has to learn that: $landed")
        assertSentenceCase(landed)
        assertTrue(landed.endsWith("."), "a toast lands on a full stop: $landed")

        // The three answers are three sentences, not one sentence three times.
        val answers = listOf(landed, Copy.RETREAT_REFUSED, Copy.UNTREAT_NOT_BINNED)
        assertEquals(answers.size, answers.toSet().size, "a refusal that reads like a success teaches nothing: $answers")
        // The two refusals say what is in the way, and neither claims a restore.
        assertTrue(Copy.RETREAT_REFUSED.contains("SOFT HITS", ignoreCase = true), Copy.RETREAT_REFUSED)
        assertTrue(Copy.UNTREAT_NOT_BINNED.contains("TRASH", ignoreCase = true), Copy.UNTREAT_NOT_BINNED)
        assertFalse(Copy.UNTREAT_NOT_BINNED.contains("BACK", ignoreCase = true), "nothing came back: ${Copy.UNTREAT_NOT_BINNED}")
    }

    /**
     * The same law for the two UNDOs the pad sheet gained on 2026-11-01, TAPE SPLICE's and
     * DE-SAMPLE's: each has four answers (it landed, nothing of its kind is on the pad, the bin
     * holds no earlier take, soft hits are in the way) and they have to be told apart. The landing
     * says the take came from the bin; the not-binned refusal says the bin and claims no restore.
     * Neither door borrows MUTATE's words, which name MUTATE.
     */
    @Test
    fun `UNDO SPLICE and DE-SAMPLE's UNDO each say which of their four answers they are giving`() {
        val doors = mapOf(
            "SPLICE" to listOf(Copy.UNSPLICED, Copy.SPLICE_UNDO_NOTHING, Copy.SPLICE_UNDO_NOT_BINNED),
            "FIND NEAREST SYNTH" to listOf(Copy.UNDESAMPLED, Copy.DESAMPLE_UNDO_NOTHING, Copy.DESAMPLE_UNDO_NOT_BINNED),
        )
        for ((door, lines) in doors) {
            val (landed, nothing, notBinned) = lines
            val answers = lines + Copy.UNDO_LAYERED
            assertEquals(answers.size, answers.toSet().size, "$door: a refusal that reads like a success teaches nothing: $answers")
            assertTrue(landed.contains("TRASH", ignoreCase = true), "$door: the bin is where the take came from: $landed")
            assertTrue(landed.contains(door), "$door: the landing names what it took off: $landed")
            assertTrue(nothing.contains(door), "$door: the refusal names what the pad does not carry: $nothing")
            assertFalse(nothing.contains("BACK", ignoreCase = true), "$door: nothing came back: $nothing")
            assertTrue(notBinned.contains("TRASH", ignoreCase = true), "$door: $notBinned")
            assertFalse(notBinned.contains("BACK", ignoreCase = true), "$door: nothing came back: $notBinned")
            for (line in answers) {
                assertSentenceCase(line)
                assertTrue(line.endsWith("."), "a toast lands on a full stop: $line")
                assertFalse(line.contains("COMBINE SOUNDS", ignoreCase = true), "$door's UNDO is not COMBINE SOUNDS's: $line")
            }
        }
        assertTrue(Copy.UNDO_LAYERED.contains("SOFT HITS", ignoreCase = true), "the layered refusal names the chip that clears it: ${Copy.UNDO_LAYERED}")
        assertFalse(Copy.UNDO_LAYERED.contains("BACK", ignoreCase = true), Copy.UNDO_LAYERED)
        assertTrue(Copy.UNSPLICED.contains("THE VERSION BEFORE THE LAST SPLICE", ignoreCase = true), "after two splices the first splice's result comes back: ${Copy.UNSPLICED}")
        assertTrue(Copy.UNDESAMPLED.contains("THE VERSION BEFORE THE LAST FIND NEAREST SYNTH", ignoreCase = true), Copy.UNDESAMPLED)
    }

    /** TAPE SPLICE's landing names its way back, as DE-SAMPLE's, MUTATE's and DRIFT's do. */
    @Test
    fun `the SPLICE landing says where the take before it went, either way it joined`() {
        for (crossfaded in listOf(true, false)) {
            val line = Copy.spliced(crossfaded)
            assertTrue(line.endsWith(" The version before it is in the trash."), "crossfaded=$crossfaded: $line")
            assertTrue(line.startsWith("SPLICED", ignoreCase = true), line)
        }
        assertNotEquals(Copy.spliced(true), Copy.spliced(false), "the seam's honesty survives the new ending")
    }

    /** ORBIT's UNDO chip says how deep the undo goes, and drops the count when there is nothing to undo. */
    @Test
    fun `the ORBIT undo chip counts its steps`() {
        assertEquals("UNDO", Copy.orbitUndo(0))
        assertEquals("UNDO (3)", Copy.orbitUndo(3))
    }

    /** KEEP on SPLICE and STACK names where the way back lives, as the buttons really are labelled. */
    @Test
    fun `the SPLICE and STACK landings say where the undo is`() {
        assertTrue(Copy.SPLICE_UNDO_AT.contains("UNDO SPLICE", ignoreCase = true) && Copy.SPLICE_UNDO_AT.contains("PAD EDITOR", ignoreCase = true), Copy.SPLICE_UNDO_AT)
        assertTrue(Copy.STACK_UNDO_AT.contains("SOFT HITS", ignoreCase = true) && Copy.STACK_UNDO_AT.contains("PAD EDITOR", ignoreCase = true), Copy.STACK_UNDO_AT)
        assertEquals("USE THIS PART ▸", Copy.USE_THIS_PART)
    }

    /**
     * A print refuses TAPE SPLICE and DE-SAMPLE (2026-11-02) in the words it already refuses SOFT
     * HITS, STACK THE TAKES and SPREAD with: REMOVE, the print's own name, then the door it blocks.
     * They are functions, so the reflective shout law cannot see them; this holds them by hand.
     */
    @Test
    fun `a print refuses SPLICE and DE-SAMPLE in the words it refuses the other doors with`() {
        assertEquals("Remove WIDE before SPLICE.", Copy.boardRemoveBeforeSplice("WIDE"))
        assertEquals("Remove AQUARIUM before FIND NEAREST SYNTH.", Copy.boardRemoveBeforeDesample("AQUARIUM"))
        val lines = listOf(
            Copy.boardRemoveBeforeSoftHits("RUSH"),
            Copy.boardRemoveBeforeStack("RUSH"),
            Copy.boardRemoveBeforeSpread("RUSH"),
            Copy.boardRemoveBeforeSplice("RUSH"),
            Copy.boardRemoveBeforeDesample("RUSH"),
        )
        assertEquals(lines.size, lines.toSet().size, "each refusal names its own door: $lines")
        for (line in lines) {
            assertTrue(line.startsWith("REMOVE RUSH BEFORE ", ignoreCase = true), "the print and the way out lead: $line")
            assertSentenceCase(line)
            assertTrue(line.endsWith("."), "a toast lands on a full stop: $line")
        }
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
        assertTrue(Copy.SETUP_FORMAT_NOTE.contains("EXPORT", ignoreCase = true), Copy.SETUP_FORMAT_NOTE)
        assertTrue(Copy.SETUP_FORMAT_NOTE.endsWith("."), "a sentence the screen says: ${Copy.SETUP_FORMAT_NOTE}")
        assertTrue(Copy.SETUP_CARD_NONE.endsWith("."), Copy.SETUP_CARD_NONE)

        // The headings are labels above a readout, so they do not end in a
        // full stop - the same register as the legends.
        val headings = listOf(
            Copy.SETUP_FORMAT_HEADING, Copy.SETUP_WHERE_HEADING, Copy.SETUP_CARD_HEADING, Copy.SETUP_RATE_HEADING,
        )
        for (h in headings) {
            assertFalse(h.endsWith("."), "furniture, not a sentence: $h")
            assertLabel(h, "heading")
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
            Copy.SETUP_RATE_NOTE.contains("TIME MACHINE", ignoreCase = true),
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

        // A held card is named, in the case it was given.
        val held = Copy.setupCardHeld("Untitled SD card")
        assertTrue(held.contains("UNTITLED SD CARD", ignoreCase = true), held)
        assertCaption(held, "setupCardHeld")
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
        assertTrue(Copy.SHELF_LEGEND.contains("HOLD", ignoreCase = true), "it names the gesture: ${Copy.SHELF_LEGEND}")
        assertTrue(Copy.SHELF_LEGEND.contains("KIT", ignoreCase = true), "and what to hold: ${Copy.SHELF_LEGEND}")
        assertTrue(Copy.SHELF_LEGEND.contains("RENAME", ignoreCase = true), "naming is the finding's own subject: ${Copy.SHELF_LEGEND}")
        assertTrue(Copy.SHELF_LEGEND.contains("DELETE", ignoreCase = true), "the hold reveals both, so both are named: ${Copy.SHELF_LEGEND}")
        assertTrue(Copy.SHELF_LEGEND.contains("EXPANSION", ignoreCase = true), "and the third door the hold now reveals: ${Copy.SHELF_LEGEND}")
        assertCaption(Copy.SHELF_LEGEND, "SHELF_LEGEND")
        assertTrue(Copy.SHELF_LEGEND.length <= 52, "one line under the shelf: ${Copy.SHELF_LEGEND.length}")
        // Furniture, not a toast - it never lands on a full stop, and the
        // reflective law exempts it for exactly that reason.
        assertFalse(Copy.SHELF_LEGEND.endsWith("."), "a permanent label is not a sentence: ${Copy.SHELF_LEGEND}")
        // Two legends on two screens must not read as the same instruction.
        assertTrue(Copy.SHELF_LEGEND != Copy.PADS_RESTING_LINE)
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
        assertTrue(Copy.STEP_EDIT_TITLE.contains("YOURS", ignoreCase = true), Copy.STEP_EDIT_TITLE)
        assertTrue(Copy.GROOVE_OVERDUBBING.contains("CAPTURED", ignoreCase = true), Copy.GROOVE_OVERDUBBING)

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
        assertTrue(footer.contains("16 STEPS", ignoreCase = true), footer)
        assertTrue(footer.contains("COPIED FROM CAPTURED", ignoreCase = true), footer)
        assertTrue(footer.contains("THE OTHER FOUR", ignoreCase = true), "the house phrase, as FORKED_TO_E says it: $footer")
        assertTrue(Copy.FORKED_TO_E.contains("THE OTHER FOUR", ignoreCase = true), Copy.FORKED_TO_E)
        assertEquals("1 step", Copy.stepEditFooter(1, "X").substringBefore(" ·"))

        // And none of the three says a letter again.
        for (line in listOf(Copy.STEP_EDIT_TITLE, Copy.GROOVE_OVERDUBBING, footer)) {
            assertFalse(
                Regex("""PROG [A-E]\b|\b[A-E][-–—][A-E]\b""").containsMatchIn(line),
                "a PROG letter is back on GROOVE: $line",
            )
        }
    }

    /**
     * The first-run note, which is the only sentence in the app whose
     * whole job is to be understood by somebody who has not used it yet.
     *
     * Three persona findings land on it. P1.1: its last verb was DUB,
     * which is the app's word for the write and not a word a beginner
     * arrives with. P2.4 and P3.2: it ended "FOUR TABS, IN ORDER", which
     * is stricter than the app — a starter kit begins at step three, and
     * TAPE's own INSTANT KIT and CATCH A HIT land sounds on pads without
     * CHOP.
     *
     * `ConventionTest`'s `the first-run note names real places` already
     * holds the harder half of this (every place it names is a real one, so
     * a verb cannot point a new user at the wrong screen). This holds what
     * that law cannot see: that the words are the ones the screens
     * actually use.
     */
    @Test
    fun `the first-run note uses the words the screens use`() {
        val note = Copy.FIRST_RUN_PLACES_NOTE

        // One verb per stage, in the stage order, and step four is the
        // word on EXPORT's own stage rather than the one on its progress
        // line. CHOP and EXPORT are the stages' own names.
        assertTrue(note.startsWith("RECORD IT, CHOP IT, KIT IT, EXPORT IT.", ignoreCase = true), note)
        assertFalse(
            Regex("""\bDUB\b""").containsMatchIn(note),
            "DUB is the app's word for the write and stays everywhere else, but not in the one " +
                "sentence written for somebody who has not used the app yet (P1.1): $note",
        )

        // ...and it no longer says the walk is compulsory, because it is
        // not (P2.4, P3.2). Checked as "does not end on the bare claim"
        // rather than by pinning the replacement, so the clause can be
        // reworded without a test rewrite.
        assertFalse(
            note.endsWith("FOUR TABS, IN ORDER.", ignoreCase = true),
            "a starter kit begins at step three and TAPE's shortcuts skip CHOP, so the note must not " +
                "end by claiming the four tabs are compulsory: $note",
        )
        assertTrue(note.endsWith("."), "still a sentence the screen says: $note")

        // It names no button of this screen, which is the rule its own KDoc
        // sets: CATCH YOUR FIRST SOUND and START FROM A KIT ▾ sit directly
        // under it in the same panel, and naming them here would be a second
        // copy of a control already on screen. "A KIT" is the way in, not the
        // button's label.
        for (door in listOf("STARTERS", "NEW KIT", "INSTANT KIT", "CATCH", "LISTEN", "MIC")) {
            assertFalse(note.contains(door, ignoreCase = true), "the note names a door it does not have to name: $note")
        }
        assertTrue(note.endsWith(" Or start from a kit."), "the tail says the second way in: $note")
        // The panel's two ways in are the labels the note glosses.
        assertEquals("START FROM A KIT ▾", Copy.START_FROM_KIT)
        assertEquals("LISTEN FOR YOUR FIRST SOUND", Places.CATCH_FIRST)

        // The doors it declines to name are named where the inventory
        // lives instead. CATCH has had a line since HELP was written;
        // INSTANT KIT was in no list at all, which is the half of P3.2
        // that was actually open.
        val help = Places.HELP.values.flatten() + Copy.HELP_MORE
        assertTrue(help.any { it.contains("INSTANT KIT", ignoreCase = true) }, "INSTANT KIT is in no HELP list")
        assertTrue(help.any { it.contains("CATCH A HIT: hold a pad as the hit goes by.", ignoreCase = true) }, "CATCH is in no HELP list")
    }

    @Test
    fun `the pads strip's resting line names the gesture`() {
        // Was the pad sheet legend under the grid, one line of 52. The strip
        // above the grid replaced it and draws its resting line on up to two.
        assertTrue(Copy.PADS_RESTING_LINE.contains("HOLD", ignoreCase = true), Copy.PADS_RESTING_LINE)
        assertTrue(Copy.PADS_RESTING_LINE.contains("PAD", ignoreCase = true), Copy.PADS_RESTING_LINE)
        assertTrue(Copy.PADS_RESTING_LINE.length <= 2 * 52, "two lines on the strip: ${Copy.PADS_RESTING_LINE.length}")
    }

    // ---- The case law (clarity spec 7.5, owner ruling D3) ----
    //
    // Copy is three kinds, told apart by the marks on the field (see CopyKind.kt):
    // a message is unmarked and is a sentence, a label is [CopyLabel] and is CAPS,
    // a caption is [CopyCaption] and is a sentence fragment. The kinds below are
    // read off the static fields of `Copy` and `Places`, so a constant added
    // next year is held to the law for its kind without anyone coming back here.

    private enum class Kind { MESSAGE, LABEL, CAPTION }

    private fun kindOf(field: java.lang.reflect.Field): Kind = when {
        field.isAnnotationPresent(CopyLabel::class.java) -> Kind.LABEL
        field.isAnnotationPresent(CopyCaption::class.java) -> Kind.CAPTION
        else -> Kind.MESSAGE
    }

    /** Every String constant of [owner], by name: its value and its kind. */
    private fun stringConstants(owner: Any): Map<String, Pair<String, Kind>> {
        val out = LinkedHashMap<String, Pair<String, Kind>>()
        for (field in owner.javaClass.declaredFields) {
            if (field.type != String::class.java || field.isSynthetic) continue
            field.isAccessible = true
            val value = field.get(owner) as? String ?: continue
            out[field.name] = value to kindOf(field)
        }
        return out
    }

    /** The first letter of [s], if it has one. A message that opens on a number or a glyph has no first letter to hold. */
    private fun firstLetter(s: String): Char? = s.firstOrNull { it.isLetter() }

    /**
     * What a message must be, in one place: a sentence in sentence case. It
     * ends in a full stop, `…` (work in progress) or `?`; its first letter
     * is a capital; and it is not all caps, which is how a label reads. It
     * does not decide which words stay caps (a control's name stays exactly as
     * labelled): that is for review, and a regex cannot.
     */
    private fun assertMessage(value: String, what: String) {
        assertTrue(
            value.endsWith(".") || value.endsWith("…") || value.endsWith("?"),
            "$what = '$value' must end in a full stop, '…' (work in progress) or '?'. If it is a label or a caption it needs @field:CopyLabel or @field:CopyCaption.",
        )
        assertCaption(value, what)
    }

    /** A caption: sentence case, no terminator required. */
    private fun assertCaption(value: String, what: String) {
        val first = value.firstOrNull()
        assertTrue(first == null || !first.isLetter() || first.isUpperCase(), "$what = '$value' must open with a capital (sentence case).")
        assertFalse(
            readsAsAllCaps(value),
            "$what = '$value' is all caps. A sentence is in sentence case; if it is a label it needs @field:CopyLabel.",
        )
    }

    /** The one definition of "reads as a label": no lowercase letter anywhere. Shared by the field law above and the source-scan law below. */
    private fun readsAsAllCaps(value: String): Boolean = value.none { it.isLowerCase() }

    /** A label is CAPS. Lowercase units (the "s" in 60s) belong in a message or a caption, not a label. */
    private fun assertLabel(value: String, what: String) {
        assertEquals(value.uppercase(Locale.ROOT), value, "$what = '$value' is a label and a label is CAPS. If it is a sentence, remove @field:CopyLabel.")
    }

    /** For the lines the tests build by calling a function: a message, whatever its arguments. */
    private fun assertSentenceCase(line: String) = assertMessage(line, "line")

    private fun assertWord(line: String, word: String, present: Boolean = true) {
        val found = Regex("\\b" + Regex.escape(word) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
        assertEquals(present, found, "'$word' ${if (present) "is missing from" else "must not appear in"}: $line")
    }

    /**
     * The exact ~30 names the old hand-listed `listOf(Copy.A, Copy.B, ...)`
     * checked, kept only so the test below can assert that every one of
     * them is still a message, never a label or a caption — proof that the
     * mechanism (hand list -> reflection -> marks) never relaxed which
     * strings are held to the full-stop law.
     */
    private val legacyHandListedToasts = setOf(
        "MELODIC_ON", "KEY_OFF",
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
        "TAKES_BIN_RULE", "SNAPPED",
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
        // This law read `value.contains("HOLD", ignoreCase = true)` until a legend arrived for a
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
                gestures.any { it in value.uppercase(Locale.ROOT) },
                "Copy.$name is a legend but names none of $gestures: '$value' — " +
                    "a legend that does not name the gesture teaches nobody the thing they cannot guess. " +
                    "If it teaches a genuinely new gesture, add that verb to the list above rather than " +
                    "removing the check.",
            )
        }
    }

    @Test
    fun `messages are sentence case and end in a full stop, labels are caps (reflective)`() {
        val copy = stringConstants(Copy)
        val places = stringConstants(Places)
        val all = copy + places
        // Guards, one per kind, so a build or classpath change that hides the
        // fields (or the marks) fails here instead of making the law pass by
        // checking nothing. Fix the reflection; do not lower these bounds.
        fun count(k: Kind, m: Map<String, Pair<String, Kind>>) = m.values.count { it.second == k }
        assertTrue(count(Kind.MESSAGE, copy) > 300, "only ${count(Kind.MESSAGE, copy)} Copy messages found by reflection")
        assertTrue(count(Kind.LABEL, copy) > 100, "only ${count(Kind.LABEL, copy)} Copy labels found: is @field:CopyLabel still readable at run time?")
        assertTrue(count(Kind.CAPTION, copy) >= 10, "only ${count(Kind.CAPTION, copy)} Copy captions found")
        assertTrue(count(Kind.LABEL, places) > 20, "only ${count(Kind.LABEL, places)} Places labels found")
        assertTrue(count(Kind.CAPTION, places) >= 5, "only ${count(Kind.CAPTION, places)} Places captions found")

        // The old hand list: every name it held is a message, and still exists.
        for (legacy in legacyHandListedToasts) {
            val entry = copy[legacy]
            assertTrue(entry != null, "Copy.$legacy (checked by the old hand-listed test) no longer exists via reflection — did it get renamed without updating this legacy set?")
            assertEquals(Kind.MESSAGE, entry.second, "Copy.$legacy was checked by the original hand-listed law and must stay a message, not a label or a caption.")
        }

        for ((name, entry) in all) {
            val (value, kind) = entry
            when (kind) {
                Kind.MESSAGE -> assertMessage(value, "Copy/Places.$name")
                Kind.CAPTION -> assertCaption(value, "Copy/Places.$name")
                Kind.LABEL -> assertLabel(value, "Copy/Places.$name")
            }
        }
    }

    @Test
    fun `HELP lines are messages, and a section is a label`() {
        val lines = Places.HELP.values.flatten() + Copy.HELP_MORE
        assertTrue(lines.size > 30, "only ${lines.size} HELP lines")
        for (line in lines) assertMessage(line.removePrefix("· "), "HELP line")
        for (section in Places.HELP.keys) assertLabel(section, "HELP section")
    }

    @Test
    fun `the interpolated lines name what they acted on`() {
        assertTrue(Copy.keySet("Am").startsWith("Am set."), "the key leads its own toast")
        assertTrue(Copy.keySet("Am").endsWith("."), "and still lands on a full stop")
        assertEquals("1 pad retuned into A MINOR. The kick is untouched.", Copy.inKey(1, "A MINOR"))
        assertEquals(
            "One tap, your IN + OUT. 8 slices on the pads. CHOKE GROUP set.",
            Copy.instantKit(8, chokeSet = true, wholeTape = false),
        )
        assertEquals("One tap, the whole sample. 5 slices on the pads.", Copy.instantKit(5, chokeSet = false, wholeTape = true))
        assertEquals("3 pads retuned into A MINOR. The kick is untouched.", Copy.inKey(3, "A MINOR"))
        assertTrue(Copy.takeRestored("T3").startsWith("T3 RESTORED.", ignoreCase = true), "the take leads its own toast")
        assertTrue(Copy.feelRolled(4).startsWith("Feel #4 rolled."), "the seed leads its own toast")
        assertTrue(Copy.feelRolled(4).endsWith("."), "and still lands on a full stop")
        assertTrue(
            Copy.treated("CRUSH", "A02").startsWith("CRUSH ON A02.", ignoreCase = true),
            "the treatment and the pad both lead their own toast",
        )
        assertTrue(Copy.treated("CRUSH", "A02").endsWith("."), "and still lands on a full stop")
        // The stacked landing: same opening, then the honest difference and the way back.
        val stacked = Copy.treatedStacked("CRUSH", "A02")
        assertTrue(stacked.startsWith("CRUSH ON A02,", ignoreCase = true), stacked)
        assertTrue(stacked.contains("NO ORIGINAL IN THE TRASH", ignoreCase = true) && stacked.contains("VERSIONS", ignoreCase = true), stacked)
        assertSentenceCase(stacked)
        assertTrue(stacked.endsWith("."))
        // A siren's landing: SEND TO PAD's own line first, then which shape landed and where its echo is.
        val sirenShot = Copy.sirenSent("A05", "Wail Siren", replaced = false, loop = false)
        assertTrue(sirenShot.startsWith("PAD A05 ADDED: WAIL SIREN.", ignoreCase = true), sirenShot)
        assertTrue(sirenShot.contains("ECHO IS IN THE RACK", ignoreCase = true) && !sirenShot.contains("SURFACE", ignoreCase = true), sirenShot)
        val sirenLoop = Copy.sirenSent("A05", "Wail Siren", replaced = true, loop = true)
        assertTrue(sirenLoop.startsWith("PAD A05 REPLACED WITH WAIL SIREN. ORIGINAL IS IN THE TRASH.", ignoreCase = true), sirenLoop)
        assertTrue(sirenLoop.contains("SURFACE", ignoreCase = true) && sirenLoop.contains("PAD ◄ ► TO A05", ignoreCase = true) && !sirenLoop.contains("RACK", ignoreCase = true), sirenLoop)
        assertTrue(sirenLoop.endsWith(".") && sirenShot.endsWith("."))
        val toSurface = Copy.sirenToSurface("A13", "Wail Siren", replaced = false)
        assertTrue(toSurface.startsWith("PAD A13 ADDED: WAIL SIREN.", ignoreCase = true), toSurface)
        assertTrue(toSurface.contains("SURFACE OPENS", ignoreCase = true) && toSurface.contains("HOLD", ignoreCase = true) && toSurface.endsWith("."), toSurface)
        assertEquals("TUNE on A02, in C MAJOR. Original is in the trash.", Copy.keyed("TUNE", "A02", "C MAJOR"))
        assertEquals("A02 blended toward Other:B03. Original is in the trash.", Copy.drifted("A02", "Other:B03"))
        // Both engines own a HAT_CLOSED and a SNARE, so the engine is
        // part of the answer rather than a prefix (DE-SAMPLE searched
        // THUMP alone until SKIN joined it).
        assertEquals(
            "A03 is a THUMP HAT CLOSED patch now, 0.12 away. Original is in the trash.",
            Copy.desampled("A03", "THUMP", "HAT_CLOSED", 0.123f),
        )
        assertEquals("NO PATCH IS NEAR. THE CLOSEST IS A SKIN SNARE, 0.61 AWAY.", Copy.desampleFar("SKIN", "SNARE", 0.61f))
        assertEquals("Not a note: a kick is a drum, not a note.", Copy.notANote("a kick is a drum, not a note"))
        assertTrue(
            Copy.mutated("SPLICE", "A01", "A03").startsWith("SPLICE: A01 × A03.", ignoreCase = true),
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
        assertEquals("BREAK is on track 2, 3 blocks long.", Copy.loopTrackFilled("BREAK", 2, 3))
        assertEquals("HIT is on track 1, 1 block long.", Copy.loopTrackFilled("HIT", 1, 1))
        assertEquals("LONG is on track 4. Only its first 8 blocks fit.", Copy.loopTrackTruncated("LONG", 4, 8))
        // loopTrackFilled beside it already sang "1 BLOCK LONG" — truncated
        // said "1 BLOCKS FIT" regardless, the same one-sided fix as the rest.
        assertEquals("LONG is on track 4. Only its first 1 block fit.", Copy.loopTrackTruncated("LONG", 4, 1))
        assertEquals("All 6 tracks are full. Clear one in LOOP first.", Copy.loopFull(6))
        // The bounce names what it rendered, and names the cycle too when the
        // two differ — a player told only "12 BARS" would read that as the
        // whole loop.
        assertEquals("12 bars bounced. It is in SAVED SAMPLES now.", Copy.loopBounced(12))
        assertEquals("64 bars bounced, out of a 840 bar cycle. It is in SAVED SAMPLES now.", Copy.loopBouncedPart(64, 840))
        // One bar is a legal bounce — the grid's default interval is one bar,
        // so a six-track grid of one-block chains bounces exactly this. Both
        // lines count the same thing and both have to say BAR for it.
        assertEquals("1 bar bounced. It is in SAVED SAMPLES now.", Copy.loopBounced(1))
        assertEquals("1 bar bounced, out of a 6 bar cycle. It is in SAVED SAMPLES now.", Copy.loopBouncedPart(1, 6))
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
        assertEquals("BREAK is ready to send. Pick where it goes.", Copy.snipReady("BREAK"))
        assertTrue(Copy.snipReady("BREAK").endsWith("."), "and still lands on a full stop")
        assertFalse(Copy.snipReady("BREAK").contains("SENT", ignoreCase = true), "the chooser opening is not the file leaving")
        assertEquals("PAD made — DRONE 2. In the LIBRARY.", Copy.madeNamed("PAD", "DRONE 2"))
        assertEquals("INSTRUMENT made — BELL. In the LIBRARY.", Copy.madeNamed("INSTRUMENT", "BELL"))
        assertTrue(Copy.madeNamed("PAD", "DRONE").endsWith("."), "and still lands on a full stop")
        val made = Copy.madeNamed("PAD", "DRONE 2")
        assertSentenceCase(made)
        // SYNTH's MAKE INSTRUMENT (RESIN, held): the sheet names what MAKE
        // will leave, by the same name the madeNamed toast then uses, and
        // says where it plays. The progress line counts zones, 1-based
        // like every count on screen, and trails off like treatmentBusy.
        val note = Copy.heldInstrumentNote("Big Brass 2")
        assertTrue(note.contains("BIG BRASS 2", ignoreCase = true), "the note names what will land: $note")
        assertTrue(note.contains("KEYBOARD", ignoreCase = true) && note.endsWith("."), "and says where it plays, on a full stop: $note")
        assertSentenceCase(note)
        assertEquals("Rendering 3/9…", Copy.instrumentRendering(3, 9))
        // SYNTH's DRONE TO LOOP: the toast names the note, the track as
        // counted on screen, and how long before it repeats; LOOP's readout
        // names the slice, and the nudge only when the promise was missed.
        assertEquals("A1 drone is on track 3, 4 bars around.", Copy.droneLanded("A1", 3, 4))
        assertEquals("A3 drone is on track 1, 1 bar around.", Copy.droneLanded("A3", 1, 1))
        assertEquals("DRONE A1 · 2/4", Copy.droneBlock("A1", 2, 4))
        assertEquals("DRONE A1 · 1/8 · -3.2¢ OFF", Copy.droneBlock("A1", 1, 8, -3.16))
        assertTrue(Copy.DRONE_NOTE.contains("TEMPO", ignoreCase = true) && Copy.DRONE_NOTE.contains("LOOP", ignoreCase = true), Copy.DRONE_NOTE)
    }

    @Test
    fun `tapeTruncated matches the import cap's own truncation phrasing`() {
        assertEquals("First 10 min kept - a sample is only so long.", Copy.tapeTruncated(600f))
        assertEquals("First 8s kept - a sample is only so long.", Copy.tapeTruncated(8.2f))
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
     * this law refuses. Case-insensitive on purpose: messages are sentence
     * case now, so a real violation reads "last 60s" and a case-sensitive
     * match would find none and pass by checking nothing. A lowercase match
     * on a KDoc comment is skipped by the source-text half of this test.
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
    private val ceilingClaim = Regex("""LAST\s+(?:\d+|\$\{?[A-Za-z_]\w*\}?)\s*(s\b|MIN\b)""", RegexOption.IGNORE_CASE)

    /**
     * Reviewed Copy field names allowed to match [ceilingClaim] anyway,
     * with a comment on each explaining why the ceiling really is settled
     * fact there (e.g. it describes a ring already known to be full).
     * Nearly empty — almost no Copy string legitimately needs "LAST Ns"
     * phrasing — kept as an explicit list rather than loosening the regex,
     * per the same reasoning as the marks on `Copy`: a rule loose enough
     * to auto-approve the legitimate cases is a rule someone disables the
     * first time it's wrong.
     */
    private val reviewedCeilingClaimFields = setOf(
        // Matched only once the law became case-insensitive (its uppercase "16 S" never fit the
        // lowercase-s unit). It is a failure line that names the length of the request that failed,
        // which KEYS' GRAB always makes as the last 16 s; it claims nothing about what a buffer holds.
        "KEYS_GRAB_FAILED",
    )

    /** Same idea as [reviewedCeilingClaimFields], but for a match inside `Personality.kt`'s own source text (a templated `fun`'s string literal) rather than a reflected field value. */
    private val reviewedCeilingClaimSourceSnippets = listOf("const val KEYS_GRAB_FAILED = ") // see reviewedCeilingClaimFields

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
        assertFalse(ceilingClaim.containsMatchIn("FIRST 3 MIN KEPT - A SAMPLE IS ONLY SO LONG."), "sanity check: a fact about what a truncation already did (FIRST, not LAST) must never be flagged")
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
        assertTrue(one.startsWith("1 SECTION IS", ignoreCase = true), "said: $one")
        assertTrue(one.contains("IT RIDES TO THE MPC", ignoreCase = true), "said: $one")
        val many = Copy.clippedSectionsIntoKit(3, 6, 24, 0)
        assertTrue(many.startsWith("3 SECTIONS ARE", ignoreCase = true) && many.contains("THEY RIDE", ignoreCase = true), "said: $many")
        // And the same on the branch that counts what stayed behind.
        assertTrue(Copy.clippedSectionsIntoKit(1, 2, 8, 1).startsWith("1 SECTION IN", ignoreCase = true))
        assertTrue(Copy.clippedSectionsIntoKit(2, 2, 8, 2).startsWith("2 SECTIONS IN", ignoreCase = true))
        // BARS and NOTES used to stay plural in this same sentence even
        // though SECTIONS was already singular right beside them — this
        // function's own KDoc bragged about fixing the count-with-an-S-on-it
        // bug for SECTIONS while leaving exactly that bug alive for its two
        // neighbors. One bar, one note, one section, one snip ring, all at
        // once: the sentence that would have caught it.
        assertEquals(
            "1 section is in the kit's grooves — 1 bar, 1 note, one sequence each. it rides to the MPC.",
            Copy.clippedSectionsIntoKit(1, 1, 1, 0),
        )
        assertEquals(
            "1 section in the grooves: 1 bar, 1 note. 1 SAMPLE RING stayed out.",
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
            "GROOVE is in the kit's grooves — 1 bar, 1 note. It rides to the MPC.",
            Copy.clippedIntoKit("GROOVE", 1, 1, 0),
        )
        assertEquals(
            "GROOVE in the grooves: 2 bars, 8 notes. 1 SAMPLE RING stayed out.",
            Copy.clippedIntoKit("GROOVE", 2, 8, 1),
        )
    }

    /** CHOP ALL's own singular: one .wav file chopped, not "1 FILES". */
    @Test
    fun `choppedAll counts its files singular at one`() {
        assertEquals("Chopped 1 of 1 file into 1 kit.", Copy.choppedAll(made = 1, wavCount = 1, skipped = 0, failed = 0))
        assertEquals("Chopped 2 of 3 files into 2 kits.", Copy.choppedAll(made = 2, wavCount = 3, skipped = 0, failed = 0))
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
                line.contains("SAMPLES", ignoreCase = true),
                "$who reads \"$line\", which does not name SAMPLES (the buttons say BOUNCE TO SAMPLES, the landing lines say SAVED SAMPLES). Every bounce in the app writes through " +
                    "SnipStore.import into the folder SAVED SAMPLES lists — three names for one destination is what " +
                    "prior #25 was, and TAPE (ORBIT's old word) is a different screen.",
            )
        }
        for ((who, line) in before + after) {
            assertFalse(
                Regex("""\bTAPE\b""").containsMatchIn(line),
                "$who reads \"$line\" and says TAPE. A bounce does not land on TAPE; it lands in SAVED SAMPLES.",
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
        assertEquals("Nothing on the pads yet.", Copy.layoutPreview(List(16) { null }))
        assertEquals(
            "1 pad of 16 · 1 KICK",
            Copy.layoutPreview(listOf("KICK") + List(15) { null }),
            "singular at one, like every other count in this file",
        )
        // Sorted by count, then alphabetically, so the same layout always
        // reads the same way rather than following slot order.
        assertEquals(
            "5 pads of 16 · 3 HAT CLOSED · 1 KICK · 1 SNARE",
            Copy.layoutPreview(
                listOf("HAT_CLOSED", "KICK", "HAT_CLOSED", "SNARE", "HAT_CLOSED") + List(11) { null },
            ),
            "underscores read as spaces - HAT_CLOSED is not a word anyone says",
        )
    }


    /** OUT's first line names what is going out, in a sentence that ends on a full stop. */
    @Test
    fun `out names what it is exporting`() {
        assertEquals("Exporting the kit BREAKS.", Copy.exportingKit("BREAKS"))
        assertEquals("Exporting the kit BREAKS and the project DEMO.", Copy.exportingKitAndProject("BREAKS", "DEMO"))
        assertEquals("Exporting the expansion VOL 1.", Copy.exportingExpansion("VOL 1"))
    }

    /** J30: CHOP's fallback source, named only when the app picked it rather than the player. */
    @Test
    fun `chop names the kit sample it fell back to`() {
        assertEquals("FROM A03_SNARE", Copy.chopFromKit("A03_SNARE"))
    }

    @Test
    fun `the armed empty deck makes the same claim as the snip toast and sends nobody to a pressed button`() {
        // TAPE's empty deck, once LISTEN is armed, says what SNIP keeps. It must
        // carry SNIPPED's qualified cap ("UP TO 60s", not "the last 60s") and must
        // not repeat NO_TAPE_YET's instruction to tap LISTEN.
        assertTrue(Copy.NO_TAPE_YET_ARMED.startsWith(Copy.SESSION_ARMED.removeSuffix(".")), Copy.NO_TAPE_YET_ARMED)
        assertTrue(Copy.NO_TAPE_YET_ARMED.contains("up to 60s", ignoreCase = true) && Copy.SNIPPED.contains("up to 60s", ignoreCase = true))
        assertTrue(!Copy.NO_TAPE_YET_ARMED.contains("TAP LISTEN", ignoreCase = true), Copy.NO_TAPE_YET_ARMED)
    }

    @Test
    fun `the SURFACE tab captions stay short enough for a fifth of a phone and say the live value`() {
        val all = listOf(
            Copy.surfacePeekVoice(true, "A01"), Copy.surfacePeekVoice(false, "A01"), Copy.surfacePeekVoice(false, null),
            Copy.surfacePeekShape('B'), Copy.surfacePeekShape(null),
            Copy.surfacePeekMod(0), Copy.surfacePeekMod(2),
            Copy.surfacePeekMidi(true), Copy.surfacePeekMidi(false),
            Copy.surfacePeekFx(true), Copy.surfacePeekFx(false),
        )
        for (c in all) {
            assertTrue(c.length <= 8, "'$c' must fit under a tab at 360 dp")
            assertLabel(c, "tab caption")
        }
        assertEquals("PAD A01", Copy.surfacePeekVoice(false, "A01"))
        assertEquals("ARM B", Copy.surfacePeekShape('B'))
        assertEquals("2 ON", Copy.surfacePeekMod(2))
    }

    // ---- The case law, read off the screen files (R4, review Q2) ----
    //
    // The laws above only see the String constants of Copy and Places. A sentence written
    // straight into a screen file passed every test. This scan reads the source of every Kotlin
    // file under app/src/main and shell/src/main and holds the sentence-like string literals
    // that reach the user through the sinks below to the same rule (sentence case; a control,
    // place or engine name stays caps inside the sentence: "Record one on BEAT first.").
    //
    // Sinks scanned (a literal sitting directly in the argument or value, not inside a nested call,
    // so `Copy.foo("KEEP")` is a control's name handed to a function, not a sentence):
    //  - toasts:      onToast(..) toast(..) onSnapToast(..) currentOnToast(..) onResult(..)
    //  - dock lines:  say(..) startOrSay(..)   (dock.say and the beat player's say)
    //  - notes, hints and status lines, as `name = value`:
    //                 hint note status message instrumentNote gatheringStatus working refusal
    //  - confirm dialogs: the first argument of every TapeText(..) inside a `fun *Confirm*(..)`
    //
    // "Sentence-like" is two or more words, or a terminator (. ! ?). A single caps word is a label.
    // What is held is the caps rule of assertCaption (readsAsAllCaps), the same detector as above.
    // The terminator rule is not applied to source literals: a fragment before a template is not a sentence.

    private val sourceSinkCalls = listOf("onToast", "toast", "onSnapToast", "currentOnToast", "onResult", "say", "startOrSay")
    private val sourceSinkNames = listOf("hint", "note", "status", "message", "instrumentNote", "gatheringStatus", "working", "refusal")
    private val tripleQuote = "\"\"\""

    /** Index just past the string literal opening at [i] (a `"` or a triple quote). */
    private fun endOfString(src: String, i: Int): Int {
        if (src.startsWith(tripleQuote, i)) {
            val j = src.indexOf(tripleQuote, i + 3)
            return if (j < 0) src.length else j + 3
        }
        var k = i + 1
        while (k < src.length && src[k] != '"') {
            when {
                src[k] == '\\' -> k += 2
                src[k] == '$' && k + 1 < src.length && src[k + 1] == '{' -> {
                    var depth = 1
                    k += 2
                    while (k < src.length && depth > 0) {
                        when (src[k]) {
                            '"' -> { k = endOfString(src, k); continue }
                            '{' -> depth++
                            '}' -> depth--
                        }
                        k++
                    }
                }
                else -> k++
            }
        }
        return k + 1
    }

    /** The text inside the parens opened just before [start], up to the matching close. */
    private fun balancedArgs(src: String, start: Int): String {
        var depth = 1
        var i = start
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '"' -> { i = endOfString(src, i); continue }
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return src.substring(start, maxOf(start, i - 1))
    }

    /** The string literals of [text] that sit at paren depth 0, raw (templates left in), without their quotes. */
    private fun topLevelLiterals(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '"' -> {
                    val end = endOfString(text, i)
                    if (depth == 0 && !text.startsWith(tripleQuote, i)) out += text.substring(i + 1, maxOf(i + 1, end - 1))
                    i = end
                    continue
                }
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return out
    }

    /** The value of a `name = value` at [start]: to the end of the statement, following `+`, `else` and string continuation lines. */
    private fun assignedValue(src: String, start: Int): String {
        var depth = 0
        var i = start
        while (i < src.length) {
            when (src[i]) {
                '"' -> { i = endOfString(src, i); continue }
                '(' -> depth++
                ')' -> { if (depth == 0) break; depth-- }
                ',', ';' -> if (depth == 0) break
                '\n' -> if (depth == 0) {
                    val before = src.substring(start, i).trimEnd()
                    val after = src.substring(i).trimStart()
                    val continues = before.endsWith("+") || before.endsWith("else") || before.endsWith("?:") ||
                        after.startsWith("+") || after.startsWith("else ") || after.startsWith("?:") ||
                        (after.startsWith("\"") && !before.endsWith("\""))
                    if (!continues) break
                }
            }
            i++
        }
        return src.substring(start, i)
    }

    /** [literal] with its `$name` and `${expr}` templates removed, which is what the reader sees as fixed text. */
    private fun withoutTemplates(literal: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < literal.length) {
            if (literal[i] == '$' && i + 1 < literal.length && literal[i + 1] == '{') {
                var depth = 1
                i += 2
                while (i < literal.length && depth > 0) {
                    when (literal[i]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    i++
                }
            } else if (literal[i] == '$' && i + 1 < literal.length && (literal[i + 1].isLetter() || literal[i + 1] == '_')) {
                i++
                while (i < literal.length && (literal[i].isLetterOrDigit() || literal[i] == '_')) i++
            } else {
                sb.append(literal[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun isSentenceLike(fixed: String): Boolean {
        val words = fixed.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
        return words >= 2 || fixed.trimEnd().let { it.endsWith(".") || it.endsWith("!") || it.endsWith("?") }
    }

    /** Every top-level sink literal as `file:literal`, and how many sink sites were read. */
    private class SinkScan(val literals: MutableSet<String> = LinkedHashSet(), var sites: Int = 0)

    private fun scanSinks(fileName: String, rawSource: String, into: SinkScan) {
        val src = rawSource.lines().joinToString("\n") { if (it.trimStart().startsWith("//")) "" else it }
        fun take(literals: List<String>) { for (l in literals) into.literals += "$fileName:$l" }
        for (sink in sourceSinkCalls) {
            for (m in Regex("(?<![\\w])(?:\\w+\\.)*" + sink + "\\(").findAll(src)) {
                if (src.substring(maxOf(0, m.range.first - 4), m.range.first) == "fun ") continue
                into.sites++
                take(topLevelLiterals(balancedArgs(src, m.range.last + 1)))
            }
        }
        for (name in sourceSinkNames) {
            for (m in Regex("(?<![\\w.])" + name + "\\s*=(?![=>])\\s*").findAll(src)) {
                into.sites++
                take(topLevelLiterals(assignedValue(src, m.range.last + 1)))
            }
        }
        for (f in Regex("fun \\w*Confirm\\w*\\(").findAll(src)) {
            val end = src.indexOf("\n}\n", f.range.last).let { if (it < 0) src.length else it }
            val body = src.substring(f.range.last, end)
            for (t in Regex("TapeText\\(").findAll(body)) {
                into.sites++
                take(topLevelLiterals(balancedArgs(body, t.range.last + 1)).take(1))
            }
        }
    }

    /** The `file:literal` keys in [scan] that read as a caps sentence. */
    private fun capsSentences(scan: SinkScan): Set<String> = scan.literals.filterTo(LinkedHashSet()) { key ->
        val fixed = withoutTemplates(key.substringAfter(':'))
        fixed.any { it.isLetter() } && isSentenceLike(fixed) && readsAsAllCaps(fixed)
    }

    private fun scanTree(): SinkScan {
        val scan = SinkScan()
        var files = 0
        for (root in listOf(File("src/main"), File("../app/src/main"))) {
            require(root.isDirectory) { "expected ${root.absolutePath}: fix the path, do not delete the scan; a scan of nothing passes by accident." }
            for (f in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                files++
                scanSinks(f.name, f.readText(), scan)
            }
        }
        require(files > 300) { "only $files Kotlin files found under app/src/main and shell/src/main" }
        require(scan.sites > 400) { "only ${scan.sites} sink sites found: the scan has stopped seeing the sinks" }
        return scan
    }

    /**
     * Caps sentences that predate this law, one `file:literal` per entry. This list can only
     * shrink: [caseLawWaiverCeiling] is today's size and the stale-entry test fails on any
     * entry the scan no longer finds. Fix a site, delete its entry, lower the ceiling.
     * What is left is the MY VOICE and CONFLUENCE status lines and the gathering preview
     * status, which androidTests read back as exact text.
     */
    private val caseLawWaiver: Set<String> = setOf(
        "ConfluenceVoicesScreen.kt:SETTINGS CHANGED · HEAR BEFORE SAVING",
        "ConfluenceVoicesScreen.kt:\${selected.mode.name} PREVIEW READY",
        "ConfluenceVoicesScreen.kt:\${exact.settings.mode.name} SAVED ON \${PadNoteMap.labelForPad(slot)}",
        "ConfluenceVoicesScreen.kt:SAVED SETTINGS READY · HEAR BEFORE SAVING",
        "ConfluenceVoicesScreen.kt:PREVIEW STOPPED",
        "MyVoiceScreen.kt:STARTING MICROPHONE…",
        "MyVoiceScreen.kt:\${next.name} PREVIEW READY",
        "MyVoiceScreen.kt:VOICE SAVED · READY FOR A NEW PAD",
        "MyVoiceScreen.kt:\${selectedMode.name} SAVED ON \${PadNoteMap.labelForPad(slot)} · VOICE KEPT",
        "MyVoiceScreen.kt:SAVED VOICE READY",
        "MyVoiceScreen.kt:TRIM CHANGED · CHECK THIS PASSAGE",
        "MyVoiceScreen.kt:PREVIEW STOPPED",
        "MyVoiceScreen.kt:RECORDING · HOLD ONE STEADY AH OR OO",
        "PadSheetScreen.kt:PREVIEW READY · ORIGINAL KEPT",
    )
    private val caseLawWaiverCeiling = 14

    @Test
    fun `law - a sentence in a toast, dock line, note, hint or confirm is sentence case, wherever it is written`() {
        val unwaived = capsSentences(scanTree()) - caseLawWaiver
        assertTrue(
            unwaived.isEmpty(),
            "These sentences reach the user in ALL CAPS. A sentence is sentence case; a control, place or engine name inside it stays caps " +
                "(\"Record one on BEAT first.\"). Move each into Copy as a message. Do not add to the waiver.\n" + unwaived.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `law - the case law waiver only shrinks`() {
        val found = capsSentences(scanTree())
        val stale = caseLawWaiver - found
        assertTrue(stale.isEmpty(), "Waiver entries the scan no longer finds (fixed, or reworded): delete them and lower caseLawWaiverCeiling.\n" + stale.joinToString("\n") { "  $it" })
        assertEquals(caseLawWaiverCeiling, caseLawWaiver.size, "caseLawWaiverCeiling must equal the waiver's size: lower it as entries go; never raise it.")
    }

    @Test
    fun `law - the source scan sees a planted caps toast, dock line, hint and confirm, and lets a label inside a sentence pass`() {
        fun violations(source: String): Set<String> {
            val scan = SinkScan()
            scanSinks("Plant.kt", source, scan)
            return capsSentences(scan)
        }
        assertEquals(setOf("Plant.kt:KIT IS BUSY · TRY SAVE AGAIN"), violations("onToast(\"KIT IS BUSY · TRY SAVE AGAIN\")"))
        assertEquals(setOf("Plant.kt:LONG-PRESS TO CAPTURE"), violations("dock.say(if (a) \"Long-press to place.\" else \"LONG-PRESS TO CAPTURE\")"))
        assertEquals(setOf("Plant.kt:A LEARNED PAD PLAYS NO SOUND"), violations("KitPad(\n    hint = \"A LEARNED PAD PLAYS NO SOUND\",\n)"))
        assertEquals(
            setOf("Plant.kt:DELETE THIS KIT FOR GOOD"),
            violations("private fun KitDeleteConfirmDialog() {\n    TapeText(\"DELETE THIS KIT FOR GOOD\", TapeType.lcdSmall)\n    TapeText(\"DELETE\", TapeType.pixel)\n}\n"),
        )
        assertEquals(emptySet(), violations("onToast(\"Record one on BEAT first.\")"), "a label inside a sentence is the house style")
        assertEquals(emptySet(), violations("onToast(Copy.keptLabel(\"KEEP\"))"), "a control's name handed to a function is not a sentence")
        assertEquals(emptySet(), violations("onToast(\"\${pad.tag} saved.\")"), "a template is not text the reader reads as caps")
        assertEquals(emptySet(), violations("// onToast(\"COMMENTED OUT SHOUT\")"))
    }
}
