package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Two plates under the same blast: a round metal plate that keeps its hardening, and a square glass
 * pane that gives it back. Each has its own body: its own sourced overtone pattern.
 */
enum class ScourVoice { PLATE, PANE }

/**
 * SCOUR: a free plate is rolled against, then blasted with shot. Each shot dents the surface, the
 * dented fraction (coverage) climbs toward saturation, and the plate's upper partials climb with it
 * while RING decides whether its ring grows or tightens. When the blast stops the plate rings out in
 * the state the blast left. The characteristic gesture is rolling contact, shot blast, coverage
 * builds and the plate stiffens, then a hardened ring that keeps its tuning (PLATE) or gives it back
 * (PANE).
 *
 * Computed: the roller's Coulomb spin-down, read against a fixed surface at its contact phase; a
 * seeded Poisson shot schedule; coverage by the random-dent recursion `S += κ·e·(1 − S)`; the plate,
 * a [Modes.Bank]. Analogy: coverage moving the modal frequencies and damping (real cold work raises
 * yield strength and barely the stiffness). Not simulated: plastic flow, residual stress, curvature,
 * rebound, stick-slip, air. The design is
 * docs/superpowers/specs/2026-10-26-scour-engine-engineering-spec.md; every constant marked shape
 * is a listening hypothesis.
 *
 * Every path is one-way: the plate never feeds back into the roller, the shots or the coverage, so
 * nothing needs a bound beyond the bank's own. [Modes.Bank] steps each mode exactly and its `tune`
 * keeps the mode's energy, so retuning on the control tick cannot pump level. Coverage stays below 1
 * by its own arithmetic: `S + κe(1 − S) = 1 − (1 − S)(1 − κe)`.
 *
 * The strike weights keep the root at full weight. A real free plate's root (the round plate's (2,0)
 * mode, the square's twisting mode) has no motion at the centre, so a real centre strike would not
 * sound the note; this departure is deliberate. The blast lands where R1's TRAVERSE default put it:
 * the owner heard TRAVERSE change nothing, and its knob went to RING.
 *
 * HOLD's top step is reserved for the R2 loop and renders as the step below it here, as GYRE's does.
 */
object Scour {
    /**
     * Seeds the draws, so it stays 1: no saved SCOUR sound exists (the engine is a prototype, outside
     * the picker), and keeping the seeds keeps every shot of PLATE's blast where the owner heard it.
     * Before PLATE's (1,1) mode was quieted, RING .25 rendered R1's PLATE byte for byte (measured).
     */
    const val MODEL_VERSION = 1
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    /** HOLD's top step, reserved for the R2 loop; [render] treats it as the step below. */
    const val LOOP_THRESHOLD = 0.99f
    /** The classifier files anything longer than this as LOOP (`Classifier`'s own constant is private). */
    const val LOOP_MIN_SECONDS = 1.5f
    /**
     * No render is shorter than this. RING 0 can tighten PANE's ring to 1.33 s in all, under the
     * classifier's 1.5 s, where its feature rules could file a plate as a drum; the floor keeps every
     * SCOUR sound a LOOP-class phrase, as R1's all were. Below it the render holds the decayed ring.
     */
    const val MIN_RENDER_SECONDS = 1.6

    internal const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    /** The control tick, in internal samples (about 1 kHz, THAW's): coverage retunes the plate this often. */
    internal const val CTRL = 176
    /** The one-pole smoothing of coverage before it retunes the plate (shape). */
    private const val SMOOTH_SECONDS = 0.015
    /** Roller revolutions per second at full speed (shape). */
    private const val REVS_PER_SECOND = 5.0
    /** Roughness points per revolution; read faster, the surface sounds brighter, by construction. */
    private const val TABLE = 8192
    /** A mode HARDEN could push past this at the note is dropped for the whole note. */
    private const val MODE_CEILING_HZ = 18_000.0
    /** Force of one shot at the reference rate (shape). */
    private const val SHOT_FORCE = 60.0
    /** A shot is a half-sine over four internal samples (22.7 µs): a band-limit, not a mallet model. */
    private const val PULSE_SAMPLES = 4
    private const val OUTPUT_DC_HZ = 20f
    /**
     * Fixed radiation trim, never per render. Measured once over the extreme corners: the loudest raw peak
     * after the output stage is 0.61 (PLATE, SHOT 0 and TRAVERSE 0, where a few heavy shots pile onto the
     * ring), and the defaults sit at 0.21 (PLATE) and 0.11 (PANE).
     */
    private const val OUTPUT_TRIM = 36.0
    /** The roller runs on the rim. */
    private const val RIM = .97
    /**
     * Where the blast lands, as a fraction from the centre to the rim: R1's TRAVERSE default (.2) through
     * R1's own float law, so the shots weigh the modes exactly as they did (each shot jitters ±.05).
     */
    private val AIM = Dsp.lin(.2f, .05f, .95f).toDouble()
    private val PULSE = DoubleArray(PULSE_SAMPLES) { sin(PI * (it + .5) / PULSE_SAMPLES) }

