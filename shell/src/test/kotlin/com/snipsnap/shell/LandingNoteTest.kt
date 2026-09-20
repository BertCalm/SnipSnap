package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LandingNoteTest {

    @Test
    fun `a clean landing keeps its toast - the box is for trouble`() {
        assertNull(LandingNote.landed("pack.xpn", listOf("Funk"), emptyList()))
        assertNull(LandingNote.landed("shelf.zip", listOf("Funk", "Soul", "Boom"), emptyList()))
        assertNull(LandingNote.backedUp(listOf("Funk", "Soul"), emptyMap()))
    }

    @Test
    fun `a landing with skips - the toast's line as the title, skipped first in the warn colour with the door's reason, landed after`() {
        val n = LandingNote.landed("pack.zip", listOf("Funk", "Soul"), listOf("Broken: no kit.json inside."))!!
        assertEquals(Copy.landed(2, 1), n.title)
        assertEquals(listOf("SKIPPED · BROKEN: NO KIT.JSON INSIDE", "LANDED · FUNK", "LANDED · SOUL"), n.lines.map { it.text })
        assertEquals(listOf(true, false, false), n.lines.map { it.trouble })
        assertEquals("FINE", n.button, "the capture-blocked box's own button")
    }

    @Test
    fun `a backup with refusals names the kit and preflight's reason, the packed ones after`() {
        val n = LandingNote.backedUp(listOf("Funk"), linkedMapOf("Soul" to "pad A03's WAV is missing"))!!
        assertEquals(Copy.backedUp(1, 1), n.title)
        assertEquals("SKIPPED · SOUL: PAD A03'S WAV IS MISSING", n.lines.first().text)
        assertTrue(n.lines.first().trouble)
        assertEquals("PACKED · FUNK", n.lines.last().text)
        assertTrue(!n.lines.last().trouble)
    }

    @Test
    fun `a backup's presets are counted in the title, on the way out and on the way home, and never open the box alone`() {
        assertEquals(Copy.landed(2, 1, 3), LandingNote.landed("shelf.zip", listOf("Funk", "Soul"), listOf("Bad: why"), presets = 3)!!.title)
        assertEquals(Copy.backedUp(1, 1, 2), LandingNote.backedUp(listOf("Funk"), linkedMapOf("Soul" to "why"), presets = 2)!!.title)
        assertNull(LandingNote.landed("shelf.zip", listOf("Funk"), emptyList(), presets = 3))
        assertNull(LandingNote.backedUp(listOf("Funk"), emptyMap(), presets = 3))
    }

    @Test
    fun `a refusal keeps the file's name and the refuser's words until read`() {
        val n = LandingNote.refused("holiday.mp4", "nothing to hear in that.")
        assertEquals(Copy.NOTHING_LANDED, n.title)
        assertEquals(listOf("HOLIDAY.MP4", "NOTHING TO HEAR IN THAT"), n.lines.map { it.text })
        assertEquals(listOf(false, true), n.lines.map { it.trouble })
        assertEquals("THE SHARE", LandingNote.refused("  ", "x").lines.first().text, "a nameless share still has a line")
        assertEquals("NAME", LandingNote.shout(" name . "), "no space left where the full stop was")
        assertEquals("A. B", LandingNote.shout("a. b..."), "only the trailing stops go")
        assertEquals("IT SAID NO", LandingNote.refused("a.zip", "").lines.last().text, "a reasonless refusal still has one")
    }

    @Test
    fun `past MAX_LINES the rest folds into +N MORE, trouble only if a folded line was`() {
        val n = LandingNote.landed("pack.zip", (1..8).map { "Kit $it" }, listOf("Bad: why"))!!
        assertEquals(LandingNote.MAX_LINES, n.lines.size)
        assertTrue(n.lines.first().trouble, "the skip leads")
        assertEquals("+4 MORE", n.lines.last().text)
        assertTrue(!n.lines.last().trouble, "the folded lines were all landed kits")
        val m = LandingNote.landed("pack.zip", listOf("Funk"), (1..7).map { "Bad $it: why" })!!
        assertEquals("+3 MORE", m.lines.last().text)
        assertTrue(m.lines.last().trouble, "a skipped kit folded away still shows as trouble")
    }

    // ---- EXPORT's own refusal (J35) ----

    private fun fail(msg: String, slot: Int? = null) = com.snipsnap.kit.Finding(com.snipsnap.kit.Severity.FAIL, msg, slot)
    private fun warn(msg: String) = com.snipsnap.kit.Finding(com.snipsnap.kit.Severity.WARN, msg)

    /**
     * J35: a blocked EXPORT wrote nothing and said nothing.
     *
     * The justification was that "the refreshed checklist below is the
     * message" — but the checklist is the first card in a `verticalScroll`
     * and the button is pinned at the bottom, so on a phone the row that
     * changed is very likely off-screen at the moment of the tap. A refusal
     * the user has to go looking for is a refusal they experience as the
     * button doing nothing.
     *
     * The box already existed for exactly this: its own KDoc lists "a
     * backup preflight refused part of" among the things it is for, and
     * [backedUp] uses it. EXPORT's preflight refusal did not.
     */
    @Test
    fun `a blocked export names what blocked it`() {
        val note = LandingNote.exportBlocked(listOf(warn("48 khz"), fail("KICK.WAV is missing", slot = 1)))
        assertNotNull(note)
        assertTrue(note.lines.any { "MISSING" in it.text }, "the blocking reason is not in the box: ${note.lines}")
        assertTrue(note.lines.first().trouble, "the reason that blocked the write should read as trouble")
    }

    /**
     * Only FAILs block. A WARN exports anyway (`Preflight`'s own rule), so
     * a box listing warnings after a *successful* write would be telling
     * the user their export failed when it did not.
     */
    @Test
    fun `warnings alone are not a refusal`() {
        assertNull(LandingNote.exportBlocked(listOf(warn("48 khz"), warn("long name"))))
        assertNull(LandingNote.exportBlocked(emptyList()))
    }

    @Test
    fun `every blocking reason is listed, not just the first`() {
        val note = LandingNote.exportBlocked(listOf(fail("A IS MISSING"), fail("B COLLIDES"), warn("C IS ODD")))
        assertNotNull(note)
        assertTrue(note.lines.any { "A IS MISSING" in it.text })
        assertTrue(note.lines.any { "B COLLIDES" in it.text })
    }

    /**
     * A kit can fail preflight on many pads at once; the box is a
     * phone-height box, not a scroll, so the same fold every other note
     * uses applies here.
     */
    @Test
    fun `a wall of failures folds like every other note`() {
        val many = (1..12).map { fail("PAD $it IS MISSING", slot = it) }
        val note = LandingNote.exportBlocked(many)
        assertNotNull(note)
        assertTrue(note.lines.size <= LandingNote.MAX_LINES, "the box grew to ${note.lines.size} lines")
        assertTrue(note.lines.last().text.startsWith("+"), "the fold line is missing: ${note.lines.last().text}")
    }

    @Test
    fun `the box shouts, like every other note`() {
        val note = LandingNote.exportBlocked(listOf(fail("kick.wav is missing")))!!
        assertEquals(note.title, note.title.uppercase(java.util.Locale.ROOT))
        for (line in note.lines) assertEquals(line.text, line.text.uppercase(java.util.Locale.ROOT))
    }

}
