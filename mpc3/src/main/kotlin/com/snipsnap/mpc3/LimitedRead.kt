package com.snipsnap.mpc3

import java.io.InputStream

/**
 * Reading untrusted compressed data into memory with a ceiling. A gzip or
 * zip entry can inflate a few kilobytes into gigabytes — a decompression
 * bomb — so anything that reads an *inflated* stream fully into memory
 * reads it through here, and a stream that runs past [limit] is refused
 * before it exhausts the heap rather than after.
 */
object LimitedRead {

    /**
     * A generous ceiling for a single MPC document — the largest real
     * container in the corpus is a few megabytes, so 128 MB is far above
     * anything honest and far below anything that hurts.
     */
    const val DEFAULT_LIMIT: Long = 128L * 1024 * 1024

    // A bad-input condition: extends IllegalArgumentException so callers and
    // the CLI's existing bad-input handling refuse it cleanly, no OOM.
    class TooLargeException(message: String) : IllegalArgumentException(message)

    /**
     * Fully read [input], refusing once more than [limit] bytes have been
     * inflated. [what] names the thing for the error. The stream is not
     * closed — the caller's `use {}` owns it.
     */
    fun bytes(input: InputStream, limit: Long = DEFAULT_LIMIT, what: String = "data"): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) {
                throw TooLargeException(
                    "$what inflates past ${limit / (1024 * 1024)} MB - refusing a possible decompression bomb",
                )
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Stream [input] to [output], refusing once more than [limit] bytes have
     * passed — a zip entry can inflate to fill a disk even when it never
     * touches the heap. Neither stream is closed.
     */
    fun copy(
        input: InputStream,
        output: java.io.OutputStream,
        limit: Long = DEFAULT_LIMIT,
        what: String = "entry",
    ) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) {
                throw TooLargeException(
                    "$what inflates past ${limit / (1024 * 1024)} MB - refusing a possible decompression bomb",
                )
            }
            output.write(buffer, 0, n)
        }
    }
}
