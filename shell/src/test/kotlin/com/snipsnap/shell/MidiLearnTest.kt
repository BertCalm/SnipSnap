package com.snipsnap.shell

import com.snipsnap.kit.MidiWire
import com.snipsnap.kit.MidiWire.Event
import com.snipsnap.shell.MidiBinding.Kind
import com.snipsnap.shell.MidiBinding.Mode
import com.snipsnap.shell.MidiBinding.Source
import com.snipsnap.shell.MidiBinding.Takeover
import com.snipsnap.shell.MidiLearn.Fire
import com.snipsnap.shell.MidiLearn.SetValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MidiLearnTest {
    private val minilab = "usb:1c75:20b:4:4:abc"
    private val fold = "usb:944:1:1:1:def"

    /** A parameter store the tests read and write, as a screen would. */
    private val values = HashMap<String, Float>()
    private fun MidiLearn.feed(event: Event, device: String? = minilab): MidiLearn.Result =
        handle(event, device) { values[it] }.also { r -> r.actions.filterIsInstance<SetValue>().forEach { values[it.target] = it.value } }

    private fun cc(number: Int, value: Int, channel: Int = 0) = Event.Control(channel, number, value)

    @Test fun `arming then moving a knob binds it, applies its value and consumes the message`() {
        val learn = MidiLearn()
        learn.arm("surface.grain")
        val r = learn.feed(cc(74, 64))
        assertEquals(Source(minilab, Kind.CC, 0, 74), r.learned!!.source)
        assertEquals(Mode.ABSOLUTE, r.learned!!.mode)
        assertTrue(r.consumed)
        assertEquals(64 / 127f, values["surface.grain"]!!, 1e-6f, "the knob the player just turned is live at once")
        assertNull(learn.armed, "one learn per arm")
        learn.feed(cc(74, 127))
        assertEquals(1f, values["surface.grain"]!!, 1e-6f)
    }

    @Test fun `a learned note defaults to a trigger and fires on its press only`() {
        val learn = MidiLearn()
        learn.arm("kit.panic")
        assertTrue(learn.feed(Event.NoteOn(9, 36, 1f)).actions.isEmpty(), "the press that learns does not also fire")
        assertEquals(listOf(Fire("kit.panic")), learn.feed(Event.NoteOn(9, 36, 0.5f)).actions)
        val off = learn.feed(Event.NoteOff(9, 36))
        assertTrue(off.actions.isEmpty())
        assertTrue(off.consumed, "the bound pad's note-off must not reach the pads either")
    }

    @Test fun `one to one - a control learned again moves, and a target relearned drops its old control`() {
        val learn = MidiLearn()
        learn.arm("a"); learn.feed(cc(20, 10))
        learn.arm("b"); learn.feed(cc(20, 10))
        assertEquals(listOf("b"), learn.bindings.map { it.target }, "CC 20 now drives b only")
        learn.arm("b"); learn.feed(cc(21, 10))
        assertEquals(listOf(Source(minilab, Kind.CC, 0, 21)), learn.bindings.map { it.source }, "b keeps one control")
    }

    @Test fun `the panics are never learned and never consumed`() {
        val learn = MidiLearn()
        learn.arm("x")
        val r = learn.feed(cc(MidiWire.CC_ALL_NOTES_OFF, 0))
        assertFalse(r.consumed)
        assertEquals("x", learn.armed, "still waiting for a real control")
        assertTrue(learn.bindings.isEmpty())
        assertFalse(learn.feed(cc(MidiWire.CC_ALL_SOUND_OFF, 0)).consumed)
    }

    @Test fun `an unbound message is left for the screen`() {
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 74), "t", Mode.ABSOLUTE)))
        assertEquals(MidiLearn.Result.NONE, learn.feed(cc(75, 1)))
        assertEquals(MidiLearn.Result.NONE, learn.feed(Event.NoteOn(0, 60, 1f)))
        assertEquals(MidiLearn.Result.NONE, learn.feed(Event.Clock()))
    }

    @Test fun `pickup - a loaded binding waits until the knob passes the parameter, then follows`() {
        values["t"] = 0.5f
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 1), "t", Mode.ABSOLUTE, Takeover.PICKUP)))
        learn.feed(cc(1, 10))
        learn.feed(cc(1, 40))
        assertEquals(0.5f, values["t"], "below the parameter and not yet across it: nothing moves")
        assertTrue(learn.feed(cc(1, 50)).consumed, "a waiting knob is still bound, so its messages are consumed")
        learn.feed(cc(1, 70))
        assertEquals(70 / 127f, values["t"]!!, 1e-6f, "crossing 0.5 between 50 and 70 picks it up")
        learn.feed(cc(1, 20))
        assertEquals(20 / 127f, values["t"]!!, 1e-6f, "caught: it follows from now on")
    }

    @Test fun `pickup - landing within a step of the parameter catches it without crossing`() {
        values["t"] = 64 / 127f
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 1), "t", Mode.ABSOLUTE)))
        learn.feed(cc(1, 65))
        assertEquals(65 / 127f, values["t"]!!, 1e-6f)
    }

    @Test fun `uncatch makes a caught knob pick up again`() {
        values["t"] = 0f
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 1), "t", Mode.ABSOLUTE)))
        learn.feed(cc(1, 0))
        learn.feed(cc(1, 10))
        assertEquals(10 / 127f, values["t"]!!, 1e-6f)
        values["t"] = 0.9f // a finger moved it on screen
        learn.uncatch("t")
        learn.feed(cc(1, 20))
        assertEquals(0.9f, values["t"], "the knob is far below the finger's value now: it waits")
        learn.uncatchAll()
        learn.feed(cc(1, 127))
        assertEquals(1f, values["t"]!!, 1e-6f, "crossing 0.9 between 20 and 127 catches it")
    }

    @Test fun `jump takeover follows at once`() {
        values["t"] = 0.9f
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 1), "t", Mode.ABSOLUTE, Takeover.JUMP)))
        learn.feed(cc(1, 0))
        assertEquals(0f, values["t"])
    }

    @Test fun `the range narrows what a knob sweeps`() {
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 1), "t", Mode.ABSOLUTE, Takeover.JUMP, min = 0.25f, max = 0.75f)))
        learn.feed(cc(1, 0)); assertEquals(0.25f, values["t"]!!, 1e-6f)
        learn.feed(cc(1, 127)); assertEquals(0.75f, values["t"]!!, 1e-6f)
    }

    @Test fun `two's complement encoders step up and down from the current value and stop at the ends`() {
        values["t"] = 0.5f
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 29), "t", Mode.RELATIVE_TWOS)))
        learn.feed(cc(29, 1)); assertEquals(0.5f + MidiLearn.RELATIVE_STEP, values["t"]!!, 1e-6f)
        learn.feed(cc(29, 127)); assertEquals(0.5f, values["t"]!!, 1e-6f, "127 is one step down")
        learn.feed(cc(29, 3)); assertEquals(0.5f + 3 * MidiLearn.RELATIVE_STEP, values["t"]!!, 1e-6f, "an accelerated turn moves further")
        repeat(200) { learn.feed(cc(29, 63)) }
        assertEquals(1f, values["t"])
        assertEquals(listOf(1, 63, 0, 0, -1, -63), listOf(1, 63, 0, 64, 127, 65).map(MidiLearn::twosSteps))
    }

    @Test fun `binary offset encoders rest at 64`() {
        values["t"] = 0.5f
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 29), "t", Mode.RELATIVE_BINOFFSET)))
        learn.feed(cc(29, 65)); assertEquals(0.5f + MidiLearn.RELATIVE_STEP, values["t"]!!, 1e-6f)
        learn.feed(cc(29, 63)); assertEquals(0.5f, values["t"]!!, 1e-6f)
        assertTrue(learn.feed(cc(29, 64)).actions.isEmpty())
    }

    @Test fun `a toggle flips once per press, however long a CC button repeats`() {
        values["t"] = 0f
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 50), "t", Mode.TOGGLE)))
        learn.feed(cc(50, 127)); assertEquals(1f, values["t"])
        learn.feed(cc(50, 127)); assertEquals(1f, values["t"], "still held: no second flip")
        learn.feed(cc(50, 0))
        learn.feed(cc(50, 127)); assertEquals(0f, values["t"])
    }

    @Test fun `momentary is max while held and min when let go`() {
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.NOTE, 9, 40), "t", Mode.MOMENTARY)))
        learn.feed(Event.NoteOn(9, 40, 1f)); assertEquals(1f, values["t"])
        learn.feed(Event.NoteOff(9, 40)); assertEquals(0f, values["t"])
    }

    @Test fun `bend, pressure and program change bind too`() {
        val learn = MidiLearn()
        learn.arm("keys.bend"); learn.feed(Event.PitchBend(0, 8191))
        assertEquals(1f, values["keys.bend"]!!, 1e-6f)
        learn.feed(Event.PitchBend(0, -8192)); assertEquals(0f, values["keys.bend"]!!, 1e-6f)
        learn.arm("t.press"); learn.feed(Event.ChannelPressure(0, 127))
        assertEquals(1f, values["t.press"]!!, 1e-6f)
        learn.arm("kit.next"); learn.feed(Event.ProgramChange(0, 3))
        assertEquals(listOf(Fire("kit.next")), learn.feed(Event.ProgramChange(0, 3)).actions)
        assertEquals(MidiLearn.Result.NONE, learn.feed(Event.ProgramChange(0, 4)), "a different program is a different source")
    }

    @Test fun `a binding is per device, and one learned with no device answers any`() {
        val learn = MidiLearn()
        learn.arm("t"); learn.feed(cc(1, 0), device = minilab)
        assertEquals(MidiLearn.Result.NONE, learn.feed(cc(1, 100), device = fold), "the Fold's CC 1 is not the MiniLab's")
        learn.arm("u"); learn.feed(cc(2, 0), device = null)
        assertTrue(learn.feed(cc(2, 50), device = fold).consumed)
        assertTrue(learn.feed(cc(2, 50), device = minilab).consumed)
    }

    @Test fun `a channel is part of the source`() {
        val learn = MidiLearn(listOf(MidiBinding(Source(minilab, Kind.CC, 0, 1), "t", Mode.ABSOLUTE)))
        assertEquals(MidiLearn.Result.NONE, learn.feed(cc(1, 10, channel = 1)))
    }

    @Test fun `update changes a binding in place and makes it pick up again`() {
        val learn = MidiLearn()
        learn.arm("t"); learn.feed(cc(1, 0))
        val b = learn.bindings.single()
        learn.update(b.copy(mode = Mode.RELATIVE_TWOS))
        assertEquals(Mode.RELATIVE_TWOS, learn.bindings.single().mode)
        learn.forget("t")
        assertTrue(learn.bindings.isEmpty())
    }

    @Test fun `a binding refuses a mode its kind cannot have and a range outside 0 to 1`() {
        assertFailsWith<IllegalArgumentException> { MidiBinding(Source(null, Kind.PITCH, 0, 0), "t", Mode.TOGGLE) }
        assertFailsWith<IllegalArgumentException> { MidiBinding(Source(null, Kind.NOTE, 0, 36), "t", Mode.ABSOLUTE) }
        assertFailsWith<IllegalArgumentException> { MidiBinding(Source(null, Kind.CC, 0, 1), "t", Mode.ABSOLUTE, min = 0.8f, max = 0.2f) }
        assertFailsWith<IllegalArgumentException> { MidiBinding(Source(null, Kind.CC, 0, 1), " ", Mode.ABSOLUTE) }
    }

    @Test fun `the store round-trips every field`() {
        val bindings = listOf(
            MidiBinding(Source(minilab, Kind.CC, 0, 74), "surface.grain", Mode.ABSOLUTE, Takeover.PICKUP, 0.1f, 0.9f),
            MidiBinding(Source(null, Kind.NOTE, 9, 36), "kit.panic", Mode.TRIGGER, Takeover.JUMP),
            MidiBinding(Source(fold, Kind.PITCH, 0, 0), "keys.bend", Mode.ABSOLUTE),
        )
        assertEquals(bindings, MidiLearnStore.read(MidiLearnStore.write(bindings)))
    }

    @Test fun `the store drops what it cannot read, entry by entry`() {
        val good = MidiBinding(Source(null, Kind.CC, 0, 1), "t", Mode.ABSOLUTE)
        val text = MidiLearnStore.write(listOf(good)).replace(
            "\"bindings\": [",
            "\"bindings\": [{\"kind\": \"WHEEL\", \"channel\": 0, \"number\": 1, \"target\": \"x\", \"mode\": \"ABSOLUTE\", \"takeover\": \"PICKUP\", \"min\": 0, \"max\": 1}, ",
        )
        assertTrue("WHEEL" in text, "the test must actually inject the bad entry\n$text")
        assertEquals(listOf(good), MidiLearnStore.read(text))
        assertTrue(MidiLearnStore.read("not json").isEmpty())
        assertTrue(MidiLearnStore.read(null).isEmpty())
        assertTrue(MidiLearnStore.read("{\"version\": 99, \"bindings\": []}").isEmpty(), "a version this build does not know reads as none")
    }
}
