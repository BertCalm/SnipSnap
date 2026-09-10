package com.snipsnap.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `OFF is respected everywhere without argument`() {
        assertFalse(Delight.toastsEnabled(Personality.OFF))
        assertFalse(Delight.quipsEnabled(Personality.OFF))
        assertFalse(Delight.deckSoundsEnabled(Personality.OFF, captureArmed = false))
    }

    @Test
    fun `quips need FULL, toasts settle for MILD`() {
        assertTrue(Delight.toastsEnabled(Personality.MILD))
        assertFalse(Delight.quipsEnabled(Personality.MILD))
        assertTrue(Delight.toastsEnabled(Personality.FULL))
        assertTrue(Delight.quipsEnabled(Personality.FULL))
    }

    @Test
    fun `law 2 - deck sounds hard-mute while capture is armed, at any level`() {
        for (level in Personality.entries) {
            assertFalse(
                Delight.deckSoundsEnabled(level, captureArmed = true),
                "$level: a deck thunk must never land inside a snip",
            )
        }
        assertTrue(Delight.deckSoundsEnabled(Personality.FULL, captureArmed = false))
    }

    @Test
    fun `commit lines rotate in order and wrap`() {
        assertEquals("TAPED. NO TAKEBACKS.", Copy.rotating(Copy.COMMIT_LINES, 0))
        assertEquals("IT'S OURS NOW.", Copy.rotating(Copy.COMMIT_LINES, 1))
        assertEquals(
            Copy.rotating(Copy.COMMIT_LINES, 0),
            Copy.rotating(Copy.COMMIT_LINES, Copy.COMMIT_LINES.size),
        )
    }

    @Test
    fun `law 3 - funny copy still says exactly what happened`() {
        // The capture-blocked box names the problem and the way out.
        assertTrue("SCREEN RECORDER" in Copy.CAPTURE_BLOCKED)
        // A session the phone ended names the two ways that happens.
        assertTrue("LOCK SCREEN" in Copy.PHONE_STOPPED_TAPE)
        assertTrue("STOP CHIP" in Copy.PHONE_STOPPED_TAPE)
        // A refused consent must not claim anything is armed.
        assertTrue("NOTHING ARMED" in Copy.INSIDE_REFUSED)
        // The Ear reports what it heard, in real numbers.
        assertEquals("HEARD 12 HITS OVER 2 BARS AT ~93 BPM. THEY PLAY ON YOUR PADS NOW.", Copy.grooveRead(12, 2, 93))
        assertEquals("HEARD 4 HITS OVER 1 BAR AT ~120 BPM. THEY PLAY ON YOUR PADS NOW.", Copy.grooveRead(4, 1, 120))
        assertEquals("NO GROOVE: NO BEAT HEARD - THE EAR FINDS HITS, NOT TONES.", Copy.grooveRefused("no beat heard - the ear finds hits, not tones."))
        assertEquals("BREAK FOUND AT 1:12-1:20. IN AND OUT ARE SET. INSTANT KIT IS ONE TAP AWAY.", Copy.dug("1:12", "1:20"))
        // Send-to-grid reports the real slice count.
        assertEquals("7 SLICES ON THE GRID. CHOKE GROUP SET.", Copy.sentToGrid(7, chokeSet = true))
        assertEquals("3 SLICES ON THE GRID.", Copy.sentToGrid(3, chokeSet = false))
    }

    @Test
    fun `hidden eggs answer only their triggers`() {
        assertEquals("VERY CREATIVE.", Copy.kitNameResponse("TEST"))
        assertEquals("VERY CREATIVE.", Copy.kitNameResponse("  test "))
        assertNull(Copy.kitNameResponse("Regroove"))
        assertEquals("ELITE.", Copy.bpmResponse(133.7f))
        assertNull(Copy.bpmResponse(120f))
        // Konami: 8 steps, all on the 4x4 grid.
        assertEquals(8, Copy.KONAMI_PADS.size)
        assertTrue(Copy.KONAMI_PADS.all { it in 1..16 })
    }

    @Test
    fun `boot sequence ends ready`() {
        assertEquals("READY.", Copy.BOOT_LINES.last())
        assertTrue(Copy.STATUS_QUIPS.isNotEmpty())
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
     * The legend that replaced the expiring toast (UAT findings 4 and 5).
     * It has to name the gesture, because it is the only thing on screen
     * that does — the toast it backstops can be dismissed forever.
     */
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
        "IMPORT_BUSY", "PACKING_BUSY", "LANDING_BUSY", "READ_GROOVE_BUSY", "DIG_BUSY", "FEEL_BUSY", "BREEDING_BUSY",
        "ARRANGE_MIXING", "XRAY_BUSY",
        "CHOP_ALL_BUSY",
        "OUTSIDE_LISTENING", "ROOM_FORGET_BUSY", "ROOM_RESTORE_BUSY", "ROOM_BIN_EMPTY_BUSY", "KIT_DELETE_BUSY", "KIT_RENAME_BUSY",
        "CHIP_NOT_SURE", "CHIP_OVERRIDDEN",
        "EXPORT_SAVED_TO", "EXPORT_SHARE_LABEL", "CARD_NONE", "CARD_PICKED",
        "KONAMI_UNLOCK",
        "SHELF_SORT_RECENT", "SHELF_SORT_ALPHA",
        // Permanent on-screen furniture, not toasts: the legend that sits under
        // KIT's grid for as long as the screen is open, and HELP's two section
        // headings. ROOMS' own legend ("HOLD A ROOM TO FORGET IT · THE BIN KEEPS
        // 30 DAYS") reads without a full stop for the same reason - it is a label
        // on the furniture, not a line the app says to you once and takes away.
        "PAD_SHEET_LEGEND", "HELP_LOOP_HEADER", "HELP_MORE_HEADER",
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
        "HUMANIZED", "FORKED_TO_E", "BAR_WIPED", "GHOSTS_ON",
        "INSTRUMENT_MADE", "NO_PITCH", "RETREAT_REFUSED",
        "TAKES_BIN_RULE", "TEACH_CONSENT", "SNAPPED",
        "TAKES_EMPTY", "BIN_EMPTY_STATE", "BIN_ITEM_GONE", "KIT_WONT_OPEN",
        "UNMUTATED", "MUTATE_NEEDS_ONE", "CRATE_EMPTY",
        "SCULPTED", "STRETCHED", "FROZEN", "PAD_MADE", "PAD_TOO_SHORT", "PAD_TOO_LONG",
        "IN_KEY_NONE", "IN_KEY_NEEDS_KEY", "TAPE_TOO_BIG",
    )

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
        assertEquals("ONE TAP. 8 SLICES ON THE GRID. CHOKE GROUP SET.", Copy.instantKit(8, true))
        assertEquals("ONE TAP. 5 SLICES ON THE GRID.", Copy.instantKit(5, false))
        assertEquals("3 PADS RETUNED INTO A MINOR. THE KICK IS UNTOUCHED.", Copy.inKey(3, "A MINOR"))
        assertTrue(Copy.takeRestored("T3").startsWith("T3 RESTORED."), "the take leads its own toast")
        assertTrue(
            Copy.treated("CRUSH", "A02").startsWith("CRUSH ON A02."),
            "the treatment and the pad both lead their own toast",
        )
        assertTrue(Copy.treated("CRUSH", "A02").endsWith("."), "and still lands on a full stop")
        assertEquals("TUNE ON A02, IN C MAJOR. ORIGINAL SLEEPS IN THE BIN.", Copy.keyed("TUNE", "A02", "C MAJOR"))
        assertEquals("A02 DRIFTED TOWARD Other:B03. ORIGINAL SLEEPS IN THE BIN.", Copy.drifted("A02", "Other:B03"))
        assertEquals("A03 IS A HAT CLOSED PATCH NOW, 0.12 AWAY. ORIGINAL SLEEPS IN THE BIN.", Copy.desampled("A03", "HAT_CLOSED", 0.123f))
        assertEquals("NO PATCH IS NEAR. THE CLOSEST IS A SNARE, 0.61 AWAY.", Copy.desampleFar("SNARE", 0.61f))
        assertEquals("NOT A NOTE: A KICK IS A DRUM, NOT A NOTE.", Copy.notANote("a kick is a drum, not a note"))
        assertTrue(
            Copy.mutated("SPLICE", "A01", "A03").startsWith("SPLICE: A01 × A03."),
            "the move and both parents lead their own toast",
        )
        assertTrue(Copy.mutated("SPLICE", "A01", "A03").endsWith("."), "and still lands on a full stop")
        assertEquals("+3 OFF-LANE — HEARD AND EXPORTED, NOT DRAWN", Copy.offLane(3), "the count leads its own line")
        assertTrue(Copy.offLane(1).uppercase() == Copy.offLane(1), "TapeOS shouts here too")
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
}
