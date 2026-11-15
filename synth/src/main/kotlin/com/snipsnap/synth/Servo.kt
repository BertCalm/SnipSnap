package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/** SERVO's ten machine voices and the kit role each files under by default (a kit may file a long HAT as open). */
enum class ServoVoice(val drumClass: DrumClass) {
    KICK(DrumClass.KICK), SNARE(DrumClass.SNARE), CLAP(DrumClass.CLAP), HAT(DrumClass.HAT_CLOSED), RIM(DrumClass.PERC),
    BELL(DrumClass.PERC), TOM(DrumClass.TOM), LASER(DrumClass.PERC), STAB(DrumClass.PERC), CRASH(DrumClass.PERC),
}

/**
 * SERVO: machine drums on a weak power rail, for electro, ghetto tech and neurofunk (docs/SERVO.md).
 *
 * The characteristic gesture is the trigger lands clean → the rail sags: the drum dives and flat-tops → the
 * rail rings back: the tail growls → recovered and silent before the next sixteenth.
 *
 * A port of `prototype/servo/servo_proto.py`, arithmetic for arithmetic. An analog drum machine's voices
 * (oscillators, pinged resonators, square clusters, filtered noise) are imagined on a supply too weak for the
 * job. Every hit draws current; a few milliseconds later the supply voltage, the rail, sags, and then it
 * recovers, ringing if it is underdamped. Three things in every voice read the rail at once:
 *
 * - pitch falls while the rail is down (the electro dive, exaggerated);
 * - swing: the clip ceiling and the output level fall with it (the ghetto tech flat-top, and the body ducking
 *   under its own transient);
 * - tone: a resonant peak moves with it (the neurofunk snarl when the rail rings).
 *
 * This is an ANALOGY with a stated law, not a circuit simulation. No schematic is modelled.
 *
 * Why each stage sits where it does:
 * - The rail is computed first, from the amplitude envelope alone, and the audio only reads it. It is one
 *   stable two-pole low-pass over a control signal, bounded by a tanh before anything reads it, so it is cheap,
 *   deterministic and cannot feed back. No limiter hides growth because nothing can grow.
 * - The rail lags the hit by a few milliseconds, so the first transient passes on a full rail. That keeps the
 *   punch: the attack is clean, then the body dives and squares off.
 * - TONE comes before HEAT so the moving peak is driven into the clipper: the peak decides which harmonics the
 *   clip makes. HEAT runs at 4x and is band-limited before the rate drops (the VALVE and FORGE rule).
 * - In a baked phrase every hit loads one rail ([phraseRails]), so a kick makes the hats dive for a moment.
 *   One-shot pads cannot do that to each other on an MPC, so the shared rail exists only in phrase pads.
 *
 * Disclosed against the prototype: Kotlin's [Random] replaces numpy's generator, so every noise burst and
 * free-running phase is another draw of the same law; the house band limit and decimator replace the
 * prototype's stand-ins. The TONE stage keeps the prototype's block-wise coefficients so the two agree.
 *
 * Macros: TUNE, DECAY, ZAP (how far and long the pitch is thrown at the start), SAG (how weak the supply is; 0
 * switches the rail off exactly), GROWL (how much the rail rings as it recovers), HEAT (drive into the
 * ceiling). All 0..1. No HOLD: every hit is finite. Every constant is "shape" until the owner's listen.
 */
object Servo {
    const val RATE = Dsp.RATE
    private const val FS = Dsp.RATE * Dsp.OVERSAMPLE
    /** Round 0's arithmetic. Bump when a pad's audio changes; saved patches carry it. */
    const val MODEL_VERSION = 1
    /** The takes a pad may name, 0 to [MAX_TAKE]: take 0 on a rested rail, the others on a rail still moving. */
    const val MAX_TAKE = 3
    /** A lone hit is peak-matched to this, as the prototype's audition was. */
    const val PEAK = 0.89f

    // The rail's three readouts, as depths at r = 1. "shape"
    private const val PITCH_OCT = 0.5
    private const val TONE_OCT = 1.6
    private const val CEIL_DROP = 0.7
    /** The tanh bound on the rail, so pitch and ceiling can never reach zero. */
    const val R_MAX = 0.85
    private const val PRE_LEVEL = 0.5
    private const val TONE_BLOCK = 64
    private val PRE_ROLL = (0.13 * FS).toInt()
    /** The most sag piled-up hits can cause, in units of one full kick. "shape" */
    private const val SUPPLY_FLOOR = 1.2

