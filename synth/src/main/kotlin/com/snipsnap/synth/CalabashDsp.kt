package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * CALABASH's arithmetic: the scipy and numpy operations its accepted round-0 prototype (`prototype/calabash/`,
 * docs/CALABASH.md) is made of, written once here so [Calabash] reads like the prototype. Every function names the
 * prototype call it stands for. Where the arithmetic cannot be the same it says so:
 *
 * - [Rng] is Kotlin's generator, not numpy's PCG64, so every noise draw and every take is another realisation of the
 *   same law (as CLUTTER's and SCUFF's ports disclose).
 * - [modal] stops each mode at -80 dB on its own, where the prototype stopped blocks of twelve at their slowest
 *   mode's -80 dB: a difference under -80 dB.
 * - The output decimation is the house's (`Tide.bandLimit`, `Dsp.decimate`), not scipy's `resample_poly(1, 4)`.
 *
 * The filter designs are exact: [butter] is scipy's `butter(N, Wn, btype, output='sos')` (the analog Butterworth
 * prototype, prewarped, moved to low, high or band pass, bilinear), run as direct-form-II-transposed sections like
 * `sosfilt`; [upsample4] is `resample_poly(x, 4, 1)` with its Kaiser-windowed 81-tap design. `CalabashDspTest`
 * holds them to coefficients and outputs computed by scipy.
 */
internal object CalabashDsp {

    const val RATE = Dsp.RATE
    const val FS = Dsp.RATE * Dsp.OVERSAMPLE
    const val TAU = 2 * PI

    // ------------------------------------------------------------------ seeded draws

    /** numpy's Generator calls the prototype makes, on Kotlin's generator (another realisation, same law). */
    class Rng(seed: Int) {
        private val r = Random(seed)
        fun random() = r.nextDouble()
        fun uniform(lo: Double, hi: Double) = lo + (hi - lo) * r.nextDouble()
        fun uniform(lo: Double, hi: Double, count: Int) = DoubleArray(count) { uniform(lo, hi) }
        /** numpy's `integers(lo, hi)`: lo inclusive, hi exclusive. */
        fun integers(lo: Int, hiExclusive: Int) = r.nextInt(lo, hiExclusive)
        private var spare = Double.NaN

        /** A standard normal (Box-Muller, CLUTTER's). */
        fun normal(): Double {
            if (!spare.isNaN()) { val s = spare; spare = Double.NaN; return s }
            var u = r.nextDouble()
            while (u <= 1e-300) u = r.nextDouble()
            val v = r.nextDouble()
            val mag = sqrt(-2.0 * ln(u))
            spare = mag * sin(TAU * v)
            return mag * cos(TAU * v)
        }

        fun normal(mean: Double, sd: Double) = mean + sd * normal()
        fun normals(count: Int) = DoubleArray(count) { normal() }
        fun normals(count: Int, mean: Double, sd: Double) = DoubleArray(count) { mean + sd * normal() }
        /** numpy's `choice((-1.0, 1.0), n)`. */
        fun signs(count: Int) = DoubleArray(count) { if (r.nextBoolean()) 1.0 else -1.0 }
    }

    fun rngFor(vararg parts: Any) = Rng(Dsp.seedFor("CALABASH", *parts))

    // ------------------------------------------------------------------ small maps (snipkit's)

    fun lin(x: Double, lo: Double, hi: Double) = lo + (hi - lo) * x.coerceIn(0.0, 1.0)
    fun expMap(x: Double, lo: Double, hi: Double) = lo * (hi / lo).pow(x.coerceIn(0.0, 1.0))
    /** EMBER's `semis`: TUNE 0..1 to a ratio, [span] semitones either side of 0.5. */
    fun semis(x: Double, span: Double) = 2.0.pow((x - 0.5) * 2 * span / 12)
    /** EMBER's `n_of`: `int(round(seconds * FS))`, Python's round (half to even, as `Math.rint`). */
    fun nOf(seconds: Double) = Math.rint(seconds * FS).toInt()
    /** Python's `round` on a float: half to even. */
    fun roundHalfEven(x: Double) = Math.rint(x).toInt()
    fun db(x: Double) = 10.0.pow(x / 20)

