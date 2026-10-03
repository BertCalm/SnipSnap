package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * MERCURY: one imaginary resonant object, part glass, part bent steel, with water
 * moving inside it. It can be tapped or rubbed, it bends, and its modes move
 * together as the water moves.
 * - **Design:** `docs/superpowers/specs/2026-10-01-mercury-modal-glass-engine-design.md`.
 * - **Phase-0 record:** `docs/superpowers/plans/2026-10-01-mercury-phase-0-spike.md`.
 *
 * **The object** is a [Modes.Bank] of up to 12 primary modes and 4 vessel modes:
 * - **Primary ratios** are Rayleigh's closed forms. PING and SING use the thin
 *   ring's inextensional bending modes, f_k ∝ k(k²−1)/√(k²+1) (a glass rim).
 *   BLADE uses the free-free beam, β_k², whose first four are
 *   `Modes.METAL_BAR`'s ratios.
 * - **Vessel modes** sit 3.5–7 % from the first four primaries (designed
 *   numbers). They are never struck or rubbed and only gain energy through
 *   the springs, which is what makes COUPLE audible as an exchange rather
 *   than as a gain.
 *
 * **One contact vector** carries both the strike and the friction. RUB moves
 * energy from the one to the other along the same vector, so a tap can seed a
 * rub and the middle of the knob is one object doing both, not two renders
 * crossfaded.
 *
 * **The mapping is Phase 0's iteration 3**, measured and not heard:
 * - pressure is set by a target e-fold of `max(20 ms, 12 periods)`, scaled by
 *   RUB²;
 * - a contact-patch taper, `b_i = ratio^−0.5`, keeps mode 1 from capturing a
 *   low note;
 * - the tap weight has a 0.2 floor, so even RUB 1 has a finger landing;
 * - WATER's depth goes with WATER^0.25 (round 2: √WATER was not heard below 0.2);
 * - the coupled anchor is put back on the note once per note
 *   ([Modes.Bank.anchorScale]).
 *
 * Every value marked "listening" is a first guess for the audition. The engine
 * renders **dry**, with no landing chain.
 *
 * **R1 has no LOOP.** HOLD is the contact length, and its whole travel is a
 * one-shot; the LOOP top step is R2.
 */
enum class MercuryVoice { PING, SING, BLADE }

object Mercury {

    /** TUNE: two octaves, snapped to semitones (every melodic engine's rule). */
    const val TUNE_SEMITONES = 24

    const val PING_ROOT_MIDI = 60
    const val SING_ROOT_MIDI = 55
    const val BLADE_ROOT_MIDI = 55

    // ---- the object --------------------------------------------------------

    const val PRIMARIES = 12
    const val VESSELS = 4

    /** Vessel mode v sits this far from primary v: near enough for COUPLE to trade energy with it. Designed, not sourced. */
    private val VESSEL_DETUNE = doubleArrayOf(0.035, -0.045, 0.06, -0.07)

    /** Spring kappa per COUPLE, before WATER's loading lifts it. The busiest mode has four springs, so its sum stays under 0.77. */
    const val KAPPA_PER_COUPLE = 0.12

    /** WATER's loading can lift a spring's kappa by up to this share of its load sum. */
    const val KAPPA_WATER_LIFT = 0.3

    /**
     * Modes are dropped when they could reach this frequency (the settled ratio times the largest
     * BEND, excursion and anchor lift, [MODE_HEADROOM]). Far under the bank's split bound: at
     * 40 kHz and 176.4 kHz, x·tan x is 0.62, so a kappa sum of 0.77 is at 0.48 of it.
     */
    const val MODE_CEILING_HZ = 40_000.0
    private const val MODE_HEADROOM = 1.45

    /** Modes fade out between these, so nothing that the band limit removes carries energy that matters. */
    const val FADE_LO_HZ = 14_000.0
    const val FADE_HI_HZ = 19_000.0

    // ---- GLASS ---------------------------------------------------------------

