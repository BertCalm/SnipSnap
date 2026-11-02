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
class TileSound(val mono: FloatArray, val sampleRate: Int) {

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

    /**
     * The attack as a line that never jumps: the window is zoomed to the
     * sound's own pitch (zero crossings in the first [windowMs]) so it holds
     * [points] / [POINTS_PER_CYCLE] cycles, each point is the mean of its
     * share of that window, a three-point average rounds the corners, and
     * the result is scaled so its largest swing is 1. A kick shows a few
     * slow cycles, a hat a few fast ones, both as smooth waves. Zeros for
     * silence.
     */
    fun attackSmooth(points: Int, windowMs: Int = 120): FloatArray {
        val out = FloatArray(points)
        val probe = minOf(frames, (sampleRate.toLong() * windowMs / 1000).toInt())
        if (probe <= 0 || peak <= 1e-6f) return out
        var crossings = 0
        var last = 0f
        for (i in 0 until probe) {
            val v = mono[i]
            if (v != 0f) {
                if (last != 0f && (v > 0f) != (last > 0f)) crossings++
                last = v
            }
        }
        val cycles = crossings / 2.0
        val window = if (cycles >= 1.0) {
            val wanted = probe * (points.toDouble() / POINTS_PER_CYCLE) / cycles
            wanted.toInt().coerceIn(minOf(8, probe), probe)
        } else {
            probe
        }
        for (p in 0 until points) {
            if (window >= points) {
                // More samples than points: each point is the mean of its share.
                val a = (window.toLong() * p / points).toInt()
                val b = (window.toLong() * (p + 1) / points).toInt().coerceAtLeast(a + 1).coerceAtMost(window)
                var sum = 0.0
                for (i in a until b) sum += mono[i]
                out[p] = (sum / (b - a)).toFloat()
            } else {
                // A pitch so high its cycles are shorter than the points asked for: read between samples.
                val t = window.toFloat() * p / points
                val i = t.toInt().coerceAtMost(frames - 1)
                val j = minOf(i + 1, frames - 1)
                val f = t - i
                out[p] = mono[i] * (1 - f) + mono[j] * f
            }
        }
        val smooth = FloatArray(points) { i ->
            (out[maxOf(0, i - 1)] + out[i] + out[minOf(points - 1, i + 1)]) / 3f
        }
        val top = smooth.maxOf { abs(it) }
        if (top <= 1e-9f) return FloatArray(points)
        for (i in smooth.indices) smooth[i] = (smooth[i] / top).coerceIn(-1f, 1f)
        return smooth
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
        /** How finely [attackSmooth] draws one cycle; enough that no step jumps more than half the height. */
        private const val POINTS_PER_CYCLE = 14

        /** Null when the file is missing or does not read as a WAV. */
        fun read(file: File): TileSound? {
            if (!file.isFile) return null
            val snip = runCatching { WavReader.read(file) }.getOrNull() ?: return null
            val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
            return TileSound(mono.samples, mono.sampleRate)
        }

        /**
         * Every pad whose WAV reads and holds at least one frame, in slot
         * order, with its class colour. A WAV with no frames (a capture
         * killed the instant it started) has nothing to draw, and every
         * style divides by a length.
         */
        fun pads(kit: Kit, kitDir: File): List<PadSound> =
            kit.pads.sortedBy { it.slot }.mapNotNull { pad ->
                read(File(kitDir, pad.sampleFile))
                    ?.takeIf { it.frames > 0 }
                    ?.let { PadSound(pad.slot, it, Schemes.classColor(pad.drumClass)) }
            }

        /** The slot with the most frames, or null for a kit with no readable pad. */
        fun longestSlot(kit: Kit, kitDir: File): Int? = pads(kit, kitDir).maxByOrNull { it.sound.frames }?.slot

        /** The seed: [slot]'s sound when it exists and reads, else the longest pad's, else null. */
        fun seed(kit: Kit, kitDir: File, slot: Int?): TileSound? = seedOf(pads(kit, kitDir), slot)

        /** [seed] over pads already read, so one draw reads each WAV once. */
        fun seedOf(pads: List<PadSound>, slot: Int?): TileSound? =
            pads.firstOrNull { it.slot == slot }?.sound ?: pads.maxByOrNull { it.sound.frames }?.sound
    }
}

/**
 * A pad's slot, its sound and its class colour (packed 0xRRGGBB). Public so
 * the phone's art picker can read a kit's pads once and hand them to
 * [KitArt.draw] for every preview.
 */
data class PadSound(val slot: Int, val sound: TileSound, val color: Int)
