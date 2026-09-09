package com.snipsnap.shell

import java.io.File
import kotlin.test.Test
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
            val text = file.readText()
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
                enabled = !anyOverlayOpen && screen != AppScreen.KITS &&
                    screen != AppScreen.SPLIT && screen != AppScreen.KEYS &&
                    note == null && !captureBlocked && !micPermissionDenied,
                """,
            ),
            justification = "the app-level ROOT handler, not a busy-guard: its entire job is to defer. " +
                "anyOverlayOpen, SPLIT, and KEYS each already have their own unconditional handler or their own " +
                "back door (the KDoc directly above this call enumerates all three); the remaining case, " +
                "AppScreen.KITS, is the true root screen, where falling through to Activity.finish() is the " +
                "correct, intended Android behavior, not an accident. This is the one site that is SUPPOSED to " +
                "read as a fallback, never as a mid-operation guard.",
        ),
        BackHandlerAllow(
            file = "ui/SnipsScreen.kt",
            args = normalizeSpan("enabled = confirmDelete == null"),
            justification = "paired: while confirmDelete != null (this one dark), the confirm dialog composes its " +
                "own unconditional BackHandler(onBack = cancelDelete) in that exact window (see the innermost " +
                "BackHandler a few lines further down, inside `confirmDelete?.let { ... }`), so Back is never left " +
                "with zero enabled callbacks. Verified below by the self-check that this file still contains an " +
                "unconditional BackHandler.",
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
     * What [inLock] actually proves is narrower than the invariant above:
     * it's true when the `open(` call's own index falls inside *some*
     * `KitWrites.mutex.withLock { ... }` span in the same file — it does
     * NOT prove the matching mutate+save live in that same span. Two
     * separate `withLock { open() }` / `withLock { mutate(); save() }`
     * blocks would each read as guarded here while still racing each
     * other. Every guarded site in this codebase today keeps open,
     * mutate, and save together in one block (verified by reading each
     * one, not assumed from this flag) — but this scan cannot enforce
     * that shape, only the sites currently on this allowlist.
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
            val text = file.readText()
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
}
