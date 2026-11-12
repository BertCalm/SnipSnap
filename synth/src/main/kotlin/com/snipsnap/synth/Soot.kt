package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * SOOT's drums (docs/SOOT.md). The round-0 layout the owner approved on 2026-11-12 puts these eight
 * voices on its pads; the alternates (the heavy kick, the dry crack, the narrow hats) are presets of
 * them ([SootPresets]). The metal files as PERC, as BREAKER's percussion does.
 */
enum class SootVoice(val drumClass: DrumClass) {
    KICK(DrumClass.KICK), SNARE(DrumClass.SNARE),
    HAT_CLOSED(DrumClass.HAT_CLOSED), HAT_OPEN(DrumClass.HAT_OPEN),
    ROD(DrumClass.PERC), PLATE(DrumClass.PERC), CUP(DrumClass.PERC), COIL(DrumClass.PERC),
}

/**
 * SOOT: a grimy street boom bap kit built only from physical models, after the owner's sonic profile.
 *
 * The characteristic gesture is **dry skin knock that sags → papery crack → tight coil clatter → the
 * kit breathing through a dictaphone**.
 *
 * A hit is made in two stages:
 * 1. **The drum**, dry:
 *    - KICK: ten circular-membrane modes whose pitch sags under the hit, a wood frame knock, a beater
 *      click, and a tuned sub an octave under the knock.
 *    - SNARE: a stick snap and two paper bands, a taut head, wires, a short steel bench, then the
 *      coil (BREAKER's spring model with a tighter tank, [COIL_TANK]), then a cassette edge.
 *    - The hats: 112 struck-metal partials and no noise, frozen in [SootTables].
 *    - ROD, PLATE, CUP: modal metal. COIL: the spring kicked on its own.
 * 2. **The print**: the glue a pad keeps. Its own send into the coil (a kick's send is opened down to
 *    90 Hz, so the kick drives the spring), then the dictaphone ([dictaphone]) at GRIME.
 *
 * The profile's "kick excites the spring and the kit breathes" is a bus: in a sampler each pad plays
 * alone, so a pad can only carry its own breath. The four-bar phrase that carries the shared one is a
 * later round.
 *
 * Literal: the membrane and plate mode ratios, the bar modes, the spring's dispersion (Välimäki,
 * Parker and Abel, JAES 58(7/8), 2010, as in [BreakerChain]). Analogy: the sag's shape, the cassette,
 * the dictaphone. Every constant is a shape the owner heard at round 0 (the Python harness), not a
 * measurement of a drum, a tape or a dictaphone. No samples.
 *
 * No HOLD: every hit is a one-shot under the classifier's 1.5 s loop threshold.
 */
object Soot {
    /** Renderer models. A save keeps the model it was made with. 1: round 1, the round-0 picks in Kotlin. */
    const val MODEL_VERSION = 1

    /** The longest hit; every hit is capped here with a 4 ms fade, under the 1.5 s loop threshold. */
    const val CAP_SECONDS = 1.45f

    fun secondsFor(voice: SootVoice): Float = when (voice) {
        SootVoice.KICK -> 1.0f
        SootVoice.SNARE -> CAP_SECONDS
        SootVoice.HAT_CLOSED -> .35f
        SootVoice.HAT_OPEN, SootVoice.ROD, SootVoice.PLATE -> 1.2f
        SootVoice.CUP -> .6f
        SootVoice.COIL -> 1.4f
    }

    // ------------------------------------------------------------------ controls

