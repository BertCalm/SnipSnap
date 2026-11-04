package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts a robin chain into the take files an `.xpm` cycles.
 *
 * The `.xpm` has no Slice Motion, but it does have `ZonePlay` 0 (Cycle):
 * layers that each hold their own WAV and alternate hit by hit. So a robin
 * pad's first takes become `_RR1..RR4` files, one full-range layer each.
 * Four is the layer count the format gives a pad.
 *
 * The one place that decides take names and take bytes, shared by the
 * folder exporter, the `.xpn` packager, the pack builder's sidecar and the
 * read-back, so they cannot disagree.
 */
/**
 * Take one of robin pad [pad]'s chain in [kitDir] as a WAV file's bytes - the untouched original,
 * cut exactly as the `.xpm` exports cut it ([RobinSplit]): the chain's own PCM bytes under a plain
 * header for 16/24-bit PCM, a re-encode otherwise. What `Robin.undo` lands when the bin has lost it.
 */
fun robinTakeOne(pad: KitPad, kitDir: File): ByteArray {
    require(pad.isRobinChain) { "pad ${pad.slot} isn't a round-robin chain" }
    return RobinSplit.takeOne(pad, File(kitDir, pad.sampleFile))
}

internal object RobinSplit {

    /** Layers per pad in the format. */
    const val MAX_TAKES = 4

    class Take(val stem: String, val bytes: ByteArray, val frames: Long)

    fun takeCount(pad: KitPad): Int = minOf(requireNotNull(pad.chain) { "not a chain pad: slot ${pad.slot}" }.cycle, MAX_TAKES)

    /** `<prefix><sampleStem>_RR<n>`, sanitized after the suffix is on (n is 1-based). */
    fun takeStem(prefix: String, pad: KitPad, n: Int): String = Names.sanitizeStem("$prefix${pad.sampleStem}_RR$n")

    /**
     * The takes of [pad], named with [stemPrefix]. [claim] sees each stem
     * before any bytes are cut, so a collision throws from the caller's own
     * check rather than after work is done.
     */
    fun split(pad: KitPad, kitDir: File, stemPrefix: String = "", claim: (String) -> Unit): List<Take> {
        val stems = (1..takeCount(pad)).map { takeStem(stemPrefix, pad, it).also(claim) }
        return cut(pad, File(kitDir, pad.sampleFile)).mapIndexed { i, (bytes, frames) -> Take(stems[i], bytes, frames) }
    }

    /** The takes [split] wrote to [samplesDir], read back by name - what a sidecar must bind. */
    fun written(pad: KitPad, samplesDir: File): List<Pair<String, ByteArray>> =
        (1..takeCount(pad)).map { n ->
            val stem = takeStem("", pad, n)
            stem to File(samplesDir, "$stem.wav").readBytes()
        }

    /** Take one of [pad]'s chain in [chainFile], as WAV bytes. */
    fun takeOne(pad: KitPad, chainFile: File): ByteArray = cut(pad, chainFile).first().first

    private fun cut(pad: KitPad, chainFile: File): List<Pair<ByteArray, Long>> {
        val chain = requireNotNull(pad.chain)
        val info = WavInfo.read(chainFile)
        val raw = chainFile.readBytes()
        val pcm = locatePcm(raw)
        val windows = (0 until takeCount(pad)).map { chain.window(it, info.frameCount) }
        if (pcm != null && windows.all { (it.last + 1) * pcm.blockAlign <= pcm.dataSize }) {
            // Byte-faithful: the take is the chain's own PCM bytes under a plain header.
            return windows.map { w ->
                val from = pcm.dataStart + (w.first * pcm.blockAlign).toInt()
                val to = pcm.dataStart + ((w.last + 1) * pcm.blockAlign).toInt()
                pcmWav(pcm, raw.copyOfRange(from, to)) to (w.last + 1 - w.first)
            }
        }
        // Other formats: decode and re-encode at 24 bit (16 stays 16).
        val snip = WavReader.read(raw)
        val depth = if (info.bitsPerSample == 16) WavWriter.BitDepth.PCM_16 else WavWriter.BitDepth.PCM_24
        val ch = snip.channels
        return windows.map { w ->
            val slice = Snip(snip.samples.copyOfRange((w.first * ch).toInt(), ((w.last + 1) * ch).toInt()), ch, snip.sampleRate)
            val out = ByteArrayOutputStream()
            WavWriter.write(out, slice, depth)
            out.toByteArray() to slice.frameCount.toLong()
        }
    }

    private class Pcm(val channels: Int, val rate: Int, val bits: Int, val blockAlign: Int, val dataStart: Int, val dataSize: Long)

    /** 16/24-bit integer PCM chains only (format tag 1); null for anything else. */
    private fun locatePcm(raw: ByteArray): Pcm? {
        if (raw.size < 12 || String(raw, 0, 4, Charsets.US_ASCII) != "RIFF" || String(raw, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var fmt: IntArray? = null
        while (pos + 8 <= raw.size) {
            val id = String(raw, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (id == "fmt " && size >= 16 && body + 16 <= raw.size) {
                fmt = intArrayOf(b.getShort(body).toInt() and 0xFFFF, b.getShort(body + 2).toInt(), b.getInt(body + 4), b.getShort(body + 12).toInt(), b.getShort(body + 14).toInt())
            } else if (id == "data") {
                val f = fmt ?: return null
                val (tag, channels, rate, blockAlign, bits) = f
                if (tag != 1 || (bits != 16 && bits != 24) || channels !in 1..2 || blockAlign != channels * bits / 8) return null
                val avail = (raw.size - body).toLong()
                val dataSize = minOf(size, avail) / blockAlign * blockAlign
                return Pcm(channels, rate, bits, blockAlign, body, dataSize)
            }
            pos = (body + size + (size and 1)).let { if (it > raw.size) return null else it.toInt() }
        }
        return null
    }

    private operator fun IntArray.component5() = this[4]

    /** A plain 44-byte header over [data], with the RIFF pad byte an odd data size needs - the shape WavWriter writes. */
    private fun pcmWav(f: Pcm, data: ByteArray): ByteArray {
        val pad = data.size % 2
        val b = ByteBuffer.allocate(44 + data.size + pad).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data.size + pad).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(f.channels.toShort())
            .putInt(f.rate).putInt(f.rate * f.blockAlign).putShort(f.blockAlign.toShort()).putShort(f.bits.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(data.size).put(data)
        return b.array()
    }
}
