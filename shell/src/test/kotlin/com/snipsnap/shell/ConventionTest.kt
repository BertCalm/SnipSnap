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
 * Both laws below are keyed on file path + the call site's own trimmed
 * text, never line numbers: line numbers drift (a concurrent kit-write-
 * mutex audit is editing several of these exact files right now), and a
 * line-numbered allowlist would start failing for reasons that have
 * nothing to do with the invariant it guards. A text-keyed entry survives
 * drift and still fails loudly the moment the call site's own shape
 * actually changes.
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

    /**
     * FROZEN — PRE-EXISTING, NOT BLESSED. These nine sites all read a file
     * `:app` itself already wrote (a kit pad sample or a SNIP) rather than
     * an arbitrary external file, and every current producer of those
     * files happens to already be capped today (SnipStore.IMPORT_MAX_SEC,
     * mic-capture's own real-time duration, TAPE's readCapped since
     * 4fd6aeb/b73ad49/0f2ff24) — but that guarantee is INDUCTIVE, not
     * enforced: KitBuilderModel.assign and OneNote.export both accept any
     * Snip with frameCount > 0, with no cap of their own, so a future
     * caller that doesn't honor the convention would silently reintroduce
     * the OOM these commits closed elsewhere. This allowlist exists so the
     * guardrail can ship without rewriting :app code that's out of scope
     * for this change (and a concurrent kit-write-mutex audit is editing
     * several of these exact files right now) — it stops NEW unbounded
     * reads from joining these nine, it does NOT certify these nine as
     * reviewed-safe. Flagged as a follow-up in the report accompanying
     * this guardrail: give each of these WavReader.readCapped(file,
     * someGenerousCeiling) as defense in depth so the guarantee becomes
     * enforced instead of conventional.
     */
    private val frozenUnblessedReads = listOf(
        ReadAllow("App.kt", "val snip = Cleanup.toMono(WavReader.read(file))", "assignPendingSnip: `file` is a SNIP"),
        ReadAllow(
            "InstrumentPlayer.kt",
            "val snip = runCatching { WavReader.read(File(dir, zone.sample)) }.getOrNull() ?: continue",
            "instrument zone sample, only ever produced by OneNote.export from an already-bounded pad Snip",
        ),
        ReadAllow(
            "KitShelf.kt",
            "val snip = WavReader.read(File(source.dir, pad.sampleFile))",
            "kit pad sample, written by KitBuilderModel.assign from an already-bounded Snip",
        ),
        ReadAllow(
            "PadEngine.kt",
            "val snip = runCatching { WavReader.read(File(entry.dir, file)) }.getOrNull() ?: continue",
            "kit pad sample, same inductive bound as KitShelf.kt",
        ),
        ReadAllow(
            "ui/GrainFieldScreen.kt",
            "runCatching { Cleanup.toMono(WavReader.read(File(entry.dir, p.sampleFile))) }.getOrNull()",
            "kit pad sample, same inductive bound",
        ),
        ReadAllow(
            "ui/PadSheetScreen.kt",
            "val s = runCatching { Cleanup.toMono(WavReader.read(File(entry.dir, p.sampleFile))) }.getOrNull()",
            "kit pad sample, same inductive bound",
        ),
        ReadAllow(
            "ui/SnipsScreen.kt",
            "runCatching { Cleanup.toMono(WavReader.read(info.file)) }.getOrNull()",
            "a SNIP file, bounded by SnipStore's own caps",
        ),
        ReadAllow(
            "ui/SplitScreen.kt",
            "withContext(Dispatchers.IO) { WavReader.read(File(dir, file)) }",
            "kit pad sample, same inductive bound",
        ),
        ReadAllow(
            "ui/SurfaceScreen.kt",
            "runCatching { WavReader.read(File(dir, pad.sampleFile)) }.getOrNull()",
            "kit pad sample, same inductive bound",
        ),
    )

    @Test
    fun `law - app code only calls the bounded WavReader readCapped, never the unbounded read`() {
        val sites = scanWavReaderReadSites()
        assertTrue(
            sites.isNotEmpty(),
            "found zero WavReader.read( sites under :app (recon counted 10) — either every site was genuinely " +
                "fixed to use readCapped (great: then also delete the now-stale allowlist entries below, since a " +
                "stale allowlist entry silently protects nothing) or this scan is broken; a scan finding nothing " +
                "would otherwise pass by accident, which is worse than no test at all.",
        )

        val allowlist = justifiedReads + frozenUnblessedReads
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
}