    /**
     * Each voice's controls, defaults from the owner's round-0 picks (2026-11-12):
     * - the kick at SAG .35 with the longer sub (t60 0.6 s, SUB .5);
     * - the hot snare (EDGE 1, the 12-bit cassette);
     * - the wide hats (DETUNE .625, 25 cents);
     * - the heavy dictaphone (GRIME .9), picked on the coupled groove.
     */
    fun macrosFor(voice: SootVoice): List<MacroSpec> = when (voice) {
        SootVoice.KICK -> listOf(MacroSpec("TUNE", .5f), MacroSpec("SAG", .35f), MacroSpec("SUB", .5f), MacroSpec("COIL", .45f), MacroSpec("GRIME", .9f))
        SootVoice.SNARE -> listOf(MacroSpec("TUNE", .5f), MacroSpec("COIL", .55f), MacroSpec("EDGE", 1f), MacroSpec("BENCH", .35f), MacroSpec("GRIME", .9f))
        SootVoice.HAT_CLOSED, SootVoice.HAT_OPEN -> listOf(MacroSpec("TUNE", .5f), MacroSpec("DETUNE", .625f), MacroSpec("DECAY", .5f), MacroSpec("GRIME", .9f))
        SootVoice.ROD -> listOf(MacroSpec("TUNE", .5f), MacroSpec("DECAY", .5f), MacroSpec("COIL", .3f), MacroSpec("GRIME", .9f))
        SootVoice.PLATE -> listOf(MacroSpec("TUNE", .5f), MacroSpec("DECAY", .5f), MacroSpec("COIL", .25f), MacroSpec("GRIME", .9f))
        SootVoice.CUP -> listOf(MacroSpec("TUNE", .5f), MacroSpec("DECAY", .5f), MacroSpec("COIL", .2f), MacroSpec("GRIME", .9f))
        SootVoice.COIL -> listOf(MacroSpec("TUNE", .5f), MacroSpec("COIL", .85f), MacroSpec("GRIME", .9f))
    }

    fun defaults(voice: SootVoice) = macrosFor(voice).associate { it.name to it.default }

    fun settled(voice: SootVoice, macros: Map<String, Float>) = defaults(voice).mapValues { (k, d) ->
        macros[k]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d
    }

    fun scramble(voice: SootVoice, random: Random, temperature: Float = .35f, near: Patch? = null) =
        Dsp.scrambleNear(settled(voice, near?.macros ?: emptyMap()), temperature, random)

    fun drumClassFor(voice: SootVoice): DrumClass = voice.drumClass

    // ------------------------------------------------------------------ small DSP, in double precision

    private val rate = Dsp.RATE
    private const val T60_NEPERS = 6.907755278982137

    private fun peak(x: DoubleArray): Double { var p = 0.0; for (s in x) p = maxOf(p, abs(s)); return p }

    private fun norm(x: DoubleArray, to: Double = 1.0): DoubleArray {
        val p = peak(x)
        if (p > 0) for (i in x.indices) x[i] *= to / p
        return x
    }

