package com.snipsnap.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads PCM and float WAVs back into a [Snip].
 *
 * The counterpart to [WavWriter], which only ever wrote. Nothing in SnipSnap
 * read sample data off disk until the loop player needed to bake blocks —
 * capture produced buffers and export consumed them, and the MPC did the
 * playing.
 *
 * Parses its own RIFF header rather than borrowing xpm's WavInfo: :audio has no
 * compile-time dependencies and keeping it that way is the point.
 */
object WavReader {

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    /**
     * Reads [file] in full via [File.readBytes] and decodes it.
     *
     * The whole file is loaded into memory at once — there is no streaming
     * path, so this is unsuitable for files too large to fit in the heap.
     * Can throw [java.io.IOException] from the read itself, in addition to
     * every [IllegalArgumentException] contract of [read] below.
     */
    fun read(file: File): Snip = read(file.readBytes())

    /** What [readCapped] did: the audio it decoded, and whether [file] ran longer than its cap and got cut short. */
    data class CappedRead(val snip: Snip, val truncated: Boolean)

    /**
     * Like [read], but never decodes more than [maxDurationSec] of audio —
     * and, unlike [read], never reads more than that much of [file] off
     * disk either. [read] always loads the *whole* file via
     * [File.readBytes] before decoding a single sample, so a caller that
     * cannot vouch for [file]'s length (an arbitrary user-picked file, not
     * one this app wrote and already capped) can OOM on the raw byte read
     * alone, before decode even starts. A cap applied only *after* calling
     * [read] would be too late for exactly that reason.
     *
     * A lightweight scan ([locateAudio]) finds the `fmt ` and `data`
     * chunks by seeking past everything else — metadata chunks
     * (LIST/bext/smpl/etc.) are skipped, never read into memory, however
     * large they are — then only `[dataAt, dataAt + min(actual, cap))` of
     * the file is read off disk. That slice is handed to [read] exactly
     * as any other byte array: its `data` chunk header still declares the
     * file's true (larger) length, so [read]'s own truncated-tail
     * handling — the same path a capture killed mid-write takes — decodes
     * precisely the bytes present and no more. No decode logic is
     * duplicated here; this only ever changes how many bytes reach [read].
     *
     * Falls back to the ordinary, unbounded [read] — full memory cost and
     * all — whenever the scan can't cleanly resolve a fmt+data pair (a
     * malformed or unusually shaped file) or the file is already within
     * the cap. Either way [read]'s own decode and error contract apply
     * completely unchanged; this never rejects a file [read] would accept,
     * and never accepts one [read] would reject.
     */
    fun readCapped(file: File, maxDurationSec: Float): CappedRead {
        val loc = runCatching { locateAudio(file) }.getOrNull()
        val stride = if (loc != null) (loc.bits / 8) * loc.channels else 0
        if (loc == null || stride <= 0 || loc.sampleRate <= 0) {
            return CappedRead(read(file.readBytes()), truncated = false)
        }
        val cappedFrames = (maxDurationSec.toDouble() * loc.sampleRate).toLong().coerceAtLeast(0)
        val cappedDataBytes = minOf(loc.dataLen, cappedFrames * stride)
        if (cappedDataBytes >= loc.dataLen) {
            // The file is already within the cap (or the scan's own
            // file-clamped dataLen is): no memory saved by slicing, so
            // just take the ordinary path rather than re-derive it.
            return CappedRead(read(file.readBytes()), truncated = false)
        }
        val sliceLen = loc.dataAt + cappedDataBytes
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(sliceLen.toInt())
            raf.seek(0)
            raf.readFully(bytes)
            return CappedRead(read(bytes), truncated = true)
        }
    }

    /** What [locateAudio] needs from a file to compute a byte-precise cap without decoding it. */
    private data class AudioLocation(
        val sampleRate: Int,
        val channels: Int,
        val bits: Int,
        /** Byte offset of the `data` chunk's body — where audio bytes actually begin. */
        val dataAt: Long,
        /** The `data` chunk's declared length, clamped to what the file physically holds — same reasoning as [read]'s own truncated-tail handling. */
        val dataLen: Long,
    )

    /**
     * Scans [file]'s chunks by seeking, reading only chunk headers (and
     * the tiny `fmt ` body) into memory — never a chunk's full content
     * unless it IS the `fmt ` body. Returns null on anything that doesn't
     * cleanly resolve to a `fmt `-then-`data` pair (order-independent: a
     * `data` chunk is only ever recognized once `fmt ` has already been
     * seen), leaving [readCapped] to fall back to [read]'s own full parse
     * and error contract for that case.
     */
    private fun locateAudio(file: File): AudioLocation? {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 12) return null
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (tag(riff, 0) != "RIFF" || tag(riff, 8) != "WAVE") return null

            var sampleRate = -1
            var channels = -1
            var bits = -1
            var pos = 12L
            val header = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(header)
                val id = tag(header, 0)
                val size = leInt(header, 4)
                if (size < 0) return null
                val bodyAt = pos + 8
                if (id == "data") {
                    if (sampleRate <= 0 || channels <= 0 || bits <= 0) return null
                    val declaredLen = minOf(size.toLong(), len - bodyAt).coerceAtLeast(0)
                    return AudioLocation(sampleRate, channels, bits, bodyAt, declaredLen)
                }
                if (id == "fmt " && size >= 16 && bodyAt + 16 <= len) {
                    val body = ByteArray(16)
                    raf.seek(bodyAt)
                    raf.readFully(body)
                    channels = leShort(body, 2)
                    sampleRate = leInt(body, 4)
                    bits = leShort(body, 14)
                }
                if (bodyAt + size > len) return null
                pos = bodyAt + size + (size.toLong() and 1L)
            }
            return null
        }
    }

    /**
     * Decodes [bytes] as a WAV file.
     *
     * Throws only [IllegalArgumentException] — never any other exception —
     * for any malformed, truncated, or unsupported input.
     */
    fun read(bytes: ByteArray): Snip {
        require(bytes.size >= 12) { "not a WAV: only ${bytes.size} bytes" }
        require(tag(bytes, 0) == "RIFF") { "not a WAV: missing RIFF header" }
        require(tag(bytes, 8) == "WAVE") { "not a WAV: missing WAVE tag" }

        var format = -1
        var channels = -1
        var sampleRate = -1
        var bits = -1
        var dataAt = -1
        var dataLen = -1

        var p = 12
        while (p + 8 <= bytes.size) {
            val id = tag(bytes, p)
            val size = leInt(bytes, p + 4)
            val body = p + 8
            // Long arithmetic here: `size` is untrusted input (a corrupt or
            // hostile header can set it near Int.MAX_VALUE), and `body + size`
            // in Int would silently wrap negative and slip past a bounds
            // check meant to catch exactly this.
            if (id == "data" && size >= 0 && body.toLong() + size > bytes.size) {
                // The declared data size runs past what the file actually
                // holds — a capture killed mid-write leaves exactly this
                // shape, since WavWriter writes the size up front while
                // streaming. Decode the frames that ARE present instead of
                // falling through to the generic bounds check below, which
                // would `break` before dataAt is ever set and report "no
                // data chunk" for a file that plainly has one.
                dataAt = body
                dataLen = bytes.size - body
                break
            }
            if (size < 0 || body.toLong() + size > bytes.size + 1) break
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "fmt chunk is $size bytes, need at least 16" }
                    require(bytes.size >= body + 16) {
                        "fmt chunk is truncated: only ${bytes.size - body} of a required 16 " +
                            "fmt body bytes are present"
                    }
                    format = leShort(bytes, body)
                    channels = leShort(bytes, body + 2)
                    sampleRate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14)
                    if (format == FORMAT_EXTENSIBLE) {
                        require(size >= 40) {
                            "extensible fmt chunk is $size bytes, need 40"
                        }
                        // The real format code is the first two bytes of the
                        // SubFormat GUID, 24 bytes into the fmt body.
                        format = leShort(bytes, body + 24)
                    }
                }
                "data" -> {
                    // The truncated-tail case is caught above before this
                    // point is reached, so `size` here is always fully
                    // present in `bytes`.
                    dataAt = body
                    dataLen = size
                }
            }
            // RIFF chunks are word-aligned: an odd size is followed by a pad byte.
            p = body + size + (size and 1)
        }

        require(format != -1) { "no fmt chunk" }
        require(dataAt >= 0) { "no data chunk" }
        require(format == FORMAT_PCM || format == FORMAT_FLOAT) {
            "unsupported WAV format code $format (want 1 PCM or 3 float)"
        }
        if (format == FORMAT_FLOAT) {
            require(bits == 32) { "float WAVs must be 32-bit, was $bits" }
        } else {
            require(bits == 8 || bits == 16 || bits == 24 || bits == 32) {
                "unsupported bit depth $bits"
            }
        }
        require(channels in 1..2) { "fmt chunk declares $channels channels, must be 1 or 2" }

        val bytesPerSample = bits / 8
        val stride = bytesPerSample * channels
        val frames = dataLen / stride
        val out = FloatArray(frames * channels)
        for (i in out.indices) {
            val at = dataAt + i * bytesPerSample
            out[i] = when {
                // Float WAVs are the one format that can carry NaN or +-Inf -
                // a corrupt or hostile file, or a decoder that emitted them.
                // A non-finite sample poisons every downstream sum, peak and
                // normalization, so it becomes silence right here at the door.
                format == FORMAT_FLOAT -> Float.fromBits(leInt(bytes, at)).let { if (it.isFinite()) it else 0f }
                // Integer PCM divides by the largest POSITIVE code - the same
                // scale the writer multiplies by - so a write then a read is
                // the identity on every code the writer can emit, and a file
                // that goes through the bin and back is the same file. The one
                // code the writer never emits (the most negative) clamps to -1.
                bits == 8 -> (((bytes[at].toInt() and 0xFF) - 128) / 127f).coerceAtLeast(-1f)
                bits == 16 -> (leShort(bytes, at).toShort() / 32767f).coerceAtLeast(-1f)
                bits == 24 -> (le24(bytes, at) / 8_388_607f).coerceAtLeast(-1f)
                // 2^31 - 1 is not a Float, so this one divides in Double first.
                else -> (leInt(bytes, at) / 2_147_483_647.0).toFloat().coerceAtLeast(-1f)
            }
        }
        return Snip(out, channels, sampleRate)
    }

    /**
     * The sampler sheet, if the file carries one: the `smpl` chunk's root
     * note and first forward loop, or null when there is none or it is
     * malformed — a missing sheet is the ordinary case for a drum hit, not
     * an error, and a loop that runs past the audio the file actually
     * holds is malformed, not a loop. The audio is [read]'s business;
     * this walks the chunks on its own, reading only the headers it needs
     * to size the audio, so a caller that only wants the sheet pays only
     * for the sheet.
     */
    fun readSmpl(file: File): SmplChunk? = readSmpl(file.readBytes())

    fun readSmpl(bytes: ByteArray): SmplChunk? {
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
        var sheet: SmplChunk? = null
        var blockAlign = -1
        var dataLen = -1L
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = tag(bytes, p)
            val size = leInt(bytes, p + 4)
            val body = p + 8
            if (size < 0) return null
            if (id == "data") {
                // A truncated tail (a capture killed mid-write) still holds
                // the frames that are present; the loop is checked against
                // those, not the declared size.
                dataLen = minOf(size.toLong(), (bytes.size - body).toLong())
            } else if (body.toLong() + size > bytes.size) {
                return null
            } else if (id == "fmt " && size >= 16) {
                blockAlign = leShort(bytes, body + 12)
            } else if (id == SmplChunk.TAG) {
                if (size < SmplChunk.HEADER_BYTES) return null
                val root = leInt(bytes, body + 12)
                if (root !in 0..127) return null
                val loops = leInt(bytes, body + 28)
                val loop = if (loops >= 1 && size >= SmplChunk.HEADER_BYTES + SmplChunk.LOOP_BYTES) {
                    val start = leInt(bytes, body + 44).toLong() and 0xFFFFFFFFL
                    val endInclusive = leInt(bytes, body + 48).toLong() and 0xFFFFFFFFL
                    if (endInclusive >= start) SmplChunk.Loop(start, endInclusive + 1) else null
                } else {
                    null
                }
                sheet = SmplChunk(root, loop)
            }
            if (id == "data" && body.toLong() + size > bytes.size) break
            p = body + size + (size and 1)
        }
        val found = sheet ?: return null
        val loop = found.loop ?: return found
        if (blockAlign <= 0 || dataLen < 0) return null
        val frames = dataLen / blockAlign
        return if (loop.endFrameExclusive <= frames) found else null
    }

    private fun tag(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.US_ASCII)

    private fun leShort(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    /** 24-bit little-endian, sign-extended into an Int. */
    private fun le24(b: ByteArray, at: Int): Int {
        val v = (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16)
        return if (v and 0x800000 != 0) v or -0x1000000 else v
    }
}
