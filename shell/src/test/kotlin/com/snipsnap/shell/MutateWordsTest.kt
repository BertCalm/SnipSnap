package com.snipsnap.shell

import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MUTATE card's words, held to the house style
 * (`docs/superpowers/specs/2026-10-04-mutate-card-redesign-design.md`,
 * "Testing", the copy laws).
 *
 * The card's labels live in `MutateSheet`, where PersonalityTest's
 * reflective shout law cannot see them, and the toasts it shows are
 * `Copy` functions, which that law cannot see either (it reflects over
 * fields). So this file holds both by hand: one word for the one input,
 * PARTNER; no word that means two things on the card; every label in
 * capitals and inside its budget.
 */
class MutateWordsTest {

    /**
     * The words the redesign retires from MUTATE's user-facing copy: one
     * input had six names on and around the card (parent, partner,
     * neighbour, crate, deal, another kit). PARTNER is the one left.
     */
    private val retired = Regex("""\b(PARENTS?|NEIGHBOU?RS?|CRATES?|DEALS?)\b""")

    private fun assertNoRetiredWord(where: String, text: String) {
        val hit = retired.find(text.uppercase())
        assertTrue(hit == null, "$where says '${hit?.value}', a word MUTATE retired for PARTNER: '$text'")
    }

    @Test
    fun `the toasts MUTATE shows say PARTNER, never a retired word`() {
        assertEquals(
            "SPLICE: A01 × B07. THE TAKE BEFORE IT SLEEPS IN THE BIN.",
            Copy.mutated("SPLICE", "A01", "B07"),
        )
        assertEquals("UNDONE. THE TAKE BEFORE THE LAST MUTATE IS BACK FROM THE BIN.", Copy.UNMUTATED)
        assertEquals("THE SHELF HOLDS NO OTHER PAD. ROULETTE HAS NOTHING TO PICK.", Copy.CRATE_EMPTY)
        assertEquals("NOT A PARTNER: THAT FILE IS SILENT.", Copy.fileRefused("that file is silent"))
        assertEquals(
            "MUTATE (SPLICE WITH KIT A02) NEEDS ITS PARTNER - NOT CARRIED.",
            Copy.replayNeedsPartner("SPLICE", listOf("KIT A02")),
        )
        for ((where, text) in listOf(
            "Copy.mutated" to Copy.mutated("MORPH", "A02 SNARE", "SOUL B02"),
            "Copy.UNMUTATED" to Copy.UNMUTATED,
            "Copy.CRATE_EMPTY" to Copy.CRATE_EMPTY,
            "Copy.fileRefused" to Copy.fileRefused("too big for the tape"),
            "Copy.replayNeedsPartner" to Copy.replayNeedsPartner("BECOME", listOf("SOUL A03")),
            "Copy.drifted" to Copy.drifted("A02", "SOUL:B03"),
        )) {
            assertNoRetiredWord(where, text)
        }
    }

    // ---------- the card's own words (M1) ----------

    /**
     * The house already wrote the card's lines, on `design/mutate-v2/`'s
     * copy board, and nobody placed them: one line per move and each knob's
     * meaning. The card uses them verbatim, so this reads the board itself
     * and finds each word the card draws in it, character for character.
     */
    private val board = File("../design/mutate-v2/Moves.dc.html").readText(Charsets.UTF_8)

    @Test
    fun `the move lines, the knob meanings and STACK's line are the board's own, character for character`() {
        for (mode in Mutate.Mode.values()) {
            val line = MutateSheet.outcomeLine(mode, 0f)
            assertTrue(">$line</span>" in board, "$mode's line '$line' is not the board's (design/mutate-v2/Moves.dc.html)")
            val knob = MutateSheet.knobFor(mode)
            val meaning = MutateSheet.knobMeaning(mode)
            if (knob == null) {
                assertEquals(null, meaning, "$mode has no knob, so no meaning")
                assertTrue(">${MutateSheet.deadKnobLine}</span>" in board, "STACK's words '${MutateSheet.deadKnobLine}' are not the board's")
            } else {
                assertTrue(">${knob.label} · $meaning</span>" in board, "${knob.label}'s meaning '$meaning' is not the board's")
            }
        }
        assertEquals("BOTH AT ONCE. THICKER.", MutateSheet.outcomeLine(Mutate.Mode.STACK, 0f))
        assertEquals("MY TIMING, THEIR TONE.", MutateSheet.outcomeLine(Mutate.Mode.TRANSPLANT, 0f))
        assertEquals("NO KNOB — THEY LINE UP ON THE HIT", MutateSheet.deadKnobLine)
        // BECOME's lines are this spec's and the brief's, not the board's: pinned as written.
        assertEquals("HOW LONG THE TURN TAKES", MutateSheet.becomeMeaning(Mutate.Mode.MORPH))
        for (mode in Mutate.Mode.values().filter { it != Mutate.Mode.MORPH }) {
            assertEquals("ONLY MORPH TURNS OVER TIME", MutateSheet.becomeMeaning(mode), "$mode's BECOME row")
        }
    }

