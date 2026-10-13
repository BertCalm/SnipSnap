package com.snipsnap.kit

/**
 * MIDI on the wire: the live byte stream a keyboard sends down a USB or
 * Bluetooth cable, read into typed events. [MidiGroove] beside it reads
 * the same messages *from a file*; this reads them as they arrive, which
 * has three complications a file never has, and all three are why this
 * is a class with state rather than a function over a byte array:
 *
 *  - **a message can be split across deliveries.** The transport hands
 *    over whatever bytes it has, so a note-on's status byte may arrive
 *    in one chunk and its two data bytes in the next. The parser keeps
 *    the half-built message between [feed]s.
 *  - **running status.** A keyboard sends the status byte once and then
 *    only data for every note on the same channel, so the last channel
 *    status has to be remembered across deliveries too.
 *  - **real-time bytes land anywhere**, even between a status byte and
 *    its data. Clock (0xF8), start, stop and active sensing (0xFE) are
 *    single bytes that must be stepped over without disturbing the
 *    message they interrupted - or the running status, which they do
 *    not cancel. System common messages (song position, a SysEx) *do*
 *    cancel it, as the spec says.
 *
 * What comes out is only what a player needs: notes and controllers.
 * Pitch bend, aftertouch and program changes are read correctly - their
 * lengths are honoured, so the bytes after them stay in step - and
 * dropped. A note-on at velocity 0 is a note-off, the convention every
 * keyboard built since 1983 relies on.
 *
 * Velocity is MIDI's 1..127 as 0..1, the inverse of what [MidiGroove]
 * writes (`velocity * 127`, rounded): a velocity played in and written
 * out comes back as the same seven bits.
 *
 * Hostile input is a stream, not a file: a parser is never more than
 * one message behind, holds at most two pending data bytes, and skips a
 * SysEx byte by byte until its 0xF7 or the next status byte that is not
 * real-time, so no input can make it allocate or stall. MIDI 1.0 asks a
 * receiver to take that status byte as the end, and it is what keeps a
 * SysEx that lost its 0xF7 from swallowing every note after it: a
 * keyboard that sends only channel messages from then on never sends
 * another byte that would end it.
 */
class MidiWire {

    sealed interface Event {
        val channel: Int

        /** A key down, [velocity] in (0, 1]. */
        data class NoteOn(override val channel: Int, val note: Int, val velocity: Float) : Event

        /** A key up. The release velocity, when a keyboard sends one, is not kept: nothing here plays it. */
        data class NoteOff(override val channel: Int, val note: Int) : Event

        /** A continuous controller: [controller] 0..127 at [value] 0..127. */
        data class Control(override val channel: Int, val controller: Int, val value: Int) : Event
    }

    /** The status byte the last channel message carried, for running status; 0 before any. */
    private var runningStatus = 0

    /** The channel message being assembled, 0 when between messages. */
    private var status = 0
    private var needed = 0
    private var data0 = -1
    private var inSysEx = false

    /**
     * Read [count] bytes of [bytes] from [offset] and return every whole
     * event they completed, in order. A message still in flight at the
     * end is kept for the next call.
     */
    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): List<Event> {
        require(offset >= 0 && count >= 0 && offset + count <= bytes.size) {
            "window $offset+$count is outside ${bytes.size} bytes"
        }
        val out = ArrayList<Event>()
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xFF
            when {
                // Real-time: one byte, anywhere - inside a SysEx too - changes nothing around it.
                b >= 0xF8 -> {}
                b >= 0xF0 -> {
                    // System common cancels running status and whatever was
                    // half-built; a SysEx swallows its data to its end, and
                    // its end byte is itself system common - a stray 0xF7
                    // with no 0xF0 before it cancels exactly the same.
                    runningStatus = 0
                    status = 0
                    data0 = -1
                    needed = 0
                    inSysEx = b == SYSEX_START
                }
                // A channel status byte ends a SysEx too, whether or not its
                // 0xF7 came, and starts its own message.
                b >= 0x80 -> {
                    inSysEx = false
                    begin(b)
                }
                inSysEx -> {}
                else -> {
                    if (status == 0) {
                        // Running status: data without a status byte reuses
                        // the last channel status. Data before any is noise.
                        if (runningStatus == 0) continue
                        begin(runningStatus)
                    }
                    if (needed == 2) {
                        data0 = b
                        needed = 1
                    } else {
                        complete(status, if (data0 >= 0) data0 else b, b)?.let { out += it }
                        status = 0
                        data0 = -1
                        needed = 0
                    }
                }
            }
        }
        return out
    }

    private fun begin(statusByte: Int) {
        status = statusByte
        runningStatus = statusByte
        data0 = -1
        needed = if ((statusByte and 0xF0) == PROGRAM_CHANGE || (statusByte and 0xF0) == CHANNEL_PRESSURE) 1 else 2
    }

    private fun complete(status: Int, first: Int, second: Int): Event? {
        val channel = status and 0x0F
        return when (status and 0xF0) {
            NOTE_ON -> if (second == 0) Event.NoteOff(channel, first) else Event.NoteOn(channel, first, velocity(second))
            NOTE_OFF -> Event.NoteOff(channel, first)
            CONTROL_CHANGE -> Event.Control(channel, first, second)
            else -> null
        }
    }

    companion object {
        const val NOTE_OFF = 0x80
        const val NOTE_ON = 0x90
        const val CONTROL_CHANGE = 0xB0
        private const val PROGRAM_CHANGE = 0xC0
        private const val CHANNEL_PRESSURE = 0xD0
        private const val SYSEX_START = 0xF0

        /** The sustain pedal: at or above [CC_SWITCH_ON] the pedal is down. */
        const val CC_SUSTAIN = 64

        /** A switch controller's threshold, per the spec: 0..63 off, 64..127 on. */
        const val CC_SWITCH_ON = 64

        /** All Sound Off: silence now, the panic a controller's button sends. */
        const val CC_ALL_SOUND_OFF = 120

        /** All Notes Off: every key up, releases included. */
        const val CC_ALL_NOTES_OFF = 123

        /** MIDI's 1..127 as 0..1; the inverse of the rounding [MidiGroove] writes. */
        fun velocity(midi: Int): Float = (midi.coerceIn(1, 127) / 127f)
    }
}
