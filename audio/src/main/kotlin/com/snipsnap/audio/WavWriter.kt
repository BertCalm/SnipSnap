package com.snipsnap.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Writes PCM WAVs the MPC will take without conversion: 16- or 24-bit,
 * 44.1 kHz.
 *
 * Deliberately minimal — no metadata chunks, no cue points. The MPC reads the
 * audio and gets everything else from the program file, and extra chunks are
 * one more thing to get wrong on a device that fails silently.
 */
object WavWriter {

    const val MPC_SAMPLE_RATE = 44_100

    enum class BitDepth(val bits: Int) {
        PCM_16(16),
        PCM_24(24);

        val bytesPerSample: Int get() = bits / 8
    }

    /**
     * Write [snip] to [file].
     *
     * Refuses a sample rate the MPC won't take natively rather than writing a
     * file that loads wrong — resampling belongs at capture time, where the
     * capture API can just be asked for 44.1 kHz in the first place.
     */
    fun write(
        file: File,
        snip: Snip,
        depth: BitDepth = BitDepth.PCM_24,
        allowNonMpcRate: Boolean = false,
    ): File {
        require(allowNonMpcRate || snip.sampleRate == MPC_SAMPLE_RATE) {
            "sample rate ${snip.sampleRate} is not MPC-native ($MPC_SAMPLE_RATE); " +
                "capture at 44.1 kHz or pass allowNonMpcRate"
        }
        file.parentFile?.mkdirs()
        BufferedOutputStream(file.outputStream()).use { write(it, snip, depth) }
        return file
    }

    fun write(out: OutputStream, snip: Snip, depth: BitDepth = BitDepth.PCM_24) {
        val bytesPerSample = depth.bytesPerSample
        val blockAlign = snip.channels * bytesPerSample
        val dataBytes = snip.samples.size.toLong() * bytesPerSample
        val byteRate = snip.sampleRate.toLong() * blockAlign

        require(dataBytes + 36 <= 0xFFFFFFFFL) { "audio too large for a RIFF file" }

        out.writeTag("RIFF")
        out.writeLeInt(36 + dataBytes)
        out.writeTag("WAVE")

        out.writeTag("fmt ")
        out.writeLeInt(16)
        out.writeLeShort(1) // PCM
        out.writeLeShort(snip.channels)
        out.writeLeInt(snip.sampleRate.toLong())
        out.writeLeInt(byteRate)
        out.writeLeShort(blockAlign)
        out.writeLeShort(depth.bits)

        out.writeTag("data")
        out.writeLeInt(dataBytes)

        when (depth) {
            BitDepth.PCM_16 -> for (s in snip.samples) out.writeLeShort(toPcm16(s))
            BitDepth.PCM_24 -> for (s in snip.samples) out.writeLe24(toPcm24(s))
        }

        // RIFF chunks are word-aligned. An odd data size needs a pad byte, which
        // only happens at 24-bit with an odd sample count.
        if (dataBytes % 2 == 1L) out.write(0)
    }

    /**
     * Float to 16-bit.
     *
     * Scales by 32767 rather than 32768 so that +1.0 maps to the largest
     * positive value instead of wrapping to the largest negative one.
     */
    private fun toPcm16(sample: Float): Int =
        (finite(sample).coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)

    private fun toPcm24(sample: Float): Int =
        (finite(sample).coerceIn(-1f, 1f).toDouble() * 8_388_607.0)
            .roundToLong()
            .coerceIn(-8_388_608L, 8_388_607L)
            .toInt()

    /**
     * A non-finite sample becomes silence before it reaches the encoder.
     * `roundToInt()` throws on NaN, and an export must never crash or emit
     * garbage over one poisoned value a DSP bug slipped through - the reader
     * scrubs non-finite on the way in, this scrubs on the way out.
     */
    private fun finite(sample: Float): Float = if (sample.isFinite()) sample else 0f

    private fun OutputStream.writeTag(tag: String) = write(tag.toByteArray(Charsets.US_ASCII))

    private fun OutputStream.writeLeInt(v: Long) {
        write((v and 0xFF).toInt())
        write(((v ushr 8) and 0xFF).toInt())
        write(((v ushr 16) and 0xFF).toInt())
        write(((v ushr 24) and 0xFF).toInt())
    }

    private fun OutputStream.writeLeShort(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }

    private fun OutputStream.writeLe24(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
    }
}