    /**
     * The move line describes what HEAR would play. On MORPH it turns to
     * BECOME's line exactly when the render would ramp, so it reads the
     * same mapping the render reads: below half a millisecond the knob
     * rounds to 0 and the hit does not turn, and the line must not say
     * it does. On every other move BECOME is ignored, so the line is too.
     */
    @Test
    fun `the MORPH line says the hit turns exactly when the render would turn it`() {
        val b = MutateSheet.BECOME
        val under = 0.4f / Mutate.MAX_BECOME_MS // 0.4 ms, which rounds to no ramp
        val over = 0.6f / Mutate.MAX_BECOME_MS // 0.6 ms, which rounds to a 1 ms ramp
        assertEquals(0, MutateSheet.value(b, under).roundToInt())
        assertEquals(1, MutateSheet.value(b, over).roundToInt())
        assertEquals("A HIT BETWEEN THE TWO.", MutateSheet.outcomeLine(Mutate.Mode.MORPH, 0f))
        assertEquals("A HIT BETWEEN THE TWO.", MutateSheet.outcomeLine(Mutate.Mode.MORPH, under))
        assertEquals("STARTS AS MINE, TURNS INTO THE MIX.", MutateSheet.outcomeLine(Mutate.Mode.MORPH, over))
        assertEquals("STARTS AS MINE, TURNS INTO THE MIX.", MutateSheet.outcomeLine(Mutate.Mode.MORPH, 1f))
        for (mode in Mutate.Mode.values().filter { it != Mutate.Mode.MORPH }) {
            assertEquals(MutateSheet.outcomeLine(mode, 0f), MutateSheet.outcomeLine(mode, 1f), "$mode ignores BECOME, so its line does")
        }
    }

    /** A name of exactly 24 characters, the longest a pad or partner name runs (the TERRA spec's MAX_FROM_CHARS). */
    private val long24 = "ABCDEFGHIJKLMNOPQRSTUVWX"

    /** Every kind of partner, each with a 24-character name where it has one. */
    private fun partners(name: String): List<MutateSheet.Partner> = listOf(
        MutateSheet.Partner.Pad(23),
        MutateSheet.Partner.Other(name, File("other"), 18),
        MutateSheet.Partner.Deal("$name:A03", File("deal.wav"), 1),
        MutateSheet.Partner.Room(name, File("room.wav")),
        MutateSheet.Partner.Wav(name, File("held.wav")),
    )

    /** The pad tag a partner's name must always keep whole, or null for a room or a file. */
    private fun tagOf(p: MutateSheet.Partner): String? = when (p) {
        is MutateSheet.Partner.Pad -> MutateSheet.padTag(p.slot)
        is MutateSheet.Partner.Other -> MutateSheet.padTag(p.slot)
        is MutateSheet.Partner.Deal -> "A03"
        is MutateSheet.Partner.Room, is MutateSheet.Partner.Wav -> null
    }

