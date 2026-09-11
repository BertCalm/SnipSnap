package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Grooves as Standard MIDI Files — the first artifact this codebase emits
 * that *every* music tool on earth understands, the MPC's own browser
 * included. Export and a DAW opens the captured rhythm; import and a beat
 * programmed anywhere becomes a kit groove.
 *
 * Format 0, division 960 — MIDI's own favourite resolution and exactly
 * our clips' PPQ, so exported times are the clip's times verbatim. Notes
 * ride channel 10 (the GM drum channel) and pads are already at GM-ish
 * numbers (pad A01 = note 36 = GM kick). Reading accepts format 0 and 1,
 * any division (times rescale to 960), running status, and pairs
 * note-offs the way the spec says; anything that isn't a MIDI file is
 * refused with a reason, not a crash.
 */
object MidiGroove {

    const val DIVISION = 960
    const val DRUM_CHANNEL = 9

    /** A groove is a few bars; past this a file is hostile or nonsense. */
    const val MAX_NOTES = 100_000

    /** What a `.mid` carried: the clip, and the file's tempo when it had one. */
    data class Imported(val clip: Mpc3Clip, val bpm: Float?)

    // ---- writing -----------------------------------------------------------

    fun write(clip: Mpc3Clip, bpm: Float): ByteArray {
        require(bpm > 0) { "bpm must be positive: $bpm" }

        // Absolute-time events first; deltas come from sorting. Offs sort
        // before ons at the same tick so retriggers never stack.
        data class Ev(val tick: Long, val order: Int, val bytes: ByteArray)

        val events = mutableListOf<Ev>()
        val micros = (60_000_000.0 / bpm).roundToInt()
        events += Ev(
            0L, 0,
            byteArrayOf(
                0xFF.toByte(), 0x51, 3,
                (micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte(),
            ),
        )
        val name = clip.name.toByteArray(Charsets.US_ASCII)
        events += Ev(0L, 0, byteArrayOf(0xFF.toByte(), 0x03, name.size.toByte()) + name)

        val on = (0x90 or DRUM_CHANNEL).toByte()
        val off = (0x80 or DRUM_CHANNEL).toByte()
        for (n in clip.notes) {
            val vel = (n.velocity * 127f).roundToInt().coerceIn(1, 127)
            events += Ev(n.timePulses, 2, byteArrayOf(on, n.note.toByte(), vel.toByte()))
            events += Ev(n.timePulses + n.lengthPulses, 1, byteArrayOf(off, n.note.toByte(), 0))
        }

        val track = ByteArrayOutputStream()
        var at = 0L
        for (ev in events.sortedWith(compareBy({ it.tick }, { it.order }))) {
            writeVarLen(track, ev.tick - at)
            track.write(ev.bytes)
            at = ev.tick
        }
        writeVarLen(track, 0)
        track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))

        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        writeInt32(out, 6)
        writeInt16(out, 0) // format 0
        writeInt16(out, 1) // one track
        writeInt16(out, DIVISION)
        val trackBytes = track.toByteArray()
        out.write("MTrk".toByteArray(Charsets.US_ASCII))
        writeInt32(out, trackBytes.size)
        out.write(trackBytes)
        return out.toByteArray()
    }

    fun writeTo(file: File, clip: Mpc3Clip, bpm: Float, overwrite: Boolean = false): File {
        if (file.exists() && !overwrite) {
            throw DestinationExists(file)
        }
        file.parentFile?.mkdirs()
        file.writeBytes(write(clip, bpm))
        return file
    }

    // ---- reading -----------------------------------------------------------

    fun read(file: File, name: String = file.nameWithoutExtension): Imported =
        read(file.readBytes(), name)

    fun read(bytes: ByteArray, name: String): Imported {
        val r = Reader(bytes)
        require(r.ascii(4) == "MThd") { "not a MIDI file (no MThd header)" }
        require(r.int32() == 6) { "malformed MIDI header" }
        val format = r.int16()
        require(format in 0..1) { "MIDI format $format isn't supported (0 and 1 are)" }
        val tracks = r.int16()
        val division = r.int16()
        require(division in 1..0x7FFF) { "SMPTE-timed MIDI isn't supported" }

        data class Open(val note: Int, val startTick: Long, val velocity: Float)

        val done = mutableListOf<Mpc3Note>()
        var bpm: Float? = null
        var trackName: String? = null

        repeat(tracks) {
            require(r.ascii(4) == "MTrk") { "malformed MIDI file (missing MTrk)" }
            val length = r.int32()
            // The declared track length is untrusted: negative or absurd would
            // send `end` (and later r.at) out of the array. Clamp to what is
            // actually present - a truncated track reads what it can.
            require(length >= 0) { "MIDI track length is negative: $length" }
            val end = minOf(bytes.size.toLong(), r.at.toLong() + length).toInt()
            val open = mutableListOf<Open>()
            var tick = 0L
            var status = 0

            fun closeNote(note: Int, atTick: Long) {
                val i = open.indexOfFirst { it.note == note }
                if (i < 0) return
                val o = open.removeAt(i)
                val start = o.startTick * DIVISION / division
                val len = (atTick * DIVISION / division - start).coerceAtLeast(1)
                done += Mpc3Note(note, start, o.velocity, len)
                // A groove is a bar or a few; a file with millions of notes is
                // hostile or nonsense - refuse before the list eats the heap.
                require(done.size <= MAX_NOTES) { "MIDI file has more than $MAX_NOTES notes" }
            }

            while (r.at < end) {
                tick += r.varLen()
                var b = r.byte()
                if (b < 0x80) {
                    require(status != 0) { "running status before any status byte" }
                    r.at--
                    b = status
                } else if (b < 0xF0) {
                    status = b
                }
                when {
                    b == 0xFF -> {
                        val type = r.byte()
                        val len = r.varLen().toInt()
                        val data = r.take(len)
                        when (type) {
                            0x51 -> if (bpm == null && data.size == 3) {
                                val micros = ((data[0].toInt() and 0xFF) shl 16) or
                                    ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                                if (micros > 0) bpm = 60_000_000f / micros
                            }
                            0x03 -> if (trackName == null && data.isNotEmpty()) {
                                trackName = data.toString(Charsets.US_ASCII).trim()
                            }
                        }
                    }
                    b == 0xF0 || b == 0xF7 -> r.take(r.varLen().toInt())
                    (b and 0xF0) == 0x90 -> {
                        val note = r.byte()
                        val vel = r.byte()
                        if (vel == 0) {
                            closeNote(note, tick)
                        } else {
                            open += Open(note, tick, (vel / 127f).coerceIn(0.01f, 1f))
                        }
                    }
                    (b and 0xF0) == 0x80 -> {
                        val note = r.byte()
                        r.byte()
                        closeNote(note, tick)
                    }
                    // Two-byte messages: program change, channel pressure.
                    (b and 0xF0) == 0xC0 || (b and 0xF0) == 0xD0 -> r.byte()
                    // Three-byte: poly pressure, controller, pitch bend.
                    else -> {
                        r.byte()
                        r.byte()
                    }
                }
            }
            // A note the file never closed still deserves its start.
            open.forEach { o ->
                done += Mpc3Note(o.note, o.startTick * DIVISION / division, o.velocity, 240)
            }
            r.at = end
        }

        require(done.isNotEmpty()) { "the MIDI file has no notes" }
        val bars = ((done.maxOf { it.timePulses + it.lengthPulses } - 1) / Mpc3Clip.PULSES_PER_BAR + 1)
            .toInt().coerceIn(1, 64)
        val limit = bars * Mpc3Clip.PULSES_PER_BAR
        val kept = done.filter { it.timePulses < limit }
            .map { if (it.timePulses + it.lengthPulses > limit) it.copy(lengthPulses = limit - it.timePulses) else it }
            .sortedBy { it.timePulses }
        return Imported(Mpc3Clip(trackName ?: name, bars, kept), bpm)
    }

    // ---- byte plumbing -----------------------------------------------------

    private class Reader(val bytes: ByteArray) {
        var at = 0

        fun byte(): Int {
            require(at < bytes.size) { "truncated MIDI file" }
            return bytes[at++].toInt() and 0xFF
        }

        fun take(n: Int): ByteArray {
            // Long arithmetic and a sign check: a mutated length must never
            // make copyOfRange run off either end of the array.
            require(n >= 0 && at >= 0 && at.toLong() + n <= bytes.size) { "truncated MIDI file" }
            return bytes.copyOfRange(at, at + n).also { at += n }
        }

        fun ascii(n: Int): String = take(n).toString(Charsets.US_ASCII)
        fun int16(): Int = (byte() shl 8) or byte()
        fun int32(): Int = (int16() shl 16) or int16()

        fun varLen(): Long {
            var v = 0L
            for (i in 0 until 4) {
                val b = byte()
                v = (v shl 7) or (b and 0x7F).toLong()
                if (b < 0x80) return v
            }
            throw IllegalArgumentException("malformed variable-length quantity")
        }
    }

    private fun writeVarLen(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "negative delta time" }
        var buffer = value and 0x7FL
        var v = value shr 7
        while (v > 0) {
            buffer = (buffer shl 8) or 0x80L or (v and 0x7FL)
            v = v shr 7
        }
        while (true) {
            out.write((buffer and 0xFFL).toInt())
            if ((buffer and 0x80L) != 0L) buffer = buffer shr 8 else break
        }
    }

    private fun writeInt16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeInt32(out: ByteArrayOutputStream, v: Int) {
        writeInt16(out, (v shr 16) and 0xFFFF)
        writeInt16(out, v and 0xFFFF)
    }
}