    /** GLASS moves the fundamental's t60 from LOW to HIGH, seconds. */
    const val T60_LOW = 1.2
    const val T60_HIGH = 9.0

    /** ...and how fast t60 falls up the modes, as ratio^(−α): steep (damped, flexible) to shallow (clear, selective). */
    const val T60_SLOPE_LOW = 1.6
    const val T60_SLOPE_HIGH = 0.4

    /** The vessel's own t60, seconds, low to high GLASS. */
    const val VESSEL_T60_LOW = 0.6
    const val VESSEL_T60_HIGH = 2.0

    /** The pickup's tilt, ratio^(−tilt): dark at low GLASS, open at high. */
    const val TILT_LOW = 0.8
    const val TILT_HIGH = 0.1

    // ---- RUB and the contact ------------------------------------------------

    /** The friction curve's a: its peak sits at η* = 1/√(2a) = 0.01. */
    const val FRICTION_A = 5000.0

    /** The finger's speed: three times η*, on the curve's falling side. */
    const val DRIVER_SPEED = 0.03

    /** The finger comes up to speed over this long. */
    const val DRIVER_RAMP_SECONDS = 0.02

    /** Pressure is lifted off over this long when the contact ends. */
    const val CONTACT_RELEASE_SECONDS = 0.03

    /** The rub's target linear e-fold: this many seconds, or [ONSET_PERIODS] periods if longer (Phase 0 iteration 3). */
    const val ONSET_SECONDS = 0.02
    const val ONSET_PERIODS = 12.0

    /** The contact-patch taper: b_i = ratio^(−this). */
    const val CONTACT_TAPER = 0.5

    /**
     * Velocity, a render parameter (not a knob) that only the rubbed voices read; PING's velocity is GLASS
     * (`Velocity.brightnessOverride`). A rubbed body is close to a pure tone, so a harder touch cannot be heard as
     * brighter (a steeper contact taper moved the held body 4–13%); it is heard in how the note starts. The owner
     * chose "attack and bite" (2026-10-02). A soft touch: the finger or bow comes up to speed up to this many times
     * slower than [DRIVER_RAMP_SECONDS], so the note swells in. Listening value.
     */
    const val VELOCITY_RAMP = 30.0

    /**
     * A hard touch catches with a scrape: the contact's own noise, this many dB under the note itself at that moment
     * at full velocity (its amplitude goes with velocity), dying over [SCRAPE_SECONDS]. It is added after the modes,
     * because friction noise fed through the contact comes out as tone: the high-Q modes filter it, so a 3x rougher
     * catch left the 1–6 kHz spectral flatness at zero. Listening value.
     *
     * It rides the note's own envelope, read every [SCRAPE_BLOCK_SECONDS]. Round 3 levelled it against the held
     * tone instead, 12 dB under, and the owner heard "a little snare or clap" (2026-10-03): the note is still
     * swelling in its first tens of milliseconds, so the scrape was 2–4 dB over it at full velocity and up to
     * 7.6 dB over it for 75 ms at velocity .65. A burst of noise over a near-silent start is a clap. It also rose
     * in 1 ms, a click; it now rises over [SCRAPE_RISE_SECONDS].
     *
     * The band stays under 2 kHz, the classifier's SNARE line (its share of magnitude above 2 kHz): at 1–6 kHz the
     * shortest SING (GLASS 0, HOLD 0) was heard as SNARE. Two poles on the top edge keep the leak above it small.
     */
    const val SCRAPE_DB = -12.0
    const val SCRAPE_SECONDS = 0.04
    const val SCRAPE_RISE_SECONDS = 0.008
    const val SCRAPE_BLOCK_SECONDS = 0.0025
    const val SCRAPE_LOW_HZ = 400f
    const val SCRAPE_HIGH_HZ = 1500f

    /** The tap's weight never falls under this, even at RUB 1: the finger lands (Phase 0 I4c, 95–534 ms onset). */
    const val TAP_FLOOR = 0.2

