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
 * Four bodies under the same blast: a round metal plate that keeps its hardening, a square glass pane
 * that gives it back, a long thin steel sheet an octave lower (a thunder sheet), and a steel beam that
 * clangs. Each has its own body: its own sourced overtone pattern.
 */
enum class ScourVoice { PLATE, PANE, SHEET, BEAM }

/**
 * SCOUR: an impact that builds. A roller spins up against a free plate while shot rains on it faster and
 * harder; each shot dents the surface, the dented fraction (coverage) climbs, and the plate's upper
 * partials climb with it, so the build rises. Then one heavy slam lands and the hardened plate rings
 * out. The characteristic gesture is the roller spins up, the shot rain accelerates, the plate stiffens
 * and its ring climbs, the slam lands, and the hardened plate rings: keeping its tuning (PLATE, BEAM,
 * and SHEET an octave down) or giving it back as it rings (PANE).
 *
 * Computed: the roller's spin-up, read against a fixed surface at its contact phase; a seeded Poisson
 * shot schedule whose rate rises twentyfold over the build; coverage by the random-dent recursion
 * `S += κ·e·(1 − S)`; the plate, a [Modes.Bank]. Analogy: coverage moving the modal frequencies and damping (real cold work raises
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
 * sound the note; this departure is deliberate. The blast lands where R1's TRAVERSE default put it.
 *
 * R2 reshaped R1's roll-then-blast into this build and hit after the owner's second listen: of three
 * phrases only the impact was a maybe ("a gong with static sounds on top" for the riff), and RING .5,
 * hardening leaving the ring alone, was the owner's pick for both voices, so the ring law is fixed
 * there and HOLD sets the ring's length instead. HOLD never loops: after a bed was a no, the owner's
 * third listen dropped the held loop, so HOLD's whole range, top step included, is how long it rings.
 */
object Scour {
    /**
     * Seeds the draws. Every saved SCOUR sound regenerates through it: the presets, the starter kit's pads, and
     * anything saved from the phone's synth picker. Changing it changes them all.
     */
    const val MODEL_VERSION = 1
    /** PLATE's, PANE's and BEAM's lowest note, C3. */
    const val ROOT_MIDI = 48
    /** SHEET's lowest note, C2: an octave down for weight, and still above the house `Pitch.detect`'s 40 Hz floor. */
    const val SHEET_ROOT_MIDI = 36
    const val TUNE_SEMITONES = 24
    /** The classifier files anything longer than this as LOOP (`Classifier`'s own constant is private). */
    const val LOOP_MIN_SECONDS = 1.5f
    /**
     * No render is shorter than this, so no SCOUR sound falls under the classifier's 1.5 s, where its
     * feature rules could file a plate as a drum. The shortest build and ring (BUILD 0 on PANE, 1.65 s)
     * already clear it; the floor states the promise.
     */
    const val MIN_RENDER_SECONDS = 1.6
    /** BUILD's range, seconds, on an exponential law: .25 s is a hit, 2 s (the default) one bar at 120 BPM. */
    const val BUILD_MIN_SECONDS = .25f
    const val BUILD_MAX_SECONDS = 8f

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
    /**
     * How far the shot rate rises over the build: it starts at SHOT's rate over this and ends at SHOT's
     * rate, exponentially, so the rain accelerates evenly on a log scale (shape).
     */
    internal const val ACCEL = 20.0
    /** Each shot's force rises over the build from this fraction to 1, as `(t/BUILD)²` (shape). */
    internal const val SWELL_FLOOR = .25
    /** The slam: a half-sine over 0.6 ms, a padded mallet next to the shots' grit, with this peak force (shape). */
    private const val SLAM_FORCE = 30.0
    private val SLAM_SAMPLES = (.0006 * INTERNAL_RATE).roundToInt()
    /** The slam stops the roller over this long (shape): long enough not to click, short enough to clear the ring. */
    private const val ROLLER_STOP_SECONDS = .06
    private const val OUTPUT_DC_HZ = 20f
    /**
     * Fixed radiation trim, never per render. Measured over the {0, 1} corners of TUNE, BUILD, RPM, SHOT and
     * HARDEN: PLATE's and PANE's loudest raw peak after the output stage is 0.73 (PLATE at C3 with BUILD 0 and
     * RPM 1, the slam landing on a roller at full speed), and their defaults sit at 0.53 (PLATE) and 0.43
     * (PANE). SHEET, an octave lower and ringing longer, would peak at 1.26 (C2 after the longest build at
     * full roller and blast), so it takes [Shape.trim] .55 of the trim: 0.69 at that corner and 0.25 at its
     * default, inside PCM range for the audition's unlevelled copies. BEAM keeps the whole trim: 0.65 at the
     * same corner at C3, 0.43 at its default.
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
    private val SLAM_PULSE = DoubleArray(SLAM_SAMPLES) { sin(PI * (it + .5) / SLAM_SAMPLES) }

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

    /**
     * SHEET's body: a completely free steel rectangle three times as long as it is wide (a theatre thunder
     * sheet, 0.6 by 1.8 m), ν = 0.3. `λ = ωa²√(ρh/D)` with a the long side, root first. The values come from
     * the same Rayleigh-Ritz solve as PANE's, run on the rectangle (converged to 1.3e-7). That solve
     * reproduces the square's entries at 1:1, and its long-plate bending modes approach the free-free
     * beam's `(βL)²·√(1 − ν²)` (Leissa Table 4.72's beam row, 22.373, 61.673, 120.903, 199.860).
     *
     * The sheet is heard near one end, .8036 of the way from the centre along the half-length and .40
     * across the half-width: on the nodal line of its third bending mode, 5.468. That mode sits near 11/2,
     * so one root period later it is in opposite phase: heard (first at .91 and .27, where it moves
     * two-thirds as much as the root) it split the house `Pitch.detect`'s autocorrelation peak at the
     * root's period in two, and C2 read a semitone flat or sharp. PANE's move again: a position on the sheet, not a filter. Of the
     * first sixteen modes, the fourteen that move at least a tenth as much as the root there are kept
     * (18.952, at .090, is left out). Its overtones crowd together above nine times the root, which is the
     * rumble. Ratios 1, 1.837, 2.778, 3.881, 6.328, 8.947, 9.331, 9.357, 10.222, 12.035, 13.112, 13.821,
     * 15.143, 17.676.
     */
    private val RECT = doubleArrayOf(
        21.44, 39.38, 59.55, 83.19, 135.65, 191.81, 200.04, 200.60, 219.13, 258.00, 281.08, 296.29, 324.64, 378.94,
    )

