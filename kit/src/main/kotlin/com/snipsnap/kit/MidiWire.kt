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
 *    its data. Clock (0xF8), start, continue and stop are single bytes
 *    that come out as their own events at the place they arrived,
 *    without disturbing the message they interrupted - or the running
 *    status, which they do not cancel. Active sensing (0xFE), reset and
 *    the two undefined bytes are stepped over and dropped. System
 *    common messages (song position, a SysEx) *do* cancel running
 *    status, as the spec says.
 *
 * What comes out is every channel message a player or a transport needs:
 * notes, controllers, pitch bend and the two pressures (as [Event.Expression]),
 * program changes, and the clock's own four bytes. Bend, pressure and
 * program change are emitted for whichever rule wants them (MIDI learn, a
 * KEYS bend range); `MidiKeys` and `MidiPads` pass over them, MIDI learn
 * binds them, and `reachesScreens()` keeps real-time bytes off the main
 * thread until something follows the clock. A bend's 14 bits arrive least
 * significant seven first. A note-on at velocity 0 is a note-off, the
 * convention every keyboard built since 1983 relies on.
 *
 * Velocity is MIDI's 1..127 as 0..1, the inverse of what [MidiGroove]
 * writes (`velocity * 127`, rounded): a velocity played in and written
 * out comes back as the same seven bits.
 *
 * Every event carries [Event.timestampNanos]: the clock reading the
 * transport gave the delivery that completed it, passed into [feed] by
 * the caller (Android's `MidiReceiver.onSend` hands one over in the
 * `System.nanoTime()` domain, the same clock the audio stream's own
 * timestamps and a touch's event time are read from). The parser never
 * reads a clock itself, so a test can hand it any number and a reader
 * on a desktop has nothing to stub. A message split across deliveries
 * is stamped by the delivery that finished it - the one whose last byte
 * made it a note - because that is the earliest moment anything could
 * have known there was a note. Zero is "unstamped", what a caller that
 * has no clock passes.
 *
 * Hostile input is a stream, not a file: a parser is never more than
 * one message behind, holds at most two pending data bytes, and skips a
 * SysEx byte by byte until its 0xF7 or the next status byte that is not
 * real-time. Output is bounded by input length (one event per byte at
 * most, the real-time branch being the one that emits per byte), and
 * the parser never stalls or holds more than one message. MIDI 1.0
 * asks a receiver to take that status byte as the end, and it is what
 * keeps a SysEx that lost its 0xF7 from swallowing every note after
 * it: a keyboard that sends only channel messages from then on never
 * sends another byte that would end it.
 */
class MidiWire {

    sealed interface Event {
        /**
         * When the delivery that completed this event arrived, in the
         * nanoseconds of the clock the caller gave [feed]; 0 when it gave
         * none. A note's "when" from the edge of the phone, so the engine
         * can place it on a frame rather than on whichever block the main
         * thread got round to.
         */
        val timestampNanos: Long

        /** A channel message: a note, a controller, a bend, a pressure or a program change on one of sixteen channels. */
        sealed interface Channel : Event {
            val channel: Int
        }

        /** A key down, [velocity] in (0, 1]. */
        data class NoteOn(
            override val channel: Int,
            val note: Int,
            val velocity: Float,
            override val timestampNanos: Long = 0L,
        ) : Channel

        /** A key up. The release velocity, when a keyboard sends one, is not kept: nothing here plays it. */
        data class NoteOff(override val channel: Int, val note: Int, override val timestampNanos: Long = 0L) : Channel

        /** A continuous controller: [controller] 0..127 at [value] 0..127. */
        data class Control(
            override val channel: Int,
            val controller: Int,
            val value: Int,
            override val timestampNanos: Long = 0L,
        ) : Channel

        /** Bend and pressure: continuous expression on one channel. MIDI learn binds them; nothing plays them yet. */
        sealed interface Expression : Channel

        /**
         * The pitch wheel or strip, as MIDI's 14 bits centred on zero:
         * [value] -8192 (all the way down) to 8191 (all the way up), 0 at
         * rest. How far that bends is the receiver's bend range, not the
         * message's. Least significant seven bits arrive first.
         */
        data class PitchBend(override val channel: Int, val value: Int, override val timestampNanos: Long = 0L) : Expression

        /** Channel pressure (aftertouch for the whole channel, D0): [value] 0..127. */
        data class ChannelPressure(override val channel: Int, val value: Int, override val timestampNanos: Long = 0L) : Expression

        /** Polyphonic pressure on one held [note] (A0): [value] 0..127. */
        data class PolyPressure(
            override val channel: Int,
            val note: Int,
            val value: Int,
            override val timestampNanos: Long = 0L,
        ) : Expression

        /** A program change: [program] 0..127, as sent (a front panel's "1" is usually 0). */
        data class ProgramChange(override val channel: Int, val program: Int, override val timestampNanos: Long = 0L) : Channel

        /** A system real-time message: one byte, no channel, the transport's clock and its buttons. */
        sealed interface RealTime : Event

        /** One tick of the timing clock (0xF8), twenty-four to a quarter note. */
        data class Clock(override val timestampNanos: Long = 0L) : RealTime

        /** The transport starts from the top (0xFA). */
        data class Start(override val timestampNanos: Long = 0L) : RealTime

        /** The transport resumes from where it stopped (0xFB). */
        data class Continue(override val timestampNanos: Long = 0L) : RealTime

        /** The transport stops (0xFC). */
        data class Stop(override val timestampNanos: Long = 0L) : RealTime
    }

    /** The status byte the last channel message carried, for running status; 0 before any. */
    private var runningStatus = 0

    /** The channel message being assembled, 0 when between messages. */
    private var status = 0
    private var needed = 0
    private var data0 = -1
    private var inSysEx = false

    /** The stamp of the delivery being read, put on every event it completes. */
    private var stamp = 0L

    /**
     * Read [count] bytes of [bytes] from [offset] and return every whole
     * event they completed, in order, each stamped [timestampNanos] - the
     * delivery's own clock reading, or 0 for none. A message still in
     * flight at the end is kept for the next call, and takes that call's
     * stamp when it completes.
     */
    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset, timestampNanos: Long = 0L): List<Event> {
        // `count <= size - offset`, not `offset + count <= size`: the sum can overflow an Int and pass.
        require(offset >= 0 && count >= 0 && count <= bytes.size - offset) {
            "window $offset+$count is outside ${bytes.size} bytes"
        }
        stamp = timestampNanos
        val out = ArrayList<Event>()
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xFF
            when {
                // Real-time: one byte, anywhere - inside a SysEx too - changes nothing around it.
                // The four the transport uses become events; the other four are dropped.
                b >= 0xF8 -> when (b) {
                    TIMING_CLOCK -> out += Event.Clock(stamp)
                    START -> out += Event.Start(stamp)
                    CONTINUE -> out += Event.Continue(stamp)
                    STOP -> out += Event.Stop(stamp)
                }
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
            NOTE_ON -> if (second == 0) Event.NoteOff(channel, first, stamp) else Event.NoteOn(channel, first, velocity(second), stamp)
            NOTE_OFF -> Event.NoteOff(channel, first, stamp)
            CONTROL_CHANGE -> Event.Control(channel, first, second, stamp)
            POLY_PRESSURE -> Event.PolyPressure(channel, first, second, stamp)
            // One data byte: begin() asked for one, so first and second are the same byte.
            PROGRAM_CHANGE -> Event.ProgramChange(channel, second, stamp)
            CHANNEL_PRESSURE -> Event.ChannelPressure(channel, second, stamp)
            // Least significant seven bits first, then the most significant.
            PITCH_BEND -> Event.PitchBend(channel, ((second shl 7) or first) - BEND_CENTRE, stamp)
            else -> null
        }
    }

    companion object {
        const val NOTE_OFF = 0x80
        const val NOTE_ON = 0x90
        const val POLY_PRESSURE = 0xA0
        const val CONTROL_CHANGE = 0xB0
        private const val PROGRAM_CHANGE = 0xC0
        const val CHANNEL_PRESSURE = 0xD0
        const val PITCH_BEND = 0xE0

        /** The raw 14-bit bend value at rest: [Event.PitchBend.value] is the raw value minus this. */
        const val BEND_CENTRE = 8192
        private const val SYSEX_START = 0xF0

        /** System real-time: the timing clock, 24 to a quarter note. */
        const val TIMING_CLOCK = 0xF8
        const val START = 0xFA
        const val CONTINUE = 0xFB
        const val STOP = 0xFC

        /** The mod wheel: 0 at rest to 127 at the top. */
        const val CC_MOD = 1

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