    /**
     * Contact roughness: the pressure jitters by up to this share at GLASS 0 and not at all at GLASS 1, as
     * seeded noise smoothed below [ROUGHNESS_HZ]. A rough, damped surface grabs and slips unevenly and
     * excites broadly; clear glass sings one mode. It is GLASS's hold on a rubbed note, which damping
     * alone barely has once friction locks to the fundamental. Listening values.
     */
    const val ROUGHNESS = 0.6
    const val ROUGHNESS_HZ = 2000f

    /** The strike's velocity jump, at full tap weight, in the same units as [DRIVER_SPEED]. */
    const val STRIKE_IMPULSE = 0.03

    /** The strike's half-sine is this long at GLASS 1 and that much longer at GLASS 0, ms. Listening values. */
    const val STRIKE_MS_HARD = 0.4
    const val STRIKE_MS_SOFT = 1.2

    /** A short seeded burst on the strike, as a share of its peak, over [STRIKE_NOISE_SECONDS]. Listening value. */
    const val STRIKE_NOISE = 0.1
    const val STRIKE_NOISE_SECONDS = 0.002

    // ---- BEND ---------------------------------------------------------------

    /** The largest signed pitch excursion of the bend gesture, semitones, at BEND 0 or 1. */
    const val BEND_EXCURSION_SEMITONES = 2.0

    /** The gesture's time constant per voice, seconds: a ping deforms fast, a blade glides. Listening values. */
    fun gestureSeconds(voice: MercuryVoice): Double = when (voice) {
        MercuryVoice.PING -> 0.08
        MercuryVoice.SING -> 0.6
        MercuryVoice.BLADE -> 0.4
    }

    // ---- WATER --------------------------------------------------------------

    /** WATER's mass depth is this times WATER^[WATER_CURVE] (Phase 0 iteration 2's depth). */
    const val WATER_DEPTH = 0.06

    /**
     * WATER's curve. Round 1 used √WATER, and the owner could not hear WATER 0.05–0.2 on SING or BLADE
     * (2026-10-02). The steeper start puts WATER 0.05 at about 0.47 of the full depth. Listening value.
     */
    const val WATER_CURVE = 0.25

    /**
     * The mass orbits at RATE_LOW + RATE_SPAN·WATER Hz, and a mode's load rises and falls twice per orbit:
     * 0.5–2 Hz, a drift. Round 1's 0.6–3 Hz orbit, read through `cos((k + 2)·angle)`, moved the fundamental
     * at 2.4–12 Hz and the upper modes faster still: a flutter the ear does not hear as water.
     */
    const val WATER_RATE_LOW = 0.25
    const val WATER_RATE_SPAN = 0.75

    /** A ring mode k reads the mass at its own angle, k times this many radians round. Designed. */
    const val RING_LOAD_PHASE = 0.9

    /** The steady orbit's ρ², the ring's load at full swing, so a ring is loaded as deeply as the beam. */
    const val RING_ORBIT_LOAD = 0.36

    /**
     * The water moves what each mode radiates: a loaded mode's pickup rises and an unloaded one's falls by up to
     * this share, times WATER^[WATER_CURVE]. Friction locks a rubbed voice's partials to one period, so the
     * water cannot be heard as the partials drifting apart; it is heard as their levels moving. Designed.
     */
    const val WATER_SHIMMER = 0.7

    /** WATER's loading shortens a mode's t60 by 1 + this·μ·load. */
    const val WATER_DAMPING = 4.0

    /** ...and lightens its contact by up to this share. */
    const val WATER_CONTACT = 0.3

    // ---- HOLD and the length ------------------------------------------------

    const val HOLD_MIN_SECONDS = 0.3f
    const val HOLD_MAX_SECONDS = 4f
    const val DEFAULT_HOLD = 0.45f

    /** After the contact the object rings for this share of the fundamental's t60, within the clamp below. */
    const val TAIL_T60_SHARE = 0.5
    const val TAIL_MIN_SECONDS = 0.4
    const val TAIL_MAX_SECONDS = 2.5