    /**
     * PLATE's body: the completely free circular plate, Leissa, *Vibration of Plates* (NASA SP-160, 1969),
     * Table 2.5, ν = 0.33: `λ² = ωa²√(ρh/D)` for modes (n, s) = (2,0) (0,1) (3,0) (1,1) (4,0) (5,0) (2,1)
     * (0,2). Leissa marks (4,0) and (5,0) "true within 2 percent". Each is proportional to ω, so the
     * ratios are taken to (2,0), the root.
     */
    private val CIRCLE = doubleArrayOf(5.253, 9.084, 12.23, 20.52, 21.6, 33.1, 35.25, 38.55)

    /**
     * PANE's body: the completely free square plate at ν = 0.225, the Poisson's ratio of Leissa's own
     * square-plate tables (SP-160, section 4.3.15) and close to glass's. `λ = ωa²√(ρh/D)`, root first.
     *
     * The pane is heard at one point on a diagonal, .7205 of the way from the centre to a corner, and
     * the body is the modes that move there. A point on the diagonal hears only modes symmetric about it
     * (every other mode of a square is exactly still along it), which drops the diagonal-node mode at
     * 1.444; at .7205 two more, at 4.347 and 5.489, have their nodes (.7202 and .7208). All three sit
     * near half-integer ratios (3/2, 9/2, 11/2), which make the pair with the root look like harmonics of
     * the octave below: heard, they made the house `Pitch.detect` read the note an octave or a twelfth
     * low. Listening at a node is the mallet maker's move turned round (they strike at the node of the
     * partial they do not want): a position on the plate, not a filter. Each degenerate pair keeps its
     * one diagonal-symmetric member, and 7.657 (a tenth of the root's motion there) is left out.
     *
     * Leissa's ν = 0.225 tables survive the NTRS scan only in part, so the values come from a
     * Rayleigh-Ritz solve (Legendre products, converged to 4e-7, in the spec) that reproduces eight entries
     * of his complete ν = 0.3 table (Table 4.65) to within 3e-5 and lands inside his ν = 0.225 bounds on the
     * fundamental (14.1028 to 14.1165). Ratios 1, 1.692, 2.549, 4.650, 8.659, 9.413.
     */
    private val SQUARE = doubleArrayOf(14.116, 23.878, 35.977, 65.645, 122.234, 132.868)

    /** Each SQUARE mode's motion at the listening point, from the same solve, as a fraction of the root's. */
    private val SQUARE_HEARD = doubleArrayOf(1.0, .615, .574, .362, .580, .429)

    /** A voice's body and its response to the blast. Every number is shape except the body's ratios. */
    private class Shape(
        /** The body's modes as frequency parameters, root first; only their ratios to the root are used. */
        val body: DoubleArray,
        /**
         * Modes 1 and 2 are picked up at [pairLevel] of the gain law and ring for [pairRing] of their t60.
         * On the round plate their ratios, 1.729 and 2.328 (near 7/4 and 7/3), line up every three root
         * periods; at full level the house `Pitch.detect` misread the note (often a twelfth low, f0/3) in 59
         * of 162 PLATE corners at the onset. At .4 and .5, 162 of 162 read within 50 cents at both windows
         * and HARDEN kept its reach (measured in R1). PANE's pair (1.692 and 2.549) was measured the same
         * way in R1.1; see its shape.
         */
        val pairLevel: Double, val pairRing: Double,
        /**
         * How strongly each body mode is heard, root 1, a weight on the gain law: PANE's is each mode's
         * motion at its listening point (the solve); PLATE's quiets one mode for the note (see its shape).
         */
        val heard: DoubleArray,
        /** Root ring, seconds, and how much shorter each mode above it rings. */
        val t60: Double, val decay: Double,
        /** Pickup weight falls by this factor per mode. */
        val gain: Double,
        /** Coverage one shot leaves (κ) and the recovery rate per second (ρ). */
        val kappa: Double, val rho: Double,
        /** How far full coverage at HARDEN 1 lifts the top mode, and how far it moves the ring (RING picks the way). */
        val shift: Double, val damp: Double,
        /** Roller alone before the blast opens, and how long the blast runs, seconds. */
        val lead: Double, val window: Double,
        /** Roller deceleration per second, and its force against a shot's. */
        val decel: Double, val roll: Double,
    )

