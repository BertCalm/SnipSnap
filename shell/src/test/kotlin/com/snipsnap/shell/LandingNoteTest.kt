package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `a refusal keeps the file's name and the refuser's words until read`() {
        val n = LandingNote.refused("holiday.mp4", "nothing to hear in that.")
        assertEquals(Copy.NOTHING_LANDED, n.title)
        assertEquals(listOf("HOLIDAY.MP4", "NOTHING TO HEAR IN THAT"), n.lines.map { it.text })
        assertEquals(listOf(false, true), n.lines.map { it.trouble })
        assertEquals("THE SHARE", LandingNote.refused("  ", "x").lines.first().text, "a nameless share still has a line")
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
}