    /** The last share of the tail fades out on a raised cosine: a documented release, not a cut. */
    const val TAIL_FADE_SHARE = 0.4

    // ---- the output ---------------------------------------------------------

    const val OUTPUT_DC_HZ = 20f

    /** The classifier's own length rule: past this, a render's duration alone reads LOOP. */
    const val LOOP_THRESHOLD_SECONDS = 1.5f

    /** Control rate: the shape, the water and the tuning move every this many samples at the oversampled rate. */
    private const val CTRL = 8

    private const val T60_LN = 6.907755278982137

    // ---- the knobs ------------------------------------------------------------

    fun macrosFor(voice: MercuryVoice): List<MacroSpec> = when (voice) {
        MercuryVoice.PING -> specs(bend = 0.5f, rub = 0.08f, water = 0.10f, glass = 0.75f, couple = 0.20f)
        MercuryVoice.SING -> specs(bend = 0.5f, rub = 0.85f, water = 0.12f, glass = 0.85f, couple = 0.25f)
        MercuryVoice.BLADE -> specs(bend = 0.65f, rub = 0.80f, water = 0.15f, glass = 0.45f, couple = 0.30f)
    }

    private fun specs(bend: Float, rub: Float, water: Float, glass: Float, couple: Float) = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("BEND", bend, neutral = 0.5f),
        MacroSpec("RUB", rub, neutral = 0.35f),
        MacroSpec("WATER", water, neutral = 0f),
        MacroSpec("GLASS", glass, neutral = 0.55f),
        MacroSpec("COUPLE", couple, neutral = 0.25f),
        MacroSpec("HOLD", DEFAULT_HOLD),
    )

    fun defaults(voice: MercuryVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(macros: Map<String, Float>, voice: MercuryVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun rootMidi(voice: MercuryVoice): Int = when (voice) {
        MercuryVoice.PING -> PING_ROOT_MIDI
        MercuryVoice.SING -> SING_ROOT_MIDI
        MercuryVoice.BLADE -> BLADE_ROOT_MIDI
    }

    /** The snapped MIDI note TUNE lands on. */
    fun midiFor(voice: MercuryVoice, tune: Float): Int = rootMidi(voice) + Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)

    fun frequencyFor(voice: MercuryVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    fun holdSeconds(hold: Float): Float = Dsp.expMap(hold.coerceIn(0f, 1f), HOLD_MIN_SECONDS, HOLD_MAX_SECONDS)

    fun t60Fundamental(glass: Float): Double = T60_LOW + (T60_HIGH - T60_LOW) * glass

    fun tailSeconds(glass: Float): Double = (TAIL_T60_SHARE * t60Fundamental(glass)).coerceIn(TAIL_MIN_SECONDS, TAIL_MAX_SECONDS)

    /** Raw frames at the oversampled rate: the contact, its release, and the tail, rounded to a whole number of output frames. */
    private fun rawFrames(m: Map<String, Float>): Int {
        val seconds = holdSeconds(m.getValue("HOLD")) + CONTACT_RELEASE_SECONDS + tailSeconds(m.getValue("GLASS"))
        return (seconds * RATE).toInt() * Dsp.OVERSAMPLE
    }

    /** The exact number of frames a render at [macros] has at [RATE]. `MercuryTest` pins it against a real render. */
    internal fun renderFrames(macros: Map<String, Float>): Int = rawFrames(macros) / Dsp.OVERSAMPLE

    /**
     * What a pad holding this sound is filed as: LOOP past the classifier's own 1.5 s length line,
     * PERC under it. Derived from the rendered frame count, the comparison the classifier makes.
     */
    fun drumClassFor(voice: MercuryVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val m = settled(macros, voice)
        return if (renderFrames(m).toFloat() / RATE > LOOP_THRESHOLD_SECONDS) DrumClass.LOOP else DrumClass.PERC
    }

    fun scramble(voice: MercuryVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + MercuryPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    // ---- the object's tables -----------------------------------------------

    /** Thin ring, inextensional bending (Rayleigh): f_k ∝ k(k²−1)/√(k²+1), k = 2, 3, …, normalised to the first. */
    internal fun ringRatios(n: Int): DoubleArray {
        val r = DoubleArray(n) { i -> val k = i + 2.0; k * (k * k - 1) / sqrt(k * k + 1) }
        return DoubleArray(n) { r[it] / r[0] }
    }

    /** Free-free beam (Rayleigh): f_k ∝ β_k², β = 4.7300, 7.8532, 10.9956, 14.1372, then (2k+1)π/2. */
    internal fun beamRatios(n: Int): DoubleArray {
        val b = DoubleArray(n) { i ->
            when (i) {
                0 -> 4.7300; 1 -> 7.8532; 2 -> 10.9956; 3 -> 14.1372
                else -> (2.0 * (i + 1) + 1) * PI / 2
            }
        }
        return DoubleArray(n) { (b[it] / b[0]).pow(2) }
    }

    private fun isRing(voice: MercuryVoice) = voice != MercuryVoice.BLADE

    /** BEND's signed per-mode deformation: the first mode never moves; the others drift up or down. Designed. */
    private fun bendA(i: Int) = if (i == 0) 0.0 else 0.12 * sin(1.3 * i + 0.4)
    private fun bendB(i: Int, n: Int) = if (i == 0) 0.0 else 0.05 * i / n

    // ---- the render ---------------------------------------------------------

    /**
     * [velocity] is a render parameter, as on PLUCK: no knob, no preset. SING and BLADE read it as the touch
     * ([VELOCITY_RAMP], [SCRAPE_DB]); PING ignores it, since its velocity is GLASS. 1, the default, is a full
     * touch, so a kit pad or a preset renders the hard version.
     */
    fun render(voice: MercuryVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val m = settled(macros, voice)
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val touch = if (voice == MercuryVoice.PING) 1.0 else velocity.coerceIn(0f, 1f).toDouble()
        return Snip(finish(sound(voice, hz, m, touch), tailSeconds(m.getValue("GLASS"))), channels = 1, sampleRate = RATE)
    }

    /**
     * The object, rung at [hz] for [macros] (settled), at the oversampled rate: raw, unlevelled,
     * so tests can read the physics. Its length is [rawFrames].
     */
    internal fun sound(voice: MercuryVoice, hz: Double, m: Map<String, Float>, velocity: Double = 1.0, scrape: Boolean = true): FloatArray {
        val rate = RATE * Dsp.OVERSAMPLE
        val dt = 1.0 / rate
        val bend = m.getValue("BEND").toDouble()
        val rub = m.getValue("RUB").toDouble()
        val water = m.getValue("WATER").toDouble()
        val glass = m.getValue("GLASS").toDouble()
        val couple = m.getValue("COUPLE").toDouble()
        val frames = rawFrames(m)

        // Which modes take part: those that cannot reach the ceiling at the widest bend.
        val base = if (isRing(voice)) ringRatios(PRIMARIES) else beamRatios(PRIMARIES)
        val primaries = (0 until PRIMARIES).count { hz * base[it] * MODE_HEADROOM < MODE_CEILING_HZ }.coerceAtLeast(1)
        val vessels = min(VESSELS, primaries)
        val n = primaries + vessels
        val ratio0 = DoubleArray(n) { i -> if (i < primaries) base[i] else base[i - primaries] * (1 + VESSEL_DETUNE[i - primaries]) }
        val isVessel = BooleanArray(n) { it >= primaries }
        val modeIndex = IntArray(n) { if (it < primaries) it else it - primaries }

        // GLASS: correlated damping, its slope up the modes, and the pickup's tilt.
        val t60Fund = t60Fundamental(glass.toFloat())
        val slope = T60_SLOPE_LOW + (T60_SLOPE_HIGH - T60_SLOPE_LOW) * glass
        val t60Base = DoubleArray(n) { i ->
            if (isVessel[i]) VESSEL_T60_LOW + (VESSEL_T60_HIGH - VESSEL_T60_LOW) * glass
            else max(0.02, t60Fund * ratio0[i].pow(-slope))
        }
        val tilt = TILT_LOW + (TILT_HIGH - TILT_LOW) * glass
        fun beamShape(k: Int, x: Double) = cos((k + 1.5) * PI * x)
        val contact0 = DoubleArray(n) { i -> if (isVessel[i]) 0.0 else ratio0[i].pow(-CONTACT_TAPER) }
        val pickup0 = DoubleArray(n) { i ->
            if (isVessel[i]) {
                0.5 * (if ((i - primaries) % 2 == 0) 1 else -1)
            } else {
                val s = if (isRing(voice)) cos((modeIndex[i] + 2) * 0.4) else beamShape(modeIndex[i], 0.3)
                s * ratio0[i].pow(-tilt)
            }
        }

        // BEND: a gesture from flat toward the target curvature, with a signed pitch excursion that settles to the note.
        val cTarget = 2 * bend - 1
        val tau = gestureSeconds(voice)
        fun ratioAt(i: Int, c: Double) = ratio0[i] * exp(bendA(i) * c + bendB(i, n) * c * c)

        // The bank, at the settled shape, for the anchor fix.
        val bank = Modes.Bank(n, rate)
        val kappa = KAPPA_PER_COUPLE * couple
        val edgeI = ArrayList<Int>()
        val edgeJ = ArrayList<Int>()
        for (i in 0 until primaries - 1) { edgeI += i; edgeJ += i + 1 }
        for (v in 0 until vessels) {
            edgeI += primaries + v; edgeJ += v
            if (v + 1 < primaries) { edgeI += primaries + v; edgeJ += v + 1 }
        }
        for (i in 0 until n) bank.tune(i, (hz * ratioAt(i, cTarget)).coerceAtMost(MODE_CEILING_HZ), t60Base[i])
        val edges = IntArray(edgeI.size) { e -> bank.connect(edgeI[e], edgeJ[e], kappa) }
        val pitchFix = bank.anchorScale()

        // WATER: one damped, circularly forced mass that every mode reads.
        val massRng = Dsp.Noise(Dsp.seedFor("MERCURY", voice, hz, "mass"))
        val om = 2 * PI * (WATER_RATE_LOW + WATER_RATE_SPAN * water)
        var mx = 0.3 * massRng.next()
        var my = 0.3 * massRng.next()
        var mvx = 0.0
        var mvy = 0.0
        val waterCurve = if (water > 0.0) water.pow(WATER_CURVE) else 0.0
        val mu = WATER_DEPTH * waterCurve
        val shimmer = WATER_SHIMMER * waterCurve
        // Every load swings between 0 and 1 and averages about a half; the pitch is centred there.
        val anchorNominal = 1.0 / sqrt(1 + mu * 0.5)
        val load = DoubleArray(n)
        val ctrlDt = CTRL * dt

        // RUB: the strike's weight and the contact's pressure.
        val rubW = sin(PI * rub / 2)
        val tapW = max(cos(PI * rub / 2), TAP_FLOOR)
        val friction = Modes.Friction(FRICTION_A)
        val onsetTau = max(ONSET_SECONDS, ONSET_PERIODS / hz)
        val gamma0 = 2 * T60_LN / t60Base[0]
        val pressure = rubW * rubW * (gamma0 + 1 / onsetTau) / (abs(friction.slope(DRIVER_SPEED)) * contact0[0] * contact0[0])
        val contactFrames = (holdSeconds(m.getValue("HOLD")) * rate).toInt()
        val releaseFrames = (CONTACT_RELEASE_SECONDS * rate).toInt()
        val rampFrames = (DRIVER_RAMP_SECONDS * (1 + VELOCITY_RAMP * (1 - velocity)) * rate).toInt()

        val strikeFrames = ((STRIKE_MS_HARD + (STRIKE_MS_SOFT - STRIKE_MS_HARD) * (1 - glass)) * 1e-3 * rate).toInt().coerceAtLeast(2)
        val strikePeak = STRIKE_IMPULSE * tapW * PI / (2 * strikeFrames * dt)
        val noiseFrames = (STRIKE_NOISE_SECONDS * rate).toInt()
        val noise = Dsp.Noise(Dsp.seedFor("MERCURY", voice, hz, "strike"))
        val grain = Dsp.Noise(Dsp.seedFor("MERCURY", voice, hz, "contact"))
        val grainLp = Dsp.OnePole(rate)
        val roughness = ROUGHNESS * (1 - glass)

        val contact = DoubleArray(n)
        val pickup = DoubleArray(n)
        var compliance = 0.0
        val out = FloatArray(frames)
        for (t in 0 until frames) {
            if (t % CTRL == 0) {
                val time = t * dt
                if (mu > 0.0) {
                    val ax = -om * mvx - om * om * mx + om * om * 0.6 * cos(om * time)
                    val ay = -om * mvy - om * om * my + om * om * 0.6 * sin(om * time)
                    mvx += ax * ctrlDt; mvy += ay * ctrlDt
                    mx += mvx * ctrlDt; my += mvy * ctrlDt
                }
                val rho2 = (mx * mx + my * my).coerceAtMost(1.0)
                val ang = atan2(my, mx)
                val xm = (0.5 + 0.45 * mx).coerceIn(0.0, 1.0)
                val env = exp(-time / tau)
                val c = cTarget * (1 - env)
                val excursion = 2.0.pow(BEND_EXCURSION_SEMITONES * cTarget * env / 12)
                for (i in 0 until n) {
                    load[i] = when {
                        mu == 0.0 -> 0.0
                        isVessel[i] -> 0.5 * rho2
                        isRing(voice) -> min(1.0, rho2 / RING_ORBIT_LOAD) * cos(ang - RING_LOAD_PHASE * modeIndex[i]).let { it * it }
                        else -> beamShape(modeIndex[i], xm).let { it * it }
                    }
                    val f = (hz * pitchFix * excursion * ratioAt(i, c) / sqrt(1 + mu * load[i]) / anchorNominal)
                        .coerceAtMost(MODE_CEILING_HZ)
                    val fade = fade(f)
                    contact[i] = contact0[i] * fade * (1 - WATER_CONTACT * water * load[i])
                    pickup[i] = pickup0[i] * fade * (if (isVessel[i]) 1.0 else 1 + shimmer * (2 * load[i] - 1))
                    bank.tune(i, f, t60Base[i] / (1 + WATER_DAMPING * mu * load[i]))
                }
                if (water > 0.0) {
                    for (e in edges.indices) {
                        bank.setKappa(edges[e], kappa * (1 + KAPPA_WATER_LIFT * water * (load[edgeI[e]] + load[edgeJ[e]])))
                    }
                }
                compliance = bank.compliance(contact)
            }
            bank.step()
            // The strike: a half-sine through the contact vector, with a short seeded burst on it.
            var fs = 0.0
            if (t < strikeFrames) fs = strikePeak * sin(PI * t / strikeFrames)
            if (t < noiseFrames) fs += STRIKE_NOISE * strikePeak * noise.next() * (1 - t.toDouble() / noiseFrames)
            if (fs != 0.0) bank.drive(contact, fs)
            // The rub: the finger at its speed, its pressure lifting off at the end of the contact.
            val p = when {
                t < contactFrames -> pressure
                t < contactFrames + releaseFrames -> pressure * (1 - (t - contactFrames).toDouble() / releaseFrames)
                else -> 0.0
            }
            if (p > 0.0) {
                val vb = DRIVER_SPEED * min(1.0, t.toDouble() / rampFrames)
                val rough = if (roughness > 0.0) 1 + roughness * 3 * grainLp.lp(grain.next(), ROUGHNESS_HZ) else 1.0
                bank.drive(contact, friction.force(vb, bank.velocityAlong(contact), p * rough.coerceAtLeast(0.0), compliance))
            }
            out[t] = bank.velocityAlong(pickup).toFloat()
        }
        if (scrape && voice != MercuryVoice.PING && rub > 0.0) addScrape(out, voice, hz, velocity, rate)
        return out
    }

    /**
     * The hard touch's scrape ([SCRAPE_DB]), on the raw output: band-passed seeded noise riding the note's own
     * envelope (the RMS of each [SCRAPE_BLOCK_SECONDS] block, read before the scrape is added and interpolated
     * between block centres), under a raised-cosine [SCRAPE_RISE_SECONDS] rise and a [SCRAPE_SECONDS] fall. It is
     * over by 0.3 s, so the held tone is untouched.
     */
    private fun addScrape(out: FloatArray, voice: MercuryVoice, hz: Double, velocity: Double, rate: Int) {
        val frames = min((0.3 * rate).toInt(), min(out.size, (6 * SCRAPE_SECONDS * rate).toInt()))
        val scale = 10.0.pow(SCRAPE_DB / 20) * velocity
        if (frames <= 0 || scale <= 0.0) return
        val block = max(1, (SCRAPE_BLOCK_SECONDS * rate).toInt())
        val blocks = (frames + block - 1) / block
        val level = DoubleArray(blocks) { k ->
            val a = k * block
            val b = min(out.size, a + block)
            var e = 0.0
            for (i in a until b) e += out[i].toDouble() * out[i]
            sqrt(e / (b - a))
        }
        val noise = Dsp.Noise(Dsp.seedFor("MERCURY", voice, hz, "scrape"))
        val low = Dsp.OnePole(rate)
        val high = Dsp.OnePole(rate)
        val high2 = Dsp.OnePole(rate)
        // Noise has RMS 1/√3; the band keeps about the share of it between the edges.
        val norm = sqrt(3.0 * rate / 2 / (SCRAPE_HIGH_HZ - SCRAPE_LOW_HZ))
        val rise = SCRAPE_RISE_SECONDS * rate
        for (i in 0 until frames) {
            val x = high2.lp(high.lp(noise.next(), SCRAPE_HIGH_HZ), SCRAPE_HIGH_HZ)
            val band = x - low.lp(x, SCRAPE_LOW_HZ)
            val pos = (i.toDouble() / block - 0.5).coerceIn(0.0, blocks - 1.0)
            val k = pos.toInt().coerceAtMost(blocks - 1)
            val note = if (k + 1 < blocks) level[k] + (level[k + 1] - level[k]) * (pos - k) else level[k]
            val attack = if (i < rise) 0.5 * (1 - cos(PI * i / rise)) else 1.0
            val env = attack * exp(-i.toDouble() / rate / SCRAPE_SECONDS)
            out[i] += (note * scale * norm * env * band).toFloat()
        }
    }

    private fun fade(f: Double) = when {
        f <= FADE_LO_HZ -> 1.0
        f >= FADE_HI_HZ -> 0.0
        else -> 0.5 * (1 + cos(PI * (f - FADE_LO_HZ) / (FADE_HI_HZ - FADE_LO_HZ)))
    }

    /**
     * The house's melodic output chain (BORE's and FORK's): the steep band limit at the oversampled
     * rate, the decimator, the DC removed twice, the shared loudness, then a raised-cosine release
     * over the last [TAIL_FADE_SHARE] of the [tail] and the 4 ms fade every render ends on.
     */
    internal fun finish(raw: FloatArray, tail: Double): FloatArray {
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
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        val n = (TAIL_FADE_SHARE * tail * RATE).toInt().coerceIn(1, out.size)
        val from = out.size - n
        for (k in 0 until n) out[from + k] *= (0.5 + 0.5 * cos(PI * k / n)).toFloat()
        Dsp.fadeTail(out)
        return out
    }
}
