package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * GYRE as round one's third pass (R1c) built it, frozen at `0ba9b05`: the reference for TOUCH 0
 * (docs/superpowers/specs/2026-10-01-gyre-coupled-string-engine-design.md, "R2"). Round two
 * rebuilds every string as a `Strings.Bow` with a bridge port; at TOUCH 0 that must sound as this
 * does, and `GyreTest` measures how closely. Never edit it to make a test pass: if the new engine
 * drifts from it, the new engine is what moves.
 *
 * It keeps `play`, `finish` and `render` and the constants and helpers they read, and nothing
 * else (no scramble, no classifier rules). Its strings are [LegacyLoop], the loop as it was, so a
 * later change to `Strings.Loop` cannot move both sides of a comparison. It has its own voice
 * type, [LegacyGyreVoice]: its `when (voice)` branches are exhaustive over FLICK and HALO, so
 * round two's DRAWN and BOURDON would otherwise break its compile and force an edit. Tests map a
 * voice across by name (`LegacyGyreVoice.valueOf(voice.name)`), for FLICK and HALO only.
 */
internal enum class LegacyGyreVoice { FLICK, HALO }

internal object LegacyGyre {

    const val TUNE_SEMITONES = 24
    const val STRINGS = 4

    /** The strings, as multiples of the note: whole numbers only (G2). */
    val RATIOS = floatArrayOf(1f, 2f, 3f, 4f)

    /** Where each string is plucked, as a fraction of its length. Shape. */
    val PICK_POSITIONS = floatArrayOf(0.12f, 0.17f, 0.21f, 0.27f)

    /** The membrane's modes over its base (the source document's, deliberately inharmonic) and their weights (>= 0, summing to 1). */
    val MEMBRANE_RATIOS = floatArrayOf(1f, 1.63f, 2.31f, 3.72f)
    val MEMBRANE_WEIGHTS = floatArrayOf(0.4f, 0.27f, 0.2f, 0.13f)

    /** BODY moves the membrane's base from a small, tight body to a large, loose one. Shape. */
    const val MEMBRANE_SMALL_HZ = 420f
    const val MEMBRANE_LARGE_HZ = 95f
    const val MEMBRANE_Q_SMALL = 3f
    const val MEMBRANE_Q_LARGE = 12f

    /** BODY's coupling at the bridge, and how much of the membrane is heard. Shape. */
    const val COUPLING_SMALL = 0.08f
    const val COUPLING_LARGE = 0.6f
    const val RADIATION_SMALL = 0.3f
    const val RADIATION_LARGE = 1.5f

    /**
     * The box the strings sit on, which BODY sizes: every sound the instrument makes leaves through
     * it. A small box (BODY 0) is thin and nasal: little below its lowest mode, its modes in the
     * middle. A large box (BODY 1) is hollow and warm: low modes that ring, a dull top. Four modes,
     * each a peak the size of [BOX_SMALL_DB] to [BOX_LARGE_DB], and two shelves for what a box of
     * that size does to the lows and the top. All shape: the owner's first listen measured the
     * membrane alone moving the octave bands 3.6 dB from BODY 0 to 1 ("Body doesn't make an impact").
     */
    val BOX_SMALL_HZ = floatArrayOf(520f, 860f, 1_300f, 1_900f)
    val BOX_LARGE_HZ = floatArrayOf(105f, 180f, 270f, 420f)
    val BOX_SMALL_DB = floatArrayOf(9f, 7f, 6f, 5f)
    val BOX_LARGE_DB = floatArrayOf(12f, 9f, 7f, 5f)
    const val BOX_Q_SMALL = 4f
    const val BOX_Q_LARGE = 7f
    const val BOX_LOW_SHELF_HZ = 250f
    const val BOX_LOW_SMALL_DB = -12f
    const val BOX_LOW_LARGE_DB = 4f
    const val BOX_HIGH_SHELF_HZ = 1_800f
    const val BOX_HIGH_SMALL_DB = 4f
    const val BOX_HIGH_LARGE_DB = -15f

    /** The box's size along BODY: `BODY^BOX_CURVE`, so the knob's first half moves it too (measured: each half at least 3.2 dB in the octave bands, the first the smaller). */
    const val BOX_CURVE = 0.7f

