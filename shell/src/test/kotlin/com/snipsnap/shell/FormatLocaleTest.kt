package com.snipsnap.shell

import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A source-scanning law, the same shape as `ConventionTest`'s (deliberately
 * duplicated rather than shared — this codebase's own convention): every
 * call of the form `"...".format(args)` whose format string carries a
 * decimal-digit conversion (`%d`, `%02d`, `%.1f`, …) must pass
 * `Locale.ROOT` as its first argument, in every module, not just the three
 * spots that already have their own dedicated regression test.
 *
 * The bug this closes: Kotlin's `String.format` (and Java's underneath it)
 * follows the JVM's *default* locale for `%d`/`%f`-family conversions —
 * proven here, not assumed, by this class's own probe test below, the
 * same way `PadNoteMapLocaleTest` proves it for one function — so on a
 * phone set to Arabic, Persian or Burmese, a bare `"%02d".format(7)`
 * renders as Eastern-Arabic or Myanmar digits instead of "07".
 * `PadNoteMapLocaleTest`, `KitAssemblerLocaleTest` and
 * `KitBuilderLocaleTest` each caught exactly this shape once, in one
 * function, after it actually shipped — three separate discoveries of the
 * identical defect, each closed by hand without ever asking "where else
 * does this pattern live?" A repo-wide sweep (2026-09-10) found 137 more
 * unguarded call sites across every module including `:app`, none behind
 * any of those three tests' reach; this law is what stops a 138th from
 * ever landing the same way.
 *
 * `%x`/`%X` (hex) and `%o` (octal) are exempt — the probe test below shows
 * they stay ASCII even under Arabic/Persian/Burmese, because the hex
 * alphabet has no locale-specific digit set the way decimal does. `%s`,
 * `%c`, `%b`, `%n` carry no digits of their own and are exempt too. A
 * format string mixing an exempt conversion with a digit one (`"%s: %d"`)
 * still trips the law — the digit conversion is what matters, not whether
 * every conversion in the string does.
 *
 * Scope, and its one known gap: this scans every `.format(` call whose
 * receiver is a plain string literal (or a `"""` raw string), skipping —
 * not flagging — the rare call whose receiver is instead the RESULT of an
 * `if`/`when`/`+`-concatenation (`(if (x) "a" else "b").format(...)`):
 * finding the template text there would mean evaluating Kotlin expressions,
 * not scanning text. Two such calls were found and fixed by hand in the
 * same 2026-09-10 sweep (`CaptureDoctor.summary()`, `WearCommand`'s report
 * line); this law cannot re-catch a regression at either exact shape, only
 * everywhere its receiver is the plain literal shape it can actually read.
 */
