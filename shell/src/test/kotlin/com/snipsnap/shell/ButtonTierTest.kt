package com.snipsnap.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI clarity 7c: button tiers and empty states (spec 7.5).
 *
 * Every law is a function over source text that returns its failures, so the
 * vacuous-test check is a plain assertion that the same function reports a
 * known-bad input (the shape `ContrastTest`'s laws use). The sources are read
 * from `:app`, the way `ContrastTest`'s scans do.
 */
class ButtonTierTest {

    private val appSrc = File("../app/src/main/kotlin/com/snipsnap/app")

    private fun sources(): Map<String, String> =
        appSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(appSrc).path to it.readText() }

    /** Code lines only: comments and KDoc carry the words and are not call sites. */
    private fun codeLines(text: String): List<Pair<Int, String>> =
        text.lines().mapIndexed { i, l -> (i + 1) to l }
            .filter { (_, l) -> l.trim().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") } }

    /** A primary: a PrimaryAction call, or a button that asks for `tier = ButtonTier.Primary`. */
    private val primarySite = Regex("""\bPrimaryAction\(|\btier\s*=[^\n]*\bButtonTier\.Primary\b""")

    private val primaryDecl = Regex("""\bfun\s+PrimaryAction\(""")

    /** The file of every primary call site in [files], one entry per site. */
    private fun primarySites(files: Map<String, String>): List<String> =
        files.flatMap { (name, text) ->
            // The declaration of PrimaryAction is not a call to it.
            codeLines(text).filter { (_, l) -> primarySite.containsMatchIn(l) && !primaryDecl.containsMatchIn(l) }.map { name }
        }

    /**
     * Counted per file, not per function: a screen is drawn by several
     * composables (EXPORT's kit card and its project card were two functions
     * and two primaries on one screen), and a file is the screen's whole set.
     * A file that holds more than one primary call site must say so here, with
     * the reason the sites are never on screen together. Anything not listed
     * holds one at most.
     */
    private val severalPrimaries = mapOf(
        // The cold shelf's panel, then the shelf with no kits, the shelf with projects and the shelf
        // with kits only: one primary each, picked by what the lists hold (`coldShelf`, `projectFirst`).
        "ui/KitsScreen.kt" to 4,
        // START MIC while the mic is off, SAVE LAST while it is on.
        "ui/PadCaptureScreen.kt" to 2,
        // The kit card's reset after a write, and its write: two states of one card. THE PROJECT's card
        // below it is secondary, so EXPORT has the kit card's one primary.
        "ui/ExportScreen.kt" to 2,
        // The empty-kit face and the loaded-groove face of RECORD.
        "ui/GrooveScreen.kt" to 2,
        // A dialog's way on, or its dismiss when there is no way on.
        "App.kt" to 2,
    )

    /** The files that break the one-primary rule, as `file has n`. */
    private fun tooManyPrimaries(files: Map<String, String>): List<String> =
        primarySites(files).groupingBy { it }.eachCount()
            .filter { (file, n) -> n > (severalPrimaries[file] ?: 1) }
            .map { (file, n) -> "$file has $n" }

    @Test
    fun `law 1 - a screen has at most one primary button`() {
        val files = sources()
        val sites = primarySites(files)
        assertTrue(sites.size >= 12, "law 1 must assert over a real set, got ${sites.size} primary sites")
        val bad = tooManyPrimaries(files)
        assertTrue(bad.isEmpty(), "more than one primary in a file: $bad")
        // The reviewed exceptions are real and not slack: each one is at its count.
        val counts = sites.groupingBy { it }.eachCount()
        for ((file, n) in severalPrimaries) assertEquals(n, counts[file], "$file is listed with $n primaries")
    }

    @Test
    fun `law 1 reports a second primary in one function`() {
        // The vacuous-test check: two primaries in one file must be caught, whichever
        // way the primary is asked for, and in different composables of it.
        val two = mapOf(
            "ui/X.kt" to """
                @Composable
                fun XScreen() {
                    PrimaryAction(label = "A", enabled = true, onClick = {})
                }
                @Composable
                private fun XCard() {
                    ActionButton("B", scheme, enabled = true, tier = ButtonTier.Primary, onClick = {})
                }
            """.trimIndent(),
        )
        assertEquals(listOf("ui/X.kt has 2"), tooManyPrimaries(two))
        // A primary and a secondary is the shape the rule wants.
        val ok = mapOf("ui/Y.kt" to "fun Y() {\n  PrimaryAction(label = \"A\", enabled = true, onClick = {})\n  ActionButton(\"B\", scheme, enabled = true, onClick = {})\n}")
        assertTrue(tooManyPrimaries(ok).isEmpty())
    }

    /** Where the one obvious action of a screen lives: each of these files must draw a primary. */
    private val primaryScreens = mapOf(
        "ui/KitsScreen.kt" to "LIBRARY: CATCH YOUR FIRST SOUND, then NEW PROJECT, then NEW KIT",
        "ui/ChopScreen.kt" to "CHOP: SEND TO PADS",
        "ui/GrooveScreen.kt" to "BEAT: RECORD",
        "ui/ExportScreen.kt" to "OUT: EXPORT",
        "ui/PadCaptureScreen.kt" to "CAPTURE: START MIC / SAVE LAST",
        "ui/GrainFieldScreen.kt" to "GRAIN FIELD: START MIC",
        "ui/MessageBox.kt" to "message box: its dismiss",
        "App.kt" to "blocked dialog: its way on, or its dismiss",
        "ui/Chrome.kt" to "every empty state: its first door",
    )

    private fun screensWithoutPrimary(files: Map<String, String>): List<String> {
        val have = primarySites(files).toSet()
        return primaryScreens.keys.filter { it !in have }
    }

    @Test
    fun `law 2 - every main screen names its primary`() {
        val files = sources()
        assertTrue(primaryScreens.size >= 9)
        assertEquals(emptyList(), screensWithoutPrimary(files))
    }

    @Test
    fun `law 2 reports a screen with no primary, as the code stood before this PR`() {
        // Before 7c nothing said `tier`, and PrimaryAction was the only primary: with
        // the Chrome empty-state doors as plain ActionButtons, Chrome.kt had none.
        val before = sources().toMutableMap()
        before["ui/Chrome.kt"] = before.getValue("ui/Chrome.kt").replace(Regex("""\bButtonTier\.Primary\b"""), "ButtonTier.Secondary")
        assertEquals(listOf("ui/Chrome.kt"), screensWithoutPrimary(before))
    }

    @Test
    fun `law 3 - the tiers are dressed in scheme tokens, and secondary is the default`() {
        val actionButton = sources().getValue("ui/PadSheetScreen.kt").substringAfter("internal fun ActionButton(")
        assertTrue("tier: ButtonTier = ButtonTier.Secondary" in actionButton.substringBefore(") {\n"), "ActionButton's tier defaults to secondary")
        val tier = sources().getValue("ui/ButtonTier.kt")
        assertTrue("scheme.accent.tape" in tier && "scheme.onAccent.tape" in tier, "primary is the accent fill with onAccent letters")
        assertTrue("raisedBevel(scheme)" in tier, "secondary is the raised, edge-outlined form")
        val primary = sources().getValue("ui/KitsScreen.kt").substringAfter("\nfun PrimaryAction(").substringBefore("\n}\n")
        assertTrue("scheme.accent.tape" in primary && "scheme.onAccent.tape" in primary, "PrimaryAction is the accent fill with onAccent letters")
        assertTrue("scheme.lcd.tape" !in primary && "scheme.amber.tape" !in primary, "PrimaryAction no longer draws the lcd fill and amber rim")
    }

    @Test
    fun `law 3 - a filled primary holds its boundary and its letters, on chrome and on the lcd panel, in every scheme`() {
        // The fill's boundary is the accent where that clears 3:1 and the `edge` outline where it
        // does not (OPENSKY's accent on the LCD is 2.83:1), so both dresses carry the outline.
        val tier = sources().getValue("ui/ButtonTier.kt").substringAfter("ButtonTier.Primary ->").substringBefore("ButtonTier.Secondary")
        assertTrue("scheme.edge.tape" in tier, "ActionButton's primary is outlined with edge")
        val primary = sources().getValue("ui/KitsScreen.kt").substringAfter("\nfun PrimaryAction(").substringBefore("\n}\n")
        assertTrue("scheme.edge.tape" in primary, "PrimaryAction's primary is outlined with edge")
        for (s in Schemes.ALL) {
            for (fill in listOf(s.lcd, s.gray, s.win, s.raised, s.field)) {
                val r = maxOf(Scheme.contrastRatio(s.accent, fill), Scheme.contrastRatio(s.edge, fill))
                assertTrue(r >= 3.0, "${s.id}: neither the accent fill nor its edge outline holds 3:1 on a surface, got %.2f".format(r))
            }
            val t = Scheme.contrastRatio(s.onAccent, s.accent)
            assertTrue(t >= 4.5, "${s.id}: onAccent on accent is %.2f:1, needs 4.5".format(t))
        }
    }

    // ==================== Empty states ====================

    /** The messages a screen draws under an empty-state panel. Each is one sentence. */
    private val emptyStateMessages = mapOf(
        "NO_TAPE_YET" to Copy.NO_TAPE_YET,
        "NO_TAPE_YET_ARMED" to Copy.NO_TAPE_YET_ARMED,
        "EMPTY_SHELF" to Copy.EMPTY_SHELF,
        "EMPTY_SHELF_FOR_ASSIGN" to Copy.EMPTY_SHELF_FOR_ASSIGN,
        "EMPTY_KIT" to Copy.EMPTY_KIT,
        "EMPTY_GROOVE" to Copy.EMPTY_GROOVE,
        "NO_PROJECT_OPEN" to Copy.NO_PROJECT_OPEN,
        "PROJECT_HAS_NO_KIT" to Copy.PROJECT_HAS_NO_KIT,
        "NO_KIT_FOR_ORBIT" to Copy.NO_KIT_FOR_ORBIT,
        "NO_KIT_FOR_SONG" to Copy.NO_KIT_FOR_SONG,
        "NO_KIT_FOR_EXPORT" to Copy.NO_KIT_FOR_EXPORT,
        "READ_GROOVE_NEEDS_KIT" to Copy.READ_GROOVE_NEEDS_KIT,
        "EMPTY_CHOP" to Copy.EMPTY_CHOP,
        "CHOP_SOURCE_GONE" to Copy.CHOP_SOURCE_GONE,
        "BEAT_NEEDS_PADS" to Copy.BEAT_NEEDS_PADS,
        "SNIPS_EMPTY" to Copy.SNIPS_EMPTY,
    )

    /** One sentence: it ends in a full stop and no full stop is followed by another sentence. */
    private fun notOneSentence(messages: Map<String, String>): List<String> =
        messages.filter { (_, m) -> !m.endsWith(".") || Regex("""[.!?]\s+\p{Lu}""").containsMatchIn(m) }.keys.toList()

    @Test
    fun `law 4 - an empty state is one sentence`() {
        assertTrue(emptyStateMessages.size >= 16)
        assertEquals(emptyList(), notOneSentence(emptyStateMessages))
    }

    @Test
    fun `law 4 reports the two-sentence strings it replaced`() {
        val before = mapOf(
            "NO_TAPE_YET" to "No sample yet. Tap LISTEN · MIC below, or open a sample.",
            "NO_PROJECT_OPEN" to "No project open. Open one in the LIBRARY, or start one.",
            "NO_KIT_FOR_EXPORT" to "Add a kit to this project first. EXPORT dubs it for the MPC.",
            "NO_FULL_STOP" to "No samples yet",
        )
        assertEquals(before.keys.toList(), notOneSentence(before))
    }

    @Test
    fun `law 4 - every message an empty-state panel draws is on the list`() {
        val call = Regex("""\b(?:EmptyStatePanel|NoKitPanel)\(([^,]*),""")
        val named = Regex("""Copy\.(\w+)""")
        val drawn = HashSet<String>()
        for ((name, text) in sources()) {
            if (name == "ui/Chrome.kt") continue
            for (line in codeLines(text).joinToString("\n") { it.second }.let { call.findAll(it.replace("\n", " ")) }) {
                named.findAll(line.groupValues[1]).forEach { drawn += it.groupValues[1] }
            }
        }
        assertTrue(drawn.size >= 6, "the scan must find the panels' messages, found $drawn")
        // CHOP_LAYOUT_FAILED is an error with no door, not an empty state.
        val unlisted = drawn - emptyStateMessages.keys - "CHOP_LAYOUT_FAILED"
        assertEquals(emptySet(), unlisted)
    }

    /** The body of `fun name(` up to the next top-level declaration. */
    private fun body(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        assertTrue(at >= 0, "$name must exist")
        val end = Regex("""\n(?:@Composable|/\*\*|private fun|internal fun|fun )""").find(text, at + 10)?.range?.first ?: text.length
        return codeLines(text.substring(at, end)).joinToString("\n") { it.second }
    }

    private val tallModifier = Regex("""fillMaxSize|fillMaxHeight|\.weight\(""")

    @Test
    fun `law 5 - an empty-state panel is sized to its content`() {
        val files = sources()
        val panels = mapOf(
            "ui/Chrome.kt" to "EmptyStatePanel",
            "ui/SnipsScreen.kt" to "SnipsEmpty",
        )
        for ((file, fn) in panels) {
            val b = body(files.getValue(file), fn)
            assertTrue(b.length > 100, "$fn must have a body")
            assertTrue(!tallModifier.containsMatchIn(b), "$fn is sized to its content, not to the screen: $file")
        }
        // The cold shelf's panel is handed no height either.
        val kits = files.getValue("ui/KitsScreen.kt")
        val call = kits.substringAfter("EmptyShelfPanel(\n").substringBefore("\n                )")
        assertTrue("modifier = Modifier.fillMaxWidth()," in call && !tallModifier.containsMatchIn(call), "EmptyShelfPanel is called without a weight")
    }

    @Test
    fun `law 5 reports the full-height panel it replaced`() {
        val before = "fun EmptyStatePanel(\n  Box(modifier.fillMaxSize().lcdPanel(scheme).padding(14.dp)) { Text(\"a long enough body to pass the length guard of the law above, yes\") }\n}"
        assertTrue(tallModifier.containsMatchIn(body(before, "EmptyStatePanel")))
    }

    @Test
    fun `law 6 - a read that has not finished is not drawn as an empty list`() {
        val snips = sources().getValue("ui/SnipsScreen.kt")
        val screen = codeLines(snips).joinToString("\n") { it.second }
        // The loading branch comes before the empty one: until the first read lands, loading.
        val loading = screen.indexOf("if (!snipsRead)")
        val empty = screen.indexOf("else if (snips.isEmpty())")
        assertTrue(loading in 0 until empty, "SNIPS asks whether the read has landed before it asks whether the list is empty")
        assertTrue("snipsRead = true" in screen)
    }

    /** A line that passes "CANCEL" as the label of an ActionButton or a LabButton, the two shapes a visible CANCEL takes. */
    private val cancelButton = Regex("""\b(ActionButton|LabButton)\(\s*"CANCEL"""")

    private fun loudCancels(lines: List<String>) = lines.filter { cancelButton.containsMatchIn(it) && "ButtonTier.Text" !in it }

    @Test
    fun `cancel is a text button`() {
        val cancels = sources().flatMap { (name, text) ->
            codeLines(text).filter { (_, l) -> cancelButton.containsMatchIn(l) }.map { name to it.second }
        }
        assertTrue(cancels.size >= 20, "found ${cancels.size} CANCEL buttons")
        assertTrue(cancels.any { "LabButton(" in it.second }, "the LabButton shape is scanned")
        val loud = cancels.filter { "ButtonTier.Text" !in it.second }
        assertEquals(emptyList(), loud)
    }

    @Test
    fun `the cancel law reads a LabButton CANCEL and fails when it is not a text button`() {
        val bare = "LabButton(\"CANCEL\", scheme, enabled = true, onClick = onCancel)"
        val ok = "LabButton(\"CANCEL\", scheme, enabled = true, tier = ButtonTier.Text, onClick = onCancel)"
        assertEquals(listOf(bare), loudCancels(listOf(bare, ok)))
        assertEquals(listOf("ActionButton(\"CANCEL\", scheme)"), loudCancels(listOf("ActionButton(\"CANCEL\", scheme)")))
        assertEquals(emptyList(), loudCancels(listOf("LabButton(\"SAVE\", scheme, enabled = true) {}")))
    }
}
