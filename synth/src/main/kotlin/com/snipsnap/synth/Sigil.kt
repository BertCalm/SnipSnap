package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

/**
 * SIGIL's twenty voices and its four prints: a port of `prototype/sigil/sigil_proto.py`, which the owner heard and
 * approved (round 0, 2026-11-20: "Pretty good"; KILN round 0b: "Sounds good"). The kits are [SigilKits].
 *
 * The brief: impactful, dark, centred kick and snare (measured against the owner's 23 reference loops in
 * `docs/SIGIL.md`) under percussion that is part organic (wood, glass, beads, skin, breath) and part synthetic
 * (pitch-swept sines, FM, sample-crushed grains). Every percussion voice is both halves in one strike.
 *
 * Every voice runs at the house's 4x rate ([FS]) so the drive stages fold their harmonics above the band the
 * decimator removes, and every voice ends in [finish], the house output stage. The filters are scipy's Butterworth
 * designs ([CalabashDsp.butter], held to scipy's coefficients by `CalabashDspTest`). The draws are Kotlin's generator
 * ([CalabashDsp.Rng]), not numpy's: a Kotlin hit is another take of the approved pad, never the same samples, as for
 * every ported kit family (CALABASH, CLUTTER, SCUFF). `prototype/sigil/compare_kotlin.py` holds the two apart by no
 * more than two takes of one language are.
 *
 * One change of arithmetic, not of sound: the prototype calls sin() per sample per partial; here a constant-frequency
 * partial is a rotating phasor ([ring]), equal to rounding error and far cheaper (a hat is six squares of up to 48
 * odd harmonics each, which would otherwise be twelve million sin calls a hit).
 */
internal object Sigil {
    const val RATE = Dsp.RATE
    const val FS = Dsp.RATE * Dsp.OVERSAMPLE
    private const val TAU = 2 * PI

    /** A minor: every tuned pad sits in it (the owner's tonal loops are labelled Am, F, C and E, all inside it). */
    const val A2 = 110.0; const val C3 = 130.81; const val D3 = 146.83; const val E3 = 164.81; const val G3 = 196.0
    const val A3 = 220.0; const val A4 = 440.0; const val C5 = 523.25; const val E5 = 659.26

    /** A pad's constants as `kits.json` holds them: numbers, the shaker's band, a vowel. */
    class P(private val m: Map<String, Any>) {
        fun d(k: String): Double = (m[k] as? Number)?.toDouble() ?: error("SIGIL param '$k' missing")
        fun d(k: String, default: Double): Double = (m[k] as? Number)?.toDouble() ?: default
        fun s(k: String): String = m[k] as? String ?: error("SIGIL param '$k' missing")
        @Suppress("UNCHECKED_CAST")
        fun pair(k: String): DoubleArray = (m[k] as List<Number>).map { it.toDouble() }.toDoubleArray()
        val len: Double get() = d("len")
        fun with(k: String, v: Double) = P(m + (k to v))
    }

    fun rngFor(vararg parts: Any) = CalabashDsp.Rng(Dsp.seedFor("SIGIL", *parts))

    // ------------------------------------------------------------------ small tools (the prototype's)

    fun nOf(seconds: Double): Int = round(seconds * FS).toInt()

    private fun lp(x: DoubleArray, hz: Double, order: Int = 2, fs: Int = FS) = CalabashDsp.lp(x, hz, order, fs)
    private fun hp(x: DoubleArray, hz: Double, order: Int = 2, fs: Int = FS) = CalabashDsp.hp(x, hz, order, fs)
    private fun band(x: DoubleArray, lo: Double, hi: Double, order: Int = 2, fs: Int = FS) = CalabashDsp.band(x, lo, hi, order, fs)

    /** exp(-(t - hold) / tau) after [hold] seconds, 1 before it. */
    fun envExp(n: Int, tau: Double, hold: Double = 0.0): DoubleArray {
        val tt = max(tau, 1e-4)
        val out = DoubleArray(n)
        // samples before the hold are 1; from the first one at or past it, the exact exponential, stepped by k
        var i = 0
        while (i < n && i.toDouble() / FS < hold) { out[i] = 1.0; i++ }
        if (i < n) {
            val k = exp(-1.0 / (tt * FS))
            var g = exp(-(i.toDouble() / FS - hold) / tt)
            while (i < n) { out[i] = g; g *= k; i++ }
        }
        return out
    }

