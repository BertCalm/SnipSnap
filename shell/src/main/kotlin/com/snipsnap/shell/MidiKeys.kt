package com.snipsnap.shell

import com.snipsnap.kit.MidiWire

/**
 * What a keyboard's messages mean to an instrument - the KEYS screen's
 * rules for a MIDI note, the way [KeyHit] is its rules for a touch. A
 * key down is a note on at the velocity the keyboard measured (a real
 * hand, so no touch-Y floor: the softest a keyboard can play is the
 * softest it plays); a key up is a note off, unless the sustain pedal
 * is down, in which case the note is held until the pedal comes up.
 *
 * The pedal is the one piece of state. A note that lets go under the
 * pedal joins [held]; the pedal rising lets every held note go at once;
 * a held note struck again leaves the held set, so its second voice is
 * live and its first is stopped by the player's own note-off when the
 * key next rises - the player stops every voice on that note, so neither
 * can hang. All Notes Off (CC 123) and All Sound Off (CC 120) are the
 * panic every controller has a button for: they clear the pedal too,
 * because a stuck note that survives the panic is the one bug this
 * object exists to make impossible.
 *
 * The bend wheel or strip bends every voice, held, releasing or struck
 * later, by up to [BEND_RANGE_SEMITONES] either way, as a synth's channel
 * bend does; the panic and the screen leaving put it back at rest, since a
 * cable pulled mid-bend never sends the wheel's return.
 *
 * Every channel is listened to: a phone with one instrument open has
 * nothing a channel could select between.
 */
class MidiKeys(private val voice: Voice) {

    /** The instrument's three verbs, the shape `InstrumentPlayer` already has. */
    interface Voice {
        fun noteOn(note: Int, velocity: Float)
        fun noteOff(note: Int)
        fun allOff()

        /** Every voice at its own speed times [ratio]; 1 is at rest. */
        fun bend(ratio: Double) {}
    }

    private val held = LinkedHashSet<Int>()

    /** The pedal is down. */
    var sustaining: Boolean = false
        private set

    /** Notes whose key has risen under the pedal, waiting for it to lift. */
    val heldNotes: Set<Int> get() = held

    /** The bend's speed ratio now, 1 at rest. */
    var bend: Double = 1.0
        private set

    private fun bendTo(ratio: Double) {
        if (ratio == bend) return
        bend = ratio
        voice.bend(ratio)
    }

    fun handle(event: MidiWire.Event) {
        when (event) {
            is MidiWire.Event.NoteOn -> {
                held.remove(event.note)
                voice.noteOn(event.note, event.velocity)
            }
            is MidiWire.Event.NoteOff -> {
                if (sustaining) held += event.note else voice.noteOff(event.note)
            }
            is MidiWire.Event.Control -> when (event.controller) {
                MidiWire.CC_SUSTAIN -> pedal(event.value >= MidiWire.CC_SWITCH_ON)
                MidiWire.CC_ALL_NOTES_OFF, MidiWire.CC_ALL_SOUND_OFF -> allOff()
            }
            is MidiWire.Event.PitchBend -> bendTo(bendRatio(event.value))
            // Pressure and program change are read off the wire but not played
            // here: MIDI learn is their user.
            is MidiWire.Event.ChannelPressure,
            is MidiWire.Event.PolyPressure, is MidiWire.Event.ProgramChange -> {}
            // The transport's clock is not a key; nothing here plays it yet.
            is MidiWire.Event.RealTime -> {}
            // Bend and pressure: nothing here plays them until the controller plan gives them a voice.
            is MidiWire.Event.Expression -> {}
        }
    }

    private fun pedal(down: Boolean) {
        if (down == sustaining) return
        sustaining = down
        if (!down) {
            val release = held.toList()
            held.clear()
            release.forEach(voice::noteOff)
        }
    }

    /** Everything off and the pedal forgotten - the screen leaving, a focus loss, the panic. */
    fun allOff() {
        held.clear()
        sustaining = false
        voice.allOff()
        bendTo(1.0)
    }

    companion object {
        /** How far the wheel's ends bend, the General MIDI default and most keyboards' own. */
        const val BEND_RANGE_SEMITONES = 2

        /**
         * A bend message's value (-8192..8191, 0 at rest) as a speed ratio: the
         * top and the bottom each exactly [semitones] away, equal-tempered in
         * between. The two halves are scaled apart because the wire has one
         * more step below the centre than above it.
         */
        fun bendRatio(value: Int, semitones: Int = BEND_RANGE_SEMITONES): Double {
            val v = value.coerceIn(-MidiWire.BEND_CENTRE, MidiWire.BEND_CENTRE - 1)
            val amount = if (v >= 0) v / (MidiWire.BEND_CENTRE - 1).toDouble() else v / MidiWire.BEND_CENTRE.toDouble()
            return Math.pow(2.0, amount * semitones / 12.0)
        }
    }
}
