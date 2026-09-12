package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The September specs decided undo is a labelling problem first: every
 * destructive action names its way back at the moment it happens. These
 * hold that, and — through [noWayBack] — count the sites where the honest
 * label is "you can't", which is the recorded test for whether the
 * deferred undo stack is worth building.
 */
class ReversalTest {

    /** Same reflection [PersonalityTest] uses, for the same reason: a hand list goes stale. */
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

    /**
     * Words that mean something the user made is no longer where it was.
     *
     * Deliberately the vocabulary and not a curated list of constants: a
     * list would have to be edited by the person adding the twentieth
     * destructive toast, which is exactly the person not thinking about
     * reversibility. A word list catches them without their help.
     */
    private val destructive = listOf(
        "DELETE", "GONE", "REPLACE", "OVERWRIT", "WIPE", "EMPTIED", "FORGET",
        "FORGOT", "CLEARED", "REMOVED", "EJECT", "DISCARD", "THROWN", "BINNED",
        "ERASE", "PURGE", "UNDONE",
    )

    /**
     * Words that answer "can I get it back?" — in either direction.
     *
     * "NOTHING TAKES THIS ONE BACK" counts as naming a way back, because
     * the law is about the *question being answered*, not about the answer
     * being yes. A line that admits there is no way back is doing its job;
     * a line that says nothing either way is the defect.
     */
    private val answersIt = listOf(
        "BIN", "UNDO", "BACK", "DAYS", "RESTORE", "KEPT", "KEEPS", "SLEEPS",
        "RECOVER", "AGAIN", "STILL THERE", "CANNOT BE UNDONE", "FOR GOOD",
        "UNTOUCHED", "STAYS", "WAITS",
        // "ARE GONE" / "IS GONE", never the bare word: "GONE" is in
        // [destructive], and a token in both lists would let every
        // destructive line satisfy the law with the word that flagged it.
        "ARE GONE", "IS GONE",
    )

    /**
     * `Copy` constants that read as destructive but are not a destructive
     * action landing, so the law does not apply to them. Commented one by
     * one, in the same spirit as `PersonalityTest`'s `notASentence`: a rule
     * loose enough to guess these is a rule someone disables the first time
     * it is wrong.
     *
     * **This set is not the stack evidence — [noWayBack] is.** Nothing here
     * destroyed anything; these are refusals, warnings, legends and busy
     * lines that merely use the vocabulary.
     */
    private val notADestructiveLanding = mapOf(
        "SHELF_LEGEND" to "a legend under the shelf naming the gesture; nothing has happened yet",
        // Refusals: the delete did NOT happen, so there is nothing to take back.
        "KIT_DELETE_FAILED" to "the delete failed; nothing was destroyed",
        "SNIP_DELETE_FAILED" to "the delete failed; nothing was destroyed",
        "BIN_ITEM_GONE" to "the restore failed because it was already gone; this IS the way-back answer",
        // Busy lines, shown while the work runs.
        "ROOM_FORGET_BUSY" to "a progress line",
        // Reassurance that the screen destroys nothing at all.
        "DOUBLES_RULE" to "states that nothing here deletes, moves or merges",
        // A standing caveat on a screen, not an action's landing.
        "STACK_LOCKS" to "a standing caveat; the landing is Copy.stacked()",
        // A legend on the export card row, naming the gesture. Nothing has happened yet.
        "EXPORT_CARD_LEGEND" to "a legend, not a landing",
    )

    /**
     * Real gaps — a destructive landing that does not say whether it can be
     * undone — whose **words belong to the copy rewrite**, not to this
     * change.
     *
     * This change was scoped to write the new reversal lines plainly and
     * leave every line that was already on screen alone, because a separate
     * pass is rewriting `Copy` wholesale and two hands on the same sentence
     * is how one of them gets reverted. So these are recorded rather than
     * fixed, and the list is the handover: each one needs a clause saying
     * what happens next, in whatever voice that pass settles on.
     *
     * An entry here is a bug that is still open. It is not an exemption,
     * and the count is asserted below so the list cannot grow quietly.
     */
    private val awaitingWords = mapOf(
        "CARD_FORGOTTEN" to
            "says where dubs go now, never that picking the card again undoes it",
        "BIN_EMPTIED" to
            "the takes bin is emptied for good; 'THE MACHINE FORGETS, AS ASKED' does not say so",
    )

