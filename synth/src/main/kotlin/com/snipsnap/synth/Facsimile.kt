package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * FACSIMILE: drums replicated, not sampled, from nine reference loops (`docs/FACSIMILE.md`). Each drum is a fitted
 * numeric model (`/facsimile/v1/<loop>.json`, made by `prototype/facsimile/fit.py`); this file renders a strike from
 * those numbers and never reads PCM. It is the Kotlin port of `prototype/facsimile/synth.py`, which the owner
 * listened to through rounds 0 to 0m (2026-11-18 to 2026-11-20).
 *
 * The characteristic gesture: **the strike lands → its pitch falls (a kick or an 808) or holds (a tom's ring) →
 * third-octave bands of noise decay each at the reference's own rate → measured tail tones beat as the reference's
 * did, then stop where it stopped.**
 *
 * A drum is a sum of:
 * - **Glide partials**: a sine whose pitch falls from f0 to f1 (exponentially in Hz, or in octaves for a zap) with
 *   its own loudness curve. The fundamental keeps its phase on every take, so the punch is the same hit to hit.
 * - **Steady partials**: rings that hold their pitch (a tom, a bell), sized from the measured spectrum.
 * - **Tail tones** (`mode`): a rebuilt kick's low tail as damped sines with the reference's phases, faded in where
 *   the pitch fall hands over and out where the reference kick ends. Fresh phases would swell at random moments,
 *   which the owner heard as a fragmented kick.
 * - **26 noise bands**: unit-RMS noise through a 3rd-order Butterworth band-pass (the fit's own design, see
 *   [bandpass]), each with its loudness curve. The filters run over a 0.25 s lead-in first, so a narrow low band is
 *   at full level when the strike begins.
 *
 * Loudness curves are decibel values on [GRID_MS], interpolated in decibels (drum decays are straight lines in dB).
 *
 * Velocity is a different strike, not a quieter copy: a softer strike tilts its noise darker (per octave from 1 kHz),
 * decays faster ([Model.decayLoss]) and falls less far in pitch ([Model.glideDepthLoss]); its level follows velocity
 * squared, with the tilt's energy given back so the tilt changes character, not loudness. A take is a fresh strike:
 * new noise, new phases for the upper partials, a few cents of pitch and per-band level scatter sized from how much
 * the reference's own repeats differed ([Model.bandSigmaDb]).
 *
 * Rendered at 4x and finished as the house does (band-limit, decimate, mean and 20 Hz out, 4 ms fade). Not levelled:
 * the models keep the reference loops' absolute levels, so a groove rebuilt from them keeps its mix balance.
 */
object Facsimile {
    /** The model assets' version (`/facsimile/v<N>/`). Bump with any refit. */
    const val ASSET_VERSION = 1

    val GRID_MS = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 14.0, 20.0, 28.0, 40.0, 55.0, 75.0, 100.0, 130.0,
        170.0, 220.0, 280.0, 350.0, 440.0, 550.0, 700.0, 850.0, 1000.0, 1200.0, 1400.0)

    /** 27 edges, 35 Hz to 17 kHz in equal ratios: 26 bands of about a third of an octave. */
    val BAND_EDGES = DoubleArray(27) { 35.0 * (17_000.0 / 35.0).pow(it / 26.0) }
    const val FLOOR_DB = -120.0
    private const val WARM_S = 0.25
    private const val ONSET_RAMP_S = 0.0002

    class Partial(
        val kind: String,
        val f0: Double, val f1: Double, val tauS: Double, val law: String, val phase: Double, val lockPhase: Boolean,
        val envDb: DoubleArray?,
        // a tail tone
        val hz: Double, val decayS: Double, val ampDb: Double, val startS: Double, val fadeS: Double,
        val endS: Double?, val endFadeS: Double,
    )

    class Event(val t: Double, val gain: Double)

    class Model(
        val id: String,
        val lengthS: Double,
        val partials: List<Partial>,
        val noiseDb: List<DoubleArray>,
        val tiltDbPerOct: Double, val decayLoss: Double, val glideDepthLoss: Double,
        val bandSigmaDb: Double, val pitchCents: Double, val tauSpread: Double,
        val selfChoke: Boolean,
        val events: List<Event>,
    )

    /** A reference loop's replica: its drums in the fit's order and its length (bar-exact at its tempo). */
    class Loop(val key: String, val seconds: Double, val drums: Map<String, Model>)

    private val loops = ConcurrentHashMap<String, Loop>()

    fun loop(key: String): Loop = loops.getOrPut(key) { parseLoop(key, resource("$key.json")) }

    fun model(id: String): Model = requireNotNull(loop(id.substringBefore('-')).drums[id]) { "no FACSIMILE drum $id" }

    internal fun resource(name: String): String =
        requireNotNull(Facsimile::class.java.getResourceAsStream("/facsimile/v$ASSET_VERSION/$name")) {
            "Missing FACSIMILE asset: $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun parseLoop(key: String, text: String): Loop {
        val root = Json.parse(text).obj()
        val drums = LinkedHashMap<String, Model>()
        for ((id, v) in root.getValue("drums").obj()) drums[id] = parseModel(v.obj())
        return Loop(key, root.getValue("seconds").num(), drums)
    }

    private fun nums(v: JsonValue) = v.arr().map { it.num() }.toDoubleArray()

    private fun parseModel(m: Map<String, JsonValue>): Model {
        fun d(o: Map<String, JsonValue>, k: String, default: Double = 0.0) = o[k]?.let { if (it is JsonValue.Num) it.num() else null } ?: default
        val partials = m.getValue("partials").arr().map { pv ->
            val p = pv.obj()
            Partial(
                kind = p.getValue("kind").str(),
                f0 = d(p, "f0"), f1 = d(p, "f1"), tauS = d(p, "tau_s"), law = p["law"]?.str() ?: "exp",
                phase = d(p, "phase"), lockPhase = p["lock_phase"]?.bool() ?: false,
                envDb = p["env_db"]?.let { nums(it) },
                hz = d(p, "hz"), decayS = d(p, "decay_s", 1.0), ampDb = d(p, "amp_db", FLOOR_DB),
                startS = d(p, "start_s"), fadeS = d(p, "fade_s", 1e-3),
                endS = p["end_s"]?.num(), endFadeS = d(p, "end_fade_s", 1e-3),
            )
        }
        val vel = m.getValue("velocity").obj()
        val rr = m.getValue("round_robin").obj()
        return Model(
            id = m.getValue("id").str(),
            lengthS = m.getValue("length_s").num(),
            partials = partials,
            noiseDb = m.getValue("noise_db").arr().map { nums(it) },
            tiltDbPerOct = d(vel, "tilt_db_per_oct"), decayLoss = d(vel, "decay_loss"), glideDepthLoss = d(vel, "glide_depth_loss"),
            bandSigmaDb = d(rr, "band_sigma_db"), pitchCents = d(rr, "pitch_cents"), tauSpread = d(rr, "tau_spread"),
            selfChoke = m["self_choke"]?.bool() ?: false,
            events = m.getValue("events").arr().map { e -> e.obj().let { Event(it.getValue("t").num(), it.getValue("gain").num()) } },
        )
    }

    // ---------------------------------------------------------------- the strike

    /**
     * One strike of [model] at [velocity] (0.05..1) and round-robin [take], at the output rate, unlevelled. Pure: the
     * same arguments give the same samples (`FacsimileTest`).
     */
    fun render(model: Model, velocity: Float, take: Int): FloatArray {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val n = (model.lengthS * rate).toInt()
        val v = velocity.toDouble().coerceIn(0.05, 1.0)
        val soft = 1.0 - v
        val scaleT = 1.0 - model.decayLoss * soft
        val firstTake = take == 0
        val rng = Random(Dsp.seedFor("facsimile", model.id, take))
        fun gauss(): Double {
            val u1 = 1.0 - rng.nextDouble()
            return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * rng.nextDouble())
        }
        val out = DoubleArray(n)

        for (p in model.partials) {
            if (p.kind == "mode") {
                val cents = if (firstTake) 0.0 else gauss() * model.pitchCents
                val levelDb = if (firstTake) 0.0 else gauss() * model.bandSigmaDb
                addMode(out, p, rate, cents, levelDb, scaleT)
                continue
            }
            val cents = if (firstTake) 0.0 else gauss() * model.pitchCents
            val detune = 2.0.pow(cents / 1200.0)
            // a softer strike: the pitch falls less far
            val depth = 1.0 - model.glideDepthLoss * soft
            val f1 = p.f1 * detune
            val f0 = f1 + (p.f0 * detune - f1) * depth
            val tau = p.tauS * (if (firstTake) 1.0 else 1.0 + gauss() * model.tauSpread)
            val phase0 = if (firstTake || p.lockPhase) p.phase else rng.nextDouble() * 2.0 * PI
            val levelDb = if (firstTake) 0.0 else gauss() * model.bandSigmaDb
            val env = envelope(requireNotNull(p.envDb), levelDb, n, rate, scaleT)
            var ph = phase0
            val still = tau <= 0.0 || abs(f0 - f1) < 1e-6
            val decay = if (still) 0.0 else exp(-1.0 / (tau * rate))
            var e = 1.0
            val ceiling = rate * 0.45
            for (i in 0 until n) {
                val f = if (still) f1 else if (p.law == "logexp") f1 * (f0 / f1).pow(e) else f1 + (f0 - f1) * e
                ph += 2.0 * PI * min(f, ceiling) / rate
                out[i] += sin(ph) * env[i]
                e *= decay
            }
        }

        // Level follows velocity squared (a ghost at .5 is about -12 dB); the tilt's energy is given back.
        val gain = v * v * tiltCompensation(model, soft)
        val ramp = (ONSET_RAMP_S * rate).toInt()
        val raw = FloatArray(n) { i -> (out[i] * gain * (if (i < ramp) i.toDouble() / ramp else 1.0)).toFloat() }
        // The partials are the only part that can alias (a pitch falling from kilohertz): band-limited and decimated.
        Tide.bandLimit(raw, rate)
        val x = Dsp.decimate(raw, Dsp.RATE)

        // The noise is band-limited by its own filters, so it runs at the output rate: four times less work than the
        // prototype's, which filtered it at 4x (every band edge is under 17 kHz; the filters are pre-warped there).
        // Every band shares one stream, as the fit's did, filtered per band after a lead-in.
        val out1 = Dsp.RATE
        val n1 = x.size
        val warm = (WARM_S * out1).toInt()
        val noiseRng = Random(Dsp.seedFor("facsimile", model.id, "noise", take))
        val noise = DoubleArray(n1 + warm) {
            val u1 = 1.0 - noiseRng.nextDouble()
            sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * noiseRng.nextDouble())
        }
        val ramp1 = (ONSET_RAMP_S * out1).toInt()
        for ((b, envDb) in model.noiseDb.withIndex()) {
            if (envDb.max() <= FLOOR_DB + 1) continue
            val centre = sqrt(BAND_EDGES[b] * BAND_EDGES[b + 1])
            val tilt = model.tiltDbPerOct * soft * log2(centre / 1000.0)
            val levelDb = tilt + (if (firstTake) 0.0 else gauss() * model.bandSigmaDb)
            val y = filtered(noise, bandpass(BAND_EDGES[b], BAND_EDGES[b + 1], out1, ORDER))
            var energy = 0.0
            for (i in warm until y.size) energy += y[i] * y[i]
            val norm = gain / (sqrt(energy / n1) + 1e-12)
            val env = envelope(envDb, levelDb, n1, out1, scaleT)
            for (i in 0 until n1) x[i] += (y[warm + i] * norm * env[i] * (if (i < ramp1) i.toDouble() / ramp1 else 1.0)).toFloat()
        }
        return finish(x)
    }

    private fun addMode(out: DoubleArray, p: Partial, rate: Int, cents: Double, levelDb: Double, scaleT: Double) {
        val a = 10.0.pow((p.ampDb + levelDb) / 20.0)
        val w = 2.0 * PI * p.hz * 2.0.pow(cents / 1200.0) / rate
        val step = exp(-1.0 / (p.decayS * scaleT * rate))
        var env = a
        for (i in out.indices) {
            val t = i.toDouble() / rate
            var ramp = ((t - p.startS) / p.fadeS).coerceIn(0.0, 1.0)
            if (p.endS != null) ramp *= ((p.endS - t) / p.endFadeS).coerceIn(0.0, 1.0)
            if (ramp > 0.0) out[i] += env * cos(w * i + p.phase) * (0.5 - 0.5 * cos(PI * ramp))
            env *= step
        }
    }

    /**
     * A loudness curve sampled at [rate]: the grid's decibels plus [levelDb], interpolated in decibels, time scaled
     * by [scaleT] (under 1 shortens it), the floor past its last point. Each segment is a constant ratio per sample.
     */
    internal fun envelope(envDb: DoubleArray, levelDb: Double, n: Int, rate: Int, scaleT: Double): DoubleArray {
        val out = DoubleArray(n)
        val floor = 10.0.pow(FLOOR_DB / 20.0)
        val samplesPerMs = rate / 1000.0 * scaleT
        for (g in 0 until GRID_MS.size - 1) {
            val from = ceil(GRID_MS[g] * samplesPerMs).toInt()
            val to = min(n, ceil(GRID_MS[g + 1] * samplesPerMs).toInt())
            if (from >= n) break
            val slope = (envDb[g + 1] - envDb[g]) / (GRID_MS[g + 1] - GRID_MS[g])
            // a straight line in dB is a constant ratio per sample; the floor is a floor on the amplitude
            var amp = 10.0.pow((envDb[g] + slope * (from / samplesPerMs - GRID_MS[g]) + levelDb) / 20.0)
            val ratio = 10.0.pow(slope / samplesPerMs / 20.0)
            for (i in from until to) { out[i] = max(amp, floor); amp *= ratio }
        }
        val last = ceil(GRID_MS.last() * samplesPerMs).toInt()
        for (i in last until n) out[i] = floor
        return out
    }

    /** The energy the velocity tilt took from the noise, given back: the strike's energy before over after, rooted. */
    private fun tiltCompensation(m: Model, soft: Double): Double {
        if (soft <= 0.0) return 1.0
        val steps = ceil(m.lengthS * 1000.0).toInt()
        fun energy(envDb: DoubleArray): Double {
            var s = 0.0
            var g = 0
            for (k in 0 until steps) {
                val t = k.toDouble()
                while (g < GRID_MS.size - 2 && t > GRID_MS[g + 1]) g++
                val db = if (t >= GRID_MS.last()) envDb.last()
                else envDb[g] + (envDb[g + 1] - envDb[g]) * (t - GRID_MS[g]) / (GRID_MS[g + 1] - GRID_MS[g])
                s += 10.0.pow(db / 10.0)
            }
            return s
        }
        var bands = 0.0
        var tilted = 0.0
        for ((b, e) in m.noiseDb.withIndex()) {
            val en = energy(e)
            val centre = sqrt(BAND_EDGES[b] * BAND_EDGES[b + 1])
            bands += en
            tilted += en * 10.0.pow(m.tiltDbPerOct * soft * log2(centre / 1000.0) / 10.0)
        }
        var parts = 0.0
        for (p in m.partials) {
            parts += if (p.kind == "mode") {
                var s = 0.0
                for (k in 0 until steps) s += 10.0.pow(p.ampDb / 10.0) / 2.0 * exp(-2.0 * k / (p.decayS * 1000.0))
                s
            } else energy(requireNotNull(p.envDb)) / 2.0
        }
        return sqrt((bands + parts) / max(tilted + parts, 1e-30))
    }

    /** The rest of the house output stage, at the output rate: mean and anything under 20 Hz out, a 4 ms fade. */
    private fun finish(x: FloatArray): FloatArray {
        val mean = x.average().toFloat()
        // 1st-order Butterworth high-pass at 20 Hz (bilinear), as the prototype's finish
        val k = tan(PI * 20.0 / Dsp.RATE)
        val b0 = 1.0 / (1.0 + k); val a1 = (k - 1.0) / (k + 1.0)
        var x1 = 0.0; var y1 = 0.0
        for (i in x.indices) {
            val s = (x[i] - mean).toDouble()
            val y = b0 * s - b0 * x1 - a1 * y1
            x1 = s; y1 = y
            x[i] = y.toFloat()
        }
        Dsp.fadeTail(x)
        return x
    }

    // ---------------------------------------------------------------- the band-pass

    /** The fit's band-pass order: scipy.signal.butter(3, band) in `synth.py`. */
    const val ORDER = 3

    private val sections = ConcurrentHashMap<Triple<Double, Double, Int>, List<DoubleArray>>()

    /**
     * An [order]th-order Butterworth band-pass from [lo] to [hi] Hz at [rate] as [order] biquads ([b0, b1, b2, a1, a2],
     * a0 = 1): the analog prototype's poles moved to a band-pass, then bilinear with pre-warped edges. TERRA's fitted
     * drums use the same construction at order 4 ([TerraFitted.bandpass]); FACSIMILE's fit ran at order 3, so this
     * takes the order.
     */
    internal fun bandpass(lo: Double, hi: Double, rate: Int, order: Int): List<DoubleArray> = sections.getOrPut(Triple(lo, hi, rate * 10 + order)) {
        val fs2 = 2.0 * rate
        val wl = fs2 * tan(PI * lo / rate); val wh = fs2 * tan(PI * hi / rate)
        val bw = wh - wl; val w0sq = wl * wh
        val poles = ArrayList<DoubleArray>()
        for (j in 0 until order) {
            val m = -(order - 1) + 2 * j
            val pr = -cos(PI * m / (2.0 * order)) * bw / 2.0; val pi = -sin(PI * m / (2.0 * order)) * bw / 2.0
            val qr = pr * pr - pi * pi - w0sq; val qi = 2.0 * pr * pi
            val mag = sqrt(sqrt(qr * qr + qi * qi)); val ang = kotlin.math.atan2(qi, qr) / 2.0
            val sr = mag * cos(ang); val si = mag * sin(ang)
            poles += doubleArrayOf(pr + sr, pi + si); poles += doubleArrayOf(pr - sr, pi - si)
        }
        var kRe = bw.pow(order) * fs2.pow(order); var kIm = 0.0
        val zPoles = poles.map { (re, im) ->
            val nr = fs2 + re; val ni = im; val dr = fs2 - re; val di = -im
            val den = dr * dr + di * di
            val tr = (kRe * dr + kIm * di) / den; val ti = (kIm * dr - kRe * di) / den
            kRe = tr; kIm = ti
            doubleArrayOf((nr * dr + ni * di) / den, (ni * dr - nr * di) / den)
        }
        val upper = zPoles.filter { it[1] > 0.0 }.sortedBy { it[0] }
        require(upper.size == order) { "a band-pass of ${2 * order} poles pairs into $order sections" }
        val gainEach = kRe.pow(1.0 / order)
        upper.map { (re, im) -> doubleArrayOf(gainEach, 0.0, -gainEach, -2.0 * re, re * re + im * im) }
    }

    private fun filtered(x: DoubleArray, secs: List<DoubleArray>): DoubleArray {
        var y = x
        for (c in secs) {
            val o = DoubleArray(y.size)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in y.indices) {
                val s = c[0] * y[i] + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
                x2 = x1; x1 = y[i]; y2 = y1; y1 = s; o[i] = s
            }
            y = o
        }
        return y
    }

    // ---------------------------------------------------------------- the groove

    /** A note's velocity from its measured gain (amplitude against the drum's loudest): level follows v^2. */
    fun velocityOf(gain: Double): Float = sqrt(gain).coerceIn(0.05, 1.0).toFloat()

    /**
     * A reference loop re-performed with its replica drums: every hit where the reference had it, as hard as it was,
     * each repeat dealing the next of four takes. A drum in [choke] (or one that chokes itself) is cut at the next
     * hit of its group with a 6 ms fade, as an 808 on a sampler pad. The loop wraps: tails ring into bar one.
     * [strike] renders a drum at a velocity and take (the kit passes its cache). [takeShift] rotates every drum's
     * takes, a fresh pass of the same performance. Not glued, not levelled.
     */
    fun groove(loop: Loop, choke: Set<String>, takeShift: Int = 0, velocityScale: Float = 1f,
               strike: (Model, Float, Int) -> FloatArray = ::render): FloatArray {
        val n = round(loop.seconds * Dsp.RATE).toInt()
        val out = FloatArray(n)
        for ((id, model) in loop.drums) {
            val events = model.events.sortedBy { it.t }
            val group = if (id in choke) choke else if (model.selfChoke) setOf(id) else emptySet()
            val groupStarts = group.flatMap { g -> loop.drums[g]?.events.orEmpty().map { round(it.t * Dsp.RATE).toInt() } }
            for ((i, e) in events.withIndex()) {
                val v = quantise(velocityOf(e.gain) * velocityScale)
                var y = strike(model, v, (i + takeShift) % TAKES)
                val start = round(e.t * Dsp.RATE).toInt()
                if (group.isNotEmpty()) {
                    val next = groupStarts.map { Math.floorMod(it - start, n) }.filter { it > 0 }.minOrNull()
                    if (next != null && next < y.size) {
                        y = y.copyOf()
                        val fade = (CHOKE_FADE_S * Dsp.RATE).toInt()
                        // the prototype's linspace(1, 0, L): from 1 to exactly 0 over the fade
                        val from = max(0, next - fade)
                        val len = next - from
                        for (j in 0 until len) y[from + j] *= if (len > 1) 1f - j.toFloat() / (len - 1) else 1f
                        for (k in next until y.size) y[k] = 0f
                    }
                }
                for (k in y.indices) out[(start + k) % n] += y[k]
            }
        }
        return out
    }

    /** Four takes per strike, as the kits' round robin. */
    const val TAKES = 4
    private const val CHOKE_FADE_S = 0.006

    /** Groove notes strike at velocities rounded to 0.01, as the prototype's grooves (and so cache well). */
    fun quantise(v: Float): Float = (round(v * 100f) / 100f).coerceIn(0.05f, 1f)

    /**
     * The bus compressor the grooves are printed through (`groove.py`'s `glue`): peak-sensing on 1 ms blocks, a soft
     * knee, gain reduction rising at the attack rate and falling at the release rate (one-pole in dB). The loop wraps,
     * so it runs twice round and keeps the second pass. The settings were fitted per loop to the reference's
     * peak-to-average ratio (`fit_glue.py`).
     */
    fun glue(x: FloatArray, thresholdDb: Double, ratio: Double, attackMs: Double, makeupDb: Double,
             kneeDb: Double = 6.0, releaseMs: Double = 120.0): FloatArray {
        val n = x.size
        val blk = (0.001 * Dsp.RATE).toInt()
        val m = ceil(n.toDouble() / blk).toInt()
        val want = DoubleArray(m) { b ->
            var peak = 0f
            for (k in b * blk until (b + 1) * blk) peak = max(peak, abs(x[k % n]))
            val over = 20.0 * kotlin.math.log10(peak + 1e-9) - thresholdDb
            when {
                over <= -kneeDb / 2 -> 0.0
                over >= kneeDb / 2 -> over * (1 - 1 / ratio)
                else -> (1 - 1 / ratio) * (over + kneeDb / 2).pow(2) / (2 * kneeDb)
            }
        }
        val a = exp(-1.0 / attackMs)
        val r = exp(-1.0 / releaseMs)
        val gr = DoubleArray(m)
        var g = 0.0
        repeat(2) { for (b in 0 until m) { val c = if (want[b] > g) a else r; g = c * g + (1 - c) * want[b]; gr[b] = g } }
        // the gain between block centres, interpolated round the loop
        val period = m * blk
        return FloatArray(n) { i ->
            val pos = (i - blk / 2.0)
            val idx = Math.floorDiv(kotlin.math.floor(pos).toInt(), blk)
            val frac = (pos - idx * blk) / blk
            val g0 = gr[Math.floorMod(idx, m)]; val g1 = gr[Math.floorMod(idx + 1, m)]
            val db = -(g0 + (g1 - g0) * frac) + makeupDb
            (x[i] * 10.0.pow(db / 20.0)).toFloat()
        }.also { check(period >= n) }
    }
}
