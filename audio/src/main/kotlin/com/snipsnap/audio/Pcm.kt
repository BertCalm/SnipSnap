package com.snipsnap.audio

/**
 * Raw PCM bytes to float samples — the one conversion a platform decoder's
 * output needs before it can be a [Snip]. Little-endian, as every Android
 * and WAV byte stream is. Both readers write into a caller-owned array so
 * a decode loop can pour block after block into one growing buffer without
 * an allocation per block.
 */
object Pcm {

    /** The largest positive 16-bit code: the scale a writer used, so 0x7FFF reads back as exactly 1.0. */
    private const val INT16_FULL_SCALE = 32_767f

    /**
     * Converts [byteCount] bytes of signed 16-bit little-endian PCM starting
     * at [offset] in [bytes] into [out] from [outOffset]. Returns the sample
     * count written. A trailing odd byte is ignored. The most negative code
     * lands at -1.0 exactly, matching [WavReader]'s symmetric read.
     */
    fun int16ToFloat(bytes: ByteArray, offset: Int, byteCount: Int, out: FloatArray, outOffset: Int): Int {
        require(offset >= 0 && byteCount >= 0 && offset + byteCount <= bytes.size) {
            "byte window [$offset, ${offset + byteCount}) is outside ${bytes.size} bytes"
        }
        val samples = byteCount / 2
        require(outOffset >= 0 && outOffset + samples <= out.size) {
            "$samples samples do not fit in ${out.size} from $outOffset"
        }
        var i = offset
        var o = outOffset
        repeat(samples) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val code = (hi shl 8) or lo
            out[o] = (code / INT16_FULL_SCALE).coerceAtLeast(-1f)
            i += 2
            o++
        }
        return samples
    }

    /**
     * Converts [byteCount] bytes of 32-bit little-endian IEEE float PCM the
     * same way. Non-finite samples become 0, the same rule the float
     * [WavReader] applies: a NaN in one block must not poison the whole
     * take. Returns the sample count written.
     */
    fun floatToFloat(bytes: ByteArray, offset: Int, byteCount: Int, out: FloatArray, outOffset: Int): Int {
        require(offset >= 0 && byteCount >= 0 && offset + byteCount <= bytes.size) {
            "byte window [$offset, ${offset + byteCount}) is outside ${bytes.size} bytes"
        }
        val samples = byteCount / 4
        require(outOffset >= 0 && outOffset + samples <= out.size) {
            "$samples samples do not fit in ${out.size} from $outOffset"
        }
        var i = offset
        var o = outOffset
        repeat(samples) {
            val bits = (bytes[i].toInt() and 0xFF) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                ((bytes[i + 3].toInt() and 0xFF) shl 24)
            val x = Float.fromBits(bits)
            out[o] = if (x.isFinite()) x.coerceIn(-1f, 1f) else 0f
            i += 4
            o++
        }
        return samples
    }
}
