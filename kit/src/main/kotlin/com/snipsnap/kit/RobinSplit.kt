package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Cuts a robin chain into the take files an `.xpm` cycles.
 *
 * The `.xpm` has no Slice Motion, but it does have `ZonePlay` 0 (Cycle):
 * layers that each hold their own WAV and alternate hit by hit. So a robin
 * pad's first takes become `_RR1..RR4` files, one full-range layer each.
 * Four is the layer count the format gives a pad.
 */
internal object RobinSplit {

    /** Layers per pad in the format. */
    const val MAX_TAKES = 4

    class Take(val bytes: ByteArray, val frames: Long)

    /**
     * Takes 1..min(cycle, 4) of the chain at [chainFile], each a complete WAV.
     * Rate and channels carry through; depth stays 16 for a 16-bit chain and
     * is 24 otherwise (SnipSnap's own chains are always 24).
     */
    fun takes(pad: KitPad, chainFile: File): List<Take> {
        val chain = requireNotNull(pad.chain) { "not a chain pad: slot ${pad.slot}" }
        val info = WavInfo.read(chainFile)
        val snip = WavReader.read(chainFile)
        val depth = if (info.bitsPerSample == 16) WavWriter.BitDepth.PCM_16 else WavWriter.BitDepth.PCM_24
        return (0 until minOf(chain.cycle, MAX_TAKES)).map { i ->
            val w = chain.window(i, info.frameCount)
            val ch = snip.channels
            val slice = Snip(snip.samples.copyOfRange((w.first * ch).toInt(), ((w.last + 1) * ch).toInt()), ch, snip.sampleRate)
            val out = ByteArrayOutputStream()
            WavWriter.write(out, slice, depth)
            Take(out.toByteArray(), slice.frameCount.toLong())
        }
    }
}