    @Test
    fun `a partner is named by its tag and name, in capitals, never past 24 characters, never without its tag`() {
        val names = mapOf(23 to "kick")
        assertEquals("B07 KICK", MutateSheet.partnerName(MutateSheet.Partner.Pad(23)) { names[it] })
        assertEquals("B07", MutateSheet.partnerName(MutateSheet.Partner.Pad(23)))
        assertEquals("SOUL B02", MutateSheet.partnerName(MutateSheet.Partner.Other("Soul", File("x"), 18)))
        assertEquals("SOUL A03", MutateSheet.partnerName(MutateSheet.Partner.Deal("Soul:A03", File("x"), 4)))
        assertEquals("FUNK ROOM", MutateSheet.partnerName(MutateSheet.Partner.Room("Funk Room", File("x"))))
        assertEquals("CLAP.WAV", MutateSheet.partnerName(MutateSheet.Partner.Wav("clap.wav", File("x"))))
        assertEquals("B07", MutateSheet.partnerShort(MutateSheet.Partner.Pad(23)), "KEEP names a pad on this kit by its tag alone")
        assertEquals("SOUL B02", MutateSheet.partnerShort(MutateSheet.Partner.Other("Soul", File("x"), 18)))

        for (len in 1..30) {
            val name = long24.repeat(2).take(len)
            for (p in partners(name)) {
                val long = MutateSheet.partnerName(p) { name }
                val short = MutateSheet.partnerShort(p)
                for ((which, text) in listOf("partnerName" to long, "partnerShort" to short)) {
                    assertTrue(text.length <= MutateSheet.NAME_CHARS, "$which of $p runs ${text.length} characters: '$text'")
                    assertEquals(text.uppercase(), text, "$which of $p does not shout: '$text'")
                    tagOf(p)?.let { tag -> assertTrue(tag in text, "$which of $p lost its tag $tag: '$text'") }
                    if (tagOf(p) == null) {
                        assertTrue(text.startsWith(name.uppercase().take(8)), "$which of $p lost the first 8 characters of its name: '$text'")
                    }
                }
            }
        }
    }

    @Test
    fun `the pairing line names the pad and its partner within one row, and never cuts a tag or the empty state`() {
        assertEquals("A02 SNARE × B07 KICK", MutateSheet.pairLine("A02", "snare", "B07 KICK"))
        assertEquals("A02 SNARE × ?  — PICK A PARTNER", MutateSheet.pairLine("A02", "snare", null))
        assertEquals("A02 SNARE × ?  — PICK A PARTNER", MutateSheet.pairSpoken("A02", "snare", null))
        // Both sides too long for the row: each gets an even share, its tag kept whole, the cut ending in an ellipsis.
        assertEquals(
            "A02 ABCDEFGHIJKLMNO… × ABCDEFGHIJKLMNOP… B02",
            MutateSheet.pairLine("A02", long24, MutateSheet.partnerName(MutateSheet.Partner.Other(long24, File("x"), 18))),
        )
        assertEquals("A02 ABCDEFGHIJKLMNOPQ… × ?  — PICK A PARTNER", MutateSheet.pairLine("A02", long24, null))
        // A pad with no name, a name of spaces, or one typed in lower case: no trailing space, all
        // capitals, and TalkBack reads the same words.
        assertEquals("A02 × B07 KICK", MutateSheet.pairLine("A02", "", "B07 KICK"))
        assertEquals("A02 × B07 KICK", MutateSheet.pairLine("A02", "   ", "B07 KICK"))
        assertEquals("A02 × ?  — PICK A PARTNER", MutateSheet.pairLine("A02", "", null))
        assertEquals("A02 × B07 KICK", MutateSheet.pairSpoken("A02", " ", "B07 KICK"))
        assertEquals("A02 SNARE ROLL × B07", MutateSheet.pairLine("A02", "snare roll", "B07"))

        for (len in 1..24) {
            val padName = long24.take(len)
            val emptyLine = MutateSheet.pairLine("A02", padName, null)
            assertTrue(emptyLine.length <= MutateSheet.ROW_CHARS, "the empty state runs ${emptyLine.length}: '$emptyLine'")
            assertTrue(emptyLine.startsWith("A02") && emptyLine.endsWith(" × ?  — PICK A PARTNER"), "the empty state was cut: '$emptyLine'")
            for (p in partners(long24)) {
                val partnerName = MutateSheet.partnerName(p) { long24 }
                val line = MutateSheet.pairLine("A02", padName, partnerName)
                assertTrue(line.length <= MutateSheet.ROW_CHARS, "'$line' runs ${line.length}, past the row's ${MutateSheet.ROW_CHARS}")
                assertEquals(line.uppercase(), line, "'$line' does not shout")
                assertTrue(line.startsWith("A02"), "the pad's tag was cut: '$line'")
                val partnerSide = line.substringAfter(" × ")
                assertTrue(partnerSide.isNotEmpty() && partnerSide != "…", "the partner was cut away: '$line'")
                tagOf(p)?.let { tag -> assertTrue(partnerSide.contains(tag), "the partner's tag $tag was cut: '$line'") }
                assertEquals(
                    "A02 ${padName.uppercase()} × $partnerName",
                    MutateSheet.pairSpoken("A02", padName, partnerName),
                    "TalkBack reads the pair uncut",
                )
            }
        }
    }

