package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh
import kotlin.random.Random

/** KINDRED's fifteen voices: one analog-style circuit set fifteen ways (docs/KINDRED.md). */
enum class KindredVoice { KICK, ZAP, SNARE, FMTOM, METAL, HAT, CLAP, RIM, COWBELL, SUB, BELL, SWEEP, LASER, PING, SHAKER }

/** What a pad plays as in a kit. Model 2's voicing reads it (a kick is voiced as a kick whichever voice made it). */
enum class KindredRole(val drumClass: DrumClass) {
    KICK(DrumClass.KICK), SNARE(DrumClass.SNARE), CLAP(DrumClass.CLAP), HAT_CLOSED(DrumClass.HAT_CLOSED),
    HAT_OPEN(DrumClass.HAT_OPEN), TOM(DrumClass.TOM), PERC(DrumClass.PERC),
}

/**
 * KINDRED: a one-voice, eight-step analog-style percussion synth.
 *
 * Round K1: the Kotlin port of the Python prototype the owner accepted by ear on 2026-11-15 at round 2
 * ("It's better ... Let's proceed with round 2 sounds"; `prototype/kindred/`, docs/KINDRED.md). Every constant
 * here is the prototype's. Where the port cannot be the same arithmetic it says so: the house resamplers in place
 * of scipy's, [Dsp.Noise] in place of numpy's generator (every noise voice is another realisation of the same
 * sound), [Random] in place of numpy for the takes, and [Dsp.levelTo] to loudness in place of the harness's RMS.
 *
 * The characteristic gesture: a strike, the pitch dives from a zap into a body, the ladder closes over the
 * harmonics, and the next step re-strikes the same voice at its own height and force. One continuous voice at 4x:
 *
 *     triggers ─► pitch EG ─┬─ AMT1 ─► VCO1 tri/square ─┬──────────────┐
 *                           └─ AMT2 ─► VCO2 tri/square ◄─┘ FM 1→2, sync │
 *                noise ─────────────────────────────────────────────► MIX ─► 4-pole ladder, LP or HP ─► VCA
 *                filter EG × velocity, noise → cutoff ──────────────────────────┘    VCA EG × velocity ──┘
 *
 * - The two VCOs share one decay envelope on pitch, each with its own bipolar amount: a negative amount makes the
 *   pitch rise into the note (the lasers, the drop).
 * - The ladder is four tanh stages with feedback, so the mixer drives it the way the reference's mixer does.
 * - A retrigger restarts the pitch and filter envelopes at their peaks and re-attacks the VCA from where it is: a
 *   new step cuts the last one's tail, which is why a pattern sounds like one machine.
 * - Velocity sets the VCA, the filter envelope's depth, part of the pitch dive and how hard the mixer drives the
 *   ladder, so a soft hit is darker and less zappy, not only quieter (the reference manual: "loudness and envelope
 *   strength").
 * - The oscillators run free between strikes, so every take starts at a new phase: the instrument's own round
 *   robin. A take also moves the noise, a few cents of drift and a few percent of the decays.
 *
 * Model 2 is the accepted sound. Round 1 was heard as "a small radio speaker"; measured against the references it
 * put 5.8 dB too much energy under 60 Hz and 5.5 dB too little between 400 Hz and 2.5 kHz. [voiced] is the fix.
 *
 * Macros, all 0..1 with 0.5 the voice as written: TUNE (an octave either way), DIVE (pitch-dive depth and time),
 * CLANG (FM 1→2, hard sync from 0.8), NOISE (noise level and noise → cutoff), TONE (cutoff and filter envelope),
 * LENGTH (the VCA and filter decays). Trademark rule: the reference is named only in docs/KINDRED.md.
 */
object Kindred {

    const val RATE = Dsp.RATE
    private const val FS = Dsp.RATE * Dsp.OVERSAMPLE
    /** The accepted model (the prototype's numbering; model 1 was never on a shelf and is not ported). */
    const val MODEL_VERSION = 2
    /** Takes a patch may name: 0 is the reference strike, the rest are fresh strikes. */
    const val MAX_TAKE = 15
    /** The reference's two VCA attack settings, about 1 ms and 100 ms. */
    internal const val FAST = 0.0008
    internal const val SLOW = 0.1
    /** One-shots end before the classifier's 1.5 s LOOP line. */
    private const val MAX_SECONDS = 1.45