    // DRAW: how much current a voice pulls (its weight on a shared rail). SENS: how deeply a voice reads the sag
    // its own hit causes. CROSS: how much of the sag other hits cause reaches it, inside a phrase. "shape"
    private fun draw(v: ServoVoice) = when (v) {
        ServoVoice.KICK -> 1.0; ServoVoice.SNARE -> 0.6; ServoVoice.CLAP -> 0.45; ServoVoice.HAT -> 0.2; ServoVoice.RIM -> 0.3
        ServoVoice.BELL -> 0.35; ServoVoice.TOM -> 0.7; ServoVoice.LASER -> 0.35; ServoVoice.STAB -> 0.9; ServoVoice.CRASH -> 0.3
    }
    private fun sens(v: ServoVoice) = when (v) {
        ServoVoice.KICK -> 1.0; ServoVoice.SNARE -> 0.95; ServoVoice.CLAP -> 0.9; ServoVoice.HAT -> 0.75; ServoVoice.RIM -> 0.85
        ServoVoice.BELL -> 0.85; ServoVoice.TOM -> 1.0; ServoVoice.LASER -> 0.85; ServoVoice.STAB -> 0.95; ServoVoice.CRASH -> 0.75
    }
    private fun cross(v: ServoVoice) = when (v) {
        ServoVoice.KICK -> 0.3; ServoVoice.SNARE -> 0.5; ServoVoice.CLAP -> 0.55; ServoVoice.TOM -> 0.45; ServoVoice.STAB -> 0.4
        ServoVoice.HAT, ServoVoice.RIM, ServoVoice.BELL, ServoVoice.LASER, ServoVoice.CRASH -> 0.6
    }

    /** The six-square metal cluster's ratios, as THUMP's hat has them. */
    private val CLUSTER = doubleArrayOf(1.0, 1.342, 1.681, 1.940, 2.318, 2.703)
    private val CRASH_CLUSTER = CLUSTER + doubleArrayOf(3.41, 4.07, 5.33)

