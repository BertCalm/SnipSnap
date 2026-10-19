package com.snipsnap.shell

import com.snipsnap.kit.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The words of the four-place shape, held the way `PersonalityTest` holds HELP today. */
class PlacesTest {

    @Test
    fun `the rows read the places and stages the navigation model has`() {
        assertEquals(listOf("SHELF", "TAPE", "PROJECT", "MAKE"), Places.PLACES)
        assertEquals(listOf("DECK", "CUT"), Places.TAPE_STAGES)
        assertEquals(listOf("PADS", "BEAT", "OUT"), Places.PROJECT_STAGES)
    }

    @Test
    fun `help has one section per place and one for the dock, in lines that fit`() {
        assertEquals(Places.PLACES + "THE DOCK", Places.HELP.keys.toList())
        for ((section, lines) in Places.HELP) {
            assertTrue(lines.size in 3..5, "$section: three to five lines, not ${lines.size}")
            for (line in lines) {
                assertTrue(line.startsWith("· "), "$section: bulleted: $line")
                assertTrue(line.length <= Places.HELP_LINE_CHARS, "$section: too long (${line.length}): $line")
                assertTrue(line.endsWith("."), "$section: a sentence: $line")
                assertEquals(line, line.replace(Regex("(?<![0-9])[a-z]"), { it.value.uppercase() }), "$section: TapeOS shouts: $line")
            }
        }
        for (tag in listOf("M0", "M1", "SKELETON", "ARRIVES WITH")) {
            assertFalse(Places.HELP.values.flatten().any { tag in it }, "HELP talks about the app, not the build: $tag")
        }
    }

    @Test
    fun `the front door says who the app is for`() {
        val firstSection = Places.HELP.values.first()
        assertTrue(firstSection.take(2).any { "MPC" in it }, "the MPC is named at the top of HELP, not ninth word of the fourth bullet")
        assertTrue(Places.HELP.getValue("PROJECT").any { "SESSION" in it }, "and the session export is named")
    }

    @Test
    fun `every door label carries its own arrow`() {
        for (door in listOf(Places.DOOR_CUT, Places.DOOR_PADS, Places.DOOR_BEAT, Places.DOOR_OUT, PadStrip.DOOR_OPEN, PadStrip.DOOR_CAPTURE, PadStrip.DOOR_FROM_SNIPS)) {
            assertTrue(door.endsWith(" ▸"), door)
        }
    }

    @Test
    fun `the first-run note for the places keeps the four verbs and names two places`() {
        val note = Copy.FIRST_RUN_PLACES_NOTE
        assertTrue(note.startsWith("RECORD IT, CUT IT, KIT IT, WRITE IT."), note)
        assertTrue("TAPE" in note && "PROJECT" in note, note)
        assertFalse("TABS" in note, "there are no tabs in the four-place shape: $note")
        for (word in listOf("CHOP", "EXPORT", "SHELF", "MAKE")) {
            assertFalse(Regex("""\b$word\b""").containsMatchIn(note), "$word is a stage or a place the note does not need: $note")
        }
    }

    @Test
    fun `the switcher and the THIS KIT line read what they hold`() {
        assertEquals("KITCHEN ▾ · 1 KIT IN THIS PROJECT", Places.switcher(Project("P", listOf("Kitchen"))))
        assertEquals("FUNK ROOM ▾ · 2 KITS IN THIS PROJECT", Places.switcher(Project("P", listOf("Kitchen", "Funk Room"), activeKit = "Funk Room")))
        assertEquals(Places.NO_KITS, Places.switcher(Project("P")))
        assertEquals("KEY Am · BANK B EMPTY · 3 VERSIONS", Places.thisKit("Am", bankBEmpty = true, versions = 3))
        assertEquals("NO KEY · BANK B IN USE · 1 VERSION", Places.thisKit(null, bankBEmpty = false, versions = 1))
    }
}
