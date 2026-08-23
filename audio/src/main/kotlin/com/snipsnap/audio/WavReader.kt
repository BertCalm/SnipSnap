package com.snipsnap.audio

import java.io.File

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

    fun read(file: File): Snip = read(file.readBytes())

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
            if (size < 0 || body + size > bytes.size + 1) break
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
                    dataAt = body
                    dataLen = minOf(size, bytes.size - body)
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
                format == FORMAT_FLOAT -> Float.fromBits(leInt(bytes, at))
                bits == 8 -> ((bytes[at].toInt() and 0xFF) - 128) / 128f
                bits == 16 -> leShort(bytes, at).toShort() / 32768f
                bits == 24 -> le24(bytes, at) / 8_388_608f
                else -> leInt(bytes, at) / 2_147_483_648f
            }
        }
        return Snip(out, channels, sampleRate)
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
