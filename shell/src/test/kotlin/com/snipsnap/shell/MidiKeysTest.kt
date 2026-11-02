package com.snipsnap.shell

import com.snipsnap.kit.MidiWire
import com.snipsnap.kit.MidiWire.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MidiKeysTest {

    /** Every call the instrument saw, in order, as words. */
    private class Log : MidiKeys.Voice {
        val calls = mutableListOf<String>()
        override fun noteOn(note: Int, velocity: Float) { calls += "on $note ${"%.3f".format(java.util.Locale.ROOT, velocity)}" }
        override fun noteOff(note: Int) { calls += "off $note" }
        override fun allOff() { calls += "all off" }
    }

    private fun pedal(down: Boolean) = Event.Control(0, MidiWire.CC_SUSTAIN, if (down) 127 else 0)

    @Test
    fun `a key down is a note on at the keyboard's own velocity and a key up is a note off`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(Event.NoteOn(0, 60, 0.5f))
        keys.handle(Event.NoteOff(0, 60))
        assertEquals(listOf("on 60 0.500", "off 60"), log.calls)
    }

    @Test
    fun `under the pedal a key up holds, and the pedal rising lets every held note go`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(pedal(true))
        assertTrue(keys.sustaining)
        keys.handle(Event.NoteOn(0, 60, 1f))
        keys.handle(Event.NoteOn(0, 64, 1f))
        keys.handle(Event.NoteOff(0, 60))
        keys.handle(Event.NoteOff(0, 64))
        assertEquals(listOf("on 60 1.000", "on 64 1.000"), log.calls, "nothing let go while the pedal is down")
        assertEquals(setOf(60, 64), keys.heldNotes)
        keys.handle(pedal(false))
        assertFalse(keys.sustaining)
        assertEquals(listOf("on 60 1.000", "on 64 1.000", "off 60", "off 64"), log.calls)
        assertTrue(keys.heldNotes.isEmpty())
    }

    @Test
    fun `clock and start reach no voice`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(Event.Clock())
        keys.handle(Event.Start())
        assertEquals(emptyList(), log.calls)
    }

    @Test
    fun `the switch threshold is the spec's 64, and a repeated pedal value changes nothing`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(Event.Control(0, MidiWire.CC_SUSTAIN, 63))
        assertFalse(keys.sustaining)
        keys.handle(Event.Control(0, MidiWire.CC_SUSTAIN, 64))
        assertTrue(keys.sustaining)
        keys.handle(Event.NoteOn(0, 60, 1f))
        keys.handle(Event.NoteOff(0, 60))
        keys.handle(Event.Control(0, MidiWire.CC_SUSTAIN, 100))
        assertEquals(setOf(60), keys.heldNotes, "a second 'down' does not release what the first is holding")
    }

    @Test
    fun `a held note struck again is live again, and its next key up under the pedal holds it afresh`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(pedal(true))
        keys.handle(Event.NoteOn(0, 60, 1f))
        keys.handle(Event.NoteOff(0, 60))
        keys.handle(Event.NoteOn(0, 60, 0.5f))
        assertTrue(keys.heldNotes.isEmpty(), "restruck: no longer waiting on the pedal")
        keys.handle(pedal(false))
        assertEquals(listOf("on 60 1.000", "on 60 0.500"), log.calls, "the pedal rising has nothing held to let go")
        keys.handle(Event.NoteOff(0, 60))
        assertEquals("off 60", log.calls.last())
    }

    @Test
    fun `the two panics silence everything and forget the pedal`() {
        for (cc in listOf(MidiWire.CC_ALL_NOTES_OFF, MidiWire.CC_ALL_SOUND_OFF)) {
            val log = Log()
            val keys = MidiKeys(log)
            keys.handle(pedal(true))
            keys.handle(Event.NoteOn(0, 60, 1f))
            keys.handle(Event.NoteOff(0, 60))
            keys.handle(Event.Control(0, cc, 0))
            assertEquals("all off", log.calls.last(), "CC $cc")
            assertFalse(keys.sustaining, "CC $cc clears the pedal")
            assertTrue(keys.heldNotes.isEmpty(), "CC $cc clears the held set")
            // The pedal is now up, so a later key up lets go at once.
            keys.handle(Event.NoteOn(0, 62, 1f))
            keys.handle(Event.NoteOff(0, 62))
            assertEquals("off 62", log.calls.last())
        }
    }

    @Test
    fun `a controller nothing here plays is ignored, and every channel is heard`() {
        val log = Log()
        val keys = MidiKeys(log)
        keys.handle(Event.Control(0, 1, 127))
        keys.handle(Event.Control(0, 7, 0))
        keys.handle(Event.NoteOn(9, 36, 1f))
        keys.handle(Event.NoteOn(15, 48, 1f))
        assertEquals(listOf("on 36 1.000", "on 48 1.000"), log.calls)
    }
    /** The bends the instrument was asked for, in order. */
    private class Bends : MidiKeys.Voice {
        val ratios = mutableListOf<Double>()
        override fun noteOn(note: Int, velocity: Float) {}
        override fun noteOff(note: Int) {}
        override fun allOff() {}
        override fun bend(ratio: Double) { ratios += ratio }
    }

    @Test
    fun `the wheel's ends are exactly two semitones away and its centre is the note`() {
        val up = Math.pow(2.0, 2.0 / 12.0)
        assertEquals(up, MidiKeys.bendRatio(8191), 1e-12)
        assertEquals(1 / up, MidiKeys.bendRatio(-8192), 1e-12)
        assertEquals(1.0, MidiKeys.bendRatio(0))
        assertEquals(Math.pow(2.0, 1.0 / 12.0), MidiKeys.bendRatio(8191 / 2, 2), 1e-4, "half way up is a semitone")
        assertEquals(up, MidiKeys.bendRatio(100_000), 1e-12, "a value off the wire's range is its end")
        assertEquals(Math.pow(2.0, 12.0 / 12.0), MidiKeys.bendRatio(8191, 12), 1e-12)
    }

    @Test
    fun `bend reaches the instrument, a repeat is not sent twice, and the panic puts it back`() {
        val bends = Bends()
        val keys = MidiKeys(bends)
        keys.handle(Event.PitchBend(0, 8191))
        keys.handle(Event.PitchBend(0, 8191))
        assertEquals(listOf(MidiKeys.bendRatio(8191)), bends.ratios, "a strip held still sends the same value again")
        assertEquals(MidiKeys.bendRatio(8191), keys.bend)
        keys.handle(Event.Control(0, MidiWire.CC_ALL_NOTES_OFF, 0))
        assertEquals(listOf(MidiKeys.bendRatio(8191), 1.0), bends.ratios, "a cable pulled mid-bend never sends the return")
        keys.allOff()
        assertEquals(2, bends.ratios.size, "already at rest: nothing more to send")
    }
    private class Wheels : MidiKeys.Voice {
        val bends = mutableListOf<Double>()
        val vibratos = mutableListOf<Float>()
        override fun noteOn(note: Int, velocity: Float) {}
        override fun noteOff(note: Int) {}
        override fun allOff() {}
        override fun bend(ratio: Double) { bends += ratio }
        override fun vibrato(semitones: Float) { vibratos += semitones }
    }

    @Test
    fun `the mod wheel is a vibrato up to half a semitone, a repeat is not sent twice, and the panic stills it`() {
        val wheels = Wheels()
        val keys = MidiKeys(wheels)
        keys.handle(Event.Control(0, MidiWire.CC_MOD, 127))
        keys.handle(Event.Control(0, MidiWire.CC_MOD, 127))
        keys.handle(Event.Control(0, MidiWire.CC_MOD, 0))
        assertEquals(listOf(0.5f, 0f), wheels.vibratos)
        assertEquals(0.5f * 64 / 127f, MidiKeys.vibratoDepth(64), 1e-6f)
        assertEquals(0.5f, MidiKeys.vibratoDepth(500), 1e-6f, "off the wire's range is the top")
        keys.handle(Event.Control(0, MidiWire.CC_MOD, 127))
        keys.handle(Event.Control(0, MidiWire.CC_ALL_SOUND_OFF, 0))
        assertEquals(listOf(0.5f, 0f, 0.5f, 0f), wheels.vibratos, "a cable pulled with the wheel up never sends its return")
    }

    @Test
    fun `changing the bend range re-bends a held wheel, and the ranges cycle`() {
        val wheels = Wheels()
        val keys = MidiKeys(wheels)
        keys.handle(Event.PitchBend(0, 8191))
        keys.bendRange = 12
        assertEquals(listOf(MidiKeys.bendRatio(8191, 2), 2.0), wheels.bends, "the wheel is at the top: an octave now")
        keys.bendRange = 12
        assertEquals(2, wheels.bends.size)
        keys.handle(Event.PitchBend(0, -8192))
        assertEquals(0.5, wheels.bends.last(), 1e-12)
        assertEquals(listOf(2, 5, 12, 1, 2), listOf(1, 2, 5, 12, 1).map(MidiKeys::nextBendRange))
        assertEquals(MidiKeys.BEND_RANGE_SEMITONES, MidiKeys.nextBendRange(7), "a range not on the list starts again at the default")
    }
}
