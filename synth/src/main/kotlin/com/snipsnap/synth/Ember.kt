package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.EmberDsp.Kind
import com.snipsnap.synth.EmberDsp.Rng
import com.snipsnap.synth.EmberDsp.add
import com.snipsnap.synth.EmberDsp.band
import com.snipsnap.synth.EmberDsp.butter
import com.snipsnap.synth.EmberDsp.conv
import com.snipsnap.synth.EmberDsp.envFollow
import com.snipsnap.synth.EmberDsp.expMap
import com.snipsnap.synth.EmberDsp.hannForce
import com.snipsnap.synth.EmberDsp.hp
import com.snipsnap.synth.EmberDsp.lin
import com.snipsnap.synth.EmberDsp.lp
import com.snipsnap.synth.EmberDsp.modalIr
import com.snipsnap.synth.EmberDsp.peakNorm
import com.snipsnap.synth.EmberDsp.peakOf
import com.snipsnap.synth.EmberDsp.rms
import com.snipsnap.synth.EmberDsp.semis
import com.snipsnap.synth.EmberDsp.toInternal
import com.snipsnap.synth.EmberDsp.toRate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

// The control sets, kept outside [Ember] so the enum below never waits on the engine object to initialise.
private val EMBER_DRUM = listOf("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")
private val EMBER_MOVING = listOf("TUNE", "SPEED", "BUZZ", "DISTANCE", "WARMTH")
private val EMBER_CROWDS = listOf("TUNE", "CROWD", "DISTANCE", "WARMTH")

/**
 * EMBER's 27 voices, each filed as the kit role it plays, with its render length and its controls. The roll,
 * the swell, the bow, the sizzle and the slowest sweeps run past 1.5 s and are declared PERC, as BREAKER
 * declares its riser and crash.
 */
enum class EmberVoice(val drumClass: DrumClass, internal val seconds: Double, internal val controls: List<String>) {
    KICK(DrumClass.KICK, 1.20, EMBER_DRUM), SNARE(DrumClass.SNARE, 0.90, EMBER_DRUM), RIMSHOT(DrumClass.SNARE, 0.90, EMBER_DRUM),
    CROSS_STICK(DrumClass.PERC, 0.55, EMBER_DRUM), BRUSH_TAP(DrumClass.SNARE, 0.80, EMBER_DRUM),
    BRUSH_SWEEP(DrumClass.PERC, 2.25, EMBER_MOVING), TOM_RACK(DrumClass.TOM, 1.30, EMBER_DRUM), TOM_FLOOR(DrumClass.TOM, 1.40, EMBER_DRUM),
    ELBOW_TOM(DrumClass.TOM, 1.40, listOf("TUNE", "BEND", "BUZZ", "DISTANCE", "WARMTH")), MALLET_TOM(DrumClass.TOM, 1.40, EMBER_DRUM),
    BRUSH_TOM(DrumClass.TOM, 1.20, EMBER_DRUM), RIDE(DrumClass.PERC, 1.45, EMBER_DRUM), RIDE_BELL(DrumClass.PERC, 1.45, EMBER_DRUM),
    BRUSH_RIDE(DrumClass.PERC, 1.45, EMBER_DRUM), SIZZLE(DrumClass.PERC, 2.20, listOf("TUNE", "DAMP", "DISTANCE", "WARMTH")),
    HAT_FOOT(DrumClass.HAT_CLOSED, 0.45, EMBER_DRUM), HAT_CLOSED(DrumClass.HAT_CLOSED, 0.45, EMBER_DRUM), HAT_OPEN(DrumClass.HAT_OPEN, 1.20, EMBER_DRUM),
    BOWED_CYMBAL(DrumClass.PERC, 3.60, listOf("TUNE", "PRESSURE", "BUZZ", "DISTANCE", "WARMTH")), CYMBAL_SWELL(DrumClass.PERC, 5.00, EMBER_MOVING),
    PRESS_ROLL(DrumClass.PERC, 3.00, EMBER_MOVING), BRUSH_ROLL(DrumClass.PERC, 3.00, EMBER_MOVING),
    SNAPS(DrumClass.CLAP, 0.60, EMBER_CROWDS), CLAPS(DrumClass.CLAP, 0.70, EMBER_CROWDS), TAMBOURINE(DrumClass.PERC, 0.80, EMBER_DRUM),
    CONGA(DrumClass.PERC, 0.90, listOf("TUNE", "DAMP", "SLAP", "DISTANCE", "WARMTH")), STICKS(DrumClass.PERC, 0.40, listOf("TUNE", "DAMP", "DISTANCE", "WARMTH")),
}

/**
 * EMBER: a live jazz and soul drum kit in a small, warm club in 1962, from physical models and fits to CC0
 * recordings.
 *
 * Round 1: the Kotlin port of the round-0e Python prototype (`prototype/ember/ember_proto.py`, docs/EMBER.md).
 * The owner heard rounds 0b to 0e on 2026-11-15 ("Much better") and said "Proceed". Every constant here is the
 * prototype's. What cannot be the same arithmetic is disclosed where it happens: Kotlin's [Random] in place of
 * numpy's generator (every noise draw and strike scatter is another realisation of the same law), and the house
 * band limit and decimator in place of scipy's `resample_poly(y, 1, 4)` on the way out. The way in (44.1 to
 * 176.4 kHz) is scipy's `resample_poly(y, 4, 1)` filter itself ([EmberDsp.toInternal]).
 *
 * The gesture: a stick, a brush or a hand meets calfskin or thin bronze → the head sags back to pitch while the
 * snare wires and ride rivets answer on their own threshold → the rest of the kit hums along → a low-ceilinged
 * room and a warm 1962 chain close around it.
 *
 *     implement force @176.4 kHz ─► calf membrane (Bessel modes, calf loss, sag, two heads)
 *                                 │ fitted cymbal (measured modes as beating pairs + residual noise)
 *                                 │ fitted brush stir (band noise along recorded stirs)
 *                                 └─► threshold parts (wires, rivets, plates, jingles): one-way, scaled to the body
 *     ─► DISTANCE: direct + the club's response ─► WARMTH: ribbon, valve, tape ─► voicing ─► band-limit, decimate,
 *        DC, cut, 60 ms fade, peak 0.89
 *
 * - **Calf heads.** Every drum is a circular membrane at Bessel-zero ratios, struck at a radius (|J_m(z r)|), with
 *   calf's steep high-mode loss, a sag back to pitch after a hard hit, and a second head 2.5% higher that beats.
 * - **Threshold parts.** Snare wires, rivets, hat plates and jingles move only past their own threshold, so a soft
 *   stroke is clean and a hard one sizzles. One-way readouts: they never push back on the body.
 * - **Fitted cymbals and brushes** (rounds 0b, 0c): the flat ride, bell, sizzle and hats are their CC0 recordings'
 *   measured resonances (`resources/ember/cymbals.json`, fitted with BREAKER's fitter); the brush sweep and roll
 *   are band noise along recorded stirs and flutters (`resources/ember/brushes.json`). NOTICE.txt beside them
 *   lists the recordings. No recording ships.
 * - **By ear, labelled as such** (rounds 0d, 0e): the sweep's air ([sweepAirDb]), the voicing on every pad, the
 *   lighter 1962 chain, and the bow's higher lock.
 *
 * Controls, each 0..1 (four or five per voice, no HOLD): TUNE, DAMP, BUZZ (0 takes the snares off; on the fitted
 * cymbals 0 is the cymbal as recorded), DISTANCE, WARMTH (0 is an exact bypass), and per voice SPEED, BEND,
 * PRESSURE, CROWD, SLAP. No maker or model names any voice.
 */
object Ember {

    const val RATE = Dsp.RATE
    private const val FS = Dsp.RATE * Dsp.OVERSAMPLE
    private const val FSD = FS.toDouble()
    private const val RATED = RATE.toDouble()
    /** Round 0e's arithmetic. Bump when a pad's audio changes; saved patches carry it. */
    const val MODEL_VERSION = 1
    /** The takes a pad may name, 0 to [MAX_TAKE]: four round-robin strikes. */
    const val MAX_TAKE = 3
    /** Every hit is peak-matched to this, as the prototype's audition was. */
    const val PEAK = 0.89f

    private val DEFAULTS = mapOf(
        "TUNE" to .5f, "DAMP" to .3f, "BUZZ" to .5f, "DISTANCE" to .3f, "WARMTH" to .35f, "SPEED" to .5f,
        "BEND" to .5f, "PRESSURE" to .5f, "CROWD" to .4f, "SLAP" to .3f,
    )
    /** On the fitted cymbals and hats BUZZ adds looseness beyond the recorded cymbal, so it starts at 0. */
    private val BUZZ_ZERO by lazy { setOf(EmberVoice.RIDE, EmberVoice.RIDE_BELL, EmberVoice.BRUSH_RIDE, EmberVoice.BOWED_CYMBAL,
        EmberVoice.CYMBAL_SWELL, EmberVoice.HAT_FOOT, EmberVoice.HAT_CLOSED, EmberVoice.HAT_OPEN) }