class FormatLocaleTest {

    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    /**
     * The empirical claim the rest of this file's KDoc rests on: under an
     * Eastern-Arabic-digit locale, `%d`/`%02d`/`%.Nf` render non-ASCII
     * digits while `%x`, `%s` and `%c` stay exactly as written. Run once,
     * proven, not assumed — the source-scanning law below only needs to
     * know WHICH conversions to require Locale.ROOT for; this is where
     * that list is actually checked against the JVM's real behavior.
     */
    @Test
    fun `digit conversions localize under the default locale, hex and string conversions do not`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("\u0664\u0662", "%02d".format(42), "%02d localizes under ar-EG")
        // The decimal separator localizes too, not just the digits: ar-EG's
        // own is U+066B (Arabic Decimal Separator), not ASCII '.'.
        assertEquals("\u0663\u066b\u0661\u0664", "%.2f".format(3.14), "%.2f localizes its digits AND separator")
        assertEquals("2a", "%x".format(42), "%x stays ASCII — no locale-specific hex digit set exists")
        assertEquals("42", "%s".format(42), "%s carries no digits of its own to localize")
        assertEquals("A03", "%c%02d".format(Locale.ROOT, 'A', 3), "Locale.ROOT is the fix this law requires")
    }

    /**
     * [receiverLiteral]'s raw-string branch, exercised directly: a `"""`
     * receiver right before `.format(` is the one shape this codebase has
     * no live example of today, so nothing in the scan itself would have
     * caught a regression here. Also pins the everyday normal-string
     * shape and the multi-line-chained shape (`Arranger.kt`'s own style)
     * against the same helper, so all three stay proven together.
     */
    @Test
    fun `receiverLiteral reads a raw triple-quoted string back to its own opening delimiter`() {
        val raw = "val x = \"\"\"a %.2f b\"\"\".format(1.0)"
        val dot = raw.indexOf(".format(")
        assertEquals("\"\"\"a %.2f b\"\"\"", receiverLiteral(raw, dot))

        val normal = "val x = \"a %d b\".format(1)"
        assertEquals("\"a %d b\"", receiverLiteral(normal, normal.indexOf(".format(")))

        val chained = "\"a %d b\"\n    .format(1)"
        assertEquals("\"a %d b\"", receiverLiteral(chained, chained.indexOf(".format(")))
    }

    /**
     * `:shell`'s own module dir is `.` from the test JVM's working
     * directory (Gradle's default, per `ConventionTest`'s own KDoc); every
     * sibling module is reached the same way `ConventionTest` reaches
     * `:app`, one `../<module>` up from there. `:app` has no test source
     * set of its own, so this is also the only place its `.format(` sites
     * ever get checked at all.
     */
    private val moduleSourceRoots: List<Pair<String, File>> = listOf(
        "shell" to File("src/main/kotlin"),
        "audio" to File("../audio/src/main/kotlin"),
        "kit" to File("../kit/src/main/kotlin"),
        "mpc3" to File("../mpc3/src/main/kotlin"),
        "synth" to File("../synth/src/main/kotlin"),
        "xpm" to File("../xpm/src/main/kotlin"),
        "json" to File("../json/src/main/kotlin"),
        "loop" to File("../loop/src/main/kotlin"),
        "cli" to File("../cli/src/main/kotlin"),
        "app" to File("../app/src/main/kotlin"),
    )

    /** A `%`-conversion that follows the default locale's digit set (proven by this file's own probe test above), so it must be Locale.ROOT-guarded. */
    private val digitConversion = Regex("""%[-+ 0,#]*\d*(?:\.\d+)?[dfeEgGaA]""")

    private fun isCommentLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    /** Index of the `)` matching the `(` one past [afterOpenIndex] — string-literal-aware, same shape as `ConventionTest.matchingParen`. */
    private fun matchingParen(text: String, afterOpenIndex: Int): Int? {
        var depth = 1
        var i = afterOpenIndex
        while (i < text.length) {
            when (text[i]) {
                '(' -> { depth++; i++ }
                ')' -> { depth--; if (depth == 0) return i; i++ }
                '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                }
                else -> i++
            }
        }
        return null
    }

    /**
     * The string literal (or raw `"""` string) that is the receiver of the
     * `.format(` call starting at [dotIndex] (the index of its own `.`) —
     * skipping back over whitespace first, since `"...".\n    .format(`
     * (this codebase's own multi-line chaining style, e.g. `Arranger.kt`)
     * puts the call on a different line than its string. Null when the
     * receiver isn't a plain literal (see this class's own KDoc on scope).
     */
    private fun receiverLiteral(text: String, dotIndex: Int): String? {
        var i = dotIndex - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || text[i] != '"') return null
        if (i >= 2 && text[i - 1] == '"' && text[i - 2] == '"') {
            // i-2 is the closing delimiter's OWN start index; searching
            // from there would match it against itself and return the
            // closing """ instead of walking back to the opening one.
            val start = text.lastIndexOf("\"\"\"", i - 3)
            return if (start < 0) null else text.substring(start, i + 1)
        }
        var j = i - 1
        while (j >= 0) {
            if (text[j] == '"') {
                var backslashes = 0
                var k = j - 1
                while (k >= 0 && text[k] == '\\') { backslashes++; k-- }
                if (backslashes % 2 == 0) break
            }
            j--
        }
        return if (j < 0) null else text.substring(j, i + 1)
    }

    /** The call's own first top-level argument (up to the first depth-0 comma or its own close), trimmed — what has to read "Locale.ROOT" or "java.util.Locale.ROOT". */
    private fun firstArgument(text: String, openParenIndex: Int): String {
        var i = openParenIndex + 1
        var depth = 0
        val start = i
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    if (depth == 0) return text.substring(start, i).trim()
                    depth--
                }
                ',' -> if (depth == 0) return text.substring(start, i).trim()
                else -> {}
            }
            i++
        }
        return text.substring(start, i).trim()
    }

    private data class Finding(val module: String, val file: String, val line: Int, val literal: String)

    private fun scanModule(module: String, root: File): Pair<Int, List<Finding>> {
        require(root.isDirectory) {
            "expected $module's source root at ${root.absolutePath} but it doesn't exist — if Gradle was " +
                "invoked with a custom -p, or a module's path moved, fix moduleSourceRoots here rather than " +
                "deleting the entry: a scan that quietly skips a module would pass by accident, which is worse " +
                "than no test at all."
        }
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        var totalCalls = 0
        val findings = mutableListOf<Finding>()
        for (file in files) {
            val text = file.readText()
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf(".format(", searchFrom)
                if (idx < 0) break
                searchFrom = idx + 1 // one past the '.', so a nested inner .format( is still found
                val lineStart = text.lastIndexOf('\n', idx) + 1
                val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
                if (isCommentLine(text.substring(lineStart, lineEnd))) continue
                val openParen = idx + ".format(".length - 1
                matchingParen(text, openParen + 1)
                    ?: fail(
                        "${file.relativeTo(root).path}: found `.format(` with no matching `)` — a bug in this " +
                            "scan (or genuinely malformed Kotlin, which wouldn't compile), not a real finding.",
                    )
                totalCalls++
                val literal = receiverLiteral(text, idx) ?: continue
                if (!digitConversion.containsMatchIn(literal)) continue
                val first = firstArgument(text, openParen)
                if (first == "Locale.ROOT" || first == "java.util.Locale.ROOT") continue
                findings += Finding(
                    module,
                    file.relativeTo(root).path.replace('\\', '/'),
                    text.substring(0, idx).count { it == '\n' } + 1,
                    literal.take(90),
                )
            }
        }
        return totalCalls to findings
    }

    @Test
    fun `law - every digit-formatting call passes Locale ROOT`() {
        var totalScanned = 0
        val allFindings = mutableListOf<Finding>()
        for ((module, root) in moduleSourceRoots) {
            val (count, findings) = scanModule(module, root)
            totalScanned += count
            allFindings += findings
        }

        assertTrue(
            totalScanned > 100,
            "found only $totalScanned `.format(` call(s) across every module (recon counted 300+) — the scan " +
                "itself is broken; a scan that finds nothing (or nearly nothing) would otherwise pass by " +
                "accident, which is worse than no test at all.",
        )

        if (allFindings.isNotEmpty()) {
            val report = allFindings.joinToString("\n") {
                "  ${it.module}/${it.file}:${it.line}: ${it.literal}"
            }
            fail(
                "${allFindings.size} `.format(` call(s) carry a decimal-digit conversion (%d, %.Nf, …) but " +
                    "don't pass Locale.ROOT as their first argument — under a phone set to Arabic, Persian or " +
                    "Burmese, `String.format`'s digits follow the DEFAULT locale, not this app's own ASCII " +
                    "convention, and render as Eastern-Arabic/Myanmar digits instead (see PadNoteMapLocaleTest, " +
                    "KitAssemblerLocaleTest, KitBuilderLocaleTest — three real instances of this exact bug, " +
                    "each caught by hand after shipping). Fix: insert `java.util.Locale.ROOT, ` (or `Locale.ROOT, " +
                    "` where the file already imports java.util.Locale) as the literal's first `.format(` " +
                    "argument. `%x`/`%X`/`%o`/`%s`/`%c` need no such guard (see this class's own KDoc for why) " +
                    "— only fix the sites actually listed below:\n$report",
            )
        }
    }
}
