package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** The 22 CLUTTER objects, each filed as the kit role it plays. */
enum class ClutterVoice(val drumClass: DrumClass, internal val seconds: Double) {
    BUCKET(DrumClass.KICK, 0.70), BOX(DrumClass.SNARE, 0.45), BOOK(DrumClass.CLAP, 0.40), JAR(DrumClass.HAT_CLOSED, 0.32),
    KEYS(DrumClass.HAT_OPEN, 1.0), TABLE(DrumClass.PERC, 0.50), POT(DrumClass.PERC, 1.40), BOTTLE(DrumClass.PERC, 1.20),
    SUITCASE(DrumClass.KICK, 0.60), MATCHBOX(DrumClass.HAT_CLOSED, 0.35), DRAWER(DrumClass.HAT_OPEN, 1.20), TUB(DrumClass.TOM, 0.60),
    PAN(DrumClass.TOM, 1.40), MUG(DrumClass.PERC, 0.70), GLASS(DrumClass.PERC, 2.0), ZIP(DrumClass.PERC, 0.60),
    STOMP(DrumClass.KICK, 0.60), BAG(DrumClass.CLAP, 0.40), BINLID(DrumClass.HAT_OPEN, 2.0), CAN(DrumClass.PERC, 0.45),
    SWITCH(DrumClass.HAT_CLOSED, 0.25), BOARD(DrumClass.SNARE, 0.40),
}

/**
 * CLUTTER — a found-sound drum engine: everyday objects, modelled rather than sampled.
 *
 * Round 1: the Kotlin port of the round-0c Python prototype (`prototype/clutter/clutter_proto.py`,
 * docs/CLUTTER.md), whose sound and four genre kits the owner accepted on 2026-11-14 ("All are good",
 * "I like it as is"). Every constant here is that prototype's. What could not be the same arithmetic is
 * disclosed where it happens: Kotlin's [Random] in place of numpy's generator (every noise draw and every
 * loose part is another realisation of the same law), and the house band-limit and decimator in place of
 * the prototype's FIR.
 *
 * The gesture: a hand or a spoon hits an everyday object → its body rings → whatever is loose in it or on
 * it rattles a moment later → the room answers, then it is still.
 *
 *     contact pulse (area = impulse, width = TOOL) ─► the object's modal body ─┐
 *            └─► lift = impulse past a FIXED threshold ─► loose parts (grains, a lid, a handle)
 *                    land after 2 v / g and bounce ─► their own modes + a small kick back ┘
 *     at 4x ─► band-limit, decimate ─► MIC: the phone's tone and a kitchen's response ─► DC ─► fade ─► peak
 *
 * - **The loose parts are the instrument.** They only move once the strike accelerates the surface harder
 *   than gravity holds them down, against a threshold that is never normalised per hit, so a soft hit is
 *   a clean knock and a hard one rattles. Velocity changes what happens, not only how loud.
 * - **TOOL is a contact width at a fixed impulse**: a palm and a spoon delivering the same push ring the
 *   low modes alike; only the short contact reaches the high ones. A harder hit shortens a soft contact
 *   more than a metal one (Hunt-Crossley, v^-0.5 down to v^-0.2).
 * - **A take is another hand**: the strike point (which modes it reaches), how the loose parts fall, and the
 *   hand's damping and grip, seeded by object and take, never by velocity.
 * - The loose parts are a one-way readout: scheduled from the strike, then they re-excite the body; the
 *   body never throws them again. That keeps every hit a fixed cost.
 * - The bin lid's crash brightness is an ANALOGY of a thin plate's nonlinear cascade (a short kick into
 *   the high modes growing with velocity squared), not a simulation of it.
 *
 * The modal banks here gain-compensate by sin(theta), as the prototype does, so a mode's peak does not
 * depend on its frequency; [Modes.ring] does not, which is why this file carries its own two-pole loop.
 *
 * Macros: TUNE (the object's size, ±7 semitones), TOOL and RATTLE (read per object: what it is hit with,
 * what is loose), DAMP (a hand on it), MIC (the phone, 12 cm to 2.8 m across a tiled kitchen). All 0..1.
 * No HOLD: every hit is finite. No maker or model names any object.
 */
object Clutter {

    const val RATE = Dsp.RATE
    private const val FS = Dsp.RATE * Dsp.OVERSAMPLE
    /** Round 0c's arithmetic. Bump when a pad's audio changes; saved patches carry it. */
    const val MODEL_VERSION = 1
    /** The takes a pad may name, 0 to [MAX_TAKE]: four round-robin strikes. */
    const val MAX_TAKE = 3
    /** Every hit is peak-matched to this, as the prototype's audition was. */
    const val PEAK = 0.89f
    private const val G = 9.81

    private val TUNE_DEFAULT = 0.5f
    private val DEFAULTS: Map<ClutterVoice, FloatArray> = mapOf(
        // TOOL, RATTLE, DAMP, MIC per object; TUNE is 0.5 on all. "shape", accepted by ear.
        ClutterVoice.BUCKET to floatArrayOf(.10f, .40f, .30f, .30f), ClutterVoice.BOX to floatArrayOf(.15f, .55f, .25f, .30f),
        ClutterVoice.BOOK to floatArrayOf(.45f, .50f, .30f, .40f), ClutterVoice.JAR to floatArrayOf(.35f, .50f, .30f, .25f),
        ClutterVoice.KEYS to floatArrayOf(.50f, .50f, .20f, .30f), ClutterVoice.TABLE to floatArrayOf(.20f, .40f, .30f, .30f),
        ClutterVoice.POT to floatArrayOf(.45f, .45f, .30f, .35f), ClutterVoice.BOTTLE to floatArrayOf(.75f, .35f, .20f, .30f),
        ClutterVoice.SUITCASE to floatArrayOf(.15f, .40f, .30f, .30f), ClutterVoice.MATCHBOX to floatArrayOf(.55f, .55f, .30f, .25f),
        ClutterVoice.DRAWER to floatArrayOf(.60f, .55f, .25f, .40f), ClutterVoice.TUB to floatArrayOf(.35f, .35f, .35f, .30f),
        ClutterVoice.PAN to floatArrayOf(.45f, .50f, .45f, .30f), ClutterVoice.MUG to floatArrayOf(.85f, .40f, .30f, .30f),
        ClutterVoice.GLASS to floatArrayOf(.50f, .40f, .15f, .30f), ClutterVoice.ZIP to floatArrayOf(.55f, .40f, .30f, .25f),
        ClutterVoice.STOMP to floatArrayOf(.45f, .35f, .30f, .35f), ClutterVoice.BAG to floatArrayOf(.45f, .45f, .30f, .35f),
        ClutterVoice.BINLID to floatArrayOf(.60f, .35f, .25f, .35f), ClutterVoice.CAN to floatArrayOf(.55f, .40f, .30f, .25f),
        ClutterVoice.SWITCH to floatArrayOf(.55f, .35f, .30f, .20f), ClutterVoice.BOARD to floatArrayOf(.20f, .35f, .30f, .30f),
    )

    fun macrosFor(voice: ClutterVoice): List<MacroSpec> {
        val d = DEFAULTS.getValue(voice)
        return listOf(MacroSpec("TUNE", TUNE_DEFAULT), MacroSpec("TOOL", d[0]), MacroSpec("RATTLE", d[1]), MacroSpec("DAMP", d[2]), MacroSpec("MIC", d[3]))
    }

