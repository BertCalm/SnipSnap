package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.CalabashDsp.RATE
import com.snipsnap.synth.CalabashDsp.Rng
import com.snipsnap.synth.CalabashDsp.TAU
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * CALABASH's fitted voices: recorded instruments' own resonances, struck again (docs/CALABASH.md, "Fitted, not
 * guessed"). Two families, both measured, neither shipping a recording:
 *
 * - **The hand drums, bells, claves, shaker and claps** (`/calabash/hands.json`, fitted by
 *   `prototype/calabash/fit_calabash.py` to the Versilian Community Sample Library, CC0): up to 160 modes, each with
 *   two decay slopes and a soft-minus-hard tilt, the head's pitch glide, and the residual (what the modes leave out)
 *   as third-octave band envelopes. [strike] is `prototype/calabash/fitted.py`'s `strike`.
 * - **The hats** (`/calabash/hats.json`, EMBER's fit of Virtuosity Drums, CC0, pinned at 967f53f7f) and **the ride and
 *   crash** (`/breaker/cymbals.json`, BREAKER's approved fit): [struck] and [envelopeHat] are EMBER's prototype's
 *   `struck` and `envelope_hat`. BREAKER's cymbals carry no residual (BREAKER renders modes and stick alone, as
 *   approved); the prototype gave them a -200 dB residual, which this leaves out.
 *
 * Everything here runs at 44.1 kHz, as the prototype did: a sum of sines is band-limited by construction, and
 * [CalabashDsp.upsample4] carries it into the 4x chain.
 */
internal object CalabashFits {

    // ------------------------------------------------------------------ the hand-drum fits

    class Hand(
        val hz: DoubleArray, val amp: DoubleArray, val early: DoubleArray, val late: DoubleArray, val tilt: DoubleArray,
        val glide: Double, val glideTau: Double, val residual: Array<DoubleArray>, val residualSoft: Array<DoubleArray>,
    ) {
        /** The strongest mode's frequency (rank 0), the second's (rank 1)... */
        fun strongest(rank: Int = 0): Double = hz[amp.indices.sortedByDescending { amp[it] }[rank]]
    }

    private fun doubles(v: JsonValue) = v.arr().map { it.num() }.toDoubleArray()
    private fun rows(v: JsonValue) = v.arr().map { doubles(it) }.toTypedArray()

    private val handRoot: Map<String, JsonValue> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/calabash/hands.json")) { "Missing CALABASH hand fits" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parse(text).obj()
    }
    val handBands: DoubleArray by lazy { doubles(handRoot.getValue("bands")) }
    val handTimes: DoubleArray by lazy { doubles(handRoot.getValue("times")) }
    /** The fits' early-decay window, seconds: each mode keeps one slope before it and one after. */
    val handEarly: Double by lazy { handRoot.getValue("early").num() }

    private val hands = java.util.concurrent.ConcurrentHashMap<String, Hand>()

    fun hand(name: String): Hand = hands.getOrPut(name) {
        val v = handRoot.getValue("voices").obj().getValue(name).obj()
        val modes = v.getValue("modes").arr().map { doubles(it) }
        val glide = doubles(v.getValue("glide"))
        Hand(
            hz = DoubleArray(modes.size) { modes[it][0] }, amp = DoubleArray(modes.size) { modes[it][1] },
            early = DoubleArray(modes.size) { modes[it][2] }, late = DoubleArray(modes.size) { modes[it][3] },
            tilt = DoubleArray(modes.size) { modes[it][4] }, glide = glide[0], glideTau = glide[1],
            residual = rows(v.getValue("residual")), residualSoft = rows(v.getValue("residualSoft")),
        )
    }

    fun handNames(): Set<String> = handRoot.getValue("voices").obj().keys

    /** fitted.py's `soft_weight`: 0 at a full hit, 1 at the soft recordings (velocity .3), up to 1.2 below. */
    fun handSoftWeight(v: Double) = ((1.0 - v) / 0.7).coerceIn(0.0, 1.2)

    /** fitted.py's `damp_factor`: DAMP .3 is the drum as recorded, 1 three times faster, 0 0.64x. */
    fun dampFactor(damp: Double) = 2.0.pow((damp - 0.3) / 0.7 * 1.6)

    /**
     * fitted.py's `strike`: fitted voice [name] struck once at velocity [v] with take generator [tr], [seconds] long,
     * at 44.1 kHz, absolute units. Returns the modes and the residual apart, so a stroke can weigh them.
     */
    fun strike(
        name: String, v: Double, tr: Rng, seconds: Double, tuneRatio: Double = 1.0, damp: Double = 0.3,
        modeGain: ((Double) -> Double)? = null, slopeMul: ((Double) -> Double)? = null, glideMul: Double = 1.0,
        residualDb: Double = 0.0, attackDb: Double = 0.0, residUntil: Double? = null, contactMs: Double? = null,
    ): Pair<DoubleArray, DoubleArray> {
        val d = hand(name)
        val n = Math.rint(seconds * RATE).toInt()
        val w = handSoftWeight(v)
        val modes = DoubleArray(n)
        val k = d.hz.size
        if (k > 0) {
            val order = d.amp.indices.sortedByDescending { d.amp[it] }
            val lowRank = BooleanArray(k).also { for (i in order.take(3)) it[i] = true }
            val fRef = d.hz[order[0]]
            val hz = DoubleArray(k) { d.hz[it] * tuneRatio * (1 + tr.normal(0.0, 0.0015)) }
            val gain = DoubleArray(k) { CalabashDsp.db(d.tilt[it] * w + tr.normal() * (if (lowRank[it]) 0.5 else 1.5)) }
            val sign = DoubleArray(k) { if (hz[it] > 1.8 * fRef * tuneRatio && tr.random() < 0.25) -1.0 else 1.0 }
            if (modeGain != null) for (i in 0 until k) gain[i] *= modeGain(hz[i])
            val df = dampFactor(damp)
            val early = DoubleArray(k) { d.early[it] * df * (slopeMul?.invoke(hz[it]) ?: 1.0) }
            val late = DoubleArray(k) { d.late[it] * df * (slopeMul?.invoke(hz[it]) ?: 1.0) }
            val g = d.glide * glideMul * v * v * (1 + tr.normal(0.0, 0.15))
            val tau = d.glideTau
            val e = handEarly
            // The phase time of a pitch that starts sharp and settles.
            val warp = DoubleArray(n) { val t = it.toDouble() / RATE; t + g * tau * (1 - exp(-t / tau)) }
            val dbToLn = ln(10.0) / 20
            // The first sample at or after the early window: t = s / RATE >= e.
            var sE = 0
            while (sE.toDouble() / RATE < e) sE++
            for (i in 0 until k) {
                if (hz[i] >= 0.47 * RATE) continue
                val atE = early[i] * e
                val t90 = if (atE <= -90) 90 / -early[i] else e + (90 + atE) / -late[i]
                val lim = min(n, (t90 * RATE).toInt() + 1)
                val a = sign[i] * d.amp[i] * gain[i]
                val wf = TAU * hz[i]
                // 10^(dbt / 20) as two geometric runs, the second restarted exactly at the early window's end.
                val r1 = exp(early[i] / RATE * dbToLn)
                val r2 = exp(late[i] / RATE * dbToLn)
                var env = a
                for (s in 0 until lim) {
                    if (s == sE) env = a * exp((early[i] * e + late[i] * (s.toDouble() / RATE - e)) * dbToLn)
                    modes[s] += env * sin(wf * warp[s])
                    env *= if (s + 1 < sE) r1 else r2
                }
            }
            // The contact: the hand or stick stays on a moment, so the modes grow in over it. Softer is longer.
            val c = (contactMs ?: 0.25) * 1e-3 * v.pow(-0.6)
            for (s in 0 until n) modes[s] *= 1 - exp(-(s.toDouble() / RATE) / c)
        }
        val res = residual(d, w, tr, n, tuneRatio, damp, residualDb, attackDb, residUntil)
        return modes to res
    }

    fun fitted(name: String, v: Double, tr: Rng, seconds: Double, tuneRatio: Double = 1.0, damp: Double = 0.3,
               contactMs: Double? = null): DoubleArray {
        val (a, b) = strike(name, v, tr, seconds, tuneRatio, damp, contactMs = contactMs)
        return DoubleArray(a.size) { a[it] + b[it] }
    }

    /** fitted.py's `residual_noise`: band noise along what the modes leave out, hard to soft, slid by TUNE. */
    private fun residual(d: Hand, w: Double, tr: Rng, n: Int, tuneRatio: Double, damp: Double, residualDb: Double,
                         attackDb: Double, residUntil: Double?): DoubleArray {
        val bands = handBands
        val times = handTimes
        val ws = min(w, 1.0)
        val shift = 3 * log2(tuneRatio)
        val cols = times.size
        // Mix hard and soft, then slide each time column across the bands.
        val mixed = Array(bands.size) { i -> DoubleArray(cols) { j -> (1 - ws) * d.residual[i][j] + ws * d.residualSoft[i][j] } }
        val lev = Array(bands.size) { i -> DoubleArray(cols) { j -> CalabashDsp.interpIndex(i - shift, DoubleArray(bands.size) { mixed[it][j] }) } }
        val df = dampFactor(damp)
        val out = DoubleArray(n)
        val dbToLn = ln(10.0) / 20
        for (i in bands.indices) {
            val fc = bands[i]
            if (fc * 2.0.pow(1.0 / 6) >= 0.49 * RATE || lev[i].max() < -150) continue
            val lift0 = (if (fc >= 400) attackDb else 0.0) + residualDb
            val raw = CalabashDsp.thirdOctave(fc).run(tr.normals(n + 2000))
            val x = raw.copyOfRange(2000, raw.size)
            val norm = CalabashDsp.rms(x) + 1e-20
            val g = CalabashDsp.gainCurve(n, df / RATE, times, lev[i], lev[i].last() - 40)
            val early = exp(lift0 * dbToLn) / norm
            val later = exp(residualDb * dbToLn) / norm
            for (s in 0 until n) out[s] += x[s] * g[s] * (if (s.toDouble() / RATE < 0.015) early else later)
        }
        if (residUntil != null) for (s in 0 until n) out[s] *= ((residUntil - s.toDouble() / RATE) / 0.02).coerceIn(0.0, 1.0)
        return out
    }

    // ------------------------------------------------------------------ the prototype's fixed draws

    /**
     * The three draws the prototype makes once rather than per take (`/calabash/fixed.json`, `room_noise.f32`,
     * written by `prototype/calabash/export_fixed.py`): each colours every hit of a voice or a kit the same way, so the
     * port uses the approved values themselves rather than drawing its own.
     */
    private val fixedRoot by lazy { jsonAt("/calabash/fixed.json") }

    class Doublets(val split: DoubleArray, val share: DoubleArray, val slope: DoubleArray)

    private val doublets: Map<String, Doublets> by lazy {
        fixedRoot.getValue("doublets").obj().mapValues { (_, v) ->
            val o = v.obj()
            Doublets(doubles(o.getValue("split")), doubles(o.getValue("share")), doubles(o.getValue("slope")))
        }
    }

    /** One snapping person: when (after the first), how loud (after the first), the cavity, the burst's length. */
    class Snapper(val late: Double, val gain: Double, val cavity: Double, val width: Double)

    val crowd: List<Snapper> by lazy {
        fixedRoot.getValue("crowd").arr().map { v ->
            val o = v.obj()
            Snapper(o["late"]?.num() ?: 0.0, o["gain"]?.num() ?: 1.0, o.getValue("cavity").num(), o.getValue("width").num())
        }
    }

    /** The room's noise: the prototype generator's standard normals, its lows from the start, its highs after them. */
    val roomNoise: DoubleArray by lazy {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/calabash/room_noise.f32")) { "Missing CALABASH room noise" }
            .use { it.readBytes() }
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val count = fixedRoot.getValue("roomNoiseValues").int()
        check(buf.remaining() == count) { "CALABASH room noise holds ${buf.remaining()} values, expected $count" }
        DoubleArray(count) { buf.get(it).toDouble() }
    }

    // ------------------------------------------------------------------ the cymbal fits (EMBER's and BREAKER's)

    class Cymbal(
        val hz: DoubleArray, val amp: DoubleArray, val slope: DoubleArray, val tilt: DoubleArray,
        val stick: DoubleArray, val stickSoft: DoubleArray,
        val times: DoubleArray?, val residual: Array<DoubleArray>?, val residualSoft: Array<DoubleArray>?,
    )

    class EnvHat(val times: DoubleArray, val hard: Array<DoubleArray>, val soft: Array<DoubleArray>)

    private fun jsonAt(path: String): Map<String, JsonValue> {
        val text = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing $path" }.use { it.readBytes().toString(Charsets.UTF_8) }
        return Json.parse(text).obj()
    }

    private val hatRoot by lazy { jsonAt("/calabash/hats.json") }
    private val breakerRoot by lazy { jsonAt("/breaker/cymbals.json") }
    /** The cymbal fits' third-octave bands (BREAKER's: 125 Hz up, 22 of them; the same in both files). */
    val cymbalBands: DoubleArray by lazy { doubles(hatRoot.getValue("bands")) }

    private fun cymbalFrom(v: Map<String, JsonValue>, withResidual: Boolean): Cymbal {
        val modes = v.getValue("modes").arr().map { doubles(it) }
        return Cymbal(
            hz = DoubleArray(modes.size) { modes[it][0] }, amp = DoubleArray(modes.size) { modes[it][1] },
            slope = DoubleArray(modes.size) { modes[it][2] }, tilt = DoubleArray(modes.size) { modes[it][3] },
            stick = doubles(v.getValue("stick")), stickSoft = doubles(v.getValue("stickSoft")),
            times = if (withResidual) doubles(v.getValue("times")) else null,
            residual = if (withResidual) rows(v.getValue("residual")) else null,
            residualSoft = if (withResidual) rows(v.getValue("residualSoft")) else null,
        )
    }

    private val cymbals: Map<String, Cymbal> by lazy {
        check(doubles(breakerRoot.getValue("bands")).map { Math.round(it * 1000) } == cymbalBands.map { Math.round(it * 1000) }) {
            "BREAKER's and EMBER's cymbal bands differ"
        }
        mapOf(
            "hat_open" to cymbalFrom(hatRoot.getValue("voices").obj().getValue("hat_open").obj(), withResidual = true),
            "breaker_ride" to cymbalFrom(breakerRoot.getValue("voices").obj().getValue("ride").obj(), withResidual = false),
            "breaker_crash" to cymbalFrom(breakerRoot.getValue("voices").obj().getValue("crash").obj(), withResidual = false),
        )
    }

    private val envHats: Map<String, EnvHat> by lazy {
        listOf("hat_closed", "hat_pedal").associateWith { k ->
            val v = hatRoot.getValue("voices").obj().getValue(k).obj()
            EnvHat(doubles(v.getValue("times")), rows(v.getValue("hard")), rows(v.getValue("soft")))
        }
    }

    /** EMBER's `damp_factor` is fitted.py's [dampFactor], the same law. EMBER's `soft_weight`: 0 at a full hit, 1 at the recordings' soft layer (velocity .35), up to 1.3. */
    fun cymbalSoftWeight(v: Double) = ((1.0 - v) / 0.65).coerceIn(0.0, 1.3)

    /**
     * EMBER's `fitted_modes`: every fitted mode at its frequency (TUNE moves it 3 semitones), amplitude (plus the
     * band's soft tilt) and decay (DAMP), each split into the beating pair a hammered plate has (seeded by the
     * cymbal, so every take is the same cymbal); a take is a fresh strike (up to 0.3 Hz detune, a fresh phase, 1.6 dB
     * rms scatter). Unit rms over the first 300 ms, at 44.1 kHz.
     */
    fun fittedModes(kind: String, tune: Double, damp: Double, v: Double, tr: Rng, seconds: Double): DoubleArray {
        val m = cymbals.getValue(kind)
        val k = m.hz.size
        val ratio = CalabashDsp.semis(tune, 3.0)
        val sw = cymbalSoftWeight(v)
        val hz0 = DoubleArray(k) { m.hz[it] * ratio + tr.uniform(-0.3, 0.3) }
        val amp0 = DoubleArray(k) { m.amp[it] * CalabashDsp.db(m.tilt[it] * sw + tr.normal(0.0, 1.6)) }
        val df = dampFactor(damp)
        val slope0 = DoubleArray(k) { min(m.slope[it] * df, -1.0) }
        // The cymbal's own beating pairs: the prototype's values (fixed per cymbal, not per take).
        val db = doublets.getValue(kind)
        check(db.split.size == k) { "doublets for $kind: ${db.split.size} against $k modes" }
        val split = DoubleArray(k) { db.split[it] * ratio }
        val share = db.share
        val slopeMul = db.slope
        val hz = DoubleArray(2 * k) { if (it < k) hz0[it] else hz0[it - k] + split[it - k] }
        val amp = DoubleArray(2 * k) { if (it < k) amp0[it] * cos(share[it]) else amp0[it - k] * sin(share[it - k]) }
        val slope = DoubleArray(2 * k) { if (it < k) slope0[it] else slope0[it - k] * slopeMul[it - k] }
        val ph = DoubleArray(2 * k) { tr.uniform(0.0, TAU) }
        val n = Math.rint(seconds * RATE).toInt()
        val out = DoubleArray(n)
        val dbToLn = ln(10.0) / 20
        for (i in 0 until 2 * k) {
            if (hz[i] >= 0.47 * RATE) continue
            val lim = min(n, (90.0 / -slope[i] * RATE).toInt() + 1)
            // 10^(slope t / 20) and the sine by recurrence: both exact up to rounding.
            val decay = exp(slope[i] / RATE * dbToLn)
            val step = TAU * hz[i] / RATE
            val cs = cos(step); val sn = sin(step)
            var s = sin(ph[i]); var c = cos(ph[i])
            var env = amp[i]
            for (j in 0 until lim) {
                out[j] += env * s
                val ns = s * cs + c * sn
                c = c * cs - s * sn
                s = ns
                env *= decay
            }
        }
        val r = CalabashDsp.rms(out, 0, (0.3 * RATE).toInt()) + 1e-20
        for (j in out.indices) out[j] /= r
        return out
    }

    /** EMBER's `stick_burst`: 60 ms of the stick at the recording's band levels, toward the soft ones as v falls. */
    fun stickBurst(stick: DoubleArray, stickSoft: DoubleArray, v: Double, tr: Rng, n: Int): DoubleArray {
        val w = min(1.0, cymbalSoftWeight(v))
        val m = (0.06 * RATE).toInt()
        val b = DoubleArray(m)
        for (i in cymbalBands.indices) {
            val level = (1 - w) * stick[i] + w * stickSoft[i]
            val x = CalabashDsp.thirdOctave(cymbalBands[i]).run(tr.normals(m))
            for (j in 0 until m) b[j] += level * x[j]
        }
        for (j in 0 until m) { val t = j.toDouble() / RATE; b[j] *= exp(-t / 0.008) * min(1.0, t / 0.0004) }
        val r = CalabashDsp.rms(b, 0, (0.02 * RATE).toInt()) + 1e-20
        return DoubleArray(n) { if (it < m) b[it] / r else 0.0 }
    }

    /** EMBER's `residual_noise` for the fitted open hat: band noise along what its modes and stick leave out. */
    private fun cymbalResidual(m: Cymbal, tune: Double, damp: Double, v: Double, tr: Rng, n: Int, refRms: Double): DoubleArray {
        val res = m.residual ?: return DoubleArray(n)
        val soft = m.residualSoft!!
        val times = m.times!!
        val bands = cymbalBands
        val w = min(1.0, cymbalSoftWeight(v))
        val shift = (tune - 0.5) * 2 * 3 / 4
        val cols = times.size
        val mixed = Array(bands.size) { i -> DoubleArray(cols) { j -> (1 - w) * res[i][j] + w * soft[i][j] } }
        val lev = Array(bands.size) { i -> DoubleArray(cols) { j -> CalabashDsp.interpIndex(i - shift, DoubleArray(bands.size) { mixed[it][j] }) } }
        val df = dampFactor(damp)
        val out = DoubleArray(n)
        val dbToLn = ln(10.0) / 20
        for (i in bands.indices) {
            val fc = bands[i]
            if (fc < 200 || fc * 2.0.pow(1.0 / 6) >= 0.49 * RATE) continue
            val raw = CalabashDsp.thirdOctave(fc).run(tr.normals(n + 2000))
            val x = raw.copyOfRange(2000, raw.size)
            val norm = CalabashDsp.rms(x) + 1e-20
            val g = CalabashDsp.gainCurve(n, df / RATE, times, lev[i])
            for (s in 0 until n) out[s] += x[s] / norm * g[s]
        }
        for (s in 0 until n) out[s] *= refRms
        return out
    }

    /** EMBER's `struck`: a stick on a fitted cymbal (modes, stick, residual) at 44.1 kHz, carried to [nFs] at 4x. */
    fun struck(kind: String, tune: Double, damp: Double, v: Double, tr: Rng, nFs: Int, burst: Double = 0.6): DoubleArray {
        val m = cymbals.getValue(kind)
        var y = fittedModes(kind, tune, damp, v, tr, nFs.toDouble() / CalabashDsp.FS)
        val b = stickBurst(m.stick, m.stickSoft, v, tr, y.size)
        y = DoubleArray(y.size) { y[it] + burst * b[it] }
        val ref = CalabashDsp.rms(y, 0, (0.5 * RATE).toInt())
        val r = cymbalResidual(m, tune, damp, v, tr, y.size, ref)
        y = DoubleArray(y.size) { y[it] + r[it] }
        return CalabashDsp.upsample4(y, nFs)
    }

    /**
     * EMBER's `envelope_hat`: a closed or pedalled hat from its fitted band envelopes, unit band noise in each third
     * octave shaped by the recording's level over time, between the hard and soft recordings (extrapolated a little
     * below the soft one). TUNE slides the spectrum up to 3 semitones; DAMP shortens it; a take is fresh noise and 1 dB
     * of band scatter. At 44.1 kHz, [n] samples.
     */
    fun envelopeHat(kind: String, tune: Double, damp: Double, v: Double, tr: Rng, n: Int): DoubleArray {
        val d = envHats.getValue(kind)
        val bands = cymbalBands
        val w = cymbalSoftWeight(v)
        val cols = d.times.size
        val mixed = Array(bands.size) { i -> DoubleArray(cols) { j -> (1 - w) * d.hard[i][j] + w * d.soft[i][j] } }
        val shift = (tune - 0.5) * 2 * 3 / 4
        val lev = Array(bands.size) { i -> DoubleArray(cols) { j -> CalabashDsp.interpIndex(i - shift, DoubleArray(bands.size) { mixed[it][j] }) } }
        val df = dampFactor(damp)
        val out = DoubleArray(n)
        val dbToLn = ln(10.0) / 20
        for (i in bands.indices) {
            val fc = bands[i]
            if (fc * 2.0.pow(1.0 / 6) >= 0.49 * RATE) continue
            val raw = CalabashDsp.thirdOctave(fc).run(tr.normals(n + 2000))
            val x = raw.copyOfRange(2000, raw.size)
            val norm = CalabashDsp.rms(x) + 1e-20
            val scatter = exp(tr.normal(0.0, 1.0) * dbToLn) / norm
            val g = CalabashDsp.gainCurve(n, df / RATE, d.times, lev[i])
            for (s in 0 until n) out[s] += x[s] * g[s] * scatter
        }
        return out
    }

}