    private val MACROS = listOf(
        MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.5f), MacroSpec("ZAP", 0.4f),
        MacroSpec("SAG", 0.45f), MacroSpec("GROWL", 0.35f), MacroSpec("HEAT", 0.4f),
    )

    @Suppress("UNUSED_PARAMETER")
    fun macrosFor(voice: ServoVoice): List<MacroSpec> = MACROS

    fun defaults(voice: ServoVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** [macros] over the defaults: unknown keys dropped, a non-finite value falls back, everything clamped 0..1. */
    fun settled(voice: ServoVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun drumClassFor(voice: ServoVoice): DrumClass = voice.drumClass

    /** SCRAMBLE near the defaults (there is no preset bank until the kits are heard), the way SCUFF and CLUTTER roll. */
    fun scramble(voice: ServoVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** One hit at 44.1 kHz, peak-matched: [velocity] 0..1 is how hard, [take] 0..[MAX_TAKE] which moment of the rail. */
    fun render(voice: ServoVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0): Snip {
        val out = renderRaw(voice, macros, velocity, take)
        Dsp.normalize(out, PEAK)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /** What the rail did during a render, for tests. */
    class Diagnostics { var railMin = 0.0; var railMax = 0.0 }

    /**
     * One hit before the peak match. Its level is the model's: a full-velocity voice enters HEAT at half scale
     * and a softer one lower, so velocity reaches the rail and the clipper. [others] (at 4x) is the sag other
     * hits cause from this hit's start on, inside a phrase ([phraseRails]); null for a lone hit.
     */
    internal fun renderRaw(
        voice: ServoVoice, macros: Map<String, Float>, velocity: Float, take: Int,
        others: DoubleArray? = null, diagnostics: Diagnostics? = null,
    ): FloatArray {
        val s = settled(voice, macros)
        val m = M(s.getValue("TUNE").toDouble(), s.getValue("DECAY").toDouble(), s.getValue("ZAP").toDouble(),
            s.getValue("SAG").toDouble(), s.getValue("GROWL").toDouble(), s.getValue("HEAT").toDouble())
        val v = (if (velocity.isFinite()) velocity.toDouble() else 1.0).coerceIn(0.0, 1.0)
        if (v <= 0.0) return FloatArray((0.05 * RATE).toInt())
        val tk = take.coerceIn(0, MAX_TAKE)
        val (pitch, stretch) = drift(voice, tk)
        val env = plan(voice, m)
        if (stretch != 1.0) {          // this take rings a little longer or shorter
            val k = -6.908 * (1.0 / stretch - 1.0) / FS / max(0.03, env.size.toDouble() / FS)
            for (i in env.indices) env[i] *= exp(k * i)
        }
        val n = env.size
        val r = DoubleArray(n)
        if (m.sag > 0.0) {
            // The hit's own sag, from its own envelope (not weighted by DRAW: a lone voice's depth is SENS).
            // Inside a phrase the earlier hits are real, so the stand-in residual is left out.
            val load = DoubleArray(PRE_ROLL + n)
            if (others == null) residual(load, voice, tk)
            val w = v.pow(1.5)
            for (i in 0 until n) load[PRE_ROLL + i] = w * env[i]
            val own = railFilter(load, m.growl)
            val depth = lin(m.sag, 0.0, 1.2)
            for (i in 0 until n) {
                var seen = sens(voice) * own[PRE_ROLL + i]
                if (others != null && i < others.size) seen += cross(voice) * others[i]
                r[i] = R_MAX * tanh(depth * seen / R_MAX)
            }
        }
        val body = body(voice, m, v, tk, env, r, pitch)
        var peak = 0.0
        for (x in body.x) peak = max(peak, abs(x))
        if (peak == 0.0) peak = 1.0
        val k = PRE_LEVEL * v / peak   // an explicit level law, then the physics
        for (i in body.x.indices) body.x[i] *= k
        val out = heat(tone(body.x, body.tone, r), m.heat, r)
        if (diagnostics != null) { diagnostics.railMin = r.min(); diagnostics.railMax = r.max() }
        return finish(out)
    }

    /** What a hit draws from the supply, at 4x: its envelope, weighted by the voice and by velocity^1.5. */
    internal fun loadOf(voice: ServoVoice, macros: Map<String, Float>, velocity: Float): DoubleArray {
        val s = settled(voice, macros)
        val m = M(s.getValue("TUNE").toDouble(), s.getValue("DECAY").toDouble(), s.getValue("ZAP").toDouble(),
            s.getValue("SAG").toDouble(), s.getValue("GROWL").toDouble(), s.getValue("HEAT").toDouble())
        val w = draw(voice) * velocity.toDouble().coerceIn(0.0, 1.0).pow(1.5)
        val env = plan(voice, m)
        for (i in env.indices) env[i] *= w
        return env
    }

    /** One hit of a phrase: when (seconds), which voice at which settings, how hard. */
    internal class Event(val at: Double, val voice: ServoVoice, val macros: Map<String, Float>, val velocity: Float)

    /**
     * For a baked phrase: per event, the sag the OTHER hits cause from that event's start on (the whole rail
     * minus its own share; the rail filter is linear, so the subtraction is exact), bounded at [SUPPLY_FLOOR].
     * Pass each to [renderRaw] as `others`. [growl] is the kit's rail.
     */
    internal fun phraseRails(events: List<Event>, seconds: Double, growl: Float): List<DoubleArray> {
        val n = (seconds * FS).toInt() + FS
        val total = DoubleArray(n)
        val loads = events.map { e ->
            val at = (e.at * FS).toInt()
            val ld = loadOf(e.voice, e.macros, e.velocity).let { if (it.size > n - at) it.copyOf(max(0, n - at)) else it }
            for (i in ld.indices) total[at + i] += ld[i]
            at to ld
        }
        val g = growl.toDouble().coerceIn(0.0, 1.0)
        val rail = railFilter(total, g)
        return loads.map { (at, ld) ->
            val len = max(ld.size, min(n - at, (1.5 * FS).toInt()))
            val own = railFilter(ld.copyOf(len), g)
            DoubleArray(min(len, n - at)) { SUPPLY_FLOOR * tanh((rail[at + it] - own[it]) / SUPPLY_FLOOR) }
        }
    }

    /** The settled macros as the voices read them. */
    internal class M(val tune: Double, val decay: Double, val zap: Double, val sag: Double, val growl: Double, val heat: Double)

    // ------------------------------------------------------------------ small tools

    private fun rng(vararg parts: Any) = Random(Dsp.seedFor("SERVO", MODEL_VERSION, *parts))
    private fun lin(x: Double, lo: Double, hi: Double) = lo + (hi - lo) * x.coerceIn(0.0, 1.0)
    private fun expMap(x: Double, lo: Double, hi: Double) = lo * (hi / lo).pow(x.coerceIn(0.0, 1.0))
    private fun noise(g: Random, n: Int) = DoubleArray(n) { g.nextDouble(-1.0, 1.0) }

    private fun env(n: Int, t60: Double, attack: Double = 0.0008): DoubleArray =
        DoubleArray(n) { val t = it.toDouble() / FS; (1.0 - exp(-t / attack)) * exp(-6.908 * t / t60) }

    /** Length of a hit: its decay plus a margin, capped under the classifier's 1.5 s LOOP line. */
    private fun frames(t60: Double, cap: Double = 1.45): Int = (min(cap, t60 * 1.15 + 0.03) * FS).toInt()

    /** A direct-form II transposed biquad, a0 already divided out. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun run(x: DoubleArray, from: Int = 0, to: Int = x.size, out: DoubleArray = DoubleArray(x.size), z: DoubleArray = DoubleArray(2)): DoubleArray {
            var z1 = z[0]; var z2 = z[1]
            for (i in from until to) {
                val v = x[i]
                val y = b0 * v + z1
                z1 = b1 * v - a1 * y + z2
                z2 = b2 * v - a2 * y
                out[i] = y
            }
            z[0] = z1; z[1] = z2
            return out
        }
    }

    /** A second-order Butterworth-family section (the RBJ form with a chosen Q, which is the bilinear design). */
    private fun section(hz: Double, q: Double, high: Boolean): Biquad {
        val w0 = 2 * PI * hz / FS
        val alpha = sin(w0) / (2 * q)
        val c = cos(w0)
        val a0 = 1 + alpha
        return if (high) Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        else Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    private val Q2 = doubleArrayOf(0.7071067811865476)
    private val Q4 = doubleArrayOf(0.5411961001461970, 1.3065629648763766)

    private fun butter(x: DoubleArray, hz: Double, high: Boolean, order: Int = 2): DoubleArray {
        var y = x
        for (q in if (order == 4) Q4 else Q2) y = section(hz, q, high).run(y)
        return y
    }
    private fun hp(x: DoubleArray, hz: Double, order: Int = 2) = butter(x, hz, true, order)
    private fun lp(x: DoubleArray, hz: Double, order: Int = 2) = butter(x, min(hz, FS * 0.45), false, order)

    /** A band-pass with unity gain at its centre (scipy's iirpeak): bandwidth is centre / q. */
    private fun bp(x: DoubleArray, hzIn: Double, q: Double): DoubleArray {
        val w0 = 2 * PI * min(hzIn, FS * 0.45) / FS
        val gain = 1.0 / (1.0 + tan(w0 / q / 2))
        return Biquad(1 - gain, 0.0, -(1 - gain), -2 * gain * cos(w0), 2 * gain - 1).run(x)
    }

    /** PolyBLEP residual: rounds off a waveform's step so it does not alias (a raw square does). */
    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    private fun frac(x: Double) = x - kotlin.math.floor(x)

    private inline fun osc(n: Int, phase0: Double, freq: (Int) -> Double, wave: (Double, Double) -> Double): DoubleArray {
        val out = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) { val dt = freq(i) / FS; acc += dt; out[i] = wave(frac(acc + phase0), dt) }
        return out
    }
    private inline fun square(n: Int, phase0: Double, freq: (Int) -> Double) =
        osc(n, phase0, freq) { t, dt -> (if (t < 0.5) 1.0 else -1.0) + blep(t, dt) - blep(frac(t + 0.5), dt) }
    private inline fun saw(n: Int, phase0: Double, freq: (Int) -> Double) = osc(n, phase0, freq) { t, dt -> 2.0 * t - 1.0 - blep(t, dt) }
    private inline fun sine(n: Int, phase0: Double, freq: (Int) -> Double): DoubleArray {
        val out = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) { acc += freq(i); out[i] = sin(2 * PI * (acc / FS + phase0)) }
        return out
    }

    /** A trigger pulse: one raised-cosine bump, already band-limited by its own width. */
    private fun addClick(x: DoubleArray, widthMs: Double, amp: Double) {
        val w = max(8, (widthMs * 1e-3 * FS).toInt())
        for (i in 0 until min(w, x.size)) x[i] += amp * 0.5 * (1 - cos(2 * PI * i / w))
    }

    /** A pitch envelope: starts [amount] times above the root and lands on it exponentially. */
    private fun sweep(n: Int, amount: Double, seconds: Double) = DoubleArray(n) { 1.0 + amount * exp(-it.toDouble() / FS / seconds) }

    // ------------------------------------------------------------------ the rail

    /**
     * The supply's response to the load: a resonant two-pole low-pass with unity gain at DC. GROWL sets how it
     * recovers: critically damped at 26 Hz (a smooth sag, no ripple) to zeta 0.055 at 72 Hz (it rings for about
     * a quarter of a second). Its poles are inside the unit circle for every zeta above 0: stable by
     * construction.
     */
    private fun railFilter(load: DoubleArray, growl: Double): DoubleArray {
        val hz = expMap(growl, 26.0, 72.0)
        val zeta = expMap(growl, 1.0, 0.055)
        val w0 = 2 * PI * hz / FS
        val alpha = sin(w0) * zeta          // sin(w0) / (2 q) with q = 1 / (2 zeta)
        val c = cos(w0)
        val a0 = 1 + alpha
        return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0).run(load)
    }

    /**
     * Round robin, the machine's way. Take 0 starts on a rested rail. Takes 1 to 3 start as if another hit
     * landed 30 to 90 ms earlier, so the rail is already down and still moving when this one fires: the same
     * drum caught at a different moment of the supply's recovery.
     */
    private fun residual(load: DoubleArray, voice: ServoVoice, take: Int) {
        if (take == 0) return
        val g = rng(voice.name, "residual", take)
        val amp = doubleArrayOf(0.0, 0.55, 0.95, 0.75)[take] * (0.9 + 0.2 * g.nextDouble())
        val at = PRE_ROLL - ((0.03 + 0.06 * g.nextDouble()) * FS).toInt()
        for (i in at until PRE_ROLL) load[i] = amp * exp(-(i - at).toDouble() / FS / 0.07)
    }

    /** Analog parts are never quite where they were: a take's tuning within about 7 cents, its decay about 5 percent. */
    private fun drift(voice: ServoVoice, take: Int): Pair<Double, Double> {
        if (take == 0) return 1.0 to 1.0
        val g = rng(voice.name, "drift", take)
        return 2.0.pow((g.nextDouble() - 0.5) * 14.0 / 1200.0) to 1.0 + (g.nextDouble() - 0.5) * 0.10
    }

    /** Each take's trigger strength: the four are kept apart on purpose. "shape" */
    private val TRIGGER = doubleArrayOf(1.0, 0.76, 1.24, 0.9)

    /**
     * The trigger pulse is not the same strength twice: it sets the click and how far the pitch is thrown. Each
     * take has its own strength with a small seeded wobble, so no two takes land on the same one. Drawn freely,
     * two of a kick's takes came out 0.5 dB apart, which is one strike heard twice.
     */
    private fun trigger(voice: ServoVoice, take: Int): Double =
        if (take == 0) 1.0 else TRIGGER[take] + 0.08 * (rng(voice.name, "trigger", take).nextDouble() - 0.5)

    // ------------------------------------------------------------------ the voices
    // plan() is a voice's amplitude envelope: the rail needs it before any audio exists. body() makes the
    // sound, reading the rail r, and names the voice's moving TONE stage.

    private fun decay(m: M, lo: Double, hi: Double) = expMap(m.decay, lo, hi)

    private fun plan(voice: ServoVoice, m: M): DoubleArray = when (voice) {
        ServoVoice.KICK -> decay(m, 0.12, 1.1).let { env(frames(it), it) }
        ServoVoice.SNARE -> decay(m, 0.09, 0.42).let { env(frames(it), it) }
        ServoVoice.CLAP -> decay(m, 0.08, 0.36).let { env(frames(it + 0.03), it) }
        ServoVoice.HAT -> decay(m, 0.03, 0.62).let { env(frames(it), it, 0.0003) }
        ServoVoice.RIM -> decay(m, 0.025, 0.13).let { env(frames(it), it, 0.0002) }
        ServoVoice.BELL -> decay(m, 0.08, 0.62).let { env(frames(it), it, 0.0003) }
        ServoVoice.TOM -> decay(m, 0.12, 0.72).let { env(frames(it), it) }
        ServoVoice.LASER -> decay(m, 0.06, 0.55).let { env(frames(it), it, 0.0003) }
        ServoVoice.STAB -> {
            val t60 = decay(m, 0.12, 0.95)
            val n = frames(t60)
            val e = env(n, t60 * 1.6, 0.002)
            val hold = (n * 0.86).toInt()          // a bass hit ends on a hard mute
            val span = n - hold
            for (i in hold until n) { val g = if (span > 1) 1.0 - (i - hold).toDouble() / (span - 1) else 0.0; e[i] *= g * g }
            e
        }
        ServoVoice.CRASH -> decay(m, 0.4, 1.4).let { env(frames(it), it, 0.0004) }
    }

    private class Tone(val lowpass: Boolean, val hz: Double, val q: Double, val gainDb: Double)
    private class Body(val x: DoubleArray, val tone: Tone)

    private fun cluster(n: Int, g: Random, ratios: DoubleArray, freq: (Int) -> Double): DoubleArray {
        val out = DoubleArray(n)
        for (ratio in ratios) {
            val s = square(n, g.nextDouble()) { freq(it) * ratio }   // free-running: a new phase every take
            for (i in 0 until n) out[i] += s[i]
        }
        for (i in 0 until n) out[i] /= ratios.size
        return out
    }

    private fun body(voice: ServoVoice, m: M, v: Double, take: Int, env: DoubleArray, r: DoubleArray, pitch: Double): Body {
        val n = env.size
        val g = rng(voice.name, take)
        val dive = DoubleArray(n) { 2.0.pow(-PITCH_OCT * r[it]) * pitch }
        return when (voice) {
            ServoVoice.KICK -> {
                val root = expMap(m.tune, 38.0, 78.0)
                val phase = 0.02 * g.nextDouble()
                val trig = trigger(voice, take)
                // ZAP: from a 0.6x overshoot in 9 ms (a click of pitch) to 11x in 50 ms (a laser into a boom).
                val sw = sweep(n, expMap(m.zap, 0.6, 11.0) * trig, expMap(m.zap, 0.009, 0.05))
                val x = sine(n, phase) { root * sw[it] * dive[it] }
                for (i in 0 until n) x[i] *= env[i]
                addClick(x, 0.35 * (0.9 + 0.2 * g.nextDouble()), 0.5 * v * v * trig * trig)
                Body(x, Tone(false, root * 2.6, 1.4, 7.0))
            }
            ServoVoice.SNARE -> {
                val f1 = expMap(m.tune, 150.0, 265.0)
                val sw = sweep(n, expMap(m.zap, 0.25, 5.0), expMap(m.zap, 0.006, 0.03))
                val bodyEnv = env(n, decay(m, 0.07, 0.26))
                val a = sine(n, 0.05 * g.nextDouble()) { f1 * sw[it] * dive[it] }
                val b = sine(n, 0.0) { f1 * 1.83 * sw[it] * dive[it] }
                // The wires: noise, brighter and louder the harder the hit (velocity is not level alone).
                val wires = hp(lp(noise(g, n), 4500.0 + 11500.0 * v.pow(0.6)), 1300.0)
                val x = DoubleArray(n) { 0.7 * (a[it] + 0.55 * b[it]) * bodyEnv[it] + (0.55 + 0.45 * v) * 0.9 * wires[it] * env[it] }
                addClick(x, 0.25, 0.4 * v)
                Body(x, Tone(false, 1900.0, 3.0, 12.0))
            }
            ServoVoice.CLAP -> {
                // Three early taps and the tail, 8 to 11 ms apart, jittered per take; a soft clap loses one.
                val starts = DoubleArray(4)
                for (i in 1..3) starts[i] = starts[i - 1] + 0.0085 + 0.0025 * g.nextDouble()
                val taps = if (v > 0.5) 3 else 2
                val last = starts[taps]
                val tail = decay(m, 0.08, 0.36)
                val gate = DoubleArray(n) {
                    val t = it.toDouble() / FS
                    var s = 0.0
                    for (i in 0 until taps) if (t >= starts[i]) s += exp(-(t - starts[i]) / 0.0035) * (1.0 - 0.12 * i)
                    if (t >= last) s += exp(-6.908 * (t - last) / tail)
                    s
                }
                val centre = expMap(m.tune, 900.0, 1900.0)
                val z = noise(g, n)
                // ZAP on a noise voice sweeps the band the noise is heard through, from above.
                val hi = bp(z, centre * (1 + expMap(m.zap, 0.05, 3.0)), 1.6)
                val lo = bp(z, centre, 1.6)
                val time = expMap(m.zap, 0.004, 0.03)
                val x = DoubleArray(n) { val blend = exp(-it.toDouble() / FS / time); (hi[it] * blend + lo[it] * (1 - blend)) * gate[it] }
                Body(hp(x, 500.0), Tone(false, centre * 1.5, 3.0, 12.0))
            }
            ServoVoice.HAT -> {
                val base = expMap(m.tune, 320.0, 620.0)
                val sw = sweep(n, expMap(m.zap, 0.02, 1.4), expMap(m.zap, 0.003, 0.02))
                val c = cluster(n, g, CLUSTER) { base * sw[it] * dive[it] }
                val lo = bp(c, 8200.0, 0.9)
                val hi = bp(c, 11500.0 * (0.8 + 0.2 * v), 1.4)
                val x = hp(DoubleArray(n) { lo[it] + 0.6 * hi[it] }, 6200.0, 4)
                for (i in 0 until n) x[i] *= env[i]
                Body(x, Tone(false, 9000.0, 3.0, 11.0))
            }
            ServoVoice.RIM -> {
                val f1 = expMap(m.tune, 420.0, 920.0)
                val trig = trigger(voice, take)
                val sw = sweep(n, expMap(m.zap, 0.1, 2.5) * trig, expMap(m.zap, 0.002, 0.012))
                val a = sine(n, 0.25) { f1 * sw[it] * dive[it] }
                val b = sine(n, 0.1 * g.nextDouble()) { f1 * 1.62 * sw[it] * dive[it] }
                val x = DoubleArray(n) { (a[it] + 0.7 * b[it]) * env[it] }
                addClick(x, 0.12 * (0.9 + 0.2 * g.nextDouble()), 0.9 * v * trig * trig)
                Body(x, Tone(false, f1 * 2.6, 3.0, 9.0))
            }
            ServoVoice.BELL -> {
                val base = expMap(m.tune, 430.0, 760.0)
                val sw = sweep(n, expMap(m.zap, 0.01, 0.9), expMap(m.zap, 0.003, 0.02))
                val a = square(n, g.nextDouble()) { base * sw[it] * dive[it] }
                val b = square(n, g.nextDouble()) { base * 1.48 * sw[it] * dive[it] }
                val x = bp(DoubleArray(n) { a[it] + b[it] }, base * 1.55, 2.2)
                // A fast drop onto a slower ring.
                for (i in 0 until n) x[i] *= env[i] * (0.6 * exp(-i.toDouble() / FS / 0.012) + 0.4)
                Body(x, Tone(false, base * 3.1, 2.5, 7.0))
            }
            ServoVoice.TOM -> {
                val root = expMap(m.tune, 70.0, 260.0)
                val phase = 0.03 * g.nextDouble()
                val trig = trigger(voice, take)
                val sw = sweep(n, expMap(m.zap, 0.3, 7.0) * trig, expMap(m.zap, 0.012, 0.07))
                val a = sine(n, phase) { root * sw[it] * dive[it] }
                val b = sine(n, 0.0) { root * 2.0 * sw[it] * dive[it] }
                val skin = lp(noise(g, n), 2500.0)
                val skinEnv = env(n, 0.05)
                val x = DoubleArray(n) { (a[it] + 0.18 * b[it]) * env[it] + 0.12 * v * trig * skin[it] * skinEnv[it] }
                Body(x, Tone(false, root * 3.0, 1.6, 6.0))
            }
            ServoVoice.LASER -> {
                val top = expMap(m.tune, 1100.0, 5200.0)
                val span = expMap(m.zap, 3.0, 48.0)          // how far it falls
                val fall = decay(m, 0.06, 0.55) * 0.3
                val f = DoubleArray(n) { top * span.pow(-(1 - exp(-it.toDouble() / FS / fall))) * dive[it] }
                val a = sine(n, g.nextDouble()) { f[it] }
                val b = square(n, g.nextDouble()) { f[it] }
                Body(DoubleArray(n) { (0.6 * a[it] + 0.4 * b[it]) * env[it] }, Tone(false, top * 0.5, 2.5, 8.0))
            }
            ServoVoice.STAB -> {
                val root = expMap(m.tune, 36.7, 73.4)        // D1 to D2
                val sw = sweep(n, expMap(m.zap, 0.02, 1.0), expMap(m.zap, 0.006, 0.04))
                val cents = 2.0.pow(17.0 / 1200.0)           // two saws 34 cents apart: the beating pair. "shape"
                val a = saw(n, g.nextDouble()) { root * sw[it] * dive[it] * cents }
                val b = saw(n, g.nextDouble()) { root * sw[it] * dive[it] / cents }
                val sub = sine(n, 0.0) { root * sw[it] * dive[it] * (if (root > 55) 0.5 else 1.0) }
                val x = DoubleArray(n) { (0.5 * (a[it] + b[it]) + 0.8 * sub[it]) * env[it] }
                Body(x, Tone(true, expMap(m.tune, 700.0, 1300.0) * (0.7 + 0.5 * v), 4.5, 0.0))
            }
            ServoVoice.CRASH -> {
                val base = expMap(m.tune, 380.0, 700.0)
                val sw = sweep(n, expMap(m.zap, 0.02, 1.0), expMap(m.zap, 0.004, 0.03))
                val c = cluster(n, g, CRASH_CLUSTER) { base * sw[it] * dive[it] }
                val z = noise(g, n)
                val x = hp(DoubleArray(n) { c[it] + 0.5 * z[it] }, 4200.0, 4)
                for (i in 0 until n) x[i] *= env[i] * (0.5 + 0.5 * exp(-i.toDouble() / FS / 0.06))
                Body(x, Tone(false, 7000.0, 1.8, 7.0))
            }
        }
    }

    // ------------------------------------------------------------------ TONE and HEAT

    private fun toneCoeffs(t: Tone, hz: Double): Biquad {
        val w0 = 2 * PI * min(hz, FS * 0.42) / FS
        val alpha = sin(w0) / (2 * t.q)
        val c = cos(w0)
        if (t.lowpass) {
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
        val amp = 10.0.pow(t.gainDb / 40)                    // a peaking boost
        val a0 = 1 + alpha / amp
        return Biquad((1 + alpha * amp) / a0, -2 * c / a0, (1 - alpha * amp) / a0, -2 * c / a0, (1 - alpha / amp) / a0)
    }

    /**
     * The voice's resonant stage, its frequency riding the rail: coefficients every [TONE_BLOCK] frames at 4x
     * (2.8 kHz, far above the rail's 72 Hz) with the filter state carried across blocks, as the prototype has it.
     */
    private fun tone(x: DoubleArray, t: Tone, r: DoubleArray): DoubleArray {
        if (r.all { it == 0.0 }) return toneCoeffs(t, t.hz).run(x)
        val out = DoubleArray(x.size)
        val z = DoubleArray(2)
        var i = 0
        while (i < x.size) {
            toneCoeffs(t, t.hz * 2.0.pow(-TONE_OCT * r[i])).run(x, i, min(x.size, i + TONE_BLOCK), out, z)
            i += TONE_BLOCK
        }
        return out
    }

    /**
     * Drive into a ceiling the rail sets, through an output stage whose swing the rail also sets. Linear below
     * half the ceiling, then a tanh knee, so HEAT 0 on a rested rail changes nothing. As the rail falls the same
     * signal sits closer to the ceiling (more clip) and the whole stage swings less (the body ducks under the
     * transient; a ringing rail becomes an audio-rate tremolo, the growl). A small bias makes the clip lopsided;
     * the DC it leaves is removed by the output stage.
     */
    private fun heat(x: DoubleArray, amount: Double, r: DoubleArray): DoubleArray {
        val gain = 10.0.pow(lin(amount, 0.0, 24.0) / 20)
        return DoubleArray(x.size) {
            val ceiling = 1.0 - CEIL_DROP * r[it]
            val u = (x[it] * gain + 0.12 * amount * ceiling) / ceiling
            val mag = abs(u)
            val shaped = if (mag <= 0.5) u else sign(u) * (0.5 + 0.5 * tanh((mag - 0.5) / 0.5))
            shaped * ceiling * ceiling
        }
    }

    /** At 4x: the house band limit and decimator, the mean out, the 20 Hz one-pole high-pass, a 4 ms tail fade. */
    private fun finish(raw: DoubleArray): FloatArray {
        val f = FloatArray(raw.size) { raw[it].toFloat() }
        Tide.bandLimit(f, FS)
        val dec = Dsp.decimate(f, RATE)
        var mean = 0.0
        for (v in dec) mean += v
        mean /= max(1, dec.size)
        val alpha = 1.0 / (1.0 + 2 * PI * 20.0 / RATE)
        val out = FloatArray(dec.size)
        var px = 0.0; var py = 0.0
        for (i in dec.indices) { val v = dec[i] - mean; py = alpha * (py + v - px); px = v; out[i] = py.toFloat() }
        val fade = min(out.size, (0.004 * RATE).toInt())
        for (q in 0 until fade) out[out.size - fade + q] *= if (fade > 1) 1f - q.toFloat() / (fade - 1) else 0f
        return out
    }

}
