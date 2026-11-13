package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/** The ten SCUFF pads: five core drums, five rare accents. */
enum class ScuffVoice { KICK, CLAP, BOOM, RIM, HAT, BELL, ZAP, TICK, PLUCK, INHALE }

/**
 * SCUFF — a dry, mid-forward Michigan drum kit and the chain that makes it.
 *
 * Round 1: the Kotlin port of the round-0b Python prototype (`prototype/scuff/scuff_proto.py`, docs/SCUFF.md),
 * whose sound direction the owner accepted on 2026-11-12. Every constant here is that prototype's; where the
 * port could not be the same arithmetic (the resampler, the noise generator, the take dealing) the KDoc says so.
 *
 * The gesture: a dry hit lands at the front of the speaker, the mids bark, the bus squares it off against the
 * ceiling, and it is gone before the next sixteenth. Every pad goes
 *
 *     voice (4x) ─► band-limit, decimate ─► GRIT ─► LOCKER ─► BARK ─► up 4x ─► SLAM ─► tail gate ─► finish
 *
 * - The voices synthesize at 4x because the kick's and the bass hit's saturation, the rim's overdrive and the
 *   hat's square cluster make harmonics that would alias at 44.1 kHz.
 * - GRIT (a sample-and-hold plus bit reduction) runs at 1x on purpose: the aliasing is the sound, as CRUNCH does
 *   it. The brief keeps it on the clap, the rim and the hat, so the kick and the bass stay clean.
 * - LOCKER (a feedback comb 11 to 24 ms long with a band-pass and a soft clip in its loop) and BARK (a resonant
 *   band-pass parked at 1.1 kHz, boosted and blended in) are parallel sends at 1x; at 0 each is an exact bypass.
 * - SLAM is the bus clipper: an asymmetric cubic clip (a DC offset before the clip makes even harmonics) whose
 *   clipped path is low-passed at 600 Hz, blended with the dry path, then a hard ceiling. It goes back to 4x
 *   because a clipper makes harmonics (the VALVE and FORGE rule).
 * - The tail gate ends every pad 2 ms after its envelope last falls 40 dB under its peak: bone dry.
 *
 * Velocity is how hard the pad was hit, 0..1: the kick drops deeper and clicks harder, the bass punches and
 * drives harder, the clap loses a tap and shortens, the rim's upper peak and overdrive grow, GRIT bites deeper,
 * the hat and the tick brighten. A take (round robin) is another strike: four audible dimensions per voice are
 * dealt on a four-position Latin square so no two of four takes share a setting (the ETCH lesson: small
 * independent wobbles read as one strike). The hat's takes are free-running oscillator phases, as on the
 * original circuit.
 *
 * Macros: TUNE and DECAY per voice, inside the brief's registers and windows; SLAM, GRIT, BARK and LOCKER
 * shared by the chain; plus one or two voice extras. All 0..1. Trademark rule: no maker or model number
 * names a voice or a preset; the research is in docs/SCUFF.md.
 */
object Scuff {

    const val RATE = Dsp.RATE
    private const val FS = Dsp.RATE * Dsp.OVERSAMPLE
    /** Round 0b's arithmetic. Bump when a pad's audio changes; saved patches carry it. */
    const val MODEL_VERSION = 1
    /** A pad ends 2 ms after its 5 ms envelope last falls this far under its peak. "shape" */
    const val TAIL_GATE_DB = -40f
    /** The takes a pad may name: 0 is the plain strike, 1 to [MAX_TAKE] the dealt round-robin takes. */
    const val MAX_TAKE = 4

    /** The chain stages, so a test or an audition can switch them in one at a time. */
    internal enum class Stage { GRIT, LOCKER, BARK, SLAM }
    internal val ALL_STAGES: Set<Stage> = Stage.entries.toSet()

    private val SLAM = MacroSpec("SLAM", 0.65f)
    private val GRIT = MacroSpec("GRIT", 0.55f)
    private val BARK = MacroSpec("BARK", 0.5f)
    private val LOCKER = MacroSpec("LOCKER", 0.45f)

