package com.snipsnap.shell

import com.snipsnap.kit.MidiWire.Event
import com.snipsnap.shell.MidiBinding.Kind
import com.snipsnap.shell.MidiBinding.Mode
import com.snipsnap.shell.MidiBinding.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeysMidiTest {
    private val fold = "usb:944:1:1:1:def"

    private fun learn(learn: MidiLearn, target: String, event: Event) {
        learn.arm(target, KeysMidi.prefer(target))
        learn.handle(event, fold) { null }
    }

    private fun MidiLearn.step(event: Event, volume: Float = 1f): KeysMidi.Change =
        KeysMidi.changes(handle(event, fold) { if (it == KeysMidi.VOLUME) volume else null }.actions)

    @Test fun `a knob on VOL sets the volume, and a key on OCT + steps once per press`() {
        val learn = MidiLearn()
        learn(learn, KeysMidi.VOLUME, Event.Control(0, 7, 127))
        learn(learn, KeysMidi.OCT_UP, Event.NoteOn(0, 72, 1f))
        learn.handle(Event.NoteOff(0, 72), fold) { null }

        assertEquals(KeysMidi.Change(64 / 127f, 0), learn.step(Event.Control(0, 7, 64)))
        assertEquals(KeysMidi.Change(null, 1), learn.step(Event.NoteOn(0, 72, 0.5f)))
        assertEquals(KeysMidi.Change(null, 0), learn.step(Event.NoteOff(0, 72)), "the release does not step again")
    }

    @Test fun `a key on an octave button is consumed, so it no longer plays its note`() {
        val learn = MidiLearn()
        learn(learn, KeysMidi.OCT_DOWN, Event.NoteOn(0, 48, 1f))
        assertTrue(learn.handle(Event.NoteOn(0, 48, 1f), fold) { null }.consumed)
        assertTrue(learn.handle(Event.NoteOff(0, 48), fold) { null }.consumed)
    }

    @Test fun `an octave button learned from a CC button fires once per press, however long it repeats`() {
        val learn = MidiLearn()
        learn(learn, KeysMidi.OCT_DOWN, Event.Control(0, 20, 127))
        assertEquals(Mode.TRIGGER, learn.bindings.single().mode)
        learn.handle(Event.Control(0, 20, 0), fold) { null }
        assertEquals(-1, learn.step(Event.Control(0, 20, 127)).octaveStep)
        assertEquals(0, learn.step(Event.Control(0, 20, 127)).octaveStep, "a held button's repeat")
        assertEquals(0, learn.step(Event.Control(0, 20, 0)).octaveStep)
        assertEquals(-1, learn.step(Event.Control(0, 20, 127)).octaveStep)
    }

    @Test fun `a knob on VOL jumps from full rather than waiting at the top for pickup`() {
        val learn = MidiLearn()
        learn.arm(KeysMidi.VOLUME)
        val learned = learn.handle(Event.Control(0, 7, 30), fold) { 1f }.learned!!
        val settled = KeysMidi.settle(learned)
        assertEquals(MidiBinding.Takeover.JUMP, settled.takeover)
        // The next visit: loaded uncaught, volume back at full.
        val next = MidiLearn(listOf(settled))
        assertEquals(31 / 127f, next.step(Event.Control(0, 7, 31), volume = 1f).volume!!, 1e-6f)
        val octave = MidiBinding(Source(fold, Kind.NOTE, 0, 60), KeysMidi.OCT_UP, Mode.TRIGGER)
        assertEquals(octave, KeysMidi.settle(octave), "only VOL changes")
    }

    @Test fun `the octave stays inside the grid's range, as the buttons do`() {
        assertEquals(KeysLayout.OCTAVE_MAX, KeysMidi.octave(KeysLayout.OCTAVE_MAX, 1))
        assertEquals(KeysLayout.OCTAVE_MIN, KeysMidi.octave(KeysLayout.OCTAVE_MIN, -1))
        assertEquals(1, KeysMidi.octave(0, 1))
    }

    @Test fun `full volume is the instrument as made, and half way is about twelve dB down`() {
        assertEquals(1f, KeysMidi.gain(1f))
        assertEquals(0f, KeysMidi.gain(0f))
        assertEquals(-12.04, 20 * Math.log10(KeysMidi.gain(0.5f).toDouble()), 0.01)
        assertEquals("VOL 50%", KeysMidi.label(0.5f))
        assertEquals("VOL 100%", KeysMidi.label(1.4f))
    }

    @Test fun `actions for another screen change nothing here`() {
        val change = KeysMidi.changes(listOf(MidiLearn.SetValue(SurfaceMidi.X, 0.2f), MidiLearn.Fire("kit.panic")))
        assertNull(change.volume)
        assertEquals(0, change.octaveStep)
    }

    @Test fun `KEYS and SURFACE keep their bindings side by side under the one key`() {
        val x = MidiBinding(Source(fold, Kind.CC, 0, 74), SurfaceMidi.X, Mode.ABSOLUTE)
        val vol = MidiBinding(Source(fold, Kind.CC, 0, 74), KeysMidi.VOLUME, Mode.ABSOLUTE)
        val surfaceSaved = MidiLearnStore.save(null, SurfaceMidi.SCREEN, listOf(x))
        val bothSaved = MidiLearnStore.save(surfaceSaved, KeysMidi.SCREEN, listOf(vol))
        assertEquals(listOf(x), MidiLearnStore.load(bothSaved, SurfaceMidi.SCREEN))
        assertEquals(listOf(vol), MidiLearnStore.load(bothSaved, KeysMidi.SCREEN))
        assertEquals(emptyList(), MidiLearnStore.load(MidiLearnStore.save(bothSaved, KeysMidi.SCREEN, emptyList()), KeysMidi.SCREEN))
    }
}
