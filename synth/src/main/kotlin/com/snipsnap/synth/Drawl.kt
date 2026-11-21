package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/**
 * DRAWL's six voices: one mechanism, a string whose pitch path is the performance.
 *
 * - CONE: resonator guitar. Steel bar, metal fingerpicks, a spun metal cone.
 * - BOTTLE: electric slide. Bottleneck on a fretted electric, bridge pickup, a small amp.
 * - LAP: lap steel. Bar across the strings, dark pickup.
 * - STEEL: pedal steel. Volume-pedal swell, pedals that rise to a stop.
 * - FLATTOP: country steel-string flat-top. Fretted, boomy.
 * - GUT: western nylon. Fretted, finger vibrato, dark and short.
 */
enum class DrawlVoice { CONE, BOTTLE, LAP, STEEL, FLATTOP, GUT }

/**
 * DRAWL: pick → the bar slides the note into place → the bar shimmers → the string rings out
 * through its body.
 *
 * A port of the Python prototype the owner heard and kept, round by round (rounds 0 to 0d,
 * 20 Nov 2026; `prototype/drawl/drawl_proto.py`, design and every measured number in
 * `docs/superpowers/specs/2026-11-20-drawl-engine-design.md`). The pitch path is worked out
 * before the string runs, by one of three laws: a bar carried by a damped arm that lands with a
 * small overshoot, a pedal that travels to a hard stop, or a finger that steps over frets. The
 * string follows it with its own read (see [Read]); a pick shaped as a pulled string excites it;
 * a bar scrapes over wound ribs while it moves; the stretch behind the bar rings faintly; and
 * each voice's body (a cone, a flat-top or nylon box, or a pickup, coil and small amp) reads
 * the string one way.
 *
 * What differs from the prototype, on purpose: the noise comes from [Dsp.Noise] and
 * [kotlin.random.Random] (so no render matches the Python one sample for sample), the level
 * target is [Dsp.MELODIC_LOUDNESS_TARGET] (perceived, not RMS), the path, tuning and loop gain
 * are worked out every [CONTROL] samples and interpolated between (not stored per sample), and
 * the electric voices' speaker band is two cascaded second-order sections rather than one
 * fourth-order band-pass. `prototype/drawl/compare_kotlin.py` measures what that leaves.
 *
 * Velocity and take are performance arguments, not macros ([DrawlPatch] carries them). One gain
 * is shared by every velocity and take of a sound ([performanceGain]), so a soft pick is
 * quieter as well as darker, as in the renders the owner heard. HOLD is finite: it lengthens
 * the note to [MAX_SECONDS] and never loops.
 */
object Drawl {
    private const val RATE = Dsp.RATE
    private const val R = RATE * Dsp.OVERSAMPLE
    private const val RD = R.toDouble()

    const val TUNE_SEMITONES = 24
    const val MAX_SECONDS = 8f
    const val DEFAULT_VELOCITY = 0.75f
    const val LOOP_THRESHOLD_SECONDS = 1.5f
    /** Takes 1 to 3 vary the player; take 0 is the nominal performance. Any take is valid; these are the ones heard. */
    const val TAKES = 4

    /** How often the path, the tuning budget and the loop gain are worked out; samples between are interpolated. */
    private const val CONTROL = 16
    private const val CEILING = 0.99f
    private const val OUTPUT_DC_HZ = 20f

    // ---- the voices ------------------------------------------------------------------------

    private enum class Stop { BAR, FRET, PEDAL }

    private class Mode(val hz: Double, val q: Double, val gain: Double)

    private class Pickup(val position: Double, val coilHz: Double, val coilQ: Double,
                         val driveLo: Double, val driveHi: Double, val speakerHz: Double)

    private class Spec(
        val root: Int,
        val baseSeconds: Float,
        val defaults: List<Float>,          // SLIDE WAVER PICK BODY RING (TUNE .5, HOLD 0)
        val stop: Stop,
        val t60: Pair<Double, Double>,      // RING 0 and 1, seconds, exponential
        val loopHz: Double,
        val pickHz: Pair<Double, Double>,   // PICK 0 and 1, exponential
        val click: Double,
        val depthScale: Double,
        val tensionCents: Double,
        val tensionSeconds: Double,
        val slideTime: Double,
        val modes: List<Mode> = emptyList(),
        val pickup: Pickup? = null,
    )