    /** A short raised-cosine onset, so no voice starts on a step. */
    fun attack(n: Int, ms: Double): DoubleArray {
        val k = max(1, (ms * 1e-3 * FS).toInt())
        val e = DoubleArray(n) { 1.0 }
        for (i in 0 until min(k, n)) e[i] = 0.5 - 0.5 * cos(PI * i / (k - 1).coerceAtLeast(1))
        return e
    }

    /** sin of an oscillator whose frequency follows [f] (Hz per internal sample), times [gain] if given. */
    fun swept(f: DoubleArray, offset: Double = 0.0): DoubleArray {
        val out = DoubleArray(f.size)
        var ph = 0.0
        for (i in f.indices) { ph += TAU * f[i] / FS; out[i] = sin(ph + offset) }
        return out
    }

    /** a * exp(-t * 6.91 / t60) * sin(2 pi f t + phase), accumulated into [out] by a rotating phasor. */
    fun ring(out: DoubleArray, f: Double, a: Double, t60: Double, phase: Double = 0.0) {
        val w = TAU * f / FS
        val r = if (t60.isInfinite()) 1.0 else exp(-6.91 / (t60 * FS))
        val cr = r * cos(w); val ci = r * sin(w)
        var re = cos(phase) * a; var im = sin(phase) * a
        for (i in out.indices) {
            out[i] += im
            val nr = re * cr - im * ci
            im = re * ci + im * cr
            re = nr
        }
    }

    /** Struck modes as decaying sines; [bright] weights the upper modes (a harder strike). */
    fun modal(n: Int, freqs: DoubleArray, amps: DoubleArray, t60s: DoubleArray, bright: Double = 1.0,
              rng: CalabashDsp.Rng? = null, phaseJitter: Double = 0.0): DoubleArray {
        val y = DoubleArray(n)
        for (i in freqs.indices) {
            val f = freqs[i]
            if (f >= RATE / 2.0 * 0.95) continue
            val w = amps[i] * bright.pow(ln(max(f / freqs[0], 1.0)) / ln(2.0))
            val ph = rng?.uniform(-phaseJitter, phaseJitter) ?: 0.0
            ring(y, f, w, t60s[i], ph)
        }
        return y
    }

    /** tanh saturation; [asym] biases it so even harmonics appear (a cone or a tape pushed off-centre). */
    fun drive(x: DoubleArray, amount: Double, asym: Double = 0.0): DoubleArray {
        if (amount <= 0) return x
        val g = 1 + 9 * amount
        val off = tanh(g * asym)
        val norm = tanh(g)
        return DoubleArray(x.size) { (tanh(g * (x[it] + asym)) - off) / norm }
    }

    /** Sample-and-hold to a lower rate with no anti-alias filter (the synthetic grain), optionally to [bits]. */
    fun crush(x: DoubleArray, keepHz: Double, bits: Int = 0): DoubleArray {
        val step = max(1, (FS / keepHz).toInt())
        val q = if (bits > 0) 2.0.pow(bits - 1) else 0.0
        return DoubleArray(x.size) {
            val y = x[(it / step) * step]
            if (bits > 0) Math.rint(y * q) / q else y
        }
    }

    fun norm(x: DoubleArray, peak: Double = 1.0): DoubleArray {
        var m = 0.0
        for (v in x) m = max(m, abs(v))
        return if (m > 0) DoubleArray(x.size) { x[it] * peak / m } else x
    }

    private fun mix(vararg parts: Pair<Double, DoubleArray>): DoubleArray {
        val n = parts.maxOf { it.second.size }
        val y = DoubleArray(n)
        for ((k, x) in parts) for (i in x.indices) y[i] += k * x[i]
        return y
    }

