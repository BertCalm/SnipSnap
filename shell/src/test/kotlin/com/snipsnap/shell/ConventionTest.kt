package com.snipsnap.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Source-scanning convention laws, run from `:shell` (which has a unit test
 * source set; `:app` does not) against `:app`'s own sources, read as text.
 * Gradle's test JVM working directory is each module's own project dir —
 * no module overrides `tasks.test`'s `workingDir` — so a `:shell` test
 * reaches `:app`'s files via `../app/...` the same way
 * `TeachLogTest.kt:88`/`CalibrationCorpusTest.kt:31` already reach
 * `../reference/...`.
 *
 * The shared bug shape these laws guard against: a pattern applied
 * correctly at some call sites and incorrectly (or not at all) at a
 * sibling site a few lines or one file away — four bugs shipped this shape
 * in one day (2026-09-08): SplitScreen's `BackHandler` used the safe
 * always-register-and-no-op idiom while two bin screens used
 * `enabled = !busy` (which *unregisters*, so Back fell through to
 * `Activity.finish()` mid-write); TapeScreen's pencil-rewind synthesized
 * accessibility action carried a real coroutine duration while HOLD/WIND
 * nearby fired instantly; the SNIP toast said "UP TO 60s" while GRAB, in
 * the *same commit*, still said "LAST 2s"; a kit-write mutex reached the
 * sites someone remembered and missed the rest. A test asserting "every
 * site of this shape agrees" catches the next one of these regardless of
 * which side is wrong — that's the whole point of testing consistency
 * instead of correctness.
 *
 * All three laws below are keyed on file path + the call site's own
 * trimmed text, never line numbers: line numbers drift (a concurrent
 * kit-write-mutex audit is editing several of these exact files right
 * now), and a line-numbered allowlist would start failing for reasons
 * that have nothing to do with the invariant it guards. A text-keyed
 * entry survives drift and still fails loudly the moment the call
 * site's own shape actually changes.
 */
class ConventionTest {

    // ---- Law: a control that can refuse refuses through `enabled` ----

    private val chopScreen = File("../app/src/main/kotlin/com/snipsnap/app/ui/ChopScreen.kt")
    private val tapeScreen = File("../app/src/main/kotlin/com/snipsnap/app/ui/TapeScreen.kt")

    /** The body of a top-level `private fun <name>(` through its closing brace at column 0. */
    /**
     * A top-level `fun` by name, whatever its visibility. It matched
     * `private fun` alone until J18, when `SegmentButton` became
     * `internal` so GROOVE's program row could draw its five segments
     * with the app's existing picker instead of a sixth one. A law about
     * what a component *does* should not fail over who can call it.
     */
    private fun topLevelFun(file: File, name: String): String {
        val src = file.readText(Charsets.UTF_8)
        val decl = Regex("""^(?:private |internal |public )?fun $name\(""", RegexOption.MULTILINE).find(src)
        assertTrue(decl != null, "expected to find a top-level `fun $name(` in ${file.name}")
        val start = decl!!.range.first
        val end = src.indexOf("\n}\n", start)
        assertTrue(end > start, "expected `fun $name(` in ${file.name} to close at column 0")
        return src.substring(start, end)
    }

    /**
     * `ChopScreen.kt`'s own stated rule is "dimmed, not disabled; the toast
     * explains" — and for a long time neither [SegmentButton] nor
     * [DeckButton] could express refusal at all, because neither passed
     * `tapeClick`'s `enabled` flag. Authors who needed to refuse were left
     * with a silent early `return` inside the lambda, which leaves the
     * control lit, tappable, and announced as actionable to TalkBack while
     * doing nothing.
     *
     * This is the class KDoc's shape exactly: the same refusal written one
     * way at some sites and another way at a sibling site. So rather than
     * demand `enabled =` at *every* call site — plenty are unconditional
     * on purpose (CLASSIC/FOLD/MELODIC set a layout; IN/OUT/zoom are cheap
     * model edits) and `enabled = true` everywhere would be noise that
     * teaches nothing — this asserts the negative: **no call site refuses
     * silently inside its own lambda.**
     *
     * The two patterns are the ones actually used: a `return@` out of the
     * lambda, and an `if (!busy)` wrapper around the whole body.
     */
    @Test
    fun `SegmentButton and DeckButton call sites never refuse with a silent return`() {
        for (file in listOf(chopScreen, tapeScreen)) {
            assertTrue(file.isFile, "expected to find ${file.absolutePath}")
            val code = stripCommentsAndStrings(file.readText(Charsets.UTF_8))
            for (component in listOf("SegmentButton", "DeckButton")) {
                val escapes = Regex("""return@$component""").findAll(code).count()
                assertTrue(
                    escapes == 0,
                    "${file.name}: $escapes call site(s) bail out with `return@$component`. " +
                        "A control that can refuse must say so with `enabled = ...` so it dims " +
                        "and reads as disabled, rather than staying lit and doing nothing.",
                )
            }
            // `SecondaryButton` and `PrimaryAction` are held to a different
            // rule, on purpose.
            //
            // The zero-returns rule above is calibrated for two components
            // that historically had no `enabled` at all: forbidding the
            // early return outright is what forced them to grow one. These
            // two always had `enabled`, and their call sites legitimately
            // re-check it inside the lambda — a tap already in flight when
            // the state changes is a real race, and PR 4's RE-CHOP guard is
            // exactly that.
            //
            // So the rule here is not "never return" but "never let the
            // return BE the refusal": a guard is fine when the control also
            // dims, and is a bug when it is the only thing saying no. That
            // is the shape J43 had — CHOP's ◀ ▶ refused a hummed chop with
            // a `?: return` inside `stepHits` while staying lit.
            for (component in listOf("SecondaryButton", "PrimaryAction")) {
                var from = 0
                while (true) {
                    val at = code.indexOf("return@$component", from)
                    if (at < 0) break
                    from = at + 1
                    val callAt = code.lastIndexOf("$component(", at)
                    assertTrue(callAt >= 0, "${file.name}: a `return@$component` with no call site above it")
                    val call = code.substring(callAt, at)
                    assertTrue(
                        "enabled" in call,
                        "${file.name}: a `$component` refuses with a bare `return@$component` and never says " +
                            "so through `enabled`, so it stays lit and does nothing:\n  " +
                            call.lines().first().trim().take(140),
                    )
                }
            }

            val guarded = Regex("""\{\s*if\s*\(!busy\)""").findAll(code).count()
            assertTrue(
                guarded == 0,
                "${file.name}: $guarded lambda(s) open with `if (!busy)`. That is `enabled = !busy` " +
                    "written where the user cannot see it.",
            )
        }
    }

