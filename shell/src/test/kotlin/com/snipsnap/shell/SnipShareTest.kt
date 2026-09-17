package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The name a snip travels under when it leaves the app.
 *
 * A snip on disk is `snip_<capturedMillis>[_<name>].wav` — a shape
 * [SnipStore] parses and nothing outside it should ever have to read. But
 * `ShareOut.send` puts the file's own name in `EXTRA_SUBJECT` and on the
 * `ClipData`, so whatever this returns is what lands in someone's Downloads,
 * their messenger's attachment row, or their DAW's import dialog. Sharing
 * the on-disk name would hand a stranger `snip_1755700000000_BREAK.wav`.
 *
 * It is also why the file is copied into the share cache rather than served
 * where it lies: `share_paths.xml` covers the share cache and EXPORT's
 * output and deliberately nothing else ("the shelf's own kit folders are
 * never exposed either way"), and `FileProvider.getUriForFile` throws for a
 * path outside it. The copy is what makes the rename possible too.
 */
class SnipShareTest {

    /** No file of that name in the share cache. */
    private val nothingTaken: (String) -> Boolean = { false }

    @Test
    fun `a named snip travels under its own name`() {
        assertEquals("BREAK.wav", SnipStore.shareName("BREAK", nothingTaken))
    }

    @Test
    fun `an unnamed snip travels under the neutral fallback`() {
        // `Info.displayName` is `name ?: "SNIP"`, so this is the value the
        // row itself shows for a snip the classifier never named.
        assertEquals("SNIP.wav", SnipStore.shareName("SNIP", nothingTaken))
    }

    /**
     * The point of the whole function: the internal shape never leaves.
     *
     * Asserted as an absence rather than an equality because the equality
     * tests above would still pass if the timestamp were appended somewhere
     * — and a leaked `snip_<millis>` is exactly the thing a reader of this
     * file would want proof about.
     */
    @Test
    fun `the on-disk encoding never leaves the app`() {
        val name = SnipStore.shareName("BREAK", nothingTaken)
        assertFalse("snip_" in name, "the internal prefix leaked into a shared file name: '$name'")
        assertFalse(Regex("""\d{10,}""").containsMatchIn(name), "a captured-at timestamp leaked into '$name'")
    }

    /**
     * Two snips called BREAK, shared one after the other, must not collide.
     *
     * Not tidiness. The share cache hands a `content://` URI to another app
     * and that app reads it on its own schedule — a messenger may still be
     * uploading the first when the second is staged. Overwriting the file
     * under it corrupts a transfer already in flight, and the user's only
     * evidence is a broken attachment at the far end.
     */
    @Test
    fun `a name the share cache already holds counts up instead of clobbering it`() {
        val held = mutableSetOf("BREAK.wav")
        assertEquals("BREAK 2.wav", SnipStore.shareName("BREAK") { it in held })
        held += "BREAK 2.wav"
        assertEquals("BREAK 3.wav", SnipStore.shareName("BREAK") { it in held })
    }

    @Test
    fun `a gap left by a swept file is reused`() {
        // `StorageSweep` deletes stale share-cache copies, so gaps are
        // normal here rather than exotic. Same rule as everywhere else that
        // counts names in this codebase.
        val held = setOf("BREAK.wav", "BREAK 3.wav")
        assertEquals("BREAK 2.wav", SnipStore.shareName("BREAK") { it in held })
    }

    /**
     * A snip's name is user-typed (SNIPS has a RENAME dialog), so it can
     * hold anything a keyboard offers — including the separators that would
     * make this a path rather than a file name.
     */
    @Test
    fun `a user-typed name cannot become a path`() {
        val name = SnipStore.shareName("../../etc/passwd", nothingTaken)
        assertFalse("/" in name, "a share name became a path: '$name'")
        assertFalse(".." in name, "a share name kept a parent-directory hop: '$name'")
        assertEquals("etc_passwd.wav", name)
    }

    @Test
    fun `a blank name still produces a usable file`() {
        val name = SnipStore.shareName("   ", nothingTaken)
        assertTrue(name.endsWith(".wav"), "'$name' is not a wav")
        assertTrue(name.length > ".wav".length, "'$name' has no stem at all")
    }
}