    // Every number below is the round-0d prototype's (drawl_proto.py), where each is labelled
    // measured or shape. CONE's first four modes and FLATTOP's and GUT's mid modes are the
    // reference loops' measured resonance peaks; the rest are shape values tuned by ear in
    // rounds 0b to 0d (twang, whine, slower and shallower vibrato).
    private val SPECS: Map<DrawlVoice, Spec> = mapOf(
        DrawlVoice.CONE to Spec(57, 2.5f, listOf(.30f, .45f, .75f, .60f, .55f), Stop.BAR, .8 to 8.0, 6500.0,
            700.0 to 7000.0, 1.0, 1.0, 28.0, .08, 1.0,
            modes = listOf(Mode(580.0, 6.0, 1.0), Mode(820.0, 5.0, .8), Mode(1180.0, 7.0, .7), Mode(2350.0, 6.0, .8))),
        DrawlVoice.BOTTLE to Spec(62, 3.0f, listOf(.40f, .60f, .55f, .60f, .85f), Stop.BAR, 1.2 to 9.0, 10000.0,
            1500.0 to 11000.0, .5, 1.0, 12.0, .12, 1.35,
            pickup = Pickup(.07, 2600.0, 4.5, 2.0, 9.0, 6000.0)),
        DrawlVoice.LAP to Spec(62, 3.5f, listOf(.55f, .40f, .40f, .50f, .65f), Stop.BAR, 1.5 to 10.0, 8000.0,
            1500.0 to 11000.0, .6, 1.0, 12.0, .12, 1.25,
            pickup = Pickup(.10, 2200.0, 3.5, 1.5, 6.0, 5000.0)),
        DrawlVoice.STEEL to Spec(64, 4.0f, listOf(.60f, .30f, .30f, .50f, .80f), Stop.PEDAL, 2.5 to 14.0, 8000.0,
            6000.0 to 12000.0, .6, .6, 12.0, .12, 1.0,
            pickup = Pickup(.08, 5000.0, 1.8, .6, 2.0, 7000.0)),
        DrawlVoice.FLATTOP to Spec(50, 2.5f, listOf(.15f, .15f, .45f, .70f, .65f), Stop.FRET, 1.2 to 8.0, 3200.0,
            700.0 to 7000.0, .8, .35, 12.0, .12, 1.0,
            modes = listOf(Mode(100.0, 10.0, .9), Mode(205.0, 12.0, .8), Mode(431.0, 6.0, .6),
                Mode(598.0, 6.0, .45), Mode(727.0, 7.0, .35), Mode(1500.0, 5.0, .2))),
        DrawlVoice.GUT to Spec(52, 2.5f, listOf(.10f, .45f, .35f, .60f, .55f), Stop.FRET, 1.0 to 6.0, 2600.0,
            500.0 to 4500.0, 0.0, .45, 12.0, .12, 1.0,
            modes = listOf(Mode(95.0, 10.0, .8), Mode(190.0, 10.0, .7), Mode(425.0, 6.0, .5),
                Mode(501.0, 6.0, .45), Mode(764.0, 6.0, .3))),
    )

    /** CONE's cone and cover plate ringing over its roll-off (shape). */
    private val CONE_RING = listOf(Mode(3300.0, 25.0, .12), Mode(4700.0, 30.0, .08))
    private const val CONE_ROLLOFF_HZ = 5500.0
    private const val CONE_HIGHPASS_HZ = 120.0
    private const val NOISE_SHARE = .25
    private const val SCALE_M = .635        // scale length, m (shape)
    private const val RIB_M = .00035        // wound-string rib pitch, m (shape)

    private val NAMES = listOf("SLIDE", "WAVER", "PICK", "BODY", "RING")

    fun macrosFor(voice: DrawlVoice): List<MacroSpec> {
        val d = SPECS.getValue(voice).defaults
        return listOf(MacroSpec("TUNE", .5f, neutral = .5f)) +
            NAMES.mapIndexed { i, name -> MacroSpec(name, d[i]) } +
            MacroSpec("HOLD", 0f)
    }

