package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WCAG 2.x AA contrast guardrails, built on [Scheme.contrastRatio] (real
 * sRGB-linearized relative luminance — not the fast Rec.601 [Scheme.luma]
 * approximation `SchemesTest` uses for its own, looser checks). Turns the
 * accessibility audit's measured ratios (2026-09-08-ux-audit/accessibility.md,
 * finding 5/6/7) into assertions so a future retune that regresses one of
 * these colours fails the build instead of shipping quietly.
 *
 * Thresholds: 4.5:1 for body text (WCAG 1.4.3), 3:1 for large text / UI
 * graphical objects (WCAG 1.4.11).
 */
class ContrastTest {

    private val AA_TEXT = 4.5
    private val AA_UI = 3.0

    // ==================== The math itself, sanity-checked ====================

    @Test
    fun `contrastRatio matches known WCAG reference pairs`() {
        // Pure black on pure white (and the reverse) is the textbook 21:1.
        assertApprox(21.0, Scheme.contrastRatio(0x000000, 0xFFFFFF), absoluteTolerance = 0.01)
        assertApprox(21.0, Scheme.contrastRatio(0xFFFFFF, 0x000000), absoluteTolerance = 0.01)
        // A colour against itself is always 1:1 — no contrast at all.
        assertApprox(1.0, Scheme.contrastRatio(0x7A6AA0, 0x7A6AA0), absoluteTolerance = 0.001)
        // Order must not matter — the formula takes lighter-over-darker
        // regardless of argument order.
        assertApprox(
            Scheme.contrastRatio(0xC86050, 0x2E3136),
            Scheme.contrastRatio(0x2E3136, 0xC86050),
            absoluteTolerance = 0.0001,
        )
    }

