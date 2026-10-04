package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * BALLAST: a deep electronic bass that physically drives a structure. The bass is a pair of band-limited
 * oscillators into a resonant low-pass; its signal is both heard directly and fed, through a bounded force
 * actuator, to a resonant frame. The frame shakes six sympathetic strings (octave-related to the note) and six
 * suspended glass tiles; the tiles knock into each other and into their housing, and each knock rings the
 * tile's own modes. Nothing in the structure has a trigger of its own: stop the bass and the wires, the frame
 * and the last few taps settle by themselves.
 * - **Design:** `docs/superpowers/specs/2026-10-04-ballast-bass-engine-design.md`.
 * - **Handoff:** the supplied *Ballast Engine Engineering Specification* v1.0, whose proposals this implements
 *   and whose open numbers it records as measured.
 *
 * **One mechanical bank.** The frame (three modes: a slow rocking mount and two audio-rate modes) and the strings
 * (every harmonic of every string is a mode) live in one [Modes.Bank], coupled by springs. A spring is reciprocal
 * and passive by construction, so what the frame gives a string the string gives back, and the bank's own bounds
 * ([Modes.MAX_NODE_KAPPA]) keep it stable. The strings are modal rather than waveguide loops ([Strings.Loop]
 * would need a scattering junction to couple to anything); a string at 1/4 of the note therefore has modes at
 * 1/4, 1/2, 3/4 and 1 times the note, and only the last of them is in tune with the bass.
 *
 * **The lower-octave issue (spec section 6).** A linear resonator cannot make a steady subharmonic out of a
 * stationary tone, and this one does not try. The 1/4 and 1/2 strings are excited at the note's onset and
 * release and by every change of the bass's own energy (the actuator carries the source's evolving signal, so
 * a filter sweep or a beat moves them), and their modes that are not harmonics of the note ring and decay.
 * Their harmonics that are (4th and 8th of the 1/4 string, and so on) are driven steadily. There is no sub
 * oscillator anywhere.
 *
 * **The actuator has three parts.** The fast one is the source signal itself, high-passed and soft-bounded, driving
 * the audio-rate frame modes: this is what the strings hear. The slow one is the source's rectified energy
 * between [SLOW_LOW_HZ] and [SLOW_HIGH_HZ], driving the rocking mode: onsets, releases, the beat of the two
 * oscillators and the filter's wobble move it, and the audio cycle does not. The tiles ride on the frame's
 * acceleration, so they knock when the bass's energy moves and not at every cycle. The third is the kick: the same
 * rectified energy, low-passed at [KICK_CUTOFF_MULTIPLE] times the note and high-passed at [KICK_LOW_HZ], applied to
 * the wires under the note. It is the change of the bass's envelope and nothing else, so it is a pulse at an onset, a
 * release or a sweep, and zero in a steady tone. A linear resonator cannot turn a stationary tone into a subharmonic,
 * and this does not try: the lower wires are rung by these pulses and fade.
 *
 * **The glass.** Six tiles hang on soft mounts inside the frame and hit each other and the housing through
 * compression-only springs with dissipative damping, integrated at 44.1 kHz. A knock's dissipated energy, once
 * the contact separates, is what rings that tile's three modes ([RING_SHARE] of it, shared between the two tiles
 * and spread over the modes by the contact's duration): the ringing is the sound of the collision loss and
 * nothing else, so it can never be a second source of mechanical energy. The mounts push back on the frame
 * ([Probe.frameReturn] turns this off in a test).
 *
 * Every constant marked "listening" is a first value for the audition gate, not a sourced one.
 */
enum class BallastVoice { ROOT, WIRE, GLINT, DEEP, BLOOM, SWARM }

object Ballast {

    /** TUNE walks three octaves from C1 to C4 in semitones, so the engine covers the spec's probe notes (MIDI 24, 36 and 60). */
    const val TUNE_SEMITONES = 36
    const val ROOT_MIDI = 24

    // ---- the lifecycle ----------------------------------------------------------

    /** HOLD from here is the held loop; below it HOLD is how long the bass is gated on. */
    const val LOOP_THRESHOLD = 0.99f
    const val GATE_MIN_SECONDS = 0.6f
    const val GATE_MAX_SECONDS = 3.0f
    const val RELEASE_SECONDS = 0.25

    /** The render stops when the slowest decay is this far down (dB), within [MAX_SECONDS]. */
    const val END_DB = 45.0
    const val TAIL_MARGIN_SECONDS = 0.35
    const val MAX_SECONDS = 8.0
    const val LOOP_THRESHOLD_SECONDS = 1.5f

    /** The last share of the post-gate tail fades on a raised cosine: the documented release when a tail is cut at the cap. */
    const val TAIL_FADE_SHARE = 0.35
    const val OUTPUT_DC_HZ = 20f

    // ---- the strings --------------------------------------------------------------

