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
        assertEquals(listOf("LIBRARY", "SAMPLE", "PROJECT", "CREATE"), Places.PLACES)
        assertEquals(listOf("RECORD", "CHOP"), Places.TAPE_STAGES)
        assertEquals(listOf("PADS", "BEAT", "EXPORT"), Places.PROJECT_STAGES)
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
                // HELP lines are messages: sentence case, so the first word is capitalised and the line is not all caps.
                assertTrue(line.removePrefix("· ").first().let { !it.isLetter() || it.isUpperCase() }, "$section: opens with a capital: $line")
                assertTrue(line.any { it.isLowerCase() }, "$section: sentence case, not caps: $line")
            }
        }
        for (tag in listOf("M0", "M1", "SKELETON", "ARRIVES WITH")) {
            assertFalse(Places.HELP.values.flatten().any { it.contains(tag, ignoreCase = true) }, "HELP talks about the app, not the build: $tag")
        }
    }

    @Test
    fun `the front door says who the app is for`() {
        val firstSection = Places.HELP.values.first()
        assertTrue(firstSection.take(2).any { it.contains("MPC", ignoreCase = true) }, "the MPC is named at the top of HELP, not ninth word of the fourth bullet")
        assertTrue(Places.HELP.getValue("PROJECT").any { it.contains("SESSION", ignoreCase = true) }, "and the session export is named")
    }

    @Test
    fun `every door label carries its own arrow`() {
        for (door in listOf(Places.DOOR_CUT, Places.DOOR_PADS, Places.DOOR_BEAT, Places.DOOR_OUT, PadStrip.DOOR_OPEN, PadStrip.DOOR_CAPTURE, PadStrip.DOOR_FROM_SNIPS)) {
            assertTrue(door.endsWith(" ▸"), door)
        }
    }

    @Test
    fun `the first-run note keeps the four verbs and ends on the kit way in`() {
        val note = Copy.FIRST_RUN_PLACES_NOTE
        assertTrue(note.startsWith("Record it, chop it, kit it, export it."), note)
        assertTrue(note.endsWith(" Or start from a kit."), note)
        // The places moved out of the note when the panel's row of places went:
        // the tab row above says them, and the buttons under the note are the way in.
        assertFalse(note.contains("SAMPLE", ignoreCase = true) || note.contains("PROJECT", ignoreCase = true), "the note repeats the tab row: $note")
        assertFalse(note.contains("TABS", ignoreCase = true), "there are no tabs in the four-place shape: $note")
        for (word in listOf("LIBRARY", "CREATE")) {
            assertFalse(Regex("""\b$word\b""", RegexOption.IGNORE_CASE).containsMatchIn(note), "$word is a stage or a place the note does not need: $note")
        }
    }

    @Test
    fun `the switcher and the THIS KIT line read what they hold`() {
        assertEquals("KITCHEN · 1 KIT IN THIS PROJECT ▾", Places.switcher(Project("P", listOf("Kitchen"))))
        assertEquals("FUNK ROOM · 2 KITS IN THIS PROJECT ▾", Places.switcher(Project("P", listOf("Kitchen", "Funk Room"), activeKit = "Funk Room")))
        assertEquals(Places.NO_KITS, Places.switcher(Project("P")))
        assertEquals("KEY Am · BANK B EMPTY · 3 VERSIONS", Places.thisKit("Am", bankBEmpty = true, versions = 3))
        assertEquals("NO KEY · BANK B IN USE · 1 VERSION", Places.thisKit(null, bankBEmpty = false, versions = 1))
    }

    @Test
    fun `the SCULPT and STRETCH sections read what is inside, then what GO would render`() {
        assertEquals("CLOUD · SCRUB · SWARM", Places.textureLine(TextureKits.modesFor("SCULPT"), "CLOUD", "8 s", null))
        assertEquals("SLOW · FREEZE", Places.textureLine(TextureKits.modesFor("STRETCH"), "SLOW", "×8", null))
        assertEquals("CLOUD · 8 S · FROM A07", Places.textureLine(TextureKits.modesFor("SCULPT"), "CLOUD", "8 s", "A07"))
        assertEquals("SLOW · ×8 · FROM A01", Places.textureLine(TextureKits.modesFor("STRETCH"), "SLOW", "×8", "A01"))
        for (kind in TextureKits.KINDS) {
            val line = Places.textureLine(TextureKits.modesFor(kind), TextureKits.modesFor(kind).first(), "8 s", null)
            assertFalse("UNTOUCHED" in line, "a closed line says what is inside, never UNTOUCHED: $line")
        }
        assertTrue(Places.KEY_OPEN.endsWith("▾"), "KEY opens a sheet, so it ends in the sheet glyph")
        assertFalse("▸" in Places.KEY_OPEN)
    }

    @Test
    fun `the kit chip keeps the project count in its name, and the sheet marks the active kit in words`() {
        val line = Places.switcher(Project("P", listOf("Kitchen", "Funk Room"), activeKit = "Funk Room"))
        assertEquals("SWITCH KIT · FUNK ROOM · 2 KITS IN THIS PROJECT", Places.switchKitName(line))
        assertEquals("● FUNK ROOM · ACTIVE", Places.kitRow("Funk Room", active = true))
        assertEquals("KITCHEN", Places.kitRow("Kitchen", active = false))
        assertFalse(Places.kitRow("Funk Room", active = true).contains("ON THE GRID", ignoreCase = true), "ON THE GRID is the quantize phrase")
        for (drawn in listOf(Places.ADD_KIT, Places.SHEET_ADD_FROM_SHELF)) {
            assertFalse("▾" in drawn || "▸" in drawn, "a row inside a sheet carries no arrow; the control that opens the sheet has the ▾: " + drawn)
        }
    }

    @Test
    fun `the menus that open as sheets name a thing in capitals and say what it does on a line of its own in sentence case, with no arrow`() {
        val pairs = listOf(
            Places.ARM_MIC to Places.ARM_MIC_DESC,
            Places.ARM_INSIDE to Places.ARM_INSIDE_DESC,
            Places.SHEET_ADD_FROM_SHELF to Places.SHEET_ADD_FROM_SHELF_DESC,
            Places.TOOL_BACKUP to Places.TOOL_BACKUP_DESC,
            Places.TOOL_CHOP_ALL to Places.TOOL_CHOP_ALL_DESC,
            Places.TOOL_XRAY to Places.TOOL_XRAY_DESC,
            Places.TOOL_DOUBLES to Places.TOOL_DOUBLES_DESC,
            Places.PROJECT_RENAME to Places.PROJECT_RENAME_DESC,
            Places.PROJECT_DELETE to Places.PROJECT_DELETE_DESC,
            Places.PROJECT_DROP to Places.PROJECT_DROP_DESC,
        )
        for ((name, description) in pairs) {
            for (line in listOf(name, description)) {
                assertFalse("▸" in line, "the description is a line of its own, not after an arrow: $line")
            }
            assertEquals(name.uppercase(), name)
            assertTrue(description.first().isUpperCase() && description.any { it.isLowerCase() }, "a description is a caption, in sentence case: $description")
        }
        for (title in listOf(Places.ARM_SHEET_TITLE, Places.SHEET_KITS_TITLE, Places.TOOLS_TITLE, Places.STARTER_SHEET_TITLE)) {
            assertEquals(title.uppercase(), title)
        }
    }
}
