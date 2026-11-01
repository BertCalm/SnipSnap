package com.snipsnap.shell

import com.snipsnap.kit.MidiWire
import com.snipsnap.kit.MidiWire.Event
import com.snipsnap.shell.MidiBinding.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KitMidiTest {
    private val minilab = "usb:1c75:20b:4:4:abc"

    private fun learn(learn: MidiLearn, target: String, event: Event) {
        learn.arm(target, KitMidi.prefer(target))
        learn.handle(event, minilab) { null }
    }

    private fun MidiLearn.change(event: Event): KitMidi.Change = KitMidi.changes(handle(event, minilab) { null }.actions)

    @Test fun `a pad on MAP steps the map once per hit and a pad on PANIC panics`() {
        val learn = MidiLearn()
        learn(learn, KitMidi.MAP, Event.NoteOn(9, 44, 1f))
        learn.handle(Event.NoteOff(9, 44), minilab) { null }
        learn(learn, KitMidi.PANIC, Event.NoteOn(9, 45, 1f))
        learn.handle(Event.NoteOff(9, 45), minilab) { null }

        assertEquals(KitMidi.Change(1, false), learn.change(Event.NoteOn(9, 44, 0.7f)))
        assertEquals(KitMidi.Change(0, false), learn.change(Event.NoteOff(9, 44)), "the release does not step again")
        assertEquals(KitMidi.Change(0, true), learn.change(Event.NoteOn(9, 45, 0.7f)))
    }

    @Test fun `a learned pad is consumed, so it no longer plays a pad as well`() {
        val learn = MidiLearn()
        learn(learn, KitMidi.PANIC, Event.NoteOn(9, 45, 1f))
        assertTrue(learn.handle(Event.NoteOn(9, 45, 1f), minilab) { null }.consumed)
        assertTrue(learn.handle(Event.NoteOff(9, 45), minilab) { null }.consumed)
        assertFalse(learn.handle(Event.NoteOn(9, 36, 1f), minilab) { null }.consumed, "pads not learned still play")
    }

    @Test fun `a CC button fires once per press, however long it repeats`() {
        val learn = MidiLearn()
        learn(learn, KitMidi.MAP, Event.Control(0, 22, 127))
        assertEquals(Mode.TRIGGER, learn.bindings.single().mode)
        learn.handle(Event.Control(0, 22, 0), minilab) { null }
        assertEquals(1, learn.change(Event.Control(0, 22, 127)).mapSteps)
        assertEquals(0, learn.change(Event.Control(0, 22, 127)).mapSteps, "a held button's repeat")
    }

    @Test fun `the keyboard's own panics are never learned, so they still reach the pads`() {
        val learn = MidiLearn()
        learn.arm(KitMidi.PANIC, KitMidi.prefer(KitMidi.PANIC))
        val r = learn.handle(Event.Control(0, MidiWire.CC_ALL_NOTES_OFF, 0), minilab) { null }
        assertFalse(r.consumed)
        assertTrue(learn.bindings.isEmpty())
    }

    @Test fun `actions for another screen change nothing here`() {
        assertEquals(KitMidi.Change(0, false), KitMidi.changes(listOf(MidiLearn.Fire(KeysMidi.OCT_UP), MidiLearn.SetValue(SurfaceMidi.X, 1f))))
    }
}