    private fun times(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] * b[it] }

    private fun noise(rng: CalabashDsp.Rng, n: Int) = rng.normals(n)

    /** The house output stage: band-limit at 4x, decimate, mean out, a 20 Hz one-pole high-pass, cut, 4 ms fade. */
    fun finish(raw: DoubleArray, seconds: Double? = null): FloatArray {
        val f = FloatArray(raw.size) { raw[it].toFloat() }
        Tide.bandLimit(f, FS)
        val dec = Dsp.decimate(f, RATE)
        var mean = 0.0
        for (v in dec) mean += v
        mean /= dec.size.coerceAtLeast(1)
        val len = if (seconds == null) dec.size else min(dec.size, (seconds * RATE).toInt())
        val rc = 1 / (TAU * 20.0)
        val al = rc / (rc + 1.0 / RATE)
        val out = FloatArray(len)
        var px = 0.0; var py = 0.0
        for (i in 0 until len) { val xi = dec[i] - mean; py = al * (py + xi - px); px = xi; out[i] = py.toFloat() }
        val fade = min(len, (4e-3 * RATE).toInt())
        for (q in 0 until fade) out[len - fade + q] *= (1.0 - q / (fade - 1).coerceAtLeast(1).toDouble()).toFloat()
        return out
    }

    // ------------------------------------------------------------------ drums

    /** A sub drop: a sine falling from f_start to f_end, a beater click and a short knock (the references: 105 to 135
     *  Hz at the hit, 36 to 60 Hz 80 ms later, 20 dB down in 70 to 150 ms). A soft hit sweeps less and drives less. */
    fun kick(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val tune = 1 + rng.normal(0.0, 0.006)
        val f1 = p.d("f_end") * tune
        val f0 = f1 + (p.d("f_start") * tune - f1) * (0.55 + 0.45 * v)
        val n = nOf(p.len)
        val tau = p.d("sweep") * (1 + rng.normal(0.0, 0.05))
        val f = DoubleArray(n) { f1 + (f0 - f1) * exp(-(it.toDouble() / FS) / tau) }
        val body = times(swept(f), envExp(n, p.d("decay") * (1 + rng.normal(0.0, 0.04)), p.d("hold", 0.0)))
        val click = times(band(noise(rng, n), 1500.0, 6000.0), envExp(n, 0.0025))
        for (i in click.indices) click[i] *= 0.35
        ring(click, 1800.0, 0.4, 0.0015 * 6.91)
        val knock = DoubleArray(n)
        ring(knock, p.d("knock_hz", 210.0) * tune, p.d("knock", 0.15), 0.022 * 6.91)
        val y = mix(1.0 to body, p.d("click") * v.pow(1.5) to click, (0.6 + 0.4 * v) to knock)
        return drive(times(y, attack(n, 0.3)), p.d("drive") * (0.55 + 0.45 * v), p.d("asym", 0.0))
    }

    /** Two head modes, wires, a crack and a short dark tail; `wire_lp` is the darkness (the references' snares sit
     *  at 1.1 to 1.8 kHz, the crack at 2.4). */
    fun snare(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val tune = p.d("tune") * (1 + rng.normal(0.0, 0.008))
        val drop = DoubleArray(n) { 1 + 0.08 * v * exp(-(it.toDouble() / FS) / 0.012) }
        val h1 = times(swept(DoubleArray(n) { 185 * tune * drop[it] }), envExp(n, p.d("head_decay")))
        val h2 = times(swept(DoubleArray(n) { 328 * tune * drop[it] }, 0.7), envExp(n, p.d("head_decay") * 0.6))
        val head = mix(1.0 to h1, 0.55 to h2)
        val nz = noise(rng, n)
        val lpHz = p.d("wire_lp") * (0.6 + 0.4 * v)
        val wires = times(lp(hp(nz, 450.0), lpHz, 2), envExp(n, p.d("wire_decay") * (1 + rng.normal(0.0, 0.05))))
        val crack = times(hp(nz, 1800.0), envExp(n, 0.004))
        val tailEnv = envExp(n, p.d("tail")); val rise = envExp(n, 0.006)
        val tailSrc = lp(band(noise(rng, n), 300.0, p.d("wire_lp") * 0.7), 2500.0)
        val tail = DoubleArray(n) { tailSrc[it] * tailEnv[it] * (1 - rise[it]) * p.d("tail_gain", 0.35) }
        val y = mix(p.d("head") to head, p.d("wires") to wires, p.d("crack") * v.pow(1.3) to crack, 1.0 to tail)
        return drive(times(y, attack(n, 0.2)), p.d("drive") * (0.5 + 0.5 * v))
    }

    /** Four noise bursts 7 to 11 ms apart; the last one partly sample-crushed. */
    fun clap(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val y = DoubleArray(n)
        var at = 0
        for (k in 0 until 4) {
            var b = times(band(noise(rng, n), 900.0, 2600.0), envExp(n, if (k < 3) 0.005 else p.d("decay")))
            b = shifted(b, at)
            if (k == 3) { val c = crush(b, 9000.0, 8); b = DoubleArray(n) { 0.6 * b[it] + 0.4 * c[it] } }
            val g = 0.7 + 0.3 * rng.random()
            for (i in 0 until n) y[i] += b[i] * g
            at += (FS * (0.007 + 0.004 * rng.random())).toInt()
        }
        for (i in 0 until n) y[i] *= 0.7 + 0.3 * v
        return drive(y, p.d("drive", 0.3))
    }

    /** numpy's roll-then-zero: [x] moved [at] samples later, the end dropped. */
    private fun shifted(x: DoubleArray, at: Int): DoubleArray {
        if (at <= 0) return x
        val y = DoubleArray(x.size)
        for (i in at until x.size) y[i] = x[i - at]
        return y
    }

    private val HAT_HZ = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)

    /** Six band-limited squares (the drum-machine metal, additive to 20 kHz) mixed with noise; a soft stick closes the top. */
    fun hat(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val ratio = p.d("ratio") * (1 + rng.normal(0.0, 0.004))
        val metal = DoubleArray(n)
        for (base in HAT_HZ) {
            val f = base * ratio
            var k = 1
            while (f * k < 20000) { ring(metal, f * k, 1.0 / k, Double.POSITIVE_INFINITY, rng.uniform(0.0, 6.28)); k += 2 }
        }
        val nz = noise(rng, n)
        var y = DoubleArray(n) { p.d("metal") * metal[it] / 6 + (1 - p.d("metal")) * nz[it] }
        y = hp(y, p.d("hp") * (0.8 + 0.2 * v), 4)
        y = lp(y, p.d("lp") * (0.45 + 0.55 * v), 2)
        val e = envExp(n, p.d("decay") * (0.8 + 0.2 * v) * (1 + rng.normal(0.0, 0.05)))
        val a = attack(n, 0.15)
        return DoubleArray(n) { y[it] * e[it] * a[it] }
    }

    // ------------------------------------------------------------------ hybrid percussion

    /** A sine bending down into the note (the synthetic half) over a free bar's modes struck by a mallet (the wood). */
    fun logDrum(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f = p.d("note") * (1 + rng.normal(0.0, 0.002))
        // each take lands the mallet a little differently: the bend, its glide and the ring move a few percent
        val bend = 2.0.pow(p.d("bend") * (0.6 + 0.4 * v) * (1 + rng.normal(0.0, 0.08)) / 12)
        val glide = p.d("glide", 0.018) * (1 + rng.normal(0.0, 0.1))
        val synth = times(swept(DoubleArray(n) { f * (1 + (bend - 1) * exp(-(it.toDouble() / FS) / glide)) }),
            envExp(n, p.d("decay") * (1 + rng.normal(0.0, 0.05))))
        val wood = modal(n, doubleArrayOf(f, f * 2.756, f * 5.404), doubleArrayOf(1.0, 0.45, 0.2),
            doubleArrayOf(p.d("decay") * 3, p.d("decay") * 1.2, 0.05), 0.5 + 0.5 * v, rng, 0.5)
        val mallet = times(band(noise(rng, n), f * 4, min(f * 30, 12000.0)), envExp(n, 0.0025))
        val y = mix((1 - p.d("wood")) to synth, p.d("wood") to norm(wood), 0.4 * v * v to mallet)
        val a = attack(n, 0.25)
        return drive(DoubleArray(n) { y[it] * a[it] * (0.4 + 0.6 * v) }, p.d("drive", 0.35))
    }

    /** FM (modulator at 3.51x, index falling) beside glass modes (1, 2.32, 4.25, 6.63). */
    fun glassBell(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f = p.d("note") * (1 + rng.normal(0.0, 0.002))
        val e = envExp(n, p.d("decay"))
        val fm = DoubleArray(n) {
            val t = it.toDouble() / FS
            val index = (0.3 + 3.5 * v) * exp(-t / 0.05) + 0.4
            sin(TAU * f * t + index * sin(TAU * f * 3.51 * t)) * e[it]
        }
        val d = p.d("decay")
        val glass = modal(n, doubleArrayOf(f, f * 2.32, f * 4.25, f * 6.63), doubleArrayOf(1.0, 0.6, 0.35, 0.2),
            doubleArrayOf(d * 4, d * 2, d, d * 0.5), 0.4 + 0.6 * v, rng, 1.0)
        val tick = times(hp(noise(rng, n), 5000.0), envExp(n, 0.001))
        return times(mix((1 - p.d("glass")) to fm, p.d("glass") to norm(glass), 0.3 * v to tick), attack(n, 0.1))
    }

    /** A thumb tine (clamped-bar modes 1, 6.27, 17.55) with its buzz; its shadow is the octave crushed to 6 bits. */
    fun tine(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f = p.d("note") * (1 + rng.normal(0.0, 0.002))
        val d = p.d("decay")
        val y = modal(n, doubleArrayOf(f, f * 6.27, f * 17.55), doubleArrayOf(1.0, 0.25 * v, 0.08 * v),
            doubleArrayOf(d, d * 0.2, 0.03), 1.0, rng, 0.3)
        val bz = band(noise(rng, n), 2000.0, 6000.0)
        val buzz = DoubleArray(n) { bz[it] * abs(y[it]) * 0.6 * p.d("buzz", 0.5) * v }
        val oct = DoubleArray(n)
        ring(oct, f * 2, 1.0, Double.POSITIVE_INFINITY)
        val shadow = crush(times(oct, envExp(n, d * 0.4)), 11025.0, 6)
        return times(mix(1.0 to y, 1.0 to buzz, p.d("shadow") * v to shadow), attack(n, 0.2))
    }

    /** Tick-tock: two tiny wooden clicks a few ms apart, the second sample-crushed. */
    fun clockTick(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f = p.d("tune") * (1 + rng.normal(0.0, 0.01))
        val one = modal(n, doubleArrayOf(f, f * 1.62, f * 2.51), doubleArrayOf(1.0, 0.6, 0.35), doubleArrayOf(0.025, 0.018, 0.01), 0.5 + 0.5 * v)
        val gap = (FS * (p.d("gap", 0.006) + rng.uniform(-0.0008, 0.0008))).toInt()
        val two = shifted(crush(one, 8000.0, 7), gap)
        return times(mix(1.0 to one, 0.55 to two), attack(n, 0.05))
    }

    /** Bead grains under a push (shape 0) or pull (shape 1) envelope; a share of them single held pulses (digital beads). */
    fun shaker(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val shape = p.d("shape")
        val peakT = 0.012 + shape * 0.05
        val e = DoubleArray(n) {
            val t = it.toDouble() / FS
            if (t < peakT) (t / peakT).pow(1 + 2 * shape) else exp(-(t - peakT) / p.d("decay"))
        }
        val rate = p.d("density", 2500.0) * (0.5 + 0.5 * v)
        val hit = DoubleArray(n) { rng.random() }
        val sgn = rng.signs(n)
        val amp = rng.uniform(0.3, 1.0, n)
        val grains = DoubleArray(n) { (if (hit[it] < rate / FS) 1.0 else 0.0) * sgn[it] * amp[it] }
        val b = p.pair("band")
        val beads = hp(band(grains, b[0] * (0.7 + 0.3 * v), b[1] * (0.55 + 0.45 * v), 2), 2500.0)
        val digital = hp(DoubleArray(n).also { val c = crush(grains, 6000 + 8000 * v); for (i in 0 until n) it[i] = c[i] * 0.8 }, 3000.0)
        val y = mix((1 - p.d("digital")) to norm(beads), p.d("digital") to norm(digital))
        return times(y, e)
    }

    /** Seconds from a shaker pad's start to its loudest point: a pull swells into the beat, so a player starts it early. */
    fun shakerLead(p: P): Double = p.d("shape") * 0.05

    /** A soft strike is darker on every pad: a low-pass opening with velocity under v^2 of the dry hit. */
    fun touchTone(x: DoubleArray, v: Double): DoubleArray {
        if (v >= 0.99) return x
        val dark = lp(x, 600 * 40.0.pow(v), 2)
        val k = v * v
        return DoubleArray(x.size) { k * x[it] + (1 - k) * dark[it] }
    }

    /** A wooden knock with a synthetic upward chirp riding it. */
    fun woodChirp(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f0 = p.d("tune") * (1 + rng.normal(0.0, 0.01))
        val chirp = times(swept(DoubleArray(n) { f0 * (1 + p.d("chirp") * (1 - exp(-(it.toDouble() / FS) / 0.02))) }), envExp(n, p.d("decay")))
        val knock = modal(n, doubleArrayOf(f0 * 0.5, f0 * 1.31, f0 * 2.2), doubleArrayOf(1.0, 0.5, 0.3), doubleArrayOf(0.06, 0.03, 0.02), 0.4 + 0.6 * v)
        return times(mix(0.55 to chirp, 0.45 to norm(knock)), attack(n, 0.1))
    }

    private val FORMANTS = mapOf("o" to doubleArrayOf(450.0, 800.0, 2830.0), "a" to doubleArrayOf(730.0, 1090.0, 2440.0),
        "u" to doubleArrayOf(325.0, 700.0, 2530.0), "e" to doubleArrayOf(530.0, 1840.0, 2480.0))

    /** A breath or half-voiced vowel through three formants, a little ring-modulated. No words. */
    fun breath(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val tEnd = max((n - 1).toDouble() / FS, 1e-3)
        val pitch = p.d("pitch") * (1 + rng.normal(0.0, 0.02))
        val f0 = DoubleArray(n) { pitch * (1 - 0.08 * (it.toDouble() / FS) / tEnd) }
        // scipy's sawtooth(phase): 2 * (phase / 2pi mod 1) - 1
        var ph = 0.0
        val voiced = p.d("voiced")
        val nz = noise(rng, n)
        val src = DoubleArray(n) {
            ph += TAU * f0[it] / FS
            val frac = (ph / TAU) - kotlin.math.floor(ph / TAU)
            (2 * frac - 1) * voiced + (1 - voiced) * nz[it]
        }
        val y = DoubleArray(n)
        for ((k, fc) in FORMANTS.getValue(p.s("vowel")).withIndex()) {
            val b = CalabashDsp.butter(2, CalabashDsp.Kind.BAND, fc * 0.88, fc * 1.12, FS).run(src)
            for (i in 0 until n) y[i] += b[i] / (k + 1)
        }
        val ringAmt = p.d("ring"); val ringHz = p.d("ring_hz", 290.0)
        val e = envExp(n, p.d("decay") * (0.8 + 0.2 * v))
        return DoubleArray(n) {
            val t = it.toDouble() / FS
            y[it] * (1 - ringAmt + ringAmt * sin(TAU * ringHz * t)) * min(t / 0.008, 1.0) * e[it]
        }
    }

    /** A droplet: a sine whose pitch rises fast, over a tiny splash. */
    fun waterDrop(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f0 = p.d("tune") * (1 + rng.normal(0.0, 0.02))
        val y = times(swept(DoubleArray(n) { f0 * exp(min(it.toDouble() / FS, 0.05) * p.d("rise")) }), envExp(n, p.d("decay")))
        val splash = times(band(noise(rng, n), 2500.0, 8000.0), envExp(n, 0.004))
        return times(mix(1.0 to y, 0.2 * v to splash), attack(n, 0.3))
    }

    /** The texture pad: noise into a tuned comb that rings, swelling in, with an FM shimmer above it. Not a hit. */
    fun rod(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f = p.d("note")
        val d = (FS / f).toInt()
        val swell = p.d("swell")
        val e = envExp(n, p.d("decay")); val e2 = envExp(n, p.d("decay") * 0.7)
        val src = band(noise(rng, n), f, 9000.0)
        val x = DoubleArray(n) { src[it] * min((it.toDouble() / FS) / swell, 1.0) * e[it] }
        val y = DoubleArray(n)
        for (i in 0 until n) y[i] = x[i] + (if (i >= d) 0.985 * y[i - d] else 0.0)
        val shimmer = DoubleArray(n) {
            val t = it.toDouble() / FS
            sin(TAU * f * 4 * t + 2 * sin(TAU * f * 4 * 1.41 * t)) * 0.15 * v * min(t / swell, 1.0) * e2[it]
        }
        return mix(1.0 to norm(y), 1.0 to shimmer)
    }

    /** Membrane modes struck by hand with a synthetic sub under them; a soft palm is more thump and less skin. */
    fun handDrum(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f = p.d("tune") * (1 + rng.normal(0.0, 0.006))
        val d = p.d("decay")
        val skin = modal(n, doubleArrayOf(1.0, 1.594, 2.136, 2.296, 2.653, 2.918).map { f * it }.toDoubleArray(),
            doubleArrayOf(1.0, 0.7, 0.5, 0.4, 0.3, 0.25), doubleArrayOf(d, d * 0.7, 0.12, 0.1, 0.07, 0.05), 0.15 + 0.85 * v, rng, 0.8)
        val slap = times(band(noise(rng, n), 1500.0, 5000.0), envExp(n, 0.006))
        val sub = times(swept(DoubleArray(n) { f * 0.5 * (1 + 0.5 * exp(-(it.toDouble() / FS) / 0.02)) }), envExp(n, d * 0.8))
        return times(mix((0.55 + 0.45 * v) to norm(skin), p.d("slap") * v * v to slap, p.d("sub") * (1.3 - 0.3 * v) to sub), attack(n, 0.2))
    }

    /** Ten jingles landing a few ms apart over the frame; a soft shake rings fewer of the highest. */
    fun tambourine(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val y = DoubleArray(n)
        val d = p.d("decay")
        repeat(10) {
            val f = rng.uniform(6000.0, 7000 + 4000 * v)
            val j = modal(n, doubleArrayOf(f, f * 1.37, f * 1.82), doubleArrayOf(1.0, 0.6, 0.4), doubleArrayOf(d, d * 0.7, d * 0.5))
            val at = (FS * rng.uniform(0.0, 0.012 + 0.01 * (1 - v))).toInt()
            val g = rng.uniform(0.4, 1.0)
            val s = shifted(j, at)
            for (i in 0 until n) y[i] += s[i] * g
        }
        val frame = modal(n, doubleArrayOf(330.0, 520.0, 910.0), doubleArrayOf(1.0, 0.5, 0.3), doubleArrayOf(0.04, 0.03, 0.02))
        return times(mix((0.6 + 0.4 * v) to norm(y), 0.3 to frame), attack(n, 0.1))
    }

    /** A stick on a wooden rim. */
    fun rim(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f = p.d("tune") * (1 + rng.normal(0.0, 0.008))
        val y = modal(n, doubleArrayOf(f, f * 3.3, f * 6.4), doubleArrayOf(1.0, 0.6, 0.3), doubleArrayOf(0.05, 0.025, 0.012), 0.25 + 0.75 * v, rng, 0.6)
        val click = times(hp(noise(rng, n), 3000.0), envExp(n, 0.0012))
        return times(mix(1.0 to norm(y), 0.5 * v * v to click), attack(n, 0.05))
    }

    /** A short FM ping, the most synthetic hit in the set. */
    fun metalTick(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val f = p.d("tune") * (1 + rng.normal(0.0, 0.004))
        // each take its own strike: the FM depth and the ring vary a little, and a tiny noise edge differs every time
        val depth = (1 + 4 * v) * (1 + rng.normal(0.0, 0.08))
        val e = envExp(n, p.d("decay") * (1 + rng.normal(0.0, 0.06))); val a = attack(n, 0.05)
        val edge = times(hp(noise(rng, n), 4000.0), envExp(n, 0.001))
        return DoubleArray(n) {
            val t = it.toDouble() / FS
            (sin(TAU * f * t + depth * exp(-t / 0.01) * sin(TAU * f * 1.4 * t)) * e[it] + 0.05 * v * edge[it]) * a[it]
        }
    }

    /** A falling sine, driven so it reads on a phone. */
    fun zap(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val fStart = p.d("f_start") * (0.7 + 0.3 * v) * (1 + rng.normal(0.0, 0.03))
        val fEnd = p.d("f_end")
        val sw = p.d("sweep") * (1 + rng.normal(0.0, 0.08))
        val tone = swept(DoubleArray(n) { fEnd + (fStart - fEnd) * exp(-(it.toDouble() / FS) / sw) })
        val grit = times(band(noise(rng, n), 1000.0, 4000.0), envExp(n, 0.01))
        val e = envExp(n, p.d("decay")); val a = attack(n, 0.1)
        return drive(DoubleArray(n) { (tone[it] + 0.08 * v * grit[it]) * e[it] * a[it] }, 0.4)
    }

    /** A finger snap: a short bright burst and the fingers' resonance, part of it sample-crushed. */
    fun snap(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val burst = times(band(noise(rng, n), 1800 * (0.8 + 0.2 * v), 4500 * (0.6 + 0.4 * v)), envExp(n, 0.004))
        val f = 1150 * (1 + rng.normal(0.0, 0.02))
        val flesh = modal(n, doubleArrayOf(f, f * 2.2), doubleArrayOf(1.0, 0.4), doubleArrayOf(0.03, 0.015), 0.4 + 0.6 * v, rng, 1.0)
        val y = mix(1.0 to burst, 0.5 to flesh)
        val c = crush(y, 9000.0, 8)
        val dg = p.d("digital")
        return times(DoubleArray(n) { (1 - dg) * y[it] + dg * c[it] }, attack(n, 0.05))
    }

    /** A scraper: a run of tiny wooden clicks slowing down, every third one sample-crushed. */
    fun scrape(p: P, v: Double, rng: CalabashDsp.Rng): DoubleArray {
        val n = nOf(p.len)
        val y = DoubleArray(n)
        var t = 0.0; var k = 0
        val rate = p.d("rate") * (0.8 + 0.2 * v) * (1 + rng.normal(0.0, 0.05))
        val oneN = nOf(0.012)
        while (t < p.len - 0.015) {
            val f = p.d("tune") * (1 + rng.normal(0.0, 0.04))
            var c = modal(oneN, doubleArrayOf(f, f * 1.7), doubleArrayOf(1.0, 0.5), doubleArrayOf(0.008, 0.005), 0.4 + 0.6 * v)
            if (k % 3 == 2) c = crush(c, 7000.0, 7)
            val at = nOf(t)
            val g = (0.6 + 0.4 * rng.random()) * (1 - 0.5 * t / p.len)
            for (i in 0 until min(oneN, n - at)) y[at + i] += c[i] * g
            t += 1.0 / (rate * (1 - 0.45 * t / p.len))
            k++
        }
        return y
    }

    /** The prototype's voice names, as `kits.json` gives them. */
    val VOICES: Map<String, (P, Double, CalabashDsp.Rng) -> DoubleArray> = mapOf(
        "kick" to ::kick, "snare" to ::snare, "clap" to ::clap, "hat" to ::hat, "log_drum" to ::logDrum,
        "glass_bell" to ::glassBell, "tine" to ::tine, "clock_tick" to ::clockTick, "shaker" to ::shaker,
        "wood_chirp" to ::woodChirp, "breath" to ::breath, "water_drop" to ::waterDrop, "rod" to ::rod,
        "hand_drum" to ::handDrum, "tambourine" to ::tambourine, "rim" to ::rim, "metal_tick" to ::metalTick,
        "zap" to ::zap, "snap" to ::snap, "scrape" to ::scrape,
    )

    // ------------------------------------------------------------------ the prints (each kit's wear)

    /** IRON: a small speaker past its excursion: lopsided clipping, a cone rolling off at 4.5 kHz, a rattle when pushed. */
    fun blownSpeaker(x: DoubleArray, amount: Double): DoubleArray {
        val y = drive(x, amount, 0.12 * amount)
        val env = lp(DoubleArray(x.size) { abs(x[it]) }, 40.0).let { e -> DoubleArray(e.size) { abs(e[it]) } }
        val rattle = band(DoubleArray(x.size) { sign(y[it]) * max(env[it] - 0.35, 0.0) }, 900.0, 3200.0)
        val z = DoubleArray(x.size) { y[it] + rattle[it] * 0.5 * amount }
        return hp(lp(z, 4500.0, 2), 32.0, 2)
    }

    /** NEON: clean and loud, a soft knee into a ceiling. */
    fun slam(x: DoubleArray, amount: Double): DoubleArray = drive(DoubleArray(x.size) { x[it] * (1 + 1.5 * amount) }, 0.25 * amount)

    /** ASH: tape pushed: a head bump at 90 Hz, lopsided saturation, the top rolled off at 10 kHz. */
    fun tape(x: DoubleArray, amount: Double): DoubleArray {
        val (b, a) = CalabashDsp.peaking(90.0, 2.5 * amount, 0.9, FS)
        val y = drive(CalabashDsp.lfilter(b, a, x), 0.45 * amount, 0.05)
        return lp(y, 10000.0, 2)
    }

    /** KILN: an old sampler: 12 bits held near 26 kHz with no filter before the hold, a little drive, the top eased at 13 kHz. */
    fun sampler(x: DoubleArray, amount: Double): DoubleArray {
        val held = crush(x, 26040.0, 12)
        val y = drive(DoubleArray(x.size) { (1 - amount) * x[it] + amount * held[it] }, 0.25 * amount)
        return lp(y, 13000.0, 2)
    }

    fun print(kind: String, x: DoubleArray, amount: Double): DoubleArray = when (kind) {
        "blown" -> blownSpeaker(x, amount)
        "slam" -> slam(x, amount)
        "tape" -> tape(x, amount)
        "sampler" -> sampler(x, amount)
        else -> error("SIGIL has no print '$kind'")
    }

    /** At 44.1 kHz, on a groove: wow (0.6 Hz) and flutter (7 Hz) as a moving delay, hiss near -62 dB, crackle. */
    fun tapeMotion(x: FloatArray, seed: Int, depth: Double = 1.0, cracklePerS: Double = 6.0): FloatArray {
        val rng = CalabashDsp.Rng(seed)
        val n = x.size
        val y = DoubleArray(n) {
            val t = it.toDouble() / RATE
            val delay = (0.0009 * sin(TAU * 0.6 * t) + 0.00012 * sin(TAU * 7 * t)) * depth * RATE
            val idx = (it - 60 - delay).coerceIn(0.0, (n - 1).toDouble())
            val i0 = idx.toInt(); val fr = idx - i0
            val i1 = min(i0 + 1, n - 1)
            x[i0] * (1 - fr) + x[i1] * fr
        }
        val hiss = band(rng.normals(n), 1500.0, 9000.0, 2, RATE)
        val hit = DoubleArray(n) { rng.random() }
        val amp = rng.uniform(-1.0, 1.0, n)
        val crackle = hp(DoubleArray(n) { (if (hit[it] < cracklePerS / RATE) 1.0 else 0.0) * amp[it] * 0.05 * depth }, 2000.0, 2, RATE)
        val g = 10.0.pow(-62.0 / 20) * depth
        return FloatArray(n) { (y[it] + hiss[it] * g + crackle[it]).toFloat() }
    }
}