    /**
     * The sites whose honest label is "you can't", each with what is lost.
     *
     * **This is the stack evidence.** The September decision wrote the test
     * down: *if writing the honest label at a site requires saying "you
     * can't", that site is the argument for a stack.* Count these. The
     * count is asserted below so it cannot grow quietly — a new entry is a
     * deliberate act that shows up in a diff, and a run of them is the
     * argument arriving.
     */
    private val noWayBack = mapOf(
        "BAR_WIPED" to "the step editor autosaves the wipe; no control steps it back",
        "FORKED_TO_E_REPLACED" to "the old PROG E steps are overwritten in place",
        "kitBinEmptied" to "emptying a bin is the one delete the app promises is final",
        "snipBinEmptied" to "emptying a bin is the one delete the app promises is final",
        "roomBinEmptied" to "emptying a bin is the one delete the app promises is final",
    )

    private fun soundsDestructive(line: String) = destructive.any { it in line }

    private fun answersTheQuestion(line: String) = answersIt.any { it in line }

    @Test
    fun `every destructive line answers can I get it back`() {
        val all = copyStringConstants()
        assertTrue(
            all.size > 50,
            "reflection over Copy found only ${all.size} String constants — expected well over 50. " +
                "A build or classpath change may be hiding fields, which would make this law pass by " +
                "checking nothing; fix the reflection, don't lower this bound.",
        )

        val unanswered = mutableListOf<String>()
        var checked = 0
        for ((name, value) in all) {
            if (!soundsDestructive(value)) continue
            if (name in notADestructiveLanding) continue
            if (name in awaitingWords) continue
            checked++
            if (!answersTheQuestion(value)) unanswered += "Copy.$name = \"$value\""
        }

        assertTrue(
            checked >= 8,
            "this law found only $checked destructive lines to check. It is meant to cover the app's " +
                "deletes, wipes and replacements; if the vocabulary stopped matching them the law " +
                "would pass by checking almost nothing.",
        )
        assertTrue(
            unanswered.isEmpty(),
            "these lines destroy something and do not say whether it can be got back. Add the way " +
                "back from Reversal (BIN, MIND, UNDO), or say plainly that there is none with " +
                "Reversal.goneBut(...) and list it in noWayBack:\n  " + unanswered.joinToString("\n  "),
        )
    }

    /**
     * Anti-relaxation. Every exclusion must name a constant that exists, or
     * it sits there exempting nothing forever — and no constant may be in
     * both sets, which would be claiming a line is simultaneously not a
     * destructive landing and a destructive landing with no way back.
     */
    @Test
    fun `no exclusion is stale, and none is in both sets`() {
        val all = copyStringConstants()
        for (name in notADestructiveLanding.keys + noWayBack.keys + awaitingWords.keys) {
            assertTrue(
                name in all,
                "$name is listed as an exclusion but reflection found no such Copy String constant — " +
                    "remove the stale entry, or fix the rename it is tracking.",
            )
        }
        for ((a, b) in listOf(
            "notADestructiveLanding" to "noWayBack",
            "notADestructiveLanding" to "awaitingWords",
            "noWayBack" to "awaitingWords",
        )) {
            val sets = mapOf(
                "notADestructiveLanding" to notADestructiveLanding.keys,
                "noWayBack" to noWayBack.keys,
                "awaitingWords" to awaitingWords.keys,
            )
            val both = sets.getValue(a) intersect sets.getValue(b)
            assertTrue(both.isEmpty(), "listed in both $a and $b: $both")
        }
    }

    /**
     * Every entry in [noWayBack] must actually say so, in the app's own
     * words. Without this the set would be a way to silence the law rather
     * than a record of where it hurts — and the count below would be
     * counting exemptions instead of evidence.
     */
    @Test
    fun `a you-cannot site says you cannot, in the line itself`() {
        val all = copyStringConstants()
        for (name in noWayBack.keys) {
            val line = all.getValue(name)
            assertTrue(
                "CANNOT BE UNDONE" in line || "FOR GOOD" in line || "GONE" in line,
                "Copy.$name is recorded as having no way back, but its line does not say so: \"$line\"",
            )
        }
    }

    /**
     * The count, asserted so it cannot drift without someone deciding to
     * let it. Six is not yet an argument for a command history: three are
     * bin-emptying, which is *supposed* to be final, and one more is the
     * takes bin saying the same thing. The two that genuinely hurt are the
     * bar wipe and the PROG E replace.
     *
     * Raise this number only alongside a note in `docs/SPECS_2026_09.md`
     * §3 — that document is where the stack decision is recorded, and a
     * count that moves without it is the evidence going missing.
     */
    @Test
    fun `the count of you-cannot sites is the stack evidence`() {
        assertEquals(
            5, noWayBack.size,
            "the number of destructive sites with no way back changed. That is the recorded test for " +
                "the deferred undo stack, so update docs/SPECS_2026_09.md §3 in the same change.",
        )
        val reallyHurts = noWayBack.keys - setOf(
            "kitBinEmptied", "snipBinEmptied", "roomBinEmptied",
        )
        assertEquals(
            setOf("BAR_WIPED", "FORKED_TO_E_REPLACED"), reallyHurts,
            "the sites that lose work with no way back changed. Emptying a bin is meant to be final; " +
                "these are not, and they are what a stack would buy.",
        )
    }

