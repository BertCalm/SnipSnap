package com.snipsnap.xpm

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The bits of a WAV header the XPM writer needs.
 *
 * [frameCount] is what becomes SliceEnd in the program, so getting it wrong
 * truncates or over-runs every pad in the kit.
 */
data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val frameCount: Long,
) {
    /** The MPC's native rate. Anything else gets converted on load, or fails. */
    val isMpcNativeRate: Boolean get() = sampleRate == 44_100

    /** MPC accepts 16- and 24-bit PCM. */
    val isMpcNativeDepth: Boolean get() = bitsPerSample == 16 || bitsPerSample == 24

    companion object {

        /**
         * Read a WAV header. Walks the RIFF chunk list rather than assuming
         * `fmt ` and `data` sit at fixed offsets — plenty of encoders insert
         * LIST/fact chunks ahead of the audio, and Android's own recorders are
         * among them.
         */
        @Throws(IOException::class)
        fun read(file: File): WavInfo = RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 12) throw IOException("not a WAV file, too short: $file")

            if (raf.readTag() != "RIFF") throw IOException("missing RIFF header: $file")
            raf.skipBytes(4) // RIFF chunk size, unreliable in streamed files
            if (raf.readTag() != "WAVE") throw IOException("not a WAVE file: $file")

            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataBytes = -1L

            while (raf.filePointer + 8 <= length) {
                val tag = raf.readTag()
                val size = raf.readLeUInt()
                val bodyStart = raf.filePointer

                when (tag) {
                    "fmt " -> {
                        raf.skipBytes(2) // audio format code
                        channels = raf.readLeUShort()
                        sampleRate = raf.readLeUInt().toInt()
                        raf.skipBytes(4) // byte rate
                        raf.skipBytes(2) // block align
                        bitsPerSample = raf.readLeUShort()
                    }
                    "data" -> {
                        // Trust the file length over a declared size of 0 or a
                        // size that overruns the file — both happen with
                        // interrupted recordings.
                        val declared = size
                        val available = length - bodyStart
                        dataBytes = if (declared in 1..available) declared else available
                    }
                }

                // Chunks are word-aligned: an odd size is followed by a pad byte.
                val next = bodyStart + size + (size and 1L)
                if (next <= bodyStart || next > length) break
                raf.seek(next)
            }

            if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0) {
                throw IOException("missing or malformed fmt chunk: $file")
            }
            if (dataBytes < 0) throw IOException("missing data chunk: $file")

            val bytesPerFrame = channels * (bitsPerSample / 8)
            if (bytesPerFrame <= 0) throw IOException("unsupported frame size: $file")

            WavInfo(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                frameCount = dataBytes / bytesPerFrame,
            )
        }

        private fun RandomAccessFile.readTag(): String {
            val buf = ByteArray(4)
            readFully(buf)
            return String(buf, Charsets.US_ASCII)
        }

        private fun RandomAccessFile.readLeUInt(): Long {
            val buf = ByteArray(4)
            readFully(buf)
            return (buf[0].toLong() and 0xFF) or
                ((buf[1].toLong() and 0xFF) shl 8) or
                ((buf[2].toLong() and 0xFF) shl 16) or
                ((buf[3].toLong() and 0xFF) shl 24)
        }

        private fun RandomAccessFile.readLeUShort(): Int {
            val buf = ByteArray(2)
            readFully(buf)
            return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        }
    }
}