    internal fun boxSize(body: Float): Float = body.coerceIn(0f, 1f).pow(BOX_CURVE)

    /** The box's peaks add level; this takes it back so the raw peak stays under [RAW_PEAK_CEILING]. The output stage sets the loudness. */
    const val BOX_TRIM = 0.7f

    /**
     * How long the box rings once nothing drives it, for the tail: the slowest pole of its six
     * sections, read from the same RBJ denominators `Dsp.Biquad` builds them with. A peak's poles
     * sit at a bandwidth of `alpha / A`, so a boost rings `A = 10^(dB/40)` times longer than a
     * band-pass of its Q, and [spin]'s swell boosts it further: each peak is taken at the largest
     * gain the rotor gives it. (Copilot's review of #434: the band-pass `Q / (pi f)` read 0.147 s
     * for BODY 1's 105 Hz mode, which rings 0.29 s, and up to 0.51 s at the top of the swell.)
     */
    internal fun boxT60(body: Float, spin: Float): Float {
        val rate = RATE * Dsp.OVERSAMPLE
        val size = boxSize(body)
        val q = Dsp.lin(size, BOX_Q_SMALL, BOX_Q_LARGE).toDouble()
        val swell = 1.0 + spinDepth(spin) * SWING_BOX
        var slowest = 0.0
        for (j in BOX_SMALL_HZ.indices) {
            val w0 = 2.0 * PI * Dsp.expMap(size, BOX_SMALL_HZ[j], BOX_LARGE_HZ[j]) / rate
            val a = 10.0.pow(Dsp.lin(size, BOX_SMALL_DB[j], BOX_LARGE_DB[j]) * swell / 40.0)
            val alpha = sin(w0) / (2.0 * q)
            slowest = max(slowest, poleT60(1.0 + alpha / a, -2.0 * cos(w0), 1.0 - alpha / a, rate))
        }
        for ((hz, low) in listOf(BOX_LOW_SHELF_HZ to true, BOX_HIGH_SHELF_HZ to false)) {
            val db = if (low) Dsp.lin(size, BOX_LOW_SMALL_DB, BOX_LOW_LARGE_DB) else Dsp.lin(size, BOX_HIGH_SMALL_DB, BOX_HIGH_LARGE_DB)
            val a = 10.0.pow(db / 40.0)
            val w0 = 2.0 * PI * hz / rate
            val cw = if (low) cos(w0) else -cos(w0)
            val k = 2.0 * kotlin.math.sqrt(a) * sin(w0) / 2.0 * kotlin.math.sqrt(2.0)
            val a1 = if (low) -2.0 * ((a - 1) + (a + 1) * cos(w0)) else 2.0 * ((a - 1) - (a + 1) * cos(w0))
            slowest = max(slowest, poleT60((a + 1) + (a - 1) * cw + k, a1, (a + 1) + (a - 1) * cw - k, rate))
        }
        return slowest.toFloat()
    }

    /** The 60 dB time of the slower pole of `a0 + a1 z^-1 + a2 z^-2`, at [rate]. */
    private fun poleT60(a0: Double, a1: Double, a2: Double, rate: Int): Double {
        val p = a1 / a0
        val q = a2 / a0
        val disc = p * p - 4 * q
        val radius = if (disc < 0) kotlin.math.sqrt(q) else (kotlin.math.abs(p) + kotlin.math.sqrt(disc)) / 2
        return if (radius <= 0.0) 0.0 else -3.0 * ln(10.0) / (rate * ln(radius))
    }

    /**
     * The box loads the strings: a large box draws more from them, so they ring darker and shorter;
     * a small one leaves them bright and long. Scales on the voice's own loop brightness and t60.
     */
    const val LOAD_BRIGHT_SMALL = 1.35f
    const val LOAD_BRIGHT_LARGE = 0.6f
    const val LOAD_T60_SMALL = 1.1f
    const val LOAD_T60_LARGE = 0.75f

    /** A large box also lowers the strings' brightness ceiling ([BRIGHT_CEILING_HZ]) by this, so it is heard on the top notes too. Never raised. */
    const val LOAD_CEILING_LARGE = 0.6f