    @Test
    fun `the one sentence about the bin is the one every bin tells`() {
        // Four objects sweep four bins on their own constants. The promise is
        // made once, here, and this is what stops the app saying 30 while the
        // sweep uses 7. Three are imported; the fourth is read from source.
        // Compared as Int: assertEquals(Double, Double, String) binds to the
        // tolerance overload, which would silently take the message as a
        // tolerance and compare nothing useful.
        assertEquals(Reversal.DAYS, KitBuilderModel.BIN_KEEP_DAYS.toInt(), "kit takes bin")
        assertEquals(Reversal.DAYS, SnipStore.BIN_DAYS.toInt(), "snips bin")
        assertEquals(Reversal.DAYS, Rooms.BIN_DAYS, "rooms bin")

        // The fourth sweeps from `:app`, which `:shell` cannot import, so it is
        // pinned by reading its source - the same way ConventionTest reaches
        // app code. Left unpinned it would be the one free to drift, and the
        // shelf is the bin a user is most likely to be counting on.
        val shelf = java.io.File("../app/src/main/kotlin/com/snipsnap/app/KitShelf.kt")
        assertTrue(shelf.isFile, "expected KitShelf at ${shelf.absolutePath} - has it moved?")
        val declared = Regex("""BIN_DAYS\s*=\s*([0-9.]+)""").find(shelf.readText(Charsets.UTF_8))
        assertTrue(declared != null, "KitShelf no longer declares BIN_DAYS - this pin is checking nothing")
        assertEquals(
            Reversal.DAYS.toDouble(), declared!!.groupValues[1].toDouble(), 0.0,
            "KitShelf.BIN_DAYS has drifted from the sentence Reversal.BIN promises",
        )

        // And no line may retype the number, which is how they drifted apart
        // in the first place: three Copy lines said "30 DAYS" as a literal.
        // Read the SOURCE, not the values - every line that correctly uses
        // Reversal.BIN has "30 DAYS" in its value, so a value scan would flag
        // the fix and miss nothing else.
        val src = java.io.File("src/main/kotlin/com/snipsnap/shell/Personality.kt")
        assertTrue(src.isFile, "expected Copy's source at ${src.absolutePath} - has the file moved?")
        val literal = Regex("""\b${Reversal.DAYS}\s+DAYS\b""")
        for ((i, line) in src.readLines().withIndex()) {
            val code = line.trim()
            if (code.startsWith("*") || code.startsWith("//")) continue
            assertFalse(
                literal.containsMatchIn(code),
                "Personality.kt:${i + 1} writes the bin retention as a literal: $code\n" +
                    "Use Reversal.BIN or Reversal.MIND so the sentence and the sweep cannot drift apart.",
            )
        }
    }

    @Test
    fun `goneBut lands on exactly one full stop, whatever it is handed`() {
        assertEquals(
            "THIS CANNOT BE UNDONE. TAP THE STEPS BACK IN.",
            Reversal.goneBut("TAP THE STEPS BACK IN"),
        )
        assertEquals(
            "THIS CANNOT BE UNDONE. TAP THE STEPS BACK IN.",
            Reversal.goneBut("TAP THE STEPS BACK IN."),
            "a caller who wrote a whole sentence must not get two full stops",
        )
    }

    /**
     * The new lines are written as plain labels rather than in the voice
     * the rest of `Copy` uses — but they are still `Copy` lines, so
     * `PersonalityTest`'s shout and full-stop laws apply to them exactly as
     * they do to everything else. Plain words, existing case.
     */
    @Test
    fun `the reversal lines still shout and land on a full stop`() {
        for (line in listOf(Reversal.BIN, Reversal.MIND, Reversal.UNDO, Reversal.GONE)) {
            assertEquals(line.uppercase(java.util.Locale.ROOT), line, "TapeOS shouts: $line")
            assertTrue(line.endsWith("."), "a line the app says, not furniture: $line")
        }
    }

    /**
     * The open-gap list cannot grow without someone deciding to let it.
     * Each entry is a destructive landing that says nothing about the way
     * back — a real defect, parked only because its words belong to the
     * copy rewrite.
     */
    @Test
    fun `the gaps awaiting words stay a short, known list`() {
        assertEquals(
            setOf("CARD_FORGOTTEN", "BIN_EMPTIED"), awaitingWords.keys,
            "the set of destructive lines that say nothing about the way back changed. Each one is " +
                "an open bug; if the copy rewrite has landed, fix the line and remove it from here " +
                "rather than adding another.",
        )
    }
}
