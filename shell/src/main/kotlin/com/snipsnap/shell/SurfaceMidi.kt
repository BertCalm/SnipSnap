package com.snipsnap.shell

import com.snipsnap.kit.MidiWire
import com.snipsnap.shell.TouchSurface.Mode
import com.snipsnap.shell.TouchSurface.Reading

/**
 * A keyboard's hand on the SURFACE pad, through MIDI learn.
 *
 * Four targets, the pad's own inputs: [X] and [Y] move the puck, [Z] is
 * XYZ's depth, and [HOLD] is the finger being down. A key or pad learned
 * to HOLD sounds the pad while it is held and lets go when it is released,
 * as a finger does, and LATCH works on it the same way; knobs moved
 * without HOLD move the puck silently, so the next press starts where they
 * left it. That is a synth's split: keys gate, knobs shape.
 *
 * A finger always wins. While one is down the knobs' moves are kept here
 * but the pad reads the finger; when it lifts, the puck is wherever the
 * finger left it (the centre, as the pad itself does), and [followed]
 * says so, so that MIDI learn's pickup waits for each knob to pass that
 * position rather than jumping the sound away from it.
 *
 * Main thread only, like the screen.
 */
class SurfaceMidi {

    var x = REST.x
        private set
    var y = REST.y
        private set
    var z = 0f
        private set
    var hold = false
        private set

    /** A target's value now, 0..1, for MIDI learn's pickup, toggle and relative steps. */
    fun current(target: String): Float? = when (target) {
        X -> x
        Y -> y
        Z -> z
        HOLD -> if (hold) 1f else 0f
        else -> null
    }

    /**
     * What one event did here: whether the pad's hand [moved] (the screen
     * re-reads [reading] unless a finger is down) and the binding it
     * [learned], for the screen to save.
     */
    data class Heard(val moved: Boolean, val learned: MidiBinding?)

    /**
     * One event from [device] (its fingerprint key, or null), through
     * [learn]. A panic (CC 120 or 123) lets HOLD go, since its release may
     * never come; it is never learned or consumed, so it reaches everything else too.
     */
    fun handle(event: MidiWire.Event, device: String?, learn: MidiLearn): Heard {
        if (event is MidiWire.Event.Control &&
            (event.controller == MidiWire.CC_ALL_NOTES_OFF || event.controller == MidiWire.CC_ALL_SOUND_OFF)
        ) {
            val held = hold
            release()
            return Heard(moved = held, learned = null)
        }
        val result = learn.handle(event, device, ::current)
        var moved = false
        for (a in result.actions) moved = apply(a) || moved
        return Heard(moved, result.learned)
    }

    /** Apply one learned action; false when it is not one of the pad's. */
    fun apply(action: MidiLearn.Action): Boolean {
        val value = (action as? MidiLearn.SetValue)?.value ?: return false
        when (action.target) {
            X -> x = value
            Y -> y = value
            Z -> z = value
            HOLD -> hold = value >= 0.5f
            else -> return false
        }
        return true
    }

    /** The pad's reading with this hand on it, in [mode]. */
    fun reading(mode: Mode): Reading = TouchSurface.at(mode, x, y, z, touching = hold)

    /**
     * A finger (or a mode change) put the puck at [reading]: the knobs no
     * longer know where it is, so each must pick it up again.
     */
    fun followed(reading: Reading, learn: MidiLearn) {
        x = reading.x
        y = reading.y
        z = reading.z
        POSITION.forEach(learn::uncatch)
    }

    /** The keyboard went away, or a panic: whatever it held is let go. */
    fun release() {
        hold = false
    }

    companion object {
        /** The screen's name in MIDI learn's targets and in [MidiLearnStore.of]. */
        const val SCREEN = "surface"

        const val X = "$SCREEN.x"
        const val Y = "$SCREEN.y"
        const val Z = "$SCREEN.z"
        const val HOLD = "$SCREEN.hold"

        /** What the learn panel offers, in its order, with each one's chip. */
        val TARGETS: List<Pair<String, String>> = listOf(X to "X", Y to "Y", Z to "Z", HOLD to "HOLD")

        /** A HOLD learned from a key or a CC button holds rather than fires or follows. */
        fun prefer(target: String): MidiBinding.Mode? = if (target == HOLD) MidiBinding.Mode.MOMENTARY else null

        private val POSITION = listOf(X, Y, Z)
        private val REST = Reading.REST
    }
}
