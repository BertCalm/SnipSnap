package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
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
 * The small DSP EMBER's round-0e prototype (`prototype/ember/ember_proto.py`) leaned on from numpy and scipy,
 * written out for the Kotlin port. Each function names the prototype call it stands in for. Everything runs
 * in Double; only the FFT convolution passes through the house Float [Fft].
 *
 * CLUTTER carries private copies of a few of these (its Rng and biquads). They stay apart on purpose: a
 * shared helper would put CLUTTER's accepted renders at risk for a refactor nobody has listened to.
 */
internal object EmberDsp {

    /**
     * Seeded draws. The prototype used numpy's PCG64; this is Kotlin's [Random], so every noise draw and every
     * strike scatter is another realisation of the same law (disclosed in docs/EMBER.md), and still byte for
     * byte deterministic.
     */
    class Rng(seed: Int) {
        private val r = Random(seed)
        fun random() = r.nextDouble()
        fun uniform(lo: Double, hi: Double) = lo + (hi - lo) * r.nextDouble()
        fun uniform(lo: Double, hi: Double, count: Int) = DoubleArray(count) { uniform(lo, hi) }
        /** numpy's `integers(lo, hi)`: lo inclusive, hi exclusive. */
        fun int(lo: Int, hiExclusive: Int) = r.nextInt(lo, hiExclusive)
        private var spare = Double.NaN
        /** Standard normal by Box-Muller. */
        fun normal(): Double {
            if (!spare.isNaN()) { val s = spare; spare = Double.NaN; return s }
            var u = r.nextDouble()
            while (u <= 1e-300) u = r.nextDouble()
            val v = r.nextDouble()
            val mag = sqrt(-2.0 * ln(u))
            spare = mag * sin(2 * PI * v)
            return mag * cos(2 * PI * v)
        }
        fun normal(mean: Double, sd: Double) = mean + sd * normal()
        fun normals(count: Int) = DoubleArray(count) { normal() }
        /** Gamma(shape >= 1, scale) by Marsaglia and Tsang. */
        fun gamma(shape: Double, scale: Double): Double {
            val d = shape - 1.0 / 3.0
            val c = 1.0 / sqrt(9.0 * d)
            while (true) {
                var x: Double
                var v: Double
                do { x = normal(); v = 1.0 + c * x } while (v <= 0.0)
                v = v * v * v
                val u = r.nextDouble()
                if (u < 1.0 - 0.0331 * x * x * x * x) return d * v * scale
                if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v * scale
            }
        }
    }

    fun rngFor(vararg parts: Any) = Rng(Dsp.seedFor("EMBER", *parts))

    fun lin(x: Double, lo: Double, hi: Double) = lo + (hi - lo) * x.coerceIn(0.0, 1.0)
    fun expMap(x: Double, lo: Double, hi: Double) = lo * (hi / lo).pow(x.coerceIn(0.0, 1.0))
    /** TUNE 0..1 to a frequency ratio, [span] semitones either side of 0.5. */
    fun semis(x: Double, span: Double) = 2.0.pow((x - 0.5) * 2 * span / 12)

    fun peakOf(x: DoubleArray): Double { var p = 0.0; for (v in x) { val a = abs(v); if (a > p) p = a }; return p }
    fun peakNorm(x: DoubleArray): DoubleArray { val p = peakOf(x); return if (p > 0) DoubleArray(x.size) { x[it] / p } else x }
    fun rms(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        val e = min(to, x.size)
        if (e <= from) return 0.0
        var s = 0.0
        for (i in from until e) s += x[i] * x[i]
        return sqrt(s / (e - from))
    }
    fun std(x: DoubleArray): Double { val m = x.average(); var s = 0.0; for (v in x) s += (v - m) * (v - m); return sqrt(s / x.size) }
    fun add(out: DoubleArray, y: DoubleArray, k: Double = 1.0) { val e = min(out.size, y.size); for (i in 0 until e) out[i] += k * y[i] }
    fun scaled(x: DoubleArray, k: Double) = DoubleArray(x.size) { x[it] * k }
    fun fit(x: DoubleArray, n: Int): DoubleArray = if (x.size == n) x else DoubleArray(n) { if (it < x.size) x[it] else 0.0 }

    // ------------------------------------------------------------------ filters

    /** A cascade of biquads, each (b0, b1, b2, a1, a2) with a0 = 1, run in direct form I (scipy's sosfilt). */
    class Sos(val sections: List<DoubleArray>) {
        fun run(x: DoubleArray): DoubleArray {
            var y = x
            for (s in sections) {
                val out = DoubleArray(y.size)
                var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
                for (i in y.indices) {
                    val v = s[0] * y[i] + s[1] * x1 + s[2] * x2 - s[3] * y1 - s[4] * y2
                    x2 = x1; x1 = y[i]; y2 = y1; y1 = v; out[i] = v
                }
                y = out
            }
            return y
        }
        /** scipy's sosfiltfilt without its edge padding: forward, then backward. */
        fun runTwice(x: DoubleArray): DoubleArray = run(run(x).reversedArray()).reversedArray()
    }

