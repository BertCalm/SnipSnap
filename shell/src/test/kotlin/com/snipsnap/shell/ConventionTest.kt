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
     * So this reads `MENU_ITEMS` out of `Chrome.kt` by source, the same way
     * the roster law above reads prose, and holds every stage to it.
     */
    @Test
    fun `the first-run loop names real menu tabs`() {
        val chrome = File("../app/src/main/kotlin/com/snipsnap/app/ui/Chrome.kt")
        assertTrue(chrome.isFile, "expected to find ${chrome.absolutePath} to read MENU_ITEMS from")
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
        // Info, displayName, rename, delete, provenanceTag) is handed a file
        // or nothing at all and cannot name the wrong folder.
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
}