    fun defaults(voice: DrawlVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** Clamped to 0..1, unknown keys dropped, a non-finite value replaced by its default. */
    fun settled(voice: DrawlVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun rootMidi(voice: DrawlVoice): Int = SPECS.getValue(voice).root

    /** TUNE .5 is the voice's root; TUNE spans an octave either side, in semitones. */
    fun midiFor(voice: DrawlVoice, tune: Float): Int =
        rootMidi(voice) + ((tune.coerceIn(0f, 1f) - .5f) * TUNE_SEMITONES).roundToInt()

    fun frequencyFor(voice: DrawlVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    fun seconds(voice: DrawlVoice, hold: Float): Float {
        val base = SPECS.getValue(voice).baseSeconds
        return base + (MAX_SECONDS - base) * hold.coerceIn(0f, 1f)
    }

    /** The render's length in output frames; [drumClassFor] agrees with it to the frame. */
    fun renderFrames(voice: DrawlVoice, macros: Map<String, Float>): Int =
        framesAt(seconds(voice, settled(voice, macros).getValue("HOLD")))

    private fun framesAt(seconds: Float): Int = (seconds * R).toInt() / Dsp.OVERSAMPLE

    /** HOLD is finite: it lengthens the note and never loops. */
    @Suppress("UNUSED_PARAMETER")
    fun isLoop(hold: Float): Boolean = false

    /** Pitched, never a drum: over [LOOP_THRESHOLD_SECONDS] the classifier files a render as LOOP. */
    fun drumClassFor(voice: DrawlVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (renderFrames(voice, macros).toFloat() / RATE > LOOP_THRESHOLD_SECONDS) DrumClass.LOOP else DrumClass.TONAL

    fun scramble(voice: DrawlVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    // ---- one performance -------------------------------------------------------------------

    /**
     * The numbers one render plays: the macros' laws, the velocity and the take's variation.
     * A take varies the player (pick shape and place, slide time, landing, vibrato) and never
     * the target pitch, as ACCORD's takes do.
     */
    private class Performance(val voice: DrawlVoice, m: Map<String, Float>, val velocity: Double, val take: Int, val midi: Int) {
        val spec = SPECS.getValue(voice)
        val seed = Dsp.seedFor("DRAWL", voice.name, midi, take)
        private val u = DoubleArray(8).also { u ->
            if (take > 0) {
                val r = Random(Dsp.seedFor("DRAWL", voice.name, midi, take, "TAKE"))
                for (i in u.indices) u[i] = r.nextDouble() * 2.0 - 1.0
            }
        }
        val pick = m.getValue("PICK").toDouble()
        val pickHz = expMap(pick, spec.pickHz.first, spec.pickHz.second) * 2.0.pow(2.5 * (velocity - .75)) * (1 + .10 * u[0])
        val tensionCents = spec.tensionCents * velocity * velocity * (1 + .1 * u[7])
        val position = lin(pick, .30, .07) * (1 + .06 * u[1])
        val click = spec.click * pick * velocity * velocity
        val swell = if (spec.stop == Stop.PEDAL) lin(1 - pick, .02, .7) else 0.0
        private val slide = m.getValue("SLIDE").toDouble()
        private val waver = m.getValue("WAVER").toDouble()
        val extent: Double
        val slideSeconds: Double
        init {
            when (spec.stop) {
                Stop.BAR -> {
                    extent = 1200 * slide.pow(1.3)
                    slideSeconds = (.07 + .012 * extent / 100) * spec.slideTime * (1 + .10 * u[2])
                }
                Stop.FRET -> {
                    extent = 100.0 * Math.round(3 * slide)
                    slideSeconds = (.04 + .03 * extent / 100) * (1 + .10 * u[2])
                }
                Stop.PEDAL -> {
                    extent = 200 * slide
                    slideSeconds = .28 * (1 + .10 * u[2])
                }
            }
        }
        val zeta = .8 + .05 * u[3]
        val vibDepth = 150 * waver.pow(1.3) * (1 - .2 * waver) * spec.depthScale * (1 + .08 * u[4])
        val vibRate = (5.0 + 2.6 * waver - .8 * waver * waver) * (1 + .04 * u[5])
        val vibPhase = PI * (1 + u[6])
        val ring = m.getValue("RING").toDouble()
        val t60 = expMap(ring, spec.t60.first, spec.t60.second)
        val loopHz = spec.loopHz * lin(ring, .75, 1.25)
        val ghost = if (spec.stop == Stop.BAR) .06 * ring.pow(1.5) else 0.0
        val body = m.getValue("BODY").toDouble()
        val seconds = Drawl.seconds(voice, m.getValue("HOLD"))
        val frames = (seconds * R).toInt()
        val target = midiHz(midi)
        /** The open string under the bar: five semitones below the slide's lowest point for a bar, one for the rest. */
        val open = target * 2.0.pow((min(0.0, -extent) - 100 - (if (spec.stop == Stop.BAR) 500 else 0)) / 1200)
    }

    // ---- the pitch path --------------------------------------------------------------------

    /**
     * Cents against the target at control rate, and the part of it that drags the bar along
     * the ribs. Bar voices: the arm's landing plus bar vibrato. Fretted: the same landing
     * stepped over frets, then finger vibrato (one-sided on FLATTOP, a bend only rises).
     * STEEL: the pedal ramp to a stop under a gentle bar vibrato after the swell. A hard pick
     * adds tension modulation: the note starts sharp and settles.
     */
    private class Path(p: Performance) {
        val steps = p.frames / CONTROL + 2
        val cents = DoubleArray(steps)
        val scraping = DoubleArray(steps)

        init {
            val dt = CONTROL / RD
            val n = p.frames
            // the base: landing, frets or pedal
            val base = DoubleArray(steps)
            val onset: Double
            if (p.spec.stop == Stop.PEDAL) {
                val start = max(.4, p.swell * .8)
                onset = start + p.slideSeconds
                if (p.extent > 0) {
                    val k = exp(-dt / .005)
                    var y = -p.extent
                    for (i in 0 until steps) {
                        val t = i * dt
                        val c = -p.extent * (1 - ((t - start) / max(p.slideSeconds, 1e-3)).coerceIn(0.0, 1.0))
                        y = (1 - k) * c + k * y
                        base[i] = y
                    }
                }
            } else {
                onset = if (p.extent > 0) p.slideSeconds else .05
                if (p.extent > 0) {
                    // The arm is a damped mass-spring aimed at the note, ~90 % there at slideSeconds;
                    // with zeta .8 it overshoots about 1.5 % and settles: the bar lands, then corrects.
                    val ctrl = 2000.0
                    val wn = 3.2 / max(p.slideSeconds, 1e-3)
                    val armSteps = (n / RD * ctrl).toInt() + 2
                    val arm = DoubleArray(armSteps)
                    var x = -p.extent
                    var v = 0.0
                    for (i in 0 until armSteps) {
                        arm[i] = x
                        val acc = wn * wn * (0 - x) - 2 * p.zeta * wn * v
                        v += acc / ctrl
                        x += v / ctrl
                    }
                    val fretK = exp(-dt / .002)
                    var fret = Math.round(arm[0] / 100.0) * 100.0
                    for (i in 0 until steps) {
                        val pos = i * dt * ctrl
                        val j = min(pos.toInt(), armSteps - 2)
                        val landed = arm[j] + (arm[j + 1] - arm[j]) * (pos - j).coerceAtMost(1.0)
                        base[i] = if (p.spec.stop == Stop.FRET) {
                            fret = (1 - fretK) * (Math.round(landed / 100.0) * 100.0) + fretK * fret
                            fret
                        } else landed
                    }
                }
            }
            // the hand: vibrato after the landing, waiting 150 ms and fading in over 350 ms, its
            // rate wandering 3 % on seeded knots
            val wander = Random(p.seed + 7)
            val knots = max(4, (n / RD * 1.5).toInt() + 4)
            val knot = DoubleArray(knots) { wander.nextDouble() * 2 - 1 }
            var phase = p.vibPhase
            val oneSided = p.voice == DrawlVoice.FLATTOP
            for (i in 0 until steps) {
                val t = i * dt
                val kp = (i.toDouble() * CONTROL / max(1, n - 1)) * (knots - 3)
                val kj = min(kp.toInt(), knots - 2)
                val w = 1 + .03 * (knot[kj] + (knot[kj + 1] - knot[kj]) * (kp - kj).coerceAtMost(1.0))
                phase += 2 * PI * p.vibRate * w * dt
                val r = ((t - onset - .15) / .35).coerceIn(0.0, 1.0)
                val ramp = .5 - .5 * cos(PI * r)
                val s = if (oneSided) .5 - .5 * cos(phase) else sin(phase)
                val vib = p.vibDepth * ramp * s + p.tensionCents * exp(-t / p.spec.tensionSeconds)
                cents[i] = base[i] + vib
                // what drags along the ribs: the slide, and a little of the vibrato (a bar vibrato
                // mostly rocks the contact; 15 % is a shape value)
                scraping[i] = base[i] + .15 * vib
            }
        }

        fun at(i: Int, a: DoubleArray): Double {
            val j = i / CONTROL
            val f = (i % CONTROL).toDouble() / CONTROL
            return a[j] + (a[j + 1] - a[j]) * f
        }
    }

    // ---- the string ------------------------------------------------------------------------

    /**
     * One waveguide string with a moving length: a cubic Lagrange read off a delay line, a
     * one-pole loop low-pass and a per-trip loop gain. This is the read the owner heard; the
     * house [Strings.Loop] (integer delay, two-tap average, allpass fraction) measured within a
     * dB on every probe but moved STEEL further than its own takes differ (distance 2.5 against
     * 1.0 to 2.2, round-1 measurement), so DRAWL keeps the heard read. The delay carries no
     * filter state across integer steps, so a slide cannot zipper.
     */
    private class Read(maxDelay: Double, loopHz: Double) {
        private val size = maxDelay.toInt() + 8
        private val h = DoubleArray(size)
        private var i = 0
        private var lp = 0.0
        private val a = lpCoefficient(loopHz)

        fun next(input: Double, delay: Double, gain: Double): Double {
            val d = delay.coerceIn(2.0, size - 4.0)
            val n = floor(d).toInt()
            val f = d - n
            var x = 0.0
            if (i > n + 3) {
                val xm1 = h[(i - n + 1) % size]
                val x0 = h[(i - n) % size]
                val x1 = h[(i - n - 1) % size]
                val x2 = h[(i - n - 2) % size]
                x = (-f * (f - 1) * (f - 2) / 6.0) * xm1 + ((f + 1) * (f - 1) * (f - 2) / 2.0) * x0 +
                    (-(f + 1) * f * (f - 2) / 2.0) * x1 + ((f + 1) * f * (f - 1) / 6.0) * x2
            }
            lp += a * (x - lp)
            val y = input + gain * lp
            h[i % size] = y
            i++
            return y
        }
    }

    private fun lpCoefficient(loopHz: Double): Double = 1.0 - exp(-2 * PI * min(loopHz, RD * .45) / RD)

    /** The loop's delay for [hz]: one period less the loop low-pass's phase delay at the fundamental. */
    private fun delayFor(hz: Double, loopHz: Double): Double {
        val r = 1 - lpCoefficient(loopHz)
        val w = 2 * PI * hz / RD
        return RD / hz - atan2(r * sin(w), 1 - r * cos(w)) / w
    }

    /** The loop gain per trip so the fundamental falls 60 dB in [t60] wherever the bar is (the hand's loss is per second). */
    private fun gainFor(hz: Double, t60: Double, loopHz: Double): Double {
        val a = lpCoefficient(loopHz)
        val w = 2 * PI * hz / RD
        val re = 1 - (1 - a) * cos(w)
        val im = (1 - a) * sin(w)
        val mag = a / sqrt(re * re + im * im)
        return min(10.0.pow(-3.0 / (t60 * hz)) / mag, .99995)
    }

    // ---- the pick ---------------------------------------------------------------------------

    /**
     * A shaped pluck: the slope of the string pulled into a triangle peaking at the pick
     * position (harmonic k starts at sin(pi k p) / k, so the fundamental is never left to
     * chance), plus [NOISE_SHARE] of combed noise for the pick's roughness (more on a harder
     * pick), both low-passed by the pick's hardness, plus a metal pick's 0.6 ms click. The
     * house exciter is all noise, which dealt a LAP take's second harmonic 1 to 13 dB over its
     * fundamental depending on the seed (round 0).
     */
    private fun pick(p: Performance, startHz: Double): DoubleArray {
        val period = max(2, (RD / startHz).toInt())
        val noise = Dsp.Noise(p.seed)
        val k = exp(-2 * PI * p.pickHz / RD)
        val pos = p.position.coerceIn(.02, .5)
        val pull = DoubleArray(period) { if (it.toDouble() / period < pos) 1 / pos else -1 / (1 - pos) }
        demean(pull); onePole(pull, k); demean(pull); peakNormalize(pull)
        val raw = DoubleArray(period) { noise.next().toDouble() }
        onePole(raw, k); demean(raw); peakNormalize(raw)
        val d = max(1, (pos * period).roundToInt())
        val combed = DoubleArray(period) { if (it >= d) raw[it] - raw[it - d] else raw[it] }
        peakNormalize(combed)
        val share = NOISE_SHARE * lin(p.velocity, .6, 1.4)
        val clickN = (.0006 * RD).toInt()
        val out = DoubleArray(max(period, clickN))
        for (i in 0 until period) out[i] = ((1 - share) * pull[i] + share * combed[i]) * p.velocity
        if (p.click > 0) {
            var x1 = 0.0
            var y1 = 0.0
            for (i in 0 until clickN) {
                val x = noise.next().toDouble()
                val y = x - x1 + .6 * y1
                x1 = x; y1 = y
                val w = .5 - .5 * cos(2 * PI * i / (clickN - 1))
                out[i] += .5 * p.click * y * w
            }
        }
        return out
    }

    // ---- bodies -----------------------------------------------------------------------------

    /** A second-order section in Double (the low modes at 176.4 kHz need the precision). */
    private class Section(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun next(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            /** scipy's iirpeak: a unit-peak band-pass at [hz] with bandwidth hz / q. */
            fun peak(hz: Double, q: Double): Section {
                val w0 = 2 * PI * hz / RD
                val beta = tan(w0 / q / 2)
                val g = 1 / (1 + beta)
                return Section(1 - g, 0.0, -(1 - g), -2 * g * cos(w0), 2 * g - 1)
            }

            /** RBJ low-pass; at q 1/sqrt(2) it is the second-order Butterworth. */
            fun lowpass(hz: Double, q: Double = SQRT_HALF): Section {
                val w = 2 * PI * hz / RD
                val alpha = sin(w) / (2 * q)
                val a0 = 1 + alpha
                val c = cos(w)
                return Section((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            fun highpass(hz: Double, q: Double = SQRT_HALF): Section {
                val w = 2 * PI * hz / RD
                val alpha = sin(w) / (2 * q)
                val a0 = 1 + alpha
                val c = cos(w)
                return Section((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            private val SQRT_HALF = sqrt(.5)
        }
    }

    /** The string read one way by its body: a cone, a wooden box, or a pickup, coil and amp. */
    private fun body(p: Performance, x: DoubleArray): FloatArray {
        val out = FloatArray(x.size)
        val pickup = p.spec.pickup
        if (pickup != null) {
            // A pickup at a fixed distance from the bridge combs the string with a fixed delay
            // in samples wherever the bar is (the stopped length times frequency is constant).
            val d = max(1, (pickup.position * RD / p.open).roundToInt())
            val coil = Section.lowpass(pickup.coilHz, pickup.coilQ)
            val presence = if (p.voice == DrawlVoice.STEEL) Section.peak(1500.0, 1.2) else null
            val drive = lin(p.body, pickup.driveLo, pickup.driveHi)
            val hp = Section.highpass(80.0)
            val lp = Section.lowpass(pickup.speakerHz)
            for (i in x.indices) {
                var y = coil.next(x[i] - (if (i >= d) x[i - d] else 0.0))
                if (presence != null) y += 1.6 * presence.next(y)
                y = tanh(drive * y) / drive
                out[i] = lp.next(hp.next(y)).toFloat()
            }
            return out
        }
        val mix = lin(p.body, .25, .9)
        val modes = p.spec.modes.map { it to Section.peak(it.hz, it.q) }
        val cone = p.voice == DrawlVoice.CONE
        // The biscuit drives the cone: an asymmetric soft curve whose drive rises with BODY and,
        // because the string scales with the pick, with velocity. The cone does not boom.
        val drive = lin(p.body, 1.0, 6.0)
        val hp = if (cone) Section.highpass(CONE_HIGHPASS_HZ) else null
        val roll = if (cone) Section.lowpass(CONE_ROLLOFF_HZ) else null
        val rings = if (cone) CONE_RING.map { it to Section.peak(it.hz, it.q) } else emptyList()
        for (i in x.indices) {
            var y = x[i]
            if (cone) y = hp!!.next(tanh(drive * (y + .25 * y * y)) / drive)
            var wet = 0.0
            for ((m, s) in modes) wet += m.gain * s.next(y)
            var o = (1 - mix) * y + mix * 2.5 * wet
            if (cone) {
                o = roll!!.next(o)
                for ((m, s) in rings) o += mix * 2.5 * m.gain * s.next(y)
            }
            out[i] = o.toFloat()
        }
        return out
    }

    // ---- render -----------------------------------------------------------------------------

    /** The raw string and body at the internal rate, before the output stage. */
    internal fun raw(voice: DrawlVoice, m: Map<String, Float>, velocity: Float, take: Int): FloatArray {
        val p = Performance(voice, m, velocity.toDouble(), take, midiFor(voice, m.getValue("TUNE")))
        val path = Path(p)
        val n = p.frames
        val bar = p.spec.stop == Stop.BAR
        val wound = if (p.target < 260) 1.0 else .25
        // control-rate quantities, interpolated per sample
        val steps = path.steps
        val delay = DoubleArray(steps)
        val gain = DoubleArray(steps)
        val ghostDelay = DoubleArray(steps)
        val ghostGain = DoubleArray(steps)
        val scrapeG = DoubleArray(steps)
        val scrapeLevel = DoubleArray(steps)
        var minHz = Double.MAX_VALUE
        var length = SCALE_M * p.open / (p.target * 2.0.pow(path.scraping[0] / 1200))
        var speed = 0.0
        val speedK = exp(-(CONTROL / RD) / .003)
        for (i in 0 until steps) {
            val hz = p.target * 2.0.pow(path.cents[i] / 1200)
            minHz = min(minHz, hz)
            delay[i] = delayFor(hz, p.loopHz)
            gain[i] = gainFor(hz, p.t60, p.loopHz)
            if (p.ghost > 0) {
                // behind the bar: the stretch between bar and nut, its pitch moving against the note's
                val stopped = (p.open / hz).coerceIn(0.0, .97)
                val behind = min(p.open / (1 - stopped), 6000.0)
                ghostDelay[i] = delayFor(behind, 5000.0)
                ghostGain[i] = gainFor(behind, .6, 5000.0)
            }
            if (bar) {
                // the bar over a wound string's ribs: rib-passing frequency is bar speed / rib pitch
                val now = SCALE_M * p.open / (p.target * 2.0.pow(path.scraping[i] / 1200))
                val v = abs(now - length) / (CONTROL / RD)
                length = now
                speed = (1 - speedK) * v + speedK * speed
                val fc = (speed / RIB_M).coerceIn(150.0, 9000.0)
                scrapeG[i] = tan(PI * fc / RD)
                scrapeLevel[i] = min(speed / .5, 2.0) * .05 * wound
            }
        }
        val string = Read(RD / minHz + 8, p.loopHz)
        val behind = if (p.ghost > 0) Read(RD / p.open + 8, 5000.0) else null
        val exc = pick(p, p.target * 2.0.pow(path.cents[0] / 1200))
        val noise = Dsp.Noise(p.seed + 11)
        var ic1 = 0.0
        var ic2 = 0.0
        val k = 1 / 3.0
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var input = if (i < exc.size) exc[i] else 0.0
            if (bar) {
                // the scrape: noise through a TPT SVF band-pass at the rib frequency (Q 3)
                val g = path.at(i, scrapeG)
                val a1 = 1 / (1 + g * (g + k))
                val a2 = g * a1
                val a3 = g * a2
                val v3 = noise.next() - ic2
                val v1 = a1 * ic1 + a2 * v3
                val v2 = ic2 + a2 * ic1 + a3 * v3
                ic1 = 2 * v1 - ic1
                ic2 = 2 * v2 - ic2
                input += v1 * path.at(i, scrapeLevel)
            }
            var y = string.next(input, path.at(i, delay), path.at(i, gain))
            if (behind != null) y += behind.next(y * p.ghost * .02, path.at(i, ghostDelay), path.at(i, ghostGain))
            out[i] = y
        }
        val shaped = body(p, out)
        if (p.swell > 0) {
            for (i in shaped.indices) {
                val r = (i / RD / p.swell).coerceAtMost(1.0)
                shaped[i] *= (.5 - .5 * cos(PI * r)).toFloat()
            }
        }
        return shaped
    }

    /** The house output stage up to the level: band limit, decimate, mean, 20 Hz high-pass. */
    private fun conditioned(voice: DrawlVoice, m: Map<String, Float>, velocity: Float, take: Int): FloatArray {
        val raw = raw(voice, m, velocity, take)
        Tide.bandLimit(raw, R)
        val out = Dsp.decimate(raw, RATE)
        var mean = 0.0
        for (x in out) mean += x
        val mm = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mm
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        return out
    }

    private data class GainKey(val voice: DrawlVoice, val macros: Map<String, Float>)
    private val gains = ConcurrentHashMap<GainKey, Float>()
    private const val GAINS = 256

    /**
     * One gain per sound, shared by every velocity and take (the ACCORD rule): the medium touch
     * of take 0 sets the loudness, lowered only so the hardest touch of take 0 clears the
     * ceiling. A soft pick is quieter as well as darker. Two extra renders the first time a
     * sound is played, then cached.
     */
    internal fun performanceGain(voice: DrawlVoice, m: Map<String, Float>): Float {
        val key = GainKey(voice, m)
        gains[key]?.let { return it }
        val medium = conditioned(voice, m, DEFAULT_VELOCITY, 0)
        val loud = Loudness.of(medium, 1, RATE)
        var g = if (loud > 1e-6f) Dsp.MELODIC_LOUDNESS_TARGET / loud else 1f
        val hard = conditioned(voice, m, 1f, 0)
        var peak = 0f
        for (x in hard) if (x.isFinite()) peak = max(peak, abs(x))
        if (peak > 1e-9f) g = min(g, CEILING / peak)
        if (gains.size >= GAINS) gains.clear()
        gains[key] = g
        return g
    }

    /** The engine. Pure: the same inputs give the same samples. */
    fun render(voice: DrawlVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = DEFAULT_VELOCITY, take: Int = 0): Snip {
        require(take >= 0) { "DRAWL take must not be negative: $take" }
        val m = settled(voice, macros)
        val v = if (velocity.isFinite()) velocity.coerceIn(0f, 1f) else DEFAULT_VELOCITY
        val out = conditioned(voice, m, v, take)
        val g = performanceGain(voice, m)
        for (i in out.indices) out[i] *= g
        Dsp.limitPeak(out, CEILING) // only a take hotter than take 0 at full touch can reach this
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    // ---- small helpers ----------------------------------------------------------------------

    private fun lin(x: Double, lo: Double, hi: Double) = lo + (hi - lo) * x
    private fun expMap(x: Double, lo: Double, hi: Double) = lo * (hi / lo).pow(x)
    private fun midiHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun demean(a: DoubleArray) {
        val m = a.average()
        for (i in a.indices) a[i] -= m
    }

    private fun onePole(a: DoubleArray, k: Double) {
        var y = 0.0
        for (i in a.indices) {
            y = (1 - k) * a[i] + k * y
            a[i] = y
        }
    }

    private fun peakNormalize(a: DoubleArray) {
        var p = 1e-9
        for (x in a) p = max(p, abs(x))
        for (i in a.indices) a[i] /= p
    }
}
