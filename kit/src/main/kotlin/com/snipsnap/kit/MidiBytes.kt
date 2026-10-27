package com.snipsnap.kit

/**
 * MIDI on the wire, the half that writes: the typed messages SnipSnap
 * sends out, as the bytes a cable carries. [MidiWire] beside it reads the
 * same bytes as they arrive; this makes them, and its tests feed every
 * result back through that parser, so the two halves cannot disagree
 * about a status byte, a channel nibble or a velocity without a red test.
 *
 * Nothing here is stateful and nothing is thrown. Every argument is
 * clamped - the channel to 0..15, everything else to 0..127 - because the
 * caller is a pad grid or a sequencer handing over whatever integer it
 * has, and a wire message with a data byte above 0x7F would be read as a
 * status byte by the first receiver it met. A note-on at velocity 0
 * is written as a note-off status byte, not as a note-on carrying zero:
 * [MidiWire] reads the two the same, but the first is unambiguous to
 * every receiver, including those that do not know the 1983 convention.
 *
 * Every message is written whole, with its own status byte; running
 * status is a saving for the sender of a long stream, and one message at
 * a time is what a pad hit needs.
 */
object MidiBytes {

    /** Three bytes: `0x90 | channel`, [note], [velocity]. Velocity 0 is written as a [noteOff]. */
    fun noteOn(channel: Int, note: Int, velocity: Int): ByteArray {
        val v = velocity.coerceIn(0, MAX_DATA)
        return if (v == 0) noteOff(channel, note) else message(MidiWire.NOTE_ON, channel, note, v)
    }

    /** Three bytes: `0x80 | channel`, [note], and release velocity 0, which nothing here plays. */
    fun noteOff(channel: Int, note: Int): ByteArray = message(MidiWire.NOTE_OFF, channel, note, 0)

    /** Three bytes: `0xB0 | channel`, [controller], [value]. */
    fun control(channel: Int, controller: Int, value: Int): ByteArray =
        message(MidiWire.CONTROL_CHANGE, channel, controller, value)

    /** Controller 123 at 0 on [channel]: All Notes Off, every key up. */
    fun allNotesOff(channel: Int): ByteArray = control(channel, MidiWire.CC_ALL_NOTES_OFF, 0)

    private const val MAX_DATA = 127
    private const val MAX_CHANNEL = 15

    private fun message(status: Int, channel: Int, data0: Int, data1: Int): ByteArray = byteArrayOf(
        (status or channel.coerceIn(0, MAX_CHANNEL)).toByte(),
        data0.coerceIn(0, MAX_DATA).toByte(),
        data1.coerceIn(0, MAX_DATA).toByte(),
    )
}