    fun peakNorm(x: DoubleArray): DoubleArray {
        var p = 0.0
        for (v in x) p = max(p, abs(v))
        return if (p > 0) DoubleArray(x.size) { x[it] / p } else x
    }

    fun peak(x: DoubleArray): Double { var p = 0.0; for (v in x) p = max(p, abs(v)); return p }
    fun rms(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        val e = min(to, x.size)
        if (e <= from) return 0.0
        var s = 0.0
        for (i in from until e) s += x[i] * x[i]
        return sqrt(s / (e - from))
    }

    fun add(out: DoubleArray, y: DoubleArray, k: Double = 1.0, at: Int = 0) {
        val e = min(out.size - at, y.size)
        for (i in 0 until e) if (i + at >= 0) out[i + at] += k * y[i]
    }

    fun scaled(x: DoubleArray, k: Double) = DoubleArray(x.size) { x[it] * k }
    fun sum(vararg xs: DoubleArray): DoubleArray {
        val n = xs.maxOf { it.size }
        val out = DoubleArray(n)
        for (x in xs) for (i in x.indices) out[i] += x[i]
        return out
    }

    fun fit(x: DoubleArray, n: Int) = if (x.size == n) x else DoubleArray(n) { if (it < x.size) x[it] else 0.0 }

    /** numpy's `interp(x, xp, fp, right=...)`: linear, clamped at the left, [right] past the last point. */
    fun interp(x: Double, xp: DoubleArray, fp: DoubleArray, right: Double = fp.last()): Double {
        if (x <= xp[0]) return fp[0]
        if (x > xp.last()) return right
        if (x == xp.last()) return fp.last()
        var lo = 0
        var hi = xp.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (xp[mid] <= x) lo = mid else hi = mid }
        val w = (x - xp[lo]) / (xp[hi] - xp[lo])
        return fp[lo] + w * (fp[hi] - fp[lo])
    }

    /** numpy's `interp` over a uniform grid 0..size-1 (the prototype's band-slide by TUNE), clamped both ends. */
    fun interpIndex(x: Double, fp: DoubleArray): Double {
        if (x <= 0) return fp[0]
        val last = fp.size - 1
        if (x >= last) return fp[last]
        val i = x.toInt()
        val w = x - i
        return fp[i] + w * (fp[i + 1] - fp[i])
    }

    /**
     * 10^(e(s dt) / 20) for s in 0 until [n], where e is numpy's `interp(x, xp, fp, right=right)`: piecewise linear in
     * dB, so each segment is a geometric run, restarted exactly at every knot (the same numbers as a per-sample exp,
     * to rounding, at a fraction of the cost).
     */
    fun gainCurve(n: Int, dt: Double, xp: DoubleArray, fp: DoubleArray, right: Double = fp.last()): DoubleArray {
        val out = DoubleArray(n)
        val k = ln(10.0) / 20
        var s = 0
        // At or before the first knot: fp[0].
        val g0 = exp(fp[0] * k)
        while (s < n && s * dt <= xp[0]) { out[s] = g0; s++ }
        var seg = 0
        while (s < n) {
            val x = s * dt
            while (seg < xp.size - 1 && x > xp[seg + 1]) seg++
            if (seg >= xp.size - 1) {
                // Past the last knot: numpy gives fp[-1] exactly at it and `right` beyond.
                val g = exp(right * k)
                while (s < n) { out[s] = if (s * dt == xp.last()) exp(fp.last() * k) else g; s++ }
                break
            }
            val x0 = xp[seg]; val x1 = xp[seg + 1]
            val slope = (fp[seg + 1] - fp[seg]) / (x1 - x0)
            var g = exp((fp[seg] + (x - x0) * slope) * k)
            val q = exp(slope * dt * k)
            while (s < n && s * dt <= x1) { out[s] = g; g *= q; s++ }
        }
        return out
    }

    // ------------------------------------------------------------------ filters

    /** Second-order sections (b0, b1, b2, a1, a2 each, a0 = 1), run like scipy's `sosfilt` (DF-II transposed). */
    class Sos(val sections: List<DoubleArray>) {
        fun run(x: DoubleArray): DoubleArray {
            var y = x.copyOf()
            for (s in sections) {
                val b0 = s[0]; val b1 = s[1]; val b2 = s[2]; val a1 = s[3]; val a2 = s[4]
                var z1 = 0.0; var z2 = 0.0
                for (i in y.indices) {
                    val xi = y[i]
                    val yi = b0 * xi + z1
                    z1 = b1 * xi - a1 * yi + z2
                    z2 = b2 * xi - a2 * yi
                    y[i] = yi
                }
            }
            return y
        }
    }

    private class C(val re: Double, val im: Double) {
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        operator fun minus(o: C) = C(re - o.re, im - o.im)
        operator fun times(o: C) = C(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun times(k: Double) = C(re * k, im * k)
        operator fun div(o: C): C { val d = o.re * o.re + o.im * o.im; return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d) }
        fun sqrt(): C {
            val m = kotlin.math.sqrt(re * re + im * im)
            val r = kotlin.math.sqrt(max(0.0, (m + re) / 2))
            val i = kotlin.math.sqrt(max(0.0, (m - re) / 2))
            return C(r, if (im < 0) -i else i)
        }
    }

    enum class Kind { LOW, HIGH, BAND }

    /**
     * scipy's `butter(order, Wn, btype, output='sos', fs=fs)`: the analog prototype's poles -exp(i pi k / 2N), the
     * edges prewarped (2 fs tan(pi f / fs)), `lp2lp`, `lp2hp` or `lp2bp`, then `bilinear_zpk`. The sections pair each
     * pole with its conjugate; any pairing gives the same filter, and the gain goes on the first.
     */
    fun butter(order: Int, kind: Kind, f1: Double, f2: Double = 0.0, fs: Int = FS): Sos {
        val fsn = 2.0
        fun warp(f: Double) = 2 * fsn * tan(PI * (f / (fs / 2.0)) / fsn)
        val proto = (0 until order).map { k -> val m = -order + 1 + 2 * k; val a = PI * m / (2.0 * order); C(-cos(a), -sin(a)) }
        var poles: List<C>
        var zeros: List<C>
        var gain: Double
        when (kind) {
            Kind.LOW -> {
                val wo = warp(f1)
                poles = proto.map { it * wo }; zeros = emptyList(); gain = wo.pow(order)
            }
            Kind.HIGH -> {
                val wo = warp(f1)
                poles = proto.map { C(wo, 0.0) / it }
                zeros = List(order) { C(0.0, 0.0) }
                var prod = C(1.0, 0.0)
                for (p in proto) prod = prod * C(-p.re, -p.im)
                gain = (C(1.0, 0.0) / prod).re
            }
            Kind.BAND -> {
                val w1 = warp(f1); val w2 = warp(f2)
                val bw = w2 - w1
                val wo2 = w1 * w2
                val pl = proto.map { it * (bw / 2) }
                poles = pl.map { it + (it * it - C(wo2, 0.0)).sqrt() } + pl.map { it - (it * it - C(wo2, 0.0)).sqrt() }
                zeros = List(order) { C(0.0, 0.0) }
                gain = bw.pow(order)
            }
        }
        // bilinear_zpk with fs = 2: z -> (4 + s) / (4 - s); the zeros at infinity land at -1.
        val fs2 = 2 * fsn
        val k2 = C(fs2, 0.0)
        val zz = zeros.map { (k2 + it) / (k2 - it) } + List(poles.size - zeros.size) { C(-1.0, 0.0) }
        val pz = poles.map { (k2 + it) / (k2 - it) }
        var num = C(1.0, 0.0)
        var den = C(1.0, 0.0)
        for (z in zeros) num = num * (k2 - z)
        for (p in poles) den = den * (k2 - p)
        gain *= (num / den).re
        // Sections: conjugate pole pairs (keep the upper member), real poles alone; zeros handed out two a section.
        val upper = pz.filter { it.im > 1e-12 }.sortedBy { it.re }
        val real = pz.filter { abs(it.im) <= 1e-12 }
        val zeroRe = zz.map { it.re }.sorted().toMutableList()
        val pairsFirst = ArrayList<DoubleArray>()
        fun takeZeros(count: Int): DoubleArray {
            // A band-pass hands each section one zero at +1 and one at -1; low and high passes two alike.
            val picked = DoubleArray(count)
            for (i in 0 until count) {
                val idx = if (i == 0 || zeroRe.size == 1) 0 else zeroRe.size - 1
                picked[i] = zeroRe.removeAt(if (kind == Kind.BAND) idx else 0)
            }
            return picked
        }
        for (p in upper) {
            val z = takeZeros(2)
            pairsFirst += doubleArrayOf(1.0, -(z[0] + z[1]), z[0] * z[1], -2 * p.re, p.re * p.re + p.im * p.im)
        }
        for (p in real) {
            val z = takeZeros(1)
            pairsFirst += doubleArrayOf(1.0, -z[0], 0.0, -p.re, 0.0)
        }
        val first = pairsFirst[0]
        for (i in 0..2) first[i] *= gain
        return Sos(pairsFirst)
    }

    private val sosCache = java.util.concurrent.ConcurrentHashMap<String, Sos>()

    private fun cached(order: Int, kind: Kind, f1: Double, f2: Double, fs: Int) =
        sosCache.getOrPut("$order|$kind|$f1|$f2|$fs") { butter(order, kind, f1, f2, fs) }

    /** EMBER's `lp`: Butterworth low-pass at min(hz, 0.45 fs). */
    fun lp(x: DoubleArray, hz: Double, order: Int = 2, fs: Int = FS) = cached(order, Kind.LOW, min(hz, 0.45 * fs), 0.0, fs).run(x)
    /** EMBER's `hp`. */
    fun hp(x: DoubleArray, hz: Double, order: Int = 2, fs: Int = FS) = cached(order, Kind.HIGH, hz, 0.0, fs).run(x)
    /** EMBER's `band`: Butterworth band-pass from [lo] to min(hi, 0.45 fs). */
    fun band(x: DoubleArray, lo: Double, hi: Double, order: Int = 2, fs: Int = FS) =
        cached(order, Kind.BAND, lo, min(hi, 0.45 * fs), fs).run(x)

    /** A third-octave band filter as the fits use it: order 4, from fc / 2^(1/6) to min(fc 2^(1/6), 0.49 fs). */
    fun thirdOctave(fc: Double, fs: Int = RATE) = cached(4, Kind.BAND, fc / 2.0.pow(1.0 / 6), min(fc * 2.0.pow(1.0 / 6), fs * 0.49), fs)

    /** `lfilter(b, a, x)` for a biquad (a0 = 1 after normalising), DF-II transposed as scipy's. */
    fun lfilter(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
        val b0 = b[0] / a[0]; val b1 = b.getOrElse(1) { 0.0 } / a[0]; val b2 = b.getOrElse(2) { 0.0 } / a[0]
        val a1 = a.getOrElse(1) { 0.0 } / a[0]; val a2 = a.getOrElse(2) { 0.0 } / a[0]
        return Sos(listOf(doubleArrayOf(b0, b1, b2, a1, a2))).run(x)
    }

    /** EMBER's `peaking`: the RBJ peaking biquad at [fs]. */
    fun peaking(f0: Double, gainDb: Double, q: Double, fs: Int = FS): Pair<DoubleArray, DoubleArray> {
        val a = 10.0.pow(gainDb / 40)
        val w = TAU * f0 / fs
        val al = sin(w) / (2 * q)
        val b = doubleArrayOf(1 + al * a, -2 * cos(w), 1 - al * a)
        val den = doubleArrayOf(1 + al / a, -2 * cos(w), 1 - al / a)
        return DoubleArray(3) { b[it] / den[0] } to DoubleArray(3) { den[it] / den[0] }
    }

    /** scipy's `iirpeak(hz / (fs / 2), q)` (fs left at its default of 2). */
    fun iirpeak(hz: Double, q: Double, fs: Int = FS): Pair<DoubleArray, DoubleArray> {
        val w0 = hz / (fs / 2.0) * PI
        val bw = hz / (fs / 2.0) / q * PI
        val beta = tan(bw / 2)
        val gain = 1.0 / (1.0 + beta)
        return doubleArrayOf(1 - gain, 0.0, -(1 - gain)) to doubleArrayOf(1.0, -2 * gain * cos(w0), 2 * gain - 1)
    }

    /** EMBER's `env_follow`: a one-pole smoother of |x| with time constant [ms]. */
    fun envFollow(x: DoubleArray, ms: Double, fs: Int = FS): DoubleArray {
        val a = exp(-1.0 / (ms * 1e-3 * fs))
        val y = DoubleArray(x.size)
        var s = 0.0
        for (i in x.indices) { s = (1 - a) * abs(x[i]) + a * s; y[i] = s }
        return y
    }

    // ------------------------------------------------------------------ resampling

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (term > 1e-17 * sum) { term *= q / (k.toDouble() * k); sum += term; k++ }
        return sum
    }

    /** scipy's `firwin(81, 0.25, window=('kaiser', 5.0))` times 4: the taps `resample_poly(x, 4, 1)` designs. */
    internal val UP4: DoubleArray by lazy {
        val m = 81
        val alpha = (m - 1) / 2.0
        val h = DoubleArray(m) { n ->
            val x = n - alpha
            val sinc = if (x == 0.0) 1.0 else sin(PI * 0.25 * x) / (PI * 0.25 * x)
            val w = besselI0(5.0 * sqrt(max(0.0, 1 - ((n - alpha) / alpha).pow(2)))) / besselI0(5.0)
            0.25 * sinc * w
        }
        val s = h.sum()
        DoubleArray(m) { h[it] / s * 4 }
    }

    /** `resample_poly(x, 4, 1)`, then cut or padded to [n] (EMBER's `to_internal`): 44.1 kHz into the 4x chain. */
    fun upsample4(x: DoubleArray, n: Int): DoubleArray {
        val h = UP4
        val half = 40
        val out = DoubleArray(n)
        val total = min(n, x.size * 4)
        for (j in 0 until total) {
            // y[j] = sum_k h[k] xup[j + half - k], xup nonzero only at multiples of 4.
            var acc = 0.0
            val start = (j + half) % 4
            var k = start
            while (k < h.size) {
                val idx = j + half - k
                if (idx < 0) break
                val src = idx / 4
                if (src < x.size) acc += h[k] * x[src]
                k += 4
            }
            out[j] = acc
        }
        return out
    }

    // ------------------------------------------------------------------ convolution

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = TAU / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    private fun span(x: DoubleArray): IntRange {
        var a = 0
        while (a < x.size && x[a] == 0.0) a++
        if (a == x.size) return IntRange.EMPTY
        var b = x.size - 1
        while (x[b] == 0.0) b--
        return a..b
    }

    /** EMBER's `conv`: the linear convolution of [x] and [h], first [n] samples (direct when one side is short). */
    fun conv(x: DoubleArray, h: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(n)
        val sx = span(x)
        val sh = span(h)
        if (sx.isEmpty() || sh.isEmpty() || sx.first + sh.first >= n) return out
        val lx = min(sx.last, n - 1 - sh.first) - sx.first + 1
        val lh = min(sh.last, n - 1 - sx.first) - sh.first + 1
        if (lx.toLong() * lh <= 4_000_000L) {
            for (i in 0 until lx) {
                val a = x[sx.first + i]
                if (a == 0.0) continue
                val base = sx.first + i + sh.first
                val e = min(lh, n - base)
                for (k in 0 until e) out[base + k] += a * h[sh.first + k]
            }
            return out
        }
        var size = 1
        while (size < lx + lh - 1) size = size shl 1
        val ar = DoubleArray(size); val ai = DoubleArray(size)
        val br = DoubleArray(size); val bi = DoubleArray(size)
        for (i in 0 until lx) ar[i] = x[sx.first + i]
        for (i in 0 until lh) br[i] = h[sh.first + i]
        fft(ar, ai, false)
        fft(br, bi, false)
        for (i in 0 until size) {
            val r = ar[i] * br[i] - ai[i] * bi[i]
            val im = ar[i] * bi[i] + ai[i] * br[i]
            ar[i] = r; ai[i] = im
        }
        fft(ar, ai, true)
        val off = sx.first + sh.first
        for (i in 0 until min(size, n - off)) out[off + i] = ar[i]
        return out
    }

    // ------------------------------------------------------------------ modes and contacts

    /**
     * EMBER's `modal_ir`: the sum of a_k exp(-6.9078 t / t60_k) sin(2 pi f_k w(t) + ph_k) at FS, where w is the time
     * the phase runs on ([warp], seconds; t when null). Modes outside 15 Hz..19.5 kHz, with no decay or no gain are
     * dropped; each stops at -80 dB.
     */
    fun modal(freqs: DoubleArray, amps: DoubleArray, t60s: DoubleArray, n: Int, phases: DoubleArray? = null,
              warp: DoubleArray? = null, fs: Int = FS): DoubleArray {
        val out = DoubleArray(n)
        for (k in freqs.indices) {
            val f = freqs[k]; val a = amps[k]; val t60 = t60s[k]
            if (!(f < 19_500 && f > 15 && t60 > 0 && a != 0.0)) continue
            val m = min(n, (t60 * 80 / 60 * fs).toInt() + 1)
            val ph = phases?.get(k) ?: 0.0
            val decay = exp(-6.9078 / (t60 * fs))
            var env = a
            if (warp == null) {
                val step = TAU * f / fs
                val cs = cos(step); val sn = sin(step)
                var s = sin(ph); var c = cos(ph)
                for (i in 0 until m) {
                    out[i] += env * s
                    val ns = s * cs + c * sn
                    c = c * cs - s * sn
                    s = ns
                    env *= decay
                }
            } else {
                val w = TAU * f
                for (i in 0 until m) { out[i] += env * sin(w * warp[i] + ph); env *= decay }
            }
        }
        return out
    }

    /** EMBER's `hann_force`: a raised-cosine contact of unit area times [amount] (times 40, EMBER's scale) at [atS]. */
    fun hannForce(widthS: Double, amount: Double, n: Int, atS: Double = 0.0): DoubleArray {
        val w = max(3, (widthS * FS).toInt())
        val f = DoubleArray(n)
        val i = (atS * FS).toInt()
        if (i >= n) return f
        val p = DoubleArray(w) { 0.5 * (1 - cos(TAU * it / w)) }
        val s = p.sum()
        val e = min(n, i + w)
        for (j in i until e) if (j >= 0) f[j] += p[j - i] * amount / s * 40.0
        return f
    }

    /** numpy's `hanning(k + 2)[1:-1]`: a Hann window without its zero ends. */
    fun hanningInner(k: Int): DoubleArray {
        val m = k + 2
        return DoubleArray(k) { 0.5 - 0.5 * cos(TAU * (it + 1) / (m - 1)) }
    }
}