    private class C(val re: Double, val im: Double) {
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        operator fun minus(o: C) = C(re - o.re, im - o.im)
        operator fun times(o: C) = C(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun times(k: Double) = C(re * k, im * k)
        operator fun div(o: C): C { val d = o.re * o.re + o.im * o.im; return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d) }
        fun sqrt(): C { val m = kotlin.math.sqrt(re * re + im * im); val r = kotlin.math.sqrt((m + re) / 2); val i = kotlin.math.sqrt(max(0.0, (m - re) / 2)); return C(r, if (im < 0) -i else i) }
        fun abs() = kotlin.math.sqrt(re * re + im * im)
    }

    enum class Kind { LOW, HIGH, BAND }

    /**
     * scipy's `butter(order, Wn, btype, output="sos")`: the Butterworth analog prototype, moved to a low-, high-
     * or band-pass at prewarped edges, then the bilinear transform. The gain is set where scipy's is exactly 1
     * (DC, Nyquist, or the band's centre), so the two agree to rounding. Orders used here: 1 and 2 (low, high),
     * 2 and 4 (band).
     */
    fun butter(order: Int, kind: Kind, f1: Double, f2: Double, fs: Double): Sos {
        val k2 = 2.0 * fs
        val proto = List(order) { k -> val th = PI * (2 * k + 1 + order) / (2 * order); C(cos(th), sin(th)) }
        val w1 = k2 * tan(PI * f1 / fs)
        val poles = ArrayList<C>()
        when (kind) {
            Kind.LOW -> proto.forEach { poles.add(it * w1) }
            Kind.HIGH -> proto.forEach { poles.add(C(w1, 0.0) / it) }
            Kind.BAND -> {
                val w2 = k2 * tan(PI * f2 / fs)
                val bw = w2 - w1
                val w0sq = w1 * w2
                for (p in proto) {
                    val pl = p * (bw / 2)
                    val disc = (pl * pl - C(w0sq, 0.0)).sqrt()
                    poles.add(pl + disc); poles.add(pl - disc)
                }
            }
        }
        val z = poles.map { (C(k2, 0.0) + it) / (C(k2, 0.0) - it) }
        // Pair conjugates into second-order sections; a real pole left over makes a first-order section.
        val upper = z.filter { it.im > 1e-12 }
        val real = z.filter { abs(it.im) <= 1e-12 }
        val secs = ArrayList<DoubleArray>()
        for (p in upper) {
            val num = when (kind) { Kind.LOW -> doubleArrayOf(1.0, 2.0, 1.0); Kind.HIGH -> doubleArrayOf(1.0, -2.0, 1.0); Kind.BAND -> doubleArrayOf(1.0, 0.0, -1.0) }
            secs.add(doubleArrayOf(num[0], num[1], num[2], -2 * p.re, p.re * p.re + p.im * p.im))
        }
        var i = 0
        while (i < real.size) {
            if (i + 1 < real.size && kind == Kind.BAND) {
                val a = real[i].re; val b = real[i + 1].re
                secs.add(doubleArrayOf(1.0, 0.0, -1.0, -(a + b), a * b)); i += 2
            } else {
                val a = real[i].re
                val num = if (kind == Kind.HIGH) doubleArrayOf(1.0, -1.0, 0.0) else doubleArrayOf(1.0, 1.0, 0.0)
                secs.add(doubleArrayOf(num[0], num[1], num[2], -a, 0.0)); i += 1
            }
        }
        // Normalise: |H| = 1 at DC (low), Nyquist (high), or the digital image of the analog centre (band).
        val wRef = when (kind) {
            Kind.LOW -> 0.0
            Kind.HIGH -> PI
            Kind.BAND -> 2 * atan(sqrt(w1 * k2 * tan(PI * f2 / fs)) / k2)
        }
        val zr = C(cos(wRef), sin(wRef))
        val zi = C(1.0, 0.0) / zr
        var g = 1.0
        for (s in secs) {
            val num = C(s[0], 0.0) + zi * s[1] + zi * zi * s[2]
            val den = C(1.0, 0.0) + zi * s[3] + zi * zi * s[4]
            g *= (num / den).abs()
        }
        val first = secs[0]
        for (j in 0..2) first[j] /= g
        return Sos(secs)
    }

