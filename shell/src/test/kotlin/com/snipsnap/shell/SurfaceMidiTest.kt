package com.snipsnap.shell

import com.snipsnap.kit.MidiWire
import com.snipsnap.kit.MidiWire.Event
import com.snipsnap.shell.MidiBinding.Kind
import com.snipsnap.shell.MidiBinding.Mode
import com.snipsnap.shell.MidiBinding.Source
import com.snipsnap.shell.TouchSurface.Reading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurfaceMidiTest {
    private val minilab = "usb:1c75:20b:4:4:abc"

    private fun cc(number: Int, value: Int, channel: Int = 0) = Event.Control(channel, number, value)

    /** A pad with X on CC 74, Y on CC 71 and HOLD on pad note 36, learned the way a player would. */
    private fun learned(): Pair<SurfaceMidi, MidiLearn> {
        val pad = SurfaceMidi()
        val learn = MidiLearn()
        for ((target, event) in listOf(SurfaceMidi.X to cc(74, 64), SurfaceMidi.Y to cc(71, 64), SurfaceMidi.HOLD to Event.NoteOn(9, 36, 1f))) {
            learn.arm(target, SurfaceMidi.prefer(target))
            pad.handle(event, minilab, learn)
        }
        pad.handle(Event.NoteOff(9, 36), minilab, learn)
        return pad to learn
    }

    @Test fun `knobs move the puck and a held key sounds it, as a finger would`() {
        val (pad, learn) = learned()
        assertTrue(pad.handle(cc(74, 127), minilab, learn).moved)
        pad.handle(cc(71, 0), minilab, learn)
        assertFalse(pad.reading(TouchSurface.Mode.XY).touching, "knobs alone move the puck silently")
        assertEquals(1f, pad.reading(TouchSurface.Mode.XY).x, 1e-6f)
        assertEquals(0f, pad.reading(TouchSurface.Mode.XY).y, 1e-6f)

        pad.handle(Event.NoteOn(9, 36, 0.8f), minilab, learn)
        assertTrue(pad.reading(TouchSurface.Mode.XY).touching, "HOLD is the finger down")
        pad.handle(Event.NoteOff(9, 36), minilab, learn)
        assertFalse(pad.reading(TouchSurface.Mode.XY).touching, "and its release lifts it")
    }

    @Test fun `a key learned to HOLD holds rather than fires, and a CC button too`() {
        val (_, learn) = learned()
        assertEquals(Mode.MOMENTARY, learn.bindings.single { it.target == SurfaceMidi.HOLD }.mode)
        learn.arm(SurfaceMidi.HOLD, SurfaceMidi.prefer(SurfaceMidi.HOLD))
        learn.handle(cc(64, 127), minilab) { null }
        assertEquals(Mode.MOMENTARY, learn.bindings.single { it.target == SurfaceMidi.HOLD }.mode)
    }

    @Test fun `a preferred mode the control cannot have falls back to its kind's default`() {
        val learn = MidiLearn()
        learn.arm(SurfaceMidi.HOLD, Mode.MOMENTARY)
        learn.handle(Event.PitchBend(0, 0), minilab) { null }
        assertEquals(Mode.ABSOLUTE, learn.bindings.single().mode, "bend has only ABSOLUTE")
        learn.arm(SurfaceMidi.X)
        learn.handle(Event.NoteOn(0, 60, 1f), minilab) { null }
        assertEquals(Mode.TRIGGER, learn.bindings.single { it.target == SurfaceMidi.X }.mode, "no preference: the kind's default")
    }

    @Test fun `after a finger moves the puck the knobs pick it up rather than jump`() {
        val (pad, learn) = learned()
        pad.handle(cc(74, 127), minilab, learn)
        // A finger drags the puck to the left edge and lifts: the pad reads the centre again.
        pad.followed(Reading.REST, learn)
        assertEquals(0.5f, pad.x, 1e-6f)
        assertFalse(pad.handle(cc(74, 120), minilab, learn).moved, "the knob is far right; the puck stays at the centre")
        assertEquals(0.5f, pad.x, 1e-6f)
        pad.handle(cc(74, 70), minilab, learn)
        assertTrue(pad.handle(cc(74, 60), minilab, learn).moved, "crossing the centre picks it up")
        assertEquals(60 / 127f, pad.x, 1e-6f)
    }

    @Test fun `a panic lets HOLD go and is never consumed`() {
        val (pad, learn) = learned()
        pad.handle(Event.NoteOn(9, 36, 1f), minilab, learn)
        assertTrue(pad.hold)
        assertTrue(pad.handle(cc(MidiWire.CC_ALL_NOTES_OFF, 0), minilab, learn).moved)
        assertFalse(pad.hold)
        assertFalse(pad.handle(cc(MidiWire.CC_ALL_SOUND_OFF, 0), minilab, learn).moved, "nothing held: nothing moved")
        assertTrue(learn.bindings.none { it.source.number == MidiWire.CC_ALL_NOTES_OFF })
    }

    @Test fun `Z reads only in XYZ, and the morph modes carry the corner weights`() {
        val (pad, learn) = learned()
        learn.arm(SurfaceMidi.Z)
        pad.handle(cc(75, 127), minilab, learn)
        assertEquals(1f, pad.reading(TouchSurface.Mode.XYZ).z, 1e-6f)
        assertEquals(0f, pad.reading(TouchSurface.Mode.XY).z, 1e-6f)
        pad.handle(cc(74, 0), minilab, learn) // caught at learn: the knob moves the puck at once
        pad.handle(cc(71, 127), minilab, learn)
        val morph = pad.reading(TouchSurface.Mode.MORPH)
        assertEquals(1f, morph.a, 1e-6f, "top-left is all A")
        assertEquals(TouchSurface.morphWeights(0f, 1f), listOf(morph.a, morph.b, morph.c, morph.d))
    }

    @Test fun `the learn panel names each kind of control in the keyboard's own counting`() {
        assertEquals("CC 74 CH 1", MidiLearn.describe(Source(null, Kind.CC, 0, 74)))
        assertEquals("NOTE C2 CH 10", MidiLearn.describe(Source(null, Kind.NOTE, 9, 36)))
        assertEquals("BEND CH 2", MidiLearn.describe(Source(null, Kind.PITCH, 1, 0)))
        assertEquals("PROGRAM 1 CH 1", MidiLearn.describe(Source(null, Kind.PROGRAM, 0, 0)))
        assertEquals("CC 74 CH 1  64", MidiLearn.heard(cc(74, 64)))
        assertEquals("NOTE C2 CH 10  OFF", MidiLearn.heard(Event.NoteOff(9, 36)))
        assertNull(MidiLearn.heard(Event.Clock(0L)))
    }

    @Test fun `one screen saving its bindings keeps every other screen's`() {
        val keys = MidiBinding(Source(minilab, Kind.CC, 0, 7), "keys.gain", Mode.ABSOLUTE)
        val oldX = MidiBinding(Source(minilab, Kind.CC, 0, 74), SurfaceMidi.X, Mode.ABSOLUTE)
        val newX = MidiBinding(Source(minilab, Kind.CC, 0, 7), SurfaceMidi.X, Mode.ABSOLUTE)
        val all = listOf(keys, oldX)
        assertEquals(listOf(oldX), MidiLearnStore.of(SurfaceMidi.SCREEN, all))
        val saved = MidiLearnStore.replace(all, SurfaceMidi.SCREEN, listOf(newX))
        assertEquals(listOf(keys, newX), saved, "the same knob may drive one thing on each screen")
        assertEquals(saved, MidiLearnStore.read(MidiLearnStore.write(saved)))
        assertFailsWith<IllegalArgumentException> { MidiLearnStore.replace(all, SurfaceMidi.SCREEN, listOf(keys)) }
        assertTrue(MidiLearnStore.of("surf", listOf(oldX)).isEmpty(), "a screen's name is a whole word, not a prefix")
    }
}