    fun defaults(voice: ClutterVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** [macros] over the defaults: unknown keys dropped, a non-finite value falls back, everything clamped 0..1. */
    fun settled(voice: ClutterVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun drumClassFor(voice: ClutterVoice): DrumClass = voice.drumClass

    /** SCRAMBLE near the defaults (there is no preset bank yet), the way THUMP and SCUFF roll. */
    fun scramble(voice: ClutterVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** One hit at 44.1 kHz: [velocity] 0..1 is the strike, [take] 0..[MAX_TAKE] is which of four hands. */
    fun render(voice: ClutterVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0): Snip {
        val out = renderRaw(voice, macros, velocity, take)
        Dsp.normalize(out, PEAK)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /** One hit before the peak match, for tests that look at what the object did rather than how loud. */
    internal fun renderRaw(voice: ClutterVoice, macros: Map<String, Float>, velocity: Float, take: Int): FloatArray {
        val s = settled(voice, macros)
        val vel = (if (velocity.isFinite()) velocity.toDouble() else 1.0).coerceIn(0.05, 1.0)
        val tk = take.coerceIn(0, MAX_TAKE)
        // A take is a different hand: it damps a little more or less and grips the tool a little firmer or
        // looser. Seeded by the object and the take only, so velocity never reshuffles it.
        val hand = Rng(Dsp.seedFor("CLUTTER", voice.name, tk, "hand"))
        val damp = (s.getValue("DAMP") + hand.uniform(-0.05, 0.05)).coerceIn(0.0, 1.0)
        val tool = (s.getValue("TOOL") + hand.uniform(-0.04, 0.04)).coerceIn(0.0, 1.0)
        val mm = M(tune = s.getValue("TUNE").toDouble(), tool = tool, rattle = s.getValue("RATTLE").toDouble(), damp = damp, mic = s.getValue("MIC").toDouble())
        val n = (voice.seconds * FS).toInt()
        val raw = when (voice) {
            ClutterVoice.BUCKET -> bucket(mm, vel, tk, n)
            ClutterVoice.BOX -> box(mm, vel, tk, n)
            ClutterVoice.BOOK -> book(mm, vel, tk, n)
            ClutterVoice.JAR -> jar(mm, vel, tk, n)
            ClutterVoice.KEYS -> keys(mm, vel, tk, n)
            ClutterVoice.TABLE -> table(mm, vel, tk, n)
            ClutterVoice.POT -> pot(mm, vel, tk, n)
            ClutterVoice.BOTTLE -> bottle(mm, vel, tk, n)
            ClutterVoice.SUITCASE -> suitcase(mm, vel, tk, n)
            ClutterVoice.MATCHBOX -> matchbox(mm, vel, tk, n)
            ClutterVoice.DRAWER -> drawer(mm, vel, tk, n)
            ClutterVoice.TUB -> tub(mm, vel, tk, n)
            ClutterVoice.PAN -> pan(mm, vel, tk, n)
            ClutterVoice.MUG -> mug(mm, vel, tk, n)
            ClutterVoice.GLASS -> glass(mm, vel, tk, n)
            ClutterVoice.ZIP -> zipper(mm, vel, tk, n)
            ClutterVoice.STOMP -> stomp(mm, vel, tk, n)
            ClutterVoice.BAG -> bag(mm, vel, tk, n)
            ClutterVoice.BINLID -> binlid(mm, vel, tk, n)
            ClutterVoice.CAN -> can(mm, vel, tk, n)
            ClutterVoice.SWITCH -> switch(mm, vel, tk, n)
            ClutterVoice.BOARD -> board(mm, vel, tk, n)
        }
        return output(raw, mm.mic)
    }

    /** The settled macros as the object functions read them. */
    internal data class M(val tune: Double, val tool: Double, val rattle: Double, val damp: Double, val mic: Double)

    // ------------------------------------------------------------------ the shared physics

    /**
     * Seeded draws. The prototype used numpy's generator; this is Kotlin's, so every random draw is another
     * realisation of the same law (disclosed in docs/CLUTTER.md), and still byte-for-byte deterministic.
     */
    internal class Rng(seed: Int) {
        private val r = Random(seed)
        fun uniform(lo: Double, hi: Double) = lo + (hi - lo) * r.nextDouble()
        fun uniform(lo: Double, hi: Double, count: Int) = DoubleArray(count) { uniform(lo, hi) }
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

    private fun rngFor(vararg parts: Any) = Rng(Dsp.seedFor("CLUTTER", *parts))

    private fun lin(x: Double, lo: Double, hi: Double) = lo + (hi - lo) * x.coerceIn(0.0, 1.0)
    private fun expMap(x: Double, lo: Double, hi: Double) = lo * (hi / lo).pow(x.coerceIn(0.0, 1.0))
    private fun tuneRatio(tune: Double) = 2.0.pow((tune - 0.5) * 14.0 / 12.0)
    /** How much a hand shortens the ring: 1 at DAMP 0, 0.15 at DAMP 1. */
    private fun dampFactor(damp: Double) = expMap(damp, 1.0, 0.15)
    private fun smooth(x: Double, lo: Double, hi: Double): Double { val t = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    /** A raised-cosine force pulse whose AREA is the impulse delivered, placed at [at] seconds. */
    private fun contact(n: Int, at: Double, widthS: Double, area: Double): DoubleArray {
        val buf = DoubleArray(n)
        val w = max(2, (widthS * FS).roundToInt())
        val s = (at * FS).toInt()
        val p = hann(w, periodic = true)
        val sum = p.sum()
        for (i in 0 until w) { val j = s + i; if (j in 0 until n) buf[j] += p[i] * area / sum }
        return buf
    }

    /** numpy's 0.5 (1 - cos(2 pi k / w)) (periodic) or np.hanning (symmetric). */
    private fun hann(w: Int, periodic: Boolean): DoubleArray =
        if (periodic) DoubleArray(w) { 0.5 * (1 - cos(2 * PI * it / w)) }
        else if (w == 1) doubleArrayOf(1.0) else DoubleArray(w) { 0.5 - 0.5 * cos(2 * PI * it / (w - 1)) }

    /** A train of contacts at [times] (s) with [amps]; width 0 leaves single-sample impulses for a kernel. */
    private fun impulses(n: Int, times: DoubleArray, amps: DoubleArray, widthS: Double = 0.0): DoubleArray {
        val buf = DoubleArray(n)
        for (i in times.indices) { val k = (times[i] * FS).toInt(); if (k in 0 until n) buf[k] += amps[i] }
        if (widthS <= 0) return buf
        val w = max(2, (widthS * FS).roundToInt())
        val p = hann(w, periodic = true)
        val sum = p.sum()
        for (j in p.indices) p[j] /= sum
        return convolveSparse(buf, p, n)
    }

    /** [x] (mostly zeros) convolved with a short [kernel], truncated to [n]. */
    private fun convolveSparse(x: DoubleArray, kernel: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(n)
        for (i in x.indices) {
            val a = x[i]
            if (a == 0.0) continue
            for (j in kernel.indices) { val k = i + j; if (k >= n) break; out[k] += a * kernel[j] }
        }
        return out
    }

    /** One mode: frequency ratio, gain, and time to fall 60 dB. */
    internal class Mode(val ratio: Double, val gain: Double, val t60: Double)

    /**
     * A bank of two-pole resonators driven by [exc], each gain-compensated by sin(theta) so its peak does not
     * depend on its frequency (the prototype's `modal_ring(..., compensate=True)`). Modes at or above Nyquist,
     * or with no decay time, are skipped.
     */
    private fun modes(exc: DoubleArray, f0: Double, table: List<Mode>): DoubleArray {
        val out = DoubleArray(exc.size)
        val nyq = FS / 2.0
        for (md in table) {
            val f = f0 * md.ratio
            if (f >= nyq || md.t60 <= 0) continue
            val r = exp(-6.9078 / (md.t60 * FS))
            val th = 2 * PI * f / FS
            val g = md.gain * sin(th)
            val a1 = 2 * r * cos(th)
            val a2 = r * r
            var y1 = 0.0
            var y2 = 0.0
            for (i in exc.indices) {
                val y = g * exc[i] + a1 * y1 - a2 * y2
                y2 = y1; y1 = y
                out[i] += y
            }
        }
        return out
    }

    /** Real round objects are never perfectly round: each mode is two a hair apart, and they beat. */
    private fun doublets(table: List<Mode>, split: Double): List<Mode> =
        table.flatMap { listOf(Mode(it.ratio * (1 - split), it.gain * 0.5, it.t60), Mode(it.ratio * (1 + split), it.gain * 0.5, it.t60)) }

    private fun modesOf(vararg rgt: Double): List<Mode> = (rgt.indices step 3).map { Mode(rgt[it], rgt[it + 1], rgt[it + 2]) }

    /**
     * How far the strike exceeds what holds the loose parts down, 0..1: the impulse (velocity^1.2) against a
     * FIXED threshold, never normalised per hit, so a soft strike leaves the rice lying still. RATTLE loosens
     * the parts (lowers the threshold). "shape" thresholds.
     */
    private fun lift(vel: Double, rattle: Double, loThr: Double = 0.15, hiThr: Double = 0.65): Double {
        val drive = vel.pow(1.2)
        val thr = lin(rattle, hiThr, loThr)
        return max(0.0, (drive - thr) / (1.0 - thr))
    }

    /** A part thrown up at [v0] m/s lands after 2 v0 / g and comes off at [e] times the speed: (times, speeds). */
    private fun bounceTrain(t0: Double, v0: Double, e: Double, vmin: Double = 0.004, limit: Int = 24): Pair<DoubleArray, DoubleArray> {
        val ts = ArrayList<Double>()
        val vs = ArrayList<Double>()
        var t = t0
        var v = v0
        for (i in 0 until limit) {
            t += 2 * v / G
            ts.add(t); vs.add(v)
            v *= e
            if (v < vmin) break
        }
        return ts.toDoubleArray() to vs.toDoubleArray()
    }

    /** Contact time: TOOL picks soft..hard, and a harder hit shortens a soft contact more than a hard one. */
    private fun strikeWidth(tool: Double, vel: Double, softMs: Double, hardMs: Double, jitter: Double) =
        expMap(tool, softMs, hardMs) * 1e-3 * vel.pow(-lin(tool, 0.5, 0.2)) * jitter

    /** A metal spoon or key rings a little on its own; only the top third of TOOL reaches it. */
    private fun utensilRing(n: Int, pulse: DoubleArray, tool: Double, rng: Rng, vel: Double): DoubleArray {
        val g = smooth(tool, 0.6, 1.0)
        val f = 2900.0 * rng.uniform(0.96, 1.04)
        if (g <= 0) return DoubleArray(n)
        val y = modes(pulse, f, modesOf(1.0, 1.0, .07, 2.756, .6, .045, 5.404, .35, .03))
        val k = 0.22 * g * vel.pow(0.8)
        for (i in y.indices) y[i] *= k
        return y
    }

    /** Where on the object the hit lands: weight mode k by how far that spot is from its node lines. */
    private fun takeWeights(rng: Rng, count: Int): DoubleArray {
        val pos = rng.uniform(0.1, 0.5)
        val phase = rng.uniform(0.0, 0.6, count)
        return DoubleArray(count) { 0.15 + 0.85 * abs(sin(PI * (it + 1) * pos + phase[it])) }
    }

    /** Simply supported rectangular plate a x b struck at (x, y) as fractions. */
    private fun rectModes(a: Double, b: Double, count: Int, x: Double, y: Double, t60: Double, slope: Double, df: Double, maxIndex: Int = 5): List<Mode> {
        data class R(val f: Double, val m: Int, val n: Int)
        val raw = (1..maxIndex).flatMap { mm -> (1..maxIndex).map { nn -> R((mm / a).pow(2) + (nn / b).pow(2), mm, nn) } }
            .sortedWith(compareBy({ it.f }, { it.m }, { it.n })).take(count)
        val base = raw[0].f
        return raw.mapIndexed { i, r ->
            Mode(r.f / base, (0.25 + abs(sin(PI * r.m * x) * sin(PI * r.n * y))) / (i + 1.0).pow(0.5), t60 * df * (r.f / base).pow(-slope))
        }
    }

    // Thin-ring flexural modes (Rayleigh): n (n^2 - 1) / sqrt(n^2 + 1), n = 2..6, normalised to n = 2.
    private val RING: DoubleArray = DoubleArray(5) { val n = it + 2.0; n * (n * n - 1) / sqrt(n * n + 1) }.let { r -> DoubleArray(5) { r[it] / r[0] } }
    private val CLAMPED_PLATE = doubleArrayOf(1.0, 2.08, 3.41, 3.89, 5.00, 5.95, 7.43)
    private val FREE_PLATE = doubleArrayOf(1.0, 1.73, 2.33, 3.91, 4.11)
    private val FREE_BAR = doubleArrayOf(1.0, 2.756, 5.404)

    // ------------------------------------------------------------------ filters (the prototype's scipy designs)

    /** A direct-form-I biquad, run in Double. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun run(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in x.indices) {
                val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v
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
        fun sqrt(): C { val m = kotlin.math.sqrt(re * re + im * im); val r = kotlin.math.sqrt((m + re) / 2); val i = kotlin.math.sqrt(max(0.0, (m - re) / 2)); return C(r, if (im < 0) -i else i) }
        fun abs2() = re * re + im * im
    }

    /**
     * scipy's `butter(2, [lo, hi], 'band')` exactly: the second-order analog prototype moved to a band-pass
     * (four poles, two zeros at 0), then the bilinear transform with prewarping, as two biquad sections.
     */
    private fun butterBandpass(lo: Double, hiIn: Double, fs: Int): List<Biquad> {
        val hi = min(hiIn, fs * 0.45)
        val w1 = 2.0 * fs * tan(PI * lo / fs)
        val w2 = 2.0 * fs * tan(PI * hi / fs)
        val bw = w2 - w1
        val w0sq = w1 * w2
        val proto = listOf(C(cos(3 * PI / 4), sin(3 * PI / 4)))  // the upper of the conjugate pair; the other is its mirror
        val k2 = 2.0 * fs
        val sections = ArrayList<Biquad>()
        var gain = bw * bw  // the low-pass prototype's gain is 1; lp2bp multiplies by bw^N
        val bpPoles = ArrayList<C>()
        for (p in proto) {
            val pl = p * (bw / 2)
            val disc = (pl * pl - C(w0sq, 0.0)).sqrt()
            bpPoles.add(pl + disc)
            bpPoles.add(pl - disc)
        }
        // Each upper-half-plane analog pole with its conjugate makes one section; bilinear: z = (2fs + s) / (2fs - s).
        // Two zeros at s = 0 map to z = 1, two at infinity to z = -1: one of each per section.
        val all = bpPoles + bpPoles.map { C(it.re, -it.im) }
        var num = C(1.0, 0.0)
        var den = C(1.0, 0.0)
        repeat(2) { num = num * C(k2, 0.0) }  // the two zeros at 0: (2fs - 0)
        for (p in all) den = den * (C(k2, 0.0) - p)
        gain *= (num / den).re
        for ((i, p) in bpPoles.withIndex()) {
            val z = (C(k2, 0.0) + p) / (C(k2, 0.0) - p)
            val a1 = -2 * z.re
            val a2 = z.abs2()
            val g = if (i == 0) gain else 1.0
            sections.add(Biquad(g, 0.0, -g, a1, a2))  // (z - 1)(z + 1) = z^2 - 1
        }
        return sections
    }

    private fun bandpass(x: DoubleArray, lo: Double, hi: Double, fs: Int = FS): DoubleArray {
        var y = x
        for (s in butterBandpass(lo, hi, fs)) y = s.run(y)
        return y
    }

    /** scipy's second-order Butterworth low- or high-pass: the RBJ biquad at Q = 1/sqrt(2). */
    private fun butter2(f: Double, high: Boolean, fs: Int): Biquad {
        val w0 = 2 * PI * f / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * (1 / sqrt(2.0)))
        val a0 = 1 + alpha
        return if (high) Biquad((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
        else Biquad((1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
    }

    /** scipy's first-order Butterworth low-pass by the bilinear transform. */
    private fun onePoleLow(x: DoubleArray, f: Double, fs: Int): DoubleArray {
        val k = tan(PI * f / fs)
        val b = k / (1 + k)
        val a1 = (k - 1) / (k + 1)
        val y = DoubleArray(x.size)
        var x1 = 0.0; var y1 = 0.0
        for (i in x.indices) { val v = b * x[i] + b * x1 - a1 * y1; x1 = x[i]; y1 = v; y[i] = v }
        return y
    }

    private fun peaking(f0: Double, gainDb: Double, q: Double, fs: Int): Biquad {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * f0 / fs
        val al = sin(w) / (2 * q)
        val a0 = 1 + al / a
        return Biquad((1 + al * a) / a0, -2 * cos(w) / a0, (1 - al * a) / a0, -2 * cos(w) / a0, (1 - al / a) / a0)
    }

    private fun windowedNoise(rng: Rng, k: Int): DoubleArray { val h = hann(k, periodic = false); val z = rng.normals(k); return DoubleArray(k) { z[it] * h[it] } }

    private fun add(out: DoubleArray, y: DoubleArray, k: Double = 1.0) { for (i in out.indices) out[i] += k * y[i] }

    // ------------------------------------------------------------------ the objects (at FS)

    private fun bucket(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("BUCKET", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 7.0, 0.35, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        // Trapped air under the base: the boom. Plastic stiffens at large displacement, so a hard hit starts
        // the boom higher and it falls back: a kick's pitch drop with a physical reason.
        val drop = 0.32 * vel.pow(1.2)
        val out = DoubleArray(n)
        var phase = 0.0
        val riseT = max(width * 0.6, 2e-4)
        val decay = 0.42 * df.pow(0.7)
        for (i in 0 until n) {
            val t = i.toDouble() / FS
            phase += 2 * PI * 64.0 * tr * (1 + drop * exp(-t / 0.028)) / FS
            out[i] = j * sin(phase) * (1 - exp(-t / riseT)) * exp(-6.9078 * t / decay)
        }
        val w = takeWeights(rng, CLAMPED_PLATE.size)
        val plateT = doubleArrayOf(.13, .085, .06, .05, .04, .035, .03)
        val table = CLAMPED_PLATE.indices.map { Mode(CLAMPED_PLATE[it], (1.0 / (it + 1.0).pow(0.6)) * w[it], plateT[it] * df) }
        add(out, modes(pulse, 150.0 * tr, table), 0.55)
        add(out, modes(contact(n, 0.0015, 0.0008, j), 340.0 * tr, modesOf(1.0, 1.0, .025, 1.9, .6, .018)), 0.25 * vel * vel)
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        val l = lift(vel, m.rattle)
        if (l > 0) {
            val (ts, vs) = bounceTrain(0.002, 0.11 * l * rng.uniform(0.85, 1.15), 0.45)
            val hit = impulses(n, ts, DoubleArray(vs.size) { vs[it] / 0.11 }, 0.00012)
            val handle = modes(hit, 2300.0 * rng.uniform(0.95, 1.05), FREE_BAR.indices.map { Mode(FREE_BAR[it], doubleArrayOf(1.0, .5, .25)[it], .05) })
            add(out, handle, 0.05 * smooth(m.rattle, 0.0, .6) * j)
            add(out, modes(hit, 150.0 * tr, table.take(3)), 0.05 * j)
        }
        return out
    }

    private fun box(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("BOX", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 5.0, 0.4, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        // Top panel, simply supported, 40 x 30 cm. The hand lands at (x, y).
        val x = rng.uniform(0.25, 0.5); val y = rng.uniform(0.25, 0.5)
        val table = rectModes(0.40, 0.30, 14, x, y, 0.075, 0.5, df, maxIndex = 4).mapIndexed { i, md ->
            // The box keeps the prototype's own gain law: (0.25 + shape) / (i + 1)^0.45.
            Mode(md.ratio, md.gain * (i + 1.0).pow(0.5) / (i + 1.0).pow(0.45), md.t60)
        }
        val out = modes(pulse, 185.0 * tr, table)
        add(out, modes(pulse, 430.0 * tr, modesOf(1.0, 1.0, .05 * df, 1.6, .4, .03 * df)), 0.35)
        // Cardboard fibres crush under the hand: a short noisy scuff that follows the contact.
        val tc = FS * max(width, 3e-4) * 1.4
        val crushNoise = DoubleArray(n) { rng.normal() * exp(-it / tc) }
        add(out, bandpass(crushNoise, 1200.0, 9000.0), 0.12 * (0.6 + m.tool) * j / max(1e-9, width * 1e3).pow(0.3))
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        val l = lift(vel, m.rattle)
        if (l > 0) {
            // Rice thrown up by the panel lands 5 to 50 ms later and bounces once.
            val pool = 900
            val launch = rng.uniform(0.0, 0.004, pool)
            val speed = DoubleArray(pool) { 0.30 * rng.uniform(0.15, 1.0).pow(0.7) }
            val mass = rng.uniform(0.5, 1.0, pool)
            val count = (pool * l * smooth(m.rattle, 0.0, 0.9)).toInt()
            val ts = DoubleArray(2 * count); val amps = DoubleArray(2 * count)
            for (i in 0 until count) {
                val v = speed[i] * sqrt(l)
                val t1 = launch[i] + 2 * v / G
                ts[2 * i] = t1; ts[2 * i + 1] = t1 + 2 * 0.25 * v / G
                amps[2 * i] = mass[i] * v; amps[2 * i + 1] = mass[i] * v * 0.25
            }
            val hit = impulses(n, ts, amps)
            val k = (0.0004 * FS).toInt()
            val kernel = bandpass(windowedNoise(rng, k), 1800.0, 7000.0)
            add(out, convolveSparse(hit, kernel, n), 0.6 * j)
            add(out, modes(hit, 185.0 * tr, table.take(6)), 0.04 * j)
        }
        return out
    }

    private fun book(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("BOOK", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        // The pages meet in a few quick taps; a harder slam closes them faster.
        val taps = 2 + (m.rattle * 4).roundToInt()
        val gaps = DoubleArray(8) { rng.uniform(2.5, 7.0) * 1e-3 / vel.pow(0.5) }
        val times = DoubleArray(taps)
        for (i in 1 until taps) times[i] = times[i - 1] + gaps[i - 1]
        val amps8 = rng.uniform(0.55, 1.0, 8)
        val amps = DoubleArray(taps) { amps8[it] }
        amps[taps - 1] = 1.0  // the cover lands last and hardest
        val width = strikeWidth(m.tool, vel, 1.6, 0.25, 1.0)
        val w = takeWeights(rng, 4)
        val board = listOf(Mode(1.0, w[0], .045 * df), Mode(1.9, .7 * w[1], .035 * df), Mode(3.0, .5 * w[2], .028 * df), Mode(4.4, .35 * w[3], .022 * df))
        val k = (0.0012 * FS).toInt()
        val burst = DoubleArray(n)
        for (i in 0 until taps) {
            val s = (times[i] * FS).toInt()
            val wn = windowedNoise(rng, k)
            for (q in 0 until k) if (s + q < n) burst[s + q] += amps[i] * wn[q]
        }
        val out = DoubleArray(n)
        add(out, bandpass(burst, 700.0, 4500.0), 0.9 * j / (width * 1e3).pow(0.25))
        add(out, modes(impulses(n, times, DoubleArray(taps) { amps[it] * j }, width), 420.0 * tr, board), 0.6)
        // The air squeezed out between the covers: a low pop at the last tap.
        val pop = DoubleArray(n)
        val s = (times[taps - 1] * FS).toInt()
        val kp = (0.006 * FS).toInt()
        for (q in 0 until kp) if (s + q < n) pop[s + q] = rng.normal() * exp(-q / (0.0015 * FS))
        add(out, bandpass(pop, 180.0, 1100.0), 0.7 * j * vel)
        return out
    }

    private fun jar(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("JAR", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        // One flick: the contents hit the wall together, spread over a few ms. Finer contents (low TOOL) are
        // more grains, smaller, higher; a harder flick throws them tighter.
        val pool = 1600
        val per = expMap(m.tool, 3.0, 0.12)
        val count = min(pool.toDouble(), (80 + 520 * m.rattle) * per * vel.pow(0.8)).toInt()
        val spread = 0.016 / vel.pow(0.6) * lin(m.damp, 1.0, 0.6)
        val gam = DoubleArray(pool) { rng.gamma(2.2, spread / 2.2) }
        val ua = rng.uniform(0.3, 1.0, pool)
        val ts = DoubleArray(count) { 0.0015 + gam[it] }
        // Stragglers arrive late because they were thrown slowly: they land softer, never as a lone click.
        val amps = DoubleArray(count) { ua[it] * ua[it] / per.pow(0.5) * exp(-max(0.0, ts[it] - 1.5 * spread) / spread) }
        val hit = impulses(n, ts, amps)
        val kl = max(8, (expMap(m.tool, 0.00012, 0.0006) * FS).toInt())
        val fc = expMap(m.tool, 9500.0, 3400.0)
        val kernel = bandpass(windowedNoise(rng, kl), fc / 1.8, fc * 1.6)
        val out = convolveSparse(hit, kernel, n)
        for (i in out.indices) out[i] *= 2.2
        val w = takeWeights(rng, 5)
        val wallR = doubleArrayOf(1.0, 2.3, 3.9, 5.6, 7.4)
        add(out, modes(hit, 1250.0 * tr, wallR.indices.map { Mode(wallR[it], w[it] / (it + 1.0).pow(0.5), .12 * df) }), 0.08)
        val coin = smooth(m.tool, 0.6, 1.0)
        if (coin > 0) add(out, modes(hit, 4100.0, modesOf(1.0, 1.0, .14, 1.78, .6, .09, 2.9, .3, .06)), 0.12 * coin)
        for (i in out.indices) out[i] *= j
        return out
    }

    private fun keys(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("KEYS", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val count = 3 + (m.rattle * 6).roundToInt()
        val fKeys = rng.uniform(2400.0, 4800.0, 9)
        val t60s = rng.uniform(0.25, 0.6, 9)
        // Collisions: a burst then a thinning rate, by thinning a Poisson process. Fixed pools, so a velocity
        // change does not reshuffle which key is which.
        val tau = 0.10 * (1 + m.rattle) * lin(m.damp, 1.0, 0.5)
        val rate0 = 380.0 * vel
        val cand = rng.uniform(0.0, 0.7, 1400).also { it.sort() }
        val keepU = rng.uniform(0.0, 1.0, 1400)
        val pa = IntArray(1400) { rng.int(0, count) }
        val pb = IntArray(1400) { rng.int(0, count) }
        val av = rng.uniform(0.3, 1.0, 1400)
        val keep = (0 until 1400).filter { keepU[it] < rate0 * exp(-cand[it] / tau) / 380.0 }
        val width = strikeWidth(m.tool, vel, 0.6, 0.07, 1.0)
        val out = DoubleArray(n)
        for (k in 0 until count) {
            val sel = keep.filter { pa[it] == k || pb[it] == k }
            if (sel.isEmpty()) continue
            val exc = impulses(n, DoubleArray(sel.size) { cand[sel[it]] }, DoubleArray(sel.size) { av[sel[it]] * exp(-cand[sel[it]] / (tau * 1.5)) }, width)
            val table = FREE_BAR.indices.map { Mode(FREE_BAR[it], doubleArrayOf(1.0, .55, .3)[it], t60s[k] * df * FREE_BAR[it].pow(-0.6)) }
            add(out, modes(exc, fKeys[k] * tr, table))
        }
        val ringExc = impulses(n, DoubleArray(keep.size) { cand[keep[it]] }, DoubleArray(keep.size) { av[keep[it]] * 0.4 }, width)
        add(out, modes(ringExc, 1750.0 * tr, modesOf(1.0, 1.0, .3 * df, 2.7, .5, .18 * df)), 0.4)
        for (i in out.indices) out[i] *= j * 3.0
        return out
    }

    private fun table(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("TABLE", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 1.8, 0.15, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        val ratios = doubleArrayOf(1.0, 1.47, 2.09, 2.56, 3.18, 3.9, 4.6, 5.7, 6.9, 8.3)  // "shape": a dense wooden plate
        val w = takeWeights(rng, ratios.size)
        val wood = ratios.indices.map { Mode(ratios[it], w[it] / (it + 1.0).pow(0.4), 0.10 * df * ratios[it].pow(-0.8)) }
        val out = modes(pulse, 215.0 * tr, wood)
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        // A knuckle is bone under skin: a dull low thump under the knock.
        add(out, modes(pulse, 120.0, modesOf(1.0, 1.0, .03)), 0.35)
        val l = lift(vel, m.rattle, 0.2, 0.75)
        if (l > 0) {
            // A fork and a spoon on the table jump and land: the same pieces every take.
            val items = 1 + (m.rattle * 2).roundToInt()
            for (i in 0 until items) {
                val (ts, vs) = bounceTrain(rng.uniform(0.001, 0.004), 0.09 * l * rng.uniform(0.85, 1.1), 0.35)
                val hit = impulses(n, ts, DoubleArray(vs.size) { vs[it] / 0.09 }, 0.0001)
                val f = doubleArrayOf(1150.0, 1420.0, 1290.0)[i] * rng.uniform(0.97, 1.03)
                add(out, modes(hit, f, FREE_BAR.indices.map { Mode(FREE_BAR[it], doubleArrayOf(1.0, .7, .4)[it], .22 * FREE_BAR[it].pow(-0.5)) }), 0.12 * j)
                add(out, modes(hit, 215.0 * tr, wood.take(4)), 0.06 * j)
            }
        }
        return out
    }

    private fun pot(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("POT", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 4.0, 0.2, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        val w = takeWeights(rng, RING.size + 3)
        val wall = RING.indices.map { Mode(RING[it], w[it] / (it + 1.0).pow(0.5), 1.5 * df * RING[it].pow(-0.6)) }
        val bottom = (0 until 3).map { Mode(0.72 * CLAMPED_PLATE[it], 0.5 * w[RING.size + it] / (it + 1.0), 0.6 * df) }
        val bodyTable = doublets(wall + bottom, 0.0025)
        val f1 = 410.0 * tr
        val out = modes(pulse, f1, bodyTable)
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        val l = lift(vel, m.rattle)
        if (l > 0) {
            // The lid hops and rocks: each landing is two contacts, one edge then the other.
            val (ts, vs) = bounceTrain(0.0015, 0.13 * l * rng.uniform(0.85, 1.15), 0.55)
            val times = DoubleArray(2 * ts.size); val amps = DoubleArray(2 * ts.size)
            for (i in ts.indices) {
                val gap = rng.uniform(0.0015, 0.004)
                times[2 * i] = ts[i]; times[2 * i + 1] = ts[i] + gap
                amps[2 * i] = 0.6 * vs[i] / 0.13; amps[2 * i + 1] = 0.4 * vs[i] / 0.13
            }
            val hit = impulses(n, times, amps, 0.00015)
            val lw = takeWeights(rng, FREE_PLATE.size)
            val lid = modes(hit, 640.0 * tr, doublets(FREE_PLATE.indices.map { Mode(FREE_PLATE[it], lw[it] / (it + 1.0).pow(0.5), .45 * df) }, 0.004))
            add(out, lid, 0.45 * smooth(m.rattle, 0.0, .6) * j)
            add(out, modes(hit, f1, bodyTable.take(6)), 0.10 * j)
        }
        return out
    }

    private fun bottle(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("BOTTLE", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 2.5, 0.12, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        val w = takeWeights(rng, RING.size)
        val glass = doublets(RING.indices.map { Mode(RING[it], w[it] / (it + 1.0).pow(0.4), 0.9 * df * RING[it].pow(-0.5)) }, 0.0015)
        val f1 = 1450.0 * tr
        val out = modes(pulse, f1, glass)
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        // The air inside (a Helmholtz resonator) is barely moved by a tap on the side: a soft body.
        add(out, modes(pulse, 118.0 * tr, modesOf(1.0, 1.0, .22 * df)), 0.25)
        val l = lift(vel, m.rattle, 0.25, 0.8)
        if (l > 0) {
            // A hard tap tips the bottle; its base lands back on the table two or three times.
            val (ts, vs) = bounceTrain(0.003, 0.06 * l * rng.uniform(0.8, 1.2), 0.5, vmin = 0.006)
            val hit = impulses(n, ts, DoubleArray(vs.size) { vs[it] / 0.06 }, 0.0004)
            val rock = smooth(m.rattle, 0.0, .6)
            add(out, modes(hit, 380.0, modesOf(1.0, 1.0, .03, 1.8, .6, .02, 2.9, .3, .015)), 0.5 * rock * j)
            add(out, modes(hit, f1, glass.take(4)), 0.35 * rock * j)
        }
        return out
    }

    private fun suitcase(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("SUITCASE", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 9.0, 0.6, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        // A 70 x 45 cm fibreboard lid under fabric: very lossy, so it thuds rather than booms.
        val x = rng.uniform(0.3, 0.5); val y = rng.uniform(0.3, 0.5)
        val out = modes(pulse, 78.0 * tr, rectModes(0.70, 0.45, 8, x, y, 0.11, 0.7, df, maxIndex = 3))
        for (i in out.indices) out[i] *= 0.8
        // The air inside: a short low thud. Fabric does not stiffen, so no pitch drop (the bucket has one).
        val riseT = max(width * 0.6, 2e-4)
        val decay = 0.2 * df.pow(0.7)
        for (i in 0 until n) { val t = i.toDouble() / FS; out[i] += 0.9 * j * sin(2 * PI * 52.0 * tr * t) * (1 - exp(-t / riseT)) * exp(-6.9078 * t / decay) }
        val ts = max(width * 1.2, 3e-4) * FS
        add(out, bandpass(DoubleArray(n) { rng.normal() * exp(-it / ts) }, 60.0, 1500.0), 0.08 * j)
        val l = lift(vel, m.rattle)
        if (l > 0) {
            // The two latches hop.
            for (i in 0 until 2) {
                val (bt, bv) = bounceTrain(rng.uniform(0.0005, 0.002), 0.08 * l * rng.uniform(0.8, 1.2), 0.4)
                val hit = impulses(n, bt, DoubleArray(bv.size) { bv[it] / 0.08 }, 0.0001)
                val f = doubleArrayOf(1900.0, 2350.0)[i] * rng.uniform(0.97, 1.03)
                add(out, modes(hit, f, modesOf(1.0, 1.0, .04, 2.4, .5, .025, 4.1, .3, .015)), 0.03 * smooth(m.rattle, 0.0, .6) * j)
            }
        }
        return out
    }

    private fun matchbox(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("MATCHBOX", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        // The matches slide and hit the end of the tray together, then a few slide back.
        val pool = 500
        val count = min(pool.toDouble(), (45 + 180 * m.rattle) * vel.pow(0.7)).toInt()
        val spread = expMap(m.tool, 0.03, 0.008) / vel.pow(0.5) * lin(m.damp, 1.0, 0.6)
        val g1 = DoubleArray(pool) { rng.gamma(2.5, spread / 2.5) }
        val u1 = rng.uniform(0.3, 1.0, pool)
        val back = rng.uniform(0.07, 0.11)
        val nb = count / 3
        val g2 = DoubleArray(pool) { rng.gamma(2.5, spread / 2.5) }
        val u2 = rng.uniform(0.3, 1.0, pool)
        val ts = DoubleArray(count + nb)
        val amps = DoubleArray(count + nb)
        for (i in 0 until count) { ts[i] = 0.001 + g1[i]; amps[i] = u1[i] * exp(-max(0.0, ts[i] - 1.5 * spread) / spread) }
        for (i in 0 until nb) { ts[count + i] = back + g2[i]; amps[count + i] = 0.35 * u2[i] }
        val hit = impulses(n, ts, amps)
        val kl = (0.0003 * FS).toInt()
        val kernel = bandpass(windowedNoise(rng, kl), 1500.0 * tr, 6000.0 * tr)
        val out = convolveSparse(hit, kernel, n)
        for (i in out.indices) out[i] *= 2.0
        add(out, modes(hit, 880.0 * tr, modesOf(1.0, 1.0, .03 * df, 1.7, .6, .02 * df, 2.6, .4, .015 * df)), 0.10)
        for (i in out.indices) out[i] *= j
        return out
    }

    private fun drawer(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("DRAWER", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 6.0, 1.0, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        val out = modes(pulse, 140.0 * tr, modesOf(1.0, 1.0, .06 * df, 1.6, .7, .045 * df, 2.5, .5, .03 * df, 3.7, .3, .02 * df))
        // The cutlery keeps going after the drawer stops: it slides into the front and piles up.
        val items = 6 + (m.rattle * 14).roundToInt()
        val f = rng.uniform(650.0, 3600.0, 20)
        val t60 = rng.uniform(0.3, 0.8, 20)
        val tau = 0.09 * (1 + m.rattle) * lin(m.damp, 1.0, 0.5)
        val start = rng.uniform(0.004, 0.012)
        val cand = rng.uniform(0.0, 0.9, 3000).also { it.sort() }
        // A harder slam (TOOL) throws the cutlery harder: more collisions at first, shorter, brighter contacts.
        val slam = lin(m.tool, 0.45, 1.3)
        val keepU = rng.uniform(0.0, 1.0, 3000)
        val who = IntArray(3000) { rng.int(0, 20) }
        val av = rng.uniform(0.3, 1.0, 3000)
        val keep = (0 until 3000).filter { keepU[it] < min(1.0, slam * vel * exp(-cand[it] / tau)) }
        val cw = expMap(m.tool, 0.0005, 0.00008)
        val cutlery = DoubleArray(n)
        for (k in 0 until items) {
            val sel = keep.filter { who[it] == k }
            if (sel.isEmpty()) continue
            val times = DoubleArray(sel.size) { cand[sel[it]] + start }
            val exc = impulses(n, times, DoubleArray(sel.size) { av[sel[it]] * exp(-times[it] / (tau * 1.5)) }, cw)
            add(cutlery, modes(exc, f[k] * tr, listOf(Mode(1.0, 1.0, t60[k] * df), Mode(2.756, .6, t60[k] * df * 0.55))))
        }
        add(out, cutlery, 1.8 * j)
        return out
    }

    private fun tub(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("TUB", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 5.0, 0.3, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        // The lid is the drumhead: a 45 x 35 cm plastic rectangle over a box of air.
        val x = rng.uniform(0.25, 0.5); val y = rng.uniform(0.25, 0.5)
        val out = modes(pulse, 120.0 * tr, rectModes(0.45, 0.35, 8, x, y, 0.10, 0.6, df, maxIndex = 3))
        for (i in out.indices) out[i] *= 0.8
        var phase = 0.0
        val riseT = max(width * 0.6, 2e-4)
        val decay = 0.22 * df.pow(0.7)
        val drop = 0.15 * vel.pow(1.2)
        for (i in 0 until n) {
            val t = i.toDouble() / FS
            phase += 2 * PI * 96.0 * tr * (1 + drop * exp(-t / 0.02)) / FS
            out[i] += 0.8 * j * sin(phase) * (1 - exp(-t / riseT)) * exp(-6.9078 * t / decay)
        }
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        val l = lift(vel, m.rattle)
        if (l > 0) {
            // One corner of the lid has come unclipped and chatters.
            val (ts, vs) = bounceTrain(0.001, 0.05 * l * rng.uniform(0.85, 1.15), 0.65)
            val hit = impulses(n, ts, DoubleArray(vs.size) { vs[it] / 0.05 }, 0.0002)
            add(out, modes(hit, 520.0 * tr, modesOf(1.0, 1.0, .04, 1.7, .6, .03, 2.6, .4, .02)), 0.25 * smooth(m.rattle, 0.0, .6) * j)
        }
        return out
    }

    /** The rivet's play in the body's own units at a full-strength hit: "shape", fixed, never per hit. */
    private const val PAN_GAP = 0.6

    private fun pan(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("PAN", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 4.0, 0.25, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        val w = takeWeights(rng, FREE_PLATE.size)
        val plate = doublets(FREE_PLATE.indices.map { Mode(FREE_PLATE[it], w[it] / (it + 1.0).pow(0.5), 0.9 * df * FREE_PLATE[it].pow(-0.5)) }, 0.003)
        val body = modes(pulse, 230.0 * tr, plate)
        val out = body.copyOf()
        add(out, modes(pulse, 165.0 * tr, modesOf(1.0, 1.0, .12 * df)), 0.25)
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        // The handle's loose rivet knocks each time the pan swings past its play: it buzzes while the pan
        // rings hard and stops as the ring dies. A soft hit never reaches the play. This one reads the body.
        val rattle = m.rattle
        val rivetF = 3400.0 * rng.uniform(0.97, 1.03)
        if (rattle > 0) {
            val gap = PAN_GAP * lin(rattle, 1.6, 0.25)
            val half = (FS / (230.0 * tr)).toInt() / 2
            val times = ArrayList<Double>()
            val amps = ArrayList<Double>()
            for (i in 0 until n - 1) {
                if (body[i] < gap && body[i + 1] >= gap) {
                    var e = 0.0
                    for (q in max(0, i - half)..min(n - 1, i + half)) e = max(e, abs(body[q]))
                    times.add(i.toDouble() / FS); amps.add(max(0.0, e - gap))
                }
            }
            if (times.isNotEmpty()) {
                val hit = impulses(n, times.toDoubleArray(), amps.toDoubleArray(), 0.0001)
                add(out, modes(hit, rivetF, modesOf(1.0, 1.0, .02, 1.8, .5, .012)), 0.3 * smooth(rattle, 0.0, .6))
            }
        }
        return out
    }

    private fun mug(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("MUG", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 2.0, 0.12, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        val w = takeWeights(rng, 4)
        // Thick ceramic with a handle: the handle breaks the symmetry, so the pairs split wider than glass.
        val glaze = doublets((0 until 4).map { Mode(RING[it], w[it] / (it + 1.0).pow(0.4), 0.28 * df * RING[it].pow(-0.6)) }, 0.015)
        val f1 = 1100.0 * tr
        val out = modes(pulse, f1, glaze)
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        add(out, modes(pulse, 300.0 * tr, modesOf(1.0, 1.0, .02)), 0.2)
        val l = lift(vel, m.rattle)
        if (l > 0) {
            // The spoon left in the mug jumps and knocks the rim.
            val (ts, vs) = bounceTrain(0.001, 0.05 * l * rng.uniform(0.85, 1.15), 0.45)
            val hit = impulses(n, ts, DoubleArray(vs.size) { vs[it] / 0.05 }, 0.0001)
            val k = smooth(m.rattle, 0.0, .6)
            add(out, modes(hit, 1800.0, FREE_BAR.indices.map { Mode(FREE_BAR[it], doubleArrayOf(1.0, .5, .3)[it], .12) }), 0.15 * k * j)
            add(out, modes(hit, f1, glaze.take(4)), 0.30 * k * j)
        }
        return out
    }

    private fun glass(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("GLASS", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 3.0, 0.15, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        val w = takeWeights(rng, 4)
        // Thin, nearly perfect glass: few partials, long, beating slowly.
        val ring = doublets((0 until 4).map { Mode(RING[it], w[it] / (it + 1.0).pow(0.9), 2.2 * df * RING[it].pow(-1.0)) }, 0.0012)
        val f1 = 880.0 * tr
        val out = modes(pulse, f1, ring)
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        val l = lift(vel, m.rattle, 0.1, 0.6)
        if (l > 0) {
            // Ice cubes, nudged by the flick, settle against the wall a few times, starting at once.
            val cubes = 1 + (m.rattle * 4).roundToInt()
            val times = ArrayList<Double>()
            val amps = ArrayList<Double>()
            for (c in 0 until cubes) {
                val t0 = rng.uniform(0.01, 0.12)
                val knocks = rng.int(1, 4)
                for (q in 0 until knocks) {
                    times.add(t0 + q * rng.uniform(0.012, 0.05))
                    amps.add(l * rng.uniform(0.3, 1.0) * 0.6.pow(q))
                }
            }
            val hit = impulses(n, times.toDoubleArray(), amps.toDoubleArray(), 0.0002)
            val kl = (0.0004 * FS).toInt()
            val click = convolveSparse(hit, bandpass(windowedNoise(rng, kl), 2000.0, 8000.0), n)
            val k = smooth(m.rattle, 0.0, .6)
            add(out, click, 1.2 * k * j)
            add(out, modes(hit, f1, ring.take(4)), 0.4 * k * j)
        }
        return out
    }

    private fun zipper(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("ZIP", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        // A 12 cm pull whose speed rises and falls (a raised sine); a tooth every 2 mm clicks as it passes.
        // TOOL is how fast you pull; TUNE the tooth size (bigger teeth, fewer and lower clicks).
        val dur = expMap(m.tool, 0.32, 0.09) / vel.pow(0.3)
        val pitch = 0.002 / tr
        val teeth = (0.12 / pitch).toInt()
        val ts = DoubleArray(teeth)
        val amps = DoubleArray(teeth)
        for (i in 0 until teeth) {
            val x = ((i + 1) * pitch + rng.normal() * pitch * 0.06).coerceIn(0.0, 0.12 - 1e-6)
            ts[i] = dur / PI * acos(1 - 2 * x / 0.12)
        }
        for (i in 0 until teeth) amps[i] = (0.25 + sin(PI * ts[i] / dur)).pow(0.7) * vel * rng.uniform(0.7, 1.0)
        val hit = impulses(n, ts, amps)
        val kl = (0.00025 * FS).toInt()
        val out = convolveSparse(hit, bandpass(windowedNoise(rng, kl), 2500.0 * tr, 9000.0 * tr), n)
        for (i in out.indices) out[i] *= 2.0
        // The cloth rustles while it moves; a hand holding it taut (DAMP) quietens it.
        val cloth = bandpass(rng.normals(n), 700.0, 4000.0)
        val ck = 0.05 * vel * (1 - 0.7 * m.damp)
        for (i in 0 until n) { val t = i.toDouble() / FS; if (t < dur) out[i] += cloth[i] * sin(PI * (t / dur).coerceIn(0.0, 1.0)) * ck }
        add(out, modes(hit, 600.0, modesOf(1.0, 1.0, .02 * df, 1.9, .5, .015 * df)), 0.15)
        // The pull tab clinks against the teeth when the pull stops.
        val k = smooth(m.rattle, 0.0, .6)
        if (k > 0) {
            val (tt, vs) = bounceTrain(dur, 0.04 * vel, 0.4)
            val tab = impulses(n, tt, DoubleArray(vs.size) { vs[it] / 0.04 }, 0.0001)
            add(out, modes(tab, 2600.0, FREE_BAR.indices.map { Mode(FREE_BAR[it], doubleArrayOf(1.0, .5, .3)[it], .06) }), 0.3 * k)
        }
        return out
    }

    private fun stomp(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("STOMP", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 14.0, 2.0, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        // A heel on a wooden floor: a 3 x 2 m span of boards on joists, heavy and lossy; no trapped air.
        val out = modes(pulse, 44.0 * tr, rectModes(3.0, 2.0, 12, rng.uniform(0.3, 0.45), rng.uniform(0.3, 0.45), 0.16, 0.6, df))
        add(out, modes(pulse, 260.0 * tr, modesOf(1.0, 1.0, .05 * df, 1.6, .6, .035 * df, 2.3, .4, .025 * df)), 0.35)
        val ts = max(width * 0.8, 5e-4) * FS
        add(out, bandpass(DoubleArray(n) { rng.normal() * exp(-it / ts) }, 80.0, 1800.0), 0.06 * j)
        val l = lift(vel, m.rattle)
        if (l > 0) {
            // Glasses in the cupboard jump and clink a moment later: the same four glasses every take.
            for (i in 0 until 1 + (m.rattle * 3).roundToInt()) {
                val (bt, bv) = bounceTrain(rng.uniform(0.004, 0.012), 0.05 * l * rng.uniform(0.85, 1.1), 0.3)
                val hit = impulses(n, bt, DoubleArray(bv.size) { bv[it] / 0.05 }, 0.0001)
                val f = doubleArrayOf(2050.0, 2400.0, 1850.0, 2250.0)[i] * rng.uniform(0.98, 1.02)
                add(out, modes(hit, f, (0 until 3).map { Mode(RING[it], doubleArrayOf(1.0, .5, .3)[it], .25) }), 0.02 * smooth(m.rattle, 0.0, .6) * j)
            }
        }
        return out
    }

    private fun bag(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("BAG", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        // A paper bag full of air, smacked: the wall tears and the air leaves at once. The one-sided pressure
        // pulse becomes the crack after the DC stage; a thicker bag (TOOL) tears slower, a harder smack faster.
        val rise = expMap(m.tool, 0.0004, 0.0012) / vel / tr
        val t0 = 0.001
        val crack = DoubleArray(n)
        for (i in 0 until n) { val t = i.toDouble() / FS; if (t >= t0) { val tt = t - t0; crack[i] = j * (tt / rise) * exp(1 - tt / rise) } }
        // Take out its slow part (a 4 ms one-pole), leaving the crack.
        val pole = exp(-1.0 / (0.004 * FS))
        var s = 0.0
        val out = DoubleArray(n)
        for (i in 0 until n) { s = crack[i] + pole * s; out[i] = 1.2 * (crack[i] - s * (1 - pole)) }
        val tearT = expMap(m.tool, 0.012, 0.03) * df
        val tear = bandpass(rng.normals(n), lin(vel, 500.0, 1800.0) * tr, lin(vel, 3500.0, 9000.0))
        for (i in 0 until n) { val t = i.toDouble() / FS; if (t >= t0) out[i] += 0.35 * j * tear[i] * exp(-(t - t0) / tearT) }
        // The torn flaps flutter a few times as the air rushes past; a hand closed round the bag holds them.
        val flaps = 1 + (m.rattle * 6 * vel).roundToInt()
        val gaps = rng.uniform(0.004, 0.011, flaps)
        val fa = rng.uniform(0.6, 1.0, flaps)
        val k = (0.0015 * FS).toInt()
        val flutter = DoubleArray(n)
        var t1 = t0
        for (f in 0 until flaps) {
            t1 += gaps[f]
            val a1 = vel * 0.8.pow(f) * fa[f]
            val i0 = (t1 * FS).toInt()
            val wn = windowedNoise(rng, k)
            if (i0 + k < n) for (q in 0 until k) flutter[i0 + q] += a1 * wn[q]
        }
        add(out, bandpass(flutter, 900.0, 6000.0), 2.0 * smooth(m.rattle, 0.0, .5) * (1 - 0.7 * m.damp) * j)
        return out
    }

    private fun binlid(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("BINLID", take)
        val obj = rngFor("BINLID", "body")  // the lid's own corrugations: the same lid on every take
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 4.0, 0.2, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        // A corrugated galvanised lid: a thin plate whose ribs scatter its modes into a dense, crash-like cloud.
        val ratios = (doubleArrayOf(1.0, 1.73, 2.33) + obj.uniform(2.6, 55.0, 21)).also { it.sort() }
        val w = takeWeights(rng, ratios.size)
        val low = ratios.indices.filter { ratios[it] < 4 }.map { Mode(ratios[it], w[it] / (it + 1.0).pow(0.5), 1.7 * df * ratios[it].pow(-0.25)) }
        val high = ratios.indices.filter { ratios[it] >= 4 }.map { Mode(ratios[it], w[it], 1.4 * df * ratios[it].pow(-0.3)) }
        // ANALOGY, not simulation: the ribbed thin plate throws energy up into the high cloud, faster the
        // harder it is hit, as a short kick into the high modes growing with velocity squared.
        val cascade = contact(n, width * rng.uniform(0.3, 0.7), 0.00008, j * 0.5 * vel * vel * rng.uniform(0.8, 1.2))
        val out = modes(pulse, 240.0 * tr, low)
        add(out, modes(cascade, 240.0 * tr, high))
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        val l = lift(vel, m.rattle)
        if (l > 0) {
            // A heavy wire bail handle: it lifts, drops back on the lid and chatters down.
            val (ts, vs) = bounceTrain(0.001, 0.09 * l * rng.uniform(0.85, 1.15), 0.6)
            val hit = impulses(n, ts, DoubleArray(vs.size) { vs[it] / 0.09 }, 0.0001)
            add(out, modes(hit, 900.0, FREE_BAR.indices.map { Mode(FREE_BAR[it], doubleArrayOf(1.0, .6, .3)[it], .15) }), 1.5 * smooth(m.rattle, 0.0, .6) * j)
        }
        return out
    }

    private fun can(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("CAN", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 2.0, 0.1, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        val w = takeWeights(rng, 5)
        // An empty drinks can: a very thin aluminium cylinder, tinny and short, with its air behind.
        val wall = doublets(RING.indices.map { Mode(RING[it], w[it] / (it + 1.0).pow(0.4), 0.16 * df * RING[it].pow(-0.4)) }, 0.006)
        val out = modes(pulse, 1500.0 * tr, wall)
        add(out, modes(pulse, 330.0 * tr, modesOf(1.0, 1.0, .05 * df)), 0.2)
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        val l = lift(vel, m.rattle)
        if (l > 0) {
            // The ring-pull, bent up, buzzes against the top.
            val (ts, vs) = bounceTrain(0.0008, 0.03 * l * rng.uniform(0.85, 1.15), 0.7, vmin = 0.002)
            val hit = impulses(n, ts, DoubleArray(vs.size) { vs[it] / 0.03 }, 0.00008)
            add(out, modes(hit, 4200.0, modesOf(1.0, 1.0, .03, 2.7, .5, .02)), 0.12 * smooth(m.rattle, 0.0, .6) * j)
        }
        return out
    }

    private fun switch(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("SWITCH", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        // The rocker leaves its rest with a small click, the spring passes centre and snaps it over. A quick
        // flick (TOOL) and a harder press shorten the gap between them.
        val gap = expMap(m.tool, 0.012, 0.003) / vel.pow(0.5) * rng.uniform(0.92, 1.08)
        val times = doubleArrayOf(0.001, 0.001 + gap)
        val amps = doubleArrayOf(rng.uniform(0.2, 0.5) * vel, 1.0 * vel)
        val hit = impulses(n, times, amps, expMap(m.tool, 0.0002, 0.00006) * rng.uniform(0.8, 1.25))
        // Where the finger presses the rocker changes which housing modes the snap reaches.
        val w = takeWeights(rng, 4)
        val out = modes(hit, 2600.0 * tr * rng.uniform(0.97, 1.03),
            listOf(Mode(1.0, w[0], .025 * df), Mode(1.6, .7 * w[1], .018 * df), Mode(2.4, .5 * w[2], .012 * df), Mode(3.3, .3 * w[3], .008 * df)))
        add(out, modes(hit, 180.0 * tr, modesOf(1.0, 1.0, .03 * df, 2.1, .4, .02 * df)), rng.uniform(0.3, 0.7))
        // A loose cover plate taps the wall once or twice after the snap.
        val k = smooth(m.rattle, 0.0, .6)
        if (k > 0) {
            val (ts, vs) = bounceTrain(times[1], 0.02 * vel, 0.35, vmin = 0.003)
            val plate = impulses(n, ts, DoubleArray(vs.size) { vs[it] / 0.02 }, 0.0001)
            add(out, modes(plate, 1200.0 * tr, modesOf(1.0, 1.0, .02, 1.9, .5, .012)), 0.3 * k)
        }
        return out
    }

    private fun board(m: M, vel: Double, take: Int, n: Int): DoubleArray {
        val rng = rngFor("BOARD", take)
        val tr = tuneRatio(m.tune); val df = dampFactor(m.damp)
        val j = vel.pow(1.2) * rng.uniform(0.95, 1.05)
        val width = strikeWidth(m.tool, vel, 3.0, 0.3, rng.uniform(0.94, 1.06))
        val pulse = contact(n, 0.0, width, j)
        // A chopping board slapped flat: a thick 40 x 28 cm plate, and the air under a cupped palm popping out.
        val out = modes(pulse, 230.0 * tr, rectModes(0.40, 0.28, 10, rng.uniform(0.3, 0.5), rng.uniform(0.3, 0.5), 0.07, 0.5, df))
        val cup = 1 - m.tool  // a flat palm traps air; a stick does not
        add(out, bandpass(DoubleArray(n) { rng.normal() * exp(-it / (0.002 * FS)) }, 500.0 * tr, 1800.0 * tr), 0.5 * cup * j)
        add(out, utensilRing(n, pulse, m.tool, rng, vel))
        val l = lift(vel, m.rattle)
        if (l > 0) {
            // The board is not flat on the counter; one corner knocks back down.
            val (ts, vs) = bounceTrain(0.0015, 0.05 * l * rng.uniform(0.85, 1.15), 0.4)
            val hit = impulses(n, ts, DoubleArray(vs.size) { vs[it] / 0.05 }, 0.0004)
            add(out, modes(hit, 230.0 * tr, rectModes(0.40, 0.28, 5, 0.1, 0.1, 0.05, 0.5, df)), 0.5 * smooth(m.rattle, 0.0, .6) * j)
        }
        return out
    }

    // ------------------------------------------------------------------ the phone in the kitchen

    private val micCache = ConcurrentHashMap<Int, FloatArray>()

    /**
     * The phone in the kitchen as an impulse response at 44.1 kHz, cached by MIC alone (to 1/1000): a knob turn
     * on any other macro never rebuilds it. Distance 12 cm to 2.8 m on a log scale; a 3.6 x 3.0 x 2.5 m tiled
     * kitchen (0.85 reflection) answers from image sources, first and second order, darkened once per bounce;
     * a diffuse tail (RT60 0.32 s) grows past the critical distance (~0.6 m). The prototype's sketch, ported
     * as it is; the shared [Room] section is a later choice, not this round's.
     */
    internal fun micIr(mic: Double): FloatArray = micCache.getOrPut((mic * 1000).roundToInt()) { buildMicIr((mic * 1000).roundToInt() / 1000.0) }

    private fun buildMicIr(mic: Double): FloatArray {
        var d = expMap(mic, 0.12, 2.8)
        val room = doubleArrayOf(3.6, 3.0, 2.5)
        val src = doubleArrayOf(1.3, 1.4, 0.95)
        val dir = doubleArrayOf(0.82, 0.48, 0.31)
        val norm = sqrt(dir.sumOf { it * it })
        val rec = DoubleArray(3) { (src[it] + d * dir[it] / norm).coerceIn(0.1, room[it] - 0.1) }
        d = sqrt((0 until 3).sumOf { (rec[it] - src[it]).pow(2) })
        val c = 343.0
        val n = (0.42 * RATE).toInt()
        val ir1 = DoubleArray(n)
        val ir2 = DoubleArray(n)
        for (nx in -2..2) for (ny in -2..2) for (nz in -2..2) {
            val order = abs(nx) + abs(ny) + abs(nz)
            if (order == 0 || order > 2) continue
            val ks = intArrayOf(nx, ny, nz)
            var path2 = 0.0
            for (ax in 0 until 3) {
                val k = ks[ax]
                val img = if (k % 2 == 0) k * room[ax] + src[ax] else (k + 1) * room[ax] - src[ax]
                path2 += (img - rec[ax]).pow(2)
            }
            val path = sqrt(path2)
            val i = ((path - d) / c * RATE).roundToInt()
            if (i in 1 until n) (if (order == 1) ir1 else ir2)[i] += (d / path) * 0.85.pow(order)
        }
        val a = onePoleLow(ir1, 6500.0, RATE)
        val b = onePoleLow(onePoleLow(ir2, 6500.0, RATE), 6500.0, RATE)
        val rng = rngFor("MIC")
        val start = 2 * room.min() / c
        val tail = DoubleArray(n) { val t = it.toDouble() / RATE; val z = rng.normal(); if (t > start) z * exp(-6.9078 * t / 0.32) else 0.0 }
        val tl = butter2(4200.0, false, RATE).run(tail)
        val k = 0.012 * (d / 0.6).pow(1.5)
        val ir = FloatArray(n) { (a[it] + b[it] + k * tl[it]).toFloat() }
        ir[0] += 1f
        return ir
    }

    /** The phone's own voice: proximity lift close up; across the room the capsule's high-pass, presence and roll-off. */
    private fun micTone(x: DoubleArray, mic: Double): DoubleArray {
        val d = expMap(mic, 0.12, 2.8)
        var y = x
        val prox = max(0.0, 6.0 * (1 - d / 0.6))
        if (prox > 0) {
            val low = butter2(180.0, false, RATE).run(y)
            val g = 10.0.pow(prox / 20) - 1
            y = DoubleArray(y.size) { y[it] + g * low[it] }
        }
        y = butter2(lin(mic, 25.0, 170.0), true, RATE).run(y)
        y = butter2(expMap(mic, 19000.0, 8500.0), false, RATE).run(y)
        val pres = lin(mic, 0.0, 3.5)
        if (pres > 0) y = peaking(2600.0, pres, 1.0, RATE).run(y)
        return y
    }

    /** Linear convolution of [x] with [h] by FFT, truncated to [len]. */
    private fun convolveFft(x: DoubleArray, h: FloatArray, len: Int): DoubleArray {
        var size = 1
        while (size < x.size + h.size - 1) size = size shl 1
        val xr = FloatArray(size); val xi = FloatArray(size)
        val hr = FloatArray(size); val hi = FloatArray(size)
        for (i in x.indices) xr[i] = x[i].toFloat()
        for (i in h.indices) hr[i] = h[i]
        Fft.forward(xr, xi)
        Fft.forward(hr, hi)
        for (i in 0 until size) {
            val r = xr[i] * hr[i] - xi[i] * hi[i]
            val im = xr[i] * hi[i] + xi[i] * hr[i]
            xr[i] = r; xi[i] = im
        }
        Fft.inverse(xr, xi)
        return DoubleArray(len) { if (it < size) xr[it].toDouble() else 0.0 }
    }

    /** At 4x: band-limit, decimate, the phone and the room, DC out, a 25 ms squared fade. */
    private fun output(raw: DoubleArray, mic: Double): FloatArray {
        val f = FloatArray(raw.size) { raw[it].toFloat() }
        Tide.bandLimit(f, FS)
        val dec = Dsp.decimate(f, RATE)
        val y = micTone(DoubleArray(dec.size) { dec[it].toDouble() }, mic)
        val len = y.size + (0.12 * RATE * mic).toInt()
        val wet = convolveFft(y, micIr(mic), len)
        // Mean out, then the house 20 Hz one-pole high-pass.
        val mean = wet.average()
        val rc = 1.0 / (2 * PI * 20.0)
        val alpha = rc / (rc + 1.0 / RATE)
        val out = FloatArray(len)
        var px = 0.0; var py = 0.0
        for (i in 0 until len) { val v = wet[i] - mean; py = alpha * (py + v - px); px = v; out[i] = py.toFloat() }
        val fade = (0.025 * RATE).toInt()
        for (q in 0 until fade) { val g = 1.0 - q.toDouble() / (fade - 1); out[len - fade + q] = (out[len - fade + q] * g * g).toFloat() }
        return out
    }
}