    private fun assertApprox(expected: Double, actual: Double, absoluteTolerance: Double) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= absoluteTolerance,
            "expected $expected, got $actual (tolerance $absoluteTolerance)",
        )
    }

    // ==================== Finding 5: destructive-action text ====================

    @Test
    fun `every scheme's bin red clears AA text contrast against its chrome gray`() {
        // gray (raisedBevel's fill) is the lightest of the backgrounds this
        // text is actually drawn on across the 6 screens that use it — the
        // other sites use lcd/field/sunkenField, all darker, so gray is the
        // binding constraint. Was 0xC86050: 3.26-4.49:1, failing in all 8
        // schemes (audit finding 5). The red is per scheme now
        // (`Scheme.bin`, BIN_RED_GLOW by default): the glow tuned for dark
        // chrome is 2.5:1 on paper, and a paper scheme brings its own.
        for (scheme in Schemes.ALL) {
            val ratio = Scheme.contrastRatio(scheme.bin, scheme.gray)
            assertTrue(
                ratio >= AA_TEXT,
                "${scheme.id}: bin vs gray = %.2f:1, below AA text (%.1f:1)".format(ratio, AA_TEXT),
            )
        }
        // The dark-chrome default is still the dark-chrome default.
        for (scheme in Schemes.ALL.filter { Scheme.luma(it.gray) < 90 }) {
            assertEquals(Schemes.BIN_RED_GLOW, scheme.bin, "${scheme.id}: a dark scheme has no reason to depart from the one tuned glow")
        }
    }

    @Test
    fun `every scheme's lcd bin red clears AA against its lcd surface`() {
        // The darker of the two backgrounds the word is drawn on — the
        // dock's STOP sits on the LCD in every scheme. Where the chrome is
        // dark one red does both (`binLcd` defaults to `bin`, checked
        // below); on paper no single red can, since the luminance a paper
        // chrome needs is below what a dark surface needs, so a paper
        // scheme gives the LCD its own.
        for (scheme in Schemes.ALL) {
            val ratio = Scheme.contrastRatio(scheme.binLcd, scheme.lcd)
            assertTrue(
                ratio >= AA_TEXT,
                "${scheme.id}: binLcd vs lcd = %.2f:1, below AA text (%.1f:1)".format(ratio, AA_TEXT),
            )
        }
        for (scheme in Schemes.ALL.filter { Scheme.luma(it.gray) < 90 }) {
            assertEquals(scheme.bin, scheme.binLcd, "${scheme.id}: dark chrome needs one red, not two")
        }
    }

    // ==================== Finding 5 (pad slot tag) / 6 (secondary chrome text) ====================

    @Test
    fun `ink2 clears AA text contrast against gray in every scheme, at full opacity`() {
        // The pad-slot-tag fix (KitScreen/PlayScreen) dropped the old
        // 0.6/0.7 alpha reduction in favour of full-opacity ink2 — this is
        // the guardrail that makes that safe. Also directly covers finding
        // 6 (secondary chrome text: four of the schemes then shipping were
        // 3.47-4.14:1 before their ink2 was raised; all four are retired).
        for (scheme in Schemes.ALL) {
            val ratio = Scheme.contrastRatio(scheme.ink2, scheme.gray)
            assertTrue(
                ratio >= AA_TEXT,
                "${scheme.id}: ink2 vs gray = %.2f:1, below AA text (%.1f:1)".format(ratio, AA_TEXT),
            )
        }
    }

    @Test
    fun `ink2 clears AA against the filled-pad backdrop too`() {
        // KitScreen's filled-pad tag sits on the pad's base fill (before
        // any class-colour wash on top of it — see the next test for that
        // composited case, which the app now sidesteps with an opaque
        // backing chip rather than relying on this alone). The base fill
        // is the `raised` card (`docs/MODERN_LOOK.md` §3, §4.2) —
        // darkening paper gives a mid-tone nothing reads on, which is why
        // the pad is a card and not a darkened chrome.
        for (scheme in Schemes.ALL) {
            val ratio = Scheme.contrastRatio(scheme.ink2, scheme.raised)
            assertTrue(
                ratio >= AA_TEXT,
                "${scheme.id}: ink2 vs the filled-pad base = %.2f:1, below AA text (%.1f:1)".format(ratio, AA_TEXT),
            )
        }
    }

    /**
     * Documents, rather than hides, the residual gap the opaque backing
     * chip (KitScreen/PlayScreen's pad tag, `theme.BinRedGlow`-style fix)
     * exists to close: a class-coloured wash drawn *underneath* the tag at
     * partial alpha can still drag ink2's effective background bright
     * enough to fail AA, even though ink2 itself and the clean chrome
     * background both pass. Composites the brightest class colours
     * (HAT_OPEN, SNARE) at the wash strengths the two screens actually use
     * (30% for KitScreen's PadWaveform, 12% as a stand-in for a faint
     * flash) over the `raised` card the pad is drawn on. On VENT the 30%
     * washes land at about 3.5:1.
     */
    @Test
    fun `worst-case class-colour wash under the pad tag would fail without its backing chip`() {
        val hatOpen = 0x7ADFE4
        val snare = 0xFFC41F
        var sawAFailure = false
        for (scheme in Schemes.ALL) {
            val base = scheme.raised
            for (washColor in listOf(hatOpen, snare)) {
                for (alpha in listOf(0.30, 0.12)) {
                    val composited = blend(washColor, base, alpha)
                    val ratio = Scheme.contrastRatio(scheme.ink2, composited)
                    if (ratio < AA_TEXT) sawAFailure = true
                }
            }
        }
        assertTrue(
            sawAFailure,
            "expected at least one scheme/class-colour/alpha combination to fail AA here — if none do, the " +
                "backing chip in KitScreen.kt/PlayScreen.kt may no longer be load-bearing and this test (and the " +
                "comments pointing at it) should be revisited, not just loosened",
        )
    }

    private fun blend(fg: Int, bg: Int, alpha: Double): Int {
        val r1 = (fg shr 16) and 0xFF; val g1 = (fg shr 8) and 0xFF; val b1 = fg and 0xFF
        val r2 = (bg shr 16) and 0xFF; val g2 = (bg shr 8) and 0xFF; val b2 = bg and 0xFF
        val r = Math.round(r1 * alpha + r2 * (1 - alpha)).toInt()
        val g = Math.round(g1 * alpha + g2 * (1 - alpha)).toInt()
        val b = Math.round(b1 * alpha + b2 * (1 - alpha)).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    // ==================== The LCD's own inks ====================

    @Test
    fun `the LCD's second tier and its emphasis ink clear AA over the lcd in every scheme`() {
        // `ink`, `ink2` and `ink3` are chrome inks: on a paper scheme, where
        // the LCD is the dark surface, they are 2.8:1 and 3.8:1 on it (the
        // review's L1). A secondary line on the LCD is `lcdInk` at
        // LCD_INK2_ALPHA, composited over the lcd the way the screen is, and
        // emphasis there is `amber`; both hold here in every scheme.
        for (scheme in Schemes.ALL) {
            val second = blend(scheme.lcdInk, scheme.lcd, Schemes.LCD_INK2_ALPHA.toDouble())
            val secondRatio = Scheme.contrastRatio(second, scheme.lcd)
            assertTrue(
                secondRatio >= AA_TEXT,
                "${scheme.id}: lcdInk @${Schemes.LCD_INK2_ALPHA} over lcd = %.2f:1, below AA text (%.1f:1)"
                    .format(secondRatio, AA_TEXT),
            )
            val amberRatio = Scheme.contrastRatio(scheme.amber, scheme.lcd)
            assertTrue(
                amberRatio >= AA_TEXT,
                "${scheme.id}: amber vs lcd = %.2f:1, below AA text (%.1f:1)".format(amberRatio, AA_TEXT),
            )
        }
    }

    /**
     * `warn` is text on the LCD as well as a needle: a binned row's days
     * left turn `warn` at two days or fewer, on the row's `lcd` fill
     * (`DeletedKitsSection`, `TakesBinScreen` and the rest). A paper
     * scheme's `warn` is tuned for the dock, not the chrome, so the dock is
     * where it must read.
     */
    @Test
    fun `warn reads as text on the lcd in every scheme`() {
        for (scheme in Schemes.ALL) {
            val ratio = Scheme.contrastRatio(scheme.warn, scheme.lcd)
            assertTrue(
                ratio >= AA_TEXT,
                "${scheme.id}: warn vs lcd = %.2f:1, below AA text (%.1f:1)".format(ratio, AA_TEXT),
            )
        }
    }

    // ==================== OPENSKY's ink3: no longer a gap ====================

    @Test
    fun `OPENSKY ink3 clears AA and is still the dimmest tier`() {
        // The third tier used to be a measured 3.85:1 gap (the ink that would
        // clear AA landed on ink2 and erased the hierarchy). It now clears
        // 4.5:1 on every chrome fill (law 1 holds it), and the hierarchy
        // survives because ink3 is still lighter than ink2 on paper, so still
        // the quieter of the two.
        val s = Schemes.OPENSKY
        assertTrue(Scheme.contrastRatio(s.ink3, s.gray) >= AA_TEXT, "OPENSKY ink3 must clear AA on gray")
        assertTrue(
            Scheme.contrastRatio(s.ink3, s.gray) < Scheme.contrastRatio(s.ink2, s.gray),
            "ink3 must stay quieter than ink2",
        )
    }

    // ==================== Finding 7: selection state ====================

    @Test
    fun `pressedBevel's accent border clears the 3-to-1 graphical-object threshold in every scheme`() {
        // The non-colour-alone fix for finding 7 (a pressed fill that is
        // effectively invisible against a raised one) is a thicker,
        // accent-tinted border rather than a fill change — this checks the
        // border is actually visible against the pressed fill it sits on
        // (`field`, per `pressedBevel` in Bevel.kt), in every scheme, at
        // the UI-component threshold (not the text one: a border is a
        // graphical object, not text).
        for (scheme in Schemes.ALL) {
            val pressedFill = scheme.field
            val ratio = Scheme.contrastRatio(scheme.accent, pressedFill)
            assertTrue(
                ratio >= AA_UI,
                "${scheme.id}: accent vs pressedBevel fill = %.2f:1, below the UI-component threshold (%.1f:1)"
                    .format(ratio, AA_UI),
            )
        }
    }

    // ==================== 7b: the four contrast laws ====================
    //
    // Law 1: text is at least 4.5:1 against its background.
    // Law 2: the boundary of an interactive control is at least 3:1 against
    //        what it sits on (WCAG 1.4.11).
    // Law 3: no `amber` on chrome (a source scan; `accent` is chrome's colour).
    // Law 4: a per-scheme destructive red, at least 4.5:1 on its fill, and
    //        no scheme-independent BIN_RED_BORDER copies.
    //
    // Each law is a function that returns its failures, so the vacuous-test
    // check is a plain assertion that the function reports a known-bad input.

    /** The fills text is drawn on in chrome, by name. */
    private fun chromeFills(s: Scheme): Map<String, Int> =
        mapOf("gray" to s.gray, "win" to s.win, "raised" to s.raised, "field" to s.field)

    private data class Pair3(val what: String, val fg: Int, val bg: Int, val min: Double)

    /** Every (text colour, background) pair the app draws text with, for one scheme. */
    private fun textPairs(s: Scheme): List<Pair3> {
        val out = ArrayList<Pair3>()
        val chromeInks = mapOf(
            "ink" to s.ink, "ink2" to s.ink2, "ink3" to s.ink3,
            "accent" to s.accent, "bin" to s.bin, "warnInk" to s.warnInk,
        )
        for ((inkName, ink) in chromeInks) for ((fillName, fill) in chromeFills(s)) {
            out += Pair3("$inkName on $fillName", ink, fill, AA_TEXT)
        }
        out += Pair3("titleInk on title1", s.titleInk, s.title1, AA_TEXT)
        out += Pair3("titleInk on title2", s.titleInk, s.title2, AA_TEXT)
        out += Pair3("onAccent on accent", s.onAccent, s.accent, AA_TEXT)
        val lcdInk2 = blend(s.lcdInk, s.lcd, Schemes.LCD_INK2_ALPHA.toDouble())
        val lcdInks = mapOf("lcdInk" to s.lcdInk, "lcdInk2" to lcdInk2, "amber" to s.amber, "warn" to s.warn, "binLcd" to s.binLcd)
        for ((inkName, ink) in lcdInks) out += Pair3("$inkName on lcd", ink, s.lcd, AA_TEXT)
        return out
    }

    private fun failures(pairs: List<Pair3>): List<String> =
        pairs.filter { Scheme.contrastRatio(it.fg, it.bg) < it.min }
            .map { "%s = %.2f:1 (needs %.1f)".format(it.what, Scheme.contrastRatio(it.fg, it.bg), it.min) }

    @Test
    fun `law 1 - every text colour clears 4_5 to 1 on every surface it is drawn on, in every scheme`() {
        var checked = 0
        for (s in Schemes.ALL) {
            val pairs = textPairs(s)
            checked += pairs.size
            val bad = failures(pairs)
            assertTrue(bad.isEmpty(), "${s.id}: " + bad.joinToString("; "))
        }
        assertTrue(checked >= Schemes.ALL.size * 30, "law 1 must assert over a real set, got $checked pairs")
    }

    @Test
    fun `law 1 reports the old tokens it replaced`() {
        // The vacuous-test check: the law's own pair list, fed the values this
        // PR moved, must say so. OPENSKY's old ink3 (3.85:1 on gray), its raw
        // warn used as chrome text (2.11:1), amber used as chrome text
        // (2.11:1), and the dark-chrome text on an accent fill.
        assertTrue(failures(textPairs(Schemes.OPENSKY.copy(ink3 = 0x817A6B))).any { it.startsWith("ink3 on gray") })
        assertTrue(failures(textPairs(Schemes.OPENSKY.copy(warnInk = 0xFF8C00))).any { it.startsWith("warnInk on gray") })
        assertTrue(failures(textPairs(Schemes.OPENSKY.copy(accent = Schemes.OPENSKY.amber))).any { it.startsWith("accent on gray") })
        assertTrue(failures(textPairs(Schemes.OPENSKY.copy(onAccent = Schemes.OPENSKY.lcd))).any { it.startsWith("onAccent on accent") })
    }

    /** The outline of an interactive control against everything it sits on. */
    private fun edgePairs(s: Scheme): List<Pair3> =
        (chromeFills(s) + ("lcd" to s.lcd)).map { (name, fill) -> Pair3("edge on $name", s.edge, fill, AA_UI) } +
            (chromeFills(s).filterKeys { it == "field" }).map { (name, fill) -> Pair3("accent outline on $name", s.accent, fill, AA_UI) }

    @Test
    fun `law 2 - a control's outline clears 3 to 1 on what it sits on, in every scheme`() {
        var checked = 0
        for (s in Schemes.ALL) {
            val pairs = edgePairs(s)
            checked += pairs.size
            val bad = failures(pairs)
            assertTrue(bad.isEmpty(), "${s.id}: " + bad.joinToString("; "))
        }
        assertTrue(checked >= Schemes.ALL.size * 5, "law 2 must assert over a real set, got $checked pairs")
    }

    @Test
    fun `law 2 reports the hairline it replaced as a control outline`() {
        // grayEdge as a button's outline is 1.24 to 1.41:1: every scheme fails.
        for (s in Schemes.ALL) {
            assertTrue(failures(edgePairs(s.copy(edge = s.grayEdge))).isNotEmpty(), "${s.id}: grayEdge must not pass as an outline")
        }
    }

    private val appSrc = java.io.File("../app/src/main/kotlin/com/snipsnap/app")

    private fun appSources(): List<java.io.File> =
        appSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Code lines only: comments and KDoc carry the words and are not reads. */
    private fun codeLines(text: String): List<Pair<Int, String>> =
        text.lines().mapIndexed { i, l -> (i + 1) to l }
            .filter { (_, l) -> l.trim().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") } }

    @Test
    fun `law 2 - the raised and sunken forms outline with edge, not the hairline`() {
        val bevel = java.io.File(appSrc, "theme/Bevel.kt").readText()
        for (form in listOf("raisedBevel", "sunkenField")) {
            val at = bevel.indexOf("fun Modifier.$form(")
            assertTrue(at >= 0, "$form must exist")
            val next = Regex("""@Suppress|/\*\*""").find(bevel, at)?.range?.first ?: bevel.length
            val body2 = bevel.substring(at, next).lines().filter { !it.trim().startsWith("//") }.joinToString("\n")
            assertTrue("scheme.edge.tape" in body2, "$form must outline with scheme.edge")
            assertTrue("grayEdge" !in body2, "$form must not outline with the grayEdge hairline")
        }
    }

    @Test
    fun `law 2 - a pad's face outlines with edge`() {
        val skin = java.io.File(appSrc, "ui/PadGrid.kt").readText().substringAfter("fun Modifier.padSkin(").substringBefore("\n}\n")
        assertTrue(skin.isNotBlank() && "scheme.edge.tape" in skin && "grayEdge" !in skin, "padSkin must outline with scheme.edge")
    }

    /** A grayEdge outline in the same modifier chain as a click handler is a control drawn with a hairline. */
    private fun hairlineControls(text: String): List<Int> {
        val raw = text.lines()
        val clicks = Regex("""tapeClick\(|\.clickable\(|\.toggleable\(|\.selectable\(|\.pointerInput\(""")
        val out = ArrayList<Int>()
        for ((i, l) in raw.withIndex()) {
            if ("grayEdge" !in l || l.trim().startsWith("//") || l.trim().startsWith("*")) continue
            // The statement: back to the line that opens the call, forward to the line that opens its block.
            var a = i
            while (a > 0 && i - a < 8 && raw[a - 1].isNotBlank() && !raw[a - 1].trimEnd().endsWith("{")) a--
            var b = i
            while (b < raw.size - 1 && b - i < 8 && !raw[b].trimEnd().endsWith("{") && raw[b + 1].isNotBlank()) b++
            if ((a..b).any { clicks.containsMatchIn(raw[it]) }) out += i + 1
        }
        return out
    }

    @Test
    fun `law 2 - no interactive control is outlined with the grayEdge hairline`() {
        val files = appSources()
        assertTrue(files.size > 100, "the scan must see the app, saw ${files.size} files")
        val hits = files.flatMap { f -> hairlineControls(f.readText()).map { "${f.name}:$it" } }
        assertTrue(hits.isEmpty(), "controls outlined with grayEdge (use scheme.edge): $hits")
    }

    @Test
    fun `law 2 scan reports a control drawn with the hairline`() {
        val bad = """
            Box(
                Modifier
                    .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(4.dp))
                    .tapeClick(label = "X", onClick = {})
            ) {}
        """.trimIndent()
        assertEquals(1, hairlineControls(bad).size)
        val fine = """
            Box(Modifier.fillMaxWidth().border(1.dp, scheme.grayEdge.tape))

            Box(Modifier.tapeClick(label = "X", onClick = {})) {}
        """.trimIndent()
        assertEquals(0, hairlineControls(fine).size)
    }

    /**
     * Law 3. Every raw `amber` read left in `:app`, by file. `amber` is the
     * LCD's second colour: bright, tuned to read on the dark LCD (6.9 to 12.4:1)
     * and, on a paper scheme, 1.3 to 2.3:1 on chrome. A read that sits on
     * chrome says `accent` instead (and `onAccent` for the ink on it). What
     * stays here are the reads that sit on the LCD, decided one by one by
     * following each read up its composable tree to the box that paints its
     * background (an `lcdPanel`, a `scheme.lcd` fill, the dock, the canvases
     * that draw on one), and by following a colour passed as a parameter to
     * its callers. The count is exact: a new read, on either surface, fails
     * here until its author has decided which surface it is on and updated
     * the number, which is the point.
     */
    private val LCD_AMBER_READS: Map<String, Int> = mapOf(
        "LoopGrid.kt" to 2,
        "ui/AquariumPanel.kt" to 1,
        "ui/ArrangeScreen.kt" to 4,
        "ui/Busy.kt" to 3,
        "ui/DeletedKitsSection.kt" to 3,
        "ui/DeletedSnipsSection.kt" to 3,
        "ui/DoublesScreen.kt" to 2,
        "ui/ExportScreen.kt" to 1,
        "ui/Frame.kt" to 8,
        "ui/GrainFieldScreen.kt" to 1,
        "ui/GrooveScreen.kt" to 8,
        "ui/GroupBox.kt" to 1,
        "ui/KeysScreen.kt" to 1,
        "ui/KitFromResultOverlay.kt" to 4,
        "ui/KitScreen.kt" to 2,
        "ui/KitsScreen.kt" to 7,
        "ui/MakeScreen.kt" to 1,
        "ui/MurmurRoundRobinKeysScreen.kt" to 1,
        "ui/OrbitScreen.kt" to 2,
        "ui/PadCaptureScreen.kt" to 2,
        "ui/PatternPicker.kt" to 1,
        "ui/PlayScreen.kt" to 1,
        "ui/ProjectOps.kt" to 3,
        "ui/ScannedShapeControls.kt" to 1,
        "ui/SnapScreen.kt" to 7,
        "ui/SnipsScreen.kt" to 2,
        "ui/SplitScreen.kt" to 2,
        "ui/StackTakesScreen.kt" to 1,
        "ui/StepEditorParts.kt" to 1,
        "ui/SurfaceScreen.kt" to 2,
        "ui/SynthScreen.kt" to 10,
        "ui/TakesBinScreen.kt" to 8,
        "ui/TapeScreen.kt" to 1,
        "ui/XRayScreen.kt" to 3,
    )

    private fun amberReads(text: String): Int =
        codeLines(text).count { (_, l) -> Regex("""\.amber\b""").containsMatchIn(l) }

    @Test
    fun `law 3 - amber is read only where it sits on the LCD`() {
        val files = appSources()
        assertTrue(files.size > 100, "the scan must see the app, saw ${files.size} files")
        val seen = LinkedHashMap<String, Int>()
        for (f in files) {
            val n = amberReads(f.readText())
            if (n > 0) seen[f.relativeTo(appSrc).path] = n
        }
        assertTrue(LCD_AMBER_READS.isNotEmpty() && LCD_AMBER_READS.values.sum() > 0, "the allow-list must not be empty")
        assertEquals(
            LCD_AMBER_READS.toSortedMap(), seen.toSortedMap(),
            "a raw `amber` read appeared, vanished or moved. On chrome use `accent` (`onAccent` for ink on it); " +
                "on the LCD, say so here by updating the count",
        )
    }

    @Test
    fun `law 3 scan counts a raw amber read and ignores comments`() {
        assertEquals(1, amberReads("TapeText(\"X\", TapeType.pixel, scheme.amber.tape)"))
        assertEquals(0, amberReads("// scheme.amber.tape\n * scheme.amber\nTapeText(\"X\", TapeType.pixel, scheme.accent.tape)"))
    }

    @Test
    fun `law 3 - accent and amber each read at AA on the surface that owns them`() {
        for (s in Schemes.ALL) {
            for ((name, fill) in chromeFills(s)) {
                assertTrue(Scheme.contrastRatio(s.accent, fill) >= AA_TEXT, "${s.id}: accent on $name")
            }
            assertTrue(Scheme.contrastRatio(s.amber, s.lcd) >= AA_TEXT, "${s.id}: amber on lcd")
        }
        // and the reason the split exists: on paper chrome amber is not enough
        assertTrue(Scheme.contrastRatio(Schemes.OPENSKY.amber, Schemes.OPENSKY.gray) < 2.2)
    }

    /** Law 4: a destructive red that is a literal rather than a scheme token. */
    private fun fixedBinRed(text: String): List<Int> =
        codeLines(text).filter { (_, l) -> "BIN_RED_BORDER" in l || "6A2020" in l || "PROJECT_BIN_RED" in l }.map { it.first }

    /** An lcd-filled box that draws the chrome red (`BinRedGlow`) on it: a red that fails on its own fill. */
    private fun chromeRedOnLcdFill(text: String): List<Int> {
        val raw = text.lines()
        val out = ArrayList<Int>()
        for ((i, l) in raw.withIndex()) {
            if (".background(scheme.lcd.tape" !in l) continue
            var j = i + 1
            while (j < raw.size && j < i + 24) {
                val t = raw[j]
                if (t.trim().startsWith("//")) { j++; continue }
                if (Regex("""\bBinRedGlow\b""").containsMatchIn(t)) { out += j + 1; break }
                if (t.trim() == "}" && raw[j].length - raw[j].trimStart().length <= raw[i].length - raw[i].trimStart().length - 8) break
                j++
            }
        }
        return out
    }

    @Test
    fun `law 4 - the destructive red is the scheme's, not a literal, and reads on its fill`() {
        val files = appSources()
        assertTrue(files.size > 100, "the scan must see the app, saw ${files.size} files")
        val literals = files.flatMap { f -> fixedBinRed(f.readText()).map { "${f.name}:$it" } }
        assertTrue(literals.isEmpty(), "BIN_RED_BORDER (or a copy) is back: $literals")
        val wrongRed = files.flatMap { f -> chromeRedOnLcdFill(f.readText()).map { "${f.name}:$it" } }
        assertTrue(wrongRed.isEmpty(), "chrome red (BinRedGlow) drawn on an lcd fill; use BinRedOnLcd: $wrongRed")
        for (s in Schemes.ALL) {
            for ((name, fill) in chromeFills(s)) assertTrue(Scheme.contrastRatio(s.bin, fill) >= AA_TEXT, "${s.id}: bin on $name")
            assertTrue(Scheme.contrastRatio(s.binLcd, s.lcd) >= AA_TEXT, "${s.id}: binLcd on lcd")
            // the border of a button that is drawn on the LCD is binLcd, so its outline reads too
            assertTrue(Scheme.contrastRatio(s.binLcd, s.lcd) >= AA_UI, "${s.id}: binLcd outline on lcd")
        }
    }

    @Test
    fun `law 4 scan reports the old literal and the chrome red on an lcd fill`() {
        assertEquals(1, fixedBinRed("private val BIN_RED_BORDER = Color(0xFF6A2020)\n// BIN_RED_BORDER in a comment").size)
        val old = """
            Box(
                Modifier
                    .background(scheme.lcd.tape, RoundedCornerShape(4.dp))
                    .border(2.dp, scheme.binLcd.tape, RoundedCornerShape(4.dp))
            ) {
                TapeText("DELETE", TapeType.pixel, BinRedGlow)
            }
        """.trimIndent()
        assertEquals(1, chromeRedOnLcdFill(old).size)
        assertEquals(0, chromeRedOnLcdFill(old.replace("BinRedGlow", "BinRedOnLcd")).size)
    }
}