    fun lp(x: DoubleArray, hz: Double, fs: Double, order: Int = 2) = butter(order, Kind.LOW, min(hz, 0.45 * fs), 0.0, fs).run(x)
    fun hp(x: DoubleArray, hz: Double, fs: Double, order: Int = 2) = butter(order, Kind.HIGH, hz, 0.0, fs).run(x)
    fun band(x: DoubleArray, lo: Double, hi: Double, fs: Double, order: Int = 2) = butter(order, Kind.BAND, lo, min(hi, 0.45 * fs), fs).run(x)

    /** RBJ peaking EQ, as the prototype's `peaking`. */
    fun peaking(f0: Double, gainDb: Double, q: Double, fs: Double): Sos {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * f0 / fs
        val al = sin(w) / (2 * q)
        val a0 = 1 + al / a
        return Sos(listOf(doubleArrayOf((1 + al * a) / a0, -2 * cos(w) / a0, (1 - al * a) / a0, -2 * cos(w) / a0, (1 - al / a) / a0)))
    }

    /** RBJ high shelf, as the prototype's `high_shelf`. */
    fun highShelf(f0: Double, gainDb: Double, fs: Double, slope: Double = 0.7): Sos {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * f0 / fs
        val al = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        val c = cos(w)
        val sa = 2 * sqrt(a) * al
        val b0 = a * ((a + 1) + (a - 1) * c + sa); val b1 = -2 * a * ((a - 1) + (a + 1) * c); val b2 = a * ((a + 1) + (a - 1) * c - sa)
        val a0 = (a + 1) - (a - 1) * c + sa; val a1 = 2 * ((a - 1) - (a + 1) * c); val a2 = (a + 1) - (a - 1) * c - sa
        return Sos(listOf(doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)))
    }

    /** scipy's `iirpeak(w0, Q)` with w0 in hertz. */
    fun iirPeak(f0: Double, q: Double, fs: Double): Sos {
        val w0 = 2 * PI * f0 / fs
        val beta = tan(w0 / q / 2)
        val gain = 1.0 / (1.0 + beta)
        return Sos(listOf(doubleArrayOf(1 - gain, 0.0, -(1 - gain), -2 * gain * cos(w0), 2 * gain - 1)))
    }

    /** The prototype's `env_follow`: a one-pole smoother of |x|, time constant [ms]. */
    fun envFollow(x: DoubleArray, ms: Double, fs: Double): DoubleArray {
        val a = exp(-1.0 / (ms * 1e-3 * fs))
        val y = DoubleArray(x.size)
        var s = 0.0
        for (i in x.indices) { s = (1 - a) * abs(x[i]) + a * s; y[i] = s }
        return y
    }

    // ------------------------------------------------------------------ convolution and rates

    /** Linear convolution of [x] and [h], truncated to [n]: direct over the non-zero samples of [x] when that is cheap, by FFT otherwise. */
    fun conv(x: DoubleArray, h: DoubleArray, n: Int): DoubleArray {
        var nz = 0
        for (v in x) if (v != 0.0) nz++
        val hl = min(h.size, n)
        if (nz.toLong() * hl <= 40_000_000L) {
            val out = DoubleArray(n)
            for (i in x.indices) {
                val a = x[i]
                if (a == 0.0 || i >= n) continue
                val e = min(hl, n - i)
                for (j in 0 until e) out[i + j] += a * h[j]
            }
            return out
        }
        return convFft(x, h, n, null)
    }

    /** FFT convolution; [hSpectrum] is [h]'s transform at this size when the caller has one cached. */
    fun convFft(x: DoubleArray, h: DoubleArray, n: Int, hSpectrum: Pair<FloatArray, FloatArray>?): DoubleArray {
        val size = fftSize(x.size + h.size - 1)
        val xr = FloatArray(size); val xi = FloatArray(size)
        for (i in x.indices) xr[i] = x[i].toFloat()
        Fft.forward(xr, xi)
        val (hr, hi) = hSpectrum ?: spectrum(h, size)
        for (i in 0 until size) {
            val r = xr[i] * hr[i] - xi[i] * hi[i]
            val im = xr[i] * hi[i] + xi[i] * hr[i]
            xr[i] = r; xi[i] = im
        }
        Fft.inverse(xr, xi)
        return DoubleArray(n) { if (it < size) xr[it].toDouble() else 0.0 }
    }

    fun fftSize(len: Int): Int { var s = 1; while (s < len) s = s shl 1; return s }

    fun spectrum(h: DoubleArray, size: Int): Pair<FloatArray, FloatArray> {
        val hr = FloatArray(size); val hi = FloatArray(size)
        for (i in h.indices) hr[i] = h[i].toFloat()
        Fft.forward(hr, hi)
        return hr to hi
    }

    /**
     * The prototype's `to_internal`, scipy's `resample_poly(y, 4, 1)` itself: 44.1 kHz into the 176.4 kHz chain
     * through the same 81-tap Kaiser (beta 5) low-pass at a quarter band, zero delay, run polyphase.
     */
    fun toInternal(y44: DoubleArray, n: Int): DoubleArray {
        val h = UP4
        val out = DoubleArray(n)
        val len = y44.size
        for (m in 0 until min(n, 4 * len)) {
            val i = m shr 2; val p = m and 3
            var acc = 0.0
            var j = p; var src = i + 10
            while (j < h.size) {
                if (src in 0 until len) acc += h[j] * y44[src]
                j += 4; src--
            }
            out[m] = acc
        }
        return out
    }

    private val UP4: DoubleArray by lazy {
        fun i0(x: Double): Double { var sum = 1.0; var term = 1.0; var k = 1; while (term > 1e-17 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }; return sum }
        val taps = 81; val half = 40; val beta = 5.0
        val h = DoubleArray(taps) { k ->
            val x = 0.25 * (k - half)
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            sinc * i0(beta * sqrt(1 - (2.0 * k / (taps - 1) - 1).pow(2))) / i0(beta)
        }
        val sum = h.sum()
        DoubleArray(taps) { 4 * h[it] / sum }
    }

    /** The prototype's `to_rate`: a 176.4 kHz contact force down to 44.1 kHz, each impulse's area kept (sum of four). */
    fun toRate(x: DoubleArray): DoubleArray = DoubleArray(x.size / 4) { x[4 * it] + x[4 * it + 1] + x[4 * it + 2] + x[4 * it + 3] }

    // ------------------------------------------------------------------ modes

    /**
     * The prototype's `modal_ir`: a sum of decaying sines, a_k exp(-6.9 t / t60_k) sin(2 pi f_k w(t) + ph_k), each
     * mode run until it is 80 dB down. [warp] is the time the phase runs on (null for a fixed pitch), so a sag or
     * a bend glides every mode together. Modes outside 15 Hz to 19.5 kHz, with no decay or no amplitude, are skipped.
     */
    fun modalIr(freqs: DoubleArray, amps: DoubleArray, t60s: DoubleArray, n: Int, fs: Double, phases: DoubleArray? = null, warp: DoubleArray? = null): DoubleArray {
        val out = DoubleArray(n)
        for (k in freqs.indices) {
            val f = freqs[k]; val a = amps[k]; val t60 = t60s[k]
            if (f >= 19_500 || f <= 15 || t60 <= 0 || a == 0.0) continue
            val ph = phases?.get(k) ?: 0.0
            val m = min(n, (t60 * 80 / 60 * fs).toInt() + 1)
            val decay = exp(-6.9078 / (t60 * fs))
            var env = a
            if (warp == null) {
                // A rotating phasor: the same sines as sin(2 pi f t + ph), without a sin call per sample.
                val dth = 2 * PI * f / fs
                val cd = cos(dth); val sd = sin(dth)
                var c = cos(ph); var s = sin(ph)
                for (i in 0 until m) {
                    out[i] += env * s
                    val c2 = c * cd - s * sd
                    s = s * cd + c * sd; c = c2
                    env *= decay
                }
            } else {
                for (i in 0 until m) { out[i] += env * sin(2 * PI * f * warp[i] + ph); env *= decay }
            }
        }
        return out
    }

    /** J_m(x) by the trapezoid rule on Bessel's integral (exact to ~1e-12 here: x under 20, 64 points). */
    fun besselJ(m: Int, x: Double): Double {
        val n = 64
        var s = 0.0
        for (k in 0 until n) { val tau = 2 * PI * k / n; s += cos(m * tau - x * sin(tau)) }
        return s / n
    }

    /** A raised-cosine contact force with unit area times [amount], at [atS] (the prototype's `hann_force`). */
    fun hannForce(widthS: Double, amount: Double, n: Int, atS: Double, fs: Double): DoubleArray {
        val f = DoubleArray(n)
        addHannForce(f, widthS, amount, atS, fs)
        return f
    }

    fun addHannForce(f: DoubleArray, widthS: Double, amount: Double, atS: Double, fs: Double) {
        val n = f.size
        val w = max(3, (widthS * fs).toInt())
        val i0 = (atS * fs).toInt()
        if (i0 >= n || i0 < 0) return
        var sum = 0.0
        for (j in 0 until w) sum += 0.5 * (1 - cos(2 * PI * j / w))
        val k = amount / sum * 40.0
        val e = min(n, i0 + w)
        for (j in i0 until e) f[j] += 0.5 * (1 - cos(2 * PI * (j - i0) / w)) * k
    }
}
