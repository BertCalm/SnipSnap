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
                    format = leShort(bytes, body)
                    channels = leShort(bytes, body + 2)
                    sampleRate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14)
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
        require(format == FORMAT_PCM) { "unsupported WAV format code $format" }
        require(bits == 16) { "unsupported bit depth $bits" }

        val bytesPerSample = bits / 8
        val stride = bytesPerSample * channels
        val frames = dataLen / stride
        val out = FloatArray(frames * channels)
        for (i in out.indices) {
            out[i] = leShort(bytes, dataAt + i * bytesPerSample).toShort() / 32768f
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
}