    /**
     * PLATE's (1,1) mode, 3.906, is heard at .6 of the gain law. R1's tightening ring kept it short; at a
     * longer ring and full blast, stretched toward 4.4, it pulled the note sharp in the tail (+116 to +139
     * cents at SHOT 1, HARDEN 1, RING 1, three of the 37 corners `ScourTest` asserts; silencing it alone
     * fixed them). At the onset, unstretched near 4, it supports the root, so too little costs a default:
     * at .5 F♯4's onset read +54 cents. At .7 one corner still read +107; .6 reads all 37 at both windows
     * (324 and 312 of a 324-corner grid) and raises HARDEN's log-spectral reach from 1.88 to 1.93 dB.
     */
    private val PLATE_HEARD = doubleArrayOf(1.0, 1.0, 1.0, .6, 1.0, 1.0, 1.0, 1.0)
    private val PLATE = Shape(CIRCLE, .4, .5, PLATE_HEARD, 2.2, .80, .72, .012, .15, .35, 1.2, .20, .90, .45, .06)
    /**
     * PANE's pair at .5 of the gain law and .7 of its ring: of the 37 corners `ScourTest` asserts, full level
     * read 34 at the onset and 31 in the tail, .7 and .7 read 36 and 36 (missing the defaults at C3 and C4),
     * and .5 and .7 read all 37 at both windows (312 and 314 of a 324-corner grid). The rest is R1's shape.
     */
    private val PANE = Shape(SQUARE, .5, .7, SQUARE_HEARD, 1.4, .74, .65, .03, 1.5, .25, .6, .12, .60, .60, .05)

