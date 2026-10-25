package com.snipsnap.shell

/**
 * The MIDI screen's monitor: what each connected device sent, delivery by
 * delivery, newest first, in words and in bytes.
 *
 * It exists to answer "what does this control send?" and "is anything
 * arriving at all?" without logcat. A knob's CC number, whether a strip
 * sends pitch bend, whether a pad's pressure is poly or channel, what
 * SysEx a controller answers with: the MiniLab 3 plan's bench rows ML2,
 * ML3, ML4 and ML9 (`docs/MIDI_CONTROLLER_PLAN_2026_10.md`) are all read
 * off this list.
 *
 * It is fed the raw bytes of each delivery, not `MidiWire`'s events,
 * because `MidiWire` drops by design what this screen most needs to show
 * (pitch bend, pressure, program change, SysEx), and must keep doing so
 * until the screens that play notes are ready for them. [describe] is
 * therefore a reader of bytes for people, not a second parser for the
 * engine: nothing it says ever plays a note, and it labels honestly what
 * a delivery cut short instead of carrying state across deliveries.
 *
 * Clock (`F8`) and active sensing (`FE`) are counted, not listed: a
 * running clock is 48 bytes a second at 120 BPM and would push every
 * other line off the list in a second. Every other byte is shown.
 *
 * Main thread only, like the screen that holds it. Bounded at [capacity]
 * lines so an hour of knob turning costs the same as a minute.
 */
