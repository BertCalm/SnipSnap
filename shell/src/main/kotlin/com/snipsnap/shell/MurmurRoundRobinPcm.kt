package com.snipsnap.shell

import com.snipsnap.xpm.WavInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Bounded snapshot reads used only by the finite RR native loading path. */
internal object MurmurRoundRobinPcm {
    fun read(file: File, bytes: Long, hash: String? = null, cancelled: () -> Boolean): ByteArray {
        stop(cancelled)
        require(bytes in 1..Int.MAX_VALUE.toLong())
        require(file.absoluteFile == file.canonicalFile && !Files.isSymbolicLink(file.toPath()) &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        val result = ByteArray(bytes.toInt())
        file.inputStream().use { input ->
            var offset = 0
            while (offset < result.size) {
                stop(cancelled)
                val count = input.read(result, offset, minOf(65536, result.size - offset))
                require(count > 0) { "MURMUR RR input shrank during read" }
                offset += count
            }
            require(input.read() == -1) { "MURMUR RR input grew during read" }
        }
        require(hash == null || sha256(result) == hash) { "MURMUR RR input changed during read" }
        stop(cancelled)
        return result
    }
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("MURMUR RR load cancelled")
    }

    /** Parse the same pinned bytes that will be decoded: exact mono PCM24, no partial tail. */
    fun info(bytes: ByteArray): WavInfo {
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        fun u16(at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
        fun u32(at: Int): Long = (0..3).fold(0L) { value, i -> value or ((bytes[at + i].toLong() and 255) shl (8 * i)) }
        require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE")
        require(u32(4) + 8 == bytes.size.toLong())
        var p = 12L; var fmt = false; var data = -1L
        while (p + 8 <= bytes.size) {
            val at = p.toInt(); val n = u32(at + 4); val body = p + 8
            require(body + n <= bytes.size) { "Truncated RR WAV chunk" }
            when (tag(at)) {
                "fmt " -> {
                    require(!fmt && n >= 16)
                    val b = body.toInt()
                    require(u16(b) == 1 && u16(b + 2) == 1 && u32(b + 4) == WavWriter.MPC_SAMPLE_RATE.toLong() &&
                        u32(b + 8) == WavWriter.MPC_SAMPLE_RATE * 3L && u16(b + 12) == 3 && u16(b + 14) == 24) { "RR WAV must be mono 44100 Hz PCM24" }
                    fmt = true
                }
                "data" -> { require(data < 0 && n > 0 && n % 3 == 0L); data = n }
            }
            p = body + n + (n and 1)
        }
        require(fmt && data > 0 && p == bytes.size.toLong())
        return WavInfo(WavWriter.MPC_SAMPLE_RATE, 1, 24, data / 3)
    }
}
