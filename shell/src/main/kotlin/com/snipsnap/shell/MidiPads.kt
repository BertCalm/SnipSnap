package com.snipsnap.shell

import com.snipsnap.kit.MidiWire
import com.snipsnap.xpm.PadNoteMap

/**
 * Which pad a keyboard's note lands on - PLAY's rule for MIDI, where
 * [MidiKeys] is KEYS's. Two maps, because the two kinds of kit on the
 * shelf want opposite things:
 *
 *  - [Map.MPC] is the MPC's own default drum map ([PadNoteMap]), read
 *    backwards: note 36 is A02, the kick, exactly as the exported kit
 *    answers on the hardware. A drum kit played from a keyboard here
 *    sounds the way it will on the Live III, which is the point of the
 *    app.
 *  - [Map.IN_ORDER] walks the slots: C1 (note 36) is A01, C#1 is A02,
 *    up through every bank. A kit laid out as a scale plays in that order
 *    under the fingers instead of being scattered across the drum map:
 *    `SynthKits.melodic` climbs from A01 to A12 (six nylon plucks up the
 *    A minor pentatonic from A2, five kalimba notes on to A4, a harp crown
 *    at E5), so notes 36 to 47 rise with it, and its A13-A16 organ stabs,
 *    on notes 48 to 51, drop back to the root, fourth, fifth and octave.
 *
 * The map is a choice the screen keeps; this object only answers "which
 * slot". A note no pad answers to is null, and the screen's own `hit`
 * already answers null for a slot the kit has not filled.
 */
object MidiPads {

    enum class Map(val label: String) {
        /** The MPC's default note map, so a kit plays here as it will on the hardware. */
        MPC("MPC NOTES"),

        /** Slots in order from C1 on A01: a melodic kit as a scale. */
        IN_ORDER("A01 = C1"),
        ;

        /** The other one - the chip's own toggle. */
        fun next(): Map = entries[(ordinal + 1) % entries.size]
    }

    /** The note [Map.IN_ORDER] puts on A01: C1, the MPC's own kick note's neighbour. */
    const val IN_ORDER_FIRST_NOTE = 36

    /** [PadNoteMap.DEFAULT] backwards: note to 1-based pad, every note covered exactly once. */
    private val mpcSlotForNote: IntArray = IntArray(128) { -1 }.also { inverse ->
        PadNoteMap.DEFAULT.forEachIndexed { pad, note ->
            check(inverse[note] < 0) { "PadNoteMap maps note $note twice" }
            inverse[note] = pad + 1
        }
    }

    /** The slot (1..[PadNoteMap.PAD_COUNT]) [note] plays under [map], or null when none does. */
    fun slotForNote(note: Int, map: Map): Int? {
        if (note !in 0..127) return null
        return when (map) {
            Map.MPC -> mpcSlotForNote[note].takeIf { it > 0 }
            Map.IN_ORDER -> (note - IN_ORDER_FIRST_NOTE + 1).takeIf { it in 1..PadNoteMap.PAD_COUNT }
        }
    }

    /**
     * Route one event to a pad screen's own two verbs: [onHit] is what a
     * finger down calls, [onRelease] a finger up. The screen's hit is the
     * one place choke groups and the voice allocator are reasoned about;
     * this adds nothing to that, it only picks the slot. A controller is
     * ignored except the two panics, which are [onPanic].
     */
    fun handle(
        event: MidiWire.Event,
        map: Map,
        onHit: (slot: Int, velocity: Float) -> Unit,
        onRelease: (slot: Int) -> Unit,
        onPanic: () -> Unit,
    ) {
        when (event) {
            is MidiWire.Event.NoteOn -> slotForNote(event.note, map)?.let { onHit(it, event.velocity) }
            is MidiWire.Event.NoteOff -> slotForNote(event.note, map)?.let(onRelease)
            is MidiWire.Event.Control -> when (event.controller) {
                MidiWire.CC_ALL_NOTES_OFF, MidiWire.CC_ALL_SOUND_OFF -> onPanic()
            }
        }
    }
}
