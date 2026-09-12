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
        // The bin as a destination or a holder — never the bare word, which
        // "BIN EMPTIED" also contains while saying the opposite. That loophole
        // let a genuinely irreversible line satisfy this law with the noun that
        // flagged it, and awaitingWords was quietly masking it.
        "THE BIN KEEPS", "IN THE BIN", "TO THE BIN", "FROM THE BIN",
        // Accept the same direction words without "THE" too.
        "IN BIN", "TO BIN", "FROM BIN",
        "UNDO", "BACK", "DAYS", "RESTORE", "KEPT", "KEEPS", "SLEEPS",
        "RECOVER", "STILL THERE", "CANNOT BE UNDONE", "FOR GOOD",
        "UNTOUCHED", "STAYS", "WAITS",
        // "AGAIN" was here and is gone. It is a RETRY instruction, not a way
        // back: "DUB AGAIN TO WRITE OVER IT" tells you how to repeat the act,
        // not how to reverse it. It was the only thing letting
        // `dubWouldOverwrite` through, and a genuinely destructive line
        // reading "ITEM REPLACED. TRY AGAIN." would have passed on it too.
        // Removing it is what forced [templatedNotALanding] to exist, which is
        // the honest mechanism: say a line is not a landing, rather than
        // pretend it answered.
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
        // TWINS_KEEP_OWN arrived from PR #136 while #137 was open, and broke
        // the default branch: `App.kt`'s `evilTwins` checks `ownPadsOnBankB`
        // and returns before touching a pad, so the line names a wipe that did
        // NOT happen - but it says WIPE and EJECT, so the law claimed it.
        //
        // Worth recording how it was missed. #137 was green on its own branch
        // and red on CI, because CI tests a PR MERGED WITH ITS BASE and the
        // base had moved. That gap is exactly what a law over shared copy is
        // for, and it is the one case a green local run structurally cannot
        // cover: another branch can add a line to the same file at any time.
        "TWINS_KEEP_OWN" to "REMIX refused; the pads were not wiped",
        // RE-TRIM refused: `Retrim.of` returns Refused(RETRIM_TAPE_GONE) at
        // Retrim.kt:130 and :132 and the pad is untouched. It was passing the
        // law for the WRONG REASON - "KEEPS", from "THE PAD KEEPS WHAT IT
        // HAS", which is not a recovery answer for the missing tape. A line
        // that passes by accident is a line the law is not really holding.
        "RETRIM_TAPE_GONE" to "RE-TRIM refused; the pad was never changed",
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
     * `Copy` **functions** that read as destructive but are not a destructive
     * landing — the source scan's own version of [notADestructiveLanding],
     * which is keyed by constant name and so cannot reach them.
     *
     * This set exists because "AGAIN" was removed from [answersIt]. While it
     * was there, `dubWouldOverwrite` satisfied the law with "DUB AGAIN TO
     * WRITE OVER IT" — a retry instruction, not a way back — and the scan had
     * no way to say "this one is not a landing" other than to keep a bad
     * token. A law that needs a wrong answer to stay green is not holding
     * anything; this is the honest version.
     *
     * The distinction the set records is **warning versus landing**: a
     * warning says what the NEXT tap would do, and nothing has happened yet,
     * so there is nothing to take back. A landing says it happened.
     */
    private data class NotALanding(
        /** Why the law does not apply. */
        val why: String,
        /**
         * The phrase that MAKES it not a landing, which must still be in the
         * function's source.
         *
         * Without this the entry is an unconditional bypass: change
         * `dubWouldOverwrite` to return "ITEM REPLACED. DUB AGAIN TO WRITE
         * OVER IT." and it is a destructive landing that the law skips
         * forever. Found in review, and it is the same fault this whole file
         * keeps producing — an exclusion nobody re-checks. Every other set
         * here is validated (a parked gap must still be a gap, a
         * you-cannot site must say so); this one was not.
         */
        val stillTrue: String,
    )

    private val templatedNotALanding = mapOf(
        // Its own KDoc: "nothing has been written when this appears." The tap
        // that overwrites is the next one, and this line exists so that tap is
        // a decision rather than a dare. "IS ALREADY THERE" is the clause that
        // says so: it describes the CURRENT state of the file, before anything
        // is written. A post-write rewrite loses it, and the guard fires.
        "dubWouldOverwrite" to NotALanding(
            why = "a warning before the act, not a landing",
            stillTrue = "IS ALREADY THERE",
        ),
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
     * The same law over `Personality.kt`'s own source text, because the
     * reflective scan above sees **fields only** — and `Copy`'s destructive
     * *functions* are invisible to it.
     *
     * Found in review, and it is exactly the hole this file exists to
     * close. The mutation that "proved" the law worked removed a way back
     * from a constant. Removing the `Reversal.MIND` reference from `kitDeleted`
     * instead left the whole suite green — a law that cannot catch the
     * regression it was written for, which is the same failure
     * `ConventionTest`'s voice-allocation law shipped with in September.
     * Checking the value was never enough; the templated toasts have to be
     * read from source, the way `PersonalityTest`'s ceiling law already
     * reads them.
     *
     * A `Reversal.` reference counts as answering: naming the shared
     * constant *is* naming the way back, and it is the form this change
     * wants callers to use.
     */
    @Test
    fun `destructive templated toasts answer it too`() {
        val lines = personalitySource().readLines()
        val declares = Regex("""\bfun\s+(\w+)\s*\(""")
        val stringLiteral = Regex(""""((?:[^"\\]|\\.)*)"""")

        val unanswered = mutableListOf<String>()
        var checked = 0
        for ((i, raw) in lines.withIndex()) {
            val head = raw.trim()
            if (isComment(head)) continue
            if (!declares.containsMatchIn(head) || ": String" !in head) continue
            // The declaration plus its whole body, bounded by INDENTATION:
            // read on while lines are indented deeper than the declaration,
            // stop at the first that is not. A `Copy` member sits at one
            // indent and the next member sits at the same one, so this ends
            // exactly where the member does.
            //
            // Two earlier bounds were both wrong, in opposite directions.
            // Unbounded, the window swept into the FOLLOWING constant and
            // charged `noDoubles` with DOUBLES_RULE's "NOTHING HERE DELETES,
            // MOVES OR MERGES". Bounded by "the next val/var", it stopped at a
            // LOCAL val inside the body - so a function with a neutral name
            // and its destructive string returned further down, the shape
            // `imported` and `grooveRead` already use, was cut off before the
            // law ever saw the string. Found in review. Indentation knows the
            // difference between a member and a local; a keyword does not.
            val indent = raw.indexOfFirst { !it.isWhitespace() }
            val body = mutableListOf(head)
            for (next in lines.subList(i + 1, lines.size)) {
                if (next.isBlank()) continue
                if (next.indexOfFirst { !it.isWhitespace() } <= indent) break
                val t = next.trim()
                if (isComment(t)) continue
                body += t
            }
            val window = body.joinToString(" ")
            // The function NAME plus its string literals — never the rest of the
            // code. Two lessons, both learned the hard way here:
            //
            // The name has to be in, because the destructive verb often lives
            // only there: `kitDeleted` says "IS OFF THE SHELF" and `snipDeleted`
            // "IS OFF THE LIST", so scanning literals alone matched nothing at
            // all and the law went back to checking nothing.
            //
            // The code has to be out, because Kotlin's own method names collide
            // with the vocabulary: `.replace('_', ' ')` inside `desampleFar` — a
            // refusal that destroys nothing — matched REPLACE.
            val name = declares.find(head)?.groupValues?.get(1).orEmpty()
            // Interpolations are stripped from the literal before matching:
            // `${'$'}{voice.uppercase().replace('_', ' ')}` sits INSIDE the string, so
            // excluding the surrounding code was not enough — `desampleFar`,
            // a refusal that destroys nothing, still matched REPLACE.
            val said = stringLiteral.findAll(window)
                .joinToString(" ") { it.groupValues[1] }
                .replace(Regex("""\$\{[^}]*\}"""), " ")
                .replace(Regex("""\$\w+"""), " ")
            val text = "$name $said".uppercase(java.util.Locale.ROOT)
            if (!soundsDestructive(text)) continue
            if (name in templatedNotALanding) continue
            checked++
            if (answersTheQuestion(text) || "Reversal." in window) continue
            unanswered += "Personality.kt:${i + 1}  $head"
        }

        assertTrue(
            unanswered.isEmpty(),
            "these templated toasts destroy something and do not say whether it can be got back:\n  " +
                unanswered.joinToString("\n  "),
        )

        // Sanity: the scan must actually reach the three functions this change
        // routed through Reversal. Without this it could pass by matching
        // nothing at all, which is how the reflective law missed them.
        assertTrue(checked >= 3, "the source scan found only $checked destructive templated toasts - it is matching almost nothing")
        for (name in listOf("kitDeleted", "snipDeleted", "roomForgotten")) {
            assertTrue(
                lines.any { "fun $name(" in it && "Reversal." in it },
                "Copy.$name no longer names its way back through Reversal. It is a function, so the " +
                    "reflective law cannot see it and this assertion is the only thing holding it.",
            )
        }
    }

    private fun isComment(line: String) =
        line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")


    /** `:shell`'s own project dir is the working dir, as `PersonalityTest`'s ceiling law assumes too. */
    private fun personalitySource(): java.io.File {
        val f = java.io.File("src/main/kotlin/com/snipsnap/shell/Personality.kt")
        assertTrue(f.isFile, "expected Copy's source at ${f.absolutePath} - has the file moved?")
        assertTrue(f.readLines().size > 500, "Personality.kt looks truncated - a source scan that reads nothing passes by checking nothing")
        return f
    }

    /**
     * [templatedNotALanding] earns its exemptions twice over: the function
     * must still exist, and the phrase that makes it a warning rather than a
     * landing must still be in it.
     *
     * Both halves were found by review rather than by me, one round apart.
     * The set shipped with no staleness guard at all — an entry naming a
     * function that does not exist passed silently — and then with a guard
     * that only checked the name, which left the exemption unconditional: a
     * rewrite to a post-write message would have kept the bypass.
     */
    @Test
    fun `no templated exclusion is stale, and each is still a warning`() {
        val src = personalitySource().readText(Charsets.UTF_8)
        for ((name, entry) in templatedNotALanding) {
            val decl = Regex("""\bfun\s+${Regex.escape(name)}\s*\([^\n]*""").find(src)
            assertTrue(
                decl != null,
                "templatedNotALanding lists Copy.$name (${entry.why}) but Personality.kt declares no " +
                    "such function - remove the stale entry, or fix the rename it is tracking.",
            )
            assertTrue(
                entry.stillTrue in decl!!.value,
                "Copy.$name is exempted as \"${entry.why}\", which held because its line said " +
                    "\"${entry.stillTrue}\". It no longer does:\n  ${decl.value.trim()}\n" +
                    "If it has become a landing, delete the exemption and give it a way back. If it " +
                    "is still a warning, say so in a new stillTrue phrase - deliberately, not by " +
                    "widening the match.",
            )
        }
    }

    /**
     * Anti-relaxation. Every exclusion must name a constant that exists, or
     * it sits there exempting nothing forever — and no constant may be in
     * two sets at once, which would be claiming a line is simultaneously not
     * a destructive landing and a destructive landing with no way back.
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
     * let it. Five is not yet an argument for a command history: three of
     * them are bin-emptying, which is *supposed* to be final. The two that
     * genuinely hurt are the bar wipe and the PROG E replace — both on one
     * screen, inside one feature.
     *
     * The takes bin is a sixth irreversible site and is deliberately NOT
     * counted here: its line does not yet say so, which puts it in
     * [awaitingWords] until the copy rewrite gives it words. When it gets
     * them it moves into [noWayBack] and this number becomes six.
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
        // Compared as Double with zero tolerance, never via toInt(): these are
        // Double policies, and truncating them would let a bin sweep at 30.5
        // days pass while the app promises 30. The explicit 0.0 also picks the
        // tolerance overload deliberately - assertEquals(Double, Double, String)
        // would otherwise bind the message as a tolerance and compare nothing.
        val promised = Reversal.DAYS.toDouble()
        assertEquals(promised, KitBuilderModel.BIN_KEEP_DAYS, 0.0, "kit takes bin")
        assertEquals(promised, SnipStore.BIN_DAYS, 0.0, "snips bin")
        assertEquals(promised, Rooms.BIN_DAYS.toDouble(), 0.0, "rooms bin")

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
        val src = personalitySource()
        // Any numeric retention literal, not just today's value: interpolating
        // Reversal.DAYS would let a stale "30 DAYS" survive a policy change to 7,
        // which is precisely the drift this law exists to stop.
        val literal = Regex("""\b\d+\s+DAYS\b""")
        for ((i, line) in src.readLines().withIndex()) {
            val code = line.trim()
            if (isComment(code)) continue
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

    /**
     * Every parked gap must still *be* a gap.
     *
     * Found in review. The main law skips [awaitingWords] outright, so a
     * line the copy rewrite fixes while leaving its key here would stay
     * parked as an open bug forever — and, worse, would never be covered
     * by the law again. This fails the moment one is fixed, which is the
     * only reliable prompt to remove it.
     */
    @Test
    fun `a parked gap that has been fixed must be unparked`() {
        val all = copyStringConstants()
        for ((name, why) in awaitingWords) {
            val line = all.getValue(name)
            assertTrue(
                soundsDestructive(line),
                "Copy.$name no longer reads as destructive (\"$line\"), so it is not a gap. " +
                    "Remove it from awaitingWords.",
            )
            assertFalse(
                answersTheQuestion(line),
                "Copy.$name now says what happens next (\"$line\") — the gap is closed ($why). " +
                    "Remove it from awaitingWords so the law covers this line again, and move it to " +
                    "noWayBack if the answer turned out to be that there is no way back.",
            )
        }
    }
}