    val MACROS = listOf("TUNE", "DIVE", "CLANG", "NOISE", "TONE", "LENGTH")

    /**
     * The six macros, less those with nothing to move: DIVE on a voice with no pitch envelope (the hat, clap,
     * cowbell, sweep, ping and shaker), CLANG on one whose oscillators are silent (the clap, sweep and shaker).
     * A knob that does nothing would be a lie on the panel. None of the accepted recipes sets either there.
     */
    fun macrosFor(voice: KindredVoice): List<MacroSpec> {
        val s = shapeOf(voice)
        val dives = s.pamt1 != 0.0 || s.pamt2 != 0.0
        val clangs = s.lv1 != 0.0 || s.lv2 != 0.0
        return MACROS.filter { (it != "DIVE" || dives) && (it != "CLANG" || clangs) }.map { MacroSpec(it, 0.5f, neutral = 0.5f) }
    }

    fun defaults(voice: KindredVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** [macros] over the defaults: unknown keys dropped, a non-finite value falls back, everything clamped 0..1. */
    fun settled(voice: KindredVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    /** The role a plain voice plays; a recipe names its own. */
    fun roleFor(voice: KindredVoice): KindredRole = when (voice) {
        KindredVoice.KICK -> KindredRole.KICK
        KindredVoice.SNARE -> KindredRole.SNARE
        KindredVoice.CLAP -> KindredRole.CLAP
        KindredVoice.HAT -> KindredRole.HAT_CLOSED
        KindredVoice.SHAKER -> KindredRole.HAT_OPEN
        KindredVoice.FMTOM -> KindredRole.TOM
        else -> KindredRole.PERC
    }

    fun drumClassFor(voice: KindredVoice, recipe: String? = null): DrumClass =
        (recipe?.let { KindredRecipes.byId(it).role } ?: roleFor(voice)).drumClass

    fun scramble(voice: KindredVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /**
     * Everything the circuit is set to, the prototype's `dict` field for field. Times are seconds, amounts are
     * octaves, levels are linear; [w1], [w2], [hp] and [sync] are switches (0 or 1). [burst] is a pad's strikes:
     * (seconds after the first, velocity scale), so a clap is three quick re-strikes.
     */
    data class Shape(
        val f1: Double, val f2: Double, val w1: Int, val w2: Int,
        val pamt1: Double, val pamt2: Double, val ptau: Double, val fm: Double, val sync: Int,
        val lv1: Double, val lv2: Double, val lnoise: Double,
        val hp: Int, val cutoff: Double, val res: Double, val famt: Double, val ftau: Double, val nmod: Double,
        val atk: Double, val atau: Double, val drive: Double, val seconds: Double,
        val velp: Double = 0.35, val veldrive: Double = 0.3,
        val burst: List<Pair<Double, Double>> = listOf(0.0 to 1.0),
    )

    /** The fifteen voices as the prototype writes them (`VOICES` and the kit module's `BASE`). Every number is "shape". */
    fun shapeOf(voice: KindredVoice): Shape = when (voice) {
        // a dive from ~400 Hz to ~70 Hz in ~80 ms, long low tail (the owner's kick reference)
        KindredVoice.KICK -> Shape(46.0, 92.0, 0, 0, 3.2, 3.2, .028, 0.0, 0, 1.0, .18, 0.0, 0, 420.0, .15, 3.2, .04, 0.0, FAST, .16, 1.6, .9, velp = .7)
        // the industrial pew: harmonics falling from over 10 kHz to about 1 kHz in ~150 ms
        KindredVoice.ZAP -> Shape(70.0, 105.0, 1, 1, 5.5, 4.5, .045, .35, 0, .8, .7, .05, 0, 500.0, .45, 4.5, .07, 0.0, FAST, .13, 2.2, .9)
        // a short body under filtered noise
        KindredVoice.SNARE -> Shape(185.0, 330.0, 0, 0, 1.0, .8, .018, 0.0, 0, .55, .3, 1.0, 0, 1300.0, .2, 3.4, .04, .6, FAST, .07, 1.5, .6, velp = .6)
        // VCO1 frequency-modulates VCO2: a talking tom
        KindredVoice.FMTOM -> Shape(95.0, 140.0, 0, 0, 1.4, 1.8, .06, 1.6, 0, 0.0, 1.0, 0.0, 0, 700.0, .35, 3.0, .09, 0.0, FAST, .14, 1.8, .8)
        // bright synced pings, short
        KindredVoice.METAL -> Shape(310.0, 1330.0, 1, 1, .6, 2.6, .03, .25, 1, .25, .9, 0.0, 1, 500.0, .55, 2.6, .05, 0.0, FAST, .06, 1.6, .5, velp = .6)
        // noise and two detuned squares through the resonant ladder, very short
        KindredVoice.HAT -> Shape(540.0, 810.0, 1, 1, 0.0, 0.0, .02, 1.0, 0, .12, .12, 1.0, 0, 2600.0, .55, 2.4, .03, .4, FAST, .03, 1.2, .35)
        KindredVoice.CLAP -> Shape(1000.0, 1500.0, 1, 1, 0.0, 0.0, .02, 0.0, 0, 0.0, 0.0, 1.0, 0, 800.0, .55, 2.6, .03, .3, FAST, .09, 1.6, .5,
            burst = listOf(0.0 to 1.0, .010 to .8, .021 to .9))
        KindredVoice.RIM -> Shape(1700.0, 2550.0, 1, 1, .4, .4, .005, 0.0, 0, .6, .4, .2, 0, 2200.0, .4, 2.4, .012, 0.0, FAST, .018, 1.5, .25, velp = .5)
        KindredVoice.COWBELL -> Shape(540.0, 800.0, 1, 1, 0.0, 0.0, .02, 0.0, 0, .7, .7, 0.0, 0, 850.0, .35, 2.8, .05, 0.0, FAST, .12, 1.4, .7)
        KindredVoice.SUB -> Shape(41.0, 82.0, 0, 0, .7, .7, .012, 0.0, 0, 1.0, 0.0, 0.0, 0, 180.0, .1, 2.4, .06, 0.0, FAST, .42, 3.2, 1.4, velp = .5, veldrive = .8)
        KindredVoice.BELL -> Shape(220.0, 607.0, 0, 0, .05, .05, .02, 1.1, 0, .25, .9, 0.0, 0, 900.0, .2, 2.0, .35, 0.0, FAST, .5, 1.1, 1.45)
        // the filter envelope runs backwards: the cutoff opens as it decays (steam, breath)
        KindredVoice.SWEEP -> Shape(200.0, 300.0, 1, 1, 0.0, 0.0, .02, 0.0, 0, 0.0, 0.0, 1.0, 0, 7000.0, .62, -4.5, .22, .2, FAST, .33, 1.5, 1.1)
        // the pitch envelope runs backwards: the pitch rises into the note
        KindredVoice.LASER -> Shape(900.0, 1350.0, 1, 0, -2.8, -2.8, .05, .2, 0, .7, .4, 0.0, 0, 2400.0, .4, 1.5, .08, 0.0, FAST, .11, 1.8, .6, velp = .5)
        // a click rings the ladder near self-oscillation
        KindredVoice.PING -> Shape(100.0, 150.0, 0, 0, 0.0, 0.0, .02, 0.0, 0, .8, 0.0, .25, 0, 900.0, .93, 1.2, .02, 0.0, FAST, .004, 1.0, .9)
        KindredVoice.SHAKER -> Shape(100.0, 150.0, 0, 0, 0.0, 0.0, .02, 0.0, 0, 0.0, 0.0, 1.0, 0, 2200.0, .3, 2.4, .04, .3, .012, .05, 1.1, .35)
    }

    /** 0.5 is the voice as written; 0 and 1 reach [lo] and [hi] times (log-symmetric): the prototype's `around`. */
    private fun around(x: Double, lo: Double, hi: Double) = if (x < .5) lo.pow(1 - 2 * x) else hi.pow(2 * x - 1)

    /** The six macros applied to [s], the prototype's `apply_macros` line for line. */
    internal fun applyMacros(s: Shape, m: Map<String, Float>): Shape {
        val tune = (m.getValue("TUNE") - .5) * 24.0
        val tf = 2.0.pow(tune / 12)
        // A voice whose oscillators are silent (the clap, sweep and shaker) is tuned by its filter instead, by the
        // same ratio; the prototype's TUNE did nothing there. None of the accepted recipes sets TUNE on those voices.
        val tunesFilter = s.lv1 == 0.0 && s.lv2 == 0.0
        val d = (m["DIVE"] ?: 0.5f).toDouble()
        val diveScale = if (d >= .5) around(d, 0.0, 1.8) else 2 * d
        val c = (m["CLANG"] ?: 0.5f).toDouble()
        val nz = m.getValue("NOISE").toDouble()
        val t = m.getValue("TONE").toDouble()
        val ln = m.getValue("LENGTH").toDouble()
        return s.copy(
            f1 = s.f1 * tf, f2 = s.f2 * tf,
            pamt1 = s.pamt1 * diveScale, pamt2 = s.pamt2 * diveScale, ptau = s.ptau * around(d, .6, 2.2),
            fm = if (c < .5) s.fm * 2 * c else s.fm + (c - .5) * 4.0,
            sync = if (c >= .8) 1 else s.sync,
            lnoise = if (nz < .5) s.lnoise * 2 * nz else s.lnoise + (nz - .5) * 3.0,
            nmod = if (nz < .5) s.nmod * 2 * nz else s.nmod + (nz - .5) * 2.0,
            cutoff = s.cutoff * 2.0.pow((t - .5) * 5.0) * (if (tunesFilter) tf else 1.0),
            famt = s.famt * around(t, .5, 1.5),
            res = min(.92, s.res + max(0.0, t - .6) * .6),
            atau = s.atau * around(ln, .3, 3.0),
            ftau = s.ftau * around(ln, .5, 2.0),
            seconds = min(MAX_SECONDS, s.seconds * around(ln, .7, 1.6)),
        )
    }

    /**
     * Model 2's voicing, the prototype's `model2`. Fixed at the source, not with an EQ:
     * - the body sounds' low-pass cutoffs sit an octave higher and their resonance is lower (less nasal honk), so
     *   the body stays after the snap; hats, bright cutoffs and resonant pings already matched the references, and
     *   opening their filter moved their velocity sweep above where the noise changes;
     * - the mixer drives the ladder 1.4x harder, so the waves pick up harmonics a phone speaker can play;
     * - kicks and anything under 56 Hz settle near 58 Hz (the reference kick settles near 70; round 1 sat at 44 to 50);
     * - a kick gets an octave square at 0.6 and an open ladder with a deeper, slower filter envelope, fitted to the
     *   reference kick's band balance (2.4 dB rms over 60 Hz to 16 kHz): the punch is the dive sweeping harmonics
     *   through an open filter, not the filter snapping shut;
     * - the kick-built toms, rumble and sub hits get the same idea half as far.
     */
    internal fun voiced(s: Shape, role: KindredRole): Shape {
        val body = role != KindredRole.HAT_CLOSED && role != KindredRole.HAT_OPEN && s.res < .85 && s.cutoff < 1500
        var q = s.copy(
            cutoff = if (s.hp == 0 && body) min(s.cutoff * 2.0, 12000.0) else s.cutoff,
            res = if (body) s.res * 0.7 else s.res,
            drive = s.drive * 1.4,
        )
        if (role == KindredRole.KICK || q.f1 < 56) {
            val r = max(1.0, 58.0 / q.f1)
            q = q.copy(f1 = q.f1 * r, f2 = q.f2 * r)
        }
        if (role == KindredRole.KICK) {
            q = q.copy(w2 = 1, f2 = q.f1 * 2.0, lv2 = max(q.lv2, .6), cutoff = min(q.cutoff * 4.0, 12000.0),
                famt = q.famt + 2.2, ftau = max(q.ftau, .11))
        } else if (q.f1 < 120 && q.lv1 > .5 && q.w1 == 0) {
            q = q.copy(w2 = 1, f2 = q.f1 * 2.0, lv2 = max(q.lv2, .35), cutoff = min(q.cutoff * 2.0, 12000.0),
                famt = q.famt + 1.0, ftau = max(q.ftau, .08))
        }
        return q
    }

    /** One strike of the voice: [at] in samples at 4x, [semis] the step's pitch, [vel] its force. */
    internal class Trigger(val at: Int, val semis: Double, val vel: Double, val toVco2Only: Boolean = false, val reset: Boolean = false)

    /** The shape a patch renders: the voice or its recipe, the macros, then model 2's voicing. */
    internal fun shapeFor(voice: KindredVoice, macros: Map<String, Float>, recipe: String?): Shape {
        val r = recipe?.let { KindredRecipes.byId(it) }
        require(r == null || r.voice == voice) { "KINDRED recipe $recipe is a ${r?.voice} recipe, not $voice" }
        val raw = r?.shape?.invoke(shapeOf(voice)) ?: shapeOf(voice)
        return voiced(applyMacros(raw, settled(voice, macros)), r?.role ?: roleFor(voice))
    }

    /**
     * Render [voice] (or its [recipe]) hit at [velocity] as take [take]. Take 0 is the reference strike: the
     * oscillators start at phases 0 and 0.25 with no drift, as the prototype's take 0 does.
     */
    fun render(voice: KindredVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0, recipe: String? = null): Snip =
        Snip(finish(padRaw(voice, macros, velocity, take, recipe)), channels = 1, sampleRate = RATE)

    /** One pad strike at 4x, before the output stage: what [render] finishes and a kit levels by zone. */
    internal fun padRaw(voice: KindredVoice, macros: Map<String, Float>, velocity: Float, take: Int, recipe: String?): DoubleArray {
        require(take in 0..MAX_TAKE) { "KINDRED take out of 0..$MAX_TAKE: $take" }
        val q = shapeFor(voice, macros, recipe)
        val id = recipe ?: voice.name
        val rng = Random(Dsp.seedFor("KINDRED", id, "burst", take))
        val v = velocity.coerceIn(0f, 1f).toDouble()
        val triggers = q.burst.map { (dt, g) ->
            val jitter = if (take == 0) 1.0 else 1.0 + (rng.nextDouble() * .3 - .15)
            Trigger((dt * jitter * FS).toInt(), 0.0, v * g)
        }
        return strike(q, triggers, (q.seconds * FS).toInt(), take, id)
    }

    /** The internal rate, for a kit that schedules strikes in 4x samples. */
    internal const val INTERNAL_RATE = FS

    /** The voice run over [triggers] for [n] samples at 4x, with take [take]'s phases, drift and decays. */
    internal fun strike(q: Shape, triggers: List<Trigger>, n: Int, take: Int, id: String): DoubleArray {
        val rng = Random(Dsp.seedFor("KINDRED", id, "take", take))
        val ph1: Double; val ph2: Double; val drift: Double; val dec: Double
        if (take == 0) { ph1 = 0.0; ph2 = 0.25; drift = 1.0; dec = 1.0 } else {
            ph1 = rng.nextDouble(); ph2 = rng.nextDouble()
            drift = 2.0.pow((rng.nextDouble() * 8 - 4) / 1200)
            dec = 1.0 + (rng.nextDouble() * .1 - .05)
        }
        val noise = Dsp.Noise(Dsp.seedFor("KINDRED", id, "noise", take))
        return run(q.copy(f1 = q.f1 * drift, f2 = q.f2 * drift, ptau = q.ptau * dec, ftau = q.ftau * dec, atau = q.atau * dec),
            triggers, n, ph1, ph2) { noise.next().toDouble() }
    }

    /** The circuit, the prototype's `_run` line for line. [noise] gives one sample of white noise in -1..1. */
    internal fun run(q: Shape, triggers: List<Trigger>, n: Int, phase1: Double, phase2: Double, noise: () -> Double): DoubleArray {
        val fs = FS.toDouble()
        val out = DoubleArray(n)
        var y1 = 0.0; var y2 = 0.0; var y3 = 0.0; var y4 = 0.0
        var penv = 0.0; var fenv = 0.0; var aenv = 0.0
        var attacking = false; var aStart = 0.0; var aT = 0.0
        var vel = 0.0; var semi = 0.0; var to2 = false
        var ph1 = phase1; var ph2 = phase2
        val k = 4.0 * q.res
        val pa = exp(-1.0 / (q.ptau * fs)); val fa = exp(-1.0 / (q.ftau * fs)); val aa = exp(-1.0 / (q.atau * fs))
        val atkN = max(1.0, q.atk * fs)
        val sorted = triggers.sortedBy { it.at }
        var ti = 0
        val twoPiOverFs = 2.0 * PI / fs
        for (i in 0 until n) {
            while (ti < sorted.size && sorted[ti].at <= i) {
                // a retrigger: the decay EGs jump to peak, the VCA re-attacks from where it is
                val tr = sorted[ti]
                vel = tr.vel; semi = tr.semis; to2 = tr.toVco2Only
                if (tr.reset) { ph1 = 0.0; ph2 = 0.25 }
                penv = 1.0; fenv = 1.0; attacking = true; aStart = aenv; aT = 0.0
                ti++
            }
            penv *= pa; fenv *= fa
            if (attacking) {
                aT += 1.0
                aenv = aStart + (1.0 - aStart) * min(1.0, aT / atkN)
                if (aT >= atkN) attacking = false
            } else aenv *= aa
            val pv = (1.0 - q.velp) + q.velp * vel
            val s1 = if (to2) 0.0 else semi
            val hz1 = q.f1 * 2.0.pow(s1 / 12.0 + q.pamt1 * penv * pv)
            val hz2 = q.f2 * 2.0.pow(semi / 12.0 + q.pamt2 * penv * pv)
            // VCO1, the square band-limited with polyBLEP at both edges
            val dt1 = min(hz1 / fs, 0.45)
            ph1 += dt1
            var wrapped = false
            if (ph1 >= 1.0) { ph1 -= 1.0; wrapped = true }
            var o1: Double
            if (q.w1 == 1) {
                o1 = if (ph1 < 0.5) 1.0 else -1.0
                var t = ph1
                if (t < dt1) { val x = t / dt1; o1 += x + x - x * x - 1.0 }
                else if (t > 1.0 - dt1) { val x = (t - 1.0) / dt1; o1 += x * x + x + x + 1.0 }
                t = ph1 - 0.5
                if (t < 0) t += 1.0
                if (t < dt1) { val x = t / dt1; o1 -= x + x - x * x - 1.0 }
                else if (t > 1.0 - dt1) { val x = (t - 1.0) / dt1; o1 -= x * x + x + x + 1.0 }
            } else o1 = 4.0 * abs(ph1 - 0.5) - 1.0
            // VCO2: linear FM from VCO1 (through zero allowed), optional hard sync to VCO1
            val dt2 = (hz2 * (1.0 + q.fm * o1) / fs).coerceIn(-0.45, 0.45)
            ph2 += dt2
            if (q.sync == 1 && wrapped) ph2 = if (dt1 > 0) ph1 * (dt2 / dt1) else 0.0
            ph2 -= floor(ph2)
            val o2 = if (q.w2 == 1) (if (ph2 < 0.5) 1.0 else -1.0) else 4.0 * abs(ph2 - 0.5) - 1.0
            val nz = noise()
            val mix = q.lv1 * o1 + q.lv2 * o2 + q.lnoise * nz
            // the ladder: cutoff from the knob, the filter EG scaled by velocity, noise modulation
            val fv = 0.25 + 0.75 * vel
            val fc = (q.cutoff * 2.0.pow(q.famt * fenv * fv + q.nmod * nz)).coerceIn(15.0, 0.42 * fs)
            val g = 1.0 - exp(-twoPiOverFs * fc)
            val dr = q.drive * (1.0 - q.veldrive + q.veldrive * vel)  // a harder strike drives the ladder harder
            val u = tanh(dr * mix - k * y4)
            val t1 = tanh(y1)
            y1 += g * (u - t1)
            val t1b = tanh(y1); val t2 = tanh(y2)
            y2 += g * (t1b - t2)
            val t2b = tanh(y2); val t3 = tanh(y3)
            y3 += g * (t2b - t3)
            val t3b = tanh(y3); val t4 = tanh(y4)
            y4 += g * (t3b - t4)
            val f = if (q.hp == 1) u - 4.0 * y1 + 6.0 * y2 - 4.0 * y3 + y4 else y4 * (1.0 + 0.5 * q.res)
            out[i] = f * aenv * vel
        }
        return out
    }

    /** Band-limit at 4x, decimate, remove DC, level to the house loudness, fade the last 4 ms. */
    internal fun finish(raw: DoubleArray): FloatArray {
        val f = FloatArray(raw.size) { raw[it].toFloat() }
        Tide.bandLimit(f, FS)
        val d = Dsp.decimate(f, RATE)
        val out = dcBlock(d)
        Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out, rate = RATE)
        return out
    }

    /** The output without levelling, for a test or an audition that must hear velocity as level. */
    internal fun unlevelled(raw: DoubleArray): FloatArray {
        val f = FloatArray(raw.size) { raw[it].toFloat() }
        Tide.bandLimit(f, FS)
        return dcBlock(Dsp.decimate(f, RATE))
    }

    /** Subtract the mean, then a one-pole high-pass at 20 Hz: the house order. */
    internal fun dcBlock(x: FloatArray): FloatArray {
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