    /** Each RECT mode's motion at SHEET's listening point, from the same solve, as a fraction of the root's. */
    private val SHEET_HEARD = doubleArrayOf(1.0, .678, .452, .528, .327, .161, .361, .103, .413, .236, .119, .711, .199, .313)

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
        /** How far full coverage at HARDEN 1 lifts the top mode. */
        val shift: Double,
        /** The roller's force against a shot's. */
        val roll: Double,
        /** The voice's share of [OUTPUT_TRIM]: a fixed level, never per render. */
        val trim: Double = 1.0,
    )

    /**
     * PLATE's (1,1) mode, 3.906, is heard at .6 of the gain law (measured in R1.1, before the impact). R1's
     * tightening ring kept it short; at a longer ring and full blast, stretched toward 4.4, it pulled the note
     * sharp in the tail (+116 to +139 cents at SHOT 1, HARDEN 1 and R1.1's RING 1, three of the 37 corners
     * `ScourTest` then asserted; silencing it alone fixed them). At the onset, unstretched near 4, it supports the root, so too little costs a default:
     * at .5 F♯4's onset read +54 cents. At .7 one corner still read +107; .6 reads all 37 at both windows
     * (324 and 312 of a 324-corner grid) and raises HARDEN's log-spectral reach from 1.88 to 1.93 dB.
     */
    private val PLATE_HEARD = doubleArrayOf(1.0, 1.0, 1.0, .6, 1.0, 1.0, 1.0, 1.0)
    private val PLATE = Shape(CIRCLE, .4, .5, PLATE_HEARD, 2.2, .80, .72, .012, .15, .35, .06)
    /**
     * PANE's pair at .5 of the gain law and .7 of its ring: of the 37 corners `ScourTest` asserts, full level
     * read 34 at the onset and 31 in the tail, .7 and .7 read 36 and 36 (missing the defaults at C3 and C4),
     * and .5 and .7 read all 37 at both windows (312 and 314 of a 324-corner grid). The rest is R1's shape.
     */
    private val PANE = Shape(SQUARE, .5, .7, SQUARE_HEARD, 1.4, .74, .65, .03, 1.5, .25, .05)
    /**
     * SHEET's pair at .5 of the gain law and .7 of its ring, as PANE's, under a gain law of .75. Measured at
     * C2, where the house `Pitch.detect` cannot lean on its spectral refine (a semitone there is under two
     * FFT bins). At the listening point, with the pair at full level and a gain law of .75, 15 of the 17 C2
     * corners `ScourTest` asserts read at both windows (1.837 and 2.778 sit under 2 and 3 and pull the period long); at .5 and
     * .7 all 17 read under gain laws from .75 to .85. On a 243-corner grid at C2, E♭2 and F2 (every other
     * control at 0, .5 and 1), .75 reads all 243 at both windows, .80 241 and 243, .85 231 and 241. The rest
     * is shape: a 4 s root ring with each mode above .88 as long, steel that keeps its hardening (κ .02,
     * ρ .1), a smaller stretch than PLATE's, and a louder roller, since a sheet rumbles under anything that
     * rubs it.
     */
    private val SHEET = Shape(RECT, .5, .7, SHEET_HEARD, 4.0, .88, .75, .02, .1, .3, .1, trim = .55)

    /**
     * BEAM's body: a free-free steel beam (a girder hung by its ends), heard in bending. Its modes are the
     * roots of `cos βL · cosh βL = 1`, and `(βL)² = ωL²√(ρA/EI)` is listed root first; the first five are
     * Leissa Table 4.72's beam row (22.373, 61.673, 120.903, 199.860, 298.556). Ratios 1, 2.757, 5.404,
     * 8.933, 13.344, 18.638, 24.814, 31.872: few, wide apart and nearly evenly stretched, which is a
     * struck bar's clang rather than a plate's crowd.
     *
     * The beam is heard .0509 of its length from one end, on the first node of its sixth mode, 18.638.
     * That mode sits near 37/2, so heard it would split the house `Pitch.detect`'s autocorrelation peak
     * at the root's period, as SHEET's 5.468 did. Its neighbour 13.344 (near 27/2) moves only .186 as much
     * as the root there. The mode shapes are the closed form `cosh βx + cos βx − σ(sinh βx + sin βx)`,
     * `σ = (cosh βL − cos βL)/(sinh βL − sin βL)`; the seven modes left are kept.
     */
    private val BAR = doubleArrayOf(22.373, 61.673, 120.903, 199.859, 298.556, 555.165, 713.079)

    /** Each BAR mode's motion at BEAM's listening point, from the closed form, as a fraction of the root's. */
    private val BEAM_HEARD = doubleArrayOf(1.0, .787, .582, .380, .186, .174, .333)

    /**
     * BEAM's pair (2.757 and 5.404) at .5 of the gain law and .7 of its ring, under a .85 gain law with each
     * mode above .85 as long. At full level the pair pulls the period long: 28 of the 37 corners `ScourTest`
     * asserts read at the onset, all flat. At .5 and .7 all 37 read at both windows for gain laws from .8 to
     * .88. The roller is the quietest of the four (.04): at PLATE's .07, the ring a full-speed roller left
     * partly cancelled the root at the slam, and a 243-corner grid at C3, E♭3 and F3 read 230 to 232 at the
     * onset. At .04 it reads 243 and 243; a .9 gain law reads 242. The rest is shape: a 3 s root ring, steel
     * that keeps its hardening (κ .015, ρ .12), and PLATE's stretch.
     */
    private val BEAM = Shape(BAR, .5, .7, BEAM_HEARD, 3.0, .85, .85, .015, .12, .3, .04)

    private fun shape(voice: ScourVoice) = when (voice) {
        ScourVoice.PLATE -> PLATE
        ScourVoice.PANE -> PANE
        ScourVoice.SHEET -> SHEET
        ScourVoice.BEAM -> BEAM
    }

    /** The voice's body as ratios to its root, every mode it can sound: what a probe of the body compares against. */
    internal fun bodyRatios(voice: ScourVoice): List<Double> = shape(voice).body.let { b -> b.map { it / b[0] } }

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: ScourVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", .5f, neutral = .5f),
        MacroSpec("BUILD", .6f, neutral = .6f),
        MacroSpec("RPM", .5f, neutral = .5f),
        MacroSpec("SHOT", .7f, neutral = .7f),
        MacroSpec("HARDEN", .6f, neutral = .5f),
        MacroSpec("HOLD", 0f, neutral = 0f),
    )

    fun defaults(voice: ScourVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** Every known macro, in 0..1; a missing or non-finite one takes its default, an unknown one is dropped. */
    internal fun settled(voice: ScourVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (k, default) -> (macros[k]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }

    fun rootMidi(voice: ScourVoice): Int = if (voice == ScourVoice.SHEET) SHEET_ROOT_MIDI else ROOT_MIDI
    fun midiFor(voice: ScourVoice, tune: Float): Int =
        rootMidi(voice) + ((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: ScourVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    /** How long BUILD's build runs before the slam, seconds. */
    fun buildSeconds(build: Float): Double =
        Dsp.expMap((build.takeIf { it.isFinite() } ?: .6f).coerceIn(0f, 1f), BUILD_MIN_SECONDS, BUILD_MAX_SECONDS).toDouble()

    /** The BUILD setting whose build lasts [seconds] (clamped to BUILD's range): how a phrase lands the slam on a beat. */
    fun buildFor(seconds: Double): Float =
        (ln(seconds.coerceIn(BUILD_MIN_SECONDS.toDouble(), BUILD_MAX_SECONDS.toDouble()) / BUILD_MIN_SECONDS) /
            ln(BUILD_MAX_SECONDS.toDouble() / BUILD_MIN_SECONDS)).toFloat()

    /** The shot rate at [t] seconds into a build of [build] seconds that ends at [peak] shots per second. */
    internal fun rateAt(peak: Double, build: Double, t: Double): Double = peak / ACCEL * ACCEL.pow(t / build)

    /** Shots expected by [t]: the integral of [rateAt], so a unit-rate arrival `u` lands where this equals `u`. */
    internal fun arrivalsBy(peak: Double, build: Double, t: Double): Double =
        peak / ACCEL * build / ln(ACCEL) * (ACCEL.pow(t / build) - 1)

    /** Each shot's force swells over the build, from [SWELL_FLOOR] to 1. */
    internal fun swell(build: Double, t: Double): Double = SWELL_FLOOR + (1 - SWELL_FLOOR) * (t / build).pow(2)

    /** No HOLD setting is a loop: HOLD sets how long the plate rings (see the class KDoc). */
    @Suppress("UNUSED_PARAMETER")
    fun isLoop(hold: Float): Boolean = false

    private fun velocityOf(velocity: Float) = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
    private fun rollerTop(rpm: Float) = Dsp.lin(rpm, .15f, 1f).toDouble()
    private fun shotRate(shot: Float) = Dsp.expMap(shot, 3f, 300f).toDouble()
    private val REFERENCE_RATE = shotRate(.5f)
    /** HOLD lengthens the ring after the slam, up to threefold at its top. */
    private fun ringSeconds(s: Shape, hold: Float) = s.t60 * (1 + 2 * hold)

    /**
     * The render's length: the build, then the root's ring, and never under [MIN_RENDER_SECONDS]. A closed
     * form in BUILD and HOLD alone, so velocity never changes it and [drumClassFor] never renders.
     */
    fun lengthSeconds(voice: ScourVoice, macros: Map<String, Float> = emptyMap()): Double {
        val s = shape(voice)
        val m = settled(voice, macros)
        return max(buildSeconds(m.getValue("BUILD")) + ringSeconds(s, m.getValue("HOLD")), MIN_RENDER_SECONDS)
    }

    fun renderFrames(voice: ScourVoice, macros: Map<String, Float> = emptyMap()): Int =
        (lengthSeconds(voice, macros) * RATE).roundToInt()

    /** LOOP when the render passes the classifier's 1.5 s, else PERC; never a drum class. Always LOOP today. */
    fun drumClassFor(voice: ScourVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (renderFrames(voice, macros) > RATE * LOOP_MIN_SECONDS) DrumClass.LOOP else DrumClass.PERC

    /** Every control is fair game: no setting loops, so every random sound ends. */
    fun scramble(voice: ScourVoice, random: Random = Random.Default): Map<String, Float> =
        defaults(voice).mapValues { random.nextFloat() }

    fun render(voice: ScourVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip =
        Snip(finish(play(voice, macros, velocity).raw), channels = 1, sampleRate = RATE)

    // ---- mechanics: the build, the slam and coverage, on the control tick -----------------------------

    /** What the mechanics decided, before any audio. Shots are sorted by their internal frame. */
    internal class Trace(
        val frames: Int,
        /** The shot rate the build ends at (SHOT's), per second. */
        val peakRate: Double,
        /** When the slam lands: the end of the build, seconds and internal frame. */
        val slamSeconds: Double,
        val slamFrame: Int,
        val shotFrames: IntArray,
        val shotAmplitude: DoubleArray,
        val shotAim: DoubleArray,
        val shotCoverage: DoubleArray,
        /** Coverage after each tick's shots, and the smoothed value that retunes the plate. */
        val coverage: DoubleArray,
        val smoothed: DoubleArray,
    )

    /**
     * Unit-rate arrivals and jitters come from fixed streams seeded by the voice alone. Each arrival `u`
     * lands where the build's expected shot count reaches it ([arrivalsBy]), so the rain accelerates
     * while turning SHOT or BUILD only stretches one schedule, and shot `i` always gets the `i`-th draws.
     */
    internal fun mechanics(voice: ScourVoice, macros: Map<String, Float>, velocity: Float = 1f): Trace {
        val s = shape(voice)
        val m = settled(voice, macros)
        val vel = velocityOf(velocity)
        val frames = renderFrames(voice, m) * Dsp.OVERSAMPLE
        val peak = shotRate(m.getValue("SHOT"))
        val build = buildSeconds(m.getValue("BUILD"))
        val slamFrame = (build * INTERNAL_RATE).roundToInt()
        val gaps = Random(Dsp.seedFor("SCOUR", MODEL_VERSION, voice.name, "GAPS"))
        val levels = Random(Dsp.seedFor("SCOUR", MODEL_VERSION, voice.name, "LEVEL"))
        val aims = Random(Dsp.seedFor("SCOUR", MODEL_VERSION, voice.name, "AIM"))
        val dent = vel.toDouble().pow(.8)
        val shotFrames = ArrayList<Int>()
        val shotAmplitude = ArrayList<Double>()
        val shotAim = ArrayList<Double>()
        val shotCoverage = ArrayList<Double>()
        val warp = peak / ACCEL * build / ln(ACCEL)
        var u = 0.0
        while (true) {
            u += -ln(1.0 - gaps.nextDouble())
            val level = levels.nextDouble() * 2 - 1
            val off = aims.nextDouble() * 2 - 1
            // The inverse of arrivalsBy: where the build's expected count reaches u.
            val t = build / ln(ACCEL) * ln(1 + u / warp)
            if (t >= build) break
            // House energy doctrine at every instant: force falls as √(λ_ref/λ(t)), so the mean shot power
            // does not follow the rate; the swell alone makes the build louder. Coverage counts dents.
            val amplitude = SHOT_FORCE * sqrt(REFERENCE_RATE / rateAt(peak, build, t)) * swell(build, t) * vel
            shotFrames += (t * INTERNAL_RATE).toInt()
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
            // The slam dents the plate too, once, like a shot.
            if (slamFrame / CTRL == k) c += s.kappa * dent * (1 - c)
            coverage[k] = c
            y = follow * y + (1 - follow) * c
            smoothed[k] = y
        }
        return Trace(
            frames, peak, build, slamFrame,
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
        val kept = keptModes(s, hz)
        val last = (s.body.size - 1).toDouble()
        val ratio = DoubleArray(kept.size) { s.body[kept[it]] / s.body[0] }
        val grade = DoubleArray(kept.size) { kept[it] / last }
        val ring = ringSeconds(s, m.getValue("HOLD")) / s.t60
        val t60s = DoubleArray(kept.size) { s.t60 * ring * s.decay.pow(kept[it]) * if (kept[it] in 1..2) s.pairRing else 1.0 }
        val pickup = DoubleArray(kept.size) { s.gain.pow(kept[it]) * s.heard[kept[it]] * if (kept[it] in 1..2) s.pairLevel else 1.0 }
        val rim = weights(RIM, kept)
        val shotWeights = Array(trace.shotFrames.size) { weights(trace.shotAim[it], kept) }
        val slamWeights = weights(AIM, kept)
        val slam = SLAM_FORCE * velocityOf(velocity)
        val ticks = trace.smoothed.size
        val modeHz = if (record) Array(kept.size) { FloatArray(ticks) } else null
        val bank = Modes.Bank(kept.size, INTERNAL_RATE)
        fun retune(tick: Int) {
            val c = trace.smoothed[tick]
            for (i in kept.indices) {
                val f = hz * ratio[i] * (1 + s.shift * harden * c * grade[i])
                bank.tune(i, f, t60s[i])
                modeHz?.get(i)?.set(tick, f.toFloat())
            }
        }
        val table = roughnessFor(voice)
        val top = rollerTop(m.getValue("RPM"))
        val build = trace.slamSeconds
        val dt = 1.0 / INTERNAL_RATE
        val force = DoubleArray(kept.size)
        val out = FloatArray(trace.frames)
        var phase = 0.0
        var first = 0
        for (n in 0 until trace.frames) {
            if (n % CTRL == 0) retune(n / CTRL)
            bank.step()
            // The roller spins up as (t/BUILD)² and the slam stops it.
            val t = n * dt
            val speed = if (t < build) top * (t / build).pow(2) else top * max(0.0, 1 - (t - build) / ROLLER_STOP_SECONDS)
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
            val k = n - trace.slamFrame
            if (k in SLAM_PULSE.indices) for (i in force.indices) force[i] += slamWeights[i] * slam * SLAM_PULSE[k]
            bank.drive(force, 1.0)
            out[n] = (bank.velocityAlong(pickup) * OUTPUT_TRIM * s.trim).toFloat()
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