class MidiMonitor(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    /** One delivery, after the clock and active-sensing bytes were counted out of it. */
    data class Line(
        /** Which device and port it came from, as the screen names them. */
        val port: String,
        /** The MIDI service's stamp, `System.nanoTime()` domain; 0 when it gave none. */
        val timestampNanos: Long,
        /**
         * Milliseconds since the previous line from the same [port], by the
         * service's stamps. Null for a port's first line, or when either
         * line has no stamp.
         */
        val deltaMillis: Double?,
        /** The bytes in hex, cut after [HEX_BYTES] with a count of the rest. */
        val hex: String,
        /** What the bytes say, in the screen's words. */
        val meaning: String,
    )

    /** Everything the screen draws, copied out so Compose can hold it as state. */
    data class Snapshot(
        /** Newest first, at most [capacity]. */
        val lines: List<Line>,
        val clockTicks: Long,
        val activeSensing: Long,
        /** Lines ever listed since the last [clear], including those the bound let go. */
        val total: Long,
    )

    private val lines = ArrayDeque<Line>()
    private val lastStamp = HashMap<String, Long>()
    private var clockTicks = 0L
    private var activeSensing = 0L
    private var total = 0L

    /**
     * One delivery from [port]: [count] bytes of [bytes] from [offset], as
     * the MIDI service handed them, stamped [timestampNanos].
     */
    fun deliver(port: String, bytes: ByteArray, offset: Int = 0, count: Int = bytes.size, timestampNanos: Long) {
        require(offset >= 0 && count >= 0 && offset + count <= bytes.size) { "offset $offset and count $count do not fit ${bytes.size} bytes" }
        val kept = ByteArray(count)
        var n = 0
        for (i in offset until offset + count) {
            when (bytes[i].toInt() and 0xFF) {
                CLOCK -> clockTicks++
                ACTIVE_SENSING -> activeSensing++
                else -> kept[n++] = bytes[i]
            }
        }
        if (n == 0) return
        val body = kept.copyOf(n)
        val previous = lastStamp[port]
        val delta = if (previous != null && previous != 0L && timestampNanos != 0L) (timestampNanos - previous) / 1e6 else null
        lastStamp[port] = timestampNanos
        lines.addFirst(Line(port, timestampNanos, delta, hex(body), describe(body)))
        while (lines.size > capacity) lines.removeLast()
        total++
    }

    fun snapshot(): Snapshot = Snapshot(lines.toList(), clockTicks, activeSensing, total)

    /** Forget everything, counters included. */
    fun clear() {
        lines.clear()
        lastStamp.clear()
        clockTicks = 0
        activeSensing = 0
        total = 0
    }

    companion object {
        const val DEFAULT_CAPACITY = 64

        /** How many bytes a line shows before it says how many more there were. */
        const val HEX_BYTES = 24

        private const val CLOCK = 0xF8
        private const val ACTIVE_SENSING = 0xFE
        private const val SYSEX_START = 0xF0
        private const val SYSEX_END = 0xF7

        /** `90 24 64`, cut after [HEX_BYTES] bytes: `... +40 B`. */
        fun hex(bytes: ByteArray): String {
            val shown = bytes.take(HEX_BYTES).joinToString(" ") { "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF) }
            val rest = bytes.size - HEX_BYTES
            return if (rest > 0) "$shown ... +$rest B" else shown
        }

        /**
         * What one delivery's bytes say, message by message, joined by ` · `.
         *
         * Running status is followed inside the delivery. A delivery that
         * starts with data bytes (the middle or end of a SysEx, or running
         * status from an earlier delivery) says `CONTINUED`; a message the
         * delivery ends before finishing says `PART`. Note numbers are
         * printed as numbers, the one name every map and manual agrees on.
         */
        fun describe(bytes: ByteArray): String {
            val parts = ArrayList<String>()
            var running = -1
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                when {
                    b >= 0xF8 -> {
                        // Real-time: one byte, anywhere, running status untouched.
                        parts += realTime(b)
                        i++
                    }
                    b == SYSEX_START -> {
                        running = -1
                        var end = i + 1
                        while (end < bytes.size && (bytes[end].toInt() and 0xFF) < 0x80) end++
                        val closed = end < bytes.size && (bytes[end].toInt() and 0xFF) == SYSEX_END
                        val length = if (closed) end - i + 1 else end - i
                        val id = maker(bytes, i + 1, end)
                        parts += if (closed) "SYSEX $length B$id" else "SYSEX $length B$id PART"
                        i = if (closed) end + 1 else end
                    }
                    b == SYSEX_END -> {
                        running = -1
                        parts += "SYSEX END"
                        i++
                    }
                    b >= 0xF0 -> {
                        running = -1
                        val r = read(bytes, i + 1, systemDataBytes(b))
                        parts += r.data?.let { system(b, it) } ?: "${systemName(b)} PART"
                        r.realTime.mapTo(parts, ::realTime)
                        i = r.next
                    }
                    b >= 0x80 -> {
                        running = b
                        val r = read(bytes, i + 1, channelDataBytes(b))
                        parts += r.data?.let { channel(b, it) } ?: "${channelName(b)} PART CH${channelOf(b)}"
                        r.realTime.mapTo(parts, ::realTime)
                        i = r.next
                    }
                    running >= 0 -> {
                        // Running status inside this delivery: the status is the last one seen.
                        val r = read(bytes, i, channelDataBytes(running))
                        parts += r.data?.let { channel(running, it) } ?: "${channelName(running)} PART CH${channelOf(running)}"
                        r.realTime.mapTo(parts, ::realTime)
                        i = r.next
                    }
                    else -> {
                        var end = i
                        while (end < bytes.size && (bytes[end].toInt() and 0xFF) < 0x80) end++
                        parts += "CONTINUED ${end - i} B"
                        i = end
                    }
                }
            }
            return parts.joinToString(" · ")
        }

        /**
         * The next [need] data bytes from [from]: [data] is null when a
         * status byte or the end of the delivery comes first. [next] is
         * where reading resumes, and [realTime] holds any real-time byte met
         * between the data, which belongs after the message in the list.
         */
        private class Read(val data: IntArray?, val next: Int, val realTime: List<Int>)

        private fun read(bytes: ByteArray, from: Int, need: Int): Read {
            val out = IntArray(need)
            val realTime = ArrayList<Int>(0)
            var got = 0
            var i = from
            while (got < need) {
                if (i >= bytes.size) return Read(null, i, realTime)
                val b = bytes[i].toInt() and 0xFF
                when {
                    b < 0x80 -> out[got++] = b
                    b >= 0xF8 -> realTime += b
                    else -> return Read(null, i, realTime)
                }
                i++
            }
            return Read(out, i, realTime)
        }

        private fun channelOf(status: Int) = (status and 0x0F) + 1

        private fun channelDataBytes(status: Int) = when (status and 0xF0) {
            0xC0, 0xD0 -> 1
            else -> 2
        }

        private fun channelName(status: Int) = when (status and 0xF0) {
            0x80 -> "NOTE OFF"
            0x90 -> "NOTE ON"
            0xA0 -> "POLY PRESSURE"
            0xB0 -> "CC"
            0xC0 -> "PROGRAM"
            0xD0 -> "PRESSURE"
            else -> "BEND"
        }

        private fun channel(status: Int, d: IntArray): String {
            val ch = "CH${channelOf(status)}"
            return when (status and 0xF0) {
                0x80 -> "NOTE OFF ${d[0]} $ch"
                0x90 -> if (d[1] == 0) "NOTE OFF ${d[0]} (V0) $ch" else "NOTE ON ${d[0]} V${d[1]} $ch"
                0xA0 -> "POLY PRESSURE ${d[0]} = ${d[1]} $ch"
                0xB0 -> "CC ${d[0]} = ${d[1]} $ch"
                0xC0 -> "PROGRAM ${d[0]} $ch"
                0xD0 -> "PRESSURE ${d[0]} $ch"
                else -> {
                    val bend = ((d[1] shl 7) or d[0]) - 8192
                    "BEND ${if (bend > 0) "+" else ""}$bend $ch"
                }
            }
        }

        private fun systemDataBytes(status: Int) = when (status) {
            0xF1, 0xF3 -> 1
            0xF2 -> 2
            else -> 0
        }

        private fun systemName(status: Int) = when (status) {
            0xF1 -> "TIME CODE"
            0xF2 -> "SONG POSITION"
            0xF3 -> "SONG SELECT"
            0xF6 -> "TUNE REQUEST"
            else -> "UNDEFINED %02X".format(status)
        }

        private fun system(status: Int, d: IntArray) = when (status) {
            0xF1 -> "TIME CODE ${d[0]}"
            0xF2 -> "SONG POSITION ${(d[1] shl 7) or d[0]}"
            0xF3 -> "SONG SELECT ${d[0]}"
            else -> systemName(status)
        }

        private fun realTime(b: Int) = when (b) {
            0xF8 -> "CLOCK"
            0xFA -> "START"
            0xFB -> "CONTINUE"
            0xFC -> "STOP"
            0xFE -> "ACTIVE SENSING"
            0xFF -> "RESET"
            else -> "UNDEFINED %02X".format(b)
        }

        /** ` ID 00 20 6B` for a three-byte manufacturer id, ` ID 41` for one byte, empty when the SysEx is too short to say. */
        private fun maker(bytes: ByteArray, from: Int, end: Int): String {
            if (from >= end) return ""
            val first = bytes[from].toInt() and 0xFF
            val width = if (first == 0) 3 else 1
            if (from + width > end) return ""
            return " ID " + (from until from + width).joinToString(" ") { "%02X".format(java.util.Locale.ROOT, bytes[it].toInt() and 0xFF) }
        }
    }
}