    private fun shape(voice: ScourVoice) = when (voice) {
        ScourVoice.PLATE -> PLATE
        ScourVoice.PANE -> PANE
    }

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: ScourVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", .5f, neutral = .5f),
        MacroSpec("RPM", .3f, neutral = .3f),
        MacroSpec("SHOT", .5f, neutral = .5f),
        MacroSpec("HARDEN", .6f, neutral = .5f),
        MacroSpec("RING", .75f, neutral = .5f),
        MacroSpec("HOLD", 0f, neutral = 0f),
    )

    fun defaults(voice: ScourVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** Every known macro, in 0..1; a missing or non-finite one takes its default, an unknown one is dropped. */
    internal fun settled(voice: ScourVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (k, default) -> (macros[k]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }

    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: ScourVoice): Int = ROOT_MIDI
    fun midiFor(voice: ScourVoice, tune: Float): Int =
        rootMidi(voice) + ((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: ScourVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    /**
     * RING: which way, and how far, hardening moves every mode's ring. The factor on t60 is
     * `(1 + damp·HARDEN·coverage)^(2(2·RING − 1))`: RING .5 leaves the ring alone, .25 is R1's tightening
     * ring (exponent −1, the "shorter" clip), .75 the "longer" clip the owner picked for both voices
     * (exponent +1, the default), and the ends double either way. With nothing hardened the factor is
     * exactly 1, so RING needs HARDEN and shots to be heard, as HARDEN needs shots. A negative exponent
     * is taken as a reciprocal so that RING .25 is R1's `1 / f` to the bit.
     */
    private fun ringFactor(s: Shape, harden: Double, coverage: Double, ring: Double): Double {
        val f = 1 + s.damp * harden * coverage
        val e = 2 * (2 * ring - 1)
        return if (e >= 0) f.pow(e) else 1 / f.pow(-e)
    }

    /** No step is a loop in R1: HOLD's top step is reserved (see the class KDoc). */
    @Suppress("UNUSED_PARAMETER")
    fun isLoop(hold: Float): Boolean = false

    private fun velocityOf(velocity: Float) = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
    private fun rollerStart(rpm: Float) = Dsp.lin(rpm, .15f, 1f).toDouble()
    private fun shotRate(shot: Float) = Dsp.expMap(shot, 3f, 300f).toDouble()
    private val REFERENCE_RATE = shotRate(.5f)
    private fun windowSeconds(s: Shape, hold: Float) = s.window * (1 + 2 * hold.coerceAtMost(LOOP_THRESHOLD))

    /** Coverage expected when the blast closes, `S*·(1 − e^(−(λκe + ρ)W))`: render length only, never the audio. */
    private fun expectedCoverage(s: Shape, m: Map<String, Float>, velocity: Float): Double {
        val gain = shotRate(m.getValue("SHOT")) * s.kappa * velocity.toDouble().pow(.8)
        val total = gain + s.rho
        return gain / total * (1 - exp(-total * windowSeconds(s, m.getValue("HOLD"))))
    }

    /**
     * The render's length: the later of the blast closing and the roller stopping, plus the hardened
     * root's ring, and never under [MIN_RENDER_SECONDS]. A closed form, so [drumClassFor] never renders.
     * A softer hit leaves less coverage, so the length moves with velocity one way only: longer below
     * RING .5, shorter above it.
     */
    fun lengthSeconds(voice: ScourVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Double {
        val s = shape(voice)
        val m = settled(voice, macros)
        val close = s.lead + windowSeconds(s, m.getValue("HOLD"))
        val stop = rollerStart(m.getValue("RPM")) / s.decel
        val coverage = expectedCoverage(s, m, velocityOf(velocity))
        val tail = s.t60 * ringFactor(s, m.getValue("HARDEN").toDouble(), coverage, m.getValue("RING").toDouble())
        return max(max(close, stop) + tail, MIN_RENDER_SECONDS)
    }

    fun renderFrames(voice: ScourVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Int =
        (lengthSeconds(voice, macros, velocity) * RATE).roundToInt()

    /**
     * LOOP when the render passes the classifier's 1.5 s, else PERC; never a drum class. The length is
     * monotonic in velocity, so its shortest is at velocity 0 or 1, and reading both makes the class hold
     * at every velocity. [MIN_RENDER_SECONDS] means it is always LOOP today.
     */
    fun drumClassFor(voice: ScourVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val shortest = minOf(renderFrames(voice, macros, 0f), renderFrames(voice, macros, 1f))
        return if (shortest > RATE * LOOP_MIN_SECONDS) DrumClass.LOOP else DrumClass.PERC
    }

    /** Random sounds stay finite: HOLD is an explicit choice. */
    fun scramble(voice: ScourVoice, random: Random = Random.Default): Map<String, Float> =
        defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else random.nextFloat() }

    fun render(voice: ScourVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip =
        Snip(finish(play(voice, macros, velocity).raw), channels = 1, sampleRate = RATE)

    // ---- mechanics: roller, blast and coverage, on the control tick ---------------------------------

    /** What the mechanics decided, before any audio. Shots are sorted by their internal frame. */
    internal class Trace(
        val frames: Int,
        val shotRate: Double,
        val closeSeconds: Double,
        val stopSeconds: Double,
        val shotFrames: IntArray,
        val shotAmplitude: DoubleArray,
        val shotAim: DoubleArray,
        val shotCoverage: DoubleArray,
        /** Coverage after each tick's shots, and the smoothed value that retunes the plate. */
        val coverage: DoubleArray,
        val smoothed: DoubleArray,
    )

    /**
     * Unit-rate gaps and jitters come from fixed streams seeded by the voice alone, then the gaps are
     * divided by λ: turning SHOT stretches one schedule instead of reshuffling it, and shot `i` always
     * gets the `i`-th draws.
     */
    internal fun mechanics(voice: ScourVoice, macros: Map<String, Float>, velocity: Float = 1f): Trace {
        val s = shape(voice)
        val m = settled(voice, macros)
        val vel = velocityOf(velocity)
        val frames = renderFrames(voice, m, vel) * Dsp.OVERSAMPLE
        val lambda = shotRate(m.getValue("SHOT"))
        val open = s.lead
        val close = s.lead + windowSeconds(s, m.getValue("HOLD"))
        val gaps = Random(Dsp.seedFor("SCOUR", MODEL_VERSION, voice.name, "GAPS"))
        val levels = Random(Dsp.seedFor("SCOUR", MODEL_VERSION, voice.name, "LEVEL"))
        val aims = Random(Dsp.seedFor("SCOUR", MODEL_VERSION, voice.name, "AIM"))
        // House energy doctrine: per-shot amplitude falls as √(λ_ref/λ), so the expected mean shot
        // power is the same at every SHOT. Coverage counts dents, not this audio level.
        val amplitude = SHOT_FORCE * sqrt(REFERENCE_RATE / lambda) * vel
        val dent = vel.toDouble().pow(.8)
        val shotFrames = ArrayList<Int>()
        val shotAmplitude = ArrayList<Double>()
        val shotAim = ArrayList<Double>()
        val shotCoverage = ArrayList<Double>()
        var t = open
        while (true) {
            t += -ln(1.0 - gaps.nextDouble()) / lambda
            val level = levels.nextDouble() * 2 - 1
            val off = aims.nextDouble() * 2 - 1
            if (t >= close) break
            val frame = (t * INTERNAL_RATE).toInt()
            if (frame >= frames) break
            shotFrames += frame
            shotAmplitude += amplitude * (1 + .2 * level)
            shotAim += (AIM + .05 * off).coerceIn(0.0, 1.0)
            shotCoverage += (1 + .2 * level) * dent
        }
        val ticks = (frames + CTRL - 1) / CTRL
        val coverage = DoubleArray(ticks)
        val smoothed = DoubleArray(ticks)
        val relax = exp(-s.rho * CTRL / INTERNAL_RATE)
        val follow = exp(-CTRL / (INTERNAL_RATE * SMOOTH_SECONDS))
        var c = 0.0
        var y = 0.0
        var j = 0
        for (k in 0 until ticks) {
            c *= relax
            while (j < shotFrames.size && shotFrames[j] / CTRL == k) {
                c += s.kappa * shotCoverage[j] * (1 - c)
                j++
            }
            coverage[k] = c
            y = follow * y + (1 - follow) * c
            smoothed[k] = y
        }
        return Trace(
            frames, lambda, close, rollerStart(m.getValue("RPM")) / s.decel,
            shotFrames.toIntArray(), shotAmplitude.toDoubleArray(), shotAim.toDoubleArray(),
            shotCoverage.toDoubleArray(), coverage, smoothed,
        )
    }

    // ---- the plate ------------------------------------------------------------------------------------

    /**
     * Strike participation at radial position [x], for the voice's modes in [kept] order. The root keeps
     * full weight (the departure the class KDoc names); the rest follow a shape law, not Bessel functions.
     */
    private fun weights(x: Double, kept: IntArray): DoubleArray = DoubleArray(kept.size) { i ->
        val k = kept[i]
        if (k == 0) 1.0 else .3 + .7 * abs(cos(PI * (k + .5) * x))
    }

    /** The voice's mode positions (0 is the root) whose hardened frequency stays under the ceiling at [hz]. */
    private fun keptModes(s: Shape, hz: Double): IntArray {
        val last = (s.body.size - 1).toDouble()
        return s.body.indices.filter { k ->
            hz * s.body[k] / s.body[0] * (1 + s.shift * k / last) < MODE_CEILING_HZ
        }.toIntArray()
    }

    /** A seeded, pink-tilted surface for one revolution, periodic by construction, peak 1. */
    private val roughness = HashMap<ScourVoice, FloatArray>()

    private fun roughnessFor(voice: ScourVoice): FloatArray = synchronized(roughness) {
        roughness.getOrPut(voice) {
            val noise = Dsp.Noise(Dsp.seedFor("SCOUR", MODEL_VERSION, voice.name, "ROUGH"))
            val re = FloatArray(TABLE) { noise.next() }
            val im = FloatArray(TABLE)
            Fft.forward(re, im)
            for (k in 0 until TABLE) {
                val bin = minOf(k, TABLE - k)
                val g = if (bin == 0) 0f else (1.0 / sqrt(bin.toDouble())).toFloat()
                re[k] *= g
                im[k] *= g
            }
            Fft.inverse(re, im)
            var peak = 0f
            for (v in re) peak = max(peak, abs(v))
            for (i in re.indices) re[i] /= peak
            re
        }
    }

    /** One render's raw audio at 4×, its mechanics, the mode frequencies it used per tick, and its cost. */
    internal class Played(
        val raw: FloatArray,
        val trace: Trace,
        /** Mode frequencies in Hz per tick, mode-major; only when recorded. */
        val modeHz: Array<FloatArray>?,
        val keptModes: Int,
        val mechanicsNanos: Long,
        val plateNanos: Long,
    )

    internal fun play(voice: ScourVoice, macros: Map<String, Float>, velocity: Float = 1f, record: Boolean = false): Played {
        val began = System.nanoTime()
        val s = shape(voice)
        val m = settled(voice, macros)
        val trace = mechanics(voice, m, velocity)
        val prepared = System.nanoTime()
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val harden = m.getValue("HARDEN").toDouble()
        val ring = m.getValue("RING").toDouble()
        val kept = keptModes(s, hz)
        val last = (s.body.size - 1).toDouble()
        val ratio = DoubleArray(kept.size) { s.body[kept[it]] / s.body[0] }
        val grade = DoubleArray(kept.size) { kept[it] / last }
        val t60s = DoubleArray(kept.size) { s.t60 * s.decay.pow(kept[it]) * if (kept[it] in 1..2) s.pairRing else 1.0 }
        val pickup = DoubleArray(kept.size) { s.gain.pow(kept[it]) * s.heard[kept[it]] * if (kept[it] in 1..2) s.pairLevel else 1.0 }
        val rim = weights(RIM, kept)
        val shotWeights = Array(trace.shotFrames.size) { weights(trace.shotAim[it], kept) }
        val ticks = trace.smoothed.size
        val modeHz = if (record) Array(kept.size) { FloatArray(ticks) } else null
        val bank = Modes.Bank(kept.size, INTERNAL_RATE)
        fun retune(tick: Int) {
            val c = trace.smoothed[tick]
            for (i in kept.indices) {
                val f = hz * ratio[i] * (1 + s.shift * harden * c * grade[i])
                bank.tune(i, f, t60s[i] * ringFactor(s, harden, c, ring))
                modeHz?.get(i)?.set(tick, f.toFloat())
            }
        }
        val table = roughnessFor(voice)
        val v0 = rollerStart(m.getValue("RPM"))
        val dt = 1.0 / INTERNAL_RATE
        val force = DoubleArray(kept.size)
        val out = FloatArray(trace.frames)
        var phase = 0.0
        var first = 0
        for (n in 0 until trace.frames) {
            if (n % CTRL == 0) retune(n / CTRL)
            bank.step()
            val speed = max(0.0, v0 - s.decel * n * dt)
            val roll = if (speed > 0.0) {
                phase += REVS_PER_SECOND * speed * dt
                val x = (phase - floor(phase)) * TABLE
                val i0 = x.toInt() % TABLE
                val frac = x - floor(x)
                val r = table[i0] * (1 - frac) + table[(i0 + 1) % TABLE] * frac
                s.roll * SHOT_FORCE * speed * sqrt(speed) * r
            } else 0.0
            for (i in force.indices) force[i] = rim[i] * roll
            while (first < trace.shotFrames.size && trace.shotFrames[first] + PULSE_SAMPLES <= n) first++
            var j = first
            while (j < trace.shotFrames.size && trace.shotFrames[j] <= n) {
                val p = trace.shotAmplitude[j] * PULSE[n - trace.shotFrames[j]]
                val w = shotWeights[j]
                for (i in force.indices) force[i] += w[i] * p
                j++
            }
            bank.drive(force, 1.0)
            out[n] = (bank.velocityAlong(pickup) * OUTPUT_TRIM).toFloat()
        }
        val done = System.nanoTime()
        return Played(out, trace, modeHz, kept.size, prepared - began, done - prepared)
    }

    /** The house output stage, GYRE's order: band limit, decimate, mean, 20 Hz high-pass, level, tail. */
    internal fun finish(raw: FloatArray, normalize: Boolean = true): FloatArray {
        val buf = raw.copyOf()
        Tide.bandLimit(buf, INTERNAL_RATE)
        val out = Dsp.decimate(buf, RATE)
        var mean = 0.0
        for (v in out) mean += v
        val mm = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mm
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        if (normalize) Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }
}