    @Test
    fun `every card label shouts, fits its row, and uses no retired word`() {
        val rows = mapOf(
            "HEAR_LABEL" to MutateSheet.HEAR_LABEL,
            "NOTE_LINE" to MutateSheet.NOTE_LINE,
            "KEEP_LABEL" to MutateSheet.KEEP_LABEL,
            "DRIFT_LABEL" to MutateSheet.DRIFT_LABEL,
            "ROULETTE_LABEL" to MutateSheet.ROULETTE_LABEL,
            "NO_ROOM_LINE" to MutateSheet.NO_ROOM_LINE,
            "NO_OTHER_KIT_LINE" to MutateSheet.NO_OTHER_KIT_LINE,
            "deadKnobLine" to MutateSheet.deadKnobLine,
        ) + Mutate.Mode.values().flatMap { mode ->
            listOfNotNull(
                "outcomeLine($mode)" to MutateSheet.outcomeLine(mode, 0f),
                "outcomeLine($mode, BECOME)" to MutateSheet.outcomeLine(mode, 1f),
                MutateSheet.knobMeaning(mode)?.let { "knobMeaning($mode)" to it },
                "becomeMeaning($mode)" to MutateSheet.becomeMeaning(mode),
            )
        }
        assertEquals("▶ HEAR THE RESULT", MutateSheet.HEAR_LABEL)
        assertEquals("NOTHING CHANGES YOUR PAD UNTIL YOU KEEP IT", MutateSheet.NOTE_LINE)
        assertEquals("KEEP", MutateSheet.KEEP_LABEL)
        assertEquals("DRIFT · BLEND & SAVE", MutateSheet.DRIFT_LABEL)
        assertEquals("ROULETTE · PICK A PARTNER OFF THE SHELF", MutateSheet.ROULETTE_LABEL)
        assertEquals("NO ROOM YET · MAKE ONE IN OUTSIDE ▸ ROOM", MutateSheet.NO_ROOM_LINE)
        assertEquals("NO OTHER KIT ON THE SHELF", MutateSheet.NO_OTHER_KIT_LINE)
        for ((name, text) in rows) {
            assertEquals(text.uppercase(), text, "MutateSheet.$name does not shout: '$text'")
            assertTrue(text.length <= MutateSheet.ROW_CHARS, "MutateSheet.$name runs ${text.length}, past the row's ${MutateSheet.ROW_CHARS}: '$text'")
            assertNoRetiredWord("MutateSheet.$name", text)
        }
        // DRIFT shares its row with KEEP: a half row holds 21 characters (the house shortened ROULETTE's
        // and DRIFT's labels to fit after a screenshot showed longer ones ellipsizing).
        assertTrue(MutateSheet.DRIFT_LABEL.length <= 21, "DRIFT's label overflows its half row")
    }

    /**
     * One word, one meaning, on the card. KEEP is the card's commit verb,
     * so it appears as that and in the note line that names it, never as
     * OUTSIDE's KEEP ROOM. A take is a bin file in MUTATE's copy, so TAKES
     * as a noun (`THE TAKES`, `ITS TAKES`, `2 TAKES`), a round-robin's
     * takes, never appears; the verb is fine (`HOW LONG THE TURN TAKES`).
     * GHOSTS is CHOP's word, not the pad sheet's.
     */
    @Test
    fun `KEEP is only the commit verb on the card, and no card word means two things`() {
        val labels = listOf(
            MutateSheet.HEAR_LABEL, MutateSheet.NOTE_LINE, MutateSheet.DRIFT_LABEL, MutateSheet.ROULETTE_LABEL,
            MutateSheet.NO_ROOM_LINE, MutateSheet.NO_OTHER_KIT_LINE, MutateSheet.deadKnobLine,
        ) + Mutate.Mode.values().flatMap { listOfNotNull(MutateSheet.outcomeLine(it, 1f), MutateSheet.knobMeaning(it), MutateSheet.becomeMeaning(it)) }
        for (text in labels + MutateSheet.KEEP_LABEL) {
            assertTrue("KEEP ROOM" !in text, "'$text' says KEEP ROOM, OUTSIDE's button, on the card whose commit verb is KEEP")
            assertTrue(Regex("""\b(THE|ITS|OF|\d+)\s+TAKES\b""").find(text) == null, "'$text' says TAKES as a noun, a round-robin's word")
            assertTrue("GHOSTS" !in text, "'$text' says GHOSTS, CHOP's word")
        }
        val keepers = labels.filter { Regex("""\bKEEP\b""").containsMatchIn(it) }
        assertEquals(listOf(MutateSheet.NOTE_LINE), keepers, "KEEP appears on the card outside the commit button and the note line")
    }
}