    /** TUNE and DECAY, the voice's extras, then the chain knobs this voice takes (a knob that did nothing would be a lie on the panel). */
    fun macrosFor(voice: ScuffVoice): List<MacroSpec> =
        listOf(MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.5f)) + extras(voice) + listOf(SLAM) +
            listOfNotNull(GRIT.takeIf { takesGrit(voice) }, BARK.takeIf { takesBark(voice) }, LOCKER.takeIf { takesLocker(voice) })

    private fun extras(voice: ScuffVoice): List<MacroSpec> = when (voice) {
        ScuffVoice.KICK -> listOf(MacroSpec("CLICK", 0.6f), MacroSpec("SQUARE", 0.5f))
        ScuffVoice.CLAP -> listOf(MacroSpec("SNAP", 0.5f))
        ScuffVoice.BOOM -> listOf(MacroSpec("DRIVE", 0.65f))
        ScuffVoice.RIM -> listOf(MacroSpec("BITE", 0.65f))
        ScuffVoice.BELL -> listOf(MacroSpec("TONE", 0.55f))
        ScuffVoice.ZAP -> listOf(MacroSpec("SHAPE", 0.5f))
        ScuffVoice.HAT, ScuffVoice.TICK, ScuffVoice.PLUCK, ScuffVoice.INHALE -> emptyList()
    }

    fun defaults(voice: ScuffVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** [macros] over the defaults: unknown keys dropped, a non-finite value falls back, everything clamped 0..1. */
    fun settled(voice: ScuffVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    /** What the kit files each pad as. The classifier's own verdicts are recorded in ScuffTest, pad by pad. */
    fun drumClassFor(voice: ScuffVoice): DrumClass = when (voice) {
        ScuffVoice.KICK -> DrumClass.KICK
        ScuffVoice.CLAP -> DrumClass.CLAP
        ScuffVoice.HAT -> DrumClass.HAT_CLOSED
        ScuffVoice.BOOM, ScuffVoice.RIM, ScuffVoice.BELL, ScuffVoice.ZAP, ScuffVoice.TICK, ScuffVoice.PLUCK, ScuffVoice.INHALE -> DrumClass.PERC
    }

    /** SCRAMBLE near the defaults (there is no preset bank yet), the way THUMP rolls. */
    fun scramble(voice: ScuffVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** Which chain stages the brief gives each voice; SLAM is the bus and takes every pad. */
    private fun takesGrit(voice: ScuffVoice) = voice == ScuffVoice.CLAP || voice == ScuffVoice.RIM || voice == ScuffVoice.HAT
    private fun takesLocker(voice: ScuffVoice) = voice == ScuffVoice.CLAP || voice == ScuffVoice.RIM || voice == ScuffVoice.PLUCK
    private fun takesBark(voice: ScuffVoice) = voice == ScuffVoice.CLAP || voice == ScuffVoice.RIM || voice == ScuffVoice.PLUCK || voice == ScuffVoice.BELL
    /** The comb's length per voice, ms: the size of the metal box. "shape" */
    private fun lockerMs(voice: ScuffVoice) = when (voice) { ScuffVoice.CLAP -> 19.0; ScuffVoice.RIM -> 11.0; else -> 24.0 }

    /** Render [voice] at [velocity] (0..1) as take [take] (0 is the plain strike; 1 to 4 are dealt takes). */
    fun render(voice: ScuffVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0): Snip {
        val buf = renderPad(voice, macros, velocity, take, ALL_STAGES)
        return Snip(finish(buf), channels = 1, sampleRate = RATE)
    }

    /** SLAM on a mix of pads (the bus), for an audition of the kick and the bass fusing. Not yet levelled. */
    internal fun slamBus(mix: FloatArray, amount: Float): FloatArray {
        val y = slam(DoubleArray(mix.size) { mix[it].toDouble() }, amount.coerceIn(0f, 1f).toDouble())
        return FloatArray(y.size) { y[it].toFloat() }
    }

    /** The house ending for a pad: the mean and anything under 20 Hz removed, peak-matched, a 4 ms fade. */
    internal fun finish(buf: FloatArray): FloatArray {
        val out = dcBlock(buf)
        Dsp.normalize(out)
        Dsp.fadeTail(out, rate = RATE)
        return out
    }

    /**
     * One pad through the chain at the output rate, tail-gated, not yet levelled. [stages] picks which chain
     * stages run (the voice always does), so the audition can switch them in one at a time.
     */
    internal fun renderPad(voice: ScuffVoice, macros: Map<String, Float>, velocity: Float, take: Int, stages: Set<Stage>, gate: Boolean = true): FloatArray {
        val m = settled(voice, macros)
        val vel = velocity.coerceIn(0f, 1f).toDouble()
        val tk = takeFor(voice, take)
        val raw = when (voice) {
            ScuffVoice.KICK -> kick(m, vel, tk)
            ScuffVoice.CLAP -> clap(m, vel, tk)
            ScuffVoice.BOOM -> boom(m, vel, tk)
            ScuffVoice.RIM -> rim(m, vel, tk)
            ScuffVoice.HAT -> hat(m, vel, tk)
            ScuffVoice.BELL -> bell(m, vel, tk)
            ScuffVoice.ZAP -> zap(m, vel, tk)
            ScuffVoice.TICK -> tick(m, vel, tk)
            ScuffVoice.PLUCK -> pluck(m, vel, tk)
            ScuffVoice.INHALE -> inhale(m, vel, tk)
        }
        var y = toOutputRate(raw)
        if (Stage.GRIT in stages && takesGrit(voice)) y = grit(y, m.getValue("GRIT").toDouble(), vel)
        if (Stage.LOCKER in stages && takesLocker(voice)) y = locker(y, m.getValue("LOCKER").toDouble(), lockerMs(voice))
        if (Stage.BARK in stages && takesBark(voice)) y = bark(y, m.getValue("BARK").toDouble())
        if (Stage.SLAM in stages) y = slam(y, m.getValue("SLAM").toDouble())
        if (gate && voice != ScuffVoice.INHALE) y = tailGate(y)
        return FloatArray(y.size) { y[it].toFloat() }
    }

    // ------------------------------------------------------------------ takes

    /** One take's four dealt dimensions (-1..1), its free phase and its own noise. */
    internal class Take(val a: Double, val b: Double, val c: Double, val d: Double, val phase: Double, val noise: Dsp.Noise)

    private val STRATA = doubleArrayOf(-0.75, -0.25, 0.25, 0.75)
    private val PERM_A = intArrayOf(0, 1, 2, 3)
    private val PERM_B = intArrayOf(2, 0, 3, 1)
    private val PERM_C = intArrayOf(1, 3, 0, 2)
    private val PERM_D = intArrayOf(3, 2, 1, 0)

    /**
     * Take 0 is the plain render. Takes 1 to 4 are dealt on a Latin square: on each of the four dimensions the
     * four takes take the four strata in a different order, so no two share a position on any dimension, and a
     * small jitter inside the stratum keeps them from being a grid. The prototype hashed the voice into the
     * stratum choice; here take k always takes column k - 1, which is simpler and still dealt.
     */
    internal fun takeFor(voice: ScuffVoice, take: Int): Take {
        val noise = Dsp.Noise(Dsp.seedFor("SCUFF", voice.name, "noise", take))
        if (take <= 0) return Take(0.0, 0.0, 0.0, 0.0, 0.0, noise)
        val rng = Random(Dsp.seedFor("SCUFF", voice.name, "take", take))
        val k = (take - 1) % 4
        fun jit() = (rng.nextDouble() - 0.5) * 0.3
        return Take(
            STRATA[PERM_A[k]] + jit(), STRATA[PERM_B[k]] + jit(), STRATA[PERM_C[k]] + jit(), STRATA[PERM_D[k]] + jit(),
            rng.nextDouble(), noise,
        )
    }

    // ------------------------------------------------------------------ small tools (Double, at FS unless said)

    private fun lin(x: Float, lo: Double, hi: Double) = lo + (hi - lo) * x.coerceIn(0f, 1f)
    private fun lin(x: Double, lo: Double, hi: Double) = lo + (hi - lo) * x.coerceIn(0.0, 1.0)
    private fun expMap(x: Float, lo: Double, hi: Double) = lo * (hi / lo).pow(x.coerceIn(0f, 1f).toDouble())
    private fun expMap(x: Double, lo: Double, hi: Double) = lo * (hi / lo).pow(x.coerceIn(0.0, 1.0))

    private fun frames(seconds: Double) = (seconds * FS).toInt().coerceAtLeast(64)
    private fun tAt(i: Int) = i.toDouble() / FS

    /** A -60 dB decay in [t60] seconds, after an optional hold. */
    private fun expEnv(t: Double, t60: Double, hold: Double = 0.0) = exp(-6.9078 * max(t - hold, 0.0) / t60)

    /** The genre's squared-off decay: the exponential lifted and clipped, so the body sits at full level, then falls. "shape" */
    private fun squaredTail(t: Double, t60: Double, plateau: Double) = min(1.0, plateau * expEnv(t, t60))

    private fun noise(tk: Take, n: Int) = DoubleArray(n) { tk.noise.next().toDouble() }

    /** The running phase of a frequency curve, radians. */
    private fun phaseOf(hz: DoubleArray): DoubleArray {
        val ph = DoubleArray(hz.size)
        var acc = 0.0
        for (i in hz.indices) { acc += 2.0 * PI * hz[i] / FS; ph[i] = acc }
        return ph
    }

    /** Asymmetric soft clip: a DC offset before tanh, the offset's own output subtracted after, so even harmonics appear and no DC leaves. */
    private fun asymClip(x: Double, drive: Double, bias: Double) = tanh(drive * (x + bias)) - tanh(drive * bias)

    /** A cubic polynomial clipper: soft up to +-1.5 (where it reaches 1), hard beyond. */
    private fun cubicClip(x: Double): Double { val s = x.coerceIn(-1.5, 1.5); return s - s * s * s / 6.75 }

    private fun triangle(ph: Double) = (2.0 / PI) * asin(sin(ph))

    private fun peakOf(x: DoubleArray): Double { var p = 0.0; for (v in x) p = max(p, abs(v)); return p }
    private fun peakOf(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }
    private fun normalized(x: DoubleArray): DoubleArray { val p = peakOf(x) + 1e-9; return DoubleArray(x.size) { x[it] / p } }

    /** A second-order section in direct form I, RBJ coefficients (the same form as [Dsp.Biquad], in Double). */
    private class Biq(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        val b0 = b0 / a0; val b1 = b1 / a0; val b2 = b2 / a0; val a1 = a1 / a0; val a2 = a2 / a0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun run(x: DoubleArray) = DoubleArray(x.size) { step(x[it]) }
    }

    private fun lowpass(f0: Double, q: Double, fs: Int): Biq {
        val w0 = 2.0 * PI * f0 / fs; val cw = cos(w0); val alpha = sin(w0) / (2.0 * q)
        return Biq((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }
    private fun highpass(f0: Double, q: Double, fs: Int): Biq {
        val w0 = 2.0 * PI * f0 / fs; val cw = cos(w0); val alpha = sin(w0) / (2.0 * q)
        return Biq((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }
    /** A constant 0 dB peak band-pass: scipy's `iirpeak`, which the prototype used everywhere. */
    private fun bandpass(f0: Double, q: Double, fs: Int): Biq {
        val w0 = 2.0 * PI * f0 / fs; val cw = cos(w0); val alpha = sin(w0) / (2.0 * q)
        return Biq(alpha, 0.0, -alpha, 1 + alpha, -2 * cw, 1 - alpha)
    }

    /** Butterworth sections' Q values by order, so a cascade of RBJ biquads is the prototype's `scipy.signal.butter`. */
    private fun butterQ(order: Int) = when (order) {
        2 -> doubleArrayOf(0.70710678)
        4 -> doubleArrayOf(0.54119610, 1.30656296)
        6 -> doubleArrayOf(0.51763809, 0.70710678, 1.93185165)
        else -> error("Butterworth order $order is not one the kit uses")
    }

    private fun butterLp(x: DoubleArray, hz: Double, order: Int, fs: Int): DoubleArray {
        var y = x
        for (q in butterQ(order)) y = lowpass(hz, q, fs).run(y)
        return y
    }

    private fun butterHp(x: DoubleArray, hz: Double, order: Int, fs: Int): DoubleArray {
        var y = x
        for (q in butterQ(order)) y = highpass(hz, q, fs).run(y)
        return y
    }

    /** A first-order section by the bilinear transform, for BARK's gentle narrowing of the dry path. */
    private fun first(x: DoubleArray, hz: Double, high: Boolean, fs: Int): DoubleArray {
        val k = tan(PI * hz / fs)
        val a1 = (k - 1) / (k + 1)
        val b0 = if (high) 1 / (1 + k) else k / (1 + k)
        val b1 = if (high) -b0 else b0
        val out = DoubleArray(x.size)
        var x1 = 0.0; var y1 = 0.0
        for (i in x.indices) { val y = b0 * x[i] + b1 * x1 - a1 * y1; x1 = x[i]; y1 = y; out[i] = y }
        return out
    }

    /** The band-pass alone, or, with [gainDb], the input plus the band-pass scaled: a parallel boost. */
    private fun peakFilter(x: DoubleArray, hz: Double, q: Double, fs: Int, gainDb: Double? = null): DoubleArray {
        val bp = bandpass(hz, q, fs).run(x)
        if (gainDb == null) return bp
        val g = 10.0.pow(gainDb / 20) - 1.0
        return DoubleArray(x.size) { x[it] + g * bp[it] }
    }

    /** A two-pole resonant low-pass: the rubbery bounce of a resonant filter near the body of a kick. */
    private fun resonantLp(x: DoubleArray, hz: Double, q: Double, fs: Int) = lowpass(hz, q, fs).run(x)

    /** Band-limit at 4x and decimate to the output rate, the house way. */
    private fun toOutputRate(raw: DoubleArray): DoubleArray {
        val f = FloatArray(raw.size) { raw[it].toFloat() }
        Tide.bandLimit(f, FS)
        val d = Dsp.decimate(f, RATE)
        return DoubleArray(d.size) { d[it].toDouble() }
    }

    /** Zero-stuff by 4 and band-limit: VALVE's upsampler. */
    private fun up4(y: DoubleArray): DoubleArray {
        val u = Valve.upsample(FloatArray(y.size) { y[it].toFloat() }, RATE)
        return DoubleArray(u.size) { u[it].toDouble() }
    }

    // ------------------------------------------------------------------ the voices (at FS, peak about 1)

    /**
     * Punch kick: 80 to 160 Hz body, a 300 to 95 Hz drop in about 27 ms, a 3 kHz click over a 420 Hz knock, a
     * resonant bounce near the body, a hard asymmetric shaper, a squared-off tail, nothing under 45 Hz.
     */
    private fun kick(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val fEnd = expMap(m.getValue("TUNE"), 70.0, 140.0)
        val fStart = fEnd * 3.0 * (1.0 + 0.15 * tk.a) * (0.45 + 0.55 * vel)
        val tau = 0.009 * (1.0 + 0.35 * tk.b)
        val t60 = expMap(m.getValue("DECAY"), 0.09, 0.22) * (1.0 + 0.12 * tk.c) * (0.8 + 0.2 * vel)
        val n = frames(t60 * 1.5 + 0.05)
        val square = m.getValue("SQUARE").toDouble()
        val hz = DoubleArray(n) { fEnd + (fStart - fEnd) * exp(-tAt(it) / tau) }
        val ph = phaseOf(hz)
        val body = DoubleArray(n) { i -> ((1 - square) * sin(ph[i]) + square * triangle(ph[i])) * squaredTail(tAt(i), t60, 1.8) }
        val nz = noise(tk, n)
        val clickDelay = (0.0005 * (1.0 + 0.5 * tk.d) * FS).toInt()
        val c0 = peakFilter(DoubleArray(n) { nz[it] * expEnv(tAt(it), 0.004) }, 3000.0 * (0.8 + 0.2 * vel), 2.0, FS)
        val clickGain = m.getValue("CLICK") * vel * vel * vel * 2.2
        val click = DoubleArray(n) { i -> if (i >= clickDelay) c0[i - clickDelay] * clickGain else 0.0 }
        val knock = peakFilter(DoubleArray(n) { nz[it] * expEnv(tAt(it), 0.006) }, 420.0, 3.0, FS)
        val knockGain = (0.4 + 0.6 * vel) * 1.2
        val drive = (2.5 + 2.0 * square) * (0.5 + 0.5 * vel)
        val shaped = DoubleArray(n) { i -> asymClip(body[i] * (0.6 + 0.4 * vel) + knock[i] * knockGain, drive, 0.25) }
        val bounced = resonantLp(shaped, fEnd * 2.2, 2.2, FS)
        return butterHp(DoubleArray(n) { bounced[it] + click[it] }, 45.0, 2, FS)
    }

    /**
     * Flint clap: four 3 ms taps 8 to 12 ms apart rising in level (three on a soft hit), a whip crack (a sine
     * falling from 2.5 kHz to 900 Hz over 12 ms) on the last, a 60 to 90 ms burst, high-passed at 250 Hz, a
     * +10 dB peak near 1.6 kHz, air from 7 kHz, a 220 Hz body, a hard ceiling in the voice.
     */
    private fun clap(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val spacing = lin(m.getValue("SNAP"), 0.012, 0.008) * (1.0 + 0.12 * tk.a)
        var t60 = expMap(m.getValue("DECAY"), 0.05, 0.12) * (1.0 + 0.08 * tk.b)
        val peakHz = 1600.0 * 2.0.pow(0.5 * (m.getValue("TUNE") - 0.5)) * (1.0 + 0.05 * tk.c) * (0.85 + 0.25 * vel)
        val taps = if (vel >= 0.55) 4 else 3
        t60 *= (0.8 + 0.2 * vel)
        val n = frames(spacing * 4 + t60 * 1.4)
        val nz = noise(tk, n)
        val env = DoubleArray(n)
        for (k in 0 until taps) {
            val t0 = k * spacing * (1.0 + 0.04 * tk.d * (k % 2 * 2 - 1))
            val level = 0.7 + 0.1 * k
            for (i in 0 until n) { val t = tAt(i); if (t >= t0) env[i] += expEnv(t, 0.003, t0) * level }
        }
        val tailT0 = (taps - 1) * spacing
        for (i in 0 until n) { val t = tAt(i); if (t >= tailT0) env[i] += expEnv(t, t60, tailT0) * 0.8 }
        var y = DoubleArray(n) { nz[it] * env[it] }
        y = butterHp(y, 250.0, 4, FS)
        y = peakFilter(y, peakHz, 2.5, FS, gainDb = 10.0)
        y = peakFilter(y, 7000.0, 1.0, FS, gainDb = 3.0)
        val bodyHz = 220.0 * 2.0.pow((m.getValue("TUNE") - 0.5).toDouble())
        val crackHz = DoubleArray(n) { 900.0 + 1600.0 * exp(-max(tAt(it) - tailT0, 0.0) / 0.004) }
        val crackPh = phaseOf(crackHz)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val t = tAt(i)
            val body = triangle(2.0 * PI * bodyHz * t) * expEnv(t, 0.035)
            val crack = if (t >= tailT0) sin(crackPh[i]) * expEnv(t, 0.012, tailT0) else 0.0
            out[i] = ((y[i] + 0.25 * body * vel + 0.6 * crack * vel) * (0.6 + 0.4 * vel) * 1.5).coerceIn(-0.8, 0.8)
        }
        return out
    }

    /**
     * Staccato bass hit: 45 to 80 Hz, a fast pitch punch, the body held in the shaper by a lifted tail; a separate
     * harmonic path (high-passed at 90 Hz, clipped hard, kept to 100 to 400 Hz) under the sub so the pitch reads
     * on a phone; low-passed at 500 Hz; muted hard at 0.7 of the decay, while the note is still loud.
     */
    private fun boom(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val f0 = expMap(m.getValue("TUNE"), 45.0, 80.0)
        val punch = (1.5 + 1.5 * vel) * (1.0 + 0.3 * tk.a)
        val tau = 0.004 * (1.0 + 0.35 * tk.b)
        val t60 = expMap(m.getValue("DECAY"), 0.20, 0.50) * (1.0 + 0.10 * tk.c) * (0.8 + 0.2 * vel)
        val gateAt = t60 * 0.7
        val n = frames(gateAt + 0.01)
        val hz = DoubleArray(n) { f0 * (1.0 + punch * exp(-tAt(it) / tau)) }
        val ph = phaseOf(hz)
        val body = DoubleArray(n) { i -> val t = tAt(i); sin(ph[i] + 0.3 * tk.phase) * min(1.0, t / 0.001) * squaredTail(t, t60 * 1.3, 2.5) }
        val drive = m.getValue("DRIVE") * (0.4 + 0.6 * vel)
        val sub = butterLp(DoubleArray(n) { asymClip(body[it], 1.0 + 4.0 * drive, 0.15) }, 500.0, 4, FS)
        var harm = butterHp(body, 90.0, 2, FS)
        harm = DoubleArray(n) { asymClip(harm[it] * 4.0, 2.0 + 8.0 * drive, 0.3) }
        harm = butterLp(butterHp(harm, 100.0, 2, FS), 420.0, 4, FS)
        val harmGain = (0.35 + 0.45 * drive) * (0.4 + 0.6 * vel)   // a soft hit saturates less (round 1)
        return DoubleArray(n) { i -> (sub[i] + harmGain * harm[i]) * ((gateAt - tAt(i)) / 0.003).coerceIn(0.0, 1.0) }
    }

    /**
     * Stank rim: a 2 ms bipolar impulse into two high-Q resonators at 850 and 1350 Hz with a woody body at 0.56x,
     * a loud 1.5 ms click, a 40 to 70 ms ring, overdriven. Velocity reaches the click, the upper peak and the bite.
     */
    private fun rim(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val f1 = 850.0 * 2.0.pow((m.getValue("TUNE") - 0.5).toDouble()) * (1.0 + 0.025 * tk.a)
        val f2 = f1 * (1350.0 / 850.0) * (1.0 + 0.04 * tk.b)
        val t60 = expMap(m.getValue("DECAY"), 0.035, 0.09) * (1.0 + 0.15 * tk.c) * (0.85 + 0.15 * vel)
        val width = 0.002 * (1.0 + 0.4 * tk.d)
        val n = frames(t60 * 1.5 + 0.02)
        val nz = noise(tk, n)
        val imp = DoubleArray(n) { val t = tAt(it); if (t < width) sin(2.0 * PI * t / width) else 0.0 }
        val click = DoubleArray(n) { val t = tAt(it); if (t < 0.0015) nz[it] * expEnv(t, 0.002) * (0.6 + 0.6 * vel) else 0.0 }
        val r1 = peakFilter(imp, f1, 26.0, FS)
        val r2 = peakFilter(imp, f2, 26.0, FS)
        val r3 = peakFilter(imp, f1 * 0.56, 12.0, FS)
        val upper = 0.4 + 0.5 * vel
        val ring = normalized(DoubleArray(n) { r1[it] + upper * r2[it] + 0.35 * r3[it] })
        val bite = 1.0 + 8.0 * m.getValue("BITE") * vel * vel
        return DoubleArray(n) { i ->
            val t = tAt(i)
            tanh(bite * (ring[i] * expEnv(t, t60) + 0.9 * click[i] + 0.5 * imp[i] * expEnv(t, 0.004))) / tanh(bite)
        }
    }

    /** The classic six-oscillator cluster, Hz: a hex Schmitt inverter's six squares (docs/SCUFF.md, research). */
    private val HAT_HZ = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)

    /**
     * Chopper hat: six squares, high-passed at 5 kHz, a +12 dB ring near 9.5 kHz, a 1 ms tip of 9 kHz noise on the
     * contact, a two-stage decay (8 ms then the ring), clipped clean. The oscillators run free: a take catches new phases.
     */
    private fun hat(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val scale = 2.0.pow(0.5 * (m.getValue("TUNE") - 0.5))
        val t60 = expMap(m.getValue("DECAY"), 0.020, 0.060) * (1.0 + 0.08 * tk.c)
        val n = frames(t60 * 1.6 + 0.01)
        val phases = DoubleArray(HAT_HZ.size) { if (tk.a != 0.0) 0.35 * tk.noise.next().toDouble().let { (it + 1) / 2 } else 0.0 }
        var y = DoubleArray(n) { i ->
            val t = tAt(i)
            var s = 0.0
            for (k in HAT_HZ.indices) s += if (sin(2.0 * PI * (HAT_HZ[k] * scale * t + phases[k])) >= 0) 1.0 else -1.0
            s
        }
        y = butterHp(y, 5000.0 * (0.8 + 0.2 * vel), 6, FS)
        y = peakFilter(y, 9500.0 * 2.0.pow(0.3 * (m.getValue("TUNE") - 0.5)), 3.0, FS, gainDb = 12.0)
        for (i in 0 until n) { val t = tAt(i); y[i] *= (0.55 * expEnv(t, 0.008) + 0.45 * expEnv(t, t60)) * (0.5 + 0.5 * vel) }
        val ring = normalized(y)
        val tipNoise = noise(tk, n)
        val tip = butterHp(tipNoise, 9000.0, 4, FS)
        return DoubleArray(n) { i -> ((ring[i] + tip[i] * expEnv(tAt(i), 0.0012) * 1.5 * vel) * 1.3).coerceIn(-0.9, 0.9) }
    }

    /**
     * Bando bell: two-operator FM at 1 : 1.414 with a second modulator at 1 : 2.756 that dies in 80 ms (the clang
     * on the strike), 600 to 900 ms, drifting 12 cents flat over the tail.
     */
    private fun bell(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val fc = expMap(m.getValue("TUNE"), 300.0, 1200.0)
        val ratio = 1.414 * (1.0 + 0.01 * tk.b)
        val t60 = expMap(m.getValue("DECAY"), 0.5, 1.1) * (0.75 + 0.25 * vel)
        val index = lin(m.getValue("TONE"), 1.5, 7.0) * (1.0 + 0.25 * tk.a) * (0.4 + 0.6 * vel)
        val n = frames(t60 * 1.2)
        val fcT = DoubleArray(n) { fc * 2.0.pow(-(12.0 + 3.0 * tk.c) / 1200.0 * min(1.0, tAt(it) / t60)) }
        val phC = phaseOf(fcT)
        val phM = phaseOf(DoubleArray(n) { fcT[it] * ratio })
        val phClang = phaseOf(DoubleArray(n) { fcT[it] * 2.756 })
        return DoubleArray(n) { i ->
            val t = tAt(i)
            val mod = sin(phM[i] + 2.0 * PI * tk.phase) * index * expEnv(t, t60 * 0.5)
            val clang = sin(phClang[i]) * index * 0.8 * expEnv(t, 0.08) * vel
            tanh(1.6 * sin(phC[i] + mod + clang) * min(1.0, t / 0.001) * expEnv(t, t60)) / tanh(1.6)
        }
    }

    /** Laser tom: an exponential sweep from 1.2 kHz to 120 Hz over 80 to 130 ms, clipped hardest at the top of the sweep. */
    private fun zap(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val fHi = expMap(m.getValue("TUNE"), 800.0, 1800.0) * (1.0 + 0.06 * tk.a) * (0.6 + 0.4 * vel)
        val sweep = expMap(m.getValue("DECAY"), 0.08, 0.13) * (1.0 + 0.1 * tk.b)
        val n = frames(sweep * 1.4)
        val ph = phaseOf(DoubleArray(n) { fHi * (120.0 / fHi).pow(min(1.0, tAt(it) / sweep)) })
        val shape = m.getValue("SHAPE").toDouble()
        val y = DoubleArray(n) { i ->
            val t = tAt(i)
            val p = ph[i] + 2.0 * PI * tk.phase
            val w = (1 - shape) * sin(p) + shape * triangle(p)
            val gain = 1.2 + 2.0 * exp(-t / (sweep * 0.3)) * vel
            tanh(gain * w) * expEnv(t, sweep * 1.2) * (0.6 + 0.4 * vel)
        }
        return normalized(y)
    }

    /** Money counter: 7 kHz+ noise, 10 to 15 ms, with a 0.8 ms pitched click at 3.6 kHz under the hiss. TUNE moves the cutoff half an octave either way. */
    private fun tick(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val t60 = expMap(m.getValue("DECAY"), 0.008, 0.020) * (0.7 + 0.3 * vel)
        val cut = 7000.0 * 2.0.pow((m.getValue("TUNE") - 0.5).toDouble()) * (1.0 + 0.1 * tk.a) * (1.4 - 0.4 * vel)
        val n = frames(t60 * 2.0)
        val nz = noise(tk, n)
        val hiss = normalized(butterHp(DoubleArray(n) { val t = tAt(it); nz[it] * min(1.0, t / 0.0003) * expEnv(t, t60) }, cut, 4, FS))
        val clickHz = 3600.0 * (1.0 + 0.05 * tk.b)
        return normalized(DoubleArray(n) { i -> val t = tAt(i); hiss[i] * (0.4 + 0.6 * vel) + sin(2.0 * PI * clickHz * t) * expEnv(t, 0.0008) * 0.7 })
    }

    /** Hollow conga: one sine at 220 to 320 Hz, up ~30 Hz in 8 ms then down ~80 Hz over 60 ms, an octave slap on the contact, no noise. */
    private fun pluck(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val f0 = expMap(m.getValue("TUNE"), 200.0, 340.0)
        val up = 30.0 * (1.0 + 0.2 * tk.a) * (0.7 + 0.3 * vel)
        val down = 80.0 * (1.0 + 0.2 * tk.b)
        val t60 = expMap(m.getValue("DECAY"), 0.08, 0.20) * (1.0 + 0.12 * tk.c)
        val n = frames(t60 * 1.3)
        val hz = DoubleArray(n) {
            val t = tAt(it)
            val rise = 1.0 - exp(-t / 0.0027)
            val fall = if (t > 0.008) 1.0 - exp(-(t - 0.008) / 0.02) else 0.0
            f0 + up * rise - down * fall
        }
        val ph = phaseOf(hz)
        return DoubleArray(n) { i ->
            val t = tAt(i)
            val p = ph[i] + 2.0 * PI * 0.1 * tk.phase
            val body = sin(p) * min(1.0, t / 0.001) * expEnv(t, t60)
            val slap = sin(2 * p) * expEnv(t, 0.015) * 0.5 * vel
            tanh(2.2 * (body + slap) * (0.7 + 0.3 * vel)) / tanh(2.2)
        }
    }

    /** Suck-back crash: the hat cluster through two ring modulators plus noise, rising over 250 to 400 ms, cut at a zero crossing. */
    private fun inhale(m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
        val rise = expMap(m.getValue("DECAY"), 0.25, 0.40) * (1.0 + 0.1 * tk.b) * (1.3 - 0.3 * vel)
        val n = frames(rise)
        val scale = 1.7 * 2.0.pow(0.5 * (m.getValue("TUNE") - 0.5))
        val phases = DoubleArray(HAT_HZ.size) { (tk.noise.next().toDouble() + 1) / 2 }
        val nz = noise(tk, n)
        var y = DoubleArray(n) { i ->
            val t = tAt(i)
            var s = 0.0
            for (k in HAT_HZ.indices) s += if (sin(2.0 * PI * (HAT_HZ[k] * scale * t + phases[k])) >= 0) 1.0 else -1.0
            val ring = s * (0.6 * sin(2.0 * PI * 1370.0 * t) + 0.4 * sin(2.0 * PI * 2210.0 * t))
            0.6 * ring + 0.4 * nz[i]
        }
        y = butterLp(butterHp(y, 3000.0 * (1.5 - 0.5 * vel), 2, FS), 12000.0, 2, FS)
        for (i in 0 until n) y[i] *= exp(6.9078 * (tAt(i) - rise) / rise) * (0.5 + 0.5 * vel)
        // The gate: stop at the last zero crossing inside the final 5 ms, sample-accurate.
        val tail = (0.005 * FS).toInt()
        var cut = n
        for (i in n - 1 downTo max(1, n - tail)) if ((y[i] >= 0) != (y[i - 1] >= 0)) { cut = i; break }
        return normalized(y.copyOf(cut))
    }

    // ------------------------------------------------------------------ the chain (at the output rate)

    /** CRUNCH's move, velocity-aware: a zero-order hold down to 11 kHz and 16 to 9 bits, both deeper on a harder hit. */
    private fun grit(y: DoubleArray, amount: Double, vel: Double): DoubleArray {
        if (amount <= 0.0) return y
        val depth = amount * (0.55 + 0.45 * vel)
        val bits = lin(depth, 16.0, 9.0)
        val hold = expMap(depth, RATE.toDouble(), 11000.0)
        val q = 2.0.pow(bits - 1)
        val out = DoubleArray(y.size)
        var held = 0.0
        var lastK = -1L
        for (i in y.indices) {
            val k = floor(i * hold / RATE).toLong()
            if (k != lastK) { held = y[i]; lastK = k }
            out[i] = Math.rint(held * q) / q
        }
        return out
    }

    /**
     * A feedback comb [delayMs] long with a band-pass (800 Hz to 2.5 kHz) and a soft clip inside the loop, as a
     * parallel send: the metal box, not a tail. Feedback tops out at 0.74 and the clip keeps the loop bounded.
     */
    private fun locker(y: DoubleArray, amount: Double, delayMs: Double): DoubleArray {
        if (amount <= 0.0) return y
        val fb = lin(amount, 0.5, 0.74)
        val send = lin(amount, 0.0, 0.9)
        val d = (delayMs * 1e-3 * RATE).toInt()
        val bp = bandpass(1400.0, 1.0, RATE)
        val n = y.size + (0.20 * RATE).toInt()
        val buf = DoubleArray(d)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val x = if (i < y.size) y[i] else 0.0
            val v = x + fb * tanh(1.5 * bp.step(buf[i % d])) / 1.5
            buf[i % d] = v
            out[i] = x + send * (v - x)
        }
        return out
    }

    /**
     * The cocked wah: a band-pass of Q 3.5 to 6 parked near 1.1 kHz, boosted up to 18 dB and blended in; past
     * 0.3 the dry path also loses its lows and highs, as a parked wah does. Output peak-matched to the input.
     */
    private fun bark(y: DoubleArray, amount: Double): DoubleArray {
        if (amount <= 0.0) return y
        val q = lin(amount, 3.5, 6.0)
        val boost = 10.0.pow(lin(amount, 0.0, 18.0) / 20)
        val bp = bandpass(1100.0, q, RATE).run(y)
        var dry = y
        if (amount > 0.3) {
            val focus = (amount - 0.3) / 0.7
            dry = first(first(y, 150.0 + 250.0 * focus, high = true, fs = RATE), 9000.0 - 5000.0 * focus, high = false, fs = RATE)
        }
        val out = DoubleArray(y.size) { dry[it] + boost * bp[it] }
        val g = peakOf(y) / (peakOf(out) + 1e-9)
        for (i in out.indices) out[i] *= g
        return out
    }

    /**
     * The bus clipper, at 4x: an asymmetric cubic clip at up to 22 dB of drive, the clipped path low-passed at 600 Hz
     * (so the growl is low-mid, not fizz) with a little of its top kept, blended with the dry path, then a hard ceiling.
     */
    private fun slam(y: DoubleArray, amount: Double): DoubleArray {
        if (amount <= 0.0) return y
        val x = up4(y)
        val drive = expMap(amount, 1.0, 12.0)
        val bias = 0.2 * amount
        val biasOut = cubicClip(drive * bias)
        val wet = DoubleArray(x.size) { (cubicClip(drive * (x[it] + bias)) - biasOut) / sqrt(drive) }
        val wetLp = butterLp(wet, 600.0, 2, FS)
        val mix = lin(amount, 0.0, 0.85)
        val gain = 1.0 + 2.0 * amount
        val out = DoubleArray(x.size) { i ->
            val hp = wet[i] - wetLp[i]
            (((1.0 - mix) * x[i] + mix * (wetLp[i] + 0.35 * hp)) * gain).coerceIn(-0.98, 0.98)
        }
        return toOutputRate(out)
    }

    /** End the pad where its 5 ms envelope last falls under [TAIL_GATE_DB] of its peak, with a 2 ms fade. */
    private fun tailGate(y: DoubleArray): DoubleArray {
        val w = (0.005 * RATE).toInt()
        val n = y.size
        if (n <= w) return y
        // A centred moving RMS, as the prototype's `convolve(..., mode="same")`.
        val sq = DoubleArray(n) { y[it] * y[it] }
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + sq[i]
        val half = w / 2
        var peak = 0.0
        val env = DoubleArray(n) { i ->
            val lo = max(0, i - half); val hi = min(n, i - half + w)
            sqrt((prefix[hi] - prefix[lo]) / w).also { peak = max(peak, it) }
        }
        val thr = peak * 10.0.pow(TAIL_GATE_DB / 20.0)
        var last = -1
        for (i in n - 1 downTo 0) if (env[i] > thr) { last = i; break }
        if (last < 0) return y
        val f = (0.002 * RATE).toInt()
        val end = min(n, last + f)
        val out = y.copyOf(end)
        if (f < end) for (i in 0 until f) out[end - f + i] *= 1.0 - i.toDouble() / (f - 1).coerceAtLeast(1)
        return out
    }

    /** Subtract the mean, then a one-pole high-pass at 20 Hz: the house order. */
    private fun dcBlock(x: FloatArray): FloatArray {
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size.coerceAtLeast(1)
        val rc = 1.0 / (2 * PI * 20.0)
        val alpha = rc / (rc + 1.0 / RATE)
        val out = FloatArray(x.size)
        var px = 0.0; var py = 0.0
        for (i in x.indices) {
            val v = x[i] - mean
            py = alpha * (py + v - px)
            px = v
            out[i] = py.toFloat()
        }
        return out
    }
}
