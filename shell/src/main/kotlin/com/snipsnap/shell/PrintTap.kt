package com.snipsnap.shell

/**
 * The arithmetic behind every PRINT tap: mixed blocks appended into a
 * preallocated ceiling until it's full, and the filled part handed back
 * trimmed. `GrainVoice`'s own render loop (`:app`, Kotlin over
 * `AudioTrack`, no JVM test) calls this once per block; the arithmetic
 * is proved here instead.
 */
object PrintTap {

    /**
     * [block] copied into [buffer] starting at [filled], returning the
     * new filled count. Never exceeds `buffer.size`: a block that would
     * overrun the ceiling is copied only up to it and the rest is
     * dropped — the ceiling reached, not an overflow. A call once
     * already full is a no-op that returns [filled] unchanged.
     */
    fun append(buffer: FloatArray, filled: Int, block: FloatArray): Int {
        require(filled in 0..buffer.size) { "filled $filled out of 0..${buffer.size}" }
        val remaining = buffer.size - filled
        if (remaining <= 0) return filled
        val n = minOf(remaining, block.size)
        System.arraycopy(block, 0, buffer, filled, n)
        return filled + n
    }

    /** [buffer]'s first [filled] frames, trimmed — or null when nothing was ever appended. */
    fun take(buffer: FloatArray, filled: Int): FloatArray? =
        if (filled <= 0) null else buffer.copyOf(filled)
}
