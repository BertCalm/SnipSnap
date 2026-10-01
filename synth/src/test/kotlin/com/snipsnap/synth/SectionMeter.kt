package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What makes several players several, measured with no ears - the companions of [FoldMeter], which measures what the
 * phone's fold costs. Four numbers, each computed one way for every test that quotes it:
 *
 * - [pitchCorrelation]: how alike the players' pitch wobbles are (the chorus's three taps are −0.5 to each other
 *   by construction; independent players are near 0);
 * - [envelopePeriodicity]: how much the level of a steady sine repeats, as the prominence of the strongest line in
 *   the envelope's spectrum (hundreds for the chorus, a handful for noise);
 * - [clickSpread]: where a click's energy lands after it, which is the onset stagger;
 * - [bassFold]: the mono fold's level of a low sine against the dry sine, which the six players' comb can hollow.
 */
internal object SectionMeter {

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(ang * k); val wi = sin(ang * k)
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                }
            }
            len = len shl 1
        }
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var saa = 0.0; var sbb = 0.0; var sab = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; saa += x * x; sbb += y * y; sab += x * y }
        return sab / sqrt(saa * sbb)
    }

    /** Mean and largest |correlation| over every pair of [tracks]' pitch deviations, from delay tracks in seconds sampled at [fs]. */
    fun pitchCorrelation(tracks: Array<DoubleArray>, fs: Int): Pair<Double, Double> {
        val devs = tracks.map { d -> DoubleArray(d.size - 1) { i -> 1200.0 * ln(1.0 - (d[i + 1] - d[i]) * fs) / ln(2.0) } }
        val rhos = ArrayList<Double>()
        for (i in devs.indices) for (j in i + 1 until devs.size) rhos += abs(pearson(devs[i], devs[j]))
        return rhos.average() to rhos.max()
    }

    /** The chorus's three taps' delay tracks at its default DEPTH and RATE, for the contrast. */
    fun chorusTracks(seconds: Int, fs: Int): Array<DoubleArray> {
        val tracks = Array(3) { DoubleArray(seconds * fs) }
        for (n in 0 until seconds * fs) {
            val d = Ensemble.delaysAt(n.toFloat() / fs, 0.5f, 1f)
            for (k in 0 until 3) tracks[k][n] = d[k].toDouble()
        }
        return tracks
    }

    /**
     * The prominence of the strongest line in the level envelope's spectrum of a steady [toneHz] sine through [process],
     * folded to mono: the strongest local peak in 0.2-20 Hz over the median of the bins around it. [toneHz] must be a
     * whole number of periods in 10 ms (100 Hz steps). A chorus's repeating swell is hundreds; noise-like beating about 3-5.
     */
    fun envelopePeriodicity(toneHz: Double, process: (Snip) -> Snip): Double {
        val rate = 44_100
        val sine = Snip(FloatArray(rate * 10) { (0.5 * sin(2 * PI * toneHz * it / rate)).toFloat() }, 1, rate)
        val out = Cleanup.toMono(process(sine))
        val block = rate / 100
        val blocks = out.frameCount / block
        val env = DoubleArray(blocks)
        for (b in 0 until blocks) {
            var ci = 0.0; var cq = 0.0
            for (n in 0 until block) {
                val idx = b * block + n
                val x = out.samples[idx].toDouble()
                ci += x * cos(2 * PI * toneHz * idx / rate); cq += x * sin(2 * PI * toneHz * idx / rate)
            }
            env[b] = sqrt(ci * ci + cq * cq)
        }
        val mean = env.average()
        val size = 1024
        val re = DoubleArray(size); val im = DoubleArray(size)
        for (i in 0 until blocks) re[i] = (env[i] / mean - 1.0) * (0.5 - 0.5 * cos(2 * PI * i / (blocks - 1)))
        fft(re, im)
        val mag = DoubleArray(size / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val df = 100.0 / size
        var best = 0.0
        for (k in (0.2 / df).roundToInt()..(20.0 / df).roundToInt()) {
            val around = ArrayList<Double>()
            for (o in 3..8) { if (k - o >= 0) around += mag[k - o]; if (k + o < mag.size) around += mag[k + o] }
            around.sort()
            best = max(best, mag[k] / around[around.size / 2].coerceAtLeast(1e-12))
        }
        return best
    }

    /** Where a click's energy lands in the mono fold, averaged over five click times: (5 %, 50 %, 95 %) of it, in ms after the click. */
    fun clickSpread(process: (Snip) -> Snip): Triple<Double, Double, Double> {
        val rate = 44_100
        val rows = listOf(0.5, 1.2, 2.0, 3.1, 4.4).map { at ->
            val click = (at * rate).toInt()
            val x = FloatArray(rate * 6); x[click] = 0.9f
            val out = Cleanup.toMono(process(Snip(x, 1, rate)))
            var total = 0.0
            for (i in click until out.frameCount) total += out.samples[i].toDouble() * out.samples[i]
            var acc = 0.0
            val at5 = 0.05 * total; val at50 = 0.5 * total; val at95 = 0.95 * total
            var t5 = -1.0; var t50 = -1.0; var t95 = -1.0
            for (i in click until out.frameCount) {
                acc += out.samples[i].toDouble() * out.samples[i]
                val ms = (i - click) * 1000.0 / rate
                if (t5 < 0 && acc >= at5) t5 = ms
                if (t50 < 0 && acc >= at50) t50 = ms
                if (t95 < 0 && acc >= at95) t95 = ms
            }
            Triple(t5, t50, t95)
        }
        return Triple(rows.map { it.first }.average(), rows.map { it.second }.average(), rows.map { it.third }.average())
    }

    /**
     * The mono fold's level of a [hz] sine against the dry sine, in dB, for [render] (the processor BEFORE any peak
     * match, since the match hides a processor whose level swings): (mean, worst 250 ms window) over 5 s, from 0.5 s.
     */
    fun bassFold(hz: Double, render: (Snip) -> Snip): Pair<Double, Double> {
        val rate = 44_100
        val sine = Snip(FloatArray(rate * 5) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }, 1, rate)
        val out = Cleanup.toMono(render(sine))
        val win = rate / 4
        val ref = 0.5 / sqrt(2.0)
        var worst = Double.MAX_VALUE; var sum = 0.0; var count = 0
        var i = rate / 2
        while (i + win <= out.frameCount) {
            var s = 0.0
            for (n in i until i + win) s += out.samples[n].toDouble() * out.samples[n]
            val db = 20.0 * log10(sqrt(s / win) / ref + 1e-12)
            worst = minOf(worst, db); sum += db; count++
            i += win
        }
        return (sum / count) to worst
    }
}
