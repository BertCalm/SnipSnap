package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One pad's sound, read once, reduced to the shapes the tile styles draw
 * from: a loudness envelope, equal slices of the waveform, the attack.
 * Every shape is scaled by the sound's own peak, so a quiet pad draws as
 * boldly as a loud one, and silence draws flat rather than NaN.
 */
internal class TileSound(val mono: FloatArray, val sampleRate: Int) {

    val frames: Int get() = mono.size

    /** The largest magnitude in the sound; 0 for silence. */
    val peak: Float = mono.fold(0f) { acc, s -> val a = abs(s); if (a > acc) a else acc }

    /** RMS per equal bin, scaled so the loudest bin is 1; all zeros for silence. */
    fun envelope(bins: Int): FloatArray {
        require(bins > 0)
        val out = FloatArray(bins)
        if (frames == 0) return out
        for (b in 0 until bins) {
            val from = (frames.toLong() * b / bins).toInt()
            val to = (frames.toLong() * (b + 1) / bins).toInt().coerceAtLeast(from + 1).coerceAtMost(frames)
            var sum = 0.0
            for (i in from until to) sum += mono[i].toDouble() * mono[i]
            out[b] = sqrt(sum / (to - from)).toFloat()
        }
        val top = out.max()
        if (top > 1e-6f) for (b in out.indices) out[b] /= top else out.fill(0f)
        return out
    }

    /**
     * The signed peak shape of slice [index] of [count] equal slices,
     * [points] long: each point is the sample of largest magnitude in its
     * sub-window, divided by [peak]. Zeros for silence.
     */
    fun slice(index: Int, count: Int, points: Int): FloatArray {
        require(count > 0 && index in 0 until count && points > 0)
        val from = (frames.toLong() * index / count).toInt()
        val to = (frames.toLong() * (index + 1) / count).toInt()
        return shape(from, to, points)
    }

    /** [slice] of the first [windowMs] milliseconds: the attack. */
    fun attack(points: Int, windowMs: Int = 120): FloatArray {
        val window = minOf(frames, (sampleRate.toLong() * windowMs / 1000).toInt())
        return shape(0, window, points)
    }

    private fun shape(from: Int, to: Int, points: Int): FloatArray {
        val out = FloatArray(points)
        if (to <= from || peak <= 1e-6f) return out
        val span = to - from
        for (p in 0 until points) {
            val a = from + (span.toLong() * p / points).toInt()
            val b = (from + (span.toLong() * (p + 1) / points).toInt()).coerceAtLeast(a + 1).coerceAtMost(to)
            var best = 0f
            for (i in a until b) if (abs(mono[i]) > abs(best)) best = mono[i]
            out[p] = (best / peak).coerceIn(-1f, 1f)
        }
        return out
    }

    companion object {
        /** Null when the file is missing or does not read as a WAV. */
        fun read(file: File): TileSound? {
            if (!file.isFile) return null
            val snip = runCatching { WavReader.read(file) }.getOrNull() ?: return null
            val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
            return TileSound(mono.samples, mono.sampleRate)
        }

        /** Every pad whose WAV reads, in slot order, with its class colour. */
        fun pads(kit: Kit, kitDir: File): List<PadSound> =
            kit.pads.sortedBy { it.slot }.mapNotNull { pad ->
                read(File(kitDir, pad.sampleFile))?.let { PadSound(pad.slot, it, Schemes.classColor(pad.drumClass)) }
            }

        /** The slot with the most frames, or null for a kit with no readable pad. */
        fun longestSlot(kit: Kit, kitDir: File): Int? = pads(kit, kitDir).maxByOrNull { it.sound.frames }?.slot

        /** The seed: [slot]'s sound when it exists and reads, else the longest pad's, else null. */
        fun seed(kit: Kit, kitDir: File, slot: Int?): TileSound? {
            val pads = pads(kit, kitDir)
            return pads.firstOrNull { it.slot == slot }?.sound ?: pads.maxByOrNull { it.sound.frames }?.sound
        }
    }
}

/** A pad's slot, its sound and its class colour (packed 0xRRGGBB). */
internal data class PadSound(val slot: Int, val sound: TileSound, val color: Int)