    /**
     * The other half of the same law: the components have to be *able* to
     * refuse, or every call site is forced back into the silent `return`
     * the law above bans.
     *
     * `Chrome.kt`'s `tapeClick(label, enabled = true, onClick)` already
     * does the right thing — a disabled control keeps its semantics node
     * and its name but exposes Compose's `disabled()` state instead of an
     * actionable one. These two components simply have to pass it through.
     *
     * `SegmentButton` additionally announces `selected`, which must stay
     * keyed on `active` **alone**.
     *
     * This half of the law was first written backwards - demanding that
     * `selected` reference `enabled`, on the reasoning that a refused
     * segment should not read as SELECTED. That is the wrong model.
     * `selected` and `disabled()` are orthogonal in Compose, and both are
     * true of the chosen segment during a chop: it is the mode you are in,
     * and it cannot be tapped right now. `tapeClick(enabled = false)`
     * already carries the second. Conflating them makes every segment
     * report NOTHING selected while busy, which loses the answer to "which
     * mode am I in" at the one moment tapping cannot reveal it - and it
     * disagrees with `pressedBevel`, which is keyed on `active` alone.
     */
    @Test
    fun `SegmentButton and DeckButton pass enabled through to tapeClick`() {
        // `internal fun`, not `private fun`, since J18: GROOVE's program
        // row draws its five segments with this one rather than inventing
        // a sixth picker. The visibility is not what this law is about,
        // so it reads the function whichever it is.
        val segment = topLevelFun(chopScreen, "SegmentButton")
        val deck = topLevelFun(tapeScreen, "DeckButton")

        for ((name, body) in listOf("SegmentButton" to segment, "DeckButton" to deck)) {
            assertTrue(
                Regex("""enabled:\s*Boolean""").containsMatchIn(body),
                "$name takes no `enabled: Boolean` parameter, so no call site can refuse " +
                    "except by a silent return.",
            )
            assertTrue(
                Regex("""tapeClick\([^)]*enabled""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(body),
                "$name never passes `enabled` to `tapeClick`, so the flag it accepts changes " +
                    "nothing about whether the control is actionable.",
            )
        }

        assertTrue(
            // `\b` then a negative lookahead for a boolean operator, rather
            // than anchoring to end-of-line: the call sits inside a
            // `semantics { ... }` lambda, so the line ends in ` }` and an
            // `$` anchor never matches it. That anchor was the first shape
            // written here, and it failed against correct source - the law
            // was proven red against the wrong shape but never run green
            // against the right one.
            Regex("""selected\s*=\s*active\b(?!\s*(&&|\|\|))""").containsMatchIn(segment),
            "SegmentButton's `selected` is not keyed on `active` alone. Selection and " +
                "availability are orthogonal: gating `selected` on `enabled` reports nothing " +
                "selected while busy, and contradicts `pressedBevel`, which uses `active`.",
        )
    }

    private val appSrcRoot = File("../app/src/main/kotlin/com/snipsnap/app")

    private fun requireAppKotlinFiles(): List<File> {
        require(appSrcRoot.isDirectory) {
            "expected :app's source root at ${appSrcRoot.absolutePath} but it doesn't exist. " +
                "This test only works when the test JVM's working directory is :shell's own " +
                "project dir (Gradle's default — confirmed no module overrides tasks.test's " +
                "workingDir). If Gradle was invoked with a custom -p, or :app's package moved, " +
                "fix the relative path here rather than deleting this check: a scan that finds " +
                "nothing would otherwise pass by accident, which is worse than no test at all."
        }
        val files = appSrcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        require(files.isNotEmpty()) {
            "found :app's source root at ${appSrcRoot.absolutePath} but zero .kt files under it " +
                "— the directory exists but is empty or the extension filter is wrong. Something " +
                "is broken in the scan, not in :app; fail loudly instead of silently checking nothing."
        }
        return files
    }

    private fun File.relativeToAppRoot(): String = relativeTo(appSrcRoot).path.replace('\\', '/')

    /** A line that's entirely inside a `//` or `/** ... */` comment — cheap enough for this scan's purposes: KDoc bodies in this codebase consistently prefix continuation lines with `*`. */
    private fun isCommentLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private fun normalizeSpan(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    /**
     * Index of the `)` matching the `(` that was just consumed (i.e. the
     * scan starts one char past that `(`). Depth-counted and aware of
     * string literals, so a stray `(`/`)` inside a quoted label (none exist
     * in today's `BackHandler` call sites, but a future one might) can't
     * desync the count. Null only on malformed input (unmatched paren),
     * which means the scan itself is broken, not that it found a real site.
     */
    private fun matchingParen(text: String, afterOpenIndex: Int): Int? {
        var depth = 1
        var i = afterOpenIndex
        while (i < text.length) {
            when (text[i]) {
                '(' -> {
                    depth++
                    i++
                }
                ')' -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
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
     * Index of the `}` matching the `{` that was just consumed (i.e. the
     * scan starts one char past that `{`) — [matchingParen]'s twin, for
     * finding the extent of a `withLock { ... }` trailing-lambda block.
     * Same depth-counting, same string-literal awareness. Null only on
     * malformed input, which means the scan itself is broken.
     */
    private fun matchingBrace(text: String, afterOpenIndex: Int): Int? {
        var depth = 1
        var i = afterOpenIndex
        while (i < text.length) {
            when (text[i]) {
                '{' -> {
                    depth++
                    i++
                }
                '}' -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
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

    // ==================== Law 3: BackHandler registration is uniform ====================

    private data class BackHandlerSite(val file: String, val args: String, val hasEnabled: Boolean)

    /** Every `BackHandler(...)` call in `:app`, with its argument text (paren-matched across lines) and whether it gates on `enabled =`. */
    private fun scanBackHandlerSites(): List<BackHandlerSite> {
        val sites = mutableListOf<BackHandlerSite>()
        for (file in requireAppKotlinFiles()) {
            val text = file.readText(Charsets.UTF_8)
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf("BackHandler(", searchFrom)
                if (idx < 0) break
                searchFrom = idx + 1
                val lineStart = text.lastIndexOf('\n', idx) + 1
                val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
                if (isCommentLine(text.substring(lineStart, lineEnd))) continue
                val argsStart = idx + "BackHandler(".length
                val argsEnd = matchingParen(text, argsStart)
                    ?: fail(
                        "${file.relativeToAppRoot()}: found `BackHandler(` with no matching `)` — this is a bug " +
                            "in the scan (or genuinely malformed Kotlin, which wouldn't compile), not a real finding.",
                    )
                val args = text.substring(argsStart, argsEnd)
                sites += BackHandlerSite(
                    file.relativeToAppRoot(),
                    normalizeSpan(args),
                    Regex("""\benabled\s*=""").containsMatchIn(args),
                )
            }
        }
        return sites
    }

    private data class BackHandlerAllow(val file: String, val args: String, val justification: String)

    /**
     * Sites where `BackHandler` legitimately uses `enabled =` instead of
     * the safe shape (register unconditionally; no-op inside `onBack`
     * when not applicable). Anything using `enabled =` that isn't listed
     * here fails the law below. Both entries here were verified by reading
     * the surrounding code, not assumed from a commit message.
     */
    private val backHandlerAllowlist = listOf(
        BackHandlerAllow(
            file = "App.kt",
            args = normalizeSpan(
                """
                enabled = !anyOverlayOpen && screenHistory.isNotEmpty() &&
                    screen != AppScreen.SPLIT && screen != AppScreen.KEYS &&
                    note == null && !captureBlocked && !micPermissionDenied,
                """,
            ),
            justification = "the app-level ROOT handler, not a busy-guard: its entire job is to defer. " +
                "anyOverlayOpen, SPLIT, and KEYS each already have their own unconditional handler or their own " +
                "back door (the KDoc directly above this call enumerates them); the remaining case is an EMPTY " +
                "screenHistory, which is the true root of the session - falling through to Activity.finish() " +
                "there is the correct, intended Android behavior, not an accident. This used to read " +
                "`screen != AppScreen.KITS` and said the same thing a weaker way (J11): with a back stack, being " +
                "the root is a property of having nowhere to go back to, not of being one particular tab, and a " +
                "non-empty history always has somewhere to pop to. This is the one site that is SUPPOSED to read " +
                "as a fallback, never as a mid-operation guard.",
        ),
        BackHandlerAllow(
            file = "ui/SnipsScreen.kt",
            args = normalizeSpan("enabled = confirmDelete == null && renameTarget == null"),
            justification = "paired, now for two dialogs: while EITHER confirmDelete or renameTarget is non-null " +
                "(this one dark), the relevant dialog composes its own unconditional BackHandler " +
                "(onBack = cancelDelete / onBack = cancelRename) in that exact window (see the innermost " +
                "BackHandlers further down, inside `confirmDelete?.let { ... }` and `renameTarget?.let { ... }`), " +
                "so Back is never left with zero enabled callbacks. renameTarget joined confirmDelete here when " +
                "RENAME shipped (name-and-find task) — same reasoning, same shape. Verified below by the " +
                "self-check that this file still contains an unconditional BackHandler.",
        ),
    )

    @Test
    fun `law - BackHandler is registered unconditionally except at allowlisted, self-checked sites`() {
        val sites = scanBackHandlerSites()
        assertTrue(
            sites.isNotEmpty(),
            "found zero BackHandler( sites under :app (recon counted 16) — the scan is broken; a scan that " +
                "finds nothing would otherwise pass by accident, which is worse than no test at all.",
        )

        val conditional = sites.filter { it.hasEnabled }
        for (site in conditional) {
            val allowed = backHandlerAllowlist.any { it.file == site.file && it.args == site.args }
            assertTrue(
                allowed,
                "${site.file}: `BackHandler(${site.args})` uses `enabled =` to gate registration instead of " +
                    "registering unconditionally and no-op'ing inside onBack. `enabled = <cond>` UNREGISTERS the " +
                    "handler when <cond> is false — if this was the only handler active at that moment (an " +
                    "overlay's own handler already gone, or one was never registered), Back falls straight through " +
                    "to Activity.finish(), exiting the app mid-operation. This is exactly SplitScreen-vs-bin-screens' " +
                    "2026-09-08 bug shape. Either switch to `BackHandler(onBack = { if (<cond>) ... })` (always " +
                    "registered, no-ops when not applicable), or, if this site is genuinely safe the way " +
                    "ui/SnipsScreen.kt:185 is, add it to ConventionTest.backHandlerAllowlist with a justification " +
                    "for why it can never leave zero enabled callbacks.",
            )
        }

        for (allowed in backHandlerAllowlist) {
            assertTrue(
                sites.any { it.file == allowed.file && it.args == allowed.args && it.hasEnabled },
                "${allowed.file}: the allowlisted `BackHandler(${allowed.args})` no longer matches anything in " +
                    "source. Either it was fixed to register unconditionally (delete this allowlist entry) or the " +
                    "call site changed shape (re-review the new shape before updating this entry's text — a text " +
                    "edit that merely re-matches a changed line could be laundering a real regression through).",
            )
            assertTrue(
                sites.any { it.file == allowed.file && !it.hasEnabled },
                "${allowed.file}: this file's allowlisted `enabled =` BackHandler is justified by a sibling " +
                    "unconditional BackHandler existing in the same file (see its justification above) — but no " +
                    "unconditional BackHandler( was found in ${allowed.file} anymore. The justification no longer " +
                    "holds; re-review this site before keeping it allowlisted, it may have gone unsafe.",
            )
        }
    }

    // ==================== Law 4: no unbounded audio decode in :app ====================

    private data class ReadSite(val file: String, val line: String)

    /** Every non-comment line under `:app` containing a literal `WavReader.read(` call (never matches `WavReader.readCapped(`/`readSmpl(` — the literal substring requires `read` immediately followed by `(`). */
    private fun scanWavReaderReadSites(): List<ReadSite> {
        val sites = mutableListOf<ReadSite>()
        for (file in requireAppKotlinFiles()) {
            for (rawLine in file.readLines()) {
                if (isCommentLine(rawLine)) continue
                if (rawLine.contains("WavReader.read(")) {
                    sites += ReadSite(file.relativeToAppRoot(), rawLine.trim())
                }
            }
        }
        return sites
    }

    private data class ReadAllow(val file: String, val line: String, val justification: String)

    /**
     * JUSTIFIED — reviewed and genuinely correct as-is, not merely
     * grandfathered: `WavReader.read` has no capped equivalent for this
     * input's own shape.
     */
    private val justifiedReads = listOf(
        ReadAllow(
            file = "MediaDecode.kt",
            line = "return WavReader.read(bytes)",
            justification = "`bytes` is already gated a few lines up by " +
                "`require(bytes.size <= MAX_WAV_BYTES)` (96 MB, MediaDecode.MAX_WAV_BYTES) before this line runs. " +
                "WavReader.readCapped(file: File, maxDurationSec: Float, ...) (audio/.../WavReader.kt:148) only " +
                "has a File-based overload — there is no capped entry point for an in-memory ByteArray — so this " +
                "IS the correct call for this input shape, not a gap the guardrail should chase.",
        ),
    )

    @Test
    fun `law - app code only calls the bounded WavReader readCapped, never the unbounded read`() {
        val sites = scanWavReaderReadSites()
        assertTrue(
            sites.isNotEmpty(),
            "found zero WavReader.read( sites under :app (recon counted 1, MediaDecode.kt's ByteArray overload, " +
                "which can never use readCapped — see justifiedReads) — if that site's line genuinely changed " +
                "shape, re-review and update justifiedReads rather than assuming this is progress; a scan " +
                "finding nothing would otherwise pass by accident, which is worse than no test at all.",
        )

        val allowlist = justifiedReads
        for (site in sites) {
            val allowed = allowlist.any { it.file == site.file && it.line == site.line }
            assertTrue(
                allowed,
                "${site.file}: `${site.line}` calls the unbounded WavReader.read, which decodes the ENTIRE file " +
                    "to float32 with no size ceiling — a 192kHz stereo field recording can cost roughly 900MB for " +
                    "one decode buffer alone (see commit 0f2ff24). :app must call " +
                    "WavReader.readCapped(file, maxDurationSec) instead, the way TAPE's own load path and its " +
                    "COMMIT/READ GROOVE/COPY GROOVE/INSTANT KIT consumers do (4fd6aeb/b73ad49/0f2ff24) — an OOM " +
                    "here crashes the whole app, not just the operation in progress. If this site genuinely can't " +
                    "use readCapped (e.g. it decodes a ByteArray, not a File, the way MediaDecode.kt does), add it " +
                    "to ConventionTest.justifiedReads with a reviewed justification, not just to make this pass.",
            )
        }

        for (allowed in allowlist) {
            assertTrue(
                sites.any { it.file == allowed.file && it.line == allowed.line },
                "${allowed.file}: the allowlisted WavReader.read call `${allowed.line}` no longer matches anything " +
                    "in source. Either it was fixed to use readCapped (delete this entry) or the call site changed " +
                    "shape (re-review the new shape before updating the text here — a text edit that merely " +
                    "re-matches a changed line could be laundering a real regression through).",
            )
        }
    }

    // ==================== Law 5: KitBuilderModel.open is always lock-guarded or allowlisted ====================

    /**
     * A kit MUTATION (anything that later calls `.save()` on the model this
     * `open` produced, or mutates it) is only safe when the whole
     * open→mutate→save sequence runs inside `KitWrites.mutex.withLock` AND
     * the model was opened inside that same lock — a model opened earlier
     * and saved later under the lock is still a stale-snapshot clobber
     * (`KitWrites`' own KDoc; the bug class fcf37f9/f011778 closed for
     * SPLIT/SURFACE/EXPORT). A model opened only to READ (never `.save()`d,
     * never mutated) does not need the lock at all.
     *
     * This is a two-halved invariant and THIS law enforces only the FIRST
     * half: that every `open(` itself runs inside a `withLock` span,
     * somewhere. It does NOT prove the matching mutate+save happen in that
     * SAME span, nor that whatever eventually gets `.save()`d was opened
     * inside its own lock rather than carried in from an earlier, already-
     * released one — that second half (a model opened once and saved
     * later, safely-inside-*a*-lock but not the one that opened it) is
     * exactly the shape that shipped live in PadSheetScreen.kt's
     * `applyTreatment`/`onMutate`/`onDrift`/`onDesample`/`onOutside`
     * (2026-09-08 audit) while this law stayed green throughout, because
     * none of those five call `open(` at all — Law 5's scan has nothing to
     * flag. **Law 6, immediately below this one, enforces that second
     * half**: every `.save()` must share its own lock span with its own
     * `open(`.
     *
     * What [inLock] actually proves is narrower than the invariant above:
     * it's true when the `open(` call's own index falls inside *some*
     * `KitWrites.mutex.withLock { ... }` span in the same file — it does
     * NOT prove the matching mutate+save live in that same span. Two
     * separate `withLock { open() }` / `withLock { mutate(); save() }`
     * blocks would each read as guarded here while still racing each
     * other. Every guarded site in this codebase today keeps open,
     * mutate, and save together in one block (verified by reading each
     * one, not assumed from this flag) — but this scan cannot enforce
     * that shape, only the sites currently on this allowlist. Law 6 closes
     * this exact gap from the save side.
     */
    private data class KitWriteSite(val file: String, val text: String, val inLock: Boolean)

    /** Every `KitWrites.mutex.withLock { ... }` brace-span in [text], via [matchingBrace] — a call site's index falling inside one of these means it's guarded, full stop, regardless of how far back the lock call sits. */
    private fun lockSpans(text: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var searchFrom = 0
        while (true) {
            val lockIdx = text.indexOf("KitWrites.mutex.withLock", searchFrom)
            if (lockIdx < 0) break
            val lockLineStart = text.lastIndexOf('\n', lockIdx) + 1
            val lockLineEnd = text.indexOf('\n', lockIdx).let { if (it < 0) text.length else it }
            if (isCommentLine(text.substring(lockLineStart, lockLineEnd))) {
                // A `withLock` mentioned only in a comment (e.g. "TODO: wrap
                // this in KitWrites.mutex.withLock {") must not mint a real
                // span — matchingBrace would run forward to the next
                // unrelated `}` and silently bless whatever unguarded opens
                // happen to fall inside it. That false-pass is exactly what
                // this file's own KDoc calls worse than no test at all.
                searchFrom = lockIdx + 1
                continue
            }
            val braceStart = text.indexOf('{', lockIdx + "KitWrites.mutex.withLock".length)
            if (braceStart < 0) {
                fail(
                    "found `KitWrites.mutex.withLock` with no following `{` in source — this is a bug in the " +
                        "scan (or genuinely malformed Kotlin, which wouldn't compile), not a real finding.",
                )
            }
            val braceEnd = matchingBrace(text, braceStart + 1)
                ?: fail(
                    "found `KitWrites.mutex.withLock {` with no matching `}` — this is a bug in the scan (or " +
                        "genuinely malformed Kotlin, which wouldn't compile), not a real finding.",
                )
            spans += braceStart..braceEnd
            searchFrom = braceEnd + 1
        }
        return spans
    }

    /** Every `KitBuilderModel.open(` call in `:app` (matches both the bare and `com.snipsnap.shell`-qualified forms — both contain this literal substring), with its own trimmed source line and whether its index falls inside a [lockSpans] span in the same file. */
    private fun scanKitWriteOpenSites(): List<KitWriteSite> {
        val sites = mutableListOf<KitWriteSite>()
        for (file in requireAppKotlinFiles()) {
            val text = file.readText(Charsets.UTF_8)
            val spans = lockSpans(text)
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf("KitBuilderModel.open(", searchFrom)
                if (idx < 0) break
                searchFrom = idx + 1
                val lineStart = text.lastIndexOf('\n', idx) + 1
                val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
                val line = text.substring(lineStart, lineEnd)
                if (isCommentLine(line)) continue
                sites += KitWriteSite(
                    file.relativeToAppRoot(),
                    normalizeSpan(line),
                    spans.any { idx in it },
                )
            }
        }
        return sites
    }

    private object KitWriteCategory {
        /** Never `.save()`d, never mutated — reading a kit's state alone needs no lock. */
        const val READ_ONLY = "READ_ONLY"

        /** Takes no lock itself, but every existing caller already wraps the *entire* call (open included) in `KitWrites.mutex.withLock` — verified by reading each caller, not assumed. A scan local to this file can't see that, hence the explicit allowlist entry. */
        const val CALLER_GUARANTEED = "CALLER_GUARANTEED"

        /** Opens outside any lock and later saves that SAME long-lived instance inside `withLock` elsewhere in the file — a real stale-snapshot clobber window. Accepted debt awaiting the reopen-and-replay redesign, NOT a blessing: do not read this category as "reviewed safe." */
        const val KNOWN_STALE_SNAPSHOT = "KNOWN_STALE_SNAPSHOT"
    }

    private data class KitWriteAllow(
        val file: String,
        val text: String,
        val category: String,
        /** How many distinct unguarded sites in [file] share this exact [text] — KitShelf.kt has three byte-identical `open(source.dir)` lines (setKey/evilTwins/inKey), so file+text alone can't tell them apart; this makes a FOURTH one added later fail loudly instead of riding along on this entry. */
        val count: Int = 1,
        val justification: String,
    )

    /**
     * The audit (fcf37f9/f011778 plus this pass) classified all 12
     * `KitBuilderModel.open(` sites in `:app`. Six are guarded (open,
     * mutate, and save all inside one `KitWrites.mutex.withLock`) and need
     * no entry here — they pass the law automatically. The other six are
     * listed below, each reviewed and put in its own category; nothing
     * here is a rubber stamp, and KNOWN_STALE_SNAPSHOT entries are
     * explicitly the opposite of one.
     */
    private val kitWriteAllowlist = listOf(
        KitWriteAllow(
            file = "App.kt",
            text = "runCatching { KitBuilderModel.open(kitDir).purgeBin() }",
            category = KitWriteCategory.READ_ONLY,
            justification = "the launch sweep over every kit on the shelf. purgeBin() (KitBuilder.kt) only " +
                "deletes files already older than BIN_KEEP_DAYS out of the on-disk bin folder — it never calls " +
                "model.save() or touches kit.json at all, so there is no write for another writer to race.",
        ),
        KitWriteAllow(
            file = "KitShelf.kt",
            text = "val model = com.snipsnap.shell.KitBuilderModel.open(source.dir)",
            category = KitWriteCategory.CALLER_GUARANTEED,
            count = 3,
            justification = "shared by setKey/evilTwins/inKey (three byte-identical open() lines — see this " +
                "entry's `count`). None of the three takes a lock itself, but every call site in App.kt " +
                "(::setKey, ::evilTwins, ::inKey) wraps the ENTIRE shelf call — open, mutate, and save alike — " +
                "in `KitWrites.mutex.withLock` before ever calling into KitShelf. Verified by reading each " +
                "caller, not assumed from a naming convention: this is a caller-guarantees pattern a scan local " +
                "to KitShelf.kt cannot see, which is exactly why it needs an explicit, reviewed entry instead of " +
                "being silently correct-by-luck.",
        ),
        // KNOWN_STALE_SNAPSHOT's two entries (ui/PadSheetScreen.kt and
        // ui/TakesBinScreen.kt's screen-mount opens) are gone: both mounts
        // now open under KitWrites.mutex.withLock themselves, and every
        // save downstream of them (commitPadEditNow, applySmear,
        // requestBack, the debounced metadata flush, and TakesBinScreen's
        // doRestoreTake) runs against a model opened FRESH under the lock
        // at save time via each file's own private `withFreshKit`, never
        // the long-lived mount-time instance. See PadSheetScreen.kt's
        // `withFreshKit`/`reapplyPendingMetadataFields` KDoc for the full
        // reasoning. This category constant stays defined for the next
        // debt that genuinely earns it — do not repopulate it with an
        // entry that hasn't been reviewed as carefully as these were.
    )

    @Test
    fun `law - every KitBuilderModel open runs inside KitWrites mutex withLock or is explicitly allowlisted`() {
        val sites = scanKitWriteOpenSites()
        assertTrue(
            sites.isNotEmpty(),
            "found zero KitBuilderModel.open( sites under :app (recon counted 12) — the scan is broken; a scan " +
                "that finds nothing would otherwise pass by accident, which is worse than no test at all.",
        )

        val unguarded = sites.filterNot { it.inLock }
        for (site in unguarded) {
            val allowed = kitWriteAllowlist.any { it.file == site.file && it.text == site.text }
            assertTrue(
                allowed,
                "${site.file}: `${site.text}` opens a KitBuilderModel outside KitWrites.mutex.withLock and isn't " +
                    "allowlisted. A kit MUTATION is only safe when the whole open→mutate→save sequence runs " +
                    "inside KitWrites.mutex.withLock AND the model was opened inside that same lock — a model " +
                    "opened earlier and saved later under the lock is still a stale-snapshot clobber (see " +
                    "KitWrites.kt's own KDoc and fcf37f9/f011778). A model opened only to READ (never .save()d, " +
                    "never mutated) does not need the lock. Either move this open inside the same " +
                    "KitWrites.mutex.withLock block as its mutate+save, or — only after actually reading every " +
                    "call site — add it to ConventionTest.kitWriteAllowlist under the correct category " +
                    "(READ_ONLY: truly never saved; CALLER_GUARANTEED: every existing caller already wraps the " +
                    "whole call in withLock; KNOWN_STALE_SNAPSHOT: accepted debt, not a blessing) with a " +
                    "reviewed justification, not just to make this pass.",
            )
        }

        for (allowed in kitWriteAllowlist) {
            val matches = unguarded.count { it.file == allowed.file && it.text == allowed.text }
            assertTrue(
                matches == allowed.count,
                "${allowed.file}: expected exactly ${allowed.count} unguarded site(s) matching `${allowed.text}` " +
                    "(category ${allowed.category}) but found $matches. If this went UP, a new unguarded open " +
                    "with this exact text joined the ones already reviewed here and is silently riding along on " +
                    "this entry's blessing — re-review each one before raising `count`. If it went DOWN " +
                    "(including to zero), one or more were fixed (moved inside a lock) — shrink or delete this " +
                    "entry rather than leaving it protecting nothing.",
            )
            assertTrue(
                sites.any { it.file == allowed.file && it.text == allowed.text },
                "${allowed.file}: the allowlisted open `${allowed.text}` no longer matches anything in source at " +
                    "all. Either every occurrence was rewritten to a different shape (delete this entry after " +
                    "re-reviewing the new shape — a text edit that merely re-matches a changed line could be " +
                    "laundering a real regression through) or it was deleted outright (delete this entry too).",
            )
        }
    }

    // ==================== Law 6: KitBuilderModel.save shares its lock span with its own open ====================

    /**
     * Law 5 above enforces the FIRST half of `KitWrites`' invariant: every
     * `KitBuilderModel.open(` runs inside SOME `KitWrites.mutex.withLock`
     * span. It does NOT enforce the second half: that whatever later calls
     * `.save()` was opened inside THAT SAME span. A model opened once —
     * itself correctly locked, so Law 5 passes — and then held across a
     * screen's lifetime while later actions each wrap only their
     * mutate+save in a FRESH `withLock` is still a stale-snapshot clobber:
     * the lock there only serializes the WRITE against other writers, it
     * never refreshes the instance being written. Because none of those
     * later `.save()` sites call `open(` themselves, Law 5's scan has
     * nothing to flag and passes green over a live bug — this was exactly
     * PadSheetScreen.kt's `applyTreatment`/`onMutate`/`onDrift`/
     * `onDesample`/`onOutside` until this law's own audit (2026-09-08)
     * named them KNOWN_STALE_SAVE below. A concurrent SET KEY/EVIL
     * TWINS/IN KEY write landing between the original open and one of
     * these saves is silently overwritten by whatever that save still
     * thinks the kit looks like.
     *
     * This law closes that gap: every `KitBuilderModel.save()` call in
     * `:app` must run inside a `KitWrites.mutex.withLock { ... }` span
     * whose SAME span also contains a `KitBuilderModel.open(` — proof the
     * instance being saved is the one THIS lock acquisition itself opened,
     * not a longer-lived one carried in from outside it.
     */
    private object KitSaveCategory {
        /**
         * No lock at the save site itself, but every existing caller
         * already wraps the ENTIRE call — open through save — in
         * `KitWrites.mutex.withLock`; verified by reading each caller.
         * Mirrors [KitWriteCategory.CALLER_GUARANTEED] one section up.
         */
        const val CALLER_GUARANTEED = "CALLER_GUARANTEED"

        /**
         * `.save()` runs inside a real `KitWrites.mutex.withLock` span —
         * but that span's own `KitBuilderModel.open(` is missing: the
         * model being saved was opened earlier (typically at screen
         * mount, itself under its OWN separate, already-released lock
         * acquisition — see Law 5) and carried into this lock as a
         * long-lived reference. Accepted debt awaiting a
         * `withFreshKit`-shaped conversion — the shape every OTHER write
         * path in PadSheetScreen.kt already uses; see that file's own
         * `withFreshKit` KDoc — NOT a blessing: do not read this category
         * as "reviewed safe."
         */
        const val KNOWN_STALE_SAVE = "KNOWN_STALE_SAVE"
    }

    private data class KitSaveSite(val file: String, val text: String, val guarded: Boolean)

    /**
     * True when [text] contains [literal] at or after [range]'s own first
     * index and at or before its last — the same "search starting at the
     * range's own start" trick [lockSpans] itself relies on to place its
     * `{`/`}` pairs: a hit strictly before [range] can never be returned,
     * since the search never looks there.
     */
    private fun rangeContainsLiteral(text: String, range: IntRange, literal: String): Boolean {
        val idx = text.indexOf(literal, range.first)
        return idx in 0..range.last
    }

    /**
     * Every zero-argument `.save()` call in `:app` files that mention
     * `KitBuilderModel` anywhere in the file. The literal, empty-parens
     * `.save()` reliably identifies `KitBuilderModel.save(accrueWear:
     * Boolean = true)` called with its default in this codebase
     * specifically — verified (2026-09-08 audit) against every OTHER
     * `.save(` receiver under `:app` (`KitStore.save`, `SurfaceStore.save`,
     * `GrooveEdit.save`, `PocketStore.save`, `AnswerStore.save`,
     * `SessionStore.save`): every one of them takes at least one required
     * argument, so none of them can ever produce a bare `.save()`. Scoping
     * to files that mention `KitBuilderModel` at all is belt-and-braces on
     * top of that: an unrelated future zero-arg `.save()` on some other
     * type can't false-positive here without also mentioning
     * `KitBuilderModel` somewhere in the same file.
     *
     * [guarded] is true only when the call's own index falls inside a
     * `KitWrites.mutex.withLock` span whose SAME span (via
     * [rangeContainsLiteral]) also contains a `KitBuilderModel.open(` —
     * see this law's own KDoc above for why that, and not just "inside
     * some lock", is the actual invariant.
     *
     * [KitSaveSite.text] is keyed on the enclosing lock span's own
     * (normalized) source text when one exists, not just the `.save()`
     * line itself: PadSheetScreen's five KNOWN_STALE_SAVE sites all save
     * via the byte-identical line `m.save()`, so the line alone can't
     * tell them apart the way file+text needs to (same reasoning as Law
     * 5's own `count` field, taken one step further here since these
     * five are otherwise textually indistinguishable, not just repeated).
     * Falls back to the bare line's own text only when there's no
     * enclosing span at all (the CALLER_GUARANTEED shape below).
     */
    private fun scanKitSaveSites(): List<KitSaveSite> {
        val sites = mutableListOf<KitSaveSite>()
        for (file in requireAppKotlinFiles()) {
            val text = file.readText(Charsets.UTF_8)
            if (!text.contains("KitBuilderModel")) continue
            val spans = lockSpans(text)
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf(".save()", searchFrom)
                if (idx < 0) break
                searchFrom = idx + 1
                val lineStart = text.lastIndexOf('\n', idx) + 1
                val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
                val line = text.substring(lineStart, lineEnd)
                if (isCommentLine(line)) continue
                val enclosingSpan = spans.firstOrNull { idx in it }
                val guarded = enclosingSpan != null &&
                    rangeContainsLiteral(text, enclosingSpan, "KitBuilderModel.open(")
                val keyText = if (enclosingSpan != null) {
                    normalizeSpan(text.substring(enclosingSpan.first, enclosingSpan.last + 1))
                } else {
                    normalizeSpan(line)
                }
                sites += KitSaveSite(file.relativeToAppRoot(), keyText, guarded)
            }
        }
        return sites
    }

    private data class KitSaveAllow(
        val file: String,
        val text: String,
        val category: String,
        /** How many distinct unguarded sites in [file] share this exact [text] — KitShelf.kt's setKey and evilTwins both save via the byte-identical line `model.save()`, so file+text alone can't tell them apart; this makes a THIRD one added later fail loudly instead of riding along on this entry. */
        val count: Int = 1,
        val justification: String,
    )

    /**
     * The 2026-09-08 audit classified every `.save()` site [scanKitSaveSites]
     * finds. Most are guarded (open, mutate, and save all inside one
     * `KitWrites.mutex.withLock` span) and need no entry here — they pass
     * automatically. The rest are listed below, each reviewed and put in
     * its own category; KNOWN_STALE_SAVE entries are explicitly the
     * opposite of a blessing — they are the debt this law exists to keep
     * visible and counted, not clear.
     */
    private val kitSaveAllowlist = listOf(
        KitSaveAllow(
            file = "KitShelf.kt",
            text = normalizeSpan("model.save()"),
            category = KitSaveCategory.CALLER_GUARANTEED,
            count = 2,
            justification = "setKey and evilTwins (two byte-identical `model.save()` lines — see this entry's " +
                "`count`). Neither takes a lock itself, but every call site in App.kt (::setKey, ::evilTwins) " +
                "wraps the ENTIRE shelf call — open, mutate, and save alike — in `KitWrites.mutex.withLock` " +
                "before ever calling into KitShelf, the same caller-guarantees shape Law 5 already allowlists " +
                "these same two opens under.",
        ),
        KitSaveAllow(
            file = "KitShelf.kt",
            text = normalizeSpan("if (moved.isNotEmpty()) model.save()"),
            category = KitSaveCategory.CALLER_GUARANTEED,
            justification = "inKey. Same caller-guarantees shape as setKey/evilTwins above — App.kt's ::inKey " +
                "wraps the whole shelf call in `KitWrites.mutex.withLock` — it's just its own allowlist entry " +
                "since the save here is conditional on `moved.isNotEmpty()` where the other two aren't.",
        ),
        // PadSheetScreen.kt's five KNOWN_STALE_SAVE entries (applyTreatment's
        // era/character/keyed branch, onMutate, onDrift, onOutside,
        // onDesample) are gone: each now runs its open→mutate→save through
        // that file's own withFreshKit, the same shape commitPadEditNow and
        // applySmear already used. onOutside's mic capture (and the send it
        // reads) still runs fully unlocked, ahead of withFreshKit — only the
        // rewrite, the open, and the save moved inside the lock span. See
        // each function's own KDoc for the per-path reasoning.
    )

    @Test
    fun `law - every KitBuilderModel save shares its own lock span with its own open, or is explicitly allowlisted`() {
        val sites = scanKitSaveSites()
        assertTrue(
            sites.isNotEmpty(),
            "found zero KitBuilderModel.save() sites under :app (recon counted 15, 2026-09-08) — the scan is " +
                "broken; a scan that finds nothing would otherwise pass by accident, which is worse than no test " +
                "at all.",
        )

        val unguarded = sites.filterNot { it.guarded }
        for (site in unguarded) {
            val allowed = kitSaveAllowlist.any { it.file == site.file && it.text == site.text }
            assertTrue(
                allowed,
                "${site.file}: a `KitBuilderModel.save()` in `${site.text}` does not share a lock span with its " +
                    "own `KitBuilderModel.open(` — either it isn't inside any KitWrites.mutex.withLock at all, " +
                    "or it is, but that span's model was opened OUTSIDE it (often a long-lived instance from " +
                    "screen mount). A kit MUTATION is only safe when the whole open→mutate→save sequence runs " +
                    "inside ONE KitWrites.mutex.withLock span — a model opened earlier and saved later under a " +
                    "(different, or no) lock is still a stale-snapshot clobber: a concurrent SET KEY/EVIL " +
                    "TWINS/IN KEY write landing between the original open and this save is silently overwritten " +
                    "by whatever this save still thinks the kit looks like. Either move the open (or reopen a " +
                    "FRESH model — see PadSheetScreen.kt's own withFreshKit) inside this same lock span, or — " +
                    "only after actually reading the call site — add it to ConventionTest.kitSaveAllowlist under " +
                    "the correct category (CALLER_GUARANTEED: every existing caller already wraps the whole call " +
                    "in withLock; KNOWN_STALE_SAVE: accepted debt, not a blessing) with a reviewed justification, " +
                    "not just to make this pass.",
            )
        }

        for (allowed in kitSaveAllowlist) {
            val matches = unguarded.count { it.file == allowed.file && it.text == allowed.text }
            assertTrue(
                matches == allowed.count,
                "${allowed.file}: expected exactly ${allowed.count} unguarded save site(s) matching " +
                    "`${allowed.text}` (category ${allowed.category}) but found $matches. If this went UP, a new " +
                    "unguarded save with this exact text joined the ones already reviewed here and is silently " +
                    "riding along on this entry's blessing — re-review each one before raising `count`. If it " +
                    "went DOWN (including to zero), one or more were fixed (its open now shares this save's own " +
                    "lock span) — shrink or delete this entry rather than leaving it protecting nothing.",
            )
            assertTrue(
                sites.any { it.file == allowed.file && it.text == allowed.text },
                "${allowed.file}: the allowlisted save `${allowed.text}` no longer matches anything in source at " +
                    "all. Either it was fixed (delete this entry after re-reviewing the new shape — a text edit " +
                    "that merely re-matches a changed line could be laundering a real regression through) or it " +
                    "was deleted outright (delete this entry too).",
            )
        }
    }

    // ---- Law: a doc-comment is never stranded above another doc-comment ----

    /** Every module's `src/main/kotlin`, from :shell's own project dir — the working directory Gradle gives `tasks.test`. */
    private val moduleSrcRoots: List<File>
        get() = listOf("app", "audio", "cli", "json", "kit", "loop", "mpc3", "shell", "synth", "xpm")
            .map { File("../$it/src/main/kotlin") }

    /** One doc-comment block: `first` and `last` are 0-based line indices, both inclusive. */
    private data class DocBlock(val first: Int, val last: Int)

    /** Every doc-comment block in [lines], in order. The opening delimiter must start its own line, which is this codebase's universal shape. */
    private fun docBlocks(lines: List<String>): List<DocBlock> {
        val blocks = mutableListOf<DocBlock>()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("/**")) {
                var j = i
                // A one-liner opens and closes on the same line; anything
                // else runs to the first line ending in a close delimiter.
                if (!(t.endsWith("*/") && t.length > 3)) {
                    while (j < lines.size && !lines[j].trim().endsWith("*/")) j++
                }
                if (j >= lines.size) break
                blocks += DocBlock(i, j)
                i = j + 1
            } else {
                i++
            }
        }
        return blocks
    }

    /**
     * A doc-comment documents the declaration directly beneath it. When
     * another doc-comment is what sits directly beneath it instead, the
     * first one documents nothing — it reaches no IDE and no generated
     * doc — and the declaration it was written for is left bare.
     *
     * This is the doc-comment face of the bug 30e6d42 fixed: an
     * `@OptIn(ExperimentalFoundationApi::class)` that had been separated
     * from its function by an insertion, so the opt-in landed on a
     * function that didn't need it and `SliceRow`, which did, lost it and
     * turned `android-build` red. Same cause both times — a declaration
     * inserted by anchoring on a signature line without looking at what
     * sits above it — and the compiler is silent for both, because the
     * file stays syntactically valid either way. Six of these were live in
     * the tree when this law was written, across three modules.
     *
     * **The one exemption** is a file's *first* doc-comment. Kotlin has no
     * file-level doc syntax, so a file overview is written as a
     * doc-comment above the file's first declaration — which is
     * positionally identical to a stranded one. Every module is scanned,
     * not just `:app`: the instances were spread across `:app`, `:shell`
     * and `:kit`, and a law that had only covered `:app` would have missed
     * the one that prompted it.
     */
    @Test
    fun `a doc-comment is never stranded above another doc-comment`() {
        val roots = moduleSrcRoots
        val missing = roots.filterNot { it.isDirectory }
        require(missing.isEmpty()) {
            "expected every module's source root to exist but these don't: " +
                missing.joinToString { it.absolutePath } + ". This scan only works when the test " +
                "JVM's working directory is :shell's own project dir (Gradle's default). If a module " +
                "was renamed or removed, fix the list here rather than deleting the check: a scan " +
                "that finds nothing would pass by accident, which is worse than no test at all."
        }
        val files = roots.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
        require(files.size > 100) {
            "found only ${files.size} .kt files across ${roots.size} module source roots — the scan is " +
                "broken, not the tree. Fail loudly instead of silently checking almost nothing."
        }

        val stranded = mutableListOf<String>()
        for (file in files) {
            val lines = file.readText(Charsets.UTF_8).split("\n")
            val blocks = docBlocks(lines)
            // `drop(1)`: the file's first doc-comment is the file-overview
            // idiom and is exempt — see this law's KDoc.
            for (block in blocks.drop(1)) {
                var k = block.last + 1
                while (k < lines.size && lines[k].isBlank()) k++
                if (k < lines.size && lines[k].trim().startsWith("/**")) {
                    stranded += "${file.path.replace('\\', '/')}:${block.first + 1} " +
                        "(next doc-comment opens at line ${k + 1})"
                }
            }
        }

        assertTrue(
            stranded.isEmpty(),
            "these doc-comments document nothing — another doc-comment sits directly beneath each of " +
                "them, so the declaration each was written for is undocumented:\n  " +
                stranded.joinToString("\n  ") +
                "\nMove each block down to sit directly above the declaration it describes, or delete " +
                "it if a newer doc on that declaration has already superseded it. Do not silence this " +
                "by merging two unrelated blocks into one.",
        )
    }

    // ---- Law: prose never states a stale count for a roster the code owns ----

    /**
     * The rosters this law polices, and where the true count comes from.
     * A roster earns an entry here when it is small, named in prose, and
     * grown by editing one list — the conditions under which a written count
     * silently goes wrong.
     */
    private val rosterCounts: Map<String, Int>
        get() = mapOf(
            "scheme" to SchemeId.entries.size,
            "starter" to StarterKits.ALL.size,
        )

    /** Number words this law can read; anything larger is written as digits in this codebase. */
    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )

    /**
     * A word between the number and the noun that means the number is not a
     * count of the roster at all — "4.5:1 in every scheme" is a contrast
     * ratio, "the other seven entries" counts something else.
     */
    private val notACount = setOf("every", "each", "all", "any", "other", "per")

    /**
     * Prose that names how many schemes or starters there are must be right.
     *
     * September UAT, finding 22: `SchemeId` had grown to eight while the
     * README still called them "the six TapeOS scheme token tables". Chasing
     * that number down found worse — `docs/UI_DESIGN.md` named six schemes of
     * which only two still exist, and `app/README.md` was a starter count out
     * — because nothing anywhere connected the written number to the list.
     * This connects them.
     *
     * Scope is the two READMEs plus `:shell` and `:app` sources: the places a
     * developer or a new contributor reads as current fact. `docs/` is
     * deliberately out, and that was tested rather than assumed — running this
     * scan across every Markdown file directly under `docs/` finds three real
     * drifts (fixed by hand in the same change) and two things it must not
     * touch:
     *
     * - `docs/FEATURE_PLAN.md` says "the real MPC 3 scheme". That is a product
     *   name, not a count, and no reasonable widening of the shape rules below
     *   tells it apart from one. A law that cries wolf gets weakened or
     *   deleted, which is worse than a narrow law that is always right.
     * - `docs/APP_PLAN.md`'s finished-milestone entries record what M0 did
     *   when there were six modules and six schemes. That is history; editing
     *   it to satisfy a scan would falsify the record.
     *
     * So prose in `docs/` stays a human's job. Copilot caught one there that
     * this law cannot see (`docs/UI_DESIGN.md`'s "flipped between all six"),
     * which is the honest cost of the narrower scope.
     */
    @Test
    fun `prose never states a stale count for a roster the code owns`() {
        val readmes = listOf(File("../README.md"), File("../app/README.md"))
        val missing = readmes.filterNot { it.isFile }
        require(missing.isEmpty()) {
            "expected these READMEs to exist but they don't: " + missing.joinToString { it.absolutePath } +
                ". Fix the paths rather than deleting the check — a scan that reads nothing passes by accident."
        }
        val sources = listOf(File("../shell/src/main/kotlin"), File("../app/src/main/kotlin"))
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
        require(sources.size > 50) { "found only ${sources.size} .kt files — the scan is broken, not the tree." }

        val counts = rosterCounts
        val nouns = counts.keys.joinToString("|")
        val numbers = (numberWords.keys + """\d+""").joinToString("|")
        // The number must be a number (not a word that merely precedes one),
        // may sit up to two words from the noun, and must not be part of a
        // decimal or a ratio - hence the lookbehind.
        val pattern = Regex("""(?<![\d:.])\b($numbers)\s+((?:[A-Za-z]+\s+){0,2}?)($nouns)s?\b""", RegexOption.IGNORE_CASE)

        val wrong = mutableListOf<String>()
        for (file in readmes + sources) {
            file.readText(Charsets.UTF_8).lineSequence().forEachIndexed { i, line ->
                for (m in pattern.findAll(line)) {
                    val token = m.groupValues[1].lowercase()
                    val stated = numberWords[token] ?: token.toIntOrNull() ?: continue
                    if (m.groupValues[2].lowercase().split(" ").any { it in notACount }) continue
                    val noun = m.groupValues[3].lowercase()
                    val actual = counts.getValue(noun)
                    if (stated != actual) {
                        wrong += "${file.path.replace('\\', '/')}:${i + 1} says \"${m.value.trim()}\" " +
                            "but there are $actual"
                    }
                }
            }
        }

        assertTrue(
            wrong.isEmpty(),
            "prose states a count that the code disagrees with:\n  " + wrong.joinToString("\n  ") +
                "\nEither the sentence is stale (fix the number) or the roster genuinely changed and the " +
                "surrounding prose needs rewriting too — finding 22 was a count that was wrong AND a list " +
                "of names that no longer existed. Check the names, not just the number.",
        )
    }

    // ---- Law: nobody takes a voice allocation and drops the casualties ----

    /**
     * `VoiceAllocator.noteOn` returns three things: the voice you asked
     * for, the voices it **choked** (same mute group — this is what makes a
     * closed hat cut an open one) and the voices it **stole** (the ring ran
     * out of room). The allocator has already forgotten all three. If the
     * caller does not stop the choked and stolen ones, their audio keeps
     * playing with nothing tracking it: a hat that never closes, a voice
     * count that drifts down, a pad that will not retrigger.
     *
     * Three screens do this correctly today, in three copies of one line.
     * A fourth that forgets is a stuck note — and `:app` has no test source
     * set and is excluded from CI's `test` task, so nothing else in this
     * build would catch it.
     *
     * **What this law can and cannot prove.** It reads the code after
     * comments and string literals are stripped, finds every `.noteOn(`
     * call in a file that names `VoiceAllocator`, and requires the lines
     * that follow it to stop both lists. It cannot prove the stop is
     * reached at runtime, that it stops the right voices, or that the
     * engine honours it — that needs the `NativePads` seam described in
     * `docs/SPECS_2026_09.md`. It is a fence, not a test.
     *
     * **Two holes this law shipped with, both found in review.** The first
     * version tested `"choked" in src` against the whole file including
     * comments — and `KitScreen` names both words in a comment above the
     * call, so deleting its stop loop would have passed. The mutation check
     * that was supposed to catch that happened to pick `PlayScreen`, the
     * one site with no such comment. The second version matched only
     * `val x = y.noteOn(`, so a `var`, a safe call or a line break before
     * `noteOn` would have slipped past while the three known sites kept the
     * count satisfied. Both are why this now strips comments and searches
     * from each call rather than across the file.
     */
    @Test
    fun `every voice allocation stops the voices it displaced`() {
        val offenders = mutableListOf<String>()
        var checked = 0
        for (file in File("../app/src/main/kotlin").walkTopDown()) {
            if (!file.isFile || file.extension != "kt") continue
            val code = stripCommentsAndStrings(file.readText(Charsets.UTF_8))
            // The type, in code, is what puts a file under this contract.
            // InstrumentPlayer binds `NativePads.noteOn(` and steals its own
            // voices: the call shape matches, the contract does not, and
            // keying on shape alone reported it on this law's first run.
            if ("VoiceAllocator" !in code) continue
            // Any call form - val, var, safe call, a line break before the
            // dot - because all of them leave `.noteOn(` in the source.
            for (call in Regex("""\.noteOn\s*\(""").findAll(code)) {
                checked++
                // The cleanup sits within a few lines of the call in every
                // correct site. A generous window: this is a fence against
                // deletion, not a style rule about where to put the loop.
                val after = code.substring(call.range.last, minOf(code.length, call.range.last + 800))
                val missing = listOf(".choked", ".stolen", "stop").filter { it !in after }
                if (missing.isNotEmpty()) {
                    offenders += "${file.path.replace('\\', '/')} allocates at offset ${call.range.first} " +
                        "and never ${missing.joinToString(" or ")} in the 800 characters after it"
                }
            }
        }
        assertTrue(
            checked >= 3,
            "expected at least the three screens that allocate voices, found $checked — the pattern this " +
                "law matches must have changed, which would make it pass by checking nothing. Fix the " +
                "pattern, do not lower this bound.",
        )
        assertTrue(
            offenders.isEmpty(),
            "a caller takes a voice allocation and drops what it displaced:\n  " +
                offenders.joinToString("\n  ") +
                "\nEvery VoiceAllocator.noteOn call must stop allocation.choked + allocation.stolen. The " +
                "allocator has already dropped them; if you do not stop them the audio plays on untracked " +
                "— a hat that never closes, or a voice count that drifts down.",
        )
    }

    /**
     * Source with block comments, line comments and string literals blanked
     * out, so a law reads what the code *does* rather than what it says
     * about itself. Lengths are not preserved; offsets are only used to
     * search forward from a match within the same stripped text.
     */
    private fun stripCommentsAndStrings(src: String): String =
        src.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
            .replace(Regex(""""(?:[^"\\\n]|\\.)*""""), "\"\"")

    // ---- Law: the app's one explanation of itself names real screens ----

    /**
     * September UAT, finding 3. The empty shelf states the product loop as
     * the four tabs it runs through, which is the whole reason the line
     * works: learn it and you have learned the navigation.
     *
     * That only holds while the words ARE the tabs. Rename a tab and the
     * app's single explanation of itself would point at a screen that no
     * longer exists — and nothing else in the build would notice, because
     * `Copy` and `MenuRow` live in different modules and neither reads the
     * other.
     *
     * So this reads the tab labels out of `Chrome.kt` by source, the same
     * way the roster law above reads prose, and holds every stage to it.
     * It matches `MenuItem("...")` rather than any one declaration, which
     * is why J12 could regroup the row into `MENU_GROUPS` underneath it
     * without this law noticing or needing to be told.
     */
    @Test
    fun `the first-run loop names real menu tabs`() {
        val chrome = File("../app/src/main/kotlin/com/snipsnap/app/ui/Chrome.kt")
        assertTrue(chrome.isFile, "expected to find ${chrome.absolutePath} to read the menu tabs from")
        val labels = Regex("""MenuItem\("([^"]+)"""")
            .findAll(chrome.readText(Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(
            labels.size >= 5,
            "only found $labels in Chrome.kt — the MenuItem pattern this law reads must have changed, " +
                "which would make it pass by checking nothing. Fix the pattern, do not lower this bound.",
        )
        for (stage in Copy.FIRST_RUN_LOOP_STAGES) {
            assertTrue(
                stage in labels,
                "Copy.FIRST_RUN_LOOP_STAGES names '$stage', which is not a menu tab. The empty shelf is " +
                    "the app's only statement of its own loop and it works by naming the tabs — a stage " +
                    "that is not one sends a new user looking for a screen that does not exist. " +
                    "Tabs are: ${labels.sorted()}",
            )
        }

        // ...and the sentence under those four words is held to the same
        // rule, which is where J13 got in: the law above passed while
        // FIRST_RUN_LOOP_NOTE glossed step three, KIT, as "PLAY IT" — and
        // PLAY is a real tab six places along the same menu row. A tab name
        // in the note is a promise about where to tap, so the only tab
        // names allowed in it are the four stages it is explaining.
        val strays = Regex("""[A-Z]+""").findAll(Copy.FIRST_RUN_LOOP_NOTE)
            .map { it.value }
            .filter { it in labels && it !in Copy.FIRST_RUN_LOOP_STAGES }
            .toSet()
        assertTrue(
            strays.isEmpty(),
            "Copy.FIRST_RUN_LOOP_NOTE (\"${Copy.FIRST_RUN_LOOP_NOTE}\") names ${strays.sorted()}, " +
                "which ${if (strays.size == 1) "is a menu tab" else "are menu tabs"} but not one of the four " +
                "stages it explains (${Copy.FIRST_RUN_LOOP_STAGES}). The note sits directly under the stage " +
                "row and reads as a gloss of it, so a tab name in it points a new user at a screen that is " +
                "not the step being described. Use a verb that is not a tab, or the stage's own name.",
        )
    }

    // ==================== Law: the ladder row's chips are named once ====================

    /**
     * THE ZOOM LADDER's first chip is COUNT — no rung at all, the plain
     * GRID by count, and the state the row is in by default. It lived as a
     * literal on `ChopScreen` while HELP's ZOOM line listed the four real
     * rungs, so the one state a new user is actually in was the one state
     * HELP never mentioned (J42).
     *
     * `Ladder.ROW_LABELS` is now the row, and HELP builds its line from it.
     * That only holds while the screen draws the row out of the same list,
     * so this reads the screen's own source and refuses the literal back.
     */
    @Test
    fun `the ladder row's COUNT chip is named in Ladder, not typed on the screen`() {
        val chop = File("../app/src/main/kotlin/com/snipsnap/app/ui/ChopScreen.kt")
        assertTrue(chop.isFile, "expected to find ${chop.absolutePath}")
        val src = codeOnly(chop.readText(Charsets.UTF_8))
        assertTrue(
            "SegmentButton(Ladder.COUNT_LABEL" in src,
            "ChopScreen no longer draws the ladder row's first chip from Ladder.COUNT_LABEL. HELP's ZOOM " +
                "line is built from Ladder.ROW_LABELS; if the screen types its own label the two can say " +
                "different things again, which is exactly J42.",
        )
        assertFalse(
            "\"COUNT\"" in src,
            "ChopScreen.kt contains the literal \"COUNT\". The ladder row's first chip is Ladder.COUNT_LABEL " +
                "so that HELP and the screen read one string out of one place — see J42.",
        )
        // HELP names every chip of the row, not four of the five.
        for (label in Ladder.ROW_LABELS) {
            assertTrue(
                Copy.HELP_MORE.any { label in it && "ZOOM ON CHOP" in it },
                "HELP's ZOOM line does not name the ladder chip '$label'. It is built from " +
                    "Ladder.ROW_LABELS precisely so it cannot miss one; something has retyped it.",
            )
        }
    }

    // ==================== Law: a toast's door belongs to that toast ====================

    /**
     * An offer is a toast with a door (J10). The first cut of it held the
     * message in `toast` and the door in `toastDoor` and wrote them
     * independently, so a plain `onToast` landing afterwards — TAPE's KEEP
     * fired one on the very next line — swapped the sentence and left the
     * door under someone else's words, on the offer's longer dwell. The
     * comment beside the two vars claimed a door "can never outlive its
     * message"; nothing made that true.
     *
     * It is true now because `toastDoor` is derived: `offered` carries the
     * sentence it was made with, and the door shows only while the toast on
     * screen is that sentence. This law keeps it derived — the moment
     * anything assigns `toastDoor` again, the two can disagree again.
     */
    @Test
    fun `the toast door is derived from the toast, never assigned beside it`() {
        val app = File("../app/src/main/kotlin/com/snipsnap/app/App.kt")
        assertTrue(app.isFile, "expected to find ${app.absolutePath}")
        val src = codeOnly(app.readText(Charsets.UTF_8))
        assertTrue(
            "val toastDoor" in src,
            "App.kt no longer declares `val toastDoor`. The door has to be computed from the toast on " +
                "screen, not stored beside it — see J10/J44.",
        )
        val assigned = Regex("""\btoastDoor\s*=(?!=)""").findAll(src).count()
        assertEquals(
            0,
            assigned,
            "App.kt assigns `toastDoor` $assigned time(s). A door held in its own var can outlive the " +
                "sentence it was offered with: any plain `toast = ...` replaces the words and leaves the " +
                "button. Set `offered` instead and let `toastDoor` be derived from it.",
        )
    }

    // ==================== Law: every snip call agrees on where snips live ====================

    /**
     * `SnipStore`'s root argument is always the app's files directory, never
     * the kit shelf's root.
     *
     * They are different folders — `MainActivity` builds the shelf as
     * `File(filesDir, "Kits")` — and a snip is written to `<root>/snips`, so
     * the two roots name two directories, one of which nothing writes.
     *
     * This is not hypothetical. SNIPS, DELETED SNIPS and the 30-day bin sweep
     * all passed `shelf.root` while all seven writers passed `filesDir`, so
     * the list screen scanned `<files>/Kits/snips` and said NO SNIPS YET
     * however many the phone actually held. It shipped that way, and it took
     * a review bot on an unrelated change to notice, because nothing
     * connected the writer's root to the reader's.
     *
     * The recurring defect shape in this repo is one quantity computed in two
     * places (`.claude/skills/steward`); this is that shape wearing a
     * directory. The law connects them: whatever a `SnipStore` call names as
     * its root has to be the files directory, by whatever local name it goes
     * under.
     */
    @Test
    fun `law - every SnipStore call is rooted at the files directory, never the shelf`() {
        val sources = File("../app/src/main/kotlin")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        require(sources.size > 20) { "found only ${sources.size} :app sources — the scan is broken, not the tree." }

        // The calls that take a root: everything else on SnipStore (DIR,
        // Info, displayName, shareName, rename, delete, provenanceTag) is
        // handed a file, a name, or nothing at all, and cannot name the
        // wrong folder.
        val rooted = Regex("""SnipStore\.(commit|commitPrepared|import|list|listWithInfo|newest|binned|restore|emptyBin|sweepBin)\s*\(([^)]*)""")
        // What a root is allowed to be: the files directory under any of the
        // names this app gives it.
        val allowed = Regex("""\b(filesDir|snipsRoot)\b""")

        val wrong = mutableListOf<String>()
        var checked = 0
        for (file in sources) {
            file.readText(Charsets.UTF_8).lineSequence().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("*") || t.startsWith("//")) return@forEachIndexed
                for (m in rooted.findAll(line)) {
                    val args = m.groupValues[2]
                    // `commit`/`import` take the buffer first, the root second;
                    // the rest take the root first. Either way the root is in
                    // the argument text, so this asks the simpler question:
                    // does a files-directory name appear at all?
                    checked++
                    if (!allowed.containsMatchIn(args)) {
                        wrong += "${file.name}:${i + 1}  ${t.take(110)}"
                    }
                }
            }
        }

        assertTrue(
            checked >= 12,
            "this law found only $checked SnipStore call sites to check. It is meant to cover every " +
                "reader and writer of `snips/`; if the pattern stopped matching them it would pass by " +
                "checking almost nothing.",
        )
        assertTrue(
            wrong.isEmpty(),
            "these SnipStore calls do not name the app's files directory as their root. A snip lives at " +
                "`<filesDir>/snips`, and the kit shelf is `<filesDir>/Kits` — a call rooted anywhere else " +
                "reads or writes a directory the rest of the app has never heard of:\n  " +
                wrong.joinToString("\n  "),
        )
    }

    // ==================== Law: a path is named in one place ====================

    /**
     * Every folder and filename the app builds a path from, and the one file
     * allowed to say it out loud.
     *
     * A path typed in two places is two paths that happen to agree today.
     * SNIPS proved what that costs: `<files>/snips` from seven writers,
     * `<files>/Kits/snips` from the list screen, and five days of a screen
     * that could not show anything. Nothing connected them, so nothing
     * noticed.
     *
     * This is the same guard the bin's "30 DAYS" already has in
     * `ReversalTest` — say the number once, and fail anyone who retypes it —
     * applied to the other quantity this app keeps in two places.
     */
    private val pathOwners = mapOf(
        "kit.json" to "KitStore.kt",
        "loop.json" to "SessionStore.kt",
        "sessions/current" to "LoopWrites.kt",
        "exports" to "Exports.kt",
        "snips" to "SnipStore.kt",
        "Kits" to "MainActivity.kt",
        // Found in the same review that named the six above: the cache's
        // two scratch folders were typed independently by their own writer
        // and by StorageSweep's sweep of them — the identical shape SNIPS
        // paid five days for, just not yet triggered by a rename.
        "share" to "ShareOut.kt",
        "landing" to "ShareInbox.kt",
    )

    @Test
    fun `law - no source retypes a path another file already owns`() {
        val roots = listOf("../app/src/main/kotlin", "../shell/src/main/kotlin", "../kit/src/main/kotlin", "../loop/src/main/kotlin")
        val sources = roots.flatMap { File(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
        require(sources.size > 60) { "found only ${sources.size} sources — the scan is broken, not the tree." }

        // Anti-staleness, first: an owner that no longer says its own path is
        // an entry guarding nothing, which is how an exclusion list rots.
        for ((path, owner) in pathOwners) {
            val file = sources.singleOrNull { it.name == owner }
            assertTrue(file != null, "pathOwners names $owner as the home of \"$path\", and no such source exists.")
            val declares = file!!.readText(Charsets.UTF_8).lineSequence()
                .any { line -> !isCommentLine(line.trim()) && "\"$path\"" in line }
            assertTrue(
                declares,
                "$owner is listed as the one place that says \"$path\", but no CODE line there does — a KDoc " +
                    "or comment naming the path does not count. Either the constant moved (point this entry at " +
                    "its new home) or it is gone (drop the entry) — do not leave it guarding a path nobody " +
                    "declares.",
            )
        }

        val strays = mutableListOf<String>()
        for (file in sources) {
            file.readText(Charsets.UTF_8).lineSequence().forEachIndexed { i, line ->
                val t = line.trim()
                // Prose may name a path freely: a KDoc explaining where
                // exports land is documentation, not a second definition.
                if (isCommentLine(t)) return@forEachIndexed
                for ((path, owner) in pathOwners) {
                    if (file.name == owner) continue
                    if ("\"$path\"" in line) {
                        strays += "${file.name}:${i + 1} says \"$path\" — $owner owns it\n      ${t.take(100)}"
                    }
                }
            }
        }

        assertTrue(
            strays.isEmpty(),
            "these lines retype a path another file already names. Use that file's constant instead: a " +
                "second copy is a second path the day someone renames one of them, which is exactly how " +
                "SNIPS spent five days reading a directory nothing wrote:\n  " + strays.joinToString("\n  "),
        )
    }
    // ---- Law: what the player chose outlives the chop it was chosen on ----

    /**
     * Every `remember(model)` in `ChopContent`, and the reason each one is
     * right to be thrown away when `model` is reassigned.
     *
     * `model` is reassigned by five sites (`rechopTo`, `editSlices`, the hum's
     * landing, AUTO, and RE-CHOP), two of which are helpers most of the bench
     * calls through — so "the model changed" is not a rare event, it is what
     * using the screen *is*. Keying state on it therefore splits cleanly in
     * two: state that **describes** the chop must go when the chop does, and
     * state that **records what the player chose** must not. Getting a piece
     * on the wrong side of that line is invisible until someone nudges HITS
     * and watches their view snap back.
     *
     * Every entry below is the first kind, with its reason. The law's job is
     * to make the *next* one a decision rather than a default: a new
     * `remember(model)` fails this test until whoever adds it says which kind
     * it is. `cutOpen`, `rechopBusy`, `sendBusy` and `layout` are the second
     * kind and are deliberately unkeyed — `cutOpen` always was, which is what
     * made `layout`'s keying visible as an inconsistency rather than a policy.
     */
    private val modelKeyedChopState = mapOf(
        "revision" to "a recompose counter for row overrides mutated in place; a new model has new rows, so the count starts again",
        "pickerFor" to "the open picker names a slice by its 1-based `n`, and a re-chop renumbers what that `n` means",
        "melodicPlaced" to "MELODIC's placement is derived from this model's own rows",
        "pitchLabels" to "the A2/C#4 labels are pitch detection over this model's own rows",
        "melodicBusy" to "guards the placement pass above, so it shares that pass's lifetime",
        "humming" to "a hum is sung against one model's source",
        "humStart" to "the instant that hum began",
        "voice" to "plays this model's audio; the DisposableEffect on the same key is what releases it",
        "classicError" to "the failure of a derivation over this model, cleared by the next successful one",
    )

    /**
     * [block] with its comment lines dropped.
     *
     * Every law that asks "does this text appear, and where" needs this:
     * an explanation of a rule quotes the code the rule is about, so a
     * comment saying "said after `v.start(0)`" is indistinguishable from
     * the call itself to a plain `indexOf`. That has now cost two laws —
     * one truncated a list at a `)` inside a comment, one read a quoted
     * call as the real one.
     */
    private fun codeOnly(block: String): String =
        block.lines().filterNot { isCommentLine(it.trim()) }.joinToString("\n")

    /** The `( ... )` list following [marker] in [src], paren-matched and comment-stripped. */
    private fun blockAfterList(src: String, marker: String): String {
        val code = codeOnly(src)
        val at = code.indexOf(marker)
        assertTrue(at >= 0, "expected to find `$marker`")
        val start = code.indexOf('(', at)
        assertTrue(start >= 0, "expected a `(` after `$marker`")
        var depth = 0
        var i = start
        while (i < code.length) {
            val c = code[i]
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) return code.substring(start, i + 1)
            }
            i++
        }
        fail("unbalanced parentheses after `$marker`")
    }

    /** The `{ ... }` block following [marker] in [src], brace-matched. */
    private fun blockAfter(src: String, marker: String): String {
        val at = src.indexOf(marker)
        assertTrue(at >= 0, "expected to find `$marker` in ChopContent")
        val start = src.indexOf('{', at)
        assertTrue(start >= 0, "expected a `{` block after `$marker`")
        var depth = 0
        var i = start
        while (i < src.length) {
            val c = src[i]
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return src.substring(start, i + 1)
            }
            i++
        }
        fail("unbalanced braces after `$marker`")
    }

    @Test
    fun `law - every CHOP state keyed on the model is one that describes the model`() {
        val body = topLevelFun(chopScreen, "ChopContent")
        val found = Regex("""var\s+(\w+)\s+by\s+remember\(model\)""")
            .findAll(body).map { it.groupValues[1] }.toList()
        require(found.size > 4) { "found only ${found.size} `remember(model)` in ChopContent — the scan is broken, not the screen." }

        val undeclared = found.filterNot { it in modelKeyedChopState }
        assertTrue(
            undeclared.isEmpty(),
            "these are keyed on `model`, so every re-chop, merge, split, hum, AUTO and HITS nudge resets " +
                "them — and nothing here says that is intended: ${undeclared.joinToString(", ")}.\n" +
                "Decide which kind each one is. State that DESCRIBES the chop (a derivation over its rows, a " +
                "voice playing its audio) belongs keyed on `model`, and goes in `modelKeyedChopState` with " +
                "its reason. State that RECORDS WHAT THE PLAYER CHOSE (a layout, an open bench, a mode) must " +
                "not be keyed on it — `model` is reassigned by ordinary use of the bench, and none of those " +
                "reassignments is the player asking to be put back to a default. Key those on `initialModel` " +
                "instead, the way `layout` is, so a genuinely new source still starts fresh.",
        )

        val stale = modelKeyedChopState.keys.filterNot { it in found }
        assertTrue(
            stale.isEmpty(),
            "`modelKeyedChopState` still lists ${stale.joinToString(", ")}, which no longer appear as " +
                "`remember(model)` in ChopContent. An allowlist that outlives what it allows guards nothing " +
                "— drop the entry, or point it at the name the state now has.",
        )
    }

    /**
     * Prior finding #12, and J20 in `docs/UX_JOURNEY_PLAN_2026_09.md`.
     *
     * CLASSIC/FOLD/MELODIC is the player saying how they want to *see* the
     * chop. It is not a property of the chop, so re-chopping must not undo
     * it: picking MELODIC and then nudging HITS once put the screen back on
     * CLASSIC with no toast and nothing to undo.
     */
    @Test
    fun `law - the CHOP layout the player picked survives a re-chop`() {
        val body = topLevelFun(chopScreen, "ChopContent")
        assertTrue(
            !Regex("""var\s+layout\s+by\s+remember\(model\)""").containsMatchIn(body),
            "`layout` is keyed on `model` again. Every re-chop, merge, split, hum and HITS nudge reassigns " +
                "`model`, so this silently returns the player to CLASSIC mid-edit — the same class of bug as " +
                "the four PR 4 controls, except it destroys a view choice rather than a file.",
        )
        assertTrue(
            Regex("""var\s+layout\s+by\s+remember\(initialModel\)""").containsMatchIn(body),
            "`layout` should be remembered against `initialModel`: a genuinely new source (a different tape " +
                "or kit) is a different job and should start on CLASSIC, but a re-chop of the same source is " +
                "not. Unkeyed `remember { }` would carry a layout across sources; `remember(model)` throws it " +
                "away on every bench nudge. `initialModel` is the one key that means what this state means.",
        )
    }

    /**
     * The coupling that makes the law above safe.
     *
     * MELODIC's placement is real pitch DSP over every row, so it is computed
     * off the main thread and cached in `melodicPlaced` — which is keyed on
     * `model`, correctly, because it describes the model's rows. While
     * `layout` was *also* keyed on `model` the two always died together and a
     * computation that ran only inside the MELODIC button's own click handler
     * was sufficient: the only way back to MELODIC was to tap it again.
     *
     * Once `layout` survives a re-chop that stops being true. A re-chop while
     * MELODIC is showing clears the placement and the click that would have
     * rebuilt it never comes, so the grid preview reads `melodicPlaced ?:
     * emptyList()` forever — a permanently empty MELODIC. The placement has
     * to be an effect of the state it derives from, not of the tap that first
     * asked for it.
     */
    @Test
    fun `law - MELODIC's placement is recomputed when the chop changes, not only when the button is tapped`() {
        val body = topLevelFun(chopScreen, "ChopContent")
        assertTrue(
            Regex("""LaunchedEffect\(\s*(model\s*,\s*melodic|melodic\s*,\s*model)\s*\)""").containsMatchIn(body),
            "MELODIC's placement must be rebuilt by a LaunchedEffect keyed on both `model` and `melodic`. " +
                "Keyed on only one of them it is wrong in one direction or the other: on `melodic` alone a " +
                "re-chop leaves a stale placement, and on `model` alone the DSP runs for players who never " +
                "opened MELODIC at all.",
        )
        val melodicButton = blockAfter(body, """SegmentButton("MELODIC"""")
        assertTrue(
            "melodicPlaced =" !in melodicButton && "melodicBusy = true" !in melodicButton,
            "the MELODIC button is computing the placement itself again. A click handler cannot be the only " +
                "way the placement is built, because `layout` now outlives the model the placement was built " +
                "from — the re-chop that clears it is not accompanied by a tap. The button should set " +
                "`layout` and say so; the effect above owns the rebuilding.",
        )
    }
    // ---- Law: PAD SHEET keeps what the player dialled ----

    private val padSheetScreen = File("../app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt")

    /**
     * J24 in `docs/UX_JOURNEY_PLAN_2026_09.md`.
     *
     * MUTATE and OUTSIDE each offer a row of moves, and each move has its
     * own knob meaning its own thing — SPLICE's `AT` is a time in
     * milliseconds, MORPH's is a blend. Keying the dialled value on the
     * *selected move* meant the value was destroyed by the act of looking
     * at another move: dial SPLICE's `AT` to 400 ms, tap MORPH to hear the
     * difference, tap back, and it reads 40 ms again.
     *
     * Comparing two moves is the entire reason the row exists, so the one
     * gesture the design invites was the one that threw work away. A value
     * per move fixes it without inventing anything: each move keeps what it
     * was last dialled to, and a move never touched still opens on its own
     * default.
     */
    @Test
    fun `law - a move's dialled knob is remembered per move, not reset by switching move`() {
        val src = padSheetScreen.readText(Charsets.UTF_8)
        for (bad in listOf("remember(slot, mutateMode)", "remember(slot, outsideMove)")) {
            assertTrue(
                bad !in src,
                "`$bad` keys a dialled knob on the selected move, so switching move to compare — the one " +
                    "gesture the move row exists to invite — silently resets it to that move's default. Keep " +
                    "a value per move (a state map keyed on the move's name) instead, so each move remembers " +
                    "what it was dialled to and an untouched move still opens on its default.",
            )
        }
        for (map in listOf("mutateKnobs", "outsideKnobs")) {
            assertTrue(
                Regex("""val\s+$map\s*=\s*remember\(slot\)\s*\{\s*mutableStateMapOf""").containsMatchIn(src),
                "expected `$map` to be a `remember(slot) { mutableStateMapOf<String, Float>() }` — one dialled " +
                    "value per move, reset only when the pad itself changes.",
            )
        }
    }

    /**
     * J23 and J26.
     *
     * `slot` changes every time the pad-nav arrows move to the next pad, so
     * keying state on it says "this belongs to one pad". Three pieces said
     * that and were not true:
     *
     * - `measuredRoom` holds a **multi-second live mic capture of the
     *   physical room**, and `KEEP ROOM ▸` shelves it kit-level, named after
     *   the kit, as a reusable parent. The pad that happened to be open when
     *   the trip ran is incidental to all of that — but one tap of `►`
     *   discarded it, and recovery meant doing the trip again.
     * - `pendingDepth` / `pendingBloom` are settings for `MAKE PAD ▸`, a
     *   door that makes a *new* pad. A tool's settings are not a property of
     *   whatever it was last pointed at, and dialling DEPTH again for every
     *   pad in a row is the workflow the reset imposes.
     *
     * All three are keyed on the kit instead, which is the scope they
     * actually belong to: a different kit is a different room to name and a
     * different job, the same reasoning `openBox` already uses one file up.
     */
    @Test
    fun `law - PAD SHEET state that is not a property of one pad is not keyed on the slot`() {
        val src = padSheetScreen.readText(Charsets.UTF_8)
        for (name in listOf("measuredRoom", "pendingDepth", "pendingBloom")) {
            assertTrue(
                !Regex("""var\s+$name\s+by\s+remember\(slot\)""").containsMatchIn(src),
                "`$name` is keyed on `slot` again, so moving to the next pad destroys it. It is not a " +
                    "property of one pad: a measured room is the room (and KEEP ROOM shelves it named after " +
                    "the *kit*), and MAKE PAD's DEPTH/BLOOM are a tool's settings. Key it on `entry.dir`.",
            )
            assertTrue(
                Regex("""var\s+$name\s+by\s+remember\(entry\.dir\)""").containsMatchIn(src),
                "expected `$name` to be keyed on `entry.dir` — the kit, which is the scope it belongs to. " +
                    "Unkeyed would carry a measured room across a kit switch and let KEEP ROOM name it after " +
                    "the wrong kit; keyed on `slot` it dies on the pad-nav arrows.",
            )
        }
    }
    /**
     * The half of J24 that nearly shipped a regression.
     *
     * `MutateSheet.DRIFT_FRACTION` is MORPH's own knob default, and its
     * KDoc says why it exists: the card "had been reading whatever fraction
     * the *previous* move's stepper happened to hold", so a DRIFT tap "blended
     * none of the neighbour in while the card then redrew MIX at 50%". The
     * value written and the value shown had diverged.
     *
     * Giving each move its own memory puts that divergence back within reach
     * from the other side: DRIFT from another move uses `DRIFT_FRACTION`,
     * then switches the card to MORPH — which now has a remembered value to
     * land on, so the knob would read whatever MORPH was last dialled to
     * while the drift that just ran used something else. Writing the
     * fraction DRIFT used into MORPH's own memory keeps the knob a true
     * readout of the last thing that happened.
     */
    @Test
    fun `law - DRIFT leaves the knob showing the blend it actually used`() {
        val src = padSheetScreen.readText(Charsets.UTF_8)
        val drift = blockAfter(src, "if (!onMorph) {")
        assertTrue(
            Regex("""mutateKnobs\[Mutate\.Mode\.MORPH\.name\]\s*=\s*MutateSheet\.DRIFT_FRACTION""")
                .containsMatchIn(drift),
            "DRIFT switches the card to MORPH without telling MORPH's knob what blend it used, so the card " +
                "shows whatever MORPH was last dialled to while the drift that just ran used " +
                "`DRIFT_FRACTION`. That is the same value-shown/value-used divergence `DRIFT_FRACTION`'s own " +
                "KDoc was written to end. Write it into the move's memory alongside the switch.",
        )
    }
    /**
     * J22. `TapeDeckViewTest` proves the carrier carries; this proves TAPE
     * still uses it.
     *
     * That gap is the whole reason these source-scanning laws exist. A
     * perfectly correct, fully tested `ViewCarrier` in `:shell` says nothing
     * about whether `:app` calls it — and `:app` has no Kotlin test source
     * set to notice if the call is dropped in a later edit. Only the
     * compiler sees this file, and a deleted `.also(viewCarrier::adopt)`
     * compiles cleanly.
     */
    @Test
    fun `law - TAPE hands each fresh deck the view the player had set up`() {
        val src = tapeScreen.readText(Charsets.UTF_8)
        assertTrue(
            Regex("""val\s+viewCarrier\s*=\s*remember\s*\{\s*TapeDeckModel\.ViewCarrier\(\)\s*\}""")
                .containsMatchIn(src),
            "TAPE needs an unkeyed `remember { TapeDeckModel.ViewCarrier() }`. Keyed on anything that moves " +
                "with the tape it would die exactly when it is needed — outliving `tapeData` is its only job.",
        )
        val built = Regex("""remember\(tapeData\)\s*\{\s*\n\s*TapeDeckModel\([^)]*\)\s*\n\s*\.also\(viewCarrier::adopt\)""")
        assertTrue(
            built.containsMatchIn(src),
            "the fresh `TapeDeckModel` is built without `.also(viewCarrier::adopt)`, so a snip landing — " +
                "reachable from the quick-settings tile without leaving this screen — silently returns the " +
                "zoom and the readout to their defaults under the player's finger. Nothing else catches " +
                "this: `:app` has no test source set, and dropping the call still compiles.",
        )
    }
    // ---- Law: KIT / TEXTURE keeps what the player picked ----

    private val kitScreen = File("../app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt")

    /**
     * J25's worst half: the target of a destructive action moving on its own.
     *
     * SOURCE names the pad SCULPT and STRETCH will turn into a tape, and
     * `SCULPT ▸ NEW TAPE` has no per-pad confirm — so whatever SOURCE points
     * at when GO is pressed is what gets rendered over. It was keyed on
     * **the value of the kit's lowest assigned slot**, so any chop landing or
     * capture that filled a lower pad silently re-pointed the stepper at the
     * new arrival. The player picks A07, a capture lands on A01, and the next
     * GO renders A01.
     *
     * The key was doing two jobs, which is why this is not simply a deletion:
     * re-pointing on a kit change (the bug) *and* picking up a first value
     * once the kit has pads at all (legitimate — `sources` is empty on the
     * first composition). A remembered pick with a fallback does the second
     * without the first: the player's choice stands while the pad still
     * exists, and the lowest slot is used only when there is no choice to
     * honour — no pick yet, or a pick whose pad has since gone.
     */
    @Test
    fun `law - the TEXTURE source pad is the one the player picked, not the kit's lowest`() {
        val src = kitScreen.readText(Charsets.UTF_8)
        assertTrue(
            "remember(entry.dir, sources.firstOrNull())" !in src,
            "SOURCE is keyed on the value of the kit's lowest assigned slot again, so a chop landing or a " +
                "capture on a lower pad silently re-points it — and SCULPT's NEW TAPE has no per-pad confirm, " +
                "so the next GO renders over a pad nobody chose.",
        )
        assertTrue(
            Regex("""var\s+pickedSource\s+by\s+remember\(entry\.dir\)""").containsMatchIn(src),
            "expected the player's pick to be remembered against the kit alone.",
        )
        assertTrue(
            Regex("""val\s+sourceSlot\s*=\s*pickedSource\?\.takeIf\s*\{\s*it\s+in\s+sources\s*\}\s*\?:\s*sources\.firstOrNull\(\)""")
                .containsMatchIn(src),
            "expected SOURCE to fall back to the lowest slot only when there is no pick to honour — none yet " +
                "(`sources` is empty on the first composition), or one whose pad has since been removed. " +
                "Without the fallback a picked-then-deleted pad leaves SOURCE naming nothing; without the " +
                "`in sources` test it would name a pad that is gone.",
        )
    }

    /**
     * The other half of J25, and the same bug J24 had on PAD SHEET.
     *
     * Each texture panel has its own modes and each mode its own knob, so
     * neither can simply carry across — but keying them on the panel and the
     * mode meant looking at the other one threw the dialled value away.
     * Comparing is what the chips are for.
     */
    @Test
    fun `law - TEXTURE remembers a mode per panel and a knob per mode`() {
        val src = kitScreen.readText(Charsets.UTF_8)
        for (bad in listOf("remember(panelKind) { mutableStateOf(", "remember(panelKind, mode)")) {
            assertTrue(
                bad !in src,
                "`$bad` throws away what the player set by the act of looking at another panel or mode. " +
                    "Keep a value per panel (and per panel-and-mode for the knob), the way PAD SHEET's own " +
                    "move knobs do — an untouched one still opens on its default.",
            )
        }
        for (map in listOf("textureModes", "textureKnobs")) {
            assertTrue(
                Regex("""val\s+$map\s*=\s*remember\(entry\.dir\)\s*\{\s*mutableStateMapOf""").containsMatchIn(src),
                "expected `$map` to be a `remember(entry.dir) { mutableStateMapOf... }` — kept for as long as " +
                    "the kit is open, reset when a different kit is.",
            )
        }
    }
    /**
     * J37, the wiring half. `PadTouchTest` proves the mapping and proves it
     * reaches the zones; only this proves KIT still asks for it.
     *
     * The hard-coded `1f` was the whole bug — SOFT HITS built real velocity
     * layers, `PadHit.resolve` has always chosen one by velocity, and the
     * grid threw that away at the last step. Putting a constant back
     * compiles cleanly and silently un-ships the feature again.
     */
    @Test
    fun `law - KIT hits a pad at the velocity the touch asked for`() {
        val src = kitScreen.readText(Charsets.UTF_8)
        assertTrue(
            Regex("""onTap\(slot,\s*PadHit\.velocityAt\(down\.position\.y,\s*size\.height\.toFloat\(\)\)\)""")
                .containsMatchIn(src),
            "the pad grid is not reading velocity from where the finger landed. A tap on glass carries no " +
                "force, so position is the only thing it does carry - without it the grid has nothing to pass " +
                "and SOFT HITS goes back to being audible nowhere.",
        )
        assertTrue(
            !Regex("""player\.hit\(pad,\s*1f""").containsMatchIn(src) &&
                !Regex("""allocator\.noteOn\(slot,\s*1f""").containsMatchIn(src),
            "a hard-coded velocity is back in KIT's `hit`. That is the original J37 bug exactly: the layers " +
                "are still built, still chosen by velocity, and still never heard.",
        )
        /*
         * The accessible path is the one place a constant is right, and it
         * must be the constant the REST of the app uses. This asserted full
         * velocity at first, on the reasoning that a synthesized click
         * should give the pad's whole sound — but PLAY and KEYS had long
         * since settled on the centre, and full velocity was one of three
         * ways KIT's wiring disagreed with them. The shared rule now owns
         * it; see `law - every pad grid reads touch-Y velocity through the
         * one shared rule`.
         */
        assertTrue(
            Regex("""onClick\(label = "PLAY"\)\s*\{\s*onTap\(slot,\s*PadHit\.CENTER\)""").containsMatchIn(src),
            "the synthesized PLAY click should pass `PadHit.CENTER` — what a tap at the pad's vertical middle " +
                "would have produced, which is what every other grid gives it.",
        )
    }
    private val grooveScreen = File("../app/src/main/kotlin/com/snipsnap/app/ui/GrooveScreen.kt")

    /**
     * J17, both halves.
     *
     * The seam: `recordBars` was a `var` initialised to 2 that no control
     * ever reassigned — a mutable variable able to hold only its initial
     * value, while the model accepted anything in 1..64.
     *
     * The silence: selecting PROG C doubles the pattern length through
     * `GrooveVariations.halfTime`, and nothing on screen said so. The file
     * already *knew* — the comment locking the PROG carousel during RECORD
     * explains the desync risk "(HALF-TIME doubles `bars`)". The app knew
     * and the player did not.
     */
    @Test
    fun `law - GROOVE has a control for the take length, and says when a program changes it`() {
        val src = grooveScreen.readText(Charsets.UTF_8)

        // EVERY assignment, not merely one of them.
        //
        // This first read `containsMatchIn(...barsAfter...)`, which is a
        // existence check wearing an invariant's clothes: replacing one of
        // the two steppers with plain arithmetic left the other matching
        // and the law green. The property is that `recordBars` is never
        // assigned anything else, so that is what it asks.
        // `=(?!=)` so a `recordBars == 1` comparison is not read as an
        // assignment — it was, the first time this ran.
        val assignments = Regex("""recordBars\s*=(?!=)\s*([^\n]*)""").findAll(src)
            .map { it.groupValues[1].trim() }
            .filterNot { it.startsWith("LiveRecord.barsAfter(recordBars,") }
            .filterNot { it.startsWith("recordBars,") } // `bars = recordBars,` style named arguments
            .toList()
        assertTrue(
            assignments.isEmpty(),
            "`recordBars` is assigned something other than a `LiveRecord.barsAfter` step: $assignments. " +
                "Plain arithmetic is not the same thing: `Take` refuses a bar count outside 1..64 *at the " +
                "arm*, so a control able to walk off the ladder turns a tap into a throw the instant the " +
                "count-in starts.",
        )
        assertTrue(
            Regex("""LiveRecord\.barsAfter\(recordBars,\s*-1\)""").containsMatchIn(src) &&
                Regex("""LiveRecord\.barsAfter\(recordBars,\s*1\)""").containsMatchIn(src),
            "the take length needs a control that steps both ways; one direction alone cannot reach every " +
                "rung on a ladder that wraps.",
        )
        assertTrue(
            Regex("""mutableIntStateOf\(LiveRecord\.DEFAULT_BARS\)""").containsMatchIn(src),
            "RECORD's opening length should come from `LiveRecord.DEFAULT_BARS`, not a literal — a default " +
                "the stepper's own ladder does not contain is one the control can never return to.",
        )

        // The program that changes the length has to say so, wherever it
        // sits in the carousel: keyed on the name, not on an index, so
        // reordering the programs cannot silently un-say it.
        //
        // Read line by line to the list's own closing `)` rather than with
        // a `[^)]*` regex, and with comment lines dropped before the
        // strings are collected. Both matter: a `)` inside an explanatory
        // comment truncated the list the first time this law ran, and a
        // quoted phrase inside one would otherwise be counted as an entry.
        fun entriesOf(declaration: String): List<String> {
            val lines = src.lineSequence().dropWhile { declaration !in it }
            assertTrue(lines.any(), "could not find `$declaration`")
            val body = lines.drop(1).takeWhile { it.trim() != ")" }
            return body.filterNot { isCommentLine(it.trim()) }
                .flatMap { line -> Regex(""""([^"]*)"""").findAll(line).map { it.groupValues[1] } }
                .toList()
        }
        val nameList = entriesOf("private val PROG_NAMES = listOf(")
        val subList = entriesOf("private val PROG_SUBS = listOf(")
        assertTrue(nameList.size == subList.size && nameList.isNotEmpty(), "PROG_NAMES and PROG_SUBS disagree: $nameList vs $subList")

        val halfTimeAt = nameList.indexOfFirst { "HALF" in it }
        assertTrue(halfTimeAt >= 0, "no program named HALF-TIME in $nameList — if it was renamed, point this law at the new name.")
        assertTrue(
            "LONG" in subList[halfTimeAt],
            "the HALF-TIME program doubles the pattern length (`GrooveVariations.halfTime`: `bars * 2`) and " +
                "its own line does not say so: '${subList[halfTimeAt]}'. A length change reached through a " +
                "carousel of five options, with nothing on screen mentioning it, is the half of J17 a player " +
                "actually trips over.",
        )
    }
    private val appKt = File("../app/src/main/kotlin/com/snipsnap/app/App.kt")

    /**
     * J10, measured the way the finding measured it.
     *
     * Counting navigations across `App.kt`, the review found KIT 11, KITS
     * 4, TAPE 3, GROOVE 3 — and **CHOP 0, EXPORT 0**. Those are steps 2 and
     * 4 of the loop the app advertises: the app automated the one join in
     * the middle (CHOP → KIT) and left the two at the ends to the user.
     *
     * Counts both forms, because the screen is reached both ways: a direct
     * `screen = AppScreen.X` and the `goToScreen(AppScreen.X)` helper. A
     * law that knew only the form the review happened to grep for would go
     * green on a navigation that does not exist, or red on one that does.
     */
    @Test
    fun `law - every step of the advertised loop can be reached from the one before it`() {
        val src = appKt.readText(Charsets.UTF_8)
        fun navigationsTo(screen: String): Int =
            Regex("""(screen\s*=\s*AppScreen\.$screen\b|goToScreen\(AppScreen\.$screen\))""")
                .findAll(src).count()

        for (step in listOf("CHOP", "EXPORT")) {
            assertTrue(
                navigationsTo(step) > 0,
                "$step is step ${if (step == "CHOP") 2 else 4} of the loop the app advertises and nothing in " +
                    "App.kt ever navigates to it. Finishing a capture has to offer CHOP; finishing a kit has " +
                    "to offer EXPORT. Without it the app automates the one join in the middle and leaves both " +
                    "ends to a user who has to already know the loop exists.",
            )
        }

        // The offers are offers. A door the player can ignore, not a screen
        // that moves under them — being moved without asking is the same
        // complaint as a control that changes its own target.
        assertTrue(
            Regex("""offer\(Copy\.CAPTURE_OFFER,\s*Copy\.CAPTURE_OFFER_DOOR\)""").containsMatchIn(src) &&
                Regex("""offer\(Copy\.KIT_OFFER,\s*Copy\.KIT_OFFER_DOOR\)""").containsMatchIn(src),
            "both handoffs should go through `offer(...)`, which puts a door on a toast and leaves the screen " +
                "where it is. Navigating outright would be a stronger handoff and the wrong one.",
        )
        // The guard has to be ON the offer, not merely somewhere in the
        // file. `containsMatchIn` passed with the guard deleted, because
        // `pads.isNotEmpty()` appears elsewhere in App.kt — an existence
        // check where a locality check was needed.
        val lines = src.lines()
        val offers = lines.withIndex().filter { (_, line) -> "offer(Copy.KIT_OFFER" in line }
        assertTrue(offers.isNotEmpty(), "no EXPORT offer found at all")
        for ((at, line) in offers) {
            val above = lines.subList((at - 3).coerceAtLeast(0), at).joinToString("\n")
            assertTrue(
                "pads.isNotEmpty()" in above,
                "the EXPORT offer at line ${at + 1} is not guarded by the kit actually having pads:" +
                    "\n  ${line.trim()}\nA door onto an empty EXPORT is a worse answer than no door.",
            )
        }
    }
    /**
     * One touch-Y velocity rule, in one place.
     *
     * This is the repo's own recurring defect shape — one quantity in two
     * places — caught in the field rather than by a test. `MIN_VELOCITY =
     * 0.35f` was declared in **two** files, the ramp around it was retyped
     * in **three**, and J37 then gave KIT a fourth copy with a different
     * floor, a different accessibility value, **and the axis inverted**,
     * plus a permanent on-screen legend advertising the wrong direction.
     * Nothing noticed until a pad was tapped on a phone.
     *
     * The rule now lives in `PadHit` (`:shell`), where `PadTouchTest` pins
     * its direction and its floor. This keeps the screens delegating.
     */
    @Test
    fun `law - every pad grid reads touch-Y velocity through the one shared rule`() {
        val ui = File("../app/src/main/kotlin/com/snipsnap/app/ui")
        val sources = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        require(sources.size > 10) { "found only ${sources.size} ui sources — the scan is broken, not the tree." }

        val floors = mutableListOf<String>()
        val ramps = mutableListOf<String>()
        for (file in sources) {
            file.readText(Charsets.UTF_8).lineSequence().forEachIndexed { i, line ->
                if (isCommentLine(line.trim())) return@forEachIndexed
                if (Regex("""MIN_VELOCITY\s*=\s*[0-9.]+f""").containsMatchIn(line)) {
                    floors += "${file.name}:${i + 1}  ${line.trim()}"
                }
                if (Regex("""MIN_VELOCITY\s*\+\s*\(\s*1f\s*-\s*MIN_VELOCITY\s*\)""").containsMatchIn(line)) {
                    ramps += "${file.name}:${i + 1}  ${line.trim()}"
                }
            }
        }
        assertTrue(
            floors.isEmpty(),
            "a touch-Y velocity floor is written as a number again, instead of taken from `PadHit.SOFTEST`:\n  " +
                floors.joinToString("\n  ") +
                "\nTwo copies of this constant is how KIT came to disagree with PLAY about which end of a pad " +
                "is loud.",
        )
        assertTrue(
            ramps.isEmpty(),
            "the touch-Y ramp is retyped instead of calling `PadHit.velocityAt`:\n  " + ramps.joinToString("\n  "),
        )

        // And the screens that read a touch position do go through it.
        for (name in listOf("PadGrid.kt", "KitScreen.kt", "KeysScreen.kt")) {
            val file = sources.single { it.name == name }
            assertTrue(
                "PadHit.velocityAt(" in file.readText(Charsets.UTF_8),
                "$name plays pads but never calls `PadHit.velocityAt` — if it grew its own rule, that is the " +
                    "fork this law exists to stop.",
            )
        }

        // The accessible path is the one place a constant is right, and it
        // has to be the SAME constant everywhere: full velocity here (KIT's
        // first answer) is louder than what every other grid gives.
        // Read the whole `onClick { ... }` block, not the line the `onClick`
        // sits on: PadGrid's spans several lines, and a single-line match
        // failed it for being correct.
        val synthesized = sources.filter { """onClick(label = "PLAY")""" in it.readText(Charsets.UTF_8) }
        assertTrue(synthesized.isNotEmpty(), "no synthesized PLAY clicks found — the scan is broken")
        for (file in synthesized) {
            val block = blockAfter(file.readText(Charsets.UTF_8), """onClick(label = "PLAY")""")
            assertTrue(
                "CENTER" in block,
                "${file.name}'s synthesized PLAY click passes something other than the centre velocity:\n  " +
                    block.lines().first().trim() +
                    "\nIt has no position to read, so it gets what a tap at the pad's vertical middle would " +
                    "have produced — not the loudest hit available.",
            )
        }
    }
    /**
     * J35: EXPORT refused to write and said nothing.
     *
     * The justification was reasonable and wrong: "the refreshed checklist
     * below is the message". The checklist is the first card in a
     * `verticalScroll` and the write button is pinned at the bottom, so on
     * a phone the row that changed is very likely off-screen at the moment
     * of the tap — and a refusal you have to go looking for is one you
     * experience as the button doing nothing.
     *
     * `LandingNote` already existed for exactly this; its own KDoc lists "a
     * backup preflight refused part of" among the things it is for. EXPORT's
     * own preflight refusal did not use it.
     */
    @Test
    fun `law - a refused EXPORT says what blocked it`() {
        val src = File("../app/src/main/kotlin/com/snipsnap/app/ui/ExportScreen.kt").readText(Charsets.UTF_8)
        val blocked = blockAfter(src, "is ExportWizardModel.WriteResult.Blocked ->")
        val speaks = "onNote(" in blocked || "onToast(" in blocked
        assertTrue(
            speaks,
            "the Blocked branch neither raises the message box nor toasts, so a refused write is silent:\n" +
                blocked.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n").take(400),
        )
        assertTrue(
            "LandingNote.exportBlocked(" in blocked,
            "a blocked write can name several failing pads at once and has to stay until read — that is what " +
                "`LandingNote.exportBlocked` is for. A bare toast drops all but one reason and takes it away " +
                "again in a couple of seconds.",
        )
    }
    /**
     * J32: HUM stated its one rule after the tape was already audible.
     *
     * `HUM_START` is "HUM ALONG. HEADPHONES ON, OR THE MIC HEARS THE TAPE
     * TOO." — advice about a take that the armed ring is already capturing.
     * In source order it sat *after* `v.start(0)`, so it arrived at the one
     * moment acting on it meant wrecking the take it was warning about.
     *
     * It is the only rule on this screen whose value depends on arriving
     * before the thing it governs, which is why the order is a law rather
     * than a preference.
     */
    @Test
    fun `law - HUM says its rule before it starts the tape`() {
        // `startHum` is nested inside `ChopContent`, so the top-level
        // helper cannot see it; brace-match from its own head instead.
        val body = codeOnly(blockAfter(chopScreen.readText(Charsets.UTF_8), "fun startHum()"))
        val says = body.indexOf("onToast(Copy.HUM_START)")
        val starts = body.indexOf("v.start(0)")
        assertTrue(says >= 0, "startHum no longer says HUM_START at all")
        assertTrue(starts >= 0, "startHum no longer starts the voice")
        assertTrue(
            says < starts,
            "HUM starts the tape before stating the rule that makes the take usable. The armed ring is " +
                "already capturing by then, so the advice arrives exactly when acting on it costs the take.",
        )
    }
    /**
     * J43: a control that cannot act says so, even when the refusal is in a
     * function rather than in its own lambda.
     *
     * `onStep` routes GRID to `stepGrid` and the ladder to `stepLadder`;
     * every other mode goes to `stepHits`, which opens with
     * `hitsOf(model.mode) ?: return`. HUMMED is the mode that reaches it
     * that way, so CHOP's ◀ ▶ were lit, announced "ONE PART FEWER" to
     * TalkBack, and did nothing.
     *
     * The `enabled`-contract law above could not catch it: that one looks
     * for `return@Component` written inline, and this refusal lives one
     * call away. So this law checks the other end — that the stepper's own
     * `enabled` is mode-aware rather than merely "not busy".
     */
    @Test
    fun `law - the CUT stepper refuses a mode it cannot step`() {
        val src = codeOnly(chopScreen.readText(Charsets.UTF_8))
        assertTrue(
            Regex("""val\s+canStep\s*=""").containsMatchIn(src),
            "the CUT bench no longer works out which modes ◀ ▶ can actually step. `stepHits` returns on its " +
                "first line for a hummed chop, so without this the buttons stay lit and do nothing.",
        )
        val steppers = src.lines().filter { """SecondaryButton("◀"""" in it || """SecondaryButton("▶"""" in it }
        assertTrue(steppers.size == 2, "expected two stepper buttons in the CUT bench, found ${steppers.size}")
        for (line in steppers) {
            assertTrue(
                "canStep" in line,
                "a CUT stepper is gated without `canStep`, so it stays lit on a mode it cannot step:\n  " +
                    line.trim(),
            )
            assertTrue(
                "!humming" in line,
                "a CUT stepper can fire while a hum is capturing:\n  " + line.trim(),
            )
        }
    }

    // ==================== Law: GROOVE's programs are named for what the exporter writes ====================

    /**
     * The five GROOVE programs read `PROG A · THE BREAK` … `PROG E ·
     * EDITED` until J18 — index first, meaning second, on a screen where
     * the index means nothing. A–E is an argument to
     * `GrooveProgram.compute` and nothing else: it is not an MPC clip
     * slot, and no exporter in the app has ever written it.
     *
     * What the exporter *does* write is the word. `GrooveVariations`
     * suffixes each derived clip's name — `Swing` (or `Tight`), `Half`,
     * `Sparse` — and those strings go into `groove.json` and out to the
     * MPC's clip list. So the letters were the one set of names in the
     * app that reached nothing outside `GrooveScreen.kt`, and the screen
     * and the SD card disagreed about what these things are called.
     *
     * This law reads the names off the screen's own source and the
     * suffixes out of the exporter, so the two cannot drift apart again.
     *
     * **Two programs are exempt, for reasons that are facts rather than
     * taste.** The captured program is the base clip: it carries no
     * suffix at all, so there is no exporter word to match and the app
     * picks its own. The user's own program is stored and found again by
     * `GrooveEdit.NAME_SUFFIX`, which is `" E"` — a marker in
     * `groove.json`, not a name a player would recognise, and not
     * renameable without migrating every kit already on disk. The screen
     * calls it YOURS and the marker stays where it is.
     */
    @Test
    fun `the GROOVE programs are named for what the exporter writes`() {
        val groove = File("../app/src/main/kotlin/com/snipsnap/app/ui/GrooveScreen.kt")
        assertTrue(groove.isFile, "expected to find ${groove.absolutePath}")
        val block = blockAfterList(groove.readText(Charsets.UTF_8), "private val PROG_NAMES = listOf")
        val names = Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(
            5,
            names.size,
            "expected five program names in GrooveScreen's PROG_NAMES, found $names — the pattern this " +
                "law reads must have changed, which would make it pass by checking nothing.",
        )

        // The exporter's own words, taken from the exporter rather than retyped.
        val base = com.snipsnap.mpc3.Mpc3Clip(
            name = "BASE",
            bars = 1,
            notes = listOf(com.snipsnap.mpc3.Mpc3Note(note = 36, timePulses = 0L, velocity = 1.0f)),
        )
        val written = com.snipsnap.kit.GrooveVariations.standard(base, swingPercent = 60)
        // standard() returns base, swung, half, sparse — in the same order
        // the screen lists them, which is the order `GrooveProgram.compute`
        // indexes. Index 0 is the base and carries no suffix.
        val suffixes = written.drop(1).map { it.name.removePrefix("BASE").trim().substringBefore(' ') }
        assertEquals(
            3,
            suffixes.count { it.isNotBlank() },
            "GrooveVariations.standard no longer suffixes its three derived clips (got $suffixes) — " +
                "this law reads the exporter's vocabulary out of it, so an unsuffixed variation would " +
                "make the check vacuous rather than failing.",
        )
        for ((i, suffix) in suffixes.withIndex()) {
            val onScreen = names[i + 1]
            assertTrue(
                onScreen.startsWith(suffix.uppercase(), ignoreCase = true) ||
                    suffix.startsWith(onScreen, ignoreCase = true),
                "GROOVE's program ${i + 1} is called '$onScreen' on screen, but the exporter writes " +
                    "'$suffix' into the clip name that lands on the MPC. A player who picks a program here " +
                    "and then looks for it on the hardware has to recognise it — that is the whole reason " +
                    "the A–E letters went (J18). Screen names: $names; exporter suffixes: $suffixes.",
            )
        }

        // The letters are gone from the screen's own labels.
        val letters = names.filter { Regex("""^PROG [A-E]\b""").containsMatchIn(it) }
        assertTrue(
            letters.isEmpty(),
            "GrooveScreen's PROG_NAMES is index-first again: $letters. A–E is an argument to " +
                "GrooveProgram.compute, not an MPC clip slot — see this law's KDoc.",
        )
    }


    // ==================== Law: no comment closes itself by accident ====================

    /**
     * An asterisk followed by a slash ends a block comment, so writing
     * markdown-ish emphasis around a slash inside a KDoc terminates it
     * mid-sentence. Everything after it becomes code, and the compiler
     * reports a cascade of "Expecting a top level declaration" at a column
     * that looks like ordinary English.
     *
     * This cost a CI cycle. `:app` has no Kotlin test source set, so
     * `android-build` is the only thing that compiles it — a syntax error
     * there is invisible to `./gradlew test` and shows up only after a
     * push. The same mistake had been made and fixed in this very file an
     * hour earlier; fixing that instance without sweeping for the shape is
     * what let the second one through, in `GrooveScreen.kt`.
     *
     * A line that is nothing but a closer is legitimate (if unusual), so
     * only an occurrence with prose around it is refused. The sequence is
     * assembled at runtime rather than written out, because a law that
     * names the thing it forbids would flag its own source — which is how
     * the first draft of this law failed.
     */
    @Test
    fun `no Kotlin source ends a doc comment by accident`() {
        val closer = "*".repeat(2) + "/"
        val roots = listOf("../app/src", "../shell/src", "../kit/src", "../audio/src", "../loop/src", "../synth/src", "../cli/src")
            .map { File(it) }
            .filter { it.isDirectory }
        assertTrue(roots.size >= 5, "only found ${roots.size} source roots — the scan is broken, not the tree.")
        val sources = roots.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
        assertTrue(sources.size > 100, "found only ${sources.size} .kt files — the scan is broken, not the tree.")

        val bad = mutableListOf<String>()
        for (file in sources) {
            file.readText(Charsets.UTF_8).lineSequence().forEachIndexed { i, line ->
                if (closer in line && line.trim() != closer) {
                    bad += "${file.path}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            bad.isEmpty(),
            "these lines close the enclosing doc comment where they stand, turning the rest of the " +
                "comment into code:\n  " + bad.joinToString("\n  ") +
                "\nUse backticks rather than double-asterisk emphasis next to a slash. This is checked " +
                "here because :app has no test source set — a syntax error in it is invisible to the " +
                "JVM suite and only surfaces when CI compiles the app.",
        )
    }


    // ==================== Law: every pad sheet door retires its own hint ====================

    /**
     * PAD SHEET has three doors — KIT's long press, DOUBLES' `GO`, and the
     * RE-TRIM return — and its discovery hint was retired inside exactly
     * one of them (J16). A user who found the sheet either of the other
     * two ways was told "HOLD A PAD TO OPEN ITS PAD SHEET" on every kit
     * open, forever.
     *
     * `Copy.PAD_SHEET_HINT`'s own KDoc says opening the sheet is "the only
     * event that proves they found it". That was true of the intent and
     * false of the code, which is the shape this wave keeps finding: a
     * comment asserting a behaviour the code does not have.
     *
     * `openPadSheet` is the one door now. Opening is assigning a slot;
     * closing is assigning null, and closing has nothing to retire — so
     * this refuses a non-null assignment made anywhere else.
     */
    @Test
    fun `every pad sheet door retires its own hint`() {
        val app = File("../app/src/main/kotlin/com/snipsnap/app/App.kt")
        assertTrue(app.isFile, "expected to find ${app.absolutePath}")
        val src = codeOnly(app.readText(Charsets.UTF_8))

        assertTrue(
            "fun openPadSheet(" in src,
            "App.kt no longer declares `openPadSheet`. Every door onto PAD SHEET has to go through one " +
                "function, or the hint gets retired by some of them and not others — see J16.",
        )
        val opener = src.substringAfter("fun openPadSheet(")
        assertTrue(
            "PAD_SHEET_FOUND" in opener.take(400),
            "`openPadSheet` no longer retires the hint (PAD_SHEET_FOUND). It is the one place that does; " +
                "without it every door nags forever.",
        )

        // Assigning a slot is opening. Assigning null is closing, and a
        // close has no hint to retire.
        //
        // The assigned token is CAPTURED and compared, not asserted around
        // with a lookahead: `padSheetSlot\s*=\s*(?!null)` reads as "an
        // assignment of something other than null" and is not one. `\s*`
        // backtracks to zero width, so the lookahead runs against the
        // space before `null`, is satisfied that a space is not `null`,
        // and the pattern matches every close in the file. It flagged all
        // ten of them on correct code.
        val outside = src.substringBefore("fun openPadSheet(") + opener.substringAfter("}", "")
        val assigned = Regex("""padSheetSlot\s*=\s*([A-Za-z0-9_.]+)""")
            .findAll(outside)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(
            assigned.isNotEmpty(),
            "found no `padSheetSlot =` assignments at all outside openPadSheet — App.kt must still close " +
                "the sheet somewhere, so this scan is broken rather than the code being clean.",
        )
        val bad = assigned.filterNot { it == "null" }
        assertEquals(
            emptyList(),
            bad,
            "App.kt opens PAD SHEET by assigning `padSheetSlot` directly, outside `openPadSheet`: $bad. " +
                "That is how J16 happened — the hint is retired in one place and the other doors walk " +
                "past it. Call openPadSheet(slot) instead.",
        )
    }

}