    /**
     * The wolf guard: on a note that sits on a membrane mode, the coupling is scaled down until the
     * bridge's own term at the note, `(2c/N)|H(f)|`, is at most this (and at most [WOLF_T60]'s bound). Measured: 0.1 left a 31-cent
     * wolf at D3# and BODY 1; 0.05 held every corner the Phase-0 prototype tried within 4.7 cents
     * (SPIN 0.5 included) and leaves the default coupling untouched. The built engine's worst over
     * the tuning test's corners is 3.4 cents (`GyreTest`; 3.3 in round one).
     */
    const val WOLF_GUARD = 0.05f

    /**
     * The wolf guard's second rule: the bridge may not drain the note's fundamental faster than this
     * t60, so `(2c/N)|H(f)|` is also at most `ln(1000) / (f * WOLF_T60)`. The first rule guards the
     * pitch, this one the note: measured on the merged round one, F4 sat on BODY 1's top membrane
     * mode (353 Hz) and its fundamental died 41 dB under its octave (HALO), so the house detector
     * read 700 Hz. Low notes keep [WOLF_GUARD]; it binds from about 140 Hz up. Shape.
     */
    const val WOLF_T60 = 1f

    /** No string's loop keeps more than this per period. The bridge's slack is far under `1 - FB_CEILING`. */
    const val FB_CEILING = 0.9995f

    /** The strings' loop filters stop here: past it, FLICK's A4 read as a snare (more than half its energy over 2 kHz). Measured: 8 kHz cleared it with no margin, 5 kHz with the worst at 0.36 (0.41 with round 1b's box, whose small end lifts the top). */
    const val BRIGHT_CEILING_HZ = 5_000f

    /**
     * SPIN's rotor in round one: stationary at 0, then [SPIN_SLOW_HZ] to [SPIN_FAST_HZ]; depth rises
     * fast and saturates at [SPIN_DEPTH]. The slow end was 0.15 Hz until the owner's first listen
     * ("Spin not noticable on short notes"): a rotor slower than the note never got round once.
     */
    const val SPIN_SLOW_HZ = 0.8f
    const val SPIN_FAST_HZ = 10f
    const val SPIN_DEPTH = 0.8f
    const val SPIN_DEPTH_KNEE = 0.2f

    /** How far the rotor swings each destination at full depth. Shape. No destination is the output level. */
    const val SWING_COUPLING = 0.6f
    const val SWING_DAMPING = 0.0015f
    const val SWING_MEMBRANE = 0.7f
    const val SWING_SYMPATHY = 0.8f

    /**
     * The two destinations the main sound carries, added after the owner's first listen ("Spin not
     * noticable on short notes"); the others move what is quiet. [SWING_HEARD]: the rotor faces the
     * three upper strings in turn, a quarter turn apart like [SWING_DAMPING] (the note's partials 2
     * to 4), the one it faces up to this much louder and the one behind it quieter, while the first
     * string holds the level. A string's level has no phase, so this moves the timbre and never the
     * pitch. [SWING_BOX]: each of the box's peaks swells and shrinks by this fraction of its size,
     * spread over half a turn so the lowest and highest swing opposite: the box's balance tilts with
     * the rotor, in the same direction as the bridge's own swing (the other way round they cancelled:
     * HALO's centroid swing fell to 7% of its mean, against round one's 12%; now 28%). Measured on
     * the way: sweeping the box's modes in frequency bent the pitch (11 cents, HALO, near a low mode),
     * a swept low-pass on each string moved almost nothing (FLICK's C4 has little over 2 kHz). Shape.
     */
    const val SWING_HEARD = 1.25f
    const val SWING_BOX = 1f

    /**
     * The strings are built this far below their note so the rotor can retune them either way: each
     * rotor step re-solves [bridgeTuned] for the bridge as it is now, so no swing of the coupling or
     * the membrane bends the pitch. Measured before: with the rotor at 1.2 Hz (HALO's default SPIN),
     * D3 at BODY 1 and SYMPATHY 1 read 5.2 cents sharp.
     */
    const val RETUNE_HEADROOM = 0.97f

    /** The rotor is a control signal: its destinations move every this many samples (5.5 kHz at 176.4 kHz). */
    const val ROTOR_BLOCK = 32

