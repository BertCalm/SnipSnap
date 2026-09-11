package com.snipsnap.mpc3

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The one pad-to-note map, pinned against the writer's own `padNoteMap`.
 *
 * These two arithmetics have to agree exactly or an exported clip plays
 * the wrong instrument: the clip says a note, the program's `padNoteMap`
 * says which pad that note belongs to, and the hardware believes the
 * program. Nothing else in the build checks that they match, which is how
 * a clamp sat in one of them.
 */
class PadNoteMapTest {

    /**
     * The map the writer actually serialises, read back out of a rendered
     * payload — not a restatement of its formula. A copied oracle would
     * pass while the two drifted apart, which is the failure this test
     * exists to prevent.
     */
    private fun writerPadNoteMap(): Map<String, JsonValue> {
        val payload = (Json.parse(Mpc3TrackWriter().payloadText(DrumProgram("Map", listOf(Pad("S", 1_000L))))) as JsonValue.Obj).entries
        val data = (payload["data"] as JsonValue.Obj).entries
        val prog = (data["program"] as JsonValue.Obj).entries
        return ((prog["padNoteMap"] as JsonValue.Obj).entries["noteForPad"] as JsonValue.Obj).entries
    }

    @Test
    fun `every pad has its own note, and no two pads share one`() {
        val notes = (1..Mpc3Note.PAD_SLOTS).map { Mpc3Note.noteFor(it) }
        assertEquals(Mpc3Note.PAD_SLOTS, notes.size)
        assertEquals(Mpc3Note.PAD_SLOTS, notes.toSet().size, "a shared note means one pad silently plays another's hits")
        assertEquals((0..127).toSet(), notes.toSet(), "128 pads fill the 128 notes exactly")
    }

    @Test
    fun `the map is the writer's own, pad for pad, as serialised`() {
        val written = writerPadNoteMap()
        assertEquals(Mpc3Note.PAD_SLOTS, written.size)
        for (slot in 1..Mpc3Note.PAD_SLOTS) {
            assertEquals(
                Mpc3Note.noteFor(slot),
                written["value${slot - 1}"]!!.int(),
                "slot $slot disagrees with the padNoteMap the writer emits, so the MPC would play a different pad",
            )
        }
    }

    @Test
    fun `it wraps where the writer wraps, and that is where a clamp used to lose pads`() {
        assertEquals(36, Mpc3Note.noteFor(1))
        assertEquals(127, Mpc3Note.noteFor(92))
        assertEquals(0, Mpc3Note.noteFor(93))
        assertEquals(35, Mpc3Note.noteFor(Mpc3Note.PAD_SLOTS))

        // What the clamp did: everything from 92 up onto one note. The
        // failure was silent twice over - the notes deduped against each
        // other, and whatever survived addressed pad 92 on the hardware.
        val clamped = (92..Mpc3Note.PAD_SLOTS).map { (35 + it).coerceIn(0, 127) }
        assertEquals(1, clamped.toSet().size, "the old clamp put 37 pads on one note")
        assertTrue(
            (92..Mpc3Note.PAD_SLOTS).map { Mpc3Note.noteFor(it) }.toSet().size == clamped.size,
            "the wrap keeps them all apart",
        )
    }

    @Test
    fun `slots below the wrap are untouched, so every clip written before still reads the same`() {
        for (slot in 1..91) {
            assertEquals(35 + slot, Mpc3Note.noteFor(slot), "slot $slot must keep the note it always had")
        }
    }

    @Test
    fun `a note says which pad plays it, and round-trips`() {
        for (slot in 1..Mpc3Note.PAD_SLOTS) {
            assertEquals(slot, Mpc3Note.slotFor(Mpc3Note.noteFor(slot)), "slot $slot did not survive the round trip")
        }
        for (note in 0..127) {
            assertEquals(note, Mpc3Note.noteFor(Mpc3Note.slotFor(note)), "note $note did not survive the round trip")
        }
        // The inverse a caller is tempted to write by hand is right only
        // above the wrap; KitPreview had this one and rendered nothing for
        // the pads below it.
        assertEquals(1, Mpc3Note.slotFor(36))
        assertEquals(93, Mpc3Note.slotFor(0))
        assertTrue(0 - 36 + 1 < 1, "the hand-written inverse gives pad 93 a slot of -35")
    }

    @Test
    fun `a pad no program can hold is refused, not folded into one that exists`() {
        assertFailsWith<IllegalArgumentException> { Mpc3Note.noteFor(0) }
        assertFailsWith<IllegalArgumentException> { Mpc3Note.noteFor(Mpc3Note.PAD_SLOTS + 1) }
        assertFailsWith<IllegalArgumentException> { Mpc3Note.slotFor(-1) }
        assertFailsWith<IllegalArgumentException> { Mpc3Note.slotFor(128) }
    }
}
