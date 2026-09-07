package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalityTest {

    @Test
    fun `OFF is respected everywhere without argument`() {
        assertFalse(Delight.toastsEnabled(Personality.OFF))
        assertFalse(Delight.quipsEnabled(Personality.OFF))
        assertFalse(Delight.deckSoundsEnabled(Personality.OFF, captureArmed = false))
    }

    @Test
    fun `quips need FULL, toasts settle for MILD`() {
        assertTrue(Delight.toastsEnabled(Personality.MILD))
        assertFalse(Delight.quipsEnabled(Personality.MILD))
        assertTrue(Delight.toastsEnabled(Personality.FULL))
        assertTrue(Delight.quipsEnabled(Personality.FULL))
    }

    @Test
    fun `law 2 - deck sounds hard-mute while capture is armed, at any level`() {
        for (level in Personality.entries) {
            assertFalse(
                Delight.deckSoundsEnabled(level, captureArmed = true),
                "$level: a deck thunk must never land inside a snip",
            )
        }
        assertTrue(Delight.deckSoundsEnabled(Personality.FULL, captureArmed = false))
    }

    @Test
    fun `commit lines rotate in order and wrap`() {
        assertEquals("TAPED. NO TAKEBACKS.", Copy.rotating(Copy.COMMIT_LINES, 0))
        assertEquals("IT'S OURS NOW.", Copy.rotating(Copy.COMMIT_LINES, 1))
        assertEquals(
            Copy.rotating(Copy.COMMIT_LINES, 0),
            Copy.rotating(Copy.COMMIT_LINES, Copy.COMMIT_LINES.size),
        )
    }

    @Test
    fun `law 3 - funny copy still says exactly what happened`() {
        // The capture-blocked box names the problem and the way out.
        assertTrue("SCREEN RECORDER" in Copy.CAPTURE_BLOCKED)
        // A session the phone ended names the two ways that happens.
        assertTrue("LOCK SCREEN" in Copy.PHONE_STOPPED_TAPE)
        assertTrue("STOP CHIP" in Copy.PHONE_STOPPED_TAPE)
        // A refused consent must not claim anything is armed.
        assertTrue("NOTHING ARMED" in Copy.INSIDE_REFUSED)
        // The Ear reports what it heard, in real numbers.
        assertEquals("HEARD 12 HITS OVER 2 BARS AT ~93 BPM. THEY PLAY ON YOUR PADS NOW.", Copy.grooveRead(12, 2, 93))
        assertEquals("HEARD 4 HITS OVER 1 BAR AT ~120 BPM. THEY PLAY ON YOUR PADS NOW.", Copy.grooveRead(4, 1, 120))
        assertEquals("NO GROOVE: NO BEAT HEARD - THE EAR FINDS HITS, NOT TONES.", Copy.grooveRefused("no beat heard - the ear finds hits, not tones."))
        assertEquals("BREAK FOUND AT 1:12-1:20. IN AND OUT ARE SET. INSTANT KIT IS ONE TAP AWAY.", Copy.dug("1:12", "1:20"))
        // Send-to-grid reports the real slice count.
        assertEquals("7 SLICES ON THE GRID. CHOKE GROUP SET.", Copy.sentToGrid(7, chokeSet = true))
        assertEquals("3 SLICES ON THE GRID.", Copy.sentToGrid(3, chokeSet = false))
    }

    @Test
    fun `hidden eggs answer only their triggers`() {
        assertEquals("VERY CREATIVE.", Copy.kitNameResponse("TEST"))
        assertEquals("VERY CREATIVE.", Copy.kitNameResponse("  test "))
        assertNull(Copy.kitNameResponse("Regroove"))
        assertEquals("ELITE.", Copy.bpmResponse(133.7f))
        assertNull(Copy.bpmResponse(120f))
        // Konami: 8 steps, all on the 4x4 grid.
        assertEquals(8, Copy.KONAMI_PADS.size)
        assertTrue(Copy.KONAMI_PADS.all { it in 1..16 })
    }

    @Test
    fun `boot sequence ends ready`() {
        assertEquals("READY.", Copy.BOOT_LINES.last())
        assertTrue(Copy.STATUS_QUIPS.isNotEmpty())
    }

    @Test
    fun `every new toast shouts and stops`() {
        val lines = listOf(
            Copy.MELODIC_ON, Copy.KEY_OFF, Copy.TEACHING_ON, Copy.TEACHING_OFF,
            Copy.BANK_B_LIT, Copy.TWINS_REROLLED, Copy.BACK_FROM_BIN, Copy.BIN_EMPTIED,
            Copy.HUMANIZED, Copy.FORKED_TO_E, Copy.BAR_WIPED, Copy.GHOSTS_ON,
            Copy.INSTRUMENT_MADE, Copy.NO_PITCH, Copy.RETREAT_REFUSED,
            Copy.TAKES_BIN_RULE, Copy.TEACH_CONSENT, Copy.SNAPPED,
            Copy.TAKES_EMPTY, Copy.BIN_EMPTY_STATE, Copy.BIN_ITEM_GONE, Copy.KIT_WONT_OPEN,
            Copy.UNMUTATED, Copy.MUTATE_NEEDS_ONE, Copy.CRATE_EMPTY,
            Copy.SCULPTED, Copy.STRETCHED, Copy.FROZEN, Copy.PAD_MADE, Copy.PAD_TOO_SHORT, Copy.PAD_TOO_LONG,
            Copy.IN_KEY_NONE, Copy.IN_KEY_NEEDS_KEY,
        )
        for (line in lines) {
            assertEquals(line.uppercase(), line, "TapeOS shouts: '$line'")
            assertTrue(line.endsWith("."), "every line lands on a full stop: '$line'")
        }
    }

    @Test
    fun `the interpolated lines name what they acted on`() {
        assertTrue(Copy.keySet("Am").startsWith("Am SET."), "the key leads its own toast")
        assertTrue(Copy.keySet("Am").endsWith("."), "and still lands on a full stop")
        assertEquals("1 PAD RETUNED INTO A MINOR. THE KICK IS UNTOUCHED.", Copy.inKey(1, "A MINOR"))
        assertEquals("ONE TAP. 8 SLICES ON THE GRID. CHOKE GROUP SET.", Copy.instantKit(8, true))
        assertEquals("ONE TAP. 5 SLICES ON THE GRID.", Copy.instantKit(5, false))
        assertEquals("3 PADS RETUNED INTO A MINOR. THE KICK IS UNTOUCHED.", Copy.inKey(3, "A MINOR"))
        assertTrue(Copy.takeRestored("T3").startsWith("T3 RESTORED."), "the take leads its own toast")
        assertTrue(
            Copy.treated("CRUSH", "A02").startsWith("CRUSH ON A02."),
            "the treatment and the pad both lead their own toast",
        )
        assertTrue(Copy.treated("CRUSH", "A02").endsWith("."), "and still lands on a full stop")
        assertEquals("TUNE ON A02, IN C MAJOR. ORIGINAL SLEEPS IN THE BIN.", Copy.keyed("TUNE", "A02", "C MAJOR"))
        assertEquals("A02 DRIFTED TOWARD Other:B03. ORIGINAL SLEEPS IN THE BIN.", Copy.drifted("A02", "Other:B03"))
        assertEquals("A03 IS A HAT CLOSED PATCH NOW, 0.12 AWAY. ORIGINAL SLEEPS IN THE BIN.", Copy.desampled("A03", "HAT_CLOSED", 0.123f))
        assertEquals("NO PATCH IS NEAR. THE CLOSEST IS A SNARE, 0.61 AWAY.", Copy.desampleFar("SNARE", 0.61f))
        assertEquals("NOT A NOTE: A KICK IS A DRUM, NOT A NOTE.", Copy.notANote("a kick is a drum, not a note"))
        assertTrue(
            Copy.mutated("SPLICE", "A01", "A03").startsWith("SPLICE: A01 × A03."),
            "the move and both parents lead their own toast",
        )
        assertTrue(Copy.mutated("SPLICE", "A01", "A03").endsWith("."), "and still lands on a full stop")
        assertEquals("+3 OFF-LANE — HEARD AND EXPORTED, NOT DRAWN", Copy.offLane(3), "the count leads its own line")
        assertTrue(Copy.offLane(1).uppercase() == Copy.offLane(1), "TapeOS shouts here too")
    }
}