    const val SYMPATHETIC = 8
    const val SYMPATHY_T60_LOW = 0.6f
    const val SYMPATHY_T60_HIGH = 6f

    /** The sympathetic bank's level: `SYMPATHY_GAIN * SYMPATHY^SYMPATHY_CURVE`. Fitted in round one to the target shares (Task 5). */
    const val SYMPATHY_GAIN = 6.2f
    const val SYMPATHY_CURVE = 2f

    /** Each sympathetic string's detune in cents, one-shots only (a LOOP zeroes them, R4). Shape. */
    val SYMPATHY_DETUNE_CENTS = floatArrayOf(1f, -2f, 3f, -1f, 2f, -3f, 1f, -2f)

    /** How much of what the sympathetic strings hear is the membrane rather than the strings: BODY x SYMPATHY. */
    const val SYMPATHY_BODY_SMALL = 0.2f
    const val SYMPATHY_BODY_LARGE = 0.8f

    /**
     * HOLD is when a hand lands on the instrument. The played strings are stopped to this t60
     * (every string the same time, whatever its pitch: a per-period loss would let a low string
     * linger), over [DAMPER_RAMP_SECONDS]; the sympathetic strings get a lighter touch, which
     * SYMPATHY lengthens ([releaseT60]). The render ends when both are [END_DB] down, so no note
     * is cut off while it still rings (measured before this: FLICK at HOLD 0 and SYMPATHY 1 ended
     * only 14 dB under its attack).
     */
    const val DAMPER_T60 = 0.15f
    const val DAMPER_RAMP_SECONDS = 0.02f
    const val END_DB = 45f

    /**
     * HOLD runs from choked to open (the owner's word for it, after the second listen: "I don't
     * hear the distinction"). At 0 the hand lands [CHOKE_SECONDS] after the pluck; at the step below
     * the LOOP it lands when the note has rung out on its own ([openSeconds]), so it stops nothing.
     * Round one ran every voice from 0.25 s to a fixed ring (FLICK 3 s, HALO 6 s), but a note falls
     * 40 dB long before that: FLICK's hand landed 50 dB down at HOLD 0.5 and 108 dB down at 0.95,
     * so half the knob did nothing anyone could hear.
     */
    const val CHOKE_SECONDS = 0.04f

    /**
     * When a note has rung out (40 dB down, measured with no hand): the played strings take about
     * [OPEN_STRINGS_SECONDS] whatever the voice, the note or BODY (FLICK 0.46 to 0.73 s, HALO 0.49
     * at SYMPATHY 0), and the sympathetic strings add up to [OPEN_SYMPATHY_SECONDS] along SYMPATHY
     * squared (both voices about 2.7 s at SYMPATHY 1, HALO 2.0 at its default 0.8). Shape: the
     * strings' figure carries a fifth more than the measured 0.47 s, for the 45 dB the render keeps.
     */
    const val OPEN_STRINGS_SECONDS = 0.55f
    const val OPEN_SYMPATHY_SECONDS = 2.75f

    /**
     * How long the sympathetic strings ring out after the hand lands, at SYMPATHY 1: more sympathy
     * is more ring. From the voice's own tight release ([Shape.releaseT60]) at SYMPATHY 0 to this,
     * along SYMPATHY^[RING_OUT_CURVE], so the low end stays tight and the top rings out. The owner's
     * first listen: FLICK's cloud at SYMPATHY 1 sounded cut off when the hand stopped it in 0.3 s.
     */
    const val RING_OUT_T60 = 2.5f
    const val RING_OUT_CURVE = 2f

    /** HOLD's top step, reserved for the LOOP (R4). */
    const val LOOP_THRESHOLD = 0.99f

    const val OUTPUT_DC_HZ = 20f

    /** The raw (pre-level) peak a render may reach. Measured worst in Phase 0: 0.95; round 1b, with the box and [BOX_TRIM]: 0.985. */
    const val RAW_PEAK_CEILING = 1.25f