    /** The fixed roster, relative to the note (spec section 6). */
    val OCTAVES = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)
    const val ROOT_STRING = 2
    const val STRINGS = 6
    const val MODES_PER_STRING = 10

    /** A wire's stiffness: harmonic m sits at m*sqrt(1 + B*m^2). Small, so harmonics up to the 8th stay inside 0.2 % of the note's own. */
    const val STRING_STIFFNESS = 4e-5

    /** Each string is a few tenths of a cent off its octave, seeded per string, so coincident modes beat a little and never lock. */
    const val STRING_DETUNE_CENTS = 0.7

    /** A mode fades in over these frequencies (the validated band's low edge) and out over these (its high edge); modes outside are never made. */
    const val BAND_LOW_FADE_FROM = 14.0
    const val BAND_LOW_FADE_TO = 30.0
    const val BAND_HIGH_FADE_FROM = 6_500.0
    const val BAND_HIGH_FADE_TO = 9_500.0
    const val MODE_FLOOR_HZ = 10.0
    const val MODE_CEILING_HZ = 11_000.0

    /** SYMPATHY moves the fundamental's t60 of the note's own string between these (seconds); a string at ratio r has r^-[STRING_T60_RATIO_SLOPE] of it. */
    const val STRING_T60_LOW = 0.8
    const val STRING_T60_HIGH = 8.0
    const val STRING_T60_RATIO_SLOPE = 0.3

    /** ...and a harmonic m of a string dies as m^-[STRING_T60_HARMONIC_SLOPE]. */
    const val STRING_T60_HARMONIC_SLOPE = 0.7
    const val STRING_T60_FLOOR = 0.05

    /** SYMPATHY moves the frame-string springs' total kappa between these. At 0 a small structural path remains. */
    const val STRING_KAPPA_LOW = 0.02
    const val STRING_KAPPA_HIGH = 0.5

    /** SPAN: the half-width, in octaves, of the weight below and above the root string, at SPAN 0 and 1. */
    const val SPAN_BELOW_LOW = 0.5
    const val SPAN_BELOW_HIGH = 2.4
    const val SPAN_ABOVE_LOW = 0.6
    const val SPAN_ABOVE_HIGH = 3.0

    /** A held loop keeps a free (not harmonic of the note) mode's t60 under this, so the onset's lower-string transients settle in the preroll. */
    const val LOOP_FREE_T60_CAP = 1.6

    // ---- the frame -----------------------------------------------------------------

    /** The rocking mount: stiff and damped at FRAME 0, soft and long at 1. */
    const val ROCK_HZ_TIGHT = 14.0
    const val ROCK_HZ_SOFT = 4.5
    const val ROCK_T60_TIGHT = 0.10
    const val ROCK_T60_SOFT = 1.3

    /** The two audio-rate frame modes, as ratios of the note (inharmonic on purpose), at FRAME 0 and 1. */
    val FRAME_RATIO_TIGHT = doubleArrayOf(1.62, 2.93)
    val FRAME_RATIO_SOFT = doubleArrayOf(1.38, 2.47)
    const val FRAME_T60_TIGHT = 0.08
    const val FRAME_T60_SOFT = 1.2

    /** Springs between the rocking mode and the audio modes, and how the actuator and the tile mounts reach the three modes. */
    const val ROCK_KAPPA = 0.15
    val ACTUATOR_VECTOR = doubleArrayOf(0.0, 1.0, 0.7)
    val MOUNT_VECTOR = doubleArrayOf(1.0, 0.2, 0.12)
    val FRAME_PICKUP = doubleArrayOf(0.0, 0.55, 0.3)

    // ---- the source and the actuator ---------------------------------------------------

    /** The fast force, per unit of source signal, at DRIVE 0 and 1 (DRIVE moves the energy the structure is given, not only the bass's timbre). */
    const val FORCE_LOW = 1.5e3
    const val FORCE_HIGH = 2.2e4

    /** The slow force on the rocking mode, in the same units, per unit of rectified-energy change. */
    const val SLOW_GAIN = 0.5
    const val SLOW_LOW = 0.15
    const val SLOW_HIGH = 1.0
    const val SLOW_LOW_HZ = 0.8

    /**
     * The onset and release kick: the source's rectified energy, low-passed at this multiple of the note and high-passed at
     * [KICK_LOW_HZ], so what is left is the change of the bass's own envelope (an onset, a release, a filter sweep, the beat),
     * as a force on the wires under the note (the bridge moving under them). A steady tone leaves nothing of it.
     */
    const val KICK_CUTOFF_MULTIPLE = 0.7
    const val KICK_LOW_HZ = 2.5
    const val KICK_GAIN_LOW = 5.0e3
    const val KICK_GAIN_HIGH = 5.0e4
    const val KICK_BELOW = 0.9
    const val SLOW_HIGH_HZ = 18.0

    /** The source's high-pass into the actuator, and the soft bound on the force (a tanh knee: it can never exceed 1/[FORCE_KNEE] of its scale). */
    const val ACTUATOR_HP_HZ = 8.0
    const val FORCE_KNEE = 1.6

    /** The oscillators' mix, the ladder's input gain and its cutoff law: cutoff = note * multiple * 2^(octaves of envelope, wobble). */
    const val FILTER_ENV_OCTAVES = 1.7
    const val FILTER_ENV_SECONDS = 0.30
    const val FILTER_WOBBLE_OCTAVES = 0.45
    const val FILTER_WOBBLE_HZ = 3.1
    const val MAX_RESONANCE_FOLLOW = 1.8

    /** The pair's detune (cents) at the shapes' own value; it is changed to fit a loop (see [planLoop]). */
    const val LOOP_MAX_DETUNE_CENTS = 14.0

    // ---- the glass -----------------------------------------------------------------------

    const val TILES = 6
    const val TILE_MODES = 3

    /** Tile modes over the tile's base frequency, and their t60 shares. */
    val TILE_MODE_RATIOS = doubleArrayOf(1.0, 2.05, 3.2)
    val TILE_MODE_T60 = doubleArrayOf(1.0, 0.6, 0.35)
    val TILE_SIZE = doubleArrayOf(1.0, 0.86, 1.18, 0.74, 1.30, 0.93)
    const val TILE_T60 = 0.55
    const val TILE_FADE_FROM_HZ = 11_000.0
    const val TILE_FADE_TO_HZ = 15_000.0

    /** The tiles' mounts: stiff (follow the frame) to soft (stay behind it). */
    val TILE_MOUNT_DEVIATION = doubleArrayOf(0.0, 0.07, -0.05, 0.11, -0.09, 0.04)
    val TILE_RELEASE_ORDER = intArrayOf(0, 3, 1, 4, 2, 5)
    const val MOUNT_HZ_STIFF = 26.0
    const val MOUNT_HZ_SOFT = 3.4
    const val MOUNT_ZETA = 0.18
    const val TILE_MASS = 0.01

    /** Gaps in model units (the frame's rocking displacement is of order one): wide at GLASS 0, narrow at 1. */
    const val GAP_WIDE = 0.08
    const val GAP_NARROW = 0.003
    val GAP_PATTERN = doubleArrayOf(1.0, 0.82, 1.25, 0.9, 1.12, 1.0)
    const val WALL_GAP_SHARE = 1.5

    /** A tile may not travel past this (a safety stop, not part of the model; a render counts how often it engaged). */
    const val TRAVEL_LIMIT = 12.0

    /** Contact stiffness is set as a natural frequency, so its stiffness-to-mass ratio is bounded: soft glass to sharp glass. */
    const val CONTACT_HZ_SOFT = 700.0
    const val CONTACT_HZ_SHARP = 2_400.0
    const val CONTACT_ZETA = 0.28

    /** A held loop's contacts are more damped and never closer than this, so the chatter settles to an orbit that repeats. */
    const val LOOP_CONTACT_ZETA = 1.0
    const val LOOP_GAP_FLOOR = 0.02
    const val OPEN_GAP = 1e3

    /** The share of a knock's dissipated energy that rings the glass, and the readout gain of the glass modes. */
    const val RING_SHARE = 0.3
    const val MIN_KNOCK_ENERGY = 1e-16

    // ---- the pickup ------------------------------------------------------------------------

    const val DIRECT_GAIN = 1.0
    const val STRING_PICK = 8.0e-3
    const val FRAME_PICK_GAIN = 2.0e-3
    const val GLASS_PICK = 10.0

    /** The pickup saturates the glass at about this level (a tanh knee), so a dense rattle is loud and not a wall. */
    const val GLASS_KNEE = 0.2

    // ---- velocity ---------------------------------------------------------------------------

    /** Velocity (a render parameter, no knob): attack milliseconds, filter brightness and force at velocity 0, against 1. Listening values. */
    const val VELOCITY_ATTACK_SLOW_MS = 16.0
    const val VELOCITY_CUTOFF_SOFT = 0.55
    const val VELOCITY_FORCE_SOFT = 0.35
    const val VELOCITY_FILTER_ENV_SOFT = 0.5

    private const val CTRL = Dsp.OVERSAMPLE
    private const val T60_LN = 6.907755278982137

    // ---- the voices ----------------------------------------------------------------------------

    /** What differs between voices inside one architecture: the bass's contour, the octaves' weights, the mount and the tiles. */
    internal class Shape(
        val sawLevel: Double,
        val pulseLevel: Double,
        val cutoffMultiple: Double,
        val resonance: Double,
        val attackMs: Double,
        val decaySeconds: Double,
        val sustain: Double,
        val detuneCents: Double,
        val stringT60: Double,
        val octaveBias: DoubleArray,
        val frameMass: Double,
        val tileHz: Double,
        val gapScale: Double,
        val mobility: Double,
        val riseSeconds: Double,
    )

    internal fun shapeOf(voice: BallastVoice): Shape = when (voice) {
        BallastVoice.ROOT -> Shape(0.55, 0.45, 4.6, 0.25, 6.0, 0.30, 0.78, 5.0, 1.0, doubleArrayOf(0.8, 1.0, 1.0, 1.0, 0.9, 0.7), 1.0, 2200.0, 1.0, 1.0, 0.0)
        BallastVoice.WIRE -> Shape(0.55, 0.45, 4.6, 0.25, 6.0, 0.30, 0.78, 5.0, 1.35, doubleArrayOf(0.7, 1.0, 1.0, 1.3, 1.0, 0.8), 1.0, 2200.0, 1.0, 1.0, 0.0)
        BallastVoice.GLINT -> Shape(0.5, 0.5, 4.2, 0.25, 5.0, 0.28, 0.75, 6.0, 0.9, doubleArrayOf(0.6, 0.8, 1.0, 1.0, 1.0, 1.1), 0.9, 3000.0, 0.82, 1.15, 0.0)
        BallastVoice.DEEP -> Shape(0.6, 0.4, 3.8, 0.3, 7.0, 0.34, 0.8, 4.0, 1.15, doubleArrayOf(1.35, 1.25, 1.0, 0.6, 0.4, 0.3), 1.35, 2000.0, 1.0, 0.95, 0.0)
        BallastVoice.BLOOM -> Shape(0.5, 0.5, 4.4, 0.3, 11.0, 0.36, 0.8, 5.5, 1.2, doubleArrayOf(0.9, 1.0, 1.0, 1.0, 1.0, 0.9), 1.25, 2400.0, 0.9, 1.0, 0.7)
        BallastVoice.SWARM -> Shape(0.45, 0.55, 5.0, 0.4, 6.0, 0.32, 0.8, 9.0, 1.1, doubleArrayOf(1.0, 1.0, 1.0, 1.1, 1.1, 1.1), 1.1, 2800.0, 0.7, 1.35, 0.0)
    }

    /** The shared neutral column and the voices' defaults (spec section 3); HOLD 0 is the finite lifecycle. */
    private val NEUTRAL = mapOf("DRIVE" to 0.45f, "SYMPATHY" to 0.35f, "SPAN" to 0.40f, "GLASS" to 0.25f, "FRAME" to 0.45f)

    private fun voiceDefaults(voice: BallastVoice): List<Float> = when (voice) {
        BallastVoice.ROOT -> listOf(0.40f, 0.25f, 0.30f, 0.10f, 0.35f)
        BallastVoice.WIRE -> listOf(0.45f, 0.70f, 0.45f, 0.20f, 0.50f)
        BallastVoice.GLINT -> listOf(0.40f, 0.45f, 0.60f, 0.65f, 0.45f)
        BallastVoice.DEEP -> listOf(0.45f, 0.45f, 0.70f, 0.15f, 0.65f)
        BallastVoice.BLOOM -> listOf(0.55f, 0.65f, 0.50f, 0.40f, 0.70f)
        BallastVoice.SWARM -> listOf(0.75f, 0.65f, 0.80f, 0.85f, 0.65f)
    }

    fun macrosFor(voice: BallastVoice): List<MacroSpec> {
        val d = voiceDefaults(voice)
        return listOf(
            MacroSpec("TUNE", 0.5f, neutral = 0.5f),
            MacroSpec("DRIVE", d[0], neutral = NEUTRAL.getValue("DRIVE")),
            MacroSpec("SYMPATHY", d[1], neutral = NEUTRAL.getValue("SYMPATHY")),
            MacroSpec("SPAN", d[2], neutral = NEUTRAL.getValue("SPAN")),
            MacroSpec("GLASS", d[3], neutral = NEUTRAL.getValue("GLASS")),
            MacroSpec("FRAME", d[4], neutral = NEUTRAL.getValue("FRAME")),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }

    fun defaults(voice: BallastVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(macros: Map<String, Float>, voice: BallastVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: BallastVoice): Int = ROOT_MIDI

    /** The snapped MIDI note TUNE lands on. */
    fun midiFor(voice: BallastVoice, tune: Float): Int = rootMidi(voice) + Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)

    fun frequencyFor(voice: BallastVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    // ---- what the macros mean ------------------------------------------------------------------

    private fun lerp(x: Double, lo: Double, hi: Double) = lo + (hi - lo) * x.coerceIn(0.0, 1.0)

    private fun smoothstep(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD

    fun gateSeconds(hold: Float): Float =
        Dsp.expMap(hold.coerceAtMost(LOOP_THRESHOLD) / LOOP_THRESHOLD, GATE_MIN_SECONDS, GATE_MAX_SECONDS)

    /** The fundamental's t60 of the string at the note's own pitch for this SYMPATHY and voice, seconds. */
    internal fun stringT60(voice: BallastVoice, sympathy: Float): Double =
        lerp(sympathy.toDouble().pow(0.9), STRING_T60_LOW, STRING_T60_HIGH) * shapeOf(voice).stringT60

    internal fun stringKappaTotal(sympathy: Float, frame: Float): Double =
        lerp(sympathy.toDouble().pow(0.9), STRING_KAPPA_LOW, STRING_KAPPA_HIGH) * lerp(frame.toDouble(), 0.7, 1.25)

    internal fun frameT60(frame: Float): Double = lerp(frame.toDouble().pow(1.3), FRAME_T60_TIGHT, FRAME_T60_SOFT)

    internal fun rockHz(voice: BallastVoice, frame: Float): Double =
        lerp(frame.toDouble(), ROCK_HZ_TIGHT, ROCK_HZ_SOFT) / sqrt(shapeOf(voice).frameMass)

    /** SPAN's weight of each string: the root's is always 1, the others fall off with their distance in octaves, wider as SPAN rises. */
    internal fun octaveWeights(voice: BallastVoice, span: Float): DoubleArray {
        val bias = shapeOf(voice).octaveBias
        val below = lerp(span.toDouble(), SPAN_BELOW_LOW, SPAN_BELOW_HIGH)
        val above = lerp(span.toDouble(), SPAN_ABOVE_LOW, SPAN_ABOVE_HIGH)
        return DoubleArray(STRINGS) { j ->
            val octaves = ln(OCTAVES[j]) / ln(2.0)
            val w = exp(-(octaves / if (octaves < 0) below else above).pow(2))
            if (j == ROOT_STRING) 1.0 else w * bias[j]
        }
    }

    internal fun contactHz(glass: Float): Double = lerp(glass.toDouble(), CONTACT_HZ_SOFT, CONTACT_HZ_SHARP)

    internal fun gapFor(glass: Float, voice: BallastVoice): Double =
        lerp(glass.toDouble().pow(0.7), GAP_WIDE, GAP_NARROW) * shapeOf(voice).gapScale

    /** How free tile [i] is to move, 0 (follows the frame) to 1 (stays behind it): GLASS releases the tiles one by one in [TILE_RELEASE_ORDER]. */
    internal fun freeness(voice: BallastVoice, glass: Float, i: Int): Double {
        val rank = TILE_RELEASE_ORDER.indexOf(i)
        val reach = glass.toDouble() * shapeOf(voice).mobility * 1.25 + 0.2 - rank * 0.15
        return smoothstep(reach / 0.55).coerceIn(0.04, 1.0)
    }

    /** The number of raw (oversampled) frames of a one-shot at [macros] (settled), whole output frames. */
    internal fun rawFrames(voice: BallastVoice, m: Map<String, Float>): Int =
        (totalSeconds(voice, m) * RATE).toInt() * Dsp.OVERSAMPLE

    internal fun tailSeconds(voice: BallastVoice, m: Map<String, Float>): Double {
        val slowest = max(stringT60(voice, m.getValue("SYMPATHY")), max(frameT60(m.getValue("FRAME")), TILE_T60 * 1.5))
        return END_DB / 60.0 * slowest + TAIL_MARGIN_SECONDS
    }

    internal fun totalSeconds(voice: BallastVoice, m: Map<String, Float>): Double =
        min(MAX_SECONDS, gateSeconds(m.getValue("HOLD")) + RELEASE_SECONDS + tailSeconds(voice, m))

    /** Frames at [RATE] a one-shot has. `BallastTest` pins this against a real render. */
    internal fun renderFrames(voice: BallastVoice, macros: Map<String, Float>): Int = rawFrames(voice, settled(macros, voice)) / Dsp.OVERSAMPLE

    /**
     * What a pad holding this sound is filed as: LOOP past the classifier's own 1.5 s line (a held loop is always
     * over it), PERC under it. Derived from the rendered frame count, the comparison the classifier makes.
     */
    fun drumClassFor(voice: BallastVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) return DrumClass.LOOP
        return if (renderFrames(voice, m).toFloat() / RATE > LOOP_THRESHOLD_SECONDS) DrumClass.LOOP else DrumClass.PERC
    }

    /** SCRAMBLE near a preset; HOLD is capped short of the loop, because the top step is a choice and not a roll. */
    fun scramble(voice: BallastVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + BallastPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        val rolled = Dsp.scrambleNear(seed, temperature, random).toMutableMap()
        rolled["HOLD"] = rolled.getValue("HOLD").coerceAtMost(0.95f)
        return rolled
    }

    // ---- a render's plan -------------------------------------------------------------------------

    /** What the source does: its pitch, the pair's ratio, the wobble's rate, and whether it is gated (one-shot) or held (steady). */
    internal class Plan(
        val hzA: Double,
        val ratioB: Double,
        val wobbleHz: Double,
        val steady: Boolean,
        val frames: Int,
        val gateFrames: Int,
        val releaseFrames: Int,
        /** A loop's last resorts when the glass will not settle to an orbit that repeats: 1 widens the gaps and damps the mounts, 2 opens the contacts. */
        val calm: Int = 0,
    )

    internal fun oneShotPlan(voice: BallastVoice, hz: Double, m: Map<String, Float>): Plan {
        val rate = RATE * Dsp.OVERSAMPLE
        val shape = shapeOf(voice)
        return Plan(
            hzA = hz,
            ratioB = 2.0.pow(shape.detuneCents / 1200.0),
            wobbleHz = FILTER_WOBBLE_HZ,
            steady = false,
            frames = rawFrames(voice, m),
            gateFrames = (gateSeconds(m.getValue("HOLD")) * rate).toInt(),
            releaseFrames = (RELEASE_SECONDS * rate).toInt(),
        )
    }

    /** A loop's shape: [periods] whole periods of the note in [frames] whole frames at [RATE], and the beat and wobble that fit it whole. */
    internal class LoopPlan(val periods: Int, val frames: Int, val hz: Double, val beats: Int, val wobbleCycles: Int) {
        val seconds get() = frames.toDouble() / RATE
        val ratioB get() = 1.0 + beats.toDouble() / periods
    }

    /**
     * The held note as a loop: at least [LOOP_SECONDS] long, the periods a multiple of four so the 1/4 string's modes
     * that are harmonics of the note repeat inside it, the pitch moved by under half a frame over the loop to fill
     * whole frames, the pair's detune the whole number of beats nearest the voice's own (none when one beat would be
     * past [LOOP_MAX_DETUNE_CENTS]) and the wobble a whole number of cycles. All of it so the driven state repeats.
     */
    internal fun planLoop(voice: BallastVoice, hz: Double): LoopPlan {
        val periods = (ceil4(LOOP_SECONDS * hz)).coerceAtLeast(8)
        val frames = Math.round(periods * RATE / hz).toInt()
        val exactHz = periods * RATE.toDouble() / frames
        val want = 2.0.pow(shapeOf(voice).detuneCents / 1200.0) - 1.0
        var beats = Math.round(periods * want).toInt()
        if (beats < 1) beats = if (1200.0 * ln(1.0 + 1.0 / periods) / ln(2.0) <= LOOP_MAX_DETUNE_CENTS) 1 else 0
        if (1200.0 * ln(1.0 + beats.toDouble() / periods) / ln(2.0) > LOOP_MAX_DETUNE_CENTS) beats = 0
        val seconds = frames.toDouble() / RATE
        return LoopPlan(periods, frames, exactHz, beats, max(1, Math.round(FILTER_WOBBLE_HZ * seconds).toInt()))
    }

    private fun ceil4(x: Double) = (kotlin.math.ceil(x / 4.0) * 4.0).toInt()

    const val LOOP_SECONDS = 2.0

    /** Seconds the structure is run before a loop is kept: long enough that the onset's free modes have settled. */
    internal fun prerollSeconds(voice: BallastVoice, m: Map<String, Float>): Double =
        (1.2 + 1.5 * max(min(stringT60(voice, m.getValue("SYMPATHY")), LOOP_FREE_T60_CAP), frameT60(m.getValue("FRAME")))).coerceIn(PREROLL_MIN_SECONDS, PREROLL_MAX_SECONDS)

    const val PREROLL_MIN_SECONDS = 3.0
    const val PREROLL_MAX_SECONDS = 6.5

    // ---- the simulation -----------------------------------------------------------------------------

    /** Test-only doors, never reachable from a macro. */
    internal class Probe(
        /** false: the slow actuator part is zero, so the rocking mode is never driven by the bass. */
        val motionDrive: Boolean = true,
        /** false: the tile mounts' force on the frame is not applied. */
        val frameReturn: Boolean = true,
        /** The source and the actuator stop dead at this second (the structure is left to settle). */
        val sourceOffAt: Double? = null,
        /** Keep each part's output and the structure's energy over time. */
        val record: Boolean = false,
        /** false: the glass rings are not read out (the knocks still happen). */
        val glass: Boolean = true,
    )

    /** What a render counted. Energies are in model units (modal mass 1). */
    internal class Stats {
        val knockTimes = ArrayList<Double>()
        val knockEnergies = ArrayList<Double>()
        val knockPeaks = ArrayList<Double>()
        var knockLoss = 0.0
        var ringEnergy = 0.0
        var work = 0.0
        var travelStops = 0
        var peakForce = 0.0
        var peakTravel = 0.0
        var peakRock = 0.0
        var peakFrame = 0.0
        val energyTimes = ArrayList<Double>()
        val energies = ArrayList<Double>()
        val knocks get() = knockTimes.size
    }

    internal class Parts(val direct: FloatArray, val frame: FloatArray, val strings: FloatArray, val glass: FloatArray)

    internal class Run(val raw: FloatArray, val stats: Stats, val parts: Parts?)

    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    private fun onePoleA(hz: Double, rate: Int) = 1.0 - exp(-2.0 * PI * hz / rate)

    private fun midiOf(hz: Double) = Math.round(12.0 * ln(hz / 440.0) / ln(2.0) + 69.0).toInt()

    /**
     * The structure driven by the bass, at the oversampled rate: raw and unlevelled, so a test can read the physics.
     * [m] is settled; [velocity] is the render parameter described at [VELOCITY_ATTACK_SLOW_MS].
     */
    internal fun simulate(voice: BallastVoice, plan: Plan, m: Map<String, Float>, velocity: Double = 1.0, probe: Probe = Probe()): Run {
        val rate = RATE * Dsp.OVERSAMPLE
        val dt = 1.0 / rate
        val dtc = CTRL * dt
        val shape = shapeOf(voice)
        val drive = m.getValue("DRIVE").toDouble()
        val sympathy = m.getValue("SYMPATHY")
        val span = m.getValue("SPAN")
        val glass = m.getValue("GLASS")
        val frame = m.getValue("FRAME")
        val hz = plan.hzA
        val midi = midiOf(hz)
        val v = velocity.coerceIn(0.0, 1.0)
        val stats = Stats()

        // ---- the strings and the frame: one bank ----
        val rockHz = rockHz(voice, frame)
        val frameRatios = DoubleArray(2) { lerp(frame.toDouble(), FRAME_RATIO_TIGHT[it], FRAME_RATIO_SOFT[it]) / sqrt(shape.frameMass) }
        val weights = octaveWeights(voice, span)
        val t60Root = stringT60(voice, sympathy)
        val detune = Dsp.Noise(Dsp.seedFor("BALLAST", voice, midi, "strings"))

        val freq = ArrayList<Double>()
        val modeT60 = ArrayList<Double>()
        val modeString = ArrayList<Int>()
        val modeHarmonic = ArrayList<Int>()
        val modeFade = ArrayList<Double>()
        for (j in 0 until STRINGS) {
            val cents = STRING_DETUNE_CENTS * detune.next()
            val f1 = hz * OCTAVES[j] * 2.0.pow(cents / 1200.0)
            for (h in 1..MODES_PER_STRING) {
                val f = f1 * h * sqrt(1.0 + STRING_STIFFNESS * h * h)
                if (f < MODE_FLOOR_HZ || f > MODE_CEILING_HZ) continue
                val fade = smoothstep((f - BAND_LOW_FADE_FROM) / (BAND_LOW_FADE_TO - BAND_LOW_FADE_FROM)) *
                    (1.0 - smoothstep((f - BAND_HIGH_FADE_FROM) / (BAND_HIGH_FADE_TO - BAND_HIGH_FADE_FROM)))
                var t60 = t60Root * OCTAVES[j].pow(-STRING_T60_RATIO_SLOPE) * h.toDouble().pow(-STRING_T60_HARMONIC_SLOPE)
                if (plan.steady) {
                    val ratio = f / hz
                    if (abs(ratio - round(ratio)) > 0.002 * max(1.0, ratio)) t60 = min(t60, LOOP_FREE_T60_CAP)
                }
                freq += f; modeT60 += max(STRING_T60_FLOOR, t60); modeString += j; modeHarmonic += h; modeFade += fade
            }
        }
        val strings = freq.size
        val size = 3 + strings
        val bank = Modes.Bank(size, rate)
        val rockT60 = lerp(frame.toDouble().pow(1.3), ROCK_T60_TIGHT, ROCK_T60_SOFT)
        bank.tune(0, rockHz, rockT60)
        val frameT60 = frameT60(frame)
        for (k in 0 until 2) bank.tune(1 + k, hz * frameRatios[k], frameT60 * (1.0 - 0.25 * k))
        for (s in 0 until strings) bank.tune(3 + s, freq[s], modeT60[s])

        // Each string mode is sprung to the audio frame mode nearest it in pitch; the springs' total kappa on each
        // frame mode is SYMPATHY's, shared out by the mode's weight, so the node bound can never be passed.
        val attach = IntArray(strings) { s ->
            if (abs(ln(freq[s] / (hz * frameRatios[0]))) <= abs(ln(freq[s] / (hz * frameRatios[1])))) 1 else 2
        }
        val springWeight = DoubleArray(strings) { s -> sqrt(weights[modeString[s]]) * modeFade[s] * modeHarmonic[s].toDouble().pow(-0.3) }
        val kappaTotal = stringKappaTotal(sympathy, frame)
        val sums = DoubleArray(3)
        for (s in 0 until strings) sums[attach[s]] += springWeight[s]
        bank.connect(0, 1, ROCK_KAPPA)
        bank.connect(0, 2, ROCK_KAPPA * 0.6)
        for (s in 0 until strings) {
            val share = if (sums[attach[s]] > 0.0) springWeight[s] / sums[attach[s]] else 0.0
            bank.connect(attach[s], 3 + s, kappaTotal * share)
        }

        val actuator = DoubleArray(size)
        for (k in 0 until 3) actuator[k] = ACTUATOR_VECTOR[k] * lerp(frame.toDouble(), 0.6, 1.2)
        val mountVec = DoubleArray(size)
        for (k in 0 until 3) mountVec[k] = MOUNT_VECTOR[k]
        val rockOnly = DoubleArray(size).also { it[0] = 1.0 }
        val kickVec = DoubleArray(size)
        for (s in 0 until strings) if (freq[s] < hz * KICK_BELOW) kickVec[3 + s] = weights[modeString[s]] * modeFade[s] * modeHarmonic[s].toDouble().pow(-0.5)
        val framePick = DoubleArray(size)
        for (k in 0 until 3) framePick[k] = FRAME_PICKUP[k] * FRAME_PICK_GAIN
        val stringPick = DoubleArray(size)
        val sympathyGain = 0.25 + 1.5 * sympathy
        for (s in 0 until strings) stringPick[3 + s] = STRING_PICK * sympathyGain * weights[modeString[s]] * modeFade[s] * modeHarmonic[s].toDouble().pow(-0.6)
        val bothPick = DoubleArray(size) { framePick[it] + stringPick[it] }

        // ---- the glass: six tiles, three uncoupled modes each ----
        val glassBank = Modes.Bank(TILES * TILE_MODES, rate)
        val glassPick = DoubleArray(TILES * TILE_MODES)
        for (i in 0 until TILES) for (k in 0 until TILE_MODES) {
            val f = shape.tileHz * TILE_SIZE[i] * TILE_MODE_RATIOS[k]
            glassBank.tune(i * TILE_MODES + k, f.coerceAtMost(MODE_CEILING_HZ), TILE_T60 * TILE_SIZE[i] * TILE_MODE_T60[k])
            val fade = 1.0 - smoothstep((f - TILE_FADE_FROM_HZ) / (TILE_FADE_TO_HZ - TILE_FADE_FROM_HZ))
            glassPick[i * TILE_MODES + k] = GLASS_PICK * fade * (if ((i + k) % 2 == 0) 1.0 else -0.8) / TILES
        }
        val contactHz = contactHz(glass)
        val contactTau = 0.5 / contactHz
        val ringWeight = Array(TILES) { i ->
            val w = DoubleArray(TILE_MODES) { k ->
                val f = shape.tileHz * TILE_SIZE[i] * TILE_MODE_RATIOS[k]
                val spectrum = 1.0 / (1.0 + (f * contactTau).pow(2))
                spectrum * (0.65 + 0.35 * cos(1.7 * k + 0.9 * i))
            }
            val total = w.sum()
            DoubleArray(TILE_MODES) { w[it] / total }
        }

        // ---- tiles ----
        val mountOmega = DoubleArray(TILES) { i ->
            val free = freeness(voice, glass, i)
            2.0 * PI * lerp(free.pow(0.8), MOUNT_HZ_STIFF, MOUNT_HZ_SOFT) * (1.0 + TILE_MOUNT_DEVIATION[i])
        }
        val gap = when {
            plan.calm >= 2 -> OPEN_GAP
            plan.steady -> max(gapFor(glass, voice), LOOP_GAP_FLOOR * if (plan.calm == 1) 3.0 else 1.0)
            else -> gapFor(glass, voice)
        }
        val tileNoise = Dsp.Noise(Dsp.seedFor("BALLAST", voice, midi, "tiles"))
        val contactOmega = 2.0 * PI * contactHz
        val kc = TILE_MASS * contactOmega * contactOmega * 0.5
        val cc = 2.0 * (if (plan.steady) LOOP_CONTACT_ZETA else CONTACT_ZETA) * sqrt(kc * TILE_MASS * 0.5)
        val tiles = Tiles(
            mountOmega, DoubleArray(TILES - 1) { gap * GAP_PATTERN[it] }, gap * WALL_GAP_SHARE, kc, cc,
            DoubleArray(TILES) { 1e-4 * tileNoise.next() },
            if (plan.calm >= 1) 0.5 else MOUNT_ZETA,
        )
        val unit = Array(TILES * TILE_MODES) { i -> DoubleArray(TILES * TILE_MODES).also { it[i] = 1.0 } }
        fun ring(tile: Int, energy: Double) {
            for (k in 0 until TILE_MODES) {
                val idx = tile * TILE_MODES + k
                val share = energy * ringWeight[tile][k]
                val v = glassBank.velocity(idx)
                glassBank.drive(unit[idx], (sqrt(v * v + 2.0 * share) - v) / dt)
                stats.ringEnergy += share
            }
        }
        val prevVel = DoubleArray(3)
        val bR = DoubleArray(3) { MOUNT_VECTOR[it] }

        // ---- the source ----
        val noiseA = Dsp.Noise(Dsp.seedFor("BALLAST", voice, midi, "phase"))
        var phaseA = (noiseA.next() + 1f) / 2.0
        var phaseB = (noiseA.next() + 1f) / 2.0
        val dtA = hz / rate
        val dtB = hz * plan.ratioB / rate
        val pulseWidth = lerp(drive, 0.5, 0.18)
        val ladder = Dsp.Ladder(rate)
        val attackN = max(1, (lerp(1.0 - v, shape.attackMs, VELOCITY_ATTACK_SLOW_MS) * 1e-3 * rate).toInt())
        val decayTau = shape.decaySeconds / 4.6
        val releaseTau = RELEASE_SECONDS / T60_LN
        val cutMultiple = shape.cutoffMultiple * 2.0.pow(2.0 * (drive - 0.45)) * lerp(v, VELOCITY_CUTOFF_SOFT, 1.0)
        val envOctaves = FILTER_ENV_OCTAVES * lerp(v, VELOCITY_FILTER_ENV_SOFT, 1.0) * lerp(drive, 0.5, 1.0)
        val wobbleOctaves = FILTER_WOBBLE_OCTAVES * drive
        val resonance = (shape.resonance + MAX_RESONANCE_FOLLOW * drive).toFloat()
        val inGain = 1.0 + 2.5 * drive
        val forceScale = lerp(drive * drive, FORCE_LOW, FORCE_HIGH) * lerp(v, VELOCITY_FORCE_SOFT, 1.0)
        val slowScale = if (probe.motionDrive) lerp(drive * drive, SLOW_LOW, SLOW_HIGH) * lerp(v, VELOCITY_FORCE_SOFT, 1.0) * rockHz.let { 2.0 * PI * it }.pow(2) * SLOW_GAIN else 0.0
        val wobblePhase0 = noiseA.next() * PI
        val aHp = onePoleA(ACTUATOR_HP_HZ, rate)
        val aSlowHi = onePoleA(SLOW_HIGH_HZ, rate)
        val aSlowLo = onePoleA(SLOW_LOW_HZ, rate)
        var hpState = 0.0
        var e1 = 0.0
        var e2 = 0.0
        var eSlow = 0.0
        var k1 = 0.0
        var k2 = 0.0
        var kSlow = 0.0
        val aKickHi = onePoleA(KICK_CUTOFF_MULTIPLE * hz, rate)
        val aKickLo = onePoleA(KICK_LOW_HZ, rate)
        val kickScale = lerp(sympathy.toDouble().pow(0.9), KICK_GAIN_LOW, KICK_GAIN_HIGH) * lerp(v, VELOCITY_FORCE_SOFT, 1.0)
        val riseTau = shape.riseSeconds
        val sourceOffN = probe.sourceOffAt?.let { (it * rate).toInt() } ?: Int.MAX_VALUE

        val frames = plan.frames
        val out = FloatArray(frames)
        val direct = if (probe.record) FloatArray(frames) else null
        val frameOut = if (probe.record) FloatArray(frames) else null
        val stringOut = if (probe.record) FloatArray(frames) else null
        val glassOut = if (probe.record) FloatArray(frames) else null
        val energyEvery = (0.05 * rate).toInt()
        var releaseLevel = 1.0

        var t = 0
        while (t < frames) {
            val time = t * dt
            // ---- the bass ----
            var src = 0.0
            if (t < sourceOffN) {
                val amp: Double
                val fEnv: Double
                if (plan.steady) {
                    amp = if (t < attackN) 0.5 * (1.0 - cos(PI * t / attackN)) else decayTo(t - attackN, decayTau, shape.sustain)
                    fEnv = exp(-time / FILTER_ENV_SECONDS)
                } else {
                    val tt = t
                    amp = when {
                        tt < attackN -> 0.5 * (1.0 - cos(PI * tt / attackN))
                        tt < plan.gateFrames -> decayTo(tt - attackN, decayTau, shape.sustain)
                        else -> {
                            if (tt == plan.gateFrames) releaseLevel = decayTo(tt - attackN, decayTau, shape.sustain)
                            releaseLevel * exp(-(tt - plan.gateFrames) * dt / releaseTau)
                        }
                    }
                    fEnv = if (tt < plan.gateFrames) exp(-time / FILTER_ENV_SECONDS) else exp(-plan.gateFrames * dt / FILTER_ENV_SECONDS) * exp(-(tt - plan.gateFrames) * dt / 0.08)
                }
                if (amp > 1e-9) {
                    val saw = 2.0 * phaseA - 1.0 - polyBlep(phaseA, dtA)
                    val pulse = (if (phaseB < pulseWidth) 1.0 else -1.0) + polyBlep(phaseB, dtB) - polyBlep((phaseB + 1.0 - pulseWidth) % 1.0, dtB)
                    val wobble = sin(2.0 * PI * plan.wobbleHz * time + wobblePhase0)
                    val cutoff = (hz * cutMultiple * 2.0.pow(envOctaves * fEnv + wobbleOctaves * wobble)).coerceIn(30.0, rate * 0.3)
                    val x = (shape.sawLevel * saw + shape.pulseLevel * pulse) * inGain * 0.5
                    src = ladder.process(x.toFloat(), cutoff.toFloat(), resonance) * amp
                }
            }
            phaseA += dtA; if (phaseA >= 1.0) phaseA -= 1.0
            phaseB += dtB; if (phaseB >= 1.0) phaseB -= 1.0

            // ---- the actuator: the source itself, high-passed and bounded; and its slow energy ----
            hpState += aHp * (src - hpState)
            val hp = src - hpState
            val rise = if (riseTau > 0.0) 1.0 - exp(-time / riseTau) else 1.0
            val force = if (t < sourceOffN) forceScale * rise * tanh(hp * FORCE_KNEE) / FORCE_KNEE else 0.0
            val rectified = abs(hp)
            e1 += aSlowHi * (rectified - e1)
            e2 += aSlowHi * (e1 - e2)
            eSlow += aSlowLo * (e2 - eSlow)
            val slow = if (t < sourceOffN) slowScale * rise * (e2 - eSlow) else 0.0
            k1 += aKickHi * (rectified - k1)
            k2 += aKickHi * (k1 - k2)
            kSlow += aKickLo * (k2 - kSlow)
            val kick = if (t < sourceOffN) kickScale * rise * (k2 - kSlow) else 0.0
            if (force != 0.0) {
                val vel = bank.velocityAlong(actuator)
                stats.work += force * vel * dt
                if (abs(force) > stats.peakForce) stats.peakForce = abs(force)
                bank.drive(actuator, force)
            }
            if (slow != 0.0) bank.drive(rockOnly, slow)
            if (kick != 0.0) bank.drive(kickVec, kick)
            bank.step()
            glassBank.step()

            // ---- the tiles, at the control rate ----
            if (t % CTRL == 0) {
                var aBase = 0.0
                for (k in 0 until 3) {
                    val vk = bank.velocity(k)
                    aBase += bR[k] * (vk - prevVel[k]) / dtc
                    prevVel[k] = vk
                }
                val reaction = tiles.step(aBase, dtc, stats, time) { tile, other, lost ->
                    if (other < 0) ring(tile, RING_SHARE * lost) else { ring(tile, 0.5 * RING_SHARE * lost); ring(other, 0.5 * RING_SHARE * lost) }
                }
                if (probe.frameReturn) bank.drive(mountVec, reaction * CTRL)
                val q0 = abs(bank.displacement(0))
                if (q0 > stats.peakRock) stats.peakRock = q0
                var qf = 0.0
                for (k in 0 until 3) qf += bR[k] * bank.displacement(k)
                if (abs(qf) > stats.peakFrame) stats.peakFrame = abs(qf)
            }

            // ---- the pickup ----
            val d = src * DIRECT_GAIN
            val fs = bank.velocityAlong(bothPick)
            val g = if (probe.glass) GLASS_KNEE * tanh(glassBank.velocityAlong(glassPick) / GLASS_KNEE) else 0.0
            out[t] = (d + fs + g).toFloat()
            if (probe.record) {
                direct!![t] = d.toFloat()
                frameOut!![t] = bank.velocityAlong(framePick).toFloat()
                stringOut!![t] = bank.velocityAlong(stringPick).toFloat()
                glassOut!![t] = g.toFloat()
            }
            if (probe.record && t % energyEvery == 0) {
                stats.energyTimes += time
                stats.energies += bank.energy() + glassBank.energy() + tiles.energy()
            }
            t++
        }
        return Run(out, stats, if (probe.record) Parts(direct!!, frameOut!!, stringOut!!, glassOut!!) else null)
    }

    private fun decayTo(n: Int, tau: Double, sustain: Double): Double =
        sustain + (1.0 - sustain) * exp(-n / (tau * RATE * Dsp.OVERSAMPLE))

    /**
     * The six tiles in the frame's coordinates, each on its own mount, with a compression-only damped contact between
     * neighbours and at the two housing walls. A contact's damper turns kinetic energy into the loss that rings the
     * glass; nothing here adds energy to anything.
     */
    private class Tiles(
        val omega: DoubleArray,
        val pairGap: DoubleArray,
        val wallGap: Double,
        val kc: Double,
        val cc: Double,
        val y: DoubleArray,
        val mountZeta: Double,
    ) {
        val yv = DoubleArray(TILES)
        private val slots = TILES - 1 + 2
        private val loss = DoubleArray(slots)
        private val peak = DoubleArray(slots)
        private val active = BooleanArray(slots)
        private val force = DoubleArray(slots)
        private val pen = DoubleArray(slots)

        private fun slotTiles(s: Int, out: IntArray) {
            when {
                s < TILES - 1 -> { out[0] = s; out[1] = s + 1 }
                s == TILES - 1 -> { out[0] = 0; out[1] = -1 }
                else -> { out[0] = TILES - 1; out[1] = -1 }
            }
        }

        private val pair = IntArray(2)
        private val work = DoubleArray(TILES)

        /** One control step under the frame's acceleration [accel]; returns the force the mounts and walls put on the frame. */
        fun step(accel: Double, dt: Double, stats: Stats, time: Double, onKnock: (tile: Int, other: Int, lost: Double) -> Unit): Double {
            val f = work
            f.fill(0.0)
            var reaction = 0.0
            for (i in 0 until TILES) {
                val spring = TILE_MASS * omega[i] * omega[i] * y[i] + 2.0 * mountZeta * TILE_MASS * omega[i] * yv[i]
                f[i] -= spring + TILE_MASS * accel
                reaction += spring
            }
            for (s in 0 until slots) {
                val p: Double
                val pd: Double
                when {
                    s < TILES - 1 -> { p = y[s] - y[s + 1] - pairGap[s]; pd = yv[s] - yv[s + 1] }
                    s == TILES - 1 -> { p = -wallGap - y[0]; pd = -yv[0] }
                    else -> { p = y[TILES - 1] - wallGap; pd = yv[TILES - 1] }
                }
                val raw = if (p > 0.0) kc * p + cc * pd else 0.0
                if (raw > 0.0) {
                    force[s] = raw
                    pen[s] = p
                    active[s] = true
                    loss[s] += cc * pd * pd * dt
                    if (raw > peak[s]) peak[s] = raw
                    when {
                        s < TILES - 1 -> { f[s] -= raw; f[s + 1] += raw }
                        s == TILES - 1 -> { f[0] += raw; reaction -= raw }
                        else -> { f[TILES - 1] -= raw; reaction += raw }
                    }
                } else if (active[s]) {
                    active[s] = false
                    if (loss[s] > MIN_KNOCK_ENERGY) {
                        stats.knockTimes += time
                        stats.knockEnergies += loss[s]
                        stats.knockPeaks += peak[s]
                        stats.knockLoss += loss[s]
                        slotTiles(s, pair)
                        onKnock(pair[0], pair[1], loss[s])
                    }
                    loss[s] = 0.0
                    peak[s] = 0.0
                }
            }
            for (i in 0 until TILES) {
                yv[i] += f[i] / TILE_MASS * dt
                y[i] += yv[i] * dt
                if (abs(y[i]) > TRAVEL_LIMIT) {
                    y[i] = TRAVEL_LIMIT * if (y[i] > 0) 1.0 else -1.0
                    yv[i] = 0.0
                    stats.travelStops++
                }
                if (abs(y[i]) > stats.peakTravel) stats.peakTravel = abs(y[i])
            }
            return reaction
        }

        fun energy(): Double {
            var e = 0.0
            for (i in 0 until TILES) e += 0.5 * TILE_MASS * (yv[i] * yv[i] + omega[i] * omega[i] * y[i] * y[i])
            for (s in 0 until slots) if (active[s]) e += 0.5 * kc * pen[s] * pen[s]
            return e
        }
    }

    // ---- the output chain -----------------------------------------------------------------------------

    /** The house chain: band limit at the oversampled rate, decimate, DC out twice, [Dsp.levelTo] unless [level] is false. */
    internal fun condition(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw, RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(raw, RATE)
        var mean = 0.0
        for (v in out) mean += v
        val mm = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mm
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        return out
    }

    internal fun finish(raw: FloatArray, tail: Double): FloatArray {
        val out = condition(raw)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        val n = (TAIL_FADE_SHARE * tail * RATE).toInt().coerceIn(1, out.size)
        val from = out.size - n
        for (k in 0 until n) out[from + k] *= (0.5 + 0.5 * cos(PI * k / n)).toFloat()
        Dsp.fadeTail(out)
        return out
    }

    // ---- the render -------------------------------------------------------------------------------------

    /**
     * [velocity] is a render parameter, as on MERCURY: no knob, no preset. 1, the default, is a full touch, so a kit pad
     * or a preset renders the hard version.
     */
    fun render(voice: BallastVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) return Snip(renderLoop(voice, m), channels = 1, sampleRate = RATE)
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val run = simulate(voice, oneShotPlan(voice, hz, m), m, velocity.coerceIn(0f, 1f).toDouble())
        val tail = totalSeconds(voice, m) - gateSeconds(m.getValue("HOLD")) - RELEASE_SECONDS
        return Snip(finish(run.raw, tail), channels = 1, sampleRate = RATE)
    }

    private const val SEAM_FRAMES = 256

    /** A rendered LOOP and how well its stretch closes on itself ([Keys.seamError]). */
    internal class LoopRender(val loop: FloatArray, val seam: Double, val prerollSeconds: Double, val calm: Int)

    internal fun renderLoop(voice: BallastVoice, macros: Map<String, Float>): FloatArray {
        val r = renderLoopMeasured(voice, macros)
        require(r.seam < Keys.MAX_SEAM_ERROR) {
            "BALLAST $voice ${midiFor(voice, settled(macros, voice).getValue("TUNE"))}: the loop does not close (seam %.2e, bar %.0e)"
                .format(java.util.Locale.ROOT, r.seam, Keys.MAX_SEAM_ERROR)
        }
        return r.loop
    }

    internal fun renderLoopMeasured(voice: BallastVoice, macros: Map<String, Float>, velocity: Double = 1.0): LoopRender {
        val m = settled(macros, voice)
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val plan = planLoop(voice, hz)
        val preroll = prerollSeconds(voice, m)
        var best: LoopRender? = null
        for ((calm, longer) in LOOP_ATTEMPTS) {
            val r = loopStretch(voice, m, plan, min(preroll * longer, LOOP_MAX_PREROLL_SECONDS), velocity, calm)
            if (best == null || r.seam < best.seam) best = r
            if (r.seam < Keys.MAX_SEAM_ERROR * LOOP_MARGIN) break
        }
        return best!!
    }

    /** The passes a loop gets, in order: (calm, preroll multiple). Almost every corner closes on the first. */
    internal val LOOP_ATTEMPTS = listOf(0 to 1.0, 0 to 1.6, 1 to 1.6, 2 to 1.0)
    const val LOOP_MARGIN = 0.5
    const val LOOP_MAX_PREROLL_SECONDS = 14.0

    private fun loopStretch(voice: BallastVoice, m: Map<String, Float>, plan: LoopPlan, preroll: Double, velocity: Double, calm: Int): LoopRender {
        val warm = Math.round(preroll * RATE).toInt()
        val total = warm + plan.frames + SEAM_FRAMES
        val steady = Plan(plan.hz, plan.ratioB, plan.wobbleCycles / plan.seconds, true, total * Dsp.OVERSAMPLE, 0, 0, calm)
        val raw = simulate(voice, steady, m, velocity).raw
        val conditioned = condition(raw)
        val seam = Keys.seamError(conditioned.copyOfRange(warm, warm + plan.frames + SEAM_FRAMES), SEAM_FRAMES)
        val one = conditioned.copyOfRange(warm, warm + plan.frames)
        val cut = Siren.bestCut(one, plan.frames)
        val loop = one.copyOfRange(cut, plan.frames) + one.copyOfRange(0, cut)
        Dsp.levelTo(loop, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        return LoopRender(loop, seam, preroll, calm)
    }

    /**
     * A one-shot taken apart for the audition page: the bass alone, the bass with the frame and the wires, and everything,
     * all conditioned and scaled by the one gain that levels the whole, so the three are directly comparable. A
     * [sourceOffAt] stops the bass dead at that second, to hear the structure settle on its own.
     */
    internal fun renderParts(voice: BallastVoice, macros: Map<String, Float>, sourceOffAt: Double? = null): Map<String, FloatArray> {
        val m = settled(macros, voice)
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val run = simulate(voice, oneShotPlan(voice, hz, m), m, 1.0, Probe(record = true, sourceOffAt = sourceOffAt))
        val parts = requireNotNull(run.parts)
        fun mix(vararg pieces: FloatArray) = FloatArray(run.raw.size) { i -> pieces.sumOf { it[i].toDouble() }.toFloat() }
        val variants = linkedMapOf(
            "bass" to condition(mix(parts.direct)),
            "structure" to condition(mix(parts.frame, parts.strings)),
            "bass+structure" to condition(mix(parts.direct, parts.frame, parts.strings)),
            "all" to condition(run.raw.copyOf()),
        )
        val whole = variants.getValue("all")
        val measured = Loudness.of(Snip(whole.copyOf(), channels = 1, sampleRate = RATE))
        val gain = if (measured > 1e-6f) Dsp.MELODIC_LOUDNESS_TARGET / measured else 1f
        var peak = 0f
        for (v in variants.values) for (x in v) peak = max(peak, abs(x * gain))
        val g = if (peak > 0.99f) gain * 0.99f / peak else gain
        for (v in variants.values) for (k in v.indices) v[k] *= g
        return variants
    }
}