    /** A biquad, RBJ's cookbook, double precision (BREAKER's Section, with the peaking shape SOOT needs). */
    private class Bq(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            fun lowpass(f0: Double, q: Double): Bq {
                val w = 2 * PI * f0 / Dsp.RATE; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
                return Bq((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }

            fun highpass(f0: Double, q: Double): Bq {
                val w = 2 * PI * f0 / Dsp.RATE; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
                return Bq((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }

            fun peaking(f0: Double, db: Double, q: Double): Bq {
                val a = 10.0.pow(db / 40); val w = 2 * PI * f0 / Dsp.RATE; val al = sin(w) / (2 * q); val c = cos(w)
                val a0 = 1 + al / a
                return Bq((1 + al * a) / a0, -2 * c / a0, (1 - al * a) / a0, -2 * c / a0, (1 - al / a) / a0)
            }
        }
    }

    private const val Q2 = 0.7071067811865476
    private val Q4 = doubleArrayOf(0.5411961001461970, 1.3065629648763766)

    private fun run(x: DoubleArray, vararg stages: Bq): DoubleArray {
        val y = x.copyOf()
        for (s in stages) for (i in y.indices) y[i] = s.process(y[i])
        return y
    }

    private fun lp2(x: DoubleArray, hz: Double) = run(x, Bq.lowpass(hz, Q2))
    private fun hp2(x: DoubleArray, hz: Double) = run(x, Bq.highpass(hz, Q2))
    private fun lp4(x: DoubleArray, hz: Double) = run(x, Bq.lowpass(hz, Q4[0]), Bq.lowpass(hz, Q4[1]))
    private fun hp4(x: DoubleArray, hz: Double) = run(x, Bq.highpass(hz, Q4[0]), Bq.highpass(hz, Q4[1]))
    private fun bp(x: DoubleArray, lo: Double, hi: Double) = run(x, Bq.highpass(lo, Q2), Bq.lowpass(hi, Q2))

    /** A first-order low-pass by the bilinear transform. */
    private fun lp1(x: DoubleArray, hz: Double): DoubleArray {
        val k = kotlin.math.tan(PI * hz / rate)
        val b = k / (1 + k); val a = (k - 1) / (k + 1)
        var x1 = 0.0; var y1 = 0.0
        return DoubleArray(x.size) { val y = b * x[it] + b * x1 - a * y1; x1 = x[it]; y1 = y; y }
    }

    /** A first-order high-pass by the bilinear transform. */
    private fun hp1(x: DoubleArray, hz: Double): DoubleArray {
        val k = kotlin.math.tan(PI * hz / rate)
        val b = 1 / (1 + k); val a = (k - 1) / (k + 1)
        var x1 = 0.0; var y1 = 0.0
        return DoubleArray(x.size) { val y = b * x[it] - b * x1 - a * y1; x1 = x[it]; y1 = y; y }
    }

    /** A memoryless curve at 4x, band-limited back down: Valve.upsample → curve → Tide.bandLimit → Dsp.decimate. */
    private fun oversampled(x: DoubleArray, curve: (Double) -> Double): DoubleArray {
        val up = Valve.upsample(FloatArray(x.size) { x[it].toFloat() }, rate)
        for (i in up.indices) up[i] = curve(up[i].toDouble()).toFloat()
        Tide.bandLimit(up, rate * Dsp.OVERSAMPLE)
        val down = Dsp.decimate(up, rate)
        return DoubleArray(x.size) { down.getOrElse(it) { 0f }.toDouble() }
    }

    /** A strike's contact law (shape): a softer strike is a longer contact, so a lower corner. */
    private fun contact(f: Double, v: Double, lo: Double, hi: Double, power: Double = 1.5): Double {
        val fc = lo + (hi - lo) * v.pow(power)
        return 1 / (1 + (f / fc) * (f / fc))
    }

    /** A raised-cosine onset over [ms]. */
    private fun attack(x: DoubleArray, ms: Double) {
        val k = maxOf(1, (ms / 1000 * rate).toInt())
        for (i in 0 until minOf(k, x.size)) x[i] *= 0.5 - 0.5 * cos(PI * i / (k - 1).coerceAtLeast(1))
    }

    /** Standard-normal noise from a seeded generator (Box-Muller). */
    private fun gauss(r: Random): Double {
        val u1 = 1.0 - r.nextDouble(); val u2 = r.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }

    private fun uniform(r: Random, lo: Double, hi: Double) = lo + (hi - lo) * r.nextDouble()

    /**
     * Exponentially decaying sines. [bend], when given, multiplies every frequency at each frame
     * (the kick's sag). Partials at or over 16 kHz are skipped, so nothing comes near the output's
     * Nyquist: no partial can alias.
     */
    private fun partials(f: DoubleArray, g: DoubleArray, t60: DoubleArray, frames: Int, phase0: DoubleArray? = null, bend: DoubleArray? = null): DoubleArray {
        val out = DoubleArray(frames)
        for (p in f.indices) {
            if (f[p] >= 16_000.0 || g[p] == 0.0) continue
            val decay = exp(-T60_NEPERS / (t60[p] * rate))
            var amp = g[p]
            var ph = phase0?.get(p) ?: 0.0
            val w = 2 * PI * f[p] / rate
            for (i in 0 until frames) {
                ph += if (bend == null) w else w * bend[i]
                out[i] += amp * sin(ph)
                amp *= decay
                if (amp < 1e-7 * g[p]) break
            }
        }
        return out
    }

    // ------------------------------------------------------------------ the coil

    /**
     * The tight coil (shape, the owner's round-0 snare): BREAKER's tank model, shorter springs (19 and
     * 24 ms) so the echoes crowd into a clatter, a lower bend (fc 2.2 kHz) so it sounds rubbery, and a
     * short loop decay (0.35 + 0.55 x COIL s) so the space closes in. Measured at round 0: one pass
     * delays 200 Hz by 3.8 ms and 2 kHz by 49.6 ms, and the energy falls in every 100 ms block.
     */
    internal val COIL_TANK = BreakerChain.Tank(
        fc = 2200.0, k = 10, ap = .62, stages = 70,
        springs = listOf(Triple(.019, 0.0, 1.0), Triple(.024, .003, .8)),
        t60Base = .35, t60Span = .55, tone = 3000.0,
    )

    // ------------------------------------------------------------------ the drums

    private val MEMBRANE = doubleArrayOf(1.0, 1.594, 2.136, 2.296, 2.653, 2.918, 3.156, 3.501, 3.600, 3.652)
    private val MEMBRANE_GAIN = doubleArrayOf(1.0, .55, .42, .30, .25, .20, .16, .12, .10, .08)
    private val BAR = doubleArrayOf(1.0, 2.756, 5.404, 8.933)

    /** TUNE: six semitones either way on the skins, four on the metal (shape). */
    private fun pitch(tune: Float, semis: Double) = 2.0.pow((tune - .5) * 2 * semis / 12)

    /** DECAY: half to double each voice's ring, pinned at 1 in the middle (shape). */
    private fun ring(decay: Float) = 2.0.pow((decay - .5) * 2)

    private fun kick(m: Map<String, Float>, v: Double, r: Random): DoubleArray {
        val frames = (secondsFor(SootVoice.KICK) * rate).toInt()
        val knock = 87.31 * pitch(m.getValue("TUNE"), 6.0)
        // The sag: sharp by sag x v^1.5 on impact (tension grows with amplitude squared), then half
        // that flat as the damp head gives way. SAG 1 is one semitone; the pick, .35, is 35 cents.
        val s = m.getValue("SAG") * v.pow(1.5) * (1 + .1 * uniform(r, -1.0, 1.0))
        val bend = DoubleArray(frames) {
            val t = it.toDouble() / rate
            2.0.pow((s * exp(-t / .015) - .5 * s * (1 - exp(-t / .09))) / 12)
        }
        val f = DoubleArray(MEMBRANE.size) { knock * MEMBRANE[it] }
        val g = DoubleArray(f.size) { MEMBRANE_GAIN[it] * 10.0.pow(uniform(r, -1.5, 1.5) / 20) * contact(f[it], v, 120.0, 1400.0) }
        val t60 = DoubleArray(f.size) { .30 / MEMBRANE[it].pow(1.4) }
        val body = partials(f, g, t60, frames, bend = bend)
        attack(body, .6 + 1.2 * (1 - v))
        // A fixed scale, not a per-hit peak normalisation: a hard hit's partials line up into a taller
        // peak, so normalising each hit would take energy from exactly the hits that should be brightest.
        val scale = .5 * MEMBRANE_GAIN.sum()
        for (i in body.indices) body[i] /= scale
        val wood = norm(partials(doubleArrayOf(640.0, 1180.0, 1930.0), doubleArrayOf(.5, .35, .2), doubleArrayOf(.035, .025, .018), frames))
        val click = DoubleArray(frames)
        val ck = (.002 * rate).toInt()
        for (i in 0 until ck) click[i] = gauss(r) * exp(-i / (.0005 * rate))
        val clicked = norm(bp(click, 1000.0, 4000.0))
        for (i in body.indices) body[i] += .18 * v * wood[i] + .3 * v.pow(1.3) * clicked[i]
        // The sub: an octave under the knock, falling from +7 semitones in a tight exponential, its
        // length from SUB (t60 0.2 to 1.0 s; the pick, .5, is 0.6 s), driven lightly so it has a third
        // harmonic a phone can play.
        val subT60 = (.2 + .8 * m.getValue("SUB")) * (1 + .06 * uniform(r, -1.0, 1.0))
        val sub = DoubleArray(frames)
        var ph = 0.0
        val ak = (.002 * rate).toInt()
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            ph += 2 * PI * (knock / 2) * 2.0.pow(7 * exp(-t / .018) / 12) / rate
            val env = exp(-T60_NEPERS * t / subT60) * (if (i < ak) 0.5 - 0.5 * cos(PI * i / (ak - 1)) else 1.0)
            sub[i] = sin(ph) * env
        }
        val driven = oversampled(sub) { tanh(1.8 * it) / tanh(1.8) }
        val subGain = .8 * 10.0.pow(uniform(r, -.4, .4) / 20)
        for (i in body.indices) body[i] += subGain * driven[i]
        return body
    }

    private fun snare(m: Map<String, Float>, v: Double, r: Random, noise: Dsp.Noise): DoubleArray {
        val n = (.6 * rate).toInt()
        val head0 = 196.0 * pitch(m.getValue("TUNE"), 6.0)
        val hf = DoubleArray(7) { head0 * MEMBRANE[it] }
        val hg = DoubleArray(7) { MEMBRANE_GAIN[it] * 10.0.pow(uniform(r, -1.5, 1.5) / 20) * contact(hf[it], v, 500.0, 4500.0) }
        val head = norm(partials(hf, hg, DoubleArray(7) { .17 / MEMBRANE[it].pow(1.2) }, n))
        // The crack: a sub-millisecond broadband stick snap and two papery bands from one burst.
        val burst = DoubleArray(n) { noise.next().toDouble() }
        val snap = hp2(DoubleArray(n) { burst[it] * exp(-it / (.0007 * rate)) }, 1800.0 - 900.0 * (1 - v))
        var paper = bp(DoubleArray(n) { burst[it] * exp(-it / (.004 * rate)) }, 1500.0, 9000.0)
        paper = run(paper, Bq.peaking(2600.0, 9.0, 2.5), Bq.peaking(5200.0, 7.0, 2.5))
        val sn = norm(snap); val pa = norm(paper)
        val crack = norm(DoubleArray(n) { sn[it] + .7 * pa[it] })
        // The wires, the one noise in the kit (they are wires; the hats are not): BOOM_BAP's soft-wire law.
        val wTau = .075 * (.55 + .45 * sqrt(v))
        val wires = norm(bp(DoubleArray(n) { noise.next() * exp(-it / (wTau * rate)) }, 1800.0, 3000.0 + 6000.0 * v.pow(.8)))
        // The metal workbench: a short unpitched plate.
        val bf = DoubleArray(24) { 410.0 * SootTables.PLATE[it] }
        val bg = DoubleArray(24) { uniform(r, .4, 1.0) * contact(bf[it], v, 1500.0, 8000.0) }
        val bph = DoubleArray(24) { uniform(r, 0.0, 2 * PI) }
        val bench = norm(partials(bf, bg, DoubleArray(24) { .12 / sqrt(SootTables.PLATE[it]) }, n, bph))
        val b = m.getValue("BENCH").toDouble()
        val dry = DoubleArray(n) { .9 * head[it] + v.pow(1.2) * crack[it] + .55 * sqrt(v) * wires[it] + b * v * bench[it] }
        attack(dry, .15)
        val frames = (CAP_SECONDS * rate).toInt()
        val coiled = BreakerChain.tankMix(dry, m.getValue("COIL").toDouble(), frames, rate, COIL_TANK)
        return cassette(coiled, m.getValue("EDGE").toDouble())
    }

    /**
     * The cassette edge (shape): asymmetric saturation at 4x (tanh above zero, a softer algebraic
     * curve below), a first-order high loss, and past half a 12-bit grain. Peak-matched.
     */
    private fun cassette(x: DoubleArray, edge: Double): DoubleArray {
        if (edge <= 0) return x
        val p = peak(x)
        if (p <= 0) return x
        val drive = 1 + 3 * edge
        var y = oversampled(x) { val u = it / p * drive; if (u >= 0) tanh(u) else u / sqrt(1 + u * u) }
        y = lp1(y, 12_000.0 - 4_000.0 * edge)
        if (edge > .5) {
            norm(y)
            val q = 2.0.pow(11)
            for (i in y.indices) y[i] = Math.rint(y[i] * q) / q
        }
        return norm(y, p)
    }

    private fun hat(m: Map<String, Float>, v: Double, r: Random, noise: Dsp.Noise, open: Boolean): DoubleArray {
        val frames = (secondsFor(if (open) SootVoice.HAT_OPEN else SootVoice.HAT_CLOSED) * rate).toInt()
        val cents = 40.0 * m.getValue("DETUNE")
        val tune = pitch(m.getValue("TUNE"), 4.0)
        val n = SootTables.HAT_F.size
        val f = DoubleArray(n) { SootTables.HAT_F[it] * 2.0.pow(SootTables.HAT_U[it] * cents / 1200) * tune }
        val g = DoubleArray(n) { SootTables.HAT_G[it] * contact(f[it], v, 2500.0, 11000.0, 1.2) * 10.0.pow(uniform(r, -1.0, 1.0) / 20) }
        val base = (if (open) .55 else .075) * ring(m.getValue("DECAY"))
        val t60 = DoubleArray(n) { base * (4000.0 / f[it]).pow(.45) }
        val ph = DoubleArray(n) { uniform(r, 0.0, 2 * PI) }
        val x = norm(partials(f, g, t60, frames, ph))
        attack(x, .25)
        val tick = DoubleArray(frames)
        for (i in 0 until 8) tick[i] = noise.next().toDouble()
        val t = norm(hp2(tick, 6000.0))
        for (i in x.indices) x[i] += .25 * v * t[i]
        return x
    }

    private fun rod(m: Map<String, Float>, v: Double, r: Random): DoubleArray {
        val f0 = 1180.0 * pitch(m.getValue("TUNE"), 4.0)
        val f = doubleArrayOf(f0 * BAR[0], f0 * BAR[1], f0 * BAR[2], f0 * BAR[3], f0 * 2.0.pow(11.0 / 1200))
        val gs = doubleArrayOf(1.0, .6, .35, .2, .7)
        val g = DoubleArray(5) { gs[it] * contact(f[it], v, 2500.0, 12000.0) }
        val k = ring(m.getValue("DECAY"))
        val t60 = doubleArrayOf(.9, .45, .25, .14, .8).map { it * k }.toDoubleArray()
        val x = partials(f, g, t60, (secondsFor(SootVoice.ROD) * rate).toInt(), DoubleArray(5) { uniform(r, 0.0, 2 * PI) })
        attack(x, .2)
        return x
    }

    private fun plate(m: Map<String, Float>, v: Double, r: Random): DoubleArray {
        val n = SootTables.PLATE.size
        val f0 = 215.0 * pitch(m.getValue("TUNE"), 4.0)
        val f = DoubleArray(n) { f0 * SootTables.PLATE[it] }
        val g = DoubleArray(n) { SootTables.PLATE_G[it] * contact(f[it], v, 1200.0, 7000.0) * 10.0.pow(uniform(r, -1.5, 1.5) / 20) }
        val k = ring(m.getValue("DECAY"))
        val t60 = DoubleArray(n) { .8 / SootTables.PLATE[it].pow(.6) * k }
        val x = partials(f, g, t60, (secondsFor(SootVoice.PLATE) * rate).toInt(), DoubleArray(n) { uniform(r, 0.0, 2 * PI) })
        attack(x, .3)
        return x
    }

    private fun cup(m: Map<String, Float>, v: Double, r: Random): DoubleArray {
        val f0 = 1650.0 * pitch(m.getValue("TUNE"), 4.0)
        val f = doubleArrayOf(1.0, 1.47, 2.09, 2.56, 3.39).map { it * f0 }.toDoubleArray()
        val gs = doubleArrayOf(1.0, .7, .5, .35, .25)
        val g = DoubleArray(5) { gs[it] * contact(f[it], v, 2500.0, 10000.0) }
        val k = ring(m.getValue("DECAY"))
        val t60 = doubleArrayOf(.28, .2, .14, .1, .07).map { it * k }.toDoubleArray()
        val x = partials(f, g, t60, (secondsFor(SootVoice.CUP) * rate).toInt(), DoubleArray(5) { uniform(r, 0.0, 2 * PI) })
        attack(x, .15)
        return x
    }

    /**
     * The coil kicked on its own: a soft thump into the tank with the send opened to 120 Hz. The tank
     * normalises its send, so the velocity sets the dwell instead (a softer kick drives it less and it
     * rings shorter); round 0 had no velocity here. A take kicks it a little differently: the thump's
     * pitch within 3% and the dwell within 5% (round 0's takes differed only in 2 ms of noise, and
     * measured as near copies).
     */
    private fun coil(m: Map<String, Float>, v: Double, r: Random, noise: Dsp.Noise): DoubleArray {
        val frames = (secondsFor(SootVoice.COIL) * rate).toInt()
        val hz = 140.0 * pitch(m.getValue("TUNE"), 6.0) * (1 + .03 * uniform(r, -1.0, 1.0))
        val tn = (.05 * rate).toInt()
        val thump = DoubleArray(tn) { val t = it.toDouble() / rate; sin(2 * PI * hz * t) * exp(-t / .008) + .2 * noise.next() * exp(-t / .002) }
        val drip = (m.getValue("COIL") * (.6 + .4 * v) * (1 + .05 * uniform(r, -1.0, 1.0))).coerceIn(0.0, 1.0)
        val ret = norm(lp2(BreakerChain.spring(thump, drip, frames, rate, COIL_TANK, sendHp = 120.0), 3200.0))
        val th = norm(thump.copyOf())
        return DoubleArray(frames) { ret[it] + if (it < tn) .15 * th[it] else 0.0 }
    }

    // ------------------------------------------------------------------ the print and the dictaphone

    /** Each voice's own send into the coil when its print is made. Where COIL is the voice's send, it is the macro. */
    private fun sendFor(voice: SootVoice, m: Map<String, Float>): Double = when (voice) {
        SootVoice.KICK, SootVoice.ROD, SootVoice.PLATE, SootVoice.CUP -> m.getValue("COIL").toDouble()
        SootVoice.SNARE -> .25
        SootVoice.HAT_CLOSED -> .05
        SootVoice.HAT_OPEN -> .08
        SootVoice.COIL -> 0.0
    }

    /** A feed-forward compressor on a peak-relative detector, smoothed in the log domain (as BREAKER's crush). */
    private fun compress(x: DoubleArray, key: DoubleArray, thrDb: Double, ratio: Double, att: Double, rel: Double): DoubleArray {
        val p = peak(key).takeIf { it > 0 } ?: 1.0
        val aa = exp(-1 / (att * rate)); val ar = exp(-1 / (rel * rate))
        var s = 0.0
        return DoubleArray(x.size) {
            val over = maxOf(20 * log10(abs(key[it]) / p + 1e-9) - thrDb, 0.0)
            val gc = -over * (1 - 1 / ratio)
            s = if (gc < s) aa * s + (1 - aa) * gc else ar * s + (1 - ar) * gc
            x[it] * 10.0.pow(s / 20)
        }
    }

    /**
     * The dictaphone (shape) at [amount]: the lows under 100 Hz go round the grit (the owner kept the
     * sub clear at round 0) and get a gentle 2:1. Above them: an AGC that pumps (3 ms attack, 180 ms
     * release, -16 to -24 dB under the peak, 6:1 to 12:1), a small speaker's 2.7 kHz bump and
     * low-pass (7.2 down to 4 kHz), and a ROM reader: a zero-order hold (22 to 11 kHz, aliasing on
     * purpose: the metallic footprint) and bit loss (12 to 8 bits), blended in at 0.6 x [amount].
     */
    internal fun dictaphone(x: DoubleArray, amount: Double): DoubleArray {
        val low = compress(lp4(x, 100.0), x, -10.0, 2.0, .003, .18)
        val pumped = compress(hp4(x, 100.0), x, -16 - 8 * amount, 6 + 6 * amount, .003, .18)
        val y = lp2(run(pumped, Bq.peaking(2700.0, 3 + 3 * amount, 1.2)), 7200.0 - 3200.0 * amount)
        val hold = (2 + 2 * amount).roundToInt()
        val q = 2.0.pow(11 - 4 * amount)
        val held = DoubleArray(y.size) { y[(it / hold) * hold] }
        val pr = peak(held).takeIf { it > 0 } ?: 1.0
        for (i in held.indices) held[i] = Math.rint(held[i] / pr * q) / q * pr
        val rom = lp1(held, 9000.0)
        val w = .6 * amount
        return DoubleArray(x.size) { (1 - w) * y[it] + w * rom[it] + low[it] }
    }

    /** The house output stage's shape for a dry drum: centred, 20 Hz high-passed, peak 0.89. */
    private fun centre(x: DoubleArray): DoubleArray {
        val mean = x.average()
        for (i in x.indices) x[i] -= mean
        return norm(hp1(x, 20.0), .89)
    }

    /**
     * One hit. [velocity] is the strike: every drum takes it through its own contact law (and the
     * kick through its sag), so a soft hit is a different strike, not a quieter one. [take]
     * re-strikes it: fresh noise, a moved strike position, fresh metal phases.
     */
    fun render(voice: SootVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0, model: Int = MODEL_VERSION): Snip {
        require(model in 1..MODEL_VERSION) { "unsupported soot model $model" }
        val m = settled(voice, macros)
        val v = (velocity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f).toDouble().coerceAtLeast(.02)
        val r = Random(Dsp.seedFor("soot", voice.name, take))
        val noise = Dsp.Noise(Dsp.seedFor("soot-noise", voice.name, take))
        val dry = when (voice) {
            SootVoice.KICK -> kick(m, v, r)
            SootVoice.SNARE -> snare(m, v, r, noise)
            SootVoice.HAT_CLOSED -> hat(m, v, r, noise, open = false)
            SootVoice.HAT_OPEN -> hat(m, v, r, noise, open = true)
            SootVoice.ROD -> rod(m, v, r)
            SootVoice.PLATE -> plate(m, v, r)
            SootVoice.CUP -> cup(m, v, r)
            SootVoice.COIL -> coil(m, v, r, noise)
        }
        var x = centre(dry)
        // The print: the pad's own send into the coil, then the dictaphone. GRIME 0 bypasses the
        // dictaphone entirely, so the dry drum is one control away.
        val send = sendFor(voice, m)
        if (send > 0) {
            val ret = lp2(BreakerChain.spring(x, .5, x.size, rate, COIL_TANK, sendHp = if (voice == SootVoice.KICK) 90.0 else 250.0), COIL_TANK.tone)
            val pr = peak(ret)
            if (pr > 0) { val k = send * peak(x) / pr; for (i in x.indices) x[i] += k * ret[i] }
        }
        val grime = m.getValue("GRIME").toDouble()
        if (grime > 0) x = dictaphone(x, grime)
        val frames = minOf(x.size, (CAP_SECONDS * rate).toInt())
        val out = FloatArray(frames) { x[it].toFloat() }
        val mean = out.average().toFloat()
        val dc = Dsp.FixedOnePole(20f, rate)
        for (i in out.indices) { val s = out[i] - mean; out[i] = s - dc.lp(s) }
        Dsp.levelTo(out, rate, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, 1, rate)
    }
}