    /**
     * A voice: its range, how long and bright its strings ring, how hard they are plucked, how
     * long its sympathetic strings ring after the damper, and its macro defaults (HOLD's sit near
     * the open end, where the voices were approved: the hand lands about 38 dB down). Tension is
     * part of the voice (decision 2): brighter, faster strings are a tighter instrument.
     */
    internal class Shape(
        val rootMidi: Int,
        val t60: Float,
        val brightRatio: Float,
        val pickHz: Float,
        val levels: FloatArray,
        val releaseT60: Float,
        val sympathy: Float,
        val spin: Float,
        val body: Float,
        val hold: Float,
    )

    internal fun shapeOf(voice: LegacyGyreVoice): Shape = when (voice) {
        LegacyGyreVoice.FLICK -> Shape(48, 2.5f, 14f, 4_000f, floatArrayOf(1f, 0.35f, 0.25f, 0.18f), 0.3f, 0.25f, 0.05f, 0.4f, 0.9f)
        LegacyGyreVoice.HALO -> Shape(48, 5f, 8f, 1_800f, floatArrayOf(1f, 0.5f, 0.4f, 0.3f), 1.5f, 0.8f, 0.15f, 0.65f, 0.92f)
    }

    fun macrosFor(voice: LegacyGyreVoice): List<MacroSpec> {
        val s = shapeOf(voice)
        return listOf(
            MacroSpec("TUNE", 0.5f, neutral = 0.5f),
            MacroSpec("SYMPATHY", s.sympathy, neutral = 0.3f),
            MacroSpec("SPIN", s.spin, neutral = 0f),
            MacroSpec("BODY", s.body, neutral = 0.4f),
            MacroSpec("HOLD", s.hold, neutral = s.hold),
        )
    }

