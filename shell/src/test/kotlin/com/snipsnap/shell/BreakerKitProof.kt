package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The measurements behind the BREAKER kit's velocity and round-robin evidence, shared by [BreakerKitTest]
 * and [BreakerKitGenerator] so the test and the published numbers are one definition.
 */
object BreakerKitProof {
    private const val RATE = 44100

    /** One pad's grid, measured. */
    data class PadGrid(
        val slot: Int,
        val name: String,
        val zones: Int,
        val takes: Int,
        /** The most alike two takes of one zone are: 1 is a copy. */
        val closestTakes: Double,
        /** The softer zone whose spectrum's shape moved least from the hard zone's, in dB: 0 is a level change only. */
        val leastZoneMoveDb: Double,
    ) {
        /**
         * How far apart the closest two takes are: what they share over what differs, in dB. A copy is
         * more than 40 dB apart; two unrelated sounds are about 0.
         */
        val takesApartDb: Double get() = -10 * log10(2 * (1 - closestTakes) + 1e-12)
    }

    /** Slice [i] of [pad]'s chain, cut from its WAV [whole]. */
    fun slice(whole: Snip, pad: KitPad, i: Int): FloatArray {
        val w = requireNotNull(pad.chain).window(i, whole.frameCount.toLong())
        return whole.samples.copyOfRange(w.first.toInt(), (w.last + 1).toInt())
    }

    fun measure(kit: Kit, dir: File): List<PadGrid> = kit.pads.map { pad ->
        val whole = WavReader.read(File(dir, pad.sampleFile))
        val zones = requireNotNull(pad.chain?.zones) { "${pad.displayName} has no velocity grid" }
        var closest = 0.0
        for (zone in zones) {
            val takes = List(zone.cycle) { slice(whole, pad, zone.baseSlice + it) }
            for (i in takes.indices) for (j in i + 1 until takes.size) closest = max(closest, likeness(takes[i], takes[j]))
        }
        val hard = shape(slice(whole, pad, zones.last().baseSlice))
        val moved = zones.dropLast(1).minOf { zone ->
            val soft = shape(slice(whole, pad, zone.baseSlice))
            hard.indices.maxOf { abs(hard[it] - soft[it]) }
        }
        PadGrid(pad.slot, pad.displayName ?: "A%02d".format(pad.slot), zones.size, zones.first().cycle, closest, moved)
    }

    private fun rms(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return sqrt(s / max(1, x.size))
    }

    /** [x] through two cascaded band-passes at [fc] (RBJ, Q 1.4: about an octave). */
    private fun octave(x: FloatArray, fc: Double): FloatArray {
        val w = 2 * PI * fc / RATE; val alpha = sin(w) / (2 * 1.4); val a0 = 1 + alpha
        val b0 = alpha / a0; val a1 = -2 * cos(w) / a0; val a2 = (1 - alpha) / a0
        var y = x
        repeat(2) {
            val out = FloatArray(y.size)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in y.indices) {
                val v = b0 * y[i] - b0 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = y[i].toDouble(); y2 = y1; y1 = v
                out[i] = v.toFloat()
            }
            y = out
        }
        return y
    }

    /** Each octave band's level against the whole hit's, over its first 300 ms: the spectrum's shape, not its loudness. */
    fun shape(x: FloatArray): List<Double> {
        val head = x.copyOf(minOf(x.size, (.3 * RATE).toInt()))
        val all = 20 * log10(rms(head) + 1e-12)
        return listOf(125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0).map { 20 * log10(rms(octave(head, it)) + 1e-12) - all }
    }

    /**
     * The highest normalised correlation of [a] against [b] over shifts up to 3 ms either way, over their
     * first half second. A take that was only nudged in level and start (a copy) correlates at 1.
     */
    fun likeness(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size, (.5 * RATE).toInt())
        val lag = (.003 * RATE).toInt()
        var best = 0.0
        for (d in -lag..lag) {
            var ab = 0.0; var aa = 0.0; var bb = 0.0
            for (i in maxOf(0, -d) until minOf(n, n - d)) {
                val x = a[i].toDouble(); val y = b[i + d].toDouble()
                ab += x * y; aa += x * x; bb += y * y
            }
            best = max(best, ab / sqrt(aa * bb + 1e-30))
        }
        return best
    }

    /** A copy correlates at 1 within rounding; anything under this is another strike. */
    const val COPY_LIKENESS = .99995

    /** A zone that is the hard take turned down moves the shape by 0; anything over this is another strike. */
    const val LEVEL_ONLY_DB = .5
}
