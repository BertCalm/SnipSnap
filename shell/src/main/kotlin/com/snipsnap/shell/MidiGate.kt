package com.snipsnap.shell

/**
 * How long a note goes out for when the hit has no release of its own (a
 * sequencer step, a tap that is gone before it is heard). One place defines
 * "one step", and it is [BeatClock]'s: this only turns that rate into a length.
 */
object MidiGate {

    /** One step (a sixteenth) at [bpm], in nanoseconds: the gate a hit with no release gets. */
    fun stepNanos(bpm: Float): Long = (1_000_000_000.0 / BeatClock.stepsPerSecond(bpm)).toLong()
}