    fun macrosFor(voice: EmberVoice): List<MacroSpec> = voice.controls.map {
        MacroSpec(it, if (it == "BUZZ" && voice in BUZZ_ZERO) 0f else DEFAULTS.getValue(it))
    }

    fun defaults(voice: EmberVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** [macros] over the defaults: unknown keys dropped, a non-finite value falls back, everything clamped 0..1. */
    fun settled(voice: EmberVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun drumClassFor(voice: EmberVoice): DrumClass = voice.drumClass

    /** SCRAMBLE near the defaults (there is no preset bank yet), the way CLUTTER and SCUFF roll. */
    fun scramble(voice: EmberVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** One full brush circle: 1.9 s at SPEED 0 (a ballad's slow stir) to 0.5 s. */
    internal fun sweepSeconds(speed: Double) = lin(speed, 1.9, 0.5)

    /** Render length: fixed per voice, except the sweep, whose length follows its circle. */
    fun secondsFor(voice: EmberVoice, macros: Map<String, Float> = emptyMap()): Double =
        if (voice == EmberVoice.BRUSH_SWEEP) round((sweepSeconds(settled(voice, macros).getValue("SPEED").toDouble()) + 0.35) * 1000) / 1000
        else voice.seconds

    /** One hit at 44.1 kHz: [velocity] 0..1 is the stroke, [take] 0..[MAX_TAKE] is which of four strikes. */
    fun render(voice: EmberVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0): Snip {
        val out = renderRaw(voice, macros, velocity, take)
        Dsp.normalize(out, PEAK)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /** One hit before the peak match. */
    internal fun renderRaw(voice: EmberVoice, macros: Map<String, Float>, velocity: Float, take: Int): FloatArray {
        val v = if (velocity.isFinite()) velocity.toDouble().coerceIn(0.05, 1.0) else 1.0
        val m = settled(voice, macros).mapValues { it.value.toDouble() }
        val seconds = secondsFor(voice, macros)
        val n = ((seconds + 0.05) * FS).roundToInt()
        val tr = EmberDsp.rngFor(voice.name, take.coerceIn(0, MAX_TAKE))
        val raw = when (voice) {
            EmberVoice.KICK -> kick(m, v, tr, n)
            EmberVoice.SNARE -> snare(m, v, tr, n)
            EmberVoice.RIMSHOT -> rimshot(m, v, tr, n)
            EmberVoice.CROSS_STICK -> crossStick(m, v, tr, n)
            EmberVoice.BRUSH_TAP -> brushTapVoice(m, v, tr, n)
            EmberVoice.BRUSH_SWEEP -> brushSweep(m, v, tr, n)
            EmberVoice.TOM_RACK -> { val f = stick(v, tr, n); tom(m, v, tr, n, 172.0, f, 0.3, 0.95).also { add(it, hp(f, 2500.0, FSD), 0.12) } }
            EmberVoice.TOM_FLOOR -> { val f = stick(v, tr, n); tom(m, v, tr, n, 104.0, f, 0.3, 1.25).also { add(it, hp(f, 2500.0, FSD), 0.1) } }
            EmberVoice.ELBOW_TOM -> elbowTom(m, v, tr, n)
            EmberVoice.MALLET_TOM -> tom(m, v, tr, n, 138.0, mallet(v, tr, n, widthHard = 2.4e-3), 0.4, 1.3)
            EmberVoice.BRUSH_TOM -> tom(m, v, tr, n, 118.0, brushTap(v, tr, n), 0.35, 1.0)
            EmberVoice.RIDE -> { val (s, d) = struck("flatride", m, v, tr, n); s.also { add(it, rivets(d, m.getValue("BUZZ"), v, tr, n), RIVET_GAIN * peakOf(s)) } }
            EmberVoice.RIDE_BELL -> { val (s, d) = struck("bell", m, v, tr, n); s.also { add(it, rivets(d, m.getValue("BUZZ"), v, tr, n), 0.6 * RIVET_GAIN * peakOf(s)) } }
            EmberVoice.BRUSH_RIDE -> brushRide(m, v, tr, n)
            EmberVoice.SIZZLE -> struck("sizzle", m, v, tr, n).first
            EmberVoice.HAT_FOOT -> hatChatter(toInternal(envelopeHat("hat_pedal", m.getValue("TUNE"), m.getValue("DAMP"), v, tr, n / 4 + 1), n), m, v, tr, n, 1200.0)
            EmberVoice.HAT_CLOSED -> hatChatter(toInternal(envelopeHat("hat_closed", m.getValue("TUNE"), m.getValue("DAMP"), v, tr, n / 4 + 1), n), m, v, tr, n, 1600.0)
            EmberVoice.HAT_OPEN -> hatChatter(struck("hat_open", m, v, tr, n).first, m, v, tr, n, 900.0)
            EmberVoice.BOWED_CYMBAL -> bowedCymbal(m, v, tr, n)
            EmberVoice.CYMBAL_SWELL -> cymbalSwell(m, v, tr, n)
            EmberVoice.PRESS_ROLL -> roll(m, v, tr, n)
            EmberVoice.BRUSH_ROLL -> brushRoll(m, v, tr, n)
            EmberVoice.SNAPS -> crowd(m, v, tr, n, clap = false)
            EmberVoice.CLAPS -> crowd(m, v, tr, n, clap = true)
            EmberVoice.TAMBOURINE -> tambourine(m, v, tr, n)
            EmberVoice.CONGA -> conga(m, v, tr, n)
            EmberVoice.STICKS -> sticks(m, v, tr, n)
        }
        return finish(raw, m, v, seconds, TAILS[voice])
    }

    // ------------------------------------------------------------------ implements

    /** A hickory tip: contact time ~ v^-0.3 (Hertz gives v^-0.2; widened for audibility, shape), force v^1.25. */
    private fun stick(v: Double, rng: Rng, n: Int, widthHard: Double = 0.28e-3, at: Double = 0.0): DoubleArray =
        hannForce(widthHard * v.pow(-0.3) * (1 + 0.07 * rng.normal()), v.pow(1.25), n, at, FSD)

    private fun mallet(v: Double, rng: Rng, n: Int, at: Double = 0.0, widthHard: Double = 1.6e-3): DoubleArray =
        hannForce(widthHard * v.pow(-0.35) * (1 + 0.06 * rng.normal()), v.pow(1.3), n, at, FSD)

    /** [stick] and [mallet] added straight into [f] (scaled by [k]): a roll's strokes without a full-length buffer each. */
    private fun addStick(f: DoubleArray, v: Double, rng: Rng, at: Double, widthHard: Double = 0.28e-3, k: Double = 1.0) =
        EmberDsp.addHannForce(f, widthHard * v.pow(-0.3) * (1 + 0.07 * rng.normal()), v.pow(1.25) * k, at, FSD)

    private fun addMallet(f: DoubleArray, v: Double, rng: Rng, at: Double, widthHard: Double, k: Double = 1.0) =
        EmberDsp.addHannForce(f, widthHard * v.pow(-0.35) * (1 + 0.06 * rng.normal()), v.pow(1.3) * k, at, FSD)

    /** A wire brush: 25 to 65 wires land over 2.5 to 7 ms; soft spreads them and lands fewer. */
    private fun brushTap(v: Double, rng: Rng, n: Int, at: Double = 0.0): DoubleArray {
        val k = (25 + 40 * v).toInt()
        val spread = (7.0 - 4.5 * v) * 1e-3
        val times = DoubleArray(k) { at + rng.gamma(2.0, spread / 4) }.sortedArray()
        val amps = rng.uniform(0.3, 1.0, k)
        val f = DoubleArray(n)
        for (i in 0 until k) EmberDsp.addHannForce(f, 0.07e-3, amps[i] * v.pow(1.4) / k * 6, times[i], FSD)
        return f
    }

    /** A palm: a broad push plus finger slaps; cupping makes the slap sharper. */
    private fun hand(v: Double, rng: Rng, n: Int, cupped: Double): DoubleArray {
        val f = hannForce((2.4 - 1.2 * cupped) * 1e-3 * v.pow(-0.2), v.pow(1.2), n, 0.0, FSD)
        for (i in 0 until 4) EmberDsp.addHannForce(f, 0.35e-3, (0.15 + 0.5 * cupped) * v.pow(1.6) * rng.uniform(.6, 1.0), 0.0006 * i + rng.uniform(0.0, 4e-4), FSD)
        return f
    }

    // ------------------------------------------------------------------ the membrane

    private class Zero(val z: Double, val m: Int, val n: Int)

    /** The lowest 18 zeros of J_m (scipy's `jn_zeros`, m 0 to 9), sorted: the circular membrane's mode table. */
    private val BESSEL = listOf(
        Zero(2.4048255576957724, 0, 1), Zero(3.8317059702075125, 1, 1), Zero(5.135622301840683, 2, 1), Zero(5.520078110286311, 0, 2),
        Zero(6.380161895923984, 3, 1), Zero(7.015586669815619, 1, 2), Zero(7.588342434503804, 4, 1), Zero(8.417244140399866, 2, 2),
        Zero(8.653727912911013, 0, 3), Zero(8.771483815959954, 5, 1), Zero(9.76102312998167, 3, 2), Zero(9.936109524217686, 6, 1),
        Zero(10.173468135062722, 1, 3), Zero(11.064709488501185, 4, 2), Zero(11.086370019245084, 7, 1), Zero(11.61984117214906, 2, 3),
        Zero(11.791534439014281, 0, 4), Zero(12.225092264004656, 8, 1),
    )

    /**
     * A calf-headed drum's impulse response, struck at radius [r] (0 centre to 1 edge). t60 of mode k is
     * t1 (f_k / f1)^-alpha: calf's high modes die far faster than its low ones. Strike weight |J_m(z r)|, times
     * cos^2(m angle) for m > 0; radiation favours m = 0. A take's [scatter] varies each mode by 1.5 dB rms. The
     * second head ([reso]) rings the low modes 2.5% higher, so the drum beats. [sag] starts every mode that much
     * sharp, settling over [sagTau]; [bend] (a pitch-ratio curve) replaces it for the elbow.
     */
    internal fun membraneIr(
        f1: Double, n: Int, t1: Double, alpha: Double, r: Double, angle: Double, sag: Double = 0.0, sagTau: Double = 0.05,
        reso: Boolean = true, bend: DoubleArray? = null, scatter: Rng? = null,
    ): DoubleArray {
        val fr = ArrayList<Double>(); val am = ArrayList<Double>(); val tt = ArrayList<Double>(); val ph = ArrayList<Double>()
        val j01 = BESSEL[0].z
        for (z in BESSEL) {
            val ratio = z.z / j01
            val f = f1 * ratio
            val wgt = abs(EmberDsp.besselJ(z.m, z.z * r)) * (if (z.m == 0) 1.0 else cos(z.m * angle).pow(2) * 0.8 + 0.2)
            val rad = if (z.m == 0) 1.0 else if (z.m == 1) 0.55 else 0.35
            var a = wgt * rad / (1 + 0.15 * z.n)
            if (scatter != null) a *= 10.0.pow(scatter.normal(0.0, 1.5) / 20)
            val t60 = t1 * ratio.pow(-alpha)
            fr.add(f); am.add(a); tt.add(t60); ph.add(0.0)
            if (reso && ratio < 3.2) { fr.add(f * 1.025); am.add(a * 0.35); tt.add(t60 * 1.15); ph.add(0.4) }
        }
        val warp = when {
            bend != null -> { val w = DoubleArray(n); var s = 0.0; for (i in 0 until n) { s += bend[i]; w[i] = s / FSD }; w }
            sag > 0 -> DoubleArray(n) { val t = it / FSD; t + sag * sagTau * (1 - exp(-t / sagTau)) }
            else -> null
        }
        return modalIr(fr.toDoubleArray(), am.toDoubleArray(), tt.toDoubleArray(), n, FSD, ph.toDoubleArray(), warp)
    }

    // ------------------------------------------------------------------ threshold parts

    /** Calibrated at round 0: a hard snare hit's wires near 0.4 of the head's rms; the ride's sizzle near 0.35 of the ride's. Shape. */
    private const val WIRE_GAIN = 9.0
    private const val RIVET_GAIN = 0.17

    /**
     * Snare wires: a noise burst whenever the head's low motion swings past their tension. Loose wires (BUZZ high)
     * lift sooner, ring longer and darker. BUZZ 0 takes the snares off (an exact bypass). One-way.
     */
    internal fun wires(head: DoubleArray, buzz: Double, rng: Rng, n: Int): DoubleArray {
        if (buzz <= 0) return DoubleArray(n)
        val theta = lin(buzz, 0.42, 0.05)
        val low = lp(head, 700.0, FSD)
        val drive = envFollow(DoubleArray(n) { if (it < low.size) max(0.0, abs(low[it]) - theta) else 0.0 }, lin(buzz, 1.5, 6.0), FSD)
        val grit = DoubleArray(n) { val g = rng.normal(); if (rng.random() < 0.35) g * drive[it] else 0.0 }
        val toneHi = lin(buzz, 9500.0, 5200.0)
        val x = band(grit, 2200 - 900 * buzz, toneHi, FSD)
        add(x, band(grit, 5500.0, 12000.0, FSD), 0.35)
        val g = min(1.0, buzz / 0.1)
        for (i in x.indices) x[i] *= g
        return x
    }

    /**
     * Rivets, jingles, a loose hat plate: [count] parts each lifting when [driveEnv] passes its own threshold, then
     * landing at a rate that grows with the excess. Each landing is a tiny ring plus a grain of noise.
     */
    private fun rattlers(driveEnv: DoubleArray, count: Int, thLo: Double, thHi: Double, rateHz: Double, ring: List<Pair<Double, Double>>, rng: Rng, n: Int, kickNoise: Double): DoubleArray {
        val out = DoubleArray(n)
        if (count <= 0) return out
        val ringIr = modalIr(ring.map { it.first }.toDoubleArray(), DoubleArray(ring.size) { 1.0 }, ring.map { it.second }.toDoubleArray(), (0.03 * FSD).roundToInt(), FSD)
        val grainN = (0.0015 * FSD).roundToInt()
        for (p in 0 until count) {
            val th = rng.uniform(thLo, thHi)
            val g = rng.uniform(0.8, 1.25)
            var phase = rng.random()
            val imp = DoubleArray(n)
            var prev = kotlin.math.floor(phase)
            for (i in 0 until n) {
                val ex = max(0.0, driveEnv[i] - th)
                phase += rateHz * sqrt(ex / max(1e-9, 1 - th)) * g / FSD
                val fl = kotlin.math.floor(phase)
                if (fl > prev && i > 0) {
                    val j = (i - 1 + rng.int(-40, 40)).coerceIn(0, n - 1)
                    imp[j] = max(0.0, driveEnv[j] - th) * rng.uniform(0.5, 1.0)
                }
                prev = fl
            }
            val part = conv(imp, ringIr, n)
            val h = DoubleArray(grainN) { val w = 0.5 - 0.5 * cos(2 * PI * it / (grainN - 1)); rng.normal() * w }
            val grains = hp(conv(imp, h, n), 3000.0, FSD)
            add(out, part); add(out, grains, kickNoise)
        }
        return out
    }

    private fun rivets(drive: DoubleArray, buzz: Double, v: Double, rng: Rng, n: Int): DoubleArray {
        val count = round(lin(buzz, 0.0, 10.0)).toInt()
        if (count == 0) return DoubleArray(n)
        val pk = peakOf(drive) + 1e-12
        val k = min(1.0, 0.35 + 0.65 * v) / pk
        return rattlers(DoubleArray(n) { drive[it] * k }, count, 0.10, 0.45, 520.0, listOf(8200.0 to .006, 11300.0 to .004, 6400.0 to .008), rng, n, 0.6)
    }

    // ------------------------------------------------------------------ fitted cymbals, hats and brushes

    private class FitVoice(val modes: List<DoubleArray>, val stick: DoubleArray, val stickSoft: DoubleArray,
                           val times: DoubleArray, val hard: List<DoubleArray>, val soft: List<DoubleArray>,
                           val residual: List<DoubleArray>, val residualSoft: List<DoubleArray>)

    private class CymbalFit(val bands: DoubleArray, val voices: Map<String, FitVoice>)

    private fun nums(v: JsonValue?) = v?.arr()?.map { it.num() }?.toDoubleArray() ?: DoubleArray(0)
    private fun rows(v: JsonValue?) = v?.arr()?.map { nums(it) } ?: emptyList()

    private val cymbalFit: CymbalFit by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/ember/cymbals.json")) { "Missing EMBER cymbal fits" }.bufferedReader().use { it.readText() }
        val root = Json.parse(text).obj()
        val voices = root.getValue("voices").obj().mapValues { (_, jv) ->
            val o = jv.obj()
            FitVoice(rows(o["modes"]), nums(o["stick"]), nums(o["stickSoft"]), nums(o["times"]), rows(o["hard"]), rows(o["soft"]),
                rows(o["residual"]), rows(o["residualSoft"]))
        }
        CymbalFit(nums(root["bands"]), voices)
    }

    private val bandSos = ConcurrentHashMap<Int, EmberDsp.Sos>()
    private fun fitBand(i: Int): EmberDsp.Sos = bandSos.getOrPut(i) {
        val fc = cymbalFit.bands[i]
        butter(4, Kind.BAND, fc / 2.0.pow(1.0 / 6), min(fc * 2.0.pow(1.0 / 6), RATED * 0.49), RATED)
    }

    /** 0 at a full hit, 1 at the recordings' soft layer (velocity .35), a little past it below that. */
    private fun softWeight(v: Double) = ((1.0 - v) / 0.65).coerceIn(0.0, 1.3)
    /** DAMP .3 is the cymbal as recorded, 1 three times faster, 0 a little longer (0.64x). Shape. */
    private fun dampFactor(damp: Double) = 2.0.pow((damp - 0.3) / 0.7 * 1.6)

    /** Interpolate a (bands x times) level table between hard and soft, and slide it [shift] bands. */
    private fun levels(hard: List<DoubleArray>, soft: List<DoubleArray>, w: Double, shift: Double): List<DoubleArray> {
        val nb = hard.size
        val mix = List(nb) { b -> DoubleArray(hard[b].size) { (1 - w) * hard[b][it] + w * soft[b][it] } }
        return List(nb) { b ->
            val x = (b - shift).coerceIn(0.0, nb - 1.0)
            val i0 = x.toInt().coerceAtMost(nb - 1); val i1 = min(nb - 1, i0 + 1); val f = x - i0
            DoubleArray(mix[b].size) { (1 - f) * mix[i0][it] + f * mix[i1][it] }
        }
    }

    private const val DB = 0.11512925464970229   // ln(10) / 20: 10^(x/20) = exp(x DB)

    /** np.interp over a rising [n]-sample time axis (j / rate x [scale]), filled in one pass. */
    private fun interpRamp(n: Int, scale: Double, xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val out = DoubleArray(n)
        var j = 1
        for (i in 0 until n) {
            val x = i / RATED * scale
            out[i] = when {
                x <= xs[0] -> ys[0]
                x >= xs[xs.size - 1] -> ys[ys.size - 1]
                else -> { while (xs[j] < x) j++; ys[j - 1] + (x - xs[j - 1]) / (xs[j] - xs[j - 1]) * (ys[j] - ys[j - 1]) }
            }
        }
        return out
    }

    /** np.interp: piecewise linear in [xs], clamped at the ends. */
    private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x <= xs[0]) return ys[0]
        if (x >= xs[xs.size - 1]) return ys[ys.size - 1]
        var j = 1
        while (xs[j] < x) j++
        val f = (x - xs[j - 1]) / (xs[j] - xs[j - 1])
        return ys[j - 1] + f * (ys[j] - ys[j - 1])
    }

    /** Unit-rms band noise in band [i], [n] samples, after 2000 samples of settling (the prototype's habit). */
    private fun bandNoise(i: Int, n: Int, rng: Rng): DoubleArray {
        val x = fitBand(i).run(rng.normals(n + 2000))
        val y = x.copyOfRange(2000, 2000 + n)
        val r = rms(y) + 1e-20
        for (j in y.indices) y[j] /= r
        return y
    }

    /**
     * The fitted cymbal struck once at 44.1 kHz: every measured mode moved by TUNE (3 semitones), DAMP and its
     * band's soft-minus-hard tilt; split into a beating pair 0.4 to 3 Hz apart (fixed per cymbal; shape); a take
     * a fresh strike (0.3 Hz detune, fresh phase, 1.6 dB rms scatter). Unit rms over its first 300 ms. With
     * [splitHz], returns (below, above).
     */
    private fun fittedModes(kind: String, tune: Double, damp: Double, v: Double, tr: Rng, seconds: Double, splitHz: Double? = null): Pair<DoubleArray, DoubleArray?> {
        val md = cymbalFit.voices.getValue(kind).modes
        val k = md.size
        val s3 = semis(tune, 3.0)
        val sw = softWeight(v)
        val df = dampFactor(damp)
        val hz = DoubleArray(2 * k); val amp = DoubleArray(2 * k); val slope = DoubleArray(2 * k)
        for (i in 0 until k) {
            hz[i] = md[i][0] * s3 + tr.uniform(-0.3, 0.3)
            amp[i] = md[i][1] * 10.0.pow((md[i][3] * sw + tr.normal(0.0, 1.6)) / 20)
            slope[i] = min(md[i][2] * df, -1.0)
        }
        val cr = Rng(Dsp.seedFor("EMBER-doublets", kind))
        for (i in 0 until k) {
            val split = cr.uniform(0.4, 3.0) * s3
            val share = cr.uniform(0.2 * PI, 0.3 * PI)
            val a = amp[i]
            hz[k + i] = hz[i] + split
            amp[i] = a * cos(share); amp[k + i] = a * sin(share)
            slope[k + i] = slope[i] * cr.uniform(0.9, 1.1)
        }
        val n = (seconds * RATED).roundToInt()
        fun synth(keep: (Int) -> Boolean): DoubleArray {
            val out = DoubleArray(n)
            for (j in 0 until 2 * k) {
                if (!keep(j) || hz[j] >= 0.47 * RATED) continue
                val ph = tr.uniform(0.0, 2 * PI)
                val lim = min(n, (90.0 / -slope[j] * RATED).toInt() + 1)
                val dth = 2 * PI * hz[j] / RATED
                val cd = cos(dth); val sd = sin(dth)
                val decay = 10.0.pow(slope[j] / 20 / RATED)
                var c = cos(ph); var s = sin(ph); var e = amp[j]
                for (t in 0 until lim) {
                    out[t] += e * s
                    val c2 = c * cd - s * sd; s = s * cd + c * sd; c = c2
                    e *= decay
                }
            }
            return out
        }
        val r300 = (0.3 * RATED).toInt()
        if (splitHz == null) {
            val y = synth { true }
            val r = rms(y, 0, r300) + 1e-20
            return DoubleArray(n) { y[it] / r } to null
        }
        val lo = synth { hz[it] < splitHz }
        val hi = synth { hz[it] >= splitHz }
        val r = rms(DoubleArray(min(n, r300)) { lo[it] + hi[it] }) + 1e-20
        return DoubleArray(n) { lo[it] / r } to DoubleArray(n) { hi[it] / r }
    }

    /** The stick's own 60 ms of noise at the recording's band levels, toward the soft recording's as velocity falls. Unit rms over 20 ms. */
    private fun stickBurst(kind: String, v: Double, tr: Rng, n: Int): DoubleArray {
        val d = cymbalFit.voices.getValue(kind)
        val w = min(1.0, softWeight(v))
        val m = (0.06 * RATED).toInt()
        val b = DoubleArray(m)
        for (i in d.stick.indices) {
            val level = (1 - w) * d.stick[i] + w * d.stickSoft[i]
            add(b, fitBand(i).run(tr.normals(m)), level)
        }
        for (i in 0 until m) { val t = i / RATED; b[i] *= exp(-t / 0.008) * min(1.0, t / 0.0004) }
        val r = rms(b, 0, (0.02 * RATED).toInt()) + 1e-20
        return DoubleArray(n) { if (it < m) b[it] / r else 0.0 }
    }

    /** A closed or pedalled hat: unit band noise along the recording's band levels over time, hard to soft. */
    private fun envelopeHat(kind: String, tune: Double, damp: Double, v: Double, tr: Rng, n: Int): DoubleArray {
        val d = cymbalFit.voices.getValue(kind)
        val lev = levels(d.hard, d.soft, softWeight(v), (tune - 0.5) * 2 * 3 / 4)
        val df = dampFactor(damp)
        val out = DoubleArray(n)
        for ((i, fc) in cymbalFit.bands.withIndex()) {
            if (fc * 2.0.pow(1.0 / 6) >= 0.49 * RATED) continue
            val x = bandNoise(i, n, tr)
            val off = tr.normal(0.0, 1.0)
            val db = interpRamp(n, df, d.times, lev[i])
            for (j in 0 until n) out[j] += x[j] * exp((db[j] + off) * DB)
        }
        return out
    }

    /** The fitted residual: band noise along what the modes and stick leave out of the recording. Not under 200 Hz (the overhead heard the room there). */
    private fun residualNoise(kind: String, tune: Double, damp: Double, v: Double, tr: Rng, n: Int, refRms: Double): DoubleArray {
        val d = cymbalFit.voices.getValue(kind)
        val lev = levels(d.residual, d.residualSoft, min(1.0, softWeight(v)), (tune - 0.5) * 2 * 3 / 4)
        val df = dampFactor(damp)
        val out = DoubleArray(n)
        for ((i, fc) in cymbalFit.bands.withIndex()) {
            if (fc < 200 || fc * 2.0.pow(1.0 / 6) >= 0.49 * RATED) continue
            val x = bandNoise(i, n, tr)
            val db = interpRamp(n, df, d.times, lev[i])
            for (j in 0 until n) out[j] += x[j] * exp(db[j] * DB)
        }
        for (j in 0 until n) out[j] *= refRms
        return out
    }

    /** A stick strike on a fitted cymbal: modes, stick and residual, at 176.4 kHz, and its drive envelope. */
    private fun struck(kind: String, m: Map<String, Double>, v: Double, tr: Rng, n: Int): Pair<DoubleArray, DoubleArray> {
        val tune = m["TUNE"] ?: .5; val damp = m["DAMP"] ?: .3
        val y = fittedModes(kind, tune, damp, v, tr, n / FSD).first
        add(y, stickBurst(kind, v, tr, y.size), 0.6)
        add(y, residualNoise(kind, tune, damp, v, tr, y.size, rms(y, 0, (0.5 * RATED).toInt())))
        val out = toInternal(y, n)
        return out to envFollow(lp(out, 2500.0, FSD), 6.0, FSD)
    }

    /** A fitted cymbal driven by any implement's force (brush, mallet, bow). With [splitHz], (below, above). */
    private fun driven(kind: String, m: Map<String, Double>, v: Double, tr: Rng, n: Int, force: DoubleArray, splitHz: Double? = null): Pair<DoubleArray, DoubleArray?> {
        val sec = n / FSD
        val f44 = toRate(force)
        val k = (sec * RATED).roundToInt()
        val (a, b) = fittedModes(kind, m["TUNE"] ?: .5, m["DAMP"] ?: .3, v, tr, sec, splitHz)
        val lo = toInternal(conv(f44, a, k), n)
        return lo to b?.let { toInternal(conv(f44, it, k), n) }
    }

    private class StirLevel(val spectrum: DoubleArray, val follow: DoubleArray, val wobble: Double, val sway: Double)
    private class BrushFit(val bands: DoubleArray, val levels: List<Pair<Double, StirLevel>>, val flutter: StirLevel)

    private val brushFit: BrushFit by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/ember/brushes.json")) { "Missing EMBER brush fits" }.bufferedReader().use { it.readText() }
        val root = Json.parse(text).obj()
        fun level(o: Map<String, JsonValue>) = StirLevel(nums(o["spectrum"]), nums(o["follow"]), o.getValue("wobble").num(), o.getValue("sway").num())
        val vel = root.getValue("velocity").obj()
        val lv = root.getValue("levels").obj().map { (key, o) -> vel.getValue(key).num() to level(o.obj()) }.sortedBy { it.first }
        BrushFit(nums(root["bands"]), lv, level(root.getValue("flutter").obj()))
    }

    /** The fitted stir at velocity [v], between the four recorded dynamic levels (dl1 at .3 to dl4 at 1). */
    private fun stirProfile(vIn: Double): StirLevel {
        val lv = brushFit.levels
        val v = vIn.coerceIn(lv.first().first, lv.last().first)
        var i = lv.indexOfFirst { it.first >= v } - 1
        i = i.coerceIn(0, lv.size - 2)
        val (va, a) = lv[i]; val (vb, b) = lv[i + 1]
        val w = (v - va) / (vb - va)
        fun mix(x: DoubleArray, y: DoubleArray) = DoubleArray(x.size) { (1 - w) * x[it] + w * y[it] }
        return StirLevel(mix(a.spectrum, b.spectrum), mix(a.follow, b.follow), (1 - w) * a.wobble + w * b.wobble, (1 - w) * a.sway + w * b.sway)
    }

    /** By ear (round 0d): the sweep brighter than its stirs, +3 dB an octave from 2 kHz to +6, and 1.5 dB out of 400 Hz to 1.3 kHz. */
    internal fun sweepAirDb(fc: Double): Double {
        val lift = if (fc >= 2000) min(6.0, 3.0 * log2(fc / 2000.0)) else 0.0
        return lift - if (fc in 400.0..1300.0) 1.5 else 0.0
    }

    /** Smoothed unit-std noise: the stroke's sway (3 Hz) or wobble (20 Hz). */
    private fun drift(hz: Double, k: Int, tr: Rng): DoubleArray {
        val x = butter(2, Kind.LOW, hz, 0.0, RATED).runTwice(tr.normals(k))
        val s = EmberDsp.std(x) + 1e-12
        return DoubleArray(k) { x[it] / s }
    }

    /** Band noise along a stir spectrum, moving with [level] (dB) by the measured follow, at 44.1 kHz. */
    private fun stirNoise(spectrum: DoubleArray, follow: DoubleArray, level: DoubleArray, peakAt: Double, buzz: Double, tr: Rng, k: Int): DoubleArray {
        val out = DoubleArray(k)
        val cs = DoubleArray(k) { (level[it] - peakAt).coerceIn(-15.0, 0.0) + 3.0 }
        for ((i, fc) in brushFit.bands.withIndex()) {
            if (fc * 2.0.pow(1.0 / 6) >= 0.49 * RATED) continue
            val wire = if (fc in 2000.0..8000.0) (buzz - 0.5) * 12.0 else 0.0
            val noise = bandNoise(i, k, tr)
            val off = tr.normal(0.0, 0.5)
            val base = spectrum[i] + wire + off
            for (j in 0 until k) out[j] += noise[j] * exp((base + follow[i] * cs[j]) * DB)
        }
        for (j in 0 until k) out[j] *= exp(level[j] * DB)
        return out
    }

    private fun slideBands(spectrum: DoubleArray, shift: Double): DoubleArray {
        val nb = spectrum.size
        return DoubleArray(nb) { b ->
            val x = (b - shift).coerceIn(0.0, nb - 1.0)
            val i0 = x.toInt().coerceAtMost(nb - 1); val i1 = min(nb - 1, i0 + 1); val f = x - i0
            (1 - f) * spectrum[i0] + f * spectrum[i1]
        }
    }

    // ------------------------------------------------------------------ the club and the chain

    /**
     * A few recent rooms (1.3 MB each) and their spectra (2 to 8 MB each, one per DISTANCE and FFT size), least
     * recently used out first: a DISTANCE sweep must not keep a thousand of them.
     */
    private class Recent<K, V>(private val limit: Int) {
        private val map = object : LinkedHashMap<K, V>(limit, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > limit
        }
        fun get(key: K, build: () -> V): V = synchronized(map) { map[key] } ?: build().let { v -> synchronized(map) { map.getOrPut(key) { v } } }
        fun size() = synchronized(map) { map.size }
    }
    private val roomCache = Recent<Int, DoubleArray>(ROOMS_KEPT)
    private val roomSpectra = Recent<Long, Pair<FloatArray, FloatArray>>(ROOMS_KEPT)
    internal const val ROOMS_KEPT = 4
    internal fun roomsHeld() = roomCache.size()

    /**
     * The club, cached by DISTANCE: a low ceiling, a hard bar, a brick back wall, a soft crowd. Fixed early
     * reflections, then a two-band tail (lows ~0.62 s, highs ~0.32 s). Direct against room by energy, from +16 dB
     * over the drum to -4 dB at the back. Shape.
     *
     * The tail is the prototype's own seeded draw (`resources/ember/club_tail.f32`, written by
     * `prototype/ember/export_club.py`), not a fresh one. Close up, the tail carries most of a drum's low end, so
     * a new draw recoloured every fundamental: Kotlin's own put the rack tom's 250 Hz octave 2.8 dB under what the
     * owner heard. It is stored at 44.1 kHz and brought back to 176.4 kHz here; only the band under 20 kHz
     * survives the output stage anyway.
     */
    internal fun roomIr(distance: Double): DoubleArray = (distance * 1000).roundToInt().let { key -> roomCache.get(key) { buildRoom(key / 1000.0) } }

    private val clubTail: DoubleArray by lazy {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/ember/club_tail.f32")) { "Missing EMBER club tail" }.use { it.readBytes() }
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val t44 = DoubleArray(bytes.size / 4) { buf.getFloat().toDouble() }
        toInternal(t44, (0.9 * FSD).roundToInt())
    }

    private fun buildRoom(distance: Double): DoubleArray {
        val n = (0.9 * FSD).roundToInt()
        val dn = (0.01 * FSD).roundToInt()
        val direct = DoubleArray(dn); direct[(distance * 0.006 * FSD).toInt()] = 1.0
        val dl = lp(direct, expMap(distance, 19000.0, 5200.0), FSD, order = 1)
        val drr = lin(distance, 16.0, -4.0)
        val dNorm = sqrt(dl.sumOf { it * it })
        val k = 10.0.pow(-drr / 20)
        val tail = clubTail
        val out = DoubleArray(n) { tail[it] * k }
        for (i in 0 until dn) out[i] += dl[i] / dNorm
        return out
    }

    /**
     * WARMTH: a ribbon mic (a top roll-off and a low lift), a valve preamp (asymmetric soft clip, driven harder by
     * harder hits) and tape (a head bump near 70 Hz, a soft top). Round 0e's lighter roll-offs. 0 is an exact bypass.
     */
    internal fun warmthChain(x: DoubleArray, warmth: Double, v: Double): DoubleArray {
        if (warmth <= 0) return x
        var y = lp(x, expMap(warmth, 19000.0, 10500.0), FSD)
        add(y, lp(y, 160.0, FSD, order = 1), warmth * 0.15)
        val pk = peakOf(y) + 1e-12
        val drive = 0.6 + 3.0 * warmth * (0.4 + 0.6 * v)
        val bias = 0.18 * warmth
        val tb = tanh(bias)
        y = DoubleArray(y.size) { val z = (tanh(y[it] / pk * drive + bias) - tb) / drive * pk; (1 - warmth) * y[it] + warmth * z }
        y = EmberDsp.peaking(70.0, 2.8 * warmth, 0.9, FSD).run(y)
        return lp(y, expMap(warmth, 19500.0, 13000.0), FSD, order = 1)
    }

    /** By ear (round 0e): the close mic's voicing on every pad, 1.5 dB out around 600 Hz and a 2 dB shelf from 6 kHz. */
    internal fun voicing(y: DoubleArray): DoubleArray = EmberDsp.highShelf(6000.0, 2.0, FSD).run(EmberDsp.peaking(600.0, -1.5, 0.7, FSD).run(y))

    /** DISTANCE, WARMTH, voicing, then the house band limit and decimator, DC out, the cut and a 60 ms fade. */
    /**
     * Round 1b (owner, 2026-11-15: "Swell cuts abruptly"): the long voices were cut while still sounding. The rolls
     * now run until they have died away on their own; the flat ride under the swell and the bow rings for well
     * over 7 s, so once the mallets stop or the bow leaves, it also takes an exponential fade reaching this many
     * dB at the end of the pad (start s to dB). A decay to the ear, by ear; the prototype's `TAILS`.
     */
    private val TAILS by lazy { mapOf(EmberVoice.CYMBAL_SWELL to (2.8 to -50.0), EmberVoice.BOWED_CYMBAL to (2.4 to -30.0)) }

    internal fun finish(raw: DoubleArray, m: Map<String, Double>, v: Double, seconds: Double, tail: Pair<Double, Double>? = null): FloatArray {
        val n = raw.size
        val room = roomIr(m.getValue("DISTANCE"))
        val size = EmberDsp.fftSize(n + room.size - 1)
        val key = round(m.getValue("DISTANCE") * 1000).toLong() * 100_000_000L + size
        val spec = roomSpectra.get(key) { EmberDsp.spectrum(room, size) }
        var y = EmberDsp.convFft(raw, room, n, spec)
        y = warmthChain(y, m.getValue("WARMTH"), v)
        y = voicing(y)
        val f = FloatArray(n) { y[it].toFloat() }
        Tide.bandLimit(f, FS)
        val dec = Dsp.decimate(f, RATE)
        val mean = dec.average()
        val rc = 1.0 / (2 * PI * 20.0)
        val alpha = rc / (rc + 1.0 / RATED)
        val len = min(dec.size, (seconds * RATED).toInt())
        val out = FloatArray(len)
        var px = 0.0; var py = 0.0
        for (i in 0 until len) { val x = dec[i] - mean; py = alpha * (py + x - px); px = x; out[i] = py.toFloat() }
        if (tail != null) {
            val i0 = (tail.first * RATED).toInt()
            val span = max(1, len - 1 - i0)
            for (i in i0 until len) out[i] = (out[i] * exp(tail.second * DB * (i - i0) / span)).toFloat()
        }
        val fade = min(len, (0.06 * RATED).toInt())
        for (q in 0 until fade) { val g = 0.5 * (1 + cos(PI * q / (fade - 1).coerceAtLeast(1))); out[len - fade + q] = (out[len - fade + q] * g).toFloat() }
        return out
    }

    // ------------------------------------------------------------------ the voices

    /** An 18-inch bass drum, calf both sides, a felt beater: tuned up and left open; the kit hums along (BUZZ). */
    private fun kick(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val f1 = 66.0 * semis(m.getValue("TUNE"), 6.0)
        val t1 = lin(m.getValue("DAMP"), 0.95, 0.16)
        val force = mallet(v, tr, n, widthHard = 1.3e-3)
        add(force, hannForce(9e-3, 0.5 * v.pow(1.6), n, 0.0, FSD))       // the air the beater pushes through the shell
        val r = (0.12 + tr.normal(0.0, 0.05)).coerceIn(0.0, 0.95)
        val ang = tr.uniform(0.0, PI)
        val ir = peakNorm(membraneIr(f1, n, t1, 1.25, r, ang, sag = 0.07 * v * v, sagTau = 0.04 + 0.02 * tr.random()))
        val out = conv(force, ir, n)
        val buzz = m.getValue("BUZZ")
        if (buzz > 0) {
            val head = lp(out, 300.0, FSD)
            val hk = v / (peakOf(head) + 1e-12)
            add(out, wires(DoubleArray(n) { head[it] * hk }, 0.8, tr, n), 0.03 * buzz * WIRE_GAIN * peakOf(out))
            val tom = peakNorm(membraneIr(104.0, n, 0.9, 1.1, 0.3, 0.7))
            add(out, peakNorm(conv(lp(out, 200.0, FSD), tom, n)), 0.10 * buzz * v)
        }
        add(out, hp(hannForce(0.5e-3, 0.06 * v * v, n, 0.0, FSD), 1500.0, FSD))
        return out
    }

    /**
     * The snare: calf head plus wires. The wires' drive is the head's low motion against one hard stroke on this
     * same head, so a roll's early strokes engage them as a single stroke of that strength would, compressed
     * (power 0.6) so ghost notes still buzz, gently.
     */
    private fun snareCore(m: Map<String, Double>, v: Double, tr: Rng, n: Int, force: DoubleArray, rBase: Double, wireGain: Double = 1.0): DoubleArray {
        val f1 = 285.0 * semis(m.getValue("TUNE"), 5.0)
        val t1 = lin(m["DAMP"] ?: .3, 0.42, 0.07)
        val r = (rBase + tr.normal(0.0, 0.07)).coerceIn(0.0, 0.95)
        val ir = peakNorm(membraneIr(f1, n, t1, 1.15, r, tr.uniform(0.0, PI), sag = 0.03 * v * v, sagTau = 0.04 + 0.02 * tr.random(), scatter = tr))
        val head = conv(force, ir, n)
        val k = (0.08 * FSD).roundToInt()
        val refFull = conv(hannForce(0.28e-3, 1.0, k, 0.0, FSD), ir.copyOf(k), k)
        val refPeak = peakOf(refFull)
        val low = lp(head, 900.0, FSD)
        val lr = peakOf(lp(refFull, 900.0, FSD)) + 1e-12
        val hd = DoubleArray(n) { val x = low[it] / lr; sign(x) * abs(x).pow(0.6) }
        add(head, wires(hd, m.getValue("BUZZ"), tr, n), wireGain * WIRE_GAIN * refPeak)
        return head
    }

    private fun snare(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val force = stick(v, tr, n)
        return snareCore(m, v, tr, n, force, 0.28).also { add(it, hp(force, 2500.0, FSD), 0.18) }
    }

    private fun rimshot(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val force = stick(v, tr, n, widthHard = 0.2e-3)
        val hoop = modalIr(doubleArrayOf(960.0, 2210.0, 3720.0, 5300.0), doubleArrayOf(1.0, .7, .5, .3), doubleArrayOf(.09, .06, .045, .03), n, FSD)
        val rim = conv(force, peakNorm(hoop), n)
        val out = snareCore(m, v, tr, n, DoubleArray(n) { force[it] * 1.2 }, 0.85)
        add(out, rim, 0.9); add(out, hp(force, 2000.0, FSD), 0.35)
        return out
    }

    /** The stick across the head, the butt clicking the hoop: wood and steel, the head and a whisper of wires. */
    private fun crossStick(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val force = stick(v, tr, n, widthHard = 0.22e-3)
        val s = semis(m.getValue("TUNE"), 5.0)
        val damp = m.getValue("DAMP")
        val ws = s * tr.uniform(.97, 1.03)
        val wood = modalIr(doubleArrayOf(505 * ws, 1390 * ws, 2730 * ws, 4500 * ws),
            doubleArrayOf(1.0, .8, .45, .2).map { it * tr.uniform(0.5, 1.5) }.toDoubleArray(),
            doubleArrayOf(.05, .035, .02, .012).map { it * lin(damp, 1.4, 0.55) }.toDoubleArray(), n, FSD)
        val hoop = modalIr(doubleArrayOf(1180 * s, 1960 * s, 2840 * s, 3950 * s),
            doubleArrayOf(.6, 1.0, .5, .3).map { it * tr.uniform(0.4, 1.6) }.toDoubleArray(),
            doubleArrayOf(.07, .06, .04, .03).map { it * lin(damp, 2.0, 0.3) }.toDoubleArray(), n, FSD)
        val wn = peakNorm(wood); val hn = peakNorm(hoop)
        val knock = conv(force, DoubleArray(n) { wn[it] + 0.7 * hn[it] }, n)
        add(knock, snareCore(m, v, tr, n, DoubleArray(n) { force[it] * 0.25 }, 0.6, wireGain = 0.4), 0.35)
        return knock
    }

    private fun brushTapVoice(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val force = brushTap(v, tr, n)
        val sl = ((0.025 + 0.02 * (1 - v)) * FSD).roundToInt()
        val scrape = DoubleArray(n)
        for (i in 0 until sl) { val l = 1.0 - i.toDouble() / (sl - 1); scrape[i] = tr.normal() * l * l }
        val out = snareCore(m, v, tr, n, force, 0.3, wireGain = 0.8)
        add(out, band(scrape, 2500.0, 11000.0, FSD), 0.05 * v)
        return out
    }

    /**
     * A brush circling on the snare (round 0c: fitted). Band noise along the recorded stirs' spectrum at this
     * velocity, riding the circle's speed; each band moves by its measured dB per dB of level, and the level wobbles
     * and sways by the recordings' own amounts. TUNE slides the spectrum, BUZZ tilts the wire band, SPEED is the
     * circle. Plus the owner's air (round 0d, by ear).
     */
    private fun brushSweep(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val k = n / 4 + 1
        val dur = sweepSeconds(m.getValue("SPEED")) * tr.uniform(0.97, 1.0)
        val ph = tr.uniform(0.0, 1.0)
        val speed = DoubleArray(k) { val t = it / RATED; if (t > dur) 0.0 else { val x = (t / dur).coerceIn(0.0, 1.0); sin(PI * x).pow(1.3) * (0.75 + 0.25 * cos(2 * PI * 2 * x + ph)) } }
        val p = stirProfile(v)
        val slow = drift(3.0, k, tr)
        val fast = drift(20.0, k, tr)
        val level = DoubleArray(k) { 20 * kotlin.math.log10(max(speed[it], 1e-4)) + p.sway * slow[it] + p.wobble * fast[it] }
        val peakAt = level.max()
        val spec = slideBands(p.spectrum, (m.getValue("TUNE") - 0.5) * 2 * 5 / 4)
        for ((i, fc) in brushFit.bands.withIndex()) spec[i] += sweepAirDb(fc)
        return toInternal(stirNoise(spec, p.follow, level, peakAt, m.getValue("BUZZ"), tr, k), n)
    }

    private fun tom(m: Map<String, Double>, v: Double, tr: Rng, n: Int, fBase: Double, implement: DoubleArray, rBase: Double, t1Max: Double, bend: DoubleArray? = null): DoubleArray {
        val f1 = fBase * semis(m.getValue("TUNE"), 6.0)
        val t1 = lin(m["DAMP"] ?: .3, t1Max, 0.15)
        val r = (rBase + tr.normal(0.0, 0.05)).coerceIn(0.0, 0.95)
        val ir = if (bend != null) membraneIr(f1, n, t1, 1.05, r, tr.uniform(0.0, PI), bend = bend)
        else membraneIr(f1, n, t1, 1.05, r, tr.uniform(0.0, PI), sag = 0.045 * v * v, sagTau = 0.05, scatter = tr)
        val head = conv(implement, peakNorm(ir), n)
        val sym = m["BUZZ"] ?: .5
        if (sym > 0) {   // the snare wires across the kit answer the tom
            val hd = peakNorm(lp(head, 700.0, FSD))
            for (i in hd.indices) hd[i] *= v * 0.7
            add(head, wires(hd, 0.75, tr, n), 0.06 * sym * WIRE_GAIN * peakOf(head))
        }
        return head
    }

    /** The floor tom, then an elbow leaning into the head: the pitch climbs (BEND, 0.4 to 5 semitones) and the ring shortens. */
    private fun elbowTom(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val start = 0.06 + tr.uniform(0.0, 0.02)
        val semi = lin(m.getValue("BEND"), 0.4, 5.0)
        val sm = DoubleArray(n) { val x = ((it / FSD - start) / 0.16).coerceIn(0.0, 1.0); x * x * (3 - 2 * x) }
        val curve = DoubleArray(n) { 2.0.pow(semi * sm[it] / 12) }
        val f = stick(v, tr, n)
        val body = tom(m + ("DAMP" to .25), v, tr, n, 104.0, f, 0.3, 1.25, bend = curve)
        for (i in 0 until n) body[i] *= 1 - 0.35 * sm[i]
        add(body, hp(f, 2500.0, FSD), 0.1)
        return body
    }

    /** Wire brush on the flat ride: the brush's spray of wires through the fitted modes, no stick, and the wires' scrape. */
    private fun brushRide(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val sound = peakNorm(driven("flatride", m, v, tr, n, brushTap(v, tr, n)).first)
        val kk = (0.03 * FSD).roundToInt()
        val scrape = DoubleArray(n)
        for (i in 0 until kk) { val l = 1.0 - i.toDouble() / (kk - 1); scrape[i] = tr.normal() * l * l }
        add(sound, peakNorm(band(scrape, 3000.0, 12000.0, FSD)), 0.08 * v)
        val drive = envFollow(lp(sound, 2500.0, FSD), 6.0, FSD)
        add(sound, rivets(drive, m.getValue("BUZZ"), v, tr, n), 1.3 * RIVET_GAIN)
        return sound
    }

    /** BUZZ on the hats: the plates looser than recorded, chattering where they touch (0 is as recorded). */
    private fun hatChatter(sound: DoubleArray, m: Map<String, Double>, v: Double, tr: Rng, n: Int, rate: Double): DoubleArray {
        val count = round(lin(m.getValue("BUZZ"), 0.0, 4.0)).toInt()
        if (count == 0) return sound
        val d = envFollow(lp(sound, 4000.0, FSD), 3.0, FSD)
        val k = (0.5 + 0.5 * v) / (peakOf(d) + 1e-12)
        for (i in d.indices) d[i] *= k
        val chatter = rattlers(d, count, 0.05, 0.3, rate, listOf(7400.0 to .004, 10200.0 to .003), tr, n, 0.8)
        add(sound, chatter, 1.2 * RIVET_GAIN * peakOf(sound))
        return sound
    }

    /**
     * A bass bow across the flat ride's edge: stick-slip locks to the strongest fitted mode between 900 Hz and
     * 2 kHz (round 0e) and the rest bloom around it. A sawtooth-like slip train with jitter (an analogy of friction).
     * PRESSURE: more bite, more harmonics, more scratch.
     */
    private fun bowedCymbal(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val md = cymbalFit.voices.getValue("flatride").modes
        val lock = md.filter { it[0] > 900 && it[0] < 2000 }.maxBy { it[1] }[0]
        val fLock = lock * semis(m.getValue("TUNE"), 3.0) * tr.uniform(0.998, 1.002)
        val bite = lin(m.getValue("PRESSURE"), 0.3, 1.0)
        val wobPh = tr.uniform(0.0, 2 * PI)
        val saw = DoubleArray(n); val swell = DoubleArray(n); val wob = DoubleArray(n)
        var jit = 0.0
        for (i in 0 until n) {
            val t = i / FSD
            jit += tr.normal() / FSD * 0.8
            val ph = 2 * PI * fLock * (t + jit * 0.001)
            saw[i] = sin(ph) + bite * 0.5 * sin(2 * ph) + bite * 0.33 * sin(3 * ph) + bite * 0.25 * sin(4 * ph + 0.3) +
                bite * 0.18 * sin(5 * ph + 0.7) + bite * 0.12 * sin(6 * ph + 1.1)
            swell[i] = (t / (0.5 - 0.25 * v)).coerceIn(0.0, 1.0).pow(1.5) * ((2.3 - t) / 0.35).coerceIn(0.0, 1.0)
            wob[i] = 1 + 0.15 * sin(2 * PI * 4.3 * t + wobPh)
        }
        val scratch = band(tr.normals(n), 2000.0, 10000.0, FSD)
        val drive = DoubleArray(n) { (saw[it] * 0.004 + scratch[it] * bite * 0.06 * 0.01) * swell[it] * wob[it] * v }
        val sound = driven("flatride", m + ("DAMP" to .3), v, tr, n, drive).first
        val direct = lp(saw, 10000.0, FSD)
        val k = 0.25 * peakOf(sound)
        for (i in 0 until n) sound[i] += direct[i] * swell[i] * wob[i] * k
        val env = envFollow(lp(sound, 2500.0, FSD), 6.0, FSD)
        add(sound, rivets(env, m.getValue("BUZZ"), v * 0.6, tr, n), 0.7 * RIVET_GAIN * peakOf(sound))
        return sound
    }

    /** The riser: felt mallets rolling on the flat ride, nothing to a full wash over 2.6 s; the top follows intensity squared (an analogy). */
    private fun cymbalSwell(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val tEnd = 2.6
        val rate0 = lin(m.getValue("SPEED"), 9.0, 16.0); val rate1 = lin(m.getValue("SPEED"), 15.0, 26.0)
        val force = DoubleArray(n)
        var tt = 0.0; var k = 0
        while (tt < tEnd) {
            val x = tt / tEnd
            val a = (0.12 + 0.88 * x.pow(1.6)) * v
            addMallet(force, max(0.08, a), tr, tt, 2.8e-3, if (k % 2 == 1) 1.0 else 0.85)
            tt += 1.0 / lin(x, rate0, rate1) * tr.uniform(0.9, 1.1)
            k++
        }
        val intensity = envFollow(force, 120.0, FSD)
        val ik = v / (peakOf(intensity) + 1e-12)
        val dry = m + ("DAMP" to .3)
        val (low, top) = driven("flatride", dry, v, tr, n, force, splitHz = 6000.0)
        val top2 = driven("flatride", dry, v, tr, n, DoubleArray(n) { val i = intensity[it] * ik; force[it] * i * i }, splitHz = 6000.0).second!!
        val body = DoubleArray(n) { low[it] + 0.4 * top!![it] + 1.6 * top2[it] }
        val env = envFollow(lp(body, 2500.0, FSD), 6.0, FSD)
        add(body, rivets(env, m.getValue("BUZZ"), v, tr, n), 0.9 * RIVET_GAIN * peakOf(body))
        return body
    }

    /** Percussion gate: the press roll. Buzz strokes of three to six bounces, the hands faster as it crescendos into an accent at 2.3 s. */
    private fun roll(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val tEnd = 2.3
        val rate0 = lin(m.getValue("SPEED"), 9.0, 14.0); val rate1 = lin(m.getValue("SPEED"), 14.0, 22.0)
        val force = DoubleArray(n)
        var tt = 0.02; var k = 0
        while (tt < tEnd - 0.04) {
            val x = tt / tEnd
            val a = (0.15 + 0.85 * x.pow(1.8)) * v * (if (k % 2 == 0) 1.0 else 0.88)
            val bounces = tr.int(3, 7)
            for (b in 0 until bounces) { val va = max(0.08, a * 0.62.pow(b)); addStick(force, va, tr, tt + b * tr.uniform(0.006, 0.009)) }
            tt += 1.0 / lin(x, rate0, rate1) * tr.uniform(0.92, 1.08)
            k++
        }
        addStick(force, v, tr, tEnd, k = 1.6)
        return snareCore(m, v, tr, n, force, 0.35).also { add(it, hp(force, 2500.0, FSD), 0.12) }
    }

    /**
     * Percussion gate: the brush roll (round 0c: fitted). Band noise along the recorded flutters, crescendoing over
     * 2.3 s with a stroke-rate ripple (the two hands, rate set by SPEED), into a brush accent on the snare.
     */
    private fun brushRoll(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val k = n / 4 + 1
        val tEnd = 2.3
        val f = brushFit.flutter
        val soft = stirProfile(0.3).spectrum
        val w = ((1.0 - v) / 0.7).coerceIn(0.0, 1.0)
        val spectrum = DoubleArray(f.spectrum.size) { (1 - 0.5 * w) * f.spectrum[it] + 0.5 * w * soft[it] }
        val r0 = lin(m.getValue("SPEED"), 9.0, 14.0); val r1 = lin(m.getValue("SPEED"), 14.0, 22.0)
        val ph0 = tr.uniform(0.0, 2 * PI)
        val slow = drift(3.0, k, tr)
        val fast = drift(20.0, k, tr)
        val level = DoubleArray(k)
        var cum = 0.0
        for (i in 0 until k) {
            val t = i / RATED
            val x = (t / tEnd).coerceIn(0.0, 1.0)
            cum += r0 + (r1 - r0) * x
            val ripple = 1.5 * sin(2 * PI * cum / RATED / 2 + ph0)
            level[i] = if (t > tEnd) -120.0 else 20 * kotlin.math.log10(0.06 + 0.94 * x.pow(1.8)) + ripple + f.sway * slow[i] + f.wobble * fast[i]
        }
        var peakAt = Double.NEGATIVE_INFINITY
        for (i in 0 until k) if (i / RATED <= tEnd && level[i] > peakAt) peakAt = level[i]
        val spec = slideBands(spectrum, (m.getValue("TUNE") - 0.5) * 2 * 5 / 4)
        val roll = peakNorm(toInternal(stirNoise(spec, f.follow, level, peakAt, m.getValue("BUZZ"), tr, k), n))
        val accent = peakNorm(snareCore(m + ("DAMP" to .3), v, tr, n, DoubleArray(n).also { EmberDsp.add(it, brushTap(v, tr, n, at = tEnd), 2.0) }, 0.35))
        add(roll, accent, 0.9)
        return roll
    }

    /** A table of listeners, each with their own hands, timing and strength. Snaps: a bright pop. Claps: a lower, longer pocket, sometimes doubled. */
    private fun crowd(m: Map<String, Double>, v: Double, tr: Rng, n: Int, clap: Boolean): DoubleArray {
        val people = 1 + round(m.getValue("CROWD") * 5).toInt()
        val out = DoubleArray(n)
        val s = semis(m.getValue("TUNE"), 5.0)
        val joining = 1 + round((people - 1) * (0.25 + 0.75 * v)).toInt()
        for (p in 0 until joining) {
            val pr = Rng(Dsp.seedFor("EMBER-crowd", clap, p))   // the same people every take
            val at = 0.004 + if (p == 0) 0.0 else abs(tr.normal(0.0, 0.012)) + pr.uniform(0.0, 0.01)
            val a = (if (p == 0) 1.0 else pr.uniform(0.35, 0.8)) * v.pow(if (p > 0) 1.2 else 1.0)
            val cav: Double; val width: Int; val q: Double
            if (clap) { cav = pr.uniform(800.0, 1500.0) * s; width = (pr.uniform(0.012, 0.022) * FSD).roundToInt(); q = 3.0 }
            else { cav = pr.uniform(1700.0, 2700.0) * s; width = (pr.uniform(0.006, 0.011) * FSD).roundToInt(); q = 5.0 }
            val x = DoubleArray(n)
            val i0 = (at * FSD).toInt()
            for (j in 0 until width) if (i0 + j < n) x[i0 + j] = tr.normal() * exp(-j / (width / 3.0))
            val body = EmberDsp.iirPeak(cav, q, FSD).run(x)
            var crack = hp(hannForce((if (clap) 0.25 else 0.12) * 1e-3, 1.0, n, at, FSD), 1800.0, FSD)
            for (j in crack.indices) crack[j] *= 0.5 + 0.5 * v
            if (clap && pr.random() < 0.4) {   // the flat double clap
                val sh = (pr.uniform(0.004, 0.009) * FSD).roundToInt()
                val c0 = crack
                crack = DoubleArray(n) { c0[it] + 0.5 * c0[(it - sh + n) % n] }
            }
            val bn = peakNorm(body); val cn = peakNorm(crack); val xn = peakNorm(hp(x, 3000.0, FSD))
            val cv = 0.8 * v.pow(1.5)
            for (j in 0 until n) out[j] += a * (bn[j] + cv * cn[j] + 0.15 * xn[j])
        }
        return out
    }

    /** A calf-headed tambourine struck on the palm: the head thumps and four to ten jingle pairs fly and bounce. */
    private fun tambourine(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val s = semis(m.getValue("TUNE"), 4.0)
        val damp = m.getValue("DAMP")
        val force = hand(v, tr, n, cupped = 0.4)
        val head = conv(force, peakNorm(membraneIr(310 * s, n, lin(damp, .12, .04), 1.2, 0.5, 0.3)), n)
        val tau = lin(damp, 0.11, 0.05)
        val drive = DoubleArray(n) { exp(-it / FSD / tau) * v }
        val pairs = 4 + round(m.getValue("BUZZ") * 6).toInt()
        val jingles = rattlers(drive, pairs, 0.02, 0.35, 260 + 300 * v, listOf(5600 * s to .035, 8100 * s to .025, 11800 * s to .018, 4300 * s to .05), tr, n, 0.3)
        val out = peakNorm(jingles)
        add(out, peakNorm(head), 0.4)
        return out
    }

    /** A rawhide conga, open tone off the edge; SLAP cups the hand for a cracking slap. */
    private fun conga(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val f1 = 196.0 * semis(m.getValue("TUNE"), 6.0)
        val slap = m.getValue("SLAP")
        val force = hand(v, tr, n, cupped = slap)
        val t1 = lin(m.getValue("DAMP"), 0.55, 0.12) * (1 - 0.5 * slap)
        val ir = membraneIr(f1, n, t1, 1.35, 0.78 + tr.normal(0.0, .03), tr.uniform(0.0, PI), sag = 0.02 * v * v, reso = false)
        val out = conv(force, peakNorm(ir), n)
        add(out, hp(force, 2200.0, FSD), 0.5 * slap)
        add(out, conv(force, peakNorm(modalIr(doubleArrayOf(410 * semis(m.getValue("TUNE"), 6.0)), doubleArrayOf(1.0), doubleArrayOf(0.06), n, FSD)), n), 0.2)
        return out
    }

    /** Two hickory sticks clicked together (the count-in): free-free beams near 330 and 345 Hz. A harder click lifts the upper overtones. */
    private fun sticks(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val s = semis(m.getValue("TUNE"), 5.0)
        val force = stick(v, tr, n, widthHard = 0.12e-3)
        val damp = lin(m.getValue("DAMP"), 1.4, 0.4)
        val out = DoubleArray(n)
        for ((base, g) in listOf(330.0 to 1.0, 345.0 to 0.8)) {
            val f0 = base * s * tr.uniform(.985, 1.015)
            val ratios = doubleArrayOf(1.0, 2.756, 5.404, 8.933)
            val w = doubleArrayOf(.5, 1.0, .7 * v.pow(0.8), .4 * v.pow(1.5))
            for (i in w.indices) w[i] *= tr.uniform(0.35, 1.6)
            val ir = modalIr(DoubleArray(4) { ratios[it] * f0 }, w, doubleArrayOf(.09, .05, .03, .02).map { it * damp }.toDoubleArray(), n, FSD)
            add(out, conv(force, peakNorm(ir), n), g)
        }
        add(out, hp(force, 3000.0, FSD), 0.4)
        return out
    }
}
