package com.snipsnap.shell

import com.snipsnap.kit.MidiWire
import com.snipsnap.kit.MidiWire.Event
import com.snipsnap.xpm.PadNoteMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MidiPadsTest {

    @Test
    fun `the MPC map is PadNoteMap backwards - the kick note is A02, as on the hardware`() {
        assertEquals(2, MidiPads.slotForNote(36, MidiPads.Map.MPC), "note 36, the kick, is pad A02")
        assertEquals(1, MidiPads.slotForNote(37, MidiPads.Map.MPC))
        assertEquals(6, MidiPads.slotForNote(38, MidiPads.Map.MPC), "the snare")
        assertEquals(3, MidiPads.slotForNote(42, MidiPads.Map.MPC), "the closed hat")
        assertEquals(17, MidiPads.slotForNote(54, MidiPads.Map.MPC), "bank B's first pad")
        // Every pad is reachable from exactly the note the MPC assigns it.
        for (pad in 1..PadNoteMap.PAD_COUNT) {
            assertEquals(pad, MidiPads.slotForNote(PadNoteMap.noteForPad(pad), MidiPads.Map.MPC), "pad $pad")
        }
    }

    @Test
    fun `in order, C1 is A01 and the slots climb a semitone a pad through every bank`() {
        assertEquals(1, MidiPads.slotForNote(36, MidiPads.Map.IN_ORDER))
        assertEquals(16, MidiPads.slotForNote(51, MidiPads.Map.IN_ORDER), "A16")
        assertEquals(17, MidiPads.slotForNote(52, MidiPads.Map.IN_ORDER), "B01")
        assertEquals(92, MidiPads.slotForNote(127, MidiPads.Map.IN_ORDER), "the top of the keyboard still lands")
        assertNull(MidiPads.slotForNote(35, MidiPads.Map.IN_ORDER), "below A01 is nothing, not A00")
    }

    @Test
    fun `a note outside MIDI is nowhere under either map`() {
        for (map in MidiPads.Map.entries) {
            assertNull(MidiPads.slotForNote(-1, map))
            assertNull(MidiPads.slotForNote(128, map))
        }
    }

    @Test
    fun `the chip toggles between the two maps and each has words`() {
        assertEquals(MidiPads.Map.IN_ORDER, MidiPads.Map.MPC.next())
        assertEquals(MidiPads.Map.MPC, MidiPads.Map.IN_ORDER.next())
        assertEquals(listOf("MPC NOTES", "A01 = C1"), MidiPads.Map.entries.map { it.label })
    }

    @Test
    fun `events route to hit, release and panic, and a note no pad answers to goes nowhere`() {
        val calls = mutableListOf<String>()
        fun route(event: Event, map: MidiPads.Map = MidiPads.Map.IN_ORDER) = MidiPads.handle(
            event, map,
            onHit = { slot, v -> calls += "hit $slot ${"%.2f".format(java.util.Locale.ROOT, v)}" },
            onRelease = { slot -> calls += "release $slot" },
            onPanic = { calls += "panic" },
        )
        route(Event.NoteOn(0, 36, 0.75f))
        route(Event.NoteOff(0, 36))
        route(Event.NoteOn(0, 36, 1f), MidiPads.Map.MPC)
        route(Event.NoteOn(0, 20, 1f))
        route(Event.Control(0, MidiWire.CC_SUSTAIN, 127))
        route(Event.Control(0, MidiWire.CC_ALL_NOTES_OFF, 0))
        route(Event.Control(0, MidiWire.CC_ALL_SOUND_OFF, 0))
        assertEquals(listOf("hit 1 0.75", "release 1", "hit 2 1.00", "panic", "panic"), calls)
    }

    @Test
    fun `clock and start fire no pad callback`() {
        val calls = mutableListOf<String>()
        for (event in listOf<Event>(Event.Clock(), Event.Start())) {
            MidiPads.handle(
                event, MidiPads.Map.IN_ORDER,
                onHit = { slot, _ -> calls += "hit $slot" },
                onRelease = { slot -> calls += "release $slot" },
                onPanic = { calls += "panic" },
            )
        }
        assertEquals(emptyList(), calls)
    }
}