    fun defaults(voice: LegacyGyreVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(macros: Map<String, Float>, voice: LegacyGyreVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    /** The snapped MIDI note TUNE lands on. */
    fun midiFor(voice: LegacyGyreVoice, tune: Float): Int = shapeOf(voice).rootMidi + Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)

    internal fun membraneHz(body: Float): Float = Dsp.expMap(body, MEMBRANE_SMALL_HZ, MEMBRANE_LARGE_HZ)

    internal fun couplingFor(body: Float): Float = Dsp.lin(body, COUPLING_SMALL, COUPLING_LARGE)

    /** SPIN's rotor rate: stationary at 0. */
    internal fun rotorHz(spin: Float): Float = if (spin <= 0f) 0f else Dsp.expMap(spin, SPIN_SLOW_HZ, SPIN_FAST_HZ)

    /** SPIN's depth: 0 at 0, most of the way by [SPIN_DEPTH_KNEE], so the knob's low end is never dead (G9). */
    internal fun spinDepth(spin: Float): Float = SPIN_DEPTH * (1f - exp(-spin / SPIN_DEPTH_KNEE))

    internal fun sympathyGain(sympathy: Float): Float = SYMPATHY_GAIN * sympathy.coerceIn(0f, 1f).pow(SYMPATHY_CURVE)

    /** When a note at [sympathy] has rung out on its own: the top of HOLD's range. */
    fun openSeconds(sympathy: Float): Float =
        OPEN_STRINGS_SECONDS + OPEN_SYMPATHY_SECONDS * sympathy.coerceIn(0f, 1f).pow(2)

    /**
     * When the hand lands: from [CHOKE_SECONDS] at HOLD 0 to [openSeconds] at the step below the
     * LOOP, evenly in log time (a note's level in dB falls fastest just after the pluck, so equal
     * ratios of time land at roughly equal steps of level). HOLD's top step is the LOOP's (R4);
     * here it is the step below.
     */
    fun dampSeconds(hold: Float, sympathy: Float): Float =
        Dsp.expMap(hold.coerceAtMost(LOOP_THRESHOLD) / LOOP_THRESHOLD, CHOKE_SECONDS, openSeconds(sympathy))

    /** The sympathetic strings' ring at [sympathy], before the hand lands. */
    internal fun sympathyT60(sympathy: Float): Float = Dsp.lin(sympathy, SYMPATHY_T60_LOW, SYMPATHY_T60_HIGH)

    /**
     * The sympathetic strings' t60 once the hand has landed: the voice's tight release at SYMPATHY 0,
     * rising to [RING_OUT_T60] at SYMPATHY 1, and never longer than their own ring before the hand.
     */
    internal fun releaseT60(voice: LegacyGyreVoice, sympathy: Float): Float {
        val tight = shapeOf(voice).releaseT60
        val s = sympathy.coerceIn(0f, 1f).pow(RING_OUT_CURVE)
        return minOf(tight + (maxOf(RING_OUT_T60, tight) - tight) * s, sympathyT60(sympathy))
    }

    /** After the hand lands, until the slowest of the strings, the sympathetic strings and the box is [END_DB] down. */
    internal fun tailSeconds(voice: LegacyGyreVoice, sympathy: Float, body: Float, spin: Float): Float =
        END_DB / 60f * maxOf(DAMPER_T60, releaseT60(voice, sympathy), boxT60(body, spin))

    /** Samples at the oversampled rate: exactly what [play] renders. */
    internal fun rawFrames(voice: LegacyGyreVoice, macros: Map<String, Float>): Int {
        val m = settled(macros, voice)
        val tail = tailSeconds(voice, m.getValue("SYMPATHY"), m.getValue("BODY"), m.getValue("SPIN"))
        return ((dampSeconds(m.getValue("HOLD"), m.getValue("SYMPATHY")) + tail) * RATE * Dsp.OVERSAMPLE).toInt()
    }

    /**
     * The bridge's coupling at the note [f] with the wolf guard applied: [c] scaled down only where
     * a membrane mode sits on the note, until `(2c/N)|H(f)|` is at most [WOLF_GUARD] and [WOLF_T60]'s bound.
     */
    internal fun wolfGuarded(c: Float, f: Float, membrane: Strings.Membrane): Float {
        val h = membrane.response(f.toDouble())
        val own = 2.0 * c / STRINGS * kotlin.math.hypot(h[0], h[1])
        val most = minOf(WOLF_GUARD.toDouble(), ln(1000.0) / (f * WOLF_T60))
        return if (own > most) (c * most / own).toFloat() else c
    }

    /**
     * The frequency a string is tuned to so it sounds at [f] on the bridge: the bridge's own phase
     * at [f], `arg(1 - (2c/N) H(f))`, is part of the string's round trip, so the loop is tuned to
     * `f * 2pi / (2pi + phase)`. Measured: the defaults drift up to 8.9 cents without it, 0.7 (FLICK)
     * and 3.0 (HALO) with it.
     */
    internal fun bridgeTuned(f: Float, c: Float, membrane: Strings.Membrane): Float {
        val h = membrane.response(f.toDouble())
        val k = 2.0 * c / STRINGS
        val phase = atan2(-k * h[1], 1.0 - k * h[0])
        return (f * 2.0 * PI / (2.0 * PI + phase)).toFloat()
    }

    /** One render before the output stage: the oversampled wave and, when asked, each string's. */
    internal class Played(val raw: FloatArray, val strings: Array<FloatArray>?)

    /**
     * Test-only doors, never reachable from a macro: [coupling] overrides the bridge's (0 makes
     * the strings independent), [solo] plucks one string only, [sympathy] false silences the
     * sympathetic bank, [record] keeps each string's own wave.
     */
    internal class Probe(val coupling: Float? = null, val solo: Int? = null, val sympathy: Boolean = true, val record: Boolean = false)

    internal fun play(voice: LegacyGyreVoice, macros: Map<String, Float>, probe: Probe = Probe()): Played {
        val m = settled(macros, voice)
        val shape = shapeOf(voice)
        val rate = RATE * Dsp.OVERSAMPLE
        val sympathy = m.getValue("SYMPATHY")
        val spin = m.getValue("SPIN")
        val body = m.getValue("BODY")
        val midi = midiFor(voice, m.getValue("TUNE"))
        val f = Keys.midiHz(midi)
        val len = rawFrames(voice, m)

        val modes = MEMBRANE_RATIOS.size
        val membrane = Strings.Membrane(rate)
        val base = membraneHz(body)
        val q = Dsp.lin(body, MEMBRANE_Q_SMALL, MEMBRANE_Q_LARGE)
        membrane.tune(FloatArray(modes) { base * MEMBRANE_RATIOS[it] }, FloatArray(modes) { q }, MEMBRANE_WEIGHTS.copyOf())
        val bridge = Strings.Bridge(STRINGS, membrane)

        val depth = spinDepth(spin)
        val c0 = probe.coupling ?: wolfGuarded(couplingFor(body), f, membrane)
        val cMean = c0 * (1f - depth * SWING_COUPLING / 2f)

        val bright = shape.brightRatio * Dsp.lin(body, LOAD_BRIGHT_SMALL, LOAD_BRIGHT_LARGE)
        val t60 = shape.t60 * Dsp.lin(body, LOAD_T60_SMALL, LOAD_T60_LARGE)
        val ceiling = BRIGHT_CEILING_HZ * Dsp.lin(body, 1f, LOAD_CEILING_LARGE)
        val loops = Array(STRINGS) { k ->
            val fk = f * RATIOS[k]
            val loopHz = minOf(fk * bright, ceiling)
            val fb = 10f.pow(-3f / (t60 * fk)).coerceAtMost(FB_CEILING)
            val low = Strings.tune(bridgeTuned(fk, cMean, membrane) * RETUNE_HEADROOM, loopHz, rate)
            LegacyLoop(low.n, low.a, fb, loopHz, rate).also { it.retune(bridgeTuned(fk, cMean, membrane)) }
        }
        val bursts = Array(STRINGS) { k ->
            if (probe.solo != null && probe.solo != k) FloatArray(0)
            else {
                val fk = f * RATIOS[k]
                val n = Strings.tune(fk, minOf(fk * bright, ceiling), rate).n
                Strings.pluckExciter(n, fk, shape.pickHz, PICK_POSITIONS[k], Dsp.seedFor("GYRE", voice.name, midi, k), rate, len)
                    .also { b -> for (i in b.indices) b[i] *= shape.levels[k] }
            }
        }

        val t60s = sympathyT60(sympathy)
        val t60r = releaseT60(voice, sympathy)
        val symHz = FloatArray(SYMPATHETIC) { j -> f * (j + 1) * 2f.pow(SYMPATHY_DETUNE_CENTS[j] / 1200f) }
        fun qFor(hz: Float, t60: Float) = (PI * hz * t60 / ln(1000.0)).toFloat().coerceAtMost(20_000f)
        val bands = Array(SYMPATHETIC) { j -> Dsp.Biquad().also { it.bandpass(symHz[j], qFor(symHz[j], t60s), rate) } }
        // The hand on the played strings: each loop's gain once stopped, so it falls to DAMPER_T60
        // at its own pitch. Never above 1 (the hand only takes energy away).
        val stopped = FloatArray(STRINGS) { k ->
            val fk = f * RATIOS[k]
            val fb = 10f.pow(-3f / (t60 * fk)).coerceAtMost(FB_CEILING)
            (10f.pow(-3f / (DAMPER_T60 * fk)) / fb).coerceAtMost(1f)
        }
        val gSym = if (probe.sympathy) sympathyGain(sympathy) else 0f
        val beta = Dsp.lin(body, SYMPATHY_BODY_SMALL, SYMPATHY_BODY_LARGE)
        val gRad = Dsp.lin(body, RADIATION_SMALL, RADIATION_LARGE)
        val size = boxSize(body)
        val boxQ = Dsp.lin(size, BOX_Q_SMALL, BOX_Q_LARGE)
        val box = Array(BOX_SMALL_HZ.size + 2) { Dsp.Biquad() }
        val boxHz = FloatArray(BOX_SMALL_HZ.size) { Dsp.expMap(size, BOX_SMALL_HZ[it], BOX_LARGE_HZ[it]) }
        val boxDb = FloatArray(BOX_SMALL_HZ.size) { Dsp.lin(size, BOX_SMALL_DB[it], BOX_LARGE_DB[it]) }
        val heard = FloatArray(STRINGS) { 1f }
        for (j in boxHz.indices) box[j].peaking(boxHz[j], boxDb[j], boxQ, rate)
        box[BOX_SMALL_HZ.size].lowShelf(BOX_LOW_SHELF_HZ, Dsp.lin(size, BOX_LOW_SMALL_DB, BOX_LOW_LARGE_DB), rate)
        box[BOX_SMALL_HZ.size + 1].highShelf(BOX_HIGH_SHELF_HZ, Dsp.lin(size, BOX_HIGH_SMALL_DB, BOX_HIGH_LARGE_DB), rate)

        val out = FloatArray(len)
        val per = if (probe.record) Array(STRINGS) { FloatArray(len) } else null
        val r = FloatArray(STRINGS)
        val back = FloatArray(STRINGS)
        val w = FloatArray(modes)
        val emphasis = FloatArray(SYMPATHETIC)
        val step = 2.0 * PI * rotorHz(spin) / rate
        val dampN = (dampSeconds(m.getValue("HOLD"), sympathy) * rate).toInt()
        val rampN = (DAMPER_RAMP_SECONDS * rate).toInt().coerceAtLeast(1)
        var phase = 0.0
        var c = c0
        var released = false
        for (i in 0 until len) {
            if (i % ROTOR_BLOCK == 0) {
                val hand = if (i < dampN) 0f else ((i - dampN).toFloat() / rampN).coerceAtMost(1f)
                if (hand > 0f && !released) {
                    // The lighter touch on the sympathetic strings: their poles move, their state stays.
                    if (t60r < t60s) for (j in 0 until SYMPATHETIC) bands[j].bandpass(symHz[j], qFor(symHz[j], t60r), rate)
                    released = true
                }
                for (k in 0 until STRINGS) {
                    val damp = 1f + (stopped[k] - 1f) * hand
                    val facing = cos(phase - k * PI / 2).toFloat()
                    loops[k].gain((1f - depth * SWING_DAMPING * (1f + facing) / 2f) * damp)
                    if (k > 0) heard[k] = 1f + depth * SWING_HEARD * facing
                }
                if (depth > 0f) for (j in boxHz.indices) {
                    box[j].peaking(boxHz[j], boxDb[j] * (1f - depth * SWING_BOX * sin(phase - PI * j / (boxHz.size - 1)).toFloat()), boxQ, rate)
                }
                for (k in 0 until modes) w[k] = MEMBRANE_WEIGHTS[k] * (1f - depth * SWING_MEMBRANE * (1f + cos(phase - 2 * PI * k / modes).toFloat()) / 2f)
                membrane.weigh(w)
                c = (c0 * (1f - depth * SWING_COUPLING * (1f + sin(phase).toFloat()) / 2f)).coerceIn(0f, 1f)
                if (depth > 0f) for (k in 0 until STRINGS) loops[k].retune(bridgeTuned(f * RATIOS[k], c, membrane))
                for (j in 0 until SYMPATHETIC) {
                    val cj = max(0f, cos(phase - 2 * PI * j / SYMPATHETIC).toFloat())
                    emphasis[j] = 1f - depth * SWING_SYMPATHY * (1f - cj * cj)
                }
            }
            var sum = 0f
            for (k in 0 until STRINGS) {
                r[k] = loops[k].reflected()
                sum += r[k]
            }
            val radiated = bridge.couple(r, c, back)
            var y = 0f
            for (k in 0 until STRINGS) {
                val x = bursts[k].let { if (i < it.size) it[i] else 0f }
                val v = loops[k].inject(x + back[k])
                if (per != null) per[k][i] = v
                y += heard[k] * v
            }
            y += gRad * radiated
            if (gSym > 0f) {
                val drive = (1f - beta) * sum + beta * radiated
                var ys = 0f
                for (j in 0 until SYMPATHETIC) ys += emphasis[j] * bands[j].process(drive)
                y += gSym * ys
            }
            for (b in box) y = b.process(y)
            out[i] = BOX_TRIM * y
            phase += step
        }
        return Played(out, per)
    }

    /** The output stage, as BORE's: the band limit at the oversampled rate, the decimator, the DC removed twice, the level, the tail. */
    internal fun finish(raw: FloatArray): FloatArray {
        val buf = raw.copyOf()
        Tide.bandLimit(buf, RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(buf, RATE)
        var mean = 0.0
        for (v in out) mean += v
        val mm = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mm
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    fun render(voice: LegacyGyreVoice, macros: Map<String, Float> = emptyMap()): Snip =
        Snip(finish(play(voice, macros).raw), channels = 1, sampleRate = RATE)
}
